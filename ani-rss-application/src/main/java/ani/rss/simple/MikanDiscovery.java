package ani.rss.simple;

import ani.rss.commons.GsonStatic;
import ani.rss.service.MikanService;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.ConfigUtil;
import lombok.Data;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;
import java.util.regex.Pattern;

/** One shared, persistent scrape per ten minutes, independent of browser traffic. */
@Component
public class MikanDiscovery {
    public static final long INTERVAL = 600_000L;
    private final Path file;
    private final Supplier<String> host;
    private final Function<String, String> fetch;
    private final LongSupplier clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<String, String> subjects = new ConcurrentHashMap<>();
    private volatile Snapshot current = new Snapshot();

    @Data public static class Entry {
        private String id, title, image, weekday, updated, url;
    }
    @Data public static class Snapshot {
        private List<Entry> items = new ArrayList<>();
        private Map<String,String> subjectIds = new HashMap<>();
        private long checkedAt, attemptedAt;
        private long refreshIntervalMs = INTERVAL;
        private boolean refreshing;
        private String error = "";
    }
    @Data private static class Cache {
        private Snapshot snapshot;
        private Map<String, String> subjects = new HashMap<>();
    }

    public MikanDiscovery() {
        this(ConfigUtil.getConfigDir().toPath().resolve("simple-mikan.json"), MikanService::getMikanHost,
                url -> HttpReq.get(url).timeout(20000).thenFunction(response -> {
                    HttpReq.assertStatus(response); return response.body();
                }), System::currentTimeMillis);
    }

    MikanDiscovery(Path file, Supplier<String> host, Function<String, String> fetch, LongSupplier clock) {
        this.file=file; this.host=host; this.fetch=fetch; this.clock=clock;
        if (Files.isRegularFile(file)) {
            try {
                Cache cached=GsonStatic.fromJson(Files.readString(file), Cache.class);
                if (cached != null && cached.snapshot != null && cached.snapshot.items != null) current=cached.snapshot;
                if (cached != null && cached.subjects != null) subjects.putAll(cached.subjects);
            } catch (Exception ignored) { current.setError("蜜柑缓存读取失败，将重新抓取"); }
        }
    }

    public Snapshot snapshot() {
        Snapshot copy=GsonStatic.fromJson(GsonStatic.toJson(current), Snapshot.class);
        copy.setRefreshing(running.get());
        copy.setSubjectIds(new HashMap<>(subjects));
        return copy;
    }

    public void ensureLoaded() {
        if (due()) Thread.startVirtualThread(this::refresh);
    }

    private boolean due() {
        return current.attemptedAt == 0 || clock.getAsLong() - current.attemptedAt >= INTERVAL;
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 600000)
    public void refresh() {
        if (!running.compareAndSet(false,true)) return;
        try {
            if (!due()) return;
            Snapshot next=snapshot(); next.setAttemptedAt(clock.getAsLong()); next.setRefreshing(false); current=next;
            try {
                List<Entry> items=parse(fetch.apply(base()+"/"),base());
                if (items.isEmpty()) throw new IOException("未发现番剧，蜜柑页面可能已变更");
                next.setItems(items); next.setCheckedAt(clock.getAsLong()); next.setError("");
            } catch (Exception e) {
                // Keep last successful data and throttle failures as well as successes.
                next.setError("蜜柑暂时无法更新，保留上次列表；十分钟后重试");
            }
            current=next;
            try { save(); } catch (IOException e) {
                Snapshot failed=snapshot(); failed.setError("列表已更新，但本地缓存保存失败"); current=failed;
            }
        } finally { running.set(false); }
    }

    private String base() { return host.get().replaceAll("/+$", ""); }

    static List<Entry> parse(String html, String host) {
        var document=Jsoup.parse(html,host);
        Map<String,Entry> items=new LinkedHashMap<>();
        for (Element block : document.select(".sk-bangumi")) {
            Element row=block.selectFirst(".row");
            String weekday=row==null ? "其他" : row.text().trim();
            for (Element li : block.select(".an-ul > li")) {
                Element link=li.selectFirst("a.an-text[href]");
                if (link==null) continue;
                var id=Pattern.compile("^/Home/Bangumi/([1-9][0-9]*)/?$").matcher(link.attr("href"));
                if (!id.matches() || link.text().isBlank()) continue;
                Entry entry=new Entry();entry.setId(id.group(1));entry.setTitle(link.text().trim());
                entry.setUrl(host+"/Home/Bangumi/"+entry.getId());entry.setWeekday(weekday);
                Element date=li.selectFirst(".date-text");entry.setUpdated(date==null?"":date.text().replace("更新", "").trim());
                Element image=li.selectFirst("[data-src]");
                entry.setImage(image==null?"":safeImage(host,image.attr("data-src")));
                items.putIfAbsent(entry.getId(),entry);
            }
        }
        return new ArrayList<>(items.values());
    }

    private static String safeImage(String host, String value) {
        try {
            URI uri=URI.create(host+"/").resolve(value);
            if (Set.of("https","http").contains(uri.getScheme()) && uri.getUserInfo()==null && uri.getHost()!=null) return uri.toString();
        } catch (IllegalArgumentException ignored) { }
        return "";
    }

    public synchronized String subject(String id) throws IOException {
        if (id==null || !id.matches("[1-9][0-9]{0,9}")) throw new IllegalArgumentException("无效的蜜柑番剧 ID");
        if (subjects.containsKey(id)) return subjects.get(id);
        var page=Jsoup.parse(fetch.apply(base()+"/Home/Bangumi/"+id));
        for (Element link : page.select(".bangumi-info a[href]")) {
            var match=Pattern.compile("^https?://(?:bgm\\.tv|bangumi\\.tv|chii\\.in)/subject/([1-9][0-9]*)/?$").matcher(link.attr("href"));
            if (!match.matches()) continue;
            String subject=match.group(1);subjects.put(id,subject);
            try { save(); } catch (IOException e) { subjects.remove(id); throw e; }
            return subject;
        }
        throw new IllegalArgumentException("蜜柑尚未提供此番的 Bangumi 关联，暂时无法自动追番");
    }

    private synchronized void save() throws IOException {
        Cache cache=new Cache();cache.snapshot=current;cache.subjects=new HashMap<>(subjects);
        Files.createDirectories(file.toAbsolutePath().getParent());
        PlaybackLibrary.atomicWrite(file,GsonStatic.toJson(cache));
    }
}
