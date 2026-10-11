package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import dev.morphia.Morphia;
import emu.lunarcore.LunarCore;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.util.Position;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.h2.mvstore.MVStore;

/** Two independent Player reads from an owned, normally stopped, physically read-only H2 file. */
public final class WolfBroAmmoFixtureInspector {
  private static void require(boolean ok, String why) { if (!ok) throw new IllegalStateException(why); }
  private static String sha(Path path) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }
  private static void ammo(JsonObject actual, JsonObject expected, int uid, String uuid, String source) {
    require(actual != null && actual.get("ownerUid").getAsInt() == uid
        && actual.get("uuid").getAsString().equals(uuid)
        && actual.get("sourceSignature").getAsString().equals(source)
        && actual.get("granted").getAsBoolean()
        && actual.get("activated").getAsBoolean() == expected.get("activated").getAsBoolean()
        && actual.get("count").getAsInt() == expected.get("ammo").getAsInt(), "Restored own ammo receipt differs from the actual wire state");
    require(actual.getAsJsonObject("bullets").size() == 0 && expected.getAsJsonArray("bullets").isEmpty(), "Consumed real drop reappeared in the stored ledger");
    var defeated = actual.getAsJsonArray("defeated");
    require(defeated.size() == 1 && defeated.get(0).getAsString().equals("22:200001"), "The committed original NPC defeat was lost or invented");
  }
  public static void main(String[] args) throws Exception {
    var root = Path.of("").toRealPath();
    var directory = Path.of(args[0]).toRealPath();
    var input = Path.of(args[1]).toRealPath();
    require(directory.startsWith(root.resolve("build/smoke"))
        && directory.getFileName().toString().startsWith("wolfbro-ammo-native-") && input.startsWith(directory), "Unowned stopped ammo fixture");
    var expected = JsonParser.parseString(Files.readString(input)).getAsJsonObject();
    int uid = expected.get("uid").getAsInt();
    String mode = expected.get("mode").getAsString();
    require(uid == 10001 && Set.of("sequence", "resume", "restored").contains(mode), "Wrong owned ammo inspection phase");
    var marker = JsonParser.parseString(Files.readString(directory.resolve("resources-fixture/resource-manifest.json"))).getAsJsonObject();
    require(marker.get("testFixture").getAsBoolean() && marker.get("wolfBroAmmoFixture").getAsBoolean(), "Missing owned ammo fixture marker");
    LunarCore.getConfig().resourceDir = directory.resolve("resources-fixture").toString();
    WolfBroCatalog.load(directory.resolve("resources-fixture"));
    var level = WolfBroCatalog.level(11);
    require(level != null && level.PlaneID() == 20321 && level.FloorID() == 20321001, "Original Level11 source is missing");
    var rulesClass = Class.forName("org.hkrpg.server.gameplay.WolfBroAmmoRules");
    var reader = rulesClass.getDeclaredMethod("read", WolfBroCatalog.Level.class); reader.setAccessible(true);
    var rules = reader.invoke(null, level);
    require(rules != null, "Frozen original ammo source rules did not load");
    var signature = rulesClass.getDeclaredField("signature"); signature.setAccessible(true);
    String source = (String) signature.get(rules);
    require(source.matches("[0-9a-f]{64}"), "Invalid original ammo source identity");
    if (expected.has("ammoSourceSignature")) require(source.equals(expected.get("ammoSourceSignature").getAsString()), "Original ammo source identity changed across normal restart");
    var file = directory.resolve("data/database.mv");
    var config = directory.resolve("config.json");
    require(Files.isRegularFile(file), "Owned normally stopped H2 file is missing");
    String dbSha = sha(file), configSha = sha(config);
    var mv = new MVStore.Builder().fileName(file.toString()).readOnly().open();
    var server = new MongoServer(new H2Backend(mv));
    var address = server.bind();
    JsonObject state;
    try (var client = MongoClients.create("mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var collection = client.getDatabase("hkrpg").getCollection("players");
      var filter = Filters.eq("_id", uid);
      require(collection.countDocuments() == 1 && collection.countDocuments(filter) == 1, "Not exactly one owned ammo Player");
      var before = Objects.requireNonNull(collection.find(filter).first());
      var store = Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, Position.class);
      var first = store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first();
      var second = store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first();
      require(first != null && second != null && first != second, "Two independent actual Player restorations failed");
      var gson = new Gson();
      var firstGame = gson.toJsonTree(first.getGameplay()).getAsJsonObject();
      require(firstGame.equals(gson.toJsonTree(second.getGameplay())) && first.getHcoin() == second.getHcoin(), "Independent persisted ammo restores differ");
      require(first.getUid() == uid && first.getHcoin() == 0 && first.getPlaneId() == 20321
          && first.getFloorId() == 20321001 && first.getEntryId() == 2032101, "Saved source scene, UID or currency differs");
      state = firstGame.getAsJsonObject("wolfBro");
      require(state.get("activeId").getAsInt() == 11 && state.getAsJsonObject("runs").size() == 1, "Original active run was lost or extra runs appeared");
      var run = state.getAsJsonObject("runs").getAsJsonObject("11");
      var wire = expected.getAsJsonObject("game");
      String uuid = wire.get("uuid").getAsString();
      require(UUID.fromString(uuid).toString().equals(uuid) && run.get("id").getAsInt() == 11
          && run.get("uuid").getAsString().equals(uuid) && run.get("active").getAsBoolean()
          && run.get("sourceSignature").getAsString().equals(level.signature())
          && run.get("archiveControlState").getAsInt() == 2, "Stored source run identity/checkpoint differs from real requests");
      ammo(run.getAsJsonObject("ammo"), wire, uid, uuid, source);
      ammo(run.getAsJsonObject("archiveAmmo"), wire.getAsJsonObject("archive"), uid, uuid, source);
      require(run.getAsJsonObject("archive").equals(wire.getAsJsonObject("archive").getAsJsonObject("motion")), "Stored signed motion differs from actual archive response");
      require(gson.toJsonTree(first.getPos()).equals(wire.getAsJsonObject("archive").getAsJsonObject("motion").get("pos"))
          && gson.toJsonTree(first.getRot()).equals(wire.getAsJsonObject("archive").getAsJsonObject("motion").get("rot")), "Actual restored Player coordinates did not persist");
      for (int id : List.of(1031501, 103150120)) {
        var record = (id == 1031501 ? first.getGameplay().getMainMissions() : first.getGameplay().getSubMissions()).get(id);
        require(record != null && record.getQuestId() == id && record.getPhase() == QuestState.Phase.CLOSED
            && record.getProgress() == 1 && record.getFinishTime() > 0, "Explicit original prerequisite history did not persist");
      }
      require(first.getGameplay().getStoryValues().get("group-state:20321001:21") == 2, "Saved small control state changed");
      require(before.equals(collection.find(filter).first()) && collection.countDocuments() == 1, "Inspector mutated the Player collection");
      Files.writeString(directory.resolve("saved-player-" + mode + ".json"), before.toJson(), StandardOpenOption.CREATE_NEW);
    } finally { server.shutdownNow(); if (!mv.isClosed()) mv.close(); }
    require(dbSha.equals(sha(file)) && configSha.equals(sha(config)), "Read-only inspection changed H2/config bytes");
    System.out.println(new Gson().toJson(Map.of("passed", true, "uid", uid, "mode", mode,
        "wolfBro", state, "ammoSourceSignature", source, "independentMorphiaReads", 2,
        "changedPlayers", 0, "databaseSHA256", dbSha, "databaseFileBytesUnchanged", true,
        "mvStoreReadOnly", true)));
  }
}
