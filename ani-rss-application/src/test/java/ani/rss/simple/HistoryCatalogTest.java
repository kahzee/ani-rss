package ani.rss.simple;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class HistoryCatalogTest {
    @TempDir Path root;
    AtomicLong now=new AtomicLong(1_790_000_000_000L);
    class Remote implements HistoryCatalog.Remote {
        Path zip;String manifest;int gets,downloads;boolean fail;String search="{\"data\":[]}";
        void snapshot(String... rows) throws Exception {
            zip=root.resolve("source-"+System.nanoTime()+".zip");try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip))){out.putNextEntry(new ZipEntry("subject.jsonlines"));out.write(String.join("\n",rows).getBytes(StandardCharsets.UTF_8));out.closeEntry();}
            manifest="{\"digest\":\"sha256:"+HistoryCatalog.digest(Files.readAllBytes(zip))+"\",\"size\":"+Files.size(zip)+",\"browser_download_url\":\"https://github.com/bangumi/Archive/releases/download/archive/test.zip\"}";
        }
        public String get(String url) throws Exception {gets++;if(fail)throw new java.io.IOException("offline");return url.startsWith("search:")?search:manifest;}
        public void download(String url,Path destination) throws Exception {downloads++;Files.copy(zip,destination,StandardCopyOption.REPLACE_EXISTING);}
    }
    String row(String title,String score){return "{\"id\":123,\"type\":2,\"name\":\"Test Anime\",\"name_cn\":\""+title+"\",\"date\":\"2005-01-01\",\"summary\":\"简介\",\"infobox\":\"别名：别称测试\",\"score\":"+score+"}";}
    @Test void importsOnceAndSearchesOfflineAcrossRestartWithoutNetwork() throws Exception {
        Remote remote=new Remote();remote.snapshot(row("历史动画","7"));HistoryCatalog catalog=new HistoryCatalog(root.resolve("db"),remote,now::get);catalog.sync(false);
        assertEquals(1,catalog.search("历史动画").size());assertEquals(1,catalog.search("TEST ANIME").size());assertEquals(1,catalog.search("别称测试").size());assertEquals(1,catalog.search("历史").size());
        int calls=remote.gets;remote.fail=true;HistoryCatalog restarted=new HistoryCatalog(root.resolve("db"),remote,now::get);assertEquals(1,restarted.search("历史动画").size());restarted.sync(false);assertEquals(calls,remote.gets);
    }
    @Test void monthlyCheckSameRevisionSkipsDownloadAndSameBusinessDataSkipsChanges() throws Exception {
        Remote remote=new Remote();remote.snapshot(row("历史动画","7"));HistoryCatalog catalog=new HistoryCatalog(root.resolve("db"),remote,now::get);catalog.sync(false);
        long changed=((Number)catalog.status().get("lastChangedAt")).longValue();now.set(((Number)catalog.status().get("nextCheckAt")).longValue()+1);catalog.sync(false);assertEquals(1,remote.downloads);
        remote.snapshot(row("历史动画","9"));now.set(((Number)catalog.status().get("nextCheckAt")).longValue()+1);catalog.sync(false);assertEquals(2,remote.downloads);assertEquals(changed,((Number)catalog.status().get("lastChangedAt")).longValue());
    }
    @Test void brokenImportRollsBackAndFailedSyncKeepsOldDataAndBacksOff() throws Exception {
        Remote remote=new Remote();remote.snapshot(row("旧资料","7"));HistoryCatalog catalog=new HistoryCatalog(root.resolve("db"),remote,now::get);catalog.sync(false);
        remote.snapshot(row("新资料","8"),"not JSON");now.set(((Number)catalog.status().get("nextCheckAt")).longValue()+1);assertThrows(Exception.class,()->catalog.sync(false));assertEquals(1,catalog.search("旧资料").size());assertTrue(catalog.search("新资料").isEmpty());
        int calls=remote.gets;catalog.sync(false);assertEquals(calls,remote.gets);assertFalse(catalog.status().get("error").toString().isEmpty());
    }
    @Test void checksumMismatchCannotReplaceCatalog() throws Exception {
        Remote remote=new Remote();remote.snapshot(row("历史动画","7"));remote.manifest=remote.manifest.replaceFirst("sha256:[a-f0-9]{64}","sha256:"+"0".repeat(64));HistoryCatalog catalog=new HistoryCatalog(root.resolve("db"),remote,now::get);
        assertThrows(Exception.class,()->catalog.sync(false));assertEquals(0L,catalog.status().get("count"));
    }
    @Test void explicitOnlineMissIsCachedButNetworkFailureIsNotNoResult() throws Exception {
        Remote remote=new Remote();HistoryCatalog catalog=new HistoryCatalog(root.resolve("db"),remote,now::get);assertTrue(catalog.onlineSearch("不存在").isEmpty());catalog.onlineSearch("不存在");assertEquals(1,remote.gets);
        remote.fail=true;assertThrows(Exception.class,()->catalog.onlineSearch("另一部"));assertThrows(Exception.class,()->catalog.onlineSearch("另一部"));assertEquals(3,remote.gets);
    }
    @Test void changedTitleAndAliasBecomeSearchableWithoutChangingIdentity() throws Exception {
        Remote remote=new Remote();remote.snapshot(row("旧名称","7"));HistoryCatalog catalog=new HistoryCatalog(root.resolve("db"),remote,now::get);catalog.sync(false);remote.snapshot(row("新名称","8"));catalog.sync(true);
        assertTrue(catalog.search("旧名称").isEmpty());assertEquals("123",catalog.search("新名称").getFirst().id());
    }
    @Test void onlineAliasResultsAreReturnedAndCachedEvenWhenPayloadLacksTheAlias() throws Exception {
        Remote remote=new Remote();remote.search="{\"data\":[{\"id\":123,\"name\":\"Original\",\"name_cn\":\"中文标题\"}]}";
        HistoryCatalog catalog=new HistoryCatalog(root.resolve("db"),remote,now::get);assertEquals("123",catalog.onlineSearch("magicword").getFirst().id());
        assertEquals("123",catalog.search("magicword").getFirst().id());
        remote.fail=true;assertEquals("123",catalog.onlineSearch("magicword").getFirst().id());assertEquals(1,remote.gets);
    }
    @Test void partialOnlineResultsKeepArchiveAliasesAndNewOnlineEntriesSurviveOldSnapshots() throws Exception {
        Remote remote=new Remote();remote.snapshot(row("历史动画","7"));HistoryCatalog catalog=new HistoryCatalog(root.resolve("db"),remote,now::get);catalog.sync(false);
        remote.search="{\"data\":[{\"id\":123,\"name\":\"Test Anime\",\"name_cn\":\"历史动画\"},{\"id\":456,\"name\":\"New Online\",\"name_cn\":\"在线新条目\"}]}";
        catalog.onlineSearch("new");assertEquals(1,catalog.search("别称测试").size());assertEquals(1,catalog.search("在线新条目").size());
        remote.snapshot(row("历史动画","8"));catalog.sync(true);assertEquals(1,catalog.search("在线新条目").size());
    }
    @Test void staleEpisodeFailureUsesOldDataAndPersistentRetryWithoutFakingSuccessfulCheck() throws Exception {
        Remote remote=new Remote(){@Override public String get(String url) throws Exception {gets++;if(fail)throw new java.io.IOException("offline");return "{\"total\":1,\"data\":[{\"id\":100,\"ep\":1,\"sort\":13,\"type\":0}]}";}};
        HistoryCatalog catalog=new HistoryCatalog(root.resolve("db"),remote,now::get);assertEquals(1,catalog.episodes("123").size());long checked=catalog.episodeCheckedAt("123");now.addAndGet(32L*86400000);remote.fail=true;
        assertEquals(1,catalog.episodes("123").size());assertEquals(1,catalog.episodes("123").size());assertEquals(2,remote.gets);assertEquals(checked,catalog.episodeCheckedAt("123"));
        HistoryCatalog restarted=new HistoryCatalog(root.resolve("db"),remote,now::get);restarted.episodes("123");assertEquals(2,remote.gets);
    }
    @Test void onlineReconfirmationSurvivesRepeatedArchiveOmission() throws Exception {
        Remote remote=new Remote();remote.snapshot(row("历史动画","7"));HistoryCatalog catalog=new HistoryCatalog(root.resolve("db"),remote,now::get);catalog.sync(false);
        remote.snapshot(row("另一作品","8").replace("123","456"));catalog.sync(true);assertTrue(catalog.search("历史动画").isEmpty());
        remote.search="{\"data\":[{\"id\":123,\"name\":\"Test Anime\",\"name_cn\":\"历史动画\"}]}";catalog.onlineSearch("重新确认");assertEquals(1,catalog.search("历史动画").size());
        remote.snapshot(row("另一作品","9").replace("123","456"));catalog.sync(true);assertEquals(1,catalog.search("历史动画").size());
    }
}
