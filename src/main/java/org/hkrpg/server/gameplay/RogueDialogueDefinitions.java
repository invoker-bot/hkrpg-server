package org.hkrpg.server.gameplay;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Authored classic-universe NPC dialogues. Effect tables from other versions are not imported. */
public final class RogueDialogueDefinitions {
  public record Option(int id, int display) {}

  public record Dialogue(
      int progress, int unlock, String dialoguePath, String optionPath, List<Option> options) {}

  public record Npc(int id, String type, List<Dialogue> dialogues) {}

  public static final Map<Integer, Npc> npcs = new HashMap<>();

  public static void load(Path root) {
    npcs.clear();
    Path table = root.resolve("ExcelOutput/RogueNPC.json");
    if (!Files.isRegularFile(table)) return;
    try {
      for (var element : read(table).getAsJsonArray()) {
        var row = element.getAsJsonObject();
        int id = integer(row, "RogueNPCID");
        Path file = resolve(root, row.get("NPCJsonPath").getAsString());
        if (!Files.isRegularFile(file)) continue;
        var config = read(file).getAsJsonObject();
        var dialogues = new ArrayList<Dialogue>();
        for (var entry : config.getAsJsonArray("DialogueList")) {
          var dialogue = entry.getAsJsonObject();
          String act = string(dialogue, "DialoguePath"), opt = string(dialogue, "OptionPath");
          var options = new ArrayList<Option>();
          if (!opt.isEmpty() && Files.isRegularFile(resolve(root, opt))) {
            var source = read(resolve(root, opt)).getAsJsonObject();
            if (source.has("OptionList")) {
              var seen = new HashSet<Integer>();
              for (var item : source.getAsJsonArray("OptionList")) {
                var option = item.getAsJsonObject();
                int optionId = integer(option, "OptionID");
                if (optionId <= 0 || !seen.add(optionId))
                  throw new IllegalStateException("Ambiguous rogue option: " + opt);
                options.add(new Option(optionId, integer(option, "DisplayID")));
              }
            }
          }
          dialogues.add(
              new Dialogue(
                  integer(dialogue, "DialogueProgress"),
                  integer(dialogue, "UnlockID"),
                  act,
                  opt,
                  List.copyOf(options)));
        }
        if (id <= 0
            || npcs.putIfAbsent(
                    id, new Npc(id, string(config, "DialogueType"), List.copyOf(dialogues)))
                != null) throw new IllegalStateException("Ambiguous rogue NPC: " + id);
      }
    } catch (Exception invalid) {
      throw new IllegalStateException("Cannot load authored rogue dialogues", invalid);
    }
  }

  private static Path resolve(Path root, String path) {
    Path base = root.toAbsolutePath().normalize(), result = base.resolve(path).normalize();
    if (!result.startsWith(base))
      throw new IllegalArgumentException("Rogue dialogue path escapes resource root");
    return result;
  }

  private static JsonElement read(Path path) throws java.io.IOException {
    try (var reader = Files.newBufferedReader(path)) {
      return JsonParser.parseReader(reader);
    }
  }

  private static int integer(JsonObject row, String name) {
    return row.has(name) ? row.get(name).getAsBigDecimal().intValueExact() : 0;
  }

  private static String string(JsonObject row, String name) {
    return row.has(name) ? row.get(name).getAsString() : "";
  }

  private RogueDialogueDefinitions() {}
}
