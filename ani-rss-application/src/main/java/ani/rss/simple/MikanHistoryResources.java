package ani.rss.simple;

import ani.rss.entity.AnimeGarden;
import ani.rss.service.MikanService;
import ani.rss.simple.SimpleModel.Show;
import ani.rss.util.basic.HttpReq;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Older torrents may lack Garden subject associations. Verify Mikan's BGM link first. */
@Component @RequiredArgsConstructor
public class MikanHistoryResources {
    private final MikanDiscovery discovery;
    private String fetch(String url){return HttpReq.get(url).timeout(20000).thenFunction(r->{HttpReq.assertStatus(r);return r.body();});}
    public List<AnimeGarden.Item> releases(Show show) throws Exception {
        String base=MikanService.getMikanHost().replaceAll("/+$","");
        if(show.getMikanId().isBlank()) {
            long now=System.currentTimeMillis();
            if(now<show.getMikanRetryAt()) {if(!show.getMikanIssue().isBlank())throw new java.io.IOException(show.getMikanIssue());return List.of();}
            show.setMikanRetryAt(now+6*60*60_000L);
            try {
                Set<String> queries=new LinkedHashSet<>(List.of(show.getTitle(),show.getTitle().replaceFirst("\\s+[-—].*$","").replaceFirst("\\s*第[一二三四五六七八九十0-9]+季.*$","").trim()));
                for(String query:queries){
                    if(query.isBlank())continue;
                    var document=Jsoup.parse(fetch(base+"/Home/Search?searchstr="+URLEncoder.encode(query,StandardCharsets.UTF_8)));
                    Set<String> candidates=new LinkedHashSet<>();
                    for(var a:document.select("a[href]")){Matcher m=Pattern.compile("^/Home/Bangumi/([1-9][0-9]*)/?$").matcher(a.attr("href"));if(m.matches())candidates.add(m.group(1));}
                    boolean failed=false;
                    for(String id:candidates.stream().limit(12).toList())try{if(show.getId().equals(discovery.subject(id))){show.setMikanId(id);break;}}catch(java.io.IOException e){failed=true;}
                    if(!show.getMikanId().isBlank())break;
                    if(failed)throw new java.io.IOException("蜜柑作品关联尚未确认");
                }
                show.setMikanCheckedAt(now);show.setMikanRetryAt(now+24*60*60_000L);show.setMikanIssue("");
            }catch(Exception e){show.setMikanIssue("蜜柑历史关联暂不可用，已延后重试");throw e;}
        }
        if(show.getMikanId().isBlank())return List.of();
        return parse(fetch(base+"/RSS/Bangumi?bangumiId="+show.getMikanId()));
    }
    static List<AnimeGarden.Item> parse(String xml) {
        List<AnimeGarden.Item> result=new ArrayList<>();
        for(var item:Jsoup.parse(xml,"",Parser.xmlParser()).select("item")) {
            var title=item.selectFirst("title");var enclosure=item.selectFirst("enclosure[url]");if(title==null||enclosure==null)continue;
            String url=enclosure.attr("url");Matcher hash=Pattern.compile("(?i)([a-f0-9]{40})\\.torrent(?:[?].*)?$").matcher(url);if(!hash.find())continue;
            java.net.URI uri;try{uri=java.net.URI.create(url);}catch(IllegalArgumentException e){continue;}
            if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)continue;
            String name=title.text();Matcher group=Pattern.compile("^[\\[【]([^\\]】]+)[\\]】]").matcher(name);
            AnimeGarden.Fansub fansub=new AnimeGarden.Fansub();fansub.setId("mikan");fansub.setName(group.find()?group.group(1):"未标注字幕组");
            AnimeGarden.Item resource=new AnimeGarden.Item();resource.setId(hash.group(1).toLowerCase(Locale.ROOT));resource.setTitle(name);resource.setFansub(fansub);resource.setHref(url);resource.setMagnet("magnet:?xt=urn:btih:"+resource.getId());resource.setType(SimpleCatalog.isBatch(name)?"合集":"单集");
            var date=item.getElementsByTag("pubDate").first();if(date==null)date=item.getElementsByTag("torrent:pubDate").first();
            if(date!=null)try{resource.setCreatedAt(Date.from(LocalDateTime.parse(date.text()).atZone(ZoneId.of("Asia/Shanghai")).toInstant()));}catch(Exception ignored){}
            result.add(resource);
        }return result;
    }
}
