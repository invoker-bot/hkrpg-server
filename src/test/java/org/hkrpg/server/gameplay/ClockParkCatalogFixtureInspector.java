package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.bson.Document;

/** Read-only inspection of a stopped owned fixture. Only the separate snapshot is created. */
public final class ClockParkCatalogFixtureInspector {
  static void require(boolean value, String reason) {
    if (!value) throw new IllegalStateException(reason);
  }

  public static void main(String[] args) throws Exception {
    require(args.length == 3, "Expected fixture, credentials and availability");
    var root = Path.of("").toRealPath();
    var directory = Path.of(args[0]).toRealPath();
    var credentials = Path.of(args[1]).toRealPath();
    require(
        directory.startsWith(root.resolve("build/smoke"))
            && !directory.equals(root.resolve("build/smoke"))
            && credentials.startsWith(directory),
        "Not an owned fixture");
    var resources = directory.resolve("resources-fixture");
    var marker =
        JsonParser.parseString(Files.readString(resources.resolve("resource-manifest.json")))
            .getAsJsonObject();
    require(
        marker.get("testFixture").getAsBoolean()
            && marker.get("clockParkCatalogFixture").getAsBoolean(),
        "Missing fixture marker");
    boolean available = Boolean.parseBoolean(args[2]);
    require(
        (ClockParkCatalog.load(resources) != null) == available,
        "Restored resource catalog differs");
    byte[] configBytes = Files.readAllBytes(directory.resolve("config.json"));
    var configuration =
        JsonParser.parseString(new String(configBytes, java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
    var data =
        directory
            .resolve(
                configuration.getAsJsonObject("internalMongoServer").get("filePath").getAsString())
            .normalize();
    require(data.startsWith(directory), "Database outside fixture");
    var server = new MongoServer(new H2Backend(data.toString()));
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var database = client.getDatabase("hkrpg");
      var players = database.getCollection("players");
      int uid =
          JsonParser.parseString(Files.readString(credentials))
              .getAsJsonObject()
              .get("uid")
              .getAsInt();
      require(players.countDocuments() == 1, "Unexpected players");
      var filter = new Document("_id", uid);
      var before = Objects.requireNonNull(players.find(filter).first());
      var lineups = database.getCollection("lineups");
      var lineupFilter = new Document("ownerUid", uid).append("index", 0);
      var lineup = Objects.requireNonNull(lineups.find(lineupFilter).first());
      require(
          lineup.getList("avatars", Integer.class).equals(List.of(8001)), "Formal roster differs");
      var state = new Document();
      for (String field :
          List.of(
              "gameplay",
              "hcoin",
              "scoin",
              "mcoin",
              "worldLevel",
              "planeId",
              "floorId",
              "entryId",
              "pos",
              "rot",
              "curAvatarPaths",
              "questStates")) if (before.containsKey(field)) state.put(field, before.get(field));
      state.put("lineup", lineup);
      var settings =
          org.bson.json.JsonWriterSettings.builder()
              .outputMode(org.bson.json.JsonMode.EXTENDED)
              .build();
      String snapshot = state.toJson(settings);
      var snapshotPath = directory.resolve("clock-park-read-only-state.json");
      if (Files.exists(snapshotPath))
        require(
            Files.readString(snapshotPath).equals(snapshot),
            "Query/restart changed gameplay, wallet or lineup");
      else Files.writeString(snapshotPath, snapshot, StandardOpenOption.CREATE_NEW);
      require(
          before.equals(players.find(filter).first())
              && lineup.equals(lineups.find(lineupFilter).first()),
          "Inspector modified stored records");
      require(
          Arrays.equals(configBytes, Files.readAllBytes(directory.resolve("config.json"))),
          "Config changed");
      System.out.println(
          new Gson()
              .toJson(
                  Map.of(
                      "passed",
                      true,
                      "uid",
                      uid,
                      "available",
                      available,
                      "changedPlayers",
                      0,
                      "changedLineups",
                      0,
                      "stateSha256",
                      HexFormat.of()
                          .formatHex(
                              MessageDigest.getInstance("SHA-256")
                                  .digest(
                                      snapshot.getBytes(
                                          java.nio.charset.StandardCharsets.UTF_8))))));
    } finally {
      server.shutdownNow();
    }
  }
}
