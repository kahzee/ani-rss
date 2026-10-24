package ani.rss.simple;

import ani.rss.entity.BgmEpisodes.BgmEpisode;
import ani.rss.simple.SimpleModel.*;
import java.util.*;
import java.util.regex.*;

/** Use the subject's actual sort/ep pairs, never guess an offset from a season number. */
public final class EpisodeNumbers {
    private EpisodeNumbers() {}
    public static void metadata(Show show,List<BgmEpisode> episodes) {
        Map<String,Double> aliases=new LinkedHashMap<>();Map<String,String> ids=new LinkedHashMap<>();
        Set<String> ambiguous=new HashSet<>();
        for(BgmEpisode e:episodes) {
            if(e.getEp()==null || e.getEp()<=0 || e.getType()!=null && e.getType()!=0) continue;
            double local=e.getEp();String key=ReleasePolicy.episodeKey(local);ids.put(key,e.getId());
            add(aliases,ambiguous,key,local);
            if(e.getSort()!=null && e.getSort()>0) add(aliases,ambiguous,ReleasePolicy.episodeKey(e.getSort()),local);
        }
        if(ids.isEmpty()) return;
        ambiguous.forEach(aliases::remove);
        show.setEpisodeNumbers(aliases);show.setEpisodeIds(ids);show.setTotalEpisodes(ids.size());show.setEpisodeMetadataAt(System.currentTimeMillis());
    }
    private static void add(Map<String,Double> aliases,Set<String> ambiguous,String key,double value) {
        Double old=aliases.putIfAbsent(key,value);if(old!=null && old!=value) ambiguous.add(key);
    }
    public static void apply(Show show) {show.getReleases().forEach(r->apply(show,r));}
    public static void apply(Show show,Release release) {
        if(release.getSourceEpisode()==null) release.setSourceEpisode(release.getEpisode());
        double raw=release.getSourceEpisode();release.setNumberingIssue("");
        Matcher explicit=Pattern.compile("(?i)(?<![A-Za-z0-9])S(\\d{1,2})E(\\d{1,3})(?!\\d)").matcher(Objects.toString(release.getTitle(),""));
        Double canonical=null;
        if(explicit.find()) {
            if(Integer.parseInt(explicit.group(1))!=show.getSeason()) {release.setNumberingIssue("发布标题季度与关注季度不一致");return;}
            canonical=Double.valueOf(explicit.group(2));
        } else if(!show.getEpisodeNumbers().isEmpty()) canonical=show.getEpisodeNumbers().get(ReleasePolicy.episodeKey(raw));
        else if(show.getSeason()==1) canonical=raw;
        if(canonical==null || canonical<=0 || show.getTotalEpisodes()>0 && canonical>show.getTotalEpisodes()) {
            release.setNumberingIssue("集数尚未与本季目录对应，保留资源等待核对（发布编号 "+ReleasePolicy.episodeKey(raw)+"）");return;
        }
        release.setEpisode(canonical);
    }
}
