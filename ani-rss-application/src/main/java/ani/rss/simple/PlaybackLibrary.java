package ani.rss.simple;

import ani.rss.commons.GsonStatic;
import ani.rss.simple.SimpleModel.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Copies preserve originals and seeding, including when library and playback use different disks. */
public class PlaybackLibrary {
    public static final Set<String> VIDEO = Set.of("mkv", "mp4", "avi", "mov", "ts", "m2ts", "webm");
    public static final Set<String> SUBTITLE = Set.of("ass", "ssa", "srt", "vtt", "sup", "sub", "idx");

    public static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static String safeName(String value) {
        String safe = value.replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", " ").replaceAll("\\s+", " ").trim().replaceAll("[. ]+$", "");
        if (safe.isBlank() || safe.equals(".") || safe.equals("..")) throw new IllegalArgumentException("番剧名称为空或不可用于文件夹");
        if (safe.codePointCount(0, safe.length()) > 100) safe = safe.substring(0, safe.offsetByCodePoints(0, 100)).replaceAll("[. ]+$", "");
        if (safe.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) safe = "_" + safe;
        return safe;
    }

    public static Path inside(Path root, String relative) throws IOException {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path path = absoluteRoot.resolve(relative).normalize();
        if (!path.startsWith(absoluteRoot) || path.equals(absoluteRoot)) throw new IOException("文件路径超出管理目录");
        // Do not follow symlinks/junctions from downloader paths into unrelated directories.
        Path cursor = path;
        while (cursor != null && cursor.startsWith(absoluteRoot)) {
            if (Files.isSymbolicLink(cursor)) throw new IOException("管理目录中不允许符号链接");
            cursor = cursor.getParent();
        }
        if (Files.exists(path) && Files.exists(absoluteRoot) && !path.toRealPath().startsWith(absoluteRoot.toRealPath()))
            throw new IOException("真实文件路径超出管理目录");
        return path;
    }

    public Path showDirectory(Settings settings, Show show) throws IOException {
        return inside(Path.of(settings.getPlaybackRoot()), show.getFolder());
    }

    public String baseName(Show show, double episode) {
        String ep = episode == Math.rint(episode) ? String.format(Locale.ROOT, "%02d", (int) episode) : ReleasePolicy.episodeKey(episode);
        return safeName(show.getTitle()) + String.format(Locale.ROOT, " S%02dE%s", show.getSeason(), ep);
    }

    public List<String> publish(Settings settings, Show show, Release release) throws IOException {
        return publish(settings, show, release, false);
    }

    public List<String> publish(Settings settings, Show show, Release release, boolean manual) throws IOException {
        Path season = inside(showDirectory(settings, show), String.format(Locale.ROOT, "Season %02d", show.getSeason()));
        Files.createDirectories(season);
        String episode = ReleasePolicy.episodeKey(release.getEpisode());
        Path manifest = season.resolve(".ani-rss-E" + episode + ".json");
        Path journal = season.resolve(".ani-rss-E" + episode + ".transaction.json");
        recover(season, journal);
        Manifest previous = Files.exists(manifest) ? GsonStatic.fromJson(Files.readString(manifest), Manifest.class) : new Manifest();
        LinkedHashMap<String, Path> inputs = new LinkedHashMap<>();
        Path sourceRoot = inside(Path.of(settings.getLibraryRoot()), show.getId() + "/" + release.getId());
        Path video = checkedSource(sourceRoot, release.getVideoPath());
        String base = baseName(show, release.getEpisode());
        inputs.put(base + "." + extension(video), video);
        Map<String, String> subtitleNames = new HashMap<>();
        Map<String, Integer> languageCounts = new HashMap<>();
        for (String subtitle : release.getSubtitles()) {
            Path source = checkedSource(sourceRoot, subtitle);
            // Preserve the original language suffix when present, with an index to avoid collisions.
            String language = subtitleLanguage(source.getFileName().toString());
            String original = source.toString().substring(0, source.toString().lastIndexOf('.'));
            // Paired .idx/.sub files must share a basename. Multiple language tracks stay distinct.
            String subtitleBase = subtitleNames.computeIfAbsent(original, ignored -> {
                int number = languageCounts.merge(language, 1, Integer::sum);
                return base + "." + language + (number == 1 ? "" : "." + number);
            });
            inputs.put(subtitleBase + "." + extension(source), source);
        }
        Transaction tx = new Transaction();
        tx.oldManifest = Files.exists(manifest) ? Files.readString(manifest) : null;
        tx.manifestName = manifest.getFileName().toString();
        String token = UUID.randomUUID().toString();
        // Backups and staging have non-media extensions so scanners don't index them.
        try {
            for (Map.Entry<String, Path> entry : inputs.entrySet()) {
                Path target = inside(season, entry.getKey());
                if (Files.exists(target) && !previous.files.contains(entry.getKey()))
                    throw new IOException("正式目录存在非本程序管理的同名文件，已保留: " + entry.getKey());
                String stage = ".ani-rss-" + token + "-" + tx.staged.size() + ".pending";
                Files.copy(entry.getValue(), season.resolve(stage));
                tx.staged.put(entry.getKey(), stage);
            }
            Set<String> affected = new LinkedHashSet<>(previous.files);
            affected.addAll(inputs.keySet());
            for (String name : affected) {
                Path target = inside(season, name);
                String backup = ".ani-rss-" + token + "-" + tx.backups.size() + ".backup";
                if (Files.exists(target)) {
                    Files.copy(target, season.resolve(backup));
                    tx.backups.put(name, backup);
                } else tx.backups.put(name, "");
            }
            atomicWrite(journal, GsonStatic.toJson(tx));
            for (Map.Entry<String, String> entry : tx.staged.entrySet())
                replace(season.resolve(entry.getValue()), inside(season, entry.getKey()));
            for (String obsolete : previous.files)
                if (!inputs.containsKey(obsolete)) Files.deleteIfExists(inside(season, obsolete));
            Manifest installed = new Manifest();
            installed.releaseId = release.getId();
            installed.manual = manual;
            installed.files = new ArrayList<>(inputs.keySet());
            atomicWrite(manifest, GsonStatic.toJson(installed));
            // Journal deletion commits the transaction. Recovery before this point restores the old version.
            Files.delete(journal);
            cleanup(season, tx);
            return installed.files.stream().map(name -> season.resolve(name).toString()).toList();
        } catch (IOException | RuntimeException e) {
            if (Files.exists(journal)) recover(season, journal);
            else cleanup(season, tx);
            throw e;
        }
    }

    static String subtitleLanguage(String name) {
        if (java.util.regex.Pattern.compile("(?i)(?:^|[.\\[ _-])(?:zh[-_]hant|cht|tc|big5)(?:[.\\] _-]|$)|繁[体體中日]").matcher(name).find()) return "zh-Hant";
        if (java.util.regex.Pattern.compile("(?i)(?:^|[.\\[ _-])(?:zh[-_]hans|chs|sc|gb)(?:[.\\] _-]|$)|[简簡][体體中日]").matcher(name).find()) return "zh-Hans";
        if (java.util.regex.Pattern.compile("(?i)(?:^|[.\\[ _-])(?:en|eng)(?:[.\\] _-]|$)").matcher(name).find()) return "en";
        if (java.util.regex.Pattern.compile("(?i)(?:^|[.\\[ _-])(?:ja|jpn)(?:[.\\] _-]|$)").matcher(name).find()) return "ja";
        return "und";
    }

    /** Reconcile committed playback manifests after a crash between file installation and state save. */
    public void reconcile(Settings settings, Show show) throws IOException {
        Path season = inside(showDirectory(settings, show), String.format(Locale.ROOT, "Season %02d", show.getSeason()));
        if (!Files.isDirectory(season)) return;
        try (DirectoryStream<Path> journals = Files.newDirectoryStream(season, ".ani-rss-E*.transaction.json")) {
            for (Path journal : journals) recover(season, journal);
        }
        try (DirectoryStream<Path> manifests = Files.newDirectoryStream(season, ".ani-rss-E*.json")) {
            for (Path manifest : manifests) {
                String name = manifest.getFileName().toString();
                if (!name.matches("\\.ani-rss-E\\d+(?:\\.5)?\\.json")) continue;
                Manifest installed = GsonStatic.fromJson(Files.readString(manifest), Manifest.class);
                Release release = show.getReleases().stream().filter(r -> Objects.equals(r.getId(), installed.releaseId)).findFirst().orElse(null);
                if (release == null) continue;
                boolean complete = !installed.files.isEmpty();
                for (String file : installed.files) if (!Files.isRegularFile(inside(season, file))) complete = false;
                String episode = ReleasePolicy.episodeKey(release.getEpisode());
                if (complete) {
                    show.getSelected().put(episode, release.getId());
                    if (installed.manual) show.getPinnedEpisodes().add(episode);
                    else show.getPinnedEpisodes().remove(episode);
                    release.setPublishedFiles(installed.files.stream().map(f -> season.resolve(f).toString()).toList());
                } else {
                    // Leave the originals alone; a later ready-resource selection will repair the playback copy.
                    show.getSelected().remove(episode);
                    show.getPinnedEpisodes().remove(episode);
                }
            }
        }
    }

    public void clearPin(Settings settings, Show show, String episode) throws IOException {
        if (!episode.matches("\\d+(?:\\.5)?")) throw new IllegalArgumentException("无效集数");
        Path season = inside(showDirectory(settings, show), String.format(Locale.ROOT, "Season %02d", show.getSeason()));
        Path file = season.resolve(".ani-rss-E" + episode + ".json");
        if (Files.exists(file)) {
            Manifest manifest = GsonStatic.fromJson(Files.readString(file), Manifest.class);
            manifest.manual = false;
            atomicWrite(file, GsonStatic.toJson(manifest));
        }
    }

    private Path checkedSource(Path root, String value) throws IOException {
        if (value == null) throw new IOException("资源尚未下载完整");
        Path source = Path.of(value).toAbsolutePath().normalize();
        Path checked = inside(root, root.toAbsolutePath().normalize().relativize(source).toString());
        if (!Files.isRegularFile(checked) || Files.size(checked) == 0) throw new IOException("资源文件不存在或为空");
        return checked;
    }

    void recover(Path directory, Path journal) throws IOException {
        if (!Files.exists(journal)) return;
        Transaction tx = GsonStatic.fromJson(Files.readString(journal), Transaction.class);
        for (Map.Entry<String, String> entry : tx.backups.entrySet()) {
            Path target = inside(directory, entry.getKey());
            if (entry.getValue().isEmpty()) Files.deleteIfExists(target);
            else Files.copy(inside(directory, entry.getValue()), target, StandardCopyOption.REPLACE_EXISTING);
        }
        Path manifest = inside(directory, tx.manifestName);
        if (tx.oldManifest == null) Files.deleteIfExists(manifest);
        else atomicWrite(manifest, tx.oldManifest);
        Files.delete(journal);
        cleanup(directory, tx);
    }

    private void cleanup(Path directory, Transaction tx) throws IOException {
        for (String name : tx.staged.values()) Files.deleteIfExists(inside(directory, name));
        for (String name : tx.backups.values()) if (!name.isEmpty()) Files.deleteIfExists(inside(directory, name));
    }

    static void atomicWrite(Path file, String text) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, text);
        replace(temp, file);
    }

    private static void replace(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }

    static class Manifest { String releaseId; boolean manual; List<String> files = new ArrayList<>(); }
    static class Transaction {
        String oldManifest;
        String manifestName;
        Map<String, String> staged = new LinkedHashMap<>();
        Map<String, String> backups = new LinkedHashMap<>();
    }
}
