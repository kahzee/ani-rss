package ani.rss.simple;

import ani.rss.simple.SimpleModel.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DownloadQueueTest {
    Settings settings(){Settings s=new Settings();s.setTrustedGroups(List.of("优先","备用"));return s;}
    Release release(String id,String group,String status){Release r=new Release();r.setId(id);r.setGroup(group);r.setStatus(status);r.setChinese(true);r.setEpisode(1);r.setRetryAt(System.currentTimeMillis()+60_000);return r;}
    @Test void supersededAlternativesDoNotCountAsWaiting(){Show s=new Show();Release ready=release("ready","优先","ready"),old=release("old","备用","waiting");s.getReleases().addAll(List.of(ready,old));s.getSelected().put("1","ready");assertTrue(DownloadQueue.retries(s,settings()).isEmpty());}
    @Test void missingEpisodeRetriesAreCountedOnce(){Show s=new Show();s.getReleases().addAll(List.of(release("a","备用","waiting"),release("b","优先","error")));assertEquals(List.of("b"),DownloadQueue.retries(s,settings()));}
    @Test void conflictIsSeparateFromAutomaticRetries(){Show s=new Show();s.getReleases().add(release("foreign","优先","conflict"));assertEquals(List.of("foreign"),DownloadQueue.conflicts(s,settings()));assertTrue(DownloadQueue.retries(s,settings()).isEmpty());assertTrue(ReleasePolicy.plan(s,settings()).isEmpty());}
    @Test void disabledAndPinnedShowsDoNotHavePendingWork(){Show s=new Show();s.getReleases().add(release("a","优先","waiting"));s.setEnabled(false);assertTrue(DownloadQueue.retries(s,settings()).isEmpty());s.setEnabled(true);s.getPinnedEpisodes().add("1");assertTrue(DownloadQueue.retries(s,settings()).isEmpty());}
    @Test void alreadyQueuedOrActiveEpisodeDoesNotAlsoCountAsRetry(){Show s=new Show();Release waiting=release("a","优先","waiting"),available=release("b","备用","available");available.setRetryAt(0);s.getReleases().addAll(List.of(waiting,available));assertTrue(DownloadQueue.retries(s,settings()).isEmpty());available.setStatus("downloading");assertTrue(DownloadQueue.retries(s,settings()).isEmpty());}
}
