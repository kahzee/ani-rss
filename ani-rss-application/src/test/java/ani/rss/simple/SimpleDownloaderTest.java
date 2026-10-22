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
        downloader=new SimpleDownloader();
    }
    @AfterEach void teardown(){server.stop(0);}
    private Settings settings(String credential){Settings s=new Settings();s.setDownloadHost("http://127.0.0.1:"+server.getAddress().getPort());s.setCredential(credential);s.setUsername("admin");return s;}

    @Test void isolatesTasksAndSupportsApiKeys() throws Exception {
        downloader.connect(settings("qbt_test"));Release release=new Release();release.setId("a".repeat(40));release.setMagnet("magnet:?xt=urn:btih:"+release.getId());downloader.add(release,root.resolve("library"));
        assertEquals("Bearer qbt_test",authorization);String body=bodies.get("/api/v2/torrents/add");
        assertTrue(body.contains("category=ani-rss-simple"));assertTrue(body.contains("autoTMM=false"));assertTrue(body.contains("paused=false"));assertFalse(body.contains("rename="));
    }
    @Test void traditionalPasswordLoginIsSupported() throws Exception {downloader.connect(settings("password & space"));assertTrue(bodies.get("/api/v2/auth/login").contains("password=password+%26+space"));}
    @Test void existingForeignTaskIsNeverMovedOrRelabeled() throws Exception {
        downloader.connect(settings("qbt_test"));replies.put("/api/v2/torrents/info","[{\"category\":\"personal\",\"save_path\":\"/private\"}]");
        Release release=new Release();release.setId("a".repeat(40));release.setMagnet("magnet:?xt=urn:btih:"+release.getId());
        assertThrows(java.io.IOException.class,()->downloader.add(release,root));assertFalse(bodies.containsKey("/api/v2/torrents/add"));
    }
    @Test void incompleteFilesCannotBecomeReady() throws Exception {
        downloader.connect(settings("qbt_test"));Release release=new Release();release.setId("a".repeat(40));release.setDownloadPath(root.toString());
        Files.writeString(root.resolve("episode.mkv"),"video");replies.put("/api/v2/torrents/files","[{\"name\":\"episode.mkv\",\"progress\":0.5,\"size\":5}]");
        assertThrows(java.io.IOException.class,()->downloader.checkFinished(release));assertNotEquals("ready",release.getStatus());
    }
    @Test void completeVideoAndSidecarsBecomeReadyOnlyWhenLocallyVisible() throws Exception {
        downloader.connect(settings("qbt_test"));Release release=new Release();release.setId("a".repeat(40));release.setDownloadPath(root.toString());
        Files.writeString(root.resolve("episode.mkv"),"video");Files.writeString(root.resolve("episode.chs.ass"),"sub");
        replies.put("/api/v2/torrents/files","[{\"name\":\"episode.mkv\",\"progress\":1,\"size\":5},{\"name\":\"episode.chs.ass\",\"progress\":1,\"size\":3}]");
        downloader.checkFinished(release);assertEquals("ready",release.getStatus());assertEquals(1,release.getSubtitles().size());
    }
}
