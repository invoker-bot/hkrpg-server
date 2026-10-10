package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.PropInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.bson.Document;

/** Only acceptance and locations may be seeded in one marked smoke database; proof is read only. */
public final class PropTimelineFixtureInspector {
  private static void require(boolean valid, String why) {
    if (!valid) throw new IllegalStateException(why);
  }

  public static void main(String[] args) throws Exception {
    var root = Path.of("").toRealPath();
    var dir = Path.of(args[0]).toRealPath();
    var controlFile = Path.of(args[1]).toRealPath();
    require(
        dir.startsWith(root.resolve("build/smoke"))
            && !dir.equals(root.resolve("build/smoke"))
            && controlFile.startsWith(dir),
        "Not an owned fixture");
    var marker =
        JsonParser.parseString(
                Files.readString(dir.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    require(
        marker.get("testFixture").getAsBoolean()
            && marker.get("propTimelineFixture").getAsBoolean(),
        "Missing timeline fixture marker");
    byte[] config = Files.readAllBytes(dir.resolve("config.json"));
    var control = JsonParser.parseString(Files.readString(controlFile)).getAsJsonObject();
    int uid = control.get("uid").getAsInt();
    String mode = args[2];
    require(uid > 0 && Set.of("seed", "move", "inspect").contains(mode), "Invalid operation");
    var file = dir.resolve("data/database.mv");
    require(Files.isRegularFile(file), "Missing owned database");
    var backend = new H2Backend(file.toString());
    var server = new MongoServer(backend);
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var players = client.getDatabase("hkrpg").getCollection("players");
      var filter = Filters.eq("_id", uid);
      require(
          players.countDocuments() == 1 && players.countDocuments(filter) == 1,
          "Not one fixture player");
      var before = Objects.requireNonNull(players.find(filter).first());
      var updates = new Document();
      if (mode.equals("seed")) {
        var saved = before.get("gameplay", Document.class);
        require(
            saved.get("propTimelines", Document.class).get("records", Document.class).isEmpty(),
            "Timeline proof already exists");
        for (var raw : control.getAsJsonArray("allGoals")) {
          var goal = raw.getAsJsonObject();
          int main = goal.get("main").getAsInt(), id = goal.get("id").getAsInt();
          updates.append(
              "gameplay.mainMissions." + main,
              Document.parse(new Gson().toJson(new QuestState(main))));
          updates.append(
              "gameplay.subMissions." + id, Document.parse(new Gson().toJson(new QuestState(id))));
        }
        for (var id : control.getAsJsonArray("linkedSubs"))
          updates.append(
              "gameplay.subMissions." + id.getAsInt(),
              Document.parse(new Gson().toJson(new QuestState(id.getAsInt()))));
      }
      if (!mode.equals("inspect")) {
        var stage = control.getAsJsonObject("stage");
        updates
            .append("planeId", stage.get("plane").getAsInt())
            .append("floorId", stage.get("floor").getAsInt())
            .append("entryId", stage.get("entry").getAsInt())
            .append("worldId", stage.get("world").getAsInt())
            .append("pos", Document.parse(stage.get("pos").toString()))
            .append("rot", new Document("x", 0).append("y", 0).append("z", 0));
        require(
            players.updateOne(filter, new Document("$set", updates)).getModifiedCount() == 1,
            "Fixture operation did not modify exactly one player");
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
      require(expected.equals(after), "Operation changed undeclared data");
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var player =
          store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first();
      require(player != null, "Morphia restore failed");
      var state = player.getGameplay();
      require(state.getPropTimelines().valid(), "Invalid restored timeline state");
      var completed = new HashSet<Integer>();
      for (var id : control.getAsJsonArray("completed")) completed.add(id.getAsInt());
      var expectedFloorValues = new LinkedHashMap<String, Integer>();
      for (var raw : control.getAsJsonArray("allGoals")) {
        var goal = raw.getAsJsonObject();
        int id = goal.get("id").getAsInt();
        var record = state.getSubMissions().get(id);
        require(
            record != null
                && record.getQuestId() == id
                && record.getProgress() == (completed.contains(id) ? 1 : 0)
                && record.getPhase()
                    == (completed.contains(id)
                        ? QuestState.Phase.FINISHED
                        : QuestState.Phase.DOING),
            "Incorrect saved original objective " + id);
        String key =
            goal.get("plane").getAsInt()
                + ":"
                + goal.get("floor").getAsInt()
                + ":0:0:"
                + goal.get("group").getAsInt()
                + ":"
                + goal.get("instance").getAsInt();
        var proof = state.getPropTimelines().getRecords().get(key);
        if (completed.contains(id)) {
          require(
              proof != null
                  && proof.getState().equals(goal.get("state").getAsString())
                  && proof.getProp() == goal.get("prop").getAsInt()
                  && proof.getAcceptance().isEmpty(),
              "Original proof not restored");
          // Fingerprints bind the canonical loaded component, including the floor loader's
          // normalization of graph ValueSource. Run that same loader on an isolated original prop.
          var props = new JsonArray();
          props.add(goal.get("propDefinition").deepCopy());
          var rawGroup = new JsonObject();
          rawGroup.add("PropList", props);
          var group = ResourceLoader.getGson().fromJson(rawGroup, GroupInfo.class);
          group.setId(goal.get("group").getAsInt());
          var floor = new FloorInfo();
          floor.getGroups().put(group.getId(), group);
          floor.onLoad();
          PropInfo info = group.getPropList().getFirst();
          var digest =
              HexFormat.of()
                  .formatHex(
                      MessageDigest.getInstance("SHA-256")
                          .digest(
                              ResourceLoader.getGson()
                                  .toJson(info)
                                  .getBytes(StandardCharsets.UTF_8)));
          require(proof.getSource().equals(digest), "Original component fingerprint differs");
          var actions = goal.getAsJsonArray("actions");
          if (!actions.isEmpty()) {
            var cursor = state.getStoryActions().get(id);
            require(
                cursor != null && cursor.getNext() == actions.size() && !cursor.isPending(),
                "Original completion actions not committed");
          }
          for (var action : goal.getAsJsonArray("actions")) {
            var a = action.getAsJsonObject();
            if ("SetFloorSavedValue".equals(a.get("FinishActionType").getAsString())) {
              var p = a.getAsJsonArray("FinishActionParaString");
              expectedFloorValues.put(
                  "floor:" + p.get(1).getAsString() + ":" + p.get(2).getAsString(),
                  p.get(3).getAsInt());
            }
            if ("SetCustomValue".equals(a.get("FinishActionType").getAsString())) {
              var p = a.getAsJsonArray("FinishActionParaString");
              require(
                  Objects.equals(
                      state
                          .getStoryValues()
                          .get(
                              "mission:"
                                  + goal.get("main").getAsInt()
                                  + ":"
                                  + p.get(0).getAsString()),
                      p.get(1).getAsInt()),
                  "Original custom action missing");
            }
            if ("delSubMission".equals(a.get("FinishActionType").getAsString()))
              for (var child : a.getAsJsonArray("FinishActionPara"))
                require(
                    state.getSubMissions().get(child.getAsInt()).getPhase()
                        == QuestState.Phase.FINISHED,
                    "Original linked action missing");
          }
        } else require(proof == null, "Completion proof was prewritten");
      }
      // Two original objectives intentionally write FSV_OpenRoad in sequence (3, then 2).
      // The latest completed action owns the saved value, rather than both historical values.
      for (var e : expectedFloorValues.entrySet())
        require(
            Objects.equals(state.getStoryValues().get(e.getKey()), e.getValue()),
            "Original latest floor action missing: " + e.getKey());
      require(
          state.getPropTimelines().getRecords().size() == completed.size(),
          "Unexpected extra proof");
      require(
          after.equals(players.find(filter).first())
              && Arrays.equals(config, Files.readAllBytes(dir.resolve("config.json"))),
          "Inspection changed persisted data");
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
                      "completed",
                      completed.size(),
                      "timelineRecords",
                      state.getPropTimelines().getRecords().size(),
                      "changedPlayers",
                      mode.equals("inspect") ? 0 : 1,
                      "clientGameplayVerified",
                      false)));
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
