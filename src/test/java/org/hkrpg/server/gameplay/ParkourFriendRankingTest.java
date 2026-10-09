package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.game.friends.Friendship;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class ParkourFriendRankingTest {
  static final int FIRST = 19993601, A = 123456784, B = 123456785, C = 123456786;
  de.bwaldvogel.mongo.MongoServer mongo;
  com.mongodb.client.MongoClient client;
  dev.morphia.Datastore store;
  Player a, b, c;

  Player racer(int uid, int time) throws Exception {
    var p = new Player();
    var id = Player.class.getDeclaredField("uid");
    id.setAccessible(true);
    id.setInt(p, uid);
    for (int level = FIRST; level < FIRST + 3; level++)
      p.getGameplay()
          .getParkour()
          .getRecords()
          .computeIfAbsent(level, k -> new GameplayState.ParkourRecord())
          .record(time, 1, true, 1);
    return p;
  }

  Friendship link(Player from, Player to, boolean accepted) {
    var link = new Friendship(from, to, a);
    link.setFriend(accepted);
    store.save(link);
    return link;
  }

  @BeforeEach
  void setup() throws Exception {
    GameplayDefinitions.parkourRankings.clear();
    var levels = new ArrayList<GameplayDefinitions.ParkourRankReferenceTime>();
    for (int id = FIRST; id < FIRST + 3; id++) {
      GameplayDefinitions.parkourLevels.put(
          id,
          new GameplayDefinitions.ParkourLevel(id, "FinishSubMission", 1, 0, 3, 3, "race.asset"));
      levels.add(new GameplayDefinitions.ParkourRankReferenceTime(id, 80000));
    }
    GameplayDefinitions.parkourRankings.add(
        new GameplayDefinitions.ParkourRankReference(6, levels));
    GameplayDefinitions.parkourBalls.put(1, new GameplayDefinitions.ParkourBall(1, 0, 0));
    GameplayDefinitions.parkourBalls.put(2, new GameplayDefinitions.ParkourBall(2, 0, 0));
    mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort());
    store = dev.morphia.Morphia.createDatastore(client, "friendrank");
    store.getMapper().mapPackage("org.hkrpg.server.gameplay");
    store.getMapper().map(Player.class, QuestState.class, Friendship.class);
    a = racer(A, 70000);
    b = racer(B, 60000);
    c = racer(C, 10000);
    store.save(a);
    store.save(b);
    store.save(c);
  }

  @AfterEach
  void clear() {
    if (client != null) client.close();
    if (mongo != null) mongo.shutdownNow();
    GameplayDefinitions.parkourRankings.clear();
    for (int id = FIRST; id < FIRST + 3; id++) GameplayDefinitions.parkourLevels.remove(id);
    GameplayDefinitions.parkourBalls.remove(1);
    GameplayDefinitions.parkourBalls.remove(2);
  }

  ParkourRanking.Snapshot query(Player p) {
    return ParkourRanking.snapshot(p, store).orElseThrow();
  }

  @Test
  void onlyMutuallyAcceptedSavedFriendsJoinPersonalAndAggregateRanks() {
    link(a, b, true);
    link(b, a, true);
    var r = query(a);
    assertEquals(
        List.of(B, A),
        r.levels().getFirst().entries().stream().map(ParkourRanking.Entry::uid).toList());
    assertEquals(
        List.of(180000, 210000),
        r.aggregate().entries().stream().map(ParkourRanking.Entry::time).toList());
    assertFalse(r.levels().getFirst().entries().stream().anyMatch(e -> e.uid() == C));
    b.getGameplay().getParkour().getRecords().get(FIRST).record(40000, 1, true, 1);
    assertEquals(60000, query(a).levels().getFirst().entries().getFirst().time(), "好友排行只读取已保存的成绩");
    store.save(b);
    assertEquals(40000, query(a).levels().getFirst().entries().getFirst().time());
  }

  @Test
  void pendingUnilateralAndDeletedLinksNeverExposeAnotherPlayersResults() {
    link(a, b, true);
    link(b, a, false);
    link(a, c, true);
    assertEquals(
        List.of(A),
        query(a).levels().getFirst().entries().stream().map(ParkourRanking.Entry::uid).toList());
    var reciprocal = link(b, a, true);
    assertEquals(2, query(a).levels().getFirst().entries().size());
    store.delete(reciprocal);
    assertEquals(
        List.of(A),
        query(a).levels().getFirst().entries().stream().map(ParkourRanking.Entry::uid).toList());
  }

  @Test
  void eachFriendRequiresItsOwnActualTimesAndCarEvidence() {
    link(a, b, true);
    link(b, a, true);
    b.getGameplay().getParkour().getRecords().get(FIRST).record(50000, 1, true, 2);
    b.getGameplay().getParkour().getRecords().put(FIRST + 1, new GameplayState.ParkourRecord());
    store.save(b);
    var r = query(a);
    assertEquals(2, r.levels().getFirst().entries().size());
    assertEquals(
        List.of(A), r.levels().get(1).entries().stream().map(ParkourRanking.Entry::uid).toList());
    assertEquals(
        List.of(A), r.aggregate().entries().stream().map(ParkourRanking.Entry::uid).toList());
  }

  @Test
  void deletedPlayerDocumentCannotLeaveInventedRankingRows() {
    link(a, b, true);
    link(b, a, true);
    store.delete(b);
    assertEquals(
        List.of(A),
        query(a).levels().getFirst().entries().stream().map(ParkourRanking.Entry::uid).toList());
  }

  @Test
  void concurrentQueriesFromBothSidesDoNotLockEachOthersLivePlayer() throws Exception {
    link(a, b, true);
    link(b, a, true);
    try (var pool = Executors.newFixedThreadPool(6)) {
      var work = new ArrayList<Callable<Integer>>();
      for (int i = 0; i < 12; i++) {
        var p = i % 2 == 0 ? a : b;
        work.add(() -> query(p).levels().getFirst().entries().size());
      }
      for (var result : pool.invokeAll(work, 10, TimeUnit.SECONDS)) assertEquals(2, result.get());
    }
  }

  @Test
  void historicalWrongKeysCannotAuthorizeRowsAndAreNeverAutomaticallyRewritten() throws Exception {
    var old = new Friendship(a, b, a);
    old.setFriend(true);
    var id = Friendship.class.getDeclaredField("id");
    id.setAccessible(true);
    id.setLong(old, ((long) A) << 32);
    store.save(old);
    link(b, a, true);
    assertEquals(
        List.of(A),
        query(a).levels().getFirst().entries().stream().map(ParkourRanking.Entry::uid).toList());
    assertEquals(2, store.find(Friendship.class).count());
    assertNotNull(
        store
            .find(Friendship.class)
            .filter(dev.morphia.query.filters.Filters.eq("_id", ((long) A) << 32))
            .first());
  }
}
