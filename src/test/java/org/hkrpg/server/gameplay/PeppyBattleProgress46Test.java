package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.OpeningStageWin46Test.field;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.data.excel.PlaneEventExcel;
import emu.lunarcore.data.excel.PropExcel;
import emu.lunarcore.data.excel.StageExcel;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.proto.GetCurSceneInfoScRspOuterClass.GetCurSceneInfoScRsp;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.send.PacketSceneGroupRefreshScNotify;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hkrpg.server.protocol.Scene46;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original companion mission battle graphs, exercised through 4.6 requests. */
class PeppyBattleProgress46Test {
  private static final int MAIN = 2000211;
  private static final int FOLLOW_PEPPY = 200021114, DAN_HENG_TALK = 200021116;
  private static final int LEGACY_FIGHT_MARKER = 200021115, ASTA_TALK = 200021117;
  private static final int PEPPY_EVENT = 20003214;
  private static final int RESCUE_ARLAN = 200021123, ARLAN_TALK = 200021124;
  private static final int ARLAN_EVENT = 20003215, MISSING_ARLAN_EVENT = 20003216;

  @TempDir Path root;
  private final OpeningStageWin46Test fixture = new OpeningStageWin46Test();
  private final Map<String, FloorInfo> oldFloors = new HashMap<>();
  private OpeningStageWin46Test.Actor player;
  private OpeningStageWin46Test.Session session;
  private PropExcel oldProp;
  private boolean sceneFixture;

  @BeforeEach
  void prepare() throws Exception {
    fixture.temp = root;
    fixture.load();
    try (var input = getClass().getResourceAsStream("/fixtures/peppy-battle-progress-4.6.json")) {
      var source = JsonParser.parseString(
          new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8))
          .getAsJsonObject();
      for (var raw : source.getAsJsonArray("records")) {
        var record = raw.getAsJsonObject();
        var row = record.getAsJsonObject("row");
        switch (record.get("kind").getAsString()) {
          case "file" -> {
            var path = root.resolve(record.get("source").getAsString());
            Files.createDirectories(path.getParent());
            Files.writeString(path, row.toString());
          }
          case "MainMission" ->
              GameData.getMainMissionExcelMap().add(fixture.gson.fromJson(row, MainMissionExcel.class));
          case "PlaneEvent" ->
              GameData.getPlaneEventExcelMap().add(fixture.gson.fromJson(row, PlaneEventExcel.class));
          case "StageConfig" -> {
            var stage = fixture.gson.fromJson(row, StageExcel.class);
            stage.onLoad();
            GameData.getStageExcelMap().add(stage);
          }
          default -> fail("Unknown fixture record");
        }
      }
    }
    GameplayDefinitions.load(root);
    player = new OpeningStageWin46Test.Actor() {
      @Override
      public int getEntryId() {
        return getPlaneId() * 100 + 1;
      }
    };
    session = fixture.new Session(player);
    MissionTrialProvisioning46Test.set(player, "loggedIn", true);
    placePlayer(20001);
    // Earlier dialogue and the accepted objectives are the isolated starting state.
    player.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    complete(200021113);
    accept(FOLLOW_PEPPY);
    accept(LEGACY_FIGHT_MARKER);
    assertNull(player.getMissionManager().sub(DAN_HENG_TALK));
  }

  @AfterEach
  void restore() {
    if (sceneFixture) {
      GameData.getPropExcelMap().keySet().remove(100);
      if (oldProp != null) GameData.getPropExcelMap().add(oldProp);
    }
    oldFloors.forEach((key, old) -> {
      if (old == null) GameData.getFloorInfos().remove(key);
      else GameData.getFloorInfos().put(key, old);
    });
    fixture.restore();
  }

  private void placePlayer(int plane) throws Exception {
    player.floor = plane * 1000 + 1;
    var floor = fixture.gson.fromJson("{\"FloorID\":" + player.floor + "}", FloorInfo.class);
    MissionTrialProvisioning46Test.setField(floor, FloorInfo.class, "loaded", true);
    var key = "P" + plane + "_F" + player.floor;
    oldFloors.put(key, GameData.getFloorInfos().put(key, floor));
    var maze = fixture.gson.fromJson(
        "{\"PlaneID\":" + plane + ",\"PlaneType\":\"Maze\",\"WorldID\":101}", MazePlaneExcel.class);
    MissionTrialProvisioning46Test.set(player, "scene", new Scene(player, maze, player.floor));
  }

  private void loadPeppyGroup(boolean explicitServer) throws Exception {
    JsonObject source;
    try (var input = getClass().getResourceAsStream("/fixtures/peppy-followup-scene-4.6.json")) {
      source = JsonParser.parseString(
          new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
    var prop = fixture.gson.fromJson(source.get("prop"), PropExcel.class);
    prop.onLoad();
    oldProp = GameData.getPropExcelMap().get(prop.getId());
    sceneFixture = true;
    GameData.getPropExcelMap().add(prop);
    var raw = source.getAsJsonObject("group");
    if (explicitServer) raw.addProperty("LoadSide", "Server");
    var group = fixture.gson.fromJson(raw, GroupInfo.class);
    group.setId(70);
    var floor = fixture.gson.fromJson("""
        {"FloorID":20001001,"GroupInstanceList":[{"ID":70}],"DimensionList":[{"ID":0,"GroupIndexList":[0]}]}
        """, FloorInfo.class);
    floor.registerGroup(0, group);
    floor.onLoad();
    GameData.getFloorInfos().put("P20001_F20001001", floor);
    var maze = fixture.gson.fromJson(
        "{\"PlaneID\":20001,\"PlaneType\":\"Maze\",\"WorldID\":101}", MazePlaneExcel.class);
    player.getCurrentLineup().getAvatars().add(0);
    var scene = new Scene(player, maze, 20001001);
    scene.setEntryId(player.getEntryId());
    MissionTrialProvisioning46Test.set(player, "scene", scene);
  }

  private DynamicMessage nativeScene() throws Exception {
    var bridge = session.getProtocol();
    var packet = GetCurSceneInfoScRsp.newInstance().setScene(player.getScene().toProto());
    var body = bridge.outbound(CmdId.GetCurSceneInfoScRsp, packet.toByteArray()).body();
    var reply = bridge.clientSchema().parse("GetCurSceneInfoScRsp",
        Scene46.enrich(bridge.clientSchema(), "GetCurSceneInfoScRsp", body, player));
    return (DynamicMessage) field(reply, "scene");
  }

  private List<DynamicMessage> nativeRefresh(BasePacket packet) throws Exception {
    var bridge = session.getProtocol();
    String name = "SceneGroupRefreshScNotify";
    var body = bridge.outbound(packet.getCmdId(), packet.getData().toByteArray()).body();
    return rows(bridge.clientSchema().parse(name, Scene46.enrich(bridge.clientSchema(), name, body, player)),
        "group_refresh_list");
  }

  private static List<DynamicMessage> rows(DynamicMessage message, String name) {
    return ((List<?>) field(message, name)).stream().map(DynamicMessage.class::cast).toList();
  }

  private static String status(DynamicMessage message, String list, int id) {
    return rows(message, list).stream().filter(row -> field(row, "id").equals(id))
        .map(row -> field(row, "status").toString()).findFirst().orElse("");
  }

  private QuestState accept(int id) {
    var state = new QuestState(id);
    player.getGameplay().getSubMissions().put(id, state);
    return state;
  }

  private void complete(int id) {
    accept(id).advance(1, 1, 100);
  }

  private DynamicMessage requestEntry(int event) throws Exception {
    return session.request("SceneEnterStageCsReq", Map.of("event_id", event));
  }

  private Battle enter(int event) throws Exception {
    assertEquals(0, field(requestEntry(event), "retcode"));
    return Objects.requireNonNull(player.getBattle());
  }

  private DynamicMessage finish(Battle battle, String outcome) throws Exception {
    var end = session.getProtocol().clientSchema().message("PVEBattleResultCsReq")
        .findFieldByName("end_status");
    return session.request("PVEBattleResultCsReq", Map.of(
        "battle_id", battle.getId(),
        "stage_id", battle.getStage().getId(),
        "end_status", end.getEnumType().findValueByName(outcome)));
  }

  private QuestState.Phase phase(int id) {
    return Objects.requireNonNull(player.getMissionManager().sub(id)).getPhase();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 6})
  void peppyVictoryFinishesTheOpeningScriptAndAcceptsDanHengDialogue(int world) throws Exception {
    player.world = world;
    session.request("GetMissionDataCsReq", Map.of());
    var battle = enter(PEPPY_EVENT);
    assertEquals(200032140 + world, battle.getStage().getId());
    var reply = finish(battle, "BATTLE_END_WIN");
    assertEquals(0, field(reply, "retcode"));
    assertEquals(PEPPY_EVENT, field(reply, "event_id"));
    assertEquals(QuestState.Phase.FINISHED, phase(FOLLOW_PEPPY));
    assertEquals(QuestState.Phase.DOING, phase(DAN_HENG_TALK));

    var notify = session.responses.get("PlayerSyncScNotify");
    assertNotNull(notify, "An online player needs the mission change as the battle ends");
    var missionSync = (DynamicMessage) field(notify, "mission_sync");
    var changed = (List<?>) field(missionSync, "mission_list");
    assertTrue(changed.stream().anyMatch(row -> field((DynamicMessage) row, "id").equals(FOLLOW_PEPPY)
        && field((DynamicMessage) row, "status").toString().equals("MissionStatus_MissionFinish")));
    assertTrue(changed.stream().anyMatch(row -> field((DynamicMessage) row, "id").equals(DAN_HENG_TALK)
        && field((DynamicMessage) row, "status").toString().equals("MissionStatus_MissionDoing")));
    assertEquals("MissionStatus_MissionNone", status(missionSync, "mission_list", LEGACY_FIGHT_MARKER));

    var snapshot = session.request("GetMissionDataCsReq", Map.of());
    assertTrue(((List<?>) field(snapshot, "OLKALABEDOA")).contains(FOLLOW_PEPPY));
    assertTrue(((List<?>) field(snapshot, "mission_list")).stream()
        .anyMatch(row -> field((DynamicMessage) row, "id").equals(DAN_HENG_TALK)));
    assertNotEquals(0, field(finish(battle, "BATTLE_END_WIN"), "retcode"));
  }

  @Test
  void savedWinRetiresTheOldMarkerAndKeepsDanHengActiveOnLogin() throws Exception {
    complete(FOLLOW_PEPPY);
    accept(DAN_HENG_TALK);
    var snapshot = session.request("GetMissionDataCsReq", Map.of());
    assertEquals("MissionStatus_MissionNone", status(snapshot, "mission_list", LEGACY_FIGHT_MARKER));
    assertEquals("MissionStatus_MissionDoing", status(snapshot, "mission_list", DAN_HENG_TALK));
    assertTrue(((List<?>) field(snapshot, "OLKALABEDOA")).contains(FOLLOW_PEPPY));
    assertEquals(QuestState.Phase.DOING, phase(LEGACY_FIGHT_MARKER));
  }

  @Test
  void postBattleSceneSuppliesTheNativeLoadGateAndTheCurrentDialogue() throws Exception {
    complete(FOLLOW_PEPPY);
    accept(DAN_HENG_TALK);
    loadPeppyGroup(false);
    var gates = (DynamicMessage) field(nativeScene(), "scene_mission_info");
    assertTrue(((List<?>) field(gates, "unfinished_main_mission_id_list")).contains(MAIN));
    assertEquals("MissionStatus_MissionFinish", status(gates, "sub_mission_status_list", 200021113));
    assertEquals("MissionStatus_MissionFinish", status(gates, "sub_mission_status_list", FOLLOW_PEPPY));
    assertEquals("MissionStatus_MissionDoing", status(gates, "sub_mission_status_list", DAN_HENG_TALK));
    complete(DAN_HENG_TALK);
    accept(ASTA_TALK);
    gates = (DynamicMessage) field(nativeScene(), "scene_mission_info");
    assertEquals("MissionStatus_MissionDoing", status(gates, "sub_mission_status_list", ASTA_TALK));
    complete(ASTA_TALK);
    var scene = player.getScene();
    scene.refreshMissionGroups();
    assertTrue(scene.hasStoryGroup(70), "The authored group unloads on the next level load");
    var reentered = new Scene(player, scene.getExcel(), scene.getFloorId());
    reentered.setEntryId(scene.getEntryId());
    MissionTrialProvisioning46Test.set(player, "scene", reentered);
    assertFalse(reentered.hasStoryGroup(70));
    gates = (DynamicMessage) field(nativeScene(), "scene_mission_info");
    assertEquals("MissionStatus_MissionFinish", status(gates, "sub_mission_status_list", ASTA_TALK));
  }

  @Test
  void originalClientGroupIsNotRegisteredByTheServerPropDuringSceneLoad() throws Exception {
    loadPeppyGroup(false);
    assertTrue(player.getScene().hasStoryGroup(70));
    assertTrue(rows(nativeScene(), "entity_group_list").stream()
        .noneMatch(row -> field(row, "group_id").equals(70)));
  }

  @Test
  void propUpdatesAndUnloadDoNotOverrideTheOriginalClientGroup() throws Exception {
    loadPeppyGroup(false);
    var scene = player.getScene();
    var prop = scene.getEntitiesByGroup(EntityProp.class, 70).stream()
        .filter(p -> p.getInstId() == 300002).findFirst().orElseThrow();
    assertTrue(nativeRefresh(new PacketSceneGroupRefreshScNotify(scene, prop, null)).isEmpty());
    assertTrue(nativeRefresh(new PacketSceneGroupRefreshScNotify(scene, 70)).isEmpty());
  }

  @Test
  void aGroupThatExplicitlyDeclaresServerOwnershipStillPublishesItsProp() throws Exception {
    loadPeppyGroup(true);
    assertTrue(rows(nativeScene(), "entity_group_list").stream()
        .anyMatch(row -> field(row, "group_id").equals(70)));
  }

  @Test
  void stalePeppyTriggerCannotStartTheFightAfterTheSavedWin() throws Exception {
    complete(FOLLOW_PEPPY);
    accept(DAN_HENG_TALK);
    assertNotEquals(0, field(requestEntry(PEPPY_EVENT), "retcode"));
    assertNull(player.getBattle());
    assertEquals(QuestState.Phase.FINISHED, phase(FOLLOW_PEPPY));
  }

  @Test
  void losingOrQuittingLeavesThePeppyEncounterRetryable() throws Exception {
    for (String outcome : List.of("BATTLE_END_LOSE", "BATTLE_END_QUIT")) {
      assertEquals(0, field(finish(enter(PEPPY_EVENT), outcome), "retcode"));
      assertEquals(QuestState.Phase.DOING, phase(FOLLOW_PEPPY));
      assertNull(player.getMissionManager().sub(DAN_HENG_TALK));
    }
    assertNotNull(enter(PEPPY_EVENT));
  }

  @Test
  void directStageOrAnUnrelatedEventSharingItsStageDoesNotFinishPeppy() throws Exception {
    var direct = Objects.requireNonNull(fixture.service.startBattle(player, 200032140, 0));
    assertEquals(0, field(finish(direct, "BATTLE_END_WIN"), "retcode"));
    int otherEvent = 29999999;
    GameData.getPlaneEventExcelMap().add(fixture.gson.fromJson(
        "{\"EventID\":" + otherEvent + ",\"StageID\":200032140}", PlaneEventExcel.class));
    assertEquals(0, field(finish(enter(otherEvent), "BATTLE_END_WIN"), "retcode"));
    assertEquals(QuestState.Phase.DOING, phase(FOLLOW_PEPPY));
    assertNull(player.getMissionManager().sub(DAN_HENG_TALK));
  }

  @Test
  void changedBattleGraphCannotConsumeAnAlreadyStartedPeppyWin() throws Exception {
    var battle = enter(PEPPY_EVENT);
    GameplayDefinitions.subs.get(FOLLOW_PEPPY).getTask().retainSourceDefinition(new JsonObject());
    assertEquals(0, field(finish(battle, "BATTLE_END_WIN"), "retcode"));
    assertEquals(QuestState.Phase.DOING, phase(FOLLOW_PEPPY));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 6})
  void authoredArlanTriggerUsesTheStageWinEventAndAcceptsTheNextDialogue(int world) throws Exception {
    placePlayer(20003);
    player.world = world;
    complete(FOLLOW_PEPPY);
    complete(200021122);
    accept(RESCUE_ARLAN);
    var battle = enter(MISSING_ARLAN_EVENT);
    assertEquals(ARLAN_EVENT * 10 + world, battle.getStage().getId());
    var reply = finish(battle, "BATTLE_END_WIN");
    assertEquals(0, field(reply, "retcode"));
    assertEquals(MISSING_ARLAN_EVENT, field(reply, "event_id"));
    assertEquals(QuestState.Phase.FINISHED, phase(RESCUE_ARLAN));
    assertEquals(QuestState.Phase.DOING, phase(ARLAN_TALK));
    assertNotEquals(0, field(requestEntry(MISSING_ARLAN_EVENT), "retcode"));
  }

  @Test
  void arlanAliasRequiresItsOriginalPrerequisiteAndFloor() throws Exception {
    complete(200021122);
    accept(RESCUE_ARLAN);
    assertNotEquals(0, field(requestEntry(MISSING_ARLAN_EVENT), "retcode"));
    placePlayer(20003);
    accept(200021122);
    assertNotEquals(0, field(requestEntry(MISSING_ARLAN_EVENT), "retcode"));
    assertNull(player.getBattle());
  }
}
