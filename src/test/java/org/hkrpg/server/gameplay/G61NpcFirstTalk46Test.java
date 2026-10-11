package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.util.Position;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/**
 * Real existing 2142/2161 entry points, original G61 NPC and complete original dialogue graphs.
 * Source dialogue menu predicates are inspected; the game client and its presentation are not run.
 * Tutorial histories, one commission catalog, fixed days and save faults are explicit fixtures.
 * The composed Player actor is static and has Morphia-transient serializer/session dependencies.
 */
class G61NpcFirstTalk46Test {
  static final int INTRO = 90000103, NPC = 10206, GROUP = 61, INSTANCE = 400001;
  static final String MAIN_GRAPH = "Config/Level/NPCDialogue/P20002/F20002001_G61/DialogueMain_F20002001_G61_N400001.json";
  static final String PACK_GRAPH = "Config/Level/NPCDialogue/P20002/F20002001_G61/DialoguePack_Transferred_F20002001_G61_N400001.json";
  static final String INTRO_GRAPH = "Config/Level/NPC/W0/NPC_DefaultStory_500001301.json";
  static final String GROUP_GRAPH = "Config/LevelOutput/SharedRuntimeGroup/Groups_P20002_F20002001/LevelGroup_P20002_F20002001_G61.json";
  final DailyMissionMessage46Test daily = new DailyMissionMessage46Test();
  final Gson gson = ResourceLoader.getGson();
  final Map<Integer, FirstTalkDefinitions.Performance> previousPerformances = new HashMap<>();
  @TempDir Path root;
  String previousRoot;
  JsonObject source;
  boolean fixtureLoaded;

  @BeforeEach
  void setup() throws Exception {
    previousRoot = LunarCore.getConfig().getResourceDir();
    previousPerformances.putAll(FirstTalkDefinitions.performances);
    daily.temp = root;
    daily.setup();
    fixtureLoaded = true;
    // Keep the actual required daily catalog; only its independent unlock history is absent.
    // G61 requires 1000401, whereas this commission separately requires 1010201.
    daily.actor.getGameplay().getMainMissions().remove(1010201);
    try (var stream = getClass().getResourceAsStream("/fixtures/g61-npc-first-talk-4.6.json")) {
      source = JsonParser.parseString(new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b", source.get("sourceCommit").getAsString());
    assertEquals(240, source.getAsJsonArray("records").size());
    var tables = new HashMap<String, JsonArray>();
    var unique = new HashSet<String>();
    for (var raw : source.getAsJsonArray("records")) {
      var record = raw.getAsJsonObject();
      String path = record.get("source").getAsString().substring("resources/4.6/".length());
      var row = record.get("row");
      if (path.startsWith("ExcelOutput/") && unique.add(path + record.get("path")))
        tables.computeIfAbsent(path, ignored -> new JsonArray()).add(row.deepCopy());
      if (record.get("kind").getAsString().equals("prerequisiteMain"))
        GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
      if (record.get("kind").getAsString().equals("entrance"))
        GameData.getMapEntranceExcelMap().add(gson.fromJson(row, emu.lunarcore.data.excel.MapEntranceExcel.class));
    }
    for (var file : source.getAsJsonObject("sourceFiles").entrySet()) {
      var blob = file.getValue().getAsJsonObject().get("sourceBlobBase64");
      if (blob == null) continue;
      Path path = root.resolve(file.getKey());
      Files.createDirectories(path.getParent());
      Files.write(path, Base64.getDecoder().decode(blob.getAsString()));
    }
    for (var table : tables.entrySet()) write(table.getKey(), table.getValue());
    resourceRoot(root.toString());
    FirstTalkDefinitions.load(root);
  }

  @AfterEach
  void cleanup() throws Exception {
    resourceRoot(previousRoot);
    FirstTalkDefinitions.performances.clear();
    FirstTalkDefinitions.performances.putAll(previousPerformances);
    if (fixtureLoaded) daily.cleanup();
  }

  void resourceRoot(String value) throws Exception {
    var field = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
    field.setAccessible(true);
    field.set(LunarCore.getConfig(), value);
  }

  void write(String path, JsonElement value) throws Exception {
    Files.createDirectories(root.resolve(path).getParent());
    Files.writeString(root.resolve(path), gson.toJson(value));
  }

  JsonObject graph(String path) throws Exception {
    return JsonParser.parseString(Files.readString(root.resolve(path))).getAsJsonObject();
  }

  EntityNpc npc() {
    var actors = daily.actor.getScene().getEntitiesByGroup(EntityNpc.class, GROUP).stream()
        .filter(actor -> actor.getInstId() == INSTANCE).toList();
    assertEquals(1, actors.size());
    var actor = actors.getFirst();
    assertEquals(NPC, actor.getNpcId());
    assertTrue(actor.getEntityId() > 0);
    assertSame(actor, daily.actor.getScene().getEntityById(actor.getEntityId()));
    return actor;
  }

  void floor() { daily.floor(); npc(); }

  void commission() throws Exception {
    daily.actor.getGameplay().getMainMissions().put(1010201, DailyMissionAcceptance46Test.closed(1010201));
    daily.catalog(DailyMissionMessage46Test.MAIN);
    floor();
  }

  DynamicMessage finish() {
    return daily.query("FinishFirstTalkByPerformanceNpcCsReq", Map.of("performance_id", INTRO));
  }

  @SuppressWarnings("unchecked")
  boolean seen() {
    var response = daily.query("GetFirstTalkByPerformanceNpcCsReq", Map.of("performance_id_list", List.of(INTRO)));
    assertEquals(0, daily.field(response, "retcode"));
    var statuses = (List<DynamicMessage>) daily.field(response, "npc_meet_status_list");
    assertEquals(1, statuses.size());
    assertEquals(INTRO, daily.field(statuses.getFirst(), "performance_id"));
    return (Boolean) daily.field(statuses.getFirst(), "is_meet");
  }

  void succeeds() {
    var response = finish();
    assertEquals(Retcode.SUCC.getVal(), daily.field(response, "retcode"));
    assertEquals(INTRO, daily.field(response, "performance_id"));
    assertTrue(seen());
    assertTrue(daily.actor.getMissionManager().hasSeenFirstTalk(INTRO));
    assertFalse(response.hasField(response.getDescriptorForType().findFieldByName("reward")));
  }

  void refusesUnchanged() {
    var before = gson.toJson(daily.actor.getGameplay());
    int saves = daily.actor.saves, credits = daily.actor.getScoin();
    assertNotEquals(Retcode.SUCC.getVal(), daily.field(finish(), "retcode"));
    assertFalse(seen());
    assertEquals(before, gson.toJson(daily.actor.getGameplay()));
    assertEquals(saves, daily.actor.saves);
    assertEquals(credits, daily.actor.getScoin());
  }

  JsonObject observed() {
    var gameplay = gson.toJsonTree(daily.actor.getGameplay()).getAsJsonObject();
    assertTrue(gameplay.has("npcFirstTalks"), "Initial meeting needs its own persisted NPC observation domain");
    var record = gameplay.getAsJsonObject("npcFirstTalks").getAsJsonObject("records").getAsJsonObject(String.valueOf(INTRO));
    assertNotNull(record);
    return record;
  }

  void restoreGameplay(String stored) throws Exception {
    daily.actor = daily.fresh();
    DailyMissionAcceptance46Test.set(daily.actor, "gameplay", gson.fromJson(stored, GameplayState.class));
    daily.clock(DailyMissionMessage46Test.TODAY);
  }

  @Test
  void oneOriginalNpcIntroIsReportableWithoutGloballyPermittingNpcPerformancePaths() {
    var definition = FirstTalkDefinitions.performances.get(INTRO);
    assertNotNull(definition);
    assertEquals(INTRO_GRAPH, definition.path());
    assertTrue(definition.reportable()); // Real original NPC path is currently excluded.
    assertFalse(FirstTalkDefinitions.performances.containsKey(90000104));
    assertTrue(GameplayDefinitions.subs.values().stream().noneMatch(sub ->
        "FinishFirstTalkPerformance".equals(sub.getFinishType())
            && Arrays.asList(sub.getParamIntList()).contains(INTRO)));
  }

  @Test
  void nativeInitialMeetingUsesTheOriginalLoadedNpcAndSavesOnlyItsActualIdentity() {
    floor();
    assertTrue(daily.actor.getGameplay().getMainMissions().keySet().stream().noneMatch(id -> id >= 3000000));
    assertTrue(daily.actor.getGameplay().getSubMissions().isEmpty());
    assertFalse(seen());
    var before = gson.toJsonTree(daily.actor.getGameplay());
    var actor = npc();
    int saves = daily.actor.saves;
    succeeds();
    assertEquals(saves + 1, daily.actor.saves);
    var record = observed();
    for (var expected : Map.of("performance", INTRO, "uid", daily.actor.getUid(), "plane", 20002,
        "floor", 20002001, "entry", 200020102, "dimension", 0, "story", 0,
        "group", GROUP, "instance", INSTANCE, "npc", NPC).entrySet())
      assertEquals(expected.getValue().intValue(), record.get(expected.getKey()).getAsInt(), expected.getKey());
    assertEquals(actor.getEntityId(), record.get("entity").getAsInt());
    assertEquals(daily.actor.getScene().getRuntimeId(), record.get("runtime").getAsLong());
    assertTrue(record.get("at").getAsLong() > 0);
    assertEquals(record.get("event").getAsString(), UUID.fromString(record.get("event").getAsString()).toString());
    assertEquals(before.getAsJsonObject().get("mainMissions"), gson.toJsonTree(daily.actor.getGameplay()).getAsJsonObject().get("mainMissions"));
    assertTrue(daily.actor.getGameplay().getSubMissions().isEmpty());
    assertTrue(daily.actor.getGameplay().getFirstTalkReports().isEmpty());
    assertTrue(daily.actor.getGameplay().getPhoneSections().isEmpty());
    assertEquals(0, daily.actor.getScoin());
    assertEquals(0, daily.actor.getGameplay().getDailyPoints());
    assertTrue(daily.packets.stream().noneMatch(packet -> packet.getKey().contains("Reward")));
  }

  @Test
  void duplicateQueriesAndCompletionDoNotSaveOrRewardAgainEvenAfterTheNpcHasUnloaded() {
    floor(); succeeds();
    String before = gson.toJson(daily.actor.getGameplay());
    int saves = daily.actor.saves;
    daily.actor.getScene().removeEntity(npc());
    for (int iteration = 0; iteration < 3; iteration++) succeeds();
    assertEquals(before, gson.toJson(daily.actor.getGameplay()));
    assertEquals(saves, daily.actor.saves);
    assertEquals(0, daily.actor.getScoin());
  }

  @Test
  void aRejectedPlayerSaveRollsBackAndTheSameNativeRequestCanRetryOnce() throws Exception {
    floor();
    String before = gson.toJson(daily.actor.getGameplay());
    int saves = daily.actor.saves;
    daily.actor.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), daily.field(finish(), "retcode"));
    assertFalse(seen());
    assertEquals(before, gson.toJson(daily.actor.getGameplay()));
    assertEquals(saves, daily.actor.saves);
    daily.actor.failSave = false;
    succeeds();
    assertEquals(saves + 1, daily.actor.saves);
    assertNotNull(daily.actor.durableGameplay);
    restoreGameplay(daily.actor.durableGameplay);
    int restoredSaves = daily.actor.saves;
    succeeds();
    assertEquals(restoredSaves, daily.actor.saves);
  }

  @Test
  void twoActualMorphiaPlayerReadsPreserveTheFirstSceneIdentityAndDeduplicateAfterReentry() throws Exception {
    floor(); succeeds();
    var observation = observed().deepCopy();
    var server = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client = com.mongodb.client.MongoClients.create("mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "g61_npc_first_talk");
      store.getMapper().map(Player.class, QuestState.class);
      for (int iteration = 0; iteration < 2; iteration++) {
        var base = new Player();
        base.resetPosition();
        DailyMissionAcceptance46Test.set(base, "uid", daily.actor.getUid());
        DailyMissionAcceptance46Test.set(base, "gameplay", daily.actor.getGameplay());
        store.save(base);
        var restored = store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", daily.actor.getUid())).first();
        assertNotNull(restored);
        restoreGameplay(gson.toJson(restored.getGameplay()));
        assertTrue(seen());
        floor();
        assertNotEquals(observation.get("runtime").getAsLong(), daily.actor.getScene().getRuntimeId());
        int saves = daily.actor.saves;
        succeeds();
        assertEquals(saves, daily.actor.saves);
        assertEquals(observation, observed());
        assertEquals(0, daily.actor.getScoin());
      }
    } finally { server.shutdownNow(); }
  }

  @Test
  void copyingThePersistedMeetingToAnotherUidCannotCreateSeenHistory() throws Exception {
    floor(); succeeds();
    var stored = gson.toJson(daily.actor.getGameplay());
    restoreGameplay(stored);
    DailyMissionAcceptance46Test.set(daily.actor, "uid", daily.actor.getUid() + 1);
    floor();
    refusesUnchanged();
  }

  @Test
  void legacyEmptyMissionReportsDoNotMasqueradeAsAnObservedNpcMeeting() {
    floor();
    var legacy = new GameplayState.FirstTalkReport(20002, 20002001, List.of());
    legacy.complete();
    daily.actor.getGameplay().getFirstTalkReports().put(INTRO, legacy);
    assertFalse(seen()); // Current global report accepts a completed empty record without an actor.
    succeeds();
    assertSame(legacy, daily.actor.getGameplay().getFirstTalkReports().get(INTRO));
    assertTrue(legacy.getSubMissionIds().isEmpty());
    observed();
  }

  @ParameterizedTest @ValueSource(strings = {"missing", "npc", "group", "instance", "position", "rotation", "unloadedGroup", "duplicate", "foreignScene", "entry", "playerFloor"})
  void aReportNeedsExactlyTheSourceNpcInItsActualLoadedScene(String changed) throws Exception {
    floor();
    var actor = npc();
    var scene = daily.actor.getScene();
    switch (changed) {
      case "missing" -> scene.removeEntity(actor);
      case "npc" -> actor.setNpcId(NPC + 1);
      case "group" -> actor.setGroupId(GROUP + 1);
      case "instance" -> actor.setInstId(INSTANCE + 1);
      case "position" -> actor.getPos().setX(actor.getPos().getX() + 1);
      case "rotation" -> actor.getRot().setY(actor.getRot().getY() + 1);
      case "unloadedGroup" -> scene.getLoadedGroups().remove(GROUP);
      case "duplicate" -> {
        var clone = new EntityNpc(scene, daily.groups.get(GROUP), daily.groups.get(GROUP).getNPCList().getFirst());
        clone.setEntityId(actor.getEntityId() + 1000);
        scene.getEntities().put(clone.getEntityId(), clone);
      }
      case "foreignScene" -> DailyMissionAcceptance46Test.set(daily.actor, "scene", new Scene(daily.fresh(), GameData.getMazePlaneExcelMap().get(20002), 20002001));
      case "entry" -> { DailyMissionAcceptance46Test.set(daily.actor, "entryId", 200020999); scene.setEntryId(200020999); }
      case "playerFloor" -> DailyMissionAcceptance46Test.set(daily.actor, "floorId", 20002002);
      default -> fail(changed);
    }
    refusesUnchanged();
  }

  @Test
  void theOriginalFinishGateMustLoadTheNpcBeforeItCanBeObserved() {
    daily.actor.getGameplay().getMainMissions().remove(1000401);
    daily.floor();
    assertTrue(daily.actor.getScene().getEntitiesByGroup(EntityNpc.class, GROUP).isEmpty());
    refusesUnchanged();
  }

  @Test
  void theOriginalDeferredUnloadDoesNotRemoveALiveNpcButDoesRemoveItOnFloorReload() throws Exception {
    floor();
    var live = npc();
    daily.actor.getGameplay().getMainMissions().put(3000604, new QuestState(3000604));
    daily.actor.getScene().refreshMissionGroups();
    assertSame(live, npc());
    succeeds();
    var fresh = daily.fresh();
    fresh.getGameplay().getMainMissions().put(3000604, new QuestState(3000604));
    daily.actor = fresh;
    daily.clock(DailyMissionMessage46Test.TODAY);
    daily.floor();
    assertTrue(daily.actor.getScene().getEntitiesByGroup(EntityNpc.class, GROUP).isEmpty());
    refusesUnchanged();
  }

  @Test
  void theOtherOriginalEntranceWithTheSameGateAndFloorAlsoAllowsTheActualNpcMeeting() throws Exception {
    floor();
    DailyMissionAcceptance46Test.set(daily.actor, "entryId", 200020103);
    daily.actor.getScene().setEntryId(200020103);
    succeeds();
    assertEquals(200020103, observed().get("entry").getAsInt());
  }

  @ParameterizedTest @ValueSource(strings = {"missingMain", "missingPack", "missingIntro", "wrongPerformancePath", "nonIntro", "unknownEffect", "owner", "npcBinding", "packCondition", "wrongPlane"})
  void unknownOrChangedSourceCannotBroadenThisFiniteNpcCompletion(String changed) throws Exception {
    floor();
    switch (changed) {
      case "missingMain" -> Files.delete(root.resolve(MAIN_GRAPH));
      case "missingPack" -> Files.delete(root.resolve(PACK_GRAPH));
      case "missingIntro" -> Files.delete(root.resolve(INTRO_GRAPH));
      case "wrongPerformancePath", "nonIntro" -> {
        var table = JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/PerformanceE.json"))).getAsJsonArray();
        var row = table.asList().stream().map(JsonElement::getAsJsonObject).filter(r -> r.get("PerformanceID").getAsInt() == INTRO).findFirst().orElseThrow();
        if (changed.equals("nonIntro")) row.addProperty("IsIntroDialogue", false);
        else row.addProperty("PerformancePath", "Config/Level/Mission/3000602/Act/Act300060201.json");
        write("ExcelOutput/PerformanceE.json", table);
      }
      case "unknownEffect" -> {
        var graph = graph(INTRO_GRAPH);
        var effect = new JsonObject(); effect.addProperty("$type", "RPG.GameCore.FinishMission"); effect.addProperty("ID", 300060202);
        graph.getAsJsonArray("OnStartSequece").get(0).getAsJsonObject().getAsJsonArray("TaskList").add(effect);
        write(INTRO_GRAPH, graph);
      }
      case "owner", "npcBinding" -> {
        var group = graph(GROUP_GRAPH);
        if (changed.equals("owner")) group.addProperty("OwnerMainMissionID", 3000602);
        else group.getAsJsonArray("NPCList").get(0).getAsJsonObject().getAsJsonObject("Dialog").addProperty("LevelGraph", "Config/Level/Mission/3000602/Talk/Talk_300060201.json");
        write(GROUP_GRAPH, group);
      }
      case "packCondition" -> {
        var graph = graph(PACK_GRAPH);
        var predicate = graph.getAsJsonArray("OnStartSequece").get(0).getAsJsonObject().getAsJsonArray("TaskList").get(2).getAsJsonObject().getAsJsonObject("Predicate");
        predicate.addProperty("SubMissionState", "Finished");
        write(PACK_GRAPH, graph);
      }
      case "wrongPlane" -> {
        var table = JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/MazePlane.json"))).getAsJsonArray();
        table.get(0).getAsJsonObject().addProperty("PlaneType", "Town"); write("ExcelOutput/MazePlane.json", table);
      }
      default -> fail(changed);
    }
    FirstTalkDefinitions.load(root);
    refusesUnchanged();
  }

  @ParameterizedTest @ValueSource(ints = {90000104, 300060201, 90000105})
  void ordinaryGreetingMissionActAndUnknownPerformancesCannotBecomeFirstTalkObjectives(int performance) {
    floor();
    var before = gson.toJson(daily.actor.getGameplay());
    var response = daily.query("FinishFirstTalkByPerformanceNpcCsReq", Map.of("performance_id", performance));
    assertNotEquals(Retcode.SUCC.getVal(), daily.field(response, "retcode"));
    assertEquals(before, gson.toJson(daily.actor.getGameplay()));
    assertTrue(daily.actor.getGameplay().getSubMissions().isEmpty());
  }

  @Test
  void aCompletedMeetingFailsClosedAfterItsOriginalNpcGraphHasChanged() throws Exception {
    floor(); succeeds();
    String before = gson.toJson(daily.actor.getGameplay());
    var group = graph(GROUP_GRAPH);
    group.getAsJsonArray("NPCList").get(0).getAsJsonObject().addProperty("NPCID", NPC + 1);
    write(GROUP_GRAPH, group);
    FirstTalkDefinitions.load(root);
    assertFalse(seen());
    assertNotEquals(Retcode.SUCC.getVal(), daily.field(finish(), "retcode"));
    assertEquals(before, gson.toJson(daily.actor.getGameplay()));
  }

  @ParameterizedTest @CsvSource({"121140202,121140205", "121140202,121140207", "121140203,121140205", "121140203,121140207"})
  void originalConditionalMenuTalkAndAllFourPhoneBranchesRemainSeparateFromInitialMeeting(int first, int second) throws Exception {
    commission();
    assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(DailyMissionMessage46Test.FIRST), DailyMissionMessage46Test.FIRST, 1));
    assertNull(daily.phone(DailyMissionMessage46Test.SECTION));
    succeeds();
    assertEquals(0, daily.actor.getMissionManager().sub(DailyMissionMessage46Test.FIRST).getProgress());
    assertNull(daily.phone(DailyMissionMessage46Test.SECTION));
    var menu = graph(PACK_GRAPH).getAsJsonArray("OnStartSequece").get(0).getAsJsonObject().getAsJsonArray("TaskList")
        .asList().stream().map(JsonElement::getAsJsonObject)
        .filter(task -> task.has("Predicate") && task.getAsJsonObject("Predicate").get("SubMissionID").getAsInt() == DailyMissionMessage46Test.FIRST).findFirst().orElseThrow();
    assertEquals("Started", menu.getAsJsonObject("Predicate").get("SubMissionState").getAsString());
    var option = menu.getAsJsonArray("SuccessTaskList").get(0).getAsJsonObject();
    assertEquals(DailyMissionMessage46Test.FIRST, option.get("MissionID").getAsInt());
    assertEquals("Config/Level/Mission/3000602/Talk/Talk_300060201.json", option.get("DialoguePath").getAsString());
    var response = daily.query("FinishTalkMissionCsReq", Map.of("sub_mission_id", DailyMissionMessage46Test.FIRST, "talk_str", "Mission_300060201"));
    assertEquals(0, daily.field(response, "retcode"));
    assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(DailyMissionMessage46Test.GOAL), DailyMissionMessage46Test.GOAL, 1));
    assertEquals(0, daily.actor.getScoin());
    daily.pay(first, second);
    assertEquals(5000, daily.actor.getScoin());
    assertEquals(200, daily.actor.getGameplay().getDailyPoints());
    assertTrue(seen());
  }

  @Test
  void globalNpcMeetingSurvivesTheNextDayWhileTheCommissionRequiresItsOriginalTalkAndNewRepliesAgain() throws Exception {
    commission(); succeeds();
    var original = observed().deepCopy();
    assertEquals(Retcode.SUCC, daily.actor.getMissionManager().finishTalk(DailyMissionMessage46Test.FIRST, "Mission_300060201"));
    daily.pay(121140202, 121140205);
    daily.clock(DailyMissionMessage46Test.NEXT);
    daily.actor.getDailyManager().ensureDay();
    daily.actor.getMissionManager().refresh();
    assertTrue(seen());
    assertEquals(original, observed());
    assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(DailyMissionMessage46Test.FIRST), DailyMissionMessage46Test.FIRST, 1));
    assertNull(daily.phone(DailyMissionMessage46Test.SECTION));
    assertEquals(Retcode.SUCC, daily.actor.getMissionManager().finishTalk(DailyMissionMessage46Test.FIRST, "Mission_300060201"));
    assertFalse(new MessageManager(daily.actor).finished(DailyMissionMessage46Test.SECTION));
    assertEquals(List.of(121140202, 121140203), daily.offered(daily.phone(DailyMissionMessage46Test.SECTION)));
    daily.pay(121140203, 121140207);
    assertEquals(10000, daily.actor.getScoin());
    assertEquals(200, daily.actor.getGameplay().getDailyPoints());
    assertEquals(original, observed());
  }
}
