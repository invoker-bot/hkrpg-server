package org.hkrpg.server;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class ResourcePreflight {
    public record Report(List<String> errors, List<String> warnings) {
        public Report { errors = List.copyOf(errors); warnings = List.copyOf(warnings); }
        public boolean ready() { return errors.isEmpty(); }
    }

    public static Report inspect(Path root, String version, Collection<String> tables) {
        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        Path manifest = root.resolve("resource-manifest.json");
        if (Files.exists(manifest)) {
            try (var reader = Files.newBufferedReader(manifest)) {
                var json = JsonParser.parseReader(reader).getAsJsonObject();
                if (!json.has("clientVersion") || !version.equals(json.get("clientVersion").getAsString())) {
                    errors.add("resource-manifest.json clientVersion must be " + version);
                }
            } catch (Exception e) {
                errors.add("Invalid resource-manifest.json: " + e.getMessage());
            }
        } else {
            warnings.add("Resource version is undeclared. Matching structure does not establish client compatibility.");
        }
        for (String table : new TreeSet<>(tables)) {
            Path file = root.resolve("ExcelOutput").resolve(table);
            if (!Files.isRegularFile(file)) {
                errors.add("Missing table: ExcelOutput/" + table);
                continue;
            }
            try (var reader = Files.newBufferedReader(file)) {
                var json = JsonParser.parseReader(reader);
                if (!(json.isJsonArray() && !json.getAsJsonArray().isEmpty()) &&
                        !(json.isJsonObject() && !json.getAsJsonObject().isEmpty())) {
                    errors.add("Empty or invalid table: ExcelOutput/" + table);
                }
            } catch (Exception e) {
                errors.add("Unreadable JSON: ExcelOutput/" + table + ": " + e.getMessage());
            }
        }
        if (!hasJson(root.resolve("Config/LevelOutput/RuntimeFloor"))) {
            errors.add("Missing floor data: Config/LevelOutput/RuntimeFloor/*.json");
        }
        if (!hasJson(root.resolve("Config/LevelOutput/SharedRuntimeGroup")) &&
                !hasJson(root.resolve("Config/LevelOutput/RuntimeGroup"))) {
            errors.add("Missing group data: Config/LevelOutput/SharedRuntimeGroup or RuntimeGroup");
        }
        if (!Files.isDirectory(root.resolve("TextMap"))) warnings.add("TextMap is absent; handbook names may be incomplete.");
        return new Report(errors, warnings);
    }

    private static boolean hasJson(Path directory) {
        if (!Files.isDirectory(directory)) return false;
        try (var files = Files.walk(directory)) {
            return files.anyMatch(p -> Files.isRegularFile(p) && p.toString().endsWith(".json"));
        } catch (IOException e) { return false; }
    }

    private ResourcePreflight() {}
}
