package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.challenge.*;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import us.hebi.quickbuf.RepeatedInt;

/** Complete original Normal-mode rows, native admission, half buffs and cumulative callbacks. */
class StoryChallenge46Test {
  final Gson gson = new Gson();
  final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  final MissionChallengeWinTest fixture = new MissionChallengeWinTest();
  @TempDir Path directory;
  Map<String, JsonArray> source = new HashMap<>();
  ChallengeExcel oldChallenge;
  MapEntranceExcel oldEntry;
  List<MazeBuffExcel> oldBuffs;
  StoryPlayer player;

  static class Session extends GameSession {
    final StoryPlayer player;
    final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
    DynamicMessage response;

    Session(StoryPlayer player) {
      super(null);
      this.player = player;
      setState(SessionState.ACTIVE);
    }

    public ProtocolBridge getProtocol() {
      return protocol;
    }

    public emu.lunarcore.game.player.Player getPlayer() {
      return player;
    }

    public void sendClientPacket(String name, byte[] bytes) {
      try {
        response = protocol.clientSchema().parse(name, bytes);
      } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
    }

    public void send(int command) {}

    public void send(BasePacket packet) {}
  }

  static class StoryPlayer extends MissionChallengeWinTest.Challenger {
    @dev.morphia.annotations.Transient final Session nativeSession = new Session(this);
    boolean began;
    int firstBuff, secondBuff, error = -1;
    List<Integer> first, second;

    public GameSession getSession() {
      return nativeSession;
    }

    public emu.lunarcore.game.avatar.GameAvatar getAvatarById(int id) {
      return id == 8001 || id == 1001 || id == 199961 ? avatar : null;
    }

    public void sendPacket(BasePacket packet) {
      if (packet.getCmdId() == CmdId.StartChallengeScRsp)
        error =
            ((emu.lunarcore.proto.StartChallengeScRspOuterClass.StartChallengeScRsp)
                    packet.getData())
                .getRetcode();
    }

    @dev.morphia.annotations.Transient
    final ChallengeManager admission =
        new ChallengeManager(this) {
          public void startChallenge(
              int id, RepeatedInt one, RepeatedInt two, int buff1, int buff2) {
            began = true;
            firstBuff = buff1;
            secondBuff = buff2;
            first = new ArrayList<>();
            one.forEach(first::add);
            second = new ArrayList<>();
            two.forEach(second::add);
          }

          public synchronized void addHistory(int id, int stars, int score, boolean extra) {}
        };

    public ChallengeManager getChallengeManager() {
      return admission;
    }
  }

  @BeforeEach
  void setup() throws Exception {
    fixture.resources();
    oldChallenge = GameData.getChallengeExcelMap().get(20011);
    oldEntry = GameData.getMapEntranceExcelMap().get(3000205);
    oldBuffs = List.copyOf(GameData.getMazeBuffExcelMap().values());
    try (var in = getClass().getResourceAsStream("/fixtures/story-challenge-4.6.json")) {
      assertNotNull(in);
      var data =
          JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
      assertEquals(
          "724b139d8c9c32d12552eb95745a4fee72bfe48b", data.get("sourceCommit").getAsString());
      for (var raw : data.getAsJsonArray("inputs")) {
        var input = raw.getAsJsonObject();
        source.put(input.get("path").getAsString(), input.getAsJsonArray("rows").deepCopy());
      }
    }
    reloadCatalog();
    var excel =
        gson.fromJson(
            source.get("ExcelOutput/ChallengeStoryMazeConfig.json").get(0), ChallengeExcel.class);
    excel.onLoad();
    excel.setStoryExcel(
        gson.fromJson(
            source.get("ExcelOutput/ChallengeStoryMazeExtra.json").get(0),
            ChallengeStoryExtraExcel.class));
    GameData.getChallengeExcelMap().add(excel);
    source
        .get("ExcelOutput/MazeBuff.json")
        .forEach(
            row -> GameData.getMazeBuffExcelMap().add(gson.fromJson(row, MazeBuffExcel.class)));
    // Admission-only entrance and roster are explicit fixtures; native smoke uses original ones.
    GameData.getMapEntranceExcelMap()
        .add(
            gson.fromJson(
                "{\"ID\":3000205,\"PlaneID\":10000,\"FloorID\":10000000}", MapEntranceExcel.class));
    player = new StoryPlayer();
    assertTrue(StoryChallengeCatalog.supports(excel));
  }

  void reloadCatalog() throws Exception {
    Files.createDirectories(directory.resolve("ExcelOutput"));
    for (var entry : source.entrySet())
      Files.writeString(directory.resolve(entry.getKey()), entry.getValue().toString());
    StoryChallengeCatalog.load(directory);
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.cleanup();
    GameData.getChallengeExcelMap().keySet().remove(20011);
    if (oldChallenge != null) GameData.getChallengeExcelMap().add(oldChallenge);
    GameData.getMapEntranceExcelMap().keySet().remove(3000205);
    if (oldEntry != null) GameData.getMapEntranceExcelMap().add(oldEntry);
    GameData.getMazeBuffExcelMap().values().clear();
    oldBuffs.forEach(GameData.getMazeBuffExcelMap()::add);
    StoryChallengeCatalog.load(directory.resolve("empty"));
  }

  static void set(DynamicMessage.Builder b, String key, Object value) {
    b.setField(b.getDescriptorForType().findFieldByName(key), value);
  }

  DynamicMessage.Builder message(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  byte[] request(int firstBuff, int secondBuff) {
    var b = message("StartChallengeCsReq");
    set(b, "challenge_id", 20011);
    b.addRepeatedField(b.getDescriptorForType().findFieldByName("first_lineup"), 8001);
    b.addRepeatedField(b.getDescriptorForType().findFieldByName("second_lineup"), 1001);
    var buffs = message("ChallengeStoryBuffInfo");
    set(buffs, "buff_one", firstBuff);
    set(buffs, "buff_two", secondBuff);
    var info = message("ChallengeBuffInfo");
    set(info, "story_info", buffs.build());
    set(b, "stage_info", info.build());
    return b.build().toByteArray();
  }

  void invoke(byte[] bytes) {
    try {
      assertTrue(
          VersionedPacketHandler.handle(
              player.nativeSession, protocol.clientCommand("StartChallengeCsReq"), bytes));
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {3031301, 3031302, 3031303})
  void originalGroupChoicesReachTheirOwnHalves(int first) {
    invoke(request(first, 3031302));
    assertTrue(player.began);
    assertEquals(first, player.firstBuff);
    assertEquals(3031302, player.secondBuff);
    assertEquals(List.of(8001), player.first);
    assertEquals(List.of(1001), player.second);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "foreign",
        "missingFirst",
        "missingSecond",
        "wrapped",
        "duplicate",
        "unknown",
        "boss",
        "duplicateStage"
      })
  void invalidNativeChoiceNeverStartsOrChangesFormalRoster(String mode) throws Exception {
    byte[] bytes =
        request(
            mode.equals("foreign") ? 3031304 : mode.equals("missingFirst") ? 0 : 3031301,
            mode.equals("missingSecond") ? 0 : 3031302);
    if (List.of("wrapped", "duplicate", "unknown", "boss", "duplicateStage").contains(mode)) {
      var base = message("StartChallengeCsReq");
      set(base, "challenge_id", 20011);
      base.addRepeatedField(base.getDescriptorForType().findFieldByName("first_lineup"), 8001);
      base.addRepeatedField(base.getDescriptorForType().findFieldByName("second_lineup"), 1001);
      var buffBytes = new java.io.ByteArrayOutputStream();
      var out = CodedOutputStream.newInstance(buffBytes);
      out.writeUInt64(7, mode.equals("wrapped") ? (1L << 32) + 3031301 : 3031301);
      out.writeUInt32(13, 3031302);
      if (mode.equals("duplicate")) out.writeUInt32(7, 3031301);
      if (mode.equals("unknown")) out.writeUInt32(15, 1);
      out.flush();
      var infoBytes = new java.io.ByteArrayOutputStream();
      out = CodedOutputStream.newInstance(infoBytes);
      out.writeByteArray(mode.equals("boss") ? 5 : 13, buffBytes.toByteArray());
      out.flush();
      var body = new java.io.ByteArrayOutputStream();
      body.write(base.build().toByteArray());
      out = CodedOutputStream.newInstance(body);
      out.writeByteArray(14, infoBytes.toByteArray());
      if (mode.equals("duplicateStage")) out.writeByteArray(14, infoBytes.toByteArray());
      out.flush();
      bytes = body.toByteArray();
    }
    var before = gson.toJsonTree(player.getGameplay());
    invoke(bytes);
    assertFalse(player.began);
    assertEquals(before, gson.toJsonTree(player.getGameplay()));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), player.error);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Fever",
        "unknownType",
        "extraSubBuff",
        "wrongEntry2",
        "missingBuff",
        "wrongBuffID",
        "wrongBuffLevel"
      })
  void unsupportedModeOrBrokenBattleResourceCannotAdmitAStoryRun(String mode) throws Exception {
    var extra = source.get("ExcelOutput/ChallengeStoryGroupExtra.json").get(0).getAsJsonObject();
    if (mode.equals("Fever") || mode.equals("unknownType")) extra.addProperty("StoryType", mode);
    else if (mode.equals("extraSubBuff")) extra.getAsJsonArray("SubMazeBuffList").add(3031303);
    else if (mode.equals("wrongEntry2"))
      source
          .get("ExcelOutput/ChallengeStoryMazeConfig.json")
          .get(0)
          .getAsJsonObject()
          .addProperty("MapEntranceID2", 3000206);
    else {
      var row = GameData.getMazeBuffExcelMap().get(3031301, 1);
      if (mode.equals("missingBuff"))
        GameData.getMazeBuffExcelMap().values().removeIf(x -> x == row);
      else {
        var f = MazeBuffExcel.class.getDeclaredField(mode.equals("wrongBuffID") ? "ID" : "Lv");
        f.setAccessible(true);
        f.setInt(row, mode.equals("wrongBuffID") ? 3031304 : 2);
      }
    }
    reloadCatalog();
    invoke(request(3031301, 3031302));
    assertFalse(player.began);
    assertNotEquals(Retcode.SUCC.getVal(), player.error);
  }

  ChallengeInstance instance() {
    var p = fixture.player(false);
    player.setInstance(new ChallengeInstance(player, GameData.getChallengeExcelMap().get(20011)));
    player.scene = p.scene;
    player.getLineupManager().setCurrentExtraLineup(1, false);
    player.getCurrentLineup().getAvatars().add(199961);
    player.getLineupManager().getExtraLineupByType(3).getAvatars().add(199961);
    var result = (ChallengeInstance) player.getInstance();
    result.addBuff(3031301);
    result.addBuff(3031302);
    return result;
  }

  Battle battle() {
    var b =
        new Battle(
            player,
            player.getCurrentLineup(),
            GameData.getStageExcelMap().get(MissionChallengeWinTest.STAGE));
    player.setBattle(b);
    return b;
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void nativeCurrentStateCarriesBothHalfChoicesAndCurrentBattleGetsOnlyItsOwnChoice(int half)
      throws Exception {
    var instance = instance();
    var field = ChallengeInstance.class.getDeclaredField("currentStage");
    field.setAccessible(true);
    field.setInt(instance, half);
    var b = battle();
    instance.onBattleStart(b);
    assertTrue(b.getBuffs().containsKey(3031001));
    assertTrue(b.getBuffs().containsKey(half == 1 ? 3031301 : 3031302));
    assertFalse(b.getBuffs().containsKey(half == 1 ? 3031302 : 3031301));
    assertEquals(10003, b.getBattleTargets().get(1).getBattleTargetList().get(0).getId());
    var core =
        emu.lunarcore.proto.GetCurChallengeScRspOuterClass.GetCurChallengeScRsp.newInstance()
            .setChallengeInfo(instance.toProto());
    var nativeResponse =
        protocol
            .clientSchema()
            .parse(
                "GetCurChallengeScRsp",
                protocol.translate("GetCurChallengeScRsp", core.toByteArray(), true));
    var current =
        (DynamicMessage)
            nativeResponse.getField(
                nativeResponse.getDescriptorForType().findFieldByName("cur_challenge"));
    var stage =
        (DynamicMessage)
            current.getField(current.getDescriptorForType().findFieldByName("stage_info"));
    var buffs =
        (DynamicMessage)
            stage.getField(stage.getDescriptorForType().findFieldByName("cur_story_buffs"));
    assertEquals(
        List.of(3031301, 3031302),
        buffs.getField(buffs.getDescriptorForType().findFieldByName("buff_list")));
  }

  @Test
  void nativeHistoryIncludesOriginalStoryChallengeAndStoredScore() {
    var history = new ChallengeHistory(player, 20011);
    history.setStars(1);
    history.setScore(40000);
    player.admission.getHistory().put(20011, history);
    try {
      assertTrue(
          VersionedPacketHandler.handle(
              player.nativeSession, protocol.clientCommand("GetChallengeCsReq"), new byte[0]));
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    var response = player.nativeSession.response;
    var rows =
        (List<?>)
            response.getField(response.getDescriptorForType().findFieldByName("challenge_list"));
    var matching =
        rows.stream()
            .map(DynamicMessage.class::cast)
            .filter(
                x ->
                    (Integer) x.getField(x.getDescriptorForType().findFieldByName("challenge_id"))
                        == 20011)
            .toList();
    assertEquals(1, matching.size());
    var row = matching.getFirst();
    assertEquals(40000, row.getField(row.getDescriptorForType().findFieldByName("score_id")));
    assertEquals(1, row.getField(row.getDescriptorForType().findFieldByName("star")));
  }

  @Test
  void actualFinishedStoryFreezesBothScoresInOneHistorySaveAndReturnsThemAfterReload()
      throws Exception {
    var challenge = instance();
    challenge.setScoreStage1(12000);
    challenge.setScoreStage2(28000);
    var status = ChallengeInstance.class.getDeclaredField("status");
    status.setAccessible(true);
    status.setInt(challenge, 2);
    var history = new SavedHistory(player, 20011);
    var manager = new ChallengeManager(player);
    manager.getHistory().put(20011, history);
    manager.addHistory(20011, 1, 40000, false);
    assertEquals(1, history.saves);
    assertNotNull(history.saved.get("storyScoreOne"));
    assertEquals(12000, history.saved.get("storyScoreOne").getAsInt());
    assertEquals(28000, history.saved.get("storyScoreTwo").getAsInt());
    assertTrue(history.saved.get("storyScoresRecorded").getAsBoolean());
    player.admission.getHistory().put(20011, gson.fromJson(history.saved, ChallengeHistory.class));
    assertTrue(
        VersionedPacketHandler.handle(
            player.nativeSession, protocol.clientCommand("GetChallengeCsReq"), new byte[0]));
    var response = player.nativeSession.response;
    var rows =
        (List<?>)
            response.getField(response.getDescriptorForType().findFieldByName("challenge_list"));
    var matching =
        rows.stream()
            .map(DynamicMessage.class::cast)
            .filter(
                x ->
                    (Integer) x.getField(x.getDescriptorForType().findFieldByName("challenge_id"))
                        == 20011)
            .toList();
    assertEquals(1, matching.size());
    var row = matching.getFirst();
    assertEquals(12000, row.getField(row.getDescriptorForType().findFieldByName("score_id")));
    assertEquals(28000, row.getField(row.getDescriptorForType().findFieldByName("score_two")));
  }

  static class SavedHistory extends ChallengeHistory {
    @dev.morphia.annotations.Transient int saves;
    @dev.morphia.annotations.Transient JsonObject saved;

    public SavedHistory() {
      super();
    }

    SavedHistory(emu.lunarcore.game.player.Player player, int id) {
      super(player, id);
    }

    public void save() {
      saves++;
      saved = new Gson().toJsonTree(this).getAsJsonObject();
    }
  }

  @Test
  void nativeStartPacketCarriesStoryBuffsWithoutAnEmptyBossOneof() throws Exception {
    instance();
    var packet =
        new emu.lunarcore.server.packet.send.PacketStartChallengeScRsp(
            player, 20011, RepeatedInt.newEmptyInstance(), RepeatedInt.newEmptyInstance());
    assertDoesNotThrow(
        () -> protocol.translate("StartChallengeScRsp", packet.getData().toByteArray(), true));
  }

  @Test
  void cumulativeFirstHalfDoesNotDiscardAnEarlierEncounterAndQuitDoesNotCommitScore() {
    var instance = instance();
    fixture.monster(player, 6, 200001);
    instance.onBattleFinish(
        battle(),
        BattleEndStatus.BATTLE_END_WIN,
        BattleStatistics.newInstance().setChallengeScore(1000));
    instance.onBattleFinish(
        battle(),
        BattleEndStatus.BATTLE_END_WIN,
        BattleStatistics.newInstance().setChallengeScore(2500));
    assertEquals(2500, instance.getScoreStage1());
    instance.onBattleFinish(
        battle(),
        BattleEndStatus.BATTLE_END_QUIT,
        BattleStatistics.newInstance().setChallengeScore(9999));
    assertEquals(2500, instance.getScoreStage1());
  }

  @Test
  void secondHalfStoresItsOwnCumulativeScoreInsteadOfSubtractingItsEarlierEncounterTwice()
      throws Exception {
    var instance = instance();
    instance.setScoreStage1(12000);
    var f = ChallengeInstance.class.getDeclaredField("currentStage");
    f.setAccessible(true);
    f.setInt(instance, 2);
    fixture.monster(player, 7, 200001);
    instance.onBattleFinish(
        battle(),
        BattleEndStatus.BATTLE_END_WIN,
        BattleStatistics.newInstance().setChallengeScore(17000));
    instance.onBattleFinish(
        battle(),
        BattleEndStatus.BATTLE_END_WIN,
        BattleStatistics.newInstance().setChallengeScore(20000));
    assertEquals(8000, instance.getScoreStage2());
    assertEquals(20000, instance.getTotalScore());
  }

  @Test
  void backwardsScoreReportPreservesBattleForAValidRetry() {
    var instance = instance();
    instance.setScoreStage1(12000);
    fixture.monster(player, 6, 200001);
    var b = battle();
    assertNull(
        fixture.service.finishBattle(
            player,
            b.getId(),
            b.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance().setChallengeScore(11999)));
    assertSame(b, player.getBattle());
    assertFalse(b.isSettling());
    assertEquals(12000, instance.getTotalScore());
    assertNotNull(
        fixture.service.finishBattle(
            player,
            b.getId(),
            b.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance().setChallengeScore(13000)));
    assertEquals(13000, instance.getTotalScore());
  }

  @Test
  void turnLimitRemovesExpiredHalfEncounterWithoutPublishingKillCredit() {
    var instance = instance();
    var old = fixture.monster(player, 6, 200001);
    var b = battle();
    instance.onBattleFinish(
        b,
        BattleEndStatus.BATTLE_END_LOSE,
        BattleStatistics.newInstance()
            .setChallengeScore(1000)
            .setEndReason(
                emu.lunarcore.proto.BattleEndReasonOuterClass.BattleEndReason
                    .BATTLE_END_REASON_TURN_LIMIT));
    assertFalse(player.scene.getEntities().containsValue(old));
    assertEquals(2, instance.getCurrentStage());
    assertEquals(1000, instance.getScoreStage1());
  }
}
