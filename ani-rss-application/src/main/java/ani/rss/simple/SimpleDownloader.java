package ani.rss.simple;

import ani.rss.commons.GsonStatic;
import ani.rss.simple.SimpleModel.*;
import com.google.gson.*;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Separate category keeps the upstream rename/delete workers away from the resource library. */
@Component
public class SimpleDownloader implements AutoCloseable {
    private final MediaInspector inspector;
    public SimpleDownloader(){this(new MediaInspector());}
    SimpleDownloader(MediaInspector inspector){this.inspector=inspector;}
    public SimpleDownloader fork(){return new SimpleDownloader(inspector);}
    @Override public void close(){if(client!=null)client.close();}
    public void validateMedia(Release release) throws IOException,InterruptedException {inspector.inspect(release);}
    public static final String CATEGORY = "ani-rss-simple";
    private HttpClient client;
    private Settings settings;
    private long spaceCheckedAt;
    private long availableBytes=-1;
    private String spaceHost="",defaultSavePath="";
    static final long MIN_FREE_SPACE=5L*1024*1024*1024;

    public void connect(Settings settings) throws IOException, InterruptedException {
        this.settings = settings;
        close();
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
        if (!settings.getCredential().startsWith("qbt_")) {
            String result = request("/api/v2/auth/login", Map.of("username", settings.getUsername(), "password", settings.getCredential()));
            if (!"Ok.".equals(result.trim())) throw new IOException("下载器登录失败，请检查地址、用户名和密码");
        }
        request("/api/v2/app/version", null);
        // Existing category returns HTTP 409; it can safely be reused.
        send("/api/v2/torrents/createCategory", Map.of("category", CATEGORY), true);
    }

    public JsonArray tasks() throws IOException, InterruptedException {
        return JsonParser.parseString(request("/api/v2/torrents/info?category=" + CATEGORY, null)).getAsJsonArray();
    }
    public static String hash(Release release){return release.getTorrentHash().isBlank()?release.getId():release.getTorrentHash();}
    public enum ExistingState { MISSING, IMPORTED, OWNED, BLOCKED }
    public ExistingState reuseExisting(Release release,Path path) throws IOException,InterruptedException {
        JsonArray existing=JsonParser.parseString(request("/api/v2/torrents/info?hashes="+hash(release),null)).getAsJsonArray();
        if(existing.isEmpty())return ExistingState.MISSING;
        JsonObject task=existing.get(0).getAsJsonObject();
        if(CATEGORY.equals(task.get("category").getAsString())&&DownloadPaths.equivalent(task.get("save_path").getAsString(),DownloadPaths.destination(settings,path)))return ExistingState.OWNED;
        ensureDownloadSpace(path);
        return importComplete(release,path,task)?ExistingState.IMPORTED:ExistingState.BLOCKED;
    }
    private boolean importComplete(Release release,Path path,JsonObject task) throws IOException,InterruptedException {
        if(!task.has("progress")||task.get("progress").getAsDouble()<1)return false;
        try {
            boolean copied=ExistingResourceImporter.copy(settings,release,path,task,files(hash(release)),inspector);
            if(copied)release.setImportedFrom(task.get("save_path").getAsString());
            return copied;
        } catch(IOException|IllegalArgumentException e){throw new DownloadConflictException("旧任务已完成，但暂不能安全复用："+e.getMessage(),e);}
    }
    public JsonArray files(String hash) throws IOException,InterruptedException {return JsonParser.parseString(request("/api/v2/torrents/files?hash="+hash,null)).getAsJsonArray();}
    public void filePriorities(DownloadTask task) throws IOException,InterruptedException {
        if(task.getFiles().isEmpty())return;
        JsonArray own=JsonParser.parseString(request("/api/v2/torrents/info?hashes="+task.getHash(),null)).getAsJsonArray();
        if(own.size()!=1 || !CATEGORY.equals(own.get(0).getAsJsonObject().get("category").getAsString()) || !DownloadPaths.equivalent(own.get(0).getAsJsonObject().get("save_path").getAsString(),DownloadPaths.destination(settings,Path.of(task.getPath()))))throw new IOException("共享任务路径或分类不属于此资源库，拒绝修改文件优先级");
        String all=task.getFiles().stream().map(f->Integer.toString(f.getIndex())).collect(java.util.stream.Collectors.joining("|"));
        request("/api/v2/torrents/filePrio",Map.of("hash",task.getHash(),"id",all,"priority","0"));
        if(!task.getRequestedFiles().isEmpty())request("/api/v2/torrents/filePrio",Map.of("hash",task.getHash(),"id",task.getRequestedFiles().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("|")),"priority","1"));
    }

    public void add(Release release, Path path) throws IOException, InterruptedException {
        String remotePath = DownloadPaths.destination(settings, path);
        JsonArray existing = JsonParser.parseString(request("/api/v2/torrents/info?hashes=" + hash(release), null)).getAsJsonArray();
        for (JsonElement element : existing) {
            JsonObject task = element.getAsJsonObject();
            if (!CATEGORY.equals(task.get("category").getAsString()) ||
                    !DownloadPaths.equivalent(task.get("save_path").getAsString(), remotePath)) {
                if(task.has("progress")&&task.get("progress").getAsDouble()>=1)throw new DownloadConflictException("旧任务已完成，等待后台校验并复制复用；原任务保持不变");
                throw new DownloadConflictException("已有相同种子的旧任务尚未完成或正在校验；未移动、接管或重复提交，将定期检查并尝试其他版本");
            }
            String state=task.has("state")?task.get("state").getAsString().toLowerCase(Locale.ROOT):"";
            if(state.equals("error")||state.equals("missingfiles"))throw new IOException("下载器报告文件或磁盘错误，请修复存储问题后再重试");
            if(task.has("progress")&&task.get("progress").getAsDouble()<1)ensureDownloadSpace(path);
            if (state.startsWith("paused") || state.startsWith("stopped")) control(hash(release),"start","resume");
            return; // An earlier request may have succeeded before its response was lost.
        }
        ensureDownloadSpace(path);
        Files.createDirectories(path);
        String source = release.getTorrentUrl();
        if (source == null || source.isBlank()) source = release.getMagnet();
        request("/api/v2/torrents/add", Map.of(
                "urls", source, "savepath", remotePath, "category", CATEGORY,
                "autoTMM", "false", "paused", "false", "stopped", "false", "contentLayout", "Original"));
    }

    public void checkFinished(Release release) throws IOException, InterruptedException {
        JsonArray files = files(hash(release));
        List<Path> videos = new ArrayList<>();
        List<String> subtitles = new ArrayList<>();
        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            if(release.getFileIndex()>=0 && (!file.has("index") || file.get("index").getAsInt()!=release.getFileIndex()&&!release.getSubtitleIndices().contains(file.get("index").getAsInt())))continue;
            Path path = PlaybackLibrary.inside(Path.of(release.getDownloadPath()), file.get("name").getAsString());
            String extension = PlaybackLibrary.extension(path);
            if (!PlaybackLibrary.VIDEO.contains(extension) && !PlaybackLibrary.SUBTITLE.contains(extension)) continue;
            if (file.get("progress").getAsDouble() < 1 || !Files.isRegularFile(path)
                    || Files.size(path) != file.get("size").getAsLong())
                throw new IOException("下载器已完成，但资源文件尚未在本机完整可见；请检查共享目录映射");
            if (PlaybackLibrary.VIDEO.contains(extension)) videos.add(path);
            else subtitles.add(path.toString());
        }
        if (videos.size() != 1) throw new IOException("资源包含 " + videos.size() + " 个视频，需要确认文件与集数的对应关系");
        release.setVideoPath(videos.getFirst().toString());
        release.setSubtitles(subtitles);
        try {validateMedia(release);}
        catch(IOException e){release.setStatus("invalid");release.setError(e.getMessage());throw e;}
        release.setStatus("ready");
        release.setProgress(100);
        release.setFailures(0);
        release.setError("");
    }

    /** Only suspend tasks whose category AND save path belong to this resource. Keep partial data. */
    public void pause(Release release) throws IOException, InterruptedException {
        String remote=DownloadPaths.destination(settings,Path.of(release.getDownloadPath()));
        JsonArray tasks=JsonParser.parseString(request("/api/v2/torrents/info?hashes="+hash(release),null)).getAsJsonArray();
        for(JsonElement element:tasks) {
            JsonObject task=element.getAsJsonObject();
            if (!CATEGORY.equals(task.get("category").getAsString()) || !DownloadPaths.equivalent(task.get("save_path").getAsString(),remote))
                throw new IOException("任务不属于此资源库，不能暂停");
            String status=task.has("state")?task.get("state").getAsString().toLowerCase(Locale.ROOT):"";
            if(status.startsWith("stopped")||status.startsWith("paused")||task.has("force_start")&&task.get("force_start").getAsBoolean())
                throw new IOException("任务已由用户暂停或强制运行，保持用户选择");
            control(hash(release),"stop","pause");
        }
    }

    private void control(String hash,String modern,String legacy) throws IOException, InterruptedException {
        try { request("/api/v2/torrents/"+modern,Map.of("hashes",hash)); }
        catch(IOException e) {
            if (!e.getMessage().contains("HTTP 404")) throw e;
            request("/api/v2/torrents/"+legacy,Map.of("hashes",hash));
        }
    }

    /** The Windows smart mount can report aggregate space, so use the downloader's own volume.
     * Only apply this guard to resources under its default save directory, not unrelated mounts. */
    private void ensureDownloadSpace(Path path) throws IOException,InterruptedException {
        long now=System.currentTimeMillis();String host=settings.getDownloadHost();
        if(!host.equals(spaceHost)||now-spaceCheckedAt>30_000) {
            spaceCheckedAt=0;availableBytes=-1;
            JsonObject prefs=JsonParser.parseString(request("/api/v2/app/preferences",null)).getAsJsonObject();
            defaultSavePath=prefs.has("save_path")?DownloadPaths.normalizeRemote(prefs.get("save_path").getAsString()):"";
            if(!defaultSavePath.isBlank()) {
                JsonObject data=JsonParser.parseString(request("/api/v2/sync/maindata?rid=0",null)).getAsJsonObject();
                JsonObject server=data.has("server_state")?data.getAsJsonObject("server_state"):null;
                if(server!=null&&server.has("free_space_on_disk"))availableBytes=server.get("free_space_on_disk").getAsLong();
            }
            spaceHost=host;spaceCheckedAt=now;
        }
        if(defaultSavePath.isBlank())return;
        String remote=DownloadPaths.normalizeRemote(DownloadPaths.destination(settings,path));
        if(!remote.equals(defaultSavePath)&&!remote.startsWith(defaultSavePath.endsWith("/")?defaultSavePath:defaultSavePath+"/"))return;
        if(availableBytes>=0&&availableBytes<MIN_FREE_SPACE)
            throw new IOException(String.format(Locale.ROOT,"下载盘可用空间仅 %.1f GiB，低于 5 GiB 保留空间；已停止新增下载及导入，请先处理 NAS 存储",availableBytes/(1024d*1024*1024)));
    }

    private String request(String path, Map<String, String> form) throws IOException, InterruptedException {
        return send(path, form, false);
    }

    private String send(String path, Map<String, String> form, boolean categoryCreation) throws IOException, InterruptedException {
        String host = settings.getDownloadHost().replaceAll("/+$", "");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(host + path)).timeout(Duration.ofSeconds(30));
        if (settings.getCredential().startsWith("qbt_")) builder.header("Authorization", "Bearer " + settings.getCredential());
        if (form == null) builder.GET();
        else {
            List<String> fields = new ArrayList<>();
            form.forEach((key, value) -> fields.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
            builder.header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(String.join("&", fields)));
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400 && !(categoryCreation && response.statusCode() == 409))
            throw new IOException("下载器请求失败（HTTP " + response.statusCode() + "）");
        if (path.equals("/api/v2/torrents/add") && !response.body().trim().equals("Ok."))
            throw new IOException("下载器未接受资源任务");
        return response.body();
    }
}
