package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import java.util.*;

/** Only the recovered three-message script has a verified server-side completion gate. */
final class MissionTalkScriptGates {
  private static final Gson GSON = ResourceLoader.getGson();
  private static final int MESSAGE_TALK = 434011114, STUDIO_TALK = 105450340;
  private static final List<Integer> WATCHED = List.of(434011111, 434011112, 434011113);
  private static final Map<Integer, JsonElement> ORIGINAL = originalRows();
  private static final JsonElement SCRIPT =
      JsonParser.parseString(
          """
          {
            "OnInitSequece": [],
            "OnStartSequece": [
              {
                "TaskList": [
                  {
                    "$type": "RPG.GameCore.CollectDataConditions",
                    "TaskIDList": [
                      434011111,
                      434011112,
                      434011113
                    ],
                    "MainMissionIDList": [],
                    "PerformanceIDList": [],
                    "PerformanceIDDsList": [],
                    "CustomValueMainMissionIDList": []
                  },
                  {
                    "$type": "RPG.GameCore.ByCompareSubMissionState",
                    "SubMissionID": 434011111,
                    "SubMissionState": "Finish"
                  },
                  {
                    "$type": "RPG.GameCore.ClientFinishMission",
                    "SubmissionID": 434011114
                  }
                ]
              },
              {
                "TaskList": [
                  {
                    "$type": "RPG.GameCore.CollectDataConditions",
                    "TaskIDList": [
                      434011111,
                      434011112,
                      434011113
                    ],
                    "MainMissionIDList": [],
                    "PerformanceIDList": [],
                    "PerformanceIDDsList": [],
                    "CustomValueMainMissionIDList": []
                  },
                  {
                    "$type": "RPG.GameCore.ByCompareSubMissionState",
                    "SubMissionID": 434011112,
                    "SubMissionState": "Finish"
                  },
                  {
                    "$type": "RPG.GameCore.ClientFinishMission",
                    "SubmissionID": 434011114
                  }
                ]
              },
              {
                "TaskList": [
                  {
                    "$type": "RPG.GameCore.CollectDataConditions",
                    "TaskIDList": [
                      434011111,
                      434011112,
                      434011113
                    ],
                    "MainMissionIDList": [],
                    "PerformanceIDList": [],
                    "PerformanceIDDsList": [],
                    "CustomValueMainMissionIDList": []
                  },
                  {
                    "$type": "RPG.GameCore.ByCompareSubMissionState",
                    "SubMissionID": 434011113,
                    "SubMissionState": "Finish"
                  },
                  {
                    "$type": "RPG.GameCore.ClientFinishMission",
                    "SubmissionID": 434011114
                  }
                ]
              }
            ],
            "Type": "Mission"
          }
""");

  private static Map<Integer, JsonElement> originalRows() {
    var result = new HashMap<Integer, JsonElement>();
    for (var raw :
        JsonParser.parseString(
                """
          [
            {
              "ID": 434011107,
              "MainMissionID": 4340111,
              "MissionJsonPath": "Config/Level/Mission/4340111/Mission_434011107.json",
              "LevelPlaneID": 20321,
              "LevelFloorID": 20321001,
              "TakeType": "AnySequence",
              "TakeParamIntList": [
                434011103
              ],
              "FinishType": "Talk",
              "ParamType": "Equal",
              "ParamStr1": "Mission_434011107",
              "ParamIntList": [],
              "ParamItemList": [],
              "FinishActionList": [],
              "Progress": 1,
              "CustomValueList": [
                "OldValue_0"
              ],
              "CustomValueReward": [],
              "GroupIDList": [
                210
              ],
              "RequiredNPCSeriesIDList": [],
              "IsShow": false,
              "GotoParam": []
            },
            {
              "ID": 434011111,
              "MainMissionID": 4340111,
              "LevelPlaneID": 20321,
              "LevelFloorID": 20321001,
              "TakeType": "AnySequence",
              "TakeParamIntList": [
                434011108
              ],
              "TakeParamStrList": [],
              "FinishType": "MessageSectionFinish",
              "ParamType": "Equal",
              "ParamInt1": 1306100,
              "ParamIntList": [],
              "ParamItemList": [],
              "FinishActionList": [],
              "Progress": 1,
              "CustomValueList": [],
              "CustomValueReward": [],
              "GroupIDList": [],
              "RequiredNPCSeriesIDList": [],
              "IsShow": false,
              "GotoParam": [],
              "StoryLineIDList": [],
              "IgnoreVerseParamList": []
            },
            {
              "ID": 434011112,
              "MainMissionID": 4340111,
              "LevelPlaneID": 20321,
              "LevelFloorID": 20321001,
              "TakeType": "AnySequence",
              "TakeParamIntList": [
                434011109
              ],
              "TakeParamStrList": [],
              "FinishType": "MessageSectionFinish",
              "ParamType": "Equal",
              "ParamInt1": 1306200,
              "ParamIntList": [],
              "ParamItemList": [],
              "FinishActionList": [],
              "Progress": 1,
              "CustomValueList": [],
              "CustomValueReward": [],
              "GroupIDList": [],
              "RequiredNPCSeriesIDList": [],
              "IsShow": false,
              "GotoParam": [],
              "StoryLineIDList": [],
              "IgnoreVerseParamList": []
            },
            {
              "ID": 434011113,
              "MainMissionID": 4340111,
              "LevelPlaneID": 20321,
              "LevelFloorID": 20321001,
              "TakeType": "AnySequence",
              "TakeParamIntList": [
                434011110
              ],
              "TakeParamStrList": [],
              "FinishType": "MessageSectionFinish",
              "ParamType": "Equal",
              "ParamInt1": 1306300,
              "ParamIntList": [],
              "ParamItemList": [],
              "FinishActionList": [],
              "Progress": 1,
              "CustomValueList": [],
              "CustomValueReward": [],
              "GroupIDList": [],
              "RequiredNPCSeriesIDList": [],
              "IsShow": false,
              "GotoParam": [],
              "StoryLineIDList": [],
              "IgnoreVerseParamList": []
            },
            {
              "ID": 434011114,
              "MainMissionID": 4340111,
              "MissionJsonPath": "Config/Level/Mission/4340111/Mission_434011114.json",
              "LevelPlaneID": 20321,
              "LevelFloorID": 20321001,
              "TakeType": "AnySequence",
              "TakeParamIntList": [
                434011107
              ],
              "TakeParamStrList": [],
              "FinishType": "Talk",
              "ParamType": "Equal",
              "ParamStr1": "Mission_434011114",
              "ParamIntList": [],
              "ParamItemList": [],
              "FinishActionList": [],
              "Progress": 1,
              "CustomValueList": [],
              "CustomValueReward": [],
              "GroupIDList": [],
              "RequiredNPCSeriesIDList": [],
              "IsShow": false,
              "GotoParam": [],
              "StoryLineIDList": [],
              "IgnoreVerseParamList": []
            }
          ]
""")
            .getAsJsonArray()) {
      var definition = GSON.fromJson(raw, MissionInfo.SubMissionInfo.class);
      result.put(definition.getId(), GSON.toJsonTree(definition));
    }
    return Map.copyOf(result);
  }

  private static boolean original(int id) {
    var definition = GameplayDefinitions.subs.get(id);
    if (definition == null || definition.getId() != id) return false;
    var actual = GSON.toJsonTree(definition).getAsJsonObject();
    actual.remove("task");
    return ORIGINAL.get(id).equals(actual);
  }

  static boolean allows(Player player, int id) {
    if (id == DailyMissionFollowupSources.TALK && DailyMissionFollowupRules.handles(DailyMissionFollowupSources.MAIN))
      return DailyMissionFollowupRules.loaded(player);
    if (id == 300060001) return DailyMissionMessageRules.initialTalk(player);
    if (id == STUDIO_TALK) return false;
    if (id != MESSAGE_TALK) return true;
    synchronized (player) {
      if (!original(MESSAGE_TALK)
          || !original(434011107)
          || player.getPlaneId() != 20321
          || !MissionStateRecords.subCompleted(player, 434011107)
          || !WATCHED.stream().allMatch(MissionTalkScriptGates::original)) return false;
      var task = GameplayDefinitions.subs.get(id).getTask();
      if (task == null
          || task.getSourceDefinition() == null
          || task.getSourceDefinition().isBlank()
          || task.getSourceDefinition().length() > 16384) return false;
      try {
        if (!SCRIPT.equals(JsonParser.parseString(task.getSourceDefinition()))) return false;
      } catch (JsonParseException | IllegalStateException invalid) {
        return false;
      }
      return WATCHED.stream()
          .anyMatch(watched -> MissionStateRecords.subCompleted(player, watched));
    }
  }

  private MissionTalkScriptGates() {}
}
