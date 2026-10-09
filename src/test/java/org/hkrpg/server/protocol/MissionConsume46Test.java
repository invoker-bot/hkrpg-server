package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class MissionConsume46Test {
  static final int MAIN = 199955, MISSION = 1999551, ITEM = 19995500;

  static class Resident extends Player {
    final Map<Integer, GameItem> stock = new HashMap<>();
    int removed;
    final Inventory inventory =
        new Inventory(this) {
          public GameItem getMaterialByItemId(int id) {
            return stock.get(id);
          }

          public boolean removeItem(GameItem item, int count) {
            if (item.getCount() < count) return false;
            item.setCount(item.getCount() - count);
            removed += count;
            return true;
          }
        };

    public Inventory getInventory() {
      return inventory;
    }

    public int getFloorId() {
      return 19995501;
    }

    public void save() {}
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final Resident resident = new Resident();

    public Player getPlayer() {
      return resident;
    }
  }

  @BeforeEach
  void setup() {
    var sub =
        new Gson()
            .fromJson(
                """
{"ID":1999551,"MainMissionID":199955,"TakeType":"Auto","FinishType":"ConsumeMissionItem",
 "ParamType":"Equal","LevelFloorID":19995501,"ParamItemList":[{"ItemID":19995500,"ItemNum":2}]}
""",
                MissionInfo.SubMissionInfo.class);
    GameplayDefinitions.subs.put(MISSION, sub);
    GameData.getItemExcelMap()
        .add(
            new Gson()
                .fromJson(
                    "{\"ID\":19995500,\"ItemMainType\":\"Material\",\"PileLimit\":99}",
                    ItemExcel.class));
  }

  @AfterEach
  void reset() {
    GameplayDefinitions.subs.remove(MISSION);
  }

  Session session(int count) {
    var s = new Session();
    s.resident.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    s.resident.getGameplay().getSubMissions().put(MISSION, new QuestState(MISSION));
    var item = new GameItem(ITEM, count);
    item.setOwner(s.resident);
    s.resident.stock.put(ITEM, item);
    return s;
  }

  void submit(Session s) throws Exception {
    var request = Gameplay46.builder(s, "FinishCosumeItemMissionCsReq");
    Gameplay46.set(request, "sub_mission_id", MISSION);
    assertTrue(
        VersionedPacketHandler.handle(
            s,
            s.getProtocol().clientCommand("FinishCosumeItemMissionCsReq"),
            request.build().toByteArray()));
  }

  @Test
  void nativeSubmissionDebitsConfiguredCostOnceAndCannotCompleteWithInsufficientItems()
      throws Exception {
    var s = session(1);
    submit(s);
    assertNotEquals(0, InitialData46Test.field(s.response, "retcode"));
    assertEquals(0, s.resident.removed);
    assertEquals(QuestState.Phase.DOING, s.resident.getMissionManager().sub(MISSION).getPhase());
    s.resident.stock.get(ITEM).setCount(5);
    submit(s);
    assertEquals(0, InitialData46Test.field(s.response, "retcode"));
    assertEquals(MISSION, InitialData46Test.field(s.response, "sub_mission_id"));
    assertEquals(2, s.resident.removed);
    assertEquals(QuestState.Phase.FINISHED, s.resident.getMissionManager().sub(MISSION).getPhase());
    submit(s);
    assertNotEquals(0, InitialData46Test.field(s.response, "retcode"));
    assertEquals(2, s.resident.removed);
  }

  @Test
  void forgedReportedCostCancelledMissionAndUnknownActionsCannotConsumeAnything() throws Exception {
    var s = session(5);
    var row = Gameplay46.builder(s, "Item");
    Gameplay46.set(row, "item_id", ITEM);
    Gameplay46.set(row, "num", 1);
    var list = Gameplay46.builder(s, "ItemList");
    Gameplay46.add(list, "item_list", row.build());
    var request = Gameplay46.builder(s, "FinishCosumeItemMissionCsReq");
    Gameplay46.set(request, "sub_mission_id", MISSION);
    Gameplay46.set(request, "item_list", list.build());
    assertTrue(VersionedPacketHandler.handle(s, 1261, request.build().toByteArray()));
    assertNotEquals(0, InitialData46Test.field(s.response, "retcode"));
    assertEquals(0, s.resident.removed);
    s.resident.getGameplay().getCancelledMissions().add(MAIN);
    submit(s);
    assertNotEquals(0, InitialData46Test.field(s.response, "retcode"));
    assertEquals(0, s.resident.removed);
    s.resident.getGameplay().getCancelledMissions().clear();
    GameplayDefinitions.subs
        .get(MISSION)
        .setFinishActionList(
            new Gson()
                .fromJson(
                    "[{\"FinishActionType\":\"Unconfirmed\"}]", MissionInfo.FinishAction[].class));
    submit(s);
    assertNotEquals(0, InitialData46Test.field(s.response, "retcode"));
    assertEquals(0, s.resident.removed);
    assertTrue(s.resident.getGameplay().getMissionPayments().isEmpty());
  }

  @Test
  void interruptedDebitNeverRetriesAutomatically() throws Exception {
    var s = session(5);
    var payment = new GameplayState.ActionCursor();
    payment.reserve();
    s.resident.getGameplay().getMissionPayments().put(MISSION, payment);
    submit(s);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY.getVal(),
        InitialData46Test.field(s.response, "retcode"));
    assertEquals(0, s.resident.removed);
    assertEquals(QuestState.Phase.DOING, s.resident.getMissionManager().sub(MISSION).getPhase());
  }
  @Test void anEmptyStoredCursorIsNotProofThatTheItemCostWasPaid() throws Exception {
    var s=session(5);
    s.resident.getGameplay().getMissionPayments().put(MISSION,new GameplayState.ActionCursor());
    submit(s);
    assertNotEquals(0,InitialData46Test.field(s.response,"retcode"));
    assertEquals(0,s.resident.removed);
    assertEquals(QuestState.Phase.DOING,s.resident.getMissionManager().sub(MISSION).getPhase());
  }
}
