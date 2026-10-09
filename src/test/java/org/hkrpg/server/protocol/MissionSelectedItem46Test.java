package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class MissionSelectedItem46Test {
  static final int MAIN = 199954, SUB = 1999541, A = 19995401, B = 19995402;
  final Gson gson = new Gson();

  static class Resident extends MissionConsume46Test.Resident {
    int plane = 199954;
    boolean interrupt;
    int saves;

    public int getPlaneId() {
      return plane;
    }

    public String getName() {
      return "Selected item test";
    }

    public void save() {
      saves++;
      if (interrupt && getGameplay().getMissionPayments().containsKey(SUB))
        throw new IllegalStateException("simulated interrupted payment save");
    }
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final Resident resident = new Resident();

    public Player getPlayer() {
      return resident;
    }
  }

  @BeforeEach
  void fixture() {
    var sub =
        gson.fromJson(
            """
            {"ID":1999541,"MainMissionID":199954,"TakeType":"Auto",
             "FinishType":"UseSelectedItem","ParamType":"ListContainList","ParamInt1":1,
             "LevelPlaneID":199954,"LevelFloorID":19995501,
             "ParamItemList":[{"ItemID":19995401,"ItemNum":1},{"ItemID":19995402,"ItemNum":1}],
             "FinishActionList":[{"FinishActionType":"SetCustomValue",
                                  "FinishActionParaString":["chosen","7"]}]}
            """,
            MissionInfo.SubMissionInfo.class);
    GameplayDefinitions.subs.put(SUB, sub);
    for (int id : List.of(A, B))
      GameData.getItemExcelMap()
          .add(
              gson.fromJson(
                  "{\"ID\":"
                      + id
                      + ",\"ItemMainType\":\"Usable\",\"ItemSubType\":\"Food\",\"PileLimit\":99}",
                  ItemExcel.class));
  }

  @AfterEach
  void clean() {
    GameplayDefinitions.subs.remove(SUB);
    GameData.getItemExcelMap().keySet().removeAll(List.of(A, B));
  }

  Session session(int count) {
    var s = new Session();
    s.resident.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    s.resident.getGameplay().getSubMissions().put(SUB, new QuestState(SUB));
    for (int id : List.of(A, B)) {
      var item = new GameItem(id, count);
      item.setOwner(s.resident);
      s.resident.stock.put(id, item);
    }
    return s;
  }

  byte[] request(Session s, Map<Integer, Integer> selected) {
    var b = Gameplay46.builder(s, "FinishCosumeItemMissionCsReq");
    Gameplay46.set(b, "sub_mission_id", SUB);
    if (selected != null) {
      var list = Gameplay46.builder(s, "ItemList");
      selected.forEach(
          (id, count) -> {
            var row = Gameplay46.builder(s, "Item");
            Gameplay46.set(row, "item_id", id);
            Gameplay46.set(row, "num", count);
            Gameplay46.add(list, "item_list", row.build());
          });
      Gameplay46.set(b, "item_list", list.build());
    }
    return b.build().toByteArray();
  }

  int submit(Session s, Map<Integer, Integer> selected) throws Exception {
    assertTrue(VersionedPacketHandler.handle(s, 1261, request(s, selected)));
    assertEquals("FinishCosumeItemMissionScRsp", s.responseName);
    assertEquals(SUB, InitialData46Test.field(s.response, "sub_mission_id"));
    return (int) InitialData46Test.field(s.response, "retcode");
  }

  @Test
  void aNativeChoiceConsumesOnlyTheSelectedStackAndRunsItsStoryActionOnce() throws Exception {
    var s = session(3);
    assertEquals(0, submit(s, Map.of(B, 1)));
    assertEquals(3, s.resident.stock.get(A).getCount());
    assertEquals(2, s.resident.stock.get(B).getCount());
    assertEquals(1, s.resident.removed);
    assertEquals(7, s.resident.getGameplay().getStoryValues().get("mission:" + MAIN + ":chosen"));
    assertEquals(QuestState.Phase.FINISHED, s.resident.getMissionManager().sub(SUB).getPhase());
    assertNotEquals(0, submit(s, Map.of(A, 1)));
    assertEquals(1, s.resident.removed);
    var plans =
        gson.toJsonTree(s.resident.getGameplay())
            .getAsJsonObject()
            .getAsJsonObject("missionPaymentPlans");
    assertNotNull(plans, "the paid choice must be persisted separately from the completion cursor");
    assertEquals(
        1,
        plans
            .getAsJsonObject(Integer.toString(SUB))
            .getAsJsonObject("costs")
            .get(Integer.toString(B))
            .getAsInt());
  }

  @Test
  void noSelectionForgedItemsExtraSelectionsAndWrongQuantitiesNeverDebit() throws Exception {
    for (var choice :
        Arrays.asList(
            null,
            Map.<Integer, Integer>of(),
            Map.of(A, 1, B, 1),
            Map.of(A, 2),
            Map.of(A, 0),
            Map.of(A + 100, 1))) {
      var s = session(3);
      assertNotEquals(0, submit(s, choice));
      assertEquals(0, s.resident.removed);
      assertTrue(s.resident.getGameplay().getMissionPayments().isEmpty());
      assertEquals(QuestState.Phase.DOING, s.resident.getMissionManager().sub(SUB).getPhase());
    }
  }

  @Test
  void configuredCreditChoiceDebitsTheActualBalanceAndDoesNotConsumeOtherAlternatives()
      throws Exception {
    var old = GameData.getItemExcelMap().get(2);
    try {
      GameData.getItemExcelMap()
          .add(
              gson.fromJson(
                  "{\"ID\":2,\"ItemMainType\":\"Virtual\",\"PileLimit\":2147483647}",
                  ItemExcel.class));
      GameplayDefinitions.subs
          .get(SUB)
          .setParamItemList(
              List.of(
                  new MissionInfo.MissionItemCost(2, 1), new MissionInfo.MissionItemCost(A, 1)));
      var s = session(3);
      s.resident.addSCoin(5);
      assertEquals(0, submit(s, Map.of(2, 1)));
      assertEquals(4, s.resident.getScoin());
      assertEquals(0, s.resident.removed);
      assertEquals(3, s.resident.stock.get(A).getCount());
      assertNotEquals(0, submit(s, Map.of(2, 1)));
      assertEquals(4, s.resident.getScoin());
      var empty = session(3);
      assertEquals(Retcode.ITEM_NOT_ENOUGH.getVal(), submit(empty, Map.of(2, 1)));
      assertEquals(0, empty.resident.getScoin());
    } finally {
      if (old == null) GameData.getItemExcelMap().keySet().remove(2);
      else GameData.getItemExcelMap().add(old);
    }
  }

  @Test
  void insufficientOrForeignStockWrongPlaneAndCancelledMissionsCannotBeSubmitted()
      throws Exception {
    var s = session(0);
    assertEquals(Retcode.ITEM_NOT_ENOUGH.getVal(), submit(s, Map.of(A, 1)));
    s = session(3);
    var owner = GameItem.class.getDeclaredField("ownerUid");
    owner.setAccessible(true);
    owner.setInt(s.resident.stock.get(A), 42);
    assertEquals(Retcode.ITEM_NOT_ENOUGH.getVal(), submit(s, Map.of(A, 1)));
    assertEquals(0, s.resident.removed);
    s = session(3);
    s.resident.plane++;
    assertEquals(Retcode.MISSION_SCENE_NOT_MATCH.getVal(), submit(s, Map.of(A, 1)));
    assertEquals(0, s.resident.removed);
    s = session(3);
    s.resident.getGameplay().getCancelledSubs().add(SUB);
    assertEquals(Retcode.MISSION_NOT_DOING.getVal(), submit(s, Map.of(A, 1)));
    assertEquals(0, s.resident.removed);
  }

  @Test
  void unsupportedActionsAndAmbiguousSelectionDefinitionsAreRejectedBeforePayment()
      throws Exception {
    var c = GameplayDefinitions.subs.get(SUB);
    c.setFinishActionList(
        gson.fromJson(
            "[{\"FinishActionType\":\"Unconfirmed\"}]", MissionInfo.FinishAction[].class));
    var s = session(3);
    assertNotEquals(0, submit(s, Map.of(A, 1)));
    assertEquals(0, s.resident.removed);
    c.setFinishActionList(new MissionInfo.FinishAction[0]);
    c.setParamInt1(2);
    assertNotEquals(0, submit(s, Map.of(A, 1)));
    c.setParamInt1(1);
    c.setParamItemList(
        List.of(new MissionInfo.MissionItemCost(A, 1), new MissionInfo.MissionItemCost(A, 1)));
    assertNotEquals(0, submit(s, Map.of(A, 1)));
    assertTrue(s.resident.getGameplay().getMissionPayments().isEmpty());
    assertEquals(0, s.resident.removed);
  }

  @Test
  void concurrentRequestsForOneChoiceDebitExactlyOnce() throws Exception {
    var s = session(10);
    try (var pool = Executors.newFixedThreadPool(4)) {
      var work = new ArrayList<Future<Retcode>>();
      for (int i = 0; i < 12; i++)
        work.add(pool.submit(() -> s.resident.getMissionManager().submitItems(SUB, Map.of(A, 1))));
      int successful = 0;
      for (var result : work) if (result.get() == Retcode.SUCC) successful++;
      assertEquals(1, successful);
    }
    assertEquals(1, s.resident.removed);
    assertEquals(9, s.resident.stock.get(A).getCount());
  }

  @Test
  void anInterruptedReservationDoesNotDebitOrPermitAnAutomaticRetry() throws Exception {
    var s = session(3);
    s.resident.interrupt = true;
    assertThrows(IllegalStateException.class, () -> submit(s, Map.of(A, 1)));
    s.resident.interrupt = false;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), submit(s, Map.of(A, 1)));
    assertEquals(0, s.resident.removed);
    assertEquals(3, s.resident.stock.get(A).getCount());
  }

  @Test
  void anUnfinishedStoryActionCannotChangeThePaidChoiceOrConsumeItAgain() throws Exception {
    GameplayDefinitions.subs
        .get(SUB)
        .setFinishActionList(
            gson.fromJson(
                "[{\"FinishActionType\":\"delMissionItem\",\"FinishActionPara\":[" + B + ",10]}]",
                MissionInfo.FinishAction[].class));
    var s = session(3);
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), submit(s, Map.of(A, 1)));
    assertEquals(2, s.resident.stock.get(A).getCount());
    assertEquals(3, s.resident.stock.get(B).getCount());
    assertEquals(QuestState.Phase.DOING, s.resident.getMissionManager().sub(SUB).getPhase());
    assertEquals(
        Map.of(A, 1), s.resident.getGameplay().getMissionPaymentPlans().get(SUB).getCosts());
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), submit(s, Map.of(B, 1)));
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), submit(s, Map.of(A, 1)));
    assertEquals(1, s.resident.removed);
  }

  @Test
  void theFrozenReservationSurvivesRealMorphiaStorageAndOldDocumentsHaveNoInventedChoice()
      throws Exception {
    var s = session(3);
    s.resident.interrupt = true;
    assertThrows(IllegalStateException.class, () -> submit(s, Map.of(B, 1)));
    s.resident.interrupt = false;
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "missionselection");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var persisted = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(persisted, 19995407);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(persisted, s.resident.getGameplay());
      store.save(persisted);
      var restored = store.find(Player.class).first();
      gameplay.set(s.resident, restored.getGameplay());
      assertEquals(
          Map.of(B, 1), s.resident.getGameplay().getMissionPaymentPlans().get(SUB).getCosts());
      assertEquals(
          "UseSelectedItem",
          s.resident.getGameplay().getMissionPaymentPlans().get(SUB).getFinishType());
      assertEquals(Retcode.SYSTEM_BUSY.getVal(), submit(s, Map.of(B, 1)));
      assertEquals(0, s.resident.removed);
      assertTrue(new Player().getGameplay().getMissionPaymentPlans().isEmpty());
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void aLegacyPaidCursorWithoutTheActualChoiceCannotManufactureSelectedMissionCompletion()
      throws Exception {
    var s = session(3);
    var payment = new GameplayState.ActionCursor();
    payment.reserve();
    payment.complete();
    s.resident.getGameplay().getMissionPayments().put(SUB, payment);
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), submit(s, Map.of(A, 1)));
    assertEquals(0, s.resident.removed);
    assertTrue(s.resident.getGameplay().getMissionPaymentPlans().isEmpty());
    assertEquals(QuestState.Phase.DOING, s.resident.getMissionManager().sub(SUB).getPhase());
  }

  @Test
  void nestedAliasesUnknownFieldsAndDuplicateItemFieldsCannotHideAnInvalidChoice()
      throws Exception {
    for (int kind = 0; kind < 3; kind++) {
      var s = session(3);
      var output = new java.io.ByteArrayOutputStream();
      var wire = com.google.protobuf.CodedOutputStream.newInstance(output);
      wire.writeUInt32(15, A);
      if (kind == 0) wire.writeUInt64(6, (1L << 32) + 1);
      else {
        wire.writeUInt32(6, 1);
        if (kind == 1) wire.writeUInt32(6, 1);
        else wire.writeUInt32(100, 1);
      }
      wire.flush();
      var list = new java.io.ByteArrayOutputStream();
      var listWire = com.google.protobuf.CodedOutputStream.newInstance(list);
      listWire.writeByteArray(2, output.toByteArray());
      listWire.flush();
      var request = new java.io.ByteArrayOutputStream();
      var requestWire = com.google.protobuf.CodedOutputStream.newInstance(request);
      requestWire.writeUInt32(2, SUB);
      requestWire.writeByteArray(4, list.toByteArray());
      requestWire.flush();
      assertTrue(VersionedPacketHandler.handle(s, 1261, request.toByteArray()));
      assertEquals(
          Retcode.REQ_PARA_INVALID.getVal(), InitialData46Test.field(s.response, "retcode"));
      assertEquals(0, s.resident.removed);
    }
  }

  @Test
  void unsignedVarintAliasesAndDuplicateRequestFieldsCannotCompleteAnOrdinaryConsumeMission()
      throws Exception {
    var c = GameplayDefinitions.subs.get(SUB);
    c.setFinishType("ConsumeMissionItem");
    c.setParamType("Equal");
    c.setParamInt1(0);
    c.setParamItemList(List.of(new MissionInfo.MissionItemCost(A, 1)));
    for (boolean alias : List.of(true, false)) {
      var s = session(3);
      var output = new java.io.ByteArrayOutputStream();
      var wire = com.google.protobuf.CodedOutputStream.newInstance(output);
      if (alias) wire.writeUInt64(2, (1L << 32) + SUB);
      else {
        wire.writeUInt32(2, SUB);
        wire.writeUInt32(2, SUB);
      }
      wire.flush();
      assertTrue(VersionedPacketHandler.handle(s, 1261, output.toByteArray()));
      assertEquals(
          Retcode.REQ_PARA_INVALID.getVal(), InitialData46Test.field(s.response, "retcode"));
      assertEquals(0, s.resident.removed);
      assertEquals(QuestState.Phase.DOING, s.resident.getMissionManager().sub(SUB).getPhase());
    }
  }
}
