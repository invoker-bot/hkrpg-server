package org.hkrpg.server.protocol;

import com.google.gson.JsonParser;
import com.mongodb.client.MongoClients;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bson.Document;

/** Isolated smoke-directory fixture only: seed jade/power, or inspect saved purchases. */
public final class JadeStaminaPurchaseFixture {
  public static void main(String[] args) throws Exception {
    var directory = Path.of(args[0]).toRealPath();
    if (!directory.getFileName().toString().startsWith("jade-stamina-")
        || !directory.getParent().getFileName().toString().equals("smoke"))
      throw new IllegalArgumentException("Only an isolated smoke directory is supported");
    var config = JsonParser.parseString(Files.readString(directory.resolve("config.json"))).getAsJsonObject();
    var mongo = config.getAsJsonObject("internalMongoServer");
    int port = mongo.get("port").getAsInt();
    if (port < 1024 || port == 27079 || !mongo.get("address").getAsString().equals("127.0.0.1"))
      throw new IllegalArgumentException("Isolated database endpoint required");
    int uid = Integer.parseInt(args[2]);
    try (var client = MongoClients.create("mongodb://127.0.0.1:" + port + "/?serverSelectionTimeoutMS=5000")) {
      var players = client.getDatabase("hkrpg").getCollection("players");
      var query = new Document("_id", uid);
      if (args[1].equals("seed")) {
        var initial = players.find(query).first();
        if (initial == null || initial.getInteger("staminaPurchaseTimes", 0) != 0)
          throw new IllegalStateException("Expected an initialized player with no purchases");
        var result = players.updateOne(query, new Document("$set", new Document("hcoin", 1000)
            .append("stamina", 0).append("staminaReserve", 0.0)));
        if (result.getModifiedCount() != 1) throw new IllegalStateException("Isolated fixture did not match initialized player");
      } else if (!args[1].equals("verify")) throw new IllegalArgumentException("Unknown fixture operation");
      var row = players.find(new Document("_id", uid))
          .projection(new Document("_id", 1).append("stamina", 1).append("staminaReserve", 1)
              .append("hcoin", 1).append("staminaPurchaseDay", 1).append("staminaPurchaseTimes", 1)).first();
      if (row == null) throw new IllegalStateException("Player missing");
      int expectedStamina = args[1].equals("seed") ? 0 : 480;
      int expectedJade = args[1].equals("seed") ? 1000 : 100;
      int expectedTimes = args[1].equals("seed") ? 0 : 8;
      if (row.getInteger("stamina") != expectedStamina || row.getInteger("hcoin") != expectedJade
          || row.getInteger("staminaPurchaseTimes", 0) != expectedTimes
          || ((Number) row.get("staminaReserve")).doubleValue() != 0)
        throw new IllegalStateException("Saved jade, power, reserve, or quota mismatch");
      System.out.println(row.toJson());
    }
  }
}
