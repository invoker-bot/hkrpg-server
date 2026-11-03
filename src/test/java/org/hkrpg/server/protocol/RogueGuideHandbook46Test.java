package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.protobuf.DynamicMessage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The native handbook must not treat a missing permanent guide as completed. */
class RogueGuideHandbook46Test {
  private static DynamicMessage query(PlayerReturnInitial46Test.Session session) throws Exception {
    assertTrue(VersionedPacketHandler.handle(session, 6029, new byte[0]));
    return session.bridge.clientSchema().parse(
        "RogueTournQueryScRsp", session.nativePackets.getLast().body());
  }

  private static List<DynamicMessage> areas(DynamicMessage reply) {
    var profile = (DynamicMessage) InitialData46Test.field(reply, "LFLCDNGALME");
    @SuppressWarnings("unchecked")
    var result = (List<DynamicMessage>) InitialData46Test.field(profile, "DANNIDIPFAL");
    return result;
  }

  @Test void unplayedProfileIncludesTheOriginalUnpassedPermanentGuide() throws Exception {
    var reply = query(new PlayerReturnInitial46Test.Session());
    var rows = areas(reply);
    assertEquals(1, rows.size(), "The original permanent guide 101 must be represented");
    var row = rows.getFirst();
    assertEquals(101, InitialData46Test.field(row, "area_id"));
    assertEquals(1, row.getAllFields().size(), "Only a catalog reference is initialized");
    assertEquals(false, InitialData46Test.field(row, "is_taken_reward"));
    assertEquals(false, InitialData46Test.field(row, "FJKHIFDJMDB"));
    assertEquals(false, InitialData46Test.field(row, "is_unlock"));
    assertFalse(reply.hasField(reply.getDescriptorForType().findFieldByNumber(5)));
  }

  @Test void nativeAllGuidesPassedPredicateMustKeepTheLegacyHandbookCategoryVisible()
      throws Exception {
    var rows = areas(query(new PlayerReturnInitial46Test.Session()));
    // df70310 returns true if its collection has no unfinished permanent guide.
    // The old empty snapshot made the handbook suppress its entire RogueBeta tab.
    boolean allPermanentGuidesPassed = rows.stream()
        .filter(row -> (int) InitialData46Test.field(row, "area_id") == 101)
        .allMatch(row -> (boolean) InitialData46Test.field(row, "FJKHIFDJMDB"));
    assertFalse(allPermanentGuidesPassed,
        "An unplayed guide must not hide the classical universe cards");
  }

  @Test void repeatedHandbookInitializationDoesNotChangePlayerProgress() throws Exception {
    var session = new PlayerReturnInitial46Test.Session();
    String before = new Gson().toJson(session.player.getGameplay());
    var first = query(session);
    var second = query(session);
    assertEquals(first, second);
    assertEquals(before, new Gson().toJson(session.player.getGameplay()));
    assertEquals(0, InitialData46Test.field(first, "retcode"));
    assertEquals(0, session.player.getGameplay().getRogue().getAreaId());
  }

  @Test void originalCatalogLinksThePermanentGuideAndFourLegacyCardsWithoutOpeningNewModes()
      throws Exception {
    try (var stream = getClass().getResourceAsStream("/fixtures/rogue-guide-handbook-4.6.json")) {
      assertNotNull(stream);
      var records = new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8),
          JsonObject.class).getAsJsonObject("records");
      var guide = records.getAsJsonArray("RogueTournArea").get(0).getAsJsonObject();
      assertEquals(101, guide.get("BEOFPCAACEP").getAsInt());
      assertEquals("Guide", guide.get("PJGJLMIODBD").getAsString());
      assertFalse(guide.has("HILINOJPLGA"), "Permanent guide uses the authored default season");
      var tabs = records.getAsJsonArray("GuideRogueTab");
      assertTrue(tabs.asList().stream().map(row -> row.getAsJsonObject()).anyMatch(row ->
          row.get("ID").getAsInt() == 1002 && row.get("GuideType").getAsString().equals("RogueBeta")));
      var legacy = records.getAsJsonArray("GuideRogueData").asList().stream()
          .map(row -> row.getAsJsonObject()).filter(row -> row.get("TabID").getAsInt() == 1002).toList();
      assertEquals(4, legacy.size());
      assertTrue(legacy.stream().allMatch(row -> row.getAsJsonArray("UnlockConditions").isEmpty()),
          "The legacy cards have no visibility gate; their original OpenConditions still apply");
    }
  }
}
