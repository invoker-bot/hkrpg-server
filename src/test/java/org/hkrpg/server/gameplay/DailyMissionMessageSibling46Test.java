package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.config.MissionTaskInfo;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/**
 * Original 3000603 accepted-period regressions, first executed against a pinned runtime in build.
 * Composes existing static Player fixture and full fixed G61 originals; no new runtime API is required.
 * Original menu predicates are inspected. The client script interpreter and UI are not executed.
 */
class DailyMissionMessageSibling46Test {
  static final int MAIN = 3000603, FIRST = 300060301, GOAL = 300060302, SECTION = 1211402;
  static final Instant TODAY = DailyMissionMessage46Test.TODAY, NEXT = DailyMissionMessage46Test.NEXT;
  final G61NpcFirstTalk46Test g61 = new G61NpcFirstTalk46Test();
  final DailyMissionMessage46Test daily = g61.daily;
  final Gson gson = daily.gson;
  @TempDir Path root;

  @BeforeEach void setup() throws Exception {
    g61.root = root;
    g61.setup();
    daily.actor.getGameplay().getMainMissions().put(1010201, DailyMissionAcceptance46Test.closed(1010201));
    // Install the complete original sibling definitions and actual MissionJsonPath scripts, not
    // invented sub goals. G61's helper loads the original NPC graphs; the daily helper loads 3000602.
    var definitions = new HashMap<Integer, JsonObject>();
    var children = new HashMap<Integer, JsonArray>();
    var scripts = new HashMap<String, JsonObject>();
    for (var raw : g61.source.getAsJsonArray("records")) {
      var record = raw.getAsJsonObject();
      var row = record.getAsJsonObject("row");
      String kind = record.get("kind").getAsString();
      switch (kind) {
        case "main" -> GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
        case "daily" -> daily.daily.put(row.get("ID").getAsInt(), row.deepCopy());
        case "definition" -> definitions.put(row.get("MainMissionID").getAsInt(), row.deepCopy());
        case "sub" -> children.computeIfAbsent(row.get("MainMissionID").getAsInt(), ignored -> new JsonArray()).add(row.deepCopy());
        case "script" -> scripts.put(record.get("source").getAsString().substring("resources/4.6/".length()), row.deepCopy());
        default -> {}
      }
      if (kind.equals("messageSection") && row.get("ID").getAsInt() == SECTION) {
        var value = gson.fromJson(row, GameplayDefinitions.MessageSection.class);
        GameplayDefinitions.messageSections.put(value.ID(), value);
      }
      if (kind.equals("messageItem") && row.get("SectionID").getAsInt() == SECTION) {
        var value = gson.fromJson(row, GameplayDefinitions.MessageItem.class);
        GameplayDefinitions.messageItems.put(value.ID(), value);
      }
    }
    for (int id : List.of(3000601, MAIN)) {
      var definition = Objects.requireNonNull(definitions.get(id));
      definition.add("SubMissionList", Objects.requireNonNull(children.get(id)));
      var graph = gson.fromJson(definition, MissionInfo.class);
      GameData.getMissionInfos().put(id, graph);
      for (var sub : graph.getSubMissionList()) {
        GameplayDefinitions.subs.put(sub.getId(), sub);
        var source = Objects.requireNonNull(scripts.get(sub.getMissionJsonPath()), sub.getMissionJsonPath());
        var task = gson.fromJson(source, MissionTaskInfo.class);
        task.retainSourceDefinition(source);
        sub.setTask(task);
      }
    }
    assertEquals(17, GameplayDefinitions.messageItems.values().stream().filter(row -> row.SectionID() == SECTION).count());
    assertEquals(List.of(121140301), GameplayDefinitions.messageSections.get(SECTION).StartMessageItemIDList());
    daily.catalog(MAIN);
  }

  @AfterEach void cleanup() throws Exception { g61.cleanup(); }

  MessageManager messages() { return new MessageManager(daily.actor); }

  void open() {
    g61.floor();
    assertEquals(Retcode.SUCC, daily.actor.getMissionManager().accept(MAIN));
    assertEquals(Retcode.SUCC, daily.actor.getMissionManager().finishTalk(FIRST, "Mission_300060301"));
    assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(GOAL), GOAL, 1));
    assertNotNull(daily.phone(SECTION));
    assertEquals(List.of(121140301, 121140316, 121140317), daily.history(daily.phone(SECTION)));
    assertEquals(List.of(121140302, 121140303), daily.offered(daily.phone(SECTION)));
    assertFalse(messages().finished(SECTION));
  }

  DailyMissionMessageState.Run run() {
    var value = daily.actor.getGameplay().getDailyMissionMessages().getRuns().get(MAIN + ":" + DailyManager.day(daily.now));
    assertNotNull(value, "Actual phone requests must save this original commission in its own accepted period");
    assertEquals(MAIN, value.getMainId());
    assertEquals(GOAL, value.getGoalId());
    assertEquals(SECTION, value.getSectionId());
    assertEquals(DailyManager.day(daily.now), value.getDay());
    assertEquals(daily.actor.getGameplay().getMainMissions().get(MAIN).getObservationKey(), value.getMainAcceptance());
    assertEquals(daily.actor.getMissionManager().sub(GOAL).getObservationKey(), value.getGoalAcceptance());
    return value;
  }

  void replies(int first, int second, int third) {
    assertEquals(Retcode.SUCC, messages().choose(first, 0));
    assertEquals(6, daily.history(daily.phone(SECTION)).size());
    assertEquals(List.of(121140306, 121140308), daily.offered(daily.phone(SECTION)));
    assertEquals(Retcode.SUCC, messages().choose(second, 0));
    assertEquals(9, daily.history(daily.phone(SECTION)).size());
    assertEquals(List.of(121140311, 121140312), daily.offered(daily.phone(SECTION)));
    assertEquals(Retcode.MESSAGE_SECTION_CAN_NOT_FINISH, messages().finishSection(SECTION));
    assertEquals(Retcode.SUCC, messages().choose(third, 0));
    assertEquals(13, daily.history(daily.phone(SECTION)).size());
    assertEquals(121140315, daily.history(daily.phone(SECTION)).getLast());
    assertTrue(daily.offered(daily.phone(SECTION)).isEmpty());
    assertFalse(messages().finished(SECTION));
    assertEquals(0, daily.actor.getMissionManager().sub(GOAL).getProgress());
  }

  void pay(int first, int second, int third) {
    int before = daily.actor.getScoin();
    replies(first, second, third);
    assertEquals(Retcode.SUCC, messages().finishSection(SECTION));
    assertEquals(QuestState.Phase.CLOSED, daily.actor.getGameplay().getMainMissions().get(MAIN).getPhase());
    assertEquals(before + 5000, daily.actor.getScoin());
    assertEquals(QuestState.Phase.FINISHED, daily.actor.getGameplay().getDailyQuests().get(2100003).getPhase());
    assertEquals(Retcode.SUCC, daily.actor.getDailyManager().claimQuest(2100003).retcode());
    assertEquals(200, daily.actor.getGameplay().getDailyPoints());
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, daily.actor.getDailyManager().claimQuest(2100003).retcode());
    assertTrue(run().getPhone().isFinished());
    assertFalse(daily.actor.getGameplay().getPhoneSections().containsKey(SECTION));
  }

  @Test void theCompleteOriginalSiblingQualifiesWithoutChangingManualOrBackTrackFields() {
    assertTrue(DailyMissionRepeats.supportedGraph(MAIN));
    assertEquals("Manual", GameData.getMainMissionExcelMap().get(MAIN).getTakeParam().getFirst().Type());
    assertTrue(GameplayDefinitions.subs.get(GOAL).isBackTrack());
    assertTrue(GameplayDefinitions.subs.get(GOAL).getMissionJsonPath().endsWith("Mission_300060303.json"));
  }

  @Test void originalCatalogRefreshActuallyAssignsTheSiblingBeforeAnyManualAcceptRequest() {
    daily.actor.getMissionManager().refresh();
    assertTrue(MissionStateRecords.mainDoing(daily.actor, MAIN));
    assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(FIRST), FIRST, 1));
    assertNull(daily.actor.getMissionManager().sub(GOAL));
    assertEquals(0, daily.actor.getScoin());
  }

  @Test void originalNpcFirstMeetingThenStartedMenuUsesTheExactSiblingMissionAndTalkPath() throws Exception {
    g61.floor();
    g61.succeeds();
    var intro = g61.observed().deepCopy();
    daily.actor.getMissionManager().refresh();
    assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(FIRST), FIRST, 1));
    var menuTasks = g61.graph(G61NpcFirstTalk46Test.PACK_GRAPH).getAsJsonArray("OnStartSequece")
        .get(0).getAsJsonObject().getAsJsonArray("TaskList");
    var menu = menuTasks.asList().stream().map(JsonElement::getAsJsonObject)
        .filter(task -> task.has("Predicate") && task.getAsJsonObject("Predicate").has("SubMissionID")
            && task.getAsJsonObject("Predicate").get("SubMissionID").getAsInt() == FIRST).findFirst().orElseThrow();
    assertEquals("Started", menu.getAsJsonObject("Predicate").get("SubMissionState").getAsString());
    var branch = menu.getAsJsonArray("SuccessTaskList").get(0).getAsJsonObject();
    assertEquals(FIRST, branch.get("MenuItemID").getAsInt());
    assertEquals(FIRST, branch.get("MissionID").getAsInt());
    assertEquals("Config/Level/Mission/3000603/Talk/Talk_300060301.json", branch.get("DialoguePath").getAsString());
    assertEquals(Retcode.SUCC, daily.actor.getMissionManager().finishTalk(FIRST, "Mission_300060301"));
    assertNotNull(run());
    assertEquals(intro, g61.observed());
  }

  @ParameterizedTest @CsvSource({"121140302,121140306,121140311", "121140302,121140306,121140312",
      "121140302,121140308,121140311", "121140302,121140308,121140312", "121140303,121140306,121140311",
      "121140303,121140306,121140312", "121140303,121140308,121140311", "121140303,121140308,121140312"})
  void allEightOriginalBranchesNeedThreeRealChoicesAndTheFinalRequest(int first, int second, int third) {
    open();
    assertEquals(Retcode.MESSAGE_SECTION_CAN_NOT_FINISH, messages().finishSection(SECTION));
    assertEquals(Retcode.MESSAGE_ITEM_CAN_NOT_FINISH, messages().choose(second, 0));
    assertEquals(Retcode.MESSAGE_ITEM_CAN_NOT_FINISH, messages().choose(third, 0));
    assertEquals(Retcode.MESSAGE_ITEM_CAN_NOT_FINISH, messages().choose(first, 1));
    pay(first, second, third);
    assertEquals(1, TodayDailyMissionCounts.count(daily.actor, DailyManager.day(TODAY), TODAY));
    assertEquals(Retcode.MESSAGE_SECTION_CAN_NOT_FINISH, messages().finishSection(SECTION));
    assertEquals(5000, daily.actor.getScoin());
  }

  @Test void previouslyFinishedGlobalSiblingHistoryCannotCloseOrPayANewAcceptedCommission() {
    // Explicit migrated legacy history is the defect precondition; this test must never use it as completion evidence.
    var legacy = new GameplayState.PhoneSection(SECTION, List.of(121140301));
    for (int id : List.of(121140301, 121140316, 121140317, 121140302, 121140304, 121140305,
        121140306, 121140307, 121140310, 121140311, 121140313, 121140314, 121140315))
      legacy.step(id, GameplayDefinitions.messageItems.get(id).NextItemIDList());
    legacy.finish();
    var previous = gson.toJson(legacy);
    daily.actor.getGameplay().getPhoneSections().put(SECTION, legacy);
    g61.floor();
    assertEquals(Retcode.SUCC, daily.actor.getMissionManager().accept(MAIN));
    assertEquals(Retcode.SUCC, daily.actor.getMissionManager().finishTalk(FIRST, "Mission_300060301"));
    assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(GOAL), GOAL, 1));
    assertTrue(MissionStateRecords.mainDoing(daily.actor, MAIN));
    assertEquals(0, daily.actor.getScoin());
    assertFalse(messages().finished(SECTION));
    assertEquals(List.of(121140302, 121140303), daily.offered(daily.phone(SECTION)));
    assertSame(legacy, daily.actor.getGameplay().getPhoneSections().get(SECTION));
    assertEquals(previous, gson.toJson(legacy));
    assertEquals(Retcode.MESSAGE_SECTION_CAN_NOT_FINISH, messages().finishSection(SECTION));
    assertNotNull(run());
  }

  @Test void unfinishedOriginalTalkNeverAuthorizesSiblingPhoneOrItsThirdChoice() {
    g61.floor();
    assertEquals(Retcode.SUCC, daily.actor.getMissionManager().accept(MAIN));
    assertNull(daily.phone(SECTION));
    assertEquals(Retcode.MESSAGE_SECTION_NOT_TAKE, messages().finishSection(SECTION));
    assertEquals(Retcode.MESSAGE_SECTION_NOT_TAKE, messages().choose(121140311, 0));
    assertEquals(0, daily.actor.getScoin());
  }

  @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3})
  void anyUnpaidPrefixExpiresAndTheNewDayBeginsAtTheOriginalOpening(int count) {
    open();
    for (int id : List.of(121140302, 121140306, 121140311).subList(0, count))
      assertEquals(Retcode.SUCC, messages().choose(id, 0));
    var oldMain = daily.actor.getGameplay().getMainMissions().get(MAIN);
    daily.clock(NEXT); daily.actor.getDailyManager().ensureDay(); daily.actor.getMissionManager().refresh();
    assertTrue(MissionStateRecords.mainDoing(daily.actor, MAIN));
    assertEquals(0, daily.actor.getMissionManager().sub(FIRST).getProgress());
    assertNull(daily.actor.getMissionManager().sub(GOAL));
    assertEquals(0, daily.actor.getScoin());
    assertFalse(daily.actor.getRewardLedger().granted("mission:" + MAIN));
    assertSame(oldMain, daily.actor.getGameplay().getDailyMissionPeriods().getExpired()
        .get(MAIN + ":" + DailyManager.day(TODAY)).getMain());
    assertEquals(Retcode.SUCC, daily.actor.getMissionManager().finishTalk(FIRST, "Mission_300060301"));
    assertEquals(3, daily.history(daily.phone(SECTION)).size());
    assertEquals(List.of(121140302, 121140303), daily.offered(daily.phone(SECTION)));
    pay(121140303, 121140308, 121140312);
    assertEquals(5000, daily.actor.getScoin());
  }

  @Test void settledSiblingMustRedoAllThreeRepliesInTheNextAcceptedPeriod() {
    open(); pay(121140302, 121140306, 121140311);
    var prior = run();
    daily.clock(NEXT); daily.actor.getDailyManager().ensureDay();
    assertEquals(Retcode.SUCC, daily.actor.getMissionManager().accept(MAIN));
    assertNull(daily.actor.getMissionManager().sub(GOAL));
    assertEquals(Retcode.SUCC, daily.actor.getMissionManager().finishTalk(FIRST, "Mission_300060301"));
    assertNotSame(prior, run());
    assertNotEquals(prior.getMainAcceptance(), run().getMainAcceptance());
    assertNotEquals(prior.getGoalAcceptance(), run().getGoalAcceptance());
    assertEquals(List.of(121140302, 121140303), daily.offered(daily.phone(SECTION)));
    assertFalse(messages().finished(SECTION));
    pay(121140303, 121140308, 121140312);
    assertEquals(10000, daily.actor.getScoin());
  }

  @Test void twoSiblingPhonesCannotConsumeTwoCopiesOfTheSameAuthoredDailyQuota() throws Exception {
    g61.floor();
    assertEquals(Retcode.SUCC, daily.actor.getMissionManager().accept(MAIN));
    daily.catalog(3000602, MAIN);
    assertEquals(Retcode.MISSION_NOT_DOING, daily.actor.getMissionManager().accept(3000602));
    assertEquals(1, daily.actor.getGameplay().getDailyMissionSelections().getDays()
        .get(Long.toString(DailyManager.day(TODAY))).size());
    assertEquals(0, daily.actor.getScoin());
  }

  @Test void theExisting3000602RunAndFirstMeetingRemainIntactWhenTheNextDaySelects3000603() throws Exception {
    // Loading the real floor refreshes the catalog. Select the first day's original commission
    // before entering it, rather than removing a correctly auto-assigned 3000603 catalog entry.
    daily.catalog(3000602);
    g61.floor(); g61.succeeds();
    var intro = g61.observed().deepCopy();
    daily.open(); daily.pay(121140202, 121140205);
    var older = daily.actor.getGameplay().getDailyMissionMessages().getRuns().get("3000602:" + DailyManager.day(TODAY));
    assertNotNull(older);
    String olderJson = gson.toJson(older);
    // Both real source catalog rows remain installed so the earlier 3000602 source can still be validated.
    // Manual acceptance selects the new day through the existing quota API before scene refresh can draw a sibling.
    daily.catalog(3000602, MAIN); daily.clock(NEXT); daily.actor.getDailyManager().ensureDay();
    assertEquals(Retcode.SUCC, daily.actor.getMissionManager().accept(MAIN));
    open(); pay(121140303, 121140308, 121140312);
    assertEquals(10000, daily.actor.getScoin());
    assertSame(older, daily.actor.getGameplay().getDailyMissionMessages().getRuns().get("3000602:" + DailyManager.day(TODAY)));
    assertEquals(olderJson, gson.toJson(older));
    assertEquals(intro, g61.observed());
  }

  @ParameterizedTest @ValueSource(strings = {"main", "goal"})
  void aFullyWalkedSiblingCannotCompleteAReplacementAcceptanceWithTheSameIds(String replaced) {
    open(); replies(121140302, 121140306, 121140311);
    if (replaced.equals("main")) daily.actor.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    else daily.actor.getGameplay().getSubMissions().put(GOAL, new QuestState(GOAL));
    String before = gson.toJson(daily.actor.getGameplay()); int saves = daily.actor.saves;
    assertNotEquals(Retcode.SUCC, messages().finishSection(SECTION));
    assertEquals(before, gson.toJson(daily.actor.getGameplay()));
    assertEquals(saves, daily.actor.saves);
    assertEquals(0, daily.actor.getScoin());
  }

  @ParameterizedTest @ValueSource(strings = {"reply", "section"})
  void saveRejectionRestoresTheExactSiblingPeriodAndTheSameRequestRemainsRetryable(String action) {
    open();
    if (action.equals("section")) replies(121140302, 121140306, 121140311);
    var original = run();
    String before = gson.toJson(daily.actor.getGameplay()); String durable = daily.actor.durableGameplay;
    int saves = daily.actor.saves;
    daily.actor.failSave = true;
    assertThrows(IllegalStateException.class, () -> {
      if (action.equals("reply")) messages().choose(121140302, 0);
      else messages().finishSection(SECTION);
    });
    assertSame(original, run());
    assertEquals(before, gson.toJson(daily.actor.getGameplay()));
    assertEquals(durable, daily.actor.durableGameplay);
    assertEquals(saves, daily.actor.saves);
    daily.actor.failSave = false;
    if (action.equals("reply")) {
      assertEquals(Retcode.SUCC, messages().choose(121140302, 0));
      assertEquals(Retcode.SUCC, messages().choose(121140306, 0));
      assertEquals(Retcode.SUCC, messages().choose(121140311, 0));
    }
    assertEquals(Retcode.SUCC, messages().finishSection(SECTION));
    assertEquals(5000, daily.actor.getScoin());
  }

  @Test void twoActualPlayerDocumentReadsPreserveTheThirdOfferAndBothAcceptanceKeys() throws Exception {
    open();
    assertEquals(Retcode.SUCC, messages().choose(121140302, 0));
    assertEquals(Retcode.SUCC, messages().choose(121140306, 0));
    var before = gson.toJson(run());
    var server = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client = com.mongodb.client.MongoClients.create("mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "daily_message_sibling");
      store.getMapper().map(Player.class, QuestState.class);
      for (int iteration = 0; iteration < 2; iteration++) {
        var base = new Player();
        DailyMissionAcceptance46Test.set(base, "uid", daily.actor.getUid());
        DailyMissionAcceptance46Test.set(base, "gameplay", daily.actor.getGameplay());
        DailyMissionAcceptance46Test.set(base, "scoin", daily.actor.getScoin());
        store.save(base);
        var restored = store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", daily.actor.getUid())).first();
        assertNotNull(restored);
        daily.actor = daily.fresh();
        DailyMissionAcceptance46Test.set(daily.actor, "gameplay", restored.getGameplay());
        DailyMissionAcceptance46Test.set(daily.actor, "scoin", restored.getScoin());
        daily.clock(TODAY); g61.floor();
        assertEquals(before, gson.toJson(run()));
        assertEquals(9, daily.history(daily.phone(SECTION)).size());
        assertEquals(List.of(121140311, 121140312), daily.offered(daily.phone(SECTION)));
      }
      assertEquals(Retcode.SUCC, messages().choose(121140312, 0));
      assertEquals(Retcode.SUCC, messages().finishSection(SECTION));
      assertEquals(5000, daily.actor.getScoin());
    } finally { server.shutdownNow(); }
  }

  @ParameterizedTest @ValueSource(strings = {"missingTask", "wrongTargetScript", "wrongSection", "perform",
      "duplicateGroup", "missingItem", "cycle", "foreignNode"})
  void aDamagedSiblingGraphCannotGainRepeatabilityOrRevokeTheSupported3000602Graph(String damaged) throws Exception {
    daily.catalog(3000602, MAIN);
    var goal = GameplayDefinitions.subs.get(GOAL);
    var section = GameplayDefinitions.messageSections.get(SECTION);
    switch (damaged) {
      case "missingTask" -> GameplayDefinitions.subs.get(FIRST).setTask(null);
      // The authored 300060203 and 300060303 goal blobs are genuinely identical. A different
      // container object with equal complete source is not damage; use the distinct original Talk.
      case "wrongTargetScript" -> goal.setTask(GameplayDefinitions.subs.get(FIRST).getTask());
      case "wrongSection" -> goal.setParamInt1(1211401);
      case "perform" -> GameplayDefinitions.messageSections.put(SECTION,
          new GameplayDefinitions.MessageSection(SECTION, true, 0, section.StartMessageItemIDList()));
      case "duplicateGroup" -> GameplayDefinitions.messageGroups.put(11126,
          new GameplayDefinitions.MessageGroup(11126, 48, 0, List.of(SECTION)));
      case "missingItem" -> GameplayDefinitions.messageItems.remove(121140315);
      case "cycle" -> GameplayDefinitions.messageItems.put(121140315,
          new GameplayDefinitions.MessageItem(121140315, SECTION, "NPC", "Text", List.of(121140301)));
      case "foreignNode" -> GameplayDefinitions.messageItems.put(121140315,
          new GameplayDefinitions.MessageItem(121140315, 1211400, "NPC", "Text", List.of()));
      default -> fail(damaged);
    }
    assertFalse(DailyMissionRepeats.supportedGraph(MAIN));
    assertTrue(DailyMissionRepeats.supportedGraph(3000602));
    daily.actor.getMissionManager().refresh();
    assertFalse(daily.actor.getGameplay().getMainMissions().containsKey(MAIN));
  }

  @Test void original3000601StillRequiresItsCompleteSeparateThreeStageSource() throws Exception {
    daily.catalog(3000601, 3000602, MAIN);
    assertTrue(DailyMissionRepeats.supportedGraph(3000601));
    // A genuinely different complete original task cannot replace original 300060001.
    GameplayDefinitions.subs.get(300060001).setTask(GameplayDefinitions.subs.get(300060301).getTask());
    assertFalse(DailyMissionRepeats.supportedGraph(3000601));
    assertTrue(DailyMissionRepeats.supportedGraph(3000602));
    assertTrue(DailyMissionRepeats.supportedGraph(MAIN));
  }
}
