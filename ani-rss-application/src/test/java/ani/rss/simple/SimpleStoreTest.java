package ani.rss.simple;

import ani.rss.simple.SimpleModel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SimpleStoreTest {
    @TempDir Path root;
    @Test void persistsVersionsAndPinsButNeverExposesCredentials() throws Exception {
        Path file=root.resolve("state.json"); SimpleStore store=new SimpleStore(file);
        store.state().getSettings().setCredential("test-secret");
        Show show=new Show(); show.setId("1"); show.getSelected().put("1","hash"); show.getPinnedEpisodes().add("1"); store.state().getShows().add(show); store.save();
        assertEquals("",store.snapshot().getSettings().getCredential());
        assertTrue(store.snapshot().getSettings().isCredentialSaved());
        SimpleStore restarted=new SimpleStore(file);
        assertEquals("test-secret",restarted.state().getSettings().getCredential());
        assertEquals("hash",restarted.snapshot().getShows().getFirst().getSelected().get("1"));
        assertTrue(restarted.snapshot().getShows().getFirst().getPinnedEpisodes().contains("1"));
        store.snapshot().getShows().clear();
        assertEquals(1,store.snapshot().getShows().size());
    }
    @Test void corruptStateIsNotOverwritten() throws Exception {
        Path file=root.resolve("state.json"); Files.writeString(file,"broken");
        assertThrows(Exception.class,()->new SimpleStore(file));
        assertEquals("broken",Files.readString(file));
    }
}
