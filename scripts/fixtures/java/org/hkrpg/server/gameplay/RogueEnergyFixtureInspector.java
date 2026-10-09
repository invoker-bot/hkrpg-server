package org.hkrpg.server.gameplay;

import static dev.morphia.query.filters.Filters.eq;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import dev.morphia.mapping.MapperOptions;
import emu.lunarcore.database.codecs.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;
import org.bson.codecs.configuration.CodecRegistries;

/** Two read-only actual Player checks; only setup explicitly seeds owned stopped fixture H2. */
public final class RogueEnergyFixtureInspector {
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
        || !marker.get("rogueEnergyFixture").getAsBoolean())
      throw new IllegalArgumentException("Missing fixture marker");
    int uid = Integer.parseInt(args[1]);
    String mode = args[2];
    if (uid <= 0 || !Set.of("setup", "quit", "missing", "win", "lose", "recover").contains(mode))
      throw new IllegalArgumentException("Invalid arguments");
    var file = directory.resolve("data/database.mv").toRealPath();
    if (!file.startsWith(directory))
      throw new IllegalArgumentException("Fixture database outside owned directory");
    var server = new MongoServer(new H2Backend(file.toString()));
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var options =
          MapperOptions.builder()
              .storeEmpties(true)
              .storeNulls(false)
              .codecProvider(
                  CodecRegistries.fromCodecs(
                      new IntSetCodec(), new IntListCodec(), new Int2IntMapCodec()))
              .build();
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg", options);
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, emu.lunarcore.util.Position.class);
      if (store.find(Player.class).count() != 1)
        throw new IllegalStateException("Not unique actual Player");
      var players = store.getDatabase().getCollection("players");
      var query = new org.bson.Document("_id", uid);
      String original = Objects.requireNonNull(players.find(query).first()).toJson();
      String runId = "";
      int expected =
          mode.equals("setup")
              ? -1
              : mode.equals("quit") || mode.equals("missing")
                  ? 1750
                  : mode.equals("win") ? 3333 : 0;
      var phase =
          Set.of("lose", "recover").contains(mode)
              ? GameplayState.RogueRun.Phase.FAILED
              : GameplayState.RogueRun.Phase.EXPLORING;
      for (int read = 0; read < 2; read++) {
        var player =
            Objects.requireNonNull(store.find(Player.class).filter(eq("_id", uid)).first());
        var run = player.getGameplay().getRogue();
        var plan = run.getResonance();
        if (run.getSelectedAeon() != 7
            || run.getAreaId() != 100
            || run.getRoomId() != 100
            || run.isInside()
            || run.getPhase() != phase
            || !run.getRoster().equals(List.of(8001))
            || !RogueLotteryState.uuid(run.getRunId())
            || plan == null
            || !plan.valid(7)
            || plan.getBasic() != 612620
            || plan.getEvent() != 41007
            || run.getResonanceSp() != expected)
          throw new IllegalStateException("Actual saved energy or exploration differs: " + mode);
        if (!mode.equals("setup") && !Objects.equals(run.getBlessings().get(612620), 1))
          throw new IllegalStateException("Missing declared seeded resonance");
        if (read == 0) runId = run.getRunId();
        else if (!runId.equals(run.getRunId()))
          throw new IllegalStateException("Run changed during reads");
        if (!original.equals(Objects.requireNonNull(players.find(query).first()).toJson()))
          throw new IllegalStateException("Reading changed actual document");
      }
      if (mode.equals("setup")) {
        var player =
            Objects.requireNonNull(store.find(Player.class).filter(eq("_id", uid)).first());
        var run = player.getGameplay().getRogue();
        if (run.pending() || !run.getBlessings().isEmpty())
          throw new IllegalStateException("Unexpected pre-seed blessing/menu");
        run.getBlessings().put(612620, 1);
        run.activateResonance(612620);
        run.saveResonanceSp(2500);
        store.save(player);
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
                      "runId",
                      runId,
                      "energyRead",
                      expected,
                      "phase",
                      phase.name(),
                      "actualPlayerReads",
                      2,
                      "changedRecords",
                      mode.equals("setup") ? 1 : 0,
                      "fixtureEnergySeed",
                      mode.equals("setup") ? 2500 : -1)));
    } finally {
      server.shutdownNow();
    }
  }
}
