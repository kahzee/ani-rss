package ani.rss.simple;

import ani.rss.simple.SimpleModel.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SimpleDownloaderTest {
    @TempDir Path root;
    HttpServer server;
    final Map<String,String> replies=new HashMap<>();
    final Map<String,String> bodies=new HashMap<>();
    String authorization;
    SimpleDownloader downloader;

    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        replies.put("/api/v2/auth/login","Ok.");replies.put("/api/v2/app/version","v5.0.0");replies.put("/api/v2/torrents/createCategory","");replies.put("/api/v2/torrents/info","[]");replies.put("/api/v2/torrents/add","Ok.");
        server.createContext("/",exchange->{String path=exchange.getRequestURI().getPath();bodies.put(path,new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));authorization=exchange.getRequestHeaders().getFirst("Authorization");byte[] bytes=replies.getOrDefault(path,"[]").getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        downloader=new SimpleDownloader(new MediaInspector(video->"{\"streams\":[{\"codec_type\":\"video\",\"width\":1920,\"height\":1080}],\"format\":{\"duration\":24}}"));
    }
    @AfterEach void teardown(){server.stop(0);}
    private Settings settings(String credential){Settings s=new Settings();s.setLibraryRoot(root.toString());s.setDownloadHost("http://127.0.0.1:"+server.getAddress().getPort());s.setCredential(credential);s.setUsername("admin");return s;}

    @Test void remoteNasReceivesItsOwnPathNotTheLocalMount() throws Exception {
        Settings settings=settings("qbt_test");settings.setDownloaderRoot("/downloads/simple");downloader.connect(settings);
        Release release=new Release();release.setId("a".repeat(40));release.setMagnet("magnet:?xt=urn:btih:"+release.getId());
        downloader.add(release,root.resolve("123/hash"));
        assertTrue(bodies.get("/api/v2/torrents/add").contains("savepath=%2Fdownloads%2Fsimple%2F123%2Fhash"));
    }

    @Test void isolatesTasksAndSupportsApiKeys() throws Exception {
        downloader.connect(settings("qbt_test"));Release release=new Release();release.setId("a".repeat(40));release.setMagnet("magnet:?xt=urn:btih:"+release.getId());downloader.add(release,root.resolve("library"));
        assertEquals("Bearer qbt_test",authorization);String body=bodies.get("/api/v2/torrents/add");
        assertTrue(body.contains("category=ani-rss-simple"));assertTrue(body.contains("autoTMM=false"));assertTrue(body.contains("paused=false"));assertFalse(body.contains("rename="));
    }
    @Test void traditionalPasswordLoginIsSupported() throws Exception {downloader.connect(settings("password & space"));assertTrue(bodies.get("/api/v2/auth/login").contains("password=password+%26+space"));}
    @Test void directTorrentMetadataLinksArePassedToTheRealClient() throws Exception {
        downloader.connect(settings("qbt_test"));Release release=new Release();release.setId("b".repeat(40));
        release.setTorrentUrl("https://example.test/sample.torrent");release.setMagnet("magnet:?xt=urn:btih:"+release.getId());
        downloader.add(release,root.resolve("123/hash"));
        assertTrue(bodies.get("/api/v2/torrents/add").contains("urls=https%3A%2F%2Fexample.test%2Fsample.torrent"));
    }
    @Test void existingForeignTaskIsNeverMovedOrRelabeled() throws Exception {
        downloader.connect(settings("qbt_test"));replies.put("/api/v2/torrents/info","[{\"category\":\"personal\",\"save_path\":\"/private\"}]");
        Release release=new Release();release.setId("a".repeat(40));release.setMagnet("magnet:?xt=urn:btih:"+release.getId());
        assertThrows(java.io.IOException.class,()->downloader.add(release,root));assertFalse(bodies.containsKey("/api/v2/torrents/add"));
    }
    @Test void completedForeignTaskIsCopiedWithoutAnySourceControlRequest() throws Exception {
        Settings settings=settings("qbt_test");settings.setLibraryRoot(root.resolve("downloads/library").toString());settings.setDownloaderRoot("/downloads/library");downloader.connect(settings);
        Path source=root.resolve("downloads/old/video.mkv");Files.createDirectories(source.getParent());Files.writeString(source,"video");
        Release r=new Release();r.setId("a".repeat(40));
        replies.put("/api/v2/torrents/info","[{\"hash\":\""+r.getId()+"\",\"category\":\"ani-rss\",\"save_path\":\"/downloads/old\",\"progress\":1,\"state\":\"stoppedUP\"}]");
        replies.put("/api/v2/torrents/files","[{\"index\":0,\"name\":\"video.mkv\",\"progress\":1,\"size\":5}]");
        assertEquals(SimpleDownloader.ExistingState.IMPORTED,downloader.reuseExisting(r,root.resolve("downloads/library/123/hash")));assertEquals("ready",r.getStatus());assertEquals(-1,Files.mismatch(source,Path.of(r.getVideoPath())));
        for(String forbidden:List.of("add","start","resume","setLocation","setCategory","filePrio"))assertFalse(bodies.containsKey("/api/v2/torrents/"+forbidden),forbidden);
    }
    @Test void incompleteFilesCannotBecomeReady() throws Exception {
        downloader.connect(settings("qbt_test"));Release release=new Release();release.setId("a".repeat(40));release.setDownloadPath(root.toString());
        Files.writeString(root.resolve("episode.mkv"),"video");replies.put("/api/v2/torrents/files","[{\"name\":\"episode.mkv\",\"progress\":0.5,\"size\":5}]");
        assertThrows(java.io.IOException.class,()->downloader.checkFinished(release));assertNotEquals("ready",release.getStatus());
    }
    @Test void ownSuspendedTaskResumesWithoutDuplicatingIt() throws Exception {
        downloader.connect(settings("qbt_test"));
        replies.put("/api/v2/torrents/info","[{\"category\":\"ani-rss-simple\",\"save_path\":\""+root.toString().replace("\\","\\\\")+"\",\"state\":\"stoppedDL\"}]");
        Release r=new Release();r.setId("a".repeat(40));downloader.add(r,root);
        assertTrue(bodies.containsKey("/api/v2/torrents/start"));assertFalse(bodies.containsKey("/api/v2/torrents/add"));
    }
    @Test void stalledTaskPauseRefusesForeignPaths() throws Exception {
        downloader.connect(settings("qbt_test"));Release r=new Release();r.setId("a".repeat(40));r.setDownloadPath(root.toString());
        replies.put("/api/v2/torrents/info","[{\"category\":\"ani-rss-simple\",\"save_path\":\"/foreign\"}]");
        assertThrows(java.io.IOException.class,()->downloader.pause(r));assertFalse(bodies.containsKey("/api/v2/torrents/stop"));
    }
    @Test void completeVideoAndSidecarsBecomeReadyOnlyWhenLocallyVisible() throws Exception {
        downloader.connect(settings("qbt_test"));Release release=new Release();release.setId("a".repeat(40));release.setDownloadPath(root.toString());
        Files.writeString(root.resolve("episode.mkv"),"video");Files.writeString(root.resolve("episode.chs.ass"),"sub");
        replies.put("/api/v2/torrents/files","[{\"name\":\"episode.mkv\",\"progress\":1,\"size\":5},{\"name\":\"episode.chs.ass\",\"progress\":1,\"size\":3}]");
        downloader.checkFinished(release);assertEquals("ready",release.getStatus());assertEquals(1,release.getSubtitles().size());
    }
}
