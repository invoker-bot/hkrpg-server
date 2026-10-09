package org.hkrpg.server.gameplay;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Client-rendered intro dialogue resources; gameplay effects require their own server handlers. */
public final class FirstTalkDefinitions {
  public record Performance(int id, String path, Set<String> unsupportedTasks, boolean readable) {
    public boolean reportable() {
      return readable && unsupportedTasks.isEmpty();
    }
  }

  public static final Map<Integer, Performance> performances = new HashMap<>();
  private static final Set<String> PRESENTATION =
      Set.of(
          "LevelPerformanceInitialize",
          "AdvNpcFaceToPlayer",
          "PlayFullScreenTransfer",
          "ScrTrfActTaskList",
          "ActiveVirtualCamera",
          "PlayAndWaitSimpleTalk",
          "EndPerformance",
          "WaitPerformanceEnd",
          "FinishLevelGraph",
          "AdvEntityFaceTo",
          "TargetFetchAdvLocalPlayer",
          "TargetFetchAdvNpcEx",
          "CharacterTriggerFreeStyle",
          "PlayOptionTalk",
          "OptionTalkInfo",
          "WaitCustomString",
          "TriggerCustomString",
          "ScrTrfActPerformanceGroup",
          "TriggerSound",
          "TargetFetchDialogueEntity",
          "PerformanceTransition",
          "TargetFetchAdvPropEx",
          "PlayScreenTransfer",
          "StartDialogueEntityInteract",
          "SharedString",
          "SharedFloat",
          "EndDialogueEntityInteract",
          "SetTextJoinValue",
          "WaitStreamingJobFinished",
          "TargetFetchAnchor",
          "SetEntityVisible",
          "SwitchCharacterAnchorV2",
          "PredicateTaskList",
          "ByCompareMainMissionState",
          "ByCompareSubMissionState",
          "ByAnd");

  public static void load(Path resourceRoot) {
    performances.clear();
    Path root = resourceRoot.toAbsolutePath().normalize();
    Path table = root.resolve("ExcelOutput/PerformanceE.json");
    if (!Files.isRegularFile(table)) return;
    try (var reader = Files.newBufferedReader(table)) {
      for (var entry : JsonParser.parseReader(reader).getAsJsonArray()) {
        var row = entry.getAsJsonObject();
        if (!row.has("IsIntroDialogue") || !row.get("IsIntroDialogue").getAsBoolean()) continue;
        int id = row.get("PerformanceID").getAsInt();
        String relative = row.get("PerformancePath").getAsString();
        Path script = root.resolve(relative).normalize();
        var tasks = new HashSet<String>();
        var effects = new HashSet<String>();
        boolean readable =
            id > 0
                && script.startsWith(root.resolve("Config/Level/Mission"))
                && Files.isRegularFile(script);
        if (readable) {
          try (var source = Files.newBufferedReader(script)) {
            collect(JsonParser.parseReader(source), tasks, effects, 0);
          }
          readable = tasks.contains("RPG.GameCore.EndPerformance");
        }
        tasks.removeIf(
            t ->
                t.startsWith("RPG.GameCore.")
                    && PRESENTATION.contains(t.substring("RPG.GameCore.".length())));
        tasks.addAll(effects);
        if (performances.putIfAbsent(id, new Performance(id, relative, Set.copyOf(tasks), readable))
            != null) throw new IllegalStateException("Conflicting intro performance: " + id);
      }
    } catch (Exception e) {
      throw new IllegalStateException("Cannot load intro performances", e);
    }
  }

  private static void collect(
      JsonElement value, Set<String> types, Set<String> effects, int depth) {
    if (depth > 100) throw new IllegalStateException("Intro script nesting exceeds limit");
    if (value.isJsonObject()) {
      var object = value.getAsJsonObject();
      if (object.has("$type")) {
        String type = object.get("$type").getAsString();
        types.add(type);
        if (type.equals("RPG.GameCore.PerformanceTransition")) {
          for (String field : List.of("CreateNpcList", "DestroyNpcList", "CaptureNpcList"))
            if (object.has(field)
                && (!object.get(field).isJsonArray() || !object.getAsJsonArray(field).isEmpty()))
              effects.add(type);
          for (String field :
              List.of("CreateProp", "DestroyProp", "AdvCreateGroupEntity", "AdvDestroyGroupEntity"))
            if (object.has(field) && !disabled(object.get(field))) effects.add(type);
        }
      }
      for (var field : object.entrySet()) collect(field.getValue(), types, effects, depth + 1);
    } else if (value.isJsonArray())
      for (var child : value.getAsJsonArray()) collect(child, types, effects, depth + 1);
  }

  private static boolean disabled(JsonElement value) {
    if (!value.isJsonObject()) return false;
    var object = value.getAsJsonObject();
    var enabled = object.get("TaskEnabled");
    return enabled != null
        && enabled.isJsonPrimitive()
        && enabled.getAsJsonPrimitive().isBoolean()
        && !enabled.getAsBoolean();
  }

  private FirstTalkDefinitions() {}
}
