package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.ResourceLoader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Additional catch binding compiled from the same pinned resource; legacy identities stay intact.
 */
final class MascotExtensionSources {
  static final int CHEST = 20433651;
  private static final JsonObject SOURCE = load();
  static final Map<String, String> ROWS = pins("rows"), FILES = pins("files");
  static final MascotSources.Control CONTROL =
      ResourceLoader.getGson().fromJson(SOURCE.get("control"), MascotSources.Control.class);
  static final WorldChestSources.Location LOCATION =
      ResourceLoader.getGson().fromJson(SOURCE.get("location"), WorldChestSources.Location.class);

  private static JsonObject load() {
    try (var in =
            Objects.requireNonNull(
                MascotExtensionSources.class.getResourceAsStream(
                    "/gameplay/mascot-extension-4.6.json"));
        var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    } catch (Exception invalid) {
      throw new ExceptionInInitializerError(invalid);
    }
  }

  private static Map<String, String> pins(String key) {
    var pins = new HashMap<String, String>();
    SOURCE
        .getAsJsonObject(key)
        .entrySet()
        .forEach(e -> pins.put(e.getKey(), e.getValue().getAsString()));
    return Map.copyOf(pins);
  }

  static <K, V> Map<K, V> extend(Map<K, V> old, Map<K, V> extra) {
    var result = new HashMap<>(old);
    extra.forEach(
        (key, value) -> {
          if (result.putIfAbsent(key, value) != null)
            throw new IllegalArgumentException("Duplicate mascot source");
        });
    return Map.copyOf(result);
  }

  private MascotExtensionSources() {}
}
