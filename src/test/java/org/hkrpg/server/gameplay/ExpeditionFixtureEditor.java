package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.bson.Document;

/**
 * Offline fixture seeding only; absent from the server jar and never an operator-data migration.
 */
public final class ExpeditionFixtureEditor {
  public static void main(String[] args) throws Exception {
    var root = Path.of("").toRealPath();
    var directory = Path.of(args[0]).toRealPath();
    if (!directory.startsWith(root.resolve("build/smoke"))
        || directory.equals(root.resolve("build/smoke")))
      throw new IllegalArgumentException("Not a smoke fixture directory");
    var marker =
        JsonParser.parseString(
                Files.readString(directory.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    if (!marker.get("testFixture").getAsBoolean()
        || !marker.get("expeditionFixture").getAsBoolean())
      throw new IllegalArgumentException("Fixture marker missing");
    int uid = Integer.parseInt(args[1]);
    if (uid <= 0) throw new IllegalArgumentException("Invalid fixture UID");
    var path = directory.resolve("data/database.mv").normalize();
    if (!path.startsWith(directory) || !Files.isRegularFile(path))
      throw new IllegalArgumentException("Existing isolated H2 file missing");
    var backend = new H2Backend(path.toString());
    var server = new MongoServer(backend);
    server.bind("127.0.0.1", 0);
    try (var client = MongoClients.create(server.getConnectionString())) {
      var players = client.getDatabase("hkrpg").getCollection("players");
      var player = players.find(Filters.eq("_id", uid)).first();
      if (player == null) throw new IllegalStateException("Fixture player missing");
      var gameplay = player.get("gameplay", Document.class);
      var state = gameplay.get("expeditions", Document.class);
      var assignments = state.getList("assignments", Document.class);
      long time = ((Number) state.get("rewardTime")).longValue();
      long changed = 0;
      if (args[2].equals("seed")) {
        if (!assignments.isEmpty() || time != 0 || state.get("lastAttempt") != null)
          throw new IllegalStateException("Not a fresh locked fixture");
        var quest =
            new Document("questId", 1010403)
                .append("progress", 1)
                .append("finishTime", Instant.now().getEpochSecond())
                .append("phase", "CLOSED");
        changed =
            players
                .updateOne(
                    Filters.eq("_id", uid), Updates.set("gameplay.mainMissions.1010403", quest))
                .getModifiedCount();
      } else if (args[2].equals("age")) {
        if (!assignments.stream().map(r -> r.getInteger("id")).toList().equals(List.of(1001, 1002))
            || time <= 7200
            || time % 3600 != 0
            || state.get("lastAttempt") != null)
          throw new IllegalStateException("Not the fresh dispatched fixture");
        changed =
            players
                .updateOne(
                    Filters.and(
                        Filters.eq("_id", uid),
                        Filters.eq("gameplay.expeditions.rewardTime", time)),
                    Updates.set("gameplay.expeditions.rewardTime", time - 7200))
                .getModifiedCount();
      } else if (!args[2].equals("inspect"))
        throw new IllegalArgumentException("Unknown fixture edit");
      if (!args[2].equals("inspect") && changed != 1)
        throw new IllegalStateException("Fixture update did not match exactly one player");
      if (changed == 1) {
        backend.commit();
        backend.getMvStore().sync();
      }
      player = players.find(Filters.eq("_id", uid)).first();
      var result = new LinkedHashMap<String, Object>();
      result.put("passed", true);
      result.put("uid", uid);
      result.put("mode", args[2]);
      result.put("changedPlayers", changed);
      result.put("gameplay", player.get("gameplay"));
      {
        var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
        store.getMapper().mapPackage("org.hkrpg.server.gameplay");
        store
            .getMapper()
            .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
        var restored =
            store
                .find(emu.lunarcore.game.player.Player.class)
                .filter(dev.morphia.query.filters.Filters.eq("_id", uid))
                .first();
        result.put("restoredMainMissionGate", restored.getMissionManager().finished(1010403));
        result.put("restoredMainMissions", restored.getGameplay().getMainMissions());
        result.put("restoredExpeditions", restored.getGameplay().getExpeditions());
        if (!args[2].equals("inspect") && !restored.getMissionManager().finished(1010403))
          throw new IllegalStateException("Fixture mission did not decode after edit");
      }
      result.put("operatorDataModified", false);
      result.put("matchingPlayerCount", players.countDocuments(Filters.eq("_id", uid)));
      System.out.println(new Gson().toJson(result));
    } finally {
      server.shutdownNow();
    }
  }

  private ExpeditionFixtureEditor() {}
}
