package org.hkrpg.server.gameplay;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Source catalogue only; client selection and resonance are separate runtime work. */
public final class RogueAeonCatalog {
  public record Row(
      int id,
      int version,
      int sort,
      int display,
      int buffType,
      int unlock,
      int dialogue,
      int basicGroup,
      int enhanceGroup,
      String sourceJson) {}

  private static volatile Map<Integer, Row> catalogue = Map.of();

  private RogueAeonCatalog() {}

  public static void load(Path resources) {
    catalogue = Map.of();
    RogueResonance.load(resources);
    var file = resources.resolve("ExcelOutput/RogueAeon.json");
    if (!Files.isRegularFile(file)) return;
    try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      var value = JsonParser.parseReader(reader);
      if (!value.isJsonArray() || value.getAsJsonArray().size() > 9)
        throw new IllegalArgumentException("Unsupported RogueAeon table");
      var next = new java.util.TreeMap<Integer, Row>();
      for (var element : value.getAsJsonArray()) {
        if (!element.isJsonObject()) throw new IllegalArgumentException("Invalid RogueAeon row");
        var row = element.getAsJsonObject();
        if (!java.util.Set.of(
                "AeonID",
                "RogueVersion",
                "Sort",
                "DisplayID",
                "EffectDesc1",
                "EffectDesc2",
                "RogueBuffType",
                "ArrivedTalkDialogueGroupID",
                "BattleEventBuffGroup",
                "BattleEventEnhanceBuffGroup",
                "UnlockID")
            .containsAll(row.keySet()))
          throw new IllegalArgumentException("Unverified RogueAeon field");
        int id = number(row, "AeonID", true);
        int version = number(row, "RogueVersion", true);
        if (id > 9 || version != 1)
          throw new IllegalArgumentException("Unverified RogueAeon identity/version");
        hash(row.get("EffectDesc1"));
        hash(row.get("EffectDesc2"));
        var parsed =
            new Row(
                id,
                version,
                number(row, "Sort", true),
                number(row, "DisplayID", true),
                number(row, "RogueBuffType", true),
                number(row, "UnlockID", false),
                number(row, "ArrivedTalkDialogueGroupID", false),
                number(row, "BattleEventBuffGroup", true),
                number(row, "BattleEventEnhanceBuffGroup", true),
                row.toString());
        if (next.putIfAbsent(id, parsed) != null)
          throw new IllegalArgumentException("Duplicate RogueAeon identity");
      }
      catalogue = Map.copyOf(next);
    } catch (Exception invalid) {
      throw new IllegalStateException("Invalid RogueAeon.json", invalid);
    }
  }

  private static int number(JsonObject row, String key, boolean required) {
    var value = row.get(key);
    if (value == null && !required) return 0;
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Invalid RogueAeon number: " + key);
    int number = value.getAsBigDecimal().intValueExact();
    if (required ? number <= 0 : number < 0)
      throw new IllegalArgumentException("Invalid RogueAeon number: " + key);
    return number;
  }

  private static void hash(JsonElement value) {
    if (value == null
        || !value.isJsonObject()
        || !value.getAsJsonObject().keySet().equals(java.util.Set.of("Hash")))
      throw new IllegalArgumentException("Invalid RogueAeon text hash");
    var hash = value.getAsJsonObject().get("Hash");
    if (!hash.isJsonPrimitive() || !hash.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Invalid RogueAeon text hash");
    var integer = hash.getAsBigDecimal().toBigIntegerExact();
    if (integer.signum() < 0 || integer.bitLength() > 64)
      throw new IllegalArgumentException("Invalid RogueAeon uint64 hash");
  }

  public static Optional<Row> find(int id) {
    return Optional.ofNullable(catalogue.get(id));
  }

  public static List<Integer> available(emu.lunarcore.game.player.Player player) {
    if (player == null) return List.of();
    synchronized (player) {
      return catalogue.values().stream()
          .filter(row -> row.version() == 1 && RogueUnlocks.open(player, row.unlock()))
          .sorted(java.util.Comparator.comparingInt(Row::sort).thenComparingInt(Row::id))
          .map(Row::id)
          .toList();
    }
  }
}
