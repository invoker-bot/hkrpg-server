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
public final class EmptyRecoveryMissionRaidFixtureInspector {
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
            && marker.get("emptyRecoveryMissionRaidFixture").getAsBoolean(),
        "Missing recovery fixture marker");
    String mode = args[2];
    require(
        Set.of(
                "pending",
                "prepare",
                "first",
                "resume",
                "completeGoals",
                "virtual",
                "virtualResume",
                "final",
                "finalResume")
            .contains(mode),
        "Unknown empty recovery inspection");
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
      if (candidate.RaidID() == 43315 && candidate.WorldLevel() == 0)
        GameplayDefinitions.raids.put(4331500L, candidate);
    }
    var row = Objects.requireNonNull(MissionRaid.resource(43315, 0));
    require(
        row.RecoverType().isEmpty()
            && row.EnterType().equals("SkipUI")
            && row.TeamType().equals("Player")
            && row.MainMissionIDList().equals(List.of(1034208)),
        "Original empty recovery row changed");
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
      require(store.find(Player.class).count() == 1, "Unexpected empty recovery player collection");
      var credentialsData = JsonParser.parseString(Files.readString(credentials)).getAsJsonObject();
      var player =
          Objects.requireNonNull(
              store
                  .find(Player.class)
                  .filter(
                      dev.morphia.query.filters.Filters.eq(
                          "_id", credentialsData.get("uid").getAsInt()))
                  .first());
      require(
          player.getScoin() == 0 && player.getHcoin() == 0 && player.getWorldLevel() == 0,
          "Unexpected empty recovery wallet/world");
      var formalFilter = new org.bson.Document("ownerUid", player.getUid()).append("index", 0);
      var formalCollection = store.getDatabase().getCollection("lineups");
      var formal = Objects.requireNonNull(formalCollection.find(formalFilter).first());
      require(
          formal.getList("avatars", Integer.class).equals(List.of(8001)),
          "Formal empty recovery party changed");
      var run = player.getGameplay().getMissionRaids().get(43315);
      int changed = 0;
      boolean actualAnchor = false;
      if (mode.equals("pending") || mode.equals("prepare")) {
        require(
            run == null && player.getGameplay().getCurrentMissionRaid() == 0,
            "Premature empty recovery entry");
        if (mode.equals("prepare")) {
          player.getGameplay().getMainMissions().put(1034207, new QuestState(1034207));
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
        require(
            run != null
                && run.getTrialParty() == null
                && run.getFormalTeamPlan() == null
                && run.getSequence() != null
                && run.getSequence().getStage() == 0
                && run.getSequence().getMains().equals(List.of(1034208))
                && run.getSequence().matches(run, row)
                && run.getSequence().consistent(player, run, row)
                && run.getTransferredSubs().isEmpty(),
            "Whole original single-main source proof changed");
        if (Set.of("first", "resume", "completeGoals").contains(mode)) {
          require(
              player.getGameplay().getCurrentMissionRaid() == 43315
                  && run.getEntry() == 43315001
                  && run.getMissionTrials().getParty() == null
                  && run.getMissionTrials().getExecuted().isEmpty()
                  && !run.isCompleted(),
              "Early empty recovery source ran");
          if (mode.equals("completeGoals")) {
            var goals = new HashSet<Integer>();
            goal(103420814, goals);
            for (int id : goals) {
              var c = GameplayDefinitions.subs.get(id);
              if (id == 103420807)
                player.getGameplay().getSubMissions().put(id, new QuestState(id));
              else completed(player, id, c.getFinishType());
            }
            require(
                !MissionStateRecords.subCompleted(player, 103420807),
                "Companion target must remain DOING for actual execution");
            store.save(player);
            changed++;
          }
        } else {
          require(
              MissionStateRecords.subCompleted(player, 103420807)
                  && MissionStateRecords.subCompleted(player, 103420827),
              "Both original instructions must complete");
          var action = player.getGameplay().getStoryActions().get(103420807);
          require(
              action != null && !action.isPending() && action.getNext() == 1,
              "Original target anchor action was not actually committed");
          var party = run.getMissionTrials().getParty();
          require(
              run.getMissionTrials().getExecuted().keySet().equals(Set.of(103420827)),
              "Composite receipt duplicated/changed");
          if (Set.of("virtual", "virtualResume").contains(mode)) {
            require(
                player.getGameplay().getCurrentMissionRaid() == 43315
                    && party != null
                    && party.getMain() == 1034208
                    && party.getRaid() == 43315
                    && party.isVirtual()
                    && party.getRoster().equals(List.of(1021317))
                    && party.getActors().keySet().equals(Set.of(1021317))
                    && party.getBaseActors().isEmpty()
                    && party.getRaidBase() == null
                    && party.getMp() == 1
                    && !run.isCompleted(),
                "Original composite party or consumed points changed");
            var special =
                new Gson()
                    .fromJson(
                        party.getActors().get(1021317).getSpecial(),
                        emu.lunarcore.data.excel.SpecialAvatarExcel.class);
            require(
                special.getAvatarID() == 1317
                    && special.getLevel() == 40
                    && special.getEquipmentID() == 23033
                    && new Gson()
                        .fromJson(
                            party.getActors().get(1021317).getSkillPlan(),
                            TrialSkillPresets.Plan.class)
                        .custom(),
                "Original custom trial source changed");
            var anchor =
                Objects.requireNonNull(GameData.getFloorInfo(43315, 43315001).getAnchorInfo(28, 1));
            require(
                player.getPos().getX() == anchor.getPos().getX()
                    && player.getPos().getY() == anchor.getPos().getY()
                    && player.getPos().getZ() == anchor.getPos().getZ()
                    && player.getRot().getY() == anchor.getRot().getY(),
                "Actual original anchor position/rotation changed");
            actualAnchor = true;
          } else {
            require(
                run.isCompleted()
                    && run.getSequence().completed(player, run, row)
                    && party == null
                    && player.getGameplay().getCurrentMissionRaid() == 0
                    && run.getFinishedReturn() != null
                    && run.getFinishedReturn().getEntry() == 2000101
                    && player.getEntryId() == 2000101,
                "Original final target/return did not retire composite party");
          }
        }
      }
      require(
          Objects.requireNonNull(formalCollection.find(formalFilter).first())
                  .getList("avatars", Integer.class)
                  .equals(List.of(8001))
              && player.getLineupManager().getMp() == (mode.equals("pending") ? 5 : 2),
          "Empty recovery changed formal team/points");
      require(
          store
                  .getDatabase()
                  .getCollection("avatars")
                  .countDocuments(
                      new org.bson.Document("ownerUid", player.getUid())
                          .append("avatarId", 1021317))
              == 0,
          "Composite trial entered formal storage");
      var output = new LinkedHashMap<String, Object>();
      output.put("passed", true);
      output.put("mode", mode);
      output.put("uid", player.getUid());
      output.put("changedPlayers", changed);
      output.put("changedLineups", 0);
      output.put("formalMp", player.getLineupManager().getMp());
      output.put("actualOriginalAnchorPositionVerified", actualAnchor);
      if (run != null) output.put("run", new Gson().toJsonTree(run));
      System.out.println(new Gson().toJson(output));
    } finally {
      server.shutdownNow();
    }
  }

  private static void goal(int id, Set<Integer> found) {
    require(found.add(id), "Repeated/cyclic original goal");
    var c = Objects.requireNonNull(GameplayDefinitions.subs.get(id));
    require(
        c.getMainMissionID() == 1034208 && Set.of("Talk", "StageWin").contains(c.getFinishType()),
        "Unexpected original earlier goal");
    if (!"Auto".equals(c.getTakeType()))
      for (int parent : c.getTakeParamIntList()) goal(parent, found);
  }
}
