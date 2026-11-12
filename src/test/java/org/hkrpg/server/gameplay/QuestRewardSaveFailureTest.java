package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.QuestStatusOuterClass.QuestStatus;
import emu.lunarcore.server.packet.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class QuestRewardSaveFailureTest {
  static final int QUEST = 198869391, REWARD = 198869392, ITEM = 198869393;
  static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
  final Gson gson = new Gson();
  QuestExcel oldQuest;
  RewardExcel oldReward;
  ItemExcel oldItem;
  List<GameplayDefinitions.DailyPool> oldPool;
  @TempDir Path directory;

  static class Actor extends Player {
    int saves, failAt, attempts, delivered;
    boolean partial, throwDelivery, daily;
    QuestState durableState;
    int durablePoints;
    Runnable afterSave, afterGrant;
    final Inventory inventory = new Inventory(this) {
      @Override public GrantResult grantItems(ItemParamMap items, boolean hint) {
        assertEquals(QuestState.Phase.CLAIMING, durableState.getPhase());
        attempts++;
        int count = partial ? 1 : items.get(ITEM);
        delivered += count;
        if (afterGrant != null) afterGrant.run();
        if (throwDelivery) throw new IllegalStateException("Fixture delivery interrupted");
        return new GrantResult(List.of(new GameItem(ITEM, count)), !partial);
      }
    };

    QuestState state() {
      return daily ? getGameplay().getDailyQuests().get(QUEST) : getQuestStates().get(QUEST);
    }
    @Override public Inventory getInventory() { return inventory; }
    @Override public void sendPacket(BasePacket packet) {}
    @Override public void save() {
      if (++saves == failAt) throw new IllegalStateException("Fixture quest write rejected");
      var gson = new Gson();
      durableState = gson.fromJson(gson.toJson(state()), QuestState.class);
      durablePoints = getGameplay().getDailyPoints();
      if (afterSave != null) afterSave.run();
    }
  }

  @BeforeEach void setup() {
    oldQuest = GameData.getQuestExcelMap().get(QUEST);
    oldReward = GameData.getRewardExcelMap().get(REWARD);
    oldItem = GameData.getItemExcelMap().get(ITEM);
    oldPool = new ArrayList<>(GameplayDefinitions.dailyPool);
    GameplayDefinitions.dailyPool.clear();
    GameData.getItemExcelMap().add(gson.fromJson(
        "{\"ID\":" + ITEM + ",\"ItemMainType\":\"Material\",\"PileLimit\":99}", ItemExcel.class));
  }

  @AfterEach void restore() {
    GameData.getQuestExcelMap().keySet().remove(QUEST);
    if (oldQuest != null) GameData.getQuestExcelMap().add(oldQuest);
    GameData.getRewardExcelMap().keySet().remove(REWARD);
    if (oldReward != null) GameData.getRewardExcelMap().add(oldReward);
    GameData.getItemExcelMap().keySet().remove(ITEM);
    if (oldItem != null) GameData.getItemExcelMap().add(oldItem);
    GameplayDefinitions.dailyPool.clear();
    GameplayDefinitions.dailyPool.addAll(oldPool);
  }

  Actor completed(boolean daily) {
    GameData.getQuestExcelMap().add(gson.fromJson("{\"QuestID\":" + QUEST + ",\"QuestType\":"
        + (daily ? 5 : 1) + ",\"RewardID\":" + REWARD + "}", QuestExcel.class));
    var reward = gson.fromJson("{\"RewardID\":" + REWARD + ",\"ItemID_1\":" + ITEM
        + ",\"Count_1\":3" + (daily ? ",\"ItemID_2\":23,\"Count_2\":40" : "") + "}", RewardExcel.class);
    reward.onLoad();
    GameData.getRewardExcelMap().add(reward);
    var actor = new Actor();
    actor.daily = daily;
    actor.getDailyManager().setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    actor.getGameplay().resetDaily(DailyManager.day(NOW));
    var state = new QuestState(QUEST);
    state.captureObservationKey();
    state.advance(7, 7, NOW.getEpochSecond());
    (daily ? actor.getGameplay().getDailyQuests() : actor.getQuestStates()).put(QUEST, state);
    actor.save();
    return actor;
  }

  void unchanged(Actor actor, QuestState state, QuestState.Phase phase) {
    assertSame(state, actor.state());
    assertEquals(7, state.getProgress());
    assertEquals(NOW.getEpochSecond(), state.getFinishTime());
    assertEquals(phase, state.getPhase());
    assertEquals(actor.durableState.getObservationKey(), state.getObservationKey());
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void rejectedInitialReservationCanRetryTheSameCompletionWithoutLosingTheReward(boolean daily) {
    var actor = completed(daily);
    var state = actor.state();
    actor.failAt = actor.saves + 1;
    assertThrows(IllegalStateException.class, () -> actor.getQuestManager().claim(QUEST));
    unchanged(actor, state, QuestState.Phase.FINISHED);
    assertEquals(0, actor.attempts);
    assertEquals(0, actor.getGameplay().getDailyPoints());
    actor.failAt = 0;
    assertEquals(Retcode.SUCC, actor.getQuestManager().claim(QUEST).retcode());
    assertEquals(3, actor.delivered);
    assertEquals(daily ? 40 : 0, actor.getGameplay().getDailyPoints());
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, actor.getQuestManager().claim(QUEST).retcode());
    assertEquals(1, actor.attempts);
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void rejectedCompletionRemainsClaimingAndDoesNotPublishAnUncommittedClosedState(boolean daily) {
    var actor = completed(daily);
    var state = actor.state();
    actor.failAt = actor.saves + 2;
    assertThrows(IllegalStateException.class, () -> actor.getQuestManager().claim(QUEST));
    unchanged(actor, state, QuestState.Phase.CLAIMING);
    assertEquals(QuestStatus.QUEST_FINISH, state.toProto().getStatus());
    assertEquals(QuestState.Phase.CLAIMING, actor.durableState.getPhase());
    assertEquals(3, actor.delivered);
    // Preserve confirmed live effects; a pending reward must never credit them again.
    assertEquals(daily ? 40 : 0, actor.getGameplay().getDailyPoints());
    assertEquals(Retcode.SYSTEM_BUSY, actor.getQuestManager().claim(QUEST).retcode());
    assertEquals(1, actor.attempts);
  }

  @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void partialOrInterruptedDeliveryKeepsTheReservationAndNeverAutomaticallyRetries(boolean daily, boolean throwing) {
    var actor = completed(daily);
    actor.partial = !throwing;
    actor.throwDelivery = throwing;
    if (throwing) assertThrows(IllegalStateException.class, () -> actor.getQuestManager().claim(QUEST));
    else assertEquals(Retcode.PLAYER_DATA_ERROR, actor.getQuestManager().claim(QUEST).retcode());
    assertEquals(QuestState.Phase.CLAIMING, actor.state().getPhase());
    assertEquals(QuestState.Phase.CLAIMING, actor.durableState.getPhase());
    assertEquals(Retcode.SYSTEM_BUSY, actor.getQuestManager().claim(QUEST).retcode());
    assertEquals(1, actor.attempts);
    assertEquals(throwing ? 3 : 1, actor.delivered);
    assertEquals(0, actor.getGameplay().getDailyPoints());
  }

  @ParameterizedTest @CsvSource({"false,1", "false,2", "true,1", "true,2"})
  void actualPlayerAndItemDocumentsKeepTheCorrectPhaseAfterNormalH2Restart(boolean daily, int boundary) throws Exception {
    var actor = completed(daily);
    var identity = Player.class.getDeclaredField("uid");
    identity.setAccessible(true);
    identity.setInt(actor, 198869300 + boundary + (daily ? 10 : 0));
    var path = directory.resolve("quest-database").toString();
    try (var database = new RewardLedgerSaveFailureTest.Database(path)) {
      var document = new Player();
      identity.setInt(document, actor.getUid());
      for (String name : List.of("gameplay", "questStates")) {
        var field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(document, field.get(actor));
      }
      database.store.save(document);
      actor.afterSave = () -> database.store.save(document);
      actor.afterGrant = () -> {
        var item = new GameItem(ITEM, 3);
        item.setOwner(actor);
        database.store.save(item);
      };
      actor.failAt = actor.saves + boundary;
      assertThrows(IllegalStateException.class, () -> actor.getQuestManager().claim(QUEST));
      actor.failAt = 0;
      actor.save();
    }
    try (var database = new RewardLedgerSaveFailureTest.Database(path)) {
      var restored = database.store.find(Player.class).first();
      assertNotNull(restored);
      var state = (daily ? restored.getGameplay().getDailyQuests() : restored.getQuestStates()).get(QUEST);
      assertNotNull(state);
      assertEquals(boundary == 1 ? QuestState.Phase.FINISHED : QuestState.Phase.CLAIMING, state.getPhase());
      assertEquals(7, state.getProgress());
      assertEquals(NOW.getEpochSecond(), state.getFinishTime());
      var items = database.store.find(GameItem.class).iterator().toList();
      assertEquals(boundary == 1 ? 0 : 1, items.size());
      if (!items.isEmpty()) {
        assertEquals(3, items.getFirst().getCount());
        assertEquals(actor.getUid(), items.getFirst().getOwnerUid());
      }
      assertEquals(daily && boundary == 2 ? 40 : 0, restored.getGameplay().getDailyPoints());
    }
  }
}
