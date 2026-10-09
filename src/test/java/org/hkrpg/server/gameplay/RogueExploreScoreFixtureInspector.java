package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;

/** Read-only inspection of the marked smoke player's real, normally stopped H2 database. */
public final class RogueExploreScoreFixtureInspector {
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
        || !marker.get("rogueExploreScoreFixture").getAsBoolean())
      throw new IllegalArgumentException("Missing fixture marker");
    int uid = Integer.parseInt(args[1]);
    boolean done = args[2].equals("finished");
    if (uid <= 0 || !Set.of("pending", "finished").contains(args[2]))
      throw new IllegalArgumentException("Invalid inspector arguments");
    var file = directory.resolve("data/database.mv");
    if (!Files.isRegularFile(file)) throw new IllegalStateException("Missing fixture database");
    var backend = new H2Backend(file.toString());
    var server = new MongoServer(backend);
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, emu.lunarcore.util.Position.class);
      if (store.find(Player.class).count() != 1)
        throw new IllegalStateException("Not a unique fixture player");
      var player =
          Objects.requireNonNull(
              store
                  .find(Player.class)
                  .filter(dev.morphia.query.filters.Filters.eq("_id", uid))
                  .first());
      var saved = player.getGameplay();
      var sub = saved.getSubMissions().get(403000601);
      var main = saved.getMainMissions().get(4030006);
      if (player.getHcoin() != 0
          || player.getScoin() != Integer.parseInt(args[3])
          || sub == null
          || sub.getProgress() != (done ? 1 : 0)
          || sub.getPhase() != (done ? QuestState.Phase.FINISHED : QuestState.Phase.DOING)
          || main == null
          || main.getPhase() != (done ? QuestState.Phase.CLOSED : QuestState.Phase.DOING))
        throw new IllegalStateException("Actual wallet or mission did not restore");
      if (saved.getRogueResults().size() != (done ? 1 : 0)
          || saved.getCycleWeeks().size() != (done ? 1 : 0))
        throw new IllegalStateException("Actual result/week count changed");
      if (done) {
        var result = saved.getRogueResults().values().iterator().next();
        var week = saved.getCycleWeeks().values().iterator().next();
        if (result.getOutcome() != GameplayState.RogueRun.Phase.ABANDONED
            || result.getAreaId() != 110
            || result.getPassedRooms() != 1
            || result.getRoomScore() != 260
            || result.getCyclePoints() != 260
            || !result.isCycleReady()
            || !result.isCycleSettled()
            || !result.isEarningsReady()
            || result.getExploreMissionTargets() == null
            || !result.getExploreMissionTargets().keySet().equals(Set.of(403000601))
            || result.getExploreMissionTargets().get(403000601).isBlank()
            || week.getScore() != 260
            || week.getStart() != result.getCycleStart()
            || week.getEnd() != result.getCycleEnd()
            || !week.getClaims().isEmpty())
          throw new IllegalStateException(
              "Frozen target, earned room score or week did not restore");
      }
      var output = new LinkedHashMap<String, Object>();
      output.put("passed", true);
      output.put("uid", uid);
      output.put("mode", args[2]);
      output.put("credits", player.getScoin());
      output.put("main", main);
      output.put("sub", sub);
      output.put("rogueResults", saved.getRogueResults());
      output.put("cycleWeeks", saved.getCycleWeeks());
      output.put("changedPlayers", 0);
      output.put("missionProgressOrScoreSeeded", false);
      System.out.println(new Gson().toJson(output));
    } finally {
      server.shutdownNow();
    }
  }

  private RogueExploreScoreFixtureInspector() {}
}
