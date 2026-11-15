package org.hkrpg.server.gameplay;

import static com.mongodb.client.model.Filters.*;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import emu.lunarcore.game.player.Player;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.bson.Document;

/** Durable weekly batches, independent of the owner's player document.
 * The _id unique index and revision compare-and-set cover simultaneous guest opens. */
public final class TrainCakeRoomStore {
  /** Allows an isolated player fixture or server embedding to provide its own database. */
  public interface Provider { TrainCakeRoomStore trainCakeRoomStore(); }
  private record Stored(TrainCakeRoomBatch batch, int revision) {}
  private final MongoCollection<Document> rooms;

  public TrainCakeRoomStore(MongoDatabase database) {
    rooms = Objects.requireNonNull(database).getCollection("train_cake_rooms");
  }

  public static TrainCakeRoomStore forPlayer(Player player) {
    if (player instanceof Provider provider)
      return Objects.requireNonNull(provider.trainCakeRoomStore());
    var database = Objects.requireNonNull(emu.lunarcore.LunarCore.getGameDatabase());
    return new TrainCakeRoomStore(database.getDatabase());
  }

  public TrainCakeRoomBatch open(Player owner, long now) {
    Objects.requireNonNull(owner);
    if (owner.getUid() <= 0) throw new IllegalArgumentException("Invalid room owner");
    String source = TrainCakeCatch.source();
    var legacy = TrainCakeCatch.snapshot(owner);
    for (int attempt = 0; attempt < 64; attempt++) {
      var doc = rooms.find(eq("_id", owner.getUid())).first();
      var previous = doc == null ? null : decode(doc, owner.getUid(), source);
      if (previous != null && previous.batch().refreshAt() > TrainCakeCatch.nextRefresh(now))
        throw new IllegalStateException("Room week is in the future");
      if (previous != null && previous.batch().valid(source, now)) {
        if (legacy.getRefreshAt() > now && (legacy.getRefreshAt() != previous.batch().refreshAt()
            || !sameSpots(legacy.getSpots(), previous.batch().spots())))
          throw new IllegalStateException("Owner and room batches conflict");
        return previous.batch();
      }
      if (legacy.getRefreshAt() > now && legacy.getRefreshAt() != TrainCakeCatch.nextRefresh(now))
        throw new IllegalStateException("Owner week is in the future");
      List<TrainCakeCatchState.Spot> spots = legacy.getRefreshAt() > now
          ? legacy.getSpots()
          : previous == null && legacy.getRefreshAt() == 0
              ? TrainCakeCatch.initialBatch()
              : TrainCakeCatch.randomBatch(ThreadLocalRandom.current());
      var batch = new TrainCakeRoomBatch(owner.getUid(), source, TrainCakeCatch.nextRefresh(now), spots);
      int revision = previous == null ? 1 : Math.addExact(previous.revision(), 1);
      var next = encode(batch, revision);
      if (previous == null) {
        try { rooms.insertOne(next); return batch; }
        catch (MongoWriteException conflict) {
          if (conflict.getError().getCode() != 11000) throw conflict;
        }
      } else if (rooms.replaceOne(and(eq("_id", owner.getUid()), eq("revision", previous.revision())), next)
          .getModifiedCount() == 1) return batch;
    }
    throw new IllegalStateException("Concurrent room batch updates did not settle");
  }

  /** A query never creates or refreshes a box. */
  public TrainCakeRoomBatch peek(int ownerUid, long now) {
    if (ownerUid <= 0) throw new IllegalArgumentException("Invalid room owner");
    String source = TrainCakeCatch.source();
    var doc = rooms.find(eq("_id", ownerUid)).first();
    if (doc == null) return null;
    var batch = decode(doc, ownerUid, source).batch();
    if (batch.refreshAt() > TrainCakeCatch.nextRefresh(now))
      throw new IllegalStateException("Room week is in the future");
    return batch.valid(source, now) ? batch : null;
  }

  private static boolean sameSpots(List<TrainCakeCatchState.Spot> left, List<TrainCakeCatchState.Spot> right) {
    if (left.size() != right.size()) return false;
    var a = new TreeMap<Integer, Integer>(); var b = new TreeMap<Integer, Integer>();
    left.forEach(s -> a.put(s.getCat(), s.getSlot()));
    right.forEach(s -> b.put(s.getCat(), s.getSlot()));
    return a.equals(b);
  }

  private static Document encode(TrainCakeRoomBatch batch, int revision) {
    var rows = new ArrayList<Document>();
    for (var spot : batch.spots()) rows.add(new Document("cat", spot.getCat()).append("slot", spot.getSlot()));
    return new Document("_id", batch.ownerUid()).append("source", batch.source())
        .append("refreshAt", batch.refreshAt()).append("revision", revision).append("spots", rows);
  }

  private static Stored decode(Document doc, int owner, String source) {
    try {
      if (doc.size() != 5 || doc.getInteger("_id") != owner || doc.getInteger("revision") <= 0)
        throw new IllegalArgumentException("Room metadata");
      var rows = doc.getList("spots", Document.class);
      var spots = new ArrayList<TrainCakeCatchState.Spot>();
      for (var row : rows) {
        if (row.size() != 2) throw new IllegalArgumentException("Room spot");
        spots.add(new TrainCakeCatchState.Spot(row.getInteger("cat"), row.getInteger("slot")));
      }
      var batch = new TrainCakeRoomBatch(owner, doc.getString("source"), doc.getLong("refreshAt"), spots);
      if (!batch.validShape(source)) throw new IllegalArgumentException("Room batch");
      return new Stored(batch, doc.getInteger("revision"));
    } catch (RuntimeException invalid) {
      throw new IllegalStateException("Invalid persisted train room", invalid);
    }
  }
}
