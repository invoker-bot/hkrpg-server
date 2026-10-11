package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.hkrpg.server.protocol.VersionedPacketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original second-season source rows; unlocks and schedule are declared entry fixtures. */
class BoxingClubResonance46Test {
  @TempDir Path root;
  final BoxingClubRounds46Test fixture = new BoxingClubRounds46Test();

  @BeforeEach
  void load() throws Exception {
    fixture.root = root;
    fixture.load();
    var gson = ResourceLoader.getGson();
    for (var raw : fixture.fixture("boxing-club-resonance-4.6.json").getAsJsonArray("inputs")) {
      var input = raw.getAsJsonObject();
      var file = root.resolve(input.get("path").getAsString());
      if (file.getFileName().toString().startsWith("BoxingClub")
          && !file.getFileName().toString().equals("BoxingClubConstValueClient.json")) continue;
      var merged =
          Files.isRegularFile(file)
              ? JsonParser.parseString(Files.readString(file)).getAsJsonArray()
              : new JsonArray();
      for (var row : input.getAsJsonArray("rows")) if (!merged.contains(row)) merged.add(row);
      Files.writeString(file, merged.toString());
      for (var row : input.getAsJsonArray("rows"))
        switch (file.getFileName().toString()) {
          case "PlaneEvent.json" ->
              GameData.getPlaneEventExcelMap().add(gson.fromJson(row, PlaneEventExcel.class));
          case "StageConfig.json" -> {
            var stage = gson.fromJson(row, StageExcel.class);
            stage.onLoad();
            GameData.getStageExcelMap().add(stage);
          }
          case "MazeBuff.json" ->
              GameData.getMazeBuffExcelMap().add(gson.fromJson(row, MazeBuffExcel.class));
          case "RewardData.json" -> {
            var reward = gson.fromJson(row, RewardExcel.class);
            reward.onLoad();
            GameData.getRewardExcelMap().add(reward);
          }
          case "ActivityPanel.json" ->
              GameData.getActivityPanelExcelMap().add(gson.fromJson(row, ActivityPanelExcel.class));
        }
    }
    for (int id : new int[] {2010720, 1011503, 8015202}) {
      var prerequisite = new QuestState(id);
      prerequisite.advance(1, 1, 1);
      fixture.session.actor.getGameplay().getMainMissions().put(id, prerequisite);
    }
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(40003, 4000301, 1, Long.MAX_VALUE));
  }

  @AfterEach
  void restore() throws Exception {
    fixture.restore();
  }

  void seedRoster(int id, int avatar, String typeName) throws Exception {
    var schema = fixture.session.getProtocol().clientSchema();
    var type = schema.message("SetBoxingClubResonanceCsReq");
    var request =
        DynamicMessage.newBuilder(type).setField(type.findFieldByName("challenge_id"), id);
    var memberType = type.findFieldByName("OOJOMKDMBBM").getMessageType();
    var kind = memberType.findFieldByName("avatar_type");
    request.addRepeatedField(
        type.findFieldByName("OOJOMKDMBBM"),
        DynamicMessage.newBuilder(memberType)
            .setField(memberType.findFieldByName("avatar_id"), avatar)
            .setField(kind, kind.getEnumType().findValueByName(typeName))
            .build());
    assertTrue(VersionedPacketHandler.handle(fixture.session, 4261, request.build().toByteArray()));
    assertEquals("SetBoxingClubResonanceScRsp", fixture.session.responseName);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
  void originalResonanceRosterStartsUnselectedWithoutPrewritingAWinner(int world) throws Exception {
    fixture.session.actor.world = world;
    seedRoster(6, 1001, "AvatarType_AvatarFormalType");
    assertEquals(
        0,
        fixture.number(fixture.session.response, "retcode"),
        "Original second-season roster remains unavailable");
    assertEquals(6, fixture.number(fixture.row(), "challenge_id"));
    assertEquals(0, fixture.number(fixture.row(), "IFHHFBIJOFC"));
    assertEquals(0, fixture.number(fixture.row(), "HODLKBMKMCF"));
    assertEquals(0, fixture.number(fixture.row(), "stage_index"));
    assertNull(fixture.session.actor.getBattle());
    assertEquals(1, fixture.session.actor.saves);
    assertEquals(0, fixture.session.actor.grants);
    assertEquals(0, fixture.formal.saves);
  }

  int code() {
    return fixture.number(fixture.session.response, "retcode");
  }

  int progress(String key) {
    return fixture.number(fixture.row(), key);
  }

  void choose(int id, int buff, boolean resonance) throws Exception {
    String name =
        resonance ? "ChooseBoxingClubResonanceCsReq" : "ChooseBoxingClubStageOptionalBuffCsReq";
    var type = fixture.session.getProtocol().clientSchema().message(name);
    var b =
        DynamicMessage.newBuilder(type)
            .setField(type.findFieldByName("challenge_id"), id)
            .setField(type.findFieldByName(resonance ? "HODLKBMKMCF" : "MFKAOHKPIPE"), buff);
    assertTrue(
        VersionedPacketHandler.handle(
            fixture.session, resonance ? 4242 : 4268, b.build().toByteArray()));
    assertEquals(name.replace("CsReq", "ScRsp"), fixture.session.responseName);
  }

  void startRequest(int id) throws Exception {
    assertTrue(
        VersionedPacketHandler.handle(
            fixture.session, 4260, fixture.scalar("StartBoxingClubBattleCsReq", id)));
  }

  BoxingClubResonanceBattle start(int id) throws Exception {
    startRequest(id);
    assertEquals(0, code());
    return assertInstanceOf(BoxingClubResonanceBattle.class, fixture.session.actor.getBattle());
  }

  Battle finish(BoxingClubResonanceBattle b, BattleEndStatus result, int cycles) {
    return new BattleService(null)
        .finishBattle(
            fixture.session.actor,
            b.getId(),
            b.getStage().getId(),
            result,
            BattleStatistics.newInstance().setRoundCnt(cycles));
  }

  void seed(int id) throws Exception {
    seedRoster(id, 1001, "AvatarType_AvatarFormalType");
    assertEquals(0, code());
  }

  BoxingClubResonanceCatalog.Challenge challenge(int id) {
    return Objects.requireNonNull(BoxingClubResonanceCatalog.load(root)).challenges().get(id);
  }

  int resonance(int id) {
    return challenge(id).resonanceGroups().keySet().iterator().next();
  }

  List<Integer> options(int id, int index) {
    var catalog = BoxingClubResonanceCatalog.load(root);
    int group = challenge(id).resonanceGroups().get(resonance(id));
    return catalog.stage(catalog.group(group).events().get(index)).options();
  }

  void prepare(int id) throws Exception {
    seed(id);
    choose(id, resonance(id), true);
    assertEquals(0, code());
  }

  void threeWins(int id, int cycles, int branch) throws Exception {
    for (int i = 0; i < 3; i++) {
      if (i > 0) {
        choose(id, options(id, i).get(branch), false);
        assertEquals(0, code());
      }
      var b = start(id);
      assertSame(b, finish(b, BattleEndStatus.BATTLE_END_WIN, cycles));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
  void originalThreeRoundsAtEachWorldCarryOnlyTheChosenResonanceAndOptions(int world)
      throws Exception {
    fixture.session.actor.world = world;
    prepare(6);
    var catalog = BoxingClubResonanceCatalog.load(root);
    var group = catalog.group(58);
    for (int i = 0; i < 3; i++) {
      if (i > 0) {
        startRequest(6);
        assertNotEquals(0, code());
        assertNull(fixture.session.actor.getBattle());
        choose(6, options(6, i).get(i - 1), false);
        assertEquals(0, code());
      }
      var b = start(6);
      var info = b.toProto();
      assertEquals(
          SceneEventBattles.resolve(group.events().get(i), world).getId(), info.getStageId());
      assertEquals(world, info.getWorldLevel());
      assertEquals(20 - i * 2, info.getRoundsLimit());
      assertEquals(10000, info.getBattleAvatarList().get(0).getHp());
      assertEquals(5000, info.getBattleAvatarList().get(0).getSpBar().getCurSp());
      var expected =
          fixture.session.actor.getGameplay().getBoxingClubResonance().getActive().buffs();
      assertEquals(i + 1, expected.size());
      assertEquals(i + 1, info.getBuffList().length());
      for (int n = 0; n < expected.size(); n++)
        assertEquals(expected.get(n), info.getBuffList().get(n).getId());
      assertSame(b, finish(b, BattleEndStatus.BATTLE_END_WIN, 2));
      assertNull(finish(b, BattleEndStatus.BATTLE_END_WIN, 2));
    }
    assertNull(fixture.session.actor.getInstance());
    var state = fixture.session.actor.getGameplay().getBoxingClubResonance();
    assertNull(state.getActive());
    assertEquals(6, state.getCompleted().get(6).rounds());
    assertTrue(new RewardLedger(fixture.session.actor).granted("boxing:second:6"));
    assertTrue(fixture.session.actor.amounts.isEmpty());
    assertEquals("BoxingClubRewardScNotify", fixture.session.responseName);
    assertEquals(1200, fixture.formal.getCurrentHp(fixture.session.actor.ordinary));
    assertEquals(3000, fixture.formal.getCurrentSp(fixture.session.actor.ordinary));
    assertEquals(0, fixture.formal.saves);
  }

  @Test
  void allSevenOriginalChallengesNeedTheirOwnThreeResultsAndRespectPreviousCompletion()
      throws Exception {
    for (int id = 6; id <= 12; id++) {
      GameplayDefinitions.windows.clear();
      GameplayDefinitions.windows.add(
          new GameplayDefinitions.Window(40003, challenge(id).module(), 1, Long.MAX_VALUE));
      if (id < 12) {
        GameplayDefinitions.windows.add(
            new GameplayDefinitions.Window(40003, challenge(id + 1).module(), 1, Long.MAX_VALUE));
        seedRoster(id + 1, 1001, "AvatarType_AvatarFormalType");
        assertNotEquals(0, code());
      }
      prepare(id);
      threeWins(id, 1, 0);
      assertEquals(
          3,
          fixture
              .session
              .actor
              .getGameplay()
              .getBoxingClubResonance()
              .getCompleted()
              .get(id)
              .rounds());
      assertTrue(new RewardLedger(fixture.session.actor).granted("boxing:second:" + id));
    }
    assertEquals(
        7, fixture.session.actor.getGameplay().getBoxingClubResonance().getCompleted().size());
    assertTrue(fixture.session.actor.amounts.isEmpty());
    assertTrue(fixture.session.actor.getGameplay().getBoxingClub().getCompleted().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void allFourOptionBranchesAreBoundToTheirOwnTwoStageLists(int branch) throws Exception {
    prepare(6);
    for (int i = 0; i < 3; i++) {
      if (i > 0) {
        choose(6, options(6, i).get((branch >> (i - 1)) & 1), false);
        assertEquals(0, code());
      }
      var b = start(6);
      assertSame(b, finish(b, BattleEndStatus.BATTLE_END_WIN, 1));
    }
    var done = fixture.session.actor.getGameplay().getBoxingClubResonance().getCompleted().get(6);
    assertEquals(
        List.of(options(6, 1).get(branch & 1), options(6, 2).get((branch >> 1) & 1)),
        done.options());
  }

  @Test
  void duplicateChoicesAreIdempotentAndAlternateOrCrossRoundChoicesCannotAppend() throws Exception {
    seed(6);
    int saves = fixture.session.actor.saves;
    choose(6, 3101093, false);
    assertNotEquals(0, code());
    assertEquals(saves, fixture.session.actor.saves);
    startRequest(6);
    assertNotEquals(0, code());
    choose(6, 3101051, true);
    assertNotEquals(0, code());
    assertEquals(saves, fixture.session.actor.saves);
    choose(6, resonance(6), true);
    assertEquals(0, code());
    saves = fixture.session.actor.saves;
    choose(6, resonance(6), true);
    assertEquals(0, code());
    assertEquals(saves, fixture.session.actor.saves);
    choose(6, options(6, 1).get(0), false);
    assertNotEquals(0, code());
    var b = start(6);
    choose(6, resonance(6), true);
    assertNotEquals(0, code());
    assertSame(b, fixture.session.actor.getBattle());
    assertSame(b, finish(b, BattleEndStatus.BATTLE_END_WIN, 1));
    choose(6, options(6, 2).get(0), false);
    assertNotEquals(0, code());
    choose(6, options(6, 1).get(0), false);
    assertEquals(0, code());
    saves = fixture.session.actor.saves;
    choose(6, options(6, 1).get(0), false);
    assertEquals(0, code());
    assertEquals(saves, fixture.session.actor.saves);
    choose(6, options(6, 1).get(1), false);
    assertNotEquals(0, code());
    assertEquals(saves, fixture.session.actor.saves);
    choose(7, options(6, 1).get(0), false);
    assertNotEquals(0, code());
    assertEquals(
        List.of(options(6, 1).get(0)),
        fixture.session.actor.getGameplay().getBoxingClubResonance().getActive().options());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void uncommittedRoundResultsRetryWithoutPregrantOrReplay(int wins) throws Exception {
    prepare(6);
    for (int i = 0; i < wins; i++) {
      if (i > 0) {
        choose(6, options(6, i).get(0), false);
        assertEquals(0, code());
      }
      var b = start(6);
      assertSame(b, finish(b, BattleEndStatus.BATTLE_END_WIN, 1));
    }
    if (wins > 0) {
      choose(6, options(6, wins).get(0), false);
      assertEquals(0, code());
    }
    var b = start(6);
    var before = fixture.session.actor.getGameplay().getBoxingClubResonance();
    fixture.session.actor.failSave = true;
    assertThrows(IllegalStateException.class, () -> finish(b, BattleEndStatus.BATTLE_END_WIN, 1));
    assertSame(before, fixture.session.actor.getGameplay().getBoxingClubResonance());
    assertSame(b, fixture.session.actor.getBattle());
    assertEquals(wins, before.getActive().index());
    assertEquals(0, fixture.session.actor.grants);
    fixture.session.actor.failSave = false;
    assertSame(b, finish(b, BattleEndStatus.BATTLE_END_WIN, 1));
    assertNull(finish(b, BattleEndStatus.BATTLE_END_WIN, 1));
  }

  @ParameterizedTest
  @ValueSource(ints = {2010720, 1011503, 8015202, 1011001})
  void eachOriginalMainMissionGateIsRequiredBeforeSaving(int mission) throws Exception {
    fixture.session.actor.getGameplay().getMainMissions().remove(mission);
    seedRoster(6, 1001, "AvatarType_AvatarFormalType");
    assertNotEquals(0, code());
    assertEquals(0, fixture.session.actor.saves);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "PlaneEvent",
        "StageConfig",
        "MazeBuff",
        "RewardData",
        "ActivityPanel",
        "ActivityConfig",
        "BoxingClubConstValueClient"
      })
  void missingOrChangedOriginalDefinitionsCannotSeedAPlayedRun(String name) throws Exception {
    var file = root.resolve("ExcelOutput/" + name + ".json");
    var bytes = Files.readAllBytes(file);
    Files.delete(file);
    seedRoster(6, 1001, "AvatarType_AvatarFormalType");
    assertNotEquals(0, code());
    assertEquals(0, fixture.session.actor.saves);
    Files.write(file, bytes);
    var rows = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
    for (var row : rows) row.getAsJsonObject().addProperty("UnexpectedOperatorChange", true);
    Files.writeString(file, rows.toString());
    seedRoster(6, 1001, "AvatarType_AvatarFormalType");
    assertNotEquals(0, code());
    assertEquals(0, fixture.session.actor.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "expired", "future", "duplicate", "wrongModule", "lowLevel"})
  void aUniqueCurrentOperatorWindowAndOriginalPanelGateAreRequired(String kind) throws Exception {
    GameplayDefinitions.windows.removeIf(w -> w.panelId() == 40003);
    long now = Instant.now().getEpochSecond();
    switch (kind) {
      case "expired" ->
          GameplayDefinitions.windows.add(new GameplayDefinitions.Window(40003, 4000301, 1, now));
      case "future" ->
          GameplayDefinitions.windows.add(
              new GameplayDefinitions.Window(40003, 4000301, now + 3600, now + 7200));
      case "duplicate" -> {
        var w = new GameplayDefinitions.Window(40003, 4000301, 1, Long.MAX_VALUE);
        GameplayDefinitions.windows.add(w);
        GameplayDefinitions.windows.add(w);
      }
      case "wrongModule" ->
          GameplayDefinitions.windows.add(
              new GameplayDefinitions.Window(40003, 4000302, 1, Long.MAX_VALUE));
      case "lowLevel" -> {
        GameplayDefinitions.windows.add(
            new GameplayDefinitions.Window(40003, 4000301, 1, Long.MAX_VALUE));
        fixture.session.actor.setLevel(20);
      }
    }
    seedRoster(6, 1001, "AvatarType_AvatarFormalType");
    assertNotEquals(0, code());
    assertEquals(0, fixture.session.actor.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"window", "source", "loadedStage"})
  void changedConditionsRejectAWinnerButAllowTheBoundLossToReleaseTheRun(String kind)
      throws Exception {
    prepare(6);
    var b = start(6);
    switch (kind) {
      case "window" -> GameplayDefinitions.windows.clear();
      case "source" -> Files.writeString(root.resolve("ExcelOutput/StageConfig.json"), "[]");
      case "loadedStage" -> GameData.getStageExcelMap().values().clear();
    }
    assertNull(finish(b, BattleEndStatus.BATTLE_END_WIN, 1));
    assertSame(b, fixture.session.actor.getBattle());
    assertSame(b, finish(b, BattleEndStatus.BATTLE_END_LOSE, 0));
    assertNull(fixture.session.actor.getBattle());
    assertNull(fixture.session.actor.getInstance());
    assertNull(fixture.session.actor.getGameplay().getBoxingClubResonance().getActive());
    assertEquals(0, fixture.session.actor.grants);
  }

  @ParameterizedTest
  @ValueSource(strings = {"seed", "resonance", "pending", "selected"})
  void savedChoicePhasesRoundTripAndRestoreOnlyAnInstance(String phase) throws Exception {
    seed(6);
    if (!phase.equals("seed")) {
      choose(6, resonance(6), true);
      assertEquals(0, code());
    }
    if (phase.equals("pending") || phase.equals("selected")) {
      var b = start(6);
      assertSame(b, finish(b, BattleEndStatus.BATTLE_END_WIN, 1));
    }
    if (phase.equals("selected")) {
      choose(6, options(6, 1).get(0), false);
      assertEquals(0, code());
    }
    var gson = new Gson();
    var json = gson.toJson(fixture.session.actor.getGameplay().getBoxingClubResonance());
    var restored = gson.fromJson(json, BoxingClubResonanceState.class);
    assertTrue(restored.valid(BoxingClubResonanceRules.load(), fixture.session.actor.getUid()));
    // Dispose the old process instance before installing its independently restored document.
    fixture.session.actor.setInstance(null);
    fixture.session.actor.getGameplay().boxingClubResonance(restored);
    BoxingClubManager.restore(fixture.session.actor);
    assertNotNull(fixture.session.actor.getInstance());
    assertNull(fixture.session.actor.getBattle());
    assertEquals(json, gson.toJson(restored));
    startRequest(6);
    assertEquals(phase.equals("resonance") || phase.equals("selected") ? 0 : 5, code());
  }

  @Test
  void choicesAndFinalBestRecordCannotIssueOriginalEmptyRewardsTwice() throws Exception {
    prepare(6);
    threeWins(6, 2, 0);
    assertEquals(
        6,
        fixture
            .session
            .actor
            .getGameplay()
            .getBoxingClubResonance()
            .getCompleted()
            .get(6)
            .rounds());
    int grants = fixture.session.actor.grants;
    prepare(6);
    threeWins(6, 1, 1);
    assertEquals(
        3,
        fixture
            .session
            .actor
            .getGameplay()
            .getBoxingClubResonance()
            .getCompleted()
            .get(6)
            .rounds());
    assertEquals(grants, fixture.session.actor.grants);
    assertTrue(fixture.session.actor.amounts.isEmpty());
    startRequest(6);
    assertNotEquals(0, code());
    choose(6, resonance(6), true);
    assertNotEquals(0, code());
  }

  @Test
  void firstSeasonAndGenericBattlesCannotStartInsideAnUnselectedResonanceRun() throws Exception {
    seed(6);
    fixture.match(1, 1001);
    assertNotEquals(0, code());
    assertNull(new BattleService(null).startEventBattle(fixture.session.actor, 304004));
    assertNull(new BattleService(null).startEventBattle(fixture.session.actor, 412311));
    assertEquals(1, fixture.session.actor.saves);
    assertNull(fixture.session.actor.getBattle());
    assertNull(fixture.session.actor.getGameplay().getBoxingClub().getActive());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 5, 13})
  void aFirstSeasonOrUnknownChallengeCannotUseTheResonanceRosterRequest(int id) throws Exception {
    seedRoster(id, 1001, "AvatarType_AvatarFormalType");
    assertNotEquals(0, code());
    assertEquals(0, fixture.session.actor.saves);
  }

  @ParameterizedTest
  @ValueSource(ints = {9999, 3071302, 3051204})
  void anUnownedOrTrialIDCannotBorrowFormalAdmission(int id) throws Exception {
    seedRoster(6, id, "AvatarType_AvatarFormalType");
    assertNotEquals(0, code());
    assertEquals(0, fixture.session.actor.saves);
  }

  @Test
  void all147SourceEventWorldPairsResolveToTheActualThreeRoundDefinitions() {
    var rules = BoxingClubResonanceRules.load();
    assertNotNull(rules);
    int count = 0;
    for (var c : rules.catalog.challenges().values())
      for (int group : c.resonanceGroups().values())
        for (int event : rules.catalog.group(group).events())
          for (int world = 0; world <= 6; world++) {
            var stage = rules.stage(event, world);
            assertNotNull(stage);
            assertEquals(SceneEventBattles.resolve(event, world), stage);
            fixture.session.actor.world = world;
            assertNull(new BattleService(null).startEventBattle(fixture.session.actor, event));
            count++;
          }
    assertEquals(147, count);
    assertEquals(0, fixture.session.actor.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"zero", "remaining", "quit"})
  void invalidResultCannotConsumeTheCurrentBattleOrEarnTheNextChoice(String variant)
      throws Exception {
    prepare(6);
    var b = start(6);
    int count = variant.equals("zero") ? 0 : variant.equals("remaining") ? 20 : 1;
    var status =
        variant.equals("quit") ? BattleEndStatus.BATTLE_END_QUIT : BattleEndStatus.BATTLE_END_WIN;
    assertNull(finish(b, status, count));
    assertSame(b, fixture.session.actor.getBattle());
    assertEquals(
        0, fixture.session.actor.getGameplay().getBoxingClubResonance().getActive().index());
    assertEquals(0, fixture.session.actor.grants);
    assertSame(b, finish(b, BattleEndStatus.BATTLE_END_LOSE, 0));
  }

  @ParameterizedTest
  @ValueSource(strings = {"seed", "pending"})
  void absentFalseAbandonClearsOnlyTheAttemptAndPreservesEarnedRecords(String phase)
      throws Exception {
    seed(6);
    if (phase.equals("pending")) {
      choose(6, resonance(6), true);
      var b = start(6);
      assertSame(b, finish(b, BattleEndStatus.BATTLE_END_WIN, 1));
    }
    assertTrue(
        VersionedPacketHandler.handle(
            fixture.session, 4258, fixture.scalar("GiveUpBoxingClubChallengeCsReq", 6)));
    assertEquals(0, code());
    assertEquals(0, progress("IFHHFBIJOFC"));
    assertNull(fixture.session.actor.getInstance());
    assertNull(fixture.session.actor.getGameplay().getBoxingClubResonance().getActive());
    assertTrue(
        fixture.session.actor.getGameplay().getBoxingClubResonance().getCompleted().isEmpty());
    assertEquals(0, fixture.session.actor.grants);
  }

  @ParameterizedTest
  @ValueSource(strings = {"resonance", "optional"})
  void failedChoiceSaveRollsBackAndTheSameSelectionCanRetry(String phase) throws Exception {
    seed(6);
    if (phase.equals("optional")) {
      choose(6, resonance(6), true);
      var b = start(6);
      assertSame(b, finish(b, BattleEndStatus.BATTLE_END_WIN, 1));
    }
    var before = fixture.session.actor.getGameplay().getBoxingClubResonance();
    int buff = phase.equals("resonance") ? resonance(6) : options(6, 1).get(0);
    fixture.session.actor.failSave = true;
    assertThrows(IllegalStateException.class, () -> choose(6, buff, phase.equals("resonance")));
    assertSame(before, fixture.session.actor.getGameplay().getBoxingClubResonance());
    fixture.session.actor.failSave = false;
    choose(6, buff, phase.equals("resonance"));
    assertEquals(0, code());
    assertEquals(0, fixture.session.actor.grants);
  }

  @ParameterizedTest
  @ValueSource(strings = {"source", "uid", "group", "option", "type"})
  void corruptedSavedChoiceRecordsCannotStartOrManufactureAReward(String variant) throws Exception {
    prepare(6);
    var gson = new Gson();
    var state =
        JsonParser.parseString(
                gson.toJson(fixture.session.actor.getGameplay().getBoxingClubResonance()))
            .getAsJsonObject();
    var run = state.getAsJsonObject("active");
    switch (variant) {
      case "source" -> run.addProperty("source", "unverified/source");
      case "uid" -> run.addProperty("uid", fixture.session.actor.getUid() + 1);
      case "group" -> run.addProperty("group", 64);
      case "option" -> run.getAsJsonArray("options").add(3101093);
      case "type" -> run.getAsJsonArray("rosterTypes").set(0, new JsonPrimitive(3));
    }
    fixture
        .session
        .actor
        .getGameplay()
        .boxingClubResonance(gson.fromJson(state, BoxingClubResonanceState.class));
    startRequest(6);
    assertNotEquals(0, code());
    assertNull(fixture.session.actor.getBattle());
    assertTrue(
        fixture.session.actor.getGameplay().getBoxingClubResonance().getCompleted().isEmpty());
    assertEquals(0, fixture.session.actor.grants);
  }
}
