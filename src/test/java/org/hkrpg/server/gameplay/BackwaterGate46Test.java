package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original Backwater Pass consoles and iron gates; no live player or client is operated. */
class BackwaterGate46Test {
  static final String FLOOR = "P20111_F20111001";
  static final String GROUP = "Config/LevelOutput/SharedRuntimeGroup/Groups_" + FLOOR
      + "/LevelGroup_" + FLOOR + "_G21.json";
  final Gson gson = ResourceLoader.getGson();
  final Map<Integer, PropExcel> oldProps = new HashMap<>();
  final Map<Integer, InteractExcel> oldInteractions = new HashMap<>();
  JsonObject records;
  FloorInfo floor, oldFloor;
  MazePlaneExcel plane;
  Actor player;

  static class Actor extends Player {
    final LineupManager teams = new LineupManager(this);
    final List<BasePacket> packets = new ArrayList<>();
    Scene scene;
    boolean failSave;
    int saves;

    Actor() { teams.getCurrentLineup().getAvatars().add(0); }
    public LineupManager getLineupManager() { return teams; }
    public GameAvatar getAvatarById(int id) { return null; }
    public Scene getScene() { return scene; }
    public int getPlaneId() { return 20111; }
    public int getFloorId() { return 20111001; }
    public int getEntryId() { return 201110102; }
    public void save() {
      if (failSave) throw new IllegalStateException("isolated failed save");
      saves++;
    }
    public void sendPacket(BasePacket packet) { packets.add(packet); }
  }

  @BeforeEach void prepare() throws Exception {
    oldFloor = GameData.getFloorInfos().get(FLOOR);
    try (var reader = new InputStreamReader(getClass().getResourceAsStream(
        "/fixtures/backwater-gates-4.6.json"), StandardCharsets.UTF_8)) {
      records = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/MazeProp.json")) {
      var row = raw.getAsJsonObject().deepCopy();
      row.addProperty("PropName", row.getAsJsonObject("PropName").get("Hash").getAsLong());
      var prop = gson.fromJson(row, PropExcel.class);
      prop.onLoad();
      oldProps.put(prop.getId(), GameData.getPropExcelMap().get(prop.getId()));
      GameData.getPropExcelMap().add(prop);
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/InteractConfig.json")) {
      var row = gson.fromJson(raw, InteractExcel.class);
      oldInteractions.put(row.getId(), GameData.getInteractExcelMap().get(row.getId()));
      GameData.getInteractExcelMap().add(row);
    }
    plane = gson.fromJson(records.getAsJsonArray("ExcelOutput/MazePlane.json").get(0), MazePlaneExcel.class);
    freshFloor();
    player = new Actor();
    scene();
  }

  void freshFloor() {
    floor = gson.fromJson(records.get("Config/LevelOutput/RuntimeFloor/" + FLOOR + ".json"), FloorInfo.class);
    var group = gson.fromJson(records.get(GROUP), GroupInfo.class);
    group.setId(21);
    floor.getGroups().put(21, group);
    floor.onLoad();
    GameData.getFloorInfos().put(FLOOR, floor);
  }

  void scene() {
    player.scene = new Scene(player, plane, 20111001);
    player.scene.setEntryId(player.getEntryId());
  }

  void reload() throws Exception {
    var saved = gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class);
    player = new Actor();
    DailyMissionAcceptance46Test.set(player, "gameplay", saved);
    freshFloor();
    scene();
  }

  EntityProp prop(int instance) {
    return player.scene.getEntitiesByGroup(EntityProp.class, 21).stream()
        .filter(p -> p.getInstId() == instance).findFirst().orElseThrow();
  }

  ScenePropTransitions.Result open(int instance) {
    return ScenePropTransitions.interact(player, 1010, prop(instance).getEntityId());
  }

  @AfterEach void cleanup() {
    if (oldFloor == null) GameData.getFloorInfos().remove(FLOOR);
    else GameData.getFloorInfos().put(FLOOR, oldFloor);
    oldProps.forEach((id, old) -> {
      GameData.getPropExcelMap().keySet().remove(id);
      if (old != null) GameData.getPropExcelMap().add(old);
    });
    oldInteractions.forEach((id, old) -> {
      GameData.getInteractExcelMap().keySet().remove(id);
      if (old != null) GameData.getInteractExcelMap().add(old);
    });
  }

  @Test void loadingPreservesOriginalConsoleAndGateBindings() {
    for (int id : List.of(300008, 300003, 300004, 300001, 300002, 300005)) {
      var raw = records.getAsJsonObject(GROUP).getAsJsonArray("PropList").asList().stream()
          .map(JsonElement::getAsJsonObject).filter(p -> p.get("ID").getAsInt() == id)
          .findFirst().orElseThrow();
      assertEquals(raw.get("ValueSource"), gson.toJsonTree(prop(id).getPropInfo().getValueSource()),
          "Preserve original binding for " + id);
    }
  }

  @ParameterizedTest @ValueSource(ints = {300008, 300003})
  void ordinaryConsoleOpensOnlyItsOwnGateAndPersistsBoth(int console) throws Exception {
    int gate = console == 300008 ? 300001 : 300002;
    int other = gate == 300001 ? 300002 : 300001;
    player.scene.toProto();
    player.packets.clear();
    assertEquals(Retcode.SUCC, open(console).retcode());
    assertEquals(PropState.Open, prop(console).getState());
    assertEquals(PropState.Open, prop(gate).getState());
    assertEquals(PropState.Closed, prop(other).getState());
    assertEquals(PropState.Locked, prop(300004).getState());
    assertEquals(PropState.Locked, prop(300007).getState());
    assertEquals(2, player.getGameplay().getScenePropStates().getRecords().size());
    assertEquals(2, player.packets.stream().filter(p -> p.getCmdId() == CmdId.SceneGroupRefreshScNotify).count());
    assertTrue(player.getGameplay().getSubMissions().isEmpty(), "Opening a normal gate must not skip story");
    reload();
    assertEquals(PropState.Open, prop(console).getState());
    assertEquals(PropState.Open, prop(gate).getState());
    assertEquals(PropState.Closed, prop(other).getState());
    String saved = gson.toJson(player.getGameplay());
    assertNotEquals(Retcode.SUCC, open(console).retcode());
    assertEquals(0, player.saves);
    assertEquals(saved, gson.toJson(player.getGameplay()));
  }

  @Test void nativeInteractionRepliesWithOpenStateAndOpensTheGate() throws Exception {
    var protocol = ProtocolBridge.forVersion("4.6.0");
    var replies = new ArrayList<DynamicMessage>();
    var session = new GameSession(null) {
      public Player getPlayer() { return player; }
      public ProtocolBridge getProtocol() { return protocol; }
      public void sendClientPacket(String name, byte[] body) {
        try {
          if (name.equals("InteractPropScRsp")) replies.add(protocol.clientSchema().parse(name, body));
        } catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
      }
    };
    session.setState(SessionState.ACTIVE);
    var type = protocol.clientSchema().message("InteractPropCsReq");
    var request = DynamicMessage.newBuilder(type)
        .setField(type.findFieldByName("prop_entity_id"), prop(300003).getEntityId())
        .setField(type.findFieldByName("interact_id2"), 1010)
        .setField(type.findFieldByName("interact_id"), -1L).build();
    assertTrue(VersionedPacketHandler.handle(session, protocol.clientCommand("InteractPropCsReq"), request.toByteArray()));
    assertEquals(1, replies.size());
    var reply = replies.getFirst();
    assertEquals(0, reply.getField(reply.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(1, reply.getField(reply.getDescriptorForType().findFieldByName("prop_state")));
    assertEquals(PropState.Open, prop(300002).getState());
  }

  @Test void failedSaveRollsBackConsoleAndGateBeforePublishingAnything() {
    player.scene.toProto();
    player.packets.clear();
    var before = gson.toJson(player.getGameplay());
    player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, open(300003).retcode());
    assertEquals(PropState.Closed, prop(300003).getState());
    assertEquals(PropState.Closed, prop(300002).getState());
    assertEquals(before, gson.toJson(player.getGameplay()));
    assertTrue(player.packets.isEmpty());
  }

  @Test void directGateRequestCannotBypassItsController() {
    assertNotEquals(Retcode.SUCC, open(300002).retcode());
    assertEquals(PropState.Closed, prop(300002).getState());
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }

  @ParameterizedTest @ValueSource(strings = {"missing", "unknown", "duplicate", "wrong-type", "wrong-gate", "missing-actor"})
  void invalidBindingCannotPartiallyOpenTheConsole(String variant) {
    var info = prop(300003).getPropInfo();
    // Restore the raw binding so this regression reaches validation on the old loader too.
    var raw = records.getAsJsonObject(GROUP).getAsJsonArray("PropList").asList().stream()
        .map(JsonElement::getAsJsonObject).filter(p -> p.get("ID").getAsInt() == 300003)
        .findFirst().orElseThrow();
    info.setValueSource(gson.fromJson(raw.get("ValueSource"), PropInfo.PropValueSource.class));
    var values = info.getValueSource().getValues();
    switch (variant) {
      case "missing" -> info.setValueSource(null);
      case "unknown" -> values.getFirst().addProperty("Value", "21,399999");
      case "duplicate" -> values.add(values.getFirst().deepCopy());
      case "wrong-type" -> values.getFirst().addProperty("$type", "RPG.GameCore.SharedInt");
      case "wrong-gate" -> values.getFirst().addProperty("Value", "21,300001");
      case "missing-actor" -> player.scene.getEntities().remove(prop(300002).getEntityId());
    }
    assertNotEquals(Retcode.SUCC, open(300003).retcode());
    assertEquals(PropState.Closed, prop(300003).getState());
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }

  @Test void oldUnpairedControllerReceiptAllowsRetryWithoutOpeningTheGateOnLogin() throws Exception {
    var original = prop(300003).getPropInfo();
    var old = gson.fromJson(gson.toJson(original), PropInfo.class);
    old.setValueSource(null);
    player.getGameplay().scenePropStates(player.getGameplay().getScenePropStates().with(List.of(
        new ScenePropState.Snapshot(20111, 20111001, 0, 0, 21, 300003, 101001,
            PropState.Open.getVal(), PropTimelines.source(old), ""))));
    reload();
    assertEquals(PropState.Closed, prop(300003).getState());
    assertEquals(PropState.Closed, prop(300002).getState());
    assertEquals(Retcode.SUCC, open(300003).retcode());
    assertEquals(PropState.Open, prop(300002).getState());
    assertEquals(2, player.getGameplay().getScenePropStates().getRecords().size());
  }

  @Test void laterLockedConsoleRemainsLockedAndItsDoorRemainsClosed() {
    assertEquals(PropState.Locked, prop(300004).getState());
    assertNotEquals(Retcode.SUCC, open(300004).retcode());
    assertNotEquals(Retcode.SUCC, ScenePropTransitions.interact(player, 1200, prop(300004).getEntityId()).retcode());
    assertEquals(PropState.Closed, prop(300005).getState());
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }

  @Test void retainingGateBindingsDoesNotInvalidateUncontrolledLegacyGateProgress() {
    // Other floors also use IronGate_06, without the newly recognized simple controller.
    floor.getGroups().get(21).getPropList().removeIf(p -> p.getID() == 300003);
    var old = gson.fromJson(gson.toJson(prop(300002).getPropInfo()), PropInfo.class);
    old.setValueSource(null);
    player.getGameplay().scenePropStates(player.getGameplay().getScenePropStates().with(List.of(
        new ScenePropState.Snapshot(20111, 20111001, 0, 0, 21, 300002, 101004,
            PropState.Open.getVal(), PropTimelines.source(old), ""))));
    scene();
    assertEquals(PropState.Open, prop(300002).getState());
  }
}
