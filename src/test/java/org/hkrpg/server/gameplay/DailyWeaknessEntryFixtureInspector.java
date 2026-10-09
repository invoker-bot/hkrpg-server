package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;

/** Reads the earned daily goals from this marked probe's normally stopped H2 database. */
public final class DailyWeaknessEntryFixtureInspector {
  public static void main(String[] args) throws Exception {
    var root = Path.of("").toRealPath();
    var directory = Path.of(args[0]).toRealPath();
    if (!directory.startsWith(root.resolve("build/smoke"))
        || directory.equals(root.resolve("build/smoke")))
      throw new IllegalArgumentException("Not an owned smoke directory");
    var marker =
        JsonParser.parseString(
                Files.readString(directory.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    if (!marker.get("testFixture").getAsBoolean()
        || !marker.get("dailyWeaknessEntryFixture").getAsBoolean())
      throw new IllegalArgumentException("Missing fixture marker");
    int uid = Integer.parseInt(args[1]);
    String mode = args[2];
    if (uid <= 0 || !Set.of("one", "ready", "claimed").contains(mode))
      throw new IllegalArgumentException("Invalid inspector arguments");
    var file = directory.resolve("data/database.mv");
    if (!Files.isRegularFile(file)) throw new IllegalStateException("Missing fixture database");
    var server = new MongoServer(new H2Backend(file.toString()));
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, emu.lunarcore.util.Position.class);
      if (store.find(Player.class).count() != 1)
        throw new IllegalStateException("Not a unique fixture player");
      var collection = store.getDatabase().getCollection("players");
      var query = new org.bson.Document("_id", uid);
      var original = Objects.requireNonNull(collection.find(query).first()).toJson();
      var goals = new TreeMap<Integer, Map<String, Object>>();
      int points = mode.equals("claimed") ? 200 : 0;
      int wins = mode.equals("one") ? 1 : 3;
      for (int read = 0; read < 2; read++) {
        var player =
            Objects.requireNonNull(
                store
                    .find(Player.class)
                    .filter(dev.morphia.query.filters.Filters.eq("_id", uid))
                    .first());
        var saved = player.getGameplay();
        if (saved.getDailyPoints() != points || saved.getDailyQuests().size() != 2)
          throw new IllegalStateException("Incorrect saved daily points or goals");
        for (int id : List.of(2100123, 2100142)) {
          var state = Objects.requireNonNull(saved.getDailyQuests().get(id));
          int goal = id == 2100123 ? 3 : 1;
          int progress = Math.min(wins, goal);
          var phase =
              points > 0
                  ? QuestState.Phase.CLOSED
                  : progress == goal ? QuestState.Phase.FINISHED : QuestState.Phase.DOING;
          if (state.getProgress() != progress
              || state.getPhase() != phase
              || (state.getFinishTime() > 0) != (progress == goal))
            throw new IllegalStateException("Saved earned daily state differs " + id);
          goals.put(
              id,
              Map.of(
                  "progress",
                  progress,
                  "phase",
                  phase.name(),
                  "finishTime",
                  state.getFinishTime()));
        }
        if (!original.equals(Objects.requireNonNull(collection.find(query).first()).toJson()))
          throw new IllegalStateException("Read changed stored player");
      }
      var output = new LinkedHashMap<String, Object>();
      output.put("passed", true);
      output.put("uid", uid);
      output.put("mode", mode);
      output.put("points", points);
      output.put("goals", goals);
      output.put("actualPlayerReads", 2);
      output.put("changedPlayers", 0);
      System.out.println(new Gson().toJson(output));
    } finally {
      server.shutdownNow();
    }
  }

  private DailyWeaknessEntryFixtureInspector() {}
}
