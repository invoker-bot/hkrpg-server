package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityNpc;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original daily groups, with isolated floor group maps and tutorial/position fixtures. */
class MissionGroupConditions46Test {
  final DailyStageWin46Test source = new DailyStageWin46Test();
  final Gson gson = emu.lunarcore.data.ResourceLoader.getGson();
  final Map<Long, GroupInfo> groups = new HashMap<>();
  final Map<Long, JsonObject> rawGroups = new HashMap<>();
  final Map<Integer, MazePlaneExcel> planes = new HashMap<>();
  final Map<Integer, FloorInfo> floors = new HashMap<>();
  final List<NpcExcel> oldNpcs = new ArrayList<>();
  final Map<String, FloorInfo> oldFloors = new HashMap<>();
  @TempDir Path temp;

  DailyStageWin46Test.Actor actor() {
    return source.actor();
  }

  @BeforeEach
  void setup() throws Exception {
    source.temp = temp;
    source.setup();
    oldNpcs.addAll(GameData.getNpcExcelMap().values());
    oldFloors.putAll(GameData.getFloorInfos());
    try (var reader =
        new java.io.InputStreamReader(
            getClass().getResourceAsStream("/fixtures/mission-group-conditions-4.6.json"),
            java.nio.charset.StandardCharsets.UTF_8)) {
      var rows = JsonParser.parseReader(reader).getAsJsonArray();
      assertEquals(12, rows.size());
      for (var raw : rows) {
        var entry = raw.getAsJsonObject();
        var row = entry.getAsJsonObject("row");
        switch (entry.get("kind").getAsString()) {
          case "group" -> {
            int id =
                Integer.parseInt(
                    entry.get("source").getAsString().replaceAll(".*_G([0-9]+)\\.json", "$1"));
            var group = gson.fromJson(row, GroupInfo.class);
            group.setId(id);
            groups.put(group.getOwnerMainMissionID() * 1000L + id, group);
            rawGroups.put(group.getOwnerMainMissionID() * 1000L + id, row);
          }
          case "plane" -> {
            var plane = gson.fromJson(row, MazePlaneExcel.class);
            planes.put(plane.getId(), plane);
          }
          case "floor" -> {
            var floor = gson.fromJson(row, FloorInfo.class);
            floors.put(floor.getFloorID(), floor);
          }
          case "npc" -> GameData.getNpcExcelMap().add(gson.fromJson(row, NpcExcel.class));
          default -> fail("Unexpected source kind");
        }
      }
    }
  }

  @AfterEach
  void cleanup() {
    source.cleanup();
    GameData.getNpcExcelMap().values().clear();
    oldNpcs.forEach(GameData.getNpcExcelMap()::add);
    GameData.getFloorInfos().clear();
    GameData.getFloorInfos().putAll(oldFloors);
  }

  GroupInfo group(int main) {
    int id =
        switch (main) {
          case 3011012 -> 80;
          case 3011112 -> 63;
          case 3011602 -> 67;
          case 3011607 -> 104;
          default -> throw new IllegalArgumentException();
        };
    return groups.get(main * 1000L + id);
  }

  void start(int main) throws Exception {
    source.single(main);
    source.reachStage(main);
  }

  @ParameterizedTest
  @ValueSource(ints = {3011602, 3011607})
  void omittedSourcePhaseMeansAcceptedRatherThanAnUnknownPhase(int main) throws Exception {
    start(main);
    assertNull(
        rawGroups
            .get(main * 1000L + group(main).getId())
            .getAsJsonObject("LoadCondition")
            .getAsJsonArray("Conditions")
            .get(0)
            .getAsJsonObject()
            .get("Phase"));
    assertTrue(actor().getMissionManager().shouldLoadGroup(group(main)));
  }

  @ParameterizedTest
  @ValueSource(ints = {3011012, 3011112})
  void originalOrGroupLoadsForEitherTheStageOrItsFollowingTalk(int main) throws Exception {
    start(main);
    assertEquals(
        "Or",
        rawGroups
            .get(main * 1000L + group(main).getId())
            .getAsJsonObject("LoadCondition")
            .get("Operation")
            .getAsString());
    assertTrue(actor().getMissionManager().shouldLoadGroup(group(main)));
    source.win(source.battle(main));
    assertTrue(actor().getMissionManager().shouldLoadGroup(group(main)));
    source.finishTail(main);
    assertFalse(actor().getMissionManager().shouldLoadGroup(group(main)));
  }

  @ParameterizedTest
  @ValueSource(ints = {3011602, 3011607})
  void actualSceneSpawnsTheOriginalNpcAndRemovesItAfterTheOriginalTail(int main) throws Exception {
    start(main);
    // The legacy Scene serializer requires one slot; zero supplies no actual avatar.
    actor().getCurrentLineup().getAvatars().add(0);
    int floorId = actor().getFloorId();
    var floor = floors.get(floorId);
    floor.getGroups().put(group(main).getId(), group(main));
    GameData.getFloorInfos().put("P" + actor().getPlaneId() + "_F" + floorId, floor);
    var scene = new Scene(actor(), planes.get(actor().getPlaneId()), floorId);
    DailyMissionAcceptance46Test.set(actor(), "scene", scene);
    assertEquals(
        List.of(main == 3011602 ? 2101 : 2102),
        scene.getEntities(EntityNpc.class).stream().map(EntityNpc::getNpcId).toList());
    assertTrue(scene.getLoadedGroups().contains(group(main).getId()));
    assertNativeNpcs(scene, List.of(main == 3011602 ? 2101 : 2102));
    source.win(source.battle(main));
    assertEquals(1, scene.getEntities(EntityNpc.class).size());
    source.finishTail(main);
    assertTrue(scene.getEntities(EntityNpc.class).isEmpty());
    assertFalse(scene.getLoadedGroups().contains(group(main).getId()));
    assertNativeNpcs(scene, List.of());
    assertEquals(5000, actor().getScoin());
  }

  @ParameterizedTest
  @ValueSource(ints = {3011602, 3011607})
  void absentOrCompletedSourceMainDoesNotKeepItsNpcGroupVisible(int main) throws Exception {
    assertFalse(actor().getMissionManager().shouldLoadGroup(group(main)));
    start(main);
    source.win(source.battle(main));
    source.finishTail(main);
    assertFalse(actor().getMissionManager().shouldLoadGroup(group(main)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Or", "Not", "unknown", "null"})
  void explicitOperationsAreRespectedAndUnsupportedOperationsCannotOpenAGroup(String operation)
      throws Exception {
    start(3011602);
    String conditions =
        "[{\"Type\":\"SubMission\",\"Phase\":\"Accept\",\"ID\":301160201}"
            + (operation.equals("Or")
                ? ",{\"Type\":\"SubMission\",\"Phase\":\"Accept\",\"ID\":301160202}"
                : "")
            + "]";
    var set =
        gson.fromJson(
            "{\"Operation\":"
                + (operation.equals("null") ? "null" : "\"" + operation + "\"")
                + ",\"Conditions\":"
                + conditions
                + "}",
            LoadConditionInfo.class);
    var group = group(3011602);
    DailyMissionAcceptance46Test.set(group, "LoadCondition", set);
    assertEquals(operation.equals("Or"), actor().getMissionManager().shouldLoadGroup(group));
  }

  @Test
  void explicitlyUnknownPhaseIsNotTreatedAsOmitted() throws Exception {
    start(3011602);
    var group = group(3011602);
    DailyMissionAcceptance46Test.set(
        group,
        "LoadCondition",
        gson.fromJson(
            "{\"Conditions\":[{\"Type\":\"SubMission\",\"ID\":301160201,\"Phase\":\"unknown\"}]}",
            LoadConditionInfo.class));
    assertFalse(actor().getMissionManager().shouldLoadGroup(group));
  }

  @ParameterizedTest
  @ValueSource(ints = {3011602, 3011607})
  void aKnownExpiredDailyCannotKeepItsOldNpcGroupVisibleBeforeReassignment(int main)
      throws Exception {
    start(main);
    assertTrue(actor().getMissionManager().shouldLoadGroup(group(main)));
    source.clock(DailyMissionRepeat46Test.NEXT_DAY);
    assertFalse(actor().getMissionManager().shouldLoadGroup(group(main)));
    assertEquals(0, actor().getScoin());
  }

  @ParameterizedTest
  @ValueSource(strings = {"id", "negative", "overGoal", "time", "doingWithTime", "phase"})
  void anOrClauseCannotHideACorruptPresentMissionRecord(String field) throws Exception {
    start(3011112);
    source.win(source.battle(3011112));
    var state = actor().getMissionManager().sub(301111202);
    switch (field) {
      case "id" -> DailyMissionAcceptance46Test.set(state, "questId", 301111203);
      case "negative" -> DailyMissionAcceptance46Test.set(state, "progress", -1);
      case "overGoal" -> DailyMissionAcceptance46Test.set(state, "progress", 2);
      case "time" -> DailyMissionAcceptance46Test.set(state, "finishTime", 0L);
      case "doingWithTime" ->
          DailyMissionAcceptance46Test.set(
              state, "phase", emu.lunarcore.game.quest.QuestState.Phase.DOING);
      case "phase" -> DailyMissionAcceptance46Test.set(state, "phase", null);
      default -> fail(field);
    }
    assertFalse(actor().getMissionManager().shouldLoadGroup(group(3011112)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"And", "omitted"})
  void andGroupsRequireEveryAcceptedClauseIncludingAFutureSub(String operation) throws Exception {
    start(3011602);
    var group = group(3011602);
    DailyMissionAcceptance46Test.set(
        group,
        "LoadCondition",
        gson.fromJson(
            "{"
                + (operation.equals("And") ? "\"Operation\":\"And\"," : "")
                + "\"Conditions\":[{\"Type\":\"SubMission\",\"ID\":301160201},{\"Type\":\"SubMission\",\"ID\":301160202}]}",
            LoadConditionInfo.class));
    assertFalse(actor().getMissionManager().shouldLoadGroup(group));
    source.win(source.battle(3011602));
    assertTrue(actor().getMissionManager().shouldLoadGroup(group));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "nullChild",
        "unknownType",
        "nullType",
        "unknownPhase",
        "nullPhase",
        "zeroId",
        "empty",
        "nullConditions"
      })
  void unsupportedOrClausesCannotBeHiddenBehindAKnownAcceptedClause(String kind) throws Exception {
    start(3011602);
    String invalid =
        switch (kind) {
          case "nullChild" -> "null";
          case "unknownType" -> "{\"Type\":\"EventMission\",\"ID\":301160201}";
          case "nullType" -> "{\"Type\":null,\"ID\":301160201}";
          case "unknownPhase" -> "{\"Type\":\"SubMission\",\"Phase\":\"unknown\",\"ID\":301160201}";
          case "nullPhase" -> "{\"Type\":\"SubMission\",\"Phase\":null,\"ID\":301160201}";
          case "zeroId" -> "{\"Type\":\"SubMission\",\"ID\":0}";
          case "empty", "nullConditions" -> "";
          default -> throw new IllegalArgumentException();
        };
    String children =
        kind.equals("empty")
            ? "[]"
            : kind.equals("nullConditions")
                ? "null"
                : "[{\"Type\":\"SubMission\",\"ID\":301160201}," + invalid + "]";
    var group = group(3011602);
    DailyMissionAcceptance46Test.set(
        group,
        "LoadCondition",
        gson.fromJson(
            "{\"Operation\":\"Or\",\"Conditions\":" + children + "}", LoadConditionInfo.class));
    assertDoesNotThrow(() -> assertFalse(actor().getMissionManager().shouldLoadGroup(group)));
  }

  void assertNativeNpcs(Scene scene, List<Integer> expected) throws Exception {
    var bridge = org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
    var bytes =
        bridge
            .outbound(
                emu.lunarcore.server.packet.CmdId.GetCurSceneInfoScRsp,
                emu.lunarcore.proto.GetCurSceneInfoScRspOuterClass.GetCurSceneInfoScRsp
                    .newInstance()
                    .setScene(scene.toProto())
                    .toByteArray())
            .body();
    var response = bridge.clientSchema().parse("GetCurSceneInfoScRsp", bytes);
    assertTrue(response.getUnknownFields().asMap().isEmpty());
    var view = (com.google.protobuf.DynamicMessage) field(response, "scene");
    assertTrue(view.getUnknownFields().asMap().isEmpty());
    assertEquals(scene.getFloorId(), field(view, "floor_id"));
    assertEquals(scene.getPlaneId(), field(view, "plane_id"));
    var ids = new ArrayList<Integer>();
    for (var raw : (List<?>) field(view, "entity_group_list")) {
      var group = (com.google.protobuf.DynamicMessage) raw;
      assertTrue(group.getUnknownFields().asMap().isEmpty());
      for (var value : (List<?>) field(group, "entity_list")) {
        var entity = (com.google.protobuf.DynamicMessage) value;
        assertTrue(entity.getUnknownFields().asMap().isEmpty());
        var npcField = entity.getDescriptorForType().findFieldByName("npc");
        if (!entity.hasField(npcField)) continue;
        var npc = (com.google.protobuf.DynamicMessage) entity.getField(npcField);
        assertTrue(npc.getUnknownFields().asMap().isEmpty());
        ids.add((Integer) field(npc, "npc_id"));
        var actual = (EntityNpc) scene.getEntities().get((Integer) field(entity, "entity_id"));
        assertNotNull(actual);
        assertEquals(actual.getGroupId(), field(entity, "group_id"));
        assertEquals(actual.getInstId(), field(entity, "inst_id"));
        var motion = (com.google.protobuf.DynamicMessage) field(entity, "motion");
        var pos = (com.google.protobuf.DynamicMessage) field(motion, "pos");
        assertEquals(actual.getPos().getX(), field(pos, "x"));
        assertEquals(actual.getPos().getY(), field(pos, "y"));
        assertEquals(actual.getPos().getZ(), field(pos, "z"));
      }
    }
    assertEquals(expected, ids);
  }

  Object field(com.google.protobuf.DynamicMessage message, String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }
}
