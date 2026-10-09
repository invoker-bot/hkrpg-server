package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.battle.skills.MazeSkillSetAttackTargetMonsterDie;
import emu.lunarcore.game.enums.*;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.scene.*;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.game.scene.triggers.*;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Scene removals are not deaths; synthetic scene/encounter fixtures exercise real runtime paths.
 */
class SceneMonsterDefeatLifecycleTest {
  @TempDir Path root;
  final MazeCasterBuffRemovalTest fixture = new MazeCasterBuffRemovalTest();
  MazeCasterBuffRemovalTest.Capture player;
  int deaths, puzzles, drops;
  MainMissionExcel previousKillMain;
  MissionInfo previousKillInfo;
  static final int KILL_MAIN = 19999871;

  @BeforeEach
  void prepare() throws Exception {
    previousKillMain = GameData.getMainMissionExcelMap().get(KILL_MAIN);
    previousKillInfo = GameData.getMissionInfos().get(KILL_MAIN);
    fixture.root = root;
    player = fixture.owner(false);
    player.getScene().getTriggers().add(counter(PropTriggerType.MONSTER_DIE));
    player.getScene().getTriggers().add(counter(PropTriggerType.PUZZLE_FINISH));
  }

  @AfterEach
  void restore() throws Exception {
    fixture.cleanup();
    GameData.getMainMissionExcelMap().keySet().remove(KILL_MAIN);
    if (previousKillMain != null) GameData.getMainMissionExcelMap().add(previousKillMain);
    if (previousKillInfo == null) GameData.getMissionInfos().remove(KILL_MAIN);
    else GameData.getMissionInfos().put(KILL_MAIN, previousKillInfo);
  }

  PropTrigger counter(PropTriggerType type) {
    return new PropTrigger() {
      public PropTriggerType getType() {
        return type;
      }

      public boolean shouldRun(int group, int instance) {
        return group == 987 && instance == 500001;
      }

      public void run(Scene scene) {
        if (type == PropTriggerType.MONSTER_DIE) deaths++;
        else puzzles++;
      }
    };
  }

  EntityMonster monster(Scene scene, String rank, boolean register) {
    var excel =
        fixture.gson.fromJson(
            "{\"ID\":1999987,\"Rank\":" + (rank == null ? "null" : "\"" + rank + "\"") + "}",
            NpcMonsterExcel.class);
    var group = fixture.gson.fromJson("{\"LoadSide\":\"Server\"}", GroupInfo.class);
    group.setId(987);
    var info = fixture.gson.fromJson("{\"ID\":500001,\"NPCMonsterID\":1999987}", MonsterInfo.class);
    var monster =
        new EntityMonster(scene, excel, group, info) {
          public void calculateDrops(ItemParamMap result) {
            synchronized (SceneMonsterDefeatLifecycleTest.this) {
              drops++;
            }
          }
        };
    if (register) scene.addEntity(monster, false);
    return monster;
  }

  void noEffects() {
    assertEquals(0, deaths);
    assertEquals(0, puzzles);
    assertEquals(0, drops);
  }

  @ParameterizedTest
  @ValueSource(strings = {"object", "id", "group"})
  void administrativeRemovalDoesNotReportMonsterDeath(String mode) {
    var scene = player.getScene();
    var monster = monster(scene, "Minion", true);
    int id = monster.getEntityId();
    switch (mode) {
      case "object" -> scene.removeEntity(monster);
      case "id" -> scene.removeEntity(id);
      case "group" -> {
        var group =
            fixture.gson.fromJson(
                "{\"Category\":\"Mission\",\"LoadSide\":\"Server\",\"OwnerMainMissionID\":199999987,\"LoadOnInitial\":false}",
                GroupInfo.class);
        group.setId(987);
        scene.getFloorInfo().getGroups().put(987, group);
        scene.getLoadedGroups().add(987);
        assertFalse(player.getMissionManager().shouldLoadGroup(group, scene));
        scene.refreshMissionGroups();
      }
      default -> fail(mode);
    }
    assertNull(scene.getEntityById(id));
    assertEquals(0, monster.getEntityId());
    noEffects();
  }

  @ParameterizedTest
  @ValueSource(strings = {"MONSTER_DIE", "PUZZLE_FINISH"})
  void matchingGroupAndInstanceDoNotCrossTriggerEventTypes(String name) {
    var type = PropTriggerType.valueOf(name);
    player.getScene().invokePropTrigger(type, 987, 500001);
    assertEquals(type == PropTriggerType.MONSTER_DIE ? 1 : 0, deaths);
    assertEquals(type == PropTriggerType.PUZZLE_FINISH ? 1 : 0, puzzles);
  }

  @ParameterizedTest
  @ValueSource(strings = {"group", "instance"})
  void aDifferentGroupOrInstanceCannotRunEitherTrigger(String mode) {
    player
        .getScene()
        .invokePropTrigger(
            PropTriggerType.MONSTER_DIE,
            mode.equals("group") ? 986 : 987,
            mode.equals("instance") ? 500002 : 500001);
    noEffects();
  }

  @Test
  void repeatedInstantKillTargetsGrantOneDropCalculationAndOneDeath() {
    var monster = monster(player.getScene(), "Minion", true);
    int entity = monster.getEntityId();
    var action = new MazeSkillSetAttackTargetMonsterDie();
    action.onAttack(player.getCurrentLeaderAvatar(), List.of(monster, monster));
    action.onAttack(player.getCurrentLeaderAvatar(), List.of(monster));
    assertEquals(1, drops);
    assertEquals(1, deaths);
    assertEquals(0, puzzles);
    assertNull(player.getScene().getEntityById(entity));
  }

  @Test
  void anUnregisteredInstantKillTargetCannotGrantDrops() {
    var monster = monster(player.getScene(), "Minion", false);
    new MazeSkillSetAttackTargetMonsterDie()
        .onAttack(player.getCurrentLeaderAvatar(), List.of(monster));
    noEffects();
  }

  @Test
  void anInstantKillCannotReachThePlayersPreviousScene() throws Exception {
    var monster = monster(player.getScene(), "Minion", true);
    assertTrue(
        player.loadScene(
            StoryLineLifecycleTest.PLANE,
            StoryLineLifecycleTest.FLOOR,
            StoryLineLifecycleTest.ENTRY,
            new emu.lunarcore.util.Position(),
            new emu.lunarcore.util.Position(),
            false,
            4));
    assertNotSame(monster.getScene(), player.getScene());
    new MazeSkillSetAttackTargetMonsterDie()
        .onAttack(player.getCurrentLeaderAvatar(), List.of(monster));
    noEffects();
  }

  @Test
  void anInstantKillCannotReachAnotherPlayersMonster() throws Exception {
    var other = new StoryLineLifecycleTest.Director();
    assertTrue(
        other.loadScene(
            StoryLineLifecycleTest.PLANE,
            StoryLineLifecycleTest.FLOOR,
            StoryLineLifecycleTest.ENTRY,
            new emu.lunarcore.util.Position(),
            new emu.lunarcore.util.Position(),
            false));
    var monster = monster(other.getScene(), "Minion", true);
    new MazeSkillSetAttackTargetMonsterDie()
        .onAttack(player.getCurrentLeaderAvatar(), List.of(monster));
    noEffects();
    assertTrue(monster.isAlive());
  }

  @ParameterizedTest
  @ValueSource(strings = {"Elite", "BigBoss", "Unknow", "missing"})
  void unsupportedRankCannotBeInstantlyKilled(String rank) {
    var monster = monster(player.getScene(), rank.equals("missing") ? null : rank, true);
    assertDoesNotThrow(
        () ->
            new MazeSkillSetAttackTargetMonsterDie()
                .onAttack(player.getCurrentLeaderAvatar(), List.of(monster)));
    noEffects();
    assertTrue(monster.isAlive());
  }

  @ParameterizedTest
  @ValueSource(strings = {"BATTLE_END_WIN", "BATTLE_END_LOSE", "BATTLE_END_QUIT"})
  void validatedBattleVictoryReportsDeathButLossAndQuitDoNot(String result) {
    var monster = monster(player.getScene(), "Minion", true);
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
    var battle = new Battle(player, player.getCurrentLineup(), stage, false);
    battle.getNpcMonsters().add(monster);
    player.setBattle(battle);
    var service =
        new BattleService(null) {
          protected void grantDrops(Battle b) {}
        };
    assertSame(
        battle,
        service.finishBattle(
            player,
            battle.getId(),
            stage.getId(),
            BattleEndStatus.valueOf(result),
            BattleStatistics.newInstance()));
    assertEquals(result.equals("BATTLE_END_WIN") ? 1 : 0, deaths);
    assertEquals(0, puzzles);
    assertEquals(!result.equals("BATTLE_END_WIN"), monster.isAlive());
    assertNull(
        service.finishBattle(
            player,
            battle.getId(),
            stage.getId(),
            BattleEndStatus.valueOf(result),
            BattleStatistics.newInstance()));
    assertEquals(result.equals("BATTLE_END_WIN") ? 1 : 0, deaths);
  }

  @Test
  void anOldSceneEntityWithTheSameIdCannotRemoveTheCurrentMonster() throws Exception {
    var old = monster(player.getScene(), "Minion", true);
    assertTrue(
        player.loadScene(
            StoryLineLifecycleTest.PLANE,
            StoryLineLifecycleTest.FLOOR,
            StoryLineLifecycleTest.ENTRY,
            new emu.lunarcore.util.Position(),
            new emu.lunarcore.util.Position(),
            false,
            4));
    var current = monster(player.getScene(), "Minion", true);
    assertEquals(
        old.getEntityId(),
        current.getEntityId(),
        "The fixture must exercise an actual cross-scene ID collision");
    player.getScene().removeEntity(old);
    assertSame(current, player.getScene().getEntityById(current.getEntityId()));
    assertTrue(current.isAlive());
    noEffects();
  }

  @Test
  void anUnregisteredObjectCannotRemoveTheOwnedMonsterWithTheSameId() {
    var current = monster(player.getScene(), "Minion", true);
    var impostor = monster(player.getScene(), "Minion", false);
    impostor.setEntityId(current.getEntityId());
    player.getScene().removeEntity(impostor);
    assertSame(current, player.getScene().getEntityById(current.getEntityId()));
    assertTrue(current.isAlive());
    noEffects();
  }

  @Test
  void anImpostorCasterWithTheCurrentEntityIdCannotGrantInstantKillDrops() {
    var actual = player.getCurrentLeaderAvatar();
    var impostor = new emu.lunarcore.game.avatar.GameAvatar(actual.getExcel());
    impostor.setOwner(player);
    impostor.setEntityId(actual.getEntityId());
    var target = monster(player.getScene(), "Minion", true);
    new MazeSkillSetAttackTargetMonsterDie().onAttack(impostor, List.of(target));
    noEffects();
    assertTrue(target.isAlive());
  }

  @Test
  void concurrentInstantKillAttemptsGrantOnlyOneDeathAndOneDropCalculation() throws Exception {
    var target = monster(player.getScene(), "Minion", true);
    var actor = player.getCurrentLeaderAvatar();
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var tasks =
          List.<java.util.concurrent.Callable<Boolean>>of(
              () -> {
                new MazeSkillSetAttackTargetMonsterDie().onAttack(actor, List.of(target));
                return true;
              },
              () -> {
                new MazeSkillSetAttackTargetMonsterDie().onAttack(actor, List.of(target));
                return true;
              });
      for (var result : executor.invokeAll(tasks)) assertTrue(result.get());
      assertEquals(1, drops);
      assertEquals(1, deaths);
      assertEquals(0, puzzles);
    } finally {
      executor.shutdownNow();
    }
  }

  void killGoals() {
    var goals =
        "[{\"ID\":199998711,\"MainMissionID\":19999871,\"TakeType\":\"Auto\",\"FinishType\":\"KillMonster\",\"ParamInt1\":987,\"ParamInt2\":500001,\"LevelFloorID\":199931001},"
            + "{\"ID\":199998712,\"MainMissionID\":19999871,\"TakeType\":\"Auto\",\"FinishType\":\"KillMonsterList\",\"ParamType\":\"ListContain\",\"ParamInt1\":987,\"ParamIntList\":[500001],\"Progress\":2,\"LevelFloorID\":199931001},"
            + "{\"ID\":199998713,\"MainMissionID\":19999871,\"TakeType\":\"Auto\",\"FinishType\":\"KillMonsterGroupList\",\"ParamType\":\"ListContain\",\"ParamIntList\":[987],\"Progress\":2,\"LevelFloorID\":199931001},"
            + "{\"ID\":199998714,\"MainMissionID\":19999871,\"TakeType\":\"Auto\",\"FinishType\":\"KillMonster\",\"ParamInt1\":988,\"ParamInt2\":500001,\"LevelFloorID\":199931001},"
            + "{\"ID\":199998715,\"MainMissionID\":19999871,\"TakeType\":\"Auto\",\"FinishType\":\"KillMonster\",\"ParamInt1\":987,\"ParamInt2\":500001,\"LevelFloorID\":199931002}]";
    new GameplayProgressTest().mission(KILL_MAIN, "Manual", "199998714,199998715", goals);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC, player.getMissionManager().accept(KILL_MAIN));
  }

  @ParameterizedTest
  @ValueSource(strings = {"instant", "battle", "remove"})
  void actualInstantKillAndBattleVictoryCountMatchingMissionGoalsOnce(String mode) {
    killGoals();
    var target = monster(player.getScene(), "Minion", true);
    if (mode.equals("instant")) {
      new MazeSkillSetAttackTargetMonsterDie()
          .onAttack(player.getCurrentLeaderAvatar(), List.of(target, target));
      new MazeSkillSetAttackTargetMonsterDie()
          .onAttack(player.getCurrentLeaderAvatar(), List.of(target));
    } else if (mode.equals("battle")) {
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
      var battle = new Battle(player, player.getCurrentLineup(), stage, false);
      battle.getNpcMonsters().add(target);
      player.setBattle(battle);
      var service =
          new BattleService(null) {
            protected void grantDrops(Battle b) {}
          };
      assertSame(
          battle,
          service.finishBattle(
              player,
              battle.getId(),
              stage.getId(),
              BattleEndStatus.BATTLE_END_WIN,
              BattleStatistics.newInstance()));
    } else player.getScene().removeEntity(target);
    for (int id : List.of(199998711, 199998712, 199998713))
      assertEquals(
          mode.equals("remove") ? 0 : 1,
          player.getMissionManager().sub(id).getProgress(),
          "Actual death must credit the matching task once: " + id);
    for (int id : List.of(199998714, 199998715))
      assertEquals(0, player.getMissionManager().sub(id).getProgress());
  }
}
