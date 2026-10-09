package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ActivityPanelExcel;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.data.excel.RewardExcel;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class MatchThree46Test {
  @TempDir Path root;
  Session session;
  ActivityPanelExcel oldPanel;
  GameplayDefinitions.Window window;
  static final int GATE = 199970456;
  static final int ITEM = 199970457, REWARD = 199970458;

  static class Performer extends Player {
    int saves, calls, granted;
    boolean partial;
    boolean interruptResultSave;
    final Inventory inventory =
        new Inventory(this) {
          public GrantResult grantItems(ItemParamMap items, boolean hint) {
            calls++;
            var result = new ArrayList<GameItem>();
            for (var item : items) {
              int count = partial ? 1 : item.getIntValue();
              granted += count;
              result.add(new GameItem(GameData.getItemExcelMap().get(item.getIntKey()), count));
            }
            return new GrantResult(result, !partial);
          }
        };

    public void save() {
      saves++;
      if (interruptResultSave
          && getGameplay().getMatchThree().getReports().values().stream()
              .anyMatch(GameplayState.MatchThreeReport::isApplied)) {
        interruptResultSave = false;
        throw new IllegalStateException("simulated interruption before story progress");
      }
    }

    public Inventory getInventory() {
      return inventory;
    }

    public void sendPacket(BasePacket packet) {}
  }

  static class Session extends GameSession {
    final Performer player = new Performer();
    DynamicMessage response;
    String responseName;
    int syncs;

    Session() {
      super(null);
      setState(SessionState.ACTIVE);
    }

    public Player getPlayer() {
      return player;
    }

    public ProtocolBridge getProtocol() {
      return ProtocolBridge.forVersion("4.6.0");
    }

    public void send(BasePacket packet) {}

    public void sendClientPacket(String name, byte[] bytes) {
      try {
        if (name.equals("MatchThreeSyncDataScNotify")) {
          assertTrue(player.saves > 0);
          syncs++;
        }
        responseName = name;
        response = getProtocol().clientSchema().parse(name, bytes);
      } catch (java.io.IOException ex) {
        throw new AssertionError(ex);
      }
    }
  }

  @BeforeEach
  void setup() throws Exception {
    session = new Session();
    oldPanel = GameData.getActivityPanelExcelMap().get(50014);
    GameData.getActivityPanelExcelMap()
        .add(new Gson().fromJson("{\"PanelID\":50014}", ActivityPanelExcel.class));
    window = new GameplayDefinitions.Window(50014, 5001400, 0, 4102444800L);
    GameplayDefinitions.windows.add(window);
    var done = new QuestState(GATE);
    done.advance(1, 1, 1);
    session.player.getGameplay().getSubMissions().put(GATE, done);
    Path excel = Files.createDirectories(root.resolve("ExcelOutput"));
    Files.writeString(
        excel.resolve("MatchThreeVersion.json"),
        "[{\"ActivityVersion\":1,\"ActivityID\":50014,\"PVPModuleID\":5001400,\"BirdIDList\":[300,301,305,311]},{\"ActivityVersion\":3,\"ActivityID\":50067,\"BirdIDList\":[701]}]");
    Files.writeString(
        excel.resolve("MatchThreeBird.json"),
        "[{\"BirdID\":300,\"IsShow\":true},{\"BirdID\":301,\"UnlockLevel\":1100,\"IsShow\":true},{\"BirdID\":305},{\"BirdID\":311},{\"BirdID\":701}]");
    Files.writeString(
        excel.resolve("MatchThreeLevel.json"),
        "[{\"LevelID\":1000,\"PlayerBirdID\":311},{\"LevelID\":1100,\"Mode\":1,\"UnlockID\":"
            + GATE
            + ",\"PlayerBirdID\":300},{\"LevelID\":1100,\"Mode\":2,\"UnlockID\":"
            + GATE
            + ",\"PlayerBirdID\":300}]");
    Files.writeString(
        excel.resolve("MatchThreeConstValueCommon.json"),
        "[{\"ConstValueName\":\"MatchThree_BirdNestNumber\",\"Value\":{\"IntValue\":6}}]");
    Files.writeString(
        excel.resolve("MatchThreeConstValueClient.json"),
        "[{\"ConstValueName\":\"MatchThree_MainPageUnlock_SubMissionID\",\"Value\":{\"IntValue\":"
            + GATE
            + "}}]");
    MatchThreeCatalog.load(root);
  }

  @AfterEach
  void cleanup() {
    MatchThreeCatalog.versions.clear();
    MatchThreeCatalog.birds.clear();
    MatchThreeCatalog.levels.clear();
    MatchThreeCatalog.constants.clear();
    GameplayDefinitions.windows.remove(window);
    if (oldPanel == null) GameData.getActivityPanelExcelMap().keySet().remove(50014);
    else GameData.getActivityPanelExcelMap().add(oldPanel);
    GameData.getItemExcelMap().keySet().remove(ITEM);
    GameData.getRewardExcelMap().keySet().remove(REWARD);
    GameData.getRewardExcelMap().keySet().remove(REWARD + 1);
  }

  DynamicMessage query() throws Exception {
    assertTrue(
        VersionedPacketHandler.handle(session, 7411, new byte[0]),
        "Native 4.6 query must be dispatched");
    assertEquals("MatchThreeGetDataDataScRsp", session.responseName);
    assertEquals(0, Gameplay46.number(session.response, "retcode"));
    return (DynamicMessage) Gameplay46.value(session.response, "GJFKKFFJDAH");
  }

  DynamicMessage position(int bird, int pos) throws Exception {
    var b = Gameplay46.builder(session, "MatchThreeSetBirdPosCsReq");
    Gameplay46.set(b, "HBONKLEOEEI", bird);
    Gameplay46.set(b, "pos", pos);
    assertTrue(
        VersionedPacketHandler.handle(session, 7432, b.build().toByteArray()),
        "Native position request must be dispatched");
    assertEquals("MatchThreeSetBirdPosScRsp", session.responseName);
    assertEquals(bird, Gameplay46.number(session.response, "HBONKLEOEEI"));
    assertEquals(pos, Gameplay46.number(session.response, "pos"));
    return session.response;
  }

  List<DynamicMessage> rows(DynamicMessage data, String field) {
    return ((List<?>) Gameplay46.value(data, field)).stream().map(v -> (DynamicMessage) v).toList();
  }

  DynamicMessage bird(int id) throws Exception {
    return rows(query(), "DBINCLPHEFN").stream()
        .filter(r -> Gameplay46.number(r, "HBONKLEOEEI") == id)
        .findFirst()
        .orElseThrow();
  }

  DynamicMessage finish(
      int mode, int level, int bird, int power, String uuid, Map<Integer, Integer> stats)
      throws Exception {
    var b = Gameplay46.builder(session, "MatchThreeLevelEndCsReq");
    Gameplay46.set(b, "KKHPKMAHDMK", mode);
    Gameplay46.set(b, "level_id", level);
    Gameplay46.set(b, "HBONKLEOEEI", bird);
    Gameplay46.set(b, "LALGADHLMCB", power);
    Gameplay46.set(b, "uuid", uuid);
    var f = b.getDescriptorForType().findFieldByName("GMONIMLFKFC");
    stats.forEach(
        (k, v) -> {
          var row = DynamicMessage.newBuilder(f.getMessageType());
          Gameplay46.set(row, "key", k);
          Gameplay46.set(row, "value", v);
          b.addRepeatedField(f, row.build());
        });
    assertTrue(
        VersionedPacketHandler.handle(session, 7420, b.build().toByteArray()),
        "Native classic result must be dispatched");
    assertEquals("MatchThreeLevelEndScRsp", session.responseName);
    assertEquals(mode, Gameplay46.number(session.response, "KKHPKMAHDMK"));
    assertEquals(level, Gameplay46.number(session.response, "level_id"));
    return session.response;
  }

  void reward(int mode, int count) {
    var gson = new Gson();
    GameData.getItemExcelMap()
        .add(
            gson.fromJson(
                "{\"ID\":"
                    + ITEM
                    + ",\"ItemMainType\":\"Material\",\"ItemSubType\":\"Material\",\"PileLimit\":9999}",
                ItemExcel.class));
    var row =
        gson.fromJson(
            "{\"RewardID\":"
                + (REWARD + mode - 1)
                + ",\"ItemID_1\":"
                + ITEM
                + ",\"Count_1\":"
                + count
                + "}",
            RewardExcel.class);
    row.onLoad();
    GameData.getRewardExcelMap().add(row);
    MatchThreeCatalog.levels.put(
        mode + ":1100", new MatchThreeCatalog.Level(1100, mode, GATE, REWARD + mode - 1, 300));
  }

  @Test
  void anOpenLevelCannotCreateAnActivityResultWhileAMissionRaidOwnsThePlayer() throws Exception {
    reward(1, 2);
    session
        .player
        .getGameplay()
        .beginMissionRaid(
            new GameplayState.MissionRaidRun(49979, 0, 199979, 49979, 49979001, 499791));
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(),
        Gameplay46.number(finish(1, 1100, 300, 50, "raid-result", Map.of(5, 2)), "retcode"));
    assertTrue(session.player.getGameplay().getMatchThree().getReports().isEmpty());
    assertTrue(session.player.getGameplay().getMatchThree().getStatistics().isEmpty());
    assertEquals(0, session.player.granted);
    assertEquals(0, session.syncs);
    session.player.getGameplay().endMissionRaid();
    assertEquals(
        0, Gameplay46.number(finish(1, 1100, 300, 50, "raid-result", Map.of(5, 2)), "retcode"));
    assertEquals(2, session.player.granted);
  }

  @Test
  void firstRewardsAreSeparatePerModeAndRepeatedNewGamesUpdateOnlyRealCounters() throws Exception {
    reward(1, 2);
    reward(2, 3);
    assertEquals(
        0, Gameplay46.number(finish(1, 1100, 300, 50, "one", Map.of(5, 2, 10, 4)), "retcode"));
    assertEquals(2, session.player.granted);
    assertEquals(
        0, Gameplay46.number(finish(1, 1100, 300, 70, "two", Map.of(5, 1, 10, 3)), "retcode"));
    assertEquals(2, session.player.granted);
    assertEquals(0, Gameplay46.number(finish(2, 1100, 300, 20, "hard", Map.of()), "retcode"));
    assertEquals(5, session.player.granted);
    assertEquals(2, session.player.calls);
    assertEquals(Map.of(5, 3, 10, 7), session.player.getGameplay().getMatchThree().getStatistics());
    assertEquals(3, Gameplay46.number(bird(300), "count"));
    assertEquals(70, Gameplay46.number(bird(300), "LALGADHLMCB"));
    assertEquals(3, session.syncs);
    assertEquals(2, session.player.getGameplay().getRewards().size());
  }

  @Test
  void partialRewardCannotPublishCompletionOrRepeatGrantsOrReplaceItsReport() throws Exception {
    reward(1, 2);
    session.player.partial = true;
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(),
        Gameplay46.number(finish(1, 1100, 300, 50, "partial", Map.of(5, 2)), "retcode"));
    assertEquals(1, session.player.granted);
    assertEquals(0, session.syncs);
    assertTrue(rows(query(), "MJNNNJPDKDI").isEmpty());
    reward(1, 9);
    assertEquals(
        Retcode.SYSTEM_BUSY.getVal(),
        Gameplay46.number(finish(1, 1100, 300, 50, "partial", Map.of(5, 2)), "retcode"));
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(),
        Gameplay46.number(finish(1, 1100, 300, 51, "partial", Map.of(5, 2)), "retcode"));
    assertEquals(
        Retcode.SYSTEM_BUSY.getVal(),
        Gameplay46.number(finish(1, 1100, 300, 50, "new", Map.of(5, 2)), "retcode"));
    assertEquals(1, session.player.granted);
    assertEquals(1, session.player.calls);
    assertEquals(
        Map.of(ITEM, 2),
        session
            .player
            .getGameplay()
            .getMatchThree()
            .getReports()
            .values()
            .iterator()
            .next()
            .getRewards());
  }

  @Test
  void missingRewardAndCounterOverflowCannotFreezeOrGrantAnInvalidResult() throws Exception {
    reward(1, 2);
    GameData.getRewardExcelMap().keySet().remove(REWARD);
    assertNotEquals(0, Gameplay46.number(finish(1, 1100, 300, 50, "missing", Map.of()), "retcode"));
    assertTrue(session.player.getGameplay().getMatchThree().getReports().isEmpty());
    reward(1, 2);
    session.player.getGameplay().getMatchThree().getStatistics().put(5, Integer.MAX_VALUE);
    assertNotEquals(
        0, Gameplay46.number(finish(1, 1100, 300, 50, "overflow", Map.of(5, 1)), "retcode"));
    assertTrue(session.player.getGameplay().getMatchThree().getReports().isEmpty());
    assertEquals(0, session.player.calls);
    assertTrue(rows(query(), "MJNNNJPDKDI").isEmpty());
  }

  @Test
  void simultaneousRetriesApplyTheResultAndFirstRewardOnce() throws Exception {
    reward(1, 2);
    var threads = java.util.concurrent.Executors.newFixedThreadPool(6);
    try {
      var jobs = new ArrayList<java.util.concurrent.Callable<Retcode>>();
      for (int i = 0; i < 6; i++)
        jobs.add(
            () ->
                new MatchThreeManager(session.player)
                    .finish(1, 1100, 300, 50, "parallel", Map.of(5, 2)));
      for (var result : threads.invokeAll(jobs)) assertEquals(Retcode.SUCC, result.get());
    } finally {
      threads.shutdownNow();
    }
    assertEquals(1, session.player.calls);
    assertEquals(2, session.player.granted);
    assertEquals(1, Gameplay46.number(bird(300), "count"));
    assertEquals(Map.of(5, 2), session.player.getGameplay().getMatchThree().getStatistics());
  }

  @Test
  void actualMorphiaReloadRestoresPositionAndReconcilesAnAlreadyGrantedFrozenResult()
      throws Exception {
    reward(1, 2);
    assertEquals(0, Gameplay46.number(position(300, 6), "retcode"));
    assertEquals(
        0, Gameplay46.number(finish(1, 1100, 300, 50, "recover", Map.of(5, 2)), "retcode"));
    var state = session.player.getGameplay().getMatchThree();
    var report = state.getReports().values().iterator().next();
    var applied = GameplayState.MatchThreeReport.class.getDeclaredField("applied");
    applied.setAccessible(true);
    applied.setBoolean(report, false);
    state.getCompleted().clear();
    state.getStatistics().clear();
    state.getBirds().get(300).result(0, 0);
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "match-three-recovery");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456788);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, session.player.getGameplay());
      store.save(document);
      gameplay.set(session.player, store.find(Player.class).first().getGameplay());
      GameData.getRewardExcelMap().keySet().remove(REWARD);
      MatchThreeCatalog.levels.remove("1:1100");
      assertEquals(
          0, Gameplay46.number(finish(1, 1100, 300, 50, "recover", Map.of(5, 2)), "retcode"));
      assertEquals(1, session.player.calls);
      assertEquals(2, session.player.granted);
      assertEquals(6, Gameplay46.number(bird(300), "pos"));
      assertEquals(1, Gameplay46.number(bird(300), "count"));
      assertEquals(Map.of(5, 2), session.player.getGameplay().getMatchThree().getStatistics());
      assertEquals(1, rows(query(), "MJNNNJPDKDI").size());
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void invalidVersionRosterDoesNotAdvertiseAnEmptySuccessfulActivity() throws Exception {
    MatchThreeCatalog.versions.put(
        1, new MatchThreeCatalog.Version(1, 50014, 5001400, List.of(300, 999)));
    assertTrue(VersionedPacketHandler.handle(session, 7411, new byte[0]));
    assertNotEquals(0, Gameplay46.number(session.response, "retcode"));
    assertFalse(
        session.response.hasField(
            session.response.getDescriptorForType().findFieldByName("GJFKKFFJDAH")));
    assertNotEquals(0, Gameplay46.number(position(300, 1), "retcode"));
  }

  @Test
  void anotherActiveMinigameCannotSubmitANewClassicResult() throws Exception {
    session.player.getGameplay().getRhythm().start(199970499);
    assertNotEquals(0, Gameplay46.number(finish(1, 1100, 300, 50, "overlap", Map.of()), "retcode"));
    assertTrue(session.player.getGameplay().getMatchThree().getReports().isEmpty());
    assertTrue(rows(query(), "MJNNNJPDKDI").isEmpty());
  }

  @Test
  void damagedFrozenCountersCannotPublishNegativeClientRecords() throws Exception {
    assertEquals(
        0, Gameplay46.number(finish(1, 1100, 300, 50, "damaged", Map.of(5, 2)), "retcode"));
    var state = session.player.getGameplay().getMatchThree();
    var report = state.getReports().values().iterator().next();
    for (var value : Map.of("applied", false, "count", -1).entrySet()) {
      var field = GameplayState.MatchThreeReport.class.getDeclaredField(value.getKey());
      field.setAccessible(true);
      field.set(report, value.getValue());
    }
    state.getCompleted().clear();
    int syncs = session.syncs;
    assertNotEquals(
        0, Gameplay46.number(finish(1, 1100, 300, 50, "damaged", Map.of(5, 2)), "retcode"));
    assertEquals(syncs, session.syncs);
    assertEquals(1, Gameplay46.number(bird(300), "count"));
    assertTrue(rows(query(), "MJNNNJPDKDI").isEmpty());
  }

  @Test
  void duplicateStatisticKeysCannotBeCollapsedIntoAnAcceptedResult() throws Exception {
    var b = Gameplay46.builder(session, "MatchThreeLevelEndCsReq");
    Gameplay46.set(b, "KKHPKMAHDMK", 1);
    Gameplay46.set(b, "level_id", 1100);
    Gameplay46.set(b, "HBONKLEOEEI", 300);
    Gameplay46.set(b, "uuid", "duplicate-stats");
    var field = b.getDescriptorForType().findFieldByName("GMONIMLFKFC");
    for (int count : List.of(1, 2)) {
      var entry = DynamicMessage.newBuilder(field.getMessageType());
      Gameplay46.set(entry, "key", 5);
      Gameplay46.set(entry, "value", count);
      b.addRepeatedField(field, entry.build());
    }
    assertTrue(VersionedPacketHandler.handle(session, 7420, b.build().toByteArray()));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(session.response, "retcode"));
    assertTrue(session.player.getGameplay().getMatchThree().getReports().isEmpty());
    assertEquals(0, session.player.calls);
  }

  @Test
  void malformedUtf8UuidIsRejectedWithoutEscapingTheNativeRequestHandler() throws Exception {
    var out = new java.io.ByteArrayOutputStream();
    var wire = com.google.protobuf.CodedOutputStream.newInstance(out);
    wire.writeUInt32(2, 300);
    wire.writeUInt32(8, 1);
    wire.writeUInt32(14, 1100);
    wire.writeByteArray(15, new byte[] {(byte) 0xc3, 0x28});
    wire.flush();
    assertDoesNotThrow(
        () -> assertTrue(VersionedPacketHandler.handle(session, 7420, out.toByteArray())));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(session.response, "retcode"));
    assertTrue(session.player.getGameplay().getMatchThree().getReports().isEmpty());
  }

  @Test
  void interruptedCompletedResultRecoversOnlyTheOriginallyAcceptedStoryTargetsOnce()
      throws Exception {
    int main = 199970460, first = 199970461, later = 199970463;
    for (int id : List.of(first, later))
      GameplayDefinitions.subs.put(
          id,
          new Gson()
              .fromJson(
                  "{\"ID\":"
                      + id
                      + ",\"MainMissionID\":"
                      + main
                      + ",\"TakeType\":\""
                      + (id == first ? "Auto" : "AnySequence")
                      + "\",\"TakeParamIntList\":["
                      + first
                      + "],\"FinishType\":\"MatchThreeFinishLevel\",\"ParamType\":\"Equal\",\"ParamInt1\":1100,\"ParamInt2\":1}",
                  emu.lunarcore.data.config.MissionInfo.SubMissionInfo.class));
    try {
      session.player.getGameplay().getMainMissions().put(main, new QuestState(main));
      session.player.getMissionManager().refresh();
      session.player.interruptResultSave = true;
      assertThrows(
          IllegalStateException.class, () -> finish(1, 1100, 300, 50, "story-recover", Map.of()));
      assertEquals(0, session.player.getMissionManager().sub(first).getProgress());
      assertEquals(
          0, Gameplay46.number(finish(1, 1100, 300, 50, "story-recover", Map.of()), "retcode"));
      assertEquals(
          QuestState.Phase.FINISHED, session.player.getMissionManager().sub(first).getPhase());
      assertEquals(0, session.player.getMissionManager().sub(later).getProgress());
      assertEquals(
          0, Gameplay46.number(finish(1, 1100, 300, 50, "story-recover", Map.of()), "retcode"));
      assertEquals(0, session.player.getMissionManager().sub(later).getProgress());
      assertEquals(1, Gameplay46.number(bird(300), "count"));
    } finally {
      for (int id : List.of(first, later)) GameplayDefinitions.subs.remove(id);
    }
  }

  @Test
  void aValidatedNewResultAdvancesOnlyAnAcceptedMatchingLevelAndModeObjective() throws Exception {
    int main = 199970460, first = 199970461, wrongMode = 199970462, later = 199970463;
    for (int id : List.of(first, wrongMode, later)) {
      GameplayDefinitions.subs.put(
          id,
          new Gson()
              .fromJson(
                  "{\"ID\":"
                      + id
                      + ",\"MainMissionID\":"
                      + main
                      + ",\"TakeType\":\""
                      + (id == later ? "AnySequence" : "Auto")
                      + "\",\"TakeParamIntList\":["
                      + first
                      + "],\"FinishType\":\"MatchThreeFinishLevel\",\"ParamType\":\"Equal\",\"ParamInt1\":1100,\"ParamInt2\":"
                      + (id == wrongMode ? 2 : 1)
                      + "}",
                  emu.lunarcore.data.config.MissionInfo.SubMissionInfo.class));
    }
    try {
      session.player.getGameplay().getMainMissions().put(main, new QuestState(main));
      session.player.getMissionManager().refresh();
      assertNotEquals(0, Gameplay46.number(finish(1, 1100, 301, 50, "bad", Map.of()), "retcode"));
      assertEquals(0, session.player.getMissionManager().sub(first).getProgress());
      assertEquals(0, Gameplay46.number(finish(1, 1100, 300, 50, "valid", Map.of()), "retcode"));
      assertEquals(
          QuestState.Phase.FINISHED, session.player.getMissionManager().sub(first).getPhase());
      assertEquals(0, session.player.getMissionManager().sub(wrongMode).getProgress());
      assertEquals(0, session.player.getMissionManager().sub(later).getProgress());
      assertEquals(0, Gameplay46.number(finish(1, 1100, 300, 50, "valid", Map.of()), "retcode"));
      assertEquals(0, session.player.getMissionManager().sub(later).getProgress());
    } finally {
      for (int id : List.of(first, wrongMode, later)) GameplayDefinitions.subs.remove(id);
    }
  }

  @Test
  void levelAndModeStayDistinctAndUnlockBirdsOnlyFromNormalCompletion() throws Exception {
    assertEquals(
        0, Gameplay46.number(finish(2, 1100, 300, 50, "hard", Map.of(5, 2, 10, 4)), "retcode"));
    assertNotEquals(0, Gameplay46.number(position(301, 1), "retcode"));
    assertEquals(
        0, Gameplay46.number(finish(1, 1100, 300, 80, "normal", Map.of(5, 3, 10, 5)), "retcode"));
    assertEquals(0, Gameplay46.number(position(301, 1), "retcode"));
    var levels = rows(query(), "MJNNNJPDKDI");
    assertEquals(
        List.of(1, 2), levels.stream().map(r -> Gameplay46.number(r, "KKHPKMAHDMK")).toList());
    assertTrue(levels.stream().allMatch(r -> Gameplay46.number(r, "level_id") == 1100));
    assertEquals(2, Gameplay46.number(bird(300), "count"));
    assertEquals(80, Gameplay46.number(bird(300), "LALGADHLMCB"));
  }

  @Test
  void retryIsIdempotentAndCannotChangeItsLevelBirdPowerOrStatistics() throws Exception {
    assertEquals(0, Gameplay46.number(finish(1, 1100, 300, 60, "same", Map.of(5, 2)), "retcode"));
    assertEquals(0, Gameplay46.number(finish(1, 1100, 300, 60, "same", Map.of(5, 2)), "retcode"));
    for (var r :
        List.of(
            finish(1, 1100, 300, 61, "same", Map.of(5, 2)),
            finish(2, 1100, 300, 60, "same", Map.of(5, 2)),
            finish(1, 1100, 301, 60, "same", Map.of(5, 2)),
            finish(1, 1100, 300, 60, "same", Map.of(5, 3))))
      assertNotEquals(0, Gameplay46.number(r, "retcode"));
    assertEquals(1, Gameplay46.number(bird(300), "count"));
    assertEquals(1, rows(query(), "MJNNNJPDKDI").size());
  }

  @Test
  void invalidResultsLeaveProgressAndRewardReceiptsEmpty() throws Exception {
    for (var r :
        List.of(
            finish(3, 1100, 300, 1, "mode", Map.of()),
            finish(1, 999, 300, 1, "level", Map.of()),
            finish(1, 1100, 301, 1, "bird", Map.of()),
            finish(1, 1100, 701, 1, "foreign", Map.of()),
            finish(1, 1100, 300, -1, "power", Map.of()),
            finish(1, 1100, 300, 1, "", Map.of()),
            finish(1, 1100, 300, 1, "bad\n", Map.of()),
            finish(1, 1100, 300, 1, "stat", Map.of(99, 1)),
            finish(1, 1100, 300, 1, "negative", Map.of(5, -1))))
      assertNotEquals(0, Gameplay46.number(r, "retcode"));
    assertTrue(rows(query(), "MJNNNJPDKDI").isEmpty());
    assertEquals(0, Gameplay46.number(bird(300), "count"));
    assertTrue(session.player.getGameplay().getRewards().isEmpty());
  }

  @Test
  void levelGateAndClosedWindowCannotBeBypassedByResultReports() throws Exception {
    session.player.getGameplay().getCancelledSubs().add(GATE);
    assertNotEquals(
        0, Gameplay46.number(finish(1, 1100, 300, 1, "cancelled", Map.of()), "retcode"));
    session.player.getGameplay().getCancelledSubs().remove(GATE);
    GameplayDefinitions.windows.remove(window);
    assertNotEquals(0, Gameplay46.number(finish(1, 1100, 300, 1, "closed", Map.of()), "retcode"));
    assertTrue(rows(query(), "MJNNNJPDKDI").isEmpty());
  }

  @Test
  void occupiedNestNeedsAnExplicitRemovalAndQueriesDoNotResetPlacements() throws Exception {
    assertEquals(0, Gameplay46.number(position(300, 2), "retcode"));
    assertNotEquals(0, Gameplay46.number(position(305, 2), "retcode"));
    assertEquals(2, Gameplay46.number(bird(300), "pos"));
    assertEquals(0, Gameplay46.number(position(300, 0), "retcode"));
    assertEquals(0, Gameplay46.number(position(305, 2), "retcode"));
    assertEquals(2, Gameplay46.number(bird(305), "pos"));
  }

  @Test
  void duplicateOrWideRawScalarsAndUnexpectedQueryFieldsFailBeforeMutation() throws Exception {
    var out = new java.io.ByteArrayOutputStream();
    var wire = com.google.protobuf.CodedOutputStream.newInstance(out);
    wire.writeUInt64(4, (1L << 32) + 300);
    wire.writeUInt32(10, 1);
    wire.flush();
    assertTrue(VersionedPacketHandler.handle(session, 7432, out.toByteArray()));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(session.response, "retcode"));
    assertTrue(
        VersionedPacketHandler.handle(
            session, 7432, new byte[] {32, (byte) 0xac, 2, 32, (byte) 0xac, 2, 80, 1}));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(session.response, "retcode"));
    assertTrue(VersionedPacketHandler.handle(session, 7411, new byte[] {8, 1}));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(session.response, "retcode"));
    assertEquals(0, Gameplay46.number(bird(300), "pos"));
  }

  @Test
  void nativeQueryUsesOnlyItsVersionRosterAndDoesNotInventFinishedLevels() throws Exception {
    var data = query();
    assertEquals(
        List.of(300, 301, 305, 311),
        rows(data, "DBINCLPHEFN").stream().map(r -> Gameplay46.number(r, "HBONKLEOEEI")).toList());
    assertTrue(rows(data, "MJNNNJPDKDI").isEmpty());
    assertTrue(
        rows(data, "DBINCLPHEFN").stream()
            .allMatch(
                r ->
                    Gameplay46.number(r, "count") == 0
                        && Gameplay46.number(r, "pos") == 0
                        && Gameplay46.number(r, "LALGADHLMCB") == 0));
    assertTrue(session.player.getGameplay().getRewards().isEmpty());
  }

  @Test
  void nativePlacementRoundTripsTheActualBirdAndLocation() throws Exception {
    assertEquals(0, Gameplay46.number(position(300, 6), "retcode"));
    assertEquals(6, Gameplay46.number(bird(300), "pos"));
    assertEquals(0, Gameplay46.number(bird(300), "count"));
    assertTrue(rows(query(), "MJNNNJPDKDI").isEmpty());
    assertEquals(0, Gameplay46.number(position(300, 0), "retcode"));
    assertEquals(0, Gameplay46.number(bird(300), "pos"));
  }

  @Test
  void lockedForeignUnknownAndOutOfRangePlacementCannotCreateBirds() throws Exception {
    for (int[] attempt :
        List.of(
            new int[] {301, 1},
            new int[] {701, 1},
            new int[] {999, 1},
            new int[] {300, 7},
            new int[] {300, -1}))
      assertNotEquals(0, Gameplay46.number(position(attempt[0], attempt[1]), "retcode"));
    assertEquals(0, Gameplay46.number(bird(300), "pos"));
    assertEquals(4, rows(query(), "DBINCLPHEFN").size());
  }

  @Test
  void closedActivityAndCancelledGateCannotMutateExistingPositions() throws Exception {
    assertEquals(0, Gameplay46.number(position(300, 2), "retcode"));
    GameplayDefinitions.windows.remove(window);
    assertNotEquals(0, Gameplay46.number(position(300, 3), "retcode"));
    assertEquals(2, Gameplay46.number(bird(300), "pos"));
    GameplayDefinitions.windows.add(window);
    session.player.getGameplay().getCancelledSubs().add(GATE);
    assertNotEquals(0, Gameplay46.number(position(300, 3), "retcode"));
    assertEquals(2, Gameplay46.number(bird(300), "pos"));
  }
}
