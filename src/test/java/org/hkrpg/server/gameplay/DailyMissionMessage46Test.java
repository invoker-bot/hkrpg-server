package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.util.Position;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/**
 * Daily phone period regressions using existing runtime APIs and complete pinned source objects.
 * Tutorial histories, one eligible catalog, clock, saves and retained floor groups are fixtures.
 * NPC client scripts are retained, not executed; these checks are neither KCP nor client UI acceptance.
 */
class DailyMissionMessage46Test {
  static final int MAIN = 3000602, FIRST = 300060201, GOAL = 300060202, SECTION = 1211401;
  static final Instant TODAY = DailyMissionAcceptance46Test.TODAY, NEXT = TODAY.plusSeconds(86400);
  final DailyMissionAcceptance46Test fixture = new DailyMissionAcceptance46Test();
  final Gson gson = emu.lunarcore.data.ResourceLoader.getGson();
  final Map<Integer, JsonObject> daily = new HashMap<>();
  final Map<Integer, GroupInfo> groups = new HashMap<>();
  final List<NpcExcel> oldNpcs = new ArrayList<>();
  final List<PropExcel> oldProps = new ArrayList<>();
  final List<MazePlaneExcel> oldPlanes = new ArrayList<>();
  final List<MapEntranceExcel> oldEntrances = new ArrayList<>();
  final Map<String, FloorInfo> oldFloors = new HashMap<>();
  final List<GameplayDefinitions.DailyPool> oldPool = new ArrayList<>();
  final Map<Integer, GameplayDefinitions.MessageGroup> oldGroups = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MessageSection> oldSections = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MessageItem> oldItems = new HashMap<>();
  final List<Map.Entry<String, DynamicMessage>> packets = new ArrayList<>();
  @TempDir Path temp;
  Actor actor;
  Instant now;
  GameSession wire;

  static class Actor extends Player {
    @dev.morphia.annotations.Transient final Gson serializer;
    @dev.morphia.annotations.Transient final java.util.function.Supplier<GameSession> session;
    final LineupManager teams = new LineupManager(this);
    int saves;
    boolean failSave;
    String durableGameplay;

    Actor() { this(emu.lunarcore.data.ResourceLoader.getGson(), () -> null); }

    Actor(Gson serializer, java.util.function.Supplier<GameSession> session) {
      this.serializer = serializer;
      resetPosition();
      this.session = session;
      setNickname("Original daily phone fixture");
      getCurrentLineup().getAvatars().add(0);
    }

    @Override public LineupManager getLineupManager() { return teams; }
    @Override public void save() {
      if (failSave) throw new IllegalStateException("Fixture player write rejected");
      durableGameplay = serializer.toJson(getGameplay());
      saves++;
    }
    @Override public boolean isOnline() { return getSession() != null; }
    @Override public GameSession getSession() { return session == null ? null : session.get(); }
    @Override public void sendPacket(BasePacket packet) {}
  }

  @BeforeEach
  void setup() throws Exception {
    fixture.temp = temp;
    fixture.setup();
    oldNpcs.addAll(GameData.getNpcExcelMap().values());
    oldProps.addAll(GameData.getPropExcelMap().values());
    oldPlanes.addAll(GameData.getMazePlaneExcelMap().values());
    oldEntrances.addAll(GameData.getMapEntranceExcelMap().values());
    oldFloors.putAll(GameData.getFloorInfos());
    oldPool.addAll(GameplayDefinitions.dailyPool);
    oldGroups.putAll(GameplayDefinitions.messageGroups);
    oldSections.putAll(GameplayDefinitions.messageSections);
    oldItems.putAll(GameplayDefinitions.messageItems);
    GameData.getNpcExcelMap().values().clear();
    GameData.getPropExcelMap().values().clear();
    GameData.getMazePlaneExcelMap().values().clear();
    GameData.getMapEntranceExcelMap().values().clear();
    GameData.getFloorInfos().clear();
    GameData.getMainMissionExcelMap().values().clear();
    GameData.getMissionInfos().clear();
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.dailyPool.clear();
    GameplayDefinitions.messageGroups.clear();
    GameplayDefinitions.messageSections.clear();
    GameplayDefinitions.messageItems.clear();
    fixture.dailyRows = new JsonArray();
    fixture.countRows = new JsonArray();
    var definitions = new HashMap<Integer, JsonObject>();
    var children = new HashMap<Integer, JsonArray>();
    var scripts = new HashMap<String, JsonObject>();
    FloorInfo floor = null;
    try (var input = getClass().getResourceAsStream("/fixtures/daily-message-3000602-4.6.json")) {
      var rows = JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray();
      assertEquals(124, rows.size());
      for (var raw : rows) {
        var entry = raw.getAsJsonObject();
        assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b", entry.get("sourceCommit").getAsString());
        var row = entry.getAsJsonObject("row");
        switch (entry.get("kind").getAsString()) {
          case "definition" -> definitions.put(row.get("MainMissionID").getAsInt(), row);
          case "sub" -> children.computeIfAbsent(row.get("MainMissionID").getAsInt(), ignored -> new JsonArray()).add(row);
          case "main" -> GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
          case "daily" -> daily.put(row.get("ID").getAsInt(), row);
          case "count" -> fixture.countRows.add(row);
          case "quest" -> GameData.getQuestExcelMap().add(gson.fromJson(row, QuestExcel.class));
          case "rule" -> GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
          case "pool" -> GameplayDefinitions.dailyPool.add(gson.fromJson(row, GameplayDefinitions.DailyPool.class));
          case "reward" -> { var value = gson.fromJson(row, RewardExcel.class); value.onLoad(); GameData.getRewardExcelMap().add(value); }
          case "item" -> GameData.getItemExcelMap().add(gson.fromJson(row, ItemExcel.class));
          case "npc" -> GameData.getNpcExcelMap().add(gson.fromJson(row, NpcExcel.class));
          case "prop" -> { var value = gson.fromJson(row, PropExcel.class); value.onLoad(); GameData.getPropExcelMap().add(value); }
          case "plane" -> GameData.getMazePlaneExcelMap().add(gson.fromJson(row, MazePlaneExcel.class));
          case "entrance" -> GameData.getMapEntranceExcelMap().add(gson.fromJson(row, MapEntranceExcel.class));
          case "floor" -> floor = gson.fromJson(row, FloorInfo.class);
          case "group" -> {
            var group = gson.fromJson(row, GroupInfo.class);
            group.setId(Integer.parseInt(entry.get("source").getAsString().replaceAll(".*_G([0-9]+)\\.json", "$1")));
            groups.put(group.getId(), group);
          }
          case "script" -> scripts.put(entry.get("source").getAsString().substring("resources/4.6/".length()), row);
          case "messageGroup" -> { var value = gson.fromJson(row, GameplayDefinitions.MessageGroup.class); GameplayDefinitions.messageGroups.put(value.ID(), value); }
          case "messageSection" -> { var value = gson.fromJson(row, GameplayDefinitions.MessageSection.class); GameplayDefinitions.messageSections.put(value.ID(), value); }
          case "messageItem" -> { var value = gson.fromJson(row, GameplayDefinitions.MessageItem.class); GameplayDefinitions.messageItems.put(value.ID(), value); }
          // Entire original client scripts and text/contact rows are source proof, never fake completions.
          case "talkGraph", "performanceGraph", "dialogue", "dialogueCondition", "dialogueGraphSourceOnly", "talkSentence", "messageContact" -> {}
          default -> fail("Unknown original source kind");
        }
      }
    }
    for (var entry : definitions.entrySet()) {
      entry.getValue().add("SubMissionList", children.get(entry.getKey()));
      var graph = gson.fromJson(entry.getValue(), MissionInfo.class);
      GameData.getMissionInfos().put(entry.getKey(), graph);
      for (var sub : graph.getSubMissionList()) {
        GameplayDefinitions.subs.put(sub.getId(), sub);
        var script = scripts.get(sub.getMissionJsonPath());
        assertNotNull(script); // Important: goal 202 loads original script 203 by actual MissionJsonPath.
        var task = gson.fromJson(script, MissionTaskInfo.class);
        task.retainSourceDefinition(script);
        sub.setTask(task);
      }
    }
    assertNotNull(floor);
    for (int index : List.of(49, 66)) {
      int id = floor.getSimpleGroupList().get(index).getID();
      assertTrue(floor.getMainDimension().getGroupIndexList().contains(index));
      floor.registerGroup(index, groups.get(id));
    }
    floor.onLoad();
    GameData.getFloorInfos().put("P20002_F20002001", floor);
    catalog(MAIN);
    actor = fresh();
    clock(TODAY);
    actor.getDailyManager().ensureDay();
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
    GameData.getNpcExcelMap().values().clear(); oldNpcs.forEach(GameData.getNpcExcelMap()::add);
    GameData.getPropExcelMap().values().clear(); oldProps.forEach(GameData.getPropExcelMap()::add);
    GameData.getMazePlaneExcelMap().values().clear(); oldPlanes.forEach(GameData.getMazePlaneExcelMap()::add);
    GameData.getMapEntranceExcelMap().values().clear(); oldEntrances.forEach(GameData.getMapEntranceExcelMap()::add);
    GameData.getFloorInfos().clear(); GameData.getFloorInfos().putAll(oldFloors);
    GameplayDefinitions.dailyPool.clear(); GameplayDefinitions.dailyPool.addAll(oldPool);
    GameplayDefinitions.messageGroups.clear(); GameplayDefinitions.messageGroups.putAll(oldGroups);
    GameplayDefinitions.messageSections.clear(); GameplayDefinitions.messageSections.putAll(oldSections);
    GameplayDefinitions.messageItems.clear(); GameplayDefinitions.messageItems.putAll(oldItems);
  }

  void catalog(int... ids) throws Exception {
    fixture.dailyRows = new JsonArray();
    for (int id : ids) fixture.dailyRows.add(daily.get(id));
    fixture.loadCatalog();
  }

  Actor fresh() throws Exception {
    var value = new Actor(gson, () -> wire);
    DailyMissionAcceptance46Test.set(value, "uid", 123456785);
    for (int id : List.of(1000401, 1010201, 1010403))
      value.getGameplay().getMainMissions().put(id, DailyMissionAcceptance46Test.closed(id));
    return value;
  }

  void clock(Instant value) {
    now = value;
    actor.getMissionManager().setClock(Clock.fixed(value, ZoneOffset.UTC));
    actor.getDailyManager().setClock(Clock.fixed(value, ZoneOffset.UTC));
  }

  void floor() {
    assertTrue(actor.loadScene(20002, 20002001, 200020102, new Position(), new Position(), false));
    assertEquals(0, actor.getScene().getDimensionId());
    assertEquals(0, actor.getScene().getStoryLineId());
  }

  void open() {
    floor();
    assertEquals(Retcode.SUCC, actor.getMissionManager().accept(MAIN));
    assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(FIRST, "Mission_300060201"));
    assertTrue(MissionStateRecords.doing(actor.getMissionManager().sub(GOAL), GOAL, 1));
    assertFalse(new MessageManager(actor).finished(SECTION));
    assertEquals(List.of(121140201, 121140212, 121140213), history(phone(SECTION)));
    assertEquals(List.of(121140202, 121140203), offered(phone(SECTION)));
  }

  void replies(int first, int second) {
    var manager = new MessageManager(actor);
    assertEquals(Retcode.SUCC, manager.choose(first, 0));
    assertEquals(List.of(121140205, 121140207), offered(phone(SECTION)));
    assertEquals(Retcode.SUCC, manager.choose(second, 0));
    assertTrue(offered(phone(SECTION)).isEmpty());
    assertEquals(9, history(phone(SECTION)).size());
    assertEquals(121140210, history(phone(SECTION)).getLast());
    assertFalse(manager.finished(SECTION));
  }

  void pay(int first, int second) {
    replies(first, second);
    assertEquals(Retcode.SUCC, new MessageManager(actor).finishSection(SECTION));
    assertEquals(QuestState.Phase.CLOSED, actor.getGameplay().getMainMissions().get(MAIN).getPhase());
    assertEquals(QuestState.Phase.FINISHED, actor.getGameplay().getDailyQuests().get(2100003).getPhase());
    assertEquals(Retcode.SUCC, actor.getDailyManager().claimQuest(2100003).retcode());
    assertEquals(200, actor.getGameplay().getDailyPoints());
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, actor.getDailyManager().claimQuest(2100003).retcode());
  }

  Object field(DynamicMessage message, String name) {
    return message.getField(Objects.requireNonNull(message.getDescriptorForType().findFieldByName(name), name));
  }

  @SuppressWarnings("unchecked")
  List<Integer> offered(DynamicMessage row) { return (List<Integer>) field(row, "message_item_list"); }

  @SuppressWarnings("unchecked")
  List<Integer> history(DynamicMessage row) {
    return ((List<DynamicMessage>) field(row, "item_list")).stream().map(item -> (Integer) field(item, "item_id")).toList();
  }

  GameSession session() {
    if (wire == null) {
      wire = new GameSession(null) {
        @Override public Player getPlayer() { return actor; }
        @Override public ProtocolBridge getProtocol() { return ProtocolBridge.forVersion("4.6.0"); }
        @Override public void sendClientPacket(String name, byte[] bytes) {
          try { packets.add(Map.entry(name, getProtocol().clientSchema().parse(name, bytes))); }
          catch (Exception error) { throw new AssertionError(error); }
        }
      };
      wire.setState(SessionState.ACTIVE);
    }
    return wire;
  }

  DynamicMessage query(String name, Map<String, Object> values) {
    var session = session();
    var builder = DynamicMessage.newBuilder(session.getProtocol().clientSchema().message(name));
    values.forEach((key, value) -> builder.setField(Objects.requireNonNull(builder.getDescriptorForType().findFieldByName(key)), value));
    packets.clear();
    try { assertTrue(VersionedPacketHandler.handle(session, session.getProtocol().clientCommand(name), builder.build().toByteArray())); }
    catch (Exception error) { throw new AssertionError(error); }
    return packets.getLast().getValue();
  }

  @SuppressWarnings("unchecked")
  DynamicMessage phone(int id) {
    var response = query("GetNpcMessageGroupCsReq", Map.of("BJIPBBFIBPD", List.of(48)));
    assertEquals(0, field(response, "retcode"));
    var group = ((List<DynamicMessage>) field(response, "message_group_list")).stream()
        .filter(row -> field(row, "id").equals(11125)).findFirst().orElseThrow();
    return ((List<DynamicMessage>) field(group, "message_section_list")).stream()
        .filter(row -> field(row, "id").equals(id)).findFirst().orElse(null);
  }

  @Test
  void thisOneSourceDailyPhoneGraphBecomesRepeatableWithoutRewritingItsManualHeader() {
    assertTrue(DailyMissionRepeats.supportedGraph(MAIN)); // Actual source qualification must fail before implementation.
    assertEquals("Manual", GameData.getMainMissionExcelMap().get(MAIN).getTakeParam().getFirst().Type());
    assertTrue(GameplayDefinitions.subs.get(GOAL).isBackTrack());
    assertTrue(GameplayDefinitions.subs.get(GOAL).getMissionJsonPath().endsWith("Mission_300060203.json"));
  }

  @ParameterizedTest @ValueSource(ints = {3000601, 3000603})
  void siblingPhoneGraphsWithoutTheirOriginalCatalogRowsDoNotGainRepeatability(int id) {
    assertNotNull(GameData.getMissionInfos().get(id));
    assertNull(DailyMissionCatalog.mission(id));
    assertFalse(DailyMissionRepeats.supportedGraph(id));
  }

  @Test
  void theOriginalDailyPhoneIsActuallyAssignedByRefreshWithoutAnAcceptRequest() {
    actor.getMissionManager().refresh();
    assertTrue(MissionStateRecords.mainDoing(actor, MAIN));
    assertTrue(MissionStateRecords.doing(actor.getMissionManager().sub(FIRST), FIRST, 1));
    assertNull(actor.getMissionManager().sub(GOAL));
    assertEquals(0, actor.getScoin());
  }

  @ParameterizedTest @CsvSource({"121140202,121140205", "121140202,121140207", "121140203,121140205", "121140203,121140207"})
  void allFourOriginalReplyBranchesPayOnlyAfterBothRepliesAndTheFinalSectionRequest(int first, int second) {
    open();
    var manager = new MessageManager(actor);
    assertEquals(Retcode.MESSAGE_SECTION_CAN_NOT_FINISH, manager.finishSection(SECTION));
    assertEquals(Retcode.MESSAGE_ITEM_CAN_NOT_FINISH, manager.choose(second, 0));
    assertEquals(Retcode.MESSAGE_ITEM_CAN_NOT_FINISH, manager.choose(first, 1));
    assertEquals(Retcode.MESSAGE_SECTION_NOT_TAKE, manager.finishSection(1211400));
    assertEquals(0, actor.getScoin());
    pay(first, second);
    assertEquals(5000, actor.getScoin());
    assertEquals(1, TodayDailyMissionCounts.count(actor, DailyManager.day(TODAY), TODAY));
  }

  @Test
  void theFirstTalkMustActuallyFinishBeforeThisPhoneGraphCanBeOpened() {
    floor();
    assertEquals(Retcode.SUCC, actor.getMissionManager().accept(MAIN));
    assertNull(phone(SECTION));
    assertEquals(Retcode.MESSAGE_SECTION_NOT_TAKE, new MessageManager(actor).finishSection(SECTION));
    assertEquals(0, actor.getScoin());
    assertEquals(0, actor.getMissionManager().sub(FIRST).getProgress());
  }

  @Test
  void theSharedOriginalNpcUsesItsIndependentFinishGateAndDeferredUnloadGate() {
    actor.getGameplay().getMainMissions().remove(1000401);
    var before = new Scene(actor, GameData.getMazePlaneExcelMap().get(20002), 20002001);
    assertTrue(before.getEntities(EntityNpc.class).stream().noneMatch(npc -> npc.getGroupId() == 61));
    actor.getGameplay().getMainMissions().put(1000401, DailyMissionAcceptance46Test.closed(1000401));
    var loaded = new Scene(actor, GameData.getMazePlaneExcelMap().get(20002), 20002001);
    var npc = loaded.getEntities(EntityNpc.class).stream().filter(value -> value.getGroupId() == 61).findFirst().orElseThrow();
    assertEquals(10206, npc.getNpcId()); assertEquals(400001, npc.getInstId());
    // The unload observer is an explicit source-condition fixture, not a completed commission.
    actor.getGameplay().getMainMissions().put(3000604, new QuestState(3000604));
    loaded.refreshMissionGroups();
    assertTrue(loaded.getEntities(EntityNpc.class).contains(npc));
    var rebuilt = new Scene(actor, GameData.getMazePlaneExcelMap().get(20002), 20002001);
    assertTrue(rebuilt.getEntities(EntityNpc.class).stream().noneMatch(value -> value.getGroupId() == 61));
  }

  @Test
  void duplicatePhoneAndCommissionRequestsCannotPayAgainInTheSamePeriod() {
    open(); pay(121140202, 121140205);
    var manager = new MessageManager(actor);
    assertEquals(Retcode.MISSION_ALREADY_DONE, actor.getMissionManager().accept(MAIN));
    for (int i = 0; i < 4; i++) {
      assertEquals(Retcode.MESSAGE_SECTION_CAN_NOT_FINISH, manager.finishSection(SECTION));
      assertEquals(Retcode.MESSAGE_ITEM_CAN_NOT_FINISH, manager.choose(121140202, 0));
      actor.getMissionManager().refresh();
    }
    assertEquals(5000, actor.getScoin()); assertEquals(200, actor.getGameplay().getDailyPoints());
    assertTrue(actor.getGameplay().getDailyMissionPeriods().getArchives().isEmpty());
  }

  @ParameterizedTest @CsvSource({"121140202,121140205", "121140202,121140207", "121140203,121140205", "121140203,121140207"})
  void theNextPeriodMustRedoBothOriginalChoicesInsteadOfBorrowingGlobalFinishedHistory(int first, int second) {
    open(); pay(first, second);
    var oldMain = actor.getGameplay().getMainMissions().get(MAIN);
    clock(NEXT); actor.getDailyManager().ensureDay();
    assertEquals(Retcode.SUCC, actor.getMissionManager().accept(MAIN));
    assertEquals(0, actor.getMissionManager().sub(FIRST).getProgress());
    assertNull(actor.getMissionManager().sub(GOAL));
    assertEquals(5000, actor.getScoin());
    assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(FIRST, "Mission_300060201"));
    assertTrue(MissionStateRecords.doing(actor.getMissionManager().sub(GOAL), GOAL, 1));
    assertFalse(new MessageManager(actor).finished(SECTION));
    assertEquals(List.of(121140201, 121140212, 121140213), history(phone(SECTION)));
    assertEquals(List.of(121140202, 121140203), offered(phone(SECTION)));
    assertEquals(Retcode.MESSAGE_SECTION_CAN_NOT_FINISH, new MessageManager(actor).finishSection(SECTION));
    pay(first == 121140202 ? 121140203 : 121140202, second == 121140205 ? 121140207 : 121140205);
    assertEquals(10000, actor.getScoin());
    var archive = actor.getGameplay().getDailyMissionPeriods().getArchives().get(MAIN + ":" + DailyManager.day(TODAY));
    assertNotNull(archive); assertSame(oldMain, archive.getMain());
    assertTrue(actor.getRewardLedger().granted("mission:" + MAIN));
    assertTrue(actor.getRewardLedger().granted("daily-mission:" + DailyManager.day(NEXT) + ":mission:" + MAIN));
    assertEquals(0, TodayDailyMissionCounts.count(actor, DailyManager.day(TODAY), TODAY));
    assertEquals(1, TodayDailyMissionCounts.count(actor, DailyManager.day(NEXT), NEXT));
  }

  @ParameterizedTest @ValueSource(strings = {"noReplies", "oneReply", "allRepliesWithoutSectionFinish"})
  void anUnpaidExpiredPhoneViewCannotLeakItsChoicesOrCompletionIntoTheNewDailyRun(String at) {
    open();
    if (!at.equals("noReplies")) assertEquals(Retcode.SUCC, new MessageManager(actor).choose(121140202, 0));
    if (at.equals("allRepliesWithoutSectionFinish")) assertEquals(Retcode.SUCC, new MessageManager(actor).choose(121140205, 0));
    var oldMain = actor.getGameplay().getMainMissions().get(MAIN);
    var oldGoal = actor.getMissionManager().sub(GOAL);
    clock(NEXT); actor.getDailyManager().ensureDay(); actor.getMissionManager().refresh();
    assertTrue(MissionStateRecords.mainDoing(actor, MAIN));
    assertEquals(0, actor.getMissionManager().sub(FIRST).getProgress());
    assertNull(actor.getMissionManager().sub(GOAL));
    assertEquals(0, actor.getScoin());
    assertFalse(actor.getRewardLedger().granted("mission:" + MAIN));
    var expired = actor.getGameplay().getDailyMissionPeriods().getExpired().get(MAIN + ":" + DailyManager.day(TODAY));
    assertNotNull(expired); assertSame(oldMain, expired.getMain()); assertSame(oldGoal, expired.getSubs().get(GOAL));
    assertTrue(actor.getGameplay().getDailyMissionPeriods().getArchives().isEmpty());
    assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(FIRST, "Mission_300060201"));
    assertEquals(List.of(121140201, 121140212, 121140213), history(phone(SECTION)));
    assertEquals(List.of(121140202, 121140203), offered(phone(SECTION)));
    pay(121140203, 121140207); assertEquals(5000, actor.getScoin());
  }

  @ParameterizedTest @ValueSource(strings = {"reply", "section"})
  void anOldPhoneRequestIsRejectedAtTheDayBoundaryEvenBeforeTheNextMissionQuery(String action) {
    open();
    assertEquals(Retcode.SUCC, new MessageManager(actor).choose(121140202, 0));
    if (action.equals("section")) assertEquals(Retcode.SUCC, new MessageManager(actor).choose(121140205, 0));
    clock(NEXT);
    String before = gson.toJson(actor.getGameplay()); int saves = actor.saves;
    var result = action.equals("reply") ? new MessageManager(actor).choose(121140205, 0)
        : new MessageManager(actor).finishSection(SECTION);
    assertNotEquals(Retcode.SUCC, result);
    assertEquals(before, gson.toJson(actor.getGameplay())); assertEquals(saves, actor.saves);
    assertEquals(0, actor.getScoin());
  }

  @ParameterizedTest @ValueSource(strings = {"main", "goal"})
  void aPreviouslyWalkedPhoneCannotCompleteAReplacementAcceptanceWithTheSameNumericId(String replaced) {
    open(); replies(121140202, 121140205);
    if (replaced.equals("main")) actor.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    else actor.getGameplay().getSubMissions().put(GOAL, new QuestState(GOAL));
    var before = gson.toJson(actor.getGameplay()); int saves = actor.saves;
    assertNotEquals(Retcode.SUCC, new MessageManager(actor).finishSection(SECTION));
    assertEquals(before, gson.toJson(actor.getGameplay())); assertEquals(saves, actor.saves);
    assertEquals(0, actor.getScoin());
  }

  @ParameterizedTest @ValueSource(strings = {"choose", "finish"})
  void aRejectedPhoneSaveRetainsItsExactAcceptedRunAndRemainsRetryable(String action) {
    open();
    if (action.equals("finish")) replies(121140202, 121140205);
    String before = gson.toJson(actor.getGameplay()); String durable = actor.durableGameplay;
    int saves = actor.saves; actor.failSave = true;
    assertThrows(IllegalStateException.class, () -> {
      if (action.equals("choose")) new MessageManager(actor).choose(121140202, 0);
      else new MessageManager(actor).finishSection(SECTION);
    });
    assertEquals(before, gson.toJson(actor.getGameplay())); assertEquals(durable, actor.durableGameplay);
    assertEquals(saves, actor.saves); assertEquals(0, actor.getScoin());
    actor.failSave = false;
    if (action.equals("choose")) {
      assertEquals(Retcode.SUCC, new MessageManager(actor).choose(121140202, 0));
      assertEquals(Retcode.SUCC, new MessageManager(actor).choose(121140205, 0));
    }
    assertEquals(Retcode.SUCC, new MessageManager(actor).finishSection(SECTION));
    assertEquals(5000, actor.getScoin());
  }

  @ParameterizedTest @ValueSource(strings = {"missingTask", "wrongTargetScript", "wrongSection", "perform", "duplicateGroup", "missingItem", "cycle", "foreignNode"})
  void anIncompleteOrForeignMessageSourceCannotAuthorizeRepeatability(String damaged) {
    var goal = GameplayDefinitions.subs.get(GOAL);
    var section = GameplayDefinitions.messageSections.get(SECTION);
    switch (damaged) {
      case "missingTask" -> GameplayDefinitions.subs.get(FIRST).setTask(null);
      case "wrongTargetScript" -> goal.setTask(GameplayDefinitions.subs.get(FIRST).getTask());
      case "wrongSection" -> goal.setParamInt1(1211400);
      case "perform" -> GameplayDefinitions.messageSections.put(SECTION,
          new GameplayDefinitions.MessageSection(SECTION, true, 0, section.StartMessageItemIDList()));
      case "duplicateGroup" -> GameplayDefinitions.messageGroups.put(11126,
          new GameplayDefinitions.MessageGroup(11126, 48, 0, List.of(SECTION)));
      case "missingItem" -> GameplayDefinitions.messageItems.remove(121140210);
      case "cycle" -> GameplayDefinitions.messageItems.put(121140210,
          new GameplayDefinitions.MessageItem(121140210, SECTION, "NPC", "Text", List.of(121140201)));
      case "foreignNode" -> GameplayDefinitions.messageItems.put(121140210,
          new GameplayDefinitions.MessageItem(121140210, 1211400, "NPC", "Text", List.of()));
      default -> fail(damaged);
    }
    assertFalse(DailyMissionRepeats.supportedGraph(MAIN));
    actor.getMissionManager().refresh(); assertFalse(actor.getGameplay().getMainMissions().containsKey(MAIN));
  }

  @Test
  void twoRealPlayerDocumentReadsPreservePartialRepliesAndPreventFinishedHistoryFromClosingTheNextRun() throws Exception {
    open(); assertEquals(Retcode.SUCC, new MessageManager(actor).choose(121140202, 0));
    var server = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client = com.mongodb.client.MongoClients.create("mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "daily_message_repeat");
      store.getMapper().map(Player.class, QuestState.class);
      for (int iteration = 0; iteration < 2; iteration++) {
        var base = new Player();
        DailyMissionAcceptance46Test.set(base, "uid", actor.getUid());
        DailyMissionAcceptance46Test.set(base, "gameplay", actor.getGameplay());
        DailyMissionAcceptance46Test.set(base, "scoin", actor.getScoin());
        store.save(base);
        var restored = store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", actor.getUid())).first();
        assertNotNull(restored);
        actor = fresh();
        DailyMissionAcceptance46Test.set(actor, "gameplay", restored.getGameplay());
        DailyMissionAcceptance46Test.set(actor, "scoin", restored.getScoin());
        clock(TODAY); floor();
        assertEquals(List.of(121140205, 121140207), offered(phone(SECTION)));
        assertEquals(List.of(121140201, 121140212, 121140213, 121140202, 121140204), history(phone(SECTION)));
      }
      assertEquals(Retcode.SUCC, new MessageManager(actor).choose(121140205, 0));
      assertEquals(Retcode.SUCC, new MessageManager(actor).finishSection(SECTION));
      assertEquals(5000, actor.getScoin());
      clock(NEXT); actor.getDailyManager().ensureDay();
      assertEquals(Retcode.SUCC, actor.getMissionManager().accept(MAIN));
      assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(FIRST, "Mission_300060201"));
      assertEquals(List.of(121140202, 121140203), offered(phone(SECTION)));
      assertFalse(new MessageManager(actor).finished(SECTION));
      pay(121140203, 121140207); assertEquals(10000, actor.getScoin());
    } finally { server.shutdownNow(); }
  }

  @Test
  void aPreviouslyCompletedSiblingSectionKeepsItsActualGlobalHistoryWhileBothCommissionsGetTheirOwnPhones() throws Exception {
    catalog(3000601);
    // A real old global document stays separate from both newly supported accepted-period views.
    var older = new GameplayState.PhoneSection(1211400, List.of(121140101));
    for (int id : List.of(121140101, 121140111, 121140104, 121140105, 121140108, 121140109, 121140110))
      older.step(id, GameplayDefinitions.messageItems.get(id).NextItemIDList());
    older.finish(); actor.getGameplay().getPhoneSections().put(1211400, older);
    var oldHistory = List.copyOf(older.getHistory()); String oldJson = gson.toJson(older);
    floor(); assertEquals(Retcode.SUCC, actor.getMissionManager().accept(3000601));
    for (int id : List.of(300060001, 300060101))
      assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(id, "Mission_" + id));
    assertEquals(Retcode.SUCC, new MessageManager(actor).choose(121140102, 0));
    assertEquals(Retcode.SUCC, new MessageManager(actor).finishSection(1211400));
    var accepted = new MessageManager(actor).states().get(1211400);
    assertNotSame(older, accepted); assertTrue(accepted.isFinished());
    var acceptedHistory = List.copyOf(accepted.getHistory());
    assertNotEquals(oldHistory, acceptedHistory); assertSame(older, actor.getGameplay().getPhoneSections().get(1211400));
    assertEquals(5000, actor.getScoin());
    catalog(3000601, MAIN); clock(NEXT);
    assertEquals(Retcode.SUCC, actor.getMissionManager().accept(MAIN)); actor.getDailyManager().ensureDay();
    actor.getMissionManager().refresh();
    assertTrue(MissionStateRecords.mainDoing(actor, MAIN));
    assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(FIRST, "Mission_300060201"));
    assertSame(older, actor.getGameplay().getPhoneSections().get(1211400));
    assertEquals(acceptedHistory, history(phone(1211400))); assertEquals(oldJson, gson.toJson(older));
    assertEquals(List.of(121140202, 121140203), offered(phone(SECTION)));
    pay(121140202, 121140205); assertEquals(10000, actor.getScoin());
    assertSame(older, actor.getGameplay().getPhoneSections().get(1211400));
    assertEquals(oldHistory, older.getHistory()); assertEquals(oldJson, gson.toJson(older));
  }

  @Test
  void theExisting4_6GroupQueryShowsFreshRepliesAndTheNewRewardArrivesOnlyAfterRealCompletion() {
    session(); open(); pay(121140202, 121140205);
    clock(NEXT); actor.getDailyManager().ensureDay();
    assertEquals(Retcode.SUCC, actor.getMissionManager().accept(MAIN));
    assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(FIRST, "Mission_300060201"));
    var fresh = phone(SECTION);
    assertEquals(1, ((Descriptors.EnumValueDescriptor) field(fresh, "status")).getNumber());
    assertEquals(List.of(121140201, 121140212, 121140213), history(fresh));
    assertEquals(List.of(121140202, 121140203), offered(fresh));
    packets.clear();
    assertEquals(Retcode.SUCC, new MessageManager(actor).choose(121140203, 0));
    assertEquals(Retcode.SUCC, new MessageManager(actor).choose(121140207, 0));
    assertEquals(5000, actor.getScoin());
    assertTrue(packets.stream().noneMatch(packet -> packet.getKey().equals("MissionRewardScNotify")));
    assertEquals(Retcode.SUCC, new MessageManager(actor).finishSection(SECTION));
    var rewards = packets.stream().filter(packet -> packet.getKey().equals("MissionRewardScNotify")).toList();
    assertEquals(1, rewards.size()); assertEquals(MAIN, field(rewards.getFirst().getValue(), "main_mission_id"));
    assertEquals(10000, actor.getScoin());
  }
}
