package ani.rss.simple;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.BgmInfo;
import ani.rss.simple.SimpleModel.*;
import ani.rss.util.other.BgmUtil;
import com.google.gson.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class SimpleTrackingService {
    private final SimpleStore store;
    private final SimpleCatalog catalog;
    private final SimpleDownloader downloader;
    private final PlaybackLibrary library;
    public SimpleTrackingService(SimpleStore store, SimpleCatalog catalog, SimpleDownloader downloader) {
        this.store=store; this.catalog=catalog; this.downloader=downloader;
        this.library=new PlaybackLibrary(store.directory().resolve("simple-playback"));
        this.progressSettings=store.state().getSettings();
    }
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean forceCatalog;
    private static final long STALL_TIMEOUT = 10 * 60_000L;
    private static final long RETRY_DELAY = 30 * 60_000L;
    private record TransferSnapshot(JsonArray tasks,long checkedAt) {}
    private volatile TransferSnapshot transfers;
    private volatile Settings progressSettings;

    public State state() {
        State snapshot=store.snapshot();
        TransferSnapshot latest=transfers;
        if(latest!=null && latest.checkedAt()>snapshot.getDownloadCheckedAt()) {
            for(Show show:snapshot.getShows()) for(Release release:show.getReleases()) if("downloading".equals(release.getStatus())) {
                JsonObject task=findTask(latest.tasks(),release.getId());if(task!=null) transfer(release,task,latest.checkedAt());
            }
            snapshot.setDownloadCheckedAt(latest.checkedAt());
        }
        for(Show show:snapshot.getShows()) if(show.isEnabled())
            show.setQueuedReleaseIds(ReleasePolicy.plan(show,snapshot.getSettings()).stream().map(Release::getId).toList());
        return snapshot;
    }

    /** Transfer-only updates are cheap and do not refresh catalogs or change the download queue. */
    @Scheduled(initialDelay = 25000, fixedDelay = 10000, scheduler="simpleProgressScheduler")
    public void pollProgress() {
        State state=store.snapshot();Settings settings=progressSettings;
        if(settings.getDownloadHost().isBlank() || settings.getCredential().isBlank()
                || state.getShows().stream().flatMap(s->s.getReleases().stream()).noneMatch(r->"downloading".equals(r.getStatus()))) return;
        try(SimpleDownloader progress=downloader.fork()) {
            progress.connect(settings);
            transfers=new TransferSnapshot(progress.tasks().deepCopy(),System.currentTimeMillis());
        } catch(Exception e) {log.debug("下载进度暂未更新: {}",e.getClass().getSimpleName());}
    }

    private static void transfer(Release release,JsonObject task,long now) {
        double progress=task.get("progress").getAsDouble()*100;
        if(progress>release.getProgress()) release.setLastProgressAt(now);
        release.setProgress(progress);
        release.setDownloadState(task.has("state")?task.get("state").getAsString():"");
        release.setDownloadSpeed(Math.max(0,number(task,"dlspeed",0)));
        release.setDownloadedBytes(Math.max(0,number(task,"completed",number(task,"downloaded",0))));
        release.setTotalBytes(Math.max(0,number(task,"total_size",number(task,"size",0))));
        long eta=number(task,"eta",-1);release.setEtaSeconds(eta>=8640000?-1:eta);
        release.setSeeds((int)number(task,"num_seeds",0));
    }
    private static long number(JsonObject task,String key,long fallback) {return task.has(key)&&!task.get(key).isJsonNull()?task.get(key).getAsLong():fallback;}

    public synchronized void settings(Settings incoming) throws IOException {
        Objects.requireNonNull(incoming.getTrustedGroups(), "请提供认可的字幕组名单");
        Settings old = store.state().getSettings();
        Path resources = absolute(incoming.getLibraryRoot());
        Path playback = absolute(incoming.getPlaybackRoot());
        incoming.setDownloaderRoot(DownloadPaths.normalizeRemote(incoming.getDownloaderRoot()));
        if (resources.startsWith(playback) || playback.startsWith(resources))
            throw new IllegalArgumentException("资源库与正式播放目录必须分开，不能互相包含");
        boolean downloaded = store.state().getShows().stream().flatMap(s -> s.getReleases().stream())
                .anyMatch(r -> r.getDownloadPath() != null);
        if (downloaded && (!resources.equals(absolute(old.getLibraryRoot())) || !playback.equals(absolute(old.getPlaybackRoot()))
                || !Objects.equals(incoming.getDownloaderRoot(), old.getDownloaderRoot())))
            throw new IllegalArgumentException("已有资源时不能直接修改根目录，请先迁移数据和下载器路径");
        URI uri = URI.create(incoming.getDownloadHost());
        if (!Set.of("http", "https").contains(String.valueOf(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("请填写有效的 qBittorrent WebUI 地址");
        if (incoming.getSimultaneousDownloads() < 1 || incoming.getSimultaneousDownloads() > 10)
            throw new IllegalArgumentException("同时下载数量应为 1 到 10");
        incoming.setTrustedGroups(cleanGroups(incoming.getTrustedGroups()));
        incoming.setInitialGroup(Objects.toString(incoming.getInitialGroup(), "").trim());
        incoming.setLibraryRoot(resources.toString());
        incoming.setPlaybackRoot(playback.toString());
        incoming.setCredentialSaved(false);
        if (incoming.getCredential() == null || incoming.getCredential().isBlank()) incoming.setCredential(old.getCredential());
        if (incoming.getCredential().isBlank()) throw new IllegalArgumentException("请填写下载器密码或 API 密钥");
        if (incoming.getUsername() == null) incoming.setUsername("");
        probe(resources);
        probe(playback);
        store.state().setSettings(incoming);
        progressSettings=incoming;
        store.save();
    }

    public synchronized String testDownloader() throws Exception {
        downloader.connect(store.state().getSettings());
        return "下载器连接成功";
    }

    public Show follow(String id) throws IOException {
        if (id == null || !id.matches("[1-9][0-9]{0,9}")) throw new IllegalArgumentException("无效的番剧 ID");
        BgmInfo info = BgmUtil.getBgmInfo(id, true);
        Show show = new Show();
        show.setId(id);
        show.setTitle(BgmUtil.getFinalName(info));
        show.setSeason(info.getSeason() == null || info.getSeason() < 1 ? 1 : info.getSeason());
        show.setTotalEpisodes(info.getEps() == null ? 0 : info.getEps());
        if (info.getImages() != null) show.setImage(Objects.toString(info.getImages().getSmall(), ""));
        // A stable unique folder means two remakes or similarly named shows never collide.
        show.setFolder(PlaybackLibrary.safeName(show.getTitle()) + " [bgm-" + id + "]");
        synchronized (this) {
            Optional<Show> existing = store.state().getShows().stream().filter(s -> s.getId().equals(id)).findFirst();
            if (existing.isPresent()) return cloneShow(existing.get());
            store.state().getShows().add(show);
            store.save();
        }
        refresh();
        return cloneShow(show);
    }

    public synchronized void preferences(PreferenceRequest request) throws IOException {
        Show show = show(request.getShowId());
        show.setPreferredGroups(cleanGroups(Objects.requireNonNull(request.getGroups())));
        show.setFastest(request.isFastest());
        show.setEnabled(request.isEnabled());
        show.setLastCatalogAt(0);
        store.save();
    }

    public synchronized void choose(VersionRequest request) throws Exception {
        Show show = show(request.getShowId());
        Release release = release(show, request.getReleaseId());
        if (!ReleasePolicy.allowed(release, show, store.state().getSettings()))
            throw new IllegalArgumentException("仅可选用认可字幕组且明确标注中文字幕的版本");
        if (!"ready".equals(release.getStatus()))
            throw new IllegalArgumentException("请先下载此版本，完成后再切换");
        install(show, release, true);
        show.getPinnedEpisodes().add(ReleasePolicy.episodeKey(release.getEpisode()));
        store.save();
    }

    public synchronized void requestDownload(VersionRequest request) throws Exception {
        Show show = show(request.getShowId());
        Release release = release(show, request.getReleaseId());
        Settings settings = store.state().getSettings();
        if (!ReleasePolicy.allowed(release, show, settings))
            throw new IllegalArgumentException("先认可该字幕组，并确认资源有中文字幕");
        if("invalid".equals(release.getStatus()) && release.getVideoPath()!=null) {
            downloader.connect(settings);downloader.checkFinished(release);store.save();refresh();return;
        }
        if (Set.of("ready", "downloading", "submitting").contains(release.getStatus())) return;
        downloader.connect(settings);
        JsonArray tasks = downloader.tasks();
        if (activeCount(tasks) >= settings.getSimultaneousDownloads())
            throw new IllegalArgumentException("下载队列已满，请稍后重试");
        submit(show, release);
        store.save();
    }

    public synchronized void automatic(AutoRequest request) throws IOException {
        Show show = show(request.getShowId());
        library.clearPin(store.state().getSettings(), show, request.getEpisode());
        show.getPinnedEpisodes().remove(request.getEpisode());
        store.save();
        refresh();
    }

    public void refresh() {
        forceCatalog = true;
        Thread.startVirtualThread(this::tick);
    }

    @Scheduled(initialDelay = 15000, fixedDelay = 60000)
    public void tick() {
        if (!running.compareAndSet(false, true)) return;
        try {
            synchronized (this) { poll(); }
        } catch (Exception e) {
            log.warn("简化追番检查失败: {}", e.getClass().getSimpleName());
        } finally { running.set(false); }
    }

    private void poll() throws Exception {
        State state = store.state();
        if (state.getShows().isEmpty()) return;
        Settings settings = state.getSettings();
        Set<String> recoveryFailed=new HashSet<>();
        for (Show show : state.getShows()) {
            try {
                String before=GsonStatic.toJson(show);
                try {catalog.episodeMetadata(show);} catch(Exception e) {log.debug("本季集数目录暂未刷新: {}",show.getId());}
                EpisodeNumbers.apply(show);
                if(!before.equals(GsonStatic.toJson(show))) store.save(); // Persist numbering before playback migration.
                library.reconcile(settings, show);
            }
            catch (Exception e) { show.setMessage("播放目录恢复待处理：" + brief(e)); recoveryFailed.add(show.getId()); }
        }
        boolean force = forceCatalog;
        forceCatalog = false;
        for (Show show : state.getShows()) {
            if(recoveryFailed.contains(show.getId())) continue;
            if (show.isEnabled() && (force || System.currentTimeMillis() - show.getLastCatalogAt() >= 10 * 60_000L)) {
                try {
                    List<Release> fresh = catalog.releases(show);
                    Set<String> known = new HashSet<>();
                    show.getReleases().forEach(r -> known.add(r.getId()));
                    fresh.stream().filter(r -> known.add(r.getId())).forEach(show.getReleases()::add);
                    show.setLastCatalogAt(System.currentTimeMillis());
                    show.setMessage(fresh.isEmpty() ? "暂无可识别的单集资源；将继续检查历史资源和后续更新" : "资源已更新");
                } catch (Exception e) { show.setMessage("资源检索失败，将自动重试：" + brief(e)); }
            }
        }
        if (settings.getDownloadHost().isBlank() || settings.getCredential().isBlank()) {
            state.getShows().forEach(s -> s.setMessage("请先完成一次基础配置，连接下载器和保存目录"));
            store.save();
            return;
        }
        try { downloader.connect(settings); }
        catch (Exception e) {
            state.getShows().forEach(s -> s.setMessage("下载器连接失败，请检查基础配置"));
            store.save();
            return;
        }
        JsonArray tasks = downloader.tasks();
        long now=System.currentTimeMillis();
        state.setDownloadCheckedAt(now);
        for (Show show : state.getShows()) {
            if(recoveryFailed.contains(show.getId())) continue;
            for (Release release : show.getReleases()) {
                if (release.getDownloadPath() == null || "invalid".equals(release.getStatus())) continue;
                JsonObject task = findTask(tasks, release.getId());
                if (task != null && !"ready".equals(release.getStatus())) {
                    transfer(release,task,now);
                    double progress=task.get("progress").getAsDouble()*100;
                    if (progress>release.getProgress()) release.setLastProgressAt(now);
                    if (release.getLastProgressAt()==0) {
                        long activity=task.has("last_activity")?task.get("last_activity").getAsLong()*1000:now;
                        release.setLastProgressAt(activity>0?Math.min(activity,now):now);
                    }
                    release.setProgress(progress);
                    String taskState=task.get("state").getAsString().toLowerCase(Locale.ROOT);
                    boolean stopped=taskState.startsWith("stopped") || taskState.startsWith("paused");
                    if (stopped && progress<100) {
                        if (!"waiting".equals(release.getStatus())) {release.setStatus("paused");release.setError("任务已暂停");}
                    } else {
                        release.setStatus("downloading");
                        if (show.isEnabled() && progress<100 && !taskState.contains("checking") && !taskState.contains("moving")
                                && !taskState.contains("queued") && now-release.getLastProgressAt()>=STALL_TIMEOUT) {
                            try {
                                downloader.pause(release);
                                task.addProperty("state","stoppedDL");
                                release.setStatus("waiting");release.setRetryAt(now+RETRY_DELAY);
                                release.setError("10 分钟没有下载进展，已让出名额；30 分钟后重试，期间尝试其他中文字幕版本");
                            } catch(Exception e) {release.setError("暂停无进展任务失败："+brief(e));}
                        }
                    }
                    if (release.getProgress() >= 100 && !task.get("state").getAsString().toLowerCase(Locale.ROOT).contains("checking")) {
                        try { downloader.checkFinished(release); }
                        catch (Exception e) { release.setError(brief(e)); }
                    }
                } else if (task == null && Set.of("downloading", "submitting").contains(release.getStatus())) {
                    release.setStatus("error");
                    release.setError("下载任务不存在，将重新提交");
                }
            }
            if (!show.isEnabled()) { show.setMessage("已暂停自动追番，资源和播放文件均保留"); continue; }
            List<String> groups = ReleasePolicy.groups(show, settings);
            if (groups.isEmpty() && !settings.isAllowOtherChinese()) { show.setMessage("等待认可字幕组：在基础配置或本番偏好中添加名单"); continue; }
            Set<String> episodes = new LinkedHashSet<>();
            show.getReleases().forEach(r -> episodes.add(ReleasePolicy.episodeKey(r.getEpisode())));
            for (String episode : episodes) {
                if (show.getPinnedEpisodes().contains(episode)) continue;
                Release best = ReleasePolicy.bestReady(show, settings, episode).orElse(null);
                Release current = show.getReleases().stream().filter(r -> Objects.equals(r.getId(), show.getSelected().get(episode))).findFirst().orElse(null);
                if (best != null && ReleasePolicy.better(best, current, show, settings)) {
                    try { install(show, best, false); store.save(); }
                    catch (Exception e) { best.setError("切换失败，原版本保留：" + brief(e)); }
                }
            }
        }
        // Poll/install every show first. Missing episodes across ALL shows precede upgrades;
        // rotate by last submission so one back-catalog cannot starve newly followed shows.
        int active=activeCount(tasks);
        List<Show> eligibleShows=state.getShows().stream().filter(Show::isEnabled).filter(s->!recoveryFailed.contains(s.getId())).toList();
        Set<String> attempted=new HashSet<>();
        while(active<settings.getSimultaneousDownloads()) {
            record Candidate(Show show,Release release) {}
            Candidate next=eligibleShows.stream().flatMap(s->ReleasePolicy.plan(s,settings).stream().map(r->new Candidate(s,r)))
                    .filter(c->!attempted.contains(c.release().getId()))
                    .min(Comparator.comparingInt((Candidate c)->c.show().getSelected().containsKey(ReleasePolicy.episodeKey(c.release().getEpisode()))?1:0)
                            .thenComparingLong(c->c.show().getLastQueuedAt())
                            .thenComparingDouble(c->c.release().getEpisode())).orElse(null);
            if(next==null) break;
            attempted.add(next.release().getId());
            try {submit(next.show(),next.release());active++;}
            catch(Exception e) {next.release().setStatus("error");next.release().setRetryAt(now+RETRY_DELAY);next.release().setError(brief(e));}
            store.save();
        }
        for(Show show:eligibleShows) updateMessage(show,settings);
        store.save();
    }

    private void submit(Show show, Release release) throws Exception {
        Path root = PlaybackLibrary.inside(Path.of(store.state().getSettings().getLibraryRoot()), show.getId() + "/" + release.getId());
        release.setDownloadPath(root.toString());
        release.setStatus("submitting");
        store.save(); // Persist the intent before talking to the download client.
        downloader.add(release, root);
        release.setStatus("downloading");
        release.setError("");
        release.setLastProgressAt(System.currentTimeMillis());
        release.setRetryAt(0);
        show.setLastQueuedAt(System.currentTimeMillis());
    }

    private void updateMessage(Show show,Settings settings) {
        if(show.getMessage().startsWith("资源检索失败")) return;
        long active=show.getReleases().stream().filter(r->Set.of("downloading","submitting").contains(r.getStatus())).count();
        long waiting=show.getReleases().stream().filter(r->"waiting".equals(r.getStatus())).count();
        long failed=show.getReleases().stream().filter(r->"error".equals(r.getStatus())).count();
        boolean allowed=show.getReleases().stream().anyMatch(r->ReleasePolicy.allowed(r,show,settings));
        String prefix="已发布 "+show.getSelected().size()+" 集 · ";
        long uncertain=show.getReleases().stream().filter(r->!r.getNumberingIssue().isBlank()).count();
        if(active>0) show.setMessage(prefix+active+" 个版本正在下载");
        else if(!ReleasePolicy.plan(show,settings).isEmpty()) show.setMessage(prefix+"排队中，等待下载名额");
        else if(waiting>0) show.setMessage(prefix+"资源暂无下载进展，已让出名额并定时重试");
        else if(failed>0) show.setMessage(prefix+"部分版本下载失败，定时重试；详情可查看原因");
        else if(!allowed) show.setMessage(show.getReleases().isEmpty()?"暂无可识别的单集资源，等待发布或资源收录":"暂无明确标注中文字幕的可用版本，等待更新");
        else if(uncertain>0) show.setMessage(prefix+"部分资源集数待核对，已保留原件");
        else show.setMessage(prefix+"等待后续更新");
    }

    private void install(Show show, Release release, boolean manual) throws IOException {
        try{downloader.validateMedia(release);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("媒体校验被中断",e);}
        release.setPublishedFiles(library.publish(store.state().getSettings(), show, release, manual));
        show.getSelected().put(ReleasePolicy.episodeKey(release.getEpisode()), release.getId());
        release.setError("");
    }

    public Map<String, Object> detail(String id) throws IOException {
        State snapshot = state();
        Show show = snapshot.getShows().stream().filter(s -> Objects.equals(s.getId(), id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("番剧不存在"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("show", cloneShow(show));
        result.put("playbackPath", library.showDirectory(snapshot.getSettings(), show).toString());
        result.put("groups", ReleasePolicy.groups(show, snapshot.getSettings()));
        return result;
    }

    private Show show(String id) {
        return store.state().getShows().stream().filter(s -> Objects.equals(s.getId(), id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("番剧不存在"));
    }

    private Release release(Show show, String id) {
        return show.getReleases().stream().filter(r -> Objects.equals(r.getId(), id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("资源不存在"));
    }

    private static Show cloneShow(Show show) { return GsonStatic.fromJson(GsonStatic.toJson(show), Show.class); }
    private static String brief(Exception e) {
        String message = Objects.toString(e.getMessage(), e.getClass().getSimpleName()).replaceAll("[\\r\\n]", " ");
        return message.substring(0, Math.min(200, message.length()));
    }
    private static Path absolute(String value) {
        if (value == null || value.isBlank() || !Path.of(value).isAbsolute()) throw new IllegalArgumentException("保存目录必须为本机或容器内的绝对路径");
        return Path.of(value).toAbsolutePath().normalize();
    }
    private static void probe(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path test = Files.createTempFile(directory, ".ani-rss-write-", ".tmp");
        Files.delete(test);
    }
    private static List<String> cleanGroups(List<String> values) {
        return values.stream().map(String::trim).filter(s -> !s.isBlank()).distinct().limit(100).toList();
    }
    private static JsonObject findTask(JsonArray tasks, String hash) {
        for (JsonElement element : tasks) if (element.getAsJsonObject().get("hash").getAsString().equalsIgnoreCase(hash)) return element.getAsJsonObject();
        return null;
    }
    static int activeCount(JsonArray tasks) {
        int count = 0;
        for (JsonElement element : tasks) {
            JsonObject task=element.getAsJsonObject();
            String status=task.has("state")?task.get("state").getAsString().toLowerCase(Locale.ROOT):"";
            if (task.get("progress").getAsDouble() < 1 && !status.startsWith("paused") && !status.startsWith("stopped")) count++;
        }
        return count;
    }
}
