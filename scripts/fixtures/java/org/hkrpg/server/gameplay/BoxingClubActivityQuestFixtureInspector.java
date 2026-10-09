package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/** Explicit entry prerequisites and stopped-fixture proofs; never seeds boxing choices or wins. */
public final class BoxingClubActivityQuestFixtureInspector {
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
        Set.of("prepare", "first", "slow", "settled", "expired", "reopened").contains(phase),
        "Unknown phase");
    var marker =
        JsonParser.parseString(
                Files.readString(dir.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    require(
        marker.get("testFixture").getAsBoolean()
            && marker.get("boxingResonanceFixture").getAsBoolean()
            && marker.get("boxingActivityQuestFixture").getAsBoolean(),
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
      if (phase.equals("prepare")) {
        Files.writeString(formalProof, formal.toJson(), StandardOpenOption.CREATE_NEW);
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
      }
      var after = Objects.requireNonNull(players.find(filter).first());
      gameplay = after.get("gameplay", Document.class);
      var activity = gameplay.get("boxingClubActivityQuests", Document.class);
      require(activity != null, "Missing activity state");
      var inputs = JsonParser.parseString(Files.readString(control)).getAsJsonObject();
      var definitions = inputs.getAsJsonArray("activityQuests");
      var finishes = inputs.getAsJsonObject("activityFinishes");
      var proofs = activity.get("completed", Document.class);
      var savedGoals = activity.get("quests", Document.class);
      int firstCount = phase.equals("prepare") ? 0 : 5;
      int secondCount =
          Set.of("settled", "expired", "reopened").contains(phase)
              ? 7
              : phase.equals("slow") ? 1 : 0;
      require(
          proofs != null && proofs.size() == firstCount + secondCount,
          "Wrong window-bound completion count");
      var firstBox = gameplay.get("boxingClub", Document.class);
      var secondBox = gameplay.get("boxingClubResonance", Document.class);
      require(
          firstBox.get("active") == null
              && firstBox.get("completed", Document.class).size() == firstCount
              && secondBox.get("active") == null
              && secondBox.get("completed", Document.class).size() == secondCount,
          "Wrong actual boxing completion count");
      for (var entry : proofs.entrySet()) {
        int id = Integer.parseInt(entry.getKey());
        var proof = (Document) entry.getValue();
        boolean first = id <= 5;
        var run = proof.get(first ? "first" : "second", Document.class);
        require(
            run != null
                && proof.get(first ? "second" : "first") == null
                && proof
                    .getString("source")
                    .equals("724b139d8c9c32d12552eb95745a4fee72bfe48b/boxing-activity/v1")
                && proof.getInteger("panel") == (first ? 50000 : 40003)
                && proof.getInteger("module") == (first ? 5000001 : 4000308)
                && ((Number) proof.get("begin")).longValue()
                    <= ((Number) proof.get("acceptedAt")).longValue()
                && ((Number) proof.get("acceptedAt")).longValue()
                    < ((Number) proof.get("end")).longValue()
                && run.getInteger("uid") == uid
                && run.getInteger("challenge") == id
                && run.getList("roster", Integer.class).equals(List.of(1001))
                && run.getList("rosterTypes", Integer.class).equals(List.of(1)),
            "Wrong original completion/window identity");
        var wins = run.getList("wins", Document.class);
        int total = first ? 4 : id == 6 ? phase.equals("slow") ? 9 : 6 : 3;
        require(
            wins.size() == (first ? 4 : 3)
                && wins.stream().mapToInt(w -> w.getInteger("rounds")).sum() == total,
            "Wrong accepted rounds in activity proof");
        var spec =
            first
                ? inputs
                    .getAsJsonObject("firstSpecs")
                    .getAsJsonObject(String.valueOf(id))
                    .getAsJsonObject("groups")
                    .getAsJsonObject(String.valueOf(run.getInteger("group")))
                : inputs.getAsJsonObject("boxSpecs").getAsJsonObject(String.valueOf(id));
        for (int i = 0; i < wins.size(); i++)
          require(
              wins.get(i).getInteger("battle") > 0
                  && wins.get(i).getInteger("stage")
                      == spec.getAsJsonArray("stages").get(i).getAsInt(),
              "Wrong source stages in stored victory proof");
        var original =
            (first ? firstBox : secondBox)
                .get("completed", Document.class)
                .get(entry.getKey(), Document.class);
        require(
            original != null && original.equals(run),
            "Activity proof disagrees with actual best completion");
      }
      var counts = new HashMap<Integer, Integer>();
      for (var raw : definitions) {
        var q = raw.getAsJsonObject();
        var f = finishes.getAsJsonObject(String.valueOf(q.get("FinishWayID").getAsInt()));
        if (!f.get("FinishType").getAsString().equals("BoxingClubAccomplish")) continue;
        var proof = proofs.get(String.valueOf(f.get("ParamInt2").getAsInt()), Document.class);
        int limit = f.has("ParamInt1") ? f.get("ParamInt1").getAsInt() : 0;
        Document run =
            proof == null
                ? null
                : proof.get(proof.get("first") != null ? "first" : "second", Document.class);
        int total =
            run == null
                ? 0
                : run.getList("wins", Document.class).stream()
                    .mapToInt(w -> w.getInteger("rounds"))
                    .sum();
        counts.put(
            q.get("QuestID").getAsInt(), run != null && (limit == 0 || total <= limit) ? 1 : 0);
      }
      for (var raw : definitions) {
        var q = raw.getAsJsonObject();
        var f = finishes.getAsJsonObject(String.valueOf(q.get("FinishWayID").getAsInt()));
        if (!f.get("FinishType").getAsString().equals("FinishQuest")) continue;
        int count = 0;
        for (var child : f.getAsJsonArray("ParamIntList"))
          count += counts.getOrDefault(child.getAsInt(), 0);
        counts.put(q.get("QuestID").getAsInt(), Math.min(f.get("Progress").getAsInt(), count));
      }
      require(counts.size() == 50 && savedGoals != null, "Wrong original task collection");
      if (phase.equals("prepare"))
        require(savedGoals.isEmpty(), "Task progress prewritten before prerequisites");
      else
        for (var raw : definitions) {
          var q = raw.getAsJsonObject();
          int id = q.get("QuestID").getAsInt();
          var state = savedGoals.get(String.valueOf(id), Document.class);
          int count = counts.get(id);
          int goal =
              finishes
                  .getAsJsonObject(String.valueOf(q.get("FinishWayID").getAsInt()))
                  .get("Progress")
                  .getAsInt();
          boolean claimed =
              id < 6000300 || Set.of("settled", "expired", "reopened").contains(phase);
          String expected = claimed ? "CLOSED" : count == goal ? "FINISHED" : "DOING";
          require(
              state != null
                  && state.getInteger("questId") == id
                  && state.getInteger("progress") == count
                  && state.getString("phase").equals(expected)
                  && (count == goal) == (((Number) state.get("finishTime")).longValue() > 0),
              "Wrong saved original goal " + id);
        }
      require(!savedGoals.containsKey("6000399"), "Unlinked historical task prewritten");
      var payout = new TreeMap<Integer, Integer>();
      if (!phase.equals("prepare")) {
        for (var e : inputs.getAsJsonObject("firstSpecs").entrySet())
          for (var item :
              e.getValue().getAsJsonObject().getAsJsonObject("firstPassItems").entrySet())
            payout.merge(
                Integer.parseInt(item.getKey()), item.getValue().getAsInt(), Math::addExact);
        for (var q : definitions) {
          int id = q.getAsJsonObject().get("QuestID").getAsInt();
          if (id >= 6000300 && !Set.of("settled", "expired", "reopened").contains(phase)) continue;
          for (var item :
              inputs.getAsJsonObject("questRecipes").getAsJsonObject(String.valueOf(id)).entrySet())
            payout.merge(
                Integer.parseInt(item.getKey()), item.getValue().getAsInt(), Math::addExact);
        }
      }
      var balances = Document.parse(Files.readString(dir.resolve("boxing-start-balances.json")));
      for (String key : List.of("hcoin", "scoin", "mcoin")) {
        int item = key.equals("hcoin") ? 1 : key.equals("scoin") ? 2 : 3;
        require(
            ((Number) after.get(key)).longValue()
                == ((Number) balances.get(key)).longValue() + payout.getOrDefault(item, 0),
            "Wrong exact source currency " + key);
      }
      var initial =
          Document.parse(Files.readString(dir.resolve("boxing-start-items.json")))
              .getList("items", Document.class);
      var actual =
          db.getCollection("items")
              .find(new Document("ownerUid", uid))
              .into(new ArrayList<Document>());
      var expectedItems = new TreeMap<Integer, Integer>();
      var actualItems = new TreeMap<Integer, Integer>();
      for (var item : initial)
        expectedItems.merge(item.getInteger("itemId"), item.getInteger("count"), Math::addExact);
      for (var item : actual)
        actualItems.merge(item.getInteger("itemId"), item.getInteger("count"), Math::addExact);
      payout.forEach(
          (id, amount) -> {
            if (id > 3) expectedItems.merge(id, amount, Math::addExact);
          });
      require(expectedItems.equals(actualItems), "Wrong complete original inventory payout");
      var payoutItems = payout.keySet();
      require(
          initial.stream()
              .filter(d -> !payoutItems.contains(d.getInteger("itemId")))
              .toList()
              .equals(
                  actual.stream()
                      .filter(d -> !payoutItems.contains(d.getInteger("itemId")))
                      .toList()),
          "Changed unrelated inventory documents");
      if (Set.of("settled", "expired", "reopened").contains(phase)) {
        var proof =
            new Document("firstBox", firstBox)
                .append("secondBox", secondBox)
                .append("activity", activity)
                .append("resident", after.get("questStates"))
                .append("items", actual)
                .append("hcoin", after.get("hcoin"))
                .append("scoin", after.get("scoin"))
                .append("mcoin", after.get("mcoin"));
        var file = dir.resolve("boxing-activity-settled-proof.json");
        if (phase.equals("settled"))
          Files.writeString(file, proof.toJson(), StandardOpenOption.CREATE_NEW);
        else
          require(
              Files.readString(file).equals(proof.toJson()),
              "Expiry/reopening reset progress or duplicated rewards");
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
                      "originalActivityRewardTotalsVerified",
                      true,
                      "formalAvatarDocumentUnchanged",
                      true)));
    } finally {
      server.shutdownNow();
    }
  }
}
