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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class WorldStoryStageWin46Test {
  private static final int MAIN = 2000701;
  private static final int LEONARD_TALK = 200070106, GRAFFITI = 200070107, AFTER_GRAFFITI = 200070109;
  private static final int GRAFFITI_EVENT = 20003225, REFERENCE_GRAFFITI_EVENT = 20003224;
  private static final int CONTROL_ROOM_TALK = 200070118, CONTROL_ROOM = 200070119;
  private static final int CONTROL_ROOM_EVENT = 20003226, AFTER_CONTROL_ROOM = 200070120;

  @TempDir Path root;
  private final OpeningStageWin46Test fixture = new OpeningStageWin46Test();
  private final Map<String, FloorInfo> oldFloors = new HashMap<>();
  private OpeningStageWin46Test.Actor player;
  private OpeningStageWin46Test.Session session;

  @BeforeEach
  void prepare() throws Exception {
    fixture.temp = root;
    fixture.load();
    try (var input = getClass().getResourceAsStream("/fixtures/world-story-stage-wins-4.6.json")) {
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
    GameplayDefinitions.loadMissions(root.resolve("Config/Level/Mission"));
    player = new OpeningStageWin46Test.Actor() {
      @Override
      public int getEntryId() {
        return getPlaneId() * 100 + 1;
      }
    };
    session = fixture.new Session(player);
    MissionTrialProvisioning46Test.set(player, "loggedIn", true);
  }

  @AfterEach
  void restore() {
    oldFloors.forEach((key, old) -> {
      if (old == null) GameData.getFloorInfos().remove(key);
      else GameData.getFloorInfos().put(key, old);
    });
    fixture.restore();
  }

  @ParameterizedTest
  @CsvSource({"20003225, 0", "20003225, 1", "20003225, 6", "20003224, 1"})
  void graffitiVictoryPublishesTheAftermathAndRemainsCompleteOnReload(int event, int world)
      throws Exception {
    startGraffiti();
    player.world = world;
    session.request("GetMissionDataCsReq", Map.of());
    var battle = enter(event);
    assertEquals(GRAFFITI_EVENT * 10 + world, battle.getStage().getId());
    var reply = finish(battle, "BATTLE_END_WIN");
    assertEquals(0, field(reply, "retcode"));
    assertEquals(event, field(reply, "event_id"));
    assertEquals(QuestState.Phase.FINISHED, phase(GRAFFITI));
    assertEquals(QuestState.Phase.DOING, phase(AFTER_GRAFFITI));
    assertEquals(QuestState.Phase.DOING, player.getGameplay().getMainMissions().get(MAIN).getPhase());
    assertNull(player.getMissionManager().sub(200070110));

    var notify = session.responses.get("PlayerSyncScNotify");
    assertNotNull(notify);
    var sync = (DynamicMessage) field(notify, "mission_sync");
    assertEquals("MissionStatus_MissionFinish", status(sync, GRAFFITI));
    assertEquals("MissionStatus_MissionDoing", status(sync, AFTER_GRAFFITI));
    session = fixture.new Session(player);
    var snapshot = session.request("GetMissionDataCsReq", Map.of());
    assertTrue(((List<?>) field(snapshot, "OLKALABEDOA")).contains(GRAFFITI));
    assertEquals("MissionStatus_MissionDoing", status(snapshot, AFTER_GRAFFITI));
    assertNotEquals(0, field(finish(battle, "BATTLE_END_WIN"), "retcode"));
    assertNotEquals(0, field(requestEntry(event), "retcode"));

    var talk = session.request("FinishTalkMissionCsReq",
        Map.of("sub_mission_id", AFTER_GRAFFITI, "talk_str", "Mission_200070109"));
    assertEquals(0, field(talk, "retcode"));
    assertEquals(QuestState.Phase.DOING, phase(200070110));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 6})
  void nextLeonardEncounterUsesItsOwnEventAndWaitsForTheNextDialogue(int world) throws Exception {
    start(MAIN, CONTROL_ROOM_TALK, CONTROL_ROOM, 20002);
    player.world = world;
    var battle = enter(CONTROL_ROOM_EVENT);
    assertEquals(CONTROL_ROOM_EVENT * 10 + world, battle.getStage().getId());
    assertEquals(0, field(finish(battle, "BATTLE_END_WIN"), "retcode"));
    assertEquals(QuestState.Phase.FINISHED, phase(CONTROL_ROOM));
    assertEquals(QuestState.Phase.DOING, phase(AFTER_CONTROL_ROOM));
    assertEquals(QuestState.Phase.DOING, player.getGameplay().getMainMissions().get(MAIN).getPhase());
  }

  @ParameterizedTest
  @CsvSource({
      "2000102, 200010201, 200010202, 20002, 20003207",
      "2000102, 200010203, 200010210, 20001, 20003208",
      "2001001, 200100136, 200100137, 20004, 20004025"
  })
  void otherOriginalWorldObjectivesRecognizeTheirOwnSceneEvent(
      int main, int before, int sub, int plane, int event) throws Exception {
    start(main, before, sub, plane);
    var battle = enter(event);
    assertEquals(event * 10, battle.getStage().getId());
    assertEquals(0, field(finish(battle, "BATTLE_END_WIN"), "retcode"));
    assertEquals(QuestState.Phase.FINISHED, phase(sub));
  }

  @Test
  void referenceGraphFromAnotherBattleCannotUseItsActualEventToAdvanceTheWrongObjective()
      throws Exception {
    start(2000102, 200010201, 200010202, 20002);
    assertEquals(0, field(finish(enter(20003208), "BATTLE_END_WIN"), "retcode"));
    assertEquals(QuestState.Phase.DOING, phase(200010202));
  }

  @ParameterizedTest
  @ValueSource(ints = {GRAFFITI_EVENT, REFERENCE_GRAFFITI_EVENT, CONTROL_ROOM_EVENT})
  void protectedEncountersRequireTheOriginalFloorAndCompletedPrerequisite(int event) throws Exception {
    boolean later = event == CONTROL_ROOM_EVENT;
    int before = later ? CONTROL_ROOM_TALK : LEONARD_TALK;
    int sub = later ? CONTROL_ROOM : GRAFFITI;
    start(MAIN, before, sub, later ? 20001 : 20002);
    assertNotEquals(0, field(requestEntry(event), "retcode"));
    place(later ? 20002 : 20001);
    player.getGameplay().getSubMissions().put(before, new QuestState(before));
    assertNotEquals(0, field(requestEntry(event), "retcode"));
    assertNull(player.getBattle());
    assertEquals(QuestState.Phase.DOING, phase(sub));
  }

  @Test
  void losingAndQuittingLeaveGraffitiAvailableForARealWin() throws Exception {
    startGraffiti();
    for (String result : List.of("BATTLE_END_LOSE", "BATTLE_END_QUIT")) {
      assertEquals(0, field(finish(enter(GRAFFITI_EVENT), result), "retcode"));
      assertEquals(QuestState.Phase.DOING, phase(GRAFFITI));
      assertNull(player.getMissionManager().sub(AFTER_GRAFFITI));
    }
    assertEquals(0, field(finish(enter(GRAFFITI_EVENT), "BATTLE_END_WIN"), "retcode"));
    assertEquals(QuestState.Phase.FINISHED, phase(GRAFFITI));
  }

  @Test
  void activeGraffitiCannotBeCompletedThroughTheDirectStageOrAnIndependentEvent() throws Exception {
    startGraffiti();
    int stage = GRAFFITI_EVENT * 10;
    assertEquals(0, field(finish(fixture.service.startBattle(player, stage, 0), "BATTLE_END_WIN"), "retcode"));
    var independent = fixture.gson.fromJson(
        "{\"EventID\":" + REFERENCE_GRAFFITI_EVENT + ",\"StageID\":" + stage + "}", PlaneEventExcel.class);
    GameData.getPlaneEventExcelMap().add(independent);
    assertEquals(0, field(finish(enter(REFERENCE_GRAFFITI_EVENT), "BATTLE_END_WIN"), "retcode"));
    assertEquals(QuestState.Phase.DOING, phase(GRAFFITI));
    assertNull(player.getMissionManager().sub(AFTER_GRAFFITI));
  }

  @Test
  void changedWorldMissionSourceCannotConsumeAnAlreadyStartedBattle() throws Exception {
    int sub = 200010202;
    start(2000102, 200010201, sub, 20002);
    var battle = enter(20003207);
    var definition = GameplayDefinitions.subs.get(sub);
    var graph = JsonParser.parseString(definition.getTask().getSourceDefinition()).getAsJsonObject();
    graph.addProperty("Description", "modified");
    definition.getTask().retainSourceDefinition(graph);
    assertEquals(0, field(finish(battle, "BATTLE_END_WIN"), "retcode"));
    assertEquals(QuestState.Phase.DOING, phase(sub));
  }

  @Test
  void changedGraffitiSourceRejectsTheProtectedEvent() throws Exception {
    startGraffiti();
    GameplayDefinitions.subs.get(GRAFFITI).getTask().retainSourceDefinition(new JsonObject());
    assertNotEquals(0, field(requestEntry(GRAFFITI_EVENT), "retcode"));
    assertNull(player.getBattle());
  }

  private void startGraffiti() throws Exception {
    start(MAIN, LEONARD_TALK, GRAFFITI, 20001);
  }

  private void start(int main, int before, int sub, int plane) throws Exception {
    place(plane);
    player.getGameplay().getMainMissions().put(main, new QuestState(main));
    var previous = new QuestState(before);
    previous.advance(1, Math.max(1, GameplayDefinitions.subs.get(before).getProgress()), 100);
    player.getGameplay().getSubMissions().put(before, previous);
    player.getGameplay().getSubMissions().put(sub, new QuestState(sub));
    assertEquals(QuestState.Phase.DOING, phase(sub));
  }

  private void place(int plane) throws Exception {
    player.floor = plane * 1000 + 1;
    var floor = fixture.gson.fromJson("{\"FloorID\":" + player.floor + "}", FloorInfo.class);
    MissionTrialProvisioning46Test.setField(floor, FloorInfo.class, "loaded", true);
    var key = "P" + plane + "_F" + player.floor;
    if (!oldFloors.containsKey(key)) oldFloors.put(key, GameData.getFloorInfos().get(key));
    GameData.getFloorInfos().put(key, floor);
    var maze = fixture.gson.fromJson(
        "{\"PlaneID\":" + plane + ",\"PlaneType\":\"Maze\",\"WorldID\":101}", MazePlaneExcel.class);
    var scene = new Scene(player, maze, player.floor);
    scene.setEntryId(player.getEntryId());
    MissionTrialProvisioning46Test.set(player, "scene", scene);
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

  private static String status(DynamicMessage message, int id) {
    for (var row : (List<?>) field(message, "mission_list")) {
      var mission = (DynamicMessage) row;
      if (field(mission, "id").equals(id)) return field(mission, "status").toString();
    }
    return "";
  }
}
