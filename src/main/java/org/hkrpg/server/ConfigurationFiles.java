package org.hkrpg.server;

import com.google.gson.*;
import emu.lunarcore.Config;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Merge explicit settings with defaults, including nested objects with parameterized constructors.
 */
public final class ConfigurationFiles {
  private static String serialize(Config config) {
    config.validate();
    return new GsonBuilder()
        .setDateFormat("dd-MM-yyyy hh:mm:ss")
        .setPrettyPrinting()
        .serializeNulls()
        .create()
        .toJson(config);
  }

  /** A competing installer or an existing operator file always wins. */
  public static boolean installDefaults(Path path, Config config) throws IOException {
    if (Files.exists(path)) return false;
    var text = serialize(config);
    try {
      Files.writeString(
          path,
          text,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
      return true;
    } catch (FileAlreadyExistsException existing) {
      return false;
    }
  }

  /**
   * Explicit saves serialize first, then replace a finished temporary file in the same directory.
   */
  public static void save(Path path, Config config) throws IOException {
    var text = serialize(config);
    var target = path.toAbsolutePath().normalize();
    var temporary = Files.createTempFile(target.getParent(), ".hkrpg-config-", ".tmp");
    try {
      Files.writeString(temporary, text, StandardCharsets.UTF_8);
      try {
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unavailable) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  public static Config read(Path path) throws IOException {
    var gson = new GsonBuilder().setDateFormat("dd-MM-yyyy hh:mm:ss").create();
    if (!Files.exists(path)) return new Config();
    JsonObject overrides;
    try (var reader = Files.newBufferedReader(path)) {
      var json = JsonParser.parseReader(reader);
      if (!json.isJsonObject()) throw new IllegalArgumentException("Config must be a JSON object");
      overrides = json.getAsJsonObject();
    }
    JsonObject defaults = gson.toJsonTree(new Config()).getAsJsonObject();
    merge(defaults, overrides);
    Config config = gson.fromJson(defaults, Config.class);
    config.validate();
    return config;
  }

  private static void merge(JsonObject target, JsonObject overrides) {
    for (var entry : overrides.entrySet()) {
      var existing = target.get(entry.getKey());
      var value = entry.getValue();
      if (existing != null && existing.isJsonObject() && value.isJsonObject()) {
        merge(existing.getAsJsonObject(), value.getAsJsonObject());
      } else {
        target.add(entry.getKey(), value);
      }
    }
  }

  private ConfigurationFiles() {}
}
