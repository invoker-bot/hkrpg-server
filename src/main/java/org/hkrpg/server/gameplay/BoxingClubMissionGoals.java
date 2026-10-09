package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;

/** Exact original boxing story goals; client performance sequences remain a separate boundary. */
final class BoxingClubMissionGoals {
  private static final Gson GSON = new Gson();
  private static final Map<Integer, JsonObject> ROWS = new TreeMap<>();
  private static final Map<Integer, MissionInfo.SubMissionInfo> SOURCES = sources();

  private static Map<Integer, MissionInfo.SubMissionInfo> sources() {
    var result = new TreeMap<Integer, MissionInfo.SubMissionInfo>();
    var scripts =
        JsonParser.parseString(
                """
{
  "201073101": {
    "OnInitSequece": [],
    "OnStartSequece": [
      {
        "TaskList": [
          {
            "$type": "RPG.GameCore.WaitCustomString",
            "CustomString": {
              "Value": "Talk_201073101"
            }
          },
          {
            "$type": "RPG.GameCore.FinishPerformanceMission",
            "Key": "Mission_201073101"
          }
        ]
      }
    ],
    "Type": "Mission"
  },
  "201073201": {
    "OnInitSequece": [],
    "OnStartSequece": [
      {
        "TaskList": [
          {
            "$type": "RPG.GameCore.WaitCustomString",
            "CustomString": {
              "Value": "Talk_201073201"
            }
          },
          {
            "$type": "RPG.GameCore.FinishPerformanceMission",
            "Key": "Mission_201073201"
          }
        ]
      }
    ],
    "Type": "Mission"
  },
  "201073301": {
    "OnInitSequece": [],
    "OnStartSequece": [
      {
        "TaskList": [
          {
            "$type": "RPG.GameCore.WaitCustomString",
            "CustomString": {
              "Value": "Talk_201073301"
            }
          },
          {
            "$type": "RPG.GameCore.FinishPerformanceMission",
            "Key": "Mission_201073301"
          }
        ]
      }
    ],
    "Type": "Mission"
  },
  "201073401": {
    "OnInitSequece": [],
    "OnStartSequece": [
      {
        "TaskList": [
          {
            "$type": "RPG.GameCore.WaitCustomString",
            "CustomString": {
              "Value": "Talk_201073401"
            }
          },
          {
            "$type": "RPG.GameCore.FinishPerformanceMission",
            "Key": "Mission_201073401"
          }
        ]
      }
    ],
    "Type": "Mission"
  }
}
""")
            .getAsJsonObject();
    for (var row :
        JsonParser.parseString(
                """
[
  {
    "ID": 201073101,
    "MainMissionID": 2010731,
    "MissionJsonPath": "Config/Level/Mission/2010731/Mission_201073101.json",
    "LevelPlaneID": 10102,
    "LevelFloorID": 10102004,
    "TakeParamIntList": [],
    "FinishType": "BoxingClubAccomplish",
    "ParamType": "LessEqual",
    "ParamInt2": 1,
    "Progress": 1,
    "IsBackTrack": true,
    "IsShow": false,
    "WayPointFloorID": 10102004
  },
  {
    "ID": 201073201,
    "MainMissionID": 2010732,
    "MissionJsonPath": "Config/Level/Mission/2010732/Mission_201073201.json",
    "LevelPlaneID": 10102,
    "LevelFloorID": 10102004,
    "TakeParamIntList": [],
    "FinishType": "BoxingClubAccomplish",
    "ParamType": "LessEqual",
    "ParamInt2": 2,
    "Progress": 1,
    "IsBackTrack": true,
    "IsShow": false,
    "WayPointFloorID": 10102004
  },
  {
    "ID": 201073301,
    "MainMissionID": 2010733,
    "MissionJsonPath": "Config/Level/Mission/2010733/Mission_201073301.json",
    "LevelPlaneID": 10102,
    "LevelFloorID": 10102004,
    "TakeParamIntList": [],
    "FinishType": "BoxingClubAccomplish",
    "ParamType": "LessEqual",
    "ParamInt2": 3,
    "Progress": 1,
    "IsBackTrack": true,
    "IsShow": false,
    "WayPointFloorID": 10102004
  },
  {
    "ID": 201073401,
    "MainMissionID": 2010734,
    "MissionJsonPath": "Config/Level/Mission/2010734/Mission_201073401.json",
    "LevelPlaneID": 10102,
    "LevelFloorID": 10102004,
    "TakeParamIntList": [],
    "FinishType": "BoxingClubAccomplish",
    "ParamType": "LessEqual",
    "ParamInt2": 4,
    "Progress": 1,
    "IsBackTrack": true,
    "IsShow": false,
    "WayPointFloorID": 10102004
  },
  {
    "ID": 201073501,
    "MainMissionID": 2010735,
    "LevelPlaneID": 10102,
    "LevelFloorID": 10102004,
    "TakeType": "Auto",
    "TakeParamIntList": [],
    "FinishType": "BoxingClubAccomplish",
    "ParamType": "LessEqual",
    "ParamInt2": 5,
    "ParamIntList": [],
    "Progress": 1,
    "IsBackTrack": true,
    "IsShow": false,
    "WayPointFloorID": 10102004
  },
  {
    "ID": 801520101,
    "MainMissionID": 8015201,
    "LevelPlaneID": 10102,
    "LevelFloorID": 10102004,
    "TakeType": "Auto",
    "TakeParamIntList": [
      8015201
    ],
    "FinishType": "BoxingClubAccomplish",
    "ParamType": "LessEqual",
    "ParamInt2": 12,
    "FinishActionList": [
      {
        "FinishActionType": "SetFloorSavedValue",
        "FinishActionParaString": [
          "10102",
          "10102004",
          "BoxClub2Hide",
          "1"
        ]
      }
    ],
    "Progress": 1,
    "CustomValueList": [],
    "CustomValueReward": [],
    "RequiredNPCSeriesIDList": [],
    "IsShow": false,
    "WayPointFloorID": 10102004
  },
  {
    "ID": 801520301,
    "MainMissionID": 8015203,
    "LevelPlaneID": 10102,
    "LevelFloorID": 10102004,
    "TakeType": "Auto",
    "TakeParamIntList": [
      8015203
    ],
    "FinishType": "BoxingClubAccomplish",
    "ParamType": "LessEqual",
    "ParamInt2": 12,
    "FinishActionList": [],
    "Progress": 1,
    "CustomValueList": [],
    "CustomValueReward": [],
    "RequiredNPCSeriesIDList": [],
    "IsShow": false,
    "WayPointFloorID": 10102004
  }
]
""")
            .getAsJsonArray()) {
      var sub = GSON.fromJson(row, MissionInfo.SubMissionInfo.class);
      ROWS.put(sub.getId(), row.getAsJsonObject().deepCopy());
      var script = scripts.get(String.valueOf(sub.getId()));
      if (script != null) {
        var task = GSON.fromJson(script, MissionTaskInfo.class);
        task.retainSourceDefinition(script);
        sub.setTask(task);
      }
      result.put(sub.getId(), sub);
    }
    return Map.copyOf(result);
  }

  private static boolean source(MissionInfo.SubMissionInfo sub) {
    try {
      if (sub == null) return false;
      var original = SOURCES.get(sub.getId());
      if (original == null || !GSON.toJsonTree(sub).equals(GSON.toJsonTree(original))) return false;
      var path =
          Path.of(LunarCore.getConfig().getResourceDir())
              .resolve(
                  "Config/Level/Mission/"
                      + sub.getMainMissionID()
                      + "/MissionInfo_"
                      + sub.getMainMissionID()
                      + ".partial.json");
      if (!Files.isRegularFile(path) || Files.size(path) > 262144) return false;
      var rows =
          JsonParser.parseString(Files.readString(path))
              .getAsJsonObject()
              .getAsJsonArray("SubMissionList");
      if (rows == null || rows.size() > 2048) return false;
      var matches =
          java.util.stream.StreamSupport.stream(rows.spliterator(), false)
              .filter(
                  row ->
                      row.isJsonObject()
                          && row.getAsJsonObject().has("ID")
                          && row.getAsJsonObject().get("ID").getAsInt() == sub.getId())
              .toList();
      if (matches.size() != 1 || !matches.getFirst().equals(ROWS.get(sub.getId()))) return false;
      if (original.getTask() == null) return sub.getTask() == null;
      if (sub.getTask() == null) return false;
      var text = sub.getTask().getSourceDefinition();
      return text != null
          && text.length() <= 8192
          && JsonParser.parseString(text)
              .equals(JsonParser.parseString(original.getTask().getSourceDefinition()));
    } catch (Exception invalid) {
      return false;
    }
  }

  private static boolean active(Player p, MissionInfo.SubMissionInfo sub) {
    return source(sub)
        && p.getPlaneId() == sub.getLevelPlaneID()
        && p.getFloorId() == sub.getLevelFloorID()
        && MissionStateRecords.mainDoing(p, sub.getMainMissionID())
        && MissionStateRecords.doing(p.getMissionManager().sub(sub.getId()), sub.getId(), 1)
        && p.getMissionManager().canFinishFromAction(sub.getId());
  }

  static boolean declaredStart(MissionInfo.SubMissionInfo sub) {
    try {
      if (sub == null
          || sub.getTakeType() != null
          || !Set.of(201073101, 201073201, 201073301, 201073401).contains(sub.getId())
          || !source(sub)) return false;
      var expected = new JsonObject();
      expected.addProperty("MainMissionID", sub.getMainMissionID());
      var roots = new JsonArray();
      roots.add(sub.getId());
      expected.add("StartSubMissionList", roots);
      expected.add("FinishSubMissionList", roots.deepCopy());
      expected.addProperty("IsShow", false);
      var path =
          Path.of(LunarCore.getConfig().getResourceDir())
              .resolve(
                  "Config/Level/Mission/"
                      + sub.getMainMissionID()
                      + "/MissionInfo_"
                      + sub.getMainMissionID()
                      + ".json");
      if (!Files.isRegularFile(path)
          || Files.size(path) > 8192
          || !JsonParser.parseString(Files.readString(path)).equals(expected)) return false;
      var live = emu.lunarcore.data.GameData.getMissionInfos().get(sub.getMainMissionID());
      if (live == null) return false;
      var actual = GSON.toJsonTree(live).getAsJsonObject();
      actual.remove("SubMissionList");
      var original = GSON.toJsonTree(GSON.fromJson(expected, MissionInfo.class)).getAsJsonObject();
      original.remove("SubMissionList");
      return actual.equals(original);
    } catch (Exception invalid) {
      return false;
    }
  }

  static boolean satisfied(Player p, MissionInfo.SubMissionInfo sub) {
    synchronized (p) {
      if (!active(p, sub)) return false;
      if (sub.isBackTrack()) {
        var completed = p.getGameplay().getBoxingClub().getCompleted().get(sub.getParamInt2());
        return completed != null
            && p.getGameplay()
                .getBoxingClub()
                .validCompletion(BoxingClubRules.load(), completed, p.getUid());
      }
      try {
        var state = p.getGameplay().getBoxingClubResonance();
        var completed = state.getMissionCompletions().get(sub.getId());
        var main = p.getGameplay().getMainMissions().get(sub.getMainMissionID());
        var doing = p.getMissionManager().sub(sub.getId());
        if (main.getObservationKey() == null || doing.getObservationKey() == null) return false;
        return completed != null
            && completed.missionTargetsReady()
            && completed.plane() == sub.getLevelPlaneID()
            && completed.floor() == sub.getLevelFloorID()
            && completed.challenge() == sub.getParamInt2()
            && (main.getObservationKey() + ":" + doing.getObservationKey())
                .equals(completed.missionTargets().get(sub.getId()))
            && state.validCompletion(BoxingClubResonanceRules.load(), completed, p.getUid());
      } catch (RuntimeException invalid) {
        return false;
      }
    }
  }

  static boolean validBindings(Map<Integer, String> targets) {
    if (targets == null || targets.size() > 2) return false;
    try {
      for (var entry : targets.entrySet()) {
        if (!Set.of(801520101, 801520301).contains(entry.getKey()) || entry.getValue() == null)
          return false;
        var pair = entry.getValue().split(":", -1);
        if (pair.length != 2) return false;
        for (var key : pair) if (!UUID.fromString(key).toString().equals(key)) return false;
      }
      return true;
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  /** Acceptance keys and the final-round observer map are saved in the same player document. */
  static BoxingClubResonanceState.Run prepare(Player p, BoxingClubResonanceState.Run run) {
    synchronized (p) {
      if (run.challenge() != 12 || run.index() != 2 || run.missionTargetsReady()) return run;
      var previous = new LinkedHashMap<QuestState, String>();
      var reserved = new LinkedHashMap<QuestState, String>();
      try {
        var bindings = new TreeMap<Integer, String>();
        for (int id : List.of(801520101, 801520301)) {
          var sub = GameplayDefinitions.subs.get(id);
          if (!active(p, sub)) continue;
          var main = p.getGameplay().getMainMissions().get(sub.getMainMissionID());
          var doing = p.getMissionManager().sub(id);
          for (var state : List.of(main, doing)) {
            previous.put(state, state.getObservationKey());
            reserved.put(state, state.captureObservationKey());
          }
          bindings.put(id, main.getObservationKey() + ":" + doing.getObservationKey());
        }
        var next = p.getGameplay().getBoxingClubResonance().copy();
        next.getActive().freezeMissionTargets(bindings);
        BoxingClubResonanceManager.save(p, next);
        return next.getActive();
      } catch (RuntimeException failure) {
        reserved.forEach((state, key) -> state.restoreObservationKey(key, previous.get(state)));
        throw failure;
      }
    }
  }

  private record Target(QuestState main, QuestState sub) {}

  static final class Context {
    private final BoxingClubState.Run first;
    private final BoxingClubResonanceState.Run second;
    private final Map<Integer, Target> targets;

    private Context(
        BoxingClubState.Run first,
        BoxingClubResonanceState.Run second,
        Map<Integer, Target> targets) {
      this.first = first;
      this.second = second;
      this.targets = Map.copyOf(targets);
    }

    boolean matches(
        Player p,
        MissionInfo.SubMissionInfo sub,
        BoxingClubState.Run firstDone,
        BoxingClubResonanceState.Run secondDone) {
      if (sub == null) return false;
      var target = targets.get(sub.getId());
      if (target == null
          || !active(p, sub)
          || p.getGameplay().getMainMissions().get(sub.getMainMissionID()) != target.main()
          || p.getMissionManager().sub(sub.getId()) != target.sub()) return false;
      if (first != null) {
        return firstDone != null
            && secondDone == null
            && first.context(p)
            && firstDone.context(p)
            && firstDone.challenge() == first.challenge()
            && firstDone.sequence() == first.sequence()
            && sub.getParamInt2() == first.challenge()
            && p.getGameplay()
                .getBoxingClub()
                .validCompletion(BoxingClubRules.load(), firstDone, p.getUid());
      }
      return second != null
          && secondDone != null
          && firstDone == null
          && second.context(p)
          && secondDone.context(p)
          && secondDone.challenge() == second.challenge()
          && secondDone.sequence() == second.sequence()
          && sub.getParamInt2() == second.challenge()
          && satisfied(p, sub)
          && p.getGameplay()
              .getBoxingClubResonance()
              .validCompletion(BoxingClubResonanceRules.load(), secondDone, p.getUid());
    }
  }

  static Context capture(Player p, BoxingClubState.Run first, BoxingClubResonanceState.Run second) {
    synchronized (p) {
      if ((first == null) == (second == null)) return null;
      int challenge;
      if (first != null) {
        if (p.getGameplay().getBoxingClub().getActive() != first
            || first.index() != 3
            || !first.context(p)) return null;
        challenge = first.challenge();
      } else {
        if (p.getGameplay().getBoxingClubResonance().getActive() != second
            || second.index() != 2
            || !second.context(p)) return null;
        challenge = second.challenge();
      }
      var targets = new TreeMap<Integer, Target>();
      for (int id : SOURCES.keySet()) {
        var sub = GameplayDefinitions.subs.get(id);
        if (active(p, sub) && sub.getParamInt2() == challenge)
          targets.put(
              id,
              new Target(
                  p.getGameplay().getMainMissions().get(sub.getMainMissionID()),
                  p.getMissionManager().sub(id)));
      }
      return targets.isEmpty() ? null : new Context(first, second, targets);
    }
  }

  private BoxingClubMissionGoals() {}
}
