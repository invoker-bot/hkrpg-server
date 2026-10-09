package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.junit.jupiter.api.*;

class UniverseBlessingTest {
  @AfterEach
  void reset() {
    GameplayDefinitions.blessings.clear();
    GameplayDefinitions.rooms.remove(19997001);
  }

  void buff(int id, int level) {
    GameData.getMazeBuffExcelMap()
        .add(new Gson().fromJson("{\"ID\":" + id + ",\"Lv\":" + level + "}", MazeBuffExcel.class));
    GameplayDefinitions.blessings.add(
        new GameplayDefinitions.Blessing(id, level, 100, "Common", 1, List.of()));
  }

  @Test
  void delayedSelectionFromAnOldRunCannotChooseTheSameBlessingInANewRun() {
    buff(19997102, 1);
    var p =
        new Player() {
          public void save() {}
        };
    var first = new GameplayState.RogueRun(100, 1, Map.of(1, 2));
    first.enter(1, 2);
    first.clear();
    p.getGameplay().setRogue(first);
    p.getUniverseManager().offerBlessings();
    int oldBatch = first.getSelectionId();
    first.finish(GameplayState.RogueRun.Phase.ABANDONED);
    var second = new GameplayState.RogueRun(100, 1, Map.of(1, 2));
    second.enter(1, 2);
    second.clear();
    p.getGameplay().setRogue(second);
    p.getUniverseManager().offerBlessings();
    assertEquals(
        Retcode.REQ_PARA_INVALID, p.getUniverseManager().selectBlessing(oldBatch, 19997102));
    assertTrue(second.getBlessings().isEmpty());
    assertEquals(List.of(19997102), second.getOffers());
    assertTrue(second.getSelectionId() > oldBatch);
  }

  @Test
  void acceptedBlessingIsBoundToActualBattlesAndNeverToOutsideBattles() {
    buff(19997101, 1);
    buff(19997101, 2);
    var p =
        new Player() {
          public void save() {}

          public int getEntryId() {
            return 777;
          }
        };
    var run = new GameplayState.RogueRun(100, 1, Map.of(1, 19997001));
    run.enter(1, 19997001);
    run.clear();
    p.getGameplay().setRogue(run);
    GameplayDefinitions.rooms.put(19997001, new GameplayDefinitions.Room(19997001, 1, 777, 10));
    assertEquals(List.of(19997101), p.getUniverseManager().offerBlessings());
    assertEquals(
        Retcode.REQ_PARA_INVALID,
        p.getUniverseManager().selectBlessing(run.getSelectionId() + 1, 19997101));
    assertEquals(
        Retcode.SUCC, p.getUniverseManager().selectBlessing(run.getSelectionId(), 19997101));
    assertEquals(
        Retcode.REQ_PARA_INVALID,
        p.getUniverseManager().selectBlessing(run.getSelectionId(), 19997101));
    assertEquals(Retcode.SUCC, p.getUniverseManager().enhanceBlessing(19997101));
    assertEquals(0, run.getCoins());
    var stage =
        new Gson()
            .fromJson(
                "{\"StageID\":19997199,\"StageType\":\"Event\"}",
                emu.lunarcore.data.excel.StageExcel.class);
    var battle = new Battle(p, new PlayerLineup(p, 0), stage, false);
    assertTrue(battle.hasBuff(19997101));
    assertEquals(2, battle.getBuffs().get(19997101).getLevel());
    run.finish(GameplayState.RogueRun.Phase.ABANDONED);
    assertFalse(new Battle(p, new PlayerLineup(p, 0), stage, false).hasBuff(19997101));
  }

  @Test
  void roomAssignmentOnlyLoadsTheConfiguredContentGroups() {
    var p =
        new Player() {
          public void save() {}
        };
    p.getGameplay().setRogue(new GameplayState.RogueRun(100, 1, Map.of(1, 19997001)));
    p.getGameplay().getRogue().enter(1, 19997001);
    GameplayDefinitions.rooms.put(
        19997001, new GameplayDefinitions.Room(19997001, 1, 777, 10, Map.of(10, 11, 20, 21)));
    assertEquals(Set.of(10, 20), UniverseEntityLoader.roomGroups(p));
  }

  @Test
  void combatInAContentGroupCannotClearTheRoomWhileAnotherGroupHasLivingMonsters() {
    var gson = new Gson();
    var plane = gson.fromJson("{\"PlaneID\":19994}", emu.lunarcore.data.excel.MazePlaneExcel.class);
    var p =
        new Player() {
          final PlayerLineup lineup = new PlayerLineup(this, 0);
          emu.lunarcore.game.scene.Scene scene;

          public void save() {}

          public int getEntryId() {
            return 777;
          }

          public PlayerLineup getCurrentLineup() {
            return lineup;
          }

          public emu.lunarcore.game.scene.Scene getScene() {
            return scene == null
                ? (scene = new emu.lunarcore.game.scene.Scene(this, plane, 19994001))
                : scene;
          }
        };
    var run = new GameplayState.RogueRun(100, 1, Map.of(1, 19997001));
    run.enter(1, 19997001);
    p.getGameplay().setRogue(run);
    // Group 10 is a portal, combat groups are 20 and 30 in GroupWithContent.
    GameplayDefinitions.rooms.put(
        19997001,
        new GameplayDefinitions.Room(19997001, 1, 777, 10, Map.of(10, 0, 20, 21, 30, 31)));
    var group = new emu.lunarcore.data.config.GroupInfo();
    group.setId(20);
    var monster =
        new emu.lunarcore.game.scene.entity.EntityMonster(
            p.getScene(),
            new emu.lunarcore.data.excel.NpcMonsterExcel(),
            group,
            gson.fromJson(
                "{\"ID\":1,\"Pos\":{},\"Rot\":{}}", emu.lunarcore.data.config.MonsterInfo.class));
    var battle =
        new Battle(
            p,
            p.getCurrentLineup(),
            gson.fromJson("{\"StageID\":19994199}", emu.lunarcore.data.excel.StageExcel.class),
            false);
    battle.getNpcMonsters().add(monster);
    battle.setResult(emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN);
    monster.setGroupId(30);
    monster.setEntityId(2);
    p.getScene().getEntities().put(2, monster);
    p.getUniverseManager().onBattleFinish(battle);
    assertFalse(run.getCleared().contains(1));
    p.getScene().getEntities().clear();
    p.getUniverseManager().onBattleFinish(battle);
    assertTrue(run.getCleared().contains(1));
    p.getUniverseManager().onBattleFinish(battle);
    assertEquals(Set.of(1), run.getCleared());
  }
}
