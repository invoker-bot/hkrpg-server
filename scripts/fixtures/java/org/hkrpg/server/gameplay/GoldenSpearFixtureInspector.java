package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import dev.morphia.Morphia;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/** Read-only inspection of a copied predecessor database; no prerequisite or progress writes. */
public final class GoldenSpearFixtureInspector {
  private static void require(boolean valid, String why) {
    if (!valid) throw new IllegalStateException(why);
  }

  public static void main(String[] args) throws Exception {
    var root = Path.of("").toRealPath();
    var dir = Path.of(args[0]).toRealPath();
    var input = Path.of(args[1]).toRealPath();
    require(
        dir.startsWith(root.resolve("build/smoke"))
            && !dir.equals(root.resolve("build/smoke"))
            && input.startsWith(dir),
        "Unowned fixture");
    var marker =
        JsonParser.parseString(
                Files.readString(dir.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    require(
        marker.get("testFixture").getAsBoolean()
            && marker.get("worldChestFixture").getAsBoolean()
            && marker.get("timeRelayFlameFixture").getAsBoolean()
            && marker.get("goldenSpearFixture").getAsBoolean(),
        "Missing marker");
    var config = Files.readAllBytes(dir.resolve("config.json"));
    var control = JsonParser.parseString(Files.readString(input)).getAsJsonObject();
    int uid = control.get("uid").getAsInt();
    String mode = args[2];
    require(uid > 0 && Set.of("inspect", "expired").contains(mode), "Invalid operation");
    var file = dir.resolve("data/database.mv");
    require(Files.isRegularFile(file), "Missing database");
    var backend = new H2Backend(file.toString());
    var server = new MongoServer(backend);
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var db = client.getDatabase("hkrpg");
      var players = db.getCollection("players");
      var filter = Filters.eq("_id", uid);
      require(
          players.countDocuments() == 1 && players.countDocuments(filter) == 1,
          "Not one fixture player");
      var before = Objects.requireNonNull(players.find(filter).first());
      var after = Objects.requireNonNull(players.find(filter).first());
      var expected =
          Document.parse(
              before.toJson(
                  org.bson.json.JsonWriterSettings.builder()
                      .outputMode(org.bson.json.JsonMode.EXTENDED)
                      .build()));
      require(expected.equals(after), "Changed undeclared player data");
      var store = Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var player =
          store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first();
      require(player != null, "Morphia restore failed");
      var field = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
      field.setAccessible(true);
      field.set(LunarCore.getConfig(), dir.resolve("resources-fixture").toString());
      ResourceLoader.loadResources();
      var floorLoader = ResourceLoader.class.getDeclaredMethod("loadFloorInfos");
      floorLoader.setAccessible(true);
      floorLoader.invoke(null);
      GameplayDefinitions.load(dir.resolve("resources-fixture"));
      GameplayDefinitions.windows.clear();
      if (!mode.equals("expired"))
        GameplayDefinitions.windows.add(
            new GameplayDefinitions.Window(30013, 3001301, 1, 4102444800L));
      var rules = WorldChestRules.load();
      var domain = player.getGameplay().getWorldChests();
      require(
          rules != null && domain.valid(rules, player),
          "Invalid original sources or chest journal");
      var known = new TreeSet<Integer>();
      var claimed = new TreeSet<Integer>();
      for (var id : control.getAsJsonArray("known")) known.add(id.getAsInt());
      for (var id : control.getAsJsonArray("claimed")) claimed.add(id.getAsInt());
      require(domain.getRecords().keySet().equals(known), "Different restored chest identities");
      var unlocked = new TreeSet<Integer>();
      for (var id : control.getAsJsonArray("unlocked")) unlocked.add(id.getAsInt());
      require(
          domain.getUnlocks().keySet().equals(unlocked),
          "Different restored puzzle finish reports");
      for (int id : known)
        if (id >= 20411654 && id <= 20411657 || id == 20411666 || id == 20411667)
          require(unlocked.contains(id), "Opening has no source-bound finish report");

      var stages = control.getAsJsonObject("spearProgress");
      var expectedSpears = new TreeSet<Integer>();
      stages
          .entrySet()
          .forEach(
              e -> {
                if (e.getValue().getAsInt() > 0) expectedSpears.add(Integer.parseInt(e.getKey()));
              });
      require(
          domain.getSpears().keySet().equals(expectedSpears),
          "Different source spear ledger identities");
      int sourceSteps = 0;
      for (int id : expectedSpears) {
        var record = domain.getSpears().get(id);
        require(
            record.getSteps().size() == stages.get(Integer.toString(id)).getAsInt(),
            "Different source spear stage");
        sourceSteps += record.getSteps().size();
      }
      for (var unlock : domain.getUnlocks().values())
        require(unlock.completed(), "Pending destruction reveal");
      var payout = new TreeMap<Integer, Integer>();
      int credits = 0;
      for (var raw : control.getAsJsonArray("goals")) {
        var goal = raw.getAsJsonObject();
        int id = goal.get("id").getAsInt(), target = goal.get("goal").getAsInt();
        var state = domain.getQuests().get(id);
        {
          int count =
              Math.min(
                  id == 6029856
                      ? (int) known.stream().filter(k -> k >= 20411654 && k <= 20411657).count()
                      : id == 6029858
                          ? (int) known.stream().filter(k -> k == 20411666 || k == 20411667).count()
                          : known.size(),
                  target);
          require(
              state != null
                  && state.getProgress() == count
                  && state.getPhase()
                      == (claimed.contains(id)
                          ? QuestState.Phase.CLOSED
                          : count == target ? QuestState.Phase.FINISHED : QuestState.Phase.DOING),
              "Different restored activity progress");
          require(
              QuestCompletionRecords.completed(player, id) == (count == target)
                  && QuestCompletionRecords.closed(player, id) == claimed.contains(id),
              "Different completed history");
          if (mode.equals("expired"))
            require(ActivityChestQuests.state(player, id) == null, "Expired activity republished");
        }
        require(!player.getQuestStates().containsKey(id), "Legacy quest masks owned chest receipt");
        if (claimed.contains(id))
          goal.getAsJsonObject("rewards")
              .entrySet()
              .forEach(
                  e ->
                      payout.merge(
                          Integer.parseInt(e.getKey()), e.getValue().getAsInt(), Math::addExact));
      }
      for (var record : domain.getRecords().values()) {
        require(record.getPhase() == WorldChestState.Phase.OPEN, "Pending native delivery");
        credits += record.getAcceptances().size();
        record.getLoot().forEach((id, count) -> payout.merge(id, count, Math::addExact));
        if (record.getAcceptances().isEmpty())
          require(record.getBegin() == 0 && record.getEnd() == 0, "Unexpected historical window");
        else
          require(
              record.getBegin() == 1 && record.getEnd() == 4102444800L,
              "Captured original window differs");
      }
      require(
          credits
              == Math.min(known.size(), 5)
                  + Math.min(known.size(), 10)
                  + Math.min(known.size(), 15)
                  + Math.min(1, known.stream().filter(k -> k >= 20411654 && k <= 20411657).count())
                  + Math.min(1, known.stream().filter(k -> k == 20411666 || k == 20411667).count()),
          "Different source-bound credit count");
      var balances =
          Document.parse(Files.readString(dir.resolve("world-chest-start-balances.json")));
      for (String currency : List.of("hcoin", "scoin", "mcoin", "exp"))
        require(
            ((Number) after.get(currency)).longValue()
                == ((Number) balances.get(currency)).longValue()
                    + payout.getOrDefault(
                        currency.equals("hcoin")
                            ? 1
                            : currency.equals("scoin") ? 2 : currency.equals("exp") ? 22 : 3,
                        0),
            "Currency payout mismatch "
                + currency
                + " actual="
                + after.get(currency)
                + " initial="
                + balances.get(currency)
                + " payouts="
                + payout);
      var initial =
          Document.parse(Files.readString(dir.resolve("world-chest-start-items.json")))
              .getList("items", Document.class);
      var actual =
          db.getCollection("items")
              .find(new Document("ownerUid", uid))
              .into(new ArrayList<Document>());
      var first = new TreeMap<Integer, Integer>();
      var second = new TreeMap<Integer, Integer>();
      initial.forEach(
          item -> first.merge(item.getInteger("itemId"), item.getInteger("count"), Math::addExact));
      actual.forEach(
          item ->
              second.merge(item.getInteger("itemId"), item.getInteger("count"), Math::addExact));
      payout.forEach(
          (id, count) -> {
            if (id > 3 && id != 22) first.merge(id, count, Math::addExact);
          });
      require(first.equals(second), "Material payout mismatch");
      require(
          after.equals(players.find(filter).first())
              && Arrays.equals(config, Files.readAllBytes(dir.resolve("config.json"))),
          "Inspector changed data");
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
                      "openedChests",
                      known.size(),
                      "activityCredits",
                      credits,
                      "claimed",
                      claimed,
                      "changedPlayers",
                      0,
                      "unlockedChests",
                      unlocked.size(),
                      "sourceSpearSteps",
                      sourceSteps,
                      "clientGameplayVerified",
                      false)));
    } finally {
      server.shutdownNow();
    }
  }
}
