package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;

/** Read-only actual Player inspection after normal stop of this explicitly marked smoke fixture. */
public final class RogueLotteryFixtureInspector {
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
        || !marker.get("galacticLotteryFixture").getAsBoolean())
      throw new IllegalArgumentException("Missing fixture marker");
    int uid = Integer.parseInt(args[1]);
    String mode = args[2];
    if (uid <= 0 || !Set.of("buy", "roll", "resume").contains(mode))
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
      String original = Objects.requireNonNull(collection.find(query).first()).toJson();
      String frozen = null;
      GameplayState.RogueRun run = null;
      for (int read = 0; read < 2; read++) {
        var player =
            Objects.requireNonNull(
                store
                    .find(Player.class)
                    .filter(dev.morphia.query.filters.Filters.eq("_id", uid))
                    .first());
        run = player.getGameplay().getRogue();
        var item = Objects.requireNonNull(run.getMiracles().get(63));
        if (run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING
            || !run.isInside()
            || run.pending()
            || run.getRoomId() != 1000012
            || !run.getRoster().equals(List.of(8001))
            || !item.getEffectParameters().equals(List.of(.99))
            || item.getLottery() == null
            || !item.getLottery().valid(item))
          throw new IllegalStateException("Invalid saved actual lottery context");
        if (mode.equals("buy")) {
          if (run.getMiracles().size() != 1
              || item.getUseCount() != 0
              || item.destructionCount() != 0
              || run.getVitals().get(8001).getHp() != 10000)
            throw new IllegalStateException("Initial purchase was not preserved");
        } else {
          var counts =
              new EnumMap<RogueLotteryState.Outcome, Integer>(RogueLotteryState.Outcome.class);
          for (var roll : item.getLottery().getRolls().values())
            counts.merge(roll.getOutcome(), 1, Integer::sum);
          if (!counts.equals(
                  Map.of(
                      RogueLotteryState.Outcome.NONE,
                      1,
                      RogueLotteryState.Outcome.GAIN,
                      1,
                      RogueLotteryState.Outcome.BREAK,
                      1))
              || run.getMiracles().size() != 2
              || item.getUseCount() != 1
              || item.destructionCount() != 3
              || !run.getDestroyedDestructibleProps().equals(item.getLottery().getRolls().keySet())
              || run.getVitals().get(8001).getHp() != 100)
            throw new IllegalStateException("Actual three outcomes or damage differ");
          for (var roll : item.getLottery().getRolls().values()) {
            if (roll.getOutcome() == RogueLotteryState.Outcome.GAIN) {
              var awarded = run.getMiracles().get(roll.getAwardedId());
              if (!Set.of(2, 12).contains(roll.getAwardedId())
                  || awarded == null
                  || !awarded.getAcquisitionId().equals(roll.getAwardedAcquisition()))
                throw new IllegalStateException("Actual award identity differs");
            }
            if (roll.getOutcome() == RogueLotteryState.Outcome.BREAK
                && (!roll.getBeforeHp().equals(Map.of(8001, 10000))
                    || !roll.getAfterHp().equals(Map.of(8001, 100))))
              throw new IllegalStateException("Actual saved damage differs");
          }
        }
        String now = new Gson().toJson(run);
        if (frozen != null && !frozen.equals(now))
          throw new IllegalStateException("Two reads differ");
        frozen = now;
        if (!original.equals(Objects.requireNonNull(collection.find(query).first()).toJson()))
          throw new IllegalStateException("Read changed saved player");
      }
      System.out.println(
          new Gson()
              .toJson(
                  Map.of(
                      "passed",
                      true,
                      "uid",
                      uid,
                      "mode",
                      mode,
                      "run",
                      run,
                      "actualPlayerReads",
                      2,
                      "changedPlayers",
                      0)));
    } finally {
      server.shutdownNow();
    }
  }

  private RogueLotteryFixtureInspector() {}
}
