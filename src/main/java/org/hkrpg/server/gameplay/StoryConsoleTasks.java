package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.game.scene.Scene;
import java.util.Set;

/** Recognizes complete authored enable scripts, never arbitrary script prefixes or unknown effects. */
final class StoryConsoleTasks {
  private static final String TYPE = "RPG.GameCore.";
  private static final JsonElement ENABLE = JsonParser.parseString("{\"$type\":\"RPG.GameCore.ChangePropState\"}");

  static boolean enables(Scene scene, JsonElement script) {
    try {
      var root = script.getAsJsonObject();
      if (!root.keySet().equals(Set.of("OnInitSequece", "OnStartSequece", "Type"))
          || !"Mission".equals(root.get("Type").getAsString()) || !root.getAsJsonArray("OnInitSequece").isEmpty()) return false;
      int enables = 0;
      for (var value : root.getAsJsonArray("OnStartSequece")) {
        var sequence = value.getAsJsonObject();
        if (!(sequence.keySet().equals(Set.of("TaskList"))
            || sequence.keySet().equals(Set.of("TaskList", "Order")) && integer(sequence.get("Order")) == 1)) return false;
        var tasks = sequence.getAsJsonArray("TaskList");
        if (tasks.size() == 1 && ENABLE.equals(tasks.get(0))) enables++;
        else if (tasks.isEmpty()) { if (sequence.has("Order")) return false; }
        else if (cleanup(scene, tasks)) { /* Same transient barrier created and immediately removed. */ }
        else if (!sequence.has("Order") && tasks.size() == 1 && hint(scene, tasks.get(0))) { /* Parallel hint only. */ }
        else return false;
      }
      return enables == 1;
    } catch (RuntimeException malformed) { return false; }
  }

  private static boolean cleanup(Scene scene, JsonArray tasks) {
    if (tasks.size() != 2) return false;
    var create = tasks.get(0).getAsJsonObject();
    if (!create.keySet().equals(Set.of("$type", "GroupID", "GroupPropID"))
        || !(TYPE + "CreateProp").equals(create.get("$type").getAsString())) return false;
    int group = fixed(create.get("GroupID")), instance = fixed(create.get("GroupPropID"));
    var definition = scene.getResourceGroups().get(group);
    if (definition == null) return false;
    var matches = definition.getPropList().stream().filter(p -> p.getID() == instance).toList();
    if (matches.size() != 1) return false;
    var barrier = matches.getFirst();
    if (barrier.isLoadOnInitial() || barrier.isIsDelete() || barrier.isIsClientOnly() || barrier.getPropID() != 100006
        || !"Config/Level/Props/Chap00/InitLevelGraph_Prop_Chap00_BlockLine_01.json".equals(barrier.getInitLevelGraph())) return false;
    return JsonParser.parseString("""
        {"$type":"RPG.GameCore.DestroyProp","TargetType":{"$type":"RPG.GameCore.TargetFetchAdvPropEx"},
         "ID":%s,"GroupID":%s}
        """.formatted(create.get("GroupPropID"), create.get("GroupID"))).equals(tasks.get(1));
  }

  private static boolean hint(Scene scene, JsonElement value) {
    var trigger = value.getAsJsonObject();
    var target = trigger.getAsJsonObject("TargetType").getAsJsonObject("SinglePropID");
    int group = fixed(target.get("GroupID")), instance = fixed(target.get("ID"));
    var definition = scene.getResourceGroups().get(group);
    if (definition == null) return false;
    var matches = definition.getPropList().stream().filter(p -> p.getID() == instance).toList();
    if (matches.size() != 1 || matches.getFirst().getPropID() != 100
        || !matches.getFirst().isLoadOnInitial() || matches.getFirst().isIsDelete()) return false;
    var enter = trigger.getAsJsonArray("OnTriggerEnter");
    if (enter.size() != 1 || !talk(enter.get(0))) return false;
    return JsonParser.parseString("""
        {"$type":"RPG.GameCore.PropSetupTrigger","TargetType":{"$type":"RPG.GameCore.TargetFetchAdvPropEx",
          "FetchType":"SinglePropByPropID","SinglePropID":%s},
          "TargetGroupID":{"IsDynamic":false,"FixedValue":{"Value":0}},
          "TargetID":{"IsDynamic":false,"FixedValue":{"Value":0}},"TargetTypes":[],
          "ColliderRelativePath":"Trigger","DestroyAfterTriggered":true,"OnTriggerEnter":%s}
        """.formatted(target, enter)).equals(value);
  }

  private static boolean talk(JsonElement value) {
    var talk = value.getAsJsonObject();
    if (!talk.keySet().equals(Set.of("$type", "SimpleTalkList"))
        || !(TYPE + "PlayMissionTalk").equals(talk.get("$type").getAsString())) return false;
    var lines = talk.getAsJsonArray("SimpleTalkList");
    if (lines.isEmpty()) return false;
    for (var valueLine : lines) {
      var line = valueLine.getAsJsonObject();
      if (!line.keySet().equals(Set.of("TalkSentenceID", "ProtectTime", "ForceToNextTime"))
          || integer(line.get("TalkSentenceID")) <= 0 || !duration(line.get("ProtectTime"))
          || !duration(line.get("ForceToNextTime"))) return false;
    }
    return true;
  }

  private static int fixed(JsonElement value) {
    var object = value.getAsJsonObject();
    int id = integer(object.getAsJsonObject("FixedValue").get("Value"));
    return id > 0 && JsonParser.parseString("{\"IsDynamic\":false,\"FixedValue\":{\"Value\":" + id + "}}")
        .equals(value) ? id : 0;
  }
  private static int integer(JsonElement value) {
    return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsBigDecimal().intValueExact() : 0;
  }
  private static boolean duration(JsonElement value) {
    return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && value.getAsBigDecimal().signum() >= 0;
  }
  private StoryConsoleTasks() {}
}
