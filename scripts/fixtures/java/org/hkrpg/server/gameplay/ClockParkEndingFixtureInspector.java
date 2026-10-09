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
public final class ClockParkEndingFixtureInspector {
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
    require(
        Set.of("prepare", "inspect", "reserve-existing-item").contains(mode), "Unknown operation");
    var marker =
        JsonParser.parseString(
                Files.readString(directory.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    require(
        marker.get("testFixture").getAsBoolean()
            && marker.get("clockParkEndingFixture").getAsBoolean(),
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

      var items = store.getCollection("items");
      var itemFilter = new Document("ownerUid", uid).append("itemId", 140484);
      var actualItems = items.find(itemFilter).into(new ArrayList<Document>());
      require(
          actualItems.size() == control.get("actualUniqueItemCount").getAsInt(),
          "Unique item document count differs");
      if (!actualItems.isEmpty())
        require(actualItems.getFirst().getInteger("count") == 1, "Unique item pile differs");
      var storySnapshot = directory.resolve("actual-ending-story-clock.json");
      if (mode.equals("reserve-existing-item")) {
        require(
            actualItems.size() == 1 && Files.isRegularFile(storySnapshot),
            "Missing actual story or granted item");
        var prior = Document.parse(Files.readString(storySnapshot));
        var activeRun = prior.get("active", Document.class);
        require(
            activeRun.getInteger("chapter") == 103 && activeRun.getString("phase").equals("STORY"),
            "Not original ending story");
        activeRun.put("phase", "FINISHING");
        activeRun.put("endingSource", ClockParkEndingRewards.SOURCE);
        var updates =
            new Document("gameplay.clockPark", prior)
                .append(
                    "gameplay.rewards.clock-park-ending:1:103",
                    new Document("rewardId", 8008001).append("granted", false));
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
            "Recovery layout fixture not installed");
        require(
            expected.equals(players.find(filter).first()),
            "Recovery fixture changed unrelated player state");
        changed = 1;
      }
      var after = Objects.requireNonNull(players.find(filter).first());
      var gameplay = after.get("gameplay", Document.class);
      var clock = gameplay.get("clockPark", Document.class);
      require(clock != null, "Missing saved clock park state");
      int active = control.get("activeScript").getAsInt();
      var run = clock.get("active", Document.class);
      require((run == null) == (active == 0), "Wrong persisted active run");
      require(
          ((Number) clock.get("sequence")).longValue() == control.get("runsStarted").getAsLong(),
          "Wrong actual start count");
      var field = emu.lunarcore.LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
      field.setAccessible(true);
      field.set(
          emu.lunarcore.LunarCore.getConfig(), directory.resolve("resources-fixture").toString());
      var state = new Gson().fromJson(clock.toJson(), ClockParkState.class);
      require(
          state.valid(ClockParkManager.catalog()),
          "Invalid original sources, normal receipts or reached proofs");
      for (var entry : control.getAsJsonObject("reachedChapters").entrySet()) {
        var expected = new ArrayList<Integer>();
        for (var v : entry.getValue().getAsJsonArray()) expected.add(v.getAsInt());
        require(
            state.reached(Integer.parseInt(entry.getKey())).equals(expected),
            "Reached proof differs");
      }
      if (run != null) {
        require(
            run.getInteger("script") == active
                && run.getInteger("chapter") == control.get("chapter").getAsInt()
                && run.getString("phase")
                    .equals(
                        mode.equals("reserve-existing-item")
                            ? "FINISHING"
                            : control.get("phase").getAsString())
                && run.getString("transaction").equals(control.get("transaction").getAsString())
                && run.getInteger("roundIndex") == control.get("roundIndex").getAsInt(),
            "Persisted phase/position/UUID differs");
        var dice = new ArrayList<Integer>();
        for (var v : control.getAsJsonArray("pendingDice")) dice.add(v.getAsInt());
        require(run.getList("dice", Integer.class).equals(dice), "Owned pending faces differ");
        for (String name : List.of("attributeA", "attributeB", "attributeC"))
          require(
              run.getInteger(name) == control.get(name).getAsInt(), "Carried attribute differs");
      }

      if (run != null && run.getInteger("chapter") == 103 && run.getString("phase").equals("STORY"))
        Files.writeString(
            storySnapshot,
            clock.toJson(
                org.bson.json.JsonWriterSettings.builder()
                    .outputMode(org.bson.json.JsonMode.EXTENDED)
                    .build()));
      require(
          state.progress(ClockParkManager.catalog()) == control.get("progress").getAsInt(),
          "Stored progress differs");
      var receipt =
          gameplay.get("rewards", Document.class).get("clock-park-ending:1:103", Document.class);
      if (state.progress(ClockParkManager.catalog()) == 2000)
        require(
            receipt != null
                && receipt.getBoolean("granted")
                && receipt.getInteger("rewardId") == 8008001
                && actualItems.size() == 1,
            "Completed ending has no actual reward receipt/item");
      if (mode.equals("reserve-existing-item"))
        require(receipt != null && !receipt.getBoolean("granted"), "Recovery ledger not reserved");
      require(
          actualItems.equals(items.find(itemFilter).into(new ArrayList<Document>())),
          "Inspection or recovery fixture changed item identity");
      var identity = directory.resolve("actual-ending-item-identity.json");
      if (!actualItems.isEmpty()) {
        String actual = actualItems.getFirst().toJson();
        if (Files.exists(identity))
          require(
              Files.readString(identity).equals(actual),
              "Unique item identity changed across completion/recovery/repeat");
        else Files.writeString(identity, actual, StandardOpenOption.CREATE_NEW);
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
                      control.get("reachedChapters").toString(),
                      "clockPark",
                      clock.toJson())));
    } finally {
      server.shutdownNow();
    }
  }
}
