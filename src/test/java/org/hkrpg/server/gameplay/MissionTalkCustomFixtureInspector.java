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

/** Seeds only an owned smoke database; never opens the operator database. */
public final class MissionTalkCustomFixtureInspector {
  public static void main(String[] args) throws Exception {
    var root = Path.of("").toRealPath();
    var directory = Path.of(args[0]).toRealPath();
    if (!directory.startsWith(root.resolve("build/smoke")) || directory.equals(root.resolve("build/smoke")))
      throw new IllegalArgumentException("Not an owned smoke directory");
    var marker = JsonParser.parseString(Files.readString(directory.resolve("resources-fixture/resource-manifest.json")))
        .getAsJsonObject();
    if (!marker.get("testFixture").getAsBoolean() || !marker.get("talkCustomFixture").getAsBoolean())
      throw new IllegalArgumentException("Missing fixture marker");
    int uid = Integer.parseInt(args[1]);
    var file = directory.resolve("data/database.mv").normalize();
    if (uid <= 0 || !file.startsWith(directory) || !Files.isRegularFile(file))
      throw new IllegalArgumentException("Invalid fixture identity or database");
    var backend = new H2Backend(file.toString());
    var server = new MongoServer(backend);
    var address = server.bind();
    try (var client = MongoClients.create("mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var players = client.getDatabase("hkrpg").getCollection("players");
      if (players.countDocuments() != 1 || players.countDocuments(Filters.eq("_id", uid)) != 1)
        throw new IllegalStateException("Not a unique fixture player");
      var before = Objects.requireNonNull(players.find(Filters.eq("_id", uid)).first());
      if (before.getInteger("floorId") != 10000003) throw new IllegalStateException("Wrong fixture floor");
      long changed = 0;
      if (args[2].equals("seed")) {
        var gameplay = before.get("gameplay", org.bson.Document.class);
        if (gameplay.get("mainMissions", org.bson.Document.class).containsKey("8027111")
            || gameplay.get("subMissions", org.bson.Document.class).containsKey("802711102"))
          throw new IllegalStateException("Target already exists in fixture");
        var main = new QuestState(8027111);
        var first = new QuestState(802711101); first.advance(1, 1, 1);
        var alternative = new QuestState(802711117); alternative.advance(1, 1, 1);
        changed = players.updateOne(Filters.eq("_id", uid), com.mongodb.client.model.Updates.combine(
            com.mongodb.client.model.Updates.set("gameplay.mainMissions.8027111", org.bson.Document.parse(new Gson().toJson(main))),
            com.mongodb.client.model.Updates.set("gameplay.subMissions.802711101", org.bson.Document.parse(new Gson().toJson(first))),
            com.mongodb.client.model.Updates.set("gameplay.subMissions.802711117", org.bson.Document.parse(new Gson().toJson(alternative)))))
            .getModifiedCount();
        if (changed != 1) throw new IllegalStateException("Fixture seed failed");
        backend.commit(); backend.getMvStore().sync();
      } else if (!args[2].equals("inspect")) throw new IllegalArgumentException("Unknown fixture mode");
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, emu.lunarcore.util.Position.class);
      var player = store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first();
      var saved = Objects.requireNonNull(player).getGameplay();
      if (args[2].equals("seed")) {
        if (saved.getSubMissions().containsKey(802711102) || saved.getStoryValues().containsKey("mission:8027111:OldValue_0"))
          throw new IllegalStateException("Fixture seed invented the target or choice");
      } else {
        if (!MissionStateRecords.completed(saved.getSubMissions().get(802711102), 802711102, 1)
            || !MissionStateRecords.doing(saved.getSubMissions().get(802711103), 802711103, 1)
            || !Objects.equals(saved.getStoryValues().get("mission:8027111:OldValue_0"), 1)
            || !Objects.equals(saved.getStoryValues().get("floor:10000003:Connection_MuteStairs"), 0))
          throw new IllegalStateException("Objective or choice did not persist");
      }
      var items = new TreeMap<Integer, Integer>();
      for (var item : client.getDatabase("hkrpg").getCollection("items").find(Filters.eq("ownerUid", uid))) {
        int id = item.getInteger("itemId", 0);
        if (id == 408636 || id == 408637) items.merge(id, item.getInteger("count", 0), Math::addExact);
      }
      if (!items.equals(args[2].equals("inspect") ? Map.of(408636, 1) : Map.of()))
        throw new IllegalStateException("Fixture inventory mismatch");
      System.out.println(new Gson().toJson(Map.of("passed", true, "mode", args[2], "uid", uid,
          "changedPlayers", changed, "items", items, "operatorDataModified", false)));
    } finally { server.shutdownNow(); }
  }
  private MissionTalkCustomFixtureInspector() {}
}
