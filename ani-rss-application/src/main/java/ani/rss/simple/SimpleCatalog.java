package ani.rss.simple;

import ani.rss.commons.GsonStatic;
import ani.rss.config.DefaultAniFactory;
import ani.rss.entity.*;
import ani.rss.simple.SimpleModel.*;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.RenameUtil;
import com.google.gson.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import java.util.regex.*;
import java.net.URI;

@Component
public class SimpleCatalog {
    @Value("${simple.catalog-url:https://api.animes.garden}")
    private String host = "https://api.animes.garden";
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private HistoryCatalog history;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private MikanHistoryResources mikanHistory;

    public SimpleCatalog() {}
    SimpleCatalog(String host) { this.host = host; }
    public void episodeMetadata(Show show) throws Exception {
        if(history!=null && (show.getEpisodeIds().isEmpty() || System.currentTimeMillis()-show.getEpisodeMetadataAt()>(show.isHistorical()?31L:1L)*24*60*60_000)) {
            var episodes=history.episodes(show.getId(),show.isHistorical());Show proposed=new Show();EpisodeNumbers.metadata(proposed,episodes);
            if(!show.getEpisodeIds().isEmpty()&&!show.getSelected().isEmpty()&&(show.getEpisodeIds().entrySet().stream().anyMatch(e->!Objects.equals(proposed.getEpisodeIds().get(e.getKey()),e.getValue()))||show.getEpisodeNumbers().entrySet().stream().anyMatch(e->!Objects.equals(proposed.getEpisodeNumbers().get(e.getKey()),e.getValue())))) {
                show.setCatalogIssue("上游剧集目录发生变化，保留原编号与播放文件等待核对");show.setEpisodeMetadataAt(System.currentTimeMillis());return;
            }
            EpisodeNumbers.metadata(show,episodes);show.setEpisodeMetadataAt(history.episodeCheckedAt(show.getId()));show.setCatalogIssue("");
            show.setHistorical(!episodes.isEmpty()&&episodes.stream().allMatch(e->e.getAirdate()!=null&&System.currentTimeMillis()-e.getAirdate().getTime()>180L*24*60*60_000));
        }
    }
    private static final Pattern HASH = Pattern.compile("(?i)btih:([a-f0-9]{40}|[a-z2-7]{32})(?:&|$)");
    // Batch titles must not be silently assigned to the first episode.
    private static final Pattern BATCH = Pattern.compile("合集|全集|(?i)(?<![a-z])(?:complete|batch)(?![a-z])|[\\[【]\\d{1,3}\\s*[-~～]\\s*\\d{1,3}(?:v\\d)?[\\]】 ]|(?i)S\\d{1,2}E\\d{1,3}\\s*[-~～]\\s*(?:S\\d{1,2})?E?\\d{1,3}(?!\\d)");

    static boolean isBatch(String title) { return BATCH.matcher(title).find(); }

    static String infoHash(String magnet) {
        Matcher match = HASH.matcher(magnet);
        if (!match.find()) return "";
        String value = match.group(1);
        if (value.length() == 40) return value.toLowerCase(Locale.ROOT);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        byte[] decoded = new byte[20];
        int bits = 0, buffer = 0, offset = 0;
        for (char character : value.toUpperCase(Locale.ROOT).toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(character);
            bits += 5;
            if (bits >= 8) { bits -= 8; decoded[offset++] = (byte) (buffer >> bits); }
        }
        return HexFormat.of().formatHex(decoded);
    }

    /** Paginate historical resources as RSS alone cannot guarantee backfilling older episodes. */
    public List<Release> releases(Show show) {
        List<Release> result = new ArrayList<>();
        Ani parser = DefaultAniFactory.create().setId(show.getId()).setTitle(show.getTitle())
                .setBgmUrl("https://bgm.tv/subject/" + show.getId())
                .setSeason(show.getSeason()).setCustomEpisode(false)
                .setCustomRenameTemplateEnable(true).setCustomRenameTemplate("${title} S${seasonFormat}E${episodeFormat}");
        int page = 1;
        while (page <= 500) {
            int current = page;
            List<AnimeGarden.Item> resources = HttpReq.get(host + "/resources")
                    .form("subject", show.getId()).form("page", current).form("pageSize", 100).form("duplicate", false)
                    .timeout(20000).thenFunction(response -> {
                        HttpReq.assertStatus(response);
                        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                        return GsonStatic.fromJsonList(json.getAsJsonArray("resources"), AnimeGarden.Item.class);
                    });
            if(current==1 && resources.isEmpty() && mikanHistory!=null) {
                try{resources=mikanHistory.releases(show);}catch(Exception e){throw new IllegalStateException("蜜柑历史资源暂不可用",e);}
            }
            for (AnimeGarden.Item resource : resources) {
                if (resource.getMagnet() == null || resource.getTitle() == null) continue;
                if(BatchMapper.special(resource.getTitle()))continue;
                if(resource.getFansub()==null){AnimeGarden.Fansub group=new AnimeGarden.Fansub();group.setId("unknown");group.setName("未标注字幕组");resource.setFansub(group);}
                String hash = infoHash(resource.getMagnet());
                if (hash.isBlank()) continue;
                boolean batch=isBatch(resource.getTitle()) || "合集".equals(resource.getType());
                Item item = new Item().setTitle(resource.getTitle()).setSubgroup(resource.getFansub().getName());
                if (!batch && (!RenameUtil.rename(parser, item) || !Double.isFinite(item.getEpisode()) || item.getEpisode() <= 0)) continue;
                Release release = new Release();
                release.setId(hash);
                release.setTitle(resource.getTitle());
                release.setGroup(resource.getFansub().getName());
                release.setBatch(batch);release.setEpisode(batch?0:item.getEpisode());
                release.setMagnet(resource.getMagnet());
                if (resource.getHref() != null) {
                    try {
                        URI source = URI.create(resource.getHref());
                        if (Set.of("http", "https").contains(source.getScheme()) && source.getHost() != null
                                && source.getUserInfo() == null && source.getPath().toLowerCase(Locale.ROOT).endsWith(".torrent"))
                            release.setTorrentUrl(source.toString());
                    } catch (IllegalArgumentException ignored) { /* Detail-page links continue to use the magnet. */ }
                }
                release.setPublishedAt(resource.getCreatedAt() == null ? Long.MAX_VALUE : resource.getCreatedAt().getTime());
                release.setChinese(ReleasePolicy.hasChinese(resource.getTitle()));
                if(!batch)EpisodeNumbers.apply(show,release);
                result.add(release);
            }
            if (resources.size() < 100) return result;
            page++;
        }
        throw new IllegalStateException("历史资源超过分页上限，请检查番剧关联");
    }
}
