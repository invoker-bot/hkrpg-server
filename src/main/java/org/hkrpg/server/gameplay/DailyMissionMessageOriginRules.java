package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.*;
import java.util.*;

/** Only original G84's loaded server instances permit its original daily initial report.
 * Complete source: TurnBasedGameData@724b139d8c9c32d12552eb95745a4fee72bfe48b.
 * Original blob SHA256 29aaaeaaeb11f41f3c0f1a0a7e31eea814549818f1af8637d9a0f9290450d4f1.
 * This declares source data, not an interpreter for the original client CustomString/Act graphs. */
final class DailyMissionMessageOriginRules {
  private static final String GROUP_PATH = "Config/LevelOutput/SharedRuntimeGroup/Groups_P20002_F20002001/LevelGroup_P20002_F20002001_G84.json";
  private static final Gson GSON = ResourceLoader.getGson();
  // Compare the same loader projection on both sides, including its float/default normalization.
  private static final JsonElement ORIGINAL = GSON.toJsonTree(GSON.fromJson("""
{
  "$type": "RPG.GameCore.RtLevelGroupInfo",
  "GroupName": "MainMission3000601_",
  "Category": "Mission",
  "AreaAnchorName": "LevelArea_P20002_F20002001_G84",
  "SaveType": "Reset",
  "LoadCondition": {
    "Conditions": [
      {
        "Type": "SubMission",
        "ID": 300060001
      }
    ]
  },
  "ForceUnloadCondition": {
    "Conditions": [
      {
        "Type": "SubMission",
        "Phase": "Cancel",
        "ID": 300060001
      }
    ]
  },
  "OwnerMainMissionID": 3000601,
  "ConflictIDList": [],
  "AnchorList": [],
  "MonsterList": [],
  "PropList": [
    {
      "PropID": 100,
      "IsOverrideInitLevelGraph": true,
      "Trigger": {
        "Radius": 2,
        "DimX": 1,
        "DimY": 1,
        "DimZ": 1,
        "PointList": []
      },
      "CampID": 4,
      "CustomTriggerMap": {},
      "DialogueGroups": [],
      "TalkDialogGroupIDList": [],
      "ServerInteractVerificationIDList": [],
      "InteractIconType": {
        "EnumIndex": 20,
        "Value": 0
      },
      "Name": "Prop1",
      "ID": 300001,
      "PosX": -115.06,
      "PosY": -0.101086,
      "PosZ": -75.27
    },
    {
      "PropID": 100,
      "IsOverrideInitLevelGraph": true,
      "Trigger": {
        "Radius": 5,
        "DimX": 1,
        "DimY": 1,
        "DimZ": 1,
        "PointList": []
      },
      "CampID": 4,
      "CustomTriggerMap": {},
      "DialogueGroups": [],
      "TalkDialogGroupIDList": [],
      "ServerInteractVerificationIDList": [],
      "InteractIconType": {
        "EnumIndex": 20,
        "Value": 0
      },
      "Name": "Prop2",
      "ID": 300002,
      "PosX": -114.76,
      "PosY": -0.101087,
      "PosZ": -75.28
    },
    {
      "PropID": 100,
      "IsOverrideInitLevelGraph": true,
      "CampID": 4,
      "CustomTriggerMap": {},
      "DialogueGroups": [],
      "TalkDialogGroupIDList": [],
      "ServerInteractVerificationIDList": [],
      "InteractIconType": {
        "EnumIndex": 20,
        "Value": 0
      },
      "Name": "Prop3",
      "ID": 300003,
      "PosX": -115.06,
      "PosY": 1.749,
      "PosZ": -75.27
    }
  ],
  "DistrictList": [],
  "WaypointList": [],
  "PathwayList": [],
  "BattleAreaList": [],
  "NPCList": [
    {
      "NPCID": 10209,
      "DialogueGroups": [],
      "ServerInteractVerificationIDList": [],
      "DefaultLookAtMode": "Closed",
      "BoardShowList": [
        8,
        4
      ],
      "TalkDialogGroupIDList": [],
      "DefaultIdleFreeStyleMotionID": 100020000,
      "InitialHiddenNodeList": [],
      "Name": "NPC1",
      "RotY": 330.9858,
      "ID": 400001,
      "PosX": -114.715,
      "PosY": -0.101086,
      "PosZ": -75.837
    },
    {
      "NPCID": 10215,
      "DialogueGroups": [],
      "ServerInteractVerificationIDList": [],
      "DefaultLookAtMode": "Closed",
      "BoardShowList": [
        8,
        4
      ],
      "TalkDialogGroupIDList": [],
      "DefaultIdleFreeStyleMotionID": 100020000,
      "InitialHiddenNodeList": [],
      "Name": "NPC2",
      "RotY": 153.4658,
      "ID": 400002,
      "PosX": -115.344,
      "PosY": -0.101086,
      "PosZ": -74.551
    }
  ],
  "CrowdList": [],
  "PedestrianList": []
}
""", GroupInfo.class));

  static boolean source() {
    var floor = GameData.getFloorInfo(20002, 20002001);
    if (floor == null || !floor.isLoaded() || !floor.isGroupInDimension(84, 0)
        || floor.getMainDimension() == null || floor.getMainDimension().getGroupIndexList() == null
        || Collections.frequency(floor.getMainDimension().getGroupIndexList(), 66) != 1
        || floor.getSimpleGroupList() == null || floor.getSimpleGroupList().size() <= 66) return false;
    var entry = floor.getSimpleGroupList().get(66);
    var group = floor.getGroups(0).get(84);
    if (entry == null || entry.getID() != 84 || entry.isIsDelete() || !GROUP_PATH.equals(entry.getGroupPath())
        || group == null || group.getId() != 84 || floor.getGroupInfoByIndex(66) != group
        || !ORIGINAL.equals(GSON.toJsonTree(group))) return false;
    for (var npc : group.getNPCList()) {
      var row = GameData.getNpcExcelMap().get(npc.getNPCID());
      if (row == null || row.getId() != npc.getNPCID()) return false;
    }
    var prop = GameData.getPropExcelMap().get(100);
    return prop != null && prop.getId() == 100;
  }

  static boolean loaded(Player player) {
    var scene = player.getScene();
    var floor = GameData.getFloorInfo(20002, 20002001);
    if (scene == null || scene.getPlayer() != player || player.getPlaneId() != 20002
        || player.getFloorId() != 20002001 || scene.getPlaneId() != 20002 || scene.getFloorId() != 20002001
        || scene.getDimensionId() != 0 || scene.getStoryLineId() != 0
        || player.getGameplay().getStoryLines().getCurrentId() != 0 || scene.getFloorInfo() != floor) return false;
    var group = floor.getGroups(0).get(84);
    if (scene.getResourceGroups().get(84) != group) return false;
    var npcs = scene.getEntitiesByGroup(EntityNpc.class, 84);
    var props = scene.getEntitiesByGroup(EntityProp.class, 84);
    if (npcs.size() != 2 || props.size() != 3) return false;
    for (var info : group.getNPCList())
      if (npcs.stream().filter(npc -> npc.getScene() == scene
          && npc.getInstId() == info.getID() && npc.getNpcId() == info.getNPCID()
          && npc.getPos().equals(info.getPos()) && npc.getRot().equals(info.getRot())).count() != 1) return false;
    for (var info : group.getPropList())
      if (props.stream().filter(prop -> prop.getScene() == scene && prop.getPropInfo() == info
          && prop.getInstId() == info.getID() && prop.getPropId() == info.getPropID()).count() != 1) return false;
    return true;
  }

  private DailyMissionMessageOriginRules() {}
}
