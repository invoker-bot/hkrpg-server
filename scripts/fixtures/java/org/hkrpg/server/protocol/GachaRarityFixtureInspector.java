package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import dev.morphia.Morphia;
import dev.morphia.mapping.MapperOptions;
import emu.lunarcore.game.player.Player;
import java.nio.file.*;
import java.util.*;

/** Only a normally stopped, explicitly marked disposable smoke database is accepted. */
public final class GachaRarityFixtureInspector {
  static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
  public static void main(String[] args) throws Exception {
    Path root = Path.of("").toRealPath(), directory = Path.of(args[1]).toRealPath();
    require(directory.startsWith(root.resolve("build/smoke")) && directory.getFileName().toString().startsWith("gacha-rarity-"), "Unowned smoke directory");
    var marker = JsonParser.parseString(Files.readString(directory.resolve("rarity-fixture.json"))).getAsJsonObject();
    require(marker.get("testFixture").getAsBoolean(), "Missing disposable fixture marker");
    int uid = marker.get("uid").getAsInt();
    var server = new MongoServer(new H2Backend(directory.resolve("data/database.mv").toString()));
    server.bind("127.0.0.1", 0);
    try (var client = MongoClients.create("mongodb://127.0.0.1:"+server.getLocalAddress().getPort()+"/?directConnection=true")) {
      var db = client.getDatabase("hkrpg"); var players = db.getCollection("players");
      require(players.countDocuments() == 1 && db.getCollection("accounts").countDocuments() == 1, "Expected one disposable account/player");
      var filter = Filters.eq("_id", uid);
      require(players.countDocuments(filter) == 1, "Wrong owned player");
      if (args[0].equals("seed")) {
        players.updateOne(filter, Updates.combine(Updates.set("gachaInfo.standardBanner.pity5",89),
            Updates.set("gachaInfo.standardBanner.pity4",9), Updates.set("gachaInfo.standardBanner.failedFeaturedItemPulls",1)));
      } else if (args[0].equals("seedTrial")) {
        // This gate belongs only to the owned test H2; no operator account is touched.
        players.updateOne(filter, Updates.combine(
            Updates.set("gameplay.mainMissions.1000510",
                new org.bson.Document("questId", 1000510).append("progress", 1)
                    .append("finishTime", 123L).append("phase", "CLOSED")),
            Updates.unset("gameplay.missionTrials.party")));
      } else require(args[0].equals("inspect"), "Unknown fixture action");
      var codecs = org.bson.codecs.configuration.CodecRegistries.fromCodecs(
          new emu.lunarcore.database.codecs.IntSetCodec(),new emu.lunarcore.database.codecs.IntListCodec(),new emu.lunarcore.database.codecs.Int2IntMapCodec());
      var store = Morphia.createDatastore(client,"hkrpg",MapperOptions.builder().storeEmpties(true).storeNulls(false).codecProvider(codecs).build());
      store.getMapper().map(Player.class);
      var first = store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first();
      var second = store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first();
      var gson = new Gson();
      require(first != null && second != null && first != second && gson.toJson(first.getGachaInfo()).equals(gson.toJson(second.getGachaInfo())), "Independent Player restorations disagree");
      var history = first.getGachaInfo(); var standard = history.getStandardBanner();
      var ticket = db.getCollection("items").find(Filters.and(Filters.eq("ownerUid",uid),Filters.eq("itemId",101))).first();
      var result = new LinkedHashMap<String,Object>();
      result.put("passed",true); result.put("uid",uid); result.put("actualPlayerReads",2);
      result.put("pity5",standard.getPity5()); result.put("pity4",standard.getPity4());
      result.put("failedFeatured",standard.getFailedFeaturedItemPulls()); result.put("ceiling",history.getCeilingNum());
      result.put("tickets",ticket == null ? 0 : ticket.getInteger("count"));
      result.put("newbieDraws", history.getNewbieDrawCount()); result.put("newbieFive", history.isNewbieFiveStarObtained());
      result.put("missionTrialInside", first.getGameplay().getMissionTrials().getParty() != null);
      result.put("gender", first.getGender().name());
      result.put("worldLevel", first.getWorldLevel());
      result.put("trialInside", first.getGameplay().getTrialRun() != null);
      result.put("trialCompletions", first.getGameplay().getTrialCompletions());
      result.put("trialReceipts", first.getGameplay().getRewards().keySet().stream()
          .filter(key -> key.startsWith("trial:")).sorted().toList());
      result.put("currentMissionRaid", first.getGameplay().getCurrentMissionRaid());
      result.put("storyId", first.getGameplay().getStoryLines().getCurrentId());
      result.put("storyTransition", first.getGameplay().getStoryLines().isTransitionPending());
      System.out.println("RARITY_FIXTURE="+gson.toJson(result));
    } finally { server.shutdownNow(); }
  }
}
