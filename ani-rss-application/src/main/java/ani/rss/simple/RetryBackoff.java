package ani.rss.simple;

import java.util.concurrent.ThreadLocalRandom;

/** Bounded backoff with jitter: cold releases are probed again without synchronized retry bursts. */
final class RetryBackoff {
    static final long MAX_DELAY=6*60*60_000L;
    private RetryBackoff() {}
    static long nextAt(long now,int failures){return now+delay(failures,ThreadLocalRandom.current().nextDouble());}
    static long delay(int failures,double random) {
        long base=Math.min(MAX_DELAY,30*60_000L*(1L<<Math.min(5,Math.max(1,failures)-1)));
        return Math.min(MAX_DELAY,(long)(base*(0.9+Math.max(0,Math.min(1,random))*0.2)));
    }
}
