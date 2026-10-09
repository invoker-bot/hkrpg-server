package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.util.Position;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/** Explicit world-one fixture and read-only preference checks in an owned stopped H2 database. */
public final class StageEventDifficultyFixtureInspector {
  private static void require(boolean condition, String why) {
    if (!condition) throw new IllegalStateException(why);
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
        "Not an owned fixture");
    String mode = args[2];
    require(
        Set.of("pending", "prepare", "easy", "default", "allEasy", "allGroupedEasy").contains(mode),
        "Unknown operation");
    var resources = directory.resolve("resources-fixture");
    var marker =
        JsonParser.parseString(Files.readString(resources.resolve("resource-manifest.json")))
            .getAsJsonObject();
    require(
        marker.get("testFixture").getAsBoolean()
            && marker.get("stageEventDifficultyFixture").getAsBoolean(),
        "Missing fixture marker");
    var config = new emu.lunarcore.Config();
    config.resourceDir = resources.toString();
    config.protocol.wireVersion = "4.6.0";
    var configField = emu.lunarcore.LunarCore.class.getDeclaredField("config");
    configField.setAccessible(true);
    configField.set(null, config);
    ResourceLoader.loadResources();
    var originalConfig = Files.readAllBytes(directory.resolve("config.json"));
    var configuration =
        JsonParser.parseString(new String(originalConfig, java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
    var data =
        directory
            .resolve(
                configuration.getAsJsonObject("internalMongoServer").get("filePath").getAsString())
            .normalize();
    require(data.startsWith(directory), "Database must stay inside fixture");
    var server = new MongoServer(new H2Backend(data.toString()));
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, Position.class);
      int uid =
          JsonParser.parseString(Files.readString(credentials))
              .getAsJsonObject()
              .get("uid")
              .getAsInt();
      require(store.find(Player.class).count() == 1, "Unexpected players");
      var filter = new Document("_id", uid);
      var players = store.getDatabase().getCollection("players");
      var before = Objects.requireNonNull(players.find(filter).first());
      var player =
          Objects.requireNonNull(
              store
                  .find(Player.class)
                  .filter(dev.morphia.query.filters.Filters.eq("_id", uid))
                  .first());
      require(
          player.getWorldLevel() == (Set.of("pending", "prepare").contains(mode) ? 0 : 1),
          "Wrong actual world");
      require(
          player.getHcoin() == 0
              && player.getScoin() == 0
              && player.getGameplay().getCurrentMissionRaid() == 0
              && player.getGameplay().getRaidDifficultySettings().snapshot().isEmpty(),
          "Unexpected unrelated gameplay");
      var choices = player.getGameplay().getStageEventDifficultySettings().snapshot();
      if (mode.equals("allEasy") || mode.equals("allGroupedEasy")) {
        boolean grouped = mode.equals("allGroupedEasy");
        var proof =
            JsonParser.parseString(
                    Files.readString(
                        root.resolve(
                            grouped
                                ? "src/test/resources/fixtures/all-stage-events-4.6.json"
                                : "src/test/resources/fixtures/single-stage-events-4.6.json")))
                .getAsJsonObject();
        var expected = new TreeSet<Integer>();
        for (var event :
            proof.getAsJsonArray(grouped ? "eligibleEventIds" : "eligibleSingleEventIds"))
          expected.add(event.getAsInt());
        require(
            expected.size() == (grouped ? 57 : 28)
                && choices.stream()
                    .map(StageEventDifficultySettings.Choice::getEvent)
                    .toList()
                    .equals(new ArrayList<>(expected))
                && choices.stream()
                    .allMatch(choice -> choice.getSource() == 2 && choice.getDifficulty() == 1),
            "Full independent preference set did not restore from H2");
      } else
        require(
            Set.of("pending", "prepare").contains(mode)
                ? choices.isEmpty()
                : choices.size() == 1
                    && choices.getFirst().getEvent() == StageEventDifficultySettings.EVENT
                    && choices.getFirst().getSource() == 2
                    && choices.getFirst().getDifficulty() == (mode.equals("easy") ? 1 : 0),
            "Preference did not restore from H2");
      var lineups = store.getDatabase().getCollection("lineups");
      var formalFilter = new Document("ownerUid", uid).append("index", 0);
      var formal = Objects.requireNonNull(lineups.find(formalFilter).first());
      require(
          formal.getList("avatars", Integer.class).equals(List.of(8001)), "Formal roster changed");
      int changed = 0;
      if (mode.equals("prepare")) {
        var result = players.updateOne(filter, new Document("$set", new Document("worldLevel", 1)));
        require(
            result.getMatchedCount() == 1 && result.getModifiedCount() == 1,
            "World fixture was not installed");
        changed = 1;
        var expected = new Document(before);
        expected.put("worldLevel", 1);
        require(
            expected.equals(players.find(filter).first()),
            "World fixture changed additional fields");
      } else
        require(
            before.equals(players.find(filter).first()), "Read-only inspection modified player");
      require(
          formal.equals(lineups.find(formalFilter).first()), "Inspection changed formal lineup");
      require(
          Arrays.equals(originalConfig, Files.readAllBytes(directory.resolve("config.json"))),
          "Config changed");
      System.out.println(
          new Gson()
              .toJson(
                  Map.of(
                      "passed",
                      true,
                      "operation",
                      mode,
                      "changedPlayers",
                      changed,
                      "changedLineups",
                      0,
                      "uid",
                      uid,
                      "actualWorld",
                      mode.equals("prepare") ? 1 : player.getWorldLevel(),
                      "formalLineup",
                      formal.toJson(),
                      "sourceTwoPreferenceCount",
                      choices.size())));
    } finally {
      server.shutdownNow();
    }
  }
}
