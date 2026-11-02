package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;
import org.bson.types.ObjectId;

/** Seed or inspect only a marked, stopped mail-duplicate smoke database. */
public final class MailDuplicateFixture {
  public static void main(String[] args) throws Exception {
    var directory = Path.of(args[0]).toRealPath();
    if (!directory.getFileName().toString().startsWith("mail-duplicate-")
        || !directory.getParent().getFileName().toString().equals("smoke")
        || !Files.readString(directory.resolve("mail-duplicate.marker"))
            .strip().equals("isolated fixture")
        || Files.exists(directory.resolve("server-running.marker")))
      throw new IllegalArgumentException(
          "Only a marked, stopped, isolated mail fixture is supported");
    var config =
        JsonParser.parseString(Files.readString(directory.resolve("config.json")))
            .getAsJsonObject();
    int port = config.getAsJsonObject("internalMongoServer").get("port").getAsInt();
    if (port == 27079 || port < 1024)
      throw new IllegalArgumentException("Production endpoint refused");
    boolean seed = args[1].equals("seed");
    if (!seed && !args[1].equals("verify")) throw new IllegalArgumentException("Unknown operation");
    int uid = Integer.parseInt(args[2]);
    var path = directory.resolve("data/database.mv");
    if (!Files.isRegularFile(path))
      throw new IllegalArgumentException("Existing fixture database required");
    var server = new MongoServer(new H2Backend(path.toString()));
    server.bind("127.0.0.1", 0);
    try (var client = MongoClients.create(server.getConnectionString())) {
      var db = client.getDatabase("hkrpg");
      var query = new Document("ownerUid", uid);
      var mails = db.getCollection("mail").find(query).into(new ArrayList<>());
      if (mails.size() != 1
          || !mails.getFirst().getString("title").equals("Welcome to a LunarCore server"))
        throw new IllegalStateException("Expected one isolated default welcome mail");
      if (seed) {
        var phase = mails.getFirst().get("attachmentClaim", Document.class);
        if (phase == null || !phase.getString("phase").equals("READY"))
          throw new IllegalStateException("Fresh mail required");
        var updated =
            db.getCollection("players")
                .updateOne(
                    new Document("_id", uid), new Document("$set", new Document("scoin", 1234)));
        if (updated.getMatchedCount() != 1)
          throw new IllegalStateException("Initialized fixture player missing");
        for (int id : List.of(1001, 1002)) {
          if (db.getCollection("avatars")
                  .countDocuments(new Document("ownerUid", uid).append("avatarId", id))
              != 0) throw new IllegalStateException("Expected an unowned isolated starter");
          db.getCollection("avatars")
              .insertOne(
                  new Document("_id", new ObjectId())
                      .append("ownerUid", uid)
                      .append("avatarId", id)
                      .append("level", 1)
                      .append("data", new Document("rank", 0).append("skillTree", new Document())));
        }
        Map.of(101, 7, 102, 9, 252, 11, 11001, 6, 11002, 6)
            .forEach(
                (id, count) -> {
                  if (db.getCollection("items")
                          .countDocuments(new Document("ownerUid", uid).append("itemId", id))
                      != 0) throw new IllegalStateException("Expected an empty isolated item pile");
                  db.getCollection("items")
                      .insertOne(
                          new Document("_id", new ObjectId())
                              .append("ownerUid", uid)
                              .append("itemId", id)
                              .append("count", count));
                });
      }
      var wallet =
          db.getCollection("players").find(new Document("_id", uid)).first().getInteger("scoin");
      if (wallet != (seed ? 1234 : 1001234))
        throw new IllegalStateException("Saved wallet changed");
      var actual = new TreeMap<Integer, Integer>();
      for (var row : db.getCollection("items").find(query))
        actual.merge(row.getInteger("itemId"), row.getInteger("count"), Math::addExact);
      if (!actual.equals(
          Map.of(
              101, seed ? 7 : 107, 102, seed ? 9 : 109, 252, seed ? 11 : 51, 11001, 6, 11002, 6)))
        throw new IllegalStateException("Saved inventory changed: " + actual);
      var mail = db.getCollection("mail").find(query).first();
      var original = new TreeMap<Integer, Integer>();
      for (var row : mail.getList("attachments", Document.class)) {
        if (row.getInteger("ownerUid", 0) != 0)
          throw new IllegalStateException("Mail copy acquired ownership");
        original.merge(row.getInteger("itemId"), row.getInteger("count"), Math::addExact);
      }
      if (!original.equals(Map.of(2, 1000000, 101, 100, 102, 100, 1001, 1, 1002, 1)))
        throw new IllegalStateException("Original welcome attachments changed");
      var receipt = mail.get("attachmentClaim", Document.class);
      if (!receipt.getString("phase").equals(seed ? "READY" : "CLAIMED"))
        throw new IllegalStateException("Saved phase changed");
      if (!seed) {
        var expected =
            new Document("2", 1000000).append("101", 100).append("102", 100).append("252", 40);
        if (!receipt.get("expected").equals(expected) || !receipt.get("received").equals(expected))
          throw new IllegalStateException("Converted receipt changed");
      }
      var avatars = new TreeMap<Integer, String>();
      for (var row : db.getCollection("avatars").find(query)) {
        if (Set.of(1001, 1002).contains(row.getInteger("avatarId"))) {
          if (row.get("data", Document.class).getInteger("rank", 0) != 0)
            throw new IllegalStateException("Owned rank changed");
          avatars.put(row.getInteger("avatarId"), row.getObjectId("_id").toHexString());
        }
      }
      if (avatars.size() != 2) throw new IllegalStateException("Formal starter characters missing");
      System.out.println(
          new Gson()
              .toJson(
                  Map.of(
                      "passed",
                      true,
                      "mode",
                      args[1],
                      "uid",
                      uid,
                      "wallet",
                      wallet,
                      "items",
                      actual,
                      "mailObjectId",
                      mail.getObjectId("_id").toHexString(),
                      "avatarObjectIds",
                      avatars,
                      "inspectionWrites",
                      seed ? 8 : 0)));
    } finally {
      server.shutdownNow();
    }
  }
}
