package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.player.Player;
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

  @Test void selectedTopicAndReceiptSurviveRealPlayerDocumentReads() throws Exception {
    int id = accepted(8000177); stock(8000177);
    assertEquals(Retcode.SUCC, player.getMissionManager().finishTalk(id, "ResearchB_800017705"));
    var expectedStock = stocks(); var expected = GSON.toJsonTree(player.getGameplay());
    var server = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client = com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var codecs = org.bson.codecs.configuration.CodecRegistries.fromCodecs(
          new emu.lunarcore.database.codecs.IntSetCodec(), new emu.lunarcore.database.codecs.IntListCodec(),
          new emu.lunarcore.database.codecs.Int2IntMapCodec());
      var options = dev.morphia.mapping.MapperOptions.builder().storeEmpties(true).storeNulls(false).codecProvider(codecs).build();
      var store = dev.morphia.Morphia.createDatastore(client, "monster-research-fixture", options);
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, emu.lunarcore.util.Position.class);
      var document = new Player(); var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true); uid.setInt(document, 194815);
      var gameplay = Player.class.getDeclaredField("gameplay"); gameplay.setAccessible(true);
      gameplay.set(document, player.getGameplay()); store.save(document);
      Player previous = null;
      for (int read = 0; read < 2; read++) {
        var restored = java.util.Objects.requireNonNull(store.find(Player.class).first());
        assertNotSame(previous, restored); previous = restored;
        assertEquals(expected, GSON.toJsonTree(restored.getGameplay()));
        assertEquals(2, GSON.toJsonTree(restored.getGameplay()).getAsJsonObject()
            .getAsJsonObject("activityReplays").getAsJsonObject("legacyMaterials")
            .getAsJsonObject("30005").getAsJsonObject("receipts").getAsJsonObject("7")
            .get("researchChoice").getAsInt());
      }
      gameplay.set(player, previous.getGameplay());
      assertNotEquals(Retcode.SUCC, player.getMissionManager().finishTalk(id, "ResearchB_800017705"));
      assertEquals(expectedStock, stocks()); assertEquals(1, player.grants);
    } finally {
      server.shutdownNow();
    }
  }
}
