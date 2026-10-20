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

/** Only source prerequisite history and map locations may be seeded in an owned smoke database. */
public final class WorldChestFixtureInspector {
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
        marker.get("testFixture").getAsBoolean() && marker.get("worldChestFixture").getAsBoolean(),
        "Missing marker");
    var config = Files.readAllBytes(dir.resolve("config.json"));
    var control = JsonParser.parseString(Files.readString(input)).getAsJsonObject();
    boolean baseZone = control.has("baseZone") && control.get("baseZone").getAsBoolean();
    boolean mapChests = control.has("mapChests") && control.get("mapChests").getAsBoolean();
    boolean mechanisms =
        control.has("mechanismProgress") && !control.get("mechanismProgress").isJsonNull();
    require(
        !mechanisms
            || marker.has("mechanismFixture") && marker.get("mechanismFixture").getAsBoolean(),
        "Missing mechanism fixture marker");
    require(
        !mapChests || marker.has("mapChestFixture") && marker.get("mapChestFixture").getAsBoolean(),
        "Missing map chest fixture marker");
    require(
        !baseZone
            || marker.has("baseZoneChestFixture")
                && marker.get("baseZoneChestFixture").getAsBoolean(),
        "Missing Base Zone fixture marker");
    int uid = control.get("uid").getAsInt();
    String mode = args[2];
    require(
        uid > 0 && Set.of("seed", "move", "inspect", "expired").contains(mode),
        "Invalid operation");
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
      var updates = new Document();
      if (mode.equals("seed")) {
        var gameplay = before.get("gameplay", Document.class);
        var saved = gameplay.get("worldChests", Document.class);
        require(
            saved.get("records", Document.class).isEmpty()
                && saved.get("quests", Document.class).isEmpty(),
            "Prewritten world observation or activity acceptance");
        updates.append("level", mapChests ? 1 : 21);
        for (int main : mapChests ? new int[0] : new int[] {baseZone ? 1000401 : 1000510}) {
          var q = new QuestState(main);
          q.advance(1, 1, 1);
          q.reserveReward();
          q.closeReward();
          updates.append("gameplay.mainMissions." + main, Document.parse(new Gson().toJson(q)));
        }
        Files.writeString(
            dir.resolve("world-chest-start-balances.json"),
            new Document("hcoin", before.get("hcoin"))
                .append("scoin", before.get("scoin"))
                .append("mcoin", before.get("mcoin"))
                .append("exp", before.get("exp"))
                .toJson());
        Files.writeString(
            dir.resolve("world-chest-start-items.json"),
            new Document(
                    "items",
                    db.getCollection("items")
                        .find(new Document("ownerUid", uid))
                        .into(new ArrayList<Document>()))
                .toJson());
      }
      if (mode.equals("seed") || mode.equals("move")) {
        var stage = control.getAsJsonObject("stage");
        updates
            .append("planeId", stage.get("plane").getAsInt())
            .append("floorId", stage.get("floor").getAsInt())
            .append("entryId", stage.get("entry").getAsInt())
            .append("worldId", stage.get("world").getAsInt())
            .append("pos", Document.parse(stage.get("pos").toString()))
            .append("rot", new Document("x", 0).append("y", 0).append("z", 0));
        require(
            players.updateOne(filter, new Document("$set", updates)).getMatchedCount() == 1,
            "No unique fixture update");
        backend.commit();
        backend.getMvStore().sync();
      }
      var after = Objects.requireNonNull(players.find(filter).first());
      var expected =
          Document.parse(
              before.toJson(
                  org.bson.json.JsonWriterSettings.builder()
                      .outputMode(org.bson.json.JsonMode.EXTENDED)
                      .build()));
      for (var e : updates.entrySet()) set(expected, e.getKey(), e.getValue());
      require(expected.equals(after), "Changed undeclared player data");
      var store = Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var player =
          store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first();
      require(player != null, "Morphia restore failed");
      var field = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
      field.setAccessible(true);
      var resources =
          baseZone || mapChests ? root.resolve("resources/4.6") : dir.resolve("resources-fixture");
      field.set(LunarCore.getConfig(), resources.toString());
      ResourceLoader.loadResources();
      var floorLoader = ResourceLoader.class.getDeclaredMethod("loadFloorInfos");
      floorLoader.setAccessible(true);
      floorLoader.invoke(null);
      GameplayDefinitions.load(resources);
      GameplayDefinitions.windows.clear();
      if (!mode.equals("expired"))
        GameplayDefinitions.windows.add(
            new GameplayDefinitions.Window(30013, 3001301, 1, 4102444800L));
      var rules = WorldChestRules.load();
      var domain = player.getGameplay().getWorldChests();
      require(
          rules != null && domain.valid(rules, player),
          "Invalid original sources or chest journal");
      int catalogValidated = 0;
      if (mapChests && mode.equals("seed")) {
        for (var e : MapChestSources.LOCATIONS.entrySet()) {
          int id = e.getKey();
          require(
              rules.chest(id) != null && rules.loot(id) != null,
              "Missing original chest definition or reward " + id);
          for (int dimension : e.getValue().dimensions())
            require(
                rules.original(id, dimension) != null,
                "Invalid original chest placement " + id + "/" + dimension);
          catalogValidated++;
        }
      }
      var known = new TreeSet<Integer>();
      var claimed = new TreeSet<Integer>();
      for (var id : control.getAsJsonArray("known")) known.add(id.getAsInt());
      for (var id : control.getAsJsonArray("claimed")) claimed.add(id.getAsInt());
      require(domain.getRecords().keySet().equals(known), "Different restored chest identities");
      boolean battlePhase = mechanisms && control.get("battleBalancePhase").getAsBoolean();
      boolean captureBattle = mechanisms && control.get("captureBattleBalances").getAsBoolean();
      if (mechanisms) {
        var progress = player.getGameplay().getWorldMechanisms();
        require(progress.valid(), "Invalid restored mechanism journal");
        var expectedProgress = control.getAsJsonObject("mechanismProgress");
        var keys = new TreeSet<Integer>();
        for (var e : expectedProgress.entrySet()) {
          int id = Integer.parseInt(e.getKey());
          keys.add(id);
          var actualProgress = progress.getRecords().get(id);
          var defeated = new TreeSet<Integer>();
          e.getValue()
              .getAsJsonObject()
              .getAsJsonArray("defeated")
              .forEach(v -> defeated.add(v.getAsInt()));
          require(
              actualProgress != null
                  && actualProgress.isComplete()
                      == e.getValue().getAsJsonObject().get("complete").getAsBoolean()
                  && actualProgress.getDefeated().equals(defeated),
              "Different restored mechanism progress " + id);
        }
        require(progress.getRecords().keySet().equals(keys), "Unexpected mechanism progress");
        if (battlePhase)
          require(
              known.isEmpty() && keys.equals(Set.of(20001632)) && domain.getQuests().isEmpty(),
              "Battle baseline only allowed before any chest rewards");
        if (captureBattle) {
          require(
              battlePhase
                  && mode.equals("inspect")
                  && progress.completed(20001632)
                  && progress
                      .getRecords()
                      .get(20001632)
                      .getDefeated()
                      .equals(WorldMechanismSources.RULES.get(20001632).guards())
                  && !Files.exists(dir.resolve("world-chest-battle-balances.json")),
              "Invalid battle balance snapshot");
          // Actual combat loot is outside this chest test's reward oracle. Record its resulting
          // balances once, without changing inventory, before checking every chest payout exactly.
          Files.writeString(
              dir.resolve("world-chest-battle-balances.json"),
              new Document("hcoin", after.get("hcoin"))
                  .append("scoin", after.get("scoin"))
                  .append("mcoin", after.get("mcoin"))
                  .append("exp", after.get("exp"))
                  .toJson());
          Files.writeString(
              dir.resolve("world-chest-battle-items.json"),
              new Document(
                      "items",
                      db.getCollection("items")
                          .find(new Document("ownerUid", uid))
                          .into(new ArrayList<Document>()))
                  .toJson());
        }
      }
      var payout = new TreeMap<Integer, Integer>();
      int credits = 0;
      for (var raw : control.getAsJsonArray("goals")) {
        var goal = raw.getAsJsonObject();
        int id = goal.get("id").getAsInt(), target = goal.get("goal").getAsInt();
        var state = domain.getQuests().get(id);
        if (mode.equals("seed")) require(state == null, "Prewritten activity acceptance");
        else {
          int count = Math.min(known.size(), target);
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
              == (baseZone || mapChests
                  ? 0
                  : Math.min(known.size(), 5)
                      + Math.min(known.size(), 10)
                      + Math.min(known.size(), 15)),
          "Different source-bound credit count");
      if (mapChests)
        require(domain.getQuests().isEmpty(), "Foreign activity accepted for level-one fixture");
      if (baseZone)
        require(
            domain.getQuests().isEmpty()
                && payout.equals(
                    known.isEmpty()
                        ? Map.of()
                        : Map.of(
                            1,
                            5 * known.size(),
                            2,
                            500 * known.size(),
                            22,
                            5 * known.size(),
                            120000,
                            15 * known.size(),
                            212,
                            3 * known.size(),
                            232,
                            known.size())),
            "Base Zone payout or foreign activity progress differs");
      String balancePrefix =
          mechanisms && Files.exists(dir.resolve("world-chest-battle-balances.json"))
              ? "world-chest-battle-"
              : "world-chest-start-";
      var balances = Document.parse(Files.readString(dir.resolve(balancePrefix + "balances.json")));
      for (String currency : List.of("hcoin", "scoin", "mcoin", "exp"))
        require(
            battlePhase
                || ((Number) after.get(currency)).longValue()
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
          Document.parse(Files.readString(dir.resolve(balancePrefix + "items.json")))
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
      require(battlePhase || first.equals(second), "Material payout mismatch");
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
                      mode.equals("seed") || mode.equals("move") ? 1 : 0,
                      "clientGameplayVerified",
                      false,
                      "catalogValidated",
                      catalogValidated,
                      "mechanismCatalogSize",
                      WorldMechanismSources.RULES.size())));
    } finally {
      server.shutdownNow();
    }
  }

  private static void set(Document document, String path, Object value) {
    var keys = path.split("\\.");
    var parent = document;
    for (int i = 0; i < keys.length - 1; i++) {
      if (!(parent.get(keys[i]) instanceof Document)) parent.put(keys[i], new Document());
      parent = parent.get(keys[i], Document.class);
    }
    parent.put(keys[keys.length - 1], value);
  }
}
