package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/** Explicit entry prerequisites and stopped-fixture proofs; never seeds boxing choices or wins. */
public final class BoxingClubResidentQuestFixtureInspector {
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
        Set.of("prepare", "earned", "partial", "settled", "restart").contains(phase),
        "Unknown phase");
    var marker =
        JsonParser.parseString(
                Files.readString(dir.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    require(
        marker.get("testFixture").getAsBoolean()
            && marker.get("boxingResonanceFixture").getAsBoolean()
            && marker.get("boxingResidentQuestFixture").getAsBoolean(),
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

      var inputs = JsonParser.parseString(Files.readString(control)).getAsJsonObject();
      var specs = inputs.getAsJsonObject("boxSpecs");
      var definitions = inputs.getAsJsonArray("residentQuests");
      var finishes = inputs.getAsJsonObject("residentFinishes");
      var completed = box.get("completed", Document.class);
      int last =
          phase.equals("prepare")
              ? 5
              : phase.equals("earned") ? 6 : phase.equals("partial") ? 9 : 12;
      require(
          box.get("active") == null && completed.size() == last - 5,
          "Wrong earned challenge count");
      var counts = new HashMap<Integer, Integer>();
      for (int id = 6; id <= last; id++) {
        var run = completed.get(String.valueOf(id), Document.class);
        var spec = specs.getAsJsonObject(String.valueOf(id));
        int rounds = id == 6 ? phase.equals("earned") ? 6 : 5 : 3;
        require(
            run != null
                && run.getInteger("uid") == uid
                && run.getInteger("challenge") == id
                && run.getInteger("resonance") == spec.get("resonance").getAsInt()
                && run.getInteger("group") == spec.get("group").getAsInt()
                && run.getString("source")
                    .equals("724b139d8c9c32d12552eb95745a4fee72bfe48b/boxing-second/v1")
                && run.getList("roster", Integer.class).equals(List.of(1001))
                && run.getList("rosterTypes", Integer.class).equals(List.of(1)),
            "Wrong original accepted record " + id);
        var wins = run.getList("wins", Document.class);
        require(
            wins.size() == 3 && wins.stream().mapToInt(w -> w.getInteger("rounds")).sum() == rounds,
            "Wrong three WIN rounds");
        for (int i = 0; i < 3; i++)
          require(
              wins.get(i).getInteger("stage") == spec.getAsJsonArray("stages").get(i).getAsInt()
                  && wins.get(i).getInteger("battle") > 0,
              "Wrong source stage/battle proof");
        require(
            receipts.get("boxing:second:" + id, Document.class).getBoolean("granted"),
            "Missing source empty receipt");
      }
      for (var raw : definitions) {
        var quest = raw.getAsJsonObject();
        var finish = finishes.getAsJsonObject(String.valueOf(quest.get("FinishWayID").getAsInt()));
        if (!finish.get("FinishType").getAsString().equals("BoxingClubAccomplish")) continue;
        var record =
            completed.get(String.valueOf(finish.get("ParamInt2").getAsInt()), Document.class);
        int limit = finish.has("ParamInt1") ? finish.get("ParamInt1").getAsInt() : 0;
        int rounds =
            record == null
                ? 0
                : record.getList("wins", Document.class).stream()
                    .mapToInt(w -> w.getInteger("rounds"))
                    .sum();
        counts.put(
            quest.get("QuestID").getAsInt(),
            record != null && (limit == 0 || rounds <= limit) ? 1 : 0);
      }
      for (var raw : definitions) {
        var quest = raw.getAsJsonObject();
        var finish = finishes.getAsJsonObject(String.valueOf(quest.get("FinishWayID").getAsInt()));
        if (!finish.get("FinishType").getAsString().equals("FinishQuest")) continue;
        int count = 0;
        for (var child : finish.getAsJsonArray("ParamIntList"))
          count += counts.getOrDefault(child.getAsInt(), 0);
        counts.put(
            quest.get("QuestID").getAsInt(), Math.min(finish.get("Progress").getAsInt(), count));
      }
      var quests = after.get("questStates", Document.class);
      require(quests != null && counts.size() == 32, "Missing resident definitions");
      var closed =
          phase.equals("partial")
              ? Set.of(6000414)
              : phase.equals("settled") || phase.equals("restart")
                  ? Set.of(6000398, 6000412, 6000413, 6000414)
                  : Set.<Integer>of();
      var resident = new Document();
      for (var raw : definitions) {
        var quest = raw.getAsJsonObject();
        int id = quest.get("QuestID").getAsInt();
        var saved = quests.get(String.valueOf(id), Document.class);
        int goal =
            finishes
                .getAsJsonObject(String.valueOf(quest.get("FinishWayID").getAsInt()))
                .get("Progress")
                .getAsInt();
        int count = counts.get(id);
        String expected = closed.contains(id) ? "CLOSED" : count == goal ? "FINISHED" : "DOING";
        require(
            saved != null
                && saved.getInteger("questId") == id
                && saved.getInteger("progress") == count
                && saved.getString("phase").equals(expected),
            "Wrong earned resident goal " + id);
        require(
            (count == goal) == (((Number) saved.get("finishTime")).longValue() > 0),
            "Wrong goal completion time");
        resident.put(String.valueOf(id), saved);
      }
      var balances = Document.parse(Files.readString(dir.resolve("boxing-start-balances.json")));
      int jade = phase.equals("settled") || phase.equals("restart") ? 100 : 0;
      int credits = phase.equals("partial") ? 10000 : jade == 100 ? 120000 : 0;
      int materials = phase.equals("partial") ? 2 : jade == 100 ? 12 : 0;
      for (String key : List.of("hcoin", "scoin", "mcoin")) {
        long expected =
            ((Number) balances.get(key)).longValue()
                + (key.equals("hcoin") ? jade : key.equals("scoin") ? credits : 0);
        require(
            ((Number) after.get(key)).longValue() == expected,
            "Wrong exact original resident currency " + key);
      }
      var initial =
          Document.parse(Files.readString(dir.resolve("boxing-start-items.json")))
              .getList("items", Document.class);
      var actual =
          db.getCollection("items")
              .find(new Document("ownerUid", uid))
              .into(new ArrayList<Document>());
      int oldCount =
          initial.stream()
              .filter(d -> d.getInteger("itemId") == 213)
              .mapToInt(d -> d.getInteger("count"))
              .sum();
      require(
          actual.stream()
                  .filter(d -> d.getInteger("itemId") == 213)
                  .mapToInt(d -> d.getInteger("count"))
                  .sum()
              == oldCount + materials,
          "Wrong original material payout");
      require(
          new Document(
                  "items", initial.stream().filter(d -> d.getInteger("itemId") != 213).toList())
              .toJson()
              .equals(
                  new Document(
                          "items",
                          actual.stream().filter(d -> d.getInteger("itemId") != 213).toList())
                      .toJson()),
          "Changed unrelated inventory");
      if (phase.equals("settled") || phase.equals("restart")) {
        var proof =
            new Document("boxing", box)
                .append("resident", resident)
                .append("items", actual)
                .append("hcoin", after.get("hcoin"))
                .append("scoin", after.get("scoin"))
                .append("mcoin", after.get("mcoin"));
        var file = dir.resolve("boxing-resident-settled-proof.json");
        if (phase.equals("settled"))
          Files.writeString(file, proof.toJson(), StandardOpenOption.CREATE_NEW);
        else
          require(
              Files.readString(file).equals(proof.toJson()),
              "Restart/rejected claims changed earned rewards");
      }
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
                      "originalResidentRewardTotalsVerified",
                      true,
                      "firstSeasonStateUnchanged",
                      true)));
    } finally {
      server.shutdownNow();
    }
  }
}
