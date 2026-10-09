package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;

/**
 * One prerequisite seed in a unique marked smoke database; all completion inspections read only.
 */
public final class MissionRelationsFixtureInspector {
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
        || !marker.get("missionRelationFixture").getAsBoolean())
      throw new IllegalArgumentException("Missing relation fixture marker");
    int uid = Integer.parseInt(args[1]);
    if (uid <= 0) throw new IllegalArgumentException("Invalid UID");
    var file = directory.resolve("data/database.mv").normalize();
    if (!file.startsWith(directory) || !Files.isRegularFile(file))
      throw new IllegalArgumentException("Missing fixture database");
    var backend = new H2Backend(file.toString());
    var server = new MongoServer(backend);
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var players = client.getDatabase("hkrpg").getCollection("players");
      if (players.countDocuments() != 1 || players.countDocuments(Filters.eq("_id", uid)) != 1)
        throw new IllegalStateException("Not a unique fixture player");
      var before = Objects.requireNonNull(players.find(Filters.eq("_id", uid)).first());
      if (before.getInteger("entryId") != 1055101 || before.getInteger("scoin") != 0)
        throw new IllegalStateException("Wrong original scene or wallet");
      long changed = 0;
      if (args[2].equals("seed")) {
        var gameplay = before.get("gameplay", org.bson.Document.class);
        if (!gameplay.get("mainMissions", org.bson.Document.class).isEmpty()
            || !gameplay.get("subMissions", org.bson.Document.class).isEmpty())
          throw new IllegalStateException("Prerequisites were not initially empty");
        var parent = new QuestState(1054502);
        var prerequisite = new QuestState(105450227);
        prerequisite.advance(1, 1, 1);
        changed =
            players
                .updateOne(
                    Filters.eq("_id", uid),
                    com.mongodb.client.model.Updates.combine(
                        com.mongodb.client.model.Updates.set(
                            "gameplay.mainMissions.1054502",
                            org.bson.Document.parse(new Gson().toJson(parent))),
                        com.mongodb.client.model.Updates.set(
                            "gameplay.subMissions.105450227",
                            org.bson.Document.parse(new Gson().toJson(prerequisite)))))
                .getModifiedCount();
        if (changed != 1)
          throw new IllegalStateException("Seed must modify exactly one owned player");
        backend.commit();
        backend.getMvStore().sync();
      } else if (!Set.of("seeded", "finished").contains(args[2]))
        throw new IllegalArgumentException("Unknown inspector mode");
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var player =
          store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first();
      var saved = player.getGameplay();
      if (player.getEntryId() != 1055101
          || player.getScoin() != 0
          || !saved.getRewards().isEmpty()
          || saved.getMainMissions().get(1054502).getPhase() != QuestState.Phase.DOING
          || saved.getSubMissions().get(105450227).getProgress() != 1)
        throw new IllegalStateException("Saved original acceptance or wallet changed");
      if (args[2].equals("finished")) {
        if (saved.getSubMissions().get(105450232).getProgress() != 2
            || saved.getSubMissions().get(105450232).getPhase() != QuestState.Phase.FINISHED
            || saved.getSubMissions().get(105450235).getProgress() != 1
            || saved.getSubMissions().get(105450235).getPhase() != QuestState.Phase.FINISHED
            || saved.getStoryActions().get(105450235).getNext() != 1
            || saved.getStoryActions().get(105450235).isPending())
          throw new IllegalStateException("Original monitor completion not restored");
        for (int id : List.of(105450230, 105450231, 105450234))
          if (saved.getSubMissions().get(id).getProgress() != 1
              || saved.getSubMissions().get(id).getPhase() != QuestState.Phase.FINISHED)
            throw new IllegalStateException("Original dialogue history changed");
      } else if (saved.getSubMissions().size() != 1 || !saved.getStoryActions().isEmpty())
        throw new IllegalStateException("Seed invented later completion");
      var result = new LinkedHashMap<String, Object>();
      result.put("passed", true);
      result.put("uid", uid);
      result.put("mode", args[2]);
      result.put("wallet", player.getScoin());
      result.put("changedPlayers", changed);
      result.put("operatorDataModified", false);
      result.put("mainMissions", saved.getMainMissions());
      result.put("subMissions", saved.getSubMissions());
      result.put("storyActions", saved.getStoryActions());
      System.out.println(new Gson().toJson(result));
    } finally {
      server.shutdownNow();
    }
  }

  private MissionRelationsFixtureInspector() {}
}
