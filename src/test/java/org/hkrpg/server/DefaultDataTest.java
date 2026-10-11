package org.hkrpg.server;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DefaultDataTest {
    @TempDir Path directory;
    @Test void installsDefaultsAndPreservesOperatorChanges() throws Exception {
        DefaultData.install(directory);
        assertTrue(Files.size(directory.resolve("Banners.json")) > 0);
        assertTrue(Files.size(directory.resolve("ChessRogueGuideRoutes.json")) > 0);
        Files.writeString(directory.resolve("Banners.json"), "custom banners");
        Files.writeString(directory.resolve("Gameplay46.json"), "operator gameplay");
        Files.writeString(directory.resolve("ChessRogueGuideRoutes.json"), "operator chess routing");
        DefaultData.install(directory);
        assertEquals("custom banners", Files.readString(directory.resolve("Banners.json")));
        assertEquals("operator gameplay", Files.readString(directory.resolve("Gameplay46.json")));
        assertEquals("operator chess routing", Files.readString(directory.resolve("ChessRogueGuideRoutes.json")));
    }
}
