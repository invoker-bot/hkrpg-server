package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.game.quest.QuestState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The seventh original report has three distinct player-selected research topics. */
class MonsterResearchSelection46Test extends MonsterResearchDelivery46Test {
  @Test void anAbsentOrUnknownChoiceCannotSpendOrComplete() {
    int id = accepted(8000177); stock(8000177); var before = stocks();
    assertNotEquals(Retcode.SUCC, player.getMissionManager().finishTalk(id, "Mission_" + id));
    assertNotEquals(Retcode.SUCC, player.getMissionManager().finishTalk(0, "ResearchD_800017705"));
    assertEquals(before, stocks()); assertEquals(0, player.grants);
  }
  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  void selectedResearchTopicSurvivesTheOriginalCostAndReward(int choice) {
    int id = accepted(8000177); var row = stock(8000177); var before = stocks();
    assertEquals(Retcode.SUCC, player.getMissionManager().finishTalk(0, "Research" + (char)('A' + choice - 1) + "_800017705"));
    var receipt = GSON.toJsonTree(player.getGameplay()).getAsJsonObject()
        .getAsJsonObject("activityReplays").getAsJsonObject("legacyMaterials")
        .getAsJsonObject("30005").getAsJsonObject("receipts").getAsJsonObject("7");
    assertEquals(choice, receipt.get("researchChoice").getAsInt());
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(800017703).getPhase(),
        "The original follow-up message remains a separate mission objective");
    row.costs().forEach((item, count) -> assertEquals(before.get(item) - count, stocks().get(item)));
    assertEquals(1, player.grants);
    assertNotEquals(Retcode.SUCC, player.getMissionManager().finishTalk(id, "Research" + (char)('A' + choice - 1) + "_800017705"));
    assertEquals(1, player.grants);
  }

  @Test void aSavedChoiceCannotBeChangedDuringCompletionRetry() {
    int id = accepted(8000177); stock(8000177); player.failSaveAt = 5;
    assertThrows(IllegalStateException.class,
        () -> player.getMissionManager().finishTalk(id, "ResearchB_800017705"));
    var after = stocks(); player.failSaveAt = 0;
    assertNotEquals(Retcode.SUCC, player.getMissionManager().finishTalk(id, "ResearchA_800017705"));
    assertEquals(after, stocks()); assertEquals(1, player.grants);
    assertEquals(Retcode.SUCC, player.getMissionManager().finishTalk(id, "ResearchB_800017705"));
    assertEquals(after, stocks()); assertEquals(1, player.grants);
  }
}
