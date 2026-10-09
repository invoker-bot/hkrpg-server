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

/** Offline operations are confined to a marked smoke database after its server stops normally. */
public final class MissionTrialFixtureInspector {
  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException("Expected fixture, uid, operation");
    var root = Path.of("").toRealPath();
    var directory = Path.of(args[0]).toRealPath();
    if (!directory.startsWith(root.resolve("build/smoke"))
        || directory.equals(root.resolve("build/smoke")))
      throw new IllegalArgumentException("Not an owned smoke directory");
    var marker =
        JsonParser.parseString(
                Files.readString(directory.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    if (!marker.get("testFixture").getAsBoolean()
        || !marker.get("missionTrialFixture").getAsBoolean())
      throw new IllegalArgumentException("Missing mission trial fixture marker");
    String mode = args[2];
    boolean removalFixture =
        marker.has("missionTrialRemovalFixture")
            && marker.get("missionTrialRemovalFixture").getAsBoolean();
    int uid = Integer.parseInt(args[1]);
    if (uid <= 0
        || !Set.of(
                "pending",
                "prepare",
                "active",
                "resume",
                "close",
                "closed",
                "remove",
                "removed",
                "removedResume")
            .contains(mode)
        || Set.of("remove", "removed", "removedResume").contains(mode) && !removalFixture)
      throw new IllegalArgumentException("Invalid fixture operation");
    if (removalFixture && !mode.equals("prepare")) {
      var config = new emu.lunarcore.Config();
      config.resourceDir = directory.resolve("resources-fixture").toString();
      var field = emu.lunarcore.LunarCore.class.getDeclaredField("config");
      field.setAccessible(true);
      field.set(null, config);
      ResourceLoader.loadResources();
      var loadFloors = ResourceLoader.class.getDeclaredMethod("loadFloorInfos");
      loadFloors.setAccessible(true);
      loadFloors.invoke(null);
      GameplayDefinitions.loadMissions(directory.resolve("resources-fixture/Config/Level/Mission"));
    }
    var file = directory.resolve("data/database.mv");
    if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Missing fixture database");
    var server = new MongoServer(new H2Backend(file.toString()));
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, Position.class);
      if (store.find(Player.class).count() != 1)
        throw new IllegalStateException("Not a unique fixture player");
      var player =
          Objects.requireNonNull(
              store
                  .find(Player.class)
                  .filter(dev.morphia.query.filters.Filters.eq("_id", uid))
                  .first());
      var gson = new Gson();
      var state = player.getGameplay().getMissionTrials();
      var party = state.getParty();
      if (player.getHcoin() != 0 || player.getScoin() != 0)
        throw new IllegalStateException("Task unexpectedly granted currency");
      int changed = 0;
      if (mode.equals("prepare")) {
        if (party != null || !state.getExecuted().isEmpty())
          throw new IllegalStateException("Already prepared player");
        var config = new emu.lunarcore.Config();
        config.resourceDir = directory.resolve("resources-fixture").toString();
        var field = emu.lunarcore.LunarCore.class.getDeclaredField("config");
        field.setAccessible(true);
        field.set(null, config);
        ResourceLoader.loadResources();
        var loadFloors = ResourceLoader.class.getDeclaredMethod("loadFloorInfos");
        loadFloors.setAccessible(true);
        loadFloors.invoke(null);
        GameplayDefinitions.loadMissions(
            directory.resolve("resources-fixture/Config/Level/Mission"));
        var c = Objects.requireNonNull(GameplayDefinitions.subs.get(100010110));
        var entry = Objects.requireNonNull(GameData.getMapEntranceExcelMap().get(2000101));
        var floor =
            Objects.requireNonNull(GameData.getFloorInfo(entry.getPlaneID(), entry.getFloorID()));
        var anchor =
            Objects.requireNonNull(
                floor.getAnchorInfo(entry.getStartGroupID(), entry.getStartAnchorID()));
        if (c.getLevelPlaneID() != entry.getPlaneID() || c.getLevelFloorID() != entry.getFloorID())
          throw new IllegalStateException("Original trial floor changed");
        for (var binding :
            Map.<String, Object>of(
                    "planeId",
                    entry.getPlaneID(),
                    "floorId",
                    entry.getFloorID(),
                    "entryId",
                    entry.getId(),
                    "pos",
                    anchor.getPos().clone(),
                    "rot",
                    anchor.getRot().clone(),
                    "worldLevel",
                    0)
                .entrySet()) {
          var f = Player.class.getDeclaredField(binding.getKey());
          f.setAccessible(true);
          f.set(player, binding.getValue());
        }
        player.getGameplay().getMainMissions().put(1000101, new QuestState(1000101));
        player.getGameplay().getSubMissions().put(100010110, new QuestState(100010110));
        store.save(player);
        changed = 1;
      } else if (mode.equals("remove")) {
        if (party == null
            || !state.getRemovals().isEmpty()
            || !state.getExecuted().keySet().equals(Set.of(100010110))
            || !MissionStateRecords.subCompleted(player, 100010110)
            || player.getGameplay().getSubMissions().containsKey(100010123))
          throw new IllegalStateException("Removal dialogue fixture has no owned original trial");
        var talk = new QuestState(100010116);
        talk.advance(1, 1, 100);
        player.getGameplay().getSubMissions().put(100010116, talk);
        store.save(player);
        changed = 1;
      } else if (mode.equals("removed") || mode.equals("removedResume")) {
        if (party != null
            || !state.getRemovals().keySet().equals(Set.of(100010123))
            || !state.getExecuted().keySet().equals(Set.of(100010110))
            || !MissionStateRecords.subCompleted(player, 100010123)
            || !MissionStateRecords.doing(player.getMissionManager().sub(100010117), 100010117, 1)
            || !MissionStateRecords.mainDoing(player, 1000101)
            || player.getGameplay().getCancelledMissions().contains(1000101))
          throw new IllegalStateException("Original removal and successor receipt differs");
        var receipt = state.getRemovals().get(100010123);
        if (!receipt.getCreations().equals(state.getExecuted()))
          throw new IllegalStateException("Removal lost the original creation proof");
        var source = JsonParser.parseString(receipt.getSource()).getAsJsonObject();
        var original =
            JsonParser.parseString(
                Files.readString(
                    directory.resolve(
                        "resources-fixture/Config/Level/Mission/1000101/Mission_100010123.json")));
        if (!source.get("wholeTask").equals(original))
          throw new IllegalStateException("Removal original whole graph changed");
      } else if (mode.equals("close")) {
        if (party == null
            || !MissionStateRecords.completed(
                player.getGameplay().getSubMissions().get(100010110), 100010110, 1))
          throw new IllegalStateException("Missing actual completed trial task");
        player.getGameplay().getCancelledMissions().add(1000101);
        store.save(player);
        changed = 1;
      } else if (mode.equals("active") || mode.equals("resume")) {
        if (party == null
            || party.getMain() != 1000101
            || party.getPlane() != 20001
            || party.getFloor() != 20001001
            || !party.getRoster().equals(List.of(8001, 1001006))
            || party.getLeader() != 0
            || party.getMp() != 5
            || party.isVirtual()
            || !party.getActors().keySet().equals(Set.of(1001006))
            || !party.getSources().keySet().equals(Set.of(100010110))
            || !state.getExecuted().equals(party.getSources())
            || !MissionStateRecords.completed(
                player.getGameplay().getSubMissions().get(100010110), 100010110, 1))
          throw new IllegalStateException("Saved actual source mission trial differs");
        var role = party.getActors().get(1001006);
        var vital = party.getVitals().get(1001006);
        if (role.getSkills().isEmpty()
            || role.getSkillResources().isEmpty()
            || vital == null
            || vital.getHp() != 10000
            || vital.getSp() != 7000)
          throw new IllegalStateException("Frozen trial skills/vitals missing");
      } else if (mode.equals("closed")) {
        if (party != null
            || !state.getExecuted().keySet().equals(Set.of(100010110))
            || !player.getGameplay().getCancelledMissions().contains(1000101))
          throw new IllegalStateException("Trial cleanup did not persist");
      } else if (party != null || !state.getExecuted().isEmpty())
        throw new IllegalStateException("Fresh player already has a mission trial");
      var normal =
          store
              .getDatabase()
              .getCollection("lineups")
              .find(new org.bson.Document("ownerUid", uid).append("index", 0))
              .first();
      if (normal == null || !normal.getList("avatars", Integer.class).equals(List.of(8001)))
        throw new IllegalStateException("Formal team document was overwritten");
      if (store
              .getDatabase()
              .getCollection("avatars")
              .countDocuments(new org.bson.Document("ownerUid", uid).append("avatarId", 1001006))
          != 0) throw new IllegalStateException("Trial entered formal avatar storage");
      var output = new LinkedHashMap<String, Object>();
      output.put("passed", true);
      output.put("mode", mode);
      output.put("uid", uid);
      output.put("changedPlayers", changed);
      output.put("party", party);
      output.put("executed", state.getExecuted().keySet());
      output.put("removals", state.getRemovals().keySet());
      output.put("formalParty", List.of(8001));
      output.put("credits", 0);
      output.put("hcoin", 0);
      System.out.println(gson.toJson(output));
    } finally {
      server.shutdownNow();
    }
  }

  private MissionTrialFixtureInspector() {}
}
