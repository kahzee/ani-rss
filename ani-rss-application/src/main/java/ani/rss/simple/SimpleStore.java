package ani.rss.simple;

import ani.rss.commons.GsonStatic;
import ani.rss.simple.SimpleModel.*;
import ani.rss.util.other.ConfigUtil;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

@Component
public class SimpleStore {
    private final Path file;
    private final State state;
    private volatile String publicSnapshot;

    public SimpleStore() throws IOException { this(ConfigUtil.getConfigDir().toPath().resolve("simple-tracking.json")); }

    SimpleStore(Path file) throws IOException {
        this.file = file;
        // Never silently reset a corrupt resource manifest.
        state = Files.exists(file) ? GsonStatic.fromJson(Files.readString(file), State.class) : new State();
        if (state == null || state.getSettings() == null || state.getShows() == null)
            throw new IOException("追番资源清单损坏，请恢复 simple-tracking.json 的备份");
        publish();
    }

    public State state() { return state; }
    Path directory() { return file.toAbsolutePath().getParent(); }
    public State snapshot() { return GsonStatic.fromJson(publicSnapshot, State.class); }

    public void save() throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, GsonStatic.toJson(state), StandardCharsets.UTF_8);
        if (Files.exists(file)) Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        publish();
    }

    void publish() {
        State copy = GsonStatic.fromJson(GsonStatic.toJson(state), State.class);
        copy.getSettings().setCredentialSaved(!copy.getSettings().getCredential().isBlank());
        copy.getSettings().setCredential("");
        publicSnapshot = GsonStatic.toJson(copy);
    }
}
