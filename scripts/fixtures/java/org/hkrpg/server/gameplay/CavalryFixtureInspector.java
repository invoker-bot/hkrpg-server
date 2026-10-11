package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import dev.morphia.Morphia;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/** Offline seed of declared prerequisites only; inspect the actual saved ride and inventory. */
public final class CavalryFixtureInspector {
  private static void require(boolean valid, String why) {
    if (!valid) throw new IllegalStateException(why);
  }

  private static Document completed(int id, int progress) {
    var state = new QuestState(id);
    state.advance(progress, progress, 1);
    state.reserveReward();
    state.closeReward();
    return Document.parse(new Gson().toJson(state));
  }

  public static void main(String[] args) throws Exception {
    var root = Path.of("").toRealPath();
    var directory = Path.of(args[0]).toRealPath();
    var input = Path.of(args[1]).toRealPath();
    require(directory.startsWith(root.resolve("build/smoke")) && !directory.equals(root.resolve("build/smoke"))
        && input.startsWith(directory), "Unowned cavalry smoke directory");
    var marker = JsonParser.parseString(Files.readString(directory.resolve("resources-fixture/resource-manifest.json")))
        .getAsJsonObject();
    require(marker.get("testFixture").getAsBoolean() && marker.get("cavalryBeastFixture").getAsBoolean(),
        "Missing owned cavalry marker");
    var config = Files.readAllBytes(directory.resolve("config.json"));
    var control = JsonParser.parseString(Files.readString(input)).getAsJsonObject();
    int uid = control.get("uid").getAsInt();
    String mode = args[2];
    require(uid == 10001 && Set.of("seed", "inspect", "expired").contains(mode), "Invalid cavalry fixture operation");
    var file = directory.resolve("data/database.mv");
    require(Files.isRegularFile(file), "Missing owned H2 database");
    var backend = new H2Backend(file.toString());
    var server = new MongoServer(backend);
    var address = server.bind();
    try (var client = MongoClients.create("mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var db = client.getDatabase("hkrpg");
      var players = db.getCollection("players");
      var filter = Filters.eq("_id", uid);
      require(players.countDocuments() == 1 && players.countDocuments(filter) == 1, "Not one isolated cavalry player");
      var before = Objects.requireNonNull(players.find(filter).first());
      var updates = new Document();
      if (mode.equals("seed")) {
        var saved = before.get("gameplay", Document.class).get("cavalryBeasts", Document.class);
        require(saved != null && saved.getInteger("version") == 1 && saved.get("activity") == null
            && saved.get("receipt") == null, "Prewritten cavalry acceptance or receipt");
        updates.append("level", 21)
            .append("gameplay.mainMissions.1000510", completed(1000510, 1))
            .append("gameplay.mainMissions.1040101", completed(1040101, 1))
            .append("gameplay.subMissions.104010102", completed(104010102, 1));
        var stage = control.getAsJsonObject("stage");
        updates.append("planeId", stage.get("plane").getAsInt())
            .append("floorId", stage.get("floor").getAsInt())
            .append("entryId", stage.get("entry").getAsInt()).append("worldId", 501)
            .append("pos", Document.parse(stage.get("pos").toString()))
            .append("rot", new Document("x", 0).append("y", 0).append("z", 0));
        Files.writeString(directory.resolve("cavalry-start-balances.json"),
            new Document("hcoin", before.get("hcoin")).append("scoin", before.get("scoin"))
                .append("mcoin", before.get("mcoin")).toJson());
        Files.writeString(directory.resolve("cavalry-start-items.json"),
            new Document("items", db.getCollection("items").find(new Document("ownerUid", uid))
                .into(new ArrayList<Document>())).toJson());
        require(players.updateOne(filter, new Document("$set", updates)).getModifiedCount() == 1,
            "No unique declared prerequisite seed");
        backend.commit();
        backend.getMvStore().sync();
      }
      var after = Objects.requireNonNull(players.find(filter).first());
      var expected = Document.parse(before.toJson(org.bson.json.JsonWriterSettings.builder()
          .outputMode(org.bson.json.JsonMode.EXTENDED).build()));
      for (var entry : updates.entrySet()) set(expected, entry.getKey(), entry.getValue());
      require(expected.equals(after), "Changed undeclared cavalry player data");
      var store = Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var player = store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first();
      require(player != null, "Cavalry Morphia restore failed");
      var field = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
      field.setAccessible(true);
      field.set(LunarCore.getConfig(), directory.resolve("resources-fixture").toString());
      ResourceLoader.loadResources();
      var floorLoader = ResourceLoader.class.getDeclaredMethod("loadFloorInfos");
      floorLoader.setAccessible(true);
      floorLoader.invoke(null);
      GameplayDefinitions.load(directory.resolve("resources-fixture"));
      GameplayDefinitions.windows.clear();
      if (!mode.equals("expired")) GameplayDefinitions.windows.add(new GameplayDefinitions.Window(30013, 3001301, 1, 4102444800L));
      var rules = CavalryBeastRules.load();
      var domain = player.getGameplay().getCavalryBeasts();
      require(rules != null && domain.valid(rules), "Invalid original cavalry source or restored receipt");
      require(!player.getQuestStates().containsKey(6029860), "Legacy state masked the original cavalry goal");
      if (mode.equals("seed")) {
        require(domain.getActivity() == null && domain.getReceipt() == null, "Target state prewritten by seed");
        require(!QuestCompletionRecords.completed(player, 6029860), "Target completion inferred from prerequisites");
      } else {
        var state = domain.getActivity();
        require(state != null && state.getProgress() == 1 && state.getPhase() == QuestState.Phase.CLOSED,
            "Restored cavalry close differs");
        require(QuestCompletionRecords.completed(player, 6029860) && QuestCompletionRecords.closed(player, 6029860),
            "Restored cavalry history lost");
        var receipt = domain.getReceipt();
        require(receipt != null && receipt.getQuest() == 6029860 && receipt.getPlane() == 10401
            && receipt.getFloor() == 10401001 && receipt.getGroup() == 176 && receipt.getInstance() == 400001
            && receipt.getNpc() == 3185 && receipt.getDimension() == 0 && receipt.getStory() == 0
            && receipt.getBegin() == 1 && receipt.getEnd() == 4102444800L
            && receipt.getAt() == state.getFinishTime() && receipt.getAcceptance().equals(state.getObservationKey()),
            "Restored actual source cavalry receipt differs");
        if (mode.equals("expired")) require(CavalryBeastQuests.state(player, 6029860) == null, "Expired cavalry republished");
      }
      var balances = Document.parse(Files.readString(directory.resolve("cavalry-start-balances.json")));
      for (String currency : List.of("hcoin", "scoin", "mcoin"))
        require(((Number) after.get(currency)).longValue() == ((Number) balances.get(currency)).longValue()
            + (!mode.equals("seed") && currency.equals("hcoin") ? 20 : 0), "Original cavalry currency payout differs: " + currency);
      var initialItems = Document.parse(Files.readString(directory.resolve("cavalry-start-items.json"))).getList("items", Document.class);
      var actualItems = db.getCollection("items").find(new Document("ownerUid", uid)).into(new ArrayList<Document>());
      var first = new TreeMap<Integer, Integer>();
      var second = new TreeMap<Integer, Integer>();
      initialItems.forEach(item -> first.merge(item.getInteger("itemId"), item.getInteger("count"), Math::addExact));
      actualItems.forEach(item -> second.merge(item.getInteger("itemId"), item.getInteger("count"), Math::addExact));
      if (!mode.equals("seed")) first.merge(213, 4, Math::addExact);
      require(first.equals(second), "Original cavalry item payout differs");
      require(after.equals(players.find(filter).first()) && Arrays.equals(config, Files.readAllBytes(directory.resolve("config.json"))),
          "Cavalry read-only inspection changed data");
      var report = new LinkedHashMap<String, Object>();
      report.put("passed", true);
      report.put("uid", uid);
      report.put("mode", mode);
      report.put("changedPlayers", mode.equals("seed") ? 1 : 0);
      report.put("targetCompletionPrewritten", false);
      report.put("receiptPrewritten", false);
      report.put("receipt", domain.getReceipt());
      report.put("actualRewards", mode.equals("seed") ? Map.of() : Map.of(1, 20, 213, 4));
      System.out.println(new Gson().toJson(report));
    } finally { server.shutdownNow(); }
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
