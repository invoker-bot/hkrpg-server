package org.hkrpg.server.gameplay;

import com.google.gson.Gson;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/** One declared prerequisite in an owned copied database; no target or mascot progress writes. */
public final class MascotFixturePrerequisite {
  public static void main(String[] args) throws Exception {
    var workspace = Path.of("").toRealPath();
    var root = Path.of(args[0]).toRealPath();
    if (!root.startsWith(workspace.resolve("build/smoke"))
        || root.equals(workspace.resolve("build/smoke")))
      throw new IllegalArgumentException("Unowned fixture");
    var marker =
        Document.parse(Files.readString(root.resolve("resources-fixture/resource-manifest.json")));
    if (!Boolean.TRUE.equals(marker.getBoolean("mascotFixture"))
        || !Boolean.TRUE.equals(marker.getBoolean("testFixture")))
      throw new IllegalArgumentException("Missing marker");
    int uid = Integer.parseInt(args[1]);
    var backend = new H2Backend(root.resolve("data/database.mv").toString());
    var server = new MongoServer(backend);
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var db = client.getDatabase("hkrpg");
      var players = db.getCollection("players");
      var filter = Filters.eq("_id", uid);
      if (players.countDocuments() != 1 || players.countDocuments(filter) != 1)
        throw new IllegalStateException("Not one fixture player");
      var before = Objects.requireNonNull(players.find(filter).first());
      var gameplay = before.get("gameplay", Document.class);
      var main = gameplay.get("mainMissions", Document.class);
      for (int id : List.of(4015704, 4015705, 4015706))
        if (main.containsKey("" + id)) throw new IllegalStateException("Prewritten target mission");
      var world = gameplay.get("worldChests", Document.class);
      if (world.containsKey("mascots") && !world.get("mascots", Document.class).isEmpty())
        throw new IllegalStateException("Prewritten mascot");
      if (main.containsKey("1040101"))
        throw new IllegalStateException("Prerequisite fixture already exists");
      var q = new QuestState(1040101);
      q.advance(1, 1, 1);
      q.reserveReward();
      q.closeReward();
      var value = Document.parse(new Gson().toJson(q));
      if (players
              .updateOne(
                  filter,
                  new Document("$set", new Document("gameplay.mainMissions.1040101", value)))
              .getModifiedCount()
          != 1) throw new IllegalStateException("No unique fixture update");
      backend.commit();
      backend.getMvStore().sync();
      var after = Objects.requireNonNull(players.find(filter).first());
      var expected =
          Document.parse(
              before.toJson(
                  org.bson.json.JsonWriterSettings.builder()
                      .outputMode(org.bson.json.JsonMode.EXTENDED)
                      .build()));
      expected
          .get("gameplay", Document.class)
          .get("mainMissions", Document.class)
          .put("1040101", value);
      if (!expected.equals(after)) throw new IllegalStateException("Changed undeclared data");
      Files.writeString(
          root.resolve("mascot-prerequisite.json"),
          new Gson()
              .toJson(
                  Map.of(
                      "main",
                      1040101,
                      "changedPlayers",
                      1,
                      "targetProgressPrewritten",
                      false,
                      "previousChests",
                      world.get("records", Document.class).size())));
      System.out.println(
          "{\"passed\":true,\"declaredPrerequisite\":1040101,\"targetProgressPrewritten\":false}");
    } finally {
      server.shutdownNow();
    }
  }
}
