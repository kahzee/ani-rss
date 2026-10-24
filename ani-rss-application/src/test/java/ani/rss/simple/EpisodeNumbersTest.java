package ani.rss.simple;
import ani.rss.entity.BgmEpisodes.BgmEpisode;
import ani.rss.simple.SimpleModel.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EpisodeNumbersTest {
    private Show season(int season,int offset,int count){Show s=new Show();s.setSeason(season);List<BgmEpisode> episodes=new ArrayList<>();for(int n=1;n<=count;n++)episodes.add(new BgmEpisode().setEp(n).setSort(n+offset).setId("ep"+n).setType(0));EpisodeNumbers.metadata(s,episodes);return s;}
    private Release release(double ep,String title){Release r=new Release();r.setEpisode(ep);r.setTitle(title);r.setChinese(true);return r;}
    @Test void mapsConfirmedAbsoluteAndLocalNumbersToSameEpisode(){Show s=season(3,24,12);Release a=release(25,"[ANi] 第三季 - 25 [CHT]"),b=release(1,"第三季 - 01 [CHS]");EpisodeNumbers.apply(s,a);EpisodeNumbers.apply(s,b);assertEquals(1,a.getEpisode());assertEquals(b.getEpisode(),a.getEpisode());assertEquals(25,a.getSourceEpisode());EpisodeNumbers.apply(s,a);assertEquals(1,a.getEpisode());assertEquals(25,a.getSourceEpisode());assertEquals(12,s.getTotalEpisodes());}
    @Test void unknownNumberingNeverEntersDownloadOrPublicationPlan(){Show s=season(2,14,12);Release r=release(99,"番 - 99 [CHS]");EpisodeNumbers.apply(s,r);s.getReleases().add(r);Settings settings=new Settings();settings.setAllowOtherChinese(true);assertFalse(r.getNumberingIssue().isBlank());assertTrue(ReleasePolicy.plan(s,settings).isEmpty());}
    @Test void missingMetadataForLaterSeasonWaitsInsteadOfGuessingOffset(){Show s=new Show();s.setSeason(2);Release r=release(13,"番 第二季 - 13 [CHS]");EpisodeNumbers.apply(s,r);assertFalse(r.getNumberingIssue().isBlank());assertEquals(13,r.getEpisode());}
    @Test void overlappingAbsoluteAndLocalNumbersRequireExplicitSeasonSyntax(){Show s=season(2,12,24);Release ambiguous=release(13,"番 - 13 [CHS]"),explicit=release(13,"番 S02E13 [CHS]");EpisodeNumbers.apply(s,ambiguous);EpisodeNumbers.apply(s,explicit);assertFalse(ambiguous.getNumberingIssue().isBlank());assertEquals("",explicit.getNumberingIssue());assertEquals(13,explicit.getEpisode());Release wrong=release(1,"番 S01E01 [CHS]");EpisodeNumbers.apply(s,wrong);assertFalse(wrong.getNumberingIssue().isBlank());}
}
