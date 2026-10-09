package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;
import org.bson.json.*;

/** Only prepares declared entry prerequisites; every message choice/completion comes from KCP. */
public final class BoxingClubMessagesFixtureInspector {
  static final JsonWriterSettings EXACT =
      JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();
  static final List<Integer> SECTIONS = List.of(1110000, 1110700, 1301100);
  static final Map<Integer, Integer> GOALS =
      Map.of(1110000, 201070100, 1110700, 201070202, 1301100, 801520201);

  static void require(boolean valid, String why) {
    if (!valid) throw new IllegalStateException(why);
  }

  public static void main(String[] args) throws Exception {
    require(args.length == 3, "Expected fixture/control/phase");
    var root = Path.of("").toRealPath();
    var dir = Path.of(args[0]).toRealPath();
    var control = Path.of(args[1]).toRealPath();
    String phase = args[2];
    require(
        dir.startsWith(root.resolve("build/smoke"))
            && !dir.equals(root.resolve("build/smoke"))
            && control.startsWith(dir),
        "Not an owned smoke fixture");
    require(
        Set.of("prepare", "partial", "finished").contains(phase), "Unknown phone inspection phase");
    var marker =
        JsonParser.parseString(
                Files.readString(dir.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    require(
        marker.get("testFixture").getAsBoolean()
            && marker.get("boxingMessagesFixture").getAsBoolean(),
        "Missing marker");
    var input = JsonParser.parseString(Files.readString(control)).getAsJsonObject();
    int uid = input.get("uid").getAsInt();
    require(uid > 0, "Invalid UID");
    var config =
        JsonParser.parseString(Files.readString(dir.resolve("config.json"))).getAsJsonObject();
    var file =
        dir.resolve(config.getAsJsonObject("internalMongoServer").get("filePath").getAsString())
            .normalize();
    require(file.startsWith(dir) && Files.isRegularFile(file), "Database outside smoke fixture");
    var server = new MongoServer(new H2Backend(file.toString()));
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var db = client.getDatabase("hkrpg");
      var players = db.getCollection("players");
      require(players.countDocuments() == 1, "Not a unique test player");
      var filter = new Document("_id", uid);
      var before = Objects.requireNonNull(players.find(filter).first());
      var gameplay = before.get("gameplay", Document.class);
      require(gameplay != null, "Missing gameplay document");
      if (phase.equals("prepare")) {
        var phones = gameplay.get("phoneSections", Document.class);
        var subs = gameplay.get("subMissions", Document.class);
        for (int id : SECTIONS) {
          require(
              phones == null || !phones.containsKey(String.valueOf(id)),
              "Message state prewritten");
          require(
              subs == null || !subs.containsKey(String.valueOf(GOALS.get(id))),
              "Message goal prewritten");
        }
        require(subs == null || !subs.containsKey("201070201"), "Talk goal prewritten");
        var entry = input.getAsJsonObject("source").getAsJsonObject("entry");
        var update =
            new Document("level", 21)
                .append("planeId", entry.get("PlaneID").getAsInt())
                .append("floorId", entry.get("FloorID").getAsInt())
                .append("entryId", entry.get("ID").getAsInt());
        var expected = Document.parse(before.toJson(EXACT));
        for (String key : List.of("level", "planeId", "floorId", "entryId"))
          expected.put(key, update.get(key));
        var expectedGameplay = expected.get("gameplay", Document.class);
        var mains = expectedGameplay.get("mainMissions", Document.class);
        if (mains == null) {
          mains = new Document();
          expectedGameplay.put("mainMissions", mains);
        }
        for (int id : List.of(1011001, 2010720, 1011503, 1010302)) {
          var gate =
              new Document("questId", id)
                  .append("progress", 1)
                  .append("finishTime", 123L)
                  .append("phase", "CLOSED");
          update.put("gameplay.mainMissions." + id, gate);
          mains.put(String.valueOf(id), gate);
        }
        require(
            players.updateOne(filter, new Document("$set", update)).getMatchedCount() == 1
                && expected.equals(players.find(filter).first()),
            "Preparation changed unrelated state");
        var baseline = new Document();
        for (String key : List.of("hcoin", "scoin", "mcoin")) baseline.put(key, before.get(key));
        baseline.put(
            "items",
            db.getCollection("items")
                .find(new Document("ownerUid", uid))
                .into(new ArrayList<Document>()));
        Files.writeString(
            dir.resolve("phone-start-inventory.json"),
            baseline.toJson(EXACT),
            StandardOpenOption.CREATE_NEW);
      } else {
        var baseline = Document.parse(Files.readString(dir.resolve("phone-start-inventory.json")));
        for (String key : List.of("hcoin", "scoin", "mcoin"))
          require(Objects.equals(baseline.get(key), before.get(key)), "Wallet changed: " + key);
        require(
            baseline
                .getList("items", Document.class)
                .equals(
                    db.getCollection("items")
                        .find(new Document("ownerUid", uid))
                        .into(new ArrayList<Document>())),
            "Item documents changed");
        // Reconstruct the real Player/PhoneSection/QuestState through the production Morphia
        // mapping.
        var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
        store.getMapper().mapPackage("org.hkrpg.server.gameplay");
        store.getMapper().map(Player.class, QuestState.class);
        var player =
            store
                .find(Player.class)
                .filter(dev.morphia.query.filters.Filters.eq("_id", uid))
                .first();
        require(player != null, "Morphia player reload failed");
        boolean done = phase.equals("finished");
        var expected = input.getAsJsonObject(done ? "finished" : "partial");
        var offered = input.getAsJsonObject(done ? "finishedOffered" : "partialOffered");
        for (int id : SECTIONS) {
          var state = player.getGameplay().getPhoneSections().get(id);
          var goal = player.getGameplay().getSubMissions().get(GOALS.get(id));
          var history = new ArrayList<Integer>();
          for (var value : expected.getAsJsonArray(String.valueOf(id)))
            history.add(value.getAsInt());
          var choices = new ArrayList<Integer>();
          for (var value : offered.getAsJsonArray(String.valueOf(id)))
            choices.add(value.getAsInt());
          require(
              state != null
                  && state.getId() == id
                  && state.isFinished() == done
                  && state.getHistory().equals(history)
                  && state.getOffered().equals(choices),
              "Wrong persisted source graph: " + id);
          require(
              goal != null
                  && goal.getQuestId() == GOALS.get(id)
                  && goal.getProgress() == (done ? 1 : 0)
                  && goal.getPhase() == (done ? QuestState.Phase.FINISHED : QuestState.Phase.DOING),
              "Wrong persisted message goal: " + id);
        }
        require(
            player.getGameplay().getSubMissions().get(201070201).getPhase()
                == QuestState.Phase.FINISHED,
            "Original Talk report not restored");
        for (int main : List.of(2010701, 8015202))
          require(
              player.getGameplay().getMainMissions().get(main).getPhase() == QuestState.Phase.DOING,
              "Unfinished story parent completed");
        var parent = player.getGameplay().getMainMissions().get(2010702);
        require(
            parent.getPhase() == (done ? QuestState.Phase.CLOSED : QuestState.Phase.DOING),
            "Wrong original message-ending parent");
        if (done) {
          var receipt = player.getGameplay().getRewards().get("mission:2010702");
          var plan = player.getGameplay().getMissionRewardPlans().get("mission:2010702");
          require(
              receipt != null
                  && receipt.isGranted()
                  && receipt.getRewardId() == 0
                  && plan != null
                  && plan.getRewardIds().equals(List.of(100))
                  && plan.getItems().isEmpty(),
              "Original empty reward plan changed");
          require(
              player.getGameplay().getMainMissions().get(2010703).getPhase()
                  == QuestState.Phase.DOING,
              "Authored subsequent main not accepted");
        }
        require(
            before.equals(players.find(filter).first()),
            "Read-only Morphia inspection wrote player state");
      }
      var result = new LinkedHashMap<String, Object>();
      result.put("passed", true);
      result.put("uid", uid);
      result.put("phase", phase);
      result.put("fixturePreparationWrites", phase.equals("prepare") ? 1 : 0);
      result.put("inspectionWrites", 0);
      result.put("messageChoicesPrewritten", false);
      result.put("messageCompletionsPrewritten", false);
      result.put("sourceResourcesModified", false);
      result.put("operatorDataModified", false);
      result.put("clientGameplayVerified", false);
      System.out.println(new Gson().toJson(result));
    } finally {
      server.shutdownNow();
    }
  }
}
