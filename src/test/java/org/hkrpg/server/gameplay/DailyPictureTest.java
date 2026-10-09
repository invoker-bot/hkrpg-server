package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class DailyPictureTest {
  final Gson gson = ResourceLoader.getGson();
  final List<Runnable> restorations = new ArrayList<>();
  final Map<Integer, JsonObject> rules = new HashMap<>();
  List<GameplayDefinitions.DailyPool> oldPool;
  int entry = 1000001, plane = 10000, floor = 10000000;
  boolean battle;
  Scene scene;
  final Player player =
      new Player() {
        final emu.lunarcore.game.player.lineup.LineupManager teams =
            new emu.lunarcore.game.player.lineup.LineupManager(this);

        public emu.lunarcore.game.player.lineup.LineupManager getLineupManager() {
          return teams;
        }

        public int getEntryId() {
          return entry;
        }

        public int getPlaneId() {
          return plane;
        }

        public int getFloorId() {
          return floor;
        }

        public boolean isInBattle() {
          return battle;
        }

        public Scene getScene() {
          return scene;
        }

        public void save() {}

        public void sendPacket(BasePacket packet) {}
      };

  class NativeSession extends GameSession {
    String version = "4.6.0";
    DynamicMessage response;

    NativeSession() {
      super(null);
      setState(SessionState.ACTIVE);
    }

    public Player getPlayer() {
      return player;
    }

    public ProtocolBridge getProtocol() {
      return ProtocolBridge.forVersion(version);
    }

    public void send(BasePacket packet) {}

    public void sendClientPacket(String name, byte[] bytes) {
      assertEquals("TakePictureScRsp", name);
      try {
        response = getProtocol().clientSchema().parse(name, bytes);
      } catch (Exception exception) {
        throw new AssertionError(exception);
      }
    }
  }

  <T extends GameResource> void install(ExcelMap<T> map, T row) {
    var old = map.get(row.getId());
    restorations.add(
        () -> {
          map.keySet().remove(row.getId());
          if (old != null) map.add(old);
        });
    map.add(row);
  }

  @BeforeEach
  void setup() throws Exception {
    oldPool = new ArrayList<>(GameplayDefinitions.dailyPool);
    GameplayDefinitions.dailyPool.clear();
    try (var in = getClass().getResourceAsStream("/fixtures/daily-picture-4.6.json")) {
      assertNotNull(in);
      for (var value :
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray()) {
        var record = value.getAsJsonObject();
        var row = record.getAsJsonObject("row");
        switch (record.get("kind").getAsString()) {
          case "pool" ->
              GameplayDefinitions.dailyPool.add(
                  gson.fromJson(row, GameplayDefinitions.DailyPool.class));
          case "quest" ->
              install(GameData.getQuestExcelMap(), gson.fromJson(row, QuestExcel.class));
          case "rule" -> {
            var r = gson.fromJson(row, FinishWayExcel.class);
            install(GameData.getFinishWayExcelMap(), r);
            rules.put(r.getId(), row.deepCopy());
          }
          case "reward" -> {
            var r = gson.fromJson(row, RewardExcel.class);
            r.onLoad();
            install(GameData.getRewardExcelMap(), r);
          }
          case "item" -> install(GameData.getItemExcelMap(), gson.fromJson(row, ItemExcel.class));
          case "entry" ->
              install(
                  GameData.getMapEntranceExcelMap(), gson.fromJson(row, MapEntranceExcel.class));
          case "plane" ->
              install(GameData.getMazePlaneExcelMap(), gson.fromJson(row, MazePlaneExcel.class));
        }
      }
    }
    scene = new Scene(player, GameData.getMazePlaneExcelMap().get(plane), floor);
    player
        .getDailyManager()
        .setClock(Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC));
    player.getDailyManager().ensureDay();
  }

  @AfterEach
  void cleanup() {
    GameplayDefinitions.dailyPool.clear();
    GameplayDefinitions.dailyPool.addAll(oldPool);
    Collections.reverse(restorations);
    restorations.forEach(Runnable::run);
  }

  QuestState state(int id) {
    return player.getGameplay().getDailyQuests().get(id);
  }

  void doing() {
    for (int id : List.of(2100129, 2100149)) {
      assertNotNull(state(id));
      assertEquals(0, state(id).getProgress());
      assertEquals(QuestState.Phase.DOING, state(id).getPhase());
    }
  }

  void finished() {
    for (int id : List.of(2100129, 2100149)) {
      assertNotNull(state(id));
      assertEquals(1, state(id).getProgress());
      assertEquals(QuestState.Phase.FINISHED, state(id).getPhase());
    }
  }

  Retcode take(byte[] bytes) throws Exception {
    var session = new NativeSession();
    assertTrue(VersionedPacketHandler.handle(session, 4160, bytes));
    assertNotNull(session.response);
    var field = session.response.getDescriptorForType().findFieldByName("retcode");
    assertEquals(13, field.getNumber());
    int value = (int) session.response.getField(field);
    return Arrays.stream(Retcode.values())
        .filter(code -> code.getVal() == value)
        .findFirst()
        .orElseThrow();
  }

  @ParameterizedTest
  @ValueSource(ints = {2100129, 2100149})
  void originalPictureObjectiveIsSelectedUnchanged(int id) {
    assertNotNull(state(id));
    assertEquals(0, state(id).getProgress());
    assertEquals(103002, GameData.getQuestExcelMap().get(id).getRewardID());
  }

  @Test
  void acceptedAuthenticatedEmptyPhotoReportCompletesBothOriginalObjectives() throws Exception {
    doing();
    assertEquals(Retcode.SUCC, take(new byte[0]));
    finished();
    assertEquals(0, player.getGameplay().getDailyPoints());
  }

  @Test
  void actualOriginalHundredPointRewardsCanEachBeClaimedOnlyOnce() throws Exception {
    assertEquals(Retcode.SUCC, take(new byte[0]));
    finished();
    for (int id : List.of(2100129, 2100149)) {
      assertEquals(Retcode.SUCC, player.getDailyManager().claimQuest(id).retcode());
      assertEquals(
          Retcode.QUEST_REWARD_ALREADY_TAKEN, player.getDailyManager().claimQuest(id).retcode());
    }
    assertEquals(200, player.getGameplay().getDailyPoints());
    assertNull(player.getInventory().getMaterialByItemId(23));
  }

  @Test
  void repeatedPhotoReportsDoNotCreateExtraProgressOrRewards() throws Exception {
    for (int i = 0; i < 10; i++) assertEquals(Retcode.SUCC, take(new byte[0]));
    finished();
    for (int id : List.of(2100129, 2100149))
      assertEquals(Retcode.SUCC, player.getDailyManager().claimQuest(id).retcode());
    assertEquals(Retcode.SUCC, take(new byte[0]));
    assertEquals(200, player.getGameplay().getDailyPoints());
    for (int id : List.of(2100129, 2100149))
      assertEquals(QuestState.Phase.CLOSED, state(id).getPhase());
  }

  @Test
  void queryingDailyOrQuestDataCannotBeMistakenForTakingAPicture() {
    player.getQuestManager().states();
    player.getDailyManager().ensureDay();
    doing();
  }

  @Test
  void clientCannotFinishTheDailyWithoutAnAcceptedPhotoReport() {
    for (int id : List.of(2100129, 2100149))
      assertEquals(Retcode.QUEST_STATUS_ERROR, player.getQuestManager().finishByClient(id));
    doing();
  }

  @ParameterizedTest
  @ValueSource(strings = {"00", "0801", "0a00", "80", "6801", "ffffffffffff"})
  void nonemptyOrMalformedPhotoBodiesDoNotAdvanceDailyProgress(String bytes) throws Exception {
    assertEquals(Retcode.REQ_PARA_INVALID, take(HexFormat.of().parseHex(bytes)));
    doing();
  }

  @ParameterizedTest
  @EnumSource(
      value = SessionState.class,
      names = {"INACTIVE", "WAITING_FOR_TOKEN", "WAITING_FOR_LOGIN", "PICKING_CHARACTER"})
  void unauthenticatedOrIncompleteLoginCannotReportAPhoto(SessionState state) throws Exception {
    var session = new NativeSession();
    session.setState(state);
    assertTrue(VersionedPacketHandler.handle(session, 4160, new byte[0]));
    assertNull(session.response);
    doing();
  }

  @Test
  void anotherCommandDoesNotBorrowThePhotoAction() throws Exception {
    var session = new NativeSession();
    VersionedPacketHandler.handle(session, 65535, new byte[0]);
    assertNull(session.response);
    doing();
  }

  @Test
  void legacySessionDoesNotRegisterANewLegacyOpcode() throws Exception {
    var session = new NativeSession();
    session.version = "4.2.0";
    assertFalse(VersionedPacketHandler.handle(session, 4160, new byte[0]));
    assertNull(session.response);
    doing();
  }

  @Test
  void activeCombatRejectsThePhotoReportWithoutProgress() throws Exception {
    battle = true;
    assertEquals(Retcode.IN_BATTLE_NOW, take(new byte[0]));
    doing();
  }

  @Test
  void missingSceneRejectsThePhotoReportWithoutProgress() throws Exception {
    scene = null;
    assertEquals(Retcode.SCENE_ENTRY_ID_NOT_MATCH, take(new byte[0]));
    doing();
  }

  @Test
  void anotherPlayersSceneCannotBeUsedToReportAPhoto() throws Exception {
    var foreign =
        new Player() {
          public emu.lunarcore.game.player.lineup.PlayerLineup getCurrentLineup() {
            return player.getCurrentLineup();
          }
        };
    scene = new Scene(foreign, GameData.getMazePlaneExcelMap().get(plane), floor);
    assertEquals(Retcode.SCENE_ENTRY_ID_NOT_MATCH, take(new byte[0]));
    doing();
  }

  @Test
  void staleSceneLocationCannotBeUsedToReportAPhoto() throws Exception {
    floor++;
    assertEquals(Retcode.SCENE_ENTRY_ID_NOT_MATCH, take(new byte[0]));
    doing();
  }

  @Test
  void missingOriginalEntranceRejectsThePhotoReport() throws Exception {
    GameData.getMapEntranceExcelMap().keySet().remove(entry);
    assertEquals(Retcode.SCENE_ENTRY_ID_NOT_MATCH, take(new byte[0]));
    doing();
  }

  @Test
  void missingOriginalPlaneRejectsThePhotoReport() throws Exception {
    GameData.getMazePlaneExcelMap().keySet().remove(plane);
    assertEquals(Retcode.SCENE_ENTRY_ID_NOT_MATCH, take(new byte[0]));
    doing();
  }

  @Test
  void hotReplacedPlaneCannotValidateTheOldScene() throws Exception {
    var old = GameData.getMazePlaneExcelMap().get(plane);
    GameData.getMazePlaneExcelMap().add(gson.fromJson(gson.toJson(old), MazePlaneExcel.class));
    assertEquals(Retcode.SCENE_ENTRY_ID_NOT_MATCH, take(new byte[0]));
    doing();
  }

  @Test
  void freshReportAfterFourAmCompletesOnlyTheNewDay() throws Exception {
    assertEquals(Retcode.SUCC, take(new byte[0]));
    finished();
    var old = state(2100129);
    player
        .getDailyManager()
        .setClock(Clock.fixed(Instant.parse("2026-10-05T20:00:00Z"), ZoneOffset.UTC));
    player.getDailyManager().ensureDay();
    doing();
    assertNotSame(old, state(2100129));
    assertEquals(Retcode.SUCC, take(new byte[0]));
    finished();
    assertEquals(1, old.getProgress());
  }

  @Test
  void concurrentPhotoReportsKeepOneCompletionAndOneRewardPerObjective() throws Exception {
    try (var workers = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<Callable<Retcode>>();
      for (int i = 0; i < 4; i++) tasks.add(() -> take(new byte[0]));
      for (var result : workers.invokeAll(tasks)) assertEquals(Retcode.SUCC, result.get());
    }
    finished();
    for (int id : List.of(2100129, 2100149))
      assertEquals(Retcode.SUCC, player.getDailyManager().claimQuest(id).retcode());
    assertEquals(200, player.getGameplay().getDailyPoints());
  }

  @Test
  void savedGameplayReloadKeepsPhotoProgressAndClaims() throws Exception {
    assertEquals(Retcode.SUCC, take(new byte[0]));
    for (int id : List.of(2100129, 2100149))
      assertEquals(Retcode.SUCC, player.getDailyManager().claimQuest(id).retcode());
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(player, gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class));
    assertEquals(Retcode.SUCC, take(new byte[0]));
    assertEquals(200, player.getGameplay().getDailyPoints());
    for (int id : List.of(2100129, 2100149))
      assertEquals(
          Retcode.QUEST_REWARD_ALREADY_TAKEN, player.getDailyManager().claimQuest(id).retcode());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ParamInt1",
        "ParamInt2",
        "ParamInt3",
        "ParamStr1",
        "ParamIntList",
        "ParamItemList",
        "ParamType",
        "Progress",
        "IsBackTrack"
      })
  void unknownPictureConditionShapesAreExcluded(String field) {
    for (int id : List.of(2100129, 2100149)) {
      var row = rules.get(id).deepCopy();
      switch (field) {
        case "ParamStr1", "ParamType" -> row.addProperty(field, "unknown");
        case "ParamIntList", "ParamItemList" -> row.add(field, JsonParser.parseString("[1]"));
        case "IsBackTrack" -> row.addProperty(field, true);
        default -> row.addProperty(field, 2);
      }
      GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
    }
    player.getGameplay().resetDaily(player.getGameplay().getDailyDay() - 1);
    player.getDailyManager().ensureDay();
    assertNull(state(2100129));
    assertNull(state(2100149));
  }

  @Test
  void resourceChangedAfterSelectionCannotBorrowTheOriginalPhotoGoal() throws Exception {
    var row = rules.get(2100129).deepCopy();
    row.addProperty("Progress", 9);
    GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
    assertEquals(Retcode.SUCC, take(new byte[0]));
    assertEquals(0, state(2100129).getProgress());
    assertEquals(1, state(2100149).getProgress());
  }
}
