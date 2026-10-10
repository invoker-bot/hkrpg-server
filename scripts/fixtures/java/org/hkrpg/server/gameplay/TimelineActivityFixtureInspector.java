package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import dev.morphia.Morphia;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.bson.Document;

/** Only source prerequisite history and map locations may be seeded in an owned smoke database. */
public final class TimelineActivityFixtureInspector {
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
            && marker.get("timelineActivityFixture").getAsBoolean(),
        "Missing marker");
    var config = Files.readAllBytes(dir.resolve("config.json"));
    var control = JsonParser.parseString(Files.readString(input)).getAsJsonObject();
    int uid = control.get("uid").getAsInt();
    String mode = args[2];
    require(uid > 0 && Set.of("seed", "move", "inspect").contains(mode), "Invalid operation");
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
        require(
            gameplay.get("propTimelines", Document.class).get("records", Document.class).isEmpty(),
            "Prewritten timeline proof");
        require(
            gameplay
                .get("timelineActivityQuests", Document.class)
                .get("receipts", Document.class)
                .isEmpty(),
            "Prewritten counter proof");
        require(
            gameplay
                .get("timelineActivityQuests", Document.class)
                .get("quests", Document.class)
                .isEmpty(),
            "Prewritten activity acceptance");
        var panel = new QuestState(1000510);
        panel.advance(1, 1, 1);
        updates
            .append("level", 21)
            .append("gameplay.mainMissions.1000510", Document.parse(new Gson().toJson(panel)));
        for (var raw : control.getAsJsonArray("prerequisites")) {
          var row = raw.getAsJsonObject();
          int main = row.get("main").getAsInt(), sub = row.get("sub").getAsInt();
          var state = new QuestState(sub);
          int goal = Math.max(1, row.getAsJsonObject("condition").get("Progress").getAsInt());
          state.advance(goal, goal, 1);
          var history = new QuestState(main);
          history.advance(1, 1, 1);
          history.reserveReward();
          history.closeReward();
          updates
              .append("gameplay.mainMissions." + main, Document.parse(new Gson().toJson(history)))
              .append("gameplay.subMissions." + sub, Document.parse(new Gson().toJson(state)));
        }
        Files.writeString(
            dir.resolve("timeline-start-balances.json"),
            new Document("hcoin", before.get("hcoin"))
                .append("scoin", before.get("scoin"))
                .append("mcoin", before.get("mcoin"))
                .toJson());
        Files.writeString(
            dir.resolve("timeline-start-items.json"),
            new Document(
                    "items",
                    db.getCollection("items")
                        .find(new Document("ownerUid", uid))
                        .into(new ArrayList<Document>()))
                .toJson());
      }
      if (!mode.equals("inspect")) {
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
      var gameplay = player.getGameplay();
      require(gameplay.getPropTimelines().valid(), "Invalid restored timeline");
      var domain = gameplay.getTimelineActivityQuests();
      var completed = new HashSet<Integer>();
      for (var id : control.getAsJsonArray("completed")) completed.add(id.getAsInt());
      var payout = new TreeMap<Integer, Integer>();
      int receipts = 0;
      var saved =
          after.get("gameplay", Document.class).get("timelineActivityQuests", Document.class);
      require(!saved.containsKey("pending"), "Transient request pair was persisted");
      for (var raw : control.getAsJsonArray("allGoals")) {
        var goal = raw.getAsJsonObject();
        int id = goal.get("id").getAsInt();
        boolean done = completed.contains(id);
        var state = domain.getQuests().get(id);
        var history = domain.getReceipts().getOrDefault(id, List.of());
        require(history.size() == (done ? 3 : 0), "Wrong receipt count " + id);
        receipts += history.size();
        if (mode.equals("seed")) require(state == null, "Acceptance was prewritten");
        else
          require(
              state != null
                  && state.getQuestId() == id
                  && state.getProgress() == (done ? 3 : 0)
                  && state.getPhase() == (done ? QuestState.Phase.CLOSED : QuestState.Phase.DOING),
              "Wrong restored quest " + id);
        String key =
            goal.get("plane").getAsInt()
                + ":"
                + goal.get("floor").getAsInt()
                + ":0:0:"
                + goal.get("group").getAsInt()
                + ":"
                + goal.get("instance").getAsInt();
        var timeline = gameplay.getPropTimelines().get(key);
        if (done) {
          var props = new JsonArray();
          props.add(goal.get("propDefinition").deepCopy());
          var rawGroup = new JsonObject();
          rawGroup.add("PropList", props);
          var group = ResourceLoader.getGson().fromJson(rawGroup, GroupInfo.class);
          group.setId(goal.get("group").getAsInt());
          var floor = new FloorInfo();
          floor.getGroups().put(group.getId(), group);
          floor.onLoad();
          String source =
              HexFormat.of()
                  .formatHex(
                      MessageDigest.getInstance("SHA-256")
                          .digest(
                              ResourceLoader.getGson()
                                  .toJson(group.getPropList().getFirst())
                                  .getBytes(StandardCharsets.UTF_8)));
          require(
              timeline != null
                  && timeline.getState().equals("L0_Idle")
                  && timeline.getSource().equals(source)
                  && timeline.getAcceptance().isEmpty(),
              "Timeline source mismatch");
          long last = 0;
          String previous = null;
          for (int i = 0; i < 3; i++) {
            var r = history.get(i);
            String value = i % 2 == 0 ? "L0_Idle" : "L1_Break";
            require(
                r.getQuest() == id
                    && r.getPlane() == goal.get("plane").getAsInt()
                    && r.getFloor() == goal.get("floor").getAsInt()
                    && r.getDimension() == 0
                    && r.getStory() == 0
                    && r.getGroup() == goal.get("group").getAsInt()
                    && r.getInstance() == goal.get("instance").getAsInt()
                    && r.getProp() == goal.get("prop").getAsInt()
                    && r.getPropSource().equals(source)
                    && r.getGroupSource().equals(goal.get("groupSource").getAsString())
                    && r.getAcceptance().equals(state.getObservationKey())
                    && UUID.fromString(r.getAcceptance()).toString().equals(r.getAcceptance())
                    && value.equals(r.getState())
                    && (i == 0 || Objects.equals(previous, r.getPrevious()))
                    && !Objects.equals(r.getPrevious(), r.getState())
                    && r.getAt() >= last
                    && r.getBegin() == 1
                    && r.getEnd() == 4102444800L
                    && r.getAt() >= 1
                    && r.getAt() < r.getEnd(),
                "Invalid source-bound receipt");
            previous = value;
            last = r.getAt();
          }
          require(state.getFinishTime() == last, "Final count timestamp mismatch");
          goal.getAsJsonObject("rewards")
              .entrySet()
              .forEach(
                  e ->
                      payout.merge(
                          Integer.parseInt(e.getKey()), e.getValue().getAsInt(), Math::addExact));
        } else require(timeline == null, "Timeline proof was prewritten");
      }
      require(
          domain.getReceipts().size() == completed.size()
              && gameplay.getPropTimelines().getRecords().size() == completed.size(),
          "Extra persisted proof");
      var balances = Document.parse(Files.readString(dir.resolve("timeline-start-balances.json")));
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
          Document.parse(Files.readString(dir.resolve("timeline-start-items.json")))
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
                      completed.size(),
                      "receipts",
                      receipts,
                      "changedPlayers",
                      mode.equals("inspect") ? 0 : 1,
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
