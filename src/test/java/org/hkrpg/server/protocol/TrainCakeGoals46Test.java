package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.TextJoinExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.gameplay.GameplayDefinitions;
import org.hkrpg.server.gameplay.GameplayState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Original mission and text-join rows; prior progress and real captures are explicit. */
class TrainCakeGoals46Test {
  @TempDir Path root;
  final TrainCakeCatch46Test f = new TrainCakeCatch46Test();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  final List<TextJoinExcel> oldText = new ArrayList<>();
  MissionInfo oldMain;

  @BeforeEach void setup() throws Exception {
    f.resources = root;
    f.resources();
    oldSubs.putAll(GameplayDefinitions.subs);
    oldText.addAll(GameData.getTextJoinExcelMap().values());
    oldMain = GameData.getMissionInfos().get(4020800);
    GameplayDefinitions.subs.clear();
    GameData.getTextJoinExcelMap().keySet().clear();
    try (var stream = getClass().getResourceAsStream("/fixtures/train-cake-goals-4.6.json")) {
      var source = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
      GameData.getMissionInfos().put(4020800, f.gson.fromJson(source.get("main"), MissionInfo.class));
      for (var raw : source.getAsJsonArray("missions")) {
        var row = f.gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
        GameplayDefinitions.subs.put(row.getId(), row);
      }
      for (var raw : source.getAsJsonArray("textJoin"))
        GameData.getTextJoinExcelMap().add(f.gson.fromJson(raw, TextJoinExcel.class));
    }
  }

  @AfterEach void cleanup() {
    f.restoreResources();
    GameplayDefinitions.subs.clear(); GameplayDefinitions.subs.putAll(oldSubs);
    GameData.getTextJoinExcelMap().keySet().clear(); oldText.forEach(GameData.getTextJoinExcelMap()::add);
    if (oldMain == null) GameData.getMissionInfos().remove(4020800);
    else GameData.getMissionInfos().put(4020800, oldMain);
  }

  static void completed(TrainCakeCatch46Test.Session s, int id) {
    var q = new QuestState(id); q.advance(1, 1, 1);
    s.actor.getGameplay().getSubMissions().put(id, q);
  }

  static void main(TrainCakeCatch46Test.Session s) {
    s.actor.getGameplay().getMainMissions().put(4020800, new QuestState(4020800));
  }

  DynamicMessage query(TrainCakeCatch46Test.Session s, int id) throws Exception {
    var req = Gameplay46.builder(s, "TextJoinQueryCsReq"); Gameplay46.add(req, "NKNFFPBDPJP", id);
    return TrainCakeCatch46Test.request(s, "TextJoinQueryCsReq", req.build().toByteArray(), "TextJoinQueryScRsp");
  }

  DynamicMessage batch(TrainCakeCatch46Test.Session s, int id, int item) throws Exception {
    var row = Gameplay46.builder(s, "JCDNAGMDELF");
    Gameplay46.set(row, "PAIPJMKCCEH", id); Gameplay46.set(row, "HPCIKHIBFHD", item);
    var req = Gameplay46.builder(s, "TextJoinBatchSaveCsReq"); Gameplay46.add(req, "HPEDOFFJKKP", row.build());
    return TrainCakeCatch46Test.request(s, "TextJoinBatchSaveCsReq", req.build().toByteArray(), "TextJoinBatchSaveScRsp");
  }

  static int item(DynamicMessage reply) {
    var rows = (List<?>) TrainCakeCatch46Test.field(reply, "HPEDOFFJKKP");
    assertEquals(1, rows.size());
    return (int) TrainCakeCatch46Test.field((DynamicMessage) rows.getFirst(), "HPCIKHIBFHD");
  }

  @Test void weeklyLabelStartsAtNineAndTracksRealCaptureEvenIfClientSendsAStaleValue() throws Exception {
    var s = f.room(true);
    assertEquals(2569, item(query(s, 256)));
    f.open(s); f.search(s, 10);
    assertEquals(2568, item(query(s, 256)));
    var response = batch(s, 256, 2560);
    assertEquals(Retcode.SUCC.getVal(), TrainCakeCatch46Test.field(response, "retcode"));
    assertEquals(2568, item(response));
    assertEquals(Map.of(10, 1), s.actor.getGameplay().getTrainCakeCatch().getCounts());
    assertEquals(2, s.actor.saves, "The derived label itself never creates a save");
  }

  @Test void normalConfiguredSelectionSurvivesAQueryInsteadOfReturningTheDefault() throws Exception {
    var s = f.room(true);
    var response = batch(s, 18, 181);
    assertEquals(Retcode.SUCC.getVal(), TrainCakeCatch46Test.field(response, "retcode"));
    assertEquals(181, item(query(s, 18)));
    var reconnected = f.room(false);
    var saved = Player.class.getDeclaredField("gameplay"); saved.setAccessible(true);
    saved.set(reconnected.actor, f.gson.fromJson(f.gson.toJson(s.actor.getGameplay()), GameplayState.class));
    assertEquals(181, item(query(reconnected, 18)));
    reconnected.actor.failSave = true;
    assertEquals(Retcode.SERVER_INTERNAL_ERROR.getVal(),
        TrainCakeCatch46Test.field(batch(reconnected, 18, 182), "retcode"));
    assertEquals(181, item(query(reconnected, 18)), "A failed save must restore the prior selection");
  }

  @Test void invalidSelectionsAndDuplicateRowsCannotPartiallyChangeDisplayState() throws Exception {
    var s = f.room(true);
    var wrong = batch(s, 18, 999);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), TrainCakeCatch46Test.field(wrong, "retcode"));
    assertEquals(180, item(query(s, 18)));
    var row = Gameplay46.builder(s, "JCDNAGMDELF");
    Gameplay46.set(row, "PAIPJMKCCEH", 18); Gameplay46.set(row, "HPCIKHIBFHD", 181);
    var req = Gameplay46.builder(s, "TextJoinBatchSaveCsReq");
    Gameplay46.add(req, "HPEDOFFJKKP", row.build()); Gameplay46.add(req, "HPEDOFFJKKP", row.build());
    var duplicate = TrainCakeCatch46Test.request(s, "TextJoinBatchSaveCsReq", req.build().toByteArray(),
        "TextJoinBatchSaveScRsp");
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), TrainCakeCatch46Test.field(duplicate, "retcode"));
    assertEquals(180, item(query(s, 18)));
    assertEquals(0, s.actor.saves);
  }

  @Test void wideAndDuplicateScalarFieldsCannotAliasAValidTextSelection() throws Exception {
    var s = f.room(true);
    for (boolean duplicate : List.of(false, true)) {
      var nested = new java.io.ByteArrayOutputStream();
      var n = com.google.protobuf.CodedOutputStream.newInstance(nested);
      n.writeUInt64(10, duplicate ? 18 : (1L << 32) + 18);
      if (duplicate) n.writeUInt32(10, 18);
      n.writeUInt32(15, 181); n.flush();
      var outer = new java.io.ByteArrayOutputStream();
      var o = com.google.protobuf.CodedOutputStream.newInstance(outer);
      o.writeByteArray(7, nested.toByteArray()); o.flush();
      var reply = TrainCakeCatch46Test.request(s, "TextJoinBatchSaveCsReq", outer.toByteArray(),
          "TextJoinBatchSaveScRsp");
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), TrainCakeCatch46Test.field(reply, "retcode"));
      assertEquals(180, item(query(s, 18)));
    }
    assertEquals(0, s.actor.saves);
  }

  @Test void actualMorphiaRestoresSelectionsAndOlderDocumentsGetAnEmptyMap() throws Exception {
    var s = f.room(true); f.open(s); f.search(s, 10); batch(s, 18, 181);
    var server = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client = com.mongodb.client.MongoClients.create(
        "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "train-cake-goals");
      store.getMapper().map(Player.class, QuestState.class);
      var base = new Player();
      for (var entry : Map.of("uid", 98761, "name", "Text fixture", "signature", "").entrySet()) {
        var field = Player.class.getDeclaredField(entry.getKey()); field.setAccessible(true);
        field.set(base, entry.getValue());
      }
      var gameplay = Player.class.getDeclaredField("gameplay"); gameplay.setAccessible(true);
      gameplay.set(base, f.gson.fromJson(f.gson.toJson(s.actor.getGameplay()), GameplayState.class));
      store.save(base);
      var restored = store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", 98761)).first();
      assertNotNull(restored);
      assertEquals(Map.of(18, 181), restored.getGameplay().getTextJoinSelections());
      assertEquals(Map.of(10, 1), restored.getGameplay().getTrainCakeCatch().getCounts());
      store.getDatabase().getCollection("players").updateOne(new org.bson.Document("_id", 98761),
          new org.bson.Document("$unset", new org.bson.Document("gameplay.textJoinSelections", "")));
      var old = store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", 98761)).first();
      assertNotNull(old);
      assertTrue(old.getGameplay().getTextJoinSelections().isEmpty());
      assertEquals(Map.of(10, 1), old.getGameplay().getTrainCakeCatch().getCounts());
    } finally { server.shutdownNow(); }
  }

  @Test void expiredWeeklyRecordShowsNineWithoutErasingLifetimeCaptures() throws Exception {
    var s = f.room(true); f.open(s); f.search(s, 10);
    var json = JsonParser.parseString(f.gson.toJson(s.actor.getGameplay())).getAsJsonObject();
    json.getAsJsonObject("trainCakeCatch").addProperty("refreshAt", 1);
    var saved = Player.class.getDeclaredField("gameplay"); saved.setAccessible(true);
    saved.set(s.actor, f.gson.fromJson(json, GameplayState.class));
    assertEquals(2569, item(query(s, 256)));
    assertEquals(Map.of(10, 1), s.actor.getGameplay().getTrainCakeCatch().getCounts());
    assertEquals(Set.of(10), s.actor.getGameplay().getTrainCakeCatch().getCaught());
  }

  @Test void oldBoxCannotBacktrackOpenGoalButReopeningCompletesOnlyTheOpenStep() throws Exception {
    var s = f.room(true);
    f.open(s); // A batch alone does not prove this non-backtracking goal was active at the time.
    main(s);
    s.actor.getGameplay().getSubMissions().put(402080008, new QuestState(402080008));
    s.actor.getMissionManager().refresh();
    assertEquals(QuestState.Phase.DOING, s.actor.getMissionManager().sub(402080008).getPhase());
    assertEquals(0, TrainCakeCatch46Test.field(f.open(s), "retcode"));
    assertEquals(QuestState.Phase.FINISHED, s.actor.getMissionManager().sub(402080008).getPhase());
    assertEquals(QuestState.Phase.DOING, s.actor.getMissionManager().sub(402080006).getPhase());
    assertNull(s.actor.getMissionManager().sub(402080009));
    assertTrue(s.actor.getGameplay().getTrainCakeCatch().getCounts().isEmpty());
  }

  private DynamicMessage boxProfile(TrainCakeCatch46Test.Session s) throws Exception {
    var response = TrainCakeCatch46Test.request(s, "SocialPlayGetDataCsReq", new byte[0],
        "SocialPlayGetDataScRsp");
    assertEquals(0, TrainCakeCatch46Test.field(response, "retcode"));
    return (DynamicMessage) TrainCakeCatch46Test.field(response, "ELDIONMAAHO");
  }

  @Test void pendingOpenGoalAllowsClientToReopenAnExistingWeeklyBatchWithoutGrantingProgress()
      throws Exception {
    var s = f.room(true); f.open(s); f.search(s, 26);
    main(s); s.actor.getGameplay().getSubMissions().put(402080008, new QuestState(402080008));
    var batch = s.actor.getGameplay().getTrainCakeCatch();
    long originalExpiry = batch.getRefreshAt();
    var before = f.gson.toJson(s.actor.getGameplay());
    int saves = s.actor.saves;

    var profile = boxProfile(s);
    assertEquals(0L, TrainCakeCatch46Test.field(profile, "CMHKFNFKGOI"),
        "The native client hides its open interaction while this timestamp is in the future");
    assertEquals(3, ((List<?>) TrainCakeCatch46Test.field(profile, "BJPHDKEBOBK")).size());
    assertEquals(before, f.gson.toJson(s.actor.getGameplay()), "Projection must not edit the saved batch");
    assertEquals(saves, s.actor.saves, "A query must never save or complete the objective");
    assertEquals(QuestState.Phase.DOING, s.actor.getMissionManager().sub(402080008).getPhase());
    assertEquals(2568, item(query(s, 256)), "The captured cat still counts for this week");

    var scene = s.actor.scene;
    s.actor.scene = null;
    assertEquals(0L, TrainCakeCatch46Test.field(boxProfile(s), "CMHKFNFKGOI"),
        "The login query may arrive before the player reaches the train");
    assertEquals(Retcode.NOT_IN_SCENE.getVal(), TrainCakeCatch46Test.field(f.open(s), "retcode"));
    assertEquals(QuestState.Phase.DOING, s.actor.getMissionManager().sub(402080008).getPhase());
    s.actor.scene = scene;

    var response = f.open(s);
    assertEquals(0, TrainCakeCatch46Test.field(response, "retcode"));
    assertEquals(originalExpiry, TrainCakeCatch46Test.field(response, "CMHKFNFKGOI"));
    assertEquals(QuestState.Phase.FINISHED, s.actor.getMissionManager().sub(402080008).getPhase());
    assertEquals(QuestState.Phase.DOING, s.actor.getMissionManager().sub(402080006).getPhase());
    assertEquals(Map.of(26, 1), s.actor.getGameplay().getTrainCakeCatch().getCounts());
    assertEquals(originalExpiry, TrainCakeCatch46Test.field(boxProfile(s), "CMHKFNFKGOI"),
        "The ordinary box cooldown resumes after the actual open action");
  }

  @Test void unrelatedOrCancelledMissionsDoNotAlterTheClientBoxCooldown() throws Exception {
    var s = f.room(true); f.open(s);
    long expiry = s.actor.getGameplay().getTrainCakeCatch().getRefreshAt();
    assertEquals(expiry, TrainCakeCatch46Test.field(boxProfile(s), "CMHKFNFKGOI"));
    main(s); s.actor.getGameplay().getSubMissions().put(402080008, new QuestState(402080008));
    s.actor.getGameplay().getCancelledSubs().add(402080008);
    assertEquals(expiry, TrainCakeCatch46Test.field(boxProfile(s), "CMHKFNFKGOI"));
    s.actor.getGameplay().getCancelledSubs().clear();
    s.actor.getGameplay().getCancelledMissions().add(4020800);
    assertEquals(expiry, TrainCakeCatch46Test.field(boxProfile(s), "CMHKFNFKGOI"));
    s.actor.getGameplay().getCancelledMissions().clear();
    s.actor.level = 20;
    assertEquals(expiry, TrainCakeCatch46Test.field(boxProfile(s), "CMHKFNFKGOI"));
    s.actor.level = 21;
    var gate = s.actor.getGameplay().getSubMissions().remove(402080005);
    assertEquals(expiry, TrainCakeCatch46Test.field(boxProfile(s), "CMHKFNFKGOI"));
    s.actor.getGameplay().getSubMissions().put(402080005, gate);
    GameplayDefinitions.subs.get(402080008).setParamType("Equal");
    assertEquals(expiry, TrainCakeCatch46Test.field(boxProfile(s), "CMHKFNFKGOI"));
    assertEquals(QuestState.Phase.DOING, s.actor.getMissionManager().sub(402080008).getPhase());
    GameplayDefinitions.subs.get(402080008).setParamType("NoPara");
    var future = JsonParser.parseString(f.gson.toJson(s.actor.getGameplay())).getAsJsonObject();
    future.getAsJsonObject("trainCakeCatch").addProperty("refreshAt", expiry + 604800);
    var gameplay = Player.class.getDeclaredField("gameplay"); gameplay.setAccessible(true);
    gameplay.set(s.actor, f.gson.fromJson(future, GameplayState.class));
    assertEquals(expiry + 604800, TrainCakeCatch46Test.field(boxProfile(s), "CMHKFNFKGOI"),
        "An invalid future week must not be presented as a valid retry");
  }

  @Test void explicitlyBacktrackingSearchGoalUsesARealEarlierCaptureAndKeepsLaterTalk() throws Exception {
    var s = f.room(true); f.open(s); f.search(s, 10);
    main(s); completed(s, 402080008); completed(s, 402080006);
    s.actor.getMissionManager().refresh();
    assertEquals(QuestState.Phase.FINISHED, s.actor.getMissionManager().sub(402080009).getPhase());
    assertEquals(QuestState.Phase.DOING, s.actor.getMissionManager().sub(402080012).getPhase());
    assertEquals(Map.of(10, 1), s.actor.getGameplay().getTrainCakeCatch().getCounts());
  }

  @Test void originalRemainingTalksMustActuallyFinishBeforeMainMissionCompletes() throws Exception {
    var s = f.room(true); f.open(s); f.search(s, 10);
    main(s); s.actor.getGameplay().getSubMissions().put(402080008, new QuestState(402080008));
    assertEquals(0, TrainCakeCatch46Test.field(f.open(s), "retcode"));
    assertEquals(QuestState.Phase.DOING, s.actor.getMissionManager().sub(402080006).getPhase());
    assertEquals(Retcode.SUCC, s.actor.getMissionManager().finishTalk(402080006, "Mission_402080006"));
    assertEquals(QuestState.Phase.FINISHED, s.actor.getMissionManager().sub(402080009).getPhase());
    assertEquals(QuestState.Phase.DOING, s.actor.getMissionManager().sub(402080012).getPhase());
    assertEquals(QuestState.Phase.DOING, s.actor.getGameplay().getMainMissions().get(4020800).getPhase());
    assertEquals(Retcode.SUCC, s.actor.getMissionManager().finishTalk(402080012, "Mission_402080012"));
    assertEquals(QuestState.Phase.DOING, s.actor.getMissionManager().sub(402080011).getPhase());
    assertEquals(Retcode.SUCC, s.actor.getMissionManager().finishTalk(402080011, "Mission_402080011"));
    assertEquals(QuestState.Phase.DOING, s.actor.getMissionManager().sub(402080010).getPhase());
    assertEquals(Retcode.SUCC, s.actor.getMissionManager().finishTalk(402080010, "Mission_402080010"));
    assertEquals(QuestState.Phase.FINISHED, s.actor.getGameplay().getMainMissions().get(4020800).getPhase());
    assertEquals(Map.of(10, 1), s.actor.getGameplay().getTrainCakeCatch().getCounts());
  }

  @Test void searchGoalDoesNotBacktrackAnOpenBoxWithoutARealCapture() throws Exception {
    var s = f.room(true); f.open(s);
    main(s); completed(s, 402080008); completed(s, 402080006);
    s.actor.getMissionManager().refresh();
    assertEquals(QuestState.Phase.DOING, s.actor.getMissionManager().sub(402080009).getPhase());
    assertNull(s.actor.getMissionManager().sub(402080012));
    assertTrue(s.actor.getGameplay().getTrainCakeCatch().getCounts().isEmpty());
    assertEquals(0, TrainCakeCatch46Test.field(f.search(s, 10), "retcode"));
    assertEquals(QuestState.Phase.FINISHED, s.actor.getMissionManager().sub(402080009).getPhase());
    assertEquals(QuestState.Phase.DOING, s.actor.getMissionManager().sub(402080012).getPhase());
  }

  @Test void failedMissionSaveRetainsTheOpenedBatchAndAnUnfinishedGoalForRetry() throws Exception {
    var s = f.room(true); f.open(s);
    main(s); s.actor.getGameplay().getSubMissions().put(402080008, new QuestState(402080008));
    var batch = s.actor.getGameplay().getTrainCakeCatch();
    assertEquals(0L, TrainCakeCatch46Test.field(boxProfile(s), "CMHKFNFKGOI"));
    s.actor.failSave = true;
    assertEquals(Retcode.SERVER_INTERNAL_ERROR.getVal(), TrainCakeCatch46Test.field(f.open(s), "retcode"));
    assertSame(batch, s.actor.getGameplay().getTrainCakeCatch());
    assertEquals(QuestState.Phase.DOING, s.actor.getMissionManager().sub(402080008).getPhase());
    assertEquals(0L, TrainCakeCatch46Test.field(boxProfile(s), "CMHKFNFKGOI"));
    s.actor.failSave = false;
    assertEquals(0, TrainCakeCatch46Test.field(f.open(s), "retcode"));
    assertEquals(QuestState.Phase.FINISHED, s.actor.getMissionManager().sub(402080008).getPhase());
  }

  @Test void cancelledOrMismatchedGoalCannotBeFinishedByOpening() throws Exception {
    var s = f.room(true); main(s);
    s.actor.getGameplay().getSubMissions().put(402080008, new QuestState(402080008));
    s.actor.getGameplay().getCancelledSubs().add(402080008);
    assertEquals(0, TrainCakeCatch46Test.field(f.open(s), "retcode"));
    assertEquals(QuestState.Phase.DOING, s.actor.getMissionManager().sub(402080008).getPhase());
    s.actor.getGameplay().getCancelledSubs().clear();
    GameplayDefinitions.subs.get(402080008).setParamType("Equal");
    assertEquals(0, TrainCakeCatch46Test.field(f.open(s), "retcode"));
    assertEquals(QuestState.Phase.DOING, s.actor.getMissionManager().sub(402080008).getPhase());
    assertTrue(s.actor.getGameplay().getTrainCakeCatch().getCounts().isEmpty());
  }

  @Test void changedSourceDoesNotTurnOldCapturesIntoMissionProgress() throws Exception {
    var s = f.room(true); f.open(s); f.search(s, 10);
    main(s); completed(s, 402080008); completed(s, 402080006);
    var config = root.resolve("ExcelOutput/CakeConfig.json");
    java.nio.file.Files.writeString(config, java.nio.file.Files.readString(config) + "\n");
    s.actor.getMissionManager().refresh();
    assertEquals(QuestState.Phase.DOING, s.actor.getMissionManager().sub(402080009).getPhase());
    var reply = query(s, 256);
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), TrainCakeCatch46Test.field(reply, "retcode"));
    assertTrue(((List<?>) TrainCakeCatch46Test.field(reply, "HPEDOFFJKKP")).isEmpty());
  }
}
