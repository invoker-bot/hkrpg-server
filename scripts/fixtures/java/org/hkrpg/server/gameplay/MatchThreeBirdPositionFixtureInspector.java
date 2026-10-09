package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/**
 * A single accepted target is prepared only in a stopped owned fixture; later reads change no data.
 */
public final class MatchThreeBirdPositionFixtureInspector {
  static void require(boolean ok, String reason) {
    if (!ok) throw new IllegalStateException(reason);
  }

  static Document quest(int id, boolean done) {
    return new Document("questId", id)
        .append("progress", done ? 1 : 0)
        .append("finishTime", done ? 123L : 0L)
        .append("phase", done ? "CLOSED" : "DOING");
  }

  static void set(Document d, String path, Object value) {
    var parts = path.split("\\.");
    for (int i = 0; i < parts.length - 1; i++) {
      var child = d.get(parts[i], Document.class);
      if (child == null) {
        child = new Document();
        d.put(parts[i], child);
      }
      d = child;
    }
    d.put(parts[parts.length - 1], value);
  }

  public static void main(String[] args) throws Exception {
    require(args.length == 3, "Expected fixture, credentials, and operation");
    var root = Path.of("").toRealPath();
    var dir = Path.of(args[0]).toRealPath();
    var input = Path.of(args[1]).toRealPath();
    require(
        dir.startsWith(root.resolve("build/smoke"))
            && !dir.equals(root.resolve("build/smoke"))
            && input.startsWith(dir),
        "Not an owned fixture");
    var mode = args[2];
    require(Set.of("prepare", "inspect").contains(mode), "Unknown operation");
    var marker =
        JsonParser.parseString(
                Files.readString(dir.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    require(
        marker.get("testFixture").getAsBoolean()
            && marker.get("matchThreeBirdPlacementFixture").getAsBoolean(),
        "Missing fixture marker");
    var control = JsonParser.parseString(Files.readString(input)).getAsJsonObject();
    int uid = control.get("uid").getAsInt();
    byte[] config = Files.readAllBytes(dir.resolve("config.json"));
    var cfg =
        JsonParser.parseString(new String(config, java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
    var data =
        dir.resolve(cfg.getAsJsonObject("internalMongoServer").get("filePath").getAsString())
            .normalize();
    require(data.startsWith(dir), "Database outside fixture");
    var server = new MongoServer(new H2Backend(data.toString()));
    var addr = server.bind();
    try (var client =
        MongoClients.create("mongodb://127.0.0.1:" + addr.getPort() + "/?directConnection=true")) {
      var db = client.getDatabase("hkrpg");
      var players = db.getCollection("players");
      var filter = new Document("_id", uid);
      require(players.countDocuments() == 1, "Unexpected players");
      var before = Objects.requireNonNull(players.find(filter).first());
      var lineups = db.getCollection("lineups");
      var lineupFilter = new Document("ownerUid", uid).append("index", 0);
      var lineup = Objects.requireNonNull(lineups.find(lineupFilter).first());
      require(
          lineup.getList("avatars", Integer.class).equals(List.of(8001)), "Formal roster changed");
      var items = db.getCollection("items");
      var itemFilter = new Document("ownerUid", uid);
      var actualItems = items.find(itemFilter).into(new ArrayList<Document>());
      int changed = 0;
      if (mode.equals("prepare")) {
        var updates =
            new Document("gameplay.mainMissions.8023101", quest(8023101, true))
                .append("gameplay.mainMissions.8023102", quest(8023102, false))
                .append("gameplay.subMissions.802310205", quest(802310205, true))
                .append("gameplay.subMissions.802310206", quest(802310206, false))
                .append("planeId", 10341)
                .append("floorId", 10341001)
                .append("entryId", 1034101);
        var expected =
            Document.parse(
                before.toJson(
                    org.bson.json.JsonWriterSettings.builder()
                        .outputMode(org.bson.json.JsonMode.EXTENDED)
                        .build()));
        updates.forEach((path, value) -> set(expected, path, value));
        var result = players.updateOne(filter, new Document("$set", updates));
        require(
            result.getMatchedCount() == 1 && result.getModifiedCount() == 1,
            "Fixture preparation failed");
        require(
            expected.equals(players.find(filter).first()),
            "Preparation changed unrelated player fields");
        changed = 1;
      }
      var after = Objects.requireNonNull(players.find(filter).first());
      var gameplay = after.get("gameplay", Document.class);
      var match = gameplay.get("matchThree", Document.class);
      var bird = match.get("birds", Document.class).get("301", Document.class);
      require(
          bird.getInteger("position") == control.get("bird301Position").getAsInt(),
          "Saved placement differs from native result");
      var goal = gameplay.get("subMissions", Document.class).get("802310206", Document.class);
      require(goal.getInteger("questId") == 802310206, "Target identity differs");
      boolean finished =
          goal.getString("phase").equals("FINISHED") || goal.getString("phase").equals("CLOSED");
      var actions = gameplay.get("storyActions", Document.class);
      var cursor = actions == null ? null : actions.get("802310206", Document.class);
      if (mode.equals("prepare")) {
        require(
            !finished
                && goal.getInteger("progress") == 0
                && goal.getLong("finishTime") == 0
                && cursor == null,
            "Target completion was prewritten");
      } else {
        require(
            finished && goal.getInteger("progress") == 1 && goal.getLong("finishTime") > 0,
            "Native placement did not finish accepted goal");
        require(
            gameplay
                    .get("storyValues", Document.class)
                    .getInteger("floor:10341001:Activity230DIYEntrance")
                == 2,
            "Authored floor action did not run");
        require(
            cursor != null && cursor.getInteger("next") == 1 && !cursor.getBoolean("pending"),
            "Authored action cursor did not settle");
        String settled = new Document("goal", goal).append("cursor", cursor).toJson();
        var proof = dir.resolve("settled-placement-goal.json");
        if (Files.exists(proof))
          require(
              Files.readString(proof).equals(settled),
              "Retry changed target time or action cursor");
        else Files.writeString(proof, settled, StandardOpenOption.CREATE_NEW);
      }
      var protectedState = new Document("lineup", lineup).append("items", actualItems);
      for (var key : List.of("hcoin", "scoin", "mcoin", "level", "worldLevel"))
        if (after.containsKey(key)) protectedState.put(key, after.get(key));
      var snapshot = dir.resolve("bird-placement-wallet-lineup-items.json");
      String protectedText = protectedState.toJson();
      if (Files.exists(snapshot))
        require(
            Files.readString(snapshot).equals(protectedText),
            "Placement changed wallet, inventory identity or formal lineup");
      else Files.writeString(snapshot, protectedText, StandardOpenOption.CREATE_NEW);
      require(
          after.equals(players.find(filter).first())
              && lineup.equals(lineups.find(lineupFilter).first())
              && actualItems.equals(items.find(itemFilter).into(new ArrayList<Document>())),
          "Inspection changed records");
      require(
          Arrays.equals(config, Files.readAllBytes(dir.resolve("config.json"))),
          "Configuration changed");
      System.out.println(
          new Gson()
              .toJson(
                  Map.of(
                      "passed",
                      true,
                      "uid",
                      uid,
                      "operation",
                      mode,
                      "changedPlayers",
                      changed,
                      "changedLineups",
                      0,
                      "goalFinished",
                      finished,
                      "goal",
                      goal.toJson(),
                      "bird301Position",
                      bird.getInteger("position"))));
    } finally {
      server.shutdownNow();
    }
  }
}
