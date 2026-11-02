package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.IntStream;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Original 16-level performance rewards; prerequisite completion and stock are isolated fixtures. */
class MusicOfferingRewardTest {
  @TempDir Path root;
  final OfferingQuery46Test query = new OfferingQuery46Test();
  final JsonObject records = JsonParser.parseReader(new InputStreamReader(
      getClass().getResourceAsStream("/fixtures/music-offering-4.6.json"), StandardCharsets.UTF_8))
      .getAsJsonObject().getAsJsonObject("records");

  @BeforeEach void setup() throws Exception {
    query.root = root; query.setup();
    for (var entry : records.entrySet()) {
      var path = root.resolve(entry.getKey());
      var merged = Files.exists(path) ? JsonParser.parseString(Files.readString(path)).getAsJsonArray() : new JsonArray();
      if (entry.getKey().endsWith("/OfferingTypeConfig.json")) {
        assertEquals(entry.getValue().getAsJsonArray().get(0), merged.asList().stream()
            .filter(row -> row.getAsJsonObject().get("ID").getAsInt() == 5).findFirst().orElseThrow());
        continue; // The original complete table order is source-pinned by the query catalog.
      }
      String key = entry.getKey().contains("RewardData") ? "RewardID" : "ID";
      if (entry.getKey().contains("OfferingLevelConfig")) key = "TypeID";
      final String idKey = key;
      var ids = new HashSet<Integer>();
      entry.getValue().getAsJsonArray().forEach(v -> ids.add(v.getAsJsonObject().get(idKey).getAsInt()));
      var combined = new JsonArray();
      for (var row : merged) if (!ids.contains(row.getAsJsonObject().get(idKey).getAsInt())) combined.add(row);
      entry.getValue().getAsJsonArray().forEach(combined::add);
      query.fixture.write(entry.getKey(), combined);
      var table = query.fixture.table(path.getFileName().toString().replace(".json", ""));
      if (table != null) for (var row : entry.getValue().getAsJsonArray()) {
        var decoded = query.gson.fromJson(row, table.type()); decoded.onLoad(); table.map().add(decoded);
      }
    }
    assertNotNull(OfferingCatalog.read(5), "Original music level/cost catalog must load");
    assertNotNull(OfferingQueryCatalog.read(), "Original music prerequisite catalog must load at " + root);
  }

  @AfterEach void cleanup() throws Exception { query.cleanup(); }

  void stock(int count) {
    query.fixture.player.stock.put(270003,
        new GameItem(GameData.getItemExcelMap().get(270003), count) { public void save() {} });
  }

  DynamicMessage request(String name, List<Integer> levels) throws Exception {
    var session = query.fixture.session;
    var descriptor = session.protocol.clientSchema().message(name);
    var b = DynamicMessage.newBuilder(descriptor);
    b.setField(descriptor.findFieldByName("offering_id"), 5);
    if (name.equals("TakeOfferingRewardCsReq")) levels.forEach(v -> b.addRepeatedField(descriptor.findFieldByName("take_reward_level_list"), v));
    assertTrue(VersionedPacketHandler.handle(session, session.protocol.clientCommand(name), b.build().toByteArray()));
    assertEquals(name.replace("CsReq", "ScRsp"), session.name);
    return session.response;
  }

  int submit() throws Exception { return KraterOffering46Test.number(request("SubmitOfferingItemCsReq", List.of()), "retcode"); }
  int claim(Integer... levels) throws Exception { return KraterOffering46Test.number(request("TakeOfferingRewardCsReq", List.of(levels)), "retcode"); }
  void qualify() { query.completeMain(8026101); }

  @Test void threeActualBadgesBuyTheFirstOriginalPerformanceRewardOnce() throws Exception {
    qualify(); stock(3);
    assertEquals(0, submit(), "Native offering 5 currently rejects the actual music badges");
    assertEquals(0, query.fixture.player.stock.get(270003).getCount());
    assertEquals(0, claim(1));
    assertEquals(60, query.fixture.player.delivered.get(1));
    assertEquals(100, query.fixture.player.delivered.get(22));
    assertEquals(10000, query.fixture.player.delivered.get(2));
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal(), claim(1));
    assertEquals(1, query.fixture.player.grants);
    var info = query.info(5);
    assertEquals(1, KraterOffering46Test.number(info, "offering_level"));
    assertEquals(3, KraterOffering46Test.number(info, "total_exp"));
    assertEquals(List.of(1), KraterOffering46Test.field(info, "has_taken_reward_id_list"));
  }

  @Test void theOriginalIntroPrerequisiteRejectsSubmissionWithoutConsumingBadges() throws Exception {
    stock(3);
    assertEquals(Retcode.SHOP_NOT_OPEN.getVal(), submit());
    assertEquals(3, query.fixture.player.stock.get(270003).getCount());
    assertTrue(query.fixture.player.getGameplay().getOfferings().isEmpty());
  }

  @Test void allSixteenLevelsUseTheirSourceRewardsAndKeepBadgesBeyondTheCap() throws Exception {
    qualify(); stock(50);
    assertEquals(0, submit());
    assertEquals(2, query.fixture.player.stock.get(270003).getCount());
    assertEquals(16, KraterOffering46Test.number(query.info(5), "offering_level"));
    assertEquals(0, claim(IntStream.rangeClosed(1, 16).boxed().toArray(Integer[]::new)));
    assertEquals(180, query.fixture.player.delivered.get(1));
    assertEquals(1600, query.fixture.player.delivered.get(22));
    assertEquals(160000, query.fixture.player.delivered.get(2));
    assertEquals(1, query.fixture.player.delivered.get(241));
    assertEquals(1, query.fixture.player.delivered.get(220007));
    assertNotEquals(0, submit());
    assertEquals(2, query.fixture.player.stock.get(270003).getCount());
    var mixed = query.query(List.of(1, 5, 6, 12));
    assertEquals(0, KraterOffering46Test.number(mixed, "retcode"));
    assertEquals(16, KraterOffering46Test.number(OfferingQuery46Test.infos(mixed).get(1), "offering_level"));
  }

  @Test void unearnedOrDuplicateLevelBatchesCannotGrantAnything() throws Exception {
    qualify(); stock(3); assertEquals(0, submit());
    assertNotEquals(0, claim(2));
    assertNotEquals(0, claim(1, 1));
    assertEquals(0, query.fixture.player.grants);
    assertEquals(0, claim(1));
  }

  @Test void anIncompleteGrantRetainsItsReservationAndDoesNotAutomaticallyPayAgain() throws Exception {
    qualify(); stock(3); assertEquals(0, submit());
    query.fixture.player.failGrant = true;
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), claim(1));
    query.fixture.player.failGrant = false;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), claim(1));
    assertEquals(1, query.fixture.player.grants);
    assertTrue(Offerings.taken(query.fixture.player.getGameplay().getOfferings().get(5)).isEmpty());
  }

  @Test void failedReservationSaveCannotConsumeBadgesOrLeaveAnUnsavedProgressRecord() throws Exception {
    qualify(); stock(3);
    query.fixture.player.failOnSave = query.fixture.player.saves + 1;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), submit());
    assertEquals(3, query.fixture.player.stock.get(270003).getCount());
    assertFalse(query.fixture.player.getGameplay().getOfferings().containsKey(5));
    query.fixture.player.failOnSave = -1;
    assertEquals(0, submit());
  }
}
