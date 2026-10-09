package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.GameplayDefinitions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class UniverseUnlock46Test {
  @TempDir Path resources;

  static class Resident extends Player {
    int world;

    public int getWorldLevel() {
      return world;
    }

    public void save() {}
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final Resident resident = new Resident();

    public Player getPlayer() {
      return resident;
    }
  }

  @AfterEach
  void reset() {
    GameplayDefinitions.areas.clear();
    GameplayDefinitions.rogueSeasons.clear();
    GameplayDefinitions.rogueUnlocks.clear();
    GameplayDefinitions.rogueRules.clear();
  }

  void load(String finishRules) throws Exception {
    var excels = Files.createDirectories(resources.resolve("ExcelOutput"));
    Files.writeString(
        excels.resolve("RogueAreaConfig.json"),
        """
        [{"RogueAreaID":199951,"AreaProgress":2,"Difficulty":1,"UnlockID":199951}]
        """);
    Files.writeString(
        excels.resolve("RogueUnlockConfig.json"),
        """
        [{"RogueUnlockID":199951,"UnlockFinishWay":199951},
         {"RogueUnlockID":199952,"UnlockFinishWay":199952},
         {"RogueUnlockID":199953,"UnlockFinishWay":199953}]
        """);
    Files.writeString(excels.resolve("FinishWayRogue.json"), finishRules);
    GameplayDefinitions.load(resources);
    GameplayDefinitions.rogueSeasons.add(
        new GameplayDefinitions.RogueSeason(
            199951, 1, List.of(199951), "2020-01-01 04:00:00", "2099-01-01 03:59:59"));
  }

  String status(Session session) throws Exception {
    session.request("GetRogueInfoCsReq");
    var game = (DynamicMessage) InitialData46Test.field(session.response, "rogue_game_info");
    var basic = (DynamicMessage) InitialData46Test.field(game, "LFLCDNGALME");
    var catalog = (DynamicMessage) InitialData46Test.field(basic, "ACLKMFCDMED");
    return InitialData46Test.field(InitialData46Test.first(catalog, "OJGOAOPLMLB"), "area_status")
        .toString();
  }

  @Test
  void linkedUnlockRequiresEveryDistinctMissionAndActualWorldLevel() throws Exception {
    load(
        """
[{"ID":199951,"FinishType":"RogueFinishUnlock","ParamType":"ListContain","ParamIntList":[199952,199953],"Progress":2},
 {"ID":199952,"FinishType":"FinishMission","ParamType":"ListContain","ParamIntList":[1999501,1999502],"Progress":2},
 {"ID":199953,"FinishType":"WorldLevel","ParamType":"NoPara","Progress":3}]
""");
    var s = new Session();
    assertEquals("RogueAreaStatus_Lock", status(s));
    for (int id : List.of(1999501, 1999502)) {
      var state = new QuestState(id);
      state.advance(1, 1, 1);
      s.resident.getGameplay().getMainMissions().put(id, state);
    }
    s.resident.world = 2;
    assertEquals("RogueAreaStatus_Lock", status(s));
    s.resident.world = 3;
    assertEquals("RogueAreaStatus_Unlock", status(s));
    assertTrue(s.resident.getGameplay().getRewards().isEmpty());
  }

  @Test
  void duplicateRequirementsUnknownRulesAndCyclesCannotUnlock() throws Exception {
    load(
        """
[{"ID":199951,"FinishType":"RogueFinishUnlock","ParamType":"ListContain","ParamIntList":[199952,199952],"Progress":2},
 {"ID":199952,"FinishType":"WorldLevel","ParamType":"NoPara","Progress":1}]
""");
    var s = new Session();
    s.resident.world = 6;
    assertEquals("RogueAreaStatus_Lock", status(s));
    load(
        """
[{"ID":199951,"FinishType":"RogueFinishUnlock","ParamType":"ListContain","ParamIntList":[199952],"Progress":1},
 {"ID":199952,"FinishType":"RogueFinishUnlock","ParamType":"ListContain","ParamIntList":[199951],"Progress":1}]
""");
    assertEquals("RogueAreaStatus_Lock", status(s));
    load(
        """
        [{"ID":199951,"FinishType":"Unconfirmed","ParamType":"NoPara","Progress":1}]
        """);
    assertEquals("RogueAreaStatus_Lock", status(s));
  }

  @Test
  void scalarRulesCannotSilentlyIgnoreExtraRequirements() throws Exception {
    load(
        """
        [{"ID":199951,"FinishType":"WorldLevel","ParamType":"NoPara",
          "ParamIntList":[199952],"Progress":1}]
        """);
    var s = new Session();
    s.resident.world = 6;
    assertEquals("RogueAreaStatus_Lock", status(s));
  }

  @Test
  void areaProgressCountsOnlyRecordedSuccessfulRunsAndDoesNotRewardQueries() throws Exception {
    load(
        """
        [{"ID":199951,"FinishType":"RoguePassAreaProgress","ParamType":"Equal",
          "ParamInt1":2,"Progress":2}]
        """);
    var s = new Session();
    var state = s.resident.getGameplay();
    var first = new org.hkrpg.server.gameplay.GameplayState.RogueRun(199951, 1, Map.of());
    state.setRogue(first);
    assertFalse(state.recordRogueWin(first));
    first.finish(org.hkrpg.server.gameplay.GameplayState.RogueRun.Phase.FINISHED);
    assertTrue(state.recordRogueWin(first));
    assertFalse(state.recordRogueWin(first));
    assertEquals("RogueAreaStatus_Lock", status(s));
    var second = new org.hkrpg.server.gameplay.GameplayState.RogueRun(199951, 1, Map.of());
    second.finish(org.hkrpg.server.gameplay.GameplayState.RogueRun.Phase.FINISHED);
    assertFalse(state.recordRogueWin(second));
    state.setRogue(second);
    assertTrue(state.recordRogueWin(second));
    assertEquals("RogueAreaStatus_Unlock", status(s));
    assertEquals("RogueAreaStatus_Unlock", status(s));
    assertEquals(2, state.getRogueWins().get(199951));
    assertTrue(state.getRewards().isEmpty());
  }
}
