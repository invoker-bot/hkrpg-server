package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import emu.lunarcore.data.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.util.Position;
import java.nio.file.*;
import java.util.*;

/** Source parent acceptance/cancellation fixtures, confined to a marked, stopped smoke database. */
public final class RaidScriptedTrialFixtureInspector {
  private static void require(boolean valid, String reason) {
    if (!valid) throw new IllegalStateException(reason);
  }

  public static void main(String[] args) throws Exception {
    require(args.length == 3, "Expected fixture directory, credentials file, operation");
    var root = Path.of("").toRealPath();
    var directory = Path.of(args[0]).toRealPath();
    var credentials = Path.of(args[1]).toRealPath();
    require(
        directory.startsWith(root.resolve("build/smoke"))
            && !directory.equals(root.resolve("build/smoke"))
            && credentials.startsWith(directory),
        "Not an owned smoke fixture");
    var marker =
        JsonParser.parseString(
                Files.readString(directory.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    require(
        marker.get("testFixture").getAsBoolean()
            && marker.get("raidScriptedTrialsFixture").getAsBoolean(),
        "Missing fixture marker");
    String mode = args[2];
    require(
        Set.of("pending", "prepare", "active", "resume", "close", "closed").contains(mode),
        "Invalid fixture operation");
    var users = JsonParser.parseString(Files.readString(credentials)).getAsJsonArray();
    require(
        users.size() >= 2
            && users.size() <= 4
            && users.size() == marker.getAsJsonArray("raids").size(),
        "Unexpected fixture player count");
    var file = directory.resolve("data/database.mv");
    require(Files.isRegularFile(file), "Missing fixture H2 database");
    var server = new MongoServer(new H2Backend(file.toString()));
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, Position.class);
      require(
          store.find(Player.class).count() == users.size(), "Unexpected fixture player collection");
      if (mode.equals("prepare")) {
        var config = new emu.lunarcore.Config();
        config.resourceDir = directory.resolve("resources-fixture").toString();
        var field = emu.lunarcore.LunarCore.class.getDeclaredField("config");
        field.setAccessible(true);
        field.set(null, config);
        ResourceLoader.loadResources();
        var floors = ResourceLoader.class.getDeclaredMethod("loadFloorInfos");
        floors.setAccessible(true);
        floors.invoke(null);
        GameplayDefinitions.loadMissions(
            directory.resolve("resources-fixture/Config/Level/Mission"));
      }
      var output = new LinkedHashMap<String, Object>();
      var snapshots = new ArrayList<Object>();
      int changed = 0;
      for (var raw : users) {
        var c = raw.getAsJsonObject();
        int uid = c.get("uid").getAsInt();
        int raid = c.get("raid").getAsInt(), main = c.get("main").getAsInt();
        int sub = c.get("sub").getAsInt(), trial = c.get("trial").getAsInt();
        var player =
            Objects.requireNonNull(
                store
                    .find(Player.class)
                    .filter(dev.morphia.query.filters.Filters.eq("_id", uid))
                    .first());
        require(
            player.getScoin() == 0 && player.getHcoin() == 0, "Unexpected fixture currency reward");
        var run = player.getGameplay().getMissionRaids().get(raid);
        boolean frozenBase = c.has("frozenBase") && c.get("frozenBase").getAsBoolean();
        boolean trialOnly = c.has("trialOnly") && c.get("trialOnly").getAsBoolean();
        var baseRoster =
            trialOnly
                ? c.getAsJsonArray("baseRoster").asList().stream()
                    .map(JsonElement::getAsInt)
                    .toList()
                : List.of(8001);
        var expectedRoster = new ArrayList<>(baseRoster);
        expectedRoster.add(trial);
        int plane = c.has("plane") ? c.get("plane").getAsInt() : raid;
        if (run != null && trialOnly) {
          var base = Objects.requireNonNull(run.getTrialParty());
          require(
              base.getRoster().equals(baseRoster)
                  && base.getConfigs().keySet().equals(new HashSet<>(baseRoster))
                  && base.getLeader() == 0
                  && base.getMp() == 5,
              "Original automatic trial party or captain changed");
          for (int id : baseRoster)
            require(
                base.getVitals().get(id).getHp() == 10000
                    && base.getVitals().get(id).getSp() == 7000,
                "Original trial vitals changed");
          var dynamic = run.getMissionTrials().getParty();
          if (dynamic != null)
            require(
                base.frozen().equals(dynamic.getRaidBase())
                    && dynamic.getBaseActors().keySet().equals(new HashSet<>(baseRoster)),
                "Dynamic party lost its original automatic trial actors");
        }
        if (run != null && frozenBase) {
          var base = Objects.requireNonNull(run.getTrialParty());
          require(
              base.getConfigs().isEmpty()
                  && base.getRoster().equals(List.of(8001))
                  && base.getLeader() == 0
                  && base.getMp() == 5
                  && base.getVitals().get(8001).getHp() == 10000
                  && base.getVitals().get(8001).getSp() == 0,
              "Original forbidden formal party was mutated");
          var dynamic = run.getMissionTrials().getParty();
          if (dynamic != null)
            require(
                base.frozen().equals(dynamic.getRaidBase()),
                "Dynamic actor lost its exact original forbidden party binding");
        }
        if (mode.equals("prepare")) {
          require(
              run == null && player.getGameplay().getCurrentMissionRaid() == 0,
              "Raid was entered before fixture preparation");
          int parent = c.get("parent").getAsInt();
          player.getGameplay().getMainMissions().put(parent, new QuestState(parent));
          if (c.has("predecessor")) {
            int id = c.get("predecessor").getAsInt();
            var definition = Objects.requireNonNull(GameplayDefinitions.subs.get(id));
            require(definition.getMainMissionID() == main, "Foreign predecessor");
            var state = new QuestState(id);
            state.advance(
                Math.max(definition.getProgress(), 1), Math.max(definition.getProgress(), 1), 1);
            player.getGameplay().getSubMissions().put(id, state);
          }
          var entry = Objects.requireNonNull(GameData.getMapEntranceExcelMap().get(2000101));
          var floor =
              Objects.requireNonNull(GameData.getFloorInfo(entry.getPlaneID(), entry.getFloorID()));
          var anchor =
              Objects.requireNonNull(
                  floor.getAnchorInfo(entry.getStartGroupID(), entry.getStartAnchorID()));
          for (var binding :
              Map.<String, Object>of(
                      "entryId",
                      entry.getId(),
                      "planeId",
                      entry.getPlaneID(),
                      "floorId",
                      entry.getFloorID(),
                      "pos",
                      anchor.getPos().clone(),
                      "rot",
                      anchor.getRot().clone(),
                      "worldLevel",
                      0)
                  .entrySet()) {
            var field = Player.class.getDeclaredField(binding.getKey());
            field.setAccessible(true);
            field.set(player, binding.getValue());
          }
          store.save(player);
          changed++;
        } else if (mode.equals("active") || mode.equals("resume") || mode.equals("close")) {
          require(
              run != null && player.getGameplay().getCurrentMissionRaid() == raid,
              "Missing active source raid");
          var state = run.getMissionTrials();
          var party = Objects.requireNonNull(state.getParty());
          require(
              party.getRaid() == raid
                  && party.getMain() == main
                  && party.getPlane() == plane
                  && party.getFloor() == c.get("floor").getAsInt()
                  && party
                      .getRoster()
                      .equals(c.get("virtual").getAsBoolean() ? List.of(trial) : expectedRoster)
                  && party.isVirtual() == c.get("virtual").getAsBoolean()
                  && party.getLeader() == 0
                  && party.getMp() == 5
                  && party.getActors().keySet().equals(Set.of(trial))
                  && party.getSources().keySet().equals(Set.of(sub))
                  && state.getExecuted().equals(party.getSources())
                  && MissionStateRecords.completed(
                      player.getGameplay().getSubMissions().get(sub), sub, 1),
              "Saved dynamic source raid party differs");
          var role = party.getActors().get(trial);
          var vital = party.getVitals().get(trial);
          require(
              !role.getSkills().isEmpty()
                  && !role.getSkillResources().isEmpty()
                  && vital.getHp() == 10000
                  && vital.getSp() == 7000,
              "Source raid skills/vitals missing");
          require(
              player.getGameplay().getMissionTrials().getParty() == null,
              "Raid wrote ordinary mission actor state");
          if (mode.equals("close")) {
            player.getGameplay().getCancelledMissions().add(main);
            store.save(player);
            changed++;
          }
        } else if (mode.equals("closed")) {
          require(
              run != null
                  && run.getMissionTrials().getParty() == null
                  && run.getMissionTrials().getExecuted().keySet().equals(Set.of(sub))
                  && player.getGameplay().getCurrentMissionRaid() == 0
                  && player.getGameplay().getCancelledMissions().contains(main),
              "Cancelled raid actor did not retire and return home");
        } else require(run == null, "Fresh player already has a raid record");
        var formal =
            store
                .getDatabase()
                .getCollection("lineups")
                .find(new org.bson.Document("ownerUid", uid).append("index", 0))
                .first();
        require(
            formal != null && formal.getList("avatars", Integer.class).equals(List.of(8001)),
            "Formal party document overwritten");
        require(
            store
                    .getDatabase()
                    .getCollection("avatars")
                    .countDocuments(
                        new org.bson.Document("ownerUid", uid).append("avatarId", trial))
                == 0,
            "Dynamic trial entered formal avatar storage");
        for (int id : trialOnly ? baseRoster : List.<Integer>of())
          require(
              store
                      .getDatabase()
                      .getCollection("avatars")
                      .countDocuments(new org.bson.Document("ownerUid", uid).append("avatarId", id))
                  == 0,
              "Original trial entered formal avatar storage");
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("uid", uid);
        snapshot.put("raid", raid);
        snapshot.put(
            "baseParty", (frozenBase || trialOnly) && run != null ? run.getTrialParty() : null);
        snapshot.put("party", run == null ? null : run.getMissionTrials().getParty());
        snapshot.put(
            "executed", run == null ? Set.of() : run.getMissionTrials().getExecuted().keySet());
        snapshot.put("formalParty", List.of(8001));
        snapshot.put("credits", 0);
        snapshot.put("hcoin", 0);
        snapshots.add(snapshot);
      }
      output.put("passed", true);
      output.put("mode", mode);
      output.put("changedPlayers", changed);
      output.put("players", snapshots);
      System.out.println(new Gson().toJson(output));
    } finally {
      server.shutdownNow();
    }
  }
}
