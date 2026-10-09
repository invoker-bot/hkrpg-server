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
 * Two predecessor seeds in a unique marked smoke database; all message and final talk history is
 * read only.
 */
public final class MissionTalkGatesFixtureInspector {
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
        || !marker.get("missionTalkGatesFixture").getAsBoolean())
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
      if (before.getInteger("entryId") != 2032101
          || before.getInteger("scoin") != (args[2].equals("finished") ? 5000 : 0))
        throw new IllegalStateException("Wrong original scene or wallet");
      long changed = 0;
      if (args[2].equals("seed")) {
        var gameplay = before.get("gameplay", org.bson.Document.class);
        if (!gameplay.get("mainMissions", org.bson.Document.class).isEmpty()
            || !gameplay.get("subMissions", org.bson.Document.class).isEmpty())
          throw new IllegalStateException("Prerequisites were not initially empty");
        var parent = new QuestState(4340111);
        var prerequisite = new QuestState(434011107);
        var wait = new QuestState(434011108);
        wait.advance(1, 1, 1);
        prerequisite.advance(1, 1, 1);
        changed =
            players
                .updateOne(
                    Filters.eq("_id", uid),
                    com.mongodb.client.model.Updates.combine(
                        com.mongodb.client.model.Updates.set(
                            "gameplay.mainMissions.4340111",
                            org.bson.Document.parse(new Gson().toJson(parent))),
                        com.mongodb.client.model.Updates.set(
                            "gameplay.subMissions.434011107",
                            org.bson.Document.parse(new Gson().toJson(prerequisite))),
                        com.mongodb.client.model.Updates.set(
                            "gameplay.subMissions.434011108",
                            org.bson.Document.parse(new Gson().toJson(wait)))))
                .getModifiedCount();
        if (changed != 1)
          throw new IllegalStateException("Seed must modify exactly one owned player");
        backend.commit();
        backend.getMvStore().sync();
      } else if (!Set.of("seeded", "finished").contains(args[2]))
        throw new IllegalArgumentException("Unknown inspector mode");
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, emu.lunarcore.util.Position.class);
      var player =
          store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first();
      var saved = player.getGameplay();
      if (player.getEntryId() != 2032101
          || saved.getSubMissions().get(434011107).getProgress() != 1
          || saved.getSubMissions().get(434011108).getProgress() != 1)
        throw new IllegalStateException("Original predecessors did not restore");
      boolean finished = args[2].equals("finished");
      if (player.getScoin() != (finished ? 5000 : 0)
          || saved.getMainMissions().get(4340111).getPhase()
              != (finished ? QuestState.Phase.CLOSED : QuestState.Phase.DOING))
        throw new IllegalStateException("Main reward or completion changed");
      var phone = saved.getPhoneSections().get(1306100);
      if (finished) {
        if (!MissionStateRecords.completed(saved.getSubMissions().get(434011111), 434011111, 1)
            || !MissionStateRecords.completed(saved.getSubMissions().get(434011114), 434011114, 1)
            || phone == null
            || !phone.isFinished()
            || !phone.getHistory().contains(130610009)
            || phone.getHistory().contains(130610011)
            || !phone.getOffered().isEmpty())
          throw new IllegalStateException("Original message and talk completion did not restore");
      } else if (phone != null
          || saved.getSubMissions().size() != 2
          || !saved.getStoryActions().isEmpty())
        throw new IllegalStateException("Seed invented message or final talk history");
      var gameItems = client.getDatabase("hkrpg").getCollection("items");
      var stacks = new TreeMap<Integer, Integer>();
      for (var item : gameItems.find(Filters.eq("ownerUid", uid))) {
        int id = item.getInteger("itemId", 0);
        if (id == 212 || id == 222) stacks.merge(id, item.getInteger("count", 0), Math::addExact);
      }
      if (!stacks.equals(finished ? Map.of(212, 3, 222, 2) : Map.of()))
        throw new IllegalStateException("Actual saved reward stacks changed");
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
      result.put("phoneSections", saved.getPhoneSections());
      result.put("materialStacks", stacks);
      result.put("finalTalkEdited", false);
      result.put("messageHistoryEdited", false);
      System.out.println(new Gson().toJson(result));
    } finally {
      server.shutdownNow();
    }
  }

  private MissionTalkGatesFixtureInspector() {}
}
