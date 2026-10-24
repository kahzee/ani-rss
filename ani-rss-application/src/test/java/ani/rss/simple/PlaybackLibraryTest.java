package ani.rss.simple;

import ani.rss.simple.SimpleModel.*;
import ani.rss.commons.GsonStatic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackLibraryTest {
    @TempDir Path root;
    private PlaybackLibrary library;
    @BeforeEach void setup() { library=new PlaybackLibrary(root.resolve("metadata")); }

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
    @Test void newSeasonDirectoryIdentifiesShowWhileExistingLinksStayStable() throws Exception {
        Settings settings=settings(); Show show=show();
        assertEquals("测试番剧 第01季", library.seasonDirectory(settings,show).getFileName().toString());
        Path legacy=library.showDirectory(settings,show).resolve("Season 01"); Files.createDirectories(legacy);
        assertEquals(legacy, library.seasonDirectory(settings,show));
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
        assertTrue(files.stream().anyMatch(f->f.endsWith("测试番剧 S01E01.chs.idx")), files.toString());
        assertTrue(files.stream().anyMatch(f->f.endsWith("测试番剧 S01E01.chs.sub")), files.toString());
    }

    @Test void reconcilesManualSelectionAfterCrashAndCanRestoreAutomaticMode() throws Exception {
        Settings settings=settings(); Show show=show(); Release release=resource("old","mkv","old"); show.getReleases().add(release);
        library.publish(settings,show,release,true);
        library.reconcile(settings,show);
        assertEquals("old",show.getSelected().get("1")); assertTrue(show.getPinnedEpisodes().contains("1"));
        library.clearPin(settings,show,"1"); show.getPinnedEpisodes().clear(); library.reconcile(settings,show);
        assertFalse(show.getPinnedEpisodes().contains("1"));
    }

    @Test void manifestsStayOutsideMediaAndSurviveUpgradeAcrossRestart() throws Exception {
        Settings settings=settings();Show show=show();Release old=resource("old","mp4","old");show.getReleases().add(old);
        old.setPublishedFiles(library.publish(settings,show,old));show.getSelected().put("1","old");
        assertFalse(Files.exists(library.seasonDirectory(settings,show).resolve(".ani-rss-E1.json")));
        assertTrue(Files.exists(library.metadataDirectory(settings,show).resolve(".ani-rss-E1.json")));
        PlaybackLibrary restarted=new PlaybackLibrary(root.resolve("metadata"));restarted.reconcile(settings,show);
        Release next=resource("new","mkv","new");restarted.publish(settings,show,next);
        assertFalse(Files.exists(Path.of(old.getPublishedFiles().getFirst())));
        assertEquals("old",Files.readString(Path.of(old.getVideoPath())));
    }

    @Test void lostNasManifestsRemoveOnlyVerifiedObsoletePlaybackCopies() throws Exception {
        Settings settings=settings();Show show=show();Release old=resource("old","mp4","old"),next=resource("new","mkv","new");show.getReleases().addAll(List.of(old,next));
        old.setPublishedFiles(library.publish(settings,show,old));next.setPublishedFiles(library.publish(settings,show,next));show.getSelected().put("1","new");
        Path leftover=Path.of(old.getPublishedFiles().getFirst());Files.copy(Path.of(old.getVideoPath()),leftover);
        Path unrelated=leftover.resolveSibling("测试番剧 S01E01.avi");Files.writeString(unrelated,"personal file");
        PlaybackLibrary restored=new PlaybackLibrary(root.resolve("fresh-metadata"));restored.reconcile(settings,show);
        assertFalse(Files.exists(leftover));assertTrue(Files.exists(unrelated));
        assertEquals("new",Files.readString(Path.of(next.getPublishedFiles().getFirst())));assertEquals(1,next.getPublishedFiles().size());
        assertTrue(Files.exists(Path.of(old.getVideoPath())));
    }

    @Test void missingManifestNeverAdoptsChangedOrUnrelatedFiles() throws Exception {
        Settings settings=settings();Show show=show();Release release=resource("old","mp4","original");show.getReleases().add(release);
        release.setPublishedFiles(library.publish(settings,show,release));show.getSelected().put("1","old");Path playing=Path.of(release.getPublishedFiles().getFirst());Files.writeString(playing,"personal replacement");
        PlaybackLibrary restored=new PlaybackLibrary(root.resolve("fresh-metadata"));
        assertThrows(IOException.class,()->restored.reconcile(settings,show));assertEquals("personal replacement",Files.readString(playing));
    }

    @Test void canonicalizesDuplicateNumbersPreservingOriginalsAndRestartState() throws Exception {
        Settings settings=settings();settings.setTrustedGroups(List.of("桜都字幕组","ANi"));Show show=show();show.setSeason(3);
        Release absolute=resource("absolute","mp4","ani original"),local=resource("local","mkv","preferred original");
        absolute.setEpisode(25);absolute.setGroup("ANi");local.setGroup("桜都字幕组");
        show.getReleases().addAll(List.of(absolute,local));
        absolute.setPublishedFiles(library.publish(settings,show,absolute));local.setPublishedFiles(library.publish(settings,show,local));
        Path obsolete=Path.of(absolute.getPublishedFiles().getFirst());
        show.getSelected().put("25",absolute.getId());show.getSelected().put("1",local.getId());
        show.getEpisodeNumbers().putAll(Map.of("25",1d,"1",1d));EpisodeNumbers.apply(show);
        // Simulate restart after the merged manifest committed but before publication began.
        Path canonical=library.metadataDirectory(settings,show).resolve(".ani-rss-E1.json");
        PlaybackLibrary.Manifest merged=GsonStatic.fromJson(Files.readString(canonical),PlaybackLibrary.Manifest.class);
        merged.files.add(obsolete.getFileName().toString());Files.writeString(canonical,GsonStatic.toJson(merged));
        library.reconcile(settings,show);
        assertEquals(Map.of("1","local"),show.getSelected());assertFalse(Files.exists(obsolete));
        assertEquals("preferred original",Files.readString(Path.of(local.getPublishedFiles().getFirst())));
        assertEquals("ani original",Files.readString(Path.of(absolute.getVideoPath())));
        assertEquals("preferred original",Files.readString(Path.of(local.getVideoPath())));
        new PlaybackLibrary(root.resolve("metadata")).reconcile(settings,show);
        assertEquals(Map.of("1","local"),show.getSelected());assertFalse(Files.exists(obsolete));
        try(var files=Files.list(library.metadataDirectory(settings,show))) {assertEquals(1,files.count());}
    }

    @Test void canonicalizationRefusesToRemoveModifiedPlaybackCopy() throws Exception {
        Settings settings=settings();Show show=show();show.setSeason(3);Release absolute=resource("absolute","mp4","original");
        absolute.setEpisode(25);show.getReleases().add(absolute);absolute.setPublishedFiles(library.publish(settings,show,absolute));
        Path playing=Path.of(absolute.getPublishedFiles().getFirst());Files.writeString(playing,"personal replacement");
        show.getEpisodeNumbers().put("25",1d);EpisodeNumbers.apply(show);
        assertThrows(IOException.class,()->library.reconcile(settings,show));
        assertEquals("personal replacement",Files.readString(playing));assertEquals("original",Files.readString(Path.of(absolute.getVideoPath())));
    }

    @Test void canonicalizationKeepsManualChoiceAndRejectsConflictingManualChoices() throws Exception {
        Settings settings=settings();settings.setTrustedGroups(List.of("桜都字幕组","ANi"));Show show=show();show.setSeason(2);
        Release absolute=resource("absolute","mp4","manual"),local=resource("local","mkv","preferred");
        absolute.setEpisode(13);absolute.setGroup("ANi");local.setGroup("桜都字幕组");show.getReleases().addAll(List.of(absolute,local));
        absolute.setPublishedFiles(library.publish(settings,show,absolute,true));local.setPublishedFiles(library.publish(settings,show,local,true));
        show.getEpisodeNumbers().putAll(Map.of("13",1d,"1",1d));EpisodeNumbers.apply(show);
        assertThrows(IOException.class,()->library.reconcile(settings,show));
        assertTrue(Files.exists(Path.of(absolute.getPublishedFiles().getFirst())));assertTrue(Files.exists(Path.of(local.getPublishedFiles().getFirst())));
        Path canonical=library.metadataDirectory(settings,show).resolve(".ani-rss-E1.json");
        PlaybackLibrary.Manifest m=GsonStatic.fromJson(Files.readString(canonical),PlaybackLibrary.Manifest.class);m.manual=false;
        Files.writeString(canonical,GsonStatic.toJson(m));library.reconcile(settings,show);
        assertEquals("absolute",show.getSelected().get("1"));assertTrue(show.getPinnedEpisodes().contains("1"));
    }

    @Test void interruptedStagingRecoversWhenPlannedBackupsHaveNotMovedYet() throws Exception {
        Path directory=root.resolve("season");Files.createDirectories(directory);Files.writeString(directory.resolve("old.mkv"),"old");
        Files.writeString(directory.resolve("partial.pending"),"partial");
        PlaybackLibrary.Transaction tx=new PlaybackLibrary.Transaction();tx.manifestName=".ani-rss-E1.json";
        tx.backups.put("old.mkv","old.backup");tx.backups.put("new.mp4","");tx.staged.put("new.mp4","partial.pending");
        Path journal=directory.resolve(".ani-rss-E1.transaction.json");Files.writeString(journal,GsonStatic.toJson(tx));
        library.recover(directory,journal);assertEquals("old",Files.readString(directory.resolve("old.mkv")));
        assertFalse(Files.exists(directory.resolve("partial.pending")));assertFalse(Files.exists(journal));
    }

    @Test void recoveryRestoresAlreadyRenamedBackupBeforeAnyNewInstall() throws Exception {
        Path directory=root.resolve("season");Files.createDirectories(directory);Files.writeString(directory.resolve("old.backup"),"old");
        PlaybackLibrary.Transaction tx=new PlaybackLibrary.Transaction();tx.manifestName=".ani-rss-E1.json";tx.oldManifest="{}";tx.backups.put("old.mkv","old.backup");
        Path journal=directory.resolve(".ani-rss-E1.transaction.json");Files.writeString(journal,GsonStatic.toJson(tx));
        library.recover(directory,journal);assertEquals("old",Files.readString(directory.resolve("old.mkv")));assertFalse(Files.exists(journal));
    }

    @Test void missingBackupAndTargetStopRecoveryWithoutDiscardingJournal() throws Exception {
        Path directory=root.resolve("season");Files.createDirectories(directory);
        PlaybackLibrary.Transaction tx=new PlaybackLibrary.Transaction();tx.manifestName=".ani-rss-E1.json";tx.backups.put("old.mkv","old.backup");
        Path journal=directory.resolve(".ani-rss-E1.transaction.json");Files.writeString(journal,GsonStatic.toJson(tx));
        assertThrows(IOException.class,()->library.recover(directory,journal));assertTrue(Files.exists(journal));
    }
}
