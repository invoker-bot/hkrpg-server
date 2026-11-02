package org.hkrpg.server.protocol;

import com.google.gson.JsonParser;
import com.mongodb.client.MongoClients;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bson.Document;

/** Isolated smoke-directory fixture only: seed real reserve, or inspect saved conservation. */
public final class CocoonRetryStaminaFixture {
  public static void main(String[] args) throws Exception {
    var directory = Path.of(args[0]).toRealPath();
    if (!directory.getFileName().toString().startsWith("cocoon-retry-")
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
        query.append("quickStaminaRecovery", true).append("stamina", 300);
        var result = players.updateOne(query, new Document("$set", new Document("staminaReserve", 394.5)));
        if (result.getModifiedCount() != 1) throw new IllegalStateException("Isolated fixture did not match initialized player");
      } else if (!args[1].equals("verify")) throw new IllegalArgumentException("Unknown fixture operation");
      var row = players.find(new Document("_id", uid))
          .projection(new Document("_id", 1).append("stamina", 1).append("staminaReserve", 1)
              .append("quickStaminaRecovery", 1)).first();
      if (row == null) throw new IllegalStateException("Player missing");
      int expectedStamina = args[1].equals("seed") ? 300 : 0;
      double expectedReserve = args[1].equals("seed") ? 394.5 : 214.5;
      if (row.getInteger("stamina") != expectedStamina
          || ((Number) row.get("staminaReserve")).doubleValue() != expectedReserve
          || !row.getBoolean("quickStaminaRecovery"))
        throw new IllegalStateException("Saved refill, preference, or settlement conservation mismatch");
      System.out.println(row.toJson());
    }
  }
}
