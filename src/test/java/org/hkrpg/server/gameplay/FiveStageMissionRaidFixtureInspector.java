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
public final class FiveStageMissionRaidFixtureInspector {
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

  private static void closure(int id, Set<Integer> found) {
    if (!found.add(id)) return;
    var sub = Objects.requireNonNull(GameplayDefinitions.subs.get(id));
    if (!"Auto".equals(sub.getTakeType()) && sub.getTakeParamIntList() != null)
      for (int gate : sub.getTakeParamIntList()) closure(gate, found);
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
            && marker.get("fiveStageMissionRaidFixture").getAsBoolean(),
        "Missing sequence fixture marker");
    String mode = args[2];
    require(
        Set.of(
                "pending",
                "prepare",
                "first",
                "completeStage0",
                "second",
                "completeStage1",
                "third",
                "acceptAlias",
                "alias",
                "acceptReplacement",
                "replacement",
                "completeStage2",
                "fourth",
                "completeStage3",
                "fifth",
                "completeStage4",
                "final",
                "finalResume")
            .contains(mode),
        "Unknown five-stage fixture operation");
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
              .filter(raw -> raw.RaidID() == 43201 && raw.WorldLevel() == 0)
              .findFirst()
              .orElseThrow();
      var run = player.getGameplay().getMissionRaids().get(43201);
      int changed = 0;
      if (mode.equals("pending") || mode.equals("prepare")) {
        require(
            run == null && player.getGameplay().getCurrentMissionRaid() == 0,
            "Premature raid entry");
        if (mode.equals("prepare")) {
          player.getGameplay().getMainMissions().put(2020501, new QuestState(2020501));
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
            "Original five-stage sequence changed");
        var base = Objects.requireNonNull(run.getTrialParty());
        require(
            base.getRoster().equals(List.of(8001, 1101208))
                && base.getConfigs().keySet().equals(Set.of(1101208))
                && base.getVitals().get(1101208).getHp() == 10000
                && base.getVitals().get(1101208).getSp() == 7000,
            "Original mixed entry team changed");
        int stage =
            switch (mode) {
              case "second", "completeStage1" -> 1;
              case "third",
                      "acceptAlias",
                      "alias",
                      "acceptReplacement",
                      "replacement",
                      "completeStage2" ->
                  2;
              case "fourth", "completeStage3" -> 3;
              case "fifth", "completeStage4", "final", "finalResume" -> 4;
              default -> 0;
            };
        require(
            run.getSequence().getStage() == stage && run.getMainMission() == 2020502 + stage,
            "Stage ordinal differs");
        var transfers = new TreeMap<Integer, Integer>();
        for (int i = 0; i < stage; i++)
          transfers.put(new int[] {202050211, 202050310, 202050409, 202050505}[i], 43201002 + i);
        require(run.getTransferredSubs().equals(transfers), "Actual transfer proofs differ");
        boolean finished = mode.equals("final") || mode.equals("finalResume");
        require(
            run.isCompleted() == finished
                && (finished
                    ? player.getGameplay().getCurrentMissionRaid() == 0
                    : player.getGameplay().getCurrentMissionRaid() == 43201),
            "Premature entire raid completion");
        if (finished)
          require(
              run.getFinishedReturn() != null
                  && run.getFinishedReturn().getEntry() == 2000101
                  && player.getEntryId() == 2000101
                  && run.getSequence().completed(player, run, row),
              "Final return proof lost");
        else
          require(
              run.getEntry() == 43201001 + stage && player.getEntryId() == run.getEntry(),
              "Wrong saved stage location");
        var party = run.getMissionTrials().getParty();
        boolean alias = Set.of("alias", "acceptReplacement").contains(mode);
        boolean replacement = Set.of("replacement", "completeStage2").contains(mode);
        if (alias || replacement) {
          require(
              party != null
                  && party.getMain() == 2020504
                  && party.getEntryRoster().equals(List.of(8001, 1101208))
                  && party.getRoster().equals(List.of(alias ? 1101208 : 1101001))
                  && party.getBaseActors().keySet().equals(Set.of(1101208))
                  && party.getRaidBase().equals(base.frozen()),
              "Original role entry binding changed");
          require(
              party.getActors().keySet().equals(alias ? Set.of() : Set.of(1101001))
                  && run.getMissionTrials()
                      .getExecuted()
                      .keySet()
                      .equals(alias ? Set.of(202050410) : Set.of(202050410, 202050412)),
              "Alias/replacement receipts differ");
          require(
              MissionStateRecords.subCompleted(player, 202050410)
                  && MissionStateRecords.subCompleted(player, 202050412) == replacement,
              "Original role condition differs");
        } else require(party == null, "Unexpected source role outside its main");
        if (mode.equals("third"))
          require(
              player.getGameplay().getSubMissions().get(202050410) == null
                  && player.getGameplay().getSubMissions().get(202050412) == null,
              "Optional roles were automatically accepted");
        if (mode.equals("acceptAlias") || mode.equals("acceptReplacement")) {
          int id = mode.equals("acceptAlias") ? 202050410 : 202050412;
          require(player.getGameplay().getSubMissions().get(id) == null, "Role already accepted");
          player.getGameplay().getSubMissions().put(id, new QuestState(id));
          store.save(player);
          changed++;
        }
        if (mode.startsWith("completeStage")) {
          require(mode.equals("completeStage" + stage), "Wrong isolated outcome stage");
          var goals = new HashSet<Integer>();
          for (int id :
              GameData.getMissionInfos().get(run.getMainMission()).getFinishSubMissionList())
            closure(id, goals);
          for (int id : goals) {
            var sub = GameplayDefinitions.subs.get(id);
            if ("EnterRaidScene".equals(sub.getFinishType())) continue;
            require(
                Set.of("Talk", "StageWin", "SubMissionFinishCnt").contains(sub.getFinishType()),
                "Unknown isolated outcome");
            completed(player, id, sub.getFinishType());
          }
          store.save(player);
          changed++;
        }
      }
      require(
          MissionStateRecords.mainDoing(player, 2020501) || mode.equals("pending"),
          "Parent fixture was completed");
      var formal =
          store
              .getDatabase()
              .getCollection("lineups")
              .find(new org.bson.Document("ownerUid", player.getUid()).append("index", 0))
              .first();
      require(
          formal != null && formal.getList("avatars", Integer.class).equals(List.of(8001)),
          "Formal team document changed");
      for (int trial : List.of(1101208, 1101001))
        require(
            store
                    .getDatabase()
                    .getCollection("avatars")
                    .countDocuments(
                        new org.bson.Document("ownerUid", player.getUid())
                            .append("avatarId", trial))
                == 0,
            "Trial entered formal storage");
      var result = new LinkedHashMap<String, Object>();
      result.put("passed", true);
      result.put("mode", mode);
      result.put("uid", player.getUid());
      result.put("changedPlayers", changed);
      result.put("credits", player.getScoin());
      result.put("formalParty", List.of(8001));
      if (run != null) result.put("run", new Gson().toJsonTree(run));
      System.out.println(new Gson().toJson(result));
    } finally {
      server.shutdownNow();
    }
  }
}
