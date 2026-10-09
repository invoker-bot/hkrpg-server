package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/** Prerequisite-only preparation and read-only proofs in a stopped, marked owned H2 fixture. */
public final class BoxingClubFixtureInspector {
  static void require(boolean valid, String why) {
    if (!valid) throw new IllegalStateException(why);
  }

  public static void main(String[] args) throws Exception {
    require(args.length == 3, "Expected fixture/control/phase");
    var root = Path.of("").toRealPath();
    var dir = Path.of(args[0]).toRealPath();
    var input = Path.of(args[1]).toRealPath();
    String phase = args[2];
    require(
        dir.startsWith(root.resolve("build/smoke"))
            && !dir.equals(root.resolve("build/smoke"))
            && input.startsWith(dir),
        "Not an owned fixture");
    require(Set.of("prepare", "active", "settled", "restart").contains(phase), "Unknown phase");
    var marker =
        JsonParser.parseString(
                Files.readString(dir.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    require(
        marker.get("testFixture").getAsBoolean()
            && marker.get("boxingRoundsFixture").getAsBoolean(),
        "Missing marker");
    int uid =
        JsonParser.parseString(Files.readString(input)).getAsJsonObject().get("uid").getAsInt();
    byte[] cfg = Files.readAllBytes(dir.resolve("config.json"));
    var config =
        JsonParser.parseString(new String(cfg, java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
    var data =
        dir.resolve(config.getAsJsonObject("internalMongoServer").get("filePath").getAsString())
            .normalize();
    require(data.startsWith(dir), "Database outside fixture");
    var server = new MongoServer(new H2Backend(data.toString()));
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var db = client.getDatabase("hkrpg");
      var players = db.getCollection("players");
      require(players.countDocuments() == 1, "Unexpected players");
      var filter = new Document("_id", uid);
      var before = Objects.requireNonNull(players.find(filter).first());
      var oldBox = before.get("gameplay", Document.class).get("boxingClub", Document.class);
      if (phase.equals("prepare")) {
        require(
            oldBox.get("active") == null && oldBox.get("completed", Document.class).isEmpty(),
            "Boxing state already seeded");
        var gate =
            new Document("questId", 1011001)
                .append("progress", 1)
                .append("finishTime", 123L)
                .append("phase", "FINISHED");
        var update = new Document("level", 21).append("gameplay.mainMissions.1011001", gate);
        var expected =
            Document.parse(
                before.toJson(
                    org.bson.json.JsonWriterSettings.builder()
                        .outputMode(org.bson.json.JsonMode.EXTENDED)
                        .build()));
        expected.put("level", 21);
        var gameplay = expected.get("gameplay", Document.class);
        var mains = gameplay.get("mainMissions", Document.class);
        if (mains == null) {
          mains = new Document();
          gameplay.put("mainMissions", mains);
        }
        mains.put("1011001", gate);
        var result = players.updateOne(filter, new Document("$set", update));
        require(
            result.getMatchedCount() == 1 && expected.equals(players.find(filter).first()),
            "Preparation changed unrelated state");
        var balances = new Document();
        for (var k : List.of("hcoin", "scoin", "mcoin")) balances.put(k, before.get(k));
        Files.writeString(
            dir.resolve("boxing-start-balances.json"),
            balances.toJson(),
            StandardOpenOption.CREATE_NEW);
      }
      var after = Objects.requireNonNull(players.find(filter).first());
      var gameplay = after.get("gameplay", Document.class);
      var box = gameplay.get("boxingClub", Document.class);
      var rewards = gameplay.get("rewards", Document.class);
      if (phase.equals("active")) {
        var run = box.get("active", Document.class);
        require(
            run != null && run.getInteger("challenge") == 1 && run.getInteger("group") == 10,
            "Wrong saved active challenge");
        var wins = run.getList("wins", Document.class);
        require(
            wins.size() == 2
                && wins.get(0).getInteger("stage") == 3040040
                && wins.get(1).getInteger("stage") == 3040020
                && wins.stream().mapToInt(w -> w.getInteger("rounds")).sum() == 4,
            "Wrong two-round proof");
        require(
            box.get("completed", Document.class).isEmpty()
                && !rewards.containsKey("boxing:first:1"),
            "Early completion or delivery");
      } else if (phase.equals("settled") || phase.equals("restart")) {
        require(box.get("active") == null, "Finished attempt remained active");
        var best = box.get("completed", Document.class).get("1", Document.class);
        require(best != null, "Missing earned four-round proof");
        var wins = best.getList("wins", Document.class);
        require(
            wins.size() == 4 && wins.stream().mapToInt(w -> w.getInteger("rounds")).sum() == 4,
            "Wrong improved record");
        require(
            rewards.get("boxing:first:1", Document.class).getBoolean("granted"),
            "Delivery receipt missing");
        var balances = Document.parse(Files.readString(dir.resolve("boxing-start-balances.json")));
        require(
            after.getInteger("hcoin") == balances.getInteger("hcoin") + 20
                && after.getInteger("scoin") == balances.getInteger("scoin") + 10000,
            "Reward missing or duplicated");
        var proof =
            new Document("boxing", box)
                .append("receipt", rewards.get("boxing:first:1"))
                .append("hcoin", after.get("hcoin"))
                .append("scoin", after.get("scoin"));
        proof.put(
            "items",
            db.getCollection("items")
                .find(new Document("ownerUid", uid))
                .into(new ArrayList<Document>()));
        var p = dir.resolve("boxing-settled-proof.json");
        if (phase.equals("restart"))
          require(
              Files.readString(p).equals(proof.toJson()),
              "Restart or replay changed proof/delivery");
        else Files.writeString(p, proof.toJson(), StandardOpenOption.CREATE_NEW);
      }
      require(
          after.equals(players.find(filter).first())
              && Arrays.equals(cfg, Files.readAllBytes(dir.resolve("config.json"))),
          "Inspection changed state");
      System.out.println(
          new Gson()
              .toJson(
                  Map.of(
                      "passed",
                      true,
                      "uid",
                      uid,
                      "phase",
                      phase,
                      "boxCompletionPrewritten",
                      false,
                      "prerequisiteOnlyPrepared",
                      phase.equals("prepare"))));
    } finally {
      server.shutdownNow();
    }
  }
}
