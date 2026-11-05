package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Original room/group/props; roster, isolated floor and completed tutorial are explicit fixtures.
 */
class RoguePropQuestTest {
  static final String FLOOR = "P80001_F80001003";

  static class Explorer extends UniverseLifecycleTest.Explorer {
    Scene scene;

    public Scene getScene() {
      return scene;
    }

    public int getEntryId() {
      return 8000103;
    }

    public int getPlaneId() {
      return 80001;
    }

    public int getFloorId() {
      return 80001003;
    }
  }

  Explorer player = new Explorer();
  final Gson gson = ResourceLoader.getGson();
  final List<QuestExcel> oldQuests = new ArrayList<>();
  final List<FinishWayExcel> oldRules = new ArrayList<>();
  final List<RewardExcel> oldRewards = new ArrayList<>();
  final List<MainMissionExcel> oldMains = new ArrayList<>();
  final Map<Integer, PropExcel> oldProps = new HashMap<>();
  final Map<Integer, JsonObject> rules = new HashMap<>();
  ItemExcel oldItem;
  MapEntranceExcel oldEntry;
  FloorInfo oldFloor;
  GameplayDefinitions.Room oldRoom;
  MazePlaneExcel plane;
  FloorInfo floor;
  GroupInfo group;

  @BeforeEach
  void setup() throws Exception {
    oldQuests.addAll(GameData.getQuestExcelMap().values());
    oldRules.addAll(GameData.getFinishWayExcelMap().values());
    oldRewards.addAll(GameData.getRewardExcelMap().values());
    oldMains.addAll(GameData.getMainMissionExcelMap().values());
    oldItem = GameData.getItemExcelMap().get(1);
    oldEntry = GameData.getMapEntranceExcelMap().get(8000103);
    oldFloor = GameData.getFloorInfos().get(FLOOR);
    oldRoom = GameplayDefinitions.rooms.get(305);
    GameData.getQuestExcelMap().values().clear();
    GameData.getFinishWayExcelMap().values().clear();
    GameData.getRewardExcelMap().values().clear();
    GameData.getMainMissionExcelMap().values().clear();
    try (var in = getClass().getResourceAsStream("/fixtures/rogue-prop-quests-4.6.json")) {
      var rows =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonArray();
      assertEquals(18, rows.size());
      for (var raw : rows) {
        var spec = raw.getAsJsonObject();
        var row = spec.getAsJsonObject("row").deepCopy();
        switch (spec.get("kind").getAsString()) {
          case "quest" -> GameData.getQuestExcelMap().add(gson.fromJson(row, QuestExcel.class));
          case "rule" -> {
            var r = gson.fromJson(row, FinishWayExcel.class);
            GameData.getFinishWayExcelMap().add(r);
            rules.put(r.getId(), row);
          }
          case "reward" -> {
            var r = gson.fromJson(row, RewardExcel.class);
            r.onLoad();
            GameData.getRewardExcelMap().add(r);
          }
          case "main" ->
              GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
          case "item" -> {
            row.addProperty("ItemName", row.getAsJsonObject("ItemName").get("Hash").getAsLong());
            GameData.getItemExcelMap().add(gson.fromJson(row, ItemExcel.class));
          }
          case "room" -> {
            var r = gson.fromJson(row, GameplayDefinitions.Room.class);
            GameplayDefinitions.rooms.put(r.RogueRoomID(), r);
          }
          case "entry" ->
              GameData.getMapEntranceExcelMap().add(gson.fromJson(row, MapEntranceExcel.class));
          case "plane" -> {
            row.addProperty("PlaneName", row.getAsJsonObject("PlaneName").get("Hash").getAsLong());
            plane = gson.fromJson(row, MazePlaneExcel.class);
          }
          case "floor" -> floor = gson.fromJson(row, FloorInfo.class);
          case "group" -> {
            group = gson.fromJson(row, GroupInfo.class);
            group.setId(21);
          }
          case "prop" -> {
            row.addProperty("PropName", row.getAsJsonObject("PropName").get("Hash").getAsLong());
            var r = gson.fromJson(row, PropExcel.class);
            oldProps.put(r.getId(), GameData.getPropExcelMap().get(r.getId()));
            r.onLoad();
            GameData.getPropExcelMap().add(r);
          }
          case "achievement" -> {}
          default -> fail(spec.toString());
        }
      }
    }
    floor.getGroups().put(21, group);
    GameData.getFloorInfos().put(FLOOR, floor);
    assertTrue(player.setNickname("PropGoals"));
    var tutorial = new QuestState(1000201);
    assertTrue(tutorial.advance(1, 1, 1));
    player.getGameplay().getMainMissions().put(1000201, tutorial);
    assertTrue(player.getQuestManager().onMissionProgress());
    assertEquals(QuestState.Phase.FINISHED, player.getQuestStates().get(4000001).getPhase());
    begin();
  }

  @AfterEach
  void restore() {
    GameData.getQuestExcelMap().values().clear();
    oldQuests.forEach(GameData.getQuestExcelMap()::add);
    GameData.getFinishWayExcelMap().values().clear();
    oldRules.forEach(GameData.getFinishWayExcelMap()::add);
    GameData.getRewardExcelMap().values().clear();
    oldRewards.forEach(GameData.getRewardExcelMap()::add);
    GameData.getMainMissionExcelMap().values().clear();
    oldMains.forEach(GameData.getMainMissionExcelMap()::add);
    GameData.getItemExcelMap().keySet().remove(1);
    if (oldItem != null) GameData.getItemExcelMap().add(oldItem);
    GameData.getMapEntranceExcelMap().keySet().remove(8000103);
    if (oldEntry != null) GameData.getMapEntranceExcelMap().add(oldEntry);
    if (oldFloor == null) GameData.getFloorInfos().remove(FLOOR);
    else GameData.getFloorInfos().put(FLOOR, oldFloor);
    GameplayDefinitions.rooms.remove(305);
    if (oldRoom != null) GameplayDefinitions.rooms.put(305, oldRoom);
    for (var e : oldProps.entrySet()) {
      GameData.getPropExcelMap().keySet().remove(e.getKey());
      if (e.getValue() != null) GameData.getPropExcelMap().add(e.getValue());
    }
  }

  void begin() {
    var run = new GameplayState.RogueRun(100, 199956, Map.of(1, 305));
    run.setupTeam(player, List.of(199961));
    player.getGameplay().setRogue(run);
    run.enter(1, 305);
    run.setInside(true);
    reload();
  }

  void reload() {
    player.scene = new Scene(player, plane, 80001003);
    player.scene.setEntryId(8000103);
  }

  EntityProp prop(int instance) {
    return player.scene.getEntities().values().stream()
        .filter(EntityProp.class::isInstance)
        .map(EntityProp.class::cast)
        .filter(p -> p.getInstId() == instance)
        .findFirst()
        .orElseThrow();
  }

  boolean hasProp(int instance) {
    return player.scene.getEntities().values().stream()
        .filter(EntityProp.class::isInstance)
        .map(EntityProp.class::cast)
        .anyMatch(p -> p.getInstId() == instance);
  }

  void destroy(int count) {
    for (int i = 0; i < count; i++) {
      if (i > 0 && i % 2 == 0) begin();
      assertTrue(player.scene.destroyProp(prop(300007 + i % 2)));
    }
  }

  QuestState state(int id) {
    var rows =
        player.getQuestManager().states().stream().filter(s -> s.getQuestId() == id).toList();
    assertEquals(1, rows.size());
    return rows.getFirst();
  }

  void held(int id) {
    assertTrue(player.getQuestManager().states().stream().noneMatch(s -> s.getQuestId() == id));
    assertNotEquals(Retcode.SUCC, player.getQuestManager().claim(id).retcode());
    assertEquals(0, player.getHcoin());
  }

  @Test
  void actualOriginalPropDestructionImmediatelyAdvancesTheTwentyGoal() {
    destroy(1);
    assertEquals(1, player.getQuestStates().get(4092312).getProgress());
  }

  @Test
  void twentyActualDestructionsPayTheOriginalFiveJadesOnce() {
    destroy(20);
    assertEquals(20, state(4092312).getProgress());
    assertEquals(Retcode.SUCC, player.getQuestManager().claim(4092312).retcode());
    assertEquals(5, player.getHcoin());
    assertNotEquals(Retcode.SUCC, player.getQuestManager().claim(4092312).retcode());
  }

  @Test
  void nineteenActualDestructionsCannotFinishOrPayTheTwentyGoal() {
    destroy(19);
    assertEquals(19, state(4092312).getProgress());
    assertEquals(QuestState.Phase.DOING, state(4092312).getPhase());
    assertEquals(Retcode.QUEST_NOT_FINISH, player.getQuestManager().claim(4092312).retcode());
  }

  @Test
  void fourHundredActualDestructionsPayBothOriginalGoalsForFifteenJades() {
    destroy(400);
    assertEquals(400, state(4092313).getProgress());
    for (int id : List.of(4092312, 4092313))
      assertEquals(Retcode.SUCC, player.getQuestManager().claim(id).retcode());
    assertEquals(15, player.getHcoin());
  }

  @Test
  void threeHundredNinetyNineActualDestructionsCannotFinishTheLargerGoal() {
    destroy(399);
    assertEquals(399, state(4092313).getProgress());
    assertEquals(QuestState.Phase.DOING, state(4092313).getPhase());
    assertEquals(Retcode.QUEST_NOT_FINISH, player.getQuestManager().claim(4092313).retcode());
  }

  @Test
  void allTwoOriginalNonRecoveryBoxesCountAsActualDestructions() {
    destroy(2);
    assertEquals(2, state(4092312).getProgress());
  }

  @Test
  void sceneReloadCannotRespawnAndRecountTheSameActualProp() {
    destroy(1);
    reload();
    assertFalse(hasProp(300007));
    assertEquals(1, state(4092312).getProgress());
  }

  @Test
  void allTwoConsumedPropsStayAbsentAfterReload() {
    destroy(2);
    reload();
    for (int id : List.of(300007, 300008)) assertFalse(hasProp(id));
    assertEquals(2, state(4092312).getProgress());
  }

  @Test
  void staleEntityCannotCreditAnotherDestruction() {
    var prop = prop(300007);
    assertTrue(player.scene.destroyProp(prop));
    assertFalse(player.scene.destroyProp(prop));
    assertEquals(1, state(4092312).getProgress());
  }

  @Test
  void bareSceneRemovalDoesNotCountAsAnAcceptedDestruction() {
    player.scene.removeEntity(prop(300007));
    assertEquals(0, state(4092312).getProgress());
  }

  @Test
  void manuallyFinishedGoalWithoutActualHistoryIsHeld() {
    assertTrue(player.getQuestStates().get(4092312).advance(20, 20, 1));
    held(4092312);
  }

  @Test
  void missingTutorialCannotAcceptOrClaimTheOriginalGoal() {
    player.getQuestStates().remove(4000001);
    player.getQuestStates().remove(4092312);
    player.getQuestStates().remove(4092313);
    destroy(1);
    held(4092312);
    held(4092313);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5})
  void unsupportedOriginalConditionShapesCannotPublishOrPay(int mode) {
    var row = rules.get(4092312).deepCopy();
    switch (mode) {
      case 0 -> row.addProperty("ParamType", "NoPara");
      case 1 -> row.addProperty("ParamInt1", 1);
      case 2 -> row.addProperty("ParamStr1", "unknown");
      case 3 -> row.getAsJsonArray("ParamIntList").add(1);
      case 4 -> row.getAsJsonArray("ParamItemList").add(new JsonObject());
      case 5 -> row.addProperty("Progress", Integer.MAX_VALUE);
      default -> fail();
    }
    GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
    held(4092312);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void damagedHistoryPreventsRemovalAndHoldsTheGoal(boolean absent) throws Exception {
    if (absent) {
      var f = GameplayState.class.getDeclaredField("rogueDestructions");
      f.setAccessible(true);
      f.set(player.getGameplay(), null);
    } else {
      var f = RogueDestructionState.class.getDeclaredField("count");
      f.setAccessible(true);
      f.setInt(player.getGameplay().getRogueDestructions(), -1);
    }
    assertFalse(player.scene.destroyProp(prop(300007)));
    assertTrue(hasProp(300007));
    assertTrue(player.getGameplay().getRogue().getDestroyedDestructibleProps().isEmpty());
    held(4092312);
  }

  @Test
  void exhaustedHistoryCapacityIsRejectedBeforePhysicalRemoval() throws Exception {
    var f = RogueDestructionState.class.getDeclaredField("count");
    f.setAccessible(true);
    f.setInt(player.getGameplay().getRogueDestructions(), Integer.MAX_VALUE);
    assertFalse(player.scene.destroyProp(prop(300007)));
    assertTrue(hasProp(300007));
    assertTrue(player.getGameplay().getRogue().getDestroyedDestructibleProps().isEmpty());
    assertEquals(Integer.MAX_VALUE, player.getGameplay().getRogueDestructions().total());
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "leadingZero", "badIdentity", "capacity"})
  void damagedOrExhaustedReceiptsCannotRemoveOrCreditAnotherProp(String shape) throws Exception {
    var receipts = player.getGameplay().getRogue().getDestroyedDestructibleProps();
    switch (shape) {
      case "null" -> {
        var f = GameplayState.RogueRun.class.getDeclaredField("destroyedDestructibleProps");
        f.setAccessible(true);
        f.set(player.getGameplay().getRogue(), null);
      }
      case "leadingZero" -> receipts.add("01:305:80001:80001003:21:300007");
      case "badIdentity" -> receipts.add("1:305:80001:80001003:21:0");
      case "capacity" -> {
        for (int i = 1; i <= 4096; i++) receipts.add(i + ":305:80001:80001003:21:1");
      }
      default -> fail();
    }
    assertFalse(player.scene.destroyProp(prop(300007)));
    assertTrue(hasProp(300007));
    assertEquals(0, player.getGameplay().getRogueDestructions().total());
  }

  @Test
  void aPreparedDestructionCannotCommitWhileItsSourceEntityIsStillAlive() {
    var plan = RoguePropDestruction.prepare(player, prop(300007));
    assertNotNull(plan);
    assertThrows(IllegalStateException.class, plan::complete);
    assertTrue(hasProp(300007));
    assertEquals(0, player.getGameplay().getRogueDestructions().total());
    assertTrue(player.getGameplay().getRogue().getDestroyedDestructibleProps().isEmpty());
  }

  @Test
  void anInterruptedProgressCallbackRecoversFromTheSavedActualHistory() {
    destroy(20);
    player.getQuestStates().put(4092312, new QuestState(4092312));
    assertEquals(20, state(4092312).getProgress());
    assertEquals(QuestState.Phase.FINISHED, state(4092312).getPhase());
    assertEquals(0, player.getHcoin());
  }

  @Test
  void concurrentDestructionRequestsRemoveAndCreditTheActualEntityOnce() throws Exception {
    var box = prop(300007);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
    try {
      var jobs = new ArrayList<java.util.concurrent.Callable<Boolean>>();
      for (int i = 0; i < 24; i++) jobs.add(() -> player.scene.destroyProp(box));
      int successful = 0;
      for (var task : pool.invokeAll(jobs)) if (task.get()) successful++;
      assertEquals(1, successful);
      assertFalse(hasProp(300007));
      assertEquals(1, player.getGameplay().getRogueDestructions().total());
      assertEquals(1, state(4092312).getProgress());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentActualGoalClaimsPayFiveJadesOnce() throws Exception {
    destroy(20);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
    try {
      var jobs = new ArrayList<java.util.concurrent.Callable<Retcode>>();
      for (int i = 0; i < 24; i++)
        jobs.add(() -> player.getQuestManager().claim(4092312).retcode());
      int successful = 0;
      for (var task : pool.invokeAll(jobs)) if (task.get() == Retcode.SUCC) successful++;
      assertEquals(1, successful);
      assertEquals(5, player.getHcoin());
    } finally {
      pool.shutdownNow();
    }
  }

  void copyPlayer(Player from, Player to, List<String> names) throws Exception {
    for (String name : names) {
      var f = Player.class.getDeclaredField(name);
      f.setAccessible(true);
      f.set(to, f.get(from));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void twoActualPlayerMorphiaReloadsRetainFourHundredOperationsAndBothRewardStates(boolean claimed)
      throws Exception {
    destroy(400);
    if (claimed)
      for (int id : List.of(4092312, 4092313))
        assertEquals(Retcode.SUCC, player.getQuestManager().claim(id).retcode());
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "destruction-achievements");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var document = new Player();
      copyPlayer(player, document, List.of("name", "gameplay", "questStates", "hcoin"));
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456785);
      store.save(document);
      var first = store.find(Player.class).first();
      assertEquals(400, first.getGameplay().getRogueDestructions().total());
      assertEquals(2, first.getGameplay().getRogue().getDestroyedDestructibleProps().size());
      assertEquals(claimed ? 15 : 0, first.getHcoin());
      for (int id : List.of(4092312, 4092313))
        assertEquals(
            claimed ? QuestState.Phase.CLOSED : QuestState.Phase.FINISHED,
            first.getQuestStates().get(id).getPhase());
      store.save(first);
      var second = store.find(Player.class).first();
      copyPlayer(second, player, List.of("gameplay", "questStates", "hcoin"));
      reload();
      for (int inst : List.of(300007, 300008)) assertFalse(hasProp(inst));
      for (int id : List.of(4092312, 4092313))
        assertEquals(
            claimed ? Retcode.QUEST_REWARD_ALREADY_TAKEN : Retcode.SUCC,
            player.getQuestManager().claim(id).retcode());
      assertEquals(15, player.getHcoin());
      assertEquals(400, state(4092313).getProgress());
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void receiptAndActualCountAreSavedBeforeQuestAndRemovalNotifications() throws Exception {
    var saved = new ArrayList<Integer>();
    var notices = new ArrayList<emu.lunarcore.server.packet.BasePacket>();
    var questNotices = new int[1];
    var session = new Gameplay46HandlerTest.Session() {
      @Override public Player getPlayer() { return RoguePropQuestTest.this.player; }
      @Override public void sendClientPacket(String name, byte[] bytes) {
        super.sendClientPacket(name, bytes);
        var actual = RoguePropQuestTest.this.player;
        if (!name.equals("PlayerSyncScNotify") || actual.getGameplay().getRogueDestructions().total() == 0)
          return;
        assertEquals(actual.getGameplay().getRogueDestructions().total(), saved.getLast());
        assertEquals(1, actual.getGameplay().getRogue().getDestroyedDestructibleProps().size());
        var rows = (List<?>) Gameplay46.value(response, "quest_list");
        var row = rows.stream().map(com.google.protobuf.DynamicMessage.class::cast)
            .filter(q -> Gameplay46.number(q, "id") == 4092312).findFirst().orElseThrow();
        assertEquals(1, Gameplay46.number(row, "progress"));
        questNotices[0]++;
      }
    };
    var actor =
        new Explorer() {
          public emu.lunarcore.server.game.GameSession getSession() {
            return session;
          }

          public void save() {
            saved.add(getGameplay().getRogueDestructions().total());
            if (saved.getLast() > 0)
              assertEquals(1, getGameplay().getRogue().getDestroyedDestructibleProps().size());
          }

          public void sendPacket(emu.lunarcore.server.packet.BasePacket packet) {
            if (getGameplay().getRogueDestructions().total() > 0) {
              assertEquals(getGameplay().getRogueDestructions().total(), saved.getLast());
              notices.add(packet);
            }
          }
        };
    copyPlayer(player, actor, List.of("gameplay", "questStates", "name"));
    player = actor;
    reload();
    int originalEntity = prop(300007).getEntityId();
    destroy(1);
    assertEquals(1, questNotices[0]);
    var removals =
        notices.stream()
            .filter(
                emu.lunarcore.server.packet.send.PacketSceneGroupRefreshScNotify.class::isInstance)
            .toList();
    assertEquals(1, removals.size());
    var removal =
        (emu.lunarcore.proto.SceneGroupRefreshScNotifyOuterClass.SceneGroupRefreshScNotify)
            removals.getFirst().getData();
    assertEquals(
        originalEntity,
        removal.getGroupRefreshInfo().get(0).getRefreshEntity().get(0).getDelEntity());
    assertFalse(hasProp(300007));
  }

  @Test
  void failedSavePublishesNoRemovalAndAnInMemoryRetryCannotCreditAgain() throws Exception {
    var notices = new ArrayList<emu.lunarcore.server.packet.BasePacket>();
    var actor =
        new Explorer() {
          public void save() {
            throw new IllegalStateException("Injected storage failure");
          }

          public void sendPacket(emu.lunarcore.server.packet.BasePacket packet) {
            notices.add(packet);
          }
        };
    copyPlayer(player, actor, List.of("gameplay", "questStates", "name"));
    player = actor;
    reload();
    var box = prop(300007);
    notices.clear();
    assertThrows(IllegalStateException.class, () -> player.scene.destroyProp(box));
    assertTrue(notices.isEmpty());
    assertEquals(1, player.getGameplay().getRogueDestructions().total());
    assertFalse(player.scene.destroyProp(box));
    reload();
    assertFalse(hasProp(300007));
    assertEquals(1, player.getGameplay().getRogueDestructions().total());
  }
}
