package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import dev.morphia.Morphia;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/** Only source prerequisite history and map locations may be seeded in an owned smoke database. */
public final class ActivityKillFixtureInspector {
  private static void require(boolean valid, String why) {
    if (!valid) throw new IllegalStateException(why);
  }

  public static void main(String[] args) throws Exception {
    var root = Path.of("").toRealPath();
    var dir = Path.of(args[0]).toRealPath();
    var input = Path.of(args[1]).toRealPath();
    require(
        dir.startsWith(root.resolve("build/smoke"))
            && !dir.equals(root.resolve("build/smoke"))
            && input.startsWith(dir),
        "Unowned fixture");
    var marker =
        JsonParser.parseString(
                Files.readString(dir.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    require(
        marker.get("testFixture").getAsBoolean()
            && marker.get("activityKillFixture").getAsBoolean(),
        "Missing marker");
    var config = Files.readAllBytes(dir.resolve("config.json"));
    var control = JsonParser.parseString(Files.readString(input)).getAsJsonObject();
    int uid = control.get("uid").getAsInt();
    String mode = args[2];
    require(
        uid > 0 && Set.of("seed", "move", "inspect", "expired").contains(mode),
        "Invalid operation");
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
        var gameplay = before.get("gameplay", Document.class);
        var saved = gameplay.get("activityKillQuests", Document.class);
        require(
            saved.get("quests", Document.class).isEmpty()
                && saved.get("receipts", Document.class).isEmpty(),
            "Prewritten activity acceptance or death counts");
        updates.append("level", 21);
        for (int main : new int[] {1000510, 1040105, 1040106}) {
          var q = new QuestState(main);
          q.advance(1, 1, 1);
          q.reserveReward();
          q.closeReward();
          updates.append("gameplay.mainMissions." + main, Document.parse(new Gson().toJson(q)));
        }
        Files.writeString(
            dir.resolve("activity-kill-start-balances.json"),
            new Document("hcoin", before.get("hcoin"))
                .append("scoin", before.get("scoin"))
                .append("mcoin", before.get("mcoin"))
                .toJson());
        Files.writeString(
            dir.resolve("activity-kill-start-items.json"),
            new Document(
                    "items",
                    db.getCollection("items")
                        .find(new Document("ownerUid", uid))
                        .into(new ArrayList<Document>()))
                .toJson());
      }
      if (mode.equals("seed") || mode.equals("move")) {
        var stage = control.getAsJsonObject("stage");
        updates
            .append("planeId", stage.get("plane").getAsInt())
            .append("floorId", stage.get("floor").getAsInt())
            .append("entryId", stage.get("entry").getAsInt())
            .append("worldId", stage.get("world").getAsInt())
            .append("pos", Document.parse(stage.get("pos").toString()))
            .append("rot", new Document("x", 0).append("y", 0).append("z", 0));
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
      for (var e : updates.entrySet()) set(expected, e.getKey(), e.getValue());
      require(expected.equals(after), "Changed undeclared player data");
      var store = Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var player =
          store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first();
      require(player != null, "Morphia restore failed");
      var field = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
      field.setAccessible(true);
      field.set(LunarCore.getConfig(), dir.resolve("resources-fixture").toString());
      ResourceLoader.loadResources();
      GameplayDefinitions.windows.clear();
      if (!mode.equals("expired"))
        GameplayDefinitions.windows.add(
            new GameplayDefinitions.Window(30013, 3001301, 1, 4102444800L));
      var rules = ActivityKillRules.load();
      var gameplay = player.getGameplay();
      var domain = gameplay.getActivityKillQuests();
      require(
          rules != null && domain.valid(rules),
          "Invalid original source or restored death receipts");
      var payout = new TreeMap<Integer, Integer>();
      int receipts = 0, completed = 0;
      var expectedProgress = control.getAsJsonObject("progress");
      boolean claimed = control.get("claimed").getAsBoolean();
      for (var raw : control.getAsJsonArray("allGoals")) {
        var goal = raw.getAsJsonObject();
        int id = goal.get("id").getAsInt(), target = goal.get("goal").getAsInt();
        int count = expectedProgress.get(String.valueOf(id)).getAsInt();
        var state = domain.getQuests().get(id);
        var history = domain.getReceipts().getOrDefault(id, List.of());
        require(history.size() == count, "Wrong restored death count " + id);
        receipts += history.size();
        if (mode.equals("seed")) require(state == null, "Activity acceptance prewritten");
        else {
          require(
              state != null
                  && state.getQuestId() == id
                  && state.getProgress() == count
                  && state.getPhase()
                      == (claimed
                          ? QuestState.Phase.CLOSED
                          : count == target ? QuestState.Phase.FINISHED : QuestState.Phase.DOING),
              "Incorrect restored activity state " + id);
          require(
              QuestCompletionRecords.completed(player, id) == (count == target),
              "Completion history mismatch " + id);
          require(
              QuestCompletionRecords.closed(player, id) == claimed,
              "Reward closure mismatch " + id);
          if (mode.equals("expired"))
            require(
                ActivityKillQuests.state(player, id) == null, "Expired activity republished " + id);
        }
        if (count == target) completed++;
        require(
            !player.getQuestStates().containsKey(id), "Legacy duplicate masks owned receipt " + id);
        for (var r : history)
          require(
              r.getMode() == 2
                  && r.getBegin() == 1
                  && r.getEnd() == 4102444800L
                  && Set.of(20411, 20412).contains(r.getPlane()),
              "Source death context changed");
        if (claimed)
          goal.getAsJsonObject("rewards")
              .entrySet()
              .forEach(
                  e ->
                      payout.merge(
                          Integer.parseInt(e.getKey()), e.getValue().getAsInt(), Math::addExact));
      }
      control
          .getAsJsonObject("battleDrops")
          .entrySet()
          .forEach(
              e ->
                  payout.merge(
                      Integer.parseInt(e.getKey()), e.getValue().getAsInt(), Math::addExact));
      var balances =
          Document.parse(Files.readString(dir.resolve("activity-kill-start-balances.json")));
      for (String currency : List.of("hcoin", "scoin", "mcoin"))
        require(
            ((Number) after.get(currency)).longValue()
                == ((Number) balances.get(currency)).longValue()
                    + payout.getOrDefault(
                        currency.equals("hcoin") ? 1 : currency.equals("scoin") ? 2 : 3, 0),
            "Currency payout mismatch "
                + currency
                + " actual="
                + after.get(currency)
                + " initial="
                + balances.get(currency)
                + " payouts="
                + payout);
      var initial =
          Document.parse(Files.readString(dir.resolve("activity-kill-start-items.json")))
              .getList("items", Document.class);
      var actual =
          db.getCollection("items")
              .find(new Document("ownerUid", uid))
              .into(new ArrayList<Document>());
      var first = new TreeMap<Integer, Integer>();
      var second = new TreeMap<Integer, Integer>();
      initial.forEach(
          item -> first.merge(item.getInteger("itemId"), item.getInteger("count"), Math::addExact));
      actual.forEach(
          item ->
              second.merge(item.getInteger("itemId"), item.getInteger("count"), Math::addExact));
      payout.forEach(
          (id, count) -> {
            if (id > 3) first.merge(id, count, Math::addExact);
          });
      require(first.equals(second), "Material payout mismatch");
      require(
          after.equals(players.find(filter).first())
              && Arrays.equals(config, Files.readAllBytes(dir.resolve("config.json"))),
          "Inspector changed data");
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
                      "completed",
                      completed,
                      "receipts",
                      receipts,
                      "changedPlayers",
                      mode.equals("seed") || mode.equals("move") ? 1 : 0,
                      "clientGameplayVerified",
                      false)));
    } finally {
      server.shutdownNow();
    }
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
