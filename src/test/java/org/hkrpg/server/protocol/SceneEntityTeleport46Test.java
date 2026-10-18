package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.UnknownFieldSet;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.data.excel.SpecialAvatarExcel;
import emu.lunarcore.game.trial.TrialAvatar;
import org.hkrpg.server.gameplay.TrialSkillPresets;
import org.hkrpg.server.gameplay.SceneTeleportLocation;
import java.util.Map;
import emu.lunarcore.data.excel.MapEntranceExcel;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.proto.GetCurSceneInfoScRspOuterClass.GetCurSceneInfoScRsp;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.SessionState;
import emu.lunarcore.util.Position;
import emu.lunarcore.server.packet.send.PacketSceneEntityMoveScNotify;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import io.netty.buffer.Unpooled;
import emu.lunarcore.server.packet.PacketCodec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native teleport changes the current actor location, never the scene or movement lifecycle. */
class SceneEntityTeleport46Test {
  static final int PLANE = 199984, FLOOR = 19998401, ENTRY = 1999841, AVATAR = 199984;
  final Gson gson = new Gson();
  final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
  FloorInfo oldFloor;
  MazePlaneExcel oldPlane;
  MapEntranceExcel oldEntry;
  Actor actor;
  GameAvatar avatar;
  Session session;

  static class Actor extends Player {
    final PlayerLineup team = new PlayerLineup(this, 0);
    final List<BasePacket> sent = new ArrayList<>();
    Scene current;
    int saves, moves, transitions;

    @Override public PlayerLineup getCurrentLineup() { return team; }
    @Override public Scene getScene() { return current; }
    @Override public void save() { saves++; }
    @Override public void onMove() { moves++; }
    @Override public void sendPacket(BasePacket packet) { sent.add(packet); }
    @Override public boolean enterScene(int entry, int teleport, boolean send) {
      transitions++;
      return false;
    }
  }

  static class Session extends GameSession {
    final Actor owner;
    ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
    final List<DynamicMessage> replies = new ArrayList<>();
    final List<String> names = new ArrayList<>();
    final List<BasePacket> inherited = new ArrayList<>();

    Session(Actor owner) {
      super(null);
      this.owner = owner;
      owner.setSession(this);
      setState(SessionState.ACTIVE);
    }
    @Override public Player getPlayer() { return owner; }
    @Override public ProtocolBridge getProtocol() { return protocol; }
    @Override public void send(BasePacket packet) { inherited.add(packet); }
    @Override public void sendClientPacket(String name, byte[] bytes) {
      try {
        names.add(name);
        replies.add(protocol.clientSchema().parse(name, bytes));
      } catch (Exception error) {
        throw new AssertionError(error);
      }
    }
  }

  @BeforeEach
  void prepare() throws Exception {
    oldFloor = GameData.getFloorInfos().put("P" + PLANE + "_F" + FLOOR,
        gson.fromJson("{\"FloorID\":" + FLOOR + ",\"GroupInstanceList\":[],\"LayerToAreaMask\":[1,2,4]}", FloorInfo.class));
    oldPlane = GameData.getMazePlaneExcelMap().get(PLANE);
    oldEntry = GameData.getMapEntranceExcelMap().get(ENTRY);
    GameData.getMazePlaneExcelMap().add(gson.fromJson(
        "{\"PlaneID\":" + PLANE + ",\"PlaneType\":\"Town\"}", MazePlaneExcel.class));
    GameData.getMapEntranceExcelMap().add(gson.fromJson(
        "{\"ID\":" + ENTRY + ",\"PlaneID\":" + PLANE + ",\"FloorID\":" + FLOOR + "}",
        MapEntranceExcel.class));
    actor = new Actor();
    actor.resetPosition();
    field(actor, "planeId", PLANE);
    field(actor, "floorId", FLOOR);
    field(actor, "entryId", ENTRY);
    actor.getPos().set(100, 200, 300);
    actor.getRot().set(10, 20, 30);
    avatar = new GameAvatar(gson.fromJson("{\"AvatarID\":" + AVATAR + "}", AvatarExcel.class));
    avatar.setOwner(actor);
    actor.getAvatars().getAvatars().put(AVATAR, avatar);
    actor.team.getAvatars().add(AVATAR);
    actor.current = new Scene(actor, GameData.getMazePlaneExcelMap().get(PLANE), FLOOR);
    actor.current.setEntryId(ENTRY);
    field(actor, "scene", actor.current);
    session = new Session(actor);
  }

  @AfterEach
  void restore() {
    if (oldFloor == null) GameData.getFloorInfos().remove("P" + PLANE + "_F" + FLOOR);
    else GameData.getFloorInfos().put("P" + PLANE + "_F" + FLOOR, oldFloor);
    GameData.getMazePlaneExcelMap().keySet().remove(PLANE);
    if (oldPlane != null) GameData.getMazePlaneExcelMap().add(oldPlane);
    GameData.getMapEntranceExcelMap().keySet().remove(ENTRY);
    if (oldEntry != null) GameData.getMapEntranceExcelMap().add(oldEntry);
  }

  static void field(Player player, String name, Object value) throws Exception {
    var field = Player.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(player, value);
  }

  DynamicMessage message(String name, Object... values) {
    var builder = DynamicMessage.newBuilder(bridge.clientSchema().message(name));
    for (int i = 0; i < values.length; i += 2)
      Gameplay46.set(builder, (String) values[i], values[i + 1]);
    return builder.build();
  }

  DynamicMessage vector(int x, int y, int z) {
    return message("Vector", "x", x, "y", y, "z", z);
  }

  DynamicMessage request() {
    return message("SceneEntityTeleportCsReq", "entry_id", ENTRY,
        "interact_id", actor.current.getRuntimeId(), "entity_motion",
        message("EntityMotion", "entity_id", avatar.getEntityId(), "motion",
            message("MotionInfo", "pos", vector(-1234, 567, -890), "rot", vector(-45, 90, 135))));
  }

  static DynamicMessage nested(DynamicMessage owner, String name) {
    return (DynamicMessage) owner.getField(owner.getDescriptorForType().findFieldByName(name));
  }

  DynamicMessage with(DynamicMessage original, String name, Object value) {
    var builder = original.toBuilder();
    Gameplay46.set(builder, name, value);
    return builder.build();
  }

  DynamicMessage without(DynamicMessage original, String name) {
    return original.toBuilder().clearField(original.getDescriptorForType().findFieldByName(name)).build();
  }

  DynamicMessage nativeScene() throws Exception {
    var core = GetCurSceneInfoScRsp.newInstance().setScene(actor.current.toProto()).toByteArray();
    var wire = bridge.outbound(CmdId.GetCurSceneInfoScRsp, core);
    var enriched = Scene46.enrich(bridge.clientSchema(), "GetCurSceneInfoScRsp", wire.body(), actor);
    return nested(bridge.clientSchema().parse("GetCurSceneInfoScRsp", enriched), "scene");
  }

  DynamicMessage route(DynamicMessage request) throws Exception {
    assertEquals(1491, bridge.clientCommand("SceneEntityTeleportCsReq"));
    assertEquals(1487, bridge.clientCommand("SceneEntityTeleportScRsp"));
    return routeBytes(request.toByteArray());
  }

  DynamicMessage routeBytes(byte[] bytes) throws Exception {
    assertTrue(VersionedPacketHandler.handle(session, 1491, bytes),
        "The observed native 1491 must have an audited handler rather than be discarded");
    assertEquals(List.of("SceneEntityTeleportScRsp"), session.names);
    return session.replies.getFirst();
  }

  void noUnrelatedEffects() {
    assertEquals(0, actor.saves, "Teleport keeps ordinary movement's existing save lifecycle");
    assertEquals(0, actor.moves, "Teleport must not trigger anchor healing through onMove");
    assertEquals(0, actor.transitions, "This contract only moves within the current scene");
    assertTrue(actor.sent.isEmpty(), "No extra SceneEntityMoveScNotify may precede the response");
    assertTrue(session.inherited.isEmpty(), "No legacy response or extra notification is expected");
  }

  void rejected(DynamicMessage request) throws Exception {
    var beforePos = new Position(actor.getPos());
    var beforeRot = new Position(actor.getRot());
    var beforeScene = actor.current;
    var response = route(request);
    assertNotEquals(0, Gameplay46.number(response, "retcode"));
    assertEquals(beforePos, actor.getPos());
    assertEquals(beforeRot, actor.getRot());
    assertSame(beforeScene, actor.current);
    noUnrelatedEffects();
  }

  @Test
  void fixtureHasActualOwnedSceneActorAndNativeRuntimeContext() throws Exception {
    assertSame(avatar, actor.current.getAvatarByEntityId(avatar.getEntityId()));
    assertSame(actor.getPos(), avatar.getPos());
    assertSame(actor.getRot(), avatar.getRot());
    assertEquals(actor.current.getRuntimeId(), Gameplay46.value(nativeScene(), "interact_id"));
    assertEquals(ENTRY, Gameplay46.number(nativeScene(), "entry_id"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void acceptedMotionUpdatesAllAxesAndTheActualSceneSnapshot(boolean origin) throws Exception {
    var request = request();
    if (origin) request = with(request, "entity_motion", with(nested(request, "entity_motion"), "motion",
        message("MotionInfo", "pos", vector(0, 0, 0), "rot", vector(0, 0, 0))));
    int sceneVersion = Gameplay46.number(nativeScene(), "client_pos_version");
    var response = route(request);
    assertEquals(0, Gameplay46.number(response, "retcode"));
    assertEquals(sceneVersion + 1, Gameplay46.number(response, "client_pos_version"),
        "A successful teleport advances the server-owned scene position revision");
    assertEquals(Gameplay46.number(nativeScene(), "client_pos_version"),
        Gameplay46.number(response, "client_pos_version"));
    assertEquals(nested(request, "entity_motion"), nested(response, "entity_motion"));
    assertEquals(origin ? new Position() : new Position(-1234, 567, -890), actor.getPos());
    assertEquals(origin ? new Position() : new Position(-45, 90, 135), actor.getRot());
    var actual = avatar.toSceneEntityProto().getMotion();
    assertEquals(actor.getPos(), new Position(actual.getPos()));
    assertEquals(actor.getRot(), new Position(actual.getRot()));
    assertEquals(ENTRY, actor.getEntryId());
    noUnrelatedEffects();
  }

  @ParameterizedTest
  @ValueSource(strings = {"entry-zero", "entry-other", "runtime-zero", "runtime-other", "entity-zero", "entity-other"})
  void invalidSceneOrEntityIdentityDoesNotMoveThePlayer(String mode) throws Exception {
    var request = request();
    request = switch (mode) {
      case "entry-zero" -> with(request, "entry_id", 0);
      case "entry-other" -> with(request, "entry_id", ENTRY + 1);
      case "runtime-zero" -> with(request, "interact_id", 0L);
      case "runtime-other" -> with(request, "interact_id", actor.current.getRuntimeId() + 1);
      case "entity-zero" -> with(request, "entity_motion", with(nested(request, "entity_motion"), "entity_id", 0));
      default -> with(request, "entity_motion", with(nested(request, "entity_motion"), "entity_id", avatar.getEntityId() + 1));
    };
    rejected(request);
  }

  @Test
  void previousSceneRuntimeCannotMoveAnEntityIdReusedInTheSameEntry() throws Exception {
    var stale = request();
    int previousEntity = avatar.getEntityId();
    long previousRuntime = actor.current.getRuntimeId();
    actor.current = new Scene(actor, GameData.getMazePlaneExcelMap().get(PLANE), FLOOR);
    actor.current.setEntryId(ENTRY);
    assertEquals(previousEntity, avatar.getEntityId());
    assertNotEquals(previousRuntime, actor.current.getRuntimeId());
    rejected(stale);
  }

  @ParameterizedTest
  @ValueSource(strings = {"cache-only", "replaced-object", "left-lineup"})
  void cachedOrDetachedActorIdentitiesAreNotOwnership(String mode) throws Exception {
    var request = request();
    if (mode.equals("cache-only")) actor.current.getAvatars().clear();
    else if (mode.equals("replaced-object")) {
      var replacement = new GameAvatar(avatar.getExcel());
      replacement.setOwner(actor);
      actor.getAvatars().getAvatars().put(AVATAR, replacement);
    } else actor.team.getAvatars().clear();
    rejected(request);
  }

  @ParameterizedTest
  @ValueSource(strings = {"entity_motion", "motion", "pos", "rot"})
  void missingMotionMessagesCannotSilentlyBecomeZeroCoordinates(String missing) throws Exception {
    var request = request();
    if (missing.equals("entity_motion")) request = without(request, missing);
    else {
      var entity = nested(request, "entity_motion");
      entity = missing.equals("motion") ? without(entity, missing)
          : with(entity, "motion", without(nested(entity, "motion"), missing));
      request = with(request, "entity_motion", entity);
    }
    rejected(request);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "entry", "plane", "floor"})
  void playerAndSceneMustDescribeTheSameCurrentLocation(String mismatch) throws Exception {
    var request = request();
    if (mismatch.equals("missing")) actor.current = null;
    else if (mismatch.equals("entry")) actor.current.setEntryId(ENTRY + 1);
    else field(actor, mismatch.equals("plane") ? "planeId" : "floorId", 1);
    rejected(request);
  }

  @Test
  void unknownRequestFieldIsRejectedWithoutAcknowledgingAMove() throws Exception {
    var extra = UnknownFieldSet.newBuilder().addField(222,
        UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build();
    rejected(request().toBuilder().setUnknownFields(extra).build());
  }

  @ParameterizedTest
  @ValueSource(strings = {"WAITING_FOR_TOKEN", "INACTIVE"})
  void inactiveSessionsDoNotMoveOrReceiveSuccess(String state) throws Exception {
    session.setState(SessionState.valueOf(state));
    assertTrue(VersionedPacketHandler.handle(session, 1491, request().toByteArray()));
    assertEquals(new Position(100, 200, 300), actor.getPos());
    assertEquals(new Position(10, 20, 30), actor.getRot());
    assertTrue(session.replies.isEmpty());
    noUnrelatedEffects();
  }

  @Test
  void aFullWidthRuntimeContextIsNotNarrowedToUInt32() throws Exception {
    actor.current = new Scene(actor, GameData.getMazePlaneExcelMap().get(PLANE), FLOOR) {
      @Override public long getRuntimeId() { return (1L << 40) + 123; }
    };
    actor.current.setEntryId(ENTRY);
    assertEquals((1L << 40) + 123, Gameplay46.value(nativeScene(), "interact_id"));
    assertEquals(0, Gameplay46.number(route(request()), "retcode"));
    assertEquals(new Position(-1234, 567, -890), actor.getPos());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  void validDeclaredMapLayerIsStoredAndZeroLeavesItUnchanged(int layer) throws Exception {
    var first = request();
    first = with(first, "entity_motion", with(nested(first, "entity_motion"), "map_layer", layer));
    assertEquals(0, Gameplay46.number(route(first), "retcode"));
    assertEquals(layer, actor.current.getTeleportMapLayer());
    assertEquals(layer, publishedLeaderLayer());
    session.names.clear();
    session.replies.clear();
    assertEquals(0, Gameplay46.number(route(request()), "retcode"));
    assertEquals(layer, actor.current.getTeleportMapLayer(), "Map layer zero means unchanged");
    assertEquals(layer, publishedLeaderLayer());
    noUnrelatedEffects();
  }

  int publishedLeaderLayer() throws Exception {
    for (var group : (List<?>) Gameplay46.value(nativeScene(), "entity_group_list")) {
      for (var raw : (List<?>) Gameplay46.value((DynamicMessage) group, "entity_list")) {
        var row = (DynamicMessage) raw;
        if (Gameplay46.number(row, "entity_id") == avatar.getEntityId())
          return Gameplay46.number(nested(row, "actor"), "map_layer");
      }
    }
    fail("Current actor disappeared from the native scene snapshot");
    return -1;
  }

  @ParameterizedTest
  @ValueSource(ints = {4, -1})
  void undeclaredMapLayersCannotMoveThePlayer(int layer) throws Exception {
    var request = request();
    rejected(with(request, "entity_motion", with(nested(request, "entity_motion"), "map_layer", layer)));
    assertEquals(1, actor.current.getTeleportMapLayer());
    assertEquals(0, actor.current.getClientPosVersion());
  }

  @Test
  void unsupportedMotionFlagDoesNotAcknowledgeATeleport() throws Exception {
    var request = request();
    rejected(with(request, "entity_motion", with(nested(request, "entity_motion"), "FJOEOEDLNED", true)));
  }

  void acknowledge(int revision) throws Exception {
    var ack = message("SceneUpdatePositionVersionNotify", "PLNEOOCPFNI", revision);
    int before = session.replies.size();
    assertTrue(VersionedPacketHandler.handle(session, 1439, ack.toByteArray()));
    assertEquals(before, session.replies.size(), "1439 is a notification, not a new request/reply pair");
  }

  @Test
  void acknowledgementsOnlyConfirmTheCurrentAuthorityAndCannotRollItBackOrAdvanceIt() throws Exception {
    assertEquals(0, actor.current.getClientPosVersion());
    assertEquals(0, actor.current.getAcknowledgedClientPosVersion());
    assertEquals(0, Gameplay46.number(route(request()), "retcode"));
    acknowledge(1);
    assertEquals(1, actor.current.getAcknowledgedClientPosVersion());
    session.names.clear();
    session.replies.clear();
    assertEquals(0, Gameplay46.number(route(request()), "retcode"));
    for (int staleOrFuture : new int[] {0, 1, 3, -1}) {
      acknowledge(staleOrFuture);
      assertEquals(2, actor.current.getClientPosVersion());
      assertEquals(1, actor.current.getAcknowledgedClientPosVersion());
    }
    acknowledge(2);
    assertEquals(2, actor.current.getClientPosVersion());
    assertEquals(2, actor.current.getAcknowledgedClientPosVersion());
    noUnrelatedEffects();
  }

  @Test
  void currentMoveNotificationAndSceneSnapshotPublishTheSameAuthority() throws Exception {
    route(request());
    var packet = new PacketSceneEntityMoveScNotify(actor);
    var input = Unpooled.wrappedBuffer(bridge.outboundFrames(packet.build(), actor));
    try {
      var frame = PacketCodec.decodeNext(input);
      assertEquals(1442, frame.cmdId());
      var notification = bridge.clientSchema().parse("SceneEntityMoveScNotify", frame.body());
      assertEquals(ENTRY, Gameplay46.number(notification, "entry_id"));
      assertEquals(actor.current.getClientPosVersion(), Gameplay46.number(notification, "client_pos_version"));
      assertEquals(Gameplay46.number(nativeScene(), "client_pos_version"),
          Gameplay46.number(notification, "client_pos_version"));
      assertFalse(input.isReadable());
    } finally {
      input.release();
    }
    noUnrelatedEffects();
  }

  static byte[] appendUnsigned(byte[] prefix, int field, BigInteger value) throws Exception {
    var output = new ByteArrayOutputStream();
    output.write(prefix);
    var coded = CodedOutputStream.newInstance(output);
    coded.writeTag(field, 0);
    coded.flush();
    do {
      int next = value.and(BigInteger.valueOf(127)).intValue();
      value = value.shiftRight(7);
      output.write(next | (value.signum() == 0 ? 0 : 128));
    } while (value.signum() != 0);
    return output.toByteArray();
  }

  byte[] messageBytes(DynamicMessage original, String field, byte[] nested) throws Exception {
    var output = new ByteArrayOutputStream();
    output.write(without(original, field).toByteArray());
    var coded = CodedOutputStream.newInstance(output);
    coded.writeByteArray(original.getDescriptorForType().findFieldByName(field).getNumber(), nested);
    coded.flush();
    return output.toByteArray();
  }

  @ParameterizedTest
  @ValueSource(strings = {"entry-overflow", "entity-overflow", "runtime-overflow", "coordinate-overflow",
      "duplicate-entry", "duplicate-entity", "duplicate-coordinate", "bool-two", "nested-unknown"})
  void wireAliasesAndNestedUnknownFieldsCannotBypassSemanticChecks(String kind) throws Exception {
    var request = request();
    var entity = nested(request, "entity_motion");
    byte[] bytes;
    if (kind.equals("entry-overflow")) {
      bytes = appendUnsigned(without(request, "entry_id").toByteArray(), 9,
          BigInteger.ONE.shiftLeft(32).add(BigInteger.valueOf(ENTRY)));
    } else if (kind.equals("runtime-overflow")) {
      bytes = appendUnsigned(without(request, "interact_id").toByteArray(), 10,
          BigInteger.ONE.shiftLeft(64).add(BigInteger.valueOf(actor.current.getRuntimeId())));
    } else if (kind.equals("duplicate-entry")) {
      bytes = appendUnsigned(request.toByteArray(), 9, BigInteger.valueOf(ENTRY));
    } else if (kind.equals("entity-overflow")) {
      bytes = messageBytes(request, "entity_motion", appendUnsigned(without(entity, "entity_id").toByteArray(),
          2, BigInteger.ONE.shiftLeft(32).add(BigInteger.valueOf(avatar.getEntityId()))));
    } else if (kind.equals("duplicate-entity")) {
      bytes = messageBytes(request, "entity_motion", appendUnsigned(entity.toByteArray(),
          2, BigInteger.valueOf(avatar.getEntityId())));
    } else if (kind.equals("bool-two")) {
      bytes = messageBytes(request, "entity_motion", appendUnsigned(entity.toByteArray(), 7, BigInteger.TWO));
    } else {
      var motion = nested(entity, "motion");
      var pos = nested(motion, "pos");
      byte[] rawPos = switch (kind) {
        case "coordinate-overflow" -> appendUnsigned(without(pos, "x").toByteArray(), 1, BigInteger.ONE.shiftLeft(32));
        case "duplicate-coordinate" -> appendUnsigned(pos.toByteArray(), 1, BigInteger.valueOf(2467));
        default -> appendUnsigned(pos.toByteArray(), 222, BigInteger.ONE);
      };
      bytes = messageBytes(request, "entity_motion", messageBytes(entity, "motion", messageBytes(motion, "pos", rawPos)));
    }
    var response = routeBytes(bytes);
    assertNotEquals(0, Gameplay46.number(response, "retcode"));
    assertEquals(new Position(100, 200, 300), actor.getPos());
    assertEquals(new Position(10, 20, 30), actor.getRot());
    assertEquals(0, actor.current.getClientPosVersion());
    assertEquals(1, actor.current.getTeleportMapLayer());
    noUnrelatedEffects();
  }

  @Test
  void anActualIndependentTrialAvatarUsesTheOwnersLocationWithoutEnteringTheFormalStore() throws Exception {
    int special = AVATAR + 1000;
    var config = gson.fromJson("{\"SpecialAvatarID\":" + special + ",\"AvatarID\":" + AVATAR
        + ",\"Level\":1}", SpecialAvatarExcel.class);
    var trial = TrialAvatar.restore(actor, config, avatar.getExcel(), TrialSkillPresets.legacy(), Map.of());
    actor.getAvatars().getTempAvatars().put(special, trial);
    actor.team.getAvatars().clear();
    actor.team.getAvatars().add(special);
    avatar = trial;
    rebuildScene();
    actor.sent.clear();
    assertSame(trial, actor.current.getAvatarByEntityId(trial.getEntityId()));
    assertNull(actor.getAvatars().getAvatars().get(special));
    assertEquals(0, Gameplay46.number(route(request()), "retcode"));
    assertEquals(new Position(-1234, 567, -890), trial.getPos());
    assertEquals(new Position(-45, 90, 135), trial.getRot());
    assertNull(actor.getAvatars().getAvatars().get(special));
    noUnrelatedEffects();
  }

  @Test
  void revisionExhaustionRejectsTheEntireMoveWithoutWrappingOrChangingLayer() throws Exception {
    var revision = Scene.class.getDeclaredField("clientPosVersion");
    revision.setAccessible(true);
    revision.setInt(actor.current, -1);
    var request = request();
    request = with(request, "entity_motion", with(nested(request, "entity_motion"), "map_layer", 2));
    rejected(request);
    assertEquals(-1, actor.current.getClientPosVersion());
    assertEquals(0, actor.current.getAcknowledgedClientPosVersion());
    assertEquals(1, actor.current.getTeleportMapLayer());
    assertThrows(IllegalStateException.class, actor.current::advanceClientPosVersion);
    assertEquals(-1, actor.current.getClientPosVersion());
  }

  void teleportToLayerTwo() throws Exception {
    var request = request();
    request = with(request, "entity_motion", with(nested(request, "entity_motion"), "map_layer", 2));
    assertEquals(0, Gameplay46.number(route(request), "retcode"));
    assertEquals(2, actor.current.getTeleportMapLayer());
  }

  void rebuildScene() throws Exception {
    actor.current = new Scene(actor, GameData.getMazePlaneExcelMap().get(PLANE), FLOOR);
    actor.current.setEntryId(ENTRY);
    field(actor, "scene", actor.current);
  }

  void restoreSerializedLayerSnapshot() {
    actor.setSceneTeleportLocation(gson.fromJson(gson.toJson(actor.getSceneTeleportLocation()),
        SceneTeleportLocation.class));
  }

  @Test
  void ordinaryMovementIsIncludedInTheExistingLocationRecordBeforeSceneRebuild() throws Exception {
    teleportToLayerTwo();
    actor.getPos().set(-1500, 570, -950);
    actor.getRot().set(-46, 91, 136);
    SceneTeleportLocation.record(actor);
    restoreSerializedLayerSnapshot();
    rebuildScene();
    assertEquals(2, actor.current.getTeleportMapLayer());
    assertEquals(2, publishedLeaderLayer());
    assertEquals(new Position(-1500, 570, -950), actor.getPos());
    noUnrelatedEffects();
  }

  @Test
  void restoredLayerRemainsAuthoritativeAfterMovingAwayFromTheSavedCoordinate() throws Exception {
    teleportToLayerTwo();
    restoreSerializedLayerSnapshot();
    rebuildScene();
    assertEquals(2, actor.current.getTeleportMapLayer(), "The saved layer must restore first");
    actor.getPos().set(-1600, 580, -1000);
    actor.getRot().set(-47, 92, 137);
    SceneTeleportLocation.record(actor);
    restoreSerializedLayerSnapshot();
    rebuildScene();
    assertEquals(2, actor.current.getTeleportMapLayer(),
        "Ordinary movement after reloading a scene must not silently revert its restored layer");
    assertEquals(2, publishedLeaderLayer());
    noUnrelatedEffects();
  }

  @ParameterizedTest
  @ValueSource(strings = {"entry", "position", "rotation"})
  void aStoredLayerDoesNotLeakIntoAnotherSceneLocation(String mismatch) throws Exception {
    teleportToLayerTwo();
    restoreSerializedLayerSnapshot();
    if (mismatch.equals("position")) actor.getPos().set(1, 2, 3);
    if (mismatch.equals("rotation")) actor.getRot().set(1, 2, 3);
    rebuildScene();
    if (mismatch.equals("entry")) actor.current.setEntryId(ENTRY + 1);
    assertEquals(1, actor.current.getTeleportMapLayer());
    noUnrelatedEffects();
  }

  @Test
  void failedSceneTransactionCanRestoreBothSavedAndLiveLayerWithItsPosition() throws Exception {
    teleportToLayerTwo();
    var originalPos = new Position(actor.getPos());
    var originalRot = new Position(actor.getRot());
    var originalSnapshot = actor.getSceneTeleportLocation();
    var before = actor.captureSceneLocation();
    actor.getPos().set(4, 5, 6);
    actor.getRot().set(7, 8, 9);
    actor.current.setTeleportMapLayer(3);
    SceneTeleportLocation.record(actor);
    assertNotSame(originalSnapshot, actor.getSceneTeleportLocation());
    actor.restoreSceneLocation(before);
    assertEquals(originalPos, actor.getPos());
    assertEquals(originalRot, actor.getRot());
    assertSame(originalSnapshot, actor.getSceneTeleportLocation());
    assertEquals(2, actor.current.getTeleportMapLayer());
    noUnrelatedEffects();
  }

  static byte[] unsignedBytes(BigInteger value) {
    var output = new ByteArrayOutputStream();
    do {
      int next = value.and(BigInteger.valueOf(127)).intValue();
      value = value.shiftRight(7);
      output.write(next | (value.signum() == 0 ? 0 : 128));
    } while (value.signum() != 0);
    return output.toByteArray();
  }

  @ParameterizedTest
  @ValueSource(strings = {"request-tag", "vector-tag", "message-length"})
  void overwideTagsAndLengthsCannotAliasAValidRequest(String kind) throws Exception {
    var request = request();
    var output = new ByteArrayOutputStream();
    byte[] bytes;
    if (kind.equals("request-tag")) {
      output.write(without(request, "entry_id").toByteArray());
      output.write(unsignedBytes(BigInteger.ONE.shiftLeft(32).add(BigInteger.valueOf(9 << 3))));
      output.write(unsignedBytes(BigInteger.valueOf(ENTRY)));
      bytes = output.toByteArray();
    } else if (kind.equals("message-length")) {
      byte[] entity = nested(request, "entity_motion").toByteArray();
      output.write(without(request, "entity_motion").toByteArray());
      output.write(unsignedBytes(BigInteger.valueOf((3 << 3) | 2)));
      output.write(unsignedBytes(BigInteger.ONE.shiftLeft(32).add(BigInteger.valueOf(entity.length))));
      output.write(entity);
      bytes = output.toByteArray();
    } else {
      var entity = nested(request, "entity_motion");
      var motion = nested(entity, "motion");
      output.write(without(nested(motion, "pos"), "x").toByteArray());
      output.write(unsignedBytes(BigInteger.ONE.shiftLeft(32).add(BigInteger.valueOf(1 << 3))));
      output.write(unsignedBytes(BigInteger.valueOf(2467))); // sint32 encoding of -1234
      bytes = messageBytes(request, "entity_motion", messageBytes(entity, "motion",
          messageBytes(motion, "pos", output.toByteArray())));
    }
    var response = routeBytes(bytes);
    assertNotEquals(0, Gameplay46.number(response, "retcode"),
        "Oversized protobuf tags/lengths must not truncate into a successful teleport");
    assertEquals(new Position(100, 200, 300), actor.getPos());
    assertEquals(new Position(10, 20, 30), actor.getRot());
    assertEquals(0, actor.current.getClientPosVersion());
    assertEquals(1, actor.current.getTeleportMapLayer());
    noUnrelatedEffects();
  }

  @Test
  void nativeHandlerDoesNotChangeTheLegacyProtocolPath() throws Exception {
    var bytes = request().toByteArray();
    session.protocol = ProtocolBridge.forVersion("4.2.0");
    assertFalse(VersionedPacketHandler.handle(session, 1491, bytes));
    assertArrayEquals(bytes, session.protocol.inbound(1491, bytes).body());
    assertEquals(new Position(100, 200, 300), actor.getPos());
    assertTrue(session.replies.isEmpty());
    noUnrelatedEffects();
  }
}
