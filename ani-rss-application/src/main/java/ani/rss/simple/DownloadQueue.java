package ani.rss.simple;

import ani.rss.simple.SimpleModel.*;
import com.google.gson.*;
import java.util.*;

/** Public queue counts describe work still needed, never every old failed alternative. */
final class DownloadQueue {
    private DownloadQueue() {}
    static boolean needed(Show show,Release release,Settings settings) {
        if(!show.isEnabled() || !ReleasePolicy.allowed(release,show,settings))return false;
        if(release.isBatch())return show.getEpisodeIds().keySet().stream().anyMatch(ep->!show.getSelected().containsKey(ep));
        String episode=ReleasePolicy.episodeKey(release.getEpisode());
        if(show.getPinnedEpisodes().contains(episode))return false;
        Release current=show.getReleases().stream().filter(r->Objects.equals(r.getId(),show.getSelected().get(episode))).findFirst().orElse(null);
        if(!ReleasePolicy.better(release,current,show,settings))return false;
        Release ready=ReleasePolicy.bestReady(show,settings,episode).orElse(null);
        return ready==null || ReleasePolicy.better(release,ready,show,settings);
    }
    static List<String> retries(Show show,Settings settings) {
        Set<String> occupied=new HashSet<>();
        for(Release r:show.getReleases())if(!r.isBatch() && Set.of("downloading","submitting").contains(r.getStatus()))occupied.add(ReleasePolicy.episodeKey(r.getEpisode()));
        for(Release r:ReleasePolicy.plan(show,settings))if("available".equals(r.getStatus()))occupied.add(key(r));
        Map<String,Release> pending=new LinkedHashMap<>();
        for(Release r:show.getReleases()) {
            if(!Set.of("waiting","error").contains(r.getStatus()) || !needed(show,r,settings) || occupied.contains(key(r)))continue;
            Release previous=pending.get(key(r));
            if(previous==null || ReleasePolicy.better(r,previous,show,settings))pending.put(key(r),r);
        }
        return pending.values().stream().map(Release::getId).toList();
    }
    static List<String> conflicts(Show show,Settings settings){return show.getReleases().stream().filter(r->"conflict".equals(r.getStatus())&&needed(show,r,settings)).map(Release::getId).toList();}
    /** Preserve rare connections when there is spare capacity or no other useful work. */
    static boolean canYield(State state,String currentHash,JsonArray tasks,long now) {
        Set<String> active=new HashSet<>();
        for(JsonElement element:tasks) {
            JsonObject task=element.getAsJsonObject();String status=task.get("state").getAsString().toLowerCase(Locale.ROOT);
            if(task.get("progress").getAsDouble()<1&&!status.endsWith("up")&&!status.startsWith("paused")&&!status.startsWith("stopped")&&!status.equals("error")&&!status.equals("missingfiles"))active.add(task.get("hash").getAsString().toLowerCase(Locale.ROOT));
        }
        if(!active.contains(currentHash.toLowerCase(Locale.ROOT))||active.size()<state.getSettings().getSimultaneousDownloads())return false;
        for(Show show:state.getShows())for(Release candidate:show.getReleases()) {
            String hash=SimpleDownloader.hash(candidate);
            if(hash.equalsIgnoreCase(currentHash)||active.contains(hash.toLowerCase(Locale.ROOT))||candidate.getRetryAt()>now
                    ||!Set.of("available","waiting","error").contains(candidate.getStatus())||!needed(show,candidate,state.getSettings()))continue;
            boolean occupied=!candidate.isBatch()&&show.getReleases().stream().anyMatch(r->!r.isBatch()&&r.getEpisode()==candidate.getEpisode()
                    &&!SimpleDownloader.hash(r).equalsIgnoreCase(currentHash)&&Set.of("downloading","submitting").contains(r.getStatus()));
            if(!occupied)return true;
        }
        return false;
    }
    private static String key(Release r){return r.isBatch()?"batch:"+SimpleDownloader.hash(r):ReleasePolicy.episodeKey(r.getEpisode());}
}
