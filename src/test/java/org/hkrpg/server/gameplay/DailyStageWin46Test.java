package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Original four authored families; tutorials, location, clock and zero-party settlement are
 * fixtures.
 */
class DailyStageWin46Test {
  final DailyMissionAssignment46Test assigned = new DailyMissionAssignment46Test();
  final List<StageExcel> oldStages = new ArrayList<>();
  final Map<Integer, Integer> stages =
      Map.of(3011012, 2012120, 3011112, 2013110, 3011602, 201342210, 3011607, 201342220);
  JsonArray catalog;
  @TempDir Path temp;

  static class Actor extends DailyMissionAcceptance46Test.Actor {
    final LineupManager teams = new LineupManager(this);
    int floor = 20111001;

    @Override
    public int getPlaneId() {
      return floor / 1000;
    }

    @Override
    public int getFloorId() {
      return floor;
    }

    @Override
    public LineupManager getLineupManager() {
      return teams;
    }

    @Override
    public boolean removeFoodBuffs(int type) {
      return false;
    }
  }

  static class Service extends BattleService {
    Service() {
      super(null);
    }

    @Override
    protected void grantDrops(Battle battle) {}
  }

  Actor actor() {
    return (Actor) assigned.repeated.fixture.actor;
  }

  void clock(Instant now) {
    assigned.repeated.clock(now);
  }

  @BeforeEach
  void setup() throws Exception {
    assigned.temp = temp;
    assigned.setup();
    oldStages.addAll(GameData.getStageExcelMap().values());
    GameData.getStageExcelMap().values().clear();
    catalog = new JsonArray();
    var definitions = new HashMap<Integer, JsonObject>();
    var children = new HashMap<Integer, JsonArray>();
    try (var reader =
        new java.io.InputStreamReader(
            getClass().getResourceAsStream("/fixtures/daily-stage-win-4.6.json"),
            java.nio.charset.StandardCharsets.UTF_8)) {
      var rows = JsonParser.parseReader(reader).getAsJsonArray();
      assertEquals(126, rows.size());
      for (var raw : rows) {
        var entry = raw.getAsJsonObject();
        var row = entry.getAsJsonObject("row");
        switch (entry.get("kind").getAsString()) {
          case "main" ->
              GameData.getMainMissionExcelMap()
                  .add(assigned.repeated.fixture.gson.fromJson(row, MainMissionExcel.class));
          case "daily" -> catalog.add(row);
          case "definition" -> definitions.put(row.get("MainMissionID").getAsInt(), row);
          case "sub" ->
              children
                  .computeIfAbsent(row.get("MainMissionID").getAsInt(), ignored -> new JsonArray())
                  .add(row);
          case "stage" -> {
            var stage = emu.lunarcore.data.ResourceLoader.getGson().fromJson(row, StageExcel.class);
            stage.onLoad();
            GameData.getStageExcelMap().add(stage);
          }
          default -> fail("Unexpected source kind");
        }
      }
    }
    for (var row : definitions.entrySet()) {
      row.getValue().add("SubMissionList", children.get(row.getKey()));
      var graph = assigned.repeated.fixture.gson.fromJson(row.getValue(), MissionInfo.class);
      GameData.getMissionInfos().put(row.getKey(), graph);
      for (var sub : graph.getSubMissionList()) GameplayDefinitions.subs.put(sub.getId(), sub);
    }
    var actor = new Actor();
    actor.setNickname("Original daily stage fixture");
    assigned.repeated.fixture.actor = actor;
    clock(DailyMissionRepeat46Test.FIRST_DAY);
    for (int id : List.of(1010403, 2010501, 1011403, 1011501))
      actor.getGameplay().getMainMissions().put(id, DailyMissionAcceptance46Test.closed(id));
    actor.getDailyManager().ensureDay();
  }

  @AfterEach
  void cleanup() {
    assigned.cleanup();
    GameData.getStageExcelMap().values().clear();
    for (var stage : oldStages) GameData.getStageExcelMap().add(stage);
  }

  void single(int main) throws Exception {
    var rows = new JsonArray();
    for (var row : catalog) if (row.getAsJsonObject().get("ID").getAsInt() == main) rows.add(row);
    assertEquals(1, rows.size());
    assigned.repeated.fixture.dailyRows = rows;
    assigned.repeated.fixture.loadCatalog();
  }

  MissionInfo.SubMissionInfo stageGoal(int main) {
    return Arrays.stream(GameData.getMissionInfos().get(main).getSubMissionList())
        .filter(s -> s.getFinishType().equals("StageWin"))
        .findFirst()
        .orElseThrow();
  }

  void reachStage(int main) {
    assertEquals(Retcode.SUCC, actor().getMissionManager().accept(main));
    for (var sub : GameData.getMissionInfos().get(main).getSubMissionList()) {
      if (sub.getFinishType().equals("StageWin")) {
        actor().floor = sub.getLevelFloorID();
        return;
      }
      actor().floor = sub.getLevelFloorID();
      assertEquals(
          Retcode.SUCC, actor().getMissionManager().finishTalk(sub.getId(), sub.getParamStr1()));
    }
    fail("Original stage objective missing");
  }

  Battle battle(int main) {
    var battle =
        new Battle(
            actor(),
            actor().teams.getCurrentLineup(),
            GameData.getStageExcelMap().get(stages.get(main)));
    actor().setBattle(battle);
    return battle;
  }

  void win(Battle battle) {
    assertNotNull(
        new Service()
            .finishBattle(
                actor(),
                battle.getId(),
                battle.getStage().getId(),
                BattleEndStatus.BATTLE_END_WIN,
                BattleStatistics.newInstance()));
  }

  void complete(int main) {
    reachStage(main);
    win(battle(main));
    assertEquals(1, actor().getMissionManager().sub(stageGoal(main).getId()).getProgress());
    finishTail(main);
  }

  void finishTail(int main) {
    boolean after = false;
    for (var sub : GameData.getMissionInfos().get(main).getSubMissionList()) {
      if (sub.getFinishType().equals("StageWin")) {
        after = true;
        continue;
      }
      if (after) {
        actor().floor = sub.getLevelFloorID();
        assertEquals(
            Retcode.SUCC, actor().getMissionManager().finishTalk(sub.getId(), sub.getParamStr1()));
      }
    }
    assertEquals(
        QuestState.Phase.CLOSED, actor().getGameplay().getMainMissions().get(main).getPhase());
  }

  @ParameterizedTest
  @ValueSource(ints = {3011012, 3011112, 3011602, 3011607})
  void supportedOriginalStageFamilyDailyIsAutomaticallyAssigned(int main) throws Exception {
    single(main);
    actor().getMissionManager().refresh();
    assertNotNull(actor().getGameplay().getMainMissions().get(main));
    assertEquals(
        QuestState.Phase.DOING, actor().getGameplay().getMainMissions().get(main).getPhase());
    assertEquals(
        "Manual", GameData.getMainMissionExcelMap().get(main).getTakeParam().getFirst().Type());
  }

  @ParameterizedTest
  @ValueSource(ints = {3011012, 3011112, 3011602, 3011607})
  void actualKnownStageInsideTheAuthoredPrefixAdvancesOnlyAfterConfirmedWin(int main)
      throws Exception {
    single(main);
    reachStage(main);
    var sub = stageGoal(main);
    assertNotEquals(sub.getParamInt1(), stages.get(main));
    assertTrue(stages.get(main).toString().startsWith(Integer.toString(sub.getParamInt1())));
    var battle = battle(main);
    win(battle);
    assertEquals(1, actor().getMissionManager().sub(sub.getId()).getProgress());
    assertEquals(0, actor().getScoin());
    assertNull(
        new Service()
            .finishBattle(
                actor(),
                battle.getId(),
                battle.getStage().getId(),
                BattleEndStatus.BATTLE_END_WIN,
                BattleStatistics.newInstance()));
    assertEquals(1, actor().getMissionManager().sub(sub.getId()).getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {3011012, 3011112, 3011602, 3011607})
  void completedOriginalFamilyCanRepeatWithIndependentCreditAndActivityProof(int main)
      throws Exception {
    single(main);
    complete(main);
    assertEquals(5000, actor().getScoin());
    assertEquals(Retcode.SUCC, actor().getDailyManager().claimQuest(2100003).retcode());
    clock(DailyMissionRepeat46Test.NEXT_DAY);
    actor().getMissionManager().refresh();
    assertEquals(1, actor().getGameplay().getDailyMissionPeriods().getArchives().size());
    complete(main);
    assertEquals(10000, actor().getScoin());
    assertEquals(Retcode.SUCC, actor().getDailyManager().claimQuest(2100003).retcode());
    assertEquals(200, actor().getGameplay().getDailyPoints());
  }

  @ParameterizedTest
  @ValueSource(ints = {3011012, 3011112, 3011602, 3011607})
  void allKnownAuthoredFamilyVariantsAdvanceWithTheSameFrozenSourceObjective(int main)
      throws Exception {
    single(main);
    int prefix = stageGoal(main).getParamInt1();
    var family =
        GameData.getStageExcelMap().values().stream()
            .filter(stage -> Integer.toString(stage.getId()).startsWith(Integer.toString(prefix)))
            .sorted(java.util.Comparator.comparingInt(StageExcel::getId))
            .toList();
    assertEquals(main == 3011012 ? 49 : main == 3011112 ? 35 : 7, family.size());
    for (var stage : family) {
      var value = new Actor();
      value.setNickname("Source family variant fixture");
      assigned.repeated.fixture.actor = value;
      clock(DailyMissionRepeat46Test.FIRST_DAY);
      for (int id : List.of(1010403, 2010501, 1011403, 1011501))
        value.getGameplay().getMainMissions().put(id, DailyMissionAcceptance46Test.closed(id));
      value.getDailyManager().ensureDay();
      reachStage(main);
      var battle = new Battle(value, value.getCurrentLineup(), stage);
      value.setBattle(battle);
      win(battle);
      assertEquals(
          1,
          value.getMissionManager().sub(stageGoal(main).getId()).getProgress(),
          "Original stage " + stage.getId());
      assertEquals(0, value.getScoin());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"prefix", "goal", "plane", "floor", "actualStage"})
  void aDefinitionChangedAfterBattleStartCannotRewriteTheCapturedObjective(String field)
      throws Exception {
    single(3011602);
    reachStage(3011602);
    var goal = stageGoal(3011602);
    var battle = battle(3011602);
    switch (field) {
      case "prefix" -> DailyMissionAcceptance46Test.set(goal, "paramInt1", 2013422);
      case "goal" -> DailyMissionAcceptance46Test.set(goal, "progress", 2);
      case "plane" ->
          DailyMissionAcceptance46Test.set(
              goal, "levelPlaneID", goal.getLevelPlaneID() == 0 ? actor().getPlaneId() : 0);
      case "floor" -> DailyMissionAcceptance46Test.set(goal, "levelFloorID", 0);
      case "actualStage" ->
          DailyMissionAcceptance46Test.set(battle.getStage(), "StageID", 201342211);
      default -> fail(field);
    }
    win(battle);
    assertEquals(0, actor().getMissionManager().sub(goal.getId()).getProgress());
    assertEquals(0, actor().getScoin());
  }

  @ParameterizedTest
  @ValueSource(ints = {3011012, 3011112, 3011602, 3011607})
  void unfinishedKnownFamilyGraphGetsAnIndependentNewViewAfterReset(int main) throws Exception {
    single(main);
    reachStage(main);
    var old = actor().getGameplay().getMainMissions().get(main);
    var oldStage = actor().getMissionManager().sub(stageGoal(main).getId());
    clock(DailyMissionRepeat46Test.NEXT_DAY);
    actor().getMissionManager().refresh();
    assertNotSame(old, actor().getGameplay().getMainMissions().get(main));
    var archive =
        actor()
            .getGameplay()
            .getDailyMissionPeriods()
            .getExpired()
            .get(main + ":" + DailyManager.day(DailyMissionRepeat46Test.FIRST_DAY));
    assertNotNull(archive);
    assertSame(old, archive.getMain());
    assertSame(oldStage, archive.getSubs().get(stageGoal(main).getId()));
    assertEquals(0, oldStage.getProgress());
    assertEquals(0, actor().getScoin());
    int first = GameData.getMissionInfos().get(main).getStartSubMissionList()[0];
    assertEquals(0, actor().getMissionManager().sub(first).getProgress());
  }

  @Test
  void aBattleFromTheExpiredRoundCannotCompleteTheNewlyAssignedStage() throws Exception {
    single(3011602);
    reachStage(3011602);
    var old = actor().getMissionManager().sub(stageGoal(3011602).getId());
    var battle = battle(3011602);
    clock(DailyMissionRepeat46Test.NEXT_DAY);
    actor().getMissionManager().refresh();
    assertNotSame(old, actor().getMissionManager().sub(stageGoal(3011602).getId()));
    win(battle);
    assertEquals(0, old.getProgress());
    assertEquals(0, actor().getMissionManager().sub(stageGoal(3011602).getId()).getProgress());
    assertEquals(0, actor().getScoin());
    win(battle(3011602));
    assertEquals(1, actor().getMissionManager().sub(stageGoal(3011602).getId()).getProgress());
  }

  @Test
  void aStageUnlockedDuringAnOlderBattleWaitsForItsOwnBattleStart() throws Exception {
    single(3011112);
    actor().floor = 20131001;
    assertEquals(Retcode.SUCC, actor().getMissionManager().accept(3011112));
    var battle = battle(3011112);
    assertEquals(
        Retcode.SUCC, actor().getMissionManager().finishTalk(301111201, "Mission_301111201"));
    win(battle);
    assertEquals(0, actor().getMissionManager().sub(301111202).getProgress());
    win(battle(3011112));
    assertEquals(1, actor().getMissionManager().sub(301111202).getProgress());
  }

  @Test
  void aBattleWhosePlayerMovedToAnotherFloorCannotFinishTheCapturedObjective() throws Exception {
    single(3011602);
    reachStage(3011602);
    var battle = battle(3011602);
    actor().floor++;
    win(battle);
    assertEquals(0, actor().getMissionManager().sub(301160201).getProgress());
  }

  @ParameterizedTest
  @ValueSource(strings = {"lose", "quit"})
  void lossAndQuitNeverCompleteTheAuthoredFamily(String outcome) throws Exception {
    single(3011602);
    reachStage(3011602);
    var battle = battle(3011602);
    assertNotNull(
        new Service()
            .finishBattle(
                actor(),
                battle.getId(),
                battle.getStage().getId(),
                outcome.equals("lose")
                    ? BattleEndStatus.BATTLE_END_LOSE
                    : BattleEndStatus.BATTLE_END_QUIT,
                BattleStatistics.newInstance()));
    assertEquals(0, actor().getMissionManager().sub(301160201).getProgress());
    assertEquals(0, actor().getScoin());
  }

  @Test
  void unrelatedKnownStageAndInvalidBattleReportsKeepTheDailyPending() throws Exception {
    single(3011602);
    reachStage(3011602);
    var unrelated =
        new Battle(actor(), actor().getCurrentLineup(), GameData.getStageExcelMap().get(201342220));
    actor().setBattle(unrelated);
    win(unrelated);
    assertEquals(0, actor().getMissionManager().sub(301160201).getProgress());
    var right = battle(3011602);
    var service = new Service();
    assertNull(
        service.finishBattle(
            actor(),
            right.getId() + 1,
            right.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance()));
    assertNull(
        service.finishBattle(
            actor(),
            right.getId(),
            right.getStage().getId() + 1,
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance()));
    var stats = BattleStatistics.newInstance();
    stats.setRoundCnt(-1);
    assertNull(
        service.finishBattle(
            actor(),
            right.getId(),
            right.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            stats));
    assertEquals(0, actor().getMissionManager().sub(301160201).getProgress());
    win(right);
    assertEquals(1, actor().getMissionManager().sub(301160201).getProgress());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "trialAtStart",
        "trialAfterStart",
        "rogueAtStart",
        "rogueAfterStart",
        "instanceAtStart",
        "instanceAfterStart",
        "raidAtStart",
        "raidAfterStart",
        "storyAtStart",
        "storyAfterStart",
        "transitionAtStart",
        "transitionAfterStart"
      })
  void anotherGameplayScopeCannotConsumeAnOrdinaryDailyStage(String scope) throws Exception {
    single(3011602);
    reachStage(3011602);
    var lineup = actor().getCurrentLineup();
    if (scope.endsWith("AtStart")) otherScope(scope);
    var battle = new Battle(actor(), lineup, GameData.getStageExcelMap().get(stages.get(3011602)));
    actor().setBattle(battle);
    if (scope.endsWith("AfterStart")) otherScope(scope);
    win(battle);
    assertEquals(0, actor().getMissionManager().sub(301160201).getProgress());
    assertEquals(0, actor().getScoin());
  }

  void otherScope(String scope) throws Exception {
    if (scope.startsWith("trial")) actor().getGameplay().setTrialRun(new TrialRun());
    else if (scope.startsWith("rogue")) actor().getGameplay().getRogue().setInside(true);
    else if (scope.startsWith("raid"))
      DailyMissionAcceptance46Test.set(actor().getGameplay(), "currentMissionRaid", 1);
    else if (scope.startsWith("story")) {
      actor().getGameplay().getStoryLines().begin(1, null, null);
      actor().getGameplay().getStoryLines().arrived();
    } else if (scope.startsWith("transition"))
      actor().getGameplay().getStoryLines().begin(0, null, null);
    else if (scope.startsWith("instance"))
      actor()
          .setInstance(
              new emu.lunarcore.game.player.PlayerGameInstance() {
                @Override
                public void onBattleStart(Battle battle) {}

                @Override
                public void onBattleFinish(
                    Battle battle, BattleEndStatus result, BattleStatistics stats) {}

                @Override
                public void onLeave() {}
              });
    else fail(scope);
  }

  @ParameterizedTest
  @ValueSource(ints = {3011012, 3011112, 3011602, 3011607})
  void actualPlayerReloadsPreserveDoingAndWonStagesBeforeTheOriginalReward(int main)
      throws Exception {
    single(main);
    reachStage(main);
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "daily_stage");
      store.getMapper().map(emu.lunarcore.game.player.Player.class, QuestState.class);
      for (int i = 0; i < 2; i++) {
        var base = new emu.lunarcore.game.player.Player();
        DailyMissionAcceptance46Test.set(base, "uid", 123456801);
        DailyMissionAcceptance46Test.set(base, "gameplay", actor().getGameplay());
        store.save(base);
        var restored =
            store
                .find(emu.lunarcore.game.player.Player.class)
                .filter(dev.morphia.query.filters.Filters.eq("_id", 123456801))
                .first();
        assertNotNull(restored);
        var value = new Actor();
        assertTrue(value.setNickname("Restored stage fixture"));
        value.floor = stageGoal(main).getLevelFloorID();
        DailyMissionAcceptance46Test.set(value, "uid", 123456801);
        DailyMissionAcceptance46Test.set(value, "gameplay", restored.getGameplay());
        assigned.repeated.fixture.actor = value;
        clock(DailyMissionRepeat46Test.FIRST_DAY);
        assertEquals(i, actor().getMissionManager().sub(stageGoal(main).getId()).getProgress());
        assertEquals(0, actor().getScoin());
        win(battle(main));
        assertEquals(1, actor().getMissionManager().sub(stageGoal(main).getId()).getProgress());
        assertEquals(0, actor().getScoin());
      }
      finishTail(main);
      assertEquals(5000, actor().getScoin());
      assertEquals(Retcode.SUCC, actor().getDailyManager().claimQuest(2100003).retcode());
      assertEquals(200, actor().getGameplay().getDailyPoints());
    } finally {
      server.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {3011012, 3011112, 3011602, 3011607})
  void frozenNativeMissionFieldsExposeActualStageVictoryWithoutCompletingTheMain(int main)
      throws Exception {
    single(main);
    reachStage(main);
    var responses = new ArrayList<com.google.protobuf.DynamicMessage>();
    var session =
        new emu.lunarcore.server.game.GameSession(null) {
          @Override
          public emu.lunarcore.game.player.Player getPlayer() {
            return actor();
          }

          @Override
          public org.hkrpg.server.protocol.ProtocolBridge getProtocol() {
            return org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
          }

          @Override
          public void sendClientPacket(String name, byte[] bytes) {
            assertEquals("GetMissionDataScRsp", name);
            try {
              responses.add(getProtocol().clientSchema().parse(name, bytes));
            } catch (Exception error) {
              throw new AssertionError(error);
            }
          }
        };
    session.setState(emu.lunarcore.server.packet.SessionState.ACTIVE);
    for (int i = 0; i < 2; i++) {
      if (i == 1) win(battle(main));
      assertTrue(
          org.hkrpg.server.protocol.VersionedPacketHandler.handle(
              session, session.getProtocol().clientCommand("GetMissionDataCsReq"), new byte[0]));
      var snapshot = responses.getLast();
      assertTrue(snapshot.getUnknownFields().asMap().isEmpty());
      assertEquals(0, field(snapshot, "retcode"));
      assertGoalProgress(snapshot, actor(), stageGoal(main).getId(), i);
      var parent = message(snapshot, "main_mission_list", main);
      assertEquals(
          1,
          ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(parent, "status"))
              .getNumber());
      assertFalse(((List<?>) field(snapshot, "finished_main_mission_id_list")).contains(main));
      assertEquals(0, actor().getScoin());
    }
    assertEquals(2, responses.size());
  }

  Object field(com.google.protobuf.DynamicMessage message, String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }

  void assertGoalProgress(
      com.google.protobuf.DynamicMessage snapshot,
      emu.lunarcore.game.player.Player owner,
      int id,
      int progress) {
    var state = owner.getMissionManager().sub(id);
    assertNotNull(state);
    assertEquals(progress, state.getProgress());
    assertEquals(progress == 0, state.getPhase() == QuestState.Phase.DOING);
    assertEquals(progress == 1, ((List<?>) field(snapshot, "OLKALABEDOA")).contains(id));
    if (progress == 0) {
      var goal = message(snapshot, "mission_list", id);
      assertEquals(0, field(goal, "progress"));
      assertEquals(1,
          ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(goal, "status")).getNumber());
    } else {
      assertTrue(((List<?>) field(snapshot, "mission_list")).stream().noneMatch(
          row -> field((com.google.protobuf.DynamicMessage) row, "id").equals(id)),
          "Completed goals load as silent history, without repeating completion callbacks");
    }
  }

  com.google.protobuf.DynamicMessage message(
      com.google.protobuf.DynamicMessage parent, String list, int id) {
    var matches =
        ((List<?>) field(parent, list))
            .stream()
                .map(com.google.protobuf.DynamicMessage.class::cast)
                .filter(row -> field(row, "id").equals(id))
                .toList();
    assertEquals(1, matches.size());
    assertTrue(matches.getFirst().getUnknownFields().asMap().isEmpty());
    return matches.getFirst();
  }
}
