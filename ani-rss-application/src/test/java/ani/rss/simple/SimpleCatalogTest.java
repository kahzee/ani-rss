package ani.rss.simple;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import ani.rss.simple.SimpleModel.Show;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import cn.hutool.extra.spring.SpringUtil;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.boot.info.BuildProperties;
import java.util.Properties;

class SimpleCatalogTest {
    @Test void datesAndWordsAreNotEpisodeBatches() {
        assertFalse(SimpleCatalog.isBatch("[2026-10-22] 番名 - 01 [CHS]"));
        assertFalse(SimpleCatalog.isBatch("Incomplete - 01 [CHS]"));
        assertTrue(SimpleCatalog.isBatch("番名 [01-12] [CHS]"));
        assertTrue(SimpleCatalog.isBatch("番名 S01E01-E12 [CHS]"));
        assertTrue(SimpleCatalog.isBatch("番名 Complete [CHS]"));
    }

    @Test void supportsBothHexAndBase32MagnetHashes() {
        assertEquals("0".repeat(40),SimpleCatalog.infoHash("magnet:?xt=urn:btih:"+"A".repeat(32)));
        assertEquals("f".repeat(40),SimpleCatalog.infoHash("magnet:?xt=urn:btih:"+"7".repeat(32)+"&dn=test"));
        assertEquals("abcdef0123456789abcdef0123456789abcdef01",SimpleCatalog.infoHash("magnet:?xt=urn:btih:ABCDEF0123456789ABCDEF0123456789ABCDEF01"));
        assertEquals("",SimpleCatalog.infoHash("magnet:?xt=urn:btih:../../outside"));
    }

    @Test void gardenFailureFallsBackToVerifiedMikanResources() throws Exception {
        Properties properties=new Properties();properties.setProperty("version","test");
        GenericApplicationContext context=new GenericApplicationContext();context.registerBean(BuildProperties.class,()->new BuildProperties(properties));context.refresh();new SpringUtil().setApplicationContext(context);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/resources",e->{e.sendResponseHeaders(503,-1);e.close();});server.start();
        try {
            SimpleCatalog catalog=new SimpleCatalog("http://127.0.0.1:"+server.getAddress().getPort());
            var field=SimpleCatalog.class.getDeclaredField("mikanHistory");field.setAccessible(true);
            field.set(catalog,new MikanHistoryResources(null){@Override public java.util.List<ani.rss.entity.AnimeGarden.Item> releases(Show show){return MikanHistoryResources.parse("<rss><item><title>[Test] 联调样片 - 01 [CHS]</title><enclosure url=\"https://mikanani.me/Download/"+"a".repeat(40)+".torrent\"/></item></rss>");}});
            Show show=new Show();show.setId("987654321");show.setTitle("联调样片");show.setTotalEpisodes(2);
            var releases=catalog.releases(show);assertEquals(1,releases.size());assertTrue(releases.getFirst().isChinese());
            field.set(catalog,new MikanHistoryResources(null){@Override public java.util.List<ani.rss.entity.AnimeGarden.Item> releases(Show ignored){return java.util.List.of();}});
            assertThrows(RuntimeException.class,()->catalog.releases(show));
        }finally{server.stop(0);context.close();}
    }

    @Test void parsesRealCatalogResponseThroughTheUpstreamEpisodeRecognizer() throws Exception {
        // HttpReq obtains its user-agent version from Spring, without starting the application workers.
        Properties properties=new Properties();properties.setProperty("version","test");
        GenericApplicationContext context=new GenericApplicationContext();
        context.registerBean(BuildProperties.class,()->new BuildProperties(properties));context.refresh();
        new SpringUtil().setApplicationContext(context);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/resources", exchange->{
            String body="{\"resources\":[{\"id\":1,\"title\":\"[TestFallback] 联调样片 - 01 [CHS]\",\"magnet\":\"magnet:?xt=urn:btih:"+"a".repeat(40)+"\",\"type\":\"单集\",\"createdAt\":\"2026-10-22T00:01:00Z\",\"fansub\":{\"id\":1,\"name\":\"TestFallback\"}}]}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try {
            Show show=new Show();show.setId("987654321");show.setTitle("联调样片");show.setTotalEpisodes(2);
            var releases=new SimpleCatalog("http://127.0.0.1:"+server.getAddress().getPort()).releases(show);
            assertEquals(1,releases.size());assertEquals(1.0,releases.getFirst().getEpisode());assertTrue(releases.getFirst().isChinese());
        } finally {server.stop(0);context.close();}
    }
}
