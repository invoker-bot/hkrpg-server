package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Live request 10157 must finish even when its gameplay preconditions are not satisfied. */
class TrainCakeCatch46Test {
  @TempDir Path resources;
  final Gson gson = ResourceLoader.getGson();
  String previousRoot;
  PropExcel previousProp;
  FloorInfo previousFloor;
  FloorInfo floor;

  @BeforeEach
  void resources() throws Exception {
    previousRoot = LunarCore.getConfig().resourceDir;
    previousProp = GameData.getPropExcelMap().get(945);
    previousFloor = GameData.getFloorInfos().get("P10000_F10000003");
    JsonObject records;
    try (var stream = getClass().getResourceAsStream("/fixtures/train-cake-box-4.6.json")) {
      records = JsonParser.parseString(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
          .getAsJsonObject().getAsJsonObject("records");
    }
    GroupInfo group = null;
    for (var entry : records.entrySet()) {
      var path = resources.resolve(entry.getKey()); Files.createDirectories(path.getParent());
      Files.writeString(path, gson.toJson(entry.getValue()));
      if (entry.getKey().endsWith("MazeProp.json")) {
        var prop = gson.fromJson(entry.getValue().getAsJsonArray().get(0), PropExcel.class);
        prop.onLoad(); GameData.getPropExcelMap().add(prop);
      } else if (entry.getKey().contains("SharedRuntimeGroup")) {
        group = gson.fromJson(entry.getValue(), GroupInfo.class); group.setId(240);
      }
    }
    // Only the floor container is synthetic; the actual G240/945 placement and data rows are original.
    floor = gson.fromJson("{\"FloorID\":10000003}", FloorInfo.class);
    floor.getGroups().put(240, group);
    GameData.getFloorInfos().put("P10000_F10000003", floor);
    LunarCore.getConfig().resourceDir = resources.toString();
  }

  @AfterEach
  void restoreResources() {
    LunarCore.getConfig().resourceDir = previousRoot;
    if (previousProp == null) GameData.getPropExcelMap().keySet().remove(945);
    else GameData.getPropExcelMap().add(previousProp);
    if (previousFloor == null) GameData.getFloorInfos().remove("P10000_F10000003");
    else GameData.getFloorInfos().put("P10000_F10000003", previousFloor);
  }

  static class Actor extends Player {
    int saves;
    int level = 21;
    boolean failSave;
    Scene scene;
    final LineupManager teams = new LineupManager(this);
    Actor() { teams.getCurrentLineup().getAvatars().add(0); }
    public LineupManager getLineupManager() { return teams; }
    public GameAvatar getAvatarById(int id) { return null; }
    public Scene getScene() { return scene; }
    public int getUid() { return 10001; }
    public int getLevel() { return level; }
    public int getPlaneId() { return 10000; }
    public int getFloorId() { return 10000003; }
    public void sendPacket(BasePacket packet) {}
    public void save() { saves++; if (failSave) throw new IllegalStateException("injected save failure"); }
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final Actor actor = new Actor();
    public Player getPlayer() { return actor; }
  }

  static Object field(DynamicMessage row, String name) {
    return row.getField(row.getDescriptorForType().findFieldByName(name));
  }

  static DynamicMessage request(Session s, String name, byte[] bytes, String response)
      throws Exception {
    s.response = null;
    assertTrue(VersionedPacketHandler.handle(s, s.getProtocol().clientCommand(name), bytes),
        "The real client request must have a handler, rather than remain pending");
    assertEquals(response, s.responseName);
    assertNotNull(s.response);
    return s.response;
  }

  Session room(boolean unlocked) {
    var s = new Session();
    var plane = gson.fromJson("{\"PlaneID\":10000,\"PlaneType\":\"Train\"}", MazePlaneExcel.class);
    s.actor.scene = new Scene(s.actor, plane, 10000003);
    if (unlocked) {
      var gate = new QuestState(402080005); gate.advance(1, 1, 1);
      s.actor.getGameplay().getSubMissions().put(402080005, gate);
    }
    return s;
  }

  DynamicMessage open(Session s) throws Exception {
    return request(s, "TrainCakeCatchOpenBoxCsReq", new byte[0], "TrainCakeCatchOpenBoxScRsp");
  }

  DynamicMessage search(Session s, int cat) throws Exception {
    var req = Gameplay46.builder(s, "TrainCakeCatchSearchCsReq"); Gameplay46.set(req, "PCPDHEIEJFO", cat);
    return request(s, "TrainCakeCatchSearchCsReq", req.build().toByteArray(), "TrainCakeCatchSearchScRsp");
  }

  static List<?> list(DynamicMessage row, String field) { return (List<?>) field(row, field); }

  static void assertNativeParkContainer(DynamicMessage response) {
    // ParkPetDiyController receives rsp.field7.field14, without a null guard.
    // An absent message differs from a present message with an empty area list.
    var root = response.getDescriptorForType().findFieldByNumber(7);
    assertTrue(response.hasField(root), "Native social root must be present on the wire");
    var social = (DynamicMessage) response.getField(root);
    var park = social.getDescriptorForType().findFieldByNumber(14);
    assertTrue(social.hasField(park), "Native park sync must receive an empty container, not null");
    var areas = (DynamicMessage) social.getField(park);
    assertTrue(((List<?>) areas.getField(areas.getDescriptorForType().findFieldByNumber(1))).isEmpty(),
        "An unimplemented park must not invent unlocked areas or placed pets");
  }

  @Test
  void lockedSocialQueryIncludesTheNativeParkContainerWithoutCreatingProgress() throws Exception {
    var s = room(false); s.actor.level = 9;
    var before = gson.toJson(s.actor.getGameplay());
    var rsp = request(s, "SocialPlayGetDataCsReq", new byte[0], "SocialPlayGetDataScRsp");
    assertEquals(0, field(rsp, "retcode"));
    // Round-trip the actual reply so default message instances cannot hide an absent field.
    assertNativeParkContainer(DynamicMessage.parseFrom(rsp.getDescriptorForType(), rsp.toByteArray()));
    assertEquals(before, gson.toJson(s.actor.getGameplay()));
    assertEquals(0, s.actor.saves);
    assertEquals(9, s.actor.level);
  }

  @Test
  void capturedCatSocialQueryStillIncludesTheEmptyNativeParkContainer() throws Exception {
    var s = room(true); open(s); search(s, 10);
    var before = gson.toJson(s.actor.getGameplay());
    var rsp = request(s, "SocialPlayGetDataCsReq", new byte[0], "SocialPlayGetDataScRsp");
    assertEquals(0, field(rsp, "retcode"));
    assertNativeParkContainer(DynamicMessage.parseFrom(rsp.getDescriptorForType(), rsp.toByteArray()));
    assertEquals(before, gson.toJson(s.actor.getGameplay()));
    assertEquals(2, s.actor.saves);
    assertEquals(Map.of(10, 1), s.actor.getGameplay().getTrainCakeCatch().getCounts());
  }

  @Test
  void originalGateRejectsLevelNineAndAnUnfinishedIntroductionWithoutChangingProgress() throws Exception {
    var s = room(false); s.actor.level = 9;
    assertEquals(Retcode.PLAYER_LEVEL_NOT_MEET.getVal(), field(open(s), "retcode"));
    s.actor.level = 21;
    assertEquals(Retcode.PLOT_NOT_UNLOCK.getVal(), field(open(s), "retcode"));
    assertEquals(0, s.actor.saves);
    assertTrue(s.actor.getGameplay().getSubMissions().isEmpty());
    assertEquals(0, s.actor.getGameplay().getTrainCakeCatch().getRefreshAt());
  }

  @Test
  void eligibleOpenReturnsTheCommittedThreePositionsWithoutGrantingAnyCaptures() throws Exception {
    var s = room(true); var rsp = open(s);
    assertEquals(0, field(rsp, "retcode"));
    assertTrue((long) field(rsp, "CMHKFNFKGOI") > TrainCakeCatch.now());
    var actual = new TreeMap<Integer, Integer>();
    for (Object raw : list(rsp, "BJPHDKEBOBK")) {
      var row = (DynamicMessage) raw;
      actual.put((int) field(row, "PCPDHEIEJFO"), (int) field(row, "slot"));
    }
    assertEquals(Map.of(10, 4, 21, 6, 26, 5), actual);
    assertEquals(1, s.actor.saves);
    assertTrue(s.actor.getGameplay().getTrainCakeCatch().getCounts().isEmpty());
    assertTrue(s.actor.getGameplay().getStoryValues().isEmpty());
    assertEquals(1, s.actor.getGameplay().getSubMissions().size());
    assertEquals(rsp, open(s));
    assertEquals(1, s.actor.saves, "Repeated open must neither reroll nor save a second batch");
  }

  @Test
  void actualCatIdentityIsCapturedOnceAndQueriesRestoreBothTheBatchAndTheRecord() throws Exception {
    var s = room(true); open(s);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), field(search(s, 4), "retcode"),
        "A position number is not the cat identity in the native search request");
    var rsp = search(s, 10);
    assertEquals(0, field(rsp, "retcode"));
    var count = (DynamicMessage) field(rsp, "NIELDCLPOLA");
    assertEquals(10, field(count, "PCPDHEIEJFO")); assertEquals(1, field(count, "count"));
    var week = (DynamicMessage) list(rsp, "CEMFBJPOLNP").get(0);
    assertEquals(10001, field(week, "PCPDHEIEJFO"));
    assertEquals(List.of(10), field(week, "LPDFMJPFGJH"));
    assertEquals(rsp, search(s, 10));
    assertEquals(2, s.actor.saves);
    var reconnected = room(false);
    var saved = Player.class.getDeclaredField("gameplay"); saved.setAccessible(true);
    saved.set(reconnected.actor, gson.fromJson(gson.toJson(s.actor.getGameplay()), GameplayState.class));
    var query = request(reconnected, "SocialPlayGetDataCsReq", new byte[0], "SocialPlayGetDataScRsp");
    var profile = (DynamicMessage) field(query, "ELDIONMAAHO");
    assertEquals(3, list(profile, "BJPHDKEBOBK").size());
    assertEquals(1, list(profile, "NIELDCLPOLA").size());
    assertEquals(List.of(10), field((DynamicMessage) list(profile, "CEMFBJPOLNP").get(0), "LPDFMJPFGJH"));
    assertEquals(0, reconnected.actor.saves);
  }

  @Test
  void failedSavesRestoreThePreviousBatchAndInventoryAndCanBeRetried() throws Exception {
    var s = room(true); var before = s.actor.getGameplay().getTrainCakeCatch();
    s.actor.failSave = true;
    assertEquals(Retcode.SERVER_INTERNAL_ERROR.getVal(), field(open(s), "retcode"));
    assertSame(before, s.actor.getGameplay().getTrainCakeCatch());
    s.actor.failSave = false; assertEquals(0, field(open(s), "retcode"));
    var opened = s.actor.getGameplay().getTrainCakeCatch(); s.actor.failSave = true;
    assertEquals(Retcode.SERVER_INTERNAL_ERROR.getVal(), field(search(s, 10), "retcode"));
    assertSame(opened, s.actor.getGameplay().getTrainCakeCatch());
    assertTrue(opened.getCounts().isEmpty()); assertTrue(opened.getCaught().isEmpty());
    s.actor.failSave = false; assertEquals(0, field(search(s, 10), "retcode"));
    assertEquals(Map.of(10, 1), s.actor.getGameplay().getTrainCakeCatch().getCounts());
  }

  @Test
  void duplicateOrWideSearchFieldsCannotWrapToAValidCatIdentity() throws Exception {
    var s = room(true); open(s);
    for (long number : new long[] {0, 28, (1L << 32) + 10, -1L}) {
      var bytes = new java.io.ByteArrayOutputStream();
      var output = com.google.protobuf.CodedOutputStream.newInstance(bytes);
      output.writeUInt64(11, number); output.flush();
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), field(request(s, "TrainCakeCatchSearchCsReq",
          bytes.toByteArray(), "TrainCakeCatchSearchScRsp"), "retcode"));
    }
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), field(request(s, "TrainCakeCatchSearchCsReq",
        new byte[] {88, 10, 88, 10}, "TrainCakeCatchSearchScRsp"), "retcode"));
    assertTrue(s.actor.getGameplay().getTrainCakeCatch().getCounts().isEmpty());
    assertEquals(1, s.actor.saves);
  }

  @Test
  void changedSourceCannotRewriteAnExistingBatchOrGrantACapture() throws Exception {
    var s = room(true); open(s); var state = s.actor.getGameplay().getTrainCakeCatch();
    var config = resources.resolve("ExcelOutput/CakeConfig.json");
    Files.writeString(config, Files.readString(config) + "\n");
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), field(open(s), "retcode"));
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), field(search(s, 10), "retcode"));
    var query = request(s, "SocialPlayGetDataCsReq", new byte[0], "SocialPlayGetDataScRsp");
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), field(query, "retcode"));
    assertSame(state, s.actor.getGameplay().getTrainCakeCatch());
    assertEquals(1, s.actor.saves);
  }

  @Test
  void weekBoundaryClearsOnlyTheCurrentBatchCaptureRecordAndHonorsThePerCatCap() throws Exception {
    var s = room(true); var now = TrainCakeCatch.now();
    assertEquals(Retcode.SUCC, TrainCakeCatch.open(s.actor, now).retcode());
    assertEquals(Retcode.SUCC, TrainCakeCatch.search(s.actor, 10, now).retcode());
    long reset = s.actor.getGameplay().getTrainCakeCatch().getRefreshAt();
    assertEquals(Retcode.SUCC, TrainCakeCatch.open(s.actor, reset).retcode());
    assertTrue(s.actor.getGameplay().getTrainCakeCatch().getCaught().isEmpty());
    assertEquals(Map.of(10, 1), s.actor.getGameplay().getTrainCakeCatch().getCounts());
    int selected = s.actor.getGameplay().getTrainCakeCatch().getSpots().getFirst().getCat();
    assertEquals(Retcode.SUCC, TrainCakeCatch.search(s.actor, selected, reset).retcode());
    assertEquals(selected == 10 ? 2 : 1, s.actor.getGameplay().getTrainCakeCatch().getCounts().get(selected));
    var json = JsonParser.parseString(gson.toJson(s.actor.getGameplay())).getAsJsonObject();
    var box = json.getAsJsonObject("trainCakeCatch");
    box.getAsJsonObject("counts").addProperty("10", 13);
    var saved = Player.class.getDeclaredField("gameplay"); saved.setAccessible(true);
    saved.set(s.actor, gson.fromJson(json, GameplayState.class));
    assertEquals(Retcode.SUCC,
        TrainCakeCatch.open(s.actor, s.actor.getGameplay().getTrainCakeCatch().getRefreshAt()).retcode());
  }

  @Test
  void openBoxAwayFromItsSceneReturnsAnErrorInsteadOfLosingTheRequest() throws Exception {
    var s = new Session();
    var rsp = request(s, "TrainCakeCatchOpenBoxCsReq", new byte[0], "TrainCakeCatchOpenBoxScRsp");
    assertNotEquals(0, field(rsp, "retcode"));
    assertEquals(0, s.actor.saves);
    assertTrue(s.actor.getGameplay().getStoryValues().isEmpty());
  }

  @Test
  void emptyOpenRequestDoesNotAcceptInventedClientFields() throws Exception {
    var s = new Session();
    var rsp = request(s, "TrainCakeCatchOpenBoxCsReq", new byte[] {8, 1},
        "TrainCakeCatchOpenBoxScRsp");
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), field(rsp, "retcode"));
    assertEquals(0, s.actor.saves);
  }

  @Test
  void malformedOpenRequestStillFinishesWithAnError() throws Exception {
    var s = new Session();
    var rsp = request(s, "TrainCakeCatchOpenBoxCsReq", new byte[] {8},
        "TrainCakeCatchOpenBoxScRsp");
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), field(rsp, "retcode"));
    assertEquals(0, s.actor.saves);
  }

  @Test
  void initialSocialProfileDoesNotInventOpenedBoxesOrCaughtCats() throws Exception {
    var s = new Session();
    var rsp = request(s, "SocialPlayGetDataCsReq", new byte[0], "SocialPlayGetDataScRsp");
    assertEquals(0, field(rsp, "retcode"));
    var cats = (DynamicMessage) field(rsp, "ELDIONMAAHO");
    for (var entry : cats.getAllFields().entrySet()) {
      if (entry.getKey().isRepeated()) assertTrue(((java.util.List<?>) entry.getValue()).isEmpty());
    }
    assertEquals(0L, field(cats, "CMHKFNFKGOI"));
    assertEquals(0, s.actor.saves);
    assertTrue(s.actor.getGameplay().getStoryValues().isEmpty());
  }

  @Test
  void searchingWithoutAnOpenedBoxCannotGrantACat() throws Exception {
    var s = new Session();
    var req = Gameplay46.builder(s, "TrainCakeCatchSearchCsReq");
    Gameplay46.set(req, "PCPDHEIEJFO", 4);
    var rsp = request(s, "TrainCakeCatchSearchCsReq", req.build().toByteArray(),
        "TrainCakeCatchSearchScRsp");
    assertNotEquals(0, field(rsp, "retcode"));
    assertEquals(0, s.actor.saves);
  }

  @Test
  void openingBeforeLoginNeverReturnsSuccessOrTouchesAPlayer() throws Exception {
    var s = new Session();
    s.setState(SessionState.WAITING_FOR_LOGIN);
    assertTrue(VersionedPacketHandler.handle(s, 10157, new byte[0]));
    assertNull(s.response);
    assertEquals(0, s.actor.saves);
  }
}
