package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import dev.morphia.Morphia;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.lang.reflect.Modifier;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/** Read only the normally stopped owned H2 database; no MP, callback or receipt seeding. */
public final class MazeCastSequenceFixtureInspector {
  private static void require(boolean ok, String why) { if (!ok) throw new IllegalStateException(why); }
  public static void main(String[] args) throws Exception {
    var root = Path.of("").toRealPath();
    var directory = Path.of(args[0]).toRealPath();
    require(directory.startsWith(root.resolve("build/smoke")) && !directory.equals(root.resolve("build/smoke")), "Unowned cast smoke directory");
    String mode = args[1];
    require(Set.of("sequence", "new-session", "restored").contains(mode), "Unknown H2 inspection phase");
    int uid = Integer.parseInt(args[2]), mp = Integer.parseInt(args[3]);
    require(uid == 10001 && mp >= 0 && mp <= 5, "Only one isolated fixture player is inspectable");
    var marker = JsonParser.parseString(Files.readString(directory.resolve("resources-fixture/resource-manifest.json"))).getAsJsonObject();
    require(marker.get("testFixture").getAsBoolean() && marker.get("mazeCastSequenceFixture").getAsBoolean(), "Missing owned cast fixture marker");
    var config = Files.readAllBytes(directory.resolve("config.json"));
    var file = directory.resolve("data/database.mv");
    require(Files.isRegularFile(file), "Missing normally stopped owned H2 data");
    var backend = new H2Backend(file.toString());
    var server = new MongoServer(backend);
    var address = server.bind();
    try (var client = MongoClients.create("mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var db = client.getDatabase("hkrpg");
      var collection = db.getCollection("players");
      var filter = Filters.eq("_id", uid);
      require(collection.countDocuments() == 1 && collection.countDocuments(filter) == 1, "Not exactly one isolated cast player");
      var before = Objects.requireNonNull(collection.find(filter).first());
      require(before.get("lineupManager", Document.class).getInteger("mp") == mp, "Saved MP differs from actual KCP balance");
      require(!before.get("gameplay", Document.class).containsKey("mazeCasts"), "Native UseIDs or prepaid receipts were incorrectly persisted");
      var store = Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var player = store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first();
      require(player != null, "Actual Morphia player restoration failed");
      var transientField = player.getGameplay().getClass().getDeclaredField("mazeCasts");
      require(Modifier.isTransient(transientField.getModifiers()) && transientField.isAnnotationPresent(dev.morphia.annotations.Transient.class), "UseID field lacks Java and Morphia transient boundaries");
      transientField.setAccessible(true);
      var casts = transientField.get(player.getGameplay());
      if (casts != null) {
        var ledger = casts.getClass().getDeclaredField("casts");
        ledger.setAccessible(true);
        require(((Map<?, ?>) ledger.get(casts)).isEmpty(), "Restored transient UseID ledger is nonempty");
      }
      require(before.equals(collection.find(filter).first()) && Arrays.equals(config, Files.readAllBytes(directory.resolve("config.json"))), "Read-only H2 inspection mutated player or config");
      Files.writeString(directory.resolve("saved-player-" + mode + ".json"), before.toJson(), StandardOpenOption.CREATE_NEW);
      System.out.println(new Gson().toJson(Map.of("passed", true, "uid", uid, "mode", mode,
          "mp", mp, "changedPlayers", 0, "nativeUseIDLedgerPersisted", false,
          "restoredUseIDLedgerEmpty", true, "receiptPrewritten", false)));
    } finally { server.shutdownNow(); }
  }
}
