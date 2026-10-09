package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class HeartDialMissing46Test {
  static final int SCRIPT = 20004, ITEM = 140469, CONDITION = 10001044;
  final HeartDial46Test fixture = new HeartDial46Test();
  @TempDir Path root;
  ItemExcel previousItem;

  @BeforeEach
  void setup() throws Exception {
    fixture.setup();
    fixture.player.getGameplay().getSubMissions().put(fixture.ALL, new QuestState(fixture.ALL));
    fixture.player.getGameplay().getSubMissions().get(fixture.ALL).advance(1, 1, 1);
    HeartDialDefinitions.scripts.clear();
    HeartDialDefinitions.dialogues.clear();
    HeartDialDefinitions.scripts.put(
        SCRIPT,
        fixture.gson.fromJson(
            """
{"ScriptID":20004,"DefaultEmoType":"Happy","StepList":["Missing","Normal"],
 "MissingEmoList":["Happy"],"MissingDialogueID":2000405,
 "TotalEmoInfoList":[{"POAIHJCEKCF":2000401},{"BNFEAFPEOIE":"Anger","POAIHJCEKCF":2000402},
  {"BNFEAFPEOIE":"Sad","POAIHJCEKCF":2000403},{"BNFEAFPEOIE":"Happy","POAIHJCEKCF":2000404}]}
""",
            HeartDialDefinitions.Script.class));
    for (int id = 2000401; id <= 2000405; id++)
      HeartDialDefinitions.dialogues.put(id, new HeartDialDefinitions.Dialogue(id, List.of()));
    var npc = new HeartDialDefinitions.Npc(fixture.FLOOR, 47, 400005, List.of(SCRIPT), SCRIPT);
    HeartDialDefinitions.npcs.put(npc.key(), npc);
    var excel = Files.createDirectories(root.resolve("ExcelOutput"));
    Files.writeString(
        excel.resolve("HeartDialScript.json"),
        fixture.gson.toJson(HeartDialDefinitions.scripts.values()));
    Files.writeString(
        excel.resolve("HeartDialDialogue.json"),
        fixture.gson.toJson(HeartDialDefinitions.dialogues.values()));
    Files.writeString(
        excel.resolve("HeartDialNpc.json"),
        fixture.gson.toJson(HeartDialDefinitions.npcs.values()));
    var constants = new JsonArray();
    HeartDialDefinitions.constants.forEach(
        (key, value) -> {
          var row = new JsonObject();
          row.addProperty("ConstValueName", key);
          row.add("Value", value);
          constants.add(row);
        });
    Files.writeString(excel.resolve("HeartDialConstValue.json"), constants.toString());
    Files.writeString(
        excel.resolve("HeartDialScriptCondition.json"),
        """
        [{"ScriptID":20004,"MissingConditionID":10001044,"FullConditionID":10001001,
          "LockConditionID":10001001,"UnLockConditionID":10001001,"ControlConditionID":10001001}]
        """);
    Files.writeString(
        excel.resolve("HeartDialCondition.json"),
        """
        [{"ID":10001001,"FinishType":"AutoFinish"},
         {"ID":10001044,"FinishType":"ConsumeSingleItem","ParamUint1":140469}]
        """);
    HeartDialDefinitions.load(root);
    previousItem = GameData.getItemExcelMap().get(ITEM);
    GameData.getItemExcelMap()
        .add(
            fixture.gson.fromJson(
                "{\"ID\":140469,\"ItemMainType\":\"Material\",\"ItemSubType\":\"Material\",\"PileLimit\":99}",
                ItemExcel.class));
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
    if (previousItem == null) GameData.getItemExcelMap().keySet().remove(ITEM);
    else GameData.getItemExcelMap().add(previousItem);
  }

  int step() throws Exception {
    var response = fixture.call(6334, "GetHeartDialInfoScRsp", new byte[0]);
    assertEquals(0, Gameplay46.number(response, "retcode"));
    var scripts = (List<?>) Gameplay46.value(response, "script_info_list");
    assertEquals(1, scripts.size());
    var script = (DynamicMessage) scripts.getFirst();
    assertEquals(SCRIPT, Gameplay46.number(script, "script_id"));
    return ((Descriptors.EnumValueDescriptor) Gameplay46.value(script, "step")).getNumber();
  }

  int submit(byte[] bytes) throws Exception {
    return Gameplay46.number(fixture.call(6360, "SubmitEmotionItemScRsp", bytes), "retcode");
  }

  byte[] payment(int id, int count) throws Exception {
    byte[] item = fixture.wire(15, id, 6, count);
    var listBytes = new ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(listBytes);
    out.writeByteArray(2, item);
    out.flush();
    var requestBytes = new ByteArrayOutputStream();
    out = CodedOutputStream.newInstance(requestBytes);
    out.writeUInt32(15, SCRIPT);
    out.writeByteArray(13, listBytes.toByteArray());
    out.flush();
    return requestBytes.toByteArray();
  }

  GameItem stock(int count) {
    var item =
        new GameItem(ITEM, count) {
          public void save() {}
        };
    assertTrue(fixture.player.getInventory().addItem(item));
    return item;
  }

  @Test
  void queryShowsTheAuthoredMissingStepAndDoesNotConsumeTheRequiredItem() throws Exception {
    var item = stock(3);
    assertEquals(0, step());
    assertEquals(3, item.getCount());
    assertTrue(fixture.player.getGameplay().getHeartDialReports().isEmpty());
    assertNotEquals(
        0,
        Gameplay46.number(
            fixture.call(6305, "ChangeScriptEmotionScRsp", fixture.wire(1, SCRIPT, 2, 999, 12, 2)),
            "retcode"));
  }

  @Test
  void actualMissingDialogueCanBeReportedWithoutRepairingTheScript() throws Exception {
    var response =
        fixture.call(
            6358,
            "FinishEmotionDialoguePerformanceScRsp",
            fixture.wire(3, SCRIPT, 4, 2000405, 10, 999));
    assertEquals(0, Gameplay46.number(response, "retcode"));
    assertTrue(fixture.player.getGameplay().getHeartDialReports().get(2000405).isApplied());
    assertEquals(0, step());
    assertNotEquals(
        0,
        Gameplay46.number(
            fixture.call(
                6358,
                "FinishEmotionDialoguePerformanceScRsp",
                fixture.wire(3, SCRIPT, 4, 2000404, 10, 999)),
            "retcode"));
  }

  @Test
  void nativeItemSubmissionConsumesExactlyOneAndAdvancesTheAuthoredStepOnce() throws Exception {
    var item = stock(3);
    fixture.saved.clear();
    assertEquals(0, submit(payment(ITEM, 1)));
    assertFalse(fixture.saved.getFirst().getHeartDialPayments().get(SCRIPT).isPaid());
    assertTrue(fixture.saved.getLast().getHeartDialPayments().get(SCRIPT).isPaid());
    assertTrue(fixture.packetStates.getLast().getHeartDialPayments().get(SCRIPT).isPaid());
    var notification = fixture.sent.get("HeartDialScriptChangeScNotify");
    assertNotNull(notification);
    var changed = (List<?>) Gameplay46.value(notification, "changed_script_info_list");
    assertEquals(1, changed.size());
    assertEquals(
        4,
        ((Descriptors.EnumValueDescriptor)
                Gameplay46.value((DynamicMessage) changed.getFirst(), "step"))
            .getNumber());
    assertEquals(2, item.getCount());
    assertEquals(4, step());
    var response =
        fixture.call(
            6358,
            "FinishEmotionDialoguePerformanceScRsp",
            fixture.wire(3, SCRIPT, 4, 2000404, 10, 999));
    assertEquals(0, Gameplay46.number(response, "retcode"));
    assertNotEquals(0, submit(payment(ITEM, 1)));
    assertEquals(2, item.getCount());
  }

  @Test
  void wrongOrExcessiveItemsAndAnUnloadedNpcNeverConsumeStock() throws Exception {
    var item = stock(3);
    assertNotEquals(0, submit(payment(ITEM, 2)));
    assertNotEquals(0, submit(payment(ITEM + 1, 1)));
    fixture.scene.getLoadedGroups().remove(47);
    assertNotEquals(0, submit(payment(ITEM, 1)));
    assertEquals(3, item.getCount());
    assertTrue(fixture.player.getGameplay().getHeartDialReports().isEmpty());
  }

  @Test
  void aStoredNormalStepAdvancesOnlyTheExactSupportedStepObjective() throws Exception {
    fixture.mission();
    var config =
        fixture.gson.fromJson(
            "{\"ID\":"
                + fixture.SUB
                + ",\"MainMissionID\":"
                + fixture.MAIN
                + ",\"TakeType\":\"Auto\",\"FinishType\":\"HeartDialScriptListStep\",\"ParamType\":\"ListContain\",\"ParamInt1\":4,\"ParamIntList\":[20004],\"Progress\":1}",
            MissionInfo.SubMissionInfo.class);
    GameplayDefinitions.subs.put(fixture.SUB, config);
    var item = stock(3);
    fixture.player.getMissionManager().refresh();
    assertEquals(0, fixture.player.getMissionManager().sub(fixture.SUB).getProgress());
    assertEquals(0, step());
    assertEquals(0, submit(payment(ITEM, 1)));
    assertEquals(1, fixture.player.getMissionManager().sub(fixture.SUB).getProgress());
    assertEquals(0, fixture.player.getMissionManager().sub(fixture.SECOND).getProgress());
    assertEquals(2, item.getCount());
  }

  @Test
  void aStackWhoseSavedDefinitionIsEquipmentIsRejectedBeforeDebitingOrReserving() throws Exception {
    var item = stock(3);
    item.setExcel(
        fixture.gson.fromJson(
            "{\"ID\":140469,\"ItemMainType\":\"Equipment\",\"ItemSubType\":\"Equipment\"}",
            ItemExcel.class));
    assertNotEquals(0, submit(payment(ITEM, 1)));
    assertEquals(3, item.getCount());
    assertTrue(fixture.player.getGameplay().getHeartDialPayments().isEmpty());
  }

  @Test
  void malformedNestedPaymentsAreRejectedWithoutDebiting() throws Exception {
    var item = stock(3);
    var valid = payment(ITEM, 1);
    var duplicate = new ByteArrayOutputStream();
    duplicate.write(valid);
    duplicate.write(fixture.wire(15, SCRIPT));
    var extra = new ByteArrayOutputStream();
    extra.write(valid);
    extra.write(fixture.wire(2, 999));
    for (byte[] bytes :
        List.of(
            new byte[0],
            Arrays.copyOf(valid, valid.length - 1),
            duplicate.toByteArray(),
            extra.toByteArray(),
            new byte[257],
            payment(0, 1),
            payment(ITEM, 0),
            payment(-1, 1))) assertNotEquals(0, submit(bytes));
    assertEquals(3, item.getCount());
    assertTrue(fixture.player.getGameplay().getHeartDialPayments().isEmpty());
  }

  @Test
  void insufficientOrForeignInventoryCannotBecomeAPaidStep() throws Exception {
    assertEquals(Retcode.ITEM_NOT_ENOUGH.getVal(), submit(payment(ITEM, 1)));
    var item = stock(3);
    var owner = GameItem.class.getDeclaredField("ownerUid");
    owner.setAccessible(true);
    owner.setInt(item, 12345);
    assertEquals(Retcode.ITEM_NOT_ENOUGH.getVal(), submit(payment(ITEM, 1)));
    assertEquals(3, item.getCount());
    assertTrue(fixture.player.getGameplay().getHeartDialPayments().isEmpty());
  }

  @Test
  void malformedOrAdditionalResourceConditionsCannotCauseAQueryExceptionOrARepair()
      throws Exception {
    var item = stock(3);
    var condition = HeartDialDefinitions.conditions.get(CONDITION);
    condition.addProperty("ParamUint2", 1);
    assertNotEquals(0, submit(payment(ITEM, 1)));
    condition.remove("ParamUint2");
    condition.add("FinishType", new JsonObject());
    assertDoesNotThrow(() -> fixture.call(6334, "GetHeartDialInfoScRsp", new byte[0]));
    assertNotEquals(0, submit(payment(ITEM, 1)));
    assertEquals(3, item.getCount());
  }

  @Test
  void anInterruptedDebitRetainsItsJournalAndCannotAutomaticallyConsumeAgain() throws Exception {
    var item = stock(3);
    var pending =
        new GameplayState.HeartDialPayment(
            SCRIPT, CONDITION, ITEM, fixture.PLANE, fixture.FLOOR, 47, 400005);
    fixture.player.getGameplay().getHeartDialPayments().put(SCRIPT, pending);
    var state = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    state.setAccessible(true);
    state.set(
        fixture.player,
        fixture.gson.fromJson(
            fixture.gson.toJson(fixture.player.getGameplay()), GameplayState.class));
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), submit(payment(ITEM, 1)));
    assertEquals(0, step());
    assertEquals(3, item.getCount());
    assertFalse(fixture.player.getGameplay().getHeartDialPayments().get(SCRIPT).isPaid());
    assertNotEquals(
        0,
        Gameplay46.number(
            fixture.call(
                6358,
                "FinishEmotionDialoguePerformanceScRsp",
                fixture.wire(3, SCRIPT, 4, 2000405, 10, 999)),
            "retcode"));
  }

  @Test
  void concurrentSubmissionsRepairOnceAndPaidStateSurvivesTwoMorphiaLoads() throws Exception {
    var item = stock(3);
    var pool = Executors.newFixedThreadPool(6);
    try {
      var jobs = new ArrayList<Future<Retcode>>();
      for (int i = 0; i < 6; i++)
        jobs.add(pool.submit(() -> HeartDialSteps.submit(fixture.player, SCRIPT, Map.of(ITEM, 1))));
      int accepted = 0;
      for (var job : jobs) if (job.get(5, TimeUnit.SECONDS) == Retcode.SUCC) accepted++;
      assertEquals(1, accepted);
      assertEquals(2, item.getCount());
    } finally {
      pool.shutdownNow();
    }
    assertEquals(
        0,
        Gameplay46.number(
            fixture.call(
                6358,
                "FinishEmotionDialoguePerformanceScRsp",
                fixture.wire(3, SCRIPT, 4, 2000404, 10, 999)),
            "retcode"));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "clockwork-repair");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(emu.lunarcore.game.player.Player.class);
      var document = new emu.lunarcore.game.player.Player();
      var state = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      state.setAccessible(true);
      for (int i = 0; i < 2; i++) {
        state.set(document, fixture.player.getGameplay());
        store.save(document);
        state.set(
            fixture.player,
            store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
        assertTrue(fixture.player.getGameplay().getHeartDialPayments().get(SCRIPT).isPaid());
        assertTrue(fixture.player.getGameplay().getHeartDialReports().get(2000404).isApplied());
        assertEquals(4, step());
        assertNotEquals(0, submit(payment(ITEM, 1)));
        assertEquals(2, item.getCount());
      }
    } finally {
      mongo.shutdownNow();
    }
  }
}
