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

/**
 * Two actual Player reads, then an explicit optional normal-blessing/energy fixture seed in owned
 * stopped H2 only.
 */
public final class RogueResonanceFixtureInspector {
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
        || !marker.get("rogueResonanceFixture").getAsBoolean())
      throw new IllegalArgumentException("Missing fixture marker");
    int uid = Integer.parseInt(args[1]), acquired = Integer.parseInt(args[2]);
    int seed = Integer.parseInt(args[3]);
    boolean missingMenu = args.length == 5 && args[4].equals("missing-menu");
    if (args.length > 5 || args.length == 5 && !missingMenu)
      throw new IllegalArgumentException("Unknown fixture mode");
    if (uid <= 0 || acquired < 0 || acquired > 4 || !Set.of(0, 3, 6, 10, 14).contains(seed))
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
        if (run.getSelectedAeon() != 7
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
      var player = Objects.requireNonNull(store.find(Player.class).filter(eq("_id", uid)).first());
      var run = player.getGameplay().getRogue();
      var plan = run.getResonance();
      if (plan == null || !plan.valid(7) || plan.getBasic() != 612620 || plan.getEvent() != 41007)
        throw new IllegalStateException("No frozen original resonance plan");
      int count =
          (run.getBlessings().containsKey(plan.getBasic()) ? 1 : 0)
              + (int)
                  plan.getEnhancements().stream().filter(run.getBlessings()::containsKey).count();
      if (count != acquired
          || run.pending()
          || acquired > 0 && run.getResonanceSp() != (acquired == 1 ? 10000 : 2500))
        throw new IllegalStateException("Saved earned resonance or energy differs");
      if (seed > 0) {
        var normals = plan.getNormals().stream().sorted().toList();
        for (int i = 0; i < (missingMenu ? seed : seed - 1); i++)
          run.getBlessings().put(normals.get(i), 1);
        if (!missingMenu) {
          int id = normals.get(seed - 1);
          run.offer(List.of(id), List.of(id), List.of(), Map.of(id, 1), 1, Map.of(id, 1));
        }
        if (acquired > 0) run.saveResonanceSp(2500);
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
                      "acquired",
                      acquired,
                      "runId",
                      runId,
                      "actualPlayerReads",
                      2,
                      "changedRecords",
                      seed > 0 ? 1 : 0,
                      "fixtureNormalThreshold",
                      seed,
                      "fixtureMissingEarnedMenu",
                      missingMenu && seed > 0,
                      "fixtureEnergySeeded",
                      seed > 0 && acquired > 0)));
    } finally {
      server.shutdownNow();
    }
  }
}
