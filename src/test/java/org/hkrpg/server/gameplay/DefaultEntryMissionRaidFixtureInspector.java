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
 * Prior acceptance, source-script acceptance and dialogue/battle results in an owned stopped DB.
 */
public final class DefaultEntryMissionRaidFixtureInspector {
  private static void require(boolean valid, String reason) {
    if (!valid) throw new IllegalStateException(reason);
  }

  private static void completed(Player player, int id, String type) {
    var sub = Objects.requireNonNull(GameplayDefinitions.subs.get(id));
    require(
        type.equals(sub.getFinishType()) && sub.getProgress() == 1,
        "Unexpected recovery fixture goal");
    var state = new QuestState(id);
    state.advance(1, 1, 1);
    player.getGameplay().getSubMissions().put(id, state);
  }

  public static void main(String[] args) throws Exception {
    require(args.length == 3, "Expected owned directory, credentials, operation");
    var root = Path.of("").toRealPath();
    var directory = Path.of(args[0]).toRealPath();
    var credentials = Path.of(args[1]).toRealPath();
    require(
        directory.startsWith(root.resolve("build/smoke"))
            && !directory.equals(root.resolve("build/smoke"))
            && credentials.startsWith(directory),
        "Not an owned recovery fixture");
    var marker =
        JsonParser.parseString(
                Files.readString(directory.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    require(
        marker.get("testFixture").getAsBoolean()
            && marker.get("defaultEntryMissionRaidFixture").getAsBoolean(),
        "Missing recovery fixture marker");
    String mode = args[2];
    require(
        Set.of(
                "pending",
                "prepare",
                "first",
                "resume",
                "completeFirst",
                "second",
                "complete102",
                "virtual",
                "virtualResume",
                "completeFinal",
                "final",
                "finalResume")
            .contains(mode),
        "Unknown Default inspection");
    var sourceConfig = new emu.lunarcore.Config();
    sourceConfig.resourceDir = directory.resolve("resources-fixture").toString();
    var configField = emu.lunarcore.LunarCore.class.getDeclaredField("config");
    configField.setAccessible(true);
    configField.set(null, sourceConfig);
    ResourceLoader.loadResources();
    var floors = ResourceLoader.class.getDeclaredMethod("loadFloorInfos");
    floors.setAccessible(true);
    floors.invoke(null);
    GameplayDefinitions.loadMissions(directory.resolve("resources-fixture/Config/Level/Mission"));
    for (var raw :
        JsonParser.parseString(
                Files.readString(
                    directory.resolve("resources-fixture/ExcelOutput/RaidConfig.json")))
            .getAsJsonArray()) {
      var candidate = new Gson().fromJson(raw, GameplayDefinitions.Raid.class);
      if (candidate.RaidID() == 43306 && candidate.WorldLevel() == 0)
        GameplayDefinitions.raids.put(4330600L, candidate);
    }
    var row = Objects.requireNonNull(MissionRaid.resource(43306, 0));
    require(
        row.EnterType() == null
            && row.TeamType().equals("Player")
            && row.RecoverType().equals(List.of("Unknown"))
            && row.MainMissionIDList().equals(List.of(1032201, 1031901)),
        "Original Default row changed");
    var rawEntrance =
        JsonParser.parseString(
                Files.readString(
                    directory.resolve("resources-fixture/ExcelOutput/RaidConfig.json")))
            .getAsJsonArray();
    var original =
        java.util.stream.StreamSupport.stream(rawEntrance.spliterator(), false)
            .map(JsonElement::getAsJsonObject)
            .filter(
                r ->
                    r.get("RaidID").getAsInt() == 43306
                        && (!r.has("WorldLevel") || r.get("WorldLevel").getAsInt() == 0))
            .findFirst()
            .orElseThrow();
    require(
        !original.has("EnterType") && original.get("DifficultyAdjustmentType").getAsInt() == 1,
        "Omitted Default/difficulty source changed");
    var configuration =
        JsonParser.parseString(Files.readString(directory.resolve("config.json")))
            .getAsJsonObject();
    var db = configuration.getAsJsonObject("internalMongoServer");
    var backend = new H2Backend(directory.resolve(db.get("filePath").getAsString()).toString());
    var server = new MongoServer(backend);
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, Position.class);
      require(store.find(Player.class).count() == 1, "Unexpected Default player collection");
      var c = JsonParser.parseString(Files.readString(credentials)).getAsJsonObject();
      int actualWorld =
          marker.has("raidDifficultyFixtureActualWorld")
              ? marker.get("raidDifficultyFixtureActualWorld").getAsInt()
              : marker.has("raidDifficultyWorldOneFixture")
                      && marker.get("raidDifficultyWorldOneFixture").getAsBoolean()
                  ? 1
                  : 0;
      boolean adjustedWorld = actualWorld > 0;
      require(actualWorld >= 0 && actualWorld <= 6, "Unknown fixture world tier");
      boolean unselected =
          marker.has("initialRaidDifficultySelectionOmitted")
              && marker.get("initialRaidDifficultySelectionOmitted").getAsBoolean();
      require(!unselected || adjustedWorld, "Unselected fixture must use world one");
      var player =
          Objects.requireNonNull(
              store
                  .find(Player.class)
                  .filter(dev.morphia.query.filters.Filters.eq("_id", c.get("uid").getAsInt()))
                  .first());
      require(
          player.getScoin() == 0
              && player.getHcoin() == 0
              && player.getWorldLevel()
                  == (adjustedWorld && !Set.of("pending", "prepare").contains(mode)
                      ? actualWorld
                      : 0),
          "Unexpected Default reward/world");
      int selectedDifficulty =
          marker.has("selectedRaidDifficulty")
              ? marker.get("selectedRaidDifficulty").getAsInt()
              : 1;
      var formalFilter = new org.bson.Document("ownerUid", player.getUid()).append("index", 0);
      var formalCollection = store.getDatabase().getCollection("lineups");
      var formal = Objects.requireNonNull(formalCollection.find(formalFilter).first());
      require(
          formal.getList("avatars", Integer.class).equals(List.of(8001)),
          "Formal Default party changed");
      var run = player.getGameplay().getMissionRaids().get(43306);
      int changed = 0;
      if (mode.equals("pending") || mode.equals("prepare")) {
        require(
            run == null && player.getGameplay().getCurrentMissionRaid() == 0,
            "Premature Default entry");
        if (mode.equals("prepare")) {
          if (adjustedWorld) player.setWorldLevel(actualWorld);
          player.getGameplay().getMainMissions().put(1031801, new QuestState(1031801));
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
                      anchor.getRot().clone())
                  .entrySet()) {
            var field = Player.class.getDeclaredField(binding.getKey());
            field.setAccessible(true);
            field.set(player, binding.getValue());
          }
          var points = emu.lunarcore.game.player.lineup.LineupManager.class.getDeclaredField("mp");
          points.setAccessible(true);
          points.setInt(player.getLineupManager(), 2);
          store.save(player);
          changed++;
        }
      } else {
        if (adjustedWorld) {
          var difficulty = run.getDifficultySnapshot();
          require(
              difficulty != null
                  && difficulty.getRaid() == 43306
                  && difficulty.getActualWorld() == actualWorld
                  && difficulty.getEffectiveWorld() == actualWorld - selectedDifficulty
                  && difficulty.getDifficulty() == selectedDifficulty
                  && difficulty.getResourceIdentity().equals(RaidDifficultySettings.identity(row)),
              "Frozen selected world changed");
          var choices = player.getGameplay().getRaidDifficultySettings().snapshot();
          boolean switched =
              Set.of("virtual", "virtualResume", "completeFinal", "final", "finalResume")
                  .contains(mode);
          int selected =
              Set.of("virtual", "virtualResume", "completeFinal", "final", "finalResume")
                      .contains(mode)
                  ? 1 - selectedDifficulty
                  : selectedDifficulty;
          require(
              unselected && !switched
                  ? choices.isEmpty()
                  : choices.size() == 1
                      && choices.getFirst().getRaid() == 43306
                      && choices.getFirst().getSource() == 1
                      && choices.getFirst().getDifficulty() == selected,
              "Saved preference changed frozen selected run");
        } else
          require(run.getDifficultySnapshot() == null, "Unexpected default difficulty snapshot");
        require(
            run != null
                && run.getSequence() != null
                && run.getSequence().matches(run, row)
                && run.getSequence().consistent(player, run, row)
                && run.getTrialParty() == null
                && run.getFormalTeamPlan() == null,
            "Default source/formal proof changed");
        if (Set.of("first", "resume", "completeFirst").contains(mode)) {
          require(
              player.getGameplay().getCurrentMissionRaid() == 43306
                  && run.getSequence().getStage() == 0
                  && run.getMainMission() == 1032201
                  && run.getEntry() == 43306001
                  && run.getTransferredSubs().isEmpty()
                  && run.getMissionTrials().getParty() == null
                  && !run.isCompleted(),
              "First Default stage changed");
          if (mode.equals("completeFirst")) {
            completed(player, 103220101, "Talk");
            completed(player, 103220102, "Talk");
            completed(player, 103220103, "StageWin");
            completed(player, 103220104, "Talk");
            store.save(player);
            changed++;
          }
        } else {
          require(
              run.getSequence().getStage() == 1
                  && run.getMainMission() == 1031901
                  && run.getTransferredSubs().equals(Map.of(103220105, 43306002))
                  && MissionRaidSequence.finished(player, run, 1032201),
              "Original Default transfer missing");
          var party = run.getMissionTrials().getParty();
          if (Set.of("second", "complete102").contains(mode)) {
            require(
                player.getGameplay().getCurrentMissionRaid() == 43306
                    && run.getEntry() == 43306002
                    && party == null
                    && run.getMissionTrials().getExecuted().isEmpty()
                    && !MissionStateRecords.subCompleted(player, 103190102)
                    && !run.isCompleted(),
                "Default virtual source ran before original Talk dependency");
            if (mode.equals("complete102")) {
              completed(player, 103190102, "Talk");
              store.save(player);
              changed++;
            }
          } else if (Set.of("virtual", "virtualResume", "completeFinal").contains(mode)) {
            require(
                player.getGameplay().getCurrentMissionRaid() == 43306
                    && run.getEntry() == 43306002
                    && party != null
                    && party.getMain() == 1031901
                    && party.getRoster().equals(List.of(1021304))
                    && party.getActors().keySet().equals(Set.of(1021304))
                    && party.getBaseActors().isEmpty()
                    && party.getRaidBase() == null
                    && party.getMp() == 2
                    && party.isVirtual()
                    && run.getMissionTrials().getExecuted().keySet().equals(Set.of(103190112))
                    && MissionStateRecords.subCompleted(player, 103190112)
                    && !run.isCompleted(),
                "Default source virtual party changed");
            var frozenActor = Objects.requireNonNull(party.getActors().get(1021304));
            var originalTrial =
                Objects.requireNonNull(
                    GameData.getSpecialAvatarExcelMap().get(1021304, actualWorld));
            require(
                frozenActor.getSpecial().equals(new com.google.gson.Gson().toJson(originalTrial)),
                "Frozen trial is not the actual-world original row");
            if (mode.equals("completeFinal")) {
              for (int sub :
                  List.of(
                      103190107, 103190108, 103190109, 103190110, 103190111, 103190103, 103190104,
                      103190105, 103190106)) completed(player, sub, "Talk");
              store.save(player);
              changed++;
            }
          } else {
            require(
                run.isCompleted()
                    && run.getSequence().completed(player, run, row)
                    && party == null
                    && run.getMissionTrials().getExecuted().keySet().equals(Set.of(103190112))
                    && player.getGameplay().getCurrentMissionRaid() == 0
                    && run.getFinishedReturn() != null
                    && run.getFinishedReturn().getEntry() == 2031101
                    && player.getEntryId() == 2031101,
                "Whole Default completion/declared return changed");
          }
        }
      }
      formal = Objects.requireNonNull(formalCollection.find(formalFilter).first());
      require(
          formal.getList("avatars", Integer.class).equals(List.of(8001))
              && player.getLineupManager().getMp() == (mode.equals("pending") ? 5 : 2),
          "Formal points changed during Default raid");
      require(
          store
                  .getDatabase()
                  .getCollection("avatars")
                  .countDocuments(
                      new org.bson.Document("ownerUid", player.getUid())
                          .append("avatarId", 1021304))
              == 0,
          "Default trial entered formal storage");
      var output = new LinkedHashMap<String, Object>();
      output.put("passed", true);
      output.put("mode", mode);
      output.put("uid", player.getUid());
      output.put("changedPlayers", changed);
      output.put("changedLineups", 0);
      output.put("credits", player.getScoin());
      output.put("formalParty", List.of(8001));
      output.put("formalMp", player.getLineupManager().getMp());
      output.put("actualWorld", player.getWorldLevel());
      output.put("initialDifficultySelectionOmitted", unselected);
      output.put("nativeDifficultyWorldOneFixture", adjustedWorld && actualWorld == 1);
      output.put("fixtureActualWorld", actualWorld);
      output.put(
          "nativeEasyWorldFixture", adjustedWorld && actualWorld == 1 && selectedDifficulty == 1);
      if (run != null) output.put("run", new Gson().toJsonTree(run));
      System.out.println(new Gson().toJson(output));
    } finally {
      server.shutdownNow();
    }
  }
}
