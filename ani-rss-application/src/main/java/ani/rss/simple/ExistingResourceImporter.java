package ani.rss.simple;

import ani.rss.commons.GsonStatic;
import ani.rss.simple.SimpleModel.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Read-only reuse of completed torrents. Never moves, relabels, resumes or reprioritizes the source task. */
final class ExistingResourceImporter {
    private ExistingResourceImporter() {}
    static boolean copy(Settings settings,Release release,Path destination,JsonObject task,JsonArray files,MediaInspector inspector) throws IOException,InterruptedException {
        if(!task.has("progress") || task.get("progress").getAsDouble()<1)return false;
        String status=task.has("state")?task.get("state").getAsString().toLowerCase(Locale.ROOT):"";
        if(status.contains("checking") || status.contains("moving"))return false;
        String actualHash=task.has("hash")?task.get("hash").getAsString():"";
        if(!SimpleDownloader.hash(release).equalsIgnoreCase(actualHash))throw new IOException("旧任务种子标识不一致，未导入");
        Path ownRoot=PlaybackLibrary.inside(Path.of(settings.getLibraryRoot()),Path.of(settings.getLibraryRoot()).toAbsolutePath().normalize().relativize(destination.toAbsolutePath().normalize()).toString());
        Path sourceRoot=mappedRoot(settings,task.get("save_path").getAsString());
        if(sourceRoot.startsWith(ownRoot)||ownRoot.startsWith(sourceRoot))throw new IOException("旧资源与导入目标重叠，未导入");
        record Input(Path source,String name,long size) {}
        List<Input> inputs=new ArrayList<>();int videos=0;Map<String,Integer> subtitleFamilies=new LinkedHashMap<>();
        for(JsonElement element:files) {
            JsonObject f=element.getAsJsonObject();int index=f.has("index")?f.get("index").getAsInt():inputs.size();
            if(release.getFileIndex()>=0 && index!=release.getFileIndex()&&!release.getSubtitleIndices().contains(index))continue;
            String relative=f.get("name").getAsString();Path source=PlaybackLibrary.inside(sourceRoot,localRelative(relative));
            String ext=PlaybackLibrary.extension(source);boolean video=PlaybackLibrary.VIDEO.contains(ext);
            if(!video&&!PlaybackLibrary.SUBTITLE.contains(ext))continue;
            long size=f.get("size").getAsLong();
            if(size<=0 || f.get("progress").getAsDouble()<1 || !Files.isRegularFile(source)||Files.size(source)!=size)
                throw new IOException("旧任务显示完成，但原文件尚不可见或不完整，未导入");
            if(video)videos++;
            String language=PlaybackLibrary.subtitleLanguage(relative);
            String suffix=video?"":"."+switch(language){case "zh-Hans"->"chs";case "zh-Hant"->"cht";default->language;};
            String family=video?Integer.toString(index):"sub-"+subtitleFamilies.computeIfAbsent(relative.substring(0,relative.lastIndexOf('.')),k->subtitleFamilies.size());
            inputs.add(new Input(source,"imported-"+family+suffix+"."+ext,size));
        }
        if(videos!=1)throw new IOException("旧任务必须明确对应一个视频，未自动导入");
        Files.createDirectories(ownRoot);Release checked=GsonStatic.fromJson(GsonStatic.toJson(release),Release.class);
        checked.setDownloadPath(ownRoot.toString());checked.setSubtitles(new ArrayList<>());checked.setMediaSignature("");
        for(Input input:inputs) {
            Path target=PlaybackLibrary.inside(ownRoot,input.name());BasicFileAttributes before=Files.readAttributes(input.source(),BasicFileAttributes.class);
            if(Files.exists(target)) {
                if(Files.size(target)!=input.size() || Files.mismatch(input.source(),target)!=-1)throw new IOException("导入目标已有不同内容，已保留双方文件");
            } else {
                Path stage=PlaybackLibrary.inside(ownRoot,"import-"+UUID.randomUUID()+".pending");
                try {
                    Files.copy(input.source(),stage);
                    if(Files.size(stage)!=input.size()||Files.mismatch(input.source(),stage)!=-1)throw new IOException("旧资源复制核验失败，原件未改动");
                    BasicFileAttributes after=Files.readAttributes(input.source(),BasicFileAttributes.class);
                    if(before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime()))throw new IOException("旧资源正在变化，稍后再导入");
                    Files.move(stage,target); // Never replace an unrelated target created concurrently.
                } finally {Files.deleteIfExists(stage);}
            }
            if(PlaybackLibrary.VIDEO.contains(PlaybackLibrary.extension(target)))checked.setVideoPath(target.toString());else checked.getSubtitles().add(target.toString());
        }
        inspector.inspect(checked);
        release.setDownloadPath(checked.getDownloadPath());release.setVideoPath(checked.getVideoPath());release.setSubtitles(checked.getSubtitles());
        release.setMediaSignature(checked.getMediaSignature());release.setSubtitleEvidence(checked.getSubtitleEvidence());
        release.setStatus("ready");release.setProgress(100);release.setRetryAt(0);release.setError("");
        return true;
    }

    /** Both roots already map the same library. Extend only one directory to its known download parent. */
    static Path mappedRoot(Settings settings,String savePath) throws IOException {
        String remote=DownloadPaths.normalizeRemote(settings.getDownloaderRoot());String actual=DownloadPaths.normalizeRemote(savePath);
        if(remote.isBlank() || !remote.startsWith("/") || remote.lastIndexOf('/')<=0)throw new IOException("旧资源缺少可核对的下载目录映射");
        Path local=Path.of(settings.getLibraryRoot()).toAbsolutePath().normalize();
        if(!local.getFileName().toString().equals(remote.substring(remote.lastIndexOf('/')+1)))throw new IOException("旧资源父目录映射不能自动确认");
        String parent=remote.substring(0,remote.lastIndexOf('/'));
        if(!actual.startsWith(parent+"/"))throw new IOException("旧资源位于已知下载映射之外，未导入");
        return PlaybackLibrary.inside(local.getParent(),localRelative(actual.substring(parent.length()+1)));
    }
    private static String localRelative(String name) throws IOException {
        String path=name.replace('\\','/');
        if(path.startsWith("/")||Arrays.stream(path.split("/",-1)).anyMatch(p->p.equals("..")||p.equals(".")||p.isBlank()))throw new IOException("旧资源包含不安全的相对路径");
        // Zspace's Windows mapped drive exposes the Linux ':' segment as U+F03A (observed C).
        return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows")?path.replace(':','\uf03a'):path;
    }
}
