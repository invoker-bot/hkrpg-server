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
 * Only original prerequisite history and map location may be seeded; rewards use real inventory.
 */
public final class KraterOfferingFixtureInspector {
  private static void require(boolean ok, String why) {
    if (!ok) throw new IllegalStateException(why);
  }

  public static void main(String[] args) throws Exception {
    var root = Path.of("").toRealPath();
    var dir = Path.of(args[0]).toRealPath();
    var input = Path.of(args[1]).toRealPath();
    String mode = args[2];
    require(
        dir.startsWith(root.resolve("build/smoke"))
            && !dir.equals(root.resolve("build/smoke"))
            && input.startsWith(dir)
            && Set.of("seed", "inspect", "expired").contains(mode),
        "Unowned fixture");
    var marker =
        JsonParser.parseString(
                Files.readString(dir.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    require(
        marker.get("testFixture").getAsBoolean()
            && marker.get("kraterOfferingFixture").getAsBoolean(),
        "Missing marker");
    var config = Files.readAllBytes(dir.resolve("config.json"));
    var control = JsonParser.parseString(Files.readString(input)).getAsJsonObject();
    int uid = control.get("uid").getAsInt();
    require(uid > 0, "Invalid UID");
    var field = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
    field.setAccessible(true);
    field.set(LunarCore.getConfig(), dir.resolve("resources-fixture").toString());
    ResourceLoader.loadResources();
    var floorLoader = ResourceLoader.class.getDeclaredMethod("loadFloorInfos");
    floorLoader.setAccessible(true);
    floorLoader.invoke(null);
    GameplayDefinitions.load(dir.resolve("resources-fixture"));
    GameplayDefinitions.windows.clear();
    if (!mode.equals("expired"))
      GameplayDefinitions.windows.add(
          new GameplayDefinitions.Window(30013, 3001301, 1, 4102444800L));
    var file = dir.resolve("data/database.mv");
    require(Files.isRegularFile(file), "Missing database");
    var backend = new H2Backend(file.toString());
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
          "Not one fixture player");
      var before = Objects.requireNonNull(players.find(filter).first());
      var updates = new Document();
      if (mode.equals("seed")) {
        var saved = before.get("gameplay", Document.class).get("kraterOffering", Document.class);
        require(
            saved == null
                || saved.getList("payments", Document.class).isEmpty()
                    && saved.getList("claims", Document.class).isEmpty()
                    && saved.get("quest") == null
                    && saved.get("goal") == null,
            "Prewritten offering progress or activity acceptance");
        updates.append("level", 21);
        for (int main : new int[] {1000510, 1040104}) {
          var q = new QuestState(main);
          q.advance(1, 1, 1);
          q.reserveReward();
          q.closeReward();
          updates.append("gameplay.mainMissions." + main, Document.parse(new Gson().toJson(q)));
        }
        var sub = new QuestState(104010403);
        sub.advance(1, Math.max(GameplayDefinitions.subs.get(104010403).getProgress(), 1), 1);
        updates.append("gameplay.subMissions.104010403", Document.parse(new Gson().toJson(sub)));
        updates
            .append("planeId", 10402)
            .append("floorId", 10402001)
            .append("entryId", 1040201)
            .append("worldId", 501)
            .append("pos", new Document("x", 0).append("y", 0).append("z", 0))
            .append("rot", new Document("x", 0).append("y", 0).append("z", 0));
        Files.writeString(
            dir.resolve("krater-start-balances.json"),
            new Document("hcoin", before.get("hcoin"))
                .append("scoin", before.get("scoin"))
                .append("mcoin", before.get("mcoin"))
                .append("exp", before.get("exp"))
                .toJson());
        Files.writeString(
            dir.resolve("krater-start-items.json"),
            new Document(
                    "items",
                    db.getCollection("items")
                        .find(new Document("ownerUid", uid))
                        .into(new ArrayList<Document>()))
                .toJson());
        Files.writeString(
            dir.resolve("krater-start-unlocks.json"),
            Objects.requireNonNull(db.getCollection("unlocks").find(Filters.eq("_id", uid)).first())
                .toJson());
        require(
            players.updateOne(filter, new Document("$set", updates)).getModifiedCount() == 1,
            "No unique fixture update");
        backend.commit();
        backend.getMvStore().sync();
      }
      var after = Objects.requireNonNull(players.find(filter).first());
      var expected =
          Document.parse(
              before.toJson(
                  org.bson.json.JsonWriterSettings.builder()
                      .outputMode(org.bson.json.JsonMode.EXTENDED)
                      .build()));
      for (var entry : updates.entrySet()) set(expected, entry.getKey(), entry.getValue());
      require(expected.equals(after), "Changed undeclared player data");
      var store = Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var player =
          store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first();
      require(player != null, "Morphia restore failed");
      var rules = KraterOfferingRules.load();
      var domain = player.getGameplay().getKraterOffering();
      require(
          rules != null && domain.valid(rules, player) && !domain.pending(),
          "Invalid original sources or journal");
      var wanted = control.getAsJsonObject("progress");
      var taken = new TreeSet<Integer>();
      wanted.getAsJsonArray("taken").forEach(v -> taken.add(v.getAsInt()));
      require(
          domain.paid() == wanted.get("total").getAsInt()
              && domain.level(rules) == wanted.get("level").getAsInt()
              && domain.exp(rules) == wanted.get("exp").getAsInt()
              && domain.taken().equals(taken),
          "Different restored offering counters");
      require(
          domain.getPayments().size() == control.get("payments").getAsInt()
              && domain.getClaims().size() == control.get("claims").getAsInt()
              && domain.tails() == control.get("tails").getAsInt(),
          "Different restored receipt counts");
      var q = domain.getQuest();
      boolean goal = control.get("completed").getAsBoolean();
      boolean closed = control.get("claimedGoal").getAsBoolean();
      require(
          QuestCompletionRecords.completed(player, 6029857) == goal
              && QuestCompletionRecords.closed(player, 6029857) == closed,
          "Different owned completion history");
      if (mode.equals("seed")) require(q == null && domain.getGoal() == null, "Prewritten goal");
      else
        require(
            q != null
                && q.getProgress() == (goal ? 1 : 0)
                && q.getPhase()
                    == (closed
                        ? QuestState.Phase.CLOSED
                        : goal ? QuestState.Phase.FINISHED : QuestState.Phase.DOING),
            "Different owned goal phase");
      if (mode.equals("expired"))
        require(
            ActivityKraterQuests.state(player, 6029857) == null, "Expired activity republished");
      require(!player.getQuestStates().containsKey(6029857), "Legacy quest masks source receipt");
      var payout = new TreeMap<Integer, Integer>();
      control
          .getAsJsonObject("payout")
          .entrySet()
          .forEach(e -> payout.put(Integer.parseInt(e.getKey()), e.getValue().getAsInt()));
      var balances = Document.parse(Files.readString(dir.resolve("krater-start-balances.json")));
      for (var e : Map.of("hcoin", 1, "scoin", 2, "mcoin", 3, "exp", 22).entrySet())
        require(
            ((Number) after.get(e.getKey())).longValue()
                == ((Number) balances.get(e.getKey())).longValue()
                    + payout.getOrDefault(e.getValue(), 0),
            "Actual currency/experience differs: " + e.getKey());
      var initial =
          Document.parse(Files.readString(dir.resolve("krater-start-items.json")))
              .getList("items", Document.class);
      var actual =
          db.getCollection("items")
              .find(new Document("ownerUid", uid))
              .into(new ArrayList<Document>());
      var originalItems = aggregate(initial);
      var actualItems = aggregate(actual);
      payout.forEach(
          (id, count) -> {
            if (id > 3 && id != 22 && id != 202023 && id != 221010)
              originalItems.merge(id, count, Math::addExact);
          });
      originalItems.merge(
          120004, control.get("suppliedMaterials").getAsInt() - domain.paid(), Math::addExact);
      originalItems.entrySet().removeIf(e -> e.getValue() == 0);
      actualItems.entrySet().removeIf(e -> e.getValue() == 0);
      require(
          originalItems.equals(actualItems),
          "Actual material/equipment inventory differs: "
              + actualItems
              + " expected="
              + originalItems);
      var unlocks =
          Objects.requireNonNull(db.getCollection("unlocks").find(Filters.eq("_id", uid)).first());
      var startUnlocks = Document.parse(Files.readString(dir.resolve("krater-start-unlocks.json")));
      for (var entry : Map.of("headIcons", 202023, "phoneThemes", 221010).entrySet()) {
        var first = new TreeSet<>(startUnlocks.getList(entry.getKey(), Integer.class));
        if (payout.getOrDefault(entry.getValue(), 0) > 0) first.add(entry.getValue());
        require(
            first.equals(new TreeSet<>(unlocks.getList(entry.getKey(), Integer.class))),
            "Actual cosmetic unlock differs");
      }
      require(
          after.equals(players.find(filter).first())
              && actual.equals(
                  db.getCollection("items")
                      .find(new Document("ownerUid", uid))
                      .into(new ArrayList<Document>()))
              && unlocks.equals(db.getCollection("unlocks").find(Filters.eq("_id", uid)).first())
              && Arrays.equals(config, Files.readAllBytes(dir.resolve("config.json"))),
          "Inspector changed data");
      var report = new LinkedHashMap<String, Object>();
      report.put("passed", true);
      report.put("uid", uid);
      report.put("mode", mode);
      report.put("paidMaterials", domain.paid());
      report.put("level", domain.level(rules));
      report.put("remaining", domain.exp(rules));
      report.put("payments", domain.getPayments().size());
      report.put("claims", domain.getClaims().size());
      report.put("tails", domain.tails());
      report.put("taken", taken);
      report.put("completed", goal);
      report.put("claimedGoal", closed);
      report.put("actualPayout", payout);
      report.put("changedPlayers", mode.equals("seed") ? 1 : 0);
      report.put("changedInventory", 0);
      report.put("changedUnlocks", 0);
      report.put("clientGameplayVerified", false);
      System.out.println(new Gson().toJson(report));
    } finally {
      server.shutdownNow();
    }
  }

  private static Map<Integer, Integer> aggregate(List<Document> items) {
    var result = new TreeMap<Integer, Integer>();
    items.forEach(
        item -> result.merge(item.getInteger("itemId"), item.getInteger("count"), Math::addExact));
    return result;
  }

  private static void set(Document document, String path, Object value) {
    var keys = path.split("\\.");
    var parent = document;
    for (int i = 0; i < keys.length - 1; i++) {
      if (!(parent.get(keys[i]) instanceof Document)) parent.put(keys[i], new Document());
      parent = parent.get(keys[i], Document.class);
    }
    parent.put(keys[keys.length - 1], value);
  }
}
