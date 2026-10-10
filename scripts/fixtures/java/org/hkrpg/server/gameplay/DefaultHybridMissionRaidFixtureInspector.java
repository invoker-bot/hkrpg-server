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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.bson.Document;

/**
 * Seed only parent/target acceptance and home position in a marked owned DB; inspect proof read
 * only.
 */
public final class DefaultHybridMissionRaidFixtureInspector {
  private static void require(boolean valid, String why) {
    if (!valid) throw new IllegalStateException(why);
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
            && marker.get("defaultHybridMissionRaidFixture").getAsBoolean(),
        "Missing fixture marker");
    var control = JsonParser.parseString(Files.readString(controlFile)).getAsJsonObject();
    int uid = control.get("uid").getAsInt();
    String mode = args[2];
    require(uid > 0 && Set.of("seed", "inspect").contains(mode), "Unknown operation");
    byte[] config = Files.readAllBytes(directory.resolve("config.json"));
    var file = directory.resolve("data/database.mv");
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
        var gameplay = before.get("gameplay", Document.class);
        require(gameplay.get("missionRaids", Document.class).isEmpty(), "Raid already exists");
        updates.append(
            "gameplay.mainMissions.1040407",
            Document.parse(new Gson().toJson(new QuestState(1040407))));
        updates.append(
            "gameplay.subMissions.104040816",
            Document.parse(new Gson().toJson(new QuestState(104040816))));
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
            "Seed did not modify exactly one player");
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
      require(player != null, "Player did not restore");
      var state = player.getGameplay();
      require(
          state.getPropTimelines().valid() && state.getPropTimelines().getRecords().isEmpty(),
          "Raid proof escaped into world state");
      var target = state.getSubMissions().get(104040816);
      require(
          target != null && target.getQuestId() == 104040816, "Missing explicit target acceptance");
      if (mode.equals("seed")) {
        require(
            target.getProgress() == 0
                && target.getPhase() == QuestState.Phase.DOING
                && state.getMissionRaids().isEmpty()
                && !state.getMainMissions().containsKey(1040408),
            "Completion proof was seeded");
      } else {
        require(
            target.getPhase() == QuestState.Phase.FINISHED && target.getProgress() == 1,
            "Actual target completion missing");
        var run = state.getMissionRaids().get(40431);
        require(
            run != null
                && run.getRaidId() == 40431
                && run.getMainMission() == 1040408
                && run.getWorldLevel() == 0
                && !run.isCompleted()
                && run.getFloor() == 40431002
                && run.getEntry() == 40431002,
            "Wrong saved original raid");
        boolean paused = control.get("mode").getAsString().equals("pause");
        require(state.getCurrentMissionRaid() == (paused ? 0 : 40431), "Wrong saved ownership");
        var party = run.getTrialParty();
        require(
            party != null
                && party.getRoster().equals(List.of(1021409))
                && party.getConfigs().keySet().equals(Set.of(1021409))
                && run.getFormalTeamPlan().getRequirements().equals(Map.of(43, 1409)),
            "Saved original trial party changed");
        require(
            run.getTransferredSubs().equals(Map.of(104040829, 40431002)),
            "Original transfer missing");
        for (int id : List.of(104040830, 104040834, 104040829)) {
          var sub = state.getSubMissions().get(id);
          require(
              sub != null && sub.getPhase() == QuestState.Phase.FINISHED && sub.getProgress() == 1,
              "Original preceding objective missing " + id);
        }
        var main = state.getMainMissions().get(1040408);
        require(
            main != null
                && main.getPhase() == QuestState.Phase.DOING
                && main.getObservationKey() != null,
            "Incomplete main was completed or lost acceptance");
        require(
            run.getPropTimelines().valid() && run.getPropTimelines().getRecords().size() == 1,
            "Timeline snapshot missing or duplicated");
        var proof = run.getPropTimelines().getRecords().get("40431:40431002:0:0:38:300001");
        require(
            proof != null
                && proof.getProp() == 104256
                && proof.getState().equals("L0_Idle")
                && proof.getAcceptance().equals(main.getObservationKey()),
            "Raid proof bound to wrong acceptance");
        var rawGroup = new JsonObject();
        var props = new JsonArray();
        props.add(control.getAsJsonObject("goal").get("propDefinition").deepCopy());
        rawGroup.add("PropList", props);
        var group = ResourceLoader.getGson().fromJson(rawGroup, GroupInfo.class);
        group.setId(38);
        var floor = new FloorInfo();
        floor.getGroups().put(38, group);
        floor.onLoad();
        String source =
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(
                            ResourceLoader.getGson()
                                .toJson(group.getPropList().getFirst())
                                .getBytes(StandardCharsets.UTF_8)));
        require(source.equals(proof.getSource()), "Loaded source component changed");
        require(
            Objects.equals(state.getStoryValues().get("floor:40431001:FSV_SunState"), 9),
            "Original battle completion action missing");
        var cursor = state.getStoryActions().get(104040830);
        require(
            cursor != null && cursor.getNext() == 1 && !cursor.isPending(),
            "Original battle action not committed");
      }
      require(
          after.equals(players.find(filter).first())
              && Arrays.equals(config, Files.readAllBytes(directory.resolve("config.json"))),
          "Inspection mutated saved data");
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
                      "targetCompletionPrewritten",
                      false,
                      "timelineProofPrewritten",
                      false,
                      "changedPlayers",
                      mode.equals("seed") ? 1 : 0,
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
