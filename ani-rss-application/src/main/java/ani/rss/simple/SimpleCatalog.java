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

    public SimpleCatalog() {}
    SimpleCatalog(String host) { this.host = host; }
    public void episodeMetadata(Show show) {
        if(show.getSeason()>1 && (show.getEpisodeNumbers().isEmpty() || System.currentTimeMillis()-show.getEpisodeMetadataAt()>24*60*60_000L))
            EpisodeNumbers.metadata(show,ani.rss.util.other.BgmUtil.getEpisodes(show.getId(),0));
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
            for (AnimeGarden.Item resource : resources) {
                if (resource.getFansub() == null || resource.getMagnet() == null || resource.getTitle() == null) continue;
                String hash = infoHash(resource.getMagnet());
                if (hash.isBlank() || isBatch(resource.getTitle()) || "合集".equals(resource.getType())) continue;
                Item item = new Item().setTitle(resource.getTitle()).setSubgroup(resource.getFansub().getName());
                if (!RenameUtil.rename(parser, item) || !Double.isFinite(item.getEpisode()) || item.getEpisode() <= 0) continue;
                Release release = new Release();
                release.setId(hash);
                release.setTitle(resource.getTitle());
                release.setGroup(resource.getFansub().getName());
                release.setEpisode(item.getEpisode());
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
                EpisodeNumbers.apply(show,release);
                result.add(release);
            }
            if (resources.size() < 100) return result;
            page++;
        }
        throw new IllegalStateException("历史资源超过分页上限，请检查番剧关联");
    }
}
