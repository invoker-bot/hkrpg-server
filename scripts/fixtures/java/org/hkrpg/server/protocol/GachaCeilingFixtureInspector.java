package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import dev.morphia.Morphia;
import dev.morphia.mapping.MapperOptions;
import emu.lunarcore.game.gacha.PlayerGachaInfo;
import emu.lunarcore.game.player.Player;
import java.nio.file.*;
import java.util.*;

/** Access only a stopped H2 database bearing this smoke test's disposable-account marker. */
public final class GachaCeilingFixtureInspector {
  static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
  public static void main(String[] args) throws Exception {
    Path root = Path.of("").toRealPath(), directory = Path.of(args[1]).toRealPath();
    require(directory.startsWith(root.resolve("build/smoke"))
        && directory.getFileName().toString().startsWith("gacha-ceiling-"), "Unowned smoke directory");
    var marker = JsonParser.parseString(Files.readString(directory.resolve("ceiling-fixture.json"))).getAsJsonObject();
    require(marker.get("testFixture").getAsBoolean(), "Missing disposable fixture marker");
    int uid = marker.get("uid").getAsInt();
    var server = new MongoServer(new H2Backend(directory.resolve("data/database.mv").toString()));
    server.bind("127.0.0.1", 0);
    try (var client = MongoClients.create("mongodb://127.0.0.1:" + server.getLocalAddress().getPort() + "/?directConnection=true")) {
      var db = client.getDatabase("hkrpg"); var players = db.getCollection("players");
      require(players.countDocuments() == 1 && db.getCollection("accounts").countDocuments() == 1,
          "Expected one disposable account/player");
      var filter = Filters.eq("_id", uid);
      require(players.countDocuments(filter) == 1, "Wrong owned player");
      if (args[0].equals("seed")) {
        var before = players.find(filter).first();
        require(before != null && before.get("gachaInfo", org.bson.Document.class).getInteger("ceilingNum", 0) == 0,
            "Unexpected existing selector progress");
        players.updateOne(filter, Updates.set("gachaInfo.ceilingNum", 299));
      } else require(args[0].equals("inspect"), "Unknown fixture action");
      var codecs = org.bson.codecs.configuration.CodecRegistries.fromCodecs(
          new emu.lunarcore.database.codecs.IntSetCodec(), new emu.lunarcore.database.codecs.IntListCodec(),
          new emu.lunarcore.database.codecs.Int2IntMapCodec());
      var store = Morphia.createDatastore(client, "hkrpg", MapperOptions.builder().storeEmpties(true)
          .storeNulls(false).codecProvider(codecs).build());
      store.getMapper().map(Player.class);
      var first = store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first();
      var second = store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first();
      var gson = new Gson();
      require(first != null && second != null && first != second
          && gson.toJson(first.getGachaInfo()).equals(gson.toJson(second.getGachaInfo()))
          && gson.toJson(first.getGameplay().getRewards()).equals(gson.toJson(second.getGameplay().getRewards())),
          "Independent Player restorations disagree");
      var receipt = first.getGameplay().getRewards().get(PlayerGachaInfo.CEILING_REWARD_KEY);
      var rank = db.getCollection("items").find(Filters.and(Filters.eq("ownerUid", uid), Filters.eq("itemId", 11003))).first();
      long himeko = db.getCollection("avatars").countDocuments(Filters.and(Filters.eq("ownerUid", uid), Filters.eq("avatarId", 1003)));
      System.out.println("CEILING_FIXTURE=" + gson.toJson(Map.of("passed", true, "uid", uid,
          "actualPlayerReads", 2, "ceiling", first.getGachaInfo().getCeilingNum(),
          "claimed", first.getGachaInfo().isCeilingClaimed(), "receipt", receipt == null ? "none" : receipt.isGranted() ? "granted" : "pending",
          "himeko", himeko, "himekoEidolons", rank == null ? 0 : rank.getInteger("count", 0))));
    } finally { server.shutdownNow(); }
  }
}
