package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.challenge.ChallengeInstance;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Complete original encounter rows, with explicit scene/roster/save fixtures. */
class StoryChallengeScope46Test {
  final StoryChallenge46Test fixture = new StoryChallenge46Test();
  final Gson gson =
      new GsonBuilder()
          .registerTypeAdapter(
              long.class,
              (JsonDeserializer<Long>)
                  (value, type, context) ->
                      value.isJsonObject()
                          ? value.getAsJsonObject().get("Hash").getAsLong()
                          : value.getAsLong())
          .create();
  @TempDir Path directory;
  List<StageExcel> oldStages;
  List<NpcMonsterExcel> oldNpcs;
  List<PlaneEventExcel> oldEvents;
  List<CocoonExcel> oldCocoons;
  MazePlaneExcel plane;
  ScopePlayer player;

  static class ScopePlayer extends StoryChallenge46Test.StoryPlayer {
    int plane = 30102, floor = 30102005, world;

    public int getPlaneId() {
      return plane;
    }

    public int getFloorId() {
      return floor;
    }

    public int getWorldLevel() {
      return world;
    }
  }

  @BeforeEach
  void setup() throws Exception {
    fixture.directory = directory.resolve("catalog");
    fixture.setup();
    oldStages =
        GameData.getStageExcelMap().values().stream()
            .filter(x -> stageIDs().contains(x.getId()))
            .toList();
    oldNpcs =
        GameData.getNpcMonsterExcelMap().values().stream()
            .filter(x -> List.of(1023010, 2023020).contains(x.getId()))
            .toList();
    oldEvents =
        GameData.getPlaneEventExcelMap().values().stream()
            .filter(x -> List.of(30019011, 30019012).contains(x.getEventId()))
            .toList();
    oldCocoons =
        GameData.getCocoonExcelMap().values().stream()
            .filter(x -> x.getCocoonId() == 1001)
            .toList();
    GameData.getPlaneEventExcelMap()
        .values()
        .removeIf(x -> List.of(30019011, 30019012).contains(x.getEventId()));
    try (var in =
        getClass().getResourceAsStream("/fixtures/story-challenge-battle-scope-4.6.json")) {
      assertNotNull(in);
      var root =
          JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
      assertEquals(
          "724b139d8c9c32d12552eb95745a4fee72bfe48b", root.get("sourceCommit").getAsString());
      for (var input : root.getAsJsonArray("inputs")) {
        var entry = input.getAsJsonObject();
        for (var raw : entry.getAsJsonArray("rows"))
          switch (entry.get("path").getAsString()) {
            case "ExcelOutput/StageConfig.json" -> {
              var row = gson.fromJson(raw, StageExcel.class);
              row.onLoad();
              GameData.getStageExcelMap().add(row);
            }
            case "ExcelOutput/NPCMonsterData.json" ->
                GameData.getNpcMonsterExcelMap().add(gson.fromJson(raw, NpcMonsterExcel.class));
            case "ExcelOutput/PlaneEvent.json" ->
                GameData.getPlaneEventExcelMap().add(gson.fromJson(raw, PlaneEventExcel.class));
            case "ExcelOutput/MapEntrance.json" ->
                GameData.getMapEntranceExcelMap().add(gson.fromJson(raw, MapEntranceExcel.class));
            case "ExcelOutput/MazePlane.json" -> plane = gson.fromJson(raw, MazePlaneExcel.class);
            case "ExcelOutput/CocoonConfig.json" ->
                GameData.getCocoonExcelMap().add(gson.fromJson(raw, CocoonExcel.class));
            default -> fail("Unexpected resource");
          }
      }
    }
    player = new ScopePlayer();
    player.entry = 3000205;
    player.setInstance(new ChallengeInstance(player, GameData.getChallengeExcelMap().get(20011)));
    ((ChallengeInstance) player.getInstance()).addBuff(3031301);
    ((ChallengeInstance) player.getInstance()).addBuff(3031302);
    player.getLineupManager().setCurrentExtraLineup(1, false);
    player.getCurrentLineup().getAvatars().add(199961);
    player.getLineupManager().getExtraLineupByType(3).getAvatars().add(199961);
    player.scene = scene();
  }

  Scene scene() {
    return new Scene(player, plane, 30102005) {
      public void loadGroup(int id) {}

      public void refreshMissionGroups() {}

      public emu.lunarcore.proto.SceneInfoOuterClass.SceneInfo toProto() {
        return emu.lunarcore.proto.SceneInfoOuterClass.SceneInfo.newInstance();
      }
    };
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.cleanup();
    GameData.getStageExcelMap().values().removeIf(x -> stageIDs().contains(x.getId()));
    oldStages.forEach(GameData.getStageExcelMap()::add);
    GameData.getNpcMonsterExcelMap()
        .values()
        .removeIf(x -> List.of(1023010, 2023020).contains(x.getId()));
    oldNpcs.forEach(GameData.getNpcMonsterExcelMap()::add);
    GameData.getPlaneEventExcelMap()
        .values()
        .removeIf(x -> List.of(30019011, 30019012).contains(x.getEventId()));
    oldEvents.forEach(GameData.getPlaneEventExcelMap()::add);
    GameData.getCocoonExcelMap().values().removeIf(x -> x.getCocoonId() == 1001);
    oldCocoons.forEach(GameData.getCocoonExcelMap()::add);
  }

  static List<Integer> stageIDs() {
    return List.of(30019011, 30019012, 1022010, 1022020, 1022030, 1012010);
  }

  Battle battle(int half) throws Exception {
    var challenge = (ChallengeInstance) player.getInstance();
    var field = ChallengeInstance.class.getDeclaredField("currentStage");
    field.setAccessible(true);
    field.setInt(challenge, half);
    if (half == 2) {
      challenge.setScoreStage1(12000);
      var extra = ChallengeInstance.class.getDeclaredField("currentExtraLineup");
      extra.setAccessible(true);
      extra.setInt(challenge, 3);
      player.getLineupManager().setCurrentExtraLineup(3, false);
    }
    var group = new GroupInfo();
    group.setId(half == 1 ? 6 : 7);
    var monster =
        new EntityMonster(
            player.scene,
            GameData.getNpcMonsterExcelMap().get(half == 1 ? 1023010 : 2023020),
            group,
            gson.fromJson("{\"ID\":200001}", MonsterInfo.class));
    monster.setEventId(half == 1 ? 30019011 : 30019012);
    monster.setEntityId(99);
    player.scene.getEntities().put(99, monster);
    var battle = new Battle(player, player.getCurrentLineup(), List.of(monster));
    challenge.onBattleStart(battle);
    player.setBattle(battle);
    return battle;
  }

  BattleStatistics stats() {
    return BattleStatistics.newInstance().setChallengeScore(23000);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void originalEncounterInEachHalfCanSettleOnce(int half) throws Exception {
    var battle = battle(half);
    assertTrue(StoryChallengeReports.valid(battle, BattleEndStatus.BATTLE_END_WIN, stats()));
    assertNotNull(
        fixture.fixture.service.finishBattle(
            player,
            battle.getId(),
            battle.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            stats()));
    assertNull(
        fixture.fixture.service.finishBattle(
            player,
            battle.getId(),
            battle.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            stats()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "instance",
        "noInstance",
        "half",
        "status",
        "entry",
        "plane",
        "floor",
        "scene",
        "lineup",
        "roster",
        "choice",
        "score",
        "npcEvent",
        "npcEntity",
        "npcRemoved",
        "extraNpc",
        "missingWave",
        "waveMonsters",
        "waveLevel",
        "stageResource",
        "world",
        "waveOrder",
        "baseBuff",
        "selectedBuff",
        "rounds",
        "counter"
      })
  void changedBattleScopeDoesNotConsumeTheBattleOrAdvanceScore(String mode) throws Exception {
    var battle = battle(1);
    var challenge = (ChallengeInstance) player.getInstance();
    var npc = battle.getNpcMonsters().getFirst();
    switch (mode) {
      case "instance" -> player.setInstance(new ChallengeInstance(player, challenge.getExcel()));
      case "noInstance" -> player.setInstance(null);
      case "half", "status" -> {
        var f =
            ChallengeInstance.class.getDeclaredField(
                mode.equals("half") ? "currentStage" : "status");
        f.setAccessible(true);
        f.setInt(challenge, mode.equals("half") ? 2 : 3);
      }
      case "entry" -> player.entry++;
      case "plane" -> player.plane++;
      case "floor" -> player.floor++;
      case "scene" -> player.scene = scene();
      case "lineup" -> player.getLineupManager().setCurrentExtraLineup(3, false);
      case "roster" -> player.getCurrentLineup().getAvatars().add(199962);
      case "choice" -> challenge.getBuffs().set(0, 3031303);
      case "score" -> challenge.setScoreStage1(1);
      case "npcEvent" -> npc.setEventId(30019012);
      case "npcEntity" -> npc.setEntityId(100);
      case "npcRemoved" -> player.scene.getEntities().remove(99);
      case "extraNpc" -> battle.getNpcMonsters().add(npc);
      case "missingWave" -> battle.getWaves().removeLast();
      case "waveMonsters" -> battle.getWaves().getFirst().getMonsters().clear();
      case "waveLevel" -> battle.getWaves().getFirst().setCustomLevel(1);
      case "stageResource" ->
          GameData.getStageExcelMap().keySet().remove(battle.getStage().getId());
      case "world" -> player.world++;
      case "waveOrder" -> {
        assertTrue(battle.getWaves().size() > 1);
        Collections.swap(battle.getWaves(), 0, 1);
      }
      case "baseBuff" -> battle.getBuffs().remove(3031001);
      case "selectedBuff" -> battle.getBuffs().remove(3031301);
      case "rounds" -> battle.setRoundsLimit(99);
      case "counter" -> battle.getBattleTargets().clear();
      default -> fail();
    }
    int before = challenge.getTotalScore();
    var savedBefore = player.saved;
    assertFalse(StoryChallengeReports.valid(battle, BattleEndStatus.BATTLE_END_WIN, stats()));
    assertNull(
        fixture.fixture.service.finishBattle(
            player,
            battle.getId(),
            battle.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            stats()));
    assertSame(battle, player.getBattle());
    assertFalse(battle.isSettling());
    assertEquals(before, challenge.getTotalScore());
    assertSame(savedBefore, player.saved);
  }

  @Test
  void unboundDirectStageCannotBeScoredAsAStoryEncounter() {
    var battle =
        new Battle(player, player.getCurrentLineup(), GameData.getStageExcelMap().get(30019011));
    player.setBattle(battle);
    assertFalse(StoryChallengeReports.valid(battle, BattleEndStatus.BATTLE_END_WIN, stats()));
  }

  @Test
  void rejectedScoreCanRetryAgainstTheSameFrozenEncounter() throws Exception {
    var battle = battle(2);
    assertNull(
        fixture.fixture.service.finishBattle(
            player,
            battle.getId(),
            battle.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance().setChallengeScore(11999)));
    assertSame(battle, player.getBattle());
    assertFalse(battle.isSettling());
    assertNotNull(
        fixture.fixture.service.finishBattle(
            player,
            battle.getId(),
            battle.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            stats()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"direct", "sceneEvent", "cocoon", "reenter"})
  void directStageEntriesCannotCreateAnUnboundStoryBattle(String mode) {
    int stamina = player.getStamina();
    var instance = player.getInstance();
    switch (mode) {
      case "direct" -> assertNull(fixture.fixture.service.startBattle(player, 30019011, 0));
      case "sceneEvent" -> assertNull(fixture.fixture.service.startEventBattle(player, 30019011));
      case "cocoon" -> assertNull(fixture.fixture.service.startCocoon(player, 1001, 0, 1));
      case "reenter" -> fixture.fixture.service.reEnterBattle(player, 1012010);
      default -> fail();
    }
    assertNull(player.getBattle());
    assertSame(instance, player.getInstance());
    assertEquals(stamina, player.getStamina());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void nativeCurrentRoundCountUsesTheStoryLimitWithoutUnsignedNegativeWrapping(int half)
      throws Exception {
    battle(half);
    var challenge = (ChallengeInstance) player.getInstance();
    assertEquals(
        challenge.getExcel().getStoryExcel().getTurnLimit(), challenge.toProto().getRoundCount());
    assertEquals(5, challenge.toProto().getRoundCount());
  }
}
