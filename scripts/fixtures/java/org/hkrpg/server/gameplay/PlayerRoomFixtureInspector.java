package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;

/** Read and prepare only the disposable player-room H2 fixture. */
public final class PlayerRoomFixtureInspector {
  public static void main(String[] args) throws Exception {
    var input = JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).getAsJsonObject();
    var directory = Path.of(input.get("directory").getAsString()).toRealPath();
    if (!directory.getFileName().toString().startsWith("player-room-")
        || !directory.getParent().getFileName().toString().equals("smoke")
        || !directory.getParent().getParent().getFileName().toString().equals("build"))
      throw new IllegalArgumentException("Not an isolated player room directory");
    var config = JsonParser.parseString(Files.readString(directory.resolve("config.json"))).getAsJsonObject();
    var mongo = config.getAsJsonObject("internalMongoServer");
    int port = mongo.get("port").getAsInt();
    if (!mongo.get("address").getAsString().equals("127.0.0.1") || port == 27017 || port == 27079
        || !config.getAsJsonObject("httpServer").get("bindAddress").getAsString().equals("127.0.0.1")
        || config.getAsJsonObject("httpServer").get("bindPort").getAsInt() == 18080
        || !config.getAsJsonObject("gameServer").get("bindAddress").getAsString().equals("127.0.0.1")
        || config.getAsJsonObject("gameServer").get("bindPort").getAsInt() == 23311)
      throw new IllegalArgumentException("Refusing an operator service");
    int uid = input.get("uid").getAsInt();
    String mode = input.get("mode").getAsString();
    if (!Set.of("prepare", "read").contains(mode) || uid <= 0) throw new IllegalArgumentException("Fixture mode");
    try (var client = MongoClients.create("mongodb://127.0.0.1:" + port + "/?directConnection=true")) {
      var db = client.getDatabase("hkrpg");
      var players = db.getCollection("players");
      var selector = new Document("_id", uid);
      if (players.find(selector).first() == null) throw new IllegalStateException("Isolated player absent");
      if (mode.equals("prepare")) {
        if (players.updateOne(selector, new Document("$set", new Document("level", 21))).getMatchedCount() != 1)
          throw new IllegalStateException("Fixture player changed");
        var items = db.getCollection("items");
        var query = new Document("ownerUid", uid).append("itemId", 295603);
        if (items.countDocuments(query) != 0) throw new IllegalStateException("Fixture item already exists");
        // Synthetic inventory setup is confined to this disposable database, not a gameplay reward.
        items.insertOne(new Document("_id", new ObjectId()).append("ownerUid", uid)
            .append("itemId", 295603).append("count", 1));
      }
      var player = players.find(selector).first();
      var gameplay = player.get("gameplay", Document.class);
      var cabinet = gameplay == null ? null : gameplay.get("playerRoomDisplay", Document.class);
      var item = db.getCollection("items").find(new Document("ownerUid", uid).append("itemId", 295603)).first();
      var result = new Document("passed", true).append("mode", mode).append("level", player.get("level"))
          .append("cabinet", cabinet).append("fixtureItemCount", item == null ? 0 : item.getInteger("count"))
          .append("syntheticPreparation", mode.equals("prepare"));
      System.out.println(result.toJson());
    }
  }
  private PlayerRoomFixtureInspector() {}
}
