package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.battle.skills.MazeSkillSetAttackTargetMonsterDie;
import emu.lunarcore.game.enums.*;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.game.scene.triggers.PropTrigger;
import emu.lunarcore.game.scene.triggers.PropTriggerType;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Complete source placements and explicitly assembled scenes; not official client acceptance. */
class MazeInstantKillPreflightTest {
  @TempDir Path root;
  final SceneMonsterDefeatLifecycleTest base = new SceneMonsterDefeatLifecycleTest();
  JsonObject proof;

  @BeforeEach
  void prepare() throws Exception {
    base.root = root;
    base.prepare();
    try (var in = getClass().getResourceAsStream("/fixtures/instant-kill-preflight-4.6.json")) {
      assertNotNull(in);
      proof =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
  }

  @AfterEach
  void restore() throws Exception {
    base.restore();
  }

  EntityMonster monster(JsonObject row, NpcMonsterExcel excel, int groupId) {
    var scene = base.player.getScene();
    var group = base.fixture.gson.fromJson("{\"LoadSide\":\"Server\"}", GroupInfo.class);
    group.setId(groupId);
    var info = base.fixture.gson.fromJson(row, MonsterInfo.class);
    var target =
        new EntityMonster(scene, excel, group, info) {
          public void calculateDrops(ItemParamMap result) {
            base.drops++;
          }
        };
    scene.addEntity(target, false);
    return target;
  }

  EntityMonster assembled(String rank, String tags) {
    var excel =
        base.fixture.gson.fromJson(
            "{\"ID\":1999987,\"Rank\":\"" + rank + "\"}", NpcMonsterExcel.class);
    var row =
        JsonParser.parseString(
                "{\"ID\":500001,\"NPCMonsterID\":1999987"
                    + (tags == null ? "" : ",\"TagList\":" + tags)
                    + "}")
            .getAsJsonObject();
    return monster(row, excel, 987);
  }

  void attack(EntityMonster... targets) {
    new MazeSkillSetAttackTargetMonsterDie()
        .onAttack(base.player.getCurrentLeaderAvatar(), Arrays.asList(targets));
  }

  void plane(String type) throws Exception {
    var excel =
        base.fixture.gson.fromJson(
            "{\"PlaneID\":19999881,\"PlaneType\":\"" + type + "\"}", MazePlaneExcel.class);
    var scene = new Scene(base.player, excel, 0);
    var field = Player.class.getDeclaredField("scene");
    field.setAccessible(true);
    field.set(base.player, scene);
    scene.getTriggers().add(base.counter(PropTriggerType.MONSTER_DIE));
    scene.getTriggers().add(base.counter(PropTriggerType.PUZZLE_FINISH));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void unchangedOriginalResistantPlacementCannotBeInstantlyDefeated(int index) {
    var row =
        proof.getAsJsonArray("placements").get(index).getAsJsonObject().getAsJsonObject("row");
    var npc =
        proof.getAsJsonArray("npcRows").asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(r -> r.get("ID").getAsInt() == row.get("NPCMonsterID").getAsInt())
            .findFirst()
            .orElseThrow();
    var target = monster(row, base.fixture.gson.fromJson(npc, NpcMonsterExcel.class), 3);
    int entityId = target.getEntityId();
    base.player
        .getScene()
        .getTriggers()
        .add(
            new PropTrigger() {
              public PropTriggerType getType() {
                return PropTriggerType.MONSTER_DIE;
              }

              public boolean shouldRun(int group, int instance) {
                return group == 3 && instance == row.get("ID").getAsInt();
              }

              public void run(Scene scene) {
                base.deaths++;
              }
            });
    attack(target, target);
    assertTrue(target.isAlive(), "The complete original placement must retain ResistInstantKill");
    assertSame(target, base.player.getScene().getEntityById(entityId));
    base.noEffects();
  }

  @ParameterizedTest
  @ValueSource(strings = {"Minion", "MinionLv2"})
  void challengeSceneRejectsInstantKillBeforeDeathAndDrops(String rank) throws Exception {
    plane("Challenge");
    var target = assembled(rank, "[]");
    attack(target);
    assertTrue(target.isAlive());
    base.noEffects();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "[null]",
        "[\"UnknownTag\"]",
        "[3]",
        "[\"resistInstantKill\"]",
        "[\"Default\",null]"
      })
  void malformedOrUnrecognizedPlacementTagsDoNotGrantDeathOrDrops(String tags) {
    var target = assembled("Minion", tags);
    attack(target);
    assertTrue(target.isAlive());
    base.noEffects();
  }

  @ParameterizedTest
  @CsvSource({
    "Minion,Default",
    "MinionLv2,Default",
    "Minion,Trotter",
    "MinionLv2,Trotter",
    "Minion,TrotterLover",
    "MinionLv2,TrotterLover",
    "Minion,FakeChest",
    "MinionLv2,FakeChest",
    "Minion,TriggerBattleFirstWave",
    "MinionLv2,TriggerBattleFirstWave"
  })
  void knownNonResistantTagsPermitActualDeathOnce(String rank, String tag) {
    var target = assembled(rank, "[\"" + tag + "\",\"" + tag + "\"]");
    attack(target, target);
    attack(target);
    assertFalse(target.isAlive());
    assertEquals(1, base.deaths);
    assertEquals(1, base.drops);
    assertEquals(0, base.puzzles);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Maze", "Raid", "Rogue"})
  void currentPlaneNamesAreNotConfusedWithDifferentNativeModeNumbers(String type) throws Exception {
    plane(type);
    var target = assembled("Minion", null);
    attack(target);
    assertFalse(target.isAlive());
    assertEquals(1, base.deaths);
    assertEquals(1, base.drops);
  }

  @Test
  void mixedTargetsOnlyDefeatEligibleMonsterAndCreditItsMissionOnce() {
    base.killGoals();
    var resistant = assembled("Minion", "[\"ResistInstantKill\"]");
    var eligible = assembled("MinionLv2", "[]");
    attack(resistant, eligible, resistant, eligible);
    attack(resistant, eligible);
    assertTrue(resistant.isAlive());
    assertFalse(eligible.isAlive());
    assertEquals(1, base.deaths);
    assertEquals(1, base.drops);
    for (int id : List.of(199998711, 199998712, 199998713))
      assertEquals(1, base.player.getMissionManager().sub(id).getProgress());
  }

  @ParameterizedTest
  @CsvSource({
    "Maze,BATTLE_END_WIN",
    "Maze,BATTLE_END_LOSE",
    "Maze,BATTLE_END_QUIT",
    "Challenge,BATTLE_END_WIN",
    "Challenge,BATTLE_END_LOSE",
    "Challenge,BATTLE_END_QUIT"
  })
  void acceptedBattleRemainsIndependentOfInstantKillEligibility(String type, String result)
      throws Exception {
    plane(type);
    var target = assembled("Minion", "[\"ResistInstantKill\"]");
    BattleStage stage =
        new BattleStage() {
          public int getId() {
            return 19999870;
          }

          public StageType getStageType() {
            return StageType.FarmElement;
          }

          public List<it.unimi.dsi.fastutil.ints.IntList> getMonsterWaves() {
            return List.of(new it.unimi.dsi.fastutil.ints.IntArrayList(new int[] {1}));
          }
        };
    var battle = new Battle(base.player, base.player.getCurrentLineup(), stage, false);
    battle.getNpcMonsters().add(target);
    base.player.setBattle(battle);
    var service =
        new BattleService(null) {
          protected void grantDrops(Battle b) {}
        };
    assertSame(
        battle,
        service.finishBattle(
            base.player,
            battle.getId(),
            stage.getId(),
            BattleEndStatus.valueOf(result),
            BattleStatistics.newInstance()));
    boolean win = result.equals("BATTLE_END_WIN");
    assertEquals(!win, target.isAlive());
    assertEquals(win ? 1 : 0, base.deaths);
    assertNull(
        service.finishBattle(
            base.player,
            battle.getId(),
            stage.getId(),
            BattleEndStatus.valueOf(result),
            BattleStatistics.newInstance()));
    assertEquals(win ? 1 : 0, base.deaths);
  }
}
