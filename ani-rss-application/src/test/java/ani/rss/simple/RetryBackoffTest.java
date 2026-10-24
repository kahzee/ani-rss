package ani.rss.simple;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetryBackoffTest {
    @Test void firstRetryIsApproximatelyThirtyMinutesWithJitter(){
        assertEquals(27*60_000L,RetryBackoff.delay(1,0));assertEquals(33*60_000L,RetryBackoff.delay(1,1));
    }
    @Test void repeatedFailuresBackOffButNeverDisappearForDays(){
        assertTrue(RetryBackoff.delay(3,0)>RetryBackoff.delay(2,1));
        assertEquals(RetryBackoff.MAX_DELAY,RetryBackoff.delay(Integer.MAX_VALUE,1));
        assertTrue(RetryBackoff.delay(Integer.MAX_VALUE,0)>=RetryBackoff.MAX_DELAY*0.9);
    }
    @Test void nonPositiveFailureCountDoesNotOverflowOrRetryImmediately(){
        assertTrue(RetryBackoff.delay(0,0)>0);assertEquals(RetryBackoff.delay(1,0),RetryBackoff.delay(Integer.MIN_VALUE,0));
    }
}
