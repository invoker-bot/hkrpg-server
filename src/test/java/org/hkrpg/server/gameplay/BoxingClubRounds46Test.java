package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original rows, owned actor and explicit prerequisite/window; no client UI or combat input. */
class BoxingClubRounds46Test {
  @TempDir Path root;
  String previous;
  JsonObject fixture;
  final List<PlaneEventExcel> oldEvents = new ArrayList<>();
  final List<StageExcel> oldStages = new ArrayList<>();
  final List<ItemExcel> oldItems = new ArrayList<>();
  final List<AvatarExcel> oldActors = new ArrayList<>();
  final List<MazeBuffExcel> oldBuffs = new ArrayList<>();
  final List<RewardExcel> oldRewards = new ArrayList<>();
  final List<ActivityPanelExcel> oldPanels = new ArrayList<>();
  final List<GameplayDefinitions.Window> oldWindows = new ArrayList<>();
  Session session;
  LocalLegendEntry46Test.Formal formal;

  static class Actor extends LocalLegendEntry46Test.Actor {
    @dev.morphia.annotations.Transient emu.lunarcore.game.scene.Scene scene;

    public emu.lunarcore.game.scene.Scene getScene() {
      return scene;
    }

    @dev.morphia.annotations.Transient final Teams teams = new Teams(this);

    public emu.lunarcore.game.player.lineup.LineupManager getLineupManager() {
      return teams;
    }

    int ids;

    public int getNextBattleId() {
      return ++ids;
    }
  }

  // Morphia scans annotated subclasses in this package during unrelated persistence tests.
  // An anonymous LineupManager inherits @Entity but has an empty collection name.
  static class Teams extends emu.lunarcore.game.player.lineup.LineupManager {
    @dev.morphia.annotations.Transient final Actor actor;

    Teams(Actor actor) {
      super(actor);
      this.actor = actor;
    }

    public emu.lunarcore.game.player.lineup.PlayerLineup getCurrentLineup() {
      return ((Actor) getPlayer()).ordinary;
    }
  }

  static class Session extends GameSession {
    final Actor actor = new Actor();
    static final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
    DynamicMessage response;
    String responseName;
    final List<String> names = new ArrayList<>();

    Session() {
      super(null);
      setState(SessionState.ACTIVE);
      actor.setSession(this);
    }

    public Actor getPlayer() {
      return actor;
    }

    public ProtocolBridge getProtocol() {
      return protocol;
    }

    public void send(BasePacket packet) {}

    public void sendClientPacket(String name, byte[] body) {
      try {
        response = protocol.clientSchema().parse(name, body);
        responseName = name;
        names.add(name);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }
  }

  JsonObject fixture(String name) throws Exception {
    try (var stream = getClass().getResourceAsStream("/fixtures/" + name)) {
      return JsonParser.parseString(
              new String(
                  Objects.requireNonNull(stream).readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
  }

  @BeforeEach
  void load() throws Exception {
    previous = LunarCore.getConfig().getResourceDir();
    rootConfig(root.toString());
    for (var raw : fixture("boxing-club-catalog-4.6.json").getAsJsonArray("inputs")) {
      var row = raw.getAsJsonObject();
      var path = root.resolve(row.get("path").getAsString());
      Files.createDirectories(path.getParent());
      Files.write(path, HexFormat.of().parseHex(row.get("rawHex").getAsString()));
    }
    oldEvents.addAll(GameData.getPlaneEventExcelMap().values());
    GameData.getPlaneEventExcelMap().values().clear();
    oldStages.addAll(GameData.getStageExcelMap().values());
    GameData.getStageExcelMap().values().clear();
    oldItems.addAll(GameData.getItemExcelMap().values());
    GameData.getItemExcelMap().values().clear();
    oldActors.addAll(GameData.getAvatarExcelMap().values());
    GameData.getAvatarExcelMap().values().clear();
    oldBuffs.addAll(GameData.getMazeBuffExcelMap().values());
    GameData.getMazeBuffExcelMap().values().clear();
    oldRewards.addAll(GameData.getRewardExcelMap().values());
    GameData.getRewardExcelMap().values().clear();
    oldPanels.addAll(GameData.getActivityPanelExcelMap().values());
    GameData.getActivityPanelExcelMap().values().clear();
    oldWindows.addAll(GameplayDefinitions.windows);
    GameplayDefinitions.windows.clear();
    fixture = fixture("boxing-club-rounds-4.6.json");
    var gson = ResourceLoader.getGson();
    for (var raw : fixture.getAsJsonArray("inputs")) {
      var input = raw.getAsJsonObject();
      String table = Path.of(input.get("path").getAsString()).getFileName().toString();
      if (!table.startsWith("BoxingClub"))
        Files.writeString(
            root.resolve(input.get("path").getAsString()), input.get("rows").toString());
      for (var row : input.getAsJsonArray("rows"))
        switch (table) {
          case "PlaneEvent.json" ->
              GameData.getPlaneEventExcelMap().add(gson.fromJson(row, PlaneEventExcel.class));
          case "StageConfig.json" -> {
            var v = gson.fromJson(row, StageExcel.class);
            v.onLoad();
            GameData.getStageExcelMap().add(v);
          }
          case "ItemConfig.json" ->
              GameData.getItemExcelMap().add(gson.fromJson(row, ItemExcel.class));
          case "AvatarConfig.json" ->
              GameData.getAvatarExcelMap().add(gson.fromJson(row, AvatarExcel.class));
          case "MazeBuff.json" ->
              GameData.getMazeBuffExcelMap().add(gson.fromJson(row, MazeBuffExcel.class));
          case "RewardData.json" -> {
            var v = gson.fromJson(row, RewardExcel.class);
            v.onLoad();
            GameData.getRewardExcelMap().add(v);
          }
          case "ActivityPanel.json" ->
              GameData.getActivityPanelExcelMap().add(gson.fromJson(row, ActivityPanelExcel.class));
        }
    }
    session = new Session();
    session.actor.setLevel(21);
    session.actor.world = 0;
    var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
    uid.setAccessible(true);
    uid.setInt(session.actor, 7310246);
    formal = new LocalLegendEntry46Test.Formal(GameData.getAvatarExcelMap().get(1001));
    formal.setOwner(session.actor);
    var id = GameAvatar.class.getDeclaredField("id");
    id.setAccessible(true);
    id.set(formal, new org.bson.types.ObjectId());
    session.actor.getAvatars().getAvatars().put(1001, formal);
    session.actor.getAvatars().getAvatarObjectIdMap().put(formal.getId(), formal);
    session.actor.ordinary.getAvatars().add(1001);
    formal.setCurrentHp(session.actor.ordinary, 1200);
    formal.setCurrentSp(session.actor.ordinary, 3000);
    var prerequisite = new QuestState(1011001);
    prerequisite.advance(1, 1, 1);
    session.actor.getGameplay().getMainMissions().put(1011001, prerequisite);
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(50000, 5000002, 1, Long.MAX_VALUE));
  }

  void rootConfig(String root) throws Exception {
    var f = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
    f.setAccessible(true);
    f.set(LunarCore.getConfig(), root);
  }

  @AfterEach
  void restore() throws Exception {
    rootConfig(previous);
    GameData.getPlaneEventExcelMap().values().clear();
    oldEvents.forEach(GameData.getPlaneEventExcelMap()::add);
    GameData.getStageExcelMap().values().clear();
    oldStages.forEach(GameData.getStageExcelMap()::add);
    GameData.getItemExcelMap().values().clear();
    oldItems.forEach(GameData.getItemExcelMap()::add);
    GameData.getAvatarExcelMap().values().clear();
    oldActors.forEach(GameData.getAvatarExcelMap()::add);
    GameData.getMazeBuffExcelMap().values().clear();
    oldBuffs.forEach(GameData.getMazeBuffExcelMap()::add);
    GameData.getRewardExcelMap().values().clear();
    oldRewards.forEach(GameData.getRewardExcelMap()::add);
    GameData.getActivityPanelExcelMap().values().clear();
    oldPanels.forEach(GameData.getActivityPanelExcelMap()::add);
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.addAll(oldWindows);
  }

  byte[] matchRequest(int id, int... roster) {
    var type = session.protocol.clientSchema().message("MatchBoxingClubOpponentCsReq");
    var b = DynamicMessage.newBuilder(type);
    b.setField(type.findFieldByName("challenge_id"), id);
    for (int actor : roster) b.addRepeatedField(type.findFieldByName("avatar_list"), actor);
    return b.build().toByteArray();
  }

  void match(int id, int... roster) throws Exception {
    assertTrue(
        VersionedPacketHandler.handle(session, 4205, matchRequest(id, roster)),
        "4.6 boxing match remains unhandled");
    assertEquals("MatchBoxingClubOpponentScRsp", session.responseName);
  }

  int number(DynamicMessage row, String field) {
    return (Integer) row.getField(row.getDescriptorForType().findFieldByName(field));
  }

  DynamicMessage row() {
    return (DynamicMessage)
        session.response.getField(
            session.response.getDescriptorForType().findFieldByName("OCDLPDDMCLP"));
  }

  @Test
  void matchingCreatesOnlyFirstRoundWithOriginalGroupAndOwnedParty() throws Exception {
    assertNotNull(BoxingClubRules.load());
    match(1, 1001);
    assertEquals(0, number(session.response, "retcode"));
    assertEquals(1, number(row(), "challenge_id"));
    assertEquals(10, number(row(), "IFHHFBIJOFC"));
    assertEquals(0, number(row(), "stage_index"));
    assertEquals(0, number(row(), "EAFICCLCLLA"));
    assertNull(session.actor.getBattle());
    assertEquals(0, session.actor.grants);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 6, 12})
  void unsupportedChallengeCannotManufactureASecondSeasonMatch(int id) throws Exception {
    match(id, 1001);
    assertNotEquals(0, number(session.response, "retcode"));
    assertEquals(0, session.actor.grants);
    assertEquals(0, session.actor.saves);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 9999})
  void anUnownedPartyIsRejectedBeforeSave(int id) throws Exception {
    match(1, id);
    assertNotEquals(0, number(session.response, "retcode"));
    assertEquals(0, session.actor.saves);
  }

  @Test
  void originalBoxingEventCannotBypassMatchingThroughTheGenericService() {
    assertEquals(3040040, SceneEventBattles.resolve(304004, 0).getId());
    assertNull(
        new BattleService(null).startEventBattle(session.actor, 304004),
        "Boxing event bypassed matching");
    assertNull(session.actor.getBattle());
    assertEquals(0, session.actor.saves);
  }

  byte[] scalar(String name, int id) {
    var type = session.protocol.clientSchema().message(name);
    return DynamicMessage.newBuilder(type)
        .setField(type.findFieldByName("challenge_id"), id)
        .build()
        .toByteArray();
  }

  BoxingClubBattle start() throws Exception {
    int id = session.actor.getGameplay().getBoxingClub().getActive().challenge();
    assertTrue(
        VersionedPacketHandler.handle(session, 4260, scalar("StartBoxingClubBattleCsReq", id)));
    assertEquals(0, number(session.response, "retcode"));
    return assertInstanceOf(BoxingClubBattle.class, session.actor.getBattle());
  }

  Battle finish(
      BoxingClubBattle battle,
      emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus result,
      int count) {
    return new BattleService(null)
        .finishBattle(
            session.actor,
            battle.getId(),
            battle.getStage().getId(),
            result,
            emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance()
                .setRoundCnt(count));
  }

  void win(BoxingClubBattle battle, int rounds) {
    assertSame(
        battle,
        finish(
            battle,
            emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN,
            rounds));
    assertNull(session.actor.getBattle());
  }

  void fourWins(int rounds) throws Exception {
    for (int i = 0; i < 4; i++) win(start(), rounds);
  }

  @Test
  void allFiveOriginalChallengesRequireTheirOwnOrderedFourVictoriesAndGrantOnce() throws Exception {
    for (int id = 1; id <= 5; id++) {
      var c = BoxingClubCatalog.load(root).challenges().get(id);
      GameplayDefinitions.windows.clear();
      GameplayDefinitions.windows.add(
          new GameplayDefinitions.Window(50000, c.module(), 1, Long.MAX_VALUE));
      match(id, 1001);
      assertEquals(0, number(session.response, "retcode"));
      for (int round = 0; round < 4; round++) {
        var run = session.actor.getGameplay().getBoxingClub().getActive();
        assertEquals(round, run.index());
        assertEquals(round * 2, run.rounds());
        assertEquals(round, run.buffs(BoxingClubCatalog.load(root)).size());
        var battle = start();
        assertEquals(20 - round * 2, battle.toProto().getRoundsLimit());
        assertEquals(round + 1, battle.toProto().getBuffList().length());
        win(battle, 2);
        assertEquals(id - (round == 3 ? 0 : 1), session.actor.grants);
      }
      assertNull(session.actor.getInstance());
      assertNull(session.actor.getGameplay().getBoxingClub().getActive());
      assertEquals(8, session.actor.getGameplay().getBoxingClub().getCompleted().get(id).rounds());
      assertTrue(new RewardLedger(session.actor).granted("boxing:first:" + id));
      assertEquals("BoxingClubRewardScNotify", session.responseName);
      assertEquals(8, number(session.response, "EAFICCLCLLA"));
      assertEquals(
          true,
          session.response.getField(
              session.response.getDescriptorForType().findFieldByName("is_win")));
    }
    assertEquals(100, session.actor.amounts.get(1));
    assertEquals(50000, session.actor.amounts.get(2));
    assertEquals(1200, formal.getCurrentHp(session.actor.ordinary));
    assertEquals(3000, formal.getCurrentSp(session.actor.ordinary));
    assertEquals(0, formal.saves);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
  void eachWorldUsesItsFourExactOriginalStagesAndTemporaryFullHealth(int world) throws Exception {
    session.actor.world = world;
    match(1, 1001);
    assertEquals(0, number(session.response, "retcode"));
    var group = BoxingClubCatalog.load(root).group(10);
    for (int round = 0; round < 4; round++) {
      var battle = start();
      var info = battle.toProto();
      assertEquals(
          SceneEventBattles.resolve(group.events().get(round), world).getId(), info.getStageId());
      assertEquals(world, info.getWorldLevel());
      assertEquals(10000, info.getBattleAvatarList().get(0).getHp());
      assertEquals(5000, info.getBattleAvatarList().get(0).getSpBar().getCurSp());
      win(battle, 1);
    }
    assertEquals(1, session.actor.grants);
    assertEquals(4, session.actor.getGameplay().getBoxingClub().getCompleted().get(1).rounds());
    assertEquals(1200, formal.getCurrentHp(session.actor.ordinary));
    assertEquals(0, formal.saves);
  }

  @Test
  void all140OriginalEventWorldPairsRequireTheDedicatedBoxingContext() {
    int rows = 0;
    var rules = BoxingClubRules.load();
    assertNotNull(rules);
    for (var input : fixture.getAsJsonArray("inputs")) {
      var table = input.getAsJsonObject();
      if (!table.get("path").getAsString().endsWith("PlaneEvent.json")) continue;
      for (var raw : table.getAsJsonArray("rows")) {
        var row = raw.getAsJsonObject();
        int event = row.get("EventID").getAsInt(),
            world = row.has("WorldLevel") ? row.get("WorldLevel").getAsInt() : 0;
        session.actor.world = world;
        assertEquals(row.get("StageID").getAsInt(), rules.stage(event, world).getId());
        assertNull(new BattleService(null).startEventBattle(session.actor, event));
        assertNull(
            new BattleService(null)
                .startBattle(session.actor, row.get("StageID").getAsInt(), world));
        rows++;
      }
    }
    assertEquals(140, rows);
    assertEquals(0, session.actor.ids);
    assertEquals(0, session.actor.saves);
  }

  @Test
  void replayAndNewAttemptsImproveTheBestRecordWithoutRegranting() throws Exception {
    match(1, 1001);
    var first = start();
    win(first, 3);
    assertNull(
        finish(
            first,
            emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN,
            3));
    for (int i = 0; i < 3; i++) win(start(), 3);
    assertEquals(12, session.actor.getGameplay().getBoxingClub().getCompleted().get(1).rounds());
    assertEquals(1, session.actor.grants);
    match(1, 1001);
    fourWins(2);
    assertEquals(8, session.actor.getGameplay().getBoxingClub().getCompleted().get(1).rounds());
    assertEquals(1, session.actor.grants);
    assertEquals(20, session.actor.amounts.get(1));
  }

  @Test
  void lossAfterThreeWinsEndsOnlyTheAttemptAndEarnsNothing() throws Exception {
    match(1, 1001);
    for (int i = 0; i < 3; i++) win(start(), 1);
    var last = start();
    assertSame(
        last,
        finish(
            last,
            emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_LOSE,
            1));
    assertNull(session.actor.getInstance());
    assertNull(session.actor.getGameplay().getBoxingClub().getActive());
    assertTrue(session.actor.getGameplay().getBoxingClub().getCompleted().isEmpty());
    assertEquals(0, session.actor.grants);
    assertTrue(session.actor.getGameplay().getRewards().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 20, 21, Integer.MAX_VALUE})
  void invalidCountsCannotAdvanceOrReleaseTheCurrentBattle(int count) throws Exception {
    match(1, 1001);
    var battle = start();
    var before = new Gson().toJson(session.actor.getGameplay());
    assertNull(
        finish(
            battle,
            emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN,
            count));
    assertSame(battle, session.actor.getBattle());
    assertEquals(before, new Gson().toJson(session.actor.getGameplay()));
    win(battle, 1);
  }

  @Test
  void sourceForbidExitRejectsQuitAndKeepsTheSameAttempt() throws Exception {
    match(1, 1001);
    var b = start();
    assertTrue(((StageExcel) b.getStage()).isForbidExitBattle());
    assertNull(
        finish(
            b, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_QUIT, 1));
    assertSame(b, session.actor.getBattle());
    assertEquals(Retcode.SYSTEM_BUSY, BoxingClubManager.giveUp(session.actor, 1));
    win(b, 1);
  }

  @Test
  void matchingAndStartingCannotReplaceAnExistingRoundOrConsumeAnotherIdentity() throws Exception {
    match(1, 1001);
    int saves = session.actor.saves;
    match(1, 1001);
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), number(session.response, "retcode"));
    assertEquals(saves, session.actor.saves);
    var battle = start();
    int ids = session.actor.ids;
    assertEquals(
        Retcode.SYSTEM_BUSY,
        BoxingClubManager.start(session.actor, 1, java.time.Instant.now()).retcode());
    assertSame(battle, session.actor.getBattle());
    assertEquals(ids, session.actor.ids);
  }

  @Test
  void abandonmentBetweenRoundsPreservesOrdinaryRosterAndEarnsNoHistory() throws Exception {
    match(1, 1001);
    win(start(), 1);
    assertTrue(
        VersionedPacketHandler.handle(session, 4258, scalar("GiveUpBoxingClubChallengeCsReq", 1)));
    assertEquals(0, number(session.response, "retcode"));
    assertEquals(0, number(row(), "IFHHFBIJOFC"));
    assertNull(session.actor.getInstance());
    assertTrue(session.actor.getGameplay().getBoxingClub().getCompleted().isEmpty());
    assertEquals(0, session.actor.grants);
    assertEquals(List.of(1001), session.actor.ordinary.getAvatars());
    assertEquals(0, formal.saves);
  }

  @Test
  void anExpiredOperatorWindowCannotStartOrSettleButCanAbandonTheAttempt() throws Exception {
    match(1, 1001);
    var b = start();
    GameplayDefinitions.windows.clear();
    assertNull(
        finish(b, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN, 1));
    assertSame(b, session.actor.getBattle());
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(50000, 5000002, 1, Long.MAX_VALUE));
    win(b, 1);
    GameplayDefinitions.windows.clear();
    assertEquals(
        Retcode.REQ_PARA_INVALID,
        BoxingClubManager.start(session.actor, 1, java.time.Instant.now()).retcode());
    assertEquals(Retcode.SUCC, BoxingClubManager.giveUp(session.actor, 1));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "PlaneEvent",
        "StageConfig",
        "MazeBuff",
        "RewardData",
        "ActivityPanel",
        "ActivityConfig"
      })
  void aMissingOriginalSourceCannotSettleAnAdmittedRound(String table) throws Exception {
    match(1, 1001);
    var b = start();
    var path = root.resolve("ExcelOutput/" + table + ".json");
    var saved = Files.readAllBytes(path);
    Files.delete(path);
    assertNull(
        finish(b, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN, 1));
    assertEquals(0, session.actor.grants);
    assertSame(b, session.actor.getBattle());
    Files.write(path, saved);
    win(b, 1);
  }

  @Test
  void replacingLoadedEventStageOrBuffCannotRebindTheFrozenBattle() throws Exception {
    match(1, 1001);
    var b = start();
    var stage = GameData.getStageExcelMap().get(3040040);
    var wave = stage.getMonsterWaves().get(0);
    int first = wave.getInt(0);
    wave.set(0, 1);
    assertNull(
        finish(b, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN, 1));
    wave.set(0, first);
    var original = GameData.getMazeBuffExcelMap().get(3100037, 1);
    GameData.getMazeBuffExcelMap().values().remove(original);
    assertNull(
        finish(b, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN, 1));
    GameData.getMazeBuffExcelMap().add(original);
    win(b, 1);
  }

  @Test
  void failedMatchSaveRollsBackAndCannotCreateAnInstance() throws Exception {
    session.actor.failSave = true;
    assertThrows(IllegalStateException.class, () -> match(1, 1001));
    assertNull(session.actor.getInstance());
    assertNull(session.actor.getGameplay().getBoxingClub().getActive());
    assertNull(session.actor.getBattle());
    assertEquals(0, session.actor.grants);
  }

  @Test
  void aPendingInterruptedDeliveryNeverAutomaticallyRegrants() throws Exception {
    session.actor.partialGrant = true;
    match(1, 1001);
    fourWins(1);
    assertEquals(1, session.actor.grants);
    assertFalse(new RewardLedger(session.actor).granted("boxing:first:1"));
    session.actor.partialGrant = false;
    match(1, 1001);
    fourWins(1);
    assertEquals(1, session.actor.grants);
    assertFalse(new RewardLedger(session.actor).granted("boxing:first:1"));
    assertEquals(20, session.actor.amounts.get(1));
  }

  @Test
  void prerequisiteLevelWindowAndPreviousChallengeAreCheckedBeforeMutation() throws Exception {
    session.actor.setLevel(20);
    match(1, 1001);
    assertNotEquals(0, number(session.response, "retcode"));
    session.actor.setLevel(21);
    var gate = session.actor.getGameplay().getMainMissions().remove(1011001);
    match(1, 1001);
    assertNotEquals(0, number(session.response, "retcode"));
    session.actor.getGameplay().getMainMissions().put(1011001, gate);
    GameplayDefinitions.windows.clear();
    match(1, 1001);
    assertNotEquals(0, number(session.response, "retcode"));
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(50000, 5000003, 1, Long.MAX_VALUE));
    match(2, 1001);
    assertNotEquals(0, number(session.response, "retcode"));
    assertEquals(0, session.actor.saves);
  }

  @Test
  void packedLegacyAndModernFormalRowsIdentifyTheSameOriginalParty() throws Exception {
    var type = session.protocol.clientSchema().message("MatchBoxingClubOpponentCsReq");
    var b = DynamicMessage.newBuilder(type).setField(type.findFieldByName("challenge_id"), 1);
    var member = type.findFieldByName("OOJOMKDMBBM").getMessageType();
    var row =
        DynamicMessage.newBuilder(member)
            .setField(member.findFieldByName("avatar_id"), 1001)
            .setField(
                member.findFieldByName("avatar_type"),
                member
                    .findFieldByName("avatar_type")
                    .getEnumType()
                    .findValueByName("AvatarType_AvatarFormalType"))
            .build();
    b.addRepeatedField(type.findFieldByName("OOJOMKDMBBM"), row);
    b.addRepeatedField(type.findFieldByName("avatar_list"), 1001);
    assertTrue(VersionedPacketHandler.handle(session, 4205, b.build().toByteArray()));
    assertEquals(0, number(session.response, "retcode"));
  }

  @Test
  void wrappedAndDuplicateChallengeIDsAreRejectedBeforeAnySave() throws Exception {
    for (boolean duplicate : List.of(false, true)) {
      var out = new java.io.ByteArrayOutputStream();
      var wire = com.google.protobuf.CodedOutputStream.newInstance(out);
      wire.writeUInt64(1, duplicate ? 1 : 0x100000001L);
      if (duplicate) wire.writeUInt32(1, 1);
      wire.writeUInt32(2, 1001);
      wire.flush();
      assertTrue(VersionedPacketHandler.handle(session, 4205, out.toByteArray()));
      assertNotEquals(0, number(session.response, "retcode"));
      assertEquals(0, session.actor.saves);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void ordinaryNpcAttackCannotCreateAnUnmatchedBoxingBattle(boolean custom) {
    var gson = ResourceLoader.getGson();
    var plane =
        gson.fromJson(
            "{\"PlaneID\":199991,\"WorldID\":1,\"PlaneType\":\"Town\"}", MazePlaneExcel.class);
    session.actor.scene = new emu.lunarcore.game.scene.Scene(session.actor, plane, 199991001);
    var monster =
        new emu.lunarcore.game.scene.entity.EntityMonster(
            session.actor.scene,
            gson.fromJson("{\"ID\":1004020}", NpcMonsterExcel.class),
            new emu.lunarcore.data.config.GroupInfo(),
            gson.fromJson("{\"ID\":1}", emu.lunarcore.data.config.MonsterInfo.class));
    monster.setEntityId(99);
    monster.setEventId(304004);
    monster.setWorldLevel(0);
    if (custom) monster.setCustomStage(GameData.getStageExcelMap().get(3040040));
    session.actor.scene.getEntities().put(99, monster);
    new BattleService(null)
        .attack(
            session.actor,
            formal.getEntityId(),
            formal.getEntityId(),
            null,
            new it.unimi.dsi.fastutil.ints.IntOpenHashSet(new int[] {99}),
            new it.unimi.dsi.fastutil.ints.IntOpenHashSet());
    assertNull(session.actor.getBattle(), "NPC path bypassed boxing matching");
    assertEquals(0, session.actor.ids);
    assertEquals(0, session.actor.grants);
  }

  @ParameterizedTest
  @ValueSource(strings = {"closed", "missing", "party"})
  void aBoundLossCanReleaseAnAttemptWhenAdmissionConditionsDisappear(String change)
      throws Exception {
    match(1, 1001);
    var b = start();
    if (change.equals("closed")) GameplayDefinitions.windows.clear();
    else if (change.equals("missing")) Files.delete(root.resolve("ExcelOutput/StageConfig.json"));
    else formal.setCurrentHp(session.actor.ordinary, 2000);
    assertSame(
        b,
        finish(
            b, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_LOSE, 0));
    assertNull(session.actor.getBattle());
    assertNull(session.actor.getInstance());
    assertNull(session.actor.getGameplay().getBoxingClub().getActive());
    assertTrue(session.actor.getGameplay().getBoxingClub().getCompleted().isEmpty());
    assertEquals(0, session.actor.grants);
    assertTrue(session.actor.getGameplay().getRewards().isEmpty());
  }
}
