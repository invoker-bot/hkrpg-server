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

/**
 * Isolated prior acceptance and performance/battle outcomes in a marked, stopped smoke database.
 */
public final class MissionRaidSequenceFixtureInspector {
  private static void require(boolean valid, String reason) {
    if (!valid) throw new IllegalStateException(reason);
  }

  private static void completed(Player player, int id, String type) {
    var definition = Objects.requireNonNull(GameplayDefinitions.subs.get(id));
    require(
        type.equals(definition.getFinishType()) && definition.getProgress() == 1,
        "Unexpected fixture source condition");
    var state = new QuestState(id);
    state.advance(1, 1, 1);
    player.getGameplay().getSubMissions().put(id, state);
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
            && marker.get("missionRaidSequenceFixture").getAsBoolean(),
        "Missing sequence fixture marker");
    String mode = args[2];
    require(
        Set.of(
                "pending",
                "prepare",
                "first",
                "completeFirst",
                "second",
                "resume",
                "completeFinal",
                "finished",
                "final",
                "finalResume")
            .contains(mode),
        "Invalid sequence operation");
    var file = directory.resolve("data/database.mv");
    require(Files.isRegularFile(file), "Missing owned fixture H2 database");
    var server = new MongoServer(new H2Backend(file.toString()));
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, Position.class);
      require(store.find(Player.class).count() == 1, "Unexpected player collection");
      var c = JsonParser.parseString(Files.readString(credentials)).getAsJsonObject();
      var player =
          Objects.requireNonNull(
              store
                  .find(Player.class)
                  .filter(dev.morphia.query.filters.Filters.eq("_id", c.get("uid").getAsInt()))
                  .first());
      require(
          player.getScoin() == 0 && player.getHcoin() == 0, "Unexpected sequence currency reward");
      var config = new emu.lunarcore.Config();
      config.resourceDir = directory.resolve("resources-fixture").toString();
      var configField = emu.lunarcore.LunarCore.class.getDeclaredField("config");
      configField.setAccessible(true);
      configField.set(null, config);
      ResourceLoader.loadResources();
      var floors = ResourceLoader.class.getDeclaredMethod("loadFloorInfos");
      floors.setAccessible(true);
      floors.invoke(null);
      GameplayDefinitions.loadMissions(directory.resolve("resources-fixture/Config/Level/Mission"));
      var raidRows =
          JsonParser.parseString(
                  Files.readString(
                      directory.resolve("resources-fixture/ExcelOutput/RaidConfig.json")))
              .getAsJsonArray();
      var row =
          raidRows.asList().stream()
              .map(raw -> new Gson().fromJson(raw, GameplayDefinitions.Raid.class))
              .filter(raw -> raw.RaidID() == 40231001 && raw.WorldLevel() == 0)
              .findFirst()
              .orElseThrow();
      var run = player.getGameplay().getMissionRaids().get(40231001);
      int changed = 0;
      if (mode.equals("pending") || mode.equals("prepare")) {
        require(
            run == null && player.getGameplay().getCurrentMissionRaid() == 0,
            "Premature raid entry");
        if (mode.equals("prepare")) {
          player.getGameplay().getMainMissions().put(1021201, new QuestState(1021201));
          for (int id = 102130101; id <= 102130110; id++) completed(player, id, "Talk");
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
        }
      } else {
        require(
            run != null
                && run.getSequence() != null
                && run.getSequence().matches(run, row)
                && run.getSequence().consistent(player, run, row),
            "Frozen authored sequence changed");
        var base = Objects.requireNonNull(run.getTrialParty());
        require(
            base.getRoster().equals(List.of(1021002))
                && base.getConfigs().keySet().equals(Set.of(1021002))
                && base.getLeader() == 0
                && base.getMp() == 5
                && base.getVitals().get(1021002).getHp() == 10000
                && base.getVitals().get(1021002).getSp() == 7000,
            "Original TrialOnly base changed");
        if (mode.equals("first") || mode.equals("completeFirst")) {
          require(
              player.getGameplay().getCurrentMissionRaid() == 40231001
                  && run.getSequence().getStage() == 0
                  && run.getMainMission() == 1021301
                  && run.getEntry() == 40231004
                  && run.getTransferredSubs().equals(Map.of(102130113, 40231004))
                  && run.getMissionTrials().getParty() == null
                  && run.getMissionTrials().getExecuted().isEmpty()
                  && MissionStateRecords.mainDoing(player, 1021301)
                  && player.getGameplay().getMainMissions().get(1021401) == null
                  && !run.isCompleted(),
              "First stage finished early");
          if (mode.equals("completeFirst")) {
            completed(player, 102130111, "StageWin");
            completed(player, 102130112, "Talk");
            store.save(player);
            changed++;
          }
        } else {
          require(
              run.getSequence().getStage() == 1
                  && run.getMainMission() == 1021401
                  && run.getTransferredSubs()
                      .equals(Map.of(102130113, 40231004, 102140141, 40231003))
                  && MissionRaidSequence.finished(player, run, 1021301),
              "Original stage transfer proofs missing");
          var party = run.getMissionTrials().getParty();
          if (Set.of("second", "resume", "completeFinal").contains(mode)) {
            require(
                player.getGameplay().getCurrentMissionRaid() == 40231001
                    && run.getEntry() == 40231003
                    && !run.isCompleted()
                    && party != null
                    && party.getMain() == 1021401
                    && party.getRoster().equals(List.of(1021213))
                    && party.getActors().keySet().equals(Set.of(1021213))
                    && party.getBaseActors().keySet().equals(Set.of(1021002))
                    && party.getRaidBase().equals(base.frozen())
                    && party.getVitals().get(1021213).getHp() == 10000
                    && party.getVitals().get(1021213).getSp() == 7000
                    && run.getMissionTrials().getExecuted().keySet().equals(Set.of(102140142)),
                "Second replacement party lost");
            if (mode.equals("completeFinal")) {
              for (int id = 102140101; id <= 102140105; id++)
                completed(player, id, id == 102140102 ? "StageWin" : "Talk");
              store.save(player);
              changed++;
            }
          } else {
            require(
                run.isCompleted()
                    && run.getSequence().completed(player, run, row)
                    && party == null
                    && run.getMissionTrials().getExecuted().keySet().equals(Set.of(102140142)),
                "Whole raid completion proof missing");
            if (mode.equals("finished"))
              require(
                  player.getGameplay().getCurrentMissionRaid() == 40231001
                      && run.getFinishedReturn() == null,
                  "Premature finished return");
            else
              require(
                  player.getGameplay().getCurrentMissionRaid() == 0
                      && run.getFinishedReturn() != null
                      && run.getFinishedReturn().getEntry() == 2023202
                      && player.getEntryId() == 2023202,
                  "Final return receipt changed");
          }
        }
      }
      require(
          player.getGameplay().getMainMissions().get(1021501) == null,
          "Distinct after-main was auto-completed/accepted");
      var formal =
          store
              .getDatabase()
              .getCollection("lineups")
              .find(new org.bson.Document("ownerUid", player.getUid()).append("index", 0))
              .first();
      require(
          formal != null && formal.getList("avatars", Integer.class).equals(List.of(8001)),
          "Formal team document changed");
      for (int trial : List.of(1021002, 1021213, 1021204))
        require(
            store
                    .getDatabase()
                    .getCollection("avatars")
                    .countDocuments(
                        new org.bson.Document("ownerUid", player.getUid())
                            .append("avatarId", trial))
                == 0,
            "Trial entered formal storage");
      var output = new LinkedHashMap<String, Object>();
      output.put("passed", true);
      output.put("mode", mode);
      output.put("uid", player.getUid());
      output.put("changedPlayers", changed);
      output.put("credits", player.getScoin());
      output.put("formalParty", List.of(8001));
      if (run != null) output.put("run", new Gson().toJsonTree(run));
      System.out.println(new Gson().toJson(output));
    } finally {
      server.shutdownNow();
    }
  }
}
