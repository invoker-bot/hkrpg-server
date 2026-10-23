package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.ByteString;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Original activity and map sources. Story prerequisites and group loading are isolated fixtures.
 */
class TimelineActivity46Test {
  @Test void lockedActivityDoesNotScanItsTimelineSourcesDuringLoginRefresh() throws Exception {
    assertTrue(ActivityAccess.unlocked(fixture.actor, 30013));
    fixture.actor.getGameplay().getMainMissions().clear();
    assertFalse(ActivityAccess.unlocked(fixture.actor, 30013));
    var reads = org.hkrpg.server.SourceReadAssertions.reads(root, () -> {
      assertFalse(TimelineActivityQuests.refresh(fixture.actor));
      assertTrue(TimelineActivityQuests.states(fixture.actor).isEmpty());
    });
    assertTrue(reads.isEmpty(), "Locked activity scanned resources: " + reads);
  }

  @TempDir Path root;
  final PropTimeline46Test fixture = new PropTimeline46Test();
  final Gson gson = ResourceLoader.getGson();
  final JsonObject source =
      JsonParser.parseReader(
              new InputStreamReader(
                  Objects.requireNonNull(
                      getClass().getResourceAsStream("/fixtures/timeline-activity-4.6.json")),
                  StandardCharsets.UTF_8))
          .getAsJsonObject();
  final List<QuestExcel> oldQuests = new ArrayList<>();
  final List<FinishWayExcel> oldFinishes = new ArrayList<>();
  final List<RewardExcel> oldRewards = new ArrayList<>();
  final List<ItemExcel> oldItems = new ArrayList<>();
  final List<ActivityPanelExcel> oldPanels = new ArrayList<>();
  final List<GameplayDefinitions.Window> oldWindows = new ArrayList<>();
  String previous;
  JsonObject selected;
  GroupInfo group;

  @BeforeEach
  void load() throws Exception {
    fixture.load();
    previous = LunarCore.getConfig().getResourceDir();
    resourceRoot(root.toString());
    oldQuests.addAll(GameData.getQuestExcelMap().values());
    oldFinishes.addAll(GameData.getFinishWayExcelMap().values());
    oldRewards.addAll(GameData.getRewardExcelMap().values());
    oldItems.addAll(GameData.getItemExcelMap().values());
    oldPanels.addAll(GameData.getActivityPanelExcelMap().values());
    oldWindows.addAll(GameplayDefinitions.windows);
    GameData.getQuestExcelMap().values().clear();
    GameData.getFinishWayExcelMap().values().clear();
    GameData.getRewardExcelMap().values().clear();
    GameData.getItemExcelMap().values().clear();
    GameData.getActivityPanelExcelMap().values().clear();
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(30013, 3001301, 1, Long.MAX_VALUE));
    for (var entry : source.getAsJsonObject("records").entrySet()) {
      write(entry.getKey(), entry.getValue());
      for (var row : entry.getValue().getAsJsonArray()) {
        switch (entry.getKey()) {
          case "ExcelOutput/QuestData.json" ->
              GameData.getQuestExcelMap().add(gson.fromJson(row, QuestExcel.class));
          case "ExcelOutput/FinishWay.json" ->
              GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
          case "ExcelOutput/RewardData.json" -> {
            var reward = gson.fromJson(row, RewardExcel.class);
            reward.onLoad();
            GameData.getRewardExcelMap().add(reward);
          }
          case "ExcelOutput/ItemConfig.json" ->
              GameData.getItemExcelMap().add(gson.fromJson(row, ItemExcel.class));
          case "ExcelOutput/ActivityPanel.json" ->
              GameData.getActivityPanelExcelMap().add(gson.fromJson(row, ActivityPanelExcel.class));
          case "ExcelOutput/MainMission.json" ->
              GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
          case "ExcelOutput/MazePlane.json" -> {
            var value = gson.fromJson(row, MazePlaneExcel.class);
            if (!fixture.oldPlanes.containsKey(value.getPlaneID()))
              fixture.oldPlanes.put(
                  value.getPlaneID(), GameData.getMazePlaneExcelMap().get(value.getPlaneID()));
            GameData.getMazePlaneExcelMap().add(value);
          }
          case "ExcelOutput/MapEntrance.json" -> {
            var value = gson.fromJson(row, MapEntranceExcel.class);
            if (!fixture.oldEntries.containsKey(value.getId()))
              fixture.oldEntries.put(
                  value.getId(), GameData.getMapEntranceExcelMap().get(value.getId()));
            GameData.getMapEntranceExcelMap().add(value);
          }
          case "ExcelOutput/MazeProp.json" -> {
            var raw = row.getAsJsonObject().deepCopy();
            int id = raw.get("ID").getAsInt();
            if (!fixture.oldProps.containsKey(id))
              fixture.oldProps.put(id, GameData.getPropExcelMap().get(id));
            if (raw.has("PropName") && raw.get("PropName").isJsonObject())
              raw.addProperty("PropName", raw.getAsJsonObject("PropName").get("Hash").getAsLong());
            var value = gson.fromJson(raw, PropExcel.class);
            value.onLoad();
            GameData.getPropExcelMap().add(value);
          }
        }
      }
    }
    for (var raw : source.getAsJsonArray("catalogGroups")) {
      var row = raw.getAsJsonObject();
      write(row.get("path").getAsString(), row.get("definition"));
    }
    for (var e : source.getAsJsonObject("floors").entrySet()) write(e.getKey(), e.getValue());
    fixture.actor.setLevel(21);
    var completed = new QuestState(1000510);
    completed.advance(1, 1, 1);
    fixture.actor.getGameplay().getMainMissions().put(1000510, completed);
    for (var raw : source.getAsJsonArray("prerequisites")) {
      var row = raw.getAsJsonObject();
      write(row.get("path").getAsString(), row.get("definition"));
      int main = row.get("main").getAsInt(), id = row.get("sub").getAsInt();
      var condition = gson.fromJson(row.get("condition"), MissionInfo.SubMissionInfo.class);
      GameplayDefinitions.subs.put(id, condition);
      fixture.actor.getGameplay().getMainMissions().put(main, new QuestState(main));
      var state = new QuestState(id);
      state.advance(Math.max(1, condition.getProgress()), Math.max(1, condition.getProgress()), 1);
      fixture.actor.getGameplay().getSubMissions().put(id, state);
    }
  }

  void write(String path, JsonElement value) throws Exception {
    var file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, gson.toJson(value));
  }

  static void resourceRoot(String value) throws Exception {
    var field = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
    field.setAccessible(true);
    field.set(LunarCore.getConfig(), value);
  }

  @AfterEach
  void restore() throws Exception {
    GameData.getQuestExcelMap().values().clear();
    oldQuests.forEach(GameData.getQuestExcelMap()::add);
    GameData.getFinishWayExcelMap().values().clear();
    oldFinishes.forEach(GameData.getFinishWayExcelMap()::add);
    GameData.getRewardExcelMap().values().clear();
    oldRewards.forEach(GameData.getRewardExcelMap()::add);
    GameData.getItemExcelMap().values().clear();
    oldItems.forEach(GameData.getItemExcelMap()::add);
    GameData.getActivityPanelExcelMap().values().clear();
    oldPanels.forEach(GameData.getActivityPanelExcelMap()::add);
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.addAll(oldWindows);
    resourceRoot(previous);
    fixture.restore();
  }

  void activate(int quest) {
    selected =
        source.getAsJsonArray("groups").asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(r -> r.get("quest").getAsInt() == quest)
            .findFirst()
            .orElseThrow();
    activateSelected();
  }

  void activateSelected() {
    int plane = selected.get("plane").getAsInt(), floorId = selected.get("floor").getAsInt();
    String key = "P" + plane + "_F" + floorId;
    var floor =
        gson.fromJson(
            source
                .getAsJsonObject("floors")
                .get("Config/LevelOutput/RuntimeFloor/" + key + ".json"),
            FloorInfo.class);
    group = gson.fromJson(selected.get("definition"), GroupInfo.class);
    group.setId(selected.get("group").getAsInt());
    floor.getGroups().put(group.getId(), group);
    if (!fixture.oldFloors.containsKey(key))
      fixture.oldFloors.put(key, GameData.getFloorInfos().get(key));
    GameData.getFloorInfos().put(key, floor);
    rebuild();
  }

  void rebuild() {
    int plane = selected.get("plane").getAsInt(), floor = selected.get("floor").getAsInt();
    fixture.actor.current =
        new Scene(fixture.actor, GameData.getMazePlaneExcelMap().get(plane), floor);
    var entry =
        source
            .getAsJsonObject("records")
            .getAsJsonArray("ExcelOutput/MapEntrance.json")
            .asList()
            .stream()
            .map(JsonElement::getAsJsonObject)
            .filter(r -> r.get("PlaneID").getAsInt() == plane)
            .findFirst()
            .orElseThrow();
    fixture.actor.current.setEntryId(entry.get("ID").getAsInt());
    fixture.actor.current.loadGroup(group);
  }

  EntityProp prop() {
    return fixture.actor.current.getEntities().values().stream()
        .filter(
            e ->
                e instanceof EntityProp
                    && e.getGroupId() == group.getId()
                    && e.getInstId() == selected.get("instance").getAsInt())
        .map(e -> (EntityProp) e)
        .findFirst()
        .orElseThrow();
  }

  QuestState state(int id) {
    return fixture.actor.getQuestManager().states().stream()
        .filter(q -> q.getQuestId() == id)
        .findFirst()
        .orElse(null);
  }

  QuestState accepted(int id) {
    var value = state(id);
    assertNotNull(value, "Original nonperiodic exploration quest is unavailable");
    return value;
  }

  byte[] finishRequest(int id, int groupId, int propId) {
    var req = Gameplay46.builder(fixture.session, "FinishQuestCsReq");
    Gameplay46.set(req, "quest_id", id);
    Gameplay46.set(req, "group_id", groupId);
    Gameplay46.set(req, "prop_id", propId);
    return req.build().toByteArray();
  }

  int finish(byte[] bytes) throws Exception {
    assertTrue(
        VersionedPacketHandler.handle(fixture.session, 915, bytes),
        "Native timeline-count request is unavailable");
    assertEquals("FinishQuestScRsp", fixture.session.responseName);
    return Gameplay46.number(fixture.session.response, "PLDKENJLKME");
  }

  int arm(int id) throws Exception {
    return finish(finishRequest(id, group.getId(), prop().getInstId()));
  }

  int report(String value) throws Exception {
    var timeline = Gameplay46.builder(fixture.session, "PropTimelineInfo");
    Gameplay46.set(
        timeline, "timeline_byte_value", ByteString.copyFrom(value, StandardCharsets.US_ASCII));
    var request = Gameplay46.builder(fixture.session, "ChangePropTimelineInfoCsReq");
    Gameplay46.set(request, "prop_entity_id", prop().getEntityId());
    Gameplay46.set(request, "interact_id", fixture.actor.current.getRuntimeId());
    Gameplay46.set(request, "timeline_info", timeline.build());
    assertTrue(VersionedPacketHandler.handle(fixture.session, 1483, request.build().toByteArray()));
    return Gameplay46.number(fixture.session.response, "retcode");
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029848, 6029849})
  void originalQuestIsPublishedAtZeroWithoutManufacturingCompletion(int id) {
    activate(id);
    var value = accepted(id);
    assertEquals(QuestState.Phase.DOING, value.getPhase());
    assertEquals(0, value.getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029848, 6029849})
  void eachAcceptedChangedStateAdvancesOnlyOneOfTheThreeOriginalCounts(int id) throws Exception {
    activate(id);
    accepted(id);
    for (int i = 0; i < 3; i++) {
      assertEquals(0, arm(id));
      assertEquals(i, accepted(id).getProgress());
      assertEquals(0, report(i % 2 == 0 ? "L0_Idle" : "L1_Break"));
      assertEquals(i + 1, accepted(id).getProgress());
      assertEquals(
          i == 2 ? QuestState.Phase.FINISHED : QuestState.Phase.DOING, accepted(id).getPhase());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029848, 6029849})
  void repeatedFinishMarkersAndUnchangedStateCannotManufactureThreeCounts(int id) throws Exception {
    activate(id);
    accepted(id);
    for (int i = 0; i < 3; i++) assertEquals(0, arm(id));
    assertEquals(0, report("L0_Idle"));
    assertEquals(1, accepted(id).getProgress());
    assertEquals(0, arm(id));
    assertEquals(0, report("L0_Idle"));
    assertEquals(1, accepted(id).getProgress());
    assertEquals(0, report("L1_Break"));
    assertEquals(1, accepted(id).getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029848, 6029849})
  void plainTimelineWritesAndTheAuthoredInitialStateDoNotCount(int id) throws Exception {
    activate(id);
    accepted(id);
    assertEquals(0, report("L0_Idle"));
    assertEquals(0, accepted(id).getProgress());
    assertEquals(0, report("L1_Break"));
    assertEquals(0, arm(id));
    assertEquals(0, report("L1_Break"));
    assertEquals(0, accepted(id).getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029848, 6029849})
  void saveFailureRollsBackBothTimelineAndQuestCountBeforeRetry(int id) throws Exception {
    activate(id);
    accepted(id);
    assertEquals(0, arm(id));
    fixture.actor.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), report("L0_Idle"));
    fixture.actor.failSave = false;
    assertEquals(0, accepted(id).getProgress());
    assertTrue(fixture.actor.getGameplay().getPropTimelines().getRecords().isEmpty());
    assertEquals(0, report("L0_Idle"));
    assertEquals(1, accepted(id).getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029848, 6029849})
  void savedReloadRestoresCountButDiscardsAnUnfinishedRequestPair(int id) throws Exception {
    activate(id);
    accepted(id);
    assertEquals(0, arm(id));
    assertEquals(0, report("L0_Idle"));
    assertEquals(0, arm(id));
    var reloaded = new PropTimeline46Test.Actor();
    reloaded.setLevel(21);
    var f = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    f.setAccessible(true);
    f.set(reloaded, gson.fromJson(gson.toJson(fixture.actor.getGameplay()), GameplayState.class));
    reloaded.lineup.getAvatars().add(1001);
    reloaded.getAvatars().getAvatars().putAll(fixture.actor.getAvatars().getAvatars());
    fixture.actor = reloaded;
    fixture.session = new PropTimeline46Test.Session(reloaded);
    rebuild();
    assertEquals(1, accepted(id).getProgress());
    assertEquals(0, report("L1_Break"));
    assertEquals(1, accepted(id).getProgress());
    assertEquals(0, arm(id));
    assertEquals(0, report("L0_Idle"));
    assertEquals(2, accepted(id).getProgress());
  }
}
