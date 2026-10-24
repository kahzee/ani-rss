package ani.rss.simple;
import ani.rss.simple.SimpleModel.Release;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MediaInspectorTest {
    @TempDir Path root;
    private Release release() throws Exception{Path p=root.resolve("video.mp4");Files.writeString(p,"fixture");Release r=new Release();r.setVideoPath(p.toString());return r;}
    @Test void rejectsAudioOnlyOrInvalidDurationAndDoesNotCacheFailure() throws Exception{Release r=release();assertThrows(java.io.IOException.class,()->new MediaInspector(p->"{\"streams\":[{\"codec_type\":\"audio\"}],\"format\":{\"duration\":12}}").inspect(r));assertEquals("",r.getMediaSignature());}
    @Test void distinguishesChineseTrackTagsFromVerifiedSubtitleTextAndRechecksChangedMedia() throws Exception{
        Release r=release();AtomicInteger calls=new AtomicInteger();MediaInspector inspector=new MediaInspector(p->{calls.incrementAndGet();return "{\"streams\":[{\"codec_type\":\"video\",\"width\":1920,\"height\":1080},{\"codec_type\":\"subtitle\",\"tags\":{\"language\":\"zho\"}}],\"format\":{\"duration\":1420}}";});
        inspector.inspect(r);assertTrue(r.getSubtitleEvidence().contains("标记"));inspector.inspect(r);assertEquals(1,calls.get());Files.writeString(Path.of(r.getVideoPath()),"changed size");inspector.inspect(r);assertEquals(2,calls.get());
    }
    @Test void chineseAssHeaderAloneIsNotEvidenceOfChineseDialogue() throws Exception {
        Release r=release();Path sub=root.resolve("sub.ass");Files.writeString(sub,"Title: 中文字幕\nDialogue: 0,00:00:01,00:00:02,Default,,0,0,0,,Hello");r.getSubtitles().add(sub.toString());
        MediaInspector i=new MediaInspector(p->"{\"streams\":[{\"codec_type\":\"video\",\"width\":16,\"height\":16}],\"format\":{\"duration\":4}}");i.inspect(r);assertTrue(r.getSubtitleEvidence().contains("待抽检"));
        r.setMediaSignature("");Files.writeString(sub,"Dialogue: 0,00:00:01,00:00:02,Default,,0,0,0,,你好");i.inspect(r);assertTrue(r.getSubtitleEvidence().contains("正文"));
    }
    @Test void japaneseKanjiMustNotBeClaimedAsVerifiedChinese() throws Exception {
        Release r=release();r.setChinese(true);Path sub=root.resolve("episode.ja.srt");Files.writeString(sub,"1\n00:00:01,000 --> 00:00:02,000\n日本語の字幕です");r.getSubtitles().add(sub.toString());
        MediaInspector inspector=new MediaInspector(p->"{\"streams\":[{\"codec_type\":\"video\",\"width\":16,\"height\":16}],\"format\":{\"duration\":4}}");
        assertThrows(java.io.IOException.class,()->inspector.inspect(r));assertEquals("",r.getMediaSignature());
    }
    @Test void explicitEnglishSubtitleTrackDoesNotSatisfyChineseRequirement() throws Exception {
        Release r=release();r.setChinese(true);
        MediaInspector inspector=new MediaInspector(p->"{\"streams\":[{\"codec_type\":\"video\",\"width\":16,\"height\":16},{\"codec_type\":\"subtitle\",\"tags\":{\"language\":\"eng\"}}],\"format\":{\"duration\":4}}");
        assertThrows(java.io.IOException.class,()->inspector.inspect(r));
    }
}
