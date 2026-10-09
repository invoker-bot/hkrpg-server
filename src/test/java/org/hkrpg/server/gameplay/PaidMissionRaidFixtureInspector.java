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
public final class PaidMissionRaidFixtureInspector {
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
            && marker.get("paidMissionRaidFixture").getAsBoolean(),
        "Missing sequence fixture marker");
    String mode = args[2];
    require(
        Set.of(
                "pending",
                "prepare",
                "entry",
                "acceptSource",
                "source",
                "resume",
                "completeFinal",
                "final",
                "finalResume")
            .contains(mode),
        "Invalid paid raid operation");
    var file = directory.resolve("data/database.mv");
    require(Files.isRegularFile(file), "Missing owned fixture H2 database");
    var server = new MongoServer(new H2Backend(file.toString()));
    var address = server.bind();
    var codecs =
        org.bson.codecs.configuration.CodecRegistries.fromRegistries(
            org.bson.codecs.configuration.CodecRegistries.fromCodecs(
                new emu.lunarcore.database.codecs.IntSetCodec(),
                new emu.lunarcore.database.codecs.IntListCodec(),
                new emu.lunarcore.database.codecs.Int2IntMapCodec()),
            com.mongodb.MongoClientSettings.getDefaultCodecRegistry());
    try (var client =
        MongoClients.create(
            com.mongodb.MongoClientSettings.builder()
                .applyConnectionString(
                    new com.mongodb.ConnectionString(
                        "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true"))
                .codecRegistry(codecs)
                .build())) {
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              Player.class,
              QuestState.class,
              Position.class,
              emu.lunarcore.game.avatar.GameAvatar.class);
      require(store.find(Player.class).count() == 2, "Unexpected player collection");
      var c = JsonParser.parseString(Files.readString(credentials)).getAsJsonObject();
      var player =
          Objects.requireNonNull(
              store
                  .find(Player.class)
                  .filter(dev.morphia.query.filters.Filters.eq("_id", c.get("uid").getAsInt()))
                  .first());
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
              .filter(raw -> raw.RaidID() == 4000301 && raw.WorldLevel() == 0)
              .findFirst()
              .orElseThrow();
      boolean formalChoice = c.get("formal").getAsBoolean();
      var run = player.getGameplay().getMissionRaids().get(4000301);
      int changed = 0;
      var lineups = store.getDatabase().getCollection("lineups");
      var lineupFilter = new org.bson.Document("ownerUid", player.getUid()).append("index", 0);
      if (mode.equals("pending") || mode.equals("prepare")) {
        require(
            run == null && player.getGameplay().getCurrentMissionRaid() == 0,
            "Premature raid entry");
        if (mode.equals("prepare")) {
          player.getGameplay().getMainMissions().put(2000702, new QuestState(2000702));
          if (formalChoice) {
            var formal =
                new emu.lunarcore.game.avatar.GameAvatar(GameData.getAvatarExcelMap().get(1006));
            formal.setOwner(player);
            store.save(formal);
            require(
                lineups
                        .updateOne(
                            lineupFilter,
                            new org.bson.Document(
                                "$set", new org.bson.Document("avatars", List.of(1006))))
                        .getModifiedCount()
                    == 1,
                "Formal roster fixture not applied");
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
        }
      } else {
        require(
            run != null
                && run.getSequence() == null
                && run.getMainMission() == 2000703
                && run.getPayout() != null
                && run.getPayout().matches(row),
            "Original frozen payout changed");
        require(
            run.getPayout().getRewards().equals(List.of(130007))
                && run.getPayout().getItems().equals(Map.of(2, 30000, 213, 3, 223, 3)),
            "Payout quantities changed");
        if (formalChoice)
          require(run.getTrialParty() == null, "Formal alternative became an entry trial");
        else {
          var base = Objects.requireNonNull(run.getTrialParty());
          require(
              base.getRoster().equals(List.of(1101006))
                  && base.getConfigs().keySet().equals(Set.of(1101006))
                  && base.getVitals().get(1101006).getHp() == 10000
                  && base.getVitals().get(1101006).getSp() == 7000,
              "Entry trial changed");
        }
        if (Set.of("entry", "acceptSource").contains(mode)) {
          require(
              player.getGameplay().getCurrentMissionRaid() == 4000301
                  && !run.isCompleted()
                  && run.getMissionTrials().getParty() == null
                  && player.getMissionManager().sub(200070318) == null,
              "Optional role was created before acceptance");
          if (mode.equals("acceptSource")) {
            player.getGameplay().getSubMissions().put(200070318, new QuestState(200070318));
            store.save(player);
            changed++;
          }
        } else if (Set.of("source", "resume", "completeFinal").contains(mode)) {
          require(
              player.getGameplay().getCurrentMissionRaid() == 4000301
                  && !run.isCompleted()
                  && MissionStateRecords.subCompleted(player, 200070318),
              "Source objective was lost");
          var party = run.getMissionTrials().getParty();
          if (formalChoice)
            require(
                party != null
                    && party.getMain() == 2000703
                    && party.getRoster().equals(List.of(1006, 1101006))
                    && party.getActors().keySet().equals(Set.of(1101006))
                    && party.getBaseActors().isEmpty()
                    && party.getRaidBase() == null
                    && run.getMissionTrials().getExecuted().keySet().equals(Set.of(200070318)),
                "Authored formal/trial append changed");
          else
            require(
                party == null && run.getMissionTrials().getExecuted().isEmpty(),
                "Already provided trial was created twice");
          if (mode.equals("completeFinal")) {
            for (int id :
                List.of(301, 303, 304, 305, 306, 307, 310, 311, 313, 314, 315, 316, 317)) {
              var definition = Objects.requireNonNull(GameplayDefinitions.subs.get(200070000 + id));
              require(
                  Set.of("Talk", "KillMonster", "PropState").contains(definition.getFinishType()),
                  "Unknown isolated outcome");
              completed(player, definition.getId(), definition.getFinishType());
            }
            store.save(player);
            changed++;
          }
        } else {
          require(
              run.isCompleted()
                  && run.getPayout().isReserved()
                  && run.getPayout().isPaid()
                  && run.getPayout().settled(player, run, row)
                  && MissionRaidSequence.finished(player, run, 2000703),
              "Missing finished payout proof");
          require(
              player.getGameplay().getCurrentMissionRaid() == 0
                  && run.getFinishedReturn() != null
                  && run.getFinishedReturn().getEntry() == 2000101
                  && player.getEntryId() == 2000101
                  && run.getMissionTrials().getParty() == null,
              "Paid final return changed");
        }
      }
      boolean paid = Set.of("final", "finalResume").contains(mode);
      require(
          player.getScoin() == (paid ? 30000 : 0) && player.getHcoin() == 0,
          "Real saved wallet differs");
      var amounts = new TreeMap<Integer, Integer>();
      for (var item :
          store
              .getDatabase()
              .getCollection("items")
              .find(new org.bson.Document("ownerUid", player.getUid()))) {
        int id = item.getInteger("itemId");
        if (id == 213 || id == 223) {
          require(!amounts.containsKey(id), "Duplicate reward item documents");
          amounts.put(id, item.getInteger("count"));
        }
      }
      require(
          amounts.equals(paid ? Map.of(213, 3, 223, 3) : Map.of()),
          "Actual item documents differ: " + amounts);
      var formal = lineups.find(lineupFilter).first();
      var expectedFormal = formalChoice && !mode.equals("pending") ? List.of(1006) : List.of(8001);
      require(
          formal != null && formal.getList("avatars", Integer.class).equals(expectedFormal),
          "Formal lineup document changed");
      require(
          store
                  .getDatabase()
                  .getCollection("avatars")
                  .countDocuments(
                      new org.bson.Document("ownerUid", player.getUid())
                          .append("avatarId", 1101006))
              == 0,
          "Trial entered formal storage");
      var result = new LinkedHashMap<String, Object>();
      result.put("passed", true);
      result.put("mode", mode);
      result.put("formal", formalChoice);
      result.put("uid", player.getUid());
      result.put("changedPlayers", changed);
      result.put("credits", player.getScoin());
      result.put("materials", amounts);
      result.put("formalParty", expectedFormal);
      if (run != null) result.put("run", new Gson().toJsonTree(run));
      System.out.println(new Gson().toJson(result));
    } finally {
      server.shutdownNow();
    }
  }
}
