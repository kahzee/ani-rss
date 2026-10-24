package ani.rss.simple;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.BgmInfo;
import ani.rss.util.other.BgmUtil;
import ani.rss.util.other.ConfigUtil;
import com.google.gson.*;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.zip.*;

/** Separate, durable catalog. Browsing never starts a remote refresh. */
@Component
public class HistoryCatalog {
    public static final String MANIFEST="https://raw.githubusercontent.com/bangumi/Archive/master/aux/latest.json";
    public record Entry(String id,String title,String original,String date,String summary,String image,String source) {}
    public interface Remote {String get(String url) throws Exception; void download(String url,Path destination) throws Exception;}
    private final Path directory;
    private final String jdbc;
    private final Remote remote;
    private final LongSupplier clock;
    private final AtomicBoolean syncing=new AtomicBoolean();
    private volatile String phase="";
    public HistoryCatalog() throws Exception {this(ConfigUtil.getConfigDir().toPath().resolve("history"),new Network(),System::currentTimeMillis);}
    HistoryCatalog(Path directory,Remote remote,LongSupplier clock) throws Exception {
        this.directory=directory.toAbsolutePath();this.remote=remote;this.clock=clock;
        if(this.directory.toString().startsWith("\\\\")) throw new IOException("历史数据库必须位于服务所在机器的本地磁盘");
        Files.createDirectories(directory);jdbc="jdbc:sqlite:"+this.directory.resolve("catalog.db");
        try(Connection c=open();Statement s=c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY,v TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS history(id TEXT PRIMARY KEY,title TEXT,original TEXT,date TEXT,summary TEXT,image TEXT,search TEXT,hash TEXT,missing INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE VIRTUAL TABLE IF NOT EXISTS history_fts USING fts5(search,content='history',content_rowid='rowid',tokenize='trigram')");
            s.execute("CREATE TRIGGER IF NOT EXISTS history_ai AFTER INSERT ON history BEGIN INSERT INTO history_fts(rowid,search) VALUES(new.rowid,new.search); END");
            s.execute("CREATE TRIGGER IF NOT EXISTS history_ad AFTER DELETE ON history BEGIN INSERT INTO history_fts(history_fts,rowid,search) VALUES('delete',old.rowid,old.search); END");
            s.execute("CREATE TRIGGER IF NOT EXISTS history_au AFTER UPDATE OF search ON history WHEN old.search<>new.search BEGIN INSERT INTO history_fts(history_fts,rowid,search) VALUES('delete',old.rowid,old.search); INSERT INTO history_fts(rowid,search) VALUES(new.rowid,new.search); END");
            s.execute("CREATE TABLE IF NOT EXISTS details(id TEXT PRIMARY KEY,body TEXT,checked INTEGER)");
            s.execute("CREATE TABLE IF NOT EXISTS episodes(id TEXT PRIMARY KEY,body TEXT,checked INTEGER)");
            s.execute("CREATE TABLE IF NOT EXISTS queries(q TEXT PRIMARY KEY,checked INTEGER)");
            s.execute("CREATE TABLE IF NOT EXISTS attempts(k TEXT PRIMARY KEY,next INTEGER)");
            addColumn(c,"history","origin","TEXT NOT NULL DEFAULT 'archive'");
            addColumn(c,"queries","ids","TEXT NOT NULL DEFAULT '[]'");
        }
    }
    private static void addColumn(Connection c,String table,String column,String definition) throws SQLException {
        boolean present=false;try(Statement s=c.createStatement();ResultSet r=s.executeQuery("PRAGMA table_info("+table+")")){while(r.next())if(r.getString("name").equals(column))present=true;}
        if(!present)try(Statement s=c.createStatement()){s.execute("ALTER TABLE "+table+" ADD COLUMN "+column+" "+definition);}
    }
    private Connection open() throws SQLException {Connection c=DriverManager.getConnection(jdbc);try(Statement s=c.createStatement()){s.execute("PRAGMA busy_timeout=3000");}return c;}
    private static String value(JsonObject o,String key){return !o.has(key)||o.get(key).isJsonNull()?"":o.get(key).isJsonPrimitive()?o.get(key).getAsString():o.get(key).toString();}
    static String normalize(String text){return Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]","");}
    static String digest(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String fileDigest(Path path) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=Files.newInputStream(path)){byte[] b=new byte[1024*1024];for(int n;(n=input.read(b))!=-1;)digest.update(b,0,n);}return HexFormat.of().formatHex(digest.digest());
    }
    private String meta(Connection c,String key) throws SQLException {try(PreparedStatement p=c.prepareStatement("SELECT v FROM meta WHERE k=?")){p.setString(1,key);try(ResultSet r=p.executeQuery()){return r.next()?r.getString(1):"";}}}
    private void meta(Connection c,String key,Object value) throws SQLException {try(PreparedStatement p=c.prepareStatement("INSERT INTO meta VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")){p.setString(1,key);p.setString(2,String.valueOf(value));p.executeUpdate();}}
    private long number(Connection c,String key) throws SQLException {try{return Long.parseLong(meta(c,key));}catch(NumberFormatException e){return 0;}}
    public Map<String,Object> status() throws SQLException {
        try(Connection c=open();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT count(*) FROM history WHERE missing=0")) {
            Map<String,Object> result=new LinkedHashMap<>();r.next();result.put("count",r.getLong(1));
            for(String key:List.of("lastAttemptAt","lastSuccessAt","lastChangedAt","nextCheckAt"))result.put(key,number(c,key));
            result.put("sourceRevision",meta(c,"sourceRevision"));result.put("error",meta(c,"error"));result.put("syncing",syncing.get());result.put("phase",phase);return result;
        }
    }
    public List<Entry> search(String query) throws SQLException {
        String q=normalize(Objects.toString(query,""));if(q.isBlank())return List.of();
        if(q.length()>120)throw new IllegalArgumentException("搜索词过长");
        String predicate=q.codePointCount(0,q.length())>=3?"rowid IN (SELECT rowid FROM history_fts WHERE history_fts MATCH ?)":"instr(search,?)>0";
        try(Connection c=open();PreparedStatement p=c.prepareStatement("SELECT id,title,original,date,summary,image FROM history WHERE missing=0 AND "+predicate+" ORDER BY CASE WHEN title=? OR original=? THEN 0 ELSE 1 END,date DESC LIMIT 60")) {
            p.setString(1,q.codePointCount(0,q.length())>=3?"\""+q.replace("\"","\"\"")+"\"":q);p.setString(2,query);p.setString(3,query);
            List<Entry> result=new ArrayList<>();try(ResultSet r=p.executeQuery()){while(r.next())result.add(new Entry(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),"history"));}
            try(PreparedStatement aliases=c.prepareStatement("SELECT ids FROM queries WHERE q=?")){aliases.setString(1,q);try(ResultSet r=aliases.executeQuery()){if(r.next())for(Entry entry:byIds(JsonParser.parseString(r.getString(1)).getAsJsonArray()))if(result.stream().noneMatch(e->e.id().equals(entry.id()))&&result.size()<60)result.add(entry);}}
            return result;
        }
    }
    /** Only an explicit online-search action uses the API. Empty results expire; failures are not cached. */
    public synchronized List<Entry> onlineSearch(String query) throws Exception {
        String q=normalize(query);if(q.isBlank()||q.length()>120)throw new IllegalArgumentException("请输入番名");
        try(Connection c=open();PreparedStatement p=c.prepareStatement("SELECT checked,ids FROM queries WHERE q=?")) {
            p.setString(1,q);try(ResultSet r=p.executeQuery()){if(r.next()&&clock.getAsLong()-r.getLong(1)<6*60*60_000L)return byIds(JsonParser.parseString(r.getString(2)).getAsJsonArray());}
        }
        JsonObject body=new JsonObject();body.addProperty("keyword",query);body.addProperty("sort","match");JsonObject filter=new JsonObject();JsonArray types=new JsonArray();types.add(2);filter.add("type",types);body.add("filter",filter);
        String response=remote.get("search:"+body);
        JsonObject json=JsonParser.parseString(response).getAsJsonObject();if(!json.has("data")||!json.get("data").isJsonArray())throw new IOException("搜索服务响应无效，保留本地资料");
        JsonArray ids=new JsonArray();
        try(Connection c=open()) {c.setAutoCommit(false);try {
            for(JsonElement element:json.getAsJsonArray("data")){JsonObject item=element.getAsJsonObject();if(item.has("type")&&item.get("type").getAsInt()!=2)continue;upsert(c,item,false);ids.add(value(item,"id"));}
            try(PreparedStatement p=c.prepareStatement("INSERT INTO queries(q,checked,ids) VALUES(?,?,?) ON CONFLICT(q) DO UPDATE SET checked=excluded.checked,ids=excluded.ids")){p.setString(1,q);p.setLong(2,clock.getAsLong());p.setString(3,ids.toString());p.executeUpdate();}c.commit();
        }catch(Exception e){c.rollback();throw e;}}
        return byIds(ids);
    }
    private List<Entry> byIds(JsonArray ids) throws SQLException {
        List<Entry> result=new ArrayList<>();try(Connection c=open();PreparedStatement p=c.prepareStatement("SELECT id,title,original,date,summary,image FROM history WHERE id=? AND missing=0")){
            for(JsonElement id:ids){p.setString(1,id.getAsString());try(ResultSet r=p.executeQuery()){if(r.next())result.add(new Entry(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),"history"));}}
        }return result;
    }
    private int upsert(Connection c,JsonObject item) throws Exception {
        return upsert(c,item,true);
    }
    private int upsert(Connection c,JsonObject item,boolean complete) throws Exception {
        String id=value(item,"id");if(!id.matches("[1-9][0-9]{0,9}"))throw new IOException("历史资料含无效 ID");
        String original=value(item,"name"),title=value(item,"name_cn");if(title.isBlank())title=original;
        if(title.isBlank())throw new IOException("历史资料含空番名");
        String info=value(item,"infobox"); // Raw wiki text contains aliases; normalized search is local.
        if(item.has("infobox")&&item.get("infobox").isJsonArray())info=item.get("infobox").toString();
        String summary=value(item,"summary"),date=value(item,"date");String image="";
        if(item.has("images")&&item.get("images").isJsonObject())image=value(item.getAsJsonObject("images"),"small");
        String search=normalize(title+" "+original+" "+info),origin=complete?"archive":"online";
        if(!complete)try(PreparedStatement old=c.prepareStatement("SELECT title,original,date,summary,search,origin FROM history WHERE id=?")) {
            old.setString(1,id);try(ResultSet r=old.executeQuery()){if(r.next()){
                if(!item.has("name_cn"))title=r.getString(1);if(!item.has("name"))original=r.getString(2);if(!item.has("date"))date=r.getString(3);if(!item.has("summary"))summary=r.getString(4);
                search=r.getString(5);for(String token:List.of(normalize(title),normalize(original),normalize(info)))if(!token.isEmpty()&&!search.contains(token))search+=token;origin="online";
            }}
        }
        String hash=digest((title+"\n"+original+"\n"+date+"\n"+summary+"\n"+search).getBytes(StandardCharsets.UTF_8));
        try(PreparedStatement p=c.prepareStatement("INSERT INTO history(id,title,original,date,summary,image,search,hash,origin) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title,original=excluded.original,date=excluded.date,summary=excluded.summary,image=CASE WHEN excluded.image='' THEN history.image ELSE excluded.image END,search=excluded.search,hash=excluded.hash,missing=0,origin=CASE WHEN history.origin=excluded.origin THEN history.origin ELSE 'both' END WHERE history.hash<>excluded.hash OR history.missing<>0 OR history.origin='archive' AND excluded.origin='online' OR history.origin='online' AND excluded.origin='archive' OR history.image='' AND excluded.image<>''")) {
            String[] values={id,title,original,date,summary,image,search,hash,origin};for(int i=0;i<values.length;i++)p.setString(i+1,values[i]);return p.executeUpdate();
        }
    }
    @FunctionalInterface private interface Fetch {String get() throws Exception;}
    private String cached(String table,String id,long ttl,Fetch fetch) throws Exception {
        String old=null;long checked=0;
        try(Connection c=open();PreparedStatement p=c.prepareStatement("SELECT body,checked FROM "+table+" WHERE id=?")){p.setString(1,id);try(ResultSet r=p.executeQuery()){if(r.next()){old=r.getString(1);checked=r.getLong(2);}}}
        if(old!=null&&clock.getAsLong()-checked<ttl)return old;
        String key=table+":"+id;
        try(Connection c=open()) {
            try(PreparedStatement p=c.prepareStatement("SELECT next FROM attempts WHERE k=?")){p.setString(1,key);try(ResultSet r=p.executeQuery()){if(r.next()&&clock.getAsLong()<r.getLong(1)){if(old!=null)return old;throw new IOException("资料获取已退避，稍后重试");}}}
            try(PreparedStatement p=c.prepareStatement("INSERT INTO attempts VALUES(?,?) ON CONFLICT(k) DO UPDATE SET next=excluded.next")){p.setString(1,key);p.setLong(2,clock.getAsLong()+6*60*60_000L);p.executeUpdate();}
        }
        try {
            String body=fetch.get();
            try(Connection c=open();PreparedStatement p=c.prepareStatement("INSERT INTO "+table+" VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET body=excluded.body,checked=excluded.checked")){p.setString(1,id);p.setString(2,body);p.setLong(3,clock.getAsLong());p.executeUpdate();}
            try(Connection c=open();PreparedStatement p=c.prepareStatement("DELETE FROM attempts WHERE k=?")){p.setString(1,key);p.executeUpdate();}
            return body;
        }catch(Exception e){if(old!=null)return old;throw e;}
    }
    public long episodeCheckedAt(String id) throws SQLException {
        try(Connection c=open();PreparedStatement p=c.prepareStatement("SELECT checked FROM episodes WHERE id=?")){p.setString(1,id);try(ResultSet r=p.executeQuery()){return r.next()?r.getLong(1):0;}}
    }
    public synchronized BgmInfo info(String id) throws Exception {
        return GsonStatic.fromJson(cached("details",id,31L*24*60*60_000,()->{BgmInfo info=BgmUtil.getBgmInfo(id,true);if(info==null)throw new IOException("番剧详情暂不可用");return GsonStatic.toJson(info);}),BgmInfo.class);
    }
    public synchronized List<ani.rss.entity.BgmEpisodes.BgmEpisode> episodes(String id) throws Exception {return episodes(id,true);}
    public synchronized List<ani.rss.entity.BgmEpisodes.BgmEpisode> episodes(String id,boolean stable) throws Exception {
        return GsonStatic.fromJsonList(cached("episodes",id,(stable?31L:1L)*24*60*60_000,()->fetchEpisodes(id)),ani.rss.entity.BgmEpisodes.BgmEpisode.class);
    }
    private String fetchEpisodes(String id) throws Exception {
        JsonArray data=new JsonArray();int total=-1;Set<String> seen=new HashSet<>();
        for(int offset=0;offset<100_000;offset+=100){
            JsonObject page=JsonParser.parseString(remote.get("https://api.bgm.tv/v0/episodes?subject_id="+id+"&type=0&limit=100&offset="+offset)).getAsJsonObject();
            if(!page.has("data")||!page.has("total"))throw new IOException("剧集目录响应不完整");
            if(total<0)total=page.get("total").getAsInt();else if(total!=page.get("total").getAsInt())throw new IOException("剧集目录同步期间发生变化");
            JsonArray rows=page.getAsJsonArray("data");
            for(JsonElement row:rows)if(!seen.add(value(row.getAsJsonObject(),"id")))throw new IOException("剧集目录包含重复身份");
            data.addAll(rows);if(data.size()==total)break;if(rows.isEmpty())throw new IOException("剧集目录分页不完整");
        }
        if(data.isEmpty()||data.size()!=total)throw new IOException("暂无完整剧集目录，等待资料补充");
        GsonStatic.fromJsonList(data,ani.rss.entity.BgmEpisodes.BgmEpisode.class);return data.toString();
    }
    @Scheduled(initialDelay=5000,fixedDelay=60000)
    public void scheduled() {try {sync(false);}catch(Exception ignored){/* Error is persisted; readers retain the previous committed catalog. */}}
    public void requestSync(){Thread.startVirtualThread(()->{try{sync(true);}catch(Exception ignored){}});}
    public void sync(boolean manual) throws Exception {
        if(!syncing.compareAndSet(false,true))return;
        try(Connection c=open()) {
            long now=clock.getAsLong();if(!manual&&now<number(c,"nextCheckAt"))return;
            // Persist throttle before network I/O, including failed and interrupted attempts.
            meta(c,"lastAttemptAt",now);meta(c,"nextCheckAt",now+6*60*60_000L);phase="检查资料版本";
            try {
                JsonObject manifest=JsonParser.parseString(remote.get(MANIFEST)).getAsJsonObject();
                String revision=value(manifest,"digest");if(!revision.matches("sha256:[a-f0-9]{64}"))throw new IOException("资料包缺少有效校验摘要");
                if(!revision.equals(meta(c,"sourceRevision"))) {
                    Path zip=directory.resolve("archive.zip");long size=manifest.get("size").getAsLong();
                    if(size<=0||size>2_000_000_000L)throw new IOException("资料包大小不合理");
                    if(!Files.isRegularFile(zip)||Files.size(zip)!=size||!("sha256:"+fileDigest(zip)).equals(revision)) {
                        phase="下载历史资料包";Path pending=directory.resolve("archive.zip.pending");
                        String url=value(manifest,"browser_download_url");if(!url.startsWith("https://github.com/bangumi/Archive/releases/download/"))throw new IOException("资料包来源异常");
                        remote.download(url,pending);
                        if(Files.size(pending)!=size||!("sha256:"+fileDigest(pending)).equals(revision))throw new IOException("资料包校验不通过，继续使用旧资料");
                        Files.move(pending,zip,StandardCopyOption.REPLACE_EXISTING);
                    }
                    phase="导入并校验本地索引";importArchive(zip,revision);
                }
                meta(c,"lastSuccessAt",now);meta(c,"error","");
                meta(c,"nextCheckAt",Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).plusMonths(1).toInstant().toEpochMilli());
            }catch(Exception e){meta(c,"error","历史资料同步失败，保留旧库："+e.getClass().getSimpleName());throw e;}
        }finally {phase="";syncing.set(false);}
    }
    int importArchive(Path zip,String revision) throws Exception {
        try(Connection c=open();ZipFile archive=new ZipFile(zip.toFile(),StandardCharsets.UTF_8)) {
            ZipEntry entry=archive.stream().filter(e->e.getName().equals("subject.jsonlines")||e.getName().endsWith("/subject.jsonlines")).findFirst().orElseThrow(()->new IOException("资料包缺少作品目录"));
            c.setAutoCommit(false);try(Statement statement=c.createStatement();BufferedReader reader=new BufferedReader(new InputStreamReader(archive.getInputStream(entry),StandardCharsets.UTF_8))) {
                statement.execute("CREATE TEMP TABLE seen(id TEXT PRIMARY KEY)");int rows=0,changed=0;
                try(PreparedStatement seen=c.prepareStatement("INSERT INTO seen VALUES(?)")) {
                    for(String line;(line=reader.readLine())!=null;) {
                        if(line.length()>4_000_000)throw new IOException("单条历史资料过大");
                        JsonObject item=JsonParser.parseString(line).getAsJsonObject();if(item.get("type").getAsInt()!=2)continue;
                        seen.setString(1,value(item,"id"));seen.executeUpdate();changed+=upsert(c,item);rows++;
                    }
                }
                long previous=0;try(ResultSet r=statement.executeQuery("SELECT count(*) FROM history WHERE missing=0")){r.next();previous=r.getLong(1);}
                if(rows==0||previous>100&&rows<previous*0.7)throw new IOException("历史资料数量异常，拒绝替换旧库");
                changed+=statement.executeUpdate("UPDATE history SET missing=1 WHERE missing=0 AND origin='archive' AND id NOT IN (SELECT id FROM seen)");
                meta(c,"sourceRevision",revision);if(changed>0)meta(c,"lastChangedAt",clock.getAsLong());
                c.commit();return changed;
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    private static class Network implements Remote {
        private HttpClient client(){return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();}
        public String get(String url) throws Exception {
            HttpRequest.Builder b=HttpRequest.newBuilder().timeout(Duration.ofSeconds(30)).header("User-Agent","ani-rss-simple/1.0 (local history catalog)");
            if(url.startsWith("search:"))b.uri(URI.create("https://api.bgm.tv/v0/search/subjects?limit=60")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(url.substring(7)));
            else b.uri(URI.create(url)).GET();
            try(HttpClient client=client()){HttpResponse<String> response=client.send(b.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));if(response.statusCode()!=200)throw new IOException("历史服务 HTTP "+response.statusCode());return response.body();}
        }
        public void download(String url,Path destination) throws Exception {
            HttpRequest request=HttpRequest.newBuilder(URI.create(url)).header("User-Agent","ani-rss-simple/1.0").timeout(Duration.ofMinutes(15)).GET().build();
            try(HttpClient client=client()){var response=client.send(request,HttpResponse.BodyHandlers.ofFile(destination,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE));if(response.statusCode()!=200)throw new IOException("资料包下载 HTTP "+response.statusCode());}
        }
    }
}
