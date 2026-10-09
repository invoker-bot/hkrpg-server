package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/**
 * Prerequisites are written only to a stopped owned fixture; subsequent inspections are read only.
 */
public final class ClockParkDiceFixtureInspector {
  static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  static Document quest(int id, boolean done) {
    return new Document("questId", id)
        .append("progress", done ? 1 : 0)
        .append("finishTime", done ? 123L : 0L)
        .append("phase", done ? "CLOSED" : "DOING");
  }

  static void set(Document document, String path, Object value) {
    String[] parts = path.split("\\.");
    var parent = document;
    for (int i = 0; i < parts.length - 1; i++) {
      var child = parent.get(parts[i], Document.class);
      if (child == null) {
        child = new Document();
        parent.put(parts[i], child);
      }
      parent = child;
    }
    parent.put(parts[parts.length - 1], value);
  }

  public static void main(String[] args) throws Exception {
    require(args.length == 3, "Expected fixture, credentials and operation");
    var root = Path.of("").toRealPath();
    var directory = Path.of(args[0]).toRealPath();
    var credentials = Path.of(args[1]).toRealPath();
    require(
        directory.startsWith(root.resolve("build/smoke"))
            && !directory.equals(root.resolve("build/smoke"))
            && credentials.startsWith(directory),
        "Not an owned fixture");
    String mode = args[2];
    require(Set.of("prepare", "inspect").contains(mode), "Unknown operation");
    var marker =
        JsonParser.parseString(
                Files.readString(directory.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    require(
        marker.get("testFixture").getAsBoolean()
            && marker.get("clockParkDiceFixture").getAsBoolean(),
        "Missing fixture marker");
    var control = JsonParser.parseString(Files.readString(credentials)).getAsJsonObject();
    int uid = control.get("uid").getAsInt();
    byte[] configBytes = Files.readAllBytes(directory.resolve("config.json"));
    var config =
        JsonParser.parseString(new String(configBytes, java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
    var data =
        directory
            .resolve(config.getAsJsonObject("internalMongoServer").get("filePath").getAsString())
            .normalize();
    require(data.startsWith(directory), "Database outside fixture");
    var server = new MongoServer(new H2Backend(data.toString()));
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = client.getDatabase("hkrpg");
      var players = store.getCollection("players");
      var filter = new Document("_id", uid);
      require(players.countDocuments() == 1, "Unexpected players");
      var before = Objects.requireNonNull(players.find(filter).first());
      var lineups = store.getCollection("lineups");
      var lineupFilter = new Document("ownerUid", uid).append("index", 0);
      var lineup = Objects.requireNonNull(lineups.find(lineupFilter).first());
      require(
          lineup.getList("avatars", Integer.class).equals(List.of(8001)), "Formal roster changed");
      int changed = 0;
      if (mode.equals("prepare")) {
        var updates = new Document("level", 21);
        updates.put("gameplay.clockPark.nextCardPool", ClockParkCards.POOL_RULE);
        int[] subs = {802210101, 802210801, 802212004, 802212404, 802210311};
        int[] mains = {8022101, 8022108, 8022120, 8022124, 8022103};
        for (int i = 0; i < subs.length; i++) {
          updates.put("gameplay.mainMissions." + mains[i], quest(mains[i], true));
          updates.put("gameplay.subMissions." + subs[i], quest(subs[i], true));
        }
        var result = players.updateOne(filter, new Document("$set", updates));
        require(
            result.getMatchedCount() == 1 && result.getModifiedCount() == 1,
            "Prerequisite fixture not installed");
        changed = 1;
        var expected =
            Document.parse(
                before.toJson(
                    org.bson.json.JsonWriterSettings.builder()
                        .outputMode(org.bson.json.JsonMode.EXTENDED)
                        .build()));
        updates.forEach((path, value) -> set(expected, path, value));
        require(
            expected.equals(players.find(filter).first()),
            "Fixture changed unrelated player fields");
      }
      var after = Objects.requireNonNull(players.find(filter).first());
      var gameplay = after.get("gameplay", Document.class);
      var clock = gameplay.get("clockPark", Document.class);
      require(clock != null, "Missing saved clock park state");
      int active = control.get("activeScript").getAsInt(),
          reached = control.get("reachedScripts").getAsInt();
      var run = clock.get("active", Document.class);
      require((run == null) == (active == 0), "Active persisted run differs");
      require(
          ((Number) clock.get("sequence")).longValue() == reached,
          "Wrong persisted start sequence");
      var chapters = clock.get("reached", Document.class);
      require(chapters.size() == reached, "Wrong persisted reached count");
      for (int id = 1; id <= reached; id++)
        require(
            chapters
                .getList(Integer.toString(id), Integer.class)
                .equals(List.of(new int[] {101, 201, 301, 410, 510}[id - 1])),
            "Wrong reached chapter");
      if (run != null) {
        require(
            run.getInteger("script") == active
                && run.getInteger("chapter") == new int[] {101, 201, 301, 410, 510}[active - 1]
                && run.getString("transaction").equals(control.get("transaction").getAsString()),
            "Persisted opening differs");
        require(
            run.getInteger("story") == new int[] {10101, 20101, 30101, 40101, 50101}[active - 1],
            "Wrong original story id");
      }
      if (run != null) {
        require("DICE".equals(run.getString("phase")), "Not saved dice waiting state");
        require(
            "hkrpg.clockpark.cards-1001-1002-1003.v1".equals(run.getString("cardPool")),
            "Missing explicit project pool");
        int index = control.get("roundIndex").getAsInt();
        require(
            run.getInteger("roundIndex") == index
                && run.getInteger("round")
                    == new int[] {10101, 20101, 30101, 41001, 51001}[active - 1] + index,
            "Wrong original pending round");
        var dice = new ArrayList<Integer>();
        for (var face : control.getAsJsonArray("pendingDice")) dice.add(face.getAsInt());
        require(run.getList("dice", Integer.class).equals(dice), "Pending server dice changed");
        for (String name : List.of("attributeA", "attributeB", "attributeC"))
          require(
              run.getInteger(name) == control.get(name).getAsInt(),
              "Effect receipt differs: " + name);
        var receipts = run.getList("receipts", Document.class);
        require(receipts.size() == index, "Wrong placement receipt count");
        for (int i = 0; i < index; i++) {
          var receipt = receipts.get(i);
          require(
              receipt.getInteger("round")
                  == new int[] {10101, 20101, 30101, 41001, 51001}[active - 1] + i,
              "Foreign applied round");
          require(
              new HashSet<>(receipt.getList("indices", Integer.class)).equals(Set.of(0, 1, 2))
                  && receipt.getList("faces", Integer.class).size() == 3,
              "Invalid original dice placement receipt");
        }
      }
      var wallet = new Document();
      for (String name : List.of("hcoin", "scoin", "mcoin", "worldLevel"))
        if (after.containsKey(name)) wallet.put(name, after.get(name));
      wallet.put("lineup", lineup);
      String protectedState = wallet.toJson();
      var snapshot = directory.resolve("opening-wallet-lineup.json");
      if (Files.exists(snapshot))
        require(
            Files.readString(snapshot).equals(protectedState),
            "Opening changed wallet or formal lineup: " + protectedState);
      else Files.writeString(snapshot, protectedState, StandardOpenOption.CREATE_NEW);
      require(
          after.equals(players.find(filter).first())
              && lineup.equals(lineups.find(lineupFilter).first()),
          "Read-only inspection changed records");
      require(
          Arrays.equals(configBytes, Files.readAllBytes(directory.resolve("config.json"))),
          "Operator config changed");
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
                      "activeScript",
                      active,
                      "reachedScripts",
                      reached,
                      "clockPark",
                      clock.toJson())));
    } finally {
      server.shutdownNow();
    }
  }
}
