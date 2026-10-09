package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InventoryGrantDeltaTest {
  @Test
  void rogueTalentCurrencyGrantsARealBalanceAndRejectsOverflow() throws Exception {
    var previous = GameData.getItemExcelMap().get(32);
    try {
      var excel = new Gson().fromJson("{\"ID\":32,\"ItemMainType\":\"Virtual\"}", ItemExcel.class);
      GameData.getItemExcelMap().add(excel);
      var p = new Resident();
      var reward = new ItemParamMap();
      reward.add(32, 80);
      var claim = p.getRewardLedger().claimItems("talent-currency", reward);
      assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, claim.retcode());
      assertEquals(80, p.getTalentPoints());
      assertTrue(p.inventory.hasItem(32, 80));
      assertEquals(80, claim.items().getFirst().getCount());
      assertEquals(
          emu.lunarcore.server.packet.Retcode.QUEST_REWARD_ALREADY_TAKEN,
          p.getRewardLedger().claimItems("talent-currency", reward).retcode());
      var points = Player.class.getDeclaredField("talentPoints");
      points.setAccessible(true);
      points.setInt(p, Integer.MAX_VALUE - 1);
      assertFalse(p.inventory.addItem(32, 2));
      assertEquals(Integer.MAX_VALUE - 1, p.getTalentPoints());
    } finally {
      if (previous != null) GameData.getItemExcelMap().add(previous);
      else GameData.getItemExcelMap().keySet().remove(32);
    }
  }

  @AfterEach
  void reset() {
    GameplayDefinitions.subs.remove(19995301);
    GameplayDefinitions.subs.remove(19995302);
  }

  static class Resident extends Player {
    final emu.lunarcore.game.player.PlayerUnlockData unlocks =
        new emu.lunarcore.game.player.PlayerUnlockData(this) {
          public void save() {}
        };

    public emu.lunarcore.game.player.PlayerUnlockData getUnlocks() {
      return unlocks;
    }

    final Inventory inventory = new Inventory(this);

    public Inventory getInventory() {
      return inventory;
    }

    public void save() {}

    public void sendPacket(emu.lunarcore.server.packet.BasePacket packet) {}
  }

  Resident stocked(int id, int stock, int limit) {
    var excel =
        new Gson()
            .fromJson(
                "{\"ID\":" + id + ",\"ItemMainType\":\"Material\",\"PileLimit\":" + limit + "}",
                ItemExcel.class);
    GameData.getItemExcelMap().add(excel);
    var p = new Resident();
    assertTrue(
        p.inventory.addItem(
            new GameItem(excel, stock) {
              public void save() {}
            }));
    return p;
  }

  @Test
  void aStackedRewardReportsOnlyTheGrantAndClosesTheReceipt() {
    var p = stocked(199953001, 5, 99);
    var reward = new ItemParamMap();
    reward.add(199953001, 3);
    var claim = p.getRewardLedger().claimItems("stacked", reward);
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, claim.retcode());
    assertEquals(3, claim.items().getFirst().getCount());
    assertEquals(8, p.inventory.getMaterialByItemId(199953001).getCount());
    assertTrue(p.getRewardLedger().granted("stacked"));
  }

  @Test
  void aPileLimitReportsTheActualPartialGrantAndKeepsReconciliationReserved() {
    var p = stocked(199953002, 9, 10);
    var reward = new ItemParamMap();
    reward.add(199953002, 3);
    var claim = p.getRewardLedger().claimItems("limited", reward);
    assertEquals(1, claim.items().getFirst().getCount());
    assertEquals(10, p.inventory.getMaterialByItemId(199953002).getCount());
    assertFalse(p.getRewardLedger().granted("limited"));
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY,
        p.getRewardLedger().claimItems("limited", reward).retcode());
  }

  @Test
  void onlyActualNewAcquisitionsAdvanceExistingObjectivesAndAFullStackCannotTriggerANewObjective() {
    var p = stocked(199953003, 1, 3);
    p.getGameplay().getMainMissions().put(199953, new QuestState(199953));
    var gson = new Gson();
    var first =
        gson.fromJson(
            "{\"ID\":19995301,\"MainMissionID\":199953,\"TakeType\":\"Auto\",\"FinishType\":\"GetItem\",\"ParamType\":\"Equal\",\"ParamInt1\":199953003}",
            MissionInfo.SubMissionInfo.class);
    var next =
        gson.fromJson(
            "{\"ID\":19995302,\"MainMissionID\":199953,\"TakeType\":\"MultiSequence\",\"TakeParamIntList\":[19995301],\"FinishType\":\"GetItem\",\"ParamType\":\"Equal\",\"ParamInt1\":199953003}",
            MissionInfo.SubMissionInfo.class);
    GameplayDefinitions.subs.put(first.getId(), first);
    GameplayDefinitions.subs.put(next.getId(), next);
    p.getMissionManager().refresh();
    assertEquals(0, p.getMissionManager().sub(first.getId()).getProgress());
    var reward = new ItemParamMap();
    reward.add(199953003, 2);
    p.inventory.grantItems(reward, false);
    assertEquals(1, p.getMissionManager().sub(first.getId()).getProgress());
    assertEquals(0, p.getMissionManager().sub(next.getId()).getProgress());
    p.inventory.grantItems(reward, false);
    assertEquals(0, p.getMissionManager().sub(next.getId()).getProgress());
  }

  @Test
  void itemListObjectivesCountDistinctResourceIdsAcrossSeparateGrants() {
    var p = stocked(199953004, 1, 99);
    p.getGameplay().getMainMissions().put(199953, new QuestState(199953));
    var sub =
        new Gson()
            .fromJson(
                "{\"ID\":19995301,\"MainMissionID\":199953,\"TakeType\":\"Auto\",\"FinishType\":\"GetItemWithList\",\"ParamType\":\"ListContain\",\"ParamIntList\":[199953004,199953005],\"Progress\":2}",
                MissionInfo.SubMissionInfo.class);
    GameplayDefinitions.subs.put(sub.getId(), sub);
    p.getMissionManager().refresh();
    p.getMissionManager().onItemsAcquired(java.util.Map.of(199953004, 20));
    p.getMissionManager().onItemsAcquired(java.util.Map.of(199953004, 30));
    assertEquals(1, p.getMissionManager().sub(sub.getId()).getProgress());
    p.getMissionManager().onItemsAcquired(java.util.Map.of(199953005, 0, 199953006, 20));
    assertEquals(1, p.getMissionManager().sub(sub.getId()).getProgress());
    p.getMissionManager().onItemsAcquired(java.util.Map.of(199953005, 1));
    assertEquals(QuestState.Phase.FINISHED, p.getMissionManager().sub(sub.getId()).getPhase());
  }

  @Test
  void foodUsesAStoredConsumableStackAndNeverUnlocksAPet() {
    var excel =
        new Gson()
            .fromJson(
                "{\"ID\":199953006,\"ItemMainType\":\"Usable\",\"ItemSubType\":\"Food\",\"PileLimit\":99}",
                ItemExcel.class);
    GameData.getItemExcelMap().add(excel);
    var p = new Resident();
    assertTrue(
        p.inventory.addItem(
            new GameItem(excel, 5) {
              public void save() {}
            }));
    assertNotNull(p.inventory.getMaterialByItemId(excel.getId()));
    var reward = new ItemParamMap();
    reward.add(excel.getId(), 3);
    var received = p.inventory.grantItems(reward, false);
    assertTrue(received.complete());
    assertEquals(3, received.items().getFirst().getCount());
    assertEquals(8, p.inventory.getMaterialByItemId(excel.getId()).getCount());
    assertFalse(p.getUnlocks().getPets().contains(excel.getId()));
  }
}
