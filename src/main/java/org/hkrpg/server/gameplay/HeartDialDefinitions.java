package org.hkrpg.server.gameplay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import emu.lunarcore.data.ResourceLoader;
import java.nio.file.*;
import java.util.*;

/** Authored clockwork NPC bindings and dialogue rows; no reference executable is loaded. */
public final class HeartDialDefinitions {
  public record Emotion(
      @SerializedName("BNFEAFPEOIE") String type, @SerializedName("POAIHJCEKCF") int dialogue) {}

  public record Script(
      int ScriptID,
      String DefaultEmoType,
      List<Emotion> TotalEmoInfoList,
      List<String> StepList,
      List<String> MissingEmoList,
      int RaidID,
      int MissingDialogueID,
      int FullDialogueID,
      int LockDialogueID,
      int UnLockDialogueID,
      int ControlDialogueID) {}

  public record Dialogue(int ID, List<Integer> ControlTalkList) {}

  public record Npc(
      int FloorID, int GroupID, int InstanceID, List<Integer> ScriptIDList, int DefaultScriptID) {
    public String key() {
      return FloorID + ":" + GroupID + ":" + InstanceID;
    }
  }

  public record Constant(String ConstValueName, JsonObject Value) {}

  public static final Map<Integer, Script> scripts = new HashMap<>();
  public static final Map<Integer, Dialogue> dialogues = new HashMap<>();
  public static final Map<String, Npc> npcs = new HashMap<>();
  public static final Map<String, JsonObject> constants = new HashMap<>();
  public static final Map<Integer, JsonObject> conditions = new HashMap<>();
  public static final Map<Integer, JsonObject> scriptConditions = new HashMap<>();

  public static void load(Path root) {
    scripts.clear();
    dialogues.clear();
    npcs.clear();
    constants.clear();
    conditions.clear();
    scriptConditions.clear();
    for (var row : rows(root, "HeartDialScript", Script.class)) put(scripts, row.ScriptID(), row);
    for (var row : rows(root, "HeartDialDialogue", Dialogue.class)) put(dialogues, row.ID(), row);
    for (var row : rows(root, "HeartDialNpc", Npc.class)) put(npcs, row.key(), row);
    for (var row : rows(root, "HeartDialConstValue", Constant.class))
      put(constants, row.ConstValueName(), row.Value());
    for (var row : rows(root, "HeartDialCondition", JsonObject.class))
      put(conditions, row.get("ID").getAsBigDecimal().intValueExact(), row);
    for (var row : rows(root, "HeartDialScriptCondition", JsonObject.class))
      put(scriptConditions, row.get("ScriptID").getAsBigDecimal().intValueExact(), row);
  }

  private static <K, V> void put(Map<K, V> target, K key, V value) {
    if (key == null || value == null || target.putIfAbsent(key, value) != null)
      throw new IllegalStateException("Conflicting clockwork resource: " + key);
  }

  private static <T> List<T> rows(Path root, String name, Class<T> type) {
    Path file = root.resolve("ExcelOutput/" + name + ".json");
    if (!Files.isRegularFile(file)) return List.of();
    try (var reader = Files.newBufferedReader(file)) {
      var result = new ArrayList<T>();
      for (var row : JsonParser.parseReader(reader).getAsJsonArray())
        result.add(ResourceLoader.getGson().fromJson(row, type));
      return result;
    } catch (Exception invalid) {
      throw new IllegalStateException("Cannot load clockwork resource: " + name, invalid);
    }
  }

  private HeartDialDefinitions() {}
}
