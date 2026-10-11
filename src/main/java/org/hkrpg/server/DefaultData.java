package org.hkrpg.server;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

public final class DefaultData {
    public static void install(Path directory) throws IOException {
        Files.createDirectories(directory);
        for (String name : List.of("Banners.json", "ActivityScheduling.json", "RogueMapGen.json", "RogueMapGenByMap.json", "RogueBossGen.json", "RogueDialogueRewards.json", "Gameplay46.json")) {
            Path target = directory.resolve(name);
            if (Files.exists(target)) continue;
            try (var stream = DefaultData.class.getResourceAsStream("/defaults/" + name)) {
                if (stream == null) throw new IOException("Missing bundled default: " + name);
                Files.copy(stream, target);
            }
        }
    }
    private DefaultData() {}
}
