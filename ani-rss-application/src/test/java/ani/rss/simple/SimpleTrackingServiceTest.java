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
}
