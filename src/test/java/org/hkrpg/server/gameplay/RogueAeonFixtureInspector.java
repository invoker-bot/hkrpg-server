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

/** Two actual Player reads in an owned stopped H2 fixture, without updating any document. */
public final class RogueAeonFixtureInspector {
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
    if (!marker.get("testFixture").getAsBoolean() || !marker.get("rogueAeonFixture").getAsBoolean())
      throw new IllegalArgumentException("Missing fixture marker");
    int uid = Integer.parseInt(args[1]), expected = Integer.parseInt(args[2]);
    if (uid <= 0 || !Set.of(1, 7).contains(expected))
      throw new IllegalArgumentException("Invalid arguments");
    var file = directory.resolve("data/database.mv");
    if (!Files.isRegularFile(file)) throw new IllegalStateException("Missing fixture database");
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
      for (int read = 0; read < 2; read++) {
        var player =
            Objects.requireNonNull(store.find(Player.class).filter(eq("_id", uid)).first());
        var run = player.getGameplay().getRogue();
        if (run.getSelectedAeon() != expected
            || run.getAreaId() != 100
            || run.isInside()
            || run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING
            || !run.getRoster().equals(List.of(8001))
            || !RogueLotteryState.uuid(run.getRunId()))
          throw new IllegalStateException("Actual saved selection differs");
        if (read == 0) runId = run.getRunId();
        else if (!runId.equals(run.getRunId()))
          throw new IllegalStateException("Run changed during reads");
        if (!original.equals(Objects.requireNonNull(players.find(query).first()).toJson()))
          throw new IllegalStateException("Reading changed actual document");
      }
      System.out.println(
          new Gson()
              .toJson(
                  Map.of(
                      "passed",
                      true,
                      "uid",
                      uid,
                      "selectedAeon",
                      expected,
                      "runId",
                      runId,
                      "actualPlayerReads",
                      2,
                      "changedRecords",
                      0)));
    } finally {
      server.shutdownNow();
    }
  }
}
