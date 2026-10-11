package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import emu.lunarcore.game.quest.QuestState;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Completed prerequisites get independent identity without changing ordinary doing observers. */
class QuestCompletedObservation46Test {
  static Stream<Arguments> invalidCompleted() {
    return Stream.of(
        Arguments.of(QuestState.Phase.DOING, 0, 0L),
        Arguments.of(QuestState.Phase.DOING, 1, 1L),
        Arguments.of(QuestState.Phase.FINISHED, 0, 1L),
        Arguments.of(QuestState.Phase.FINISHED, 1, 0L),
        Arguments.of(QuestState.Phase.CLOSED, -1, 1L),
        Arguments.of(QuestState.Phase.CLOSED, 1, -1L),
        Arguments.of(QuestState.Phase.CLAIMING, 1, 1L),
        Arguments.of(null, 1, 1L));
  }
  @ParameterizedTest @MethodSource("invalidCompleted")
  void unfinishedOrMalformedStateCannotBecomeACompletedObserver(QuestState.Phase phase, int progress, long time) throws Exception {
    var state=new QuestState(300060001);
    DailyMissionAcceptance46Test.set(state,"phase",phase);
    DailyMissionAcceptance46Test.set(state,"progress",progress);
    DailyMissionAcceptance46Test.set(state,"finishTime",time);
    assertThrows(IllegalStateException.class,state::captureCompletedObservationKey);
    assertNull(state.getObservationKey());
  }
  @ParameterizedTest @ValueSource(booleans={false,true})
  void aRealFinishedOrClosedPrerequisiteKeepsItsPayloadAndCanRollBackIdentity(boolean closed) {
    var state=new QuestState(300060001); assertTrue(state.advance(1,1,123));
    if(closed){assertTrue(state.reserveReward()); state.closeReward();}
    var phase=state.getPhase(); String key=state.captureCompletedObservationKey();
    assertEquals(key,UUID.fromString(key).toString()); assertEquals(key,state.captureCompletedObservationKey());
    assertEquals(phase,state.getPhase()); assertEquals(1,state.getProgress()); assertEquals(123,state.getFinishTime());
    assertThrows(IllegalStateException.class,state::captureObservationKey);
    state.restoreObservationKey(key,null); assertNull(state.getObservationKey()); assertEquals(phase,state.getPhase());
  }
  @Test void anExistingDoingObserverBecomesTheSameCompletedPrerequisiteIdentity(){
    var state=new QuestState(300060101); String key=state.captureObservationKey();
    assertTrue(state.advance(1,1,123)); assertEquals(key,state.captureCompletedObservationKey());
    assertThrows(IllegalStateException.class,()->state.restoreObservationKey(UUID.randomUUID().toString(),null));
    assertEquals(key,state.getObservationKey());
  }
  @ParameterizedTest @ValueSource(strings={"", "not-a-uuid", "1-1-1-1-1"})
  void aMalformedOldIdentityIsRefusedAndNeverReplaced(String invalid) throws Exception {
    var state=new QuestState(300060101); assertTrue(state.advance(1,1,123));
    DailyMissionAcceptance46Test.set(state,"observationKey",invalid);
    assertThrows(RuntimeException.class,state::captureCompletedObservationKey); assertEquals(invalid,state.getObservationKey());
  }
}
