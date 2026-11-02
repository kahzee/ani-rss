package ani.rss.simple;

import ani.rss.simple.SimpleModel.Settings;
import java.nio.file.Path;

/** File-system paths must be interpreted on the machine that actually uses them. */
public final class DownloadPaths {
    private DownloadPaths() {}

    public static String normalizeRemote(String value) {
        if (value == null || value.isBlank()) return "";
        String path = value.trim().replace('\\', '/');
        if (!(path.startsWith("/") || path.matches("^[A-Za-z]:/.*")))
            throw new IllegalArgumentException("下载器内资源库路径必须为绝对路径");
        for (String segment : path.split("/"))
            if (segment.equals("..") || segment.equals(".")) throw new IllegalArgumentException("下载器路径不能包含 . 或 ..");
        if (path.indexOf('\0') >= 0) throw new IllegalArgumentException("下载器路径含无效字符");
        return path.replaceAll("/+$", "") + (path.equals("/") ? "/" : "");
    }

    public static String destination(Settings settings, Path localDirectory) {
        Path localRoot = Path.of(settings.getLibraryRoot()).toAbsolutePath().normalize();
        Path local = localDirectory.toAbsolutePath().normalize();
        if (!local.startsWith(localRoot)) throw new IllegalArgumentException("资源目录超出本地资源库");
        if (settings.getDownloaderRoot() == null || settings.getDownloaderRoot().isBlank()) return local.toString();
        String remote = normalizeRemote(settings.getDownloaderRoot());
        String suffix = localRoot.relativize(local).toString().replace('\\', '/');
        return remote + (remote.endsWith("/") ? "" : "/") + suffix;
    }

    public static boolean equivalent(String actual, String expected) {
        String left = normalizeRemote(actual), right = normalizeRemote(expected);
        return right.matches("^[A-Za-z]:/.*") || right.startsWith("//") ? left.equalsIgnoreCase(right) : left.equals(right);
    }
}
