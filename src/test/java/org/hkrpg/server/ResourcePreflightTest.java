package org.hkrpg.server;

import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ResourcePreflightTest {
    @TempDir Path dir;
    @Test void missingResourcesAreReportedTogether() throws Exception {
        var report = ResourcePreflight.inspect(dir, "4.2.0", List.of("AvatarConfig.json", "ItemConfig.json"));
        assertFalse(report.ready());
        assertTrue(report.errors().stream().anyMatch(s -> s.contains("AvatarConfig.json")));
        assertTrue(report.errors().stream().anyMatch(s -> s.contains("ItemConfig.json")));
    }
    @Test void validStructureDoesNotClaimClientVerification() throws Exception {
        fixture();
        var report = ResourcePreflight.inspect(dir, "4.2.0", List.of("AvatarConfig.json"));
        assertTrue(report.ready(), report.errors().toString());
        assertFalse(report.warnings().isEmpty());
    }
    @Test void rejectsWrongVersionAndInvalidOrEmptyTables() throws Exception {
        fixture();
        Files.writeString(dir.resolve("resource-manifest.json"), "{\"clientVersion\":\"4.6.0\"}");
        assertFalse(ResourcePreflight.inspect(dir, "4.2.0", List.of("AvatarConfig.json")).ready());
        Files.writeString(dir.resolve("resource-manifest.json"), "{\"clientVersion\":\"4.2.0\"}");
        Files.writeString(dir.resolve("ExcelOutput/AvatarConfig.json"), "[]");
        assertFalse(ResourcePreflight.inspect(dir, "4.2.0", List.of("AvatarConfig.json")).ready());
        Files.writeString(dir.resolve("ExcelOutput/AvatarConfig.json"), "{broken");
        assertFalse(ResourcePreflight.inspect(dir, "4.2.0", List.of("AvatarConfig.json")).ready());
    }
    private void fixture() throws Exception {
        Files.createDirectories(dir.resolve("ExcelOutput"));
        Files.writeString(dir.resolve("ExcelOutput/AvatarConfig.json"), "[{\"AvatarID\":1001}]");
        Files.createDirectories(dir.resolve("Config/LevelOutput/RuntimeFloor"));
        Files.writeString(dir.resolve("Config/LevelOutput/RuntimeFloor/P1_F1.json"), "{}");
        Files.createDirectories(dir.resolve("Config/LevelOutput/SharedRuntimeGroup"));
        Files.writeString(dir.resolve("Config/LevelOutput/SharedRuntimeGroup/Group1.json"), "{}");
    }
}
