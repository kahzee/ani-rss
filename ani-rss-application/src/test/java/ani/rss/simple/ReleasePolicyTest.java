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

    @Test void startsWithAniThenUpgradesThroughThePreferredGroups() {
        settings.setInitialGroup("ANi");settings.setTrustedGroups(List.of("桜都字幕组","LoliHouse","ANi"));
        Release ani=release("ani","ANi",1,3);Release sakura=release("sakura","桜都字幕组",1,1);release("loli","LoliHouse",1,2);
        assertEquals("ani",ReleasePolicy.plan(show,settings).getFirst().getId());
        ani.setStatus("downloading");assertTrue(ReleasePolicy.plan(show,settings).isEmpty());
        ani.setStatus("ready");show.getSelected().put("1","ani");
        assertEquals("sakura",ReleasePolicy.plan(show,settings).getFirst().getId());
        sakura.setStatus("ready");show.getSelected().put("1","sakura");assertTrue(ReleasePolicy.plan(show,settings).isEmpty());
    }

    @Test void otherGroupsAreFallbacksOnlyWhenListedGroupsAreAbsentAndChineseIsRequired() {
        settings.setAllowOtherChinese(true);settings.setTrustedGroups(List.of("ANi"));
        release("other","其他组",1,1);release("ani","ANi",1,5);release("fallback","其他组",2,1);
        release("raw","其他组",3,1).setChinese(false);
        assertEquals(List.of("ani","fallback"),ReleasePolicy.plan(show,settings).stream().map(Release::getId).toList());
        show.setPreferredGroups(List.of("ANi"));assertEquals(List.of("ani"),ReleasePolicy.plan(show,settings).stream().map(Release::getId).toList());
    }

    @Test void simplifiedWinsWithinTheSameGroupAndCanUpgradeTraditionalWithoutDowngradingGroup() {
        settings.setPreferSimplified(true);settings.setTrustedGroups(List.of("首选","备选"));
        Release traditional=release("traditional","首选",1,1);traditional.setTitle("番 [繁体内嵌]");
        Release simplified=release("simplified","首选",1,2);simplified.setTitle("番 [简体内嵌]");
        Release lower=release("lower","备选",1,0);lower.setTitle("番 [CHS]");
        assertEquals("simplified",ReleasePolicy.plan(show,settings).getFirst().getId());
        traditional.setStatus("ready");show.getSelected().put("1","traditional");
        assertTrue(ReleasePolicy.better(simplified,traditional,show,settings));
        assertFalse(ReleasePolicy.better(lower,traditional,show,settings));
        simplified.setStatus("ready");assertEquals(simplified,ReleasePolicy.bestReady(show,settings,"1").orElseThrow());
    }
}
