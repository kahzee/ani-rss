package ani.rss.simple;

import java.io.IOException;

/** Needs read-only import or a different resource; do not endlessly resubmit the same hash. */
final class DownloadConflictException extends IOException {
    DownloadConflictException(String message){super(message);}
    DownloadConflictException(String message,Throwable cause){super(message,cause);}
}
