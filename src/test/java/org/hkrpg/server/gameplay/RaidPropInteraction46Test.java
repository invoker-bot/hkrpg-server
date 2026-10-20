package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Original 40431 G46 and interaction 1010; prior battle and scene arrival are fixtures. */
class RaidPropInteraction46Test {
  @TempDir Path root;
  final DefaultHybridMissionRaid46Test hybrid = new DefaultHybridMissionRaid46Test();
  final Map<Integer, PropExcel> oldProps = new HashMap<>();
  final Map<Integer, InteractExcel> oldInteractions = new HashMap<>();
  FloorInfo oldFloor;
  Actor player;

  static class Actor extends DefaultEntryMissionRaid46Test.WorldActor {
    boolean failSave;

    Actor(MissionRaidTrialLifecycle46Test helper) {
      super(helper);
    }

    @Override
    public void save() {
      if (failSave) throw new IllegalStateException("isolated storage failure");
      super.save();
    }
  }

  @BeforeEach
  void prepare() throws Exception {
    hybrid.root = root;
    hybrid.prepare();
    var helper = hybrid.source.helper;
    player = new Actor(helper);
    player.getGameplay().getMainMissions().putAll(hybrid.player.getGameplay().getMainMissions());
    for (var avatar : hybrid.player.getAvatars().getAvatars().values()) {
      avatar.setOwner(player);
      player.getAvatars().getAvatars().put(avatar.getAvatarId(), avatar);
    }
    player.getCurrentLineup().getAvatars().addAll(hybrid.player.getCurrentLineup().getAvatars());
    helper.player = player;
    helper.fixture.player = player;
    hybrid.source.player = player;
    hybrid.player = player;
    helper.position(player, 2000101);
    var fixture = helper.fixture.fixture("raid-prop-interaction-4.6.json");
    var records = fixture.getAsJsonObject("records");
    var gson = ResourceLoader.getGson();
    for (var raw : records.getAsJsonArray("ExcelOutput/MazeProp.json")) {
      var decoded = raw.getAsJsonObject().deepCopy();
      if (decoded.has("PropName") && decoded.get("PropName").isJsonObject())
        decoded.addProperty(
            "PropName", decoded.getAsJsonObject("PropName").get("Hash").getAsLong());
      var row = gson.fromJson(decoded, PropExcel.class);
      oldProps.put(row.getId(), GameData.getPropExcelMap().get(row.getId()));
      row.onLoad();
      GameData.getPropExcelMap().add(row);
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/InteractConfig.json")) {
      var row = gson.fromJson(raw, InteractExcel.class);
      oldInteractions.put(row.getId(), GameData.getInteractExcelMap().get(row.getId()));
      row.onLoad();
      GameData.getInteractExcelMap().add(row);
    }
    oldFloor = GameData.getFloorInfo(40431, 40431002);
    var floor =
        gson.fromJson(
            records.get("Config/LevelOutput/RuntimeFloor/P40431_F40431002.json"), FloorInfo.class);
    if (oldFloor != null) floor.getGroups().putAll(oldFloor.getGroups());
    for (int id : List.of(11, 33, 41, 46)) {
      var group =
          gson.fromJson(
              records.get(
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P40431_F40431002/LevelGroup_P40431_F40431002_G"
                      + id
                      + ".json"),
              GroupInfo.class);
      group.setId(id);
      floor.getGroups().put(id, group);
    }
    floor.onLoad();
    GameData.getFloorInfos().put("P40431_F40431002", floor);
    assertEquals(
        Retcode.SUCC,
        new MissionRaid(player)
            .enterSelected(40431, 0, 0, 0, List.of(new RaidTeams.Selection(1021409, true))));
    player.getMissionManager().onStageWin(20431070);
    talk(104040834);
    assertTrue(new MissionRaid(player).activeFor(1040408));
  }

  @AfterEach
  void restore() throws Exception {
    for (var e : oldProps.entrySet()) {
      if (e.getValue() == null) GameData.getPropExcelMap().keySet().remove(e.getKey());
      else GameData.getPropExcelMap().add(e.getValue());
    }
    for (var e : oldInteractions.entrySet()) {
      if (e.getValue() == null) GameData.getInteractExcelMap().keySet().remove(e.getKey());
      else GameData.getInteractExcelMap().add(e.getValue());
    }
    if (oldFloor == null) GameData.getFloorInfos().remove("P40431_F40431002");
    else GameData.getFloorInfos().put("P40431_F40431002", oldFloor);
    hybrid.restore();
  }

  void talk(int id) {
    assertEquals(Retcode.SUCC, player.getMissionManager().finishTalk(id, "Mission_" + id));
  }

  EntityProp prop() {
    return player.getScene().getEntities().values().stream()
        .filter(e -> e instanceof EntityProp && e.getGroupId() == 46 && e.getInstId() == 300002)
        .map(EntityProp.class::cast)
        .findFirst()
        .orElseThrow();
  }

  void ready() {
    talk(104040801);
    talk(104040802);
    talk(104040806);
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(104040807).getPhase());
  }

  void reload() {
    hybrid.source.helper.position(player, 40431002);
  }

  @Test
  void originalMirageOrbTransitionSurvivesActualRaidSceneReconstruction() {
    ready();
    var before = prop();
    assertEquals(PropState.Closed, before.getState());
    assertSame(before, player.interactWithProp(1010, before.getEntityId()));
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(104040807).getPhase());
    reload();
    assertEquals(PropState.Open, prop().getState());
  }

  @Test
  void worldDoorBacktrackingGuardDoesNotReplaceTheExistingRaidReceiptScope() {
    ready();
    var gson = ResourceLoader.getGson();
    // Isolated console variant of the same valid raid placement and PropState objective.
    var old = prop();
    var info = gson.toJsonTree(old.getPropInfo()).getAsJsonObject();
    info.addProperty("PropID", 100000);
    info.addProperty(
        "InitLevelGraph",
        "Config/Level/Props/Chap00/InitLevelGraph_Prop_Chap00_Console01_OpenDoor_01.json");
    var replacement = gson.fromJson(info, PropInfo.class);
    var group = player.getScene().getResourceGroups().get(46);
    group.getPropList().replaceAll(p -> p.getID() == old.getInstId() ? replacement : p);
    var excel = gson.toJsonTree(old.getExcel()).getAsJsonObject();
    excel.addProperty("ID", 100000);
    oldProps.put(100000, GameData.getPropExcelMap().get(100000));
    var console = gson.fromJson(excel, PropExcel.class);
    console.onLoad();
    GameData.getPropExcelMap().add(console);
    reload();
    var raw = gson.toJsonTree(GameplayDefinitions.subs.get(104040807)).getAsJsonObject();
    raw.addProperty("IsBackTrack", true);
    var objective = gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
    assertTrue(objective.isBackTrack());
    assertSame(prop(), player.interactWithProp(1010, prop().getEntityId()));
    assertTrue(ScenePropTransitions.satisfied(player, objective));
  }

  @Test
  void failedStorageRollsBackTheOrbAndEmitsNoStateOrObjectiveSuccess() {
    ready();
    var prop = prop();
    var json = ResourceLoader.getGson().toJson(player.getGameplay());
    int packets = player.packets.size();
    player.failSave = true;
    assertNull(player.interactWithProp(1010, prop.getEntityId()));
    assertEquals(PropState.Closed, prop.getState());
    assertEquals(json, ResourceLoader.getGson().toJson(player.getGameplay()));
    assertEquals(packets, player.packets.size());
    player.failSave = false;
    assertSame(prop, player.interactWithProp(1010, prop.getEntityId()));
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(104040807).getPhase());
  }

  @Test
  void originalBacktrackingGoalUsesTheSavedTransitionAfterItsPredecessorCompletes() {
    talk(104040801);
    talk(104040802);
    assertNull(player.getMissionManager().sub(104040807));
    var prop = prop();
    assertSame(prop, player.interactWithProp(1010, prop.getEntityId()));
    reload();
    talk(104040806);
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(104040807).getPhase());
  }

  @Test
  void sourceStateIsRestoredAcrossGameplaySerializationAndIndependentSceneObjects() {
    ready();
    var prop = prop();
    assertNotNull(player.interactWithProp(1010, prop.getEntityId()));
    var gson = ResourceLoader.getGson();
    MissionTrialProvisioning46Test.set(
        player, "gameplay", gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class));
    assertTrue(new MissionRaid(player).restoreOnLogin());
    reload();
    assertEquals(PropState.Open, prop().getState());
  }

  @Test
  void aReplacementMainAcceptanceCannotBorrowTheOldOrbTransition() {
    ready();
    var prop = prop();
    assertNotNull(player.interactWithProp(1010, prop.getEntityId()));
    player.getGameplay().getMainMissions().put(1040408, new QuestState(1040408));
    reload();
    assertEquals(PropState.Closed, prop().getState());
  }

  @Test
  void successfulInteractionOnlyEmitsNotificationsThatTheFrozen46ProtocolCanEncode() {
    ready();
    player.getScene().toProto();
    player.packets.clear();
    var prop = prop();
    assertNotNull(player.interactWithProp(1010, prop.getEntityId()));
    var bridge = org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
    assertFalse(player.packets.isEmpty());
    for (var packet : player.packets)
      assertDoesNotThrow(() -> bridge.outboundFrames(packet, player));
  }

  @Test
  void changedAuthoredGraphCannotRestoreThePreviousOrbState() {
    ready();
    var prop = prop();
    assertNotNull(player.interactWithProp(1010, prop.getEntityId()));
    prop.getPropInfo().setInitLevelGraph("changed-authored-graph");
    reload();
    assertEquals(PropState.Closed, prop().getState());
  }

  @Test
  void unknownSavedStateVersionCannotChangeTheOrbOrAdvanceItsGoal() {
    ready();
    var run = new MissionRaid(player).current();
    var invalid =
        ResourceLoader.getGson().fromJson("{\"version\":0,\"records\":{}}", ScenePropState.class);
    run.scenePropStates(invalid);
    var prop = prop();
    int saves = player.saves;
    assertEquals(
        Retcode.PLAYER_DATA_ERROR,
        ScenePropTransitions.interact(player, 1010, prop.getEntityId()).retcode());
    assertEquals(PropState.Closed, prop.getState());
    assertEquals(0, player.getMissionManager().sub(104040807).getProgress());
    assertEquals(saves, player.saves);
  }

  void finishRequiredRoute() {
    ready();
    var orb = prop();
    assertNotNull(player.interactWithProp(1010, orb.getEntityId()));
    for (int id :
        List.of(104040832, 104040833, 104040809, 104040831, 104040818, 104040823, 104040821))
      talk(id);
    // The server-side battle outcome is isolated here; native probes submit both WIN requests.
    player.getMissionManager().onStageWin(20431080);
    talk(104040824);
    assertTrue(new MissionRaid(player).current().isCompleted());
    assertTrue(MissionStateRecords.mainCompleted(player, 1040408));
  }

  @Test
  void fullRequiredStoryRouteExecutesItsOriginalAnchorMovesAndLiftProperty() {
    ready();
    assertNotNull(player.interactWithProp(1010, prop().getEntityId()));
    for (int id : List.of(104040832, 104040833, 104040809)) talk(id);
    var first = player.getScene().getFloorInfo().getAnchorInfo(11, 1);
    assertEquals(first.getPos(), player.getPos());
    assertEquals(first.getRot(), player.getRot());
    talk(104040831);
    var second = player.getScene().getFloorInfo().getAnchorInfo(33, 1);
    assertEquals(second.getPos(), player.getPos());
    assertEquals(second.getRot(), player.getRot());
    for (int id : List.of(104040818, 104040823, 104040821)) talk(id);
    for (String key :
        List.of(
            "FSV_CpuRepair",
            "FSV_PassLightCount",
            "FSV_EraLight01Finished",
            "FSV_UnloadEraLight",
            "FSV_EraLight02Finished",
            "FSV_EraLight03Finished",
            "FSV_BossDoorRainBow",
            "FSV_LoadAnchor"))
      assertEquals(1, player.getGameplay().getStoryValues().get("floor:40431002:" + key));
    assertEquals(1, player.getGameplay().getStoryValues().get("group:40431002:41:Lock"));
    assertEquals(1, player.getScene().getGroups().get(41).getProperties().getInt("Lock"));
    player.getMissionManager().onStageWin(20431080);
    talk(104040824);
    assertTrue(MissionStateRecords.mainCompleted(player, 1040408));
    assertTrue(new MissionRaid(player).current().isCompleted());
  }

  @Test
  void completedOriginalRaidRestoresItsOrbUntilThePlayerLeaves() {
    finishRequiredRoute();
    reload();
    assertEquals(PropState.Open, prop().getState());
  }

  @Test
  void serializedCompletedOriginalRaidRestoresItsOrbAcrossLogin() {
    finishRequiredRoute();
    var gson = ResourceLoader.getGson();
    MissionTrialProvisioning46Test.set(
        player, "gameplay", gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class));
    assertTrue(new MissionRaid(player).restoreOnLogin());
    reload();
    assertEquals(PropState.Open, prop().getState());
  }

  @Test
  void completedMainCannotMakeFreshOrbTransitionsAfterAReconstruction() {
    finishRequiredRoute();
    reload();
    var orb = prop();
    var before =
        ResourceLoader.getGson().toJson(new MissionRaid(player).current().getScenePropStates());
    assertNotEquals(
        Retcode.SUCC, ScenePropTransitions.interact(player, 1010, orb.getEntityId()).retcode());
    assertEquals(
        before,
        ResourceLoader.getGson().toJson(new MissionRaid(player).current().getScenePropStates()));
  }
}
