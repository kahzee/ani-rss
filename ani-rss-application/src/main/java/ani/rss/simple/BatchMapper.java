package ani.rss.simple;

import ani.rss.simple.SimpleModel.*;
import com.google.gson.*;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

/** Map file identities, never manufacture twelve downloads from one torrent. */
public final class BatchMapper {
    private BatchMapper() {}
    private static final Pattern SPECIAL=Pattern.compile("(?i)(?:^|[ /\\\\_.\\[-])(?:NCOP|NCED|OP|ED|SP|OVA|PV|MENU|TRAILER|SAMPLE)(?:[ ._\\]0-9-]|$)|特典|特别篇|特別篇|映像特典");
    private static final Pattern EXPLICIT=Pattern.compile("(?i)(?<![a-z0-9])S(\\d{1,2})E(\\d{1,3})(?!\\d)");
    private static final Pattern MULTI=Pattern.compile("(?i)E\\d+\\s*[-~～]\\s*E?\\d+|\\d+\\s*[-~～]\\s*\\d+|第\\d+[、+&]\\d+");
    private static final Pattern NUMBER=Pattern.compile("(?i)(?: - |[\\[【]|(?:^|[/\\\\])(?:EP?)?)(\\d{1,3})(?:v\\d)?(?=[ ._\\]】集話话]|$)|第\\s*(\\d{1,3})\\s*[集話话]");
    static boolean special(String title){return SPECIAL.matcher(title).find();}
    public static List<MediaFile> files(JsonArray response) {
        List<MediaFile> result=new ArrayList<>();Set<Integer> indices=new HashSet<>();
        for(JsonElement element:response){JsonObject item=element.getAsJsonObject();if(!item.has("index"))throw new IllegalArgumentException("下载器文件列表缺少稳定索引");
            MediaFile f=new MediaFile();f.setIndex(item.get("index").getAsInt());if(f.getIndex()<0||!indices.add(f.getIndex()))throw new IllegalArgumentException("下载器文件索引重复");
            f.setName(item.get("name").getAsString());f.setSize(item.get("size").getAsLong());f.setProgress(item.get("progress").getAsDouble());result.add(f);
        }return result;
    }
    public static List<Release> map(Show show,Release pack,DownloadTask task) {
        if(show.getEpisodeIds().isEmpty())return List.of();
        List<Release> mapped=new ArrayList<>();
        for(MediaFile file:task.getFiles()) {
            if(!PlaybackLibrary.VIDEO.contains(PlaybackLibrary.extension(Path.of(file.getName())))||special(file.getName()))continue;
            String name=file.getName();Matcher explicit=EXPLICIT.matcher(name);Double raw=null;
            if(explicit.find()) {if(Integer.parseInt(explicit.group(1))!=show.getSeason())continue;raw=Double.valueOf(explicit.group(2));
                String rest=name.substring(explicit.end());if(rest.matches("(?i)^\\s*[-~～+]\\s*E?\\d+.*"))continue;
            } else {
                Matcher season=Pattern.compile("(?i)(?:Season[ _-]*|(?:^|[/\\\\ _])S)(\\d{1,2})(?!\\d)|第(\\d{1,2})季").matcher(name);
                boolean wrong=false;while(season.find()){int n=Integer.parseInt(season.group(1)!=null?season.group(1):season.group(2));if(n!=show.getSeason())wrong=true;}if(wrong)continue;
                if(MULTI.matcher(Path.of(name).getFileName().toString()).find())continue;
                Set<Double> possible=new LinkedHashSet<>();Matcher number=NUMBER.matcher(name);
                while(number.find()){double n=Double.parseDouble(number.group(1)!=null?number.group(1):number.group(2));if(show.getEpisodeNumbers().containsKey(ReleasePolicy.episodeKey(n)))possible.add(n);}
                if(possible.size()!=1)continue;raw=possible.iterator().next();
            }
            Release r=new Release();r.setId(task.getHash()+"-file-"+file.getIndex());r.setTorrentHash(task.getHash());r.setFileIndex(file.getIndex());r.setTitle(name);r.setSourceTitle(pack.getTitle());
            r.setEpisode(raw);r.setGroup(pack.getGroup());r.setChinese(pack.isChinese());r.setPublishedAt(pack.getPublishedAt());r.setMagnet(pack.getMagnet());r.setTorrentUrl(pack.getTorrentUrl());r.setDownloadPath(task.getPath());
            EpisodeNumbers.apply(show,r);if(!r.getNumberingIssue().isBlank()||r.getEpisodeId().isBlank())continue;
            String stem=name.substring(0,name.lastIndexOf('.'));
            for(MediaFile subtitle:task.getFiles()) {
                String sub=subtitle.getName();if(!PlaybackLibrary.SUBTITLE.contains(PlaybackLibrary.extension(Path.of(sub))))continue;
                if(sub.startsWith(stem+"."))r.getSubtitleIndices().add(subtitle.getIndex());
            }
            mapped.add(r);
        }
        // Two files claiming the same episode are ambiguous (not automatically different quality versions).
        Map<String,Long> counts=new HashMap<>();mapped.forEach(r->counts.merge(r.getEpisodeId(),1L,Long::sum));
        return mapped.stream().filter(r->counts.get(r.getEpisodeId())==1).toList();
    }
}
