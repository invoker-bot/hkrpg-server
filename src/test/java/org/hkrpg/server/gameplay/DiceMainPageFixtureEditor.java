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

/** Only seeds the completed tutorial in an isolated fixture; all roll history is read only. */
public final class DiceMainPageFixtureEditor {
  public static void main(String[] args) throws Exception {
    var root = Path.of("").toRealPath();
    var directory = Path.of(args[0]).toRealPath();
    if (!directory.startsWith(root.resolve("build/smoke"))
        || directory.equals(root.resolve("build/smoke")))
      throw new IllegalArgumentException("Not an isolated smoke directory");
    var marker =
        JsonParser.parseString(
                Files.readString(directory.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    if (!marker.get("testFixture").getAsBoolean()
        || !marker.get("diceMainPageFixture").getAsBoolean())
      throw new IllegalArgumentException("Dice fixture marker missing");
    int uid = Integer.parseInt(args[1]);
    if (uid <= 0) throw new IllegalArgumentException("Invalid fixture UID");
    var path = directory.resolve("data/database.mv").normalize();
    if (!path.startsWith(directory) || !Files.isRegularFile(path))
      throw new IllegalArgumentException("Existing fixture H2 missing");
    var backend = new H2Backend(path.toString());
    var server = new MongoServer(backend);
    server.bind("127.0.0.1", 0);
    try (var client = MongoClients.create(server.getConnectionString())) {
      var players = client.getDatabase("hkrpg").getCollection("players");
      var player = players.find(Filters.eq("_id", uid)).first();
      if (player == null) throw new IllegalStateException("Fixture player missing");
      long changed = 0;
      if (args[2].equals("seed")) {
        var counter = player.get("gameplay", Document.class).get("diceRolls", Document.class);
        if (counter == null || counter.get("count", Number.class).longValue() != 0)
          throw new IllegalStateException("Not a fresh fixture counter");
        var tutorial =
            new Document("questId", 4000001)
                .append("progress", 1)
                .append("finishTime", Instant.now().getEpochSecond())
                .append("phase", "FINISHED");
        changed =
            players
                .updateOne(Filters.eq("_id", uid), Updates.set("questStates.4000001", tutorial))
                .getModifiedCount();
        if (changed != 1) throw new IllegalStateException("Expected one tutorial fixture edit");
        backend.commit();
        backend.getMvStore().sync();
      } else if (!args[2].equals("inspect"))
        throw new IllegalArgumentException("Unknown fixture operation");
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              emu.lunarcore.game.player.Player.class,
              emu.lunarcore.game.quest.QuestState.class,
              emu.lunarcore.util.Position.class);
      var restored =
          store
              .find(emu.lunarcore.game.player.Player.class)
              .filter(dev.morphia.query.filters.Filters.eq("_id", uid))
              .first();
      if (restored == null || !restored.getGameplay().getDiceRolls().valid())
        throw new IllegalStateException("Invalid restored roll state");
      var tutorial = restored.getQuestStates().get(4000001);
      if (!MissionStateRecords.completed(tutorial, 4000001, 1))
        throw new IllegalStateException("Tutorial fixture did not restore");
      var quest = restored.getQuestStates().get(4055044);
      var result = new LinkedHashMap<String, Object>();
      result.put("passed", true);
      result.put("uid", uid);
      result.put("mode", args[2]);
      result.put("changedPlayers", changed);
      result.put("count", restored.getGameplay().getDiceRolls().getCount());
      result.put("lastMillis", restored.getGameplay().getDiceRolls().getLastMillis());
      result.put("hcoin", restored.getHcoin());
      result.put("questProgress", quest == null ? 0 : quest.getProgress());
      result.put("questPhase", quest == null ? "absent" : quest.getPhase().name());
      result.put("matchingPlayerCount", players.countDocuments(Filters.eq("_id", uid)));
      result.put("operatorDataModified", false);
      result.put("rollCounterEdited", false);
      System.out.println(new Gson().toJson(result));
    } finally {
      server.shutdownNow();
    }
  }
}
