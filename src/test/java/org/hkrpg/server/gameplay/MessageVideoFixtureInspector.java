package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;

/** Read-only reopening of the unique, marked smoke database; absent from the runtime jar. */
public final class MessageVideoFixtureInspector {
  public static void main(String[] args) throws Exception {
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
        || !marker.get("messageVideoFixture").getAsBoolean())
      throw new IllegalArgumentException("Missing video fixture marker");
    int uid = Integer.parseInt(args[1]);
    if (uid <= 0) throw new IllegalArgumentException("Invalid UID");
    var file = directory.resolve("data/database.mv").normalize();
    if (!file.startsWith(directory) || !Files.isRegularFile(file))
      throw new IllegalArgumentException("Missing fixture database");
    var backend = new H2Backend(file.toString());
    var server = new MongoServer(backend);
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var players = client.getDatabase("hkrpg").getCollection("players");
      if (players.countDocuments() != 1 || players.countDocuments(Filters.eq("_id", uid)) != 1)
        throw new IllegalStateException("Not a unique fixture player");
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var player =
          store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first();
      if (player == null || player.getScoin() != 0)
        throw new IllegalStateException("Player or wallet changed");
      var state = player.getGameplay();
      var histories = new TreeMap<Integer, List<Integer>>();
      for (var entry : marker.getAsJsonObject("paths").entrySet()) {
        int id = Integer.parseInt(entry.getKey());
        var path = new ArrayList<Integer>();
        for (var v : entry.getValue().getAsJsonArray()) path.add(v.getAsInt());
        var phone = state.getPhoneSections().get(id);
        var sub = state.getSubMissions().get(id);
        if (phone == null
            || phone.getId() != id
            || !phone.isFinished()
            || !phone.getHistory().equals(path)
            || !phone.getOffered().isEmpty()
            || sub == null
            || sub.getProgress() != 1
            || sub.getPhase() != QuestState.Phase.FINISHED)
          throw new IllegalStateException("Saved original message graph changed: " + id);
        histories.put(id, List.copyOf(phone.getHistory()));
      }
      if (histories.size() != 3
          || state.getMainMissions().get(199936).getPhase() == QuestState.Phase.DOING
          || !state.getRewards().keySet().equals(Set.of("mission:199936"))
          || state.getMissionRewardPlans().get("mission:199936") == null
          || !state.getMissionRewardPlans().get("mission:199936").getItems().isEmpty()
          || state.getRewards().get("mission:199936").getRewardId() != 0
          || !state.getRewards().get("mission:199936").isGranted())
        throw new IllegalStateException("Fixture parent or rewards changed");
      var result = new LinkedHashMap<String, Object>();
      result.put("passed", true);
      result.put("uid", uid);
      result.put("histories", histories);
      result.put("wallet", player.getScoin());
      result.put("inspectionWrites", 0);
      result.put("operatorDataModified", false);
      System.out.println(new Gson().toJson(result));
    } finally {
      server.shutdownNow();
    }
  }

  private MessageVideoFixtureInspector() {}
}
