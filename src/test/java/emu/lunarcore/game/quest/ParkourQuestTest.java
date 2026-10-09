package emu.lunarcore.game.quest;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class ParkourQuestTest {
  static final int LEVEL = 19993401,
      SUB = 19993402,
      FINISH = 19993403,
      RANK = 19993404,
      ALL = 19993405,
      OUTSIDE = 19993406,
      REWARD = 19993407,
      FLOOR = 44306001;
  static final Gson GSON = new Gson();
  final GameplayDefinitions.Window window =
      new GameplayDefinitions.Window(50039, 5003901, 100, 1000);
  ActivityPanelExcel panel;
  ItemExcel jade;
  final List<Integer> savedJade = new ArrayList<>();
  final Player p =
      new Player() {
        public void save() {
          savedJade.add(getHcoin());
        }

        public String getName() {
          return "Parkour regression";
        }

        public int getFloorId() {
          return FLOOR;
        }

        public void sendPacket(emu.lunarcore.server.packet.BasePacket packet) {}
      };

  @BeforeEach
  void setup() {
    panel = GameData.getActivityPanelExcelMap().get(50039);
    jade = GameData.getItemExcelMap().get(1);
    GameData.getItemExcelMap()
        .add(GSON.fromJson("{\"ID\":1,\"ItemMainType\":\"Virtual\"}", ItemExcel.class));
    panel("[QuestClose:" + FINISH + "],[QuestClose:" + RANK + "],[QuestClose:" + ALL + "]");
    GameplayDefinitions.windows.add(window);
    GameplayDefinitions.parkourConstants.put("parkour_activity_module_id", 5003901);
    GameplayDefinitions.parkourConstants.put("parkour_gameplay_floor_id", FLOOR);
    GameplayDefinitions.parkourLevels.put(
        LEVEL,
        new GameplayDefinitions.ParkourLevel(
            LEVEL, "FinishSubMission", SUB, 1, 3, 3, "race.asset"));
    GameplayDefinitions.parkourBalls.put(1, new GameplayDefinitions.ParkourBall(1, 0, 0));
    var prerequisite = new QuestState(SUB);
    prerequisite.advance(1, 1, 1);
    p.getGameplay().getSubMissions().put(SUB, prerequisite);
    p.getLoginActivityManager().setClock(Clock.fixed(Instant.ofEpochSecond(500), ZoneOffset.UTC));
    quest(FINISH, "ParkourFinishLevel", "ListContain", "\"ParamIntList\":[" + LEVEL + "]", 1);
    quest(
        RANK, "ParkourReachRankInLevel", "Equal", "\"ParamInt1\":" + LEVEL + ",\"ParamInt2\":2", 1);
    quest(ALL, "FinishQuest", "ListContain", "\"ParamIntList\":[" + FINISH + "," + RANK + "]", 2);
    quest(OUTSIDE, "AutoFinish", "Equal", "\"ParamInt1\":0", 1);
    var reward = GSON.fromJson("{\"RewardID\":" + REWARD + ",\"Hcoin\":60}", RewardExcel.class);
    reward.onLoad();
    GameData.getRewardExcelMap().add(reward);
  }

  void panel(String conditions) {
    GameData.getActivityPanelExcelMap()
        .add(
            GSON.fromJson(
                "{\"PanelID\":50039,\"FinishConditions\":\"" + conditions + "\"}",
                ActivityPanelExcel.class));
  }

  void quest(int id, String type, String param, String fields, int goal) {
    GameData.getQuestExcelMap()
        .add(
            GSON.fromJson(
                "{\"QuestID\":"
                    + id
                    + ",\"QuestType\":3,\"RewardID\":"
                    + REWARD
                    + ",\"FinishWayID\":"
                    + id
                    + ",\"UnlockType\":\"AutoUnlock\"}",
                QuestExcel.class));
    GameData.getFinishWayExcelMap()
        .add(
            GSON.fromJson(
                "{\"ID\":"
                    + id
                    + ",\"FinishType\":\""
                    + type
                    + "\",\"ParamType\":\""
                    + param
                    + "\",\"Progress\":"
                    + goal
                    + ",\"IsBackTrack\":true,"
                    + fields
                    + "}",
                FinishWayExcel.class));
  }

  QuestState state(int id) {
    return p.getQuestManager().states().stream()
        .filter(q -> q.getQuestId() == id)
        .findFirst()
        .orElse(null);
  }

  void race(int rank) {
    var manager = new ParkourManager(p);
    assertEquals(Retcode.SUCC, manager.start(LEVEL, 1));
    assertEquals(Retcode.SUCC, manager.finish(LEVEL, 1, 80000, 85000, rank, 2).code());
  }

  @AfterEach
  void clear() {
    GameplayDefinitions.windows.removeIf(w -> w.panelId() == 50039 && w.moduleId() == 5003901);
    GameplayDefinitions.parkourConstants.clear();
    GameplayDefinitions.parkourLevels.clear();
    GameplayDefinitions.parkourBalls.clear();
    if (panel == null) GameData.getActivityPanelExcelMap().keySet().remove(50039);
    else GameData.getActivityPanelExcelMap().add(panel);
    if (jade == null) GameData.getItemExcelMap().keySet().remove(1);
    else GameData.getItemExcelMap().add(jade);
    for (int id : List.of(FINISH, RANK, ALL, OUTSIDE)) {
      GameData.getQuestExcelMap().keySet().remove(id);
      GameData.getFinishWayExcelMap().keySet().remove(id);
    }
    GameData.getRewardExcelMap().keySet().remove(REWARD);
  }

  @Test
  void onlyResourceLinkedActiveActivityQuestsAreAccepted() {
    assertNotNull(state(FINISH));
    assertEquals(QuestState.Phase.DOING, state(FINISH).getPhase());
    assertNull(state(OUTSIDE));
    assertTrue(p.getQuestStates().keySet().stream().noneMatch(id -> id == FINISH));
    GameplayDefinitions.windows.remove(window);
    assertNull(state(FINISH));
    assertNotEquals(Retcode.SUCC, p.getQuestManager().claim(FINISH).retcode());
  }

  @Test
  void realRaceBacktracksPassAndTopRankWithoutAutomaticRewards() throws Exception {
    race(4);
    assertEquals(0, state(FINISH).getProgress());
    assertEquals(0, state(RANK).getProgress());
    race(3);
    assertEquals(QuestState.Phase.FINISHED, state(FINISH).getPhase());
    assertEquals(0, state(RANK).getProgress());
    assertEquals(1, state(ALL).getProgress());
    race(1);
    assertEquals(QuestState.Phase.FINISHED, state(RANK).getPhase());
    assertEquals(2, state(ALL).getProgress());
    assertEquals(0, p.getHcoin());
    assertEquals(Retcode.QUEST_STATUS_ERROR, p.getQuestManager().finishByClient(RANK));
    try (var pool = Executors.newFixedThreadPool(6)) {
      var work = new ArrayList<Callable<Retcode>>();
      for (int i = 0; i < 12; i++) work.add(() -> p.getQuestManager().claim(RANK).retcode());
      int success = 0;
      for (var result : pool.invokeAll(work)) if (result.get() == Retcode.SUCC) success++;
      assertEquals(1, success);
    }
    assertEquals(60, p.getHcoin());
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, p.getQuestManager().claim(RANK).retcode());
  }

  @Test
  void expiredAndNewOperatorWindowsDoNotReuseOldRaceOrClaim() {
    race(2);
    assertEquals(QuestState.Phase.FINISHED, state(FINISH).getPhase());
    p.getLoginActivityManager().setClock(Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC));
    assertNotEquals(Retcode.SUCC, p.getQuestManager().claim(FINISH).retcode());
    GameplayDefinitions.windows.add(new GameplayDefinitions.Window(50039, 5003901, 1000, 2000));
    assertEquals(QuestState.Phase.DOING, state(FINISH).getPhase());
    assertEquals(0, state(RANK).getProgress());
    assertNotEquals(Retcode.SUCC, p.getQuestManager().claim(FINISH).retcode());
    assertEquals(0, p.getHcoin());
    race(2);
    assertEquals(Retcode.SUCC, p.getQuestManager().claim(FINISH).retcode());
    p.getLoginActivityManager().setClock(Clock.fixed(Instant.ofEpochSecond(500), ZoneOffset.UTC));
    assertEquals(QuestState.Phase.FINISHED, state(FINISH).getPhase());
    assertEquals(Retcode.SUCC, p.getQuestManager().claim(FINISH).retcode());
    assertEquals(120, p.getHcoin());
  }

  @Test
  void unrelatedForgedCycleStateCannotUseTheRewardPipeline() {
    var forged = new QuestState(OUTSIDE);
    forged.advance(1, 1, 1);
    p.getQuestStates().put(OUTSIDE, forged);
    assertNotEquals(Retcode.SUCC, p.getQuestManager().claim(OUTSIDE).retcode());
    assertEquals(0, p.getHcoin());
    assertEquals(QuestState.Phase.FINISHED, forged.getPhase());
  }

  @Test
  void invalidResourceConditionsCannotSilentlyWidenActivityScope() {
    for (String bad :
        List.of(
            "[QuestClose:" + FINISH + "],",
            "[QuestClose:4294967297]",
            "[Unknown:" + FINISH + "]",
            "[QuestClose:" + FINISH + "],[QuestClose:" + FINISH + "]")) {
      panel(bad);
      assertNull(state(FINISH), bad);
      assertEquals(0, p.getHcoin());
    }
  }

  @Test
  void unsupportedParametersAndCrossActivityAggregatesStayUnfinished() {
    quest(
        RANK,
        "ParkourReachRankInLevel",
        "Equal",
        "\"ParamInt1\":" + LEVEL + ",\"ParamInt2\":2,\"ParamInt3\":1",
        1);
    quest(
        ALL, "FinishQuest", "ListContain", "\"ParamIntList\":[" + FINISH + "," + OUTSIDE + "]", 2);
    race(1);
    assertEquals(QuestState.Phase.FINISHED, state(FINISH).getPhase());
    assertNull(state(RANK));
    assertNull(state(ALL));
    assertEquals(0, p.getHcoin());
  }

  @Test
  void windowResultsAndClaimReceiptsSurviveRealMorphiaReload() throws Exception {
    race(1);
    assertEquals(Retcode.SUCC, p.getQuestManager().claim(RANK).retcode());
    assertTrue(state(FINISH).reserveReward());
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "parkourquests");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456782);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, p.getGameplay());
      store.save(document);
      gameplay.set(p, store.find(Player.class).first().getGameplay());
      assertEquals(QuestState.Phase.CLAIMING, state(FINISH).getPhase());
      assertEquals(QuestState.Phase.CLOSED, state(RANK).getPhase());
      assertEquals(QuestState.Phase.FINISHED, state(ALL).getPhase());
      assertEquals(Retcode.SYSTEM_BUSY, p.getQuestManager().claim(FINISH).retcode());
      assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, p.getQuestManager().claim(RANK).retcode());
      assertEquals(60, p.getHcoin());
      store
          .getDatabase()
          .getCollection(store.getMapper().getEntityModel(Player.class).getCollectionName())
          .updateOne(
              new org.bson.Document("_id", 123456782),
              new org.bson.Document(
                  "$unset", new org.bson.Document("gameplay.parkour.questWindows", "")));
      gameplay.set(p, store.find(Player.class).first().getGameplay());
      assertEquals(QuestState.Phase.DOING, state(RANK).getPhase());
      assertEquals(0, state(RANK).getProgress(), "旧全局名次没有窗口证据，不能替代当前窗口记录");
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void partialInventoryDeliveryRemainsReservedAndCannotBeRetriedForFree() {
    var reward =
        GSON.fromJson(
            "{\"RewardID\":" + REWARD + ",\"Hcoin\":60,\"ItemID_1\":19993499,\"Count_1\":1}",
            RewardExcel.class);
    reward.onLoad();
    GameData.getRewardExcelMap().add(reward);
    race(1);
    assertEquals(Retcode.PLAYER_DATA_ERROR, p.getQuestManager().claim(RANK).retcode());
    assertEquals(60, savedJade.getLast(), "部分发放的货币必须和保留中的领取记录一起保存");
    assertEquals(QuestState.Phase.CLAIMING, state(RANK).getPhase());
    assertEquals(60, p.getHcoin());
    assertEquals(Retcode.SYSTEM_BUSY, p.getQuestManager().claim(RANK).retcode());
    assertEquals(60, p.getHcoin());
  }

  @Test
  void rankObjectiveUsesTopNIndependentOfTheLevelPassTarget() {
    GameplayDefinitions.parkourLevels.put(
        LEVEL,
        new GameplayDefinitions.ParkourLevel(
            LEVEL, "FinishSubMission", SUB, 1, 1, 3, "race.asset"));
    race(2);
    assertEquals(QuestState.Phase.DOING, state(FINISH).getPhase());
    assertEquals(QuestState.Phase.FINISHED, state(RANK).getPhase());
    for (int i = 0; i < 10; i++) assertEquals(1, state(ALL).getProgress());
    assertEquals(0, p.getHcoin());
  }
}
