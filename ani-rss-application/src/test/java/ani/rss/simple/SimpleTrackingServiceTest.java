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
    private Release release(String id,String group,int ep) {Release r=new Release();r.setId(id);r.setGroup(group);r.setEpisode(ep);r.setChinese(true);r.setPublishedAt(1);r.setTitle("["+group+"] 测试番 - "+ep+" [CHS]");return r;}

    @Test void followsBackfillsUpgradesAndKeepsManualSelectionAcrossRestart() throws Exception {
        SimpleStore store=new SimpleStore(root.resolve("state.json"));
        Settings settings=store.state().getSettings(); settings.setLibraryRoot(root.resolve("library").toString());settings.setPlaybackRoot(root.resolve("playback").toString());settings.setDownloadHost("http://localhost");settings.setCredential("fake");settings.setTrustedGroups(List.of("首选","备选"));
        Show show=new Show();show.setId("123");show.setTitle("测试番");show.setFolder("测试番 [bgm-123]");store.state().getShows().add(show);
        Catalog catalog=new Catalog();catalog.candidates.addAll(List.of(release("old1","备选",1),release("old2","备选",2)));
        Downloader downloader=new Downloader();SimpleTrackingService service=new SimpleTrackingService(store,catalog,downloader);
        service.tick();assertEquals(Set.of("old1","old2"),downloader.tasks.keySet());assertTrue(show.getSelected().isEmpty());
        downloader.finish("old1");downloader.finish("old2");service.tick();assertEquals(2,show.getSelected().size());
        Release next=release("preferred1","首选",1);catalog.candidates.add(next);show.setLastCatalogAt(0);service.tick();
        assertEquals("old1",show.getSelected().get("1"));assertEquals(3,downloader.tasks.size());
        downloader.finish("preferred1");service.tick();assertEquals("preferred1",show.getSelected().get("1"));
        Release old=show.getReleases().stream().filter(r->r.getId().equals("old1")).findFirst().orElseThrow();
        assertEquals("old1",Files.readString(Path.of(old.getVideoPath())));
        VersionRequest choose=new VersionRequest();choose.setShowId("123");choose.setReleaseId("old1");service.choose(choose);service.tick();
        assertEquals("old1",show.getSelected().get("1"));assertTrue(show.getPinnedEpisodes().contains("1"));
        SimpleStore restarted=new SimpleStore(root.resolve("state.json"));SimpleTrackingService resumed=new SimpleTrackingService(restarted,catalog,downloader);resumed.tick();
        assertEquals("old1",restarted.state().getShows().getFirst().getSelected().get("1"));
        assertEquals(3,downloader.tasks.size());
    }

    @Test void pendingDownloadsDoNotPreventBackfillOfOtherEpisodesOrExceedLimit() throws Exception {
        SimpleStore store=new SimpleStore(root.resolve("state.json"));Settings settings=store.state().getSettings();settings.setLibraryRoot(root.resolve("library").toString());settings.setPlaybackRoot(root.resolve("playback").toString());settings.setCredential("fake");settings.setDownloadHost("http://localhost");settings.setTrustedGroups(List.of("组"));settings.setSimultaneousDownloads(1);
        Show show=new Show();show.setId("123");show.setTitle("番");show.setFolder("番");store.state().getShows().add(show);
        Catalog catalog=new Catalog();catalog.candidates.addAll(List.of(release("one","组",1),release("two","组",2)));
        Downloader downloader=new Downloader();SimpleTrackingService service=new SimpleTrackingService(store,catalog,downloader);
        service.tick();service.tick();assertEquals(1,downloader.tasks.size());assertTrue(downloader.tasks.containsKey("one"));
        downloader.finish("one");service.tick();assertEquals(2,downloader.tasks.size());assertTrue(downloader.tasks.containsKey("two"));
    }

    @Test void pausedTasksDoNotOccupyDownloadSlots() {
        JsonArray tasks=new JsonArray();
        for(String status:List.of("pausedDL","stoppedDL","downloading","metaDL","stalledUP")) {
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
        Downloader dl=new Downloader();SimpleTrackingService service=new SimpleTrackingService(store,new Catalog(),dl);
        service.tick();assertEquals(Set.of("1a","2a","3a"),dl.tasks.keySet());
        dl.finish("1a");service.tick();assertTrue(dl.tasks.containsKey("4a"));assertFalse(dl.tasks.containsKey("1b"));
    }

    @Test void newShowGetsWatchableEpisodeBeforeOlderShowUpgrade() throws Exception {
        SimpleStore store=configured();store.state().getSettings().setSimultaneousDownloads(1);
        Show old=show(store,"1");old.getReleases().add(release("old","备选",1));
        Downloader dl=new Downloader();SimpleTrackingService service=new SimpleTrackingService(store,new Catalog(),dl);
        service.tick();dl.finish("old");service.tick();old.getReleases().add(release("upgrade","首选",1));
        Show fresh=show(store,"2");fresh.getReleases().add(release("fresh","备选",1));service.tick();
        assertTrue(dl.tasks.containsKey("fresh"));assertFalse(dl.tasks.containsKey("upgrade"));
    }

    @Test void stuckMetadataReleasesSlotAndTriesAnotherChineseVersion() throws Exception {
        SimpleStore store=configured();store.state().getSettings().setSimultaneousDownloads(1);
        Show s=show(store,"1");Release stuck=release("stuck","首选",1);s.getReleases().addAll(List.of(stuck,release("fallback","备选",1)));
        Downloader dl=new Downloader();SimpleTrackingService service=new SimpleTrackingService(store,new Catalog(),dl);service.tick();
        stuck.setLastProgressAt(System.currentTimeMillis()-11*60_000);dl.tasks.get("stuck").addProperty("state","metaDL");service.tick();
        assertEquals("waiting",stuck.getStatus());assertTrue(stuck.getRetryAt()>System.currentTimeMillis());
        assertEquals("stoppedDL",dl.tasks.get("stuck").get("state").getAsString());assertTrue(dl.tasks.containsKey("fallback"));
        service.tick();assertEquals("waiting",stuck.getStatus());
    }

    @Test void submissionFailureBacksOffAndDoesNotBlockOtherShows() throws Exception {
        SimpleStore store=configured();store.state().getSettings().setSimultaneousDownloads(1);
        Show a=show(store,"1"),b=show(store,"2");Release broken=release("broken","首选",1);a.getReleases().add(broken);b.getReleases().add(release("good","首选",1));
        Downloader dl=new Downloader(){@Override public void add(Release r,Path p) throws java.io.IOException {if(r.getId().equals("broken"))throw new java.io.IOException("foreign task");super.add(r,p);}};
        new SimpleTrackingService(store,new Catalog(),dl).tick();
        assertEquals("error",broken.getStatus());assertTrue(broken.getRetryAt()>System.currentTimeMillis());assertTrue(dl.tasks.containsKey("good"));
        assertTrue(a.getMessage().contains("失败"));
    }

    @Test void waitingInQueueIsVisibleAndPausedTaskIsNotShownAsDownloading() throws Exception {
        SimpleStore store=configured();store.state().getSettings().setSimultaneousDownloads(1);
        Show a=show(store,"1"),b=show(store,"2");a.getReleases().add(release("a","首选",1));b.getReleases().add(release("b","首选",1));
        Downloader dl=new Downloader();SimpleTrackingService service=new SimpleTrackingService(store,new Catalog(),dl);service.tick();
        assertTrue(b.getMessage().contains("排队"));dl.tasks.get("a").addProperty("state","stoppedDL");service.tick();
        assertEquals("paused",a.getReleases().getFirst().getStatus());assertTrue(dl.tasks.containsKey("b"));
    }

    @Test void fastProgressPollPublishesLiveStatsAndQueueWithoutSubmittingTasks() throws Exception {
        SimpleStore store=configured();store.state().getSettings().setSimultaneousDownloads(1);
        Show s=show(store,"1");s.getReleases().addAll(List.of(release("one","首选",1),release("two","首选",2)));
        Downloader dl=new Downloader();SimpleTrackingService service=new SimpleTrackingService(store,new Catalog(),dl);service.tick();
        JsonObject task=dl.tasks.get("one");task.addProperty("progress",0.25);task.addProperty("dlspeed",1024);task.addProperty("completed",250);task.addProperty("total_size",1000);task.addProperty("eta",8640000);task.addProperty("num_seeds",3);
        service.pollProgress();State snapshot=service.state();Release live=snapshot.getShows().getFirst().getReleases().getFirst();
        assertEquals(25,live.getProgress());assertEquals(1024,live.getDownloadSpeed());assertEquals(250,live.getDownloadedBytes());assertEquals(1000,live.getTotalBytes());assertEquals(-1,live.getEtaSeconds());assertEquals(3,live.getSeeds());
        assertTrue(snapshot.getDownloadCheckedAt()>0);assertEquals("",snapshot.getSettings().getCredential());
        assertEquals(List.of("two"),snapshot.getShows().getFirst().getQueuedReleaseIds());assertEquals(Set.of("one"),dl.tasks.keySet());
        assertTrue(s.getReleases().getFirst().getLastProgressAt()>0);
        task.addProperty("total_size",-1);service.pollProgress();
        assertEquals(0,service.state().getShows().getFirst().getReleases().getFirst().getTotalBytes());
    }
}
