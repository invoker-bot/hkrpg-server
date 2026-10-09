package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import dev.morphia.Morphia;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GameplayPersistenceTest {
  @Test
  void confirmedTrialQuantitiesActualGrantAndReservedReceiptSurviveMorphiaReload()
      throws Exception {
    var server = new MongoServer(new MemoryBackend());
    var address = server.bind();
    try (var client = MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = Morphia.createDatastore(client, "trial-persistence");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var p = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(p, 123456786);
      var plan = new GameplayState.TrialCompletion(3011001, Map.of(2, 10000, 212, 2), true);
      p.getGameplay().getTrialCompletions().put(310010, plan);
      p.getGameplay().getRewards().put(TrialRewards.key(310010), new GameplayState.Receipt(0));
      var row =
          new com.google.gson.Gson()
              .fromJson(
                  "{\"ID\":2,\"ItemMainType\":\"Virtual\"}",
                  emu.lunarcore.data.excel.ItemExcel.class);
      plan.received(java.util.List.of(new emu.lunarcore.game.inventory.GameItem(row, 3000)));
      store.save(p);
      var loaded = store.find(Player.class).first();
      var restored = loaded.getGameplay().getTrialCompletions().get(310010);
      assertEquals(3011001, restored.getRewardId());
      assertEquals(Map.of(2, 10000, 212, 2), restored.getItems());
      assertEquals(Map.of(2, 3000), restored.getReceived());
      assertTrue(restored.isReady());
      assertEquals(
          emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY,
          TrialRewards.claim(loaded, 310010).retcode());
      assertFalse(loaded.getRewardLedger().granted(TrialRewards.key(310010)));
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void actualPlayerRestoresMissionsDailyActivityRunAndReservedRewards() throws Exception {
    var server = new MongoServer(new MemoryBackend());
    var address = server.bind();
    try (var client = MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = Morphia.createDatastore(client, "gameplay-persistence");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var p = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(p, 123456788);
      p.getGameplay().resetDaily(20000);
      p.getGameplay().addPoints(100);
      var sub = new QuestState(123);
      sub.advance(2, 3, 1);
      p.getGameplay().getSubMissions().put(123, sub);
      p.getGameplay().getRewards().put("daily:20000:1", new GameplayState.Receipt(123));
      var login = new GameplayState.LoginDays();
      login.record(20000, 7);
      p.getGameplay().getActivityDays().put(456, login);
      var run = new GameplayState.RogueRun(100, 1, Map.of(1, 100, 2, 201));
      run.setupTeam(new UniverseLifecycleTest.Explorer(), java.util.List.of(199961));
      run.getVitals().get(199961).setHp(2300);
      run.getVitals().get(199961).setSp(4700);
      run.setMp(2);
      run.enter(1, 100);
      run.clear();
      run.offer(java.util.List.of(600000, 600010));
      run.getBlessings().put(600020, 2);
      run.getMiracles().put(4, new GameplayState.RogueMiracle(1));
      run.getMiracles().get(4).consume();
      run.getProcessedBattles().add("1234:5678");
      p.getGameplay().setRogue(run);
      p.getGameplay().getRogueCollectedBuffs().add(600020);
      p.getGameplay().getRogueWins().put(110, 2);
      p.getGameplay().getStoryValues().put("floor:20001001:StoryDoor", 1);
      p.getGameplay().getStoryValues().put("group-state:20001001:9", 3);
      p.getGameplay().getStoryValues().put("prop-state:20001:20001001:9:300001", 1);
      var actionCursor = new GameplayState.ActionCursor();
      actionCursor.reserve();
      p.getGameplay().getStoryActions().put(123, actionCursor);
      p.getGameplay().getMissionPayments().put(124, actionCursor);
      p.getGameplay()
          .getMissionAcquiredItems()
          .put(125, new java.util.HashSet<>(java.util.List.of(140033, 140039)));
      p.getGameplay().getRhythm().start(101);
      p.getGameplay().getRhythm().finish(2, true);
      p.getGameplay().getRhythm().start(102);
      p.getGameplay()
          .getRhythm()
          .compose(1, 0, java.util.List.of(3767, 4095, 2015), java.util.List.of(11));
      p.getGameplay().getMissionAcceptedDays().put(126, 20000L);
      var intro = new GameplayState.FirstTalkReport(20001, 20001001, java.util.List.of(123));
      intro.complete();
      p.getGameplay().getFirstTalkReports().put(129, intro);
      p.getGameplay()
          .getMissionRewardPlans()
          .put(
              "submission:123",
              new GameplayState.MissionRewardPlan(
                  java.util.List.of(2000001, 2000002),
                  Map.of("OldValue_0", 1),
                  Map.of(140033, 3),
                  true));
      p.getGameplay()
          .getMissionRewardPlans()
          .put(
              "submission:124",
              new GameplayState.MissionRewardPlan(
                  java.util.List.of(2000003), Map.of(), Map.of(), false));
      var phone = new GameplayState.PhoneSection(127, java.util.List.of(1270));
      phone.step(1270, java.util.List.of(1271, 1272));
      p.getGameplay().getPhoneSections().put(127, phone);
      var completedPhone = new GameplayState.PhoneSection(128, java.util.List.of(1280));
      completedPhone.step(1280, java.util.List.of());
      completedPhone.finish();
      p.getGameplay().getPhoneSections().put(128, completedPhone);
      String rhythmTransaction = p.getGameplay().getRhythm().getTransaction();
      store.save(p);
      var loaded = store.find(Player.class).first();
      assertNotNull(loaded.getMissionManager());
      assertNotNull(loaded.getDailyManager());
      assertNotNull(loaded.getUniverseManager());
      assertNotNull(loaded.getLoginActivityManager());
      assertEquals(
          java.util.List.of(1270), loaded.getGameplay().getPhoneSections().get(127).getHistory());
      assertEquals(
          java.util.List.of(1271, 1272),
          loaded.getGameplay().getPhoneSections().get(127).getOffered());
      assertFalse(loaded.getGameplay().getPhoneSections().get(127).isFinished());
      assertTrue(loaded.getGameplay().getPhoneSections().get(128).isFinished());
      assertEquals(
          completedPhone.getRefreshed(),
          loaded.getGameplay().getPhoneSections().get(128).getRefreshed());
      assertEquals(2, loaded.getGameplay().getSubMissions().get(123).getProgress());
      assertTrue(loaded.getMissionManager().hasSeenFirstTalk(129));
      var restoredIntro = loaded.getGameplay().getFirstTalkReports().get(129);
      assertEquals(20001, restoredIntro.getPlane());
      assertEquals(20001001, restoredIntro.getFloor());
      assertEquals(java.util.List.of(123), restoredIntro.getSubMissionIds());
      var restoredReward = loaded.getGameplay().getMissionRewardPlans().get("submission:123");
      assertEquals(java.util.List.of(2000001, 2000002), restoredReward.getRewardIds());
      assertEquals(Map.of("OldValue_0", 1), restoredReward.getChoices());
      assertEquals(Map.of(140033, 3), restoredReward.getItems());
      assertTrue(restoredReward.isReady());
      assertFalse(loaded.getGameplay().getMissionRewardPlans().get("submission:124").isReady());
      assertEquals(100, loaded.getGameplay().getDailyPoints());
      assertEquals(1, loaded.getLoginActivityManager().days(456));
      assertEquals(run.getRunId(), loaded.getGameplay().getRogue().getRunId());
      assertEquals(Map.of(1, 100, 2, 201), loaded.getGameplay().getRogue().getRooms());
      assertTrue(loaded.getGameplay().getRogue().getCleared().contains(1));
      assertTrue(loaded.getGameplay().getRogue().isInside());
      assertEquals(java.util.List.of(199961), loaded.getUniverseManager().lineup().getAvatars());
      assertEquals(2300, loaded.getUniverseManager().lineup().hp(199961));
      assertEquals(4700, loaded.getUniverseManager().lineup().sp(199961));
      assertEquals(2, loaded.getUniverseManager().lineup().getMp());
      assertEquals(11, loaded.getGameplay().getRogue().getReturnTo().getEntry());
      assertEquals(120, loaded.getGameplay().getRogue().getReturnTo().getPos().getX());
      assertEquals(90, loaded.getGameplay().getRogue().getReturnTo().getRot().getY());
      assertEquals(java.util.List.of(600000, 600010), loaded.getGameplay().getRogue().getOffers());
      assertEquals(2, loaded.getGameplay().getRogue().getBlessings().get(600020));
      assertTrue(loaded.getGameplay().getRogue().getMiracles().get(4).broken());
      assertEquals(1, loaded.getGameplay().getRogue().getMiracles().get(4).getUseCount());
      assertTrue(loaded.getGameplay().getRogue().getProcessedBattles().contains("1234:5678"));
      assertEquals(2, loaded.getGameplay().getRogueWins().get(110));
      assertTrue(loaded.getGameplay().getRogueCollectedBuffs().contains(600020));
      assertTrue(loaded.getGameplay().getStoryActions().get(123).isPending());
      assertTrue(loaded.getGameplay().getMissionPayments().get(124).isPending());
      assertEquals(
          java.util.Set.of(140033, 140039),
          loaded.getGameplay().getMissionAcquiredItems().get(125));
      assertEquals(1, loaded.getGameplay().getStoryValues().get("floor:20001001:StoryDoor"));
      assertEquals(3, loaded.getGameplay().getStoryValues().get("group-state:20001001:9"));
      assertEquals(
          1, loaded.getGameplay().getStoryValues().get("prop-state:20001:20001001:9:300001"));
      assertEquals(102, loaded.getGameplay().getRhythm().getActiveLevel());
      assertEquals(rhythmTransaction, loaded.getGameplay().getRhythm().getTransaction());
      assertEquals(2, loaded.getGameplay().getRhythm().getStars().get(101));
      assertTrue(loaded.getGameplay().getRhythm().getFullCombos().contains(101));
      assertEquals(1, loaded.getGameplay().getRhythm().getCurrentSong());
      assertEquals(
          java.util.List.of(3767, 4095, 2015),
          loaded.getGameplay().getRhythm().getCompositions().get(1).getGrid());
      assertEquals(
          java.util.List.of(11),
          loaded.getGameplay().getRhythm().getCompositions().get(1).getEffects());
      assertEquals(20000L, loaded.getGameplay().getMissionAcceptedDays().get(126));
      assertFalse(loaded.getGameplay().getRewards().get("daily:20000:1").isGranted());
      loaded.getGameplay().getRogue().finish(GameplayState.RogueRun.Phase.FAILED);
      loaded.getGameplay().getRogue().dismiss();
      store.save(loaded);
      assertTrue(store.find(Player.class).first().getGameplay().getRogue().isDismissed());
      assertEquals(
          GameplayState.RogueRun.Phase.FAILED,
          store.find(Player.class).first().getGameplay().getRogue().getPhase());
      assertEquals(
          emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY,
          loaded.getRewardLedger().claim("daily:20000:1", 123).retcode());
      var nextRun = new GameplayState.RogueRun(100, 1, Map.of(1, 100));
      loaded.getGameplay().setRogue(nextRun);
      nextRun.finish(GameplayState.RogueRun.Phase.FINISHED);
      assertTrue(loaded.getGameplay().recordRogueWin(nextRun));
      nextRun.offer(java.util.List.of(600000));
      nextRun.getOffers().clear();
      nextRun.offerMiracles(java.util.List.of(6, 13));
      assertTrue(nextRun.getSelectionId() > run.getSelectionId());
      store.save(loaded);
      assertEquals(
          nextRun.getSelectionId(),
          store.find(Player.class).first().getGameplay().getRogue().getSelectionId());
      var recovered = store.find(Player.class).first().getGameplay();
      assertTrue(recovered.getRogue().isHistoryRecorded());
      assertFalse(recovered.recordRogueWin(recovered.getRogue()));
      assertEquals(1, recovered.getRogueWins().get(100));
      assertEquals(java.util.List.of(6, 13), recovered.getRogue().getMiracleOffers());
    } finally {
      server.shutdownNow();
    }
  }
}
