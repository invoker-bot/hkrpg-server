package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/** Only the parent acceptance and home are seeded; original goal and prop proof are read only. */
public final class RaidPropInteractionFixtureInspector {
  private static void require(boolean value, String why) {
    if (!value) throw new IllegalStateException(why);
  }

  public static void main(String[] args) throws Exception {
    var root = Path.of("").toRealPath();
    var directory = Path.of(args[0]).toRealPath();
    var controlFile = Path.of(args[1]).toRealPath();
    require(
        directory.startsWith(root.resolve("build/smoke"))
            && !directory.equals(root.resolve("build/smoke"))
            && controlFile.startsWith(directory),
        "Not an owned fixture");
    var marker =
        JsonParser.parseString(
                Files.readString(directory.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    require(
        marker.get("testFixture").getAsBoolean()
            && marker.get("raidPropInteractionFixture").getAsBoolean(),
        "Missing fixture marker");
    var control = JsonParser.parseString(Files.readString(controlFile)).getAsJsonObject();
    int uid = control.get("uid").getAsInt();
    String operation = args[2];
    require(uid > 0 && Set.of("seed", "inspect").contains(operation), "Invalid operation");
    byte[] config = Files.readAllBytes(directory.resolve("config.json"));
    var file = directory.resolve("data/database.mv");
    require(Files.isRegularFile(file), "Missing database");
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
          "Not one fixture account");
      var before = Objects.requireNonNull(players.find(filter).first());
      var updates = new Document();
      if (operation.equals("seed")) {
        var gameplay = before.get("gameplay", Document.class);
        require(
            gameplay.get("missionRaids", Document.class).isEmpty()
                && !gameplay.get("mainMissions", Document.class).containsKey("1040408")
                && !gameplay.get("subMissions", Document.class).containsKey("104040807"),
            "Target acceptance already exists");
        updates
            .append("worldLevel", 0)
            .append(
                "gameplay.mainMissions.1040407",
                Document.parse(new Gson().toJson(new QuestState(1040407))));
        var home = control.getAsJsonObject("home");
        updates
            .append("planeId", home.get("plane").getAsInt())
            .append("floorId", home.get("floor").getAsInt())
            .append("entryId", home.get("entry").getAsInt())
            .append("worldId", home.get("world").getAsInt())
            .append("pos", Document.parse(home.get("pos").toString()))
            .append("rot", new Document("x", 0).append("y", 0).append("z", 0));
        require(
            players.updateOne(filter, new Document("$set", updates)).getModifiedCount() == 1,
            "Seed did not modify one player");
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
      require(expected.equals(after), "Undeclared data changed");
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var player =
          store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first();
      require(player != null, "Player did not restore");
      var state = player.getGameplay();
      require(
          state.getScenePropStates().valid() && state.getScenePropStates().getRecords().isEmpty(),
          "Raid state escaped into world state");
      if (operation.equals("seed"))
        require(
            state.getMissionRaids().isEmpty()
                && !state.getSubMissions().containsKey(104040807)
                && !state.getMainMissions().containsKey(1040408),
            "Target or proof was prewritten");
      else {
        var run = state.getMissionRaids().get(40431);
        require(
            run != null
                && run.getRaidId() == 40431
                && run.getWorldLevel() == 0
                && run.getMainMission() == 1040408
                && run.isCompleted()
                    == Set.of("finish", "finished_resume", "leave", "home_resume")
                        .contains(control.get("mode").getAsString())
                && run.getPlane() == 40431
                && run.getFloor() == 40431002
                && run.getEntry() == 40431002,
            "Wrong source raid");
        String mode = control.get("mode").getAsString();
        boolean finished =
            Set.of("finish", "finished_resume", "leave", "home_resume").contains(mode);
        boolean paused = Set.of("pause", "leave", "home_resume").contains(mode);
        require(state.getCurrentMissionRaid() == (paused ? 0 : 40431), "Wrong current ownership");
        var main = state.getMainMissions().get(1040408);
        require(
            finished
                ? MissionStateRecords.completed(main, 1040408, 1)
                : MissionStateRecords.doing(main, 1040408, 1),
            "Main acceptance changed");
        for (int id :
            List.of(104040830, 104040834, 104040829, 104040801, 104040802, 104040806, 104040807))
          require(
              MissionStateRecords.completed(state.getSubMissions().get(id), id, 1),
              "Actual source objective did not finish " + id);
        require(
            run.getScenePropStates().valid() && run.getScenePropStates().getRecords().size() == 1,
            "Missing or duplicate prop state");
        var record = run.getScenePropStates().getRecords().get("40431:40431002:0:0:46:300002");
        require(
            record != null
                && record.getProp() == 104022
                && record.getState() == 1
                && Objects.equals(record.getAcceptance(), main.getObservationKey()),
            "Wrong source-bound transition");
        var gson = ResourceLoader.getGson();
        var definition = gson.fromJson(control.get("propDefinition"), PropInfo.class);
        var floor = gson.fromJson("{\"FloorID\":40431002}", FloorInfo.class);
        var group = gson.fromJson("{\"GroupName\":\"Source\",\"PropList\":[]}", GroupInfo.class);
        group.setId(46);
        group.getPropList().add(definition);
        floor.getGroups().put(46, group);
        floor.onLoad();
        require(
            record.getSource().equals(PropTimelines.source(definition)),
            "Saved source fingerprint changed");
        require(
            run.getTrialParty() != null
                && run.getTrialParty().getConfigs().containsKey(1021409)
                && run.getTrialParty().getRoster().equals(List.of(1021409)),
            "Original selected trial did not persist");
        if (finished) {
          for (int id :
              List.of(
                  104040832, 104040833, 104040809, 104040831, 104040818, 104040823, 104040821,
                  104040822, 104040824))
            require(
                MissionStateRecords.completed(state.getSubMissions().get(id), id, 1),
                "Required source goal did not finish " + id);
          for (String name :
              List.of(
                  "FSV_CpuRepair",
                  "FSV_PassLightCount",
                  "FSV_EraLight01Finished",
                  "FSV_UnloadEraLight",
                  "FSV_EraLight02Finished",
                  "FSV_EraLight03Finished",
                  "FSV_BossDoorRainBow",
                  "FSV_LoadAnchor"))
            require(
                Objects.equals(state.getStoryValues().get("floor:40431002:" + name), 1),
                "Original floor action did not persist " + name);
          require(
              Objects.equals(state.getStoryValues().get("group:40431002:41:Lock"), 1),
              "Original lift lock did not persist");
        } else
          require(
              !MissionStateRecords.completed(state.getSubMissions().get(104040824), 104040824, 1),
              "Incomplete main was published as finished");
        var rawRun =
            after
                .get("gameplay", Document.class)
                .get("missionRaids", Document.class)
                .get("40431", Document.class);
        require(
            rawRun.get("scenePropStates", Document.class).get("records", Document.class).size()
                == 1,
            "Raw BSON state changed");
      }
      require(
          Arrays.equals(config, Files.readAllBytes(directory.resolve("config.json"))),
          "Config changed");
      System.out.println(
          new Gson()
              .toJson(
                  Map.of(
                      "passed",
                      true,
                      "operation",
                      operation,
                      "uid",
                      uid,
                      "mode",
                      control.get("mode").getAsString(),
                      "targetAcceptancePrewritten",
                      false,
                      "targetCompletionPrewritten",
                      false,
                      "propStateProofPrewritten",
                      false,
                      "clientGameplayVerified",
                      false)));
    } finally {
      server.shutdownNow();
      backend.close();
    }
  }

  private static void set(Document document, String path, Object value) {
    String[] parts = path.split("\\.");
    var cursor = document;
    for (int i = 0; i < parts.length - 1; i++) {
      var next = cursor.get(parts[i], Document.class);
      if (next == null) {
        next = new Document();
        cursor.put(parts[i], next);
      }
      cursor = next;
    }
    cursor.put(parts[parts.length - 1], value);
  }

  private RaidPropInteractionFixtureInspector() {}
}
