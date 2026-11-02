package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;
import org.bson.Document;

/** Explicit prerequisite fixtures and independent reads; operator databases are never accepted. */
public final class MusicRewardsFixtureInspector {
  public static void main(String[] args) throws Exception {
    var input = JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).getAsJsonObject();
    var directory = Path.of(input.get("directory").getAsString()).toRealPath();
    if (!directory.getFileName().toString().startsWith("music-performance-")
        || !directory.getParent().getFileName().toString().equals("smoke")
        || !directory.getParent().getParent().getFileName().toString().equals("build"))
      throw new IllegalArgumentException("Not an isolated music performance directory");
    var marker = JsonParser.parseString(Files.readString(directory.resolve("music-fixture.json"))).getAsJsonObject();
    int uid = input.get("uid").getAsInt();
    if (!marker.get("testFixture").getAsBoolean() || marker.get("uid").getAsInt() != uid)
      throw new IllegalArgumentException("Missing owned fixture identity");
    var config = JsonParser.parseString(Files.readString(directory.resolve("config.json"))).getAsJsonObject();
    var mongo = config.getAsJsonObject("internalMongoServer"); int port = mongo.get("port").getAsInt();
    if (!mongo.get("address").getAsString().equals("127.0.0.1") || Set.of(27017, 27079).contains(port)
        || config.getAsJsonObject("httpServer").get("bindPort").getAsInt() == 18080
        || config.getAsJsonObject("gameServer").get("bindPort").getAsInt() == 23311)
      throw new IllegalArgumentException("Refusing an operator service");
    String mode = input.get("mode").getAsString();
    if (!Set.of("read", "prepare-locked", "prepare-qualified").contains(mode)) throw new IllegalArgumentException("Unknown fixture mode");
    try (var client = MongoClients.create("mongodb://127.0.0.1:" + port + "/?directConnection=true")) {
      var db = client.getDatabase("hkrpg"); var players = db.getCollection("players");
      if (players.countDocuments() != 1 || db.getCollection("accounts").countDocuments() != 1)
        throw new IllegalArgumentException("Expected exactly one disposable account and player");
      var filter = new Document("_id", uid);
      if (!mode.equals("read")) {
        var update = new Document("level", mode.equals("prepare-locked") ? 10 : 21)
            .append("entryId", 1030617).append("planeId", 20101).append("floorId", 20101001)
            .append("pos", new Document("x", 18380).append("y", -467).append("z", -88929));
        for (int main : new int[]{1000510, 8026101, 8026103})
          update.append("gameplay.mainMissions." + main, complete(main, "CLOSED"));
        update.append("gameplay.subMissions.802610307", complete(802610307, "FINISHED"));
        if (players.updateOne(filter, new Document("$set", update)).getMatchedCount() != 1)
          throw new IllegalStateException("Fixture player absent");
      }
      var player = players.find(filter).first(); var gameplay = player.get("gameplay", Document.class);
      var offerings = gameplay.get("offerings", Document.class);
      var result = new Document("passed", true).append("uid", uid).append("mode", mode)
          .append("level", player.get("level")).append("rhythm", gameplay.get("rhythm"))
          .append("offering", offerings == null ? null : offerings.get("5"))
          .append("hcoin", player.get("hcoin")).append("scoin", player.get("scoin")).append("exp", player.get("exp"))
          .append("syntheticPrerequisites", !mode.equals("read"));
      for (int id : new int[]{270003, 223}) {
        var item = db.getCollection("items").find(new Document("ownerUid", uid).append("itemId", id)).first();
        result.append("item" + id, item == null ? 0 : item.getInteger("count"));
      }
      System.out.println(result.toJson());
    }
  }
  private static Document complete(int id, String phase) {
    return new Document("questId", id).append("progress", 1).append("finishTime", 123L).append("phase", phase);
  }
}
