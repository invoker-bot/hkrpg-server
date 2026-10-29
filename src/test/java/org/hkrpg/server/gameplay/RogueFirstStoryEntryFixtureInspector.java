package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;

/** Reads only the marked probe's normally stopped real H2 player. */
public final class RogueFirstStoryEntryFixtureInspector {
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
        || !marker.get("rogueFirstStoryTriggerFixture").getAsBoolean())
      throw new IllegalArgumentException("Missing fixture marker");
    int uid = Integer.parseInt(args[1]);
    boolean active = args[2].equals("active");
    if (uid <= 0 || !Set.of("pending", "active").contains(args[2]))
      throw new IllegalArgumentException("Invalid inspector arguments");
    var expected =
        JsonParser.parseString(Files.readString(directory.resolve("actors.json")))
            .getAsJsonObject();
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
      var player =
          Objects.requireNonNull(
              store
                  .find(Player.class)
                  .filter(dev.morphia.query.filters.Filters.eq("_id", uid))
                  .first());
      var saved = player.getGameplay();
      var run = saved.getRogue();
      if (player.getHcoin() != 0
          || player.getScoin() != 0
          || !saved.getRogueResults().isEmpty()
          || !saved.getCycleWeeks().isEmpty())
        throw new IllegalStateException("Entry unexpectedly granted settlement or currency");
      if (active) {
        if (run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING
            || !run.isInside()
            || run.getAreaId() != 100
            || run.getMapId() != 1
            || run.getSiteId() != 1
            || run.getRoomId() != 100
            || player.getEntryId() != 8000101
            || !run.getRooms()
                .equals(Map.of(1, 100))
            || !run.getRoster().equals(List.of(emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID))
            || run.getReturnTo() == null
            || !run.getCleared().isEmpty()
            || !run.getProcessedBattles().isEmpty()
            || run.pending()
            || run.getMonsterSelections().size() != 6)
          throw new IllegalStateException("Actual entry, team or map did not restore");
        for (var entry : run.getMonsterSelections().entrySet()) {
          var parts = entry.getKey().split(":");
          if (parts.length != 5 || !entry.getKey().startsWith("1:100:80001:80001001:"))
            throw new IllegalStateException("Wrong saved source group identity");
          var definition = expected.getAsJsonObject(parts[4]);
          var choice = entry.getValue();
          if (definition == null
              || choice.getContent() != definition.get("content").getAsInt()
              || !new Gson().toJsonTree(choice.getWeights()).equals(definition.get("weights")))
            throw new IllegalStateException("Original content weights did not restore");
          boolean found = false;
          for (var raw : definition.getAsJsonArray("choices")) {
            var candidate = raw.getAsJsonObject();
            if (choice.getMonster() == candidate.get("monster").getAsInt()
                && choice.getNpc() == candidate.get("npc").getAsInt()
                && choice.getStage() == candidate.get("stage").getAsInt()) found = true;
          }
          if (!found) throw new IllegalStateException("Unproven saved source actor");
        }
      } else if (run.getPhase() != GameplayState.RogueRun.Phase.IDLE
          || !run.getRooms().isEmpty()
          || !run.getMonsterSelections().isEmpty())
        throw new IllegalStateException("Pending player already has a run");
      if (active) {
        var entered = saved.getSubMissions().get(403000102);
        if (entered == null || entered.getQuestId() != 403000102
            || entered.getPhase() != QuestState.Phase.FINISHED || entered.getProgress() != 1)
          throw new IllegalStateException("Original enter-floor objective did not complete");
      }
      var output = new LinkedHashMap<String, Object>();
      output.put("passed", true);
      output.put("uid", uid);
      output.put("mode", args[2]);
      output.put("rooms", run.getRooms());
      output.put("enterMission", active ? saved.getSubMissions().get(403000102).getPhase().name() : "pending");
      output.put("sourceChoices", run.getMonsterSelections());
      output.put("runId", run.getRunId());
      output.put("changedPlayers", 0);
      System.out.println(new Gson().toJson(output));
    } finally {
      server.shutdownNow();
    }
  }

  private RogueFirstStoryEntryFixtureInspector() {}
}
