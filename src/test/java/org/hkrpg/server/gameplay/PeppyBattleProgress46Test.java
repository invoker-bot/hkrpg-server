package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.OpeningStageWin46Test.field;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.data.excel.PlaneEventExcel;
import emu.lunarcore.data.excel.StageExcel;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original companion mission battle graphs, exercised through 4.6 requests. */
class PeppyBattleProgress46Test {
  private static final int MAIN = 2000211;
  private static final int FOLLOW_PEPPY = 200021114, DAN_HENG_TALK = 200021116;
  private static final int PEPPY_EVENT = 20003214;
  private static final int RESCUE_ARLAN = 200021123, ARLAN_TALK = 200021124;
  private static final int ARLAN_EVENT = 20003215, MISSING_ARLAN_EVENT = 20003216;

  @TempDir Path root;
  private final OpeningStageWin46Test fixture = new OpeningStageWin46Test();
  private final Map<String, FloorInfo> oldFloors = new HashMap<>();
  private OpeningStageWin46Test.Actor player;
  private OpeningStageWin46Test.Session session;

  @BeforeEach
  void prepare() throws Exception {
    fixture.temp = root;
    fixture.load();
    try (var input = getClass().getResourceAsStream("/fixtures/peppy-battle-progress-4.6.json")) {
      var source = JsonParser.parseString(
          new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8))
          .getAsJsonObject();
      for (var raw : source.getAsJsonArray("records")) {
        var record = raw.getAsJsonObject();
        var row = record.getAsJsonObject("row");
        switch (record.get("kind").getAsString()) {
          case "file" -> {
            var path = root.resolve(record.get("source").getAsString());
            Files.createDirectories(path.getParent());
            Files.writeString(path, row.toString());
          }
          case "MainMission" ->
              GameData.getMainMissionExcelMap().add(fixture.gson.fromJson(row, MainMissionExcel.class));
          case "PlaneEvent" ->
              GameData.getPlaneEventExcelMap().add(fixture.gson.fromJson(row, PlaneEventExcel.class));
          case "StageConfig" -> {
            var stage = fixture.gson.fromJson(row, StageExcel.class);
            stage.onLoad();
            GameData.getStageExcelMap().add(stage);
          }
          default -> fail("Unknown fixture record");
        }
      }
    }
    GameplayDefinitions.load(root);
    player = new OpeningStageWin46Test.Actor() {
      @Override
      public int getEntryId() {
        return getPlaneId() * 100 + 1;
      }
    };
    session = fixture.new Session(player);
    MissionTrialProvisioning46Test.set(player, "loggedIn", true);
    placePlayer(20001);
    // Earlier dialogue and the accepted objectives are the isolated starting state.
    player.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    complete(200021113);
    accept(FOLLOW_PEPPY);
    accept(200021115);
    assertNull(player.getMissionManager().sub(DAN_HENG_TALK));
  }

  @AfterEach
  void restore() {
    oldFloors.forEach((key, old) -> {
      if (old == null) GameData.getFloorInfos().remove(key);
      else GameData.getFloorInfos().put(key, old);
    });
    fixture.restore();
  }

  private void placePlayer(int plane) throws Exception {
    player.floor = plane * 1000 + 1;
    var floor = fixture.gson.fromJson("{\"FloorID\":" + player.floor + "}", FloorInfo.class);
    MissionTrialProvisioning46Test.setField(floor, FloorInfo.class, "loaded", true);
    var key = "P" + plane + "_F" + player.floor;
    oldFloors.put(key, GameData.getFloorInfos().put(key, floor));
    var maze = fixture.gson.fromJson(
        "{\"PlaneID\":" + plane + ",\"PlaneType\":\"Maze\",\"WorldID\":101}", MazePlaneExcel.class);
    MissionTrialProvisioning46Test.set(player, "scene", new Scene(player, maze, player.floor));
  }

  private QuestState accept(int id) {
    var state = new QuestState(id);
    player.getGameplay().getSubMissions().put(id, state);
    return state;
  }

  private void complete(int id) {
    accept(id).advance(1, 1, 100);
  }

  private DynamicMessage requestEntry(int event) throws Exception {
    return session.request("SceneEnterStageCsReq", Map.of("event_id", event));
  }

  private Battle enter(int event) throws Exception {
    assertEquals(0, field(requestEntry(event), "retcode"));
    return Objects.requireNonNull(player.getBattle());
  }

  private DynamicMessage finish(Battle battle, String outcome) throws Exception {
    var end = session.getProtocol().clientSchema().message("PVEBattleResultCsReq")
        .findFieldByName("end_status");
    return session.request("PVEBattleResultCsReq", Map.of(
        "battle_id", battle.getId(),
        "stage_id", battle.getStage().getId(),
        "end_status", end.getEnumType().findValueByName(outcome)));
  }

  private QuestState.Phase phase(int id) {
    return Objects.requireNonNull(player.getMissionManager().sub(id)).getPhase();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 6})
  void peppyVictoryFinishesTheOpeningScriptAndAcceptsDanHengDialogue(int world) throws Exception {
    player.world = world;
    session.request("GetMissionDataCsReq", Map.of());
    var battle = enter(PEPPY_EVENT);
    assertEquals(200032140 + world, battle.getStage().getId());
    var reply = finish(battle, "BATTLE_END_WIN");
    assertEquals(0, field(reply, "retcode"));
    assertEquals(PEPPY_EVENT, field(reply, "event_id"));
    assertEquals(QuestState.Phase.FINISHED, phase(FOLLOW_PEPPY));
    assertEquals(QuestState.Phase.DOING, phase(DAN_HENG_TALK));

    var notify = session.responses.get("PlayerSyncScNotify");
    assertNotNull(notify, "An online player needs the mission change as the battle ends");
    var missionSync = (DynamicMessage) field(notify, "mission_sync");
    var changed = (List<?>) field(missionSync, "mission_list");
    assertTrue(changed.stream().anyMatch(row -> field((DynamicMessage) row, "id").equals(FOLLOW_PEPPY)
        && field((DynamicMessage) row, "status").toString().equals("MissionStatus_MissionFinish")));
    assertTrue(changed.stream().anyMatch(row -> field((DynamicMessage) row, "id").equals(DAN_HENG_TALK)
        && field((DynamicMessage) row, "status").toString().equals("MissionStatus_MissionDoing")));

    var snapshot = session.request("GetMissionDataCsReq", Map.of());
    assertTrue(((List<?>) field(snapshot, "OLKALABEDOA")).contains(FOLLOW_PEPPY));
    assertTrue(((List<?>) field(snapshot, "mission_list")).stream()
        .anyMatch(row -> field((DynamicMessage) row, "id").equals(DAN_HENG_TALK)));
    assertNotEquals(0, field(finish(battle, "BATTLE_END_WIN"), "retcode"));
  }

  @Test
  void stalePeppyTriggerCannotStartTheFightAfterTheSavedWin() throws Exception {
    complete(FOLLOW_PEPPY);
    accept(DAN_HENG_TALK);
    assertNotEquals(0, field(requestEntry(PEPPY_EVENT), "retcode"));
    assertNull(player.getBattle());
    assertEquals(QuestState.Phase.FINISHED, phase(FOLLOW_PEPPY));
  }

  @Test
  void losingOrQuittingLeavesThePeppyEncounterRetryable() throws Exception {
    for (String outcome : List.of("BATTLE_END_LOSE", "BATTLE_END_QUIT")) {
      assertEquals(0, field(finish(enter(PEPPY_EVENT), outcome), "retcode"));
      assertEquals(QuestState.Phase.DOING, phase(FOLLOW_PEPPY));
      assertNull(player.getMissionManager().sub(DAN_HENG_TALK));
    }
    assertNotNull(enter(PEPPY_EVENT));
  }

  @Test
  void directStageOrAnUnrelatedEventSharingItsStageDoesNotFinishPeppy() throws Exception {
    var direct = Objects.requireNonNull(fixture.service.startBattle(player, 200032140, 0));
    assertEquals(0, field(finish(direct, "BATTLE_END_WIN"), "retcode"));
    int otherEvent = 29999999;
    GameData.getPlaneEventExcelMap().add(fixture.gson.fromJson(
        "{\"EventID\":" + otherEvent + ",\"StageID\":200032140}", PlaneEventExcel.class));
    assertEquals(0, field(finish(enter(otherEvent), "BATTLE_END_WIN"), "retcode"));
    assertEquals(QuestState.Phase.DOING, phase(FOLLOW_PEPPY));
    assertNull(player.getMissionManager().sub(DAN_HENG_TALK));
  }

  @Test
  void changedBattleGraphCannotConsumeAnAlreadyStartedPeppyWin() throws Exception {
    var battle = enter(PEPPY_EVENT);
    GameplayDefinitions.subs.get(FOLLOW_PEPPY).getTask().retainSourceDefinition(new JsonObject());
    assertEquals(0, field(finish(battle, "BATTLE_END_WIN"), "retcode"));
    assertEquals(QuestState.Phase.DOING, phase(FOLLOW_PEPPY));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 6})
  void authoredArlanTriggerUsesTheStageWinEventAndAcceptsTheNextDialogue(int world) throws Exception {
    placePlayer(20003);
    player.world = world;
    complete(FOLLOW_PEPPY);
    complete(200021122);
    accept(RESCUE_ARLAN);
    var battle = enter(MISSING_ARLAN_EVENT);
    assertEquals(ARLAN_EVENT * 10 + world, battle.getStage().getId());
    var reply = finish(battle, "BATTLE_END_WIN");
    assertEquals(0, field(reply, "retcode"));
    assertEquals(MISSING_ARLAN_EVENT, field(reply, "event_id"));
    assertEquals(QuestState.Phase.FINISHED, phase(RESCUE_ARLAN));
    assertEquals(QuestState.Phase.DOING, phase(ARLAN_TALK));
    assertNotEquals(0, field(requestEntry(MISSING_ARLAN_EVENT), "retcode"));
  }

  @Test
  void arlanAliasRequiresItsOriginalPrerequisiteAndFloor() throws Exception {
    complete(200021122);
    accept(RESCUE_ARLAN);
    assertNotEquals(0, field(requestEntry(MISSING_ARLAN_EVENT), "retcode"));
    placePlayer(20003);
    accept(200021122);
    assertNotEquals(0, field(requestEntry(MISSING_ARLAN_EVENT), "retcode"));
    assertNull(player.getBattle());
  }
}
