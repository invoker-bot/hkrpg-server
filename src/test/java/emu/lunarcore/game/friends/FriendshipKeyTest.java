package emu.lunarcore.game.friends;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.game.player.Player;
import org.junit.jupiter.api.Test;

class FriendshipKeyTest {
  Player player(int id) throws Exception {
    var p = new Player();
    var uid = Player.class.getDeclaredField("uid");
    uid.setAccessible(true);
    uid.setInt(p, id);
    return p;
  }

  @Test
  void constructorUsesTheAssignedFriendUidInTheSavedIdentity() throws Exception {
    var owner = player(10001);
    var target = player(10002);
    var outgoing = new Friendship(owner, target, owner);
    var incoming = new Friendship(target, owner, owner);
    assertEquals(Friendship.generateUniqueKey(10001, 10002), outgoing.getId());
    assertEquals(Friendship.generateUniqueKey(10002, 10001), incoming.getId());
    assertNotEquals(outgoing.getId(), incoming.getId());
  }

  @Test
  void twoFriendshipsForOneOwnerRemainDistinctInTheDatabase() throws Exception {
    var owner = player(10001);
    var first = new Friendship(owner, player(10002), owner);
    var second = new Friendship(owner, player(10003), owner);
    first.setFriend(true);
    second.setFriend(true);
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "friendkeys");
      store.getMapper().map(Friendship.class);
      store.save(first);
      store.save(second);
      assertEquals(2, store.find(Friendship.class).count(), "第二个好友关系不能覆盖同一玩家的第一个关系");
      assertEquals(
          10002,
          store
              .find(Friendship.class)
              .filter(
                  dev.morphia.query.filters.Filters.eq(
                      "_id", Friendship.generateUniqueKey(10001, 10002)))
              .first()
              .getFriendUid());
    } finally {
      mongo.shutdownNow();
    }
  }
}
