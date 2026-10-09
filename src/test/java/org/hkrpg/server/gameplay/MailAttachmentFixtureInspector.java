package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.mail.Mail;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/** Reads only a marked, stopped smoke fixture. This class never enters the server jar. */
public final class MailAttachmentFixtureInspector {
  public static void main(String[] args) throws Exception {
    var root = Path.of("").toRealPath();
    var directory = Path.of(args[0]).toRealPath();
    if (!directory.startsWith(root.resolve("build/smoke"))
        || directory.equals(root.resolve("build/smoke")))
      throw new IllegalArgumentException("Not an isolated smoke fixture");
    var marker =
        JsonParser.parseString(
                Files.readString(directory.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    if (!marker.get("testFixture").getAsBoolean()
        || !marker.get("mailAttachmentFixture").getAsBoolean())
      throw new IllegalArgumentException("Mail fixture marker missing");
    int uid = Integer.parseInt(args[1]), wallet = Integer.parseInt(args[3]);
    boolean deleted = args[2].equals("deleted");
    if (uid <= 0 || !(deleted || args[2].equals("claimed")))
      throw new IllegalArgumentException("Invalid inspection");
    var path = directory.resolve("data/database.mv").normalize();
    if (!path.startsWith(directory) || !Files.isRegularFile(path))
      throw new IllegalArgumentException("Existing fixture file missing");
    var server = new MongoServer(new H2Backend(path.toString()));
    server.bind("127.0.0.1", 0);
    try (var client = MongoClients.create(server.getConnectionString())) {
      var database = client.getDatabase("hkrpg");
      var player = database.getCollection("players").find(Filters.eq("_id", uid)).first();
      if (player == null || player.getInteger("scoin") != wallet)
        throw new IllegalStateException("Saved wallet changed");
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Mail.class, GameItem.class, GameItemSubAffix.class);
      var mails =
          store
              .find(Mail.class)
              .filter(dev.morphia.query.filters.Filters.eq("ownerUid", uid))
              .iterator()
              .toList();
      if (mails.size() != (deleted ? 0 : 1))
        throw new IllegalStateException("Saved mail count changed");
      var output = new LinkedHashMap<String, Object>();
      output.put("passed", true);
      output.put("uid", uid);
      output.put("wallet", wallet);
      output.put("mailCount", mails.size());
      output.put("mode", args[2]);
      if (!deleted) {
        var mail = mails.getFirst();
        var receipt = mail.getAttachmentClaim();
        var expected = Map.of(2, 1000, 235, 3, 20000, 2);
        if (!mail.isRead()
            || receipt == null
            || receipt.getPhase() != MailAttachmentClaim.Phase.CLAIMED
            || !receipt.getExpected().equals(expected)
            || !receipt.getReceived().equals(expected)
            || receipt.getReceivedUniqueIds().size() != 2)
          throw new IllegalStateException("Saved actual claim receipt changed");
        var attachments = new TreeMap<Integer, Integer>();
        for (var item : mail.getAttachments()) {
          if (item.getOwnerUid() != 0)
            throw new IllegalStateException("Mail's immutable copy acquired inventory ownership");
          attachments.merge(item.getItemId(), item.getCount(), Math::addExact);
        }
        if (!attachments.equals(expected))
          throw new IllegalStateException("Original attachment plan changed");
        output.put("mailObjectId", mail.getId().toHexString());
        output.put("originalAttachments", attachments);
        output.put("phase", receipt.getPhase());
        output.put("expected", receipt.getExpected());
        output.put("received", receipt.getReceived());
        output.put("receivedUniqueIds", receipt.getReceivedUniqueIds());
      }
      var items =
          database
              .getCollection("items")
              .find(Filters.eq("ownerUid", uid))
              .into(new ArrayList<Document>());
      if (items.stream()
                  .filter(row -> row.getInteger("itemId") == 20000 && row.getInteger("count") == 1)
                  .count()
              != 2
          || items.stream()
                  .filter(row -> row.getInteger("itemId") == 235 && row.getInteger("count") == 3)
                  .count()
              != 1) throw new IllegalStateException("Saved actual inventory changed");
      output.put("inventoryRows", items.size());
      output.put("inspectionWrites", 0);
      output.put("operatorDataModified", false);
      System.out.println(new Gson().toJson(output));
    } finally {
      server.shutdownNow();
    }
  }

  private MailAttachmentFixtureInspector() {}
}
