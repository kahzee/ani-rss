package ani.rss.simple;

import ani.rss.simple.SimpleModel.*;
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
        for(Release r:ReleasePolicy.plan(show,settings))occupied.add(key(r));
        Map<String,Release> pending=new LinkedHashMap<>();
        for(Release r:show.getReleases()) {
            if(!Set.of("waiting","error").contains(r.getStatus()) || !needed(show,r,settings) || occupied.contains(key(r)))continue;
            Release previous=pending.get(key(r));
            if(previous==null || ReleasePolicy.better(r,previous,show,settings))pending.put(key(r),r);
        }
        return pending.values().stream().map(Release::getId).toList();
    }
    static List<String> conflicts(Show show,Settings settings){return show.getReleases().stream().filter(r->"conflict".equals(r.getStatus())&&needed(show,r,settings)).map(Release::getId).toList();}
    private static String key(Release r){return r.isBatch()?"batch:"+SimpleDownloader.hash(r):ReleasePolicy.episodeKey(r.getEpisode());}
}
