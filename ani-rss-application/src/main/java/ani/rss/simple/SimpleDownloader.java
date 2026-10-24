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

    public void add(Release release, Path path) throws IOException, InterruptedException {
        String remotePath = DownloadPaths.destination(settings, path);
        JsonArray existing = JsonParser.parseString(request("/api/v2/torrents/info?hashes=" + release.getId(), null)).getAsJsonArray();
        for (JsonElement element : existing) {
            JsonObject task = element.getAsJsonObject();
            if (!CATEGORY.equals(task.get("category").getAsString()) ||
                    !DownloadPaths.equivalent(task.get("save_path").getAsString(), remotePath))
                throw new IOException("下载器中已有相同资源，但路径或分类不属于此资源库；请先处理现有任务");
            String state=task.has("state")?task.get("state").getAsString().toLowerCase(Locale.ROOT):"";
            if (state.startsWith("paused") || state.startsWith("stopped")) control(release.getId(),"start","resume");
            return; // An earlier request may have succeeded before its response was lost.
        }
        Files.createDirectories(path);
        String source = release.getTorrentUrl();
        if (source == null || source.isBlank()) source = release.getMagnet();
        request("/api/v2/torrents/add", Map.of(
                "urls", source, "savepath", remotePath, "category", CATEGORY,
                "autoTMM", "false", "paused", "false", "stopped", "false", "contentLayout", "Original"));
    }

    public void checkFinished(Release release) throws IOException, InterruptedException {
        JsonArray files = JsonParser.parseString(request("/api/v2/torrents/files?hash=" + release.getId(), null)).getAsJsonArray();
        List<Path> videos = new ArrayList<>();
        List<String> subtitles = new ArrayList<>();
        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
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
        release.setError("");
    }

    /** Only suspend tasks whose category AND save path belong to this resource. Keep partial data. */
    public void pause(Release release) throws IOException, InterruptedException {
        String remote=DownloadPaths.destination(settings,Path.of(release.getDownloadPath()));
        JsonArray tasks=JsonParser.parseString(request("/api/v2/torrents/info?hashes="+release.getId(),null)).getAsJsonArray();
        for(JsonElement element:tasks) {
            JsonObject task=element.getAsJsonObject();
            if (!CATEGORY.equals(task.get("category").getAsString()) || !DownloadPaths.equivalent(task.get("save_path").getAsString(),remote))
                throw new IOException("任务不属于此资源库，不能暂停");
            control(release.getId(),"stop","pause");
        }
    }

    private void control(String hash,String modern,String legacy) throws IOException, InterruptedException {
        try { request("/api/v2/torrents/"+modern,Map.of("hashes",hash)); }
        catch(IOException e) {
            if (!e.getMessage().contains("HTTP 404")) throw e;
            request("/api/v2/torrents/"+legacy,Map.of("hashes",hash));
        }
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
