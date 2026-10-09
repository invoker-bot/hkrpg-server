package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.config.MissionTaskInfo;
import emu.lunarcore.game.player.Player;
import java.nio.file.Path;
import java.util.*;

/** Five authored backtracking targets consume only validated, settled ending records. */
final class ClockParkMissionProgress {
  private static final Gson GSON = new Gson();
  private static final JsonElement SCRIPT =
      JsonParser.parseString(
          """
{
  "OnInitSequece": [],
  "OnStartSequece": [
    {
      "TaskList": [
        {
          "$type": "RPG.GameCore.SetFloorCustomString",
          "Name": {
            "Value": "ScreenContent"
          },
          "Value": {
            "Value": "Idle"
          }
        }
      ]
    }
  ],
  "Type": "Mission"
}
""");
  private static final Map<Integer, MissionInfo.SubMissionInfo> SOURCES = sources();

  private static Map<Integer, MissionInfo.SubMissionInfo> sources() {
    var result = new HashMap<Integer, MissionInfo.SubMissionInfo>();
    for (var row :
        JsonParser.parseString(
                """
[
  {
    "ID": 802210103,
    "MainMissionID": 8022101,
    "LevelPlaneID": 20321,
    "LevelFloorID": 20321001,
    "TakeType": "AnySequence",
    "TakeParamIntList": [
      802210102
    ],
    "FinishType": "ClockParkProgress",
    "ParamType": "GreaterEqual",
    "ParamInt1": 2000,
    "ParamIntList": [],
    "ParamItemList": [],
    "FinishActionList": [
      {
        "FinishActionType": "SetFloorSavedValue",
        "FinishActionPara": [],
        "FinishActionParaString": [
          "20321",
          "20321001",
          "Crowd",
          "4"
        ]
      },
      {
        "FinishActionType": "SetFloorSavedValue",
        "FinishActionPara": [],
        "FinishActionParaString": [
          "20321",
          "20321001",
          "FSV_Post01",
          "1"
        ]
      }
    ],
    "Progress": 1,
    "IsBackTrack": true,
    "CustomValueList": [],
    "CustomValueReward": [],
    "GroupIDList": [],
    "RequiredNPCSeriesIDList": [],
    "WayPointType": "NPC",
    "GotoParam": [],
    "WayPointFloorID": 20321001,
    "WayPointGroupID": 196,
    "WayPointEntityID": 400001
  },
  {
    "ID": 802210201,
    "MainMissionID": 8022102,
    "LevelPlaneID": 20321,
    "LevelFloorID": 20321001,
    "TakeType": "AnySequence",
    "TakeParamIntList": [
      802210209
    ],
    "FinishType": "ClockParkProgress",
    "ParamType": "GreaterEqual",
    "ParamInt1": 4000,
    "ParamIntList": [],
    "ParamItemList": [],
    "FinishActionList": [
      {
        "FinishActionType": "SetFloorSavedValue",
        "FinishActionPara": [],
        "FinishActionParaString": [
          "20321",
          "20321001",
          "Crowd",
          "5"
        ]
      },
      {
        "FinishActionType": "SetFloorSavedValue",
        "FinishActionPara": [],
        "FinishActionParaString": [
          "20321",
          "20321001",
          "Activity_Raid1",
          "1"
        ]
      },
      {
        "FinishActionType": "SetFloorSavedValue",
        "FinishActionPara": [],
        "FinishActionParaString": [
          "20321",
          "20321001",
          "FSV_Post06",
          "1"
        ]
      }
    ],
    "Progress": 1,
    "IsBackTrack": true,
    "CustomValueList": [],
    "CustomValueReward": [],
    "GroupIDList": [],
    "RequiredNPCSeriesIDList": [],
    "WayPointType": "NPC",
    "GotoParam": [],
    "WayPointFloorID": 20321001,
    "WayPointGroupID": 196,
    "WayPointEntityID": 400001
  },
  {
    "ID": 802210205,
    "MainMissionID": 8022102,
    "LevelPlaneID": 20321,
    "LevelFloorID": 20321001,
    "TakeType": "AnySequence",
    "TakeParamIntList": [
      802210210
    ],
    "FinishType": "ClockParkProgress",
    "ParamType": "GreaterEqual",
    "ParamInt1": 6000,
    "ParamIntList": [],
    "ParamItemList": [],
    "FinishActionList": [
      {
        "FinishActionType": "SetFloorSavedValue",
        "FinishActionPara": [],
        "FinishActionParaString": [
          "20321",
          "20321001",
          "Crowd",
          "6"
        ]
      },
      {
        "FinishActionType": "SetFloorSavedValue",
        "FinishActionPara": [],
        "FinishActionParaString": [
          "20321",
          "20321001",
          "Activity_Raid3",
          "1"
        ]
      },
      {
        "FinishActionType": "SetFloorSavedValue",
        "FinishActionPara": [],
        "FinishActionParaString": [
          "20321",
          "20321001",
          "FSV_Post05",
          "1"
        ]
      }
    ],
    "Progress": 1,
    "IsBackTrack": true,
    "CustomValueList": [],
    "CustomValueReward": [],
    "GroupIDList": [],
    "RequiredNPCSeriesIDList": [],
    "WayPointType": "NPC",
    "GotoParam": [],
    "WayPointFloorID": 20321001,
    "WayPointGroupID": 196,
    "WayPointEntityID": 400001
  },
  {
    "ID": 802210207,
    "MainMissionID": 8022102,
    "LevelPlaneID": 20321,
    "LevelFloorID": 20321001,
    "TakeType": "AnySequence",
    "TakeParamIntList": [
      802210211
    ],
    "FinishType": "ClockParkProgress",
    "ParamType": "GreaterEqual",
    "ParamInt1": 8000,
    "ParamIntList": [],
    "ParamItemList": [],
    "FinishActionList": [
      {
        "FinishActionType": "SetFloorSavedValue",
        "FinishActionPara": [],
        "FinishActionParaString": [
          "20321",
          "20321001",
          "Crowd",
          "7"
        ]
      },
      {
        "FinishActionType": "SetFloorSavedValue",
        "FinishActionPara": [],
        "FinishActionParaString": [
          "20321",
          "20321001",
          "FSV_Post04",
          "1"
        ]
      }
    ],
    "Progress": 1,
    "IsBackTrack": true,
    "CustomValueList": [],
    "CustomValueReward": [],
    "GroupIDList": [],
    "RequiredNPCSeriesIDList": [],
    "IsShowFinishEffect": 1,
    "WayPointType": "NPC",
    "GotoParam": [],
    "WayPointFloorID": 20321001,
    "WayPointGroupID": 196,
    "WayPointEntityID": 400001
  },
  {
    "ID": 802210401,
    "MainMissionID": 8022104,
    "MissionJsonPath": "Config/Level/Mission/8022104/Mission_802210401.json",
    "LevelPlaneID": 20321,
    "LevelFloorID": 20321001,
    "TakeType": "Auto",
    "TakeParamIntList": [
      8022104
    ],
    "FinishType": "ClockParkProgress",
    "ParamType": "GreaterEqual",
    "ParamInt1": 10000,
    "ParamIntList": [],
    "ParamItemList": [],
    "FinishActionList": [
      {
        "FinishActionType": "SetFloorSavedValue",
        "FinishActionPara": [],
        "FinishActionParaString": [
          "20321",
          "20321001",
          "Crowd",
          "8"
        ]
      },
      {
        "FinishActionType": "SetFloorSavedValue",
        "FinishActionPara": [],
        "FinishActionParaString": [
          "20321",
          "20321001",
          "FSV_Post03",
          "1"
        ]
      }
    ],
    "Progress": 1,
    "IsBackTrack": true,
    "CustomValueList": [],
    "CustomValueReward": [],
    "GroupIDList": [],
    "RequiredNPCSeriesIDList": [],
    "IsShowStartHint": "New",
    "WayPointType": "NPC",
    "GotoParam": [],
    "WayPointFloorID": 20321001,
    "WayPointGroupID": 196,
    "WayPointEntityID": 400001
  }
]
""")
            .getAsJsonArray()) {
      var sub = GSON.fromJson(row, MissionInfo.SubMissionInfo.class);
      if (sub.getId() == 802210401) {
        var task = GSON.fromJson(SCRIPT, MissionTaskInfo.class);
        task.retainSourceDefinition(SCRIPT);
        sub.setTask(task);
      }
      result.put(sub.getId(), sub);
    }
    return Map.copyOf(result);
  }

  private static boolean source(MissionInfo.SubMissionInfo sub) {
    if (sub == null) return false;
    var original = SOURCES.get(sub.getId());
    if (original == null || !GSON.toJsonTree(sub).equals(GSON.toJsonTree(original))) return false;
    if (original.getTask() == null) return sub.getTask() == null;
    var text = sub.getTask().getSourceDefinition();
    return text != null && text.length() <= 8192 && JsonParser.parseString(text).equals(SCRIPT);
  }

  static boolean satisfied(Player player, MissionInfo.SubMissionInfo sub) {
    synchronized (player) {
      try {
        if (!source(sub)) return false;
        var state = player.getGameplay().getClockPark();
        var catalog = ClockParkCatalog.load(Path.of(LunarCore.getConfig().getResourceDir()));
        return state != null
            && state.valid(catalog)
            && ClockParkEndingRewards.valid(player, state)
            && state.progress(catalog) >= sub.getParamInt1();
      } catch (JsonParseException
          | IllegalArgumentException
          | IllegalStateException
          | NullPointerException invalid) {
        return false;
      }
    }
  }

  private ClockParkMissionProgress() {}
}
