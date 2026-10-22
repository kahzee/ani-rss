package ani.rss.simple;

import ani.rss.simple.SimpleModel.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReleasePolicyTest {
    private final Settings settings = new Settings();
    private final Show show = new Show();
    ReleasePolicyTest() {settings.setTrustedGroups(List.of("首选", "备选"));}

    private Release release(String id, String group, int episode, long published) {
        Release release = new Release();
        release.setId(id); release.setGroup(group); release.setEpisode(episode);
        release.setChinese(true); release.setPublishedAt(published);
        show.getReleases().add(release);
        return release;
    }

    @Test void backfillsEveryEpisodeButOnlyOneVersion() {
        release("a", "备选", 1, 1); release("b", "首选", 1, 2); release("c", "备选", 2, 3);
        assertEquals(List.of("b", "c"), ReleasePolicy.plan(show, settings).stream().map(Release::getId).toList());
    }
    @Test void fastestOnlyWithinTrustedChineseResources() {
        show.setFastest(true);
        release("a", "备选", 1, 3); release("b", "首选", 1, 4); release("unknown", "陌生", 1, 1);
        release("raw", "首选", 1, 2).setChinese(false);
        assertEquals("a", ReleasePolicy.plan(show, settings).getFirst().getId());
    }
    @Test void emptyTrustNeverDownloadsAnything() {
        release("a", "首选", 1, 1); settings.setTrustedGroups(List.of());
        assertTrue(ReleasePolicy.plan(show, settings).isEmpty());
    }
    @Test void upgradesButNeverDowngradesOrDuplicates() {
        Release old = release("old", "备选", 1, 1); old.setStatus("ready"); show.getSelected().put("1", "old");
        Release preferred = release("new", "首选", 1, 2);
        assertEquals(List.of(preferred), ReleasePolicy.plan(show, settings));
        preferred.setStatus("downloading");
        assertTrue(ReleasePolicy.plan(show, settings).isEmpty());
        preferred.setStatus("ready"); show.getSelected().put("1", "new");
        assertTrue(ReleasePolicy.plan(show, settings).isEmpty());
    }
    @Test void manualPinStopsAutomaticReplacement() {
        release("a", "首选", 1, 1); show.getPinnedEpisodes().add("1");
        assertTrue(ReleasePolicy.plan(show, settings).isEmpty());
    }
    @Test void unfinishedPreferredVersionIsNeverPublished() {
        Release old = release("old", "备选", 1, 1); old.setStatus("ready");
        release("new", "首选", 1, 2).setStatus("downloading");
        assertEquals(old, ReleasePolicy.bestReady(show, settings, "1").orElseThrow());
    }
    @Test void recognizesChineseMarkersWithoutAssumingChineseTitlesMeanChineseSubtitles() {
        for (String title : List.of("[组] 动画 - 01 [CHS]", "[简繁内封]", "[繁日双语]", "[BIG5]", "[zh-Hans]", "[GB_MP4]"))
            assertTrue(ReleasePolicy.hasChinese(title), title);
        for (String title : List.of("[组] 中文番名 - 01 [1080p]", "[RAW][CHS]", "[English]", "无中文字幕", "[SCARY]", "[10 GB]", "英日双语"))
            assertFalse(ReleasePolicy.hasChinese(title), title);
    }
}
