package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/** Explicit synthetic gate preparation and independent reads, restricted to the runner's temp H2. */
public final class TrainCakeCatchFixtureInspector {
  public static void main(String[] args) throws Exception {
    var input = JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
        .getAsJsonObject();
    var directory = Path.of(input.get("directory").getAsString()).toRealPath();
    if (!directory.getFileName().toString().startsWith("train-cat-box-")
        || !directory.getParent().getFileName().toString().equals("smoke")
        || !directory.getParent().getParent().getFileName().toString().equals("build"))
      throw new IllegalArgumentException("Not an isolated cat box directory");
    var config = JsonParser.parseString(Files.readString(directory.resolve("config.json"))).getAsJsonObject();
    var mongo = config.getAsJsonObject("internalMongoServer");
    int port = mongo.get("port").getAsInt();
    if (!mongo.get("address").getAsString().equals("127.0.0.1") || port == 27017 || port == 27079
        || config.getAsJsonObject("httpServer").get("bindPort").getAsInt() == 18080
        || config.getAsJsonObject("gameServer").get("bindPort").getAsInt() == 23311)
      throw new IllegalArgumentException("Refusing an operator service");
    int uid = input.get("uid").getAsInt();
    String mode = input.get("mode").getAsString();
    if (!Set.of("unlock", "read").contains(mode) || uid <= 0) throw new IllegalArgumentException("Fixture mode");
    try (var client = MongoClients.create("mongodb://127.0.0.1:" + port + "/?directConnection=true")) {
      var players = client.getDatabase("hkrpg").getCollection("players");
      var selector = new Document("_id", uid);
      var before = players.find(selector).first();
      if (before == null) throw new IllegalStateException("Isolated player absent");
      if (mode.equals("unlock")) {
        // This is synthetic fixture setup, not evidence of playing the original introduction.
        var gate = new Document("questId", 402080005).append("progress", 1)
            .append("finishTime", System.currentTimeMillis() / 1000).append("phase", "FINISHED");
        var main = new Document("questId", 4020800).append("progress", 0)
            .append("finishTime", 0L).append("phase", "DOING");
        var open = new Document("questId", 402080008).append("progress", 0)
            .append("finishTime", 0L).append("phase", "DOING");
        var update = new Document("level", 21).append("gameplay.subMissions.402080005", gate)
            .append("gameplay.mainMissions.4020800", main).append("gameplay.subMissions.402080008", open);
        if (players.updateOne(selector, new Document("$set", update)).getMatchedCount() != 1)
          throw new IllegalStateException("Fixture player changed");
      }
      var player = players.find(selector).first();
      var gameplay = player.get("gameplay", Document.class);
      var subs = gameplay.get("subMissions", Document.class);
      var gate = subs == null ? null : subs.get("402080005", Document.class);
      var open = subs == null ? null : subs.get("402080008", Document.class);
      var dialogue = subs == null ? null : subs.get("402080006", Document.class);
      var result = new Document("passed", true).append("mode", mode).append("level", player.get("level"))
          .append("introGatePhase", gate == null ? null : gate.get("phase"))
          .append("boxGoalPhase", open == null ? null : open.get("phase"))
          .append("nextDialoguePhase", dialogue == null ? null : dialogue.get("phase"))
          .append("catBox", gameplay.get("trainCakeCatch"))
          .append("syntheticGatePreparation", mode.equals("unlock"));
      System.out.println(result.toJson());
    }
  }
  private TrainCakeCatchFixtureInspector() {}
}
