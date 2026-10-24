package ani.rss.simple;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MikanHistoryResourcesTest {
    @Test void oldRssResourcesKeepTorrentIdentityChineseMarkersAndBatchType(){String hash="a".repeat(40);String xml="<rss><channel><item><title>[花语字幕组] 她和她的猫 [01-04][GB]</title><enclosure url=\"https://mikanani.me/Download/20160417/"+hash+".torrent\"/></item></channel></rss>";var rows=MikanHistoryResources.parse(xml);assertEquals(1,rows.size());assertEquals("合集",rows.getFirst().getType());assertTrue(ReleasePolicy.hasChinese(rows.getFirst().getTitle()));assertTrue(rows.getFirst().getMagnet().endsWith(hash));assertEquals("花语字幕组",rows.getFirst().getFansub().getName());}
    @Test void malformedOrUnsafeTorrentLinksAreNotAccepted(){assertTrue(MikanHistoryResources.parse("<rss><item><title>test</title><enclosure url=\"javascript:evil\"/></item></rss>").isEmpty());}
}
