package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

/** Actual 10108 graph and native fields, with an isolated NPC and raid resource fixture. */
class HeartDialLock46Test {
  static final int SCRIPT = 10108, RAID = 43305, MAIN = 2030002, BEFORE = 2030001;
  static final int MISSING = 140408, LOCK = 140410;
  final HeartDial46Test fixture = new HeartDial46Test();
  final Map<Integer, ItemExcel> oldItems = new HashMap<>();

  @BeforeEach
  void setup() {
    fixture.setup();
    var all = new QuestState(fixture.ALL);
    all.advance(1, 1, 1);
    fixture.player.getGameplay().getSubMissions().put(fixture.ALL, all);
    HeartDialDefinitions.scripts.clear();
    HeartDialDefinitions.dialogues.clear();
    HeartDialDefinitions.scripts.put(
        SCRIPT,
        fixture.gson.fromJson(
            """
{"ScriptID":10108,"RaidID":43305,"DefaultEmoType":"Sad",
 "StepList":["Missing","Lock","UnLock","Normal"],"MissingEmoList":["Sad"],
 "MissingDialogueID":1010801,"LockDialogueID":1010802,"UnLockDialogueID":1010803,
 "TotalEmoInfoList":[{"POAIHJCEKCF":1010804},{"BNFEAFPEOIE":"Anger","POAIHJCEKCF":1010805},
  {"BNFEAFPEOIE":"Sad","POAIHJCEKCF":1010806},{"BNFEAFPEOIE":"Happy","POAIHJCEKCF":1010807}]}
""",
            HeartDialDefinitions.Script.class));
    for (int id = 1010801; id <= 1010807; id++)
      HeartDialDefinitions.dialogues.put(id, new HeartDialDefinitions.Dialogue(id, List.of()));
    bind(SCRIPT);
    condition(10001001, "AutoFinish", 0);
    condition(10001004, "ConsumeSingleItem", MISSING);
    condition(10001012, "ConsumeSingleItem", LOCK);
    condition(10001009, "FinishMainMission", MAIN);
    HeartDialDefinitions.scriptConditions.put(
        SCRIPT,
        JsonParser.parseString(
                """
{"ScriptID":10108,"MissingConditionID":10001004,"FullConditionID":10001001,
 "LockConditionID":10001012,"UnLockConditionID":10001009,"ControlConditionID":10001001}
""")
            .getAsJsonObject());
    raid();
    for (int item : List.of(MISSING, LOCK)) {
      oldItems.put(item, GameData.getItemExcelMap().get(item));
      GameData.getItemExcelMap()
          .add(
              fixture.gson.fromJson(
                  "{\"ID\":"
                      + item
                      + ",\"ItemMainType\":\"Material\",\"ItemSubType\":\"Material\",\"PileLimit\":99}",
                  ItemExcel.class));
    }
  }

  void bind(int script) {
    var binding = new HeartDialDefinitions.Npc(fixture.FLOOR, 47, 400005, List.of(script), script);
    HeartDialDefinitions.npcs.put(binding.key(), binding);
  }

  void condition(int id, String type, int parameter) {
    HeartDialDefinitions.conditions.put(
        id,
        JsonParser.parseString(
                "{\"ID\":"
                    + id
                    + ",\"FinishType\":\""
                    + type
                    + "\",\"ParamUint1\":"
                    + parameter
                    + "}")
            .getAsJsonObject());
  }

  void raid() {
    for (int id : List.of(MAIN, BEFORE))
      GameData.getMainMissionExcelMap()
          .add(
              fixture.gson.fromJson(
                  "{\"MainMissionID\":" + id + ",\"IsInRaid\":" + (id == MAIN) + "}",
                  MainMissionExcel.class));
    GameData.getMissionInfos()
        .put(
            MAIN,
            fixture.gson.fromJson(
                "{\"MainMissionID\":2030002,\"StartSubMissionList\":[203000201],\"FinishSubMissionList\":[203000234]}",
                MissionInfo.class));
    var row =
        fixture.gson.fromJson(
            """
{"RaidID":43305,"WorldLevel":0,"Type":"Mission","TeamType":"Player","EnterType":"SkipUI",
 "SkipRewardOnFinish":true,"MainMissionIDList":[2030002],"MainMissionIDBefore":2030001,
 "MainMissionIDAfter":2030001,"TrialAvatarList":[],"RaidTargetID":[],"RewardList":[],
 "TeamLimitIDList":[],"LimitIDList":[],"BuffParamList":[],"UnlockWorldLevel":[],"RecoverType":["Unknown"]}
""",
            GameplayDefinitions.Raid.class);
    GameplayDefinitions.raids.put(row.key(), row);
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
    GameplayDefinitions.raids.remove((long) RAID * 100);
    GameData.getMissionInfos().remove(MAIN);
    for (int id : List.of(MAIN, BEFORE)) GameData.getMainMissionExcelMap().keySet().remove(id);
    oldItems.forEach(
        (id, old) -> {
          if (old == null) GameData.getItemExcelMap().keySet().remove(id);
          else GameData.getItemExcelMap().add(old);
        });
  }

  int step() throws Exception {
    var response = fixture.call(6334, "GetHeartDialInfoScRsp", new byte[0]);
    assertEquals(0, Gameplay46.number(response, "retcode"));
    var rows = (List<?>) Gameplay46.value(response, "script_info_list");
    assertEquals(1, rows.size(), "资源声明的情绪锁流程必须能被原生查询读取");
    return enumStep((DynamicMessage) rows.getFirst());
  }

  int enumStep(DynamicMessage script) {
    return ((Descriptors.EnumValueDescriptor) Gameplay46.value(script, "step")).getNumber();
  }

  GameItem stock(int id, int count) {
    var item =
        new GameItem(id, count) {
          public void save() {}
        };
    assertTrue(fixture.player.getInventory().addItem(item));
    return item;
  }

  byte[] payment(int id, int count) throws Exception {
    var list = new ByteArrayOutputStream();
    var output = CodedOutputStream.newInstance(list);
    output.writeByteArray(2, fixture.wire(15, id, 6, count));
    output.flush();
    var bytes = new ByteArrayOutputStream();
    output = CodedOutputStream.newInstance(bytes);
    output.writeUInt32(15, SCRIPT);
    output.writeByteArray(13, list.toByteArray());
    output.flush();
    return bytes.toByteArray();
  }

  int submit(int id) throws Exception {
    return Gameplay46.number(
        fixture.call(6360, "SubmitEmotionItemScRsp", payment(id, 1)), "retcode");
  }

  int report(int dialogue) throws Exception {
    return Gameplay46.number(
        fixture.call(
            6358,
            "FinishEmotionDialoguePerformanceScRsp",
            fixture.wire(3, SCRIPT, 4, dialogue, 10, 999)),
        "retcode");
  }

  JsonObject journal(GameplayState state) {
    return fixture
        .gson
        .toJsonTree(state)
        .getAsJsonObject()
        .getAsJsonObject("heartDialLocks")
        .getAsJsonObject(Integer.toString(SCRIPT));
  }

  void reload(GameplayState state) throws Exception {
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(
        fixture.player, fixture.gson.fromJson(fixture.gson.toJson(state), GameplayState.class));
  }

  void completed(int id) {
    var state = new QuestState(id);
    state.advance(1, 1, 1);
    fixture.player.getGameplay().getMainMissions().put(id, state);
  }

  void payBoth() throws Exception {
    stock(MISSING, 3);
    stock(LOCK, 3);
    assertEquals(0, submit(MISSING));
    assertEquals(0, submit(LOCK));
  }

  @Test
  void queryExposesMissingWithoutCreatingAnItemReceiptOrCompletingItsMission() throws Exception {
    var missing = stock(MISSING, 3);
    var lock = stock(LOCK, 3);
    fixture.saved.clear();
    assertEquals(0, step());
    assertEquals(3, missing.getCount());
    assertEquals(3, lock.getCount());
    assertTrue(fixture.saved.isEmpty());
    assertFalse(fixture.player.getGameplay().getMainMissions().containsKey(MAIN));
  }

  @Test
  void missingAndLockConsumeTheirOwnItemAndCannotSkipTheUnlockMission() throws Exception {
    var missing = stock(MISSING, 3);
    var lock = stock(LOCK, 3);
    assertEquals(0, report(1010801));
    assertNotEquals(0, submit(LOCK));
    assertEquals(0, submit(MISSING));
    assertEquals(2, step());
    assertEquals(2, missing.getCount());
    assertEquals(3, lock.getCount());
    assertEquals(0, report(1010802));
    assertNotEquals(0, submit(MISSING));
    assertEquals(0, submit(LOCK));
    assertEquals(3, step());
    assertEquals(2, lock.getCount());
    assertEquals(0, report(1010803));
    assertNotEquals(0, report(1010806));
    assertFalse(fixture.player.getMissionManager().finished(MAIN));
    assertTrue(fixture.player.getGameplay().getHeartDialPayments().isEmpty());
  }

  @Test
  void onlyTheExactCompletedMainMissionReleasesNormalAndKeepsEveryEarlierDialogue()
      throws Exception {
    stock(MISSING, 3);
    stock(LOCK, 3);
    assertEquals(0, report(1010801));
    assertEquals(0, submit(MISSING));
    assertEquals(0, report(1010802));
    assertEquals(0, submit(LOCK));
    assertEquals(0, report(1010803));
    completed(MAIN + 1);
    fixture.player.getMissionManager().refresh();
    assertEquals(3, step());
    completed(MAIN);
    fixture.player.getMissionManager().refresh();
    assertEquals(4, step());
    assertEquals(Retcode.SUCC, HeartDials.change(fixture.player, SCRIPT, 999, 2));
    assertEquals(0, report(1010807));
    assertEquals(
        Set.of(1010801, 1010802, 1010803, 1010807),
        fixture.player.getGameplay().getHeartDialReports().keySet());
    assertTrue(HeartDials.validState(fixture.player));
  }

  @Test
  void doingCancelledWrongIdAndZeroCompletionEvidenceCannotReleaseUnlock() throws Exception {
    payBoth();
    fixture.player.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    assertEquals(3, step());
    completed(MAIN);
    fixture.player.getGameplay().getCancelledMissions().add(MAIN);
    assertEquals(3, step());
    fixture.player.getGameplay().getCancelledMissions().remove(MAIN);
    var wrong = new QuestState(MAIN + 1);
    wrong.advance(1, 1, 1);
    fixture.player.getGameplay().getMainMissions().put(MAIN, wrong);
    assertEquals(3, step());
    completed(MAIN);
    var time = QuestState.class.getDeclaredField("finishTime");
    time.setAccessible(true);
    time.setLong(fixture.player.getGameplay().getMainMissions().get(MAIN), 0);
    assertEquals(3, step());
    assertFalse(journal(fixture.player.getGameplay()).get("normalUnlocked").getAsBoolean());
  }

  @Test
  void completedMissionCannotBypassEitherRequiredItem() throws Exception {
    var missing = stock(MISSING, 3);
    var lock = stock(LOCK, 3);
    completed(MAIN);
    assertEquals(0, step());
    assertEquals(0, submit(MISSING));
    assertEquals(2, step());
    assertEquals(0, submit(LOCK));
    assertEquals(4, step());
    assertEquals(2, missing.getCount());
    assertEquals(2, lock.getCount());
    assertNotEquals(0, submit(LOCK));
    assertEquals(2, lock.getCount());
  }

  @Test
  void eachPhaseHasItsOwnSavedDebitAndNativeNotificationBeforeItsResponse() throws Exception {
    stock(MISSING, 3);
    stock(LOCK, 3);
    fixture.saved.clear();
    assertEquals(0, submit(MISSING));
    assertFalse(
        journal(fixture.saved.getFirst())
            .getAsJsonObject("missingPayment")
            .get("paid")
            .getAsBoolean());
    assertTrue(
        journal(fixture.saved.getLast())
            .getAsJsonObject("missingPayment")
            .get("paid")
            .getAsBoolean());
    var first = fixture.sent.get("HeartDialScriptChangeScNotify");
    assertNotNull(first);
    assertEquals(
        2,
        enumStep(
            (DynamicMessage)
                ((List<?>) Gameplay46.value(first, "changed_script_info_list")).getFirst()));
    fixture.saved.clear();
    assertEquals(0, submit(LOCK));
    assertFalse(
        journal(fixture.saved.getFirst())
            .getAsJsonObject("lockPayment")
            .get("paid")
            .getAsBoolean());
    assertTrue(
        journal(fixture.saved.getLast()).getAsJsonObject("lockPayment").get("paid").getAsBoolean());
    var second = fixture.sent.get("HeartDialScriptChangeScNotify");
    assertEquals(
        3,
        enumStep(
            (DynamicMessage)
                ((List<?>) Gameplay46.value(second, "changed_script_info_list")).getFirst()));
    assertEquals(3, journal(fixture.packetStates.getLast()).get("notifiedStep").getAsInt());
  }

  @Test
  void concurrentSubmissionsConsumeOneItemPerPhaseAndRestoredQueriesDoNotRepeat() throws Exception {
    var missing = stock(MISSING, 3);
    var lock = stock(LOCK, 3);
    var pool = Executors.newFixedThreadPool(6);
    try {
      for (int item : List.of(MISSING, LOCK)) {
        var jobs = new ArrayList<Callable<Retcode>>();
        for (int i = 0; i < 6; i++)
          jobs.add(() -> HeartDialSteps.submit(fixture.player, SCRIPT, Map.of(item, 1)));
        int accepted = 0;
        for (var job : pool.invokeAll(jobs))
          if (job.get(5, TimeUnit.SECONDS) == Retcode.SUCC) accepted++;
        assertEquals(1, accepted);
      }
    } finally {
      pool.shutdownNow();
    }
    long notifications =
        fixture.packetNames.stream().filter("HeartDialScriptChangeScNotify"::equals).count();
    reload(fixture.player.getGameplay());
    fixture.player.getMissionManager().refresh();
    assertEquals(3, step());
    assertEquals(
        notifications,
        fixture.packetNames.stream().filter("HeartDialScriptChangeScNotify"::equals).count());
    assertNotEquals(0, submit(LOCK));
    assertEquals(2, missing.getCount());
    assertEquals(2, lock.getCount());
  }

  @Test
  void savedNormalReleaseSurvivesRemovedOldMissionEvidenceWithoutRepeatingNotifications()
      throws Exception {
    payBoth();
    completed(MAIN);
    fixture.player.getMissionManager().refresh();
    assertEquals(4, step());
    long notifications =
        fixture.packetNames.stream().filter("HeartDialScriptChangeScNotify"::equals).count();
    fixture.player.getGameplay().getMainMissions().remove(MAIN);
    fixture.player.getGameplay().getCancelledMissions().add(MAIN);
    reload(fixture.player.getGameplay());
    fixture.player.getMissionManager().refresh();
    assertEquals(4, step());
    assertTrue(HeartDials.validState(fixture.player));
    assertEquals(
        notifications,
        fixture.packetNames.stream().filter("HeartDialScriptChangeScNotify"::equals).count());
  }

  @Test
  void theExactPaidLockAndUnlockStepsCanSatisfyOnlyTheirOwnSingleScriptObjective()
      throws Exception {
    stock(MISSING, 3);
    stock(LOCK, 3);
    var row =
        fixture.gson.fromJson(
            "{\"ID\":199971011,\"MainMissionID\":19997101,\"FinishType\":\"HeartDialScriptListStep\",\"ParamType\":\"ListContain\",\"Progress\":1,\"ParamInt1\":2,\"ParamIntList\":[10108]}",
            MissionInfo.SubMissionInfo.class);
    assertFalse(HeartDialSteps.objective(fixture.player, row));
    assertEquals(0, submit(MISSING));
    assertTrue(HeartDialSteps.objective(fixture.player, row));
    var json = fixture.gson.toJsonTree(row).getAsJsonObject();
    json.addProperty("ParamInt1", 3);
    row = fixture.gson.fromJson(json, MissionInfo.SubMissionInfo.class);
    assertFalse(HeartDialSteps.objective(fixture.player, row));
    assertEquals(0, submit(LOCK));
    assertTrue(HeartDialSteps.objective(fixture.player, row));
    json.add("ParamIntList", JsonParser.parseString("[10108,20027]"));
    assertFalse(
        HeartDialSteps.objective(
            fixture.player, fixture.gson.fromJson(json, MissionInfo.SubMissionInfo.class)));
  }

  @Test
  void aLockOnlyGraphStartsAtLockAndHasNoInventedMissingPayment() throws Exception {
    var json = fixture.gson.toJsonTree(HeartDialDefinitions.scripts.get(SCRIPT)).getAsJsonObject();
    json.add("StepList", JsonParser.parseString("[\"Lock\",\"UnLock\",\"Normal\"]"));
    json.add("MissingEmoList", new JsonArray());
    json.addProperty("MissingDialogueID", 0);
    HeartDialDefinitions.scripts.put(
        SCRIPT, fixture.gson.fromJson(json, HeartDialDefinitions.Script.class));
    HeartDialDefinitions.scriptConditions.get(SCRIPT).addProperty("MissingConditionID", 10001001);
    var lock = stock(LOCK, 3);
    assertEquals(2, step());
    assertEquals(0, report(1010802));
    assertEquals(0, submit(LOCK));
    assertEquals(3, step());
    assertFalse(journal(fixture.player.getGameplay()).has("missingPayment"));
    assertEquals(2, lock.getCount());
  }

  @Test
  void phaseSpecificDialoguesCannotBeReportedBeforeTheirPaymentEvidence() throws Exception {
    stock(MISSING, 3);
    stock(LOCK, 3);
    assertNotEquals(0, report(1010802));
    assertNotEquals(0, report(1010803));
    assertEquals(0, submit(MISSING));
    assertNotEquals(0, report(1010801));
    assertNotEquals(0, report(1010803));
    assertEquals(0, submit(LOCK));
    assertNotEquals(0, report(1010802));
    assertEquals(0, report(1010803));
  }

  @Test
  void anInterruptedMissingDebitKeepsTheSavedReservationAndCannotDebitAgain() throws Exception {
    interruptedDebit(MISSING, 0);
  }

  @Test
  void anInterruptedLockDebitKeepsThePaidMissingReceiptAndCannotDebitAgain() throws Exception {
    stock(MISSING, 3);
    assertEquals(0, submit(MISSING));
    interruptedDebit(LOCK, 2);
    assertTrue(
        journal(fixture.player.getGameplay())
            .getAsJsonObject("missingPayment")
            .get("paid")
            .getAsBoolean());
  }

  void interruptedDebit(int id, int expectedStep) throws Exception {
    var item =
        new GameItem(id, 3) {
          public void save() {
            if (getCount() == 2)
              throw new IllegalStateException("interrupted after the inventory debit");
          }
        };
    assertTrue(fixture.player.getInventory().addItem(item));
    fixture.saved.clear();
    assertThrows(IllegalStateException.class, () -> submit(id));
    assertEquals(2, item.getCount());
    assertEquals(1, fixture.saved.size());
    reload(fixture.saved.getFirst());
    assertEquals(expectedStep, step());
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), submit(id));
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), report(expectedStep == 0 ? 1010801 : 1010802));
    assertEquals(2, item.getCount());
    assertEquals(
        expectedStep == 0 ? -1 : 2,
        journal(fixture.player.getGameplay()).get("notifiedStep").getAsInt());
  }

  @Test
  void aPaidLaterPhaseWithoutItsEarlierReceiptIsRejectedWithoutHealingTheJournal()
      throws Exception {
    payBoth();
    var json = fixture.gson.toJsonTree(fixture.player.getGameplay()).getAsJsonObject();
    json.getAsJsonObject("heartDialLocks")
        .getAsJsonObject(Integer.toString(SCRIPT))
        .getAsJsonObject("missingPayment")
        .addProperty("paid", false);
    reload(fixture.gson.fromJson(json, GameplayState.class));
    String snapshot = fixture.gson.toJson(fixture.player.getGameplay());
    assertFalse(HeartDials.validState(fixture.player));
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), submit(LOCK));
    assertEquals(snapshot, fixture.gson.toJson(fixture.player.getGameplay()));
  }

  @Test
  void changedResourceCostsCannotReplaceEitherSavedPhaseReceipt() throws Exception {
    payBoth();
    String snapshot = fixture.gson.toJson(fixture.player.getGameplay());
    HeartDialDefinitions.conditions.get(10001012).addProperty("ParamUint1", LOCK + 1);
    assertFalse(HeartDials.validState(fixture.player));
    var query = fixture.call(6334, "GetHeartDialInfoScRsp", new byte[0]);
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), Gameplay46.number(query, "retcode"));
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), submit(LOCK));
    assertEquals(snapshot, fixture.gson.toJson(fixture.player.getGameplay()));
  }

  @Test
  void aForgedUnlockDialogueCannotCreateTheMissingLockPayment() throws Exception {
    stock(MISSING, 3);
    assertEquals(0, submit(MISSING));
    fixture
        .player
        .getGameplay()
        .getHeartDialReports()
        .put(
            1010803,
            new GameplayState.HeartDialReport(
                SCRIPT, 3, fixture.PLANE, fixture.FLOOR, 47, 400005, Set.of(), 3));
    assertFalse(HeartDials.validState(fixture.player));
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), submit(LOCK));
  }

  @Test
  void validItemAmountsStillRequireOneActualLoadedNpcAndAnOwnedStack() throws Exception {
    var item = stock(MISSING, 3);
    fixture.scene.getLoadedGroups().remove(47);
    assertEquals(Retcode.SCENE_ENTITY_NOT_EXIST.getVal(), submit(MISSING));
    fixture.scene.getLoadedGroups().add(47);
    var owner = GameItem.class.getDeclaredField("ownerUid");
    owner.setAccessible(true);
    owner.setInt(item, 12345);
    assertEquals(Retcode.ITEM_NOT_ENOUGH.getVal(), submit(MISSING));
    assertEquals(3, item.getCount());
    assertFalse(
        fixture
            .gson
            .toJsonTree(fixture.player.getGameplay())
            .getAsJsonObject()
            .getAsJsonObject("heartDialLocks")
            .has(Integer.toString(SCRIPT)));
  }

  @Test
  void bothNestedPhaseReceiptsNormalHistoryAndTheCursorSurviveTwoMorphiaLoads() throws Exception {
    payBoth();
    assertEquals(0, report(1010803));
    completed(MAIN);
    fixture.player.getMissionManager().refresh();
    assertEquals(0, report(1010806));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "clockwork-lock");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var document = new Player();
      var field = Player.class.getDeclaredField("gameplay");
      field.setAccessible(true);
      for (int i = 0; i < 2; i++) {
        field.set(document, fixture.player.getGameplay());
        store.save(document);
        field.set(fixture.player, store.find(Player.class).first().getGameplay());
        var saved = journal(fixture.player.getGameplay());
        assertTrue(saved.getAsJsonObject("missingPayment").get("paid").getAsBoolean());
        assertTrue(saved.getAsJsonObject("lockPayment").get("paid").getAsBoolean());
        assertTrue(saved.get("normalUnlocked").getAsBoolean());
        assertEquals(4, saved.get("notifiedStep").getAsInt());
        assertEquals(4, step());
        assertNotEquals(0, submit(LOCK));
        assertEquals(
            Set.of(1010803, 1010806), fixture.player.getGameplay().getHeartDialReports().keySet());
      }
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void trialTeamRaidAndUnknownExtraConditionsRemainUnsupportedWithoutConsuming() throws Exception {
    var missing = stock(MISSING, 3);
    var original = GameplayDefinitions.raids.get((long) RAID * 100);
    var json = fixture.gson.toJsonTree(original).getAsJsonObject();
    json.addProperty("TeamType", "TrialOnly");
    json.add("TrialAvatarList", JsonParser.parseString("[1068001,1068002]"));
    GameplayDefinitions.raids.put(
        original.key(), fixture.gson.fromJson(json, GameplayDefinitions.Raid.class));
    assertNotEquals(0, submit(MISSING));
    assertEquals(3, missing.getCount());
    GameplayDefinitions.raids.put(original.key(), original);
    HeartDialDefinitions.conditions.get(10001012).addProperty("ParamUint2", 1);
    assertNotEquals(0, submit(MISSING));
    assertEquals(3, missing.getCount());
    assertTrue(fixture.player.getGameplay().getHeartDialPayments().isEmpty());
  }

  @Test
  void aFrozenPlayerCaptainCannotOpenAnUnsupportedPaidTeamGraph() throws Exception {
    var missing = stock(MISSING, 3);
    var original = GameplayDefinitions.raids.get((long) RAID * 100);
    var json = fixture.gson.toJsonTree(original).getAsJsonObject();
    json.addProperty("LockCaptain", true);
    json.addProperty("LockCaptainAvatarID", 8001);
    GameplayDefinitions.raids.put(
        original.key(), fixture.gson.fromJson(json, GameplayDefinitions.Raid.class));
    assertNotEquals(0, submit(MISSING));
    assertEquals(3, missing.getCount());
    assertTrue(fixture.player.getGameplay().getHeartDialLocks().isEmpty());
  }
}
