package ani.rss.auth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class LocalAccessTest {
    @Test void localAccessRequiresNeitherRequestContextNorSession() {
        assertDoesNotThrow(()->new AuthAspect().before(null,null));
    }
}
