package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.game.challenge.ChallengeHistory;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.Test;

class ChallengeHistoryStarsTest {
  @Test
  void complementaryWinsKeepEveryPreviouslyEarnedObjective() {
    var history = new ChallengeHistory();
    history.setStars(3);
    history.setStars(5);
    assertEquals(7, history.getStars());
    assertEquals(3, history.getTotalStars());
  }

  @Test
  void everyPairOfValidResultsAccumulatesIndependentObjectives() {
    for (int first = 0; first < 8; first++) {
      for (int second = 0; second < 8; second++) {
        var history = new ChallengeHistory();
        history.setStars(first);
        history.setStars(second);
        assertEquals(first | second, history.getStars(), first + "/" + second);
        assertEquals(Integer.bitCount(first | second), history.getTotalStars());
      }
    }
  }

  @Test
  void zeroAndRepeatedWinsNeverEraseAnEarnedObjective() {
    var history = new ChallengeHistory();
    history.setStars(6);
    history.setStars(0);
    history.setStars(2);
    history.setStars(6);
    assertEquals(6, history.getStars());
  }

  @Test
  void completionOrderDoesNotChangeTheHistory() {
    var first = new ChallengeHistory();
    var second = new ChallengeHistory();
    first.setStars(1);
    first.setStars(6);
    second.setStars(6);
    second.setStars(1);
    assertEquals(7, first.getStars());
    assertEquals(first.getStars(), second.getStars());
  }

  @Test
  void savedHistoryKeepsOldObjectivesWhenAnotherRunCompletes() {
    var gson = new Gson();
    var history =
        gson.fromJson("{\"challengeId\":1,\"stars\":5,\"takenReward\":1}", ChallengeHistory.class);
    history.setStars(2);
    history = gson.fromJson(gson.toJson(history), ChallengeHistory.class);
    assertEquals(7, history.getStars());
    assertEquals(1, history.getTakenReward());
    assertEquals(1, history.getChallengeId());
  }

  @Test
  void legacyZeroHistoryStartsWithoutInventedStars() {
    var history = new Gson().fromJson("{\"challengeId\":1}", ChallengeHistory.class);
    assertEquals(0, history.getStars());
    history.setStars(4);
    assertEquals(1, history.getTotalStars());
    assertEquals(4, history.getStars());
  }

  @Test
  void unsupportedMasksCannotCreateEarnedObjectives() {
    var history = new ChallengeHistory();
    history.setStars(2);
    for (int invalid : new int[] {-1, 8, 9, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
      history.setStars(invalid);
      assertEquals(2, history.getStars());
    }
  }

  @Test
  void nativeHistoryReplyUsesTheAccumulatedMask() throws Exception {
    var history = new ChallengeHistory();
    history.setChallengeId(15);
    history.setStars(3);
    history.setStars(4);
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var reply =
        bridge
            .clientSchema()
            .parse(
                "Challenge", bridge.translate("Challenge", history.toProto().toByteArray(), true));
    assertEquals(7, reply.getField(reply.getDescriptorForType().findFieldByName("star")));
    assertEquals(15, reply.getField(reply.getDescriptorForType().findFieldByName("challenge_id")));
    assertTrue(reply.getUnknownFields().asMap().isEmpty());
  }
}
