package ani.rss.simple;

import ani.rss.simple.SimpleModel.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SimpleTrackingServiceTest {
    @TempDir Path root;

    static class Catalog extends SimpleCatalog {
        List<Release> candidates = new ArrayList<>();
        @Override public List<Release> releases(Show show) {return candidates;}
    }
    static class Downloader extends SimpleDownloader {
        @Override public SimpleDownloader fork(){return this;}
        @Override public void validateMedia(Release release) {}
        final Map<String, JsonObject> tasks = new LinkedHashMap<>();
        final Map<String, Path> paths = new HashMap<>();
        @Override public void connect(Settings settings) {}
        @Override public JsonArray tasks() {JsonArray result=new JsonArray();tasks.values().forEach(result::add);return result;}
        @Override public void add(Release release, Path path) throws java.io.IOException {
            Files.createDirectories(path);paths.put(release.getId(),path);
            JsonObject task=new JsonObject();task.addProperty("hash",release.getId());task.addProperty("progress",0);task.addProperty("state","downloading");tasks.put(release.getId(),task);
        }
        void finish(String id) throws Exception {Files.writeString(paths.get(id).resolve("original.mkv"),id);tasks.get(id).addProperty("progress",1);tasks.get(id).addProperty("state","stalledUP");}
        @Override public void checkFinished(Release release) {
            release.setVideoPath(paths.get(release.getId()).resolve("original.mkv").toString());release.setStatus("ready");release.setProgress(100);
        }
        @Override public void pause(Release release) {tasks.get(release.getId()).addProperty("state","stoppedDL");}
    }
    // Tests explicitly advance scheduler ticks; background imports must not outlive their temp directory.
    static class DirectService extends SimpleTrackingService {
        DirectService(SimpleStore store,SimpleCatalog catalog,SimpleDownloader downloader){super(store,catalog,downloader);}
        @Override public void wake() {}
    }
    private Release release(String id,String group,int ep) {Release r=new Release();r.setId(id);r.setGroup(group);r.setEpisode(ep);r.setChinese(true);r.setPublishedAt(1);r.setTitle("["+group+"] 测试番 - "+ep+" [CHS]");return r;}
    private void addIdleRetries(SimpleStore store,int count){for(int i=0;i<count;i++){Show s=show(store,"idle"+i);Release r=release("idle"+i,"首选",1);r.setStatus("waiting");r.setRetryAt(Long.MAX_VALUE);s.getReleases().add(r);}}
    @Test void idleCapacityPullsOnlyThreeFutureRetriesAndRestoresFairnessAfterRestart() throws Exception {
        SimpleStore store=configured();store.state().getSettings().setSimultaneousDownloads(10);addIdleRetries(store,5);Downloader dl=new Downloader();SimpleTrackingService service=new DirectService(store,new Catalog(),dl);
        service.tick();assertEquals(3,dl.tasks.size());assertTrue(store.state().getShows().stream().limit(3).allMatch(s->s.getReleases().getFirst().isIdleProbe()));
        SimpleStore restored=new SimpleStore(root.resolve("state.json"));new DirectService(restored,new Catalog(),dl).tick();assertEquals(3,dl.tasks.size());assertTrue(restored.state().getShows().getFirst().getReleases().getFirst().getLastProbeAt()>0);
    }
    @Test void uploadOnlyTorrentWithZeroReportedProgressDoesNotBlockIdleRetries() throws Exception {
        SimpleStore store=configured();addIdleRetries(store,4);Downloader dl=new Downloader();JsonObject upload=new JsonObject();upload.addProperty("hash","upload");upload.addProperty("state","stalledUP");upload.addProperty("progress",0);dl.tasks.put("upload",upload);
        new DirectService(store,new Catalog(),dl).tick();assertEquals(4,dl.tasks.size());assertEquals(3,SimpleTrackingService.activeCount(dl.tasks()));
    }
    @Test void normalWorkPreventsIdleProbesButCompletionFillsSpareProbeSlots() throws Exception {
        SimpleStore store=configured();addIdleRetries(store,5);Show normal=show(store,"normal");normal.getReleases().add(release("normal","首选",1));Downloader dl=new Downloader();SimpleTrackingService service=new DirectService(store,new Catalog(),dl);
        service.tick();assertEquals(Set.of("normal"),dl.tasks.keySet());dl.finish("normal");service.tick();assertEquals(4,dl.tasks.size());
        dl.finish("idle0");service.tick();assertTrue(dl.tasks.containsKey("idle3"));assertEquals(3,SimpleTrackingService.activeCount(dl.tasks()));
    }
    @Test void stalledIdleProbesRotateToUntriedTasksWithoutExceedingThree() throws Exception {
        SimpleStore store=configured();store.state().getSettings().setSimultaneousDownloads(10);addIdleRetries(store,6);Downloader dl=new Downloader();SimpleTrackingService service=new DirectService(store,new Catalog(),dl);service.tick();
        var field=SimpleTrackingService.class.getDeclaredField("idleRetries");field.setAccessible(true);IdleRetryQueue q=(IdleRetryQueue)field.get(service);
        for(int i=0;i<3;i++)q.stalled("idle"+i,dl.tasks.get("idle"+i),System.currentTimeMillis()-IdleRetryQueue.WINDOW-1000);
        service.tick();assertEquals(6,dl.tasks.size());assertEquals(3,SimpleTrackingService.activeCount(dl.tasks()));
        for(int i=0;i<3;i++){assertEquals("stoppedDL",dl.tasks.get("idle"+i).get("state").getAsString());assertTrue(store.state().getShows().get(i).getReleases().getFirst().getError().contains("轮换"));}
    }
    @Test void idleProbeRespectsLowerConcurrencyAndDoesNotWakeStorageErrors() throws Exception {
        SimpleStore store=configured();store.state().getSettings().setSimultaneousDownloads(1);addIdleRetries(store,3);Release blocked=store.state().getShows().getFirst().getReleases().getFirst();blocked.setStatus("error");blocked.setError("磁盘空间不足");
        Downloader dl=new Downloader();new DirectService(store,new Catalog(),dl).tick();assertEquals(Set.of("idle1"),dl.tasks.keySet());
    }
    @Test void stalledNormalTaskDoesNotBlockIdleRotationAndHealthyProbeKeepsItsConnection() throws Exception {
        SimpleStore store=configured();addIdleRetries(store,4);Show normal=show(store,"normal");Release r=release("normal","首选",1);normal.getReleases().add(r);Downloader dl=new Downloader();SimpleTrackingService service=new DirectService(store,new Catalog(),dl);service.tick();
        var field=SimpleTrackingService.class.getDeclaredField("idleRetries");field.setAccessible(true);IdleRetryQueue q=(IdleRetryQueue)field.get(service);
        q.stalled("normal",dl.tasks.get("normal"),System.currentTimeMillis()-IdleRetryQueue.WINDOW-1000);service.tick();assertEquals("waiting",r.getStatus());assertEquals(3,SimpleTrackingService.activeCount(dl.tasks()));
        JsonObject healthy=dl.tasks.get("idle0");q.stalled("idle0",healthy,System.currentTimeMillis()-IdleRetryQueue.WINDOW-1000);healthy.addProperty("completed",1024*1024);healthy.addProperty("dlspeed",1024);service.tick();assertEquals("downloading",healthy.get("state").getAsString());
    }
    @Test void sharedBatchCountsOnceAndDoesNotImmediatelyRestartThroughAnotherOwner() throws Exception {
        SimpleStore store=configured();String hash="b".repeat(40);
        for(String id:List.of("a","b")){Show s=show(store,id);s.getEpisodeIds().put("1","ep");Release r=release(hash,"首选",0);r.setBatch(true);r.setStatus("waiting");r.setRetryAt(Long.MAX_VALUE);s.getReleases().add(r);}
        addIdleRetries(store,3);Downloader dl=new Downloader(){@Override public JsonArray files(String h){return new JsonArray();}@Override public void filePriorities(DownloadTask task){}};
        SimpleTrackingService service=new DirectService(store,new Catalog(),dl);service.tick();assertEquals(3,dl.tasks.size());assertTrue(dl.tasks.containsKey(hash));
        var field=SimpleTrackingService.class.getDeclaredField("idleRetries");field.setAccessible(true);((IdleRetryQueue)field.get(service)).stalled(hash,dl.tasks.get(hash),System.currentTimeMillis()-IdleRetryQueue.WINDOW-1000);
        service.tick();assertEquals("stoppedDL",dl.tasks.get(hash).get("state").getAsString());assertEquals(3,SimpleTrackingService.activeCount(dl.tasks()));assertTrue(dl.tasks.containsKey("idle2"));
    }
    @Test void noAlternativeKeepsProbeConnectionAndFailedSubmissionsAreBoundedPerTick() throws Exception {
        SimpleStore store=configured();addIdleRetries(store,1);Downloader dl=new Downloader();SimpleTrackingService service=new DirectService(store,new Catalog(),dl);service.tick();
        var field=SimpleTrackingService.class.getDeclaredField("idleRetries");field.setAccessible(true);((IdleRetryQueue)field.get(service)).stalled("idle0",dl.tasks.get("idle0"),System.currentTimeMillis()-IdleRetryQueue.WINDOW-1000);service.tick();assertEquals("downloading",dl.tasks.get("idle0").get("state").getAsString());
        SimpleStore failures=new SimpleStore(root.resolve("failed.json"));failures.state().setSettings(store.state().getSettings());addIdleRetries(failures,8);
        class Failing extends Downloader{int attempts;@Override public void add(Release r,Path p)throws java.io.IOException{attempts++;throw new java.io.IOException("connection timeout");}}
        Failing failing=new Failing();new DirectService(failures,new Catalog(),failing).tick();assertEquals(3,failing.attempts);
    }
    private void primeSlowWindows(SimpleTrackingService service,String hash,JsonObject task) throws Exception {
        var field=SimpleTrackingService.class.getDeclaredField("transferHealth");field.setAccessible(true);
        TransferHealth health=(TransferHealth)field.get(service);long at=System.currentTimeMillis()-2*TransferHealth.WINDOW-1000;
        health.assess(hash,task,at,false);health.assess(hash,task,at+TransferHealth.WINDOW,false);
    }

    @Test void legacyErrorsAreImportedWithoutSubmittingADownloadAndPublished() throws Exception {
        SimpleStore store=configured();Show show=show(store,"123");Release legacy=release("legacy","首选",1);legacy.setStatus("error");legacy.setError("下载器中已有相同资源，但路径或分类不属于此资源库；请先处理现有任务");legacy.setRetryAt(Long.MAX_VALUE);show.getReleases().add(legacy);
        Downloader dl=new Downloader(){@Override public ExistingState reuseExisting(Release r,Path path)throws java.io.IOException{Files.createDirectories(path);Path p=path.resolve("imported.mkv");Files.writeString(p,"legacy original");r.setVideoPath(p.toString());r.setDownloadPath(path.toString());r.setStatus("ready");return ExistingState.IMPORTED;}};
        SimpleTrackingService service=new DirectService(store,new Catalog(),dl);service.tick();
        long deadline=System.currentTimeMillis()+2000;while(show.getSelected().isEmpty()&&System.currentTimeMillis()<deadline){Thread.sleep(10);service.tick();}
        assertEquals("legacy",show.getSelected().get("1"));assertEquals("legacy original",Files.readString(Path.of(legacy.getPublishedFiles().getFirst())));assertTrue(dl.tasks.isEmpty());assertTrue(service.state().getShows().getFirst().getConflictReleaseIds().isEmpty());
    }

    @Test void externalConflictDoesNotOccupySlotOrBlockAlternative() throws Exception {
        SimpleStore store=configured();store.state().getSettings().setSimultaneousDownloads(1);Show show=show(store,"1");Release foreign=release("foreign","首选",1),fallback=release("fallback","备选",1);show.getReleases().addAll(List.of(foreign,fallback));
        Downloader dl=new Downloader(){@Override public void add(Release r,Path p)throws java.io.IOException{if(r.getId().equals("foreign"))throw new DownloadConflictException("旧任务未完成");super.add(r,p);}};
        SimpleTrackingService service=new DirectService(store,new Catalog(),dl);service.tick();service.tick();
        assertEquals("conflict",foreign.getStatus());assertEquals(Set.of("fallback"),dl.tasks.keySet());Show snapshot=service.state().getShows().getFirst();assertEquals(List.of("foreign"),snapshot.getConflictReleaseIds());assertTrue(snapshot.getRetryReleaseIds().isEmpty());
    }

    @Test void slowLegacyCopyDoesNotHoldTheSchedulerOrStateResponse() throws Exception {
        SimpleStore store=configured();Show show=show(store,"1");Release r=release("legacy","首选",1);r.setStatus("conflict");show.getReleases().add(r);
        var entered=new java.util.concurrent.CountDownLatch(1);var finish=new java.util.concurrent.CountDownLatch(1);
        Downloader dl=new Downloader(){@Override public ExistingState reuseExisting(Release release,Path path)throws InterruptedException{entered.countDown();finish.await();return ExistingState.BLOCKED;}};
        SimpleTrackingService service=new DirectService(store,new Catalog(),dl);
        try {assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),service::tick);assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),()->service.state());}
        finally {finish.countDown();}
        long deadline=System.currentTimeMillis()+2000;while(r.getRetryAt()==0&&System.currentTimeMillis()<deadline){Thread.sleep(10);service.tick();}
        assertTrue(r.getRetryAt()>System.currentTimeMillis());assertEquals("conflict",r.getStatus());
    }

    @Test void tinyIntermittentGrowthYieldsToFallbackDespiteRecentPercentProgress() throws Exception {
        SimpleStore store=configured();store.state().getSettings().setSimultaneousDownloads(1);Show show=show(store,"1");Release slow=release("slow","首选",1),fallback=release("fallback","备选",1);show.getReleases().addAll(List.of(slow,fallback));Downloader dl=new Downloader();SimpleTrackingService service=new DirectService(store,new Catalog(),dl);service.tick();
        JsonObject task=dl.tasks.get("slow");task.addProperty("completed",100_000_000);task.addProperty("num_seeds",0);task.addProperty("availability",0.203);
        primeSlowWindows(service,"slow",task);
        task.addProperty("completed",100_300_000);task.addProperty("progress",0.23);service.tick();
        assertEquals("waiting",slow.getStatus());assertTrue(slow.getError().contains("供给不足"));assertEquals("stoppedDL",task.get("state").getAsString());assertTrue(dl.tasks.containsKey("fallback"));
    }

    @Test void rareConnectionIsNotPausedWithoutCompetingWork() throws Exception {
        SimpleStore store=configured();store.state().getSettings().setSimultaneousDownloads(1);Show show=show(store,"1");Release slow=release("slow","首选",1);show.getReleases().add(slow);
        Downloader dl=new Downloader();SimpleTrackingService service=new DirectService(store,new Catalog(),dl);service.tick();
        primeSlowWindows(service,"slow",dl.tasks.get("slow"));service.tick();assertEquals("downloading",slow.getStatus());assertEquals("downloading",dl.tasks.get("slow").get("state").getAsString());
    }

    @Test void followsBackfillsUpgradesAndKeepsManualSelectionAcrossRestart() throws Exception {
        SimpleStore store=new SimpleStore(root.resolve("state.json"));
        Settings settings=store.state().getSettings(); settings.setLibraryRoot(root.resolve("library").toString());settings.setPlaybackRoot(root.resolve("playback").toString());settings.setDownloadHost("http://localhost");settings.setCredential("fake");settings.setTrustedGroups(List.of("首选","备选"));
        Show show=new Show();show.setId("123");show.setTitle("测试番");show.setFolder("测试番 [bgm-123]");store.state().getShows().add(show);
        Catalog catalog=new Catalog();catalog.candidates.addAll(List.of(release("old1","备选",1),release("old2","备选",2)));
        Downloader downloader=new Downloader();SimpleTrackingService service=new DirectService(store,catalog,downloader);
        service.tick();assertEquals(Set.of("old1","old2"),downloader.tasks.keySet());assertTrue(show.getSelected().isEmpty());
        downloader.finish("old1");downloader.finish("old2");service.tick();assertEquals(2,show.getSelected().size());
        Release next=release("preferred1","首选",1);catalog.candidates.add(next);show.setLastCatalogAt(0);service.tick();
        assertEquals("old1",show.getSelected().get("1"));assertEquals(3,downloader.tasks.size());
        downloader.finish("preferred1");service.tick();assertEquals("preferred1",show.getSelected().get("1"));
        Release old=show.getReleases().stream().filter(r->r.getId().equals("old1")).findFirst().orElseThrow();
        assertEquals("old1",Files.readString(Path.of(old.getVideoPath())));
        VersionRequest choose=new VersionRequest();choose.setShowId("123");choose.setReleaseId("old1");service.choose(choose);service.tick();
        assertEquals("old1",show.getSelected().get("1"));assertTrue(show.getPinnedEpisodes().contains("1"));
        SimpleStore restarted=new SimpleStore(root.resolve("state.json"));SimpleTrackingService resumed=new DirectService(restarted,catalog,downloader);resumed.tick();
        assertEquals("old1",restarted.state().getShows().getFirst().getSelected().get("1"));
        assertEquals(3,downloader.tasks.size());
    }

    @Test void pendingDownloadsDoNotPreventBackfillOfOtherEpisodesOrExceedLimit() throws Exception {
        SimpleStore store=new SimpleStore(root.resolve("state.json"));Settings settings=store.state().getSettings();settings.setLibraryRoot(root.resolve("library").toString());settings.setPlaybackRoot(root.resolve("playback").toString());settings.setCredential("fake");settings.setDownloadHost("http://localhost");settings.setTrustedGroups(List.of("组"));settings.setSimultaneousDownloads(1);
        Show show=new Show();show.setId("123");show.setTitle("番");show.setFolder("番");store.state().getShows().add(show);
        Catalog catalog=new Catalog();catalog.candidates.addAll(List.of(release("one","组",1),release("two","组",2)));
        Downloader downloader=new Downloader();SimpleTrackingService service=new DirectService(store,catalog,downloader);
        service.tick();service.tick();assertEquals(1,downloader.tasks.size());assertTrue(downloader.tasks.containsKey("one"));
        downloader.finish("one");service.tick();assertEquals(2,downloader.tasks.size());assertTrue(downloader.tasks.containsKey("two"));
    }

    @Test void pausedTasksDoNotOccupyDownloadSlots() {
        JsonArray tasks=new JsonArray();
        for(String status:List.of("pausedDL","stoppedDL","downloading","metaDL","stalledUP","error","missingFiles")) {
            JsonObject task=new JsonObject();task.addProperty("progress",status.equals("stalledUP")?1:0);task.addProperty("state",status);tasks.add(task);
        }
        assertEquals(2,SimpleTrackingService.activeCount(tasks));
    }

    private SimpleStore configured() throws Exception {
        SimpleStore store=new SimpleStore(root.resolve("state.json"));Settings s=store.state().getSettings();
        s.setLibraryRoot(root.resolve("library").toString());s.setPlaybackRoot(root.resolve("playback").toString());
        s.setDownloadHost("http://localhost");s.setCredential("fake");s.setTrustedGroups(List.of("首选","备选"));return store;
    }
    private Show show(SimpleStore store,String id) {Show s=new Show();s.setId(id);s.setTitle("番"+id);s.setFolder("番"+id);s.setLastCatalogAt(System.currentTimeMillis());store.state().getShows().add(s);return s;}

    @Test void sharesSlotsAcrossShowsAndNewShowPrecedesMoreBackfill() throws Exception {
        SimpleStore store=configured();store.state().getSettings().setSimultaneousDownloads(3);
        for(String id:List.of("1","2","3","4")){Show s=show(store,id);s.getReleases().addAll(List.of(release(id+"a","备选",1),release(id+"b","备选",2)));}
        Downloader dl=new Downloader();SimpleTrackingService service=new DirectService(store,new Catalog(),dl);
        service.tick();assertEquals(Set.of("1a","2a","3a"),dl.tasks.keySet());
        dl.finish("1a");service.tick();assertTrue(dl.tasks.containsKey("4a"));assertFalse(dl.tasks.containsKey("1b"));
    }

    @Test void newShowGetsWatchableEpisodeBeforeOlderShowUpgrade() throws Exception {
        SimpleStore store=configured();store.state().getSettings().setSimultaneousDownloads(1);
        Show old=show(store,"1");old.getReleases().add(release("old","备选",1));
        Downloader dl=new Downloader();SimpleTrackingService service=new DirectService(store,new Catalog(),dl);
        service.tick();dl.finish("old");service.tick();old.getReleases().add(release("upgrade","首选",1));
        Show fresh=show(store,"2");fresh.getReleases().add(release("fresh","备选",1));service.tick();
        assertTrue(dl.tasks.containsKey("fresh"));assertFalse(dl.tasks.containsKey("upgrade"));
    }

    @Test void stuckMetadataReleasesSlotAndTriesAnotherChineseVersion() throws Exception {
        SimpleStore store=configured();store.state().getSettings().setSimultaneousDownloads(1);
        Show s=show(store,"1");Release stuck=release("stuck","首选",1);s.getReleases().addAll(List.of(stuck,release("fallback","备选",1)));
        Downloader dl=new Downloader();SimpleTrackingService service=new DirectService(store,new Catalog(),dl);service.tick();
        stuck.setLastProgressAt(System.currentTimeMillis()-11*60_000);dl.tasks.get("stuck").addProperty("state","metaDL");primeSlowWindows(service,"stuck",dl.tasks.get("stuck"));service.tick();
        assertEquals("waiting",stuck.getStatus());assertTrue(stuck.getRetryAt()>System.currentTimeMillis());
        assertEquals("stoppedDL",dl.tasks.get("stuck").get("state").getAsString());assertTrue(dl.tasks.containsKey("fallback"));
        service.tick();assertEquals("waiting",stuck.getStatus());
    }

    @Test void submissionFailureBacksOffAndDoesNotBlockOtherShows() throws Exception {
        SimpleStore store=configured();store.state().getSettings().setSimultaneousDownloads(1);
        Show a=show(store,"1"),b=show(store,"2");Release broken=release("broken","首选",1);a.getReleases().add(broken);b.getReleases().add(release("good","首选",1));
        Downloader dl=new Downloader(){@Override public void add(Release r,Path p) throws java.io.IOException {if(r.getId().equals("broken"))throw new java.io.IOException("foreign task");super.add(r,p);}};
        new DirectService(store,new Catalog(),dl).tick();
        assertEquals("error",broken.getStatus());assertTrue(broken.getRetryAt()>System.currentTimeMillis());assertTrue(dl.tasks.containsKey("good"));
        assertTrue(a.getMessage().contains("失败"));
    }
    @Test void downloaderStorageErrorsAreNotPresentedAsActiveDownloads() throws Exception {
        SimpleStore store=configured();Show show=show(store,"1");Release r=release("broken","首选",1);show.getReleases().add(r);
        Downloader dl=new Downloader();SimpleTrackingService service=new DirectService(store,new Catalog(),dl);service.tick();
        dl.tasks.get("broken").addProperty("state","error");service.tick();
        assertEquals("error",r.getStatus());assertTrue(r.getError().contains("磁盘错误"));assertEquals(0,SimpleTrackingService.activeCount(dl.tasks()));assertTrue(r.getRetryAt()>System.currentTimeMillis());
    }
    @Test void acceptedUrlThatNeverCreatesATorrentGetsAGracePeriodThenBacksOff() throws Exception {
        SimpleStore store=configured();Show show=show(store,"1");Release r=release("lost","首选",1);show.getReleases().add(r);
        class Dropping extends Downloader {int adds;@Override public void add(Release release,Path path)throws java.io.IOException {adds++;super.add(release,path);tasks.remove(release.getId());}}
        Dropping dl=new Dropping();SimpleTrackingService service=new DirectService(store,new Catalog(),dl);service.tick();service.tick();assertEquals(1,dl.adds);
        r.setLastProgressAt(System.currentTimeMillis()-3*60_000);service.tick();assertEquals("error",r.getStatus());assertTrue(r.getRetryAt()>System.currentTimeMillis());
        service.tick();assertEquals(1,dl.adds);
    }

    @Test void waitingInQueueIsVisibleAndPausedTaskIsNotShownAsDownloading() throws Exception {
        SimpleStore store=configured();store.state().getSettings().setSimultaneousDownloads(1);
        Show a=show(store,"1"),b=show(store,"2");a.getReleases().add(release("a","首选",1));b.getReleases().add(release("b","首选",1));
        Downloader dl=new Downloader();SimpleTrackingService service=new DirectService(store,new Catalog(),dl);service.tick();
        assertTrue(b.getMessage().contains("排队"));dl.tasks.get("a").addProperty("state","stoppedDL");service.tick();
        assertEquals("paused",a.getReleases().getFirst().getStatus());assertTrue(dl.tasks.containsKey("b"));
    }

    @Test void fastProgressPollPublishesLiveStatsAndQueueWithoutSubmittingTasks() throws Exception {
        SimpleStore store=configured();store.state().getSettings().setSimultaneousDownloads(1);
        Show s=show(store,"1");s.getReleases().addAll(List.of(release("one","首选",1),release("two","首选",2)));
        Downloader dl=new Downloader();SimpleTrackingService service=new DirectService(store,new Catalog(),dl);service.tick();
        JsonObject task=dl.tasks.get("one");task.addProperty("progress",0.25);task.addProperty("dlspeed",1024);task.addProperty("completed",250);task.addProperty("total_size",1000);task.addProperty("eta",8640000);task.addProperty("num_seeds",3);
        service.pollProgress();State snapshot=service.state();Release live=snapshot.getShows().getFirst().getReleases().getFirst();
        assertEquals(25,live.getProgress());assertEquals(1024,live.getDownloadSpeed());assertEquals(250,live.getDownloadedBytes());assertEquals(1000,live.getTotalBytes());assertEquals(-1,live.getEtaSeconds());assertEquals(3,live.getSeeds());
        assertTrue(snapshot.getDownloadCheckedAt()>0);assertEquals("",snapshot.getSettings().getCredential());
        assertEquals(List.of("two"),snapshot.getShows().getFirst().getQueuedReleaseIds());assertEquals(Set.of("one"),dl.tasks.keySet());
        assertTrue(s.getReleases().getFirst().getLastProgressAt()>0);
        task.addProperty("total_size",-1);service.pollProgress();
        assertEquals(0,service.state().getShows().getFirst().getReleases().getFirst().getTotalBytes());
    }
    @Test void progressDoesNotWaitForTheCatalogAndPublishMonitor() throws Exception {
        SimpleStore store=configured();Show s=show(store,"1");s.getReleases().add(release("one","首选",1));
        Downloader dl=new Downloader();SimpleTrackingService service=new DirectService(store,new Catalog(),dl);service.tick();
        synchronized(service){java.util.concurrent.CompletableFuture.runAsync(service::pollProgress).get(2,java.util.concurrent.TimeUnit.SECONDS);}
        assertTrue(service.state().getDownloadCheckedAt()>0);
    }
    @Test void oneSharedTorrentFillsOnlyFourMissingEpisodesAndSurvivesRestart() throws Exception {
        SimpleStore store=configured();Show show=show(store,"123");show.setTotalEpisodes(12);
        for(int ep=1;ep<=12;ep++){show.getEpisodeNumbers().put(""+ep,(double)ep);show.getEpisodeIds().put(""+ep,"ep-"+ep);}
        PlaybackLibrary library=new PlaybackLibrary(root.resolve("simple-playback"));
        for(int ep=1;ep<=8;ep++){
            Release r=release("existing"+ep,"首选",ep);r.setStatus("ready");Path dir=root.resolve("library/123/"+r.getId());Files.createDirectories(dir);Path video=dir.resolve("old.mkv");Files.writeString(video,"old"+ep);r.setVideoPath(video.toString());r.setDownloadPath(dir.toString());show.getReleases().add(r);r.setPublishedFiles(library.publish(store.state().getSettings(),show,r));show.getSelected().put(""+ep,r.getId());
        }
        Release pack=release("a".repeat(40),"备选",0);pack.setBatch(true);pack.setTitle("历史番全集 [CHS]");show.getReleases().add(pack);
        class Shared extends Downloader {
            int adds;Set<Integer> priorities=Set.of();boolean finished;
            @Override public void add(Release r,Path p) throws java.io.IOException{adds++;super.add(r,p);}
            @Override public JsonArray files(String hash){JsonArray rows=new JsonArray();for(int i=1;i<=12;i++){JsonObject f=new JsonObject();f.addProperty("index",100+i);f.addProperty("name",String.format("S01E%02d.mkv",i));f.addProperty("progress",finished?1:0);f.addProperty("size",5);rows.add(f);}return rows;}
            @Override public void filePriorities(DownloadTask task){priorities=new HashSet<>(task.getRequestedFiles());}
            @Override public void checkFinished(Release r){r.setVideoPath(Path.of(r.getDownloadPath()).resolve(String.format("S01E%02d.mkv",(int)r.getEpisode())).toString());r.setStatus("ready");r.setProgress(100);}
        }
        Shared dl=new Shared();SimpleTrackingService service=new DirectService(store,new Catalog(),dl);service.tick();service.tick();
        assertEquals(1,dl.adds);assertEquals(Set.of(109,110,111,112),dl.priorities);assertEquals(1,store.state().getDownloads().size());
        Path shared=Path.of(store.state().getDownloads().get(pack.getId()).getPath());
        for(int ep=9;ep<=12;ep++)Files.writeString(shared.resolve(String.format("S01E%02d.mkv",ep)),"new"+ep);
        dl.finished=true;dl.tasks.get(pack.getId()).addProperty("progress",1);service.tick();assertEquals(12,show.getSelected().size());
        for(int ep=1;ep<=8;ep++)assertEquals("existing"+ep,show.getSelected().get(""+ep));
        SimpleStore restarted=new SimpleStore(root.resolve("state.json"));new DirectService(restarted,new Catalog(),dl).tick();assertEquals(1,dl.adds);assertEquals(12,restarted.state().getShows().getFirst().getSelected().size());
    }
    @Test void batchWithoutMetadataYieldsOnlyToUsefulCompetingWork() throws Exception {
        SimpleStore store=configured();Show s=show(store,"123");s.getEpisodeIds().put("1","ep1");s.getEpisodeNumbers().put("1",1d);
        Release pack=release("b".repeat(40),"备选",0);pack.setBatch(true);s.getReleases().add(pack);
        Downloader dl=new Downloader(){@Override public JsonArray files(String hash){return new JsonArray();}};
        SimpleTrackingService service=new DirectService(store,new Catalog(),dl);service.tick();
        DownloadTask shared=store.state().getDownloads().get(pack.getId());shared.setProgress(0);shared.setLastProgressAt(System.currentTimeMillis()-11*60_000);dl.tasks.get(pack.getId()).addProperty("state","metaDL");
        primeSlowWindows(service,pack.getId(),dl.tasks.get(pack.getId()));service.tick();assertEquals("downloading",pack.getStatus());
        store.state().getSettings().setSimultaneousDownloads(1);Show other=show(store,"456");other.getReleases().add(release("other","首选",1));
        var field=SimpleTrackingService.class.getDeclaredField("transferHealth");field.setAccessible(true);((TransferHealth)field.get(service)).forget(pack.getId());
        primeSlowWindows(service,pack.getId(),dl.tasks.get(pack.getId()));service.tick();
        assertEquals("waiting",pack.getStatus());assertEquals("stoppedDL",dl.tasks.get(pack.getId()).get("state").getAsString());assertTrue(dl.tasks.containsKey("other"));
    }
    @Test void missingBatchDoesNotKeepPostponingRetryAndSecondSubmissionCanSucceed() throws Exception {
        SimpleStore store=configured();Show s=show(store,"123");s.getEpisodeIds().put("1","ep1");s.getEpisodeNumbers().put("1",1d);Release pack=release("b".repeat(40),"备选",0);pack.setBatch(true);s.getReleases().add(pack);
        class Flaky extends Downloader {int adds;@Override public void add(Release r,Path p) throws java.io.IOException {if(++adds==1)throw new java.io.IOException("once");super.add(r,p);}}
        Flaky dl=new Flaky();SimpleTrackingService service=new DirectService(store,new Catalog(),dl);service.tick();long retry=pack.getRetryAt();service.tick();assertEquals(retry,pack.getRetryAt());pack.setRetryAt(System.currentTimeMillis()-1);service.tick();assertEquals(2,dl.adds);assertEquals("downloading",pack.getStatus());
    }
    @Test void sharedTaskReportsItsSpeedSeparatelyAndWaitsForRequiredSubtitles() throws Exception {
        SimpleStore store=configured();Show s=show(store,"123");String hash="b".repeat(40);Release r=release(hash+"-file-1","备选",1);r.setTorrentHash(hash);r.setFileIndex(1);r.getSubtitleIndices().add(0);r.setStatus("downloading");r.setRequested(true);s.getReleases().add(r);store.save();
        Downloader dl=new Downloader(){@Override public JsonArray files(String h){return JsonParser.parseString("[{\"index\":0,\"name\":\"01.chs.srt\",\"size\":10,\"priority\":1,\"progress\":0.25},{\"index\":1,\"name\":\"01.mp4\",\"size\":100,\"priority\":1,\"progress\":1}]").getAsJsonArray();}};
        JsonObject task=new JsonObject();task.addProperty("hash",hash);task.addProperty("progress",0.8);task.addProperty("state","downloading");task.addProperty("dlspeed",2048);dl.tasks.put(hash,task);
        SimpleTrackingService service=new DirectService(store,new Catalog(),dl);service.pollProgress();Release live=service.state().getShows().getFirst().getReleases().getFirst();assertEquals(25,live.getProgress());assertEquals(2048,live.getTaskDownloadSpeed());assertEquals(0,live.getDownloadSpeed());
    }
    @Test void reusingCompletedSharedTorrentResetsGlobalActivityRatherThanOldPackActivity() throws Exception {
        SimpleStore store=configured();Show a=show(store,"1"),b=show(store,"2");a.setEnabled(false);b.setSeason(2);b.getEpisodeIds().put("1","b1");b.getEpisodeNumbers().put("1",1d);
        String hash="c".repeat(40);Path shared=root.resolve("library/_torrents/"+hash);Files.createDirectories(shared);
        DownloadTask task=new DownloadTask();task.setHash(hash);task.setPath(shared.toString());task.setProgress(100);task.setLastProgressAt(System.currentTimeMillis()-20*60_000);MediaFile f=new MediaFile();f.setIndex(0);f.setName("S01E01.mkv");f.setProgress(1);task.getFiles().add(f);store.state().getDownloads().put(hash,task);
        Release old=release(hash,"备选",0);old.setBatch(true);old.setDownloadPath(shared.toString());old.setStatus("batch");old.setProgress(100);old.setLastProgressAt(System.currentTimeMillis()-20*60_000);a.getReleases().add(old);
        Release next=release(hash,"备选",0);next.setBatch(true);b.getReleases().add(next);
        Downloader dl=new Downloader(){@Override public JsonArray files(String h){return JsonParser.parseString("[{\"index\":1,\"name\":\"S02E01.mkv\",\"size\":10,\"progress\":0}]").getAsJsonArray();}@Override public void filePriorities(DownloadTask t){}};
        JsonObject remote=new JsonObject();remote.addProperty("hash",hash);remote.addProperty("progress",0);remote.addProperty("state","downloading");dl.tasks.put(hash,remote);
        SimpleTrackingService service=new DirectService(store,new Catalog(),dl);service.tick();service.tick();service.tick();assertEquals("downloading",dl.tasks.get(hash).get("state").getAsString());assertTrue(task.getLastProgressAt()>System.currentTimeMillis()-60_000);assertTrue(b.getReleases().stream().anyMatch(r->r.getFileIndex()==1&&r.isRequested()));
    }
    @Test void revisedResourceNumberCannotSilentlyRenameAnInstalledEpisode() throws Exception {
        SimpleStore store=configured();Show show=show(store,"123");Release old=release("old","备选",1);old.setStatus("ready");Path dir=root.resolve("library/123/old");Files.createDirectories(dir);Path video=dir.resolve("old.mkv");Files.writeString(video,"original");old.setDownloadPath(dir.toString());old.setVideoPath(video.toString());show.getReleases().add(old);
        PlaybackLibrary library=new PlaybackLibrary(root.resolve("simple-playback"));old.setPublishedFiles(library.publish(store.state().getSettings(),show,old));show.getSelected().put("1","old");String title=old.getTitle();
        Catalog catalog=new Catalog();catalog.candidates.add(release("old","备选",2));show.setLastCatalogAt(0);new DirectService(store,catalog,new Downloader()).tick();
        assertEquals(title,old.getTitle());assertEquals(1,old.getEpisode());assertEquals("old",show.getSelected().get("1"));assertFalse(show.getCatalogIssue().isBlank());assertEquals("original",Files.readString(Path.of(old.getPublishedFiles().getFirst())));
    }
}
