package ani.rss.simple;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.BgmInfo;
import ani.rss.simple.SimpleModel.*;
import ani.rss.util.other.BgmUtil;
import com.google.gson.*;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class SimpleTrackingService {
    private final SimpleStore store;
    private final SimpleCatalog catalog;
    private final SimpleDownloader downloader;
    private final PlaybackLibrary library = new PlaybackLibrary();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean forceCatalog;

    public State state() { return store.snapshot(); }

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
        incoming.setLibraryRoot(resources.toString());
        incoming.setPlaybackRoot(playback.toString());
        incoming.setCredentialSaved(false);
        if (incoming.getCredential() == null || incoming.getCredential().isBlank()) incoming.setCredential(old.getCredential());
        if (incoming.getCredential().isBlank()) throw new IllegalArgumentException("请填写下载器密码或 API 密钥");
        if (incoming.getUsername() == null) incoming.setUsername("");
        probe(resources);
        probe(playback);
        store.state().setSettings(incoming);
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
        if (!ReleasePolicy.allowed(release, ReleasePolicy.groups(show, store.state().getSettings())))
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
        if (!ReleasePolicy.allowed(release, ReleasePolicy.groups(show, settings)))
            throw new IllegalArgumentException("先认可该字幕组，并确认资源有中文字幕");
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
        for (Show show : state.getShows()) {
            try { library.reconcile(settings, show); }
            catch (Exception e) { show.setMessage("播放目录恢复待处理：" + brief(e)); store.save(); return; }
        }
        boolean force = forceCatalog;
        forceCatalog = false;
        for (Show show : state.getShows()) {
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
        int active = activeCount(tasks);
        for (Show show : state.getShows()) {
            for (Release release : show.getReleases()) {
                if (release.getDownloadPath() == null) continue;
                JsonObject task = findTask(tasks, release.getId());
                if (task != null && !"ready".equals(release.getStatus())) {
                    release.setStatus("downloading");
                    release.setProgress(task.get("progress").getAsDouble() * 100);
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
            if (groups.isEmpty()) { show.setMessage("等待认可字幕组：在基础配置或本番偏好中添加名单"); continue; }
            Set<String> episodes = new LinkedHashSet<>();
            show.getReleases().forEach(r -> episodes.add(ReleasePolicy.episodeKey(r.getEpisode())));
            for (String episode : episodes) {
                if (show.getPinnedEpisodes().contains(episode)) continue;
                Release best = ReleasePolicy.bestReady(show, settings, episode).orElse(null);
                Release current = show.getReleases().stream().filter(r -> Objects.equals(r.getId(), show.getSelected().get(episode))).findFirst().orElse(null);
                if (best != null && (current == null || ReleasePolicy.rank(best, groups) < ReleasePolicy.rank(current, groups))) {
                    try { install(show, best, false); store.save(); }
                    catch (Exception e) { best.setError("切换失败，原版本保留：" + brief(e)); }
                }
            }
            for (Release release : ReleasePolicy.plan(show, settings)) {
                if (active >= settings.getSimultaneousDownloads()) break;
                try { submit(show, release); active++; }
                catch (Exception e) { release.setStatus("error"); release.setError(brief(e)); }
                store.save();
            }
            long eligible = show.getReleases().stream().filter(r -> ReleasePolicy.allowed(r, groups)).count();
            if (eligible == 0 && !show.getMessage().startsWith("资源检索失败"))
                show.setMessage("等待认可字幕组的中文字幕资源；其他版本不会自动下载");
            else if (!show.getMessage().startsWith("资源检索失败"))
                show.setMessage("已就绪 " + show.getSelected().size() + " 集 · 自动补齐与追更中");
        }
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
    }

    private void install(Show show, Release release, boolean manual) throws IOException {
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
    private static int activeCount(JsonArray tasks) {
        int count = 0;
        for (JsonElement element : tasks) if (element.getAsJsonObject().get("progress").getAsDouble() < 1) count++;
        return count;
    }
}
