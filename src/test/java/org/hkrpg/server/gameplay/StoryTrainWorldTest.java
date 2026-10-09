package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;

class StoryTrainWorldTest {
  static final int WORLD = 199960501, MAIN = 19996519, SUB = 199965191;
  final GameplayProgressTest fixture = new GameplayProgressTest();

  static class SavedPlayer extends GameplayProgressTest.TestPlayer {
    final List<GameplayState> saved = new ArrayList<>();
    final List<byte[]> notices = new ArrayList<>();
    boolean online = true;
    String version = "4.6.0";
    final emu.lunarcore.server.game.GameSession session =
        new emu.lunarcore.server.game.GameSession(null) {
          public ProtocolBridge getProtocol() {
            return ProtocolBridge.forVersion(version);
          }

          public void sendClientPacket(String name, byte[] bytes) {
            assertEquals("TrainWorldIdChangeScNotify", name);
            assertEquals(1459, getProtocol().clientCommand(name));
            var document = saved.getLast();
            assertEquals(WORLD, document.getStoryValues().get("train:world"));
            assertEquals(1, document.getStoryActions().get(SUB).getNext());
            assertFalse(document.getStoryActions().get(SUB).isPending());
            notices.add(bytes);
          }
        };

    public void save() {
      saved.add(new Gson().fromJson(new Gson().toJson(getGameplay()), GameplayState.class));
    }

    public boolean isOnline() {
      return online;
    }

    public emu.lunarcore.server.game.GameSession getSession() {
      return session;
    }
  }

  void restore(Player p, GameplayState state) throws Exception {
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(p, state);
  }

  @BeforeEach
  void resources() {
    GameplayDefinitions.worlds.put(WORLD, new GameplayDefinitions.World(WORLD));
  }

  @AfterEach
  void cleanup() {
    fixture.reset();
    GameplayDefinitions.worlds.remove(WORLD);
  }

  MissionInfo.SubMissionInfo action(int id, String actions) {
    return new Gson()
        .fromJson(
            "{\"ID\":"
                + id
                + ",\"MainMissionID\":"
                + MAIN
                + ",\"FinishActionList\":"
                + actions
                + "}",
            MissionInfo.SubMissionInfo.class);
  }

  String change(int world) {
    return "{\"FinishActionType\":\"ChangeTrainWorldId\",\"FinishActionPara\":[" + world + "]}";
  }

  @Test
  void declaredTrainWorldActionPersistsWithItsCursorAndDoesNotRepeat() throws Exception {
    var p = new GameplayProgressTest.TestPlayer();
    var sub = action(SUB, "[" + change(WORLD) + "]");
    assertEquals(Retcode.SUCC, new StoryActions(p).execute(sub));
    assertEquals(WORLD, p.getGameplay().getStoryValues().get("train:world"));
    assertEquals(1, p.getGameplay().getStoryActions().get(SUB).getNext());
    assertFalse(p.getGameplay().getStoryActions().get(SUB).isPending());
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(p, new Gson().fromJson(new Gson().toJson(p.getGameplay()), GameplayState.class));
    assertEquals(Retcode.SUCC, new StoryActions(p).execute(sub));
    assertEquals(WORLD, p.getGameplay().getStoryValues().get("train:world"));
  }

  @Test
  void actualAcceptedTalkMissionCompletesOnlyAfterApplyingItsDeclaredWorld() {
    fixture.mission(
        MAIN,
        "Manual",
        Integer.toString(SUB),
        "[{\"ID\":"
            + SUB
            + ",\"MainMissionID\":"
            + MAIN
            + ",\"TakeType\":\"Auto\",\"FinishType\":\"Talk\",\"ParamStr1\":\"train\",\"FinishActionList\":["
            + change(WORLD)
            + "]}]");
    var p = new GameplayProgressTest.TestPlayer();
    assertNotEquals(Retcode.SUCC, p.getMissionManager().finishTalk(SUB, "train"));
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
    assertEquals(Retcode.SUCC, p.getMissionManager().finishTalk(SUB, "train"));
    assertEquals(WORLD, p.getGameplay().getStoryValues().get("train:world"));
    assertNotEquals(Retcode.SUCC, p.getMissionManager().finishTalk(SUB, "train"));
  }

  @Test
  void unknownWorldAndMalformedArgumentsRejectBeforeEarlierEffects() {
    for (var invalid :
        List.of(
            change(WORLD + 1),
            change(0),
            change(-1),
            "{\"FinishActionType\":\"ChangeTrainWorldId\",\"FinishActionPara\":[" + WORLD + ",1]}",
            "{\"FinishActionType\":\"ChangeTrainWorldId\",\"FinishActionPara\":["
                + WORLD
                + "],\"FinishActionParaString\":[\"unknown\"]}")) {
      var p = new GameplayProgressTest.TestPlayer();
      var sub =
          action(
              SUB,
              "[{\"FinishActionType\":\"AddCustomValue\",\"FinishActionParaString\":[\"before\",\"1\"]},"
                  + invalid
                  + "]");
      assertNotEquals(Retcode.SUCC, new StoryActions(p).execute(sub));
      assertTrue(p.getGameplay().getStoryValues().isEmpty());
      assertFalse(p.getGameplay().getStoryActions().containsKey(SUB));
    }
    var p = new GameplayProgressTest.TestPlayer();
    assertEquals(Retcode.SUCC, new StoryActions(p).execute(action(SUB, "[" + change(WORLD) + "]")));
  }

  @Test
  void nativeWorldFieldIsEmittedOnlyAfterWorldAndCompletedCursorHaveBeenSaved() throws Exception {
    var p = new SavedPlayer();
    var sub = action(SUB, "[" + change(WORLD) + "]");
    assertEquals(Retcode.SUCC, new StoryActions(p).execute(sub));
    assertEquals(2, p.saved.size());
    assertTrue(p.saved.getFirst().getStoryActions().get(SUB).isPending());
    assertFalse(p.saved.getFirst().getStoryValues().containsKey("train:world"));
    assertEquals(1, p.notices.size());
    var wire = com.google.protobuf.UnknownFieldSet.parseFrom(p.notices.getFirst());
    assertEquals(Set.of(1), wire.asMap().keySet());
    assertEquals(List.of((long) WORLD), wire.getField(1).getVarintList());
    assertEquals(Retcode.SUCC, new StoryActions(p).execute(sub));
    assertEquals(2, p.saved.size());
    assertEquals(1, p.notices.size());
  }

  @Test
  void offlineAndLegacyActionsPersistWithoutSendingExperimentalNotifications() {
    for (boolean offline : List.of(true, false)) {
      var p = new SavedPlayer();
      p.online = !offline;
      p.version = offline ? "4.6.0" : "4.2.0";
      assertEquals(
          Retcode.SUCC, new StoryActions(p).execute(action(SUB, "[" + change(WORLD) + "]")));
      assertEquals(WORLD, p.saved.getLast().getStoryValues().get("train:world"));
      assertTrue(p.notices.isEmpty());
    }
  }

  @Test
  void invalidRestoredWorldOrMismatchedResourceDoesNotProduceAnInventedNotification() {
    var p = new SavedPlayer();
    for (int world : List.of(0, -1, WORLD + 1)) {
      p.getGameplay().getStoryValues().put("train:world", world);
      TrainWorld46.sync(p);
    }
    p.getGameplay().getStoryValues().put("train:world", WORLD);
    GameplayDefinitions.worlds.put(WORLD, new GameplayDefinitions.World(WORLD + 1));
    TrainWorld46.sync(p);
    assertTrue(p.notices.isEmpty());
    assertEquals(WORLD, p.getGameplay().getStoryValues().get("train:world"));
    assertNotEquals(
        Retcode.SUCC, new StoryActions(p).execute(action(SUB, "[" + change(WORLD) + "]")));
    assertTrue(p.saved.isEmpty());
  }

  @Test
  void actualMorphiaReloadKeepsWorldAndCursorAndDoesNotRepeatTheAction() throws Exception {
    var p = new SavedPlayer();
    p.online = false;
    var sub = action(SUB, "[" + change(WORLD) + "]");
    assertEquals(Retcode.SUCC, new StoryActions(p).execute(sub));
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "story-train-world");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456773);
      restore(document, p.saved.getLast());
      store.save(document);
      restore(p, store.find(Player.class).first().getGameplay());
      p.saved.clear();
      assertEquals(WORLD, p.getGameplay().getStoryValues().get("train:world"));
      assertEquals(Retcode.SUCC, new StoryActions(p).execute(sub));
      assertTrue(p.saved.isEmpty());
      assertFalse(p.getGameplay().getStoryActions().get(SUB).isPending());
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void simultaneousRetriesCompleteAndNotifyOnlyOnce() throws Exception {
    var p = new SavedPlayer();
    var sub = action(SUB, "[" + change(WORLD) + "]");
    try (var pool = Executors.newFixedThreadPool(6)) {
      var ready = new CountDownLatch(6);
      var start = new CountDownLatch(1);
      var results = new ArrayList<Future<Retcode>>();
      for (int i = 0; i < 6; i++)
        results.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  assertTrue(start.await(5, TimeUnit.SECONDS));
                  return new StoryActions(p).execute(sub);
                }));
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      for (var result : results) assertEquals(Retcode.SUCC, result.get(5, TimeUnit.SECONDS));
    }
    assertEquals(2, p.saved.size());
    assertEquals(1, p.notices.size());
    assertEquals(WORLD, p.getGameplay().getStoryValues().get("train:world"));
  }
}
