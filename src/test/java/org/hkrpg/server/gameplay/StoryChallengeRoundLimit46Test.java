package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.challenge.ChallengeInstance;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * All forty original Normal resources. Roster/scene and half selection are explicit Java fixtures.
 */
class StoryChallengeRoundLimit46Test {
  final StoryChallengeScope46Test combat = new StoryChallengeScope46Test();
  @TempDir Path directory;
  final Map<Integer, ChallengeExcel> installed = new HashMap<>(), prior = new HashMap<>();
  final List<StageExcel> priorStages = new ArrayList<>();
  final List<NpcMonsterExcel> priorNPCs = new ArrayList<>();
  final List<PlaneEventExcel> priorEvents = new ArrayList<>();
  Map<String, JsonArray> rows = new HashMap<>();

  static List<Integer> ids() {
    var result = new ArrayList<Integer>();
    for (int g = 1; g <= 10; g++) for (int l = 1; l <= 4; l++) result.add(20000 + g * 10 + l);
    return result;
  }

  @BeforeEach
  void setup() throws Exception {
    for (int id : ids()) {
      var value = GameData.getChallengeExcelMap().get(id);
      if (value != null) prior.put(id, value);
    }
    combat.directory = directory.resolve("combat");
    combat.setup();
    try (var stream =
        getClass().getResourceAsStream("/fixtures/story-challenge-round-limit-4.6.json")) {
      assertNotNull(stream);
      var input =
          JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
      assertEquals(
          "724b139d8c9c32d12552eb95745a4fee72bfe48b", input.get("sourceCommit").getAsString());
      for (var raw : input.getAsJsonArray("inputs")) {
        var entry = raw.getAsJsonObject();
        rows.put(entry.get("path").getAsString(), entry.getAsJsonArray("rows"));
      }
    }
    for (var entry : rows.entrySet()) {
      Path out = directory.resolve(entry.getKey());
      Files.createDirectories(out.getParent());
      Files.writeString(out, entry.getValue().toString());
    }
    StoryChallengeCatalog.load(directory);
    var extras = new HashMap<Integer, ChallengeStoryExtraExcel>();
    for (var raw : rows.get("ExcelOutput/ChallengeStoryMazeExtra.json")) {
      var e = combat.gson.fromJson(raw, ChallengeStoryExtraExcel.class);
      extras.put(e.getId(), e);
    }
    for (var raw : rows.get("ExcelOutput/MazeBuff.json"))
      GameData.getMazeBuffExcelMap().add(combat.gson.fromJson(raw, MazeBuffExcel.class));
    for (var raw : rows.get("ExcelOutput/ChallengeStoryMazeConfig.json")) {
      var excel = combat.gson.fromJson(raw, ChallengeExcel.class);
      excel.onLoad();
      excel.setStoryExcel(extras.get(excel.getId()));
      installed.put(excel.getId(), excel);
      GameData.getChallengeExcelMap().add(excel);
      assertTrue(StoryChallengeCatalog.supports(excel));
    }
    assertEquals(new HashSet<>(ids()), installed.keySet());
    for (var raw : rows.get("ExcelOutput/StageConfig.json")) {
      var row = combat.gson.fromJson(raw, StageExcel.class);
      var old = GameData.getStageExcelMap().get(row.getId());
      if (old != null) priorStages.add(old);
      row.onLoad();
      GameData.getStageExcelMap().add(row);
    }
    for (var raw : rows.get("ExcelOutput/NPCMonsterData.json")) {
      var row = combat.gson.fromJson(raw, NpcMonsterExcel.class);
      var old = GameData.getNpcMonsterExcelMap().get(row.getId());
      if (old != null) priorNPCs.add(old);
      GameData.getNpcMonsterExcelMap().add(row);
    }
    for (var raw : rows.get("ExcelOutput/PlaneEvent.json")) {
      var row = combat.gson.fromJson(raw, PlaneEventExcel.class);
      var old = GameData.getPlaneEventExcelMap().get(row.getEventId(), row.getWorldLevel());
      if (old != null) priorEvents.add(old);
      GameData.getPlaneEventExcelMap().add(row);
    }
  }

  @AfterEach
  void cleanup() throws Exception {
    combat.cleanup();
    for (int id : ids()) GameData.getChallengeExcelMap().keySet().remove(id);
    prior.values().forEach(GameData.getChallengeExcelMap()::add);
    for (var raw : rows.get("ExcelOutput/StageConfig.json"))
      GameData.getStageExcelMap().keySet().remove(raw.getAsJsonObject().get("StageID").getAsInt());
    priorStages.forEach(GameData.getStageExcelMap()::add);
    for (var raw : rows.get("ExcelOutput/NPCMonsterData.json"))
      GameData.getNpcMonsterExcelMap().keySet().remove(raw.getAsJsonObject().get("ID").getAsInt());
    priorNPCs.forEach(GameData.getNpcMonsterExcelMap()::add);
    var events = Set.of(30019041, 30019042);
    GameData.getPlaneEventExcelMap()
        .values()
        .removeIf(x -> events.contains(x.getEventId()) && x.getWorldLevel() == 0);
    priorEvents.forEach(GameData.getPlaneEventExcelMap()::add);
  }

  int wireRound(ChallengeInstance instance) throws Exception {
    var core =
        emu.lunarcore.proto.GetCurChallengeScRspOuterClass.GetCurChallengeScRsp.newInstance()
            .setChallengeInfo(instance.toProto());
    var response =
        combat
            .fixture
            .protocol
            .clientSchema()
            .parse(
                "GetCurChallengeScRsp",
                combat.fixture.protocol.translate(
                    "GetCurChallengeScRsp", core.toByteArray(), true));
    assertTrue(response.getUnknownFields().asMap().isEmpty());
    var value =
        (com.google.protobuf.DynamicMessage)
            response.getField(response.getDescriptorForType().findFieldByName("cur_challenge"));
    return (Integer) value.getField(value.getDescriptorForType().findFieldByName("round_count"));
  }

  @ParameterizedTest
  @MethodSource("ids")
  void eachOriginalNativeNormalChallengeUsesItsOwnTurnLimit(int id) throws Exception {
    var excel = installed.get(id);
    var instance = new ChallengeInstance(combat.player, excel);
    combat.player.setInstance(instance);
    int expected = excel.getStoryExcel().getTurnLimit();
    assertEquals(expected, instance.getRoundsLeft());
    assertEquals(expected, wireRound(instance));
    instance.addBuff(excel.getMazeBuffID());
    instance.addBuff(excel.getMazeBuffID());
    var battle =
        new Battle(
            combat.player,
            combat.player.getCurrentLineup(),
            GameData.getStageExcelMap().get(30019041));
    instance.onBattleStart(battle);
    assertEquals(expected, battle.getRoundsLimit());
  }

  @ParameterizedTest
  @MethodSource("ids")
  void legacyNormalInitializationRetainsExistingFiveRoundBehavior(int id) {
    combat.player.nativeProtocol = false;
    var instance = new ChallengeInstance(combat.player, installed.get(id));
    assertEquals(5, instance.getRoundsLeft());
  }

  @Test
  void originalFourRoundEncounterUsesFourInBothHalvesAndSettlesOnce() throws Exception {
    var excel = installed.get(20014);
    var instance = new ChallengeInstance(combat.player, excel);
    instance.addBuff(3031301);
    instance.addBuff(3031302);
    combat.player.setInstance(instance);
    for (int half = 1; half <= 2; half++) {
      assertEquals(4, wireRound(instance));
      var group = new GroupInfo();
      group.setId(half == 1 ? 6 : 7);
      var monster =
          new EntityMonster(
              combat.player.scene,
              GameData.getNpcMonsterExcelMap().get(half == 1 ? 1004020 : 1014010),
              group,
              combat.gson.fromJson("{\"ID\":200001}", MonsterInfo.class));
      monster.setEventId(half == 1 ? 30019041 : 30019042);
      monster.setEntityId(100 + half);
      combat.player.scene.getEntities().put(100 + half, monster);
      var battle = new Battle(combat.player, combat.player.getCurrentLineup(), List.of(monster));
      instance.onBattleStart(battle);
      combat.player.setBattle(battle);
      assertEquals(4, battle.getRoundsLimit());
      assertTrue(battle.getStoryChallengeBinding().required());
      assertNotNull(battle.getStoryChallengeBinding().context());
      int score = half == 1 ? 12000 : 40000;
      var stats = BattleStatistics.newInstance().setChallengeScore(score).setRoundCnt(4);
      assertNotNull(
          combat.fixture.fixture.service.finishBattle(
              combat.player,
              battle.getId(),
              battle.getStage().getId(),
              BattleEndStatus.BATTLE_END_WIN,
              stats));
      assertNull(
          combat.fixture.fixture.service.finishBattle(
              combat.player,
              battle.getId(),
              battle.getStage().getId(),
              BattleEndStatus.BATTLE_END_WIN,
              stats));
    }
    assertTrue(instance.isWin());
    assertEquals(40000, instance.getTotalScore());
    assertEquals(4, instance.getRoundsLeft());
  }
}
