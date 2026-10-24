package ani.rss.simple;

import lombok.Data;
import java.util.*;

/** The simple workflow owns its state; upstream subscriptions are left untouched. */
public final class SimpleModel {
    private SimpleModel() {}

    @Data
    public static class Settings {
        private String libraryRoot = "/Media/追番资源";
        private String playbackRoot = "/Media/番剧";
        private boolean watchRootConfirmed;
        // Optional path as seen by a remote/containerized downloader. Local access still uses libraryRoot.
        private String downloaderRoot = "";
        private String downloadHost = "";
        private String username = "admin";
        private String credential = "";
        private boolean credentialSaved;
        // Ordered quality preferences; unlisted groups require explicit Chinese fallback opt-in.
        private List<String> trustedGroups = new ArrayList<>();
        private String initialGroup = "";
        private boolean allowOtherChinese;
        private boolean preferSimplified;
        private int simultaneousDownloads = 3;

        public Settings() {
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
                String downloads = java.nio.file.Path.of(System.getProperty("user.home"), "Downloads").toString();
                libraryRoot = java.nio.file.Path.of(downloads, "追番资源").toString();
                playbackRoot = java.nio.file.Path.of(downloads, "番剧").toString();
            }
        }
    }

    @Data
    public static class State {
        private Settings settings = new Settings();
        private List<Show> shows = new ArrayList<>();
        private long downloadCheckedAt;
        private Map<String,DownloadTask> downloads = new LinkedHashMap<>();
    }

    @Data
    public static class Show {
        private String id;
        private String title;
        private String image = "";
        private String folder;
        private int season = 1;
        private int totalEpisodes;
        private boolean enabled = true;
        private boolean fastest;
        private List<String> preferredGroups = new ArrayList<>();
        private List<Release> releases = new ArrayList<>();
        private Map<String, String> selected = new LinkedHashMap<>();
        // A manual selection stays pinned until explicitly returned to automatic mode.
        private Set<String> pinnedEpisodes = new HashSet<>();
        private long lastCatalogAt;
        private long lastQueuedAt;
        private Map<String, Double> episodeNumbers = new LinkedHashMap<>();
        private Map<String, String> episodeIds = new LinkedHashMap<>();
        private long episodeMetadataAt;
        private String catalogIssue = "";
        private List<String> missingEpisodes = new ArrayList<>();
        private long nextCatalogAt;
        private int unchangedCatalogs;
        private boolean historical;
        private String mikanId = "";
        private long mikanCheckedAt;
        private long mikanRetryAt;
        private String mikanIssue = "";
        private String message = "等待检查资源";
        // Filled on public snapshots, one planned resource per episode, not every alternative.
        private List<String> queuedReleaseIds = new ArrayList<>();
    }

    @Data
    public static class Release {
        private String id;
        private String title;
        private String group;
        private boolean batch;
        private String torrentHash = "";
        private String sourceTitle = "";
        private String episodeId = "";
        private int fileIndex = -1;
        private List<Integer> subtitleIndices = new ArrayList<>();
        private boolean requested;
        private double episode;
        private Double sourceEpisode;
        private String numberingIssue = "";
        private String subtitleEvidence = "尚未检查媒体";
        private String mediaSignature = "";
        private String magnet;
        private String torrentUrl;
        private long publishedAt;
        private boolean chinese;
        private String status = "available";
        private String downloadPath;
        private String videoPath;
        private List<String> subtitles = new ArrayList<>();
        private double progress;
        private long downloadSpeed;
        private long taskDownloadSpeed;
        private long downloadedBytes;
        private long totalBytes;
        private long etaSeconds = -1;
        private int seeds;
        private String downloadState = "";
        private long lastProgressAt;
        private long retryAt;
        private int failures;
        private String error = "";
        // Snapshot of the paths installed into the playback directory, for recovery.
        private List<String> publishedFiles = new ArrayList<>();
    }

    @Data public static class DownloadTask {
        private String hash;
        private String path;
        private Set<Integer> requestedFiles = new LinkedHashSet<>();
        private List<MediaFile> files = new ArrayList<>();
        private long lastProgressAt;
        private double progress = -1;
        private long retryAt;
        private int failures;
    }
    @Data public static class MediaFile {
        private int index;
        private String name;
        private long size;
        private double progress;
    }

    @Data
    public static class FollowRequest {
        private String subjectId;
    }

    @Data
    public static class PreferenceRequest {
        private String showId;
        private List<String> groups = new ArrayList<>();
        private boolean fastest;
        private boolean enabled = true;
    }

    @Data
    public static class VersionRequest {
        private String showId;
        private String releaseId;
    }

    @Data
    public static class AutoRequest {
        private String showId;
        private String episode;
    }
}
