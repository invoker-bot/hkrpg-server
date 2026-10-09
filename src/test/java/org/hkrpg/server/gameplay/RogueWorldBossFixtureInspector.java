package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;

/** Only reads the marked matrix probe's normally stopped real H2 players. */
public final class RogueWorldBossFixtureInspector {
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
        || !marker.get("rogueWorldBossFixture").getAsBoolean())
      throw new IllegalArgumentException("Missing fixture marker");
    if (!Set.of("active", "finished").contains(args[1]))
      throw new IllegalArgumentException("Invalid mode");
    boolean done = args[1].equals("finished");
    var expected =
        JsonParser.parseString(Files.readString(directory.resolve("expected-bosses.json")))
            .getAsJsonArray();
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
      int count = marker.getAsJsonArray("syntheticUnlockDisabledForAreas").size();
      if (!Set.of(9, 32).contains(count)
          || expected.size() != count
          || store.find(Player.class).count() != count)
        throw new IllegalStateException("Wrong fixture player count");
      var restored = new ArrayList<Map<String, Object>>();
      for (var raw : expected) {
        var e = raw.getAsJsonObject();
        int uid = e.get("uid").getAsInt(), area = e.get("area").getAsInt();
        var player =
            Objects.requireNonNull(
                store
                    .find(Player.class)
                    .filter(dev.morphia.query.filters.Filters.eq("_id", uid))
                    .first());
        var saved = player.getGameplay();
        var run = saved.getRogue();
        var plan = run.getBossEncounter();
        var choices = run.getMonsterSelections();
        var choice = choices.get("1:222713:81222:81222001:39");
        if (player.getHcoin() != 0
            || player.getScoin() != e.get("credits").getAsInt()
            || run.getAreaId() != area
            || run.getMapId() != e.get("map").getAsInt()
            || run.getSiteId() != 1
            || run.getRoomId() != 222713
            || !run.isBossEncounterPlanned()
            || plan == null
            || plan.getArea() != area
            || plan.getMap() != run.getMapId()
            || plan.getContent() != e.get("content").getAsInt()
            || !plan.isLevelPlanned()
            || plan.getLevel() != e.get("level").getAsInt()
            || !new Gson().toJsonTree(plan.getLevels()).equals(e.get("levels"))
            || plan.getSignature() == null
            || plan.getSignature().length() != 64
            || choices.size() != 1
            || choice == null
            || choice.getContent() != plan.getContent()
            || choice.getNpc() != e.get("npc").getAsInt()
            || choice.getStage() != e.get("stage").getAsInt()
            || run.getPhase()
                != (done
                    ? GameplayState.RogueRun.Phase.ABANDONED
                    : GameplayState.RogueRun.Phase.EXPLORING)
            || run.isInside() == done
            || run.isDismissed() != done
            || !run.getCleared().equals(done ? Set.of(1) : Set.of())
            || saved.getRogueResults().size() != (done ? 1 : 0)
            || saved.getCycleWeeks().size() != (done ? 1 : 0)
            || saved.getRewards().containsKey("rogue-first:" + area))
          throw new IllegalStateException(
              "Actual world, source choice or wallet did not restore for " + area);
        if (done) {
          var result = saved.getRogueResults().values().iterator().next();
          var week = saved.getCycleWeeks().values().iterator().next();
          if (result.getOutcome() != GameplayState.RogueRun.Phase.ABANDONED
              || result.getPassedRooms() != 1
              || result.getAreaId() != area
              || result.getCyclePoints() != e.get("score").getAsInt()
              || result.getRoomScore() != result.getCyclePoints()
              || !result.isCycleSettled()
              || week.getScore() != result.getCyclePoints())
            throw new IllegalStateException("Wrong restored actual room settlement");
        }
        restored.add(
            Map.of(
                "uid",
                uid,
                "area",
                area,
                "bossPlan",
                plan,
                "sourceChoice",
                choice,
                "credits",
                player.getScoin(),
                "results",
                saved.getRogueResults().size(),
                "runId",
                run.getRunId()));
      }
      System.out.println(
          new Gson()
              .toJson(
                  Map.of(
                      "passed", true, "mode", args[1], "players", restored, "changedPlayers", 0)));
    } finally {
      server.shutdownNow();
    }
  }

  private RogueWorldBossFixtureInspector() {}
}
