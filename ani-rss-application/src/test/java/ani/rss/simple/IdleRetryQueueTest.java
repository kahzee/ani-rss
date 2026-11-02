package ani.rss.simple;

import ani.rss.simple.SimpleModel.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IdleRetryQueueTest {
    Release retry(String id){Release r=new Release();r.setId(id);r.setStatus("waiting");r.setChinese(true);r.setGroup("ANi");r.setEpisode(1);r.setRetryAt(Long.MAX_VALUE);return r;}
    JsonObject task(String state,long bytes){JsonObject t=new JsonObject();t.addProperty("state",state);t.addProperty("progress",0.2);t.addProperty("completed",bytes);return t;}
    @Test void coolDownDoesNotPreventIdleProbeAndOldestAttemptWins(){
        State s=new State();s.getSettings().setTrustedGroups(List.of("ANi"));Show a=new Show(),b=new Show();Release first=retry("first"),last=retry("last");first.setLastProbeAt(1);last.setLastProbeAt(2);a.getReleases().add(last);b.getReleases().add(first);s.getShows().addAll(List.of(a,b));
        assertEquals(List.of("first","last"),IdleRetryQueue.candidates(s,Set.of(),Set.of(),Set.of(),1_000_000).stream().map(c->c.release().getId()).toList());
        assertTrue(IdleRetryQueue.candidates(s,Set.of(),Set.of("first"),Set.of("last"),1_000_000).isEmpty());
    }
    @Test void storageManualPausesAndUnknownFailuresAreNotIdleNetworkRetries(){
        Release r=retry("a");for(String reason:List.of("磁盘错误","空间不足","文件或磁盘错误","路径不可用","disk error")){r.setError(reason);assertFalse(IdleRetryQueue.retryCandidate(r));}
        r.setError("");r.setStatus("paused");assertFalse(IdleRetryQueue.retryCandidate(r));r.setStatus("conflict");assertFalse(IdleRetryQueue.retryCandidate(r));
        r.setStatus("error");r.setError("unknown");assertFalse(IdleRetryQueue.retryCandidate(r));r.setError("提交后仍未出现下载任务");assertTrue(IdleRetryQueue.retryCandidate(r));
    }
    @Test void observesActualBytesAndResetsAfterHealthyWindowOrMonitoringGap(){
        IdleRetryQueue q=new IdleRetryQueue();JsonObject t=task("stalledDL",0);assertFalse(q.stalled("a",t,1));assertTrue(q.stalled("a",t,1+IdleRetryQueue.WINDOW));
        t.addProperty("completed",1024*1024);assertFalse(q.stalled("a",t,1+2*IdleRetryQueue.WINDOW));assertFalse(q.stalled("a",t,1+5*IdleRetryQueue.WINDOW));
        t.addProperty("state","checkingDL");assertFalse(q.stalled("a",t,1+6*IdleRetryQueue.WINDOW));
        t.addProperty("state","downloading");t.addProperty("force_start",true);assertFalse(q.stalled("a",t,1+7*IdleRetryQueue.WINDOW));
    }
    @Test void disabledPinnedBusyAndSupersededAreNotCandidates(){
        State s=new State();s.getSettings().setTrustedGroups(List.of("ANi"));Show show=new Show();Release r=retry("r");show.getReleases().add(r);s.getShows().add(show);
        show.setEnabled(false);assertTrue(IdleRetryQueue.candidates(s,Set.of(),Set.of(),Set.of(),1000).isEmpty());show.setEnabled(true);
        show.getPinnedEpisodes().add("1");assertTrue(IdleRetryQueue.candidates(s,Set.of(),Set.of(),Set.of(),1000).isEmpty());show.getPinnedEpisodes().clear();
        Release ready=retry("ready");ready.setStatus("ready");show.getReleases().add(ready);show.getSelected().put("1","ready");assertTrue(IdleRetryQueue.candidates(s,Set.of(),Set.of(),Set.of(),1000).isEmpty());
    }
}
