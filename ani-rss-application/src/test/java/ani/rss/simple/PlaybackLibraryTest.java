package ani.rss.simple;

import ani.rss.simple.SimpleModel.*;
import ani.rss.commons.GsonStatic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackLibraryTest {
    @TempDir Path root;
    private final PlaybackLibrary library = new PlaybackLibrary();

    private Settings settings() {
        Settings settings = new Settings();
        settings.setLibraryRoot(root.resolve("resources").toString());
        settings.setPlaybackRoot(root.resolve("playback").toString());
        return settings;
    }
    private Show show() {Show show = new Show(); show.setId("123"); show.setTitle("测试番剧"); show.setFolder("测试番剧 [bgm-123]"); return show;}
    private Release resource(String id, String extension, String content) throws IOException {
        Release release = new Release(); release.setId(id); release.setEpisode(1);
        Path folder = root.resolve("resources/123/" + id); Files.createDirectories(folder);
        Path video = folder.resolve("original." + extension); Files.writeString(video, content);
        release.setVideoPath(video.toString());
        release.setDownloadPath(folder.toString());
        return release;
    }

    @Test void publishesOnlyCompleteVersionAndKeepsOriginalsAcrossExtensionChanges() throws Exception {
        Settings settings=settings(); Show show=show();
        Release old=resource("old","mp4","old video");
        Path subtitle=Path.of(old.getDownloadPath()).resolve("original.chs.ass"); Files.writeString(subtitle,"old subtitle"); old.getSubtitles().add(subtitle.toString());
        List<String> oldFiles=library.publish(settings,show,old);
        Release next=resource("next","mkv","new video");
        List<String> files=library.publish(settings,show,next);
        assertEquals(1,files.size()); assertEquals("new video", Files.readString(Path.of(files.getFirst())));
        for(String path:oldFiles) assertFalse(Files.exists(Path.of(path)));
        assertEquals("old video",Files.readString(Path.of(old.getVideoPath())));
        assertEquals("old subtitle",Files.readString(subtitle));
        library.publish(settings,show,old);
        assertEquals("old video",Files.readString(Path.of(oldFiles.getFirst())));
        assertFalse(Files.exists(Path.of(files.getFirst())));
    }
    @Test void failedUpgradeKeepsCurrentVideo() throws Exception {
        Settings settings=settings(); Show show=show(); Release old=resource("old","mkv","playable");
        Path playing=Path.of(library.publish(settings,show,old).getFirst());
        Release next=resource("new","mp4","new"); next.getSubtitles().add(root.resolve("missing.ass").toString());
        assertThrows(IOException.class,()->library.publish(settings,show,next));
        assertEquals("playable",Files.readString(playing));
    }
    @Test void neverOverwritesAnUnmanagedFile() throws Exception {
        Settings settings=settings(); Show show=show(); Release release=resource("new","mkv","new");
        Path directory=library.showDirectory(settings,show).resolve("Season 01"); Files.createDirectories(directory);
        Path existing=directory.resolve("测试番剧 S01E01.mkv"); Files.writeString(existing,"personal file");
        assertThrows(IOException.class,()->library.publish(settings,show,release));
        assertEquals("personal file",Files.readString(existing));
    }
    @Test void rejectsTraversalAndInvalidSources() throws Exception {
        assertThrows(IOException.class,()->PlaybackLibrary.inside(root,"../outside"));
        assertThrows(IOException.class,()->PlaybackLibrary.inside(root,root.getParent().toString()));
        Release release=resource("new","mkv","new"); Path unrelated=root.resolve("unrelated.mkv"); Files.writeString(unrelated,"private"); release.setVideoPath(unrelated.toString());
        assertThrows(IOException.class,()->library.publish(settings(),show(),release));
    }
    @Test void interruptedSwitchRollsBackFromJournal() throws Exception {
        Path directory=root.resolve("season"); Files.createDirectories(directory);
        Files.writeString(directory.resolve("episode.mkv"),"partial replacement");
        Files.writeString(directory.resolve(".saved.backup"),"old video");
        PlaybackLibrary.Transaction tx=new PlaybackLibrary.Transaction();
        tx.manifestName=".ani-rss-E1.json"; tx.oldManifest="{}"; tx.backups.put("episode.mkv",".saved.backup");
        Path journal=directory.resolve(".ani-rss-E1.transaction.json"); Files.writeString(journal,GsonStatic.toJson(tx));
        library.recover(directory,journal);
        assertEquals("old video",Files.readString(directory.resolve("episode.mkv")));
        assertFalse(Files.exists(journal));
    }
    @Test void normalizesReservedAndUnsafeNames() {
        assertEquals("测试 番剧",PlaybackLibrary.safeName("测试:/番剧..."));
        assertEquals("_CON",PlaybackLibrary.safeName("CON"));
        assertThrows(IllegalArgumentException.class,()->PlaybackLibrary.safeName(".."));
    }

    @Test void languageDetectionUsesTokensAndPreservesUnknown() {
        assertEquals("en",PlaybackLibrary.subtitleLanguage("01.en.srt"));
        assertEquals("zh-Hant",PlaybackLibrary.subtitleLanguage("01.zh-Hant.ass"));
        assertEquals("zh-Hans",PlaybackLibrary.subtitleLanguage("01.chs.ass"));
        assertEquals("und",PlaybackLibrary.subtitleLanguage("watch.ass"));
    }

    @Test void keepsBitmapSubtitlePairsTogether() throws Exception {
        Release release=resource("old","mkv","video");
        for(String extension:List.of("idx","sub")) {Path path=Path.of(release.getDownloadPath()).resolve("original.chs."+extension);Files.writeString(path,"data");release.getSubtitles().add(path.toString());}
        List<String> files=library.publish(settings(),show(),release);
        assertTrue(files.stream().anyMatch(f->f.endsWith("测试番剧 S01E01.zh-Hans.idx")), files.toString());
        assertTrue(files.stream().anyMatch(f->f.endsWith("测试番剧 S01E01.zh-Hans.sub")), files.toString());
    }

    @Test void reconcilesManualSelectionAfterCrashAndCanRestoreAutomaticMode() throws Exception {
        Settings settings=settings(); Show show=show(); Release release=resource("old","mkv","old"); show.getReleases().add(release);
        library.publish(settings,show,release,true);
        library.reconcile(settings,show);
        assertEquals("old",show.getSelected().get("1")); assertTrue(show.getPinnedEpisodes().contains("1"));
        library.clearPin(settings,show,"1"); show.getPinnedEpisodes().clear(); library.reconcile(settings,show);
        assertFalse(show.getPinnedEpisodes().contains("1"));
    }
}
