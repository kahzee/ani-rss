package ani.rss.simple;

import ani.rss.simple.SimpleModel.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class ExistingResourceImporterTest {
    @TempDir Path root;
    Settings settings(){Settings s=new Settings();s.setLibraryRoot(root.resolve("downloads/library").toString());s.setDownloaderRoot("/downloads/library");return s;}
    Release release(){Release r=new Release();r.setId("a".repeat(40));r.setChinese(true);return r;}
    JsonObject task(){return JsonParser.parseString("{\"hash\":\""+"a".repeat(40)+"\",\"progress\":1,\"state\":\"stoppedUP\",\"category\":\"ani-rss\",\"save_path\":\"/downloads/old\"}").getAsJsonObject();}
    JsonArray files(){return JsonParser.parseString("[{\"index\":0,\"name\":\"video.mkv\",\"size\":5,\"progress\":1}]").getAsJsonArray();}
    MediaInspector inspector(){return new MediaInspector(p->"{\"streams\":[{\"codec_type\":\"video\",\"width\":16,\"height\":16}],\"format\":{\"duration\":4}}");}
    Path source()throws Exception{Path p=root.resolve("downloads/old/video.mkv");Files.createDirectories(p.getParent());Files.writeString(p,"video");return p;}
    @Test void copiesAndValidatesCompleteLegacyResourceWithoutChangingItsSource() throws Exception {
        Path old=source();Release r=release();JsonObject t=task();String original=t.toString();
        assertTrue(ExistingResourceImporter.copy(settings(),r,root.resolve("downloads/library/123/hash"),t,files(),inspector()));
        assertEquals("ready",r.getStatus());assertEquals("video",Files.readString(old));assertEquals(-1,Files.mismatch(old,Path.of(r.getVideoPath())));assertEquals(original,t.toString());assertFalse(r.getMediaSignature().isBlank());
    }
    @Test void incompleteForeignTaskIsNotTouched() throws Exception {JsonObject t=task();t.addProperty("progress",0.5);assertFalse(ExistingResourceImporter.copy(settings(),release(),root.resolve("downloads/library/x"),t,files(),inspector()));assertFalse(Files.exists(root.resolve("downloads/library/x")));}
    @Test void pathsOutsideKnownDownloadMountAreRejected() throws Exception {JsonObject t=task();t.addProperty("save_path","/private");assertThrows(IOException.class,()->ExistingResourceImporter.copy(settings(),release(),root.resolve("downloads/library/x"),t,files(),inspector()));}
    @Test void traversalAndHashMismatchAreRejected() throws Exception {source();JsonArray f=files();f.get(0).getAsJsonObject().addProperty("name","../video.mkv");assertThrows(IOException.class,()->ExistingResourceImporter.copy(settings(),release(),root.resolve("downloads/library/x"),task(),f,inspector()));JsonObject t=task();t.addProperty("hash","b".repeat(40));assertThrows(IOException.class,()->ExistingResourceImporter.copy(settings(),release(),root.resolve("downloads/library/x"),t,files(),inspector()));}
    @Test void missingOrChangedOriginalCannotBecomeReady() throws Exception {source();Files.writeString(root.resolve("downloads/old/video.mkv"),"partial");Release r=release();assertThrows(IOException.class,()->ExistingResourceImporter.copy(settings(),r,root.resolve("downloads/library/x"),task(),files(),inspector()));assertNotEquals("ready",r.getStatus());}
    @Test void existingDifferentDestinationIsNeverOverwritten() throws Exception {Path old=source(),dest=root.resolve("downloads/library/x");Files.createDirectories(dest);Files.writeString(dest.resolve("imported-0.mkv"),"private");assertThrows(IOException.class,()->ExistingResourceImporter.copy(settings(),release(),dest,task(),files(),inspector()));assertEquals("private",Files.readString(dest.resolve("imported-0.mkv")));assertEquals("video",Files.readString(old));}
    @Test void failedMediaProbePreservesSourceAndDoesNotMarkReady() throws Exception {source();Release r=release();assertThrows(IOException.class,()->ExistingResourceImporter.copy(settings(),r,root.resolve("downloads/library/x"),task(),files(),new MediaInspector(p->"{\"streams\":[],\"format\":{\"duration\":0}}")));assertNotEquals("ready",r.getStatus());assertEquals("video",Files.readString(root.resolve("downloads/old/video.mkv")));}
    @Test void encodedColonFolderOnWindowsIsMappedWithoutChangingTheDriveColon() throws Exception {if(!System.getProperty("os.name").toLowerCase().contains("windows"))return;assertEquals(root.resolve("downloads/C\uf03a/Media").toAbsolutePath(),ExistingResourceImporter.mappedRoot(settings(),"/downloads/C:/Media"));}
    @Test void pairedBitmapSubtitlesRetainOneSharedBasename() throws Exception {
        source();Files.writeString(root.resolve("downloads/old/video.chs.idx"),"idx");Files.writeString(root.resolve("downloads/old/video.chs.sub"),"sub");JsonArray f=files();
        for(String ext:new String[]{"idx","sub"}){JsonObject row=new JsonObject();row.addProperty("index",f.size());row.addProperty("name","video.chs."+ext);row.addProperty("progress",1);row.addProperty("size",3);f.add(row);}
        Release r=release();assertTrue(ExistingResourceImporter.copy(settings(),r,root.resolve("downloads/library/x"),task(),f,inspector()));
        assertEquals(2,r.getSubtitles().size());assertEquals(r.getSubtitles().get(0).replaceFirst("\\.idx$",""),r.getSubtitles().get(1).replaceFirst("\\.sub$",""));
    }
}
