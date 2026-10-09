package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.bson.Document;

/** Explicit isolated-fixture gate seeding and independent H2 reads; absent from the server jar. */
public final class LocalLegendFixtureEditor {
  public static void main(String[] args) throws Exception {
    var root = Path.of("").toRealPath();
    var directory = Path.of(args[0]).toRealPath();
    if (!directory.startsWith(root.resolve("build/smoke"))
        || directory.equals(root.resolve("build/smoke")))
      throw new IllegalArgumentException("Not a smoke fixture directory");
    var marker =
        JsonParser.parseString(
                Files.readString(directory.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    if (!marker.get("testFixture").getAsBoolean()
        || !marker.get("localLegendFixture").getAsBoolean())
      throw new IllegalArgumentException("Local Legend fixture marker missing");
    int uid = Integer.parseInt(args[1]);
    if (uid <= 0) throw new IllegalArgumentException("Invalid fixture UID");
    var path = directory.resolve("data/database.mv").normalize();
    if (!path.startsWith(directory) || !Files.isRegularFile(path))
      throw new IllegalArgumentException("Existing isolated H2 file missing");
    LocalLegendCatalog.load(directory.resolve("resources-fixture"));
    loadAchievementDefinitions(directory.resolve("resources-fixture"));
    var backend = new H2Backend(path.toString());
    var server = new MongoServer(backend);
    server.bind("127.0.0.1", 0);
    try (var client = MongoClients.create(server.getConnectionString())) {
      var database = client.getDatabase("hkrpg");
      var players = database.getCollection("players");
      var player = players.find(Filters.eq("_id", uid)).first();
      if (player == null) throw new IllegalStateException("Fixture player missing");
      var gameplay = player.get("gameplay", Document.class);
      var history = gameplay.get("localLegendProgress", Document.class);
      long changed = 0;
      if (args[2].equals("seed")) {
        if (!history.get("entries", Document.class).isEmpty()
            || gameplay.get("mainMissions", Document.class).containsKey("1040416"))
          throw new IllegalStateException("Not a fresh locked fixture");
        var quest =
            new Document("questId", 1040416)
                .append("progress", 1)
                .append("finishTime", Instant.now().getEpochSecond())
                .append("phase", "CLOSED");
        changed =
            players
                .updateOne(
                    Filters.eq("_id", uid),
                    Updates.combine(
                        Updates.set("level", 21),
                        Updates.set("worldLevel", 1),
                        Updates.set("gameplay.mainMissions.1040416", quest)))
                .getModifiedCount();
        if (changed != 1)
          throw new IllegalStateException("Fixture update did not match exactly one player");
        backend.commit();
        backend.getMvStore().sync();
      } else if (!args[2].equals("inspect"))
        throw new IllegalArgumentException("Unknown fixture edit");
      player = players.find(Filters.eq("_id", uid)).first();
      var result = new LinkedHashMap<String, Object>();
      result.put("passed", true);
      result.put("uid", uid);
      result.put("mode", args[2]);
      result.put("changedPlayers", changed);
      result.put("level", player.getInteger("level"));
      result.put("worldLevel", player.getInteger("worldLevel"));
      result.put("hcoin", player.getInteger("hcoin"));
      result.put("scoin", player.getInteger("scoin"));
      result.put(
          "rawLocalLegendProgress",
          player.get("gameplay", Document.class).get("localLegendProgress"));
      var states = player.get("questStates", Document.class);
      var selected = new TreeMap<String, Document>();
      for (int id : List.of(6072101, 6072102, 6072113, 6072114))
        if (states.containsKey(Integer.toString(id)))
          selected.put(Integer.toString(id), states.get(Integer.toString(id), Document.class));
      result.put("targetQuests", selected);
      var stocks = new TreeMap<Integer, Integer>();
      for (var item : database.getCollection("items").find(Filters.eq("ownerUid", uid))) {
        int id = item.getInteger("itemId");
        if (id == 213 || id == 232 || id == 110222)
          stocks.merge(id, item.getInteger("count"), Integer::sum);
      }
      result.put("actualStoredMaterials", stocks);
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              emu.lunarcore.game.player.Player.class,
              emu.lunarcore.game.quest.QuestState.class,
              emu.lunarcore.util.Position.class);
      var restored =
          store
              .find(emu.lunarcore.game.player.Player.class)
              .filter(dev.morphia.query.filters.Filters.eq("_id", uid))
              .first();
      var progress = restored.getGameplay().getLocalLegendProgress();
      if (!progress.valid())
        throw new IllegalStateException("Invalid restored source-bound progress");
      var masks = new TreeMap<String, Integer>();
      for (var level : LocalLegendCatalog.levels())
        masks.put(level.group() + ":" + level.difficulty(), progress.mask(level));
      result.put("restoredMasks", masks);
      var receipts = new TreeMap<Integer, Integer>();
      for (var receipt : progress.achievementEntries())
        receipts.put(receipt.quest(), receipt.achievement());
      result.put("restoredAchievementReceipts", receipts);
      var achievementQuests = new TreeMap<Integer, String>();
      for (var rule : LocalLegendAchievementRules.rules()) {
        var state = LocalLegendQuests.state(restored, rule.quest());
        if (state == null) throw new IllegalStateException("Invalid restored achievement quest");
        achievementQuests.put(rule.quest(), state.getPhase().name());
      }
      result.put("restoredAchievementQuestPhases", achievementQuests);
      result.put("restoredMainMissionGate", MissionStateRecords.mainCompleted(restored, 1040416));
      if (args[2].equals("seed") && !MissionStateRecords.mainCompleted(restored, 1040416))
        throw new IllegalStateException("Seeded gate failed Morphia decoding");
      result.put("operatorDataModified", false);
      result.put("matchingPlayerCount", players.countDocuments(Filters.eq("_id", uid)));
      System.out.println(new Gson().toJson(result));
    } finally {
      server.shutdownNow();
    }
  }

  private static void loadAchievementDefinitions(Path root) throws Exception {
    var ids = new HashSet<Integer>();
    var rewards = new HashSet<Integer>();
    for (var rule : LocalLegendAchievementRules.rules()) {
      ids.add(rule.quest());
      rewards.add(rule.reward());
    }
    for (var row : LocalLegendCatalog.rows(root, "QuestData", 16384))
      if (ids.contains(row.get("QuestID").getAsInt()))
        GameData.getQuestExcelMap().add(ResourceLoader.getGson().fromJson(row, QuestExcel.class));
    for (var row : LocalLegendCatalog.rows(root, "FinishWay", 16384))
      if (ids.contains(row.get("ID").getAsInt()))
        GameData.getFinishWayExcelMap()
            .add(ResourceLoader.getGson().fromJson(row, FinishWayExcel.class));
    for (var row : LocalLegendCatalog.rows(root, "RewardData", 16384))
      if (rewards.contains(row.get("RewardID").getAsInt())) {
        var reward = ResourceLoader.getGson().fromJson(row, RewardExcel.class);
        reward.onLoad();
        GameData.getRewardExcelMap().add(reward);
      }
  }

  private LocalLegendFixtureEditor() {}
}
