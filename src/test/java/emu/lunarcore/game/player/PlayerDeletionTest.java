package emu.lunarcore.game.player;

import static org.junit.jupiter.api.Assertions.*;

import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.database.DatabaseManager;
import java.lang.reflect.Field;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;

class PlayerDeletionTest {
  MongoServer backend;
  DatabaseManager database;
  final Map<Field, Object> saved = new HashMap<>();

  @BeforeEach
  void setup() throws Exception {
    for (String name : List.of("config", "gameDatabase")) {
      var field = LunarCore.class.getDeclaredField(name);
      field.setAccessible(true);
      saved.put(field, field.get(null));
    }
    setGlobal("config", new Config());
    backend = new MongoServer(new MemoryBackend());
    var address = backend.bind();
    var info = new Config.DatabaseInfo();
    info.useInternal = false;
    info.uri = "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true";
    database = new DatabaseManager(info, LunarCore.ServerType.GAME);
    setGlobal("gameDatabase", database);
  }

  void setGlobal(String name, Object value) throws Exception {
    var field = saved.keySet().stream().filter(f -> f.getName().equals(name)).findFirst().orElseThrow();
    field.set(null, value);
  }

  @AfterEach
  void cleanup() throws Exception {
    if (database != null) database.close();
    if (backend != null) backend.shutdownNow();
    for (var entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
  }

  Player player(int uid) throws Exception {
    var player = new Player();
    var field = Player.class.getDeclaredField("uid");
    field.setAccessible(true);
    field.setInt(player, uid);
    return player;
  }

  @Test
  void deletingAPlayerRemovesPersistedUnlocksWithoutTouchingAnotherPlayer() throws Exception {
    var target = player(10001);
    var other = player(10002);
    // Persist through Morphia so the test exercises @Id ownerUid -> Mongo _id.
    var targetUnlocks = new PlayerUnlockData(target);
    var otherUnlocks = new PlayerUnlockData(other);
    var unlocks = database.getDatabase().getCollection("unlocks");
    assertEquals(2, unlocks.countDocuments());
    var oldOther = unlocks.find(new Document("_id", other.getUid())).first();
    assertNotNull(oldOther);
    assertFalse(oldOther.containsKey("ownerUid"));
    assertEquals(target.getUid(), targetUnlocks.getOwnerUid());
    assertEquals(other.getUid(), otherUnlocks.getOwnerUid());

    target.delete();

    assertEquals(0, unlocks.countDocuments(new Document("_id", target.getUid())));
    assertEquals(1, unlocks.countDocuments());
    assertEquals(oldOther, unlocks.find(new Document("_id", other.getUid())).first());
  }

  @Test
  void deletingAPlayerRemovesOwnedProgressAndInventoryButKeepsOtherOwners() throws Exception {
    var target = player(10001);
    var db = database.getDatabase();
    var oldOther = new TreeMap<String, Document>();
    for (String name : List.of("avatars", "challenge", "challengeReward", "multiPaths",
        "items", "lineups", "lineupsExtra", "mail")) {
      var collection = db.getCollection(name);
      collection.insertOne(new Document("_id", "target").append("ownerUid", 10001));
      var preserved = new Document("_id", "other").append("ownerUid", 10002);
      collection.insertOne(preserved);
      oldOther.put(name, preserved);
    }
    var players = db.getCollection("players");
    players.insertOne(new Document("_id", 10001).append("gameplay",
        new Document("storyValues", new Document("profile:character-path", 8002))
            .append("mainMissions", new Document("1000101", new Document("phase", "CLOSED")))));
    var other = new Document("_id", 10002).append("accountUid", "preserved-account");
    players.insertOne(other);

    target.delete();
    target.delete();

    for (var entry : oldOther.entrySet()) {
      var collection = db.getCollection(entry.getKey());
      assertEquals(1, collection.countDocuments(), entry.getKey());
      assertEquals(entry.getValue(), collection.find().first(), entry.getKey());
    }
    assertEquals(1, players.countDocuments());
    assertEquals(other, players.find().first());
  }

  @Test
  void deletingAPlayerRemovesBothFriendshipDirectionsAndKeepsUnrelatedEdges() throws Exception {
    var friendships = database.getDatabase().getCollection("friendships");
    friendships.insertOne(new Document("_id", 1L).append("ownerUid", 10001).append("friendUid", 10002));
    friendships.insertOne(new Document("_id", 2L).append("ownerUid", 10002).append("friendUid", 10001));
    var preserved = new Document("_id", 3L).append("ownerUid", 10002).append("friendUid", 10003);
    friendships.insertOne(preserved);

    player(10001).delete();

    assertEquals(1, friendships.countDocuments());
    assertEquals(preserved, friendships.find().first());
  }
}
