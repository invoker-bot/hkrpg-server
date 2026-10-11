package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original catch requests and mission outcomes must precede a reward-group spawn. */
class Mascot46Test {
  @TempDir Path root;
  final WorldChestActivity46Test fixture = new WorldChestActivity46Test();
  final Map<Integer, MissionInfo> oldMissions = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  JsonObject mascot;
  Session session;

  class Session extends GameSession {
    final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
    DynamicMessage response;

    Session() {
      super(null);
      setState(SessionState.ACTIVE);
    }

    public Player getPlayer() {
      return fixture.player;
    }

    public ProtocolBridge getProtocol() {
      return bridge;
    }

    public void sendClientPacket(String name, byte[] data) {
      if (name.equals("GroupStateChangeScRsp"))
        try {
          response = bridge.clientSchema().parse(name, data);
        } catch (Exception e) {
          throw new AssertionError(e);
        }
    }
  }

  @BeforeEach
  void setup() throws Exception {
    mascot =
        JsonParser.parseReader(
                new java.io.InputStreamReader(
                    getClass().getResourceAsStream("/fixtures/mascot-4.6.json"),
                    StandardCharsets.UTF_8))
            .getAsJsonObject();
    for (String file :
        List.of("time-relay-flame-4.6.json", "golden-spear-4.6.json", "mascot-4.6.json")) {
      var extra =
          JsonParser.parseReader(
                  new java.io.InputStreamReader(
                      getClass().getResourceAsStream("/fixtures/" + file), StandardCharsets.UTF_8))
              .getAsJsonObject();
      for (var e : extra.getAsJsonObject("records").entrySet()) {
        var rows = fixture.source.getAsJsonObject("records").getAsJsonArray(e.getKey());
        if (rows == null)
          fixture.source.getAsJsonObject("records").add(e.getKey(), e.getValue().deepCopy());
        else for (var r : e.getValue().getAsJsonArray()) rows.add(r.deepCopy());
      }
      for (String section : List.of("groups", "locations", "headers"))
        for (var e : extra.getAsJsonObject(section).entrySet())
          fixture.source.getAsJsonObject(section).add(e.getKey(), e.getValue().deepCopy());
      for (var e : extra.getAsJsonObject("floors").entrySet()) {
        var current = fixture.source.getAsJsonObject("floors").getAsJsonObject(e.getKey());
        if (current == null)
          fixture.source.getAsJsonObject("floors").add(e.getKey(), e.getValue().deepCopy());
        else {
          var ids = new HashSet<Integer>();
          current
              .getAsJsonArray("GroupInstanceList")
              .forEach(g -> ids.add(g.getAsJsonObject().get("ID").getAsInt()));
          e.getValue()
              .getAsJsonObject()
              .getAsJsonArray("GroupInstanceList")
              .forEach(
                  g -> {
                    if (ids.add(g.getAsJsonObject().get("ID").getAsInt()))
                      current.getAsJsonArray("GroupInstanceList").add(g.deepCopy());
                  });
          for (var dimension : current.getAsJsonArray("DimensionList")) {
            var d = dimension.getAsJsonObject();
            var indexes = new JsonArray();
            if (!d.has("ID") || d.get("ID").getAsInt() == 1)
              for (int i = 0; i < current.getAsJsonArray("GroupInstanceList").size(); i++)
                indexes.add(i);
            d.add("GroupIndexList", indexes);
          }
        }
      }
    }
    fixture.root = root;
    fixture.load();
    for (int id : List.of(4015704, 4015705, 4015706)) {
      var header =
          mascot
              .getAsJsonObject("headers")
              .getAsJsonObject("Config/Level/Mission/" + id + "/MissionInfo_" + id + ".json")
              .deepCopy();
      var partial =
          mascot
              .getAsJsonObject("headers")
              .getAsJsonObject(
                  "Config/Level/Mission/" + id + "/MissionInfo_" + id + ".partial.json");
      partial.entrySet().forEach(e -> header.add(e.getKey(), e.getValue().deepCopy()));
      var info = fixture.gson.fromJson(header, MissionInfo.class);
      oldMissions.put(id, GameData.getMissionInfos().put(id, info));
      for (var sub : info.getSubMissionList())
        oldSubs.put(sub.getId(), GameplayDefinitions.subs.put(sub.getId(), sub));
    }
    var q = new QuestState(1040101);
    q.advance(1, 1, 1);
    q.reserveReward();
    q.closeReward();
    fixture.player.getGameplay().getMainMissions().put(1040101, q);
    fixture.player.getMissionManager().refresh();
    fixture.scene();
    session = new Session();
  }

  @AfterEach
  void cleanup() throws Exception {
    oldMissions.forEach(
        (k, v) -> {
          if (v == null) GameData.getMissionInfos().remove(k);
          else GameData.getMissionInfos().put(k, v);
        });
    oldSubs.forEach(
        (k, v) -> {
          if (v == null) GameplayDefinitions.subs.remove(k);
          else GameplayDefinitions.subs.put(k, v);
        });
    fixture.restore();
  }

  JsonObject control(int chest) {
    return mascot.getAsJsonObject("controls").getAsJsonObject(String.valueOf(chest));
  }

  void travel(int chest) {
    var row = control(chest);
    fixture.player.plane = row.get("plane").getAsInt();
    fixture.player.floor = row.get("floor").getAsInt();
    fixture.player.entry = fixture.player.plane * 100 + 1;
    fixture.scene();
  }

  int request(int chest, int state) throws Exception {
    var schema = session.bridge.clientSchema();
    var group = DynamicMessage.newBuilder(schema.message("GroupStateInfo"));
    group.setField(
        group.getDescriptorForType().findFieldByName("group_id"),
        control(chest).get("group").getAsInt());
    group.setField(group.getDescriptorForType().findFieldByName("entry_id"), fixture.player.entry);
    group.setField(group.getDescriptorForType().findFieldByName("group_state"), state);
    var b = DynamicMessage.newBuilder(schema.message("GroupStateChangeCsReq"));
    b.setField(b.getDescriptorForType().findFieldByName("group_state_info"), group.build());
    assertTrue(
        VersionedPacketHandler.handle(
            session,
            session.bridge.clientCommand("GroupStateChangeCsReq"),
            b.build().toByteArray()));
    assertNotNull(session.response);
    return (Integer)
        session.response.getField(
            session.response.getDescriptorForType().findFieldByName("retcode"));
  }

  void catchAndReturn(int chest) throws Exception {
    catchAndTalk(chest);
    assertEquals(0, request(chest, 4));
  }

  void catchAndTalk(int chest) throws Exception {
    travel(chest);
    assertEquals(0, request(chest, 1));
    assertEquals(0, request(chest, 2));
    var c = control(chest);
    if (c.get("battle").getAsInt() > 0) fixture.player.getMissionManager().onStageWin(20411244);
    assertEquals(
        Retcode.SUCC,
        fixture
            .player
            .getMissionManager()
            .finishTalk(c.get("talk").getAsInt(), "Mission_" + c.get("talk").getAsInt()));
    assertEquals(3, fixture.player.scene.getGroups().get(c.get("group").getAsInt()).getState());
  }

  @ParameterizedTest
  @ValueSource(ints = {20411614, 20411625, 20412620})
  void conditionalRewardGroupsAreAbsentBeforeTheCatch(int chest) {
    travel(chest);
    assertFalse(
        fixture
            .player
            .scene
            .getLoadedGroups()
            .contains(control(chest).get("rewardGroup").getAsInt()));
  }

  @ParameterizedTest
  @ValueSource(ints = {20411614, 20411625, 20412620})
  void nativeStealAndCatchRequestsPersistInTheOriginalGroup(int chest) throws Exception {
    travel(chest);
    assertEquals(0, request(chest, 1));
    assertEquals(
        1, fixture.player.scene.getGroups().get(control(chest).get("group").getAsInt()).getState());
    fixture.scene();
    assertEquals(
        1, fixture.player.scene.getGroups().get(control(chest).get("group").getAsInt()).getState());
    assertEquals(0, request(chest, 2));
  }

  @Test
  void allThreeSourceGoalsAreAcceptedWithoutInitialProgress() {
    for (int id : List.of(6029853, 6029854, 6029855)) {
      assertNotNull(fixture.state(id));
      assertEquals(0, fixture.progress(id));
    }
  }

  @Test
  void originalMissionsGateTheRewardAndOpeningCreditsOnlyTheMascotGoals() throws Exception {
    fixture.player.getQuestManager().refreshUnlocks();
    assertNotNull(fixture.state(6029853));
    for (int chest : List.of(20411614, 20411625, 20412620)) {
      catchAndReturn(chest);
      var actor = fixture.chest(chest);
      assertSame(actor, fixture.player.interactWithProp(112131, actor.getEntityId()));
      assertNull(fixture.player.interactWithProp(112131, actor.getEntityId()));
      assertTrue(
          fixture
              .player
              .getGameplay()
              .getWorldChests()
              .valid(WorldChestRules.load(), fixture.player));
    }
    assertEquals(1, fixture.progress(6029853));
    assertEquals(2, fixture.progress(6029854));
    assertEquals(3, fixture.progress(6029855));
    assertEquals(0, fixture.progress(6029856));
    assertEquals(0, fixture.progress(6029858));
  }

  @Test
  void aFailedSaveLeavesTheOriginalGroupAtZero() throws Exception {
    fixture.player.failSave = true;
    assertNotEquals(0, request(20411614, 1));
    var g = fixture.player.scene.getGroups().get(251);
    assertTrue(g == null || g.getState() == 0);
  }

  @Test
  void arbitrarySavedFloorValuesCannotRevealARewardGroup() {
    fixture
        .player
        .getGameplay()
        .getStoryValues()
        .put("floor:20411001:FSV_QTEGotchard_RewardGet_01", 3);
    fixture.player.getGameplay().getStoryValues().put("group-state:20411001:251", 4);
    fixture.scene();
    assertFalse(fixture.player.scene.getLoadedGroups().contains(252));
  }

  @Test
  void stagesCannotBeSkippedReplayedOrCompletedByAnEarlyTalk() throws Exception {
    for (int state : List.of(2, 3, 4)) assertNotEquals(0, request(20411614, state));
    assertTrue(fixture.player.getGameplay().getWorldChests().getMascots().isEmpty());
    assertNotEquals(
        Retcode.SUCC,
        fixture.player.getMissionManager().finishTalk(401570402, "Mission_401570402"));
    assertEquals(0, request(20411614, 1));
    assertNotEquals(0, request(20411614, 1));
    assertNotEquals(0, request(20411614, 4));
    assertEquals(0, request(20411614, 2));
    assertNotEquals(0, request(20411614, 2));
    assertNotEquals(0, request(20411614, 3));
    assertNotEquals(0, request(20411614, 4));
    assertFalse(fixture.player.scene.getLoadedGroups().contains(252));
    assertEquals(
        2, fixture.player.getGameplay().getWorldChests().getMascots().get(20411614).state());
  }

  @Test
  void failedCatchAndRevealSavesKeepTheLastSavedStageAndNoRewardGroup() throws Exception {
    assertEquals(0, request(20411614, 1));
    fixture.player.failSave = true;
    assertNotEquals(0, request(20411614, 2));
    assertEquals(
        1, fixture.player.getGameplay().getWorldChests().getMascots().get(20411614).state());
    assertFalse(MissionStateRecords.subCompleted(fixture.player, 401570401));
    fixture.player.failSave = false;
    assertEquals(0, request(20411614, 2));
    assertEquals(
        Retcode.SUCC,
        fixture.player.getMissionManager().finishTalk(401570402, "Mission_401570402"));
    fixture.player.failSave = true;
    assertNotEquals(0, request(20411614, 4));
    assertEquals(
        3, fixture.player.getGameplay().getWorldChests().getMascots().get(20411614).state());
    assertEquals(
        1,
        fixture
            .player
            .getGameplay()
            .getStoryValues()
            .get("floor:20411001:FSV_QTEGotchard_RewardGet_01"));
    assertFalse(fixture.player.scene.getLoadedGroups().contains(252));
    fixture.player.failSave = false;
    assertEquals(0, request(20411614, 4));
    fixture.player.getGameplay().worldChests(fixture.player.saved.getWorldChests());
    fixture.scene();
    assertEquals(4, fixture.player.scene.getGroups().get(251).getState());
    assertNotNull(fixture.chest(20411614));
  }

  @Test
  void invalidJournalAndChangedMissionSourceCannotRevealTheReward() throws Exception {
    assertEquals(0, request(20411614, 1));
    var good = fixture.gson.toJsonTree(fixture.player.getGameplay().getWorldChests());
    var invalid = good.deepCopy().getAsJsonObject();
    invalid.getAsJsonObject("mascots").getAsJsonObject("20411614").add("steps", JsonNull.INSTANCE);
    fixture.player.getGameplay().worldChests(fixture.gson.fromJson(invalid, WorldChestState.class));
    assertNotEquals(0, request(20411614, 2));
    assertDoesNotThrow(() -> fixture.player.getMissionManager().refresh());
    fixture.scene();
    assertFalse(fixture.player.scene.getLoadedGroups().contains(252));
    fixture.player.getGameplay().worldChests(fixture.gson.fromJson(good, WorldChestState.class));
    fixture.scene();
    var original = GameplayDefinitions.subs.get(401570402);
    var changed = fixture.gson.toJsonTree(original).getAsJsonObject();
    changed.addProperty("Progress", 2);
    GameplayDefinitions.subs.put(
        401570402, fixture.gson.fromJson(changed, MissionInfo.SubMissionInfo.class));
    assertNotEquals(0, request(20411614, 2));
    GameplayDefinitions.subs.put(401570402, original);
    assertEquals(0, request(20411614, 2));
  }

  @Test
  void protocolUnknownFieldsAndCrossEntryRequestsCannotAdvanceTheCatch() throws Exception {
    assertEquals(0, request(20411614, 1));
    var info = session.response;
    var row =
        (DynamicMessage)
            info.getField(info.getDescriptorForType().findFieldByName("group_state_info"));
    var group = row.toBuilder();
    group.setField(row.getDescriptorForType().findFieldByName("group_state"), 2);
    var request =
        DynamicMessage.newBuilder(session.bridge.clientSchema().message("GroupStateChangeCsReq"));
    request.setField(
        request.getDescriptorForType().findFieldByName("group_state_info"), group.build());
    request.setUnknownFields(
        com.google.protobuf.UnknownFieldSet.newBuilder()
            .addField(
                1000, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build());
    assertTrue(
        VersionedPacketHandler.handle(
            session,
            session.bridge.clientCommand("GroupStateChangeCsReq"),
            request.build().toByteArray()));
    assertNotEquals(
        0,
        (Integer)
            session.response.getField(
                session.response.getDescriptorForType().findFieldByName("retcode")));
    fixture.player.entry = 2041201;
    assertNotEquals(0, request(20411614, 2));
    fixture.player.entry = 2041101;
    assertEquals(0, request(20411614, 2));
    assertFalse(fixture.player.scene.getLoadedGroups().contains(252));
  }

  @Test
  void olderWorldChestDataRetainsItsReceiptsAndExpiredActivityDoesNotCaptureNewCredit()
      throws Exception {
    fixture.player.getQuestManager().refreshUnlocks();
    var old =
        fixture.gson.toJsonTree(fixture.player.getGameplay().getWorldChests()).getAsJsonObject();
    old.remove("mascots");
    fixture.player.getGameplay().worldChests(fixture.gson.fromJson(old, WorldChestState.class));
    assertTrue(
        fixture
            .player
            .getGameplay()
            .getWorldChests()
            .valid(WorldChestRules.load(), fixture.player));
    GameplayDefinitions.windows.clear();
    catchAndReturn(20411614);
    var chest = fixture.chest(20411614);
    assertSame(chest, fixture.player.interactWithProp(112131, chest.getEntityId()));
    assertTrue(
        fixture
            .player
            .getGameplay()
            .getWorldChests()
            .getRecords()
            .get(20411614)
            .getAcceptances()
            .isEmpty());
    assertEquals(
        0, fixture.player.getGameplay().getWorldChests().getQuests().get(6029853).getProgress());
    assertNull(fixture.player.interactWithProp(112131, chest.getEntityId()));
  }
}
