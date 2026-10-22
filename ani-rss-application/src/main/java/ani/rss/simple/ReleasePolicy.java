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

    public static String episodeKey(double episode) {
        return java.math.BigDecimal.valueOf(episode).stripTrailingZeros().toPlainString();
    }

    /** Pick at most one new task per episode; an existing task prevents duplicate submissions. */
    public static List<Release> plan(Show show, Settings settings) {
        List<String> groups = groups(show, settings);
        Map<String, List<Release>> episodes = new TreeMap<>(Comparator.comparingDouble(Double::parseDouble));
        for (Release release : show.getReleases()) {
            if (allowed(release, groups)) episodes.computeIfAbsent(episodeKey(release.getEpisode()), k -> new ArrayList<>()).add(release);
        }
        List<Release> result = new ArrayList<>();
        episodes.forEach((episode, releases) -> {
            if (show.getPinnedEpisodes().contains(episode)) return;
            if (releases.stream().anyMatch(r -> Set.of("downloading", "submitting").contains(r.getStatus()))) return;
            Release selected = show.getReleases().stream()
                    .filter(r -> Objects.equals(r.getId(), show.getSelected().get(episode))).findFirst().orElse(null);
            int current = selected == null ? Integer.MAX_VALUE : rank(selected, groups);
            Comparator<Release> preferred = Comparator.comparingInt((Release r) -> rank(r, groups))
                    .thenComparingLong(Release::getPublishedAt).thenComparing(Release::getId);
            Comparator<Release> order = selected == null && show.isFastest()
                    ? Comparator.comparingLong(Release::getPublishedAt).thenComparing(preferred) : preferred;
            releases.stream().filter(r -> "available".equals(r.getStatus()) || "error".equals(r.getStatus()))
                    .filter(r -> selected == null || rank(r, groups) < current)
                    .min(order).ifPresent(result::add);
        });
        return result;
    }

    public static Optional<Release> bestReady(Show show, Settings settings, String episode) {
        List<String> groups = groups(show, settings);
        return show.getReleases().stream().filter(r -> "ready".equals(r.getStatus()))
                .filter(r -> episodeKey(r.getEpisode()).equals(episode) && allowed(r, groups))
                .min(Comparator.comparingInt((Release r) -> rank(r, groups)).thenComparingLong(Release::getPublishedAt));
    }
}
