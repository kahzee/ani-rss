package ani.rss.simple;

import ani.rss.simple.SimpleModel.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DownloadPathsTest {
    @TempDir Path root;
    @Test void mapsLocalFilesToLinuxContainerWithoutWindowsDriveLetters() {
        Settings settings=new Settings();settings.setLibraryRoot(root.toString());settings.setDownloaderRoot("/downloads/ani-rss-simple/");
        assertEquals("/downloads/ani-rss-simple/123/hash",DownloadPaths.destination(settings,root.resolve("123/hash")));
        assertTrue(DownloadPaths.equivalent("/downloads/ani-rss-simple/123/hash/","/downloads/ani-rss-simple/123/hash"));
    }
    @Test void doesNotDependOnTheLocalPlatformsPathParser() {
        assertTrue(DownloadPaths.equivalent("C:\\Media\\Library\\123\\hash\\","c:/media/library/123/hash"));
        assertFalse(DownloadPaths.equivalent("/downloads/Case","/downloads/case"));
        assertEquals("/",DownloadPaths.normalizeRemote("/"));
    }
    @Test void rejectsRelativeRemotePathsAndEscapes() {
        assertThrows(IllegalArgumentException.class,()->DownloadPaths.normalizeRemote("downloads/library"));
        assertThrows(IllegalArgumentException.class,()->DownloadPaths.normalizeRemote("/downloads/../private"));
        Settings settings=new Settings();settings.setLibraryRoot(root.toString());settings.setDownloaderRoot("/downloads");
        assertThrows(IllegalArgumentException.class,()->DownloadPaths.destination(settings,root.resolve("../private")));
    }
}
