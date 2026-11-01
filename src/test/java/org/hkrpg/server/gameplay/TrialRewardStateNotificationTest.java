package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.packet.SessionState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.*;

class TrialRewardStateNotificationTest {
  static final int STAGE = TrialActivityLifecycleTest.STAGE;
  private static final com.google.gson.Gson GSON = new com.google.gson.Gson();
  final TrialRewardWireTest fixture = new TrialRewardWireTest();

  static class Recipient extends TrialRewardWireTest.Recipient {
    String saved;
    boolean failSave;

    public void save() {
      if (failSave) throw new IllegalStateException("injected player save failure");
      super.save();
      saved = GSON.toJson(getGameplay());
    }
  }

  static class Session extends TrialActivityLifecycleTest.Session {
    final Recipient recipient;
    final List<String> names = new ArrayList<>();
    final List<DynamicMessage> changes = new ArrayList<>();
    final List<String> savedAtChange = new ArrayList<>();
    boolean legacy;

    Session(Recipient player) {
      super(player);
      recipient = player;
      player.setSession(this);
    }

    public org.hkrpg.server.protocol.ProtocolBridge getProtocol() {
      return org.hkrpg.server.protocol.ProtocolBridge.forVersion(legacy ? "4.2.0" : "4.6.0");
    }

    public void sendClientPacket(String name, byte[] bytes) {
      super.sendClientPacket(name, bytes);
      names.add(name);
      if (name.equals("TrialActivityDataChangeScNotify")) {
        changes.add(response);
        savedAtChange.add(recipient.saved);
      }
    }
  }

  @BeforeEach void install() { fixture.install(); }
  @AfterEach void restore() { fixture.restore(); }

  void assertChange(Session session, int index, boolean taken) {
    assertTrue(session.changes.size() > index,
        "The native trial cache needs a state-change notification without querying again");
    var message = session.changes.get(index);
    var child = message.getDescriptorForType().findFieldByNumber(6);
    assertTrue(message.hasField(child));
    var info = (DynamicMessage) message.getField(child);
    assertEquals(STAGE, info.getField(info.getDescriptorForType().findFieldByNumber(14)));
    assertEquals(taken, info.getField(info.getDescriptorForType().findFieldByNumber(1)));
    assertTrue(message.getUnknownFields().asMap().isEmpty());
    var saved = fixture.gson.fromJson(session.savedAtChange.get(index), GameplayState.class);
    assertTrue(saved.getTrialCompletions().containsKey(STAGE), "Only saved victories may be published");
    var receipt = saved.getRewards().get(TrialRewards.key(STAGE));
    assertEquals(taken, receipt != null && receipt.isGranted());
  }

  @Test
  void victoryPublishesTheSavedUnclaimedStageBeforeLeavingWithoutARefreshQuery() {
    var player = new Recipient();
    var session = new Session(player);
    fixture.win(player);
    assertChange(session, 0, false);
    assertEquals(1, session.changes.size());
    assertTrue(session.names.indexOf("TrialActivityDataChangeScNotify")
        < session.names.indexOf("CurTrialActivityScNotify"));
    assertEquals(0, player.grants);
    assertEquals(0, player.getHcoin());
  }

  @Test
  void successfulClaimPublishesTakenStateAfterSavingAndBeforeTheReply() throws Exception {
    var player = new Recipient();
    var session = new Session(player);
    fixture.win(player);
    session.changes.clear();
    session.savedAtChange.clear();
    session.names.clear();
    fixture.claim(session, STAGE);
    assertChange(session, 0, true);
    assertEquals(List.of("TrialActivityDataChangeScNotify", "TakeTrialActivityRewardScRsp"), session.names);
    assertEquals(7, player.getHcoin());
    fixture.claim(session, STAGE);
    assertEquals(1, session.changes.size());
    assertEquals(1, player.grants);
  }

  @Test
  void rejectedClaimsDoNotPublishTakenStateAndAValidRetryCanStillNotify() throws Exception {
    var player = new Recipient();
    var session = new Session(player);
    fixture.win(player);
    var balance = emu.lunarcore.game.player.Player.class.getDeclaredField("hcoin");
    balance.setAccessible(true);
    balance.setInt(player, 999997);
    fixture.claim(session, STAGE);
    assertEquals(1, session.changes.size());
    assertChange(session, 0, false);
    balance.setInt(player, 0);
    fixture.claim(session, STAGE);
    assertChange(session, 1, true);
    assertEquals(1, player.grants);
  }

  @Test
  void failedVictorySaveCannotNotifyAnUncommittedCompletion() {
    var player = new Recipient();
    var session = new Session(player);
    var trial = player.getTrialManager().startTrial(STAGE);
    assertNotNull(trial);
    player.failSave = true;
    assertThrows(IllegalStateException.class, () -> trial.onBattleFinish(
        fixture.fixture.trialBattle(player, true, 42), BattleEndStatus.BATTLE_END_WIN,
        BattleStatistics.newInstance()));
    assertTrue(session.changes.isEmpty());
    assertFalse(fixture.gson.fromJson(player.saved, GameplayState.class).getTrialCompletions().containsKey(STAGE));
  }

  @Test
  void anExistingUnclaimedCompletionCanBeReadAndClaimedWithoutRepeatingTheTrial() throws Exception {
    var original = new Recipient();
    new Session(original);
    fixture.win(original);
    var restored = new Recipient();
    var state = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    state.setAccessible(true);
    state.set(restored, fixture.gson.fromJson(original.saved, GameplayState.class));
    var session = new Session(restored);
    fixture.query(session);
    var info = (DynamicMessage) ((List<?>) fixture.field(session.response, "trial_activity_info_list")).getFirst();
    assertEquals(STAGE, fixture.field(info, "stage_id"));
    assertEquals(false, fixture.field(info, "taken_reward"));
    assertTrue(session.changes.isEmpty(), "History queries remain read-only");
    assertNull(restored.getInstance());
    fixture.claim(session, STAGE);
    assertEquals(7, restored.getHcoin());
    assertEquals(0, restored.entryAttempts);
    assertChange(session, 0, true);
  }

  @Test
  void ordinaryExitAndUnplayedClaimDoNotInventACompletedStage() throws Exception {
    var player = new Recipient();
    var session = new Session(player);
    assertNotNull(player.getTrialManager().startTrial(STAGE));
    player.getTrialManager().leaveTrial(STAGE);
    fixture.claim(session, STAGE);
    assertTrue(session.changes.isEmpty());
    assertTrue(player.getGameplay().getTrialCompletions().isEmpty());
  }

  @Test
  void preLoginAndLegacyCompletionDoNotPublishModernNotifications() {
    var first = new Recipient();
    var waiting = new Session(first);
    waiting.setState(SessionState.WAITING_FOR_LOGIN);
    fixture.win(first);
    assertTrue(waiting.changes.isEmpty());
    var second = new Recipient();
    var legacy = new Session(second);
    legacy.legacy = true;
    fixture.win(second);
    assertTrue(legacy.changes.isEmpty());
  }
}
