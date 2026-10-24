package ani.rss.simple;

import ani.rss.simple.SimpleModel.*;
import com.google.gson.*;
import java.util.*;

/** A small, persistent round robin for otherwise idle network capacity. */
final class IdleRetryQueue {
    static final int LIMIT=3;
    static final long WINDOW=3*60_000L;
    record Candidate(Show show,Release release) {}
    private record Sample(long at,long bytes) {}
    private final Map<String,Sample> samples=new HashMap<>();

    static boolean retryCandidate(Release r) {
        if(!Set.of("waiting","error").contains(r.getStatus()))return false;
        String reason=Objects.toString(r.getError(),"").toLowerCase(Locale.ROOT);
        if(reason.matches("(?s).*(磁盘|存储|空间|文件或|文件缺失|路径|目录|校验|权限|space|disk|missingfiles|permission).*"))return false;
        return "waiting".equals(r.getStatus()) || reason.matches("(?s).*(未出现下载任务|种子文件获取失败|超时|连接失败|timeout|timed out|connection|network).*" );
    }
    static Set<String> probeHashes(State state) {
        Set<String> result=new HashSet<>();
        for(Show show:state.getShows())for(Release r:show.getReleases())
            if(r.isIdleProbe()&&Set.of("downloading","submitting","batch").contains(r.getStatus()))result.add(SimpleDownloader.hash(r).toLowerCase(Locale.ROOT));
        return result;
    }
    static Set<String> activeHashes(JsonArray tasks) {
        Set<String> result=new HashSet<>();
        for(JsonElement e:tasks){JsonObject t=e.getAsJsonObject();String s=t.get("state").getAsString().toLowerCase(Locale.ROOT);
            if(t.get("progress").getAsDouble()<1&&!s.endsWith("up")&&!s.startsWith("paused")&&!s.startsWith("stopped")&&!Set.of("error","missingfiles").contains(s))result.add(t.get("hash").getAsString().toLowerCase(Locale.ROOT));}
        return result;
    }
    static List<Candidate> candidates(State state,Set<String> excludedShows,Set<String> active,Set<String> attempted,long now) {
        List<Candidate> result=new ArrayList<>();
        for(Show show:state.getShows()) {
            if(show.getId()!=null&&excludedShows.contains(show.getId()))continue;
            for(Release r:show.getReleases()) {
                String hash=SimpleDownloader.hash(r).toLowerCase(Locale.ROOT);
                if(!retryCandidate(r)||!DownloadQueue.needed(show,r,state.getSettings())||active.contains(hash)||attempted.contains(hash)
                        ||r.getLastProbeAt()>0&&now-r.getLastProbeAt()<WINDOW)continue;
                boolean busy=!r.isBatch()&&show.getReleases().stream().anyMatch(other->!other.isBatch()&&other.getEpisode()==r.getEpisode()
                        &&Set.of("downloading","submitting").contains(other.getStatus()));
                if(!busy)result.add(new Candidate(show,r));
            }
        }
        result.sort(Comparator.comparingLong((Candidate c)->c.release().getLastProbeAt())
                .thenComparingInt(c->c.show().getSelected().containsKey(ReleasePolicy.episodeKey(c.release().getEpisode()))?1:0)
                .thenComparingLong(c->c.show().getLastQueuedAt()).thenComparing(c->c.release().getId()));
        return result;
    }
    boolean stalled(String hash,JsonObject task,long now) {
        String s=task.get("state").getAsString().toLowerCase(Locale.ROOT);
        if(task.get("progress").getAsDouble()>=1||!Set.of("downloading","stalleddl","metadl").contains(s)
                ||task.has("force_start")&&task.get("force_start").getAsBoolean()){samples.remove(hash);return false;}
        long bytes=task.has("completed")?task.get("completed").getAsLong():task.has("downloaded")?task.get("downloaded").getAsLong():0;
        Sample first=samples.get(hash);
        if(first==null||now<first.at()||bytes<first.bytes()||now-first.at()>2*WINDOW){samples.put(hash,new Sample(now,bytes));return false;}
        if(now-first.at()<WINDOW)return false;
        samples.put(hash,new Sample(now,bytes));
        return bytes-first.bytes()<64*1024; // A few handshake/piece bytes do not monopolize a probe slot.
    }
    void forget(String hash){samples.remove(hash);}
}
