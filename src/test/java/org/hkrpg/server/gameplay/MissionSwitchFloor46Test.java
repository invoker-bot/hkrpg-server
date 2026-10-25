package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.util.Position;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original branch and message graphs; tutorial, empty floor groups and zero party are fixtures. */
class MissionSwitchFloor46Test {
  static final int MAIN = 4340104, FIRST = 434010404, SECOND = 434010407;
  final Gson gson = emu.lunarcore.data.ResourceLoader.getGson();
  final List<MainMissionExcel> oldMains = new ArrayList<>();
  final List<MazePlaneExcel> oldPlanes = new ArrayList<>();
  final List<RewardExcel> oldRewards = new ArrayList<>();
  final List<ItemExcel> oldItems = new ArrayList<>();
  final Map<Integer, MissionInfo> oldGraphs = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  final Map<String, FloorInfo> oldFloors = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MessageSection> oldSections = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MessageGroup> oldGroups = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MessageItem> oldMessages = new HashMap<>();
  Actor actor;

  static class Actor extends Player {
    final LineupManager teams = new LineupManager(this);
    int saves;
    Runnable movingSave;

    Actor() {
      resetPosition();
      assertTrue(setNickname("Original floor change fixture"));
      getCurrentLineup().getAvatars().add(0);
      getMissionManager().setClock(Clock.fixed(DailyMissionAcceptance46Test.TODAY, ZoneOffset.UTC));
      getDailyManager().setClock(Clock.fixed(DailyMissionAcceptance46Test.TODAY, ZoneOffset.UTC));
    }

    @Override
    public LineupManager getLineupManager() {
      return teams;
    }

    @Override
    public void save() {
      saves++;
      if (movingSave != null) {
        var hook = movingSave;
        movingSave = null;
        hook.run();
      }
    }
  }

  @BeforeEach
  void setup() throws Exception {
    oldMains.addAll(GameData.getMainMissionExcelMap().values());
    oldPlanes.addAll(GameData.getMazePlaneExcelMap().values());
    oldRewards.addAll(GameData.getRewardExcelMap().values());
    oldItems.addAll(GameData.getItemExcelMap().values());
    oldGraphs.putAll(GameData.getMissionInfos());
    oldSubs.putAll(GameplayDefinitions.subs);
    oldFloors.putAll(GameData.getFloorInfos());
    oldSections.putAll(GameplayDefinitions.messageSections);
    oldGroups.putAll(GameplayDefinitions.messageGroups);
    oldMessages.putAll(GameplayDefinitions.messageItems);
    GameData.getMainMissionExcelMap().values().clear();
    GameData.getMazePlaneExcelMap().values().clear();
    GameData.getRewardExcelMap().values().clear();
    GameData.getItemExcelMap().values().clear();
    GameData.getMissionInfos().clear();
    GameplayDefinitions.subs.clear();
    GameData.getFloorInfos().clear();
    GameplayDefinitions.messageSections.clear();
    GameplayDefinitions.messageGroups.clear();
    GameplayDefinitions.messageItems.clear();
    JsonObject definition = null;
    var children = new JsonArray();
    try (var input = getClass().getResourceAsStream("/fixtures/mission-switch-floor-4.6.json")) {
      var rows =
          JsonParser.parseString(
                  new String(
                      Objects.requireNonNull(input).readAllBytes(),
                      java.nio.charset.StandardCharsets.UTF_8))
              .getAsJsonArray();
      assertEquals(47, rows.size());
      for (var entry : rows) {
        var raw = entry.getAsJsonObject();
        var row = raw.getAsJsonObject("row");
        switch (raw.get("kind").getAsString()) {
          case "main" ->
              GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
          case "definition" -> definition = row;
          case "sub" -> children.add(row);
          case "plane" ->
              GameData.getMazePlaneExcelMap().add(gson.fromJson(row, MazePlaneExcel.class));
          case "floor" -> {
            var floor = gson.fromJson(row, FloorInfo.class);
            GameData.getFloorInfos()
                .put("P" + floor.getFloorID() / 1000 + "_F" + floor.getFloorID(), floor);
          }
          case "reward" -> {
            var reward = gson.fromJson(row, RewardExcel.class);
            reward.onLoad();
            GameData.getRewardExcelMap().add(reward);
          }
          case "item" -> GameData.getItemExcelMap().add(gson.fromJson(row, ItemExcel.class));
          case "section" -> {
            var section = gson.fromJson(row, GameplayDefinitions.MessageSection.class);
            GameplayDefinitions.messageSections.put(section.ID(), section);
          }
          case "messageGroup" -> {
            var group = gson.fromJson(row, GameplayDefinitions.MessageGroup.class);
            GameplayDefinitions.messageGroups.put(group.ID(), group);
          }
          case "messageItem" -> {
            var item = gson.fromJson(row, GameplayDefinitions.MessageItem.class);
            GameplayDefinitions.messageItems.put(item.ID(), item);
          }
          default -> fail("Unknown original kind");
        }
      }
    }
    Objects.requireNonNull(definition).add("SubMissionList", children);
    var graph = gson.fromJson(definition, MissionInfo.class);
    GameData.getMissionInfos().put(MAIN, graph);
    for (var sub : graph.getSubMissionList()) GameplayDefinitions.subs.put(sub.getId(), sub);
    actor = new Actor();
    actor
        .getGameplay()
        .getMainMissions()
        .put(1030801, DailyMissionAcceptance46Test.closed(1030801));
  }

  @AfterEach
  void cleanup() {
    GameData.getMainMissionExcelMap().values().clear();
    oldMains.forEach(GameData.getMainMissionExcelMap()::add);
    GameData.getMazePlaneExcelMap().values().clear();
    oldPlanes.forEach(GameData.getMazePlaneExcelMap()::add);
    GameData.getRewardExcelMap().values().clear();
    oldRewards.forEach(GameData.getRewardExcelMap()::add);
    GameData.getItemExcelMap().values().clear();
    oldItems.forEach(GameData.getItemExcelMap()::add);
    GameData.getMissionInfos().clear();
    GameData.getMissionInfos().putAll(oldGraphs);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
    GameData.getFloorInfos().clear();
    GameData.getFloorInfos().putAll(oldFloors);
    GameplayDefinitions.messageSections.clear();
    GameplayDefinitions.messageSections.putAll(oldSections);
    GameplayDefinitions.messageGroups.clear();
    GameplayDefinitions.messageGroups.putAll(oldGroups);
    GameplayDefinitions.messageItems.clear();
    GameplayDefinitions.messageItems.putAll(oldMessages);
  }

  boolean move(int plane) {
    return actor.loadScene(
        plane, plane * 1000 + 1, plane * 100 + 1, new Position(), new Position(), false);
  }

  void reachFirst() {
    assertTrue(move(20311));
    assertTrue(MissionStateRecords.mainDoing(actor, MAIN));
    assertEquals(
        Retcode.SUCC, actor.getMissionManager().finishTalk(434010401, "Mission_434010401"));
    assertEquals(
        Retcode.SUCC,
        actor.getMissionManager().finishTalk(434010402, "Mission_434010402", Map.of(0, 1)));
    bookPlan();
    assertEquals(0, actor.getScoin());
    assertNotNull(actor.getMissionManager().sub(FIRST));
    assertEquals(0, actor.getMissionManager().sub(FIRST).getProgress());
  }

  void performance(int section) {
    var row = GameplayDefinitions.messageSections.get(section);
    var choices = new ArrayList<Integer>();
    var next = row.StartMessageItemIDList();
    while (!next.isEmpty()) {
      int id = next.getFirst();
      assertFalse(choices.contains(id));
      choices.add(id);
      next = GameplayDefinitions.messageItems.get(id).NextItemIDList();
    }
    assertEquals(Retcode.SUCC, new MessageManager(actor).finishPerformance(section, choices));
  }

  void bookPlan() {
    var plan = actor.getGameplay().getMissionRewardPlans().get("submission:434010402");
    assertNotNull(plan);
    assertEquals(Map.of(190543, 1), plan.getItems());
  }

  void reachSecond() {
    reachFirst();
    assertTrue(move(20312));
    assertEquals(1, actor.getMissionManager().sub(FIRST).getProgress());
    assertEquals(0, actor.getMissionManager().sub(434010405).getProgress());
    assertTrue(move(20311));
    assertEquals(1, actor.getMissionManager().sub(434010405).getProgress());
    performance(1303200);
    assertEquals(1, actor.getMissionManager().sub(434010406).getProgress());
    assertEquals(0, actor.getMissionManager().sub(SECOND).getProgress());
  }

  @Test
  void actualAcceptedOutgoingSceneCompletesOnlyTheFirstOriginalSwitch() {
    reachFirst();
    assertTrue(move(20312));
    assertEquals(1, actor.getMissionManager().sub(FIRST).getProgress());
    assertEquals(0, actor.getMissionManager().sub(434010405).getProgress());
    assertNull(actor.getMissionManager().sub(SECOND));
    assertFalse(actor.getMissionManager().finished(MAIN));
    assertEquals(0, actor.getScoin());
  }

  @Test
  void originalBookRewardActuallyEntersTheBagAndClosesItsReceipt() {
    reachFirst();
    var book = actor.getInventory().getMaterialByItemId(190543);
    assertNotNull(book);
    assertEquals(1, book.getCount());
    assertTrue(actor.getRewardLedger().granted("submission:434010402"));
    assertEquals(QuestState.Phase.CLOSED, actor.getMissionManager().sub(434010402).getPhase());
  }

  @Test
  void sameFloorReloadAndAnotherEntranceDoNotBecomeAnOutgoingFloorChange() {
    reachFirst();
    var scene = actor.getScene();
    assertTrue(move(20311));
    assertSame(scene, actor.getScene());
    assertTrue(actor.loadScene(20311, 20311001, 2031102, new Position(), new Position(), false));
    assertSame(scene, actor.getScene());
    assertEquals(0, actor.getMissionManager().sub(FIRST).getProgress());
  }

  @Test
  void rejectedDestinationDoesNotChangeLocationOrFinishTheOriginalObjective() {
    reachFirst();
    int saved = actor.saves;
    var scene = actor.getScene();
    assertFalse(move(99999));
    assertEquals(20311001, actor.getFloorId());
    assertSame(scene, actor.getScene());
    assertEquals(saved, actor.saves);
    assertEquals(0, actor.getMissionManager().sub(FIRST).getProgress());
  }

  @Test
  void originalBranchRunsBothOutgoingAndReturningVisitsAndPaysItsActualTailOnce() {
    reachSecond();
    assertTrue(move(20312));
    assertEquals(1, actor.getMissionManager().sub(SECOND).getProgress());
    assertEquals(0, actor.getMissionManager().sub(434010408).getProgress());
    assertTrue(move(20311));
    assertEquals(1, actor.getMissionManager().sub(434010408).getProgress());
    performance(1303300);
    assertTrue(actor.getMissionManager().finished(MAIN));
    assertEquals(3000, actor.getScoin());
    assertEquals(3, actor.getInventory().getMaterialByItemId(212).getCount());
    bookPlan();
    assertNotNull(actor.getInventory().getMaterialByItemId(190543));
    assertEquals(1, actor.getInventory().getMaterialByItemId(190543).getCount());
    assertNull(actor.getMissionManager().sub(434010410));
    assertEquals(0, actor.getMissionManager().sub(434010403).getProgress());
    for (int i = 0; i < 12; i++) {
      actor.getMissionManager().refresh();
      assertTrue(move(i % 2 == 0 ? 20312 : 20311));
    }
    assertEquals(3000, actor.getScoin());
    assertEquals(3, actor.getInventory().getMaterialByItemId(212).getCount());
  }

  @Test
  void returnedOriginalFloorCannotFinishTheSecondSwitchBeforeItsOwnMessage() {
    reachFirst();
    assertTrue(move(20312));
    assertEquals(1, actor.getMissionManager().sub(FIRST).getProgress());
    assertTrue(move(20311));
    assertNull(actor.getMissionManager().sub(SECOND));
    assertEquals(0, actor.getMissionManager().sub(434010406).getProgress());
    assertFalse(actor.getMissionManager().finished(MAIN));
  }

  @Test
  void originalAlternativeChoiceWaitsADayAndNeverAcceptsEitherOutgoingFloorGoal() {
    assertTrue(move(20311));
    assertEquals(
        Retcode.SUCC, actor.getMissionManager().finishTalk(434010401, "Mission_434010401"));
    assertEquals(
        Retcode.SUCC,
        actor.getMissionManager().finishTalk(434010402, "Mission_434010402", Map.of(0, 2)));
    assertNull(actor.getMissionManager().sub(FIRST));
    assertNull(actor.getMissionManager().sub(434010403));
    assertNull(actor.getMissionManager().sub(434010411));
    assertEquals(0, actor.getMissionManager().sub(434010410).getProgress());
    assertTrue(move(20312));
    assertTrue(move(20311));
    assertNull(actor.getMissionManager().sub(FIRST));
    actor
        .getMissionManager()
        .setClock(
            Clock.fixed(DailyMissionAcceptance46Test.TODAY.plusSeconds(86400), ZoneOffset.UTC));
    actor.getMissionManager().refresh();
    assertEquals(1, actor.getMissionManager().sub(434010410).getProgress());
    performance(1303300);
    assertTrue(actor.getMissionManager().finished(MAIN));
    assertNull(actor.getMissionManager().sub(FIRST));
    assertNull(actor.getMissionManager().sub(SECOND));
    assertEquals(3000, actor.getScoin());
    assertEquals(3, actor.getInventory().getMaterialByItemId(212).getCount());
    assertEquals(1, actor.getInventory().getMaterialByItemId(190543).getCount());
  }

  @Test
  void originalChoiceCanInsteadDeliverAndConsumeItsAuthoredMissionItemForTheOtherTail() {
    reachFirst();
    assertEquals(
        Retcode.ITEM_NOT_ENOUGH,
        actor.getMissionManager().submitItems(434010403, Map.of(140424, 1)));
    assertFalse(actor.getMissionManager().finished(MAIN));
    assertEquals(
        Retcode.SUCC, actor.getMissionManager().finishTalk(434010411, "Mission_434010411"));
    assertEquals(1, actor.getInventory().getMaterialByItemId(140424).getCount());
    assertEquals(Retcode.SUCC, actor.getMissionManager().submitItems(434010403, Map.of(140424, 1)));
    assertTrue(actor.getMissionManager().finished(MAIN));
    assertEquals(10000, actor.getScoin());
    assertEquals(1, actor.getInventory().getMaterialByItemId(408418).getCount());
    assertEquals(1, actor.getInventory().getMaterialByItemId(190543).getCount());
    assertEquals(0, actor.getMissionManager().sub(FIRST).getProgress());
    assertNull(actor.getMissionManager().sub(SECOND));
    assertEquals(
        Retcode.MISSION_NOT_DOING,
        actor.getMissionManager().submitItems(434010403, Map.of(140424, 1)));
    for (int i = 0; i < 12; i++) actor.getMissionManager().refresh();
    assertEquals(10000, actor.getScoin());
    assertEquals(1, actor.getInventory().getMaterialByItemId(408418).getCount());
  }

  @Test
  void locationReportsAloneCannotSupplyTheCapturedServerOutgoingEvent() {
    reachFirst();
    actor.getMissionManager().onSceneTransition(20312, 20312001, 2031201, 20311, 20311001, 2031101);
    actor.getMissionManager().onEnterFloor(20311, 20311001);
    actor.getMissionManager().refresh();
    assertEquals(0, actor.getMissionManager().sub(FIRST).getProgress());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"wrongId", "finishTime", "overGoal", "nullPhase", "cancelSub", "cancelMain"})
  void corruptedOrCancelledOutgoingRecordsStayPending(String field) throws Exception {
    reachFirst();
    var state = actor.getMissionManager().sub(FIRST);
    switch (field) {
      case "wrongId" -> DailyMissionAcceptance46Test.set(state, "questId", FIRST + 1);
      case "finishTime" -> DailyMissionAcceptance46Test.set(state, "finishTime", 1L);
      case "overGoal" -> DailyMissionAcceptance46Test.set(state, "progress", 2);
      case "nullPhase" -> DailyMissionAcceptance46Test.set(state, "phase", null);
      case "cancelSub" -> actor.getGameplay().getCancelledSubs().add(FIRST);
      case "cancelMain" -> actor.getGameplay().getCancelledMissions().add(MAIN);
      default -> fail(field);
    }
    var before = gson.toJson(state);
    assertDoesNotThrow(() -> assertTrue(move(20312)));
    assertEquals(before, gson.toJson(state));
    assertFalse(actor.getMissionManager().finished(MAIN));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"paramType", "extraParam", "goal", "floor", "takeStrings", "script", "storyList"})
  void unsupportedAuthoredFloorShapesDoNotConsumeARealSceneChange(String field) {
    reachFirst();
    var c = GameplayDefinitions.subs.get(FIRST);
    switch (field) {
      case "paramType" -> c.setParamType("Equal");
      case "extraParam" -> c.setParamInt1(1);
      case "goal" -> c.setProgress(2);
      case "floor" -> c.setLevelFloorID(20312001);
      case "takeStrings" -> c.setTakeParamStrList(new String[] {"unknown"});
      case "script" -> c.setTask(new MissionTaskInfo());
      case "storyList" -> c.setStoryLineIDList(List.of(1));
      default -> fail(field);
    }
    assertTrue(move(20312));
    assertEquals(0, actor.getMissionManager().sub(FIRST).getProgress());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"sub", "main", "definition", "destination", "sourceFloor", "goal", "choice"})
  void identitiesAndConditionsChangedDuringTheActualSceneSaveCannotReuseAnOldTarget(String field) {
    reachFirst();
    actor.movingSave =
        () -> {
          switch (field) {
            case "sub" -> actor.getGameplay().getSubMissions().put(FIRST, new QuestState(FIRST));
            case "main" -> actor.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
            case "definition" ->
                GameplayDefinitions.subs.put(
                    FIRST,
                    gson.fromJson(
                        gson.toJson(GameplayDefinitions.subs.get(FIRST)),
                        MissionInfo.SubMissionInfo.class));
            case "destination" ->
                GameData.getMazePlaneExcelMap()
                    .add(
                        gson.fromJson(
                            gson.toJson(GameData.getMazePlaneExcelMap().get(20312)),
                            MazePlaneExcel.class));
            case "sourceFloor" ->
                GameData.getFloorInfos()
                    .put(
                        "P20311_F20311001",
                        gson.fromJson(
                            gson.toJson(GameData.getFloorInfo(20311, 20311001)), FloorInfo.class));
            case "goal" -> GameplayDefinitions.subs.get(FIRST).setProgress(2);
            case "choice" ->
                actor.getGameplay().getStoryValues().put("mission:4340104:OldValue_0", 2);
            default -> fail(field);
          }
        };
    assertTrue(move(20312));
    assertEquals(0, actor.getMissionManager().sub(FIRST).getProgress());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "wrong",
        "invalidValue",
        "unknownName",
        "anchorId",
        "anchorTime",
        "anchorCancelled",
        "mixed",
        "graph"
      })
  void namedGatesRequireTheirOwnValidCompletedDialogueAndDeclaredValue(String field)
      throws Exception {
    reachFirst();
    var target = GameplayDefinitions.subs.get(FIRST);
    var anchor = actor.getMissionManager().sub(434010402);
    switch (field) {
      case "missing" -> actor.getGameplay().getStoryValues().remove("mission:4340104:OldValue_0");
      case "wrong" -> actor.getGameplay().getStoryValues().put("mission:4340104:OldValue_0", 2);
      case "invalidValue" -> {
        target.setTakeParamStrList(new String[] {"OldValue_0", "3", "434010402"});
        actor.getGameplay().getStoryValues().put("mission:4340104:OldValue_0", 3);
      }
      case "unknownName" -> target.setTakeParamStrList(new String[] {"unknown", "1", "434010402"});
      case "anchorId" -> DailyMissionAcceptance46Test.set(anchor, "questId", 434010401);
      case "anchorTime" -> DailyMissionAcceptance46Test.set(anchor, "finishTime", 0L);
      case "anchorCancelled" -> actor.getGameplay().getCancelledSubs().add(434010402);
      case "mixed" -> target.setTakeParamIntList(new Integer[] {1});
      case "graph" ->
          DailyMissionAcceptance46Test.set(
              GameData.getMissionInfos().get(MAIN), "mainMissionID", MAIN + 1);
      default -> fail(field);
    }
    int saves = actor.saves;
    assertFalse(MissionNamedCustomGates.matches(actor, target));
    assertEquals(saves, actor.saves);
    assertTrue(move(20312));
    assertEquals(0, actor.getMissionManager().sub(FIRST).getProgress());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "trialBefore",
        "trialAfter",
        "rogueBefore",
        "rogueAfter",
        "raidBefore",
        "raidAfter",
        "transitionBefore",
        "transitionAfter"
      })
  void anotherGameplayScopeCannotUseAnOrdinaryOutgoingTarget(String field) throws Exception {
    reachFirst();
    if (field.endsWith("Before")) scope(field);
    else
      actor.movingSave =
          () -> {
            try {
              scope(field);
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          };
    move(20312);
    assertEquals(0, actor.getMissionManager().sub(FIRST).getProgress());
  }

  void scope(String kind) throws Exception {
    if (kind.startsWith("trial")) actor.getGameplay().setTrialRun(new TrialRun());
    else if (kind.startsWith("rogue")) actor.getGameplay().getRogue().setInside(true);
    else if (kind.startsWith("raid"))
      DailyMissionAcceptance46Test.set(actor.getGameplay(), "currentMissionRaid", 1);
    else actor.getGameplay().getStoryLines().begin(1, null, null);
  }

  @Test
  void sourceBookUsesActualPileLimitAndReportsOnlyTheDeliveredAmount() {
    reachFirst();
    var inventory = actor.getInventory();
    var book = inventory.getMaterialByItemId(190543);
    int limit = book.getExcel().getPileLimit();
    assertEquals(99999, limit);
    var extra = new emu.lunarcore.game.inventory.ItemParamMap();
    extra.add(190543, limit - 1);
    var result = inventory.grantItems(extra, false);
    assertTrue(result.complete());
    assertEquals(limit - 1, result.items().getFirst().getCount());
    assertSame(book, inventory.getMaterialByItemId(190543));
    assertEquals(limit, book.getCount());
    var overflow = new emu.lunarcore.game.inventory.ItemParamMap();
    overflow.add(190543, 1);
    var rejected = inventory.grantItems(overflow, false);
    assertFalse(rejected.complete());
    assertTrue(rejected.items().isEmpty());
    assertEquals(limit, book.getCount());
  }

  @Test
  void aGoalInstalledDuringSceneSaveWaitsForItsOwnNewOutgoingEvent() {
    reachFirst();
    actor.getGameplay().getSubMissions().remove(FIRST);
    actor.movingSave = () -> actor.getGameplay().getSubMissions().put(FIRST, new QuestState(FIRST));
    assertTrue(move(20312));
    assertEquals(0, actor.getMissionManager().sub(FIRST).getProgress());
    assertTrue(move(20311));
    assertEquals(0, actor.getMissionManager().sub(FIRST).getProgress());
    assertTrue(move(20312));
    assertEquals(1, actor.getMissionManager().sub(FIRST).getProgress());
  }

  @ParameterizedTest
  @ValueSource(strings = {"type", "method", "data", "return", "limit"})
  void unrecognizedBookConversionShapesCannotBeDeliveredAsOrdinaryBooks(String field)
      throws Exception {
    var item = GameData.getItemExcelMap().get(190543);
    switch (field) {
      case "type" ->
          DailyMissionAcceptance46Test.set(
              item, "ItemMainType", emu.lunarcore.game.enums.ItemMainType.Unknown);
      case "method" ->
          DailyMissionAcceptance46Test.set(
              item, "UseMethod", emu.lunarcore.game.enums.ItemUseMethod.Unknown);
      case "data" -> DailyMissionAcceptance46Test.set(item, "UseDataID", 1);
      case "return" -> DailyMissionAcceptance46Test.set(item, "ReturnItemIDList", null);
      case "limit" -> DailyMissionAcceptance46Test.set(item, "PileLimit", 0);
      default -> fail(field);
    }
    assertFalse(BookItems.storable(item));
    assertFalse(actor.getInventory().addItem(190543, 1));
    assertNull(actor.getInventory().getMaterialByItemId(190543));
  }

  @Test
  void twoPlayerAndBookMorphiaReloadsKeepDoingAndFinishedFloorViewsWithoutRegranting()
      throws Exception {
    reachFirst();
    int uid = 123456803;
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "source_floor_changes");
      store
          .getMapper()
          .map(Player.class, QuestState.class, emu.lunarcore.game.inventory.GameItem.class);
      for (int i = 0; i < 2; i++) {
        var base = new Player();
        DailyMissionAcceptance46Test.set(base, "uid", uid);
        DailyMissionAcceptance46Test.set(base, "gameplay", actor.getGameplay());
        DailyMissionAcceptance46Test.set(base, "planeId", actor.getPlaneId());
        DailyMissionAcceptance46Test.set(base, "floorId", actor.getFloorId());
        DailyMissionAcceptance46Test.set(base, "entryId", actor.getEntryId());
        store.save(base);
        var book = actor.getInventory().getMaterialByItemId(190543);
        DailyMissionAcceptance46Test.set(book, "ownerUid", uid);
        store.save(book);
        var restored =
            store
                .find(Player.class)
                .filter(dev.morphia.query.filters.Filters.eq("_id", uid))
                .first();
        var restoredBook =
            store
                .find(emu.lunarcore.game.inventory.GameItem.class)
                .filter(dev.morphia.query.filters.Filters.eq("ownerUid", uid))
                .first();
        assertNotNull(restored);
        assertNotNull(restoredBook);
        actor = new Actor();
        DailyMissionAcceptance46Test.set(actor, "uid", uid);
        DailyMissionAcceptance46Test.set(actor, "gameplay", restored.getGameplay());
        assertTrue(actor.getInventory().loadItem(restoredBook));
        assertTrue(move(restored.getPlaneId()));
        assertEquals(i, actor.getMissionManager().sub(FIRST).getProgress());
        assertEquals(1, actor.getInventory().getMaterialByItemId(190543).getCount());
        assertTrue(actor.getRewardLedger().granted("submission:434010402"));
        assertEquals(0, actor.getScoin());
        if (i == 0) assertTrue(move(20312));
        else {
          assertTrue(move(20311));
          assertEquals(1, actor.getMissionManager().sub(434010405).getProgress());
        }
        assertEquals(1, actor.getInventory().getMaterialByItemId(190543).getCount());
      }
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void frozenNativeMissionSceneAndBagFieldsExposeTheOriginalOutgoingAndTailResults()
      throws Exception {
    reachFirst();
    var snapshots = new ArrayList<com.google.protobuf.DynamicMessage>();
    var bridge = org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
    var session =
        new emu.lunarcore.server.game.GameSession(null) {
          @Override
          public Player getPlayer() {
            return actor;
          }

          @Override
          public org.hkrpg.server.protocol.ProtocolBridge getProtocol() {
            return bridge;
          }

          @Override
          public void sendClientPacket(String name, byte[] bytes) {
            assertEquals("GetMissionDataScRsp", name);
            try {
              snapshots.add(bridge.clientSchema().parse(name, bytes));
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          }
        };
    session.setState(emu.lunarcore.server.packet.SessionState.ACTIVE);
    var views = new DailyStageWin46Test();
    for (int step = 0; step < 5; step++) {
      if (step == 1) assertTrue(move(20312));
      if (step == 2) {
        assertTrue(move(20311));
        performance(1303200);
      }
      if (step == 3) assertTrue(move(20312));
      if (step == 4) {
        assertTrue(move(20311));
        performance(1303300);
      }
      assertTrue(
          org.hkrpg.server.protocol.VersionedPacketHandler.handle(
              session, bridge.clientCommand("GetMissionDataCsReq"), new byte[0]));
      var response = snapshots.getLast();
      assertTrue(response.getUnknownFields().asMap().isEmpty());
      assertEquals(0, views.field(response, "retcode"));
      views.assertGoalProgress(response, actor, FIRST, step == 0 ? 0 : 1);
      if (step >= 2)
        views.assertGoalProgress(response, actor, SECOND, step >= 3 ? 1 : 0);
      assertEquals(
          step == 4,
          ((List<?>) views.field(response, "finished_main_mission_id_list")).contains(MAIN));
      new MissionGroupConditions46Test().assertNativeNpcs(actor.getScene(), List.of());
      var packet = new emu.lunarcore.server.packet.send.PacketGetBagScRsp(session);
      var bag =
          bridge
              .clientSchema()
              .parse(
                  "GetBagScRsp",
                  bridge.outbound(packet.getCmdId(), packet.getData().toByteArray()).body());
      assertTrue(bag.getUnknownFields().asMap().isEmpty());
      var counts = new HashMap<Integer, Integer>();
      for (var raw : (List<?>) views.field(bag, "material_list")) {
        var item = (com.google.protobuf.DynamicMessage) raw;
        assertTrue(item.getUnknownFields().asMap().isEmpty());
        assertNull(
            counts.put((Integer) views.field(item, "tid"), (Integer) views.field(item, "num")));
      }
      assertEquals(1, counts.get(190543));
      assertEquals(step == 4 ? 3 : 0, counts.getOrDefault(212, 0));
      assertEquals(step == 4 ? 3000 : 0, actor.getScoin());
    }
    assertEquals(5, snapshots.size());
  }
}
