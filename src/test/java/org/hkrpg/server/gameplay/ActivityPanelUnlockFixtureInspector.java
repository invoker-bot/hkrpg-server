package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.util.Position;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/** Explicit prerequisites in an owned stopped H2 fixture; query inspections do not write. */
public final class ActivityPanelUnlockFixtureInspector {
  static void require(boolean condition, String reason) {
    if (!condition) throw new IllegalStateException(reason);
  }

  static Document state(int id, String phase) {
    return new Document("questId", id)
        .append("progress", phase.equals("DOING") ? 0 : 1)
        .append("finishTime", phase.equals("DOING") ? 0L : 123L)
        .append("phase", phase);
  }

  public static void main(String[] args) throws Exception {
    require(args.length == 3, "Expected owned directory, credentials, operation");
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
        Set.of("pending", "main", "sub", "closed", "prepareMain", "prepareSub", "prepareClosed")
            .contains(mode),
        "Unknown operation");
    var resources = directory.resolve("resources-fixture");
    var marker =
        JsonParser.parseString(Files.readString(resources.resolve("resource-manifest.json")))
            .getAsJsonObject();
    require(
        marker.get("testFixture").getAsBoolean()
            && marker.get("activityPanelUnlockFixture").getAsBoolean(),
        "Missing fixture marker");
    var config = new emu.lunarcore.Config();
    config.resourceDir = resources.toString();
    config.dataDir = directory.resolve("data").toString();
    config.protocol.wireVersion = "4.6.0";
    var field = emu.lunarcore.LunarCore.class.getDeclaredField("config");
    field.setAccessible(true);
    field.set(null, config);
    ResourceLoader.loadResources();
    GameplayDefinitions.loadMissions(resources.resolve("Config/Level/Mission"));
    var configBytes = Files.readAllBytes(directory.resolve("config.json"));
    var configuration =
        JsonParser.parseString(new String(configBytes, java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
    var data =
        directory
            .resolve(
                configuration.getAsJsonObject("internalMongoServer").get("filePath").getAsString())
            .normalize();
    require(data.startsWith(directory), "Database must stay inside fixture");
    var server = new MongoServer(new H2Backend(data.toString()));
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, Position.class);
      int uid =
          JsonParser.parseString(Files.readString(credentials))
              .getAsJsonObject()
              .get("uid")
              .getAsInt();
      require(store.find(Player.class).count() == 1, "Unexpected players");
      var players = store.getDatabase().getCollection("players");
      var filter = new Document("_id", uid);
      var before = Objects.requireNonNull(players.find(filter).first());
      var lineups = store.getDatabase().getCollection("lineups");
      var lineupFilter = new Document("ownerUid", uid).append("index", 0);
      var lineup = Objects.requireNonNull(lineups.find(lineupFilter).first());
      require(
          lineup.getList("avatars", Integer.class).equals(List.of(8001)), "Formal roster changed");
      var updates = new Document();
      var removed = new Document();
      if (mode.equals("prepareMain"))
        updates.put("gameplay.mainMissions.4020103", state(4020103, "CLOSED"));
      if (mode.equals("prepareSub")) {
        removed.put("gameplay.mainMissions.4020103", "");
        updates.put("gameplay.mainMissions.8017101", state(8017101, "DOING"));
        updates.put("gameplay.subMissions.801710102", state(801710102, "CLOSED"));
      }
      if (mode.equals("prepareClosed"))
        updates.put("questStates.3000018", state(3000018, "CLOSED"));
      int changed = 0;
      if (!updates.isEmpty()) {
        var mutation = new Document("$set", updates);
        if (!removed.isEmpty()) mutation.put("$unset", removed);
        var result = players.updateOne(filter, mutation);
        require(
            result.getMatchedCount() == 1 && result.getModifiedCount() == 1,
            "Prerequisite fixture was not installed");
        changed = 1;
        // Reconstruct precisely the permitted fixture edits; every other BSON field must match.
        var expected =
            Document.parse(
                before.toJson(
                    org.bson.json.JsonWriterSettings.builder()
                        .outputMode(org.bson.json.JsonMode.EXTENDED)
                        .build()));
        for (var item : updates.entrySet()) set(expected, item.getKey(), item.getValue());
        for (var key : removed.keySet()) set(expected, key, null);
        require(expected.equals(players.find(filter).first()), "Fixture changed unrelated fields");
      }
      var player =
          Objects.requireNonNull(
              store
                  .find(Player.class)
                  .filter(dev.morphia.query.filters.Filters.eq("_id", uid))
                  .first());
      boolean boss = !mode.equals("pending"),
          quest = Set.of("closed", "prepareClosed").contains(mode);
      require(
          ActivityAccess.unlocked(player, 10061) == boss
              && ActivityAccess.unlocked(player, 10025) == quest,
          "Original prerequisites did not restore");
      require(
          player.getWorldLevel() == 0 && player.getHcoin() == 0 && player.getScoin() == 0,
          "Fixture changed player world or rewards");
      require(lineup.equals(lineups.find(lineupFilter).first()), "Inspector changed formal lineup");
      if (changed == 0)
        require(
            before.equals(players.find(filter).first()), "Read-only inspection modified player");
      require(
          Arrays.equals(configBytes, Files.readAllBytes(directory.resolve("config.json"))),
          "Config changed");
      System.out.println(
          new Gson()
              .toJson(
                  Map.of(
                      "passed",
                      true,
                      "operation",
                      mode,
                      "changedPlayers",
                      changed,
                      "changedLineups",
                      0,
                      "uid",
                      uid,
                      "formalLineup",
                      lineup.toJson(),
                      "bossPanelUnlocked",
                      boss,
                      "questClosePanelUnlocked",
                      quest)));
    } finally {
      server.shutdownNow();
    }
  }

  private static void set(Document document, String path, Object value) {
    String[] parts = path.split("\\.");
    var parent = document;
    for (int index = 0; index < parts.length - 1; index++) {
      var child = parent.get(parts[index], Document.class);
      if (child == null) {
        child = new Document();
        parent.put(parts[index], child);
      }
      parent = child;
    }
    if (value == null) parent.remove(parts[parts.length - 1]);
    else parent.put(parts[parts.length - 1], value);
  }
}
