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
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private HistoryCatalog history;
    public SimpleTrackingService(SimpleStore store, SimpleCatalog catalog, SimpleDownloader downloader) {
        this.store=store; this.catalog=catalog; this.downloader=downloader;
        this.library=new PlaybackLibrary(store.directory().resolve("simple-playback"));
        this.progressSettings=store.state().getSettings();
    }
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean forceCatalog;
    private static final long RETRY_DELAY = 30 * 60_000L;
    private record TransferSnapshot(JsonArray tasks,Map<String,JsonArray> files,long checkedAt) {}
    private volatile TransferSnapshot transfers;
    private volatile Settings progressSettings;
    private final TransferHealth transferHealth=new TransferHealth();
    private final IdleRetryQueue idleRetries=new IdleRetryQueue();
    private record ReuseResult(SimpleDownloader.ExistingState state,Release copy,String error) {}
    private final Map<String,java.util.concurrent.CompletableFuture<ReuseResult>> reuseJobs=new LinkedHashMap<>();

    public State state() {
        State snapshot=store.snapshot();
        TransferSnapshot latest=transfers;
        if(latest!=null && latest.checkedAt()>snapshot.getDownloadCheckedAt()) {
            for(Show show:snapshot.getShows()) for(Release release:show.getReleases()) if("downloading".equals(release.getStatus())) {
                JsonObject task=findTask(latest.tasks(),SimpleDownloader.hash(release));if(task!=null) transfer(release,task,latest.checkedAt(),latest.files().get(SimpleDownloader.hash(release)));
            }
            snapshot.setDownloadCheckedAt(latest.checkedAt());
        }
        for(Show show:snapshot.getShows()) {
            show.setMissingEpisodes(show.getEpisodeIds().keySet().stream().filter(ep->!show.getSelected().containsKey(ep)).toList());
            if(show.isEnabled())show.setQueuedReleaseIds(ReleasePolicy.plan(show,snapshot.getSettings()).stream().filter(r->"available".equals(r.getStatus())).map(Release::getId).toList());
            show.setRetryReleaseIds(DownloadQueue.retries(show,snapshot.getSettings()));
            show.setConflictReleaseIds(DownloadQueue.conflicts(show,snapshot.getSettings()));
        }
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
            JsonArray tasks=progress.tasks();Map<String,JsonArray> files=new HashMap<>();
            for(Show show:state.getShows())for(Release r:show.getReleases())if(r.getFileIndex()>=0&&"downloading".equals(r.getStatus())&&!files.containsKey(SimpleDownloader.hash(r)))
                files.put(SimpleDownloader.hash(r),progress.files(SimpleDownloader.hash(r)));
            transfers=new TransferSnapshot(tasks.deepCopy(),files,System.currentTimeMillis());
        } catch(Exception e) {log.debug("下载进度暂未更新: {}",e.getClass().getSimpleName());}
    }

    private static void transfer(Release release,JsonObject task,long now) {
        double progress=task.get("progress").getAsDouble()*100;
        if(progress>release.getProgress()) release.setLastProgressAt(now);
        release.setProgress(progress);
        release.setDownloadState(task.has("state")?task.get("state").getAsString():"");
        release.setDownloadSpeed(Math.max(0,number(task,"dlspeed",0)));
        release.setTaskDownloadSpeed(release.getDownloadSpeed());
        release.setDownloadedBytes(Math.max(0,number(task,"completed",number(task,"downloaded",0))));
        release.setTotalBytes(Math.max(0,number(task,"total_size",number(task,"size",0))));
        long eta=number(task,"eta",-1);release.setEtaSeconds(eta>=8640000?-1:eta);
        release.setSeeds((int)number(task,"num_seeds",0));
        release.setAvailability(task.has("availability")&&!task.get("availability").isJsonNull()?task.get("availability").getAsDouble():-1);
    }
    private static void transfer(Release release,JsonObject task,long now,JsonArray files) {
        transfer(release,task,now);
        if(release.getFileIndex()<0||files==null)return;
        for(JsonElement element:files){JsonObject f=element.getAsJsonObject();if(f.has("index")&&f.get("index").getAsInt()==release.getFileIndex()){
            release.setProgress(f.get("progress").getAsDouble()*100);release.setTotalBytes(f.get("size").getAsLong());release.setDownloadedBytes((long)(release.getTotalBytes()*release.getProgress()/100));
            release.setDownloadSpeed(0);release.setEtaSeconds(-1);break;
        }}
        for(JsonElement element:files){JsonObject f=element.getAsJsonObject();if(f.has("index")&&release.getSubtitleIndices().contains(f.get("index").getAsInt()))release.setProgress(Math.min(release.getProgress(),f.get("progress").getAsDouble()*100));}
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
        synchronized(this){Optional<Show> existing=store.state().getShows().stream().filter(s->s.getId().equals(id)).findFirst();if(existing.isPresent())return cloneShow(existing.get());}
        BgmInfo info;
        try{info=history==null?BgmUtil.getBgmInfo(id,true):history.info(id);}catch(Exception e){throw new IOException("番剧详情暂时不可用，请稍后重试",e);}
        Show show = new Show();
        show.setId(id);
        show.setTitle(BgmUtil.getFinalName(info));
        show.setSeason(info.getSeason() == null || info.getSeason() < 1 ? 1 : info.getSeason());
        show.setTotalEpisodes(info.getEps() == null ? 0 : info.getEps());
        show.setHistorical(info.getDate()!=null&&System.currentTimeMillis()-info.getDate().getTime()>365L*24*60*60_000);
        if (info.getImages() != null) show.setImage(Objects.toString(info.getImages().getSmall(), ""));
        // A stable unique folder means two remakes or similarly named shows never collide.
        show.setFolder(PlaybackLibrary.safeName(show.getTitle()) + " [bgm-" + id + "]");
        synchronized (this) {
            Optional<Show> existing = store.state().getShows().stream().filter(s -> s.getId().equals(id)).findFirst();
            if (existing.isPresent()) return cloneShow(existing.get());
            store.state().getShows().add(show);
            store.save();
        }
        wake();
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
        wake();
    }
    public void wake() {
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
            boolean due=force||show.getLastCatalogAt()==0||(show.getNextCatalogAt()>0?System.currentTimeMillis()>=show.getNextCatalogAt():System.currentTimeMillis()-show.getLastCatalogAt()>=10*60_000L);
            if (show.isEnabled() && due) {
                try {
                    List<Release> fresh = catalog.releases(show);
                    Map<String,Release> known=new HashMap<>();show.getReleases().forEach(r->known.put(r.getId(),r));boolean changed=false;
                    for(Release r:fresh){Release old=known.get(r.getId());if(old==null){show.getReleases().add(r);known.put(r.getId(),r);changed=true;}
                        else if(!Objects.equals(old.getTitle(),r.getTitle())||!Objects.equals(old.getGroup(),r.getGroup())||old.isChinese()!=r.isChinese()){
                            if(old.getDownloadPath()!=null && (old.isBatch()!=r.isBatch() || !old.isBatch()&&(old.getEpisode()!=r.getEpisode()||!r.getNumberingIssue().isBlank()))) {
                                show.setCatalogIssue("已下载资源的发布说明改变了集数或季度，保留原文件等待核对");continue;
                            }
                            old.setTitle(r.getTitle());old.setGroup(r.getGroup());old.setChinese(r.isChinese());old.setTorrentUrl(r.getTorrentUrl());changed=true;
                            if(old.getDownloadPath()==null){old.setSourceEpisode(r.getSourceEpisode());old.setEpisode(r.getEpisode());old.setEpisodeId(r.getEpisodeId());old.setNumberingIssue(r.getNumberingIssue());}
                        }}
                    show.setUnchangedCatalogs(changed?0:show.getUnchangedCatalogs()+1);
                    show.setLastCatalogAt(System.currentTimeMillis());
                    boolean complete=!show.getEpisodeIds().isEmpty()&&show.getSelected().keySet().containsAll(show.getEpisodeIds().keySet());
                    long interval=show.isHistorical()?(complete&&ReleasePolicy.plan(show,settings).isEmpty()?30L*24*60*60_000:Math.min(24*60*60_000L,10*60_000L*(1L<<Math.min(8,show.getUnchangedCatalogs())))):10*60_000L;
                    show.setNextCatalogAt(System.currentTimeMillis()+interval);
                    show.setMessage(fresh.isEmpty() ? "暂无可识别的单集资源；将继续检查历史资源和后续更新" : "资源已更新");
                } catch (Exception e) {show.setNextCatalogAt(System.currentTimeMillis()+30*60_000L);show.setMessage("资源检索失败，将自动重试：" + brief(e)); }
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
        recheckConflicts(state);
        Map<String,JsonArray> sharedFiles=syncBatches(state,tasks);
        long now=System.currentTimeMillis();
        state.setDownloadCheckedAt(now);
        for (Show show : state.getShows()) {
            if(recoveryFailed.contains(show.getId())) continue;
            for (Release release : show.getReleases()) {
                if (release.isBatch() || release.getDownloadPath() == null || "invalid".equals(release.getStatus()) || release.getFileIndex()>=0&&!release.isRequested()&&!"ready".equals(release.getStatus())) continue;
                JsonObject task = findTask(tasks, SimpleDownloader.hash(release));
                if (task != null && !"ready".equals(release.getStatus())) {
                    transfer(release,task,now,sharedFiles.get(SimpleDownloader.hash(release)));
                    double progress=release.getProgress();
                    if (progress>release.getProgress()) release.setLastProgressAt(now);
                    if (release.getLastProgressAt()==0) {
                        long activity=task.has("last_activity")?task.get("last_activity").getAsLong()*1000:now;
                        release.setLastProgressAt(activity>0?Math.min(activity,now):now);
                    }
                    release.setProgress(progress);
                    String taskState=task.get("state").getAsString().toLowerCase(Locale.ROOT);
                    if(taskState.equals("error")||taskState.equals("missingfiles")) {
                        transferHealth.forget(SimpleDownloader.hash(release));
                        if(!"error".equals(release.getStatus()))release.setRetryAt(now+RETRY_DELAY);
                        release.setStatus("error");release.setError("下载器报告文件或磁盘错误，请检查 NAS 存储和下载器日志；这不是缺种等待");
                        continue;
                    }
                    boolean stopped=taskState.startsWith("stopped") || taskState.startsWith("paused");
                    if (stopped && progress<100) {
                        transferHealth.forget(SimpleDownloader.hash(release));
                        if (!"waiting".equals(release.getStatus())) {release.setStatus("paused");release.setError("任务已暂停");}
                    } else {
                        release.setStatus("downloading");
                        String unhealthy=release.getFileIndex()<0?transferHealth.assess(SimpleDownloader.hash(release),task,now,show.getSelected().containsKey(ReleasePolicy.episodeKey(release.getEpisode()))):null;
                        boolean rotate=release.getFileIndex()<0&&idleShouldRotate(state,release,task,tasks,now);
                        if (release.getFileIndex()<0 && show.isEnabled() && (rotate || unhealthy!=null
                                && DownloadQueue.canYield(state,SimpleDownloader.hash(release),tasks,now))) {
                            try {
                                downloader.pause(release);
                                transferHealth.forget(SimpleDownloader.hash(release));
                                idleRetries.forget(SimpleDownloader.hash(release));
                                task.addProperty("state","stoppedDL");
                                release.setStatus("waiting");release.setFailures(release.getFailures()+1);release.setRetryAt(RetryBackoff.nextAt(now,release.getFailures()));
                                markAttempt(state,SimpleDownloader.hash(release),now);
                                release.setError((rotate?"空闲试跑连续 3 分钟新增不足 64 KiB，轮换其他任务":unhealthy)+"；下次检查 "+new java.text.SimpleDateFormat("MM-dd HH:mm").format(new Date(release.getRetryAt())));
                            } catch(Exception e) {release.setError("暂停无进展任务失败："+brief(e));}
                        }
                    }
                    if (release.getProgress() >= 100 && !task.get("state").getAsString().toLowerCase(Locale.ROOT).contains("checking")) {
                        try { downloader.checkFinished(release); }
                        catch (Exception e) { release.setError(brief(e)); }
                    }
                } else if (task == null && Set.of("downloading", "submitting").contains(release.getStatus())) {
                    if(now-release.getLastProgressAt()<2*60_000L)continue;
                    release.setStatus("error");
                    release.setFailures(release.getFailures()+1);release.setRetryAt(RetryBackoff.nextAt(now,release.getFailures()));
                    release.setError("提交后仍未出现下载任务，可能是种子文件获取失败；已延后重试");
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
                    .filter(c->!IdleRetryQueue.retryCandidate(c.release()))
                    .filter(c->!attempted.contains(c.release().getId()))
                    .min(Comparator.comparingInt((Candidate c)->c.show().getSelected().containsKey(ReleasePolicy.episodeKey(c.release().getEpisode()))?1:0)
                            .thenComparingLong(c->c.show().getLastQueuedAt())
                            .thenComparingDouble(c->c.release().getEpisode())).orElse(null);
            if(next==null) break;
            attempted.add(next.release().getId());
            try {submit(next.show(),next.release());if(!"ready".equals(next.release().getStatus()))active++;}
            catch(Exception e) {next.release().setStatus(e instanceof DownloadConflictException?"conflict":"error");next.release().setRetryAt(e instanceof DownloadConflictException?0:now+RETRY_DELAY);next.release().setError(brief(e));}
            store.save();
        }
        Set<String> activeHashes=IdleRetryQueue.activeHashes(tasks);
        // Include tasks submitted in this tick, which are not yet present in the qBittorrent snapshot.
        for(Show show:state.getShows())for(Release r:show.getReleases())if(Set.of("downloading","submitting").contains(r.getStatus()))activeHashes.add(SimpleDownloader.hash(r).toLowerCase(Locale.ROOT));
        Set<String> probes=IdleRetryQueue.probeHashes(state);
        if(probes.containsAll(activeHashes)) {
            Set<String> tried=new HashSet<>();
            int limit=Math.min(IdleRetryQueue.LIMIT,settings.getSimultaneousDownloads());
            while(activeHashes.size()<limit && tried.size()<limit) {
                var options=IdleRetryQueue.candidates(state,recoveryFailed,activeHashes,tried,now);
                if(options.isEmpty())break;
                var candidate=options.getFirst();Release r=candidate.release();String hash=SimpleDownloader.hash(r).toLowerCase(Locale.ROOT);
                tried.add(hash);r.setLastProbeAt(now);
                try {submit(candidate.show(),r,true);if(!"ready".equals(r.getStatus()))activeHashes.add(hash);}
                catch(Exception e){r.setIdleProbe(false);r.setStatus(e instanceof DownloadConflictException?"conflict":"error");r.setRetryAt(e instanceof DownloadConflictException?0:now+RETRY_DELAY);r.setError(brief(e));}
                store.save();
            }
        }
        for(Show show:eligibleShows) updateMessage(show,settings);
        store.save();
    }

    private void recheckConflicts(State state) throws IOException {
        long now=System.currentTimeMillis();
        for(Show show:state.getShows())for(Release r:show.getReleases()) {
            if("error".equals(r.getStatus())&&r.getError().contains("路径或分类不属于此资源库")){r.setStatus("conflict");r.setRetryAt(0);}
            var pending=reuseJobs.get(r.getId());
            if(pending!=null) {
                if(!pending.isDone())continue;
                ReuseResult result=pending.join();reuseJobs.remove(r.getId());
                if(result.error()!=null){r.setStatus("conflict");r.setRetryAt(now+30*60_000L);r.setError(result.error());}
                else switch(result.state()) {
                    case IMPORTED -> {
                        Release copied=result.copy();r.setDownloadPath(copied.getDownloadPath());r.setVideoPath(copied.getVideoPath());r.setSubtitles(copied.getSubtitles());
                        r.setMediaSignature(copied.getMediaSignature());r.setSubtitleEvidence(copied.getSubtitleEvidence());r.setImportedFrom(copied.getImportedFrom());
                        r.setStatus("ready");r.setProgress(100);r.setRetryAt(0);r.setError("");
                    }
                    case OWNED -> {r.setStatus("downloading");r.setRetryAt(0);r.setError("");}
                    case MISSING -> {r.setStatus("available");r.setRetryAt(0);r.setError("");}
                    case BLOCKED -> {r.setRetryAt(now+5*60_000L);r.setError("旧任务尚未完成，原任务保持不变；5 分钟后核对，可先使用其他版本");}
                }
                store.save();
            }
            if(!"conflict".equals(r.getStatus())||!DownloadQueue.needed(show,r,state.getSettings())||r.getRetryAt()>now)continue;
            if(!reuseJobs.isEmpty())continue; // One large copy at a time, independently of scheduling and telemetry.
            Release copy=GsonStatic.fromJson(GsonStatic.toJson(r),Release.class);
            Settings privateSettings=GsonStatic.fromJson(GsonStatic.toJson(state.getSettings()),Settings.class);
            Path destination=PlaybackLibrary.resourceDirectory(privateSettings,show,copy);
            var future=new java.util.concurrent.CompletableFuture<ReuseResult>();reuseJobs.put(r.getId(),future);
            r.setError("后台校验并复制旧资源中，不占下载名额");store.save();
            Thread.startVirtualThread(()->{
                try(SimpleDownloader worker=downloader.fork()) {worker.connect(privateSettings);future.complete(new ReuseResult(worker.reuseExisting(copy,destination),copy,null));}
                catch(Exception e){future.complete(new ReuseResult(null,copy,brief(e)));}
                wake();
            });
        }
    }

    /** One file-list request and one priority union per shared torrent, across all subjects. */
    private Map<String,JsonArray> syncBatches(State state,JsonArray torrents) throws Exception {
        Map<String,JsonArray> responses=new HashMap<>();Set<String> checked=new HashSet<>();
        for(Show show:state.getShows())for(Release pack:new ArrayList<>(show.getReleases())) {
            if(!pack.isBatch()||pack.getDownloadPath()==null)continue;
            DownloadTask task=state.getDownloads().get(SimpleDownloader.hash(pack));if(task==null)continue;
            JsonObject torrent=findTask(torrents,task.getHash());if(torrent==null){if(!"error".equals(pack.getStatus())){pack.setStatus("error");pack.setRetryAt(System.currentTimeMillis()+RETRY_DELAY);}continue;}
            boolean active=task.getFiles().isEmpty()||state.getShows().stream().flatMap(s->s.getReleases().stream()).anyMatch(r->task.getHash().equals(r.getTorrentHash())&&Set.of("downloading","submitting").contains(r.getStatus()));
            if(!active && !"downloading".equals(pack.getStatus()))continue;
            JsonArray response=responses.get(task.getHash());
            try {
                if(checked.add(task.getHash()))checkSharedStall(state,task,pack,torrent,torrents);
                if(task.getRetryAt()>System.currentTimeMillis())continue;
                if(response==null){response=downloader.files(task.getHash());responses.put(task.getHash(),response);task.setFiles(BatchMapper.files(response));}
                if(task.getFiles().isEmpty())continue; // Magnet metadata has not arrived yet.
                List<Release> candidates=BatchMapper.map(show,pack,task);
                Set<Integer> previous=new LinkedHashSet<>(task.getRequestedFiles());
                for(Release candidate:candidates) {
                    Release r=show.getReleases().stream().filter(v->v.getId().equals(candidate.getId())).findFirst().orElse(null);
                    if(r==null){r=candidate;show.getReleases().add(r);}
                    Release current=show.getReleases().stream().filter(v->v.getId().equals(show.getSelected().get(ReleasePolicy.episodeKey(candidate.getEpisode())))).findFirst().orElse(null);
                    boolean busy=show.getReleases().stream().anyMatch(v->!v.isBatch()&&v.getTorrentHash().isBlank()&&v.getEpisode()==candidate.getEpisode()&&Set.of("downloading","submitting").contains(v.getStatus()));
                    if(show.isEnabled()&&!busy&&!show.getPinnedEpisodes().contains(ReleasePolicy.episodeKey(r.getEpisode()))&&ReleasePolicy.allowed(r,show,state.getSettings())&&ReleasePolicy.better(r,current,show,state.getSettings())&& !"invalid".equals(r.getStatus())) {
                        r.setRequested(true);r.setIdleProbe(IdleRetryQueue.probeHashes(state).contains(task.getHash().toLowerCase(Locale.ROOT)));if(!"ready".equals(r.getStatus()))r.setStatus("downloading");task.getRequestedFiles().add(r.getFileIndex());task.getRequestedFiles().addAll(r.getSubtitleIndices());
                    }
                }
                if(!previous.equals(task.getRequestedFiles())||"downloading".equals(pack.getStatus())) {
                    task.setLastProgressAt(System.currentTimeMillis());task.setProgress(-1);task.setRetryAt(0);downloader.filePriorities(task);
                    transferHealth.forget(task.getHash());
                }
                pack.setStatus("batch");pack.setError(candidates.isEmpty()?"合集文件尚不能明确对应本季正篇，已保留任务等待核对":"");

            }catch(Exception e){pack.setError("合集检查失败，将保留已有版本："+brief(e));}
        }
        return responses;
    }

    private void checkSharedStall(State state,DownloadTask shared,Release pack,JsonObject torrent,JsonArray torrents) throws Exception {
        long now=System.currentTimeMillis();double progress=torrent.get("progress").getAsDouble()*100;
        String status=torrent.get("state").getAsString().toLowerCase(Locale.ROOT);
        if(status.equals("error")||status.equals("missingfiles")) {
            if(shared.getRetryAt()<=now)shared.setRetryAt(now+RETRY_DELAY);
            for(Show owner:state.getShows())for(Release r:owner.getReleases())if((r.isBatch()&&shared.getHash().equals(SimpleDownloader.hash(r))||shared.getHash().equals(r.getTorrentHash())&&r.isRequested())&&!"ready".equals(r.getStatus())) {
                r.setStatus("error");r.setRetryAt(shared.getRetryAt());r.setError("下载器报告共享合集文件或磁盘错误，请先修复 NAS 存储");
            }
            transferHealth.forget(shared.getHash());return;
        }
        if(shared.getLastProgressAt()==0||progress!=shared.getProgress()||status.contains("checking")||status.contains("moving"))shared.setLastProgressAt(now);
        shared.setProgress(progress);
        boolean upgrade=state.getShows().stream().noneMatch(s->s.getReleases().stream().anyMatch(r->shared.getHash().equals(r.getTorrentHash())&&r.isRequested()&&!s.getSelected().containsKey(ReleasePolicy.episodeKey(r.getEpisode()))));
        String unhealthy=transferHealth.assess(shared.getHash(),torrent,now,upgrade);
        boolean rotate=idleShouldRotate(state,pack,torrent,torrents,now);
        if(!rotate&&(unhealthy==null||!DownloadQueue.canYield(state,shared.getHash(),torrents,now)))return;
        downloader.pause(pack);shared.setFailures(shared.getFailures()+1);shared.setRetryAt(RetryBackoff.nextAt(now,shared.getFailures()));
        transferHealth.forget(shared.getHash());
        idleRetries.forget(shared.getHash());
        for(Show owner:state.getShows())for(Release r:owner.getReleases())if((r.isBatch()&&shared.getHash().equals(SimpleDownloader.hash(r))||shared.getHash().equals(r.getTorrentHash())&&r.isRequested())&&!"ready".equals(r.getStatus())) {
            r.setStatus("waiting");r.setLastProbeAt(now);r.setRetryAt(shared.getRetryAt());r.setError((rotate?"空闲试跑连续 3 分钟新增不足 64 KiB，轮换其他任务":unhealthy)+"；共享任务延后重试");
        }
        torrent.addProperty("state","stoppedDL");
    }

    private void submit(Show show, Release release) throws Exception {
        submit(show,release,false);
    }
    private void submit(Show show, Release release,boolean idleProbe) throws Exception {
        State state=store.state();Path root = PlaybackLibrary.resourceDirectory(state.getSettings(),show,release);
        if(release.isBatch()||release.getFileIndex()>=0){
            String pathName=root.toString();DownloadTask task=state.getDownloads().computeIfAbsent(SimpleDownloader.hash(release),hash->{DownloadTask t=new DownloadTask();t.setHash(hash);t.setPath(pathName);return t;});
            root=Path.of(task.getPath());release.setRequested(true);task.setLastProgressAt(System.currentTimeMillis());task.setProgress(-1);task.setRetryAt(0);
            if(release.getFileIndex()>=0){task.getRequestedFiles().add(release.getFileIndex());task.getRequestedFiles().addAll(release.getSubtitleIndices());}
        }
        release.setDownloadPath(root.toString());
        release.setIdleProbe(idleProbe);
        markAttempt(state,SimpleDownloader.hash(release),System.currentTimeMillis());
        for(Show owner:state.getShows())for(Release r:owner.getReleases())if(SimpleDownloader.hash(release).equalsIgnoreCase(SimpleDownloader.hash(r)))r.setIdleProbe(idleProbe);
        release.setStatus("submitting");
        store.save(); // Persist the intent before talking to the download client.
        try {downloader.add(release, root);}
        catch(DownloadConflictException e){release.setStatus("conflict");release.setRetryAt(0);release.setError(brief(e));store.save();throw e;}
        if("ready".equals(release.getStatus())){store.save();return;}
        transferHealth.forget(SimpleDownloader.hash(release));
        idleRetries.forget(SimpleDownloader.hash(release));
        if(release.getFileIndex()>=0)downloader.filePriorities(state.getDownloads().get(SimpleDownloader.hash(release)));
        release.setStatus("downloading");
        release.setError("");
        release.setLastProgressAt(System.currentTimeMillis());
        release.setRetryAt(0);
        show.setLastQueuedAt(System.currentTimeMillis());
    }

    private boolean idleShouldRotate(State state,Release release,JsonObject task,JsonArray tasks,long now) {
        String hash=SimpleDownloader.hash(release);
        boolean probe=IdleRetryQueue.probeHashes(state).contains(hash.toLowerCase(Locale.ROOT));
        // A stalled normal queue must not prevent idle retries forever merely by occupying slots.
        boolean allIdle=true;
        Set<String> active=IdleRetryQueue.activeHashes(tasks);
        for(JsonElement element:tasks){JsonObject t=element.getAsJsonObject();
            if(active.contains(t.get("hash").getAsString().toLowerCase(Locale.ROOT))
                    &&t.has("dlspeed")&&t.get("dlspeed").getAsLong()>0){allIdle=false;break;}}
        if(!probe&&!allIdle){idleRetries.forget(hash);return false;}
        boolean stalled=idleRetries.stalled(hash,task,now);
        return stalled&&!IdleRetryQueue.candidates(state,Set.of(),IdleRetryQueue.activeHashes(tasks),Set.of(),now).isEmpty();
    }
    private static void markAttempt(State state,String hash,long now) {
        for(Show owner:state.getShows())for(Release r:owner.getReleases())if(hash.equalsIgnoreCase(SimpleDownloader.hash(r)))r.setLastProbeAt(now);
    }

    private void updateMessage(Show show,Settings settings) {
        if(show.getMessage().startsWith("资源检索失败")) return;
        long active=show.getReleases().stream().filter(r->Set.of("downloading","submitting").contains(r.getStatus())).count();
        List<String> retries=DownloadQueue.retries(show,settings);
        long waiting=show.getReleases().stream().filter(r->retries.contains(r.getId())&&"waiting".equals(r.getStatus())).count();
        long failed=show.getReleases().stream().filter(r->retries.contains(r.getId())&&"error".equals(r.getStatus())).count();
        long conflicts=DownloadQueue.conflicts(show,settings).size();
        boolean allowed=show.getReleases().stream().anyMatch(r->ReleasePolicy.allowed(r,show,settings));
        String prefix="已发布 "+show.getSelected().size()+" 集 · ";
        if(!show.getCatalogIssue().isBlank()){show.setMessage(prefix+show.getCatalogIssue());return;}
        long uncertain=show.getReleases().stream().filter(r->!r.getNumberingIssue().isBlank()).count();
        if(active>0) show.setMessage(prefix+active+" 个版本正在下载");
        else if(ReleasePolicy.plan(show,settings).stream().anyMatch(r->"available".equals(r.getStatus()))) show.setMessage(prefix+"排队中，等待下载名额");
        else if(waiting>0) show.setMessage(prefix+"资源暂无下载进展，已让出名额并定时重试");
        else if(failed>0) show.setMessage(prefix+"部分版本下载失败，定时重试；详情可查看原因");
        else if(conflicts>0)show.setMessage(prefix+conflicts+" 个旧任务待核对，原任务未改动");
        else if(!allowed) show.setMessage(show.getReleases().isEmpty()?"暂无可识别的单集资源，等待发布或资源收录":"暂无明确标注中文字幕的可用版本，等待更新");
        else if(uncertain>0) show.setMessage(prefix+"部分资源集数待核对，已保留原件");
        else show.setMessage(prefix+(!show.getEpisodeIds().isEmpty()&&show.getSelected().keySet().containsAll(show.getEpisodeIds().keySet())?"本季正篇已补齐":"等待后续更新"));
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
            if (task.get("progress").getAsDouble() < 1 && !status.startsWith("paused") && !status.startsWith("stopped")&&!status.equals("error")&&!status.equals("missingfiles")) count++;
        }
        return count;
    }
}
