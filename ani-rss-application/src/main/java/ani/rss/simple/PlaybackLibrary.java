package ani.rss.simple;

import ani.rss.commons.GsonStatic;
import ani.rss.simple.SimpleModel.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Copies preserve originals and seeding, including when library and playback use different disks. */
public class PlaybackLibrary {
    private final Path stateRoot;
    public PlaybackLibrary(Path stateRoot) { this.stateRoot=stateRoot; }
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
    static Path resourceDirectory(Settings settings,Show show,Release release) throws IOException {
        if(release.isBatch() || !release.getTorrentHash().isBlank()) {
            String hash=SimpleDownloader.hash(release);if(!hash.matches("[a-f0-9]{40}"))throw new IOException("无效的共享种子标识");
            return inside(Path.of(settings.getLibraryRoot()),"_torrents/"+hash);
        }
        return inside(Path.of(settings.getLibraryRoot()),show.getId()+"/"+release.getId());
    }

    /** Keep existing linked folders stable; new folders identify the show to Zspace scanners. */
    public Path seasonDirectory(Settings settings, Show show) throws IOException {
        Path parent = showDirectory(settings, show);
        Path legacy = inside(parent, String.format(Locale.ROOT, "Season %02d", show.getSeason()));
        if (Files.isDirectory(legacy)) return legacy;
        return inside(parent, safeName(show.getTitle()) + String.format(Locale.ROOT, " 第%02d季", show.getSeason()));
    }

    Path metadataDirectory(Settings settings, Show show) throws IOException {
        String scope=UUID.nameUUIDFromBytes(settings.getPlaybackRoot().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        Path directory=inside(stateRoot,scope+"/"+show.getId()+"/season-"+show.getSeason());
        Files.createDirectories(directory);
        return directory;
    }

    public String baseName(Show show, double episode) {
        String ep = episode == Math.rint(episode) ? String.format(Locale.ROOT, "%02d", (int) episode) : ReleasePolicy.episodeKey(episode);
        return safeName(show.getTitle()) + String.format(Locale.ROOT, " S%02dE%s", show.getSeason(), ep);
    }

    public List<String> publish(Settings settings, Show show, Release release) throws IOException {
        return publish(settings, show, release, false);
    }

    public List<String> publish(Settings settings, Show show, Release release, boolean manual) throws IOException {
        Path season = seasonDirectory(settings, show);
        Files.createDirectories(season);
        String episode = ReleasePolicy.episodeKey(release.getEpisode());
        Path metadata=metadataDirectory(settings,show);
        Path manifest = metadata.resolve(".ani-rss-E" + episode + ".json");
        Path journal = metadata.resolve(".ani-rss-E" + episode + ".transaction.json");
        recover(season, journal);
        Manifest previous = Files.exists(manifest) ? GsonStatic.fromJson(Files.readString(manifest), Manifest.class) : new Manifest();
        LinkedHashMap<String, Path> inputs = new LinkedHashMap<>();
        Path sourceRoot = resourceDirectory(settings,show,release);
        Path video = checkedSource(sourceRoot, release.getVideoPath());
        String base = baseName(show, release.getEpisode());
        inputs.put(base + "." + extension(video), video);
        Map<String, String> subtitleNames = new HashMap<>();
        Map<String, Integer> languageCounts = new HashMap<>();
        for (String subtitle : release.getSubtitles()) {
            Path source = checkedSource(sourceRoot, subtitle);
            // Preserve the original language suffix when present, with an index to avoid collisions.
            String language = switch (subtitleLanguage(source.getFileName().toString())) {
                case "zh-Hans" -> "chs";
                case "zh-Hant" -> "cht";
                default -> subtitleLanguage(source.getFileName().toString());
            };
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
                String stage = "ani-rss-" + token + "-" + tx.staged.size() + ".pending";
                tx.staged.put(entry.getKey(), stage);
            }
            Set<String> affected = new LinkedHashSet<>(previous.files);
            affected.addAll(inputs.keySet());
            for (String name : affected) {
                Path target = inside(season, name);
                String backup = "ani-rss-" + token + "-" + tx.backups.size() + ".backup";
                if (Files.exists(target)) {
                    tx.backups.put(name, backup);
                } else tx.backups.put(name, "");
            }
            // Record all planned paths before I/O, including partial staging copies.
            atomicWrite(journal, GsonStatic.toJson(tx));
            for (var entry:inputs.entrySet()) Files.copy(entry.getValue(),season.resolve(tx.staged.get(entry.getKey())));
            for (var entry:tx.backups.entrySet()) if(!entry.getValue().isEmpty())
                replace(inside(season,entry.getKey()),inside(season,entry.getValue()));
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
        Path season = seasonDirectory(settings, show);
        if (!Files.isDirectory(season)) return;
        Path metadata=metadataDirectory(settings,show);
        // Recover/import older installations whose journals lived beside the media.
        try (DirectoryStream<Path> journals = Files.newDirectoryStream(season, ".ani-rss-E*.transaction.json")) {
            for (Path journal : journals) recover(season, journal);
        }
        try (DirectoryStream<Path> legacy = Files.newDirectoryStream(season, ".ani-rss-E*.json")) {
            for (Path old : legacy) {
                if (!old.getFileName().toString().matches("\\.ani-rss-E\\d+(?:\\.5)?\\.json")) continue;
                Path target=metadata.resolve(old.getFileName());
                if (!Files.exists(target)) Files.copy(old,target);
                // The local manifest is now authoritative; an old absolute-number sidecar
                // must not be re-imported after canonicalization on the next poll.
                Files.delete(old);
            }
        }
        try (DirectoryStream<Path> journals = Files.newDirectoryStream(metadata, ".ani-rss-E*.transaction.json")) {
            for (Path journal : journals) recover(season,journal);
        }
        normalizeManifests(settings,show,season,metadata);
        show.getSelected().entrySet().removeIf(e->show.getReleases().stream().noneMatch(r->r.getId().equals(e.getValue())
                && r.getNumberingIssue().isBlank() && ReleasePolicy.episodeKey(r.getEpisode()).equals(e.getKey())));
        restoreMissingManifests(settings,show,season,metadata);
        try (DirectoryStream<Path> manifests = Files.newDirectoryStream(metadata, ".ani-rss-E*.json")) {
            for (Path manifest : manifests) {
                String name = manifest.getFileName().toString();
                if (!name.matches("\\.ani-rss-E\\d+(?:\\.5)?\\.json")) continue;
                Manifest installed = GsonStatic.fromJson(Files.readString(manifest), Manifest.class);
                Release release = show.getReleases().stream().filter(r -> Objects.equals(r.getId(), installed.releaseId)).findFirst().orElse(null);
                if (release == null || !release.getNumberingIssue().isBlank()) continue;
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

    /** Consolidate old absolute-number manifests only after byte verification against retained originals.
     * The normal publish journal handles replacing E25/E01 by one canonical E01. A restart can repeat this safely. */
    private void normalizeManifests(Settings settings,Show show,Path season,Path metadata) throws IOException {
        record Installed(Path path,Manifest manifest,Release release) {}
        Map<String,List<Installed>> byEpisode=new LinkedHashMap<>();
        try(DirectoryStream<Path> files=Files.newDirectoryStream(metadata,".ani-rss-E*.json")) {
            for(Path path:files) {
                if(!path.getFileName().toString().matches("\\.ani-rss-E\\d+(?:\\.5)?\\.json")) continue;
                Manifest m=GsonStatic.fromJson(Files.readString(path),Manifest.class);
                Release r=show.getReleases().stream().filter(v->v.getId().equals(m.releaseId)).findFirst().orElse(null);
                if(r!=null && r.getNumberingIssue().isBlank()) byEpisode.computeIfAbsent(ReleasePolicy.episodeKey(r.getEpisode()),k->new ArrayList<>()).add(new Installed(path,m,r));
            }
        }
        for(var entry:byEpisode.entrySet()) {
            Path canonical=metadata.resolve(".ani-rss-E"+entry.getKey()+".json");List<Installed> group=entry.getValue();
            String expected=baseName(show,group.getFirst().release().getEpisode())+".";
            if(group.size()==1 && group.getFirst().path().equals(canonical)
                    && group.getFirst().manifest().files.stream().allMatch(n->n.startsWith(expected))) continue;
            List<Installed> manual=group.stream().filter(i->i.manifest().manual).toList();
            if(manual.stream().map(i->i.release().getId()).distinct().count()>1) throw new IOException("第 "+entry.getKey()+" 集存在多个手动固定版本，已保留所有文件等待选择");
            Installed chosen=manual.isEmpty()?group.getFirst():manual.getFirst();
            if(manual.isEmpty()) for(Installed i:group) if(ReleasePolicy.better(i.release(),chosen.release(),show,settings)) chosen=i;
            Set<String> owned=new LinkedHashSet<>();
            for(Installed i:group) {
                for(String file:i.manifest().files) {
                    Path target=inside(season,file);if(!Files.exists(target)) continue;
                    boolean equal=false;
                    // A merged manifest can survive a crash before publish. Its union may
                    // contain copies from any retained version of this canonical episode.
                    for(Release candidate:show.getReleases()) {
                        if(!candidate.getNumberingIssue().isBlank() || !ReleasePolicy.episodeKey(candidate.getEpisode()).equals(entry.getKey())) continue;
                        Path sourceRoot=resourceDirectory(settings,show,candidate);
                        List<String> sources=new ArrayList<>(candidate.getSubtitles());if(candidate.getVideoPath()!=null)sources.add(candidate.getVideoPath());
                        for(String source:sources) {Path original=checkedSource(sourceRoot,source);if(Files.size(original)==Files.size(target)&&Files.mismatch(original,target)==-1){equal=true;break;}}
                        if(equal)break;
                    }
                    if(!equal) throw new IOException("编号归并前校验失败，已保留正式文件："+file);
                    owned.add(file);
                }
            }
            Manifest merged=new Manifest();merged.releaseId=chosen.release().getId();merged.manual=!manual.isEmpty();merged.files=new ArrayList<>(owned);
            atomicWrite(canonical,GsonStatic.toJson(merged));
            chosen.release().setPublishedFiles(publish(settings,show,chosen.release(),merged.manual));
            for(Installed i:group) if(!i.path().equals(canonical)) Files.deleteIfExists(i.path());
        }
    }

    public void clearPin(Settings settings, Show show, String episode) throws IOException {
        if (!episode.matches("\\d+(?:\\.5)?")) throw new IllegalArgumentException("无效集数");
        reconcile(settings,show);
        Path file = metadataDirectory(settings,show).resolve(".ani-rss-E" + episode + ".json");
        if (Files.exists(file)) {
            Manifest manifest = GsonStatic.fromJson(Files.readString(file), Manifest.class);
            manifest.manual = false;
            atomicWrite(file, GsonStatic.toJson(manifest));
        }
    }

    /** Smart NAS mounts can discard hidden sidecars. Adopt only paths recorded by this app AND
     * byte-identical to retained original resources; never guess ownership from a filename. */
    private void restoreMissingManifests(Settings settings, Show show, Path season, Path metadata) throws IOException {
        for (var selection : new ArrayList<>(show.getSelected().entrySet())) {
            String episode=selection.getKey();
            if (!episode.matches("\\d+(?:\\.5)?")) throw new IOException("播放清单包含无效集数");
            Path manifest=metadata.resolve(".ani-rss-E"+episode+".json");
            if (Files.exists(manifest)) continue;
            Release selected=show.getReleases().stream().filter(r->Objects.equals(r.getId(),selection.getValue())).findFirst().orElse(null);
            if (selected==null) continue;
            Set<String> owned=new LinkedHashSet<>();
            for (Release release : show.getReleases()) {
                if (!ReleasePolicy.episodeKey(release.getEpisode()).equals(episode)) continue;
                Path sourceRoot=resourceDirectory(settings,show,release);
                List<String> sources=new ArrayList<>(release.getSubtitles());
                if (release.getVideoPath()!=null) sources.add(release.getVideoPath());
                for (String published : release.getPublishedFiles()) {
                    Path target=Path.of(published).toAbsolutePath().normalize();
                    String name=target.getFileName().toString();
                    if (!target.equals(inside(season,name)) || !name.startsWith(baseName(show,release.getEpisode())+"."))
                        throw new IOException("播放清单路径不在本集目录中");
                    if (!Files.isRegularFile(target)) continue;
                    for (String value : sources) {
                        Path source=checkedSource(sourceRoot,value);
                        if (Files.size(source)==Files.size(target) && Files.mismatch(source,target)==-1) {owned.add(name);break;}
                    }
                }
            }
            Manifest previous=new Manifest();previous.releaseId=selected.getId();previous.manual=show.getPinnedEpisodes().contains(episode);previous.files=new ArrayList<>(owned);
            atomicWrite(manifest,GsonStatic.toJson(previous));
            selected.setPublishedFiles(publish(settings,show,selected,previous.manual));
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
            else {
                Path backup=inside(directory,entry.getValue());
                if(Files.exists(backup)) Files.copy(backup,target,StandardCopyOption.REPLACE_EXISTING);
                else if(!Files.exists(target)) throw new IOException("事务备份和正式文件均丢失，已保留现场等待恢复");
                // A planned backup which has not been renamed yet leaves the old target intact.
            }
        }
        Path manifest = inside(journal.toAbsolutePath().getParent(), tx.manifestName);
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
