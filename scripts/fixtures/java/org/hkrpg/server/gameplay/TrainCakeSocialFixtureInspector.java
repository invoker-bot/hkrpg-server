package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/** Synthetic unlock setup and independent reads; refuses operator databases and ports. */
public final class TrainCakeSocialFixtureInspector {
  public static void main(String[] args) throws Exception {
    var input = JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).getAsJsonObject();
    var directory = Path.of(input.get("directory").getAsString()).toRealPath();
    if (!directory.getFileName().toString().startsWith("train-cat-social-")
        || !directory.getParent().getFileName().toString().equals("smoke")
        || !directory.getParent().getParent().getFileName().toString().equals("build"))
      throw new IllegalArgumentException("Not an isolated social test directory");
    var config = JsonParser.parseString(Files.readString(directory.resolve("config.json"))).getAsJsonObject();
    var mongo = config.getAsJsonObject("internalMongoServer"); int port = mongo.get("port").getAsInt();
    if (!mongo.get("address").getAsString().equals("127.0.0.1") || port == 27017 || port == 27079
        || config.getAsJsonObject("httpServer").get("bindPort").getAsInt() == 18080
        || config.getAsJsonObject("gameServer").get("bindPort").getAsInt() == 23311)
      throw new IllegalArgumentException("Refusing an operator service");
    int uid = input.get("uid").getAsInt(); String mode = input.get("mode").getAsString();
    if (uid <= 0 || !Set.of("unlock", "read").contains(mode)) throw new IllegalArgumentException("Fixture mode");
    try (var client = MongoClients.create("mongodb://127.0.0.1:" + port + "/?directConnection=true")) {
      var players = client.getDatabase("hkrpg").getCollection("players"); var selector = new Document("_id", uid);
      if (players.find(selector).first() == null) throw new IllegalStateException("Synthetic player absent");
      if (mode.equals("unlock")) {
        long now = System.currentTimeMillis() / 1000;
        var main = new Document("questId", 4020800).append("progress", 1).append("finishTime", now).append("phase", "FINISHED");
        var sub = new Document("questId", 402080005).append("progress", 1).append("finishTime", now).append("phase", "FINISHED");
        var update = new Document("level", 21).append("gameplay.mainMissions.4020800", main)
            .append("gameplay.subMissions.402080005", sub);
        if (players.updateOne(selector, new Document("$set", update)).getMatchedCount() != 1)
          throw new IllegalStateException("Synthetic player changed");
      }
      var player = players.find(selector).first(); var gameplay = player.get("gameplay", Document.class);
      var result = new Document("passed", true).append("mode", mode).append("uid", uid)
          .append("planeId", player.get("planeId")).append("floorId", player.get("floorId"))
          .append("entryId", player.get("entryId")).append("catBox", gameplay.get("trainCakeCatch"))
          .append("visitorCaptures", gameplay.get("trainCakeVisits"))
          .append("returnBookmark", gameplay.get("trainCakeReturnLocation"))
          .append("permissions", gameplay.get("trainCakeSocialSettings"))
          .append("syntheticGatePreparation", mode.equals("unlock"));
      System.out.println(result.toJson());
    }
  }
  private TrainCakeSocialFixtureInspector() {}
}
