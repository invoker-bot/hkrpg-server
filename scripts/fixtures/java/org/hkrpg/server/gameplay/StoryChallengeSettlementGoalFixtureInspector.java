package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/**
 * Accepted parent and intro are written only in a stopped owned fixture; target is never completed.
 */
public final class StoryChallengeSettlementGoalFixtureInspector {
  static void require(boolean value, String reason) {
    if (!value) throw new IllegalStateException(reason);
  }

  static Document quest(int id, boolean done) {
    return new Document("questId", id)
        .append("progress", done ? 1 : 0)
        .append("finishTime", done ? 123L : 0L)
        .append("phase", done ? "FINISHED" : "DOING");
  }

  static void set(Document doc, String path, Object value) {
    var keys = path.split("\\.");
    for (int i = 0; i < keys.length - 1; i++) {
      var next = doc.get(keys[i], Document.class);
      if (next == null) {
        next = new Document();
        doc.put(keys[i], next);
      }
      doc = next;
    }
    doc.put(keys[keys.length - 1], value);
  }

  public static void main(String[] args) throws Exception {
    require(args.length == 3, "Expected fixture, control and operation");
    var root = Path.of("").toRealPath();
    var dir = Path.of(args[0]).toRealPath();
    var input = Path.of(args[1]).toRealPath();
    String mode = args[2];
    require(
        dir.startsWith(root.resolve("build/smoke"))
            && !dir.equals(root.resolve("build/smoke"))
            && input.startsWith(dir),
        "Not an owned fixture");
    require(Set.of("prepare", "inspect").contains(mode), "Unknown operation");
    var marker =
        JsonParser.parseString(
                Files.readString(dir.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    require(
        marker.get("testFixture").getAsBoolean()
            && marker.get("storySettlementGoalFixture").getAsBoolean(),
        "Missing marker");
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
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var db = client.getDatabase("hkrpg");
      var players = db.getCollection("players");
      var filter = new Document("_id", uid);
      require(players.countDocuments() == 1, "Unexpected players");
      var before = Objects.requireNonNull(players.find(filter).first());
      int changed = 0;
      if (mode.equals("prepare")) {
        var updates =
            new Document("gameplay.mainMissions.8017101", quest(8017101, false))
                .append("gameplay.subMissions.801710101", quest(801710101, true))
                .append("gameplay.subMissions.801710102", quest(801710102, true))
                .append("gameplay.subMissions.801710105", quest(801710105, false))
                .append("entryId", 102020107)
                .append("planeId", 10202)
                .append("floorId", 10202001);
        var expected =
            Document.parse(
                before.toJson(
                    org.bson.json.JsonWriterSettings.builder()
                        .outputMode(org.bson.json.JsonMode.EXTENDED)
                        .build()));
        updates.forEach((path, value) -> set(expected, path, value));
        var result = players.updateOne(filter, new Document("$set", updates));
        require(
            result.getMatchedCount() == 1
                && result.getModifiedCount() == 1
                && expected.equals(players.find(filter).first()),
            "Preparation changed unrelated state");
        changed = 1;
      }
      var after = Objects.requireNonNull(players.find(filter).first());
      var gameplay = after.get("gameplay", Document.class);
      var subs = gameplay.get("subMissions", Document.class);
      var goal = subs.get("801710105", Document.class);
      require(goal.getInteger("questId") == 801710105, "Wrong target identity");
      var values = gameplay.get("storyValues", Document.class);
      var actions = gameplay.get("storyActions", Document.class);
      if (mode.equals("prepare")) {
        require(
            goal.getString("phase").equals("DOING")
                && goal.getInteger("progress") == 0
                && goal.getLong("finishTime") == 0,
            "Target completion prewritten");
        require(
            values == null || !values.containsKey("floor:10202001:ChallengeStoryEntrance"),
            "Entrance prewritten");
        require(actions == null || !actions.containsKey("801710104"), "Followup action prewritten");
        require(
            !finished(subs.get("801710103", Document.class))
                && !finished(subs.get("801710104", Document.class)),
            "Followup completion prewritten");
      } else {
        for (int id : List.of(801710103, 801710104, 801710105)) {
          var state = subs.get(Integer.toString(id), Document.class);
          require(
              finished(state)
                  && state.getInteger("progress") == 1
                  && state.getLong("finishTime") > 0,
              "Native chain did not finish " + id);
        }
        var cursor = actions.get("801710104", Document.class);
        require(
            values.getInteger("floor:10202001:ChallengeStoryEntrance") == 1
                && cursor.getInteger("next") == 1
                && !cursor.getBoolean("pending"),
            "Authored entrance action did not settle");
        require(
            finished(gameplay.get("mainMissions", Document.class).get("8017101", Document.class)),
            "Parent not settled");
        var proof = new Document("subs", subs).append("actions", actions).append("values", values);
        for (var key : List.of("rewards", "missionRewardPlans")) proof.put(key, gameplay.get(key));
        for (var key : List.of("hcoin", "scoin", "mcoin", "level", "exp"))
          proof.put(key, after.get(key));
        proof.put(
            "items",
            db.getCollection("items")
                .find(new Document("ownerUid", uid))
                .into(new ArrayList<Document>()));
        var stored = dir.resolve("settled-story-goal-proof.json");
        String text = proof.toJson();
        if (Files.exists(stored))
          require(
              Files.readString(stored).equals(text),
              "Restart or duplicate changed goals/actions/rewards");
        else Files.writeString(stored, text, StandardOpenOption.CREATE_NEW);
      }
      require(
          after.equals(players.find(filter).first())
              && Arrays.equals(config, Files.readAllBytes(dir.resolve("config.json"))),
          "Inspection changed state");
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
                      "targetFinished",
                      mode.equals("inspect"),
                      "entranceValue",
                      mode.equals("inspect") ? 1 : 0)));
    } finally {
      server.shutdownNow();
    }
  }

  static boolean finished(Document state) {
    return state != null && Set.of("FINISHED", "CLOSED").contains(state.getString("phase"));
  }
}
