package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.OpeningStageWin46Test.field;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.quest.QuestState;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original snow-plains objectives and both events; native replies must preserve event identity. */
class SilvermaneBattleResult46Test {
  static final int MAIN = 1010101, TARGET = 101010110, FIRST = 20101011, LAST = 20101012;
  @TempDir Path temp;
  final OpeningStageWin46Test source = new OpeningStageWin46Test();
  OpeningStageWin46Test.Actor actor;
  OpeningStageWin46Test.Session session;

  @BeforeEach
  void load() throws Exception {
    source.temp = temp;
    source.load();
    try (var input = getClass().getResourceAsStream("/fixtures/silvermane-battle-result-4.6.json")) {
      var fixture = JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),
          StandardCharsets.UTF_8)).getAsJsonObject();
      for (var raw : fixture.getAsJsonArray("records")) {
        var record = raw.getAsJsonObject();
        var row = record.getAsJsonObject("row");
        switch (record.get("kind").getAsString()) {
          case "file" -> {
            var path = temp.resolve(record.get("source").getAsString());
            Files.createDirectories(path.getParent());
            Files.writeString(path, row.toString());
          }
          case "MainMission" -> GameData.getMainMissionExcelMap().add(source.gson.fromJson(row, MainMissionExcel.class));
          case "PlaneEvent" -> GameData.getPlaneEventExcelMap().add(source.gson.fromJson(row, PlaneEventExcel.class));
          case "StageConfig" -> {
            var stage = source.gson.fromJson(row, StageExcel.class);
            stage.onLoad();
            GameData.getStageExcelMap().add(stage);
          }
          default -> fail("Unknown source record");
        }
      }
    }
    GameplayDefinitions.load(temp);
    actor = new OpeningStageWin46Test.Actor() {
      @Override public int getEntryId() { return 2010101; }
    };
    actor.floor = 20101001;
    // Earlier dialogue, message and snow investigation are an isolated accepted-state fixture.
    actor.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    for (int id : new int[]{101010102, 101010107, 101010108, 101010109, 101010111, 101010120}) {
      var state = new QuestState(id); state.advance(1, 1, java.time.Instant.now().getEpochSecond());
      actor.getGameplay().getSubMissions().put(id, state);
    }
    for (int id : new int[]{TARGET, 101010112})
      actor.getGameplay().getSubMissions().put(id, new QuestState(id));
    session = source.new Session(actor);
  }

  @AfterEach void restore() { source.restore(); }

  Battle enter(int event) throws Exception {
    var entered = session.request("SceneEnterStageCsReq", Map.of("event_id", event));
    assertEquals(0, field(entered, "retcode"));
    return Objects.requireNonNull(actor.getBattle());
  }

  Map<String, Object> report(Battle battle, String outcome) {
    var end = session.getProtocol().clientSchema().message("PVEBattleResultCsReq").findFieldByName("end_status");
    return Map.of("battle_id", battle.getId(), "stage_id", battle.getStage().getId(),
        "end_status", end.getEnumType().findValueByName(outcome));
  }

  DynamicMessage finish(Battle battle, String outcome) throws Exception {
    return session.request("PVEBattleResultCsReq", report(battle, outcome));
  }

  @ParameterizedTest @ValueSource(ints = {0, 1})
  void firstVictoryNamesItsEventAndOnlySecondVictoryCompletesTheGoal(int world) throws Exception {
    actor.world = world;
    var first = enter(FIRST);
    assertEquals(FIRST * 10 + world, first.getStage().getId());
    var reply = finish(first, "BATTLE_END_WIN");
    assertEquals(0, field(reply, "retcode"));
    assertEquals(FIRST, field(reply, "event_id"), "Client ByCompareMissionBattleWin needs the event, not just stage_id");
    assertEquals(first.getStage().getId(), field(reply, "stage_id"));
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(TARGET).getPhase());
    assertNull(actor.getMissionManager().sub(101010113));
    var last = enter(LAST);
    var finalReply = finish(last, "BATTLE_END_WIN");
    assertEquals(0, field(finalReply, "retcode"));
    assertEquals(LAST, field(finalReply, "event_id"));
    assertEquals(QuestState.Phase.FINISHED, actor.getMissionManager().sub(TARGET).getPhase());
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(101010113).getPhase());
    var repeated = session.request("PVEBattleResultCsReq", report(last, "BATTLE_END_WIN"));
    assertNotEquals(0, field(repeated, "retcode"));
    assertEquals(0, field(repeated, "event_id"));
  }

  @Test void lossEchoesTheActualEventWithoutCompletingTheVictoryGoal() throws Exception {
    var battle = enter(FIRST);
    var reply = finish(battle, "BATTLE_END_LOSE");
    assertEquals(0, field(reply, "retcode"));
    assertEquals(FIRST, field(reply, "event_id"));
    assertEquals("BATTLE_END_LOSE", ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(reply, "end_status")).getName());
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(TARGET).getPhase());
  }

  @Test void rejectedIdentityCannotPublishAnEventOrConsumeTheBattle() throws Exception {
    var battle = enter(FIRST);
    var invalid = new HashMap<>(report(battle, "BATTLE_END_WIN"));
    invalid.put("stage_id", LAST * 10);
    var reply = session.request("PVEBattleResultCsReq", invalid);
    assertNotEquals(0, field(reply, "retcode"));
    assertEquals(0, field(reply, "event_id"));
    assertSame(battle, actor.getBattle());
    assertEquals(FIRST, field(finish(battle, "BATTLE_END_WIN"), "event_id"));
  }

  @Test void directStageEntryDoesNotInventAnEventByDividingItsId() throws Exception {
    var battle = Objects.requireNonNull(source.service.startBattle(actor, FIRST * 10, actor.world));
    assertEquals(0, field(finish(battle, "BATTLE_END_WIN"), "event_id"));
  }

  @Test void twoEventsSharingAStageStillReplyWithTheAdmittedEvent() throws Exception {
    int alias = 29999999;
    GameData.getPlaneEventExcelMap().add(source.gson.fromJson(
        "{\"EventID\":" + alias + ",\"StageID\":" + FIRST * 10 + "}", PlaneEventExcel.class));
    var battle = enter(alias);
    assertEquals(FIRST * 10, battle.getStage().getId());
    assertEquals(alias, field(finish(battle, "BATTLE_END_WIN"), "event_id"));
  }
}
