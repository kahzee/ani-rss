package ani.rss.simple;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class MikanDiscoveryTest {
    @TempDir Path root;
    private static final String HOME="""
        <div class='sk-bangumi' data-dayofweek='4'><div class='row'>星期四</div><ul class='an-ul'>
        <li><span data-src='/images/test.webp?width=400'></span><div class='num-node'>6</div>
        <div class='date-text'>2026/10/22 更新</div><a class='an-text' href='/Home/Bangumi/4059'>测试番 &amp; 第二季</a></li>
        <li><span data-src='javascript:alert(1)'></span><a class='an-text' href='/Home/Bangumi/4060'>未发布番剧</a></li>
        <li><a class='an-text' href='https://attacker.example/Home/Bangumi/4061'>外部注入</a></li>
        </ul></div>
        """;
    private final AtomicLong now=new AtomicLong(1_000_000L);

    @Test void parsesWeekdayDatesAndImagesWithoutMistakingUnreadCountForEpisode() {
        var items=MikanDiscovery.parse(HOME,"https://mikanani.me");
        assertEquals(2,items.size());var show=items.getFirst();
        assertEquals("4059",show.getId());assertEquals("测试番 & 第二季",show.getTitle());
        assertEquals("星期四",show.getWeekday());assertEquals("2026/10/22",show.getUpdated());
        assertEquals("https://mikanani.me/images/test.webp?width=400",show.getImage());
        assertEquals("",items.get(1).getImage());assertEquals("",items.get(1).getUpdated());
    }

    @Test void browserReadsShareOneScrapeAndRefreshExactlyAfterTenMinutes() {
        AtomicInteger requests=new AtomicInteger();
        var source=new MikanDiscovery(root.resolve("cache.json"),()->"https://mikanani.me",url->{requests.incrementAndGet();return HOME;},now::get);
        source.refresh();source.refresh();source.snapshot();source.snapshot();
        assertEquals(1,requests.get());
        now.addAndGet(MikanDiscovery.INTERVAL-1);source.refresh();assertEquals(1,requests.get());
        now.incrementAndGet();source.refresh();assertEquals(2,requests.get());
        source.snapshot().getItems().clear();assertEquals(2,source.snapshot().getItems().size());
    }

    @Test void failedScrapeRetainsLastSuccessAndIsAlsoThrottledAcrossRestart() {
        AtomicInteger requests=new AtomicInteger();Path cache=root.resolve("cache.json");
        var source=new MikanDiscovery(cache,()->"https://mikanani.me",url->{requests.incrementAndGet();return HOME;},now::get);
        source.refresh();long success=source.snapshot().getCheckedAt();now.addAndGet(MikanDiscovery.INTERVAL);
        var failing=new MikanDiscovery(cache,()->"https://mikanani.me",url->{requests.incrementAndGet();throw new IllegalStateException("offline");},now::get);
        failing.refresh();failing.refresh();assertEquals(2,requests.get());
        assertEquals(success,failing.snapshot().getCheckedAt());assertEquals(2,failing.snapshot().getItems().size());assertFalse(failing.snapshot().getError().isBlank());
        var restarted=new MikanDiscovery(cache,()->"https://mikanani.me",url->{fail("Must retain retry interval after restart");return HOME;},now::get);
        restarted.refresh();assertEquals(2,restarted.snapshot().getItems().size());assertFalse(restarted.snapshot().getError().isBlank());
    }

    @Test void emptyOrChangedWebsiteCannotEraseTheList() {
        AtomicInteger requests=new AtomicInteger();
        var source=new MikanDiscovery(root.resolve("cache.json"),()->"https://mikanani.me",url->requests.getAndIncrement()==0?HOME:"<html>captcha</html>",now::get);
        source.refresh();now.addAndGet(MikanDiscovery.INTERVAL);source.refresh();
        assertEquals(2,source.snapshot().getItems().size());assertFalse(source.snapshot().getError().isBlank());
    }

    @Test void followsExactBangumiLinkAndPersistsMappingWithoutGuessingNames() throws Exception {
        AtomicInteger requests=new AtomicInteger();Path file=root.resolve("cache.json");
        var source=new MikanDiscovery(file,()->"https://mikanani.me",url->{
            requests.incrementAndGet();assertEquals("https://mikanani.me/Home/Bangumi/4059",url);
            return "<p class='bangumi-info'><a href='https://evil.example/subject/1'>Wrong</a><a href='https://bgm.tv/subject/485936'>Bangumi</a></p>";
        },now::get);
        assertEquals("485936",source.subject("4059"));assertEquals("485936",source.subject("4059"));assertEquals(1,requests.get());
        var restarted=new MikanDiscovery(file,()->"https://mikanani.me",url->{fail("Mapping should be cached");return "";},now::get);
        assertEquals("485936",restarted.subject("4059"));assertEquals("485936",restarted.snapshot().getSubjectIds().get("4059"));
        assertThrows(IllegalArgumentException.class,()->source.subject("../1"));
    }

    @Test void missingAssociationDoesNotInventABangumiId() {
        var source=new MikanDiscovery(root.resolve("cache.json"),()->"https://mikanani.me",url->"<p>No link</p>",now::get);
        assertThrows(IllegalArgumentException.class,()->source.subject("4059"));assertTrue(source.snapshot().getSubjectIds().isEmpty());
    }
}
