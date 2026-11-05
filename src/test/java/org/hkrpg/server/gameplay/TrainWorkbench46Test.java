package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.excel.InteractExcel;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.data.excel.PlayerLevelExcel;
import emu.lunarcore.data.excel.PropExcel;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.proto.PlayerSyncScNotifyOuterClass.PlayerSyncScNotify;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.hkrpg.server.protocol.VersionedPacketHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrainWorkbench46Test {
  private static final String FLOOR = "P10000_F10000000";
  private static final String GROUP =
      "Config/LevelOutput/SharedRuntimeGroup/Groups_" + FLOOR + "/LevelGroup_" + FLOOR + "_G30.json";
  private static final int UNLOCK_MISSION = 1010403;
  private static final String OLD_STATE = "prop-state:10000:10000000:30:300002";
  private final Map<Integer, PropExcel> oldProps = new HashMap<>();
  private final Map<Integer, InteractExcel> oldInteractions = new HashMap<>();
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final Actor player = new Actor();
  private final Session session = new Session(protocol);
  private FloorInfo oldFloor;
  private MazePlaneExcel plane;

  private static final class Actor extends Player {
    final LineupManager teams = new LineupManager(this);
    final List<BasePacket> packets = new ArrayList<>();
    Scene scene;
    int saves;

    Actor() { teams.getCurrentLineup().getAvatars().add(0); }
    @Override public LineupManager getLineupManager() { return teams; }
    @Override public Scene getScene() { return scene; }
    @Override public int getPlaneId() { return 10000; }
    @Override public int getFloorId() { return 10000000; }
    @Override public int getEntryId() { return 1000001; }
    @Override public void onSync(PlayerSyncScNotify notify) {}
    @Override public void save() { saves++; }
    @Override public void sendPacket(BasePacket packet) { packets.add(packet); }
  }

  private final class Session extends GameSession {
    final ProtocolBridge bridge;
    DynamicMessage reply;

    Session(ProtocolBridge bridge) {
      super(null);
      this.bridge = bridge;
      setState(SessionState.ACTIVE);
    }

    @Override public Player getPlayer() { return player; }
    @Override public ProtocolBridge getProtocol() { return bridge; }
    @Override public void sendClientPacket(String name, byte[] body) {
      assertEquals("InteractPropScRsp", name);
      assertNull(reply, "One reply per request");
      try {
        reply = bridge.clientSchema().parse(name, body);
      } catch (IOException invalid) {
        throw new AssertionError(invalid);
      }
    }
  }

  @BeforeEach void loadOriginalGroup() throws Exception {
    oldFloor = GameData.getFloorInfos().get(FLOOR);
    JsonObject records;
    try (var reader = new InputStreamReader(getClass().getResourceAsStream(
        "/fixtures/train-workbench-4.6.json"), StandardCharsets.UTF_8)) {
      records = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
    }
    var gson = ResourceLoader.getGson();
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
    var group = gson.fromJson(records.get(GROUP), GroupInfo.class);
    group.setId(30);
    var floor = new FloorInfo(10000000);
    floor.getGroups().put(30, group);
    floor.onLoad();
    GameData.getFloorInfos().put(FLOOR, floor);
    player.setSession(session);
    DailyMissionAcceptance46Test.set(player, "level", 14);
  }

  @AfterEach void restoreResources() {
    if (oldFloor == null) GameData.getFloorInfos().remove(FLOOR);
    else GameData.getFloorInfos().put(FLOOR, oldFloor);
    oldProps.forEach((id, old) -> {
      if (old == null) GameData.getPropExcelMap().keySet().remove(id);
      else GameData.getPropExcelMap().add(old);
    });
    oldInteractions.forEach((id, old) -> {
      if (old == null) GameData.getInteractExcelMap().keySet().remove(id);
      else GameData.getInteractExcelMap().add(old);
    });
  }

  private void completeOriginalMission() {
    var mission = new QuestState(UNLOCK_MISSION);
    mission.advance(1, 1, 1);
    mission.reserveReward();
    mission.closeReward();
    player.getGameplay().getMainMissions().put(UNLOCK_MISSION, mission);
  }

  private EntityProp loadScene() {
    player.scene = new Scene(player, plane, player.getFloorId());
    player.scene.setEntryId(player.getEntryId());
    return prop(300002);
  }

  private EntityProp prop(int instance) {
    return player.scene.getEntitiesByGroup(EntityProp.class, 30).stream()
        .filter(p -> p.getInstId() == instance).findFirst().orElseThrow();
  }

  private int press(int entity, int config) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var wire = CodedOutputStream.newInstance(bytes);
    wire.writeUInt32(4, entity);
    wire.writeUInt32(5, config);
    wire.writeUInt64(8, -1L);
    wire.flush();
    session.reply = null;
    assertTrue(VersionedPacketHandler.handle(session, 1405, bytes.toByteArray()));
    assertNotNull(session.reply);
    assertTrue(session.reply.getUnknownFields().asMap().isEmpty());
    assertEquals(entity, field("prop_entity_id"));
    return field("retcode");
  }

  private int field(String name) {
    return (Integer) session.reply.getField(session.reply.getDescriptorForType().findFieldByName(name));
  }

  @Test void qualifiedWorkbenchAcknowledgesTheNativeRequestOnEveryUse() throws IOException {
    completeOriginalMission();
    var workbench = loadScene();
    var before = ResourceLoader.getGson().toJson(player.getGameplay());
    assertEquals(0, press(workbench.getEntityId(), 1100));
    assertEquals(PropState.Closed.getVal(), field("prop_state"));
    assertEquals(PropState.Closed, workbench.getState());
    assertEquals(0, press(workbench.getEntityId(), 1100));
    assertEquals(0, player.saves);
    assertEquals(before, ResourceLoader.getGson().toJson(player.getGameplay()));
    assertTrue(player.packets.isEmpty());
  }

  @Test void reloadProjectsTheOriginalUsableStateFromSavedProgress() {
    completeOriginalMission();
    player.getGameplay().getStoryValues().put(OLD_STATE, PropState.Open.getVal());
    assertEquals(PropState.Closed, loadScene().getState());
    assertEquals(PropState.Closed, loadScene().getState());
    assertEquals(PropState.Open.getVal(), player.getGameplay().getStoryValues().get(OLD_STATE));
    assertEquals(0, player.saves);
  }

  @Test void aMissingMissionCannotBeBypassedByAnOldStateOrDirectUnlock() throws IOException {
    player.getGameplay().getStoryValues().put(OLD_STATE, PropState.Closed.getVal());
    var workbench = loadScene();
    assertEquals(PropState.Locked, workbench.getState());
    assertEquals(Retcode.PLOT_NOT_UNLOCK.getVal(), press(workbench.getEntityId(), 1100));
    assertEquals(Retcode.INTERACT_TYPE_INVALID.getVal(), press(workbench.getEntityId(), 1200));
    assertEquals(PropState.Locked, workbench.getState());
    assertEquals(0, player.saves);
  }

  @Test void completedMissionAloneDoesNotMeetTheOriginalLevelGate() throws Exception {
    completeOriginalMission();
    DailyMissionAcceptance46Test.set(player, "level", 13);
    var workbench = loadScene();
    assertEquals(PropState.Locked, workbench.getState());
    assertEquals(Retcode.PLAYER_LEVEL_NOT_MEET.getVal(), press(workbench.getEntityId(), 1100));
    assertEquals(0, player.saves);
  }

  @Test void completedMissionUnlocksAnAlreadyLoadedWorkbench() throws IOException {
    var workbench = loadScene();
    assertEquals(PropState.Locked, workbench.getState());
    player.scene.toProto();
    player.packets.clear();
    completeOriginalMission();
    player.getMissionManager().refresh();
    assertEquals(PropState.Closed, workbench.getState());
    assertEquals(1, player.packets.stream()
        .filter(packet -> packet.getCmdId() == CmdId.SceneGroupRefreshScNotify).count());
    assertEquals(0, press(workbench.getEntityId(), 1100));
  }

  @Test void gainingTheLastRequiredLevelUnlocksTheLoadedWorkbench() throws Exception {
    var levels = GameData.getPlayerLevelExcelMap();
    var previous = levels.get(14);
    var config = LunarCore.class.getDeclaredField("config");
    config.setAccessible(true);
    var previousConfig = config.get(null);
    try {
      config.set(null, new Config());
      levels.add(ResourceLoader.getGson().fromJson(
          "{\"Level\":14,\"PlayerExp\":10200}", PlayerLevelExcel.class));
      completeOriginalMission();
      DailyMissionAcceptance46Test.set(player, "level", 13);
      DailyMissionAcceptance46Test.set(player, "exp", 10199);
      var workbench = loadScene();
      player.scene.toProto();
      player.packets.clear();
      assertEquals(PropState.Locked, workbench.getState());
      player.addExp(1);
      assertEquals(14, player.getLevel());
      assertEquals(PropState.Closed, workbench.getState());
      assertEquals(List.of(CmdId.PlayerSyncScNotify, CmdId.SceneGroupRefreshScNotify),
          player.packets.stream().map(BasePacket::getCmdId).toList());
    } finally {
      config.set(null, previousConfig);
      if (previous == null) levels.keySet().remove(14);
      else levels.add(previous);
    }
  }

  @Test void theLegacySceneKeepsItsExistingState() throws Exception {
    completeOriginalMission();
    var legacy = new Session(ProtocolBridge.forVersion("4.2.0"));
    DailyMissionAcceptance46Test.set(player, "session", legacy);
    assertEquals(PropState.Locked, loadScene().getState());
  }

  @Test void theJukeboxAndMissingEntitiesKeepTheOrdinaryChecks() throws IOException {
    completeOriginalMission();
    loadScene();
    var jukebox = prop(300001);
    assertEquals(Retcode.SCENE_ENTITY_NOT_EXIST.getVal(), press(9999, 1100));
    assertEquals(Retcode.UNSUPPORTED_PROP_STATE.getVal(), press(jukebox.getEntityId(), 1100));
    assertTrue(jukebox.setState(PropState.Open, false));
    assertEquals(0, press(jukebox.getEntityId(), 1100));
    assertEquals(PropState.Closed, jukebox.getState());
    assertEquals(1, player.saves);
  }
}
