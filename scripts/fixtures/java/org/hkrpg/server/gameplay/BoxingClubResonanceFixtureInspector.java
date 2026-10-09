package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/** Explicit entry prerequisites and stopped-fixture proofs; never seeds boxing choices or wins. */
public final class BoxingClubResonanceFixtureInspector {
  private static void require(boolean valid, String why) {
    if (!valid) throw new IllegalStateException(why);
  }

  public static void main(String[] args) throws Exception {
    require(args.length == 3, "Expected fixture/control/phase");
    var root = Path.of("").toRealPath();
    var dir = Path.of(args[0]).toRealPath();
    var control = Path.of(args[1]).toRealPath();
    String phase = args[2];
    require(
        dir.startsWith(root.resolve("build/smoke"))
            && !dir.equals(root.resolve("build/smoke"))
            && control.startsWith(dir),
        "Not an owned fixture");
    require(
        Set.of("prepare", "active", "pending", "settled", "restart").contains(phase),
        "Unknown phase");
    var marker =
        JsonParser.parseString(
                Files.readString(dir.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    require(
        marker.get("testFixture").getAsBoolean()
            && marker.get("boxingResonanceFixture").getAsBoolean(),
        "Missing marker");
    int uid =
        JsonParser.parseString(Files.readString(control)).getAsJsonObject().get("uid").getAsInt();
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
      var gameplay = before.get("gameplay", Document.class);
      var box = gameplay.get("boxingClubResonance", Document.class);
      require(box != null, "Missing persistent resonance state");
      var formal =
          db.getCollection("avatars")
              .find(new Document("ownerUid", uid).append("avatarId", 1001))
              .first();
      require(formal != null, "Missing console formal fixture");
      var formalProof = dir.resolve("boxing-formal-avatar-proof.json");
      var firstProof = dir.resolve("boxing-first-unchanged-proof.json");
      if (phase.equals("prepare")) {
        Files.writeString(formalProof, formal.toJson(), StandardOpenOption.CREATE_NEW);
        Files.writeString(
            firstProof,
            gameplay.get("boxingClub", Document.class).toJson(),
            StandardOpenOption.CREATE_NEW);
        require(
            box.get("active") == null && box.get("completed", Document.class).isEmpty(),
            "Boxing choices/completion already seeded");
        var update = new Document("level", 21);
        var expected =
            Document.parse(
                before.toJson(
                    org.bson.json.JsonWriterSettings.builder()
                        .outputMode(org.bson.json.JsonMode.EXTENDED)
                        .build()));
        expected.put("level", 21);
        var expectedGameplay = expected.get("gameplay", Document.class);
        var mains = expectedGameplay.get("mainMissions", Document.class);
        if (mains == null) {
          mains = new Document();
          expectedGameplay.put("mainMissions", mains);
        }
        for (int id : List.of(1011001, 2010720, 1011503, 8015202)) {
          var gate =
              new Document("questId", id)
                  .append("progress", 1)
                  .append("finishTime", 123L)
                  .append("phase", "CLOSED");
          update.put("gameplay.mainMissions." + id, gate);
          mains.put(String.valueOf(id), gate);
        }
        var result = players.updateOne(filter, new Document("$set", update));
        require(
            result.getMatchedCount() == 1 && expected.equals(players.find(filter).first()),
            "Preparation changed unrelated state");
        var balances = new Document();
        for (String key : List.of("hcoin", "scoin", "mcoin")) balances.put(key, before.get(key));
        Files.writeString(
            dir.resolve("boxing-start-balances.json"),
            balances.toJson(
                org.bson.json.JsonWriterSettings.builder()
                    .outputMode(org.bson.json.JsonMode.EXTENDED)
                    .build()),
            StandardOpenOption.CREATE_NEW);
        Files.writeString(
            dir.resolve("boxing-start-items.json"),
            new Document(
                    "items",
                    db.getCollection("items")
                        .find(new Document("ownerUid", uid))
                        .into(new ArrayList<Document>()))
                .toJson(),
            StandardOpenOption.CREATE_NEW);
      } else {
        require(
            Files.readString(formalProof).equals(formal.toJson()),
            "Boxing changed the formal avatar document");
        require(
            Files.readString(firstProof)
                .equals(gameplay.get("boxingClub", Document.class).toJson()),
            "Resonance changed first-season state");
      }
      var after = Objects.requireNonNull(players.find(filter).first());
      gameplay = after.get("gameplay", Document.class);
      box = gameplay.get("boxingClubResonance", Document.class);
      var receipts = gameplay.get("rewards", Document.class);
      if (phase.equals("active") || phase.equals("pending")) {
        var run = box.get("active", Document.class);
        int count = phase.equals("active") ? 1 : 2;
        require(
            run != null
                && run.getInteger("challenge") == 6
                && run.getInteger("group") == 58
                && run.getInteger("resonance") == 3101091,
            "Wrong original resonance/group");
        require(
            run.getList("roster", Integer.class).equals(List.of(3071302, 1001))
                && run.getList("rosterTypes", Integer.class).equals(List.of(3, 1)),
            "Lost trial member types");
        var wins = run.getList("wins", Document.class);
        require(
            wins.size() == count
                && wins.stream().mapToInt(w -> w.getInteger("rounds")).sum() == count * 2
                && wins.get(0).getInteger("stage") == 4123110
                && (count == 1 || wins.get(1).getInteger("stage") == 4123120),
            "Wrong accepted round proof");
        require(
            run.getList("options", Integer.class).equals(count == 1 ? List.of() : List.of(3101093)),
            "Wrong pending optional phase");
        require(
            box.get("completed", Document.class).isEmpty()
                && !receipts.containsKey("boxing:second:6"),
            "Early completion or receipt");
      } else if (phase.equals("settled") || phase.equals("restart")) {
        require(box.get("active") == null, "Finished attempt stayed active");
        var best = box.get("completed", Document.class).get("6", Document.class);
        require(best != null, "Missing earned completion");
        var wins = best.getList("wins", Document.class);
        require(
            wins.size() == 3
                && wins.stream().mapToInt(w -> w.getInteger("rounds")).sum() == 3
                && best.getList("options", Integer.class).equals(List.of(3101092, 3101095)),
            "Wrong improved original branch record");
        require(
            receipts.get("boxing:second:6", Document.class).getBoolean("granted"),
            "Missing empty source reward receipt");
        var proof = new Document("boxing", box).append("receipt", receipts.get("boxing:second:6"));
        var file = dir.resolve("boxing-settled-proof.json");
        if (phase.equals("settled"))
          Files.writeString(file, proof.toJson(), StandardOpenOption.CREATE_NEW);
        else
          require(
              Files.readString(file).equals(proof.toJson()),
              "Restart/rejected choices changed completion");
      }
      var balances = Document.parse(Files.readString(dir.resolve("boxing-start-balances.json")));
      for (String key : List.of("hcoin", "scoin", "mcoin"))
        require(
            Objects.equals(after.get(key), balances.get(key)),
            "Original empty reward changed currency "
                + key
                + ": "
                + balances.get(key)
                + " -> "
                + after.get(key));
      var items =
          new Document(
              "items",
              db.getCollection("items")
                  .find(new Document("ownerUid", uid))
                  .into(new ArrayList<Document>()));
      require(
          Files.readString(dir.resolve("boxing-start-items.json")).equals(items.toJson()),
          "Original empty reward changed inventory");
      require(
          db.getCollection("avatars")
                  .countDocuments(new Document("ownerUid", uid).append("avatarId", 3071302))
              == 0,
          "Trial became owned");
      require(
          after.equals(players.find(filter).first())
              && Arrays.equals(cfg, Files.readAllBytes(dir.resolve("config.json"))),
          "Inspection changed unrelated state");
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
                      phase.equals("prepare"),
                      "emptySourceRewardVerified",
                      true,
                      "firstSeasonStateUnchanged",
                      true)));
    } finally {
      server.shutdownNow();
    }
  }
}
