package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.MapEntranceExcel;
import emu.lunarcore.data.excel.PropExcel;
import emu.lunarcore.data.excel.SpecialAvatarExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.PlayerGender;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.game.scene.SceneEntityLoader;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.proto.SceneInfoOuterClass.SceneInfo;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.hkrpg.server.protocol.Universe46;

/** The completed first test and the second test's active entrance are explicit fixtures. */
class RogueSecondStoryOffice46Test {
  static class CapturingSession extends RogueFirstStoryEntry46Test.Session {
    String name;
    byte[] response;
    @Override public void sendClientPacket(String type, byte[] bytes) {
      name = type;
      response = bytes;
    }
  }
  final RogueFirstStoryEntry46Test fixture = new RogueFirstStoryEntry46Test();
  FloorInfo previousFloor;
  MapEntranceExcel previousEntry;
  PropExcel previousProp;
  final java.util.List<SpecialAvatarExcel> previousTrials = new java.util.ArrayList<>();

  @BeforeEach
  void setup() throws Exception {
    fixture.prepare();
    previousTrials.addAll(GameData.getSpecialAvatarExcelMap().values());
    GameData.getSpecialAvatarExcelMap().values().clear();
    try (var input = getClass().getResourceAsStream("/fixtures/rogue-second-story-trials-4.6.json")) {
      assertNotNull(input);
      var source = com.google.gson.JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
      for (var row : source.getAsJsonObject().getAsJsonArray("rows"))
        GameData.getSpecialAvatarExcelMap().add(fixture.gson.fromJson(row, SpecialAvatarExcel.class));
    }
    var first = fixture.actor.getGameplay().getMainMissions().get(4030001);
    assertTrue(first.advance(1, 1, 100));
    fixture.actor.getGameplay().getMainMissions().put(4030002, new QuestState(4030002));
    fixture.actor.getGameplay().getSubMissions().put(403000202, new QuestState(403000202));
    fixture.actor.getGameplay().getSubMissions().put(403000102, completed(403000102));
    var mission = fixture.gson.fromJson(
        "{\"ID\":403000202,\"MainMissionID\":4030002,\"LevelPlaneID\":80112,"
            + "\"LevelFloorID\":80112001,\"FinishType\":\"EnterFloor\",\"ParamType\":\"Equal\","
            + "\"ParamInt1\":80001,\"ParamInt2\":80001002,\"Progress\":1}",
        MissionInfo.SubMissionInfo.class);
    GameplayDefinitions.subs.put(403000202, mission);
    var previous = new GameplayState.RogueRun(100, 1, Map.of(1, 100));
    previous.enter(1, 100);
    previous.finish(GameplayState.RogueRun.Phase.FINISHED);
    previous.setInside(false);
    fixture.actor.getGameplay().setRogue(previous);
    GameplayDefinitions.maps.put(2, List.of(
        new GameplayDefinitions.Site(2, 1, true, List.of(2), 0),
        new GameplayDefinitions.Site(2, 2, false, List.of(3), 0),
        new GameplayDefinitions.Site(2, 3, false, List.of(), 0)));
    GameplayDefinitions.rooms.put(201, new GameplayDefinitions.Room(201, 1, 8000102, 19,
        Map.of(19, 2003, 3, 2001, 4, 2002)));
    GameplayDefinitions.rooms.put(202, new GameplayDefinitions.Room(202, 1, 8000102, 18,
        Map.of(18, 2006, 6, 2004, 7, 2005)));
    GameplayDefinitions.rooms.put(203, new GameplayDefinitions.Room(203, 7, 8000102, 29,
        Map.of(29, 2007)));
    GameplayDefinitions.roomPoolByMap.remove(2);
    previousFloor = GameData.getFloorInfos().get("P80001_F80001002");
    previousEntry = GameData.getMapEntranceExcelMap().get(8000102);
    var floor = new FloorInfo();
    var start = fixture.gson.fromJson(
        "{\"AnchorList\":[{\"ID\":1,\"PosX\":-42.98,\"PosY\":18.61,"
            + "\"PosZ\":-91.38,\"RotY\":345}]}", GroupInfo.class);
    start.setId(19);
    floor.getGroups().put(19, start);
    GameData.getFloorInfos().put("P80001_F80001002", floor);
    GameData.getMapEntranceExcelMap().add(fixture.gson.fromJson(
        "{\"ID\":8000102,\"PlaneID\":80001,\"FloorID\":80001002}", MapEntranceExcel.class));
    previousProp = GameData.getPropExcelMap().get(1003);
    var prop = fixture.gson.fromJson(
        "{\"ID\":1003,\"PropType\":\"PROP_ORDINARY\","
            + "\"PropStateList\":[\"Closed\",\"Open\",\"Locked\"]}", PropExcel.class);
    prop.onLoad();
    GameData.getPropExcelMap().add(prop);
    try (var input = getClass().getResourceAsStream("/fixtures/rogue-second-story-office-group14-4.6.json")) {
      assertNotNull(input);
      var group = fixture.gson.fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8), GroupInfo.class);
      group.setId(14);
      fixture.actor.actual.getResourceGroups().put(14, group);
    }
  }

  private static QuestState completed(int id) {
    var state = new QuestState(id);
    assertTrue(state.advance(1, 1, 100));
    return state;
  }

  @AfterEach
  void cleanup() {
    GameData.getSpecialAvatarExcelMap().values().clear();
    previousTrials.forEach(GameData.getSpecialAvatarExcelMap()::add);
    if (previousFloor == null) GameData.getFloorInfos().remove("P80001_F80001002");
    else GameData.getFloorInfos().put("P80001_F80001002", previousFloor);
    if (previousEntry == null) GameData.getMapEntranceExcelMap().keySet().remove(8000102);
    else GameData.getMapEntranceExcelMap().add(previousEntry);
    if (previousProp == null) GameData.getPropExcelMap().keySet().remove(1003);
    else GameData.getPropExcelMap().add(previousProp);
    fixture.restore();
  }

  @Test
  void acceptedSecondTestLoadsItsAuthoredOfficeEntrance() throws Exception {
    {
      var group = fixture.actor.actual.getResourceGroups().get(14);
      assertTrue(fixture.actor.getMissionManager().shouldLoadGroup(group, fixture.actor.actual));
      new SceneEntityLoader().onSceneLoad(fixture.actor.actual);
      var props = fixture.actor.actual.getEntities(EntityProp.class).stream()
          .filter(p -> p.getGroupId() == 14 && p.getInstId() == 300002).toList();
      assertEquals(1, props.size(), "The tutorial entry must reach the client scene");
      assertEquals(1003, props.getFirst().getPropId());
      assertEquals(PropState.Open, props.getFirst().getState());
    }
  }

  @Test
  void secondEntranceIsNotExposedDuringFirstTest() {
    fixture.actor.getGameplay().getMainMissions().put(4030001, new QuestState(4030001));
    fixture.actor.getGameplay().getMainMissions().remove(4030002);
    new SceneEntityLoader().onSceneLoad(fixture.actor.actual);
    assertTrue(fixture.actor.actual.getEntities(EntityProp.class).stream()
        .noneMatch(p -> p.getGroupId() == 14 && p.getInstId() == 300002));
  }

  @Test
  void unknownSecondEntryMissionDoesNotSilentlyRestartFirstTest() {
    var previous = fixture.actor.getGameplay().getRogue();
    GameplayDefinitions.subs.remove(403000202);
    assertTrue(RogueSecondStoryEntry.inProgress(fixture.actor));
    assertFalse(RogueSecondStoryEntry.awaiting(fixture.actor));
    assertEquals(Retcode.ROGUE_AREA_INVALID, fixture.actor.getUniverseManager().start(100));
    assertSame(previous, fixture.actor.getGameplay().getRogue());
    assertEquals(0, fixture.actor.starts);
  }

  @Test
  void alteredMapAndRoomsAreNotUsedAsSecondTutorial() {
    new SceneEntityLoader().onSceneLoad(fixture.actor.actual);
    GameplayDefinitions.rooms.put(203, new GameplayDefinitions.Room(203, 7, 8000102, 29,
        Map.of(29, 9999)));
    assertTrue(RogueMapPools.assign(2, fixture.actor.getUid(), 100).isEmpty());
    var previous = fixture.actor.getGameplay().getRogue();
    assertEquals(Retcode.ROGUE_AREA_INVALID, fixture.actor.getUniverseManager().start(100));
    assertSame(previous, fixture.actor.getGameplay().getRogue());
  }

  @Test
  void secondTestUsesItsOwnThreeRoomsAndStartAnchor() {
    var old = fixture.actor.getGameplay().getRogue();
    new SceneEntityLoader().onSceneLoad(fixture.actor.actual);
    assertEquals(Map.of(1, 201, 2, 202, 3, 203),
        RogueMapPools.assign(2, fixture.actor.getUid(), 100).orElseThrow().rooms());
    assertEquals(Retcode.SUCC, fixture.actor.getUniverseManager().start(100));
    var current = fixture.actor.getGameplay().getRogue();
    assertNotEquals(old.getRunId(), current.getRunId());
    assertEquals(2, current.getMapId());
    assertEquals(201, current.getRoomId());
    assertEquals(8000102, fixture.actor.getEntryId());
    assertEquals(-42980, fixture.actor.getPos().getX());
    assertEquals(-91380, fixture.actor.getPos().getZ());
    assertEquals(GameplayState.RogueRun.Phase.FINISHED, old.getPhase());
  }

  @Test
  void nativeEmptySelectionUsesCurrentFormalPartyAtSecondEntrance() {
    new SceneEntityLoader().onSceneLoad(fixture.actor.actual);
    var normal = fixture.actor.getCurrentLineup();
    var selected = List.copyOf(normal.getAvatars());
    assertFalse(selected.isEmpty());
    assertEquals(Retcode.SUCC,
        fixture.actor.getUniverseManager().start(100, List.of(), 0, false));
    assertEquals(selected, fixture.actor.getGameplay().getRogue().getRoster());
    assertEquals(selected, normal.getAvatars(), "Entering must not modify the normal lineup");
    assertEquals(201, fixture.actor.getGameplay().getRogue().getRoomId());
  }

  @Test
  void emptyNativeStartGetsARealResponseAndSecondMap() throws Exception {
    assertEquals(0, nativeStart(List.of(), List.of(), 0));
    assertEquals(2, fixture.actor.getGameplay().getRogue().getMapId());
    assertEquals(201, fixture.actor.getGameplay().getRogue().getRoomId());
  }

  @ParameterizedTest
  @ValueSource(ints = {4000003, 4000004})
  void observedTrialWireFieldEntersSecondMapWithoutChangingOwnedParty(int protagonist) throws Exception {
    gender(protagonist);
    var normal = fixture.actor.getCurrentLineup();
    var roster = List.copyOf(normal.getAvatars());
    var owned = java.util.Set.copyOf(fixture.actor.getAvatars().getAvatars().keySet());
    // Live command 1805: field 10 = 100, packed field 4 = [4000004,4000001,4000002].
    // This field is wrongly named unfinished_story_line_id_list in the frozen descriptor.
    assertEquals(0, nativeStart(List.of(protagonist, 4000001, 4000002), List.of(), 0));
    var run = fixture.actor.getGameplay().getRogue();
    assertEquals(2, run.getMapId());
    assertEquals(201, run.getRoomId());
    assertEquals(roster, run.getRoster(), "Documented formal-party compatibility remains explicit");
    assertEquals(roster, normal.getAvatars());
    assertEquals(owned, fixture.actor.getAvatars().getAvatars().keySet());
    assertTrue(fixture.actor.getAvatars().getTempAvatars().isEmpty());
    var saved = fixture.gson.fromJson(fixture.gson.toJson(run), GameplayState.RogueRun.class);
    saved.setInside(false);
    fixture.actor.getGameplay().setRogue(saved);
    assertEquals(Retcode.SUCC, fixture.actor.getUniverseManager().resume(100, false));
    assertEquals(run.getRunId(), saved.getRunId());
    assertEquals(roster, saved.getRoster());
    assertEquals(-42980, fixture.actor.getPos().getX());
  }

  @Test
  void trialFieldRejectsWrongGenderDuplicatesUnknownAndIncompleteCombinations() throws Exception {
    gender(4000004);
    var prior = fixture.actor.getGameplay().getRogue();
    for (var trials : List.of(List.of(4000003, 4000001, 4000002),
        List.of(4000004, 4000001, 4000001), List.of(4000004, 4000001, 9999999),
        List.of(4000004, 4000001), List.of(4030002))) {
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), nativeStart(trials, List.of(), 0));
      assertSame(prior, fixture.actor.getGameplay().getRogue());
    }
    assertEquals(0, fixture.actor.starts);
  }

  @Test
  void trialFieldCannotAuthorizeAnotherMissionOrMissingTrialSource() throws Exception {
    gender(4000004);
    var prior = fixture.actor.getGameplay().getRogue();
    var mission = fixture.actor.getGameplay().getMainMissions().remove(4030002);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), nativeStart(List.of(4000004, 4000001, 4000002), List.of(), 0));
    fixture.actor.getGameplay().getMainMissions().put(4030002, mission);
    GameData.getSpecialAvatarExcelMap().values().removeIf(r -> r.getId() == 4000001);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), nativeStart(List.of(4000004, 4000001, 4000002), List.of(), 0));
    assertSame(prior, fixture.actor.getGameplay().getRogue());
    assertEquals(0, fixture.actor.starts);
  }

  @Test
  void trialFieldCannotBeMixedWithFormalSelectionOrPathChoice() throws Exception {
    gender(4000004);
    var trials = List.of(4000004, 4000001, 4000002);
    var prior = fixture.actor.getGameplay().getRogue();
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), nativeStart(trials, List.of(199961), 0));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), nativeStart(trials, List.of(), 1));
    assertSame(prior, fixture.actor.getGameplay().getRogue());
    assertEquals(0, fixture.actor.starts);
  }

  private void gender(int protagonist) throws Exception {
    var field = Player.class.getDeclaredField("gender");
    field.setAccessible(true);
    field.set(fixture.actor, protagonist == 4000003 ? PlayerGender.GENDER_MAN : PlayerGender.GENDER_WOMAN);
  }

  private int nativeStart(List<Integer> trials, List<Integer> formal, int aeon) throws Exception {
    var original = fixture.actor.actual;
    var scene = new Scene(fixture.actor, original.getExcel(), 80112001) {
      @Override public SceneInfo toProto() {
        return SceneInfo.newInstance().setEntryId(fixture.actor.getEntryId())
            .setPlaneId(fixture.actor.getPlaneId()).setFloorId(fixture.actor.getFloorId());
      }
    };
    scene.setEntryId(8011201);
    scene.getResourceGroups().putAll(original.getResourceGroups());
    fixture.actor.actual = scene;
    new SceneEntityLoader().onSceneLoad(scene);
    var session = new CapturingSession();
    session.actor = fixture.actor;
    fixture.actor.session = session;
    var season = new GameplayDefinitions.RogueSeason(
        199963, 1, List.of(100), "2024-01-01 00:00:00", "2099-01-01 00:00:00");
    GameplayDefinitions.rogueSeasons.add(season);
    GameplayDefinitions.rogueConstants.put("Start_Rogue_Coin_Base", "50");
    try {
      var descriptor = session.getProtocol().clientSchema().message("StartRogueCsReq");
      var request = com.google.protobuf.DynamicMessage.newBuilder(descriptor)
          .setField(descriptor.findFieldByName("area_id"), 100)
          .setField(descriptor.findFieldByName("AFEMBLFCCCH"), aeon);
      for (int id : formal) request.addRepeatedField(descriptor.findFieldByName("NOEPBNMJFGL"), id);
      // Use the observed wire number rather than the misleading semantic name.
      for (int id : trials) request.addRepeatedField(descriptor.findFieldByNumber(4), id);
      if (!trials.isEmpty() && formal.isEmpty() && aeon == 0 && trials.size() == 3)
        assertEquals(16, request.build().toByteArray().length);
      assertTrue(Universe46.handle(session,
          session.getProtocol().clientCommand("StartRogueCsReq"), request.build().toByteArray()));
      assertEquals("StartRogueScRsp", session.name);
      var response = session.getProtocol().clientSchema().parse(session.name, session.response);
      return (Integer) response.getField(response.getDescriptorForType().findFieldByName("retcode"));
    } finally {
      GameplayDefinitions.rogueSeasons.remove(season);
    }
  }

  @Test
  void secondTutorialPartyAndAnchorSurviveSavedRunRoundTrip() {
    new SceneEntityLoader().onSceneLoad(fixture.actor.actual);
    assertEquals(Retcode.SUCC, fixture.actor.getUniverseManager().start(100, List.of(), 0, false));
    var saved = fixture.actor.getGameplay().getRogue();
    var copy = fixture.gson.fromJson(fixture.gson.toJson(saved), GameplayState.RogueRun.class);
    copy.setInside(false);
    fixture.actor.getGameplay().setRogue(copy);
    assertEquals(Retcode.SUCC, fixture.actor.getUniverseManager().resume(100, false));
    assertEquals(saved.getRunId(), copy.getRunId());
    assertEquals(saved.getRoster(), copy.getRoster());
    assertEquals(2, copy.getMapId());
    assertEquals(201, copy.getRoomId());
    assertEquals(-42980, fixture.actor.getPos().getX());
    assertEquals(-91380, fixture.actor.getPos().getZ());
  }

  @Test
  void fallbackDoesNotAcceptAnUnownedPartyMember() {
    new SceneEntityLoader().onSceneLoad(fixture.actor.actual);
    fixture.actor.getCurrentLineup().getAvatars().add(199962);
    var prior = fixture.actor.getGameplay().getRogue();
    assertEquals(Retcode.REQ_PARA_INVALID,
        fixture.actor.getUniverseManager().start(100, List.of(), 0, false));
    assertSame(prior, fixture.actor.getGameplay().getRogue());
  }

  @Test
  void emptySelectionIsNotAUniversalFallback() {
    new SceneEntityLoader().onSceneLoad(fixture.actor.actual);
    var prior = fixture.actor.getGameplay().getRogue();
    fixture.actor.getGameplay().getMainMissions().remove(4030002);
    assertEquals(Retcode.REQ_PARA_INVALID,
        fixture.actor.getUniverseManager().start(100, List.of(), 0, false));
    assertSame(prior, fixture.actor.getGameplay().getRogue());
  }

  @Test
  void cannotStartSecondTutorialWhenEntranceIsNotLoaded() {
    var previous = fixture.actor.getGameplay().getRogue();
    assertEquals(Retcode.ROGUE_STATUS_NOT_MATCH, fixture.actor.getUniverseManager().start(100));
    assertSame(previous, fixture.actor.getGameplay().getRogue());
  }

  @Test
  void explicitlyDisabledOperatorPoolIsRespected() {
    GameplayDefinitions.roomPoolByMap.put(2, Map.of(1, List.of()));
    assertTrue(RogueMapPools.assign(2, fixture.actor.getUid(), 100).isEmpty());
  }
}
