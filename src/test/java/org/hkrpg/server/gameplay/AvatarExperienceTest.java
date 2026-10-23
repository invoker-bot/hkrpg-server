package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.drops.DropService;
import emu.lunarcore.game.enums.StageType;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.avatar.*;
import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import it.unimi.dsi.fastutil.ints.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AvatarExperienceTest {
  final DailyUpgradeTest f = new DailyUpgradeTest();
  final Gson gson = new Gson();
  int worldLevel;
  boolean rogue, extraParty;

  @BeforeEach void setup() throws Exception {
    f.setup(); f.prepare(2100107);
    var field = emu.lunarcore.game.player.Player.class.getDeclaredField("lineupManager");
    field.setAccessible(true);
    field.set(f.player, new emu.lunarcore.game.player.lineup.LineupManager(f.player));
    try (var in = getClass().getResourceAsStream("/fixtures/avatar-progression-4.6.json")) {
      var source = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
      for (var file : source.getAsJsonArray("files")) {
        String name = file.getAsJsonObject().get("path").getAsString();
        for (var entry : file.getAsJsonObject().getAsJsonArray("rows")) {
          var row = entry.getAsJsonObject().getAsJsonObject("row").deepCopy();
          switch (name) {
            case "ExcelOutput/AvatarConfig.json" -> {
              row.add("AvatarName", row.getAsJsonObject("AvatarName").get("Hash"));
              var value = gson.fromJson(row, AvatarExcel.class);
              f.install(GameData.getAvatarExcelMap(), value); value.onLoad();
            }
            case "ExcelOutput/AvatarPromotionConfig.json" -> f.install(GameData.getAvatarPromotionExcelMap(), gson.fromJson(row, AvatarPromotionExcel.class));
            case "ExcelOutput/ExpType.json" -> f.install(GameData.getExpTypeExcelMap(), gson.fromJson(row, ExpTypeExcel.class));
            case "ExcelOutput/MonsterDrop.json" -> {
              var value = gson.fromJson(row, MonsterDropExcel.class); value.onLoad();
              f.install(GameData.getMonsterDropExcelMap(), value);
            }
            case "ExcelOutput/NPCMonsterData.json" -> {
              row.add("NPCName", row.getAsJsonObject("NPCName").get("Hash"));
              f.install(GameData.getNpcMonsterExcelMap(), gson.fromJson(row, NpcMonsterExcel.class));
            }
            case "ExcelOutput/ItemConfig.json" -> {
              row.add("ItemName", row.getAsJsonObject("ItemName").get("Hash"));
              f.install(GameData.getItemExcelMap(), gson.fromJson(row, ItemExcel.class));
            }
            case "ExcelOutput/AvatarExpItemConfig.json" -> { /* Installed after item rows below. */ }
            case "ExcelOutput/MultiplePathAvatarConfig.json" -> f.install(GameData.getMultiplePathAvatarExcelMap(), gson.fromJson(row, MultiplePathAvatarExcel.class));
            default -> fail("Unexpected fixture file " + name);
          }
        }
      }
      for (var file : source.getAsJsonArray("files"))
        if (file.getAsJsonObject().get("path").getAsString().endsWith("AvatarExpItemConfig.json"))
          for (var entry : file.getAsJsonObject().getAsJsonArray("rows")) {
            var row = entry.getAsJsonObject().getAsJsonObject("row");
            GameData.getItemExcelMap().get(row.get("ItemID").getAsInt()).setAvatarExp(row.get("Exp").getAsInt());
          }
    }
  }
  @AfterEach void cleanup() { f.cleanup(); }

  Battle battle() {
    var lineup = new PlayerLineup(f.player, 0) {
      public boolean isExtraLineup() { return extraParty; }
    };
    lineup.getAvatars().add(f.avatar.getAvatarId());
    var stage = new BattleStage() {
      public int getId() { return 100201010; }
      public StageType getStageType() { return StageType.Mainline; }
      public List<IntList> getMonsterWaves() { return List.of(new IntArrayList(new int[] {1002010})); }
    };
    // Authored NPC/drop rows; the encounter arrangement and item-drop isolation are test-only.
    var scene = new Scene(f.player, gson.fromJson(
        "{\"PlaneID\":20002,\"WorldID\":101,\"PlaneType\":\"" + (rogue ? "Rogue" : "Maze") + "\"}", MazePlaneExcel.class), 20002001);
    var monster = new EntityMonster(scene,
        GameData.getNpcMonsterExcelMap().get(1002020),
        gson.fromJson("{\"GroupID\":1}", GroupInfo.class),
        gson.fromJson("{\"ID\":1,\"PosX\":0,\"PosY\":0,\"PosZ\":0}", MonsterInfo.class)) {
      public void calculateDrops(ItemParamMap drops) {}
    };
    monster.setCustomStage(stage);
    monster.setWorldLevel(worldLevel);
    var battle = new Battle(f.player, lineup, List.of(monster));
    f.player.setBattle(battle);
    return battle;
  }

  BattleService service() {
    return new BattleService(null) {
      protected void grantDrops(Battle battle) { new DropService(null).calculateDrops(battle); }
    };
  }

  void hero(int path) {
    var hero = new GameAvatar(GameData.getAvatarExcelMap().get(8001)) { public void save() {} };
    hero.setOwner(f.player);
    hero.setMultiPath(new AvatarMultiPath(f.player, GameData.getAvatarExcelMap().get(path)));
    f.player.getAvatars().getAvatars().put(8001, hero);
    f.avatar = hero;
  }

  Battle finish(Battle battle, BattleEndStatus result) {
    return service().finishBattle(f.player, battle.getId(), battle.getStage().getId(), result, BattleStatistics.newInstance());
  }

  @Test void victoryAddsAuthoredExperienceToTheActualParticipantsOnlyOnce() {
    var battle = battle();
    int money = f.player.getScoin(), books = f.material.getCount();
    assertSame(battle, service().finishBattle(f.player, battle.getId(), battle.getStage().getId(),
        BattleEndStatus.BATTLE_END_WIN, BattleStatistics.newInstance()));
    assertEquals(36, f.avatar.getExp(), "Monster XP must reach the participating owned character");
    assertEquals(1, f.avatar.getLevel());
    assertEquals(money, f.player.getScoin());
    assertEquals(books, f.material.getCount());
    assertNull(service().finishBattle(f.player, battle.getId(), battle.getStage().getId(),
        BattleEndStatus.BATTLE_END_WIN, BattleStatistics.newInstance()));
    assertEquals(36, f.avatar.getExp());
  }

  @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
  void usesTheAuthoredWorldLevelReward(int world) {
    worldLevel = world;
    var battle = battle();
    assertSame(battle, finish(battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(36 + world * 12, f.avatar.getExp());
  }

  @ParameterizedTest @ValueSource(ints = {8001, 8002, 1202})
  void sixBooksProduceTheSameLevelAndCostAsTheClientPreview(int id) {
    if (id != 1202) hero(id);
    var books = f.item(212, 6);
    int credits = f.player.getScoin();
    assertNotNull(f.service.levelUpAvatar(f.player, id, List.of(new ItemParam(212, 6))));
    assertEquals(12, f.avatar.getLevel());
    assertEquals(5990, f.avatar.getExp());
    assertEquals(credits - 3000, f.player.getScoin());
    assertEquals(0, books.getCount());
    assertNull(f.service.levelUpAvatar(f.player, id, List.of(new ItemParam(212, 6))));
    assertEquals(credits - 3000, f.player.getScoin());
  }

  @ParameterizedTest @ValueSource(ints = {8001, 8002})
  void battleLevelUpUsesTheOwnedBaseCharacterAndDoesNotGrantReserveMembers(int path) {
    var reserve = f.avatar;
    hero(path); f.avatar.setExp(190);
    var battle = battle();
    assertSame(battle, finish(battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(2, f.avatar.getLevel());
    assertEquals(26, f.avatar.getExp());
    assertEquals(0, reserve.getExp());
  }

  @Test void captureDoesNotChangeWhenTheEncounterOrSourceIsMutatedAfterEntry() {
    var battle = battle();
    battle.getNpcMonsters().clear();
    f.install(GameData.getMonsterDropExcelMap(), gson.fromJson(
        "{\"MonsterTemplateID\":1002020,\"AvatarExpReward\":999}", MonsterDropExcel.class));
    assertSame(battle, finish(battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(36, f.avatar.getExp());
  }

  @Test void lossAndQuitDoNotGiveExperience() {
    for (var result : List.of(BattleEndStatus.BATTLE_END_LOSE, BattleEndStatus.BATTLE_END_QUIT)) {
      var battle = battle(); assertSame(battle, finish(battle, result));
      assertEquals(0, f.avatar.getExp());
    }
  }

  @Test void capsExperienceAtTheCurrentPromotionWithoutGrantingRefundBooks() {
    f.avatar.setLevel(19); f.avatar.setExp(15860);
    int books = f.material.getCount();
    var battle = battle(); assertSame(battle, finish(battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(20, f.avatar.getLevel()); assertEquals(0, f.avatar.getExp());
    battle = battle(); assertSame(battle, finish(battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(20, f.avatar.getLevel()); assertEquals(0, f.avatar.getExp());
    assertEquals(books, f.material.getCount());
  }

  @Test void rogueAndTemporaryPartyBattlesDoNotGrantPermanentExperience() {
    rogue = true;
    var battle = battle(); assertSame(battle, finish(battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, f.avatar.getExp());
    rogue = false; extraParty = true;
    battle = battle(); assertSame(battle, finish(battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, f.avatar.getExp());
  }

  @Test void missingFutureCurveCannotConsumeUpgradeMaterialsOrCoins() {
    f.install(GameData.getExpTypeExcelMap(), gson.fromJson("{\"TypeID\":1,\"Level\":2}", ExpTypeExcel.class));
    var books = f.item(212, 6); int credits = f.player.getScoin();
    assertNull(f.service.levelUpAvatar(f.player, 1202, List.of(new ItemParam(212, 6))));
    assertEquals(6, books.getCount()); assertEquals(credits, f.player.getScoin());
    assertEquals(1, f.avatar.getLevel()); assertEquals(0, f.avatar.getExp());
  }

  @Test void invalidExperienceCannotConsumeTheBattleSettlement() {
    f.avatar.setExp(200);
    var battle = battle();
    assertNull(finish(battle, BattleEndStatus.BATTLE_END_WIN));
    assertFalse(battle.isSettling()); assertSame(battle, f.player.getBattle());
    assertEquals(200, f.avatar.getExp());
  }
}
