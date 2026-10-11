package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import dev.morphia.Morphia;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/**
 * Only prerequisite 1040505 may be seeded; the second offering must be paid over the game socket.
 */
public final class WorldShopOfferingFixtureInspector {
  private static void require(boolean value, String why) {
    if (!value) throw new IllegalStateException(why);
  }

  public static void main(String[] args) throws Exception {
    var root = Path.of("").toRealPath();
    var dir = Path.of(args[0]).toRealPath();
    var input = Path.of(args[1]).toRealPath();
    String mode = args[2];
    require(
        dir.startsWith(root.resolve("build/smoke"))
            && input.startsWith(dir)
            && Set.of("seed", "inspect").contains(mode),
        "Unowned fixture");
    var marker =
        JsonParser.parseString(
                Files.readString(dir.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    require(
        marker.get("testFixture").getAsBoolean()
            && marker.get("worldShopOfferingFixture").getAsBoolean(),
        "Missing owned marker");
    byte[] config = Files.readAllBytes(dir.resolve("config.json"));
    var control = JsonParser.parseString(Files.readString(input)).getAsJsonObject();
    int uid = control.get("uid").getAsInt();
    var field = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
    field.setAccessible(true);
    field.set(LunarCore.getConfig(), dir.resolve("resources-fixture").toString());
    ResourceLoader.loadResources();
    var floor = ResourceLoader.class.getDeclaredMethod("loadFloorInfos");
    floor.setAccessible(true);
    floor.invoke(null);
    GameplayDefinitions.load(dir.resolve("resources-fixture"));
    var backend = new H2Backend(dir.resolve("data/database.mv").toString());
    var server = new MongoServer(backend);
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var db = client.getDatabase("hkrpg");
      var players = db.getCollection("players");
      var filter = Filters.eq("_id", uid);
      require(
          players.countDocuments() == 1 && players.countDocuments(filter) == 1,
          "Not one original fixture player");
      var before = Objects.requireNonNull(players.find(filter).first());
      var oldSix = before.get("gameplay", Document.class).get("kraterOffering", Document.class);
      var initialItems =
          db.getCollection("items")
              .find(new Document("ownerUid", uid))
              .into(new ArrayList<Document>());
      if (mode.equals("seed")) {
        var saved = before.get("gameplay", Document.class).get("worldShopOffering", Document.class);
        require(
            saved == null
                || saved.getList("payments", Document.class).isEmpty()
                    && saved.getList("claims", Document.class).isEmpty()
                    && saved.get("quest") == null
                    && saved.get("goal") == null,
            "Prewritten world-shop progress");
        var prerequisite = new QuestState(1040505);
        prerequisite.advance(1, 1, 1);
        prerequisite.reserveReward();
        prerequisite.closeReward();
        var change = Document.parse(new Gson().toJson(prerequisite));
        players.updateOne(
            filter, new Document("$set", new Document("gameplay.mainMissions.1040505", change)));
        backend.commit();
        backend.getMvStore().sync();
        var expected =
            Document.parse(
                before.toJson(
                    org.bson.json.JsonWriterSettings.builder()
                        .outputMode(org.bson.json.JsonMode.EXTENDED)
                        .build()));
        expected
            .get("gameplay", Document.class)
            .get("mainMissions", Document.class)
            .put("1040505", change);
        require(
            expected.equals(players.find(filter).first()),
            "Changed data outside source prerequisite");
        Files.writeString(
            dir.resolve("world-shop-start.json"),
            new Document("player", before).append("items", initialItems).toJson());
      }
      var after = Objects.requireNonNull(players.find(filter).first());
      require(
          oldSix.equals(
              after.get("gameplay", Document.class).get("kraterOffering", Document.class)),
          "Seed changed offering six");
      var store = Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var player =
          store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first();
      require(player != null, "Morphia restore failed");
      var rules = KraterOfferingRules.load(10);
      var domain = player.getGameplay().getWorldShopOffering();
      require(
          rules != null && domain.valid(rules, player) && !domain.pending(),
          "Invalid source or receipts");
      require(rules.unlocked(player), "Original main prerequisite not restored");
      var progress = control.getAsJsonObject("progress");
      var taken = new TreeSet<Integer>();
      progress.getAsJsonArray("taken").forEach(v -> taken.add(v.getAsInt()));
      require(
          domain.paid() == progress.get("total").getAsInt()
              && domain.level(rules) == progress.get("level").getAsInt()
              && domain.exp(rules) == progress.get("exp").getAsInt()
              && domain.taken().equals(taken),
          "Different restored source progress");
      require(
          domain.getPayments().size() == control.get("payments").getAsInt()
              && domain.getClaims().size() == control.get("claims").getAsInt()
              && domain.tails() == control.get("tails").getAsInt()
              && domain.getQuest() == null
              && domain.getGoal() == null,
          "Different restored journal");
      var previous = player.getGameplay().getKraterOffering();
      require(
          previous.valid(KraterOfferingRules.load(6), player)
              && previous.paid() == 18800
              && previous.taken().size() == 50
              && previous.tails() == 3,
          "Offering six history changed");
      var start = Document.parse(Files.readString(dir.resolve("world-shop-start.json")));
      var baseline = start.get("player", Document.class);
      var payout = new TreeMap<Integer, Integer>();
      control
          .getAsJsonObject("payout")
          .entrySet()
          .forEach(e -> payout.put(Integer.parseInt(e.getKey()), e.getValue().getAsInt()));
      for (var e : Map.of("hcoin", 1, "scoin", 2, "mcoin", 3, "exp", 22).entrySet())
        require(
            ((Number) after.get(e.getKey())).longValue()
                == ((Number) baseline.get(e.getKey())).longValue()
                    + payout.getOrDefault(e.getValue(), 0),
            "Actual currency differs: " + e.getKey());
      var expected = aggregate(start.getList("items", Document.class));
      payout.forEach(
          (id, count) -> {
            if (id > 3 && id != 22) expected.merge(id, count, Math::addExact);
          });
      expected.merge(
          120005, control.get("suppliedMaterials").getAsInt() - domain.paid(), Math::addExact);
      expected.values().removeIf(n -> n == 0);
      var actual =
          aggregate(
              db.getCollection("items")
                  .find(new Document("ownerUid", uid))
                  .into(new ArrayList<Document>()));
      actual.values().removeIf(n -> n == 0);
      require(expected.equals(actual), "Actual stored material totals differ");
      require(
          Arrays.equals(config, Files.readAllBytes(dir.resolve("config.json")))
              && after.equals(players.find(filter).first()),
          "Inspector changed data");
      require(
          initialItems.equals(
              db.getCollection("items")
                  .find(new Document("ownerUid", uid))
                  .into(new ArrayList<Document>())),
          "Inspector changed inventory");
      System.out.println(
          new Gson()
              .toJson(
                  Map.of(
                      "passed",
                      true,
                      "uid",
                      uid,
                      "mode",
                      mode,
                      "paidMaterials",
                      domain.paid(),
                      "level",
                      domain.level(rules),
                      "tails",
                      domain.tails(),
                      "taken",
                      taken,
                      "offeringSixRetained",
                      true,
                      "changedPlayers",
                      mode.equals("seed") ? 1 : 0,
                      "changedInventory",
                      0)));
    } finally {
      server.shutdownNow();
    }
  }

  private static Map<Integer, Integer> aggregate(List<Document> rows) {
    var result = new TreeMap<Integer, Integer>();
    rows.forEach(
        row -> result.merge(row.getInteger("itemId"), row.getInteger("count"), Math::addExact));
    return result;
  }
}
