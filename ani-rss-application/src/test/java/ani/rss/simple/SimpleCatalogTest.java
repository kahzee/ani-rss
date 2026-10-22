package ani.rss.simple;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimpleCatalogTest {
    @Test void datesAndWordsAreNotEpisodeBatches() {
        assertFalse(SimpleCatalog.isBatch("[2026-10-22] 番名 - 01 [CHS]"));
        assertFalse(SimpleCatalog.isBatch("Incomplete - 01 [CHS]"));
        assertTrue(SimpleCatalog.isBatch("番名 [01-12] [CHS]"));
        assertTrue(SimpleCatalog.isBatch("番名 S01E01-E12 [CHS]"));
        assertTrue(SimpleCatalog.isBatch("番名 Complete [CHS]"));
    }

    @Test void supportsBothHexAndBase32MagnetHashes() {
        assertEquals("0".repeat(40),SimpleCatalog.infoHash("magnet:?xt=urn:btih:"+"A".repeat(32)));
        assertEquals("f".repeat(40),SimpleCatalog.infoHash("magnet:?xt=urn:btih:"+"7".repeat(32)+"&dn=test"));
        assertEquals("abcdef0123456789abcdef0123456789abcdef01",SimpleCatalog.infoHash("magnet:?xt=urn:btih:ABCDEF0123456789ABCDEF0123456789ABCDEF01"));
        assertEquals("",SimpleCatalog.infoHash("magnet:?xt=urn:btih:../../outside"));
    }
}
