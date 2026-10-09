package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.config.MissionTaskInfo;
import org.junit.jupiter.api.Test;

class MissionBattleScriptTest {
  final Gson gson = new Gson();

  @Test
  void otherTasksKeepScalarGroupAndEventFieldsWithoutBreakingMissionLoading() {
    var task =
        gson.fromJson(
            """
{"Type":"Mission","OnStartSequece":[{"TaskList":[
  {"$type":"RPG.GameCore.SetGroupState","GroupID":24,"EventID":"other-task"},
  {"$type":"RPG.GameCore.TriggerBattle","EventID":{"IsDynamic":false,"FixedValue":{"Value":305001}},
   "GroupID":{"IsDynamic":false,"FixedValue":{"Value":24}},
   "BattleAreaID":{"IsDynamic":false,"FixedValue":{"Value":1}}}]}]}
""",
            MissionTaskInfo.class);
    assertTrue(task.declaresBattle(305001));
  }

  @Test
  void scalarDynamicMissingFractionalAndStringBattleLiteralsAreNotGuessed() {
    for (String event :
        new String[] {
          "305001",
          "null",
          "{\"FixedValue\":{\"Value\":305001}}",
          "{\"IsDynamic\":true,\"FixedValue\":{\"Value\":305001}}",
          "{\"IsDynamic\":false,\"FixedValue\":{\"Value\":305001.5}}",
          "{\"IsDynamic\":false,\"FixedValue\":{\"Value\":\"305001\"}}"
        }) {
      var task =
          gson.fromJson(
              "{\"Type\":\"Mission\",\"OnStartSequece\":[{\"TaskList\":[{\"$type\":\"RPG.GameCore.TriggerBattle\",\"EventID\":"
                  + event
                  + ",\"GroupID\":{\"IsDynamic\":false,\"FixedValue\":{\"Value\":24}},\"BattleAreaID\":{\"IsDynamic\":false,\"FixedValue\":{\"Value\":1}}}]}]}",
              MissionTaskInfo.class);
      assertFalse(task.declaresBattle(305001), event);
      assertFalse(task.declaresBattle(0), "invalid event cannot match missing literal: " + event);
    }
  }
}
