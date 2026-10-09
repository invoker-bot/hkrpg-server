package org.hkrpg.server.gameplay;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import java.util.*;

/** Only the five authored classic placement objectives, following a successful saved placement. */
final class MatchThreeBirdPlacement {
  private static final Gson GSON = new Gson();
  private static final Map<Integer, MissionInfo.SubMissionInfo> SOURCES = sources();

  private static Map<Integer, MissionInfo.SubMissionInfo> sources() {
    var result = new HashMap<Integer, MissionInfo.SubMissionInfo>();
    for (var row :
        JsonParser.parseString(
                """
[
  {
    "ID": 802310205,
    "MainMissionID": 8023102,
    "LevelPlaneID": 10341,
    "LevelFloorID": 10341001,
    "TakeType": "AnySequence",
    "TakeParamIntList": [
      802310204
    ],
    "FinishType": "MatchThreeBirdPos",
    "ParamType": "Equal",
    "ParamInt1": 2,
    "ParamInt2": 2,
    "ParamInt3": 300,
    "ParamIntList": [],
    "ParamItemList": [],
    "FinishActionList": [],
    "Progress": 1,
    "CustomValueList": [],
    "CustomValueReward": [],
    "GroupIDList": [
      265
    ],
    "RequiredNPCSeriesIDList": [],
    "WayPointType": "Prop",
    "GotoParam": [],
    "WayPointFloorID": 10341001,
    "WayPointGroupID": 187,
    "WayPointEntityID": 300002
  },
  {
    "ID": 802310206,
    "MainMissionID": 8023102,
    "LevelPlaneID": 10341,
    "LevelFloorID": 10341001,
    "TakeType": "AnySequence",
    "TakeParamIntList": [
      802310205
    ],
    "FinishType": "MatchThreeBirdPos",
    "ParamType": "Equal",
    "ParamInt1": 2,
    "ParamInt2": 1,
    "ParamInt3": 301,
    "ParamIntList": [],
    "ParamItemList": [],
    "FinishActionList": [
      {
        "FinishActionType": "SetFloorSavedValue",
        "FinishActionPara": [],
        "FinishActionParaString": [
          "10341",
          "10341001",
          "Activity230DIYEntrance",
          "2"
        ]
      }
    ],
    "Progress": 1,
    "CustomValueList": [],
    "CustomValueReward": [],
    "GroupIDList": [
      127
    ],
    "RequiredNPCSeriesIDList": [],
    "WayPointType": "Prop",
    "GotoParam": [],
    "WayPointFloorID": 10341001,
    "WayPointGroupID": 127,
    "WayPointEntityID": 300001
  },
  {
    "ID": 802310305,
    "MainMissionID": 8023103,
    "LevelPlaneID": 10341,
    "LevelFloorID": 10341001,
    "TakeType": "AnySequence",
    "TakeParamIntList": [
      802310304
    ],
    "FinishType": "MatchThreeBirdPos",
    "ParamType": "NoPara",
    "ParamInt1": 2,
    "ParamInt2": 3,
    "ParamInt3": 302,
    "ParamIntList": [],
    "ParamItemList": [],
    "FinishActionList": [
      {
        "FinishActionType": "SetFloorSavedValue",
        "FinishActionPara": [],
        "FinishActionParaString": [
          "10341",
          "10341001",
          "Activity230DIYEntrance",
          "3"
        ]
      }
    ],
    "Progress": 1,
    "CustomValueList": [],
    "CustomValueReward": [],
    "GroupIDList": [
      129
    ],
    "RequiredNPCSeriesIDList": [],
    "WayPointType": "Prop",
    "GotoParam": [],
    "WayPointFloorID": 10341001,
    "WayPointGroupID": 129,
    "WayPointEntityID": 300001
  },
  {
    "ID": 802310405,
    "MainMissionID": 8023104,
    "LevelPlaneID": 10341,
    "LevelFloorID": 10341001,
    "TakeType": "AnySequence",
    "TakeParamIntList": [
      802310404
    ],
    "FinishType": "MatchThreeBirdPos",
    "ParamType": "Equal",
    "ParamInt1": 2,
    "ParamInt2": 4,
    "ParamInt3": 303,
    "ParamIntList": [],
    "ParamItemList": [],
    "FinishActionList": [],
    "Progress": 1,
    "CustomValueList": [],
    "CustomValueReward": [],
    "GroupIDList": [
      267
    ],
    "RequiredNPCSeriesIDList": [],
    "WayPointType": "Prop",
    "GotoParam": [],
    "WayPointFloorID": 10341001,
    "WayPointGroupID": 189,
    "WayPointEntityID": 300004
  },
  {
    "ID": 802310506,
    "MainMissionID": 8023105,
    "LevelPlaneID": 10341,
    "LevelFloorID": 10341001,
    "TakeType": "AnySequence",
    "TakeParamIntList": [
      802310505
    ],
    "FinishType": "MatchThreeBirdPos",
    "ParamType": "Equal",
    "ParamInt1": 2,
    "ParamInt2": 6,
    "ParamInt3": 305,
    "ParamIntList": [],
    "ParamItemList": [],
    "FinishActionList": [],
    "Progress": 1,
    "CustomValueList": [],
    "CustomValueReward": [],
    "GroupIDList": [
      138
    ],
    "RequiredNPCSeriesIDList": [],
    "WayPointType": "Prop",
    "GotoParam": [],
    "WayPointFloorID": 10341001,
    "WayPointGroupID": 138,
    "WayPointEntityID": 300001
  }
]
""")
            .getAsJsonArray()) {
      var sub = GSON.fromJson(row, MissionInfo.SubMissionInfo.class);
      result.put(sub.getId(), sub);
    }
    return Map.copyOf(result);
  }

  static boolean matches(Player player, MissionInfo.SubMissionInfo sub, int bird, int position) {
    if (sub == null || bird <= 0 || position <= 0) return false;
    var original = SOURCES.get(sub.getId());
    if (original == null || !GSON.toJsonTree(sub).equals(GSON.toJsonTree(original))) return false;
    var record = player.getGameplay().getMatchThree().getBirds().get(bird);
    return sub.getParamInt3() == bird
        && sub.getParamInt2() == position
        && record != null
        && record.getPosition() == position
        && new MatchThreeManager(player).birdUnlocked(bird);
  }

  private MatchThreeBirdPlacement() {}
}
