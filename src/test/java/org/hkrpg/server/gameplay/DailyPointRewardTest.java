package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.server.packet.send.PacketGetDailyActiveInfoScRsp;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Original auto objective, actual inventory and five original point rows; other reward IDs are
 * explicit variants.
 */
class DailyPointRewardTest {
  static final int QUEST = 2100132;
  final Gson gson = ResourceLoader.getGson();
  JsonArray fixture;
  List<GameplayDefinitions.DailyPool> beforePool;
  QuestExcel oldQuest;
  FinishWayExcel oldRule;
  Map<Integer, RewardExcel> oldRewards = new HashMap<>();
  Map<Integer, ItemExcel> oldItems = new HashMap<>();
  final Player player =
      new Player() {
        @Override
        public void save() {}

        @Override
        public void sendPacket(BasePacket packet) {}
      };

  JsonObject row(String kind, int id) {
    String field =
        switch (kind) {
          case "quest", "pool" -> "QuestID";
          case "reward" -> "RewardID";
          default -> "ID";
        };
    for (var element : fixture) {
      var r = element.getAsJsonObject();
      if (kind.equals(r.get("kind").getAsString())
          && r.getAsJsonObject("row").get(field).getAsInt() == id)
        return r.getAsJsonObject("row").deepCopy();
    }
    throw new AssertionError("Original row missing");
  }

  @BeforeEach
  void setup() throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/daily-prop-points-4.6.json")) {
      assertNotNull(in);
      fixture =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
    beforePool = new ArrayList<>(GameplayDefinitions.dailyPool);
    GameplayDefinitions.dailyPool.clear();
    GameplayDefinitions.dailyPool.add(
        gson.fromJson(row("pool", QUEST), GameplayDefinitions.DailyPool.class));
    oldQuest = GameData.getQuestExcelMap().get(QUEST);
    oldRule = GameData.getFinishWayExcelMap().get(QUEST);
    GameData.getQuestExcelMap().add(gson.fromJson(row("quest", QUEST), QuestExcel.class));
    GameData.getFinishWayExcelMap().add(gson.fromJson(row("rule", QUEST), FinishWayExcel.class));
    for (int id = 103001; id <= 103005; id++) {
      oldRewards.put(id, GameData.getRewardExcelMap().get(id));
      var reward = gson.fromJson(row("reward", id), RewardExcel.class);
      reward.onLoad();
      GameData.getRewardExcelMap().add(reward);
    }
    for (int id : List.of(23, 35)) {
      oldItems.put(id, GameData.getItemExcelMap().get(id));
      GameData.getItemExcelMap().keySet().remove(id);
    }
    var raw = row("item", 23);
    raw.addProperty("ItemName", raw.getAsJsonObject("ItemName").get("Hash").getAsLong());
    GameData.getItemExcelMap().add(gson.fromJson(raw, ItemExcel.class));
    player
        .getDailyManager()
        .setClock(Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC));
  }

  @AfterEach
  void restore() {
    GameplayDefinitions.dailyPool.clear();
    GameplayDefinitions.dailyPool.addAll(beforePool);
    GameData.getQuestExcelMap().keySet().remove(QUEST);
    if (oldQuest != null) GameData.getQuestExcelMap().add(oldQuest);
    GameData.getFinishWayExcelMap().keySet().remove(QUEST);
    if (oldRule != null) GameData.getFinishWayExcelMap().add(oldRule);
    for (var old : oldRewards.entrySet()) {
      GameData.getRewardExcelMap().keySet().remove(old.getKey());
      if (old.getValue() != null) GameData.getRewardExcelMap().add(old.getValue());
    }
    for (var old : oldItems.entrySet()) {
      GameData.getItemExcelMap().keySet().remove(old.getKey());
      if (old.getValue() != null) GameData.getItemExcelMap().add(old.getValue());
    }
  }

  void variant(int reward) {
    var q = row("quest", QUEST);
    q.addProperty("RewardID", reward);
    GameData.getQuestExcelMap().add(gson.fromJson(q, QuestExcel.class));
  }

  @ParameterizedTest
  @ValueSource(ints = {103001, 103002, 103003, 103004, 103005})
  void originalActivePointRowsCreditDailyStateAndCloseOnce(int reward) {
    if (reward != 103002)
      variant(
          reward); // Four explicit constructed variants; original row is unchanged in the fixture.
    var manager = player.getDailyManager();
    manager.ensureDay();
    assertEquals(
        QuestState.Phase.FINISHED, player.getGameplay().getDailyQuests().get(QUEST).getPhase());
    var result = manager.claimQuest(QUEST);
    assertEquals(Retcode.SUCC, result.retcode());
    assertTrue(result.items().isEmpty(), "Daily points entered ordinary inventory");
    int points = row("reward", reward).get("Count_1").getAsInt();
    assertEquals(points, player.getGameplay().getDailyPoints());
    assertEquals(
        QuestState.Phase.CLOSED, player.getGameplay().getDailyQuests().get(QUEST).getPhase());
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, manager.claimQuest(QUEST).retcode());
    assertEquals(points, player.getGameplay().getDailyPoints());
  }

  @Test
  void nativeDailyInfoContainsTheOriginalHundredPoints() throws Exception {
    assertEquals(Retcode.SUCC, player.getDailyManager().claimQuest(QUEST).retcode());
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var packet = new PacketGetDailyActiveInfoScRsp(player);
    var nativeReply =
        bridge
            .clientSchema()
            .parse(
                "GetDailyActiveInfoScRsp",
                bridge.translate("GetDailyActiveInfoScRsp", packet.getData().toByteArray(), true));
    assertEquals(
        100,
        nativeReply.getField(
            nativeReply.getDescriptorForType().findFieldByName("daily_active_point")));
    assertTrue(nativeReply.getUnknownFields().asMap().isEmpty());
  }

  @Test
  void concurrentClaimsCreditActualPointsOnlyOnce() throws Exception {
    var executor = Executors.newFixedThreadPool(2);
    try {
      var results =
          executor.invokeAll(
              List.of(
                  () -> player.getDailyManager().claimQuest(QUEST).retcode(),
                  () -> player.getDailyManager().claimQuest(QUEST).retcode()));
      var codes = new HashSet<Object>();
      for (var result : results) codes.add(result.get());
      assertEquals(Set.of(Retcode.SUCC, Retcode.QUEST_REWARD_ALREADY_TAKEN), codes);
      assertEquals(100, player.getGameplay().getDailyPoints());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void absentItemThirtyFiveCannotBeInventedAsDailyPoints() {
    var raw = row("reward", 103002);
    raw.addProperty("ItemID_1", 35);
    var reward = gson.fromJson(raw, RewardExcel.class);
    reward.onLoad();
    GameData.getRewardExcelMap().add(reward);
    assertEquals(Retcode.PLAYER_DATA_ERROR, player.getDailyManager().claimQuest(QUEST).retcode());
    assertEquals(0, player.getGameplay().getDailyPoints());
    assertEquals(
        QuestState.Phase.FINISHED, player.getGameplay().getDailyQuests().get(QUEST).getPhase());
  }
}
