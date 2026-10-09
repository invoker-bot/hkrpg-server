package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/**
 * Offline death/clock fixture only, absent from the runtime jar and guarded to owned smoke data.
 */
public final class RogueRevivalFixtureEditor {
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
    if (!marker.get("testFixture").getAsBoolean() || !marker.get("revivalFixture").getAsBoolean())
      throw new IllegalArgumentException("Missing revival fixture marker");
    int uid = Integer.parseInt(args[1]);
    if (uid <= 0) throw new IllegalArgumentException("Invalid fixture UID");
    var file = directory.resolve("data/database.mv").normalize();
    if (!file.startsWith(directory) || !Files.isRegularFile(file))
      throw new IllegalArgumentException("Missing existing fixture database");
    var backend = new H2Backend(file.toString());
    var server = new MongoServer(backend);
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var players = client.getDatabase("hkrpg").getCollection("players");
      if (players.countDocuments() != 1 || players.countDocuments(Filters.eq("_id", uid)) != 1)
        throw new IllegalStateException("Not the unique owned fixture player");
      var document = Objects.requireNonNull(players.find(Filters.eq("_id", uid)).first());
      var run = document.get("gameplay", Document.class).get("rogue", Document.class);
      if (!"EXPLORING".equals(run.getString("phase"))
          || !Boolean.TRUE.equals(run.getBoolean("inside"))
          || run.getInteger("areaId") != 100
          || run.getInteger("roomId") != 306
          || !run.getList("roster", Integer.class).equals(List.of(8001, 1202))
          || run.getInteger("revivalCost") != 80
          || run.getInteger("revivalHp") != 10000)
        throw new IllegalStateException("Not the saved original rest-room run");
      long changed = 0;
      if (args[2].equals("seed")) {
        var vitals = run.get("vitals", Document.class);
        var curios = run.get("miracles", Document.class);
        if (run.getInteger("coins") != 200
            || !curios.isEmpty()
            || !vitals.keySet().equals(Set.of("8001", "1202")))
          throw new IllegalStateException("Not a fresh revival fixture");
        for (String id : vitals.keySet()) {
          var vital = vitals.get(id, Document.class);
          if (vital.getInteger("hp") != 10000 || vital.getInteger("sp") != 5000)
            throw new IllegalStateException("Unexpected original independent vital");
        }
        var clock = new GameplayState.RogueMiracle(0, List.of(.25));
        clock.freezeClockBreakParameters(List.of(499.0, 999.0));
        clock.recordExtraFragments(490);
        var clockDocument = Document.parse(new Gson().toJson(clock));
        changed =
            players
                .updateOne(
                    Filters.and(Filters.eq("_id", uid), Filters.eq("gameplay.rogue.coins", 200)),
                    Updates.combine(
                        Updates.set("gameplay.rogue.vitals.8001.hp", 0),
                        Updates.set("gameplay.rogue.vitals.1202.hp", 0),
                        Updates.set("gameplay.rogue.miracles.70", clockDocument)))
                .getModifiedCount();
        if (changed != 1)
          throw new IllegalStateException("Fixture seed did not match exactly one player");
        backend.commit();
        backend.getMvStore().sync();
      } else if (!args[2].equals("dead") && !args[2].equals("revived"))
        throw new IllegalArgumentException("Unknown fixture mode");
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var player =
          store
              .find(emu.lunarcore.game.player.Player.class)
              .filter(dev.morphia.query.filters.Filters.eq("_id", uid))
              .first();
      var restored = player.getGameplay().getRogue();
      boolean revived = args[2].equals("revived");
      if (restored.getCoins() != (revived ? 999 : 200)
          || !restored.getRoster().equals(List.of(8001, 1202))
          || restored.getMp() != 5
          || restored.getVitals().size() != 2
          || !restored.getMiracles().keySet().equals(Set.of(revived ? 9 : 70)))
        throw new IllegalStateException("Saved clock/roster/wallet changed");
      for (var vital : restored.getVitals().values())
        if (vital.getHp() != (revived ? 10000 : 0) || vital.getSp() != 5000)
          throw new IllegalStateException("Saved revival vital changed");
      var item = restored.getMiracles().get(revived ? 9 : 70);
      if (item.getAcquisitionId().isBlank()
          || !item.getEffectParameters().equals(revived ? List.of(499.0, 999.0) : List.of(.25)))
        throw new IllegalStateException("Saved curio acquisition changed");
      if (!revived && item.getExtraFragmentsSpent() != 490)
        throw new IllegalStateException("Saved fixture extra expenditure changed");
      var result = new LinkedHashMap<String, Object>();
      result.put("passed", true);
      result.put("uid", uid);
      result.put("mode", args[2]);
      result.put("changedPlayers", changed);
      result.put("operatorDataModified", false);
      result.put("restoredRun", restored);
      System.out.println(new Gson().toJson(result));
    } finally {
      server.shutdownNow();
    }
  }

  private RogueRevivalFixtureEditor() {}
}
