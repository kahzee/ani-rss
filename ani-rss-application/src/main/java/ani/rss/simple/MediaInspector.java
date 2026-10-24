package ani.rss.simple;

import ani.rss.simple.SimpleModel.Release;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Probe real media before publishing. Subtitle evidence deliberately distinguishes tags from content. */
public class MediaInspector {
    @FunctionalInterface interface Probe {String read(Path video) throws IOException,InterruptedException;}
    private final Probe probe;
    public MediaInspector(){this(MediaInspector::run);}
    MediaInspector(Probe probe){this.probe=probe;}
    public void inspect(Release release) throws IOException,InterruptedException {
        Path video=Path.of(release.getVideoPath());
        String signature=video+":"+Files.size(video)+":"+Files.getLastModifiedTime(video).toMillis();
        for(String name:release.getSubtitles()) {
            Path subtitle=Path.of(name);
            signature+="|"+subtitle+":"+Files.size(subtitle)+":"+Files.getLastModifiedTime(subtitle).toMillis();
        }
        if(signature.equals(release.getMediaSignature())) return;
        JsonObject json;
        try {json=JsonParser.parseString(probe.read(video)).getAsJsonObject();}
        catch(RuntimeException e){throw new IOException("无法读取媒体探测结果",e);}
        boolean picture=false,chineseTrack=false,foreignTracks=false;
        if(!json.has("streams") || !json.get("streams").isJsonArray()) throw new IOException("媒体探测结果缺少音视频轨道");
        for(JsonElement element:json.getAsJsonArray("streams")) {
            if(!element.isJsonObject()) continue;
            JsonObject stream=element.getAsJsonObject();String type=stream.has("codec_type")?stream.get("codec_type").getAsString():"";
            if(type.equals("video") && stream.has("width") && stream.has("height") && stream.get("width").getAsInt()>0 && stream.get("height").getAsInt()>0) picture=true;
            if(type.equals("subtitle") && stream.has("tags")) {
                JsonObject tags=stream.getAsJsonObject("tags");String language=tags.has("language")?tags.get("language").getAsString().toLowerCase(Locale.ROOT):"";
                chineseTrack|=Set.of("chi","zho","zh","chs","cht","zh-cn","zh-tw","zh-hans","zh-hant").contains(language);
                foreignTracks|=Set.of("jpn","ja","eng","en").contains(language);
            }
        }
        double duration=0;try{duration=json.getAsJsonObject("format").get("duration").getAsDouble();}catch(RuntimeException ignored){}
        if(!picture || !Double.isFinite(duration) || duration<=0) throw new IOException("媒体不含可识别的视频画面或有效时长，原件保留，改用其他版本");
        boolean text=false,kana=false,declaredChineseSidecar=false;
        for(String name:release.getSubtitles()) {
            Path file=Path.of(name);String ext=PlaybackLibrary.extension(file);
            if(!Set.of("ass","ssa","srt","vtt").contains(ext)) continue;
            byte[] bytes;try(var input=Files.newInputStream(file)){bytes=input.readNBytes(256*1024);}
            String content;
            try{content=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();}
            catch(CharacterCodingException e){content=Charset.forName("GB18030").decode(java.nio.ByteBuffer.wrap(bytes)).toString();}
            if(Set.of("ass","ssa").contains(ext)) content=content.lines().filter(l->l.startsWith("Dialogue:")).reduce("",(a,b)->a+"\n"+b);
            text|=content.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN);
            kana|=content.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HIRAGANA||Character.UnicodeScript.of(c)==Character.UnicodeScript.KATAKANA);
            declaredChineseSidecar|=Set.of("zh-Hans","zh-Hant").contains(PlaybackLibrary.subtitleLanguage(file.getFileName().toString()));
        }
        if(release.isChinese()&&!chineseTrack&&!declaredChineseSidecar&&(foreignTracks||kana))throw new IOException("仅发现外语字幕证据，中文字幕待核对；已保留原件");
        release.setSubtitleEvidence(chineseTrack?"字幕轨标记为中文（未逐句校验）":text?(declaredChineseSidecar?"外挂字幕标记中文且正文含汉字（未逐句校验）":"外挂字幕正文含汉字，语言待核验") :"发布标题声明中文；画面内嵌字幕待抽检");
        release.setMediaSignature(signature);
    }
    private static String run(Path video) throws IOException,InterruptedException {
        Path output=Files.createTempFile("ani-probe-",".json"),error=Files.createTempFile("ani-probe-",".log");
        try {
            Process process=new ProcessBuilder(System.getProperty("simple.ffprobe","ffprobe"),"-v","error","-show_streams","-show_format","-of","json",video.toString())
                    .redirectOutput(output.toFile()).redirectError(error.toFile()).start();
            try {
                if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("媒体校验超时，稍后重试");}
                if(process.exitValue()!=0 || Files.size(output)>2_000_000) throw new IOException("媒体读取失败，原件保留，请查看其他版本");
                return Files.readString(output);
            } finally {if(process.isAlive())process.destroyForcibly();}
        } catch(IOException e){if(e.getMessage()!=null&&e.getMessage().contains("CreateProcess"))throw new IOException("无法启动 ffprobe，请安装 ffmpeg 或设置 -Dsimple.ffprobe=绝对路径",e);throw e;}
        finally {Files.deleteIfExists(output);Files.deleteIfExists(error);}
    }
}
