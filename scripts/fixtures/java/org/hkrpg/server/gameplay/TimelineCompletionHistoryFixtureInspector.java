package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import dev.morphia.Morphia;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.ActivityPanelExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/**
 * Read-only restored original network receipts; the dependent panel and expired window are
 * fixtures.
 */
public final class TimelineCompletionHistoryFixtureInspector {
  private static void require(boolean valid, String why) {
    if (!valid) throw new IllegalStateException(why);
  }

  public static void main(String[] args) throws Exception {
    var root = Path.of("").toRealPath();
    var dir = Path.of(args[0]).toRealPath();
    int uid = Integer.parseInt(args[1]);
    require(
        uid > 0
            && dir.startsWith(root.resolve("build/smoke"))
            && !dir.equals(root.resolve("build/smoke")),
        "Unowned fixture");
    var resources = dir.resolve("resources-fixture");
    var marker =
        JsonParser.parseString(Files.readString(resources.resolve("resource-manifest.json")))
            .getAsJsonObject();
    require(
        marker.get("testFixture").getAsBoolean()
            && marker.get("timelineActivityFixture").getAsBoolean(),
        "Missing source fixture marker");
    var config = Files.readAllBytes(dir.resolve("config.json"));
    var field = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
    field.setAccessible(true);
    field.set(LunarCore.getConfig(), resources.toString());
    ResourceLoader.loadResources();
    GameplayDefinitions.loadMissions(resources.resolve("Config/Level/Mission"));
    GameplayDefinitions.windows.clear();
    var rules = TimelineActivityRules.load();
    require(rules != null, "Original runtime resource identity unavailable");
    var backend = new H2Backend(dir.resolve("data/database.mv").toString());
    var server = new MongoServer(backend);
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var db = client.getDatabase("hkrpg");
      var collection = db.getCollection("players");
      require(collection.countDocuments() == 1, "Unexpected fixture player count");
      var filter = Filters.eq("_id", uid);
      var before = Objects.requireNonNull(collection.find(filter).first());
      var items = db.getCollection("items").find().into(new ArrayList<Document>());
      var store = Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var player =
          store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first();
      require(
          player != null && player.getGameplay().getTimelineActivityQuests().valid(rules),
          "Restored source-bound domain is invalid");
      var verified = new TreeSet<Integer>();
      for (int id : TimelineActivityRules.IDS) {
        var state = player.getGameplay().getTimelineActivityQuests().getQuests().get(id);
        require(
            state != null
                && state.getPhase() == QuestState.Phase.CLOSED
                && MissionStateRecords.completed(state, id, 3),
            "Original reward was not closed");
        require(!player.getQuestStates().containsKey(id), "Legacy duplicate masks owned history");
        require(
            TimelineActivityQuests.state(player, id) == null, "Expired activity was republished");
        require(
            QuestCompletionRecords.completed(player, id), "Completed history lost at expiry " + id);
        var panel = new JsonObject();
        panel.addProperty("PanelID", 799999);
        panel.addProperty("UnlockConditions", "[QuestClose:" + id + "]");
        GameData.getActivityPanelExcelMap()
            .add(ResourceLoader.getGson().fromJson(panel, ActivityPanelExcel.class));
        require(
            ActivityAccess.unlocked(player, 799999), "Owned closed history did not unlock " + id);
        verified.add(id);
      }
      require(
          before.equals(collection.find(filter).first())
              && items.equals(db.getCollection("items").find().into(new ArrayList<Document>()))
              && Arrays.equals(config, Files.readAllBytes(dir.resolve("config.json"))),
          "Read-only inspector changed fixture data");
      System.out.println(
          new Gson()
              .toJson(
                  Map.of(
                      "passed",
                      true,
                      "uid",
                      uid,
                      "completedOriginalActivityQuests",
                      verified,
                      "sourceBoundReceipts",
                      12,
                      "expiredWindowIsFixture",
                      true,
                      "dependentPanelIsFixture",
                      true,
                      "changedPlayers",
                      0,
                      "changedInventory",
                      0,
                      "clientGameplayVerified",
                      false)));
    } finally {
      server.shutdownNow();
    }
  }
}
