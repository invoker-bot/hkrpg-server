package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.proto.PropTimelineInfoOuterClass.PropTimelineInfo;
import emu.lunarcore.server.packet.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Whole original rows/groups; acceptance and group gates are explicitly isolated fixtures. */
class PropTimeline46Test {
  final Gson gson = ResourceLoader.getGson();
  final JsonObject source =
      JsonParser.parseReader(
              new InputStreamReader(
                  Objects.requireNonNull(
                      getClass().getResourceAsStream("/fixtures/prop-timeline-4.6.json")),
                  StandardCharsets.UTF_8))
          .getAsJsonObject();
  final List<MainMissionExcel> oldMains = new ArrayList<>();
  final Map<Integer, MissionInfo> oldHeaders = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  final Map<String, FloorInfo> oldFloors = new HashMap<>();
  final Map<Integer, PropExcel> oldProps = new HashMap<>();
  final Map<Integer, MazePlaneExcel> oldPlanes = new HashMap<>();
  final Map<Integer, MapEntranceExcel> oldEntries = new HashMap<>();
  final Map<Long, GameplayDefinitions.Raid> oldRaids = new HashMap<>();
  MissionInfo.SubMissionInfo goal;
  GroupInfo group;
  Actor actor;
  Session session;

  static class Actor extends Player {
    Scene current;
    int saves, notifications;
    boolean failSave;
    boolean battle;
    final List<JsonObject> persisted = new ArrayList<>();
    final PlayerLineup lineup = new PlayerLineup(this, 0);

    public Scene getScene() {
      return current;
    }

    public int getPlaneId() {
      return current == null ? 0 : current.getPlaneId();
    }

    public int getFloorId() {
      return current == null ? 0 : current.getFloorId();
    }

    public int getEntryId() {
      return current == null ? 0 : current.getEntryId();
    }

    public emu.lunarcore.util.Position getPos() {
      return new emu.lunarcore.util.Position();
    }

    public emu.lunarcore.util.Position getRot() {
      return new emu.lunarcore.util.Position();
    }

    public PlayerLineup getCurrentLineup() {
      return lineup;
    }

    public void save() {
      if (failSave) throw new IllegalStateException("injected save failure");
      saves++;
      persisted.add(new Gson().toJsonTree(getGameplay()).getAsJsonObject());
    }

    public boolean isInBattle() {
      return battle;
    }

    public void sendPacket(BasePacket packet) {
      notifications++;
    }
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final Actor actor;

    Session(Actor actor) {
      this.actor = actor;
    }

    public Player getPlayer() {
      return actor;
    }
  }

  @BeforeEach
  void load() {
    oldMains.addAll(GameData.getMainMissionExcelMap().values());
    oldHeaders.putAll(GameData.getMissionInfos());
    oldSubs.putAll(GameplayDefinitions.subs);
    GameData.getMainMissionExcelMap().values().clear();
    GameData.getMissionInfos().clear();
    GameplayDefinitions.subs.clear();
    for (var value : source.getAsJsonArray("propRows")) {
      var row = value.getAsJsonObject().deepCopy();
      int id = row.get("ID").getAsInt();
      oldProps.put(id, GameData.getPropExcelMap().get(id));
      if (row.has("PropName") && row.get("PropName").isJsonObject())
        row.addProperty("PropName", row.getAsJsonObject("PropName").get("Hash").getAsLong());
      var prop = gson.fromJson(row, PropExcel.class);
      prop.onLoad();
      GameData.getPropExcelMap().add(prop);
    }
    for (var value : source.getAsJsonArray("planeRows")) {
      var row = gson.fromJson(value, MazePlaneExcel.class);
      oldPlanes.put(row.getPlaneID(), GameData.getMazePlaneExcelMap().get(row.getPlaneID()));
      GameData.getMazePlaneExcelMap().add(row);
    }
    for (var value : source.getAsJsonArray("entryRows")) {
      var row = gson.fromJson(value, MapEntranceExcel.class);
      oldEntries.put(row.getId(), GameData.getMapEntranceExcelMap().get(row.getId()));
      GameData.getMapEntranceExcelMap().add(row);
    }
    actor = new Actor();
    actor.lineup.getAvatars().add(1001);
    var avatar =
        new emu.lunarcore.game.avatar.GameAvatar(
            gson.fromJson("{\"AvatarID\":1001}", AvatarExcel.class));
    avatar.setOwner(actor);
    actor.getAvatars().getAvatars().put(1001, avatar);
    session = new Session(actor);
  }

  @AfterEach
  void restore() {
    for (var e : oldRaids.entrySet()) {
      GameplayDefinitions.raids.remove(e.getKey());
      if (e.getValue() != null) GameplayDefinitions.raids.put(e.getKey(), e.getValue());
    }
    GameData.getMainMissionExcelMap().values().clear();
    oldMains.forEach(GameData.getMainMissionExcelMap()::add);
    GameData.getMissionInfos().clear();
    GameData.getMissionInfos().putAll(oldHeaders);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
    for (var e : oldFloors.entrySet()) {
      GameData.getFloorInfos().remove(e.getKey());
      if (e.getValue() != null) GameData.getFloorInfos().put(e.getKey(), e.getValue());
    }
    for (var e : oldProps.entrySet()) {
      GameData.getPropExcelMap().keySet().remove(e.getKey());
      if (e.getValue() != null) GameData.getPropExcelMap().add(e.getValue());
    }
    for (var e : oldPlanes.entrySet()) {
      GameData.getMazePlaneExcelMap().keySet().remove(e.getKey());
      if (e.getValue() != null) GameData.getMazePlaneExcelMap().add(e.getValue());
    }
    for (var e : oldEntries.entrySet()) {
      GameData.getMapEntranceExcelMap().keySet().remove(e.getKey());
      if (e.getValue() != null) GameData.getMapEntranceExcelMap().add(e.getValue());
    }
  }

  JsonObject row(String array, String idKey, int id) {
    for (var v : source.getAsJsonArray(array))
      if (v.getAsJsonObject().get(idKey).getAsInt() == id) return v.getAsJsonObject().deepCopy();
    throw new AssertionError("Missing original row " + id);
  }

  void activate(int id) {
    goal = gson.fromJson(row("subs", "ID", id), MissionInfo.SubMissionInfo.class);
    if (source.getAsJsonObject("scripts").has("" + id)) {
      var raw = source.getAsJsonObject("scripts").get("" + id);
      var task = gson.fromJson(raw, MissionTaskInfo.class);
      task.retainSourceDefinition(raw);
      goal.setTask(task);
    }
    int main = goal.getMainMissionID();
    GameData.getMainMissionExcelMap()
        .add(gson.fromJson(row("mainRows", "MainMissionID", main), MainMissionExcel.class));
    var header = row("headers", "MainMissionID", main);
    var selected = new JsonArray();
    selected.add(row("subs", "ID", id));
    header.add("SubMissionList", selected);
    var parsedHeader = gson.fromJson(header, MissionInfo.class);
    parsedHeader.getSubMissionList()[0] = goal;
    GameData.getMissionInfos().put(main, parsedHeader);
    GameplayDefinitions.subs.put(id, goal);
    for (var raw : source.getAsJsonArray("linkedSubs")) {
      var linked = gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
      if (linked.getMainMissionID() == main) {
        GameplayDefinitions.subs.put(linked.getId(), linked);
        actor.getGameplay().getSubMissions().put(linked.getId(), new QuestState(linked.getId()));
      }
    }
    actor.getGameplay().getMainMissions().put(main, new QuestState(main));
    actor.getGameplay().getSubMissions().put(id, new QuestState(id));
    String key = "P" + goal.getLevelPlaneID() + "_F" + goal.getLevelFloorID();
    var floor = gson.fromJson("{\"FloorID\":" + goal.getLevelFloorID() + "}", FloorInfo.class);
    for (var v : source.getAsJsonArray("groups")) {
      var g = v.getAsJsonObject();
      if (g.get("path").getAsString().endsWith("_G" + goal.getParamInt1() + ".json")
          && g.get("path").getAsString().contains("Groups_" + key + "/")) {
        group = gson.fromJson(g.get("definition"), GroupInfo.class);
        group.setId(goal.getParamInt1());
        floor.getGroups().put(group.getId(), group);
        break;
      }
    }
    assertNotNull(group);
    if (!oldFloors.containsKey(key)) oldFloors.put(key, GameData.getFloorInfos().get(key));
    GameData.getFloorInfos().put(key, floor);
    rebuild();
  }

  void rebuild() {
    actor.current =
        new Scene(
            actor,
            GameData.getMazePlaneExcelMap().get(goal.getLevelPlaneID()),
            goal.getLevelFloorID());
    var entry =
        GameData.getMapEntranceExcelMap().values().stream()
            .filter(
                e ->
                    e.getPlaneID() == goal.getLevelPlaneID()
                        && e.getFloorID() == goal.getLevelFloorID())
            .findFirst()
            .orElseThrow();
    actor.current.setEntryId(entry.getId());
    actor.current.loadGroup(group);
  }

  EntityProp prop() {
    return actor.current.getEntities().values().stream()
        .filter(e -> e instanceof EntityProp && e.getInstId() == goal.getParamInt2())
        .map(e -> (EntityProp) e)
        .findFirst()
        .orElseThrow(() -> new AssertionError("Original tagged prop was not loaded"));
  }

  byte[] request(String state) {
    var info = Gameplay46.builder(session, "PropTimelineInfo");
    Gameplay46.set(
        info, "timeline_byte_value", ByteString.copyFrom(state, StandardCharsets.US_ASCII));
    var req = Gameplay46.builder(session, "ChangePropTimelineInfoCsReq");
    Gameplay46.set(req, "prop_entity_id", prop().getEntityId());
    Gameplay46.set(req, "interact_id", actor.current.getRuntimeId());
    Gameplay46.set(req, "uuid", -1L);
    Gameplay46.set(req, "timeline_info", info.build());
    return req.build().toByteArray();
  }

  int receive(byte[] bytes) throws Exception {
    assertTrue(
        VersionedPacketHandler.handle(session, 1483, bytes),
        "The original native action must be handled");
    assertEquals("ChangePropTimelineInfoScRsp", session.responseName);
    return Gameplay46.number(session.response, "retcode");
  }

  @ParameterizedTest
  @ValueSource(ints = {104010366, 104010523, 104010572, 104061121, 104061152, 104061020, 104370627})
  void originalWorldGoalNeedsAnActualStateReportAndRestoresItsTimeline(int id) throws Exception {
    activate(id);
    actor.getMissionManager().refresh();
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(id).getPhase());
    assertNull(prop().getTimelineInfo());
    assertEquals(0, receive(request(goal.getParamStr1())));
    assertEquals(QuestState.Phase.FINISHED, actor.getMissionManager().sub(id).getPhase());
    for (var action : goal.getFinishActionList()) {
      if ("SetFloorSavedValue".equals(action.getType())) {
        var args = action.getStrings();
        assertEquals(
            Integer.parseInt(args[3]),
            actor.getGameplay().getStoryValues().get("floor:" + args[1] + ":" + args[2]));
      }
      if ("delSubMission".equals(action.getType()))
        for (int linked : action.getParams())
          assertEquals(QuestState.Phase.FINISHED, actor.getMissionManager().sub(linked).getPhase());
    }
    assertEquals(
        goal.getParamStr1(),
        new String(prop().getTimelineInfo().getCOOGIPJIPIA().toArray(), StandardCharsets.US_ASCII));
    rebuild();
    assertNotNull(prop().getTimelineInfo());
    assertEquals(
        goal.getParamStr1(),
        new String(prop().getTimelineInfo().getCOOGIPJIPIA().toArray(), StandardCharsets.US_ASCII));
  }

  @Test
  void aPlainWorldSceneAdvertisesItsCurrentNativeInteractionContext() throws Exception {
    activate(104010366);
    var core =
        emu.lunarcore.proto.GetCurSceneInfoScRspOuterClass.GetCurSceneInfoScRsp.newInstance()
            .setScene(actor.current.toProto())
            .toByteArray();
    var frame = PacketCodec.encode(CmdId.GetCurSceneInfoScRsp, new byte[0], core);
    var input =
        io.netty.buffer.Unpooled.wrappedBuffer(session.getProtocol().outboundFrames(frame, actor));
    try {
      var packet = PacketCodec.decodeNext(input);
      var response =
          session.getProtocol().clientSchema().parse("GetCurSceneInfoScRsp", packet.body());
      var scene = (DynamicMessage) Gameplay46.value(response, "scene");
      assertEquals(actor.current.getRuntimeId(), Gameplay46.value(scene, "interact_id"));
    } finally {
      input.release();
    }
  }

  @Test
  void legacyTimelineFieldsTranslateToTheMatchedNativeFields() throws Exception {
    var core =
        PropTimelineInfo.newInstance()
            .setKBFLIKKFOEG(true)
            .setCOOGIPJIPIA("L0_Idle".getBytes(StandardCharsets.US_ASCII));
    var nativeBody = session.getProtocol().translate("PropTimelineInfo", core.toByteArray(), true);
    var nativeInfo = session.getProtocol().clientSchema().parse("PropTimelineInfo", nativeBody);
    assertEquals(true, Gameplay46.value(nativeInfo, "timeline_bool_value"));
    assertEquals(
        ByteString.copyFromUtf8("L0_Idle"), Gameplay46.value(nativeInfo, "timeline_byte_value"));
    assertArrayEquals(
        core.toByteArray(), session.getProtocol().translate("PropTimelineInfo", nativeBody, false));
  }

  DynamicMessage.Builder parsed(byte[] bytes) throws Exception {
    return session
        .getProtocol()
        .clientSchema()
        .parse("ChangePropTimelineInfoCsReq", bytes)
        .toBuilder();
  }

  void gameplay(JsonObject document) throws Exception {
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(actor, gson.fromJson(document, GameplayState.class));
  }

  @Test
  void wrongStateAndDuplicateReportCannotManufactureExtraProgress() throws Exception {
    activate(104010366);
    assertEquals(0, receive(request("L1_Break")));
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(goal.getId()).getPhase());
    assertEquals(0, receive(request(goal.getParamStr1())));
    var state = actor.getMissionManager().sub(goal.getId());
    long time = state.getFinishTime();
    assertEquals(0, receive(request(goal.getParamStr1())));
    assertEquals(1, state.getProgress());
    assertEquals(time, state.getFinishTime());
  }

  @Test
  void aReplacementSceneRejectsItsPredecessorsRequestEvenWhenTheEntityNumberIsReused()
      throws Exception {
    activate(104010366);
    var old = request(goal.getParamStr1());
    int id = prop().getEntityId();
    rebuild();
    assertEquals(id, prop().getEntityId());
    assertEquals(Retcode.MISSION_SCENE_NOT_MATCH.getVal(), receive(old));
    assertNull(prop().getTimelineInfo());
    assertTrue(actor.getGameplay().getPropTimelines().getRecords().isEmpty());
    assertEquals(0, receive(request(goal.getParamStr1())));
  }

  @Test
  void failedPersistencePreservesTheOldMapEntityAndGoalAndCanBeRetried() throws Exception {
    activate(104010366);
    var old = actor.getGameplay().getPropTimelines();
    actor.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), receive(request(goal.getParamStr1())));
    assertSame(old, actor.getGameplay().getPropTimelines());
    assertTrue(old.getRecords().isEmpty());
    assertNull(prop().getTimelineInfo());
    assertEquals(0, actor.notifications);
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(goal.getId()).getPhase());
    actor.failSave = false;
    assertEquals(0, receive(request(goal.getParamStr1())));
    var first = actor.persisted.getFirst();
    assertEquals(1, first.getAsJsonObject("propTimelines").getAsJsonObject("records").size());
    assertEquals(
        "DOING",
        first
            .getAsJsonObject("subMissions")
            .getAsJsonObject("" + goal.getId())
            .get("phase")
            .getAsString());
  }

  @Test
  void aReportedWorldStateCanBacktrackAfterLaterAcceptanceAndJsonReload() throws Exception {
    activate(104010366);
    actor.getGameplay().getMainMissions().clear();
    actor.getGameplay().getSubMissions().clear();
    assertEquals(0, receive(request(goal.getParamStr1())));
    var document = gson.toJsonTree(actor.getGameplay()).getAsJsonObject();
    gameplay(document);
    rebuild();
    assertNotNull(prop().getTimelineInfo());
    actor
        .getGameplay()
        .getMainMissions()
        .put(goal.getMainMissionID(), new QuestState(goal.getMainMissionID()));
    actor.getGameplay().getSubMissions().put(goal.getId(), new QuestState(goal.getId()));
    actor.getMissionManager().refresh();
    assertEquals(QuestState.Phase.FINISHED, actor.getMissionManager().sub(goal.getId()).getPhase());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "absent-version",
        "wrong-version",
        "null-records",
        "wrong-key",
        "wrong-prop",
        "wrong-acceptance",
        "wrong-state",
        "wrong-source"
      })
  void corruptStoredProofIsRetainedAndCannotBeReplacedByAClientReport(String corruption)
      throws Exception {
    activate(104010366);
    assertEquals(0, receive(request("L1_Break")));
    var document = gson.toJsonTree(actor.getGameplay()).getAsJsonObject();
    var state = document.getAsJsonObject("propTimelines");
    var records = state.getAsJsonObject("records");
    String key = records.keySet().iterator().next();
    var record = records.getAsJsonObject(key);
    switch (corruption) {
      case "absent-version" -> state.remove("version");
      case "wrong-version" -> state.addProperty("version", 99);
      case "null-records" -> state.add("records", JsonNull.INSTANCE);
      case "wrong-key" -> {
        records.remove(key);
        records.add("999", record);
      }
      case "wrong-prop" -> record.addProperty("prop", -1);
      case "wrong-acceptance" -> record.addProperty("acceptance", "bad");
      case "wrong-state" -> record.addProperty("state", "bad state");
      case "wrong-source" -> record.addProperty("source", "bad");
    }
    gameplay(document);
    var before = gson.toJson(actor.getGameplay().getPropTimelines());
    rebuild();
    assertNull(prop().getTimelineInfo());
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), receive(request(goal.getParamStr1())));
    assertEquals(before, gson.toJson(actor.getGameplay().getPropTimelines()));
    assertEquals(0, actor.getMissionManager().sub(goal.getId()).getProgress());
  }

  @Test
  void aChangedOriginalComponentCannotRestoreOrSatisfyAnOldSnapshot() throws Exception {
    activate(104010366);
    actor.getGameplay().getSubMissions().clear();
    actor.getGameplay().getMainMissions().clear();
    assertEquals(0, receive(request(goal.getParamStr1())));
    var raw = gson.toJsonTree(group).getAsJsonObject();
    var target = raw.getAsJsonArray("PropList").get(0).getAsJsonObject();
    var tags =
        target
            .getAsJsonObject("TagOverrideComponents")
            .getAsJsonArray("TagComponents")
            .get(0)
            .getAsJsonObject();
    tags.addProperty("InitState", "L2_New");
    group = gson.fromJson(raw, GroupInfo.class);
    group.setId(goal.getParamInt1());
    actor.current.getFloorInfo().getGroups().put(group.getId(), group);
    rebuild();
    assertNull(prop().getTimelineInfo());
    actor
        .getGameplay()
        .getMainMissions()
        .put(goal.getMainMissionID(), new QuestState(goal.getMainMissionID()));
    actor.getGameplay().getSubMissions().put(goal.getId(), new QuestState(goal.getId()));
    actor.getMissionManager().refresh();
    assertEquals(0, actor.getMissionManager().sub(goal.getId()).getProgress());
    assertEquals(0, receive(request(goal.getParamStr1())));
    assertEquals(1, actor.getMissionManager().sub(goal.getId()).getProgress());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "duplicate-top",
        "duplicate-state",
        "unknown-top",
        "unknown-state",
        "overflow-entity",
        "bad-bool",
        "truncated",
        "wrong-wire",
        "too-large"
      })
  void malformedWireIsRejectedBeforeAnyPersistence(String corruption) throws Exception {
    activate(104010366);
    byte[] valid = request(goal.getParamStr1());
    var out = new java.io.ByteArrayOutputStream();
    var wire = CodedOutputStream.newInstance(out);
    switch (corruption) {
      case "duplicate-top" -> {
        wire.writeRawBytes(valid);
        wire.writeUInt32(6, prop().getEntityId());
      }
      case "unknown-top" -> {
        wire.writeRawBytes(valid);
        wire.writeUInt32(15, 1);
      }
      case "overflow-entity" -> {
        wire.writeUInt64(6, 0x100000000L + prop().getEntityId());
      }
      case "bad-bool" -> {
        wire.writeUInt64(7, 2);
      }
      case "wrong-wire" -> wire.writeFixed32(6, prop().getEntityId());
      case "too-large" -> wire.writeByteArray(9, new byte[513]);
      case "truncated" -> wire.writeRawBytes(new byte[] {0x4a, 0x7f, 0x1a});
      default -> {
        var inner = new java.io.ByteArrayOutputStream();
        var iw = CodedOutputStream.newInstance(inner);
        iw.writeBytes(3, ByteString.copyFromUtf8(goal.getParamStr1()));
        if ("duplicate-state".equals(corruption))
          iw.writeBytes(3, ByteString.copyFromUtf8(goal.getParamStr1()));
        else iw.writeUInt32(15, 1);
        iw.flush();
        wire.writeByteArray(9, inner.toByteArray());
      }
    }
    wire.flush();
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), receive(out.toByteArray()));
    assertEquals(0, actor.saves);
    assertTrue(actor.getGameplay().getPropTimelines().getRecords().isEmpty());
    assertNull(prop().getTimelineInfo());
  }

  @ParameterizedTest
  @ValueSource(strings = {"empty", "control", "non-ascii", "too-long", "boolean-route", "bad-name"})
  void unsupportedStatePayloadsCannotAffectTheGoal(String kind) throws Exception {
    activate(104010366);
    var req = parsed(request(goal.getParamStr1()));
    var info = Gameplay46.builder(session, "PropTimelineInfo");
    byte[] state =
        switch (kind) {
          case "empty" -> new byte[0];
          case "control" -> new byte[] {1};
          case "non-ascii" -> new byte[] {(byte) 0xc2, (byte) 0xa0};
          case "too-long" -> new byte[129];
          case "bad-name" -> "L0 Idle".getBytes(StandardCharsets.US_ASCII);
          default -> goal.getParamStr1().getBytes(StandardCharsets.US_ASCII);
        };
    Gameplay46.set(info, "timeline_byte_value", ByteString.copyFrom(state));
    if ("boolean-route".equals(kind)) Gameplay46.set(info, "timeline_bool_value", true);
    Gameplay46.set(req, "timeline_info", info.build());
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), receive(req.build().toByteArray()));
    assertEquals(0, actor.saves);
    assertNull(prop().getTimelineInfo());
  }

  @Test
  void battleReportsAndUntaggedPropsCannotWriteTimelineProof() throws Exception {
    activate(104010366);
    actor.battle = true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), receive(request(goal.getParamStr1())));
    actor.battle = false;
    var raw = gson.toJsonTree(prop().getPropInfo()).getAsJsonObject();
    raw.remove("TagOverrideComponents");
    prop().setPropInfo(gson.fromJson(raw, PropInfo.class));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), receive(request(goal.getParamStr1())));
    assertEquals(0, actor.saves);
  }

  @Test
  void queuedForeignSceneSnapshotsAndLegacyFramesKeepTheirOwnContext() throws Exception {
    activate(104010366);
    var bridge = session.getProtocol();
    var core =
        emu.lunarcore.proto.GetCurSceneInfoScRspOuterClass.GetCurSceneInfoScRsp.newInstance()
            .setScene(actor.current.toProto())
            .toByteArray();
    var nativeBody = bridge.outbound(CmdId.GetCurSceneInfoScRsp, core).body();
    var response = bridge.clientSchema().parse("GetCurSceneInfoScRsp", nativeBody);
    var field = response.getDescriptorForType().findFieldByName("scene");
    for (var name : List.of("plane_id", "floor_id", "entry_id")) {
      var scene = ((DynamicMessage) response.getField(field)).toBuilder();
      Gameplay46.set(scene, name, 1);
      var bytes = response.toBuilder().setField(field, scene.build()).build().toByteArray();
      assertArrayEquals(
          bytes, Scene46.enrich(bridge.clientSchema(), "GetCurSceneInfoScRsp", bytes, actor));
    }
    var frame = PacketCodec.encode(CmdId.GetCurSceneInfoScRsp, new byte[0], core);
    assertArrayEquals(frame, ProtocolBridge.forVersion("4.2.0").outboundFrames(frame, actor));
  }

  GameplayState.MissionRaidRun raid(int id) {
    activate(104010366);
    var ordinary = actor.current;
    activate(id);
    var current = actor.current;
    actor.current = ordinary;
    int main = goal.getMainMissionID();
    var raw =
        source.getAsJsonArray("raidRows").asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(
                r ->
                    r.getAsJsonArray("MainMissionIDList").asList().stream()
                        .anyMatch(v -> v.getAsInt() == main))
            .findFirst()
            .orElseThrow();
    var definition = gson.fromJson(raw, GameplayDefinitions.Raid.class);
    long key = definition.key();
    if (!oldRaids.containsKey(key)) oldRaids.put(key, GameplayDefinitions.raids.get(key));
    GameplayDefinitions.raids.put(key, definition);
    int parent = definition.MainMissionIDBefore();
    GameData.getMainMissionExcelMap()
        .add(gson.fromJson(row("raidParentRows", "MainMissionID", parent), MainMissionExcel.class));
    actor.getGameplay().getMainMissions().put(parent, new QuestState(parent));
    var run =
        new GameplayState.MissionRaidRun(
            definition.RaidID(),
            0,
            main,
            current.getPlaneId(),
            current.getFloorId(),
            current.getEntryId());
    run.returnFrom(actor);
    actor.getGameplay().beginMissionRaid(run);
    actor.current = current;
    return run;
  }

  @Test
  void original40404001RaidStateBelongsToItsRunAndMainAcceptanceAndRestores() throws Exception {
    var run = raid(204020313);
    assertTrue(
        new MissionRaid(actor).activeFor(goal.getMainMissionID()),
        "Original supported raid ownership fixture");
    actor.getGameplay().getSubMissions().clear();
    assertEquals(0, receive(request(goal.getParamStr1())));
    assertEquals(1, run.getPropTimelines().getRecords().size());
    assertTrue(actor.getGameplay().getPropTimelines().getRecords().isEmpty());
    String oldKey =
        actor.getGameplay().getMainMissions().get(goal.getMainMissionID()).getObservationKey();
    assertNotNull(oldKey);
    var document = gson.toJsonTree(actor.getGameplay()).getAsJsonObject();
    gameplay(document);
    rebuild();
    assertNotNull(prop().getTimelineInfo());
    actor.getGameplay().getSubMissions().put(goal.getId(), new QuestState(goal.getId()));
    actor.getMissionManager().refresh();
    assertEquals(QuestState.Phase.FINISHED, actor.getMissionManager().sub(goal.getId()).getPhase());
    var savedValue = goal.getFinishActionList()[0].getStrings();
    assertEquals(
        Integer.parseInt(savedValue[3]),
        actor.getGameplay().getStoryValues().get("floor:" + savedValue[1] + ":" + savedValue[2]));
    actor
        .getGameplay()
        .getMainMissions()
        .put(goal.getMainMissionID(), new QuestState(goal.getMainMissionID()));
    actor.getGameplay().getSubMissions().put(goal.getId(), new QuestState(goal.getId()));
    rebuild();
    assertNull(prop().getTimelineInfo());
    actor.getMissionManager().refresh();
    assertEquals(0, actor.getMissionManager().sub(goal.getId()).getProgress());
    assertEquals(0, receive(request(goal.getParamStr1())));
    assertNotEquals(
        oldKey,
        actor.getGameplay().getMainMissions().get(goal.getMainMissionID()).getObservationKey());
    assertEquals(1, actor.getMissionManager().sub(goal.getId()).getProgress());
  }

  @Test
  void aFailedRaidSnapshotSaveRollsBackItsNewMainAcceptanceKey() throws Exception {
    var run = raid(204020313);
    assertTrue(new MissionRaid(actor).activeFor(goal.getMainMissionID()));
    var main = actor.getGameplay().getMainMissions().get(goal.getMainMissionID());
    var old = run.getPropTimelines();
    assertNull(main.getObservationKey());
    actor.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), receive(request(goal.getParamStr1())));
    assertSame(old, run.getPropTimelines());
    assertNull(main.getObservationKey());
    assertNull(prop().getTimelineInfo());
    assertTrue(old.getRecords().isEmpty());
    actor.failSave = false;
    assertEquals(0, receive(request(goal.getParamStr1())));
  }

  @ParameterizedTest
  @ValueSource(ints = {104040816, 204020313})
  void originalRaidGoalsCannotBorrowAnOrdinaryWorldStateWithoutOwnedRaidContext(int id)
      throws Exception {
    activate(id);
    assertEquals(Retcode.MISSION_SCENE_NOT_MATCH.getVal(), receive(request(goal.getParamStr1())));
    assertEquals(0, actor.getMissionManager().sub(id).getProgress());
    assertTrue(actor.getGameplay().getPropTimelines().getRecords().isEmpty());
  }

  @Test
  void original40431DefaultTrialOrPlayerEntranceRemainsAnExplicitUnsupportedBoundary()
      throws Exception {
    raid(104040816);
    assertNull(MissionRaid.resource(40431, 0));
    assertEquals(Retcode.MISSION_SCENE_NOT_MATCH.getVal(), receive(request(goal.getParamStr1())));
    assertEquals(0, actor.getMissionManager().sub(goal.getId()).getProgress());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualPlayerDocumentRestoresWorldOrRaidTimelineThroughMorphia(boolean raidScope)
      throws Exception {
    if (raidScope) {
      raid(204020313);
      actor.getGameplay().getSubMissions().clear();
    } else activate(104010366);
    String reported = raidScope ? goal.getParamStr1() : "L1_Break";
    assertEquals(0, receive(request(reported)));
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "timeline-reload");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var actual = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(actual, 123457891);
      var field = Player.class.getDeclaredField("gameplay");
      field.setAccessible(true);
      field.set(actual, actor.getGameplay());
      store.save(actual);
      var loaded = store.find(Player.class).first();
      assertNotNull(loaded);
      field.set(actor, loaded.getGameplay());
      rebuild();
      assertNotNull(prop().getTimelineInfo());
      assertEquals(
          reported,
          new String(
              prop().getTimelineInfo().getCOOGIPJIPIA().toArray(), StandardCharsets.US_ASCII));
      if (raidScope)
        actor.getGameplay().getSubMissions().put(goal.getId(), new QuestState(goal.getId()));
      assertEquals(0, receive(request(goal.getParamStr1())));
      assertEquals(1, actor.getMissionManager().sub(goal.getId()).getProgress());
    } finally {
      server.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"tag", "context", "message-length", "state-length"})
  void oversizedVarintsCannotAliasValidContextTagsOrLengths(String alias) throws Exception {
    activate(104010366);
    var out = new java.io.ByteArrayOutputStream();
    var wire = CodedOutputStream.newInstance(out);
    wire.writeUInt64NoTag((6L << 3) + (alias.equals("tag") ? 0x100000000L : 0));
    wire.writeUInt32NoTag(prop().getEntityId());
    wire.writeTag(8, 0);
    if (alias.equals("context")) {
      long value = actor.current.getRuntimeId();
      for (int i = 0; i < 9; i++) {
        wire.writeRawByte((byte) ((value & 127) | 128));
        value >>>= 7;
      }
      wire.writeRawByte((byte) (value | 2));
    } else wire.writeUInt64NoTag(actor.current.getRuntimeId());
    byte[] value = goal.getParamStr1().getBytes(StandardCharsets.US_ASCII);
    var inner = new java.io.ByteArrayOutputStream();
    var iw = CodedOutputStream.newInstance(inner);
    iw.writeTag(3, 2);
    iw.writeUInt64NoTag(value.length + (alias.equals("state-length") ? 0x100000000L : 0));
    iw.writeRawBytes(value);
    iw.flush();
    wire.writeTag(9, 2);
    wire.writeUInt64NoTag(inner.size() + (alias.equals("message-length") ? 0x100000000L : 0));
    wire.writeRawBytes(inner.toByteArray());
    wire.flush();
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), receive(out.toByteArray()));
    assertEquals(0, actor.saves);
    assertTrue(actor.getGameplay().getPropTimelines().getRecords().isEmpty());
  }
}
