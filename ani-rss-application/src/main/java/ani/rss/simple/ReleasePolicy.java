package ani.rss.simple;

import ani.rss.simple.SimpleModel.*;
import java.util.*;
import java.util.regex.Pattern;

public final class ReleasePolicy {
    private ReleasePolicy() {}
    private static final Pattern CHINESE = Pattern.compile(
            "简体|簡體|繁体|繁體|简繁|簡繁|简日|簡日|繁日|简中|簡中|繁中|中日|中文字幕|中文内嵌|中文內嵌|(?i)(?<![a-z])(?:zh[-_]hans|zh[-_]hant|chs|cht|chinese|big5)(?![a-z])|(?i)[\\[【(](?:GB|SC|TC)(?:[^a-z0-9]|$)");
    private static final Pattern RAW = Pattern.compile("(?i)(?<![a-z])raw(?![a-z])|无字幕|無字幕|无中文|無中文");

    public static boolean hasChinese(String title) {
        return title != null && CHINESE.matcher(title).find() && !RAW.matcher(title).find();
    }

    public static List<String> groups(Show show, Settings settings) {
        return show.getPreferredGroups().isEmpty() ? settings.getTrustedGroups() : show.getPreferredGroups();
    }

    public static int rank(Release release, List<String> groups) {
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).equalsIgnoreCase(release.getGroup())) return i;
        }
        return Integer.MAX_VALUE;
    }

    public static boolean allowed(Release release, List<String> groups) {
        return release.isChinese() && rank(release, groups) != Integer.MAX_VALUE;
    }

    public static boolean allowed(Release release, Show show, Settings settings) {
        return release.isChinese() && (rank(release, groups(show,settings)) != Integer.MAX_VALUE
                || settings.isAllowOtherChinese() && show.getPreferredGroups().isEmpty());
    }

    private static int languageRank(Release release, Settings settings) {
        if (!settings.isPreferSimplified()) return 0;
        String title=Objects.toString(release.getTitle(), "");
        if (Pattern.compile("简|簡|(?i)(?<![a-z])(?:chs|zh[-_]hans)(?![a-z])|(?i)[\\[【(](?:GB|SC)(?:[^a-z0-9]|$)").matcher(title).find()) return 0;
        return 1;
    }

    private static Comparator<Release> quality(Show show, Settings settings) {
        return Comparator.comparingInt((Release r)->rank(r,groups(show,settings)))
                .thenComparingInt(r->languageRank(r,settings));
    }

    public static boolean better(Release candidate, Release current, Show show, Settings settings) {
        return current==null || quality(show,settings).compare(candidate,current)<0;
    }

    public static String episodeKey(double episode) {
        return java.math.BigDecimal.valueOf(episode).stripTrailingZeros().toPlainString();
    }

    /** Pick at most one new task per episode; an existing task prevents duplicate submissions. */
    public static List<Release> plan(Show show, Settings settings) {
        List<String> groups = groups(show, settings);
        Map<String, List<Release>> episodes = new TreeMap<>(Comparator.comparingDouble(Double::parseDouble));
        for (Release release : show.getReleases()) {
            if (allowed(release, show, settings)) episodes.computeIfAbsent(episodeKey(release.getEpisode()), k -> new ArrayList<>()).add(release);
        }
        List<Release> result = new ArrayList<>();
        episodes.forEach((episode, releases) -> {
            if (show.getPinnedEpisodes().contains(episode)) return;
            if (releases.stream().anyMatch(r -> Set.of("downloading", "submitting").contains(r.getStatus()))) return;
            Release selected = show.getReleases().stream()
                    .filter(r -> Objects.equals(r.getId(), show.getSelected().get(episode))).findFirst().orElse(null);
            Comparator<Release> preferred = quality(show,settings)
                    .thenComparingLong(Release::getPublishedAt).thenComparing(Release::getId);
            Comparator<Release> order = selected == null && show.isFastest()
                    ? Comparator.comparingLong(Release::getPublishedAt).thenComparing(preferred) : preferred;
            String initial=settings.getInitialGroup();
            if (selected==null && initial!=null && !initial.isBlank())
                order=Comparator.comparingInt((Release r)->initial.equalsIgnoreCase(r.getGroup())?0:1).thenComparing(preferred);
            releases.stream().filter(r -> Set.of("available", "error", "waiting").contains(r.getStatus()))
                    .filter(r -> r.getRetryAt() <= System.currentTimeMillis())
                    .filter(r -> better(r,selected,show,settings))
                    .min(order).ifPresent(result::add);
        });
        return result;
    }

    public static Optional<Release> bestReady(Show show, Settings settings, String episode) {
        List<String> groups = groups(show, settings);
        return show.getReleases().stream().filter(r -> "ready".equals(r.getStatus()))
                .filter(r -> episodeKey(r.getEpisode()).equals(episode) && allowed(r, show, settings))
                .min(quality(show,settings).thenComparingLong(Release::getPublishedAt).thenComparing(Release::getId));
    }
}
