package org.popcraft.bolt.neoforge;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.popcraft.bolt.util.Mode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class PlayerPreferences {
    private final Path file;
    private final Map<String, Set<Mode>> modes;
    public PlayerPreferences(Path directory) throws IOException {
        file = directory.resolve("player-modes.json");
        if (Files.exists(file)) try (var reader = Files.newBufferedReader(file)) {
            Map<String, Set<Mode>> read = new Gson().fromJson(reader, new TypeToken<Map<String, Set<Mode>>>(){}.getType());
            modes = read == null ? new HashMap<>() : new HashMap<>(read);
        } else modes = new HashMap<>();
    }
    public Set<Mode> load(UUID player) { return Set.copyOf(modes.getOrDefault(player.toString(), Set.of())); }
    public void save(UUID player, Collection<Mode> selected) {
        modes.put(player.toString(), new HashSet<>(selected));
        var temporary = file.resolveSibling(file.getFileName()+".tmp");
        try {
            Files.writeString(temporary, new Gson().toJson(modes));
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
}
