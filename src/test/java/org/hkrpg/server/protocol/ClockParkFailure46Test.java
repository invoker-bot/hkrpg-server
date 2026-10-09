package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.gameplay.ClockParkManager;
import org.hkrpg.server.gameplay.GameplayState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A real failed checkpoint must settle failure without earning an authored ending. */
class ClockParkFailure46Test {
  @TempDir Path root;
  final ClockParkCheckpoint46Test checkpoint = new ClockParkCheckpoint46Test();
  final List<String> sent = new ArrayList<>();
  DynamicMessage notification;
  final Gameplay46HandlerTest.Session completion =
      new Gameplay46HandlerTest.Session() {
        public emu.lunarcore.game.player.Player getPlayer() {
          return checkpoint.fixture.opening.player;
        }

        public void sendClientPacket(String name, byte[] bytes) {
          sent.add(name);
          if (name.equals("ClockParkFinishScriptScNotify")) {
            try {
              notification = getProtocol().clientSchema().parse(name, bytes);
            } catch (Exception invalid) {
              throw new AssertionError(invalid);
            }
          } else super.sendClientPacket(name, bytes);
        }
      };

  @BeforeEach
  void prepare() throws Exception {
    checkpoint.root = root;
    checkpoint.prepare();
  }

  @AfterEach
  void restore() throws Exception {
    checkpoint.restore();
  }

  void failedCheckpoint(int script) throws Exception {
    checkpoint.advanceToLast(script, 1);
    checkpoint.finish(script);
    assertFalse(checkpoint.run().getCheckpointPassed());
  }

  DynamicMessage request(int script) {
    return checkpoint.fixture.operation(
        script,
        checkpoint.run().getRound(),
        "MLHNHAFPLKD",
        checkpoint.fixture.request("MOEMKBLCKKO").build());
  }

  void submit(DynamicMessage request) throws Exception {
    VersionedPacketHandler.handle(
        completion,
        completion.getProtocol().clientCommand("ClockParkHandleWaitOperationCsReq"),
        request.toByteArray());
  }

  String saved() {
    var opening = checkpoint.fixture.opening;
    return opening.gson.toJson(opening.player.getGameplay());
  }

  @Test
  void successfulCheckpointUsesNativeNormalPassStatus() throws Exception {
    checkpoint.advanceToLast(1, 6);
    checkpoint.finish(1);
    submit(request(1));
    assertEquals(0, checkpoint.fixture.number(completion.response, "retcode"));
    assertEquals(
        2,
        ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                checkpoint.fixture.value(completion.response, "LNPGHCLDOKG"))
            .getNumber());
    assertEquals("BRANCH", checkpoint.run().getPhase());
    assertNull(notification);
  }

  @Test
  void failedCompletionSaveRetainsTheCheckpointAndRetryPublishesOneFailure() throws Exception {
    failedCheckpoint(1);
    var request = request(1);
    String before = saved();
    checkpoint.fixture.opening.failSave = true;
    submit(request);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY.getVal(),
        checkpoint.fixture.number(completion.response, "retcode"));
    assertEquals(before, saved());
    assertNull(notification);
    checkpoint.fixture.opening.failSave = false;
    submit(request);
    assertEquals(0, checkpoint.fixture.number(completion.response, "retcode"));
    assertNull(checkpoint.run());
    assertNotNull(notification);
    assertFalse(
        checkpoint
            .fixture
            .opening
            .player
            .getGameplay()
            .getClockPark()
            .getLastFailure()
            .getCheckpointPassed());
    String completed = saved();
    notification = null;
    sent.clear();
    submit(request);
    assertEquals(5, checkpoint.fixture.number(completion.response, "retcode"));
    assertEquals(completed, saved());
    assertNull(notification);
    assertEquals(List.of("ClockParkHandleWaitOperationScRsp"), sent);
  }

  @Test
  void sixConcurrentFailureCompletionsSaveOneFailureAndDoNotCreateAnEnding() throws Exception {
    failedCheckpoint(2);
    int round = checkpoint.run().getRound();
    var manager = new ClockParkManager(checkpoint.fixture.opening.player);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var calls = new ArrayList<java.util.concurrent.Callable<ClockParkManager.Result>>();
      for (int i = 0; i < 6; i++) calls.add(() -> manager.finishCheckpoint(2, round));
      int successful = 0;
      for (var result : pool.invokeAll(calls)) {
        var value = result.get();
        if (value.code() == emu.lunarcore.server.packet.Retcode.SUCC) {
          successful++;
          assertTrue(value.failed());
        } else assertEquals(emu.lunarcore.server.packet.Retcode.REQ_PARA_INVALID, value.code());
      }
      assertEquals(1, successful);
    }
    assertNull(checkpoint.run());
    assertTrue(
        checkpoint
            .fixture
            .opening
            .player
            .getGameplay()
            .getClockPark()
            .completedEndings()
            .isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"script", "round", "story", "unknown", "truncated"})
  void invalidFailureConfirmationsPreserveTheActualFailedCheckpoint(String kind) throws Exception {
    failedCheckpoint(1);
    var request = request(1);
    if (kind.equals("script"))
      request =
          checkpoint.fixture.operation(
              2,
              checkpoint.run().getRound(),
              "MLHNHAFPLKD",
              checkpoint.fixture.request("MOEMKBLCKKO").build());
    if (kind.equals("round"))
      request =
          checkpoint.fixture.operation(
              1,
              checkpoint.run().getRound() + 1,
              "MLHNHAFPLKD",
              checkpoint.fixture.request("MOEMKBLCKKO").build());
    if (kind.equals("story"))
      request =
          checkpoint.fixture.operation(
              1,
              checkpoint.run().getRound(),
              "IKNEKEJEGLM",
              checkpoint.fixture.request("NKOCCGLHOML").build());
    if (kind.equals("unknown")) {
      var inner = checkpoint.fixture.request("MOEMKBLCKKO");
      inner.setUnknownFields(
          com.google.protobuf.UnknownFieldSet.newBuilder()
              .addField(
                  1, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build())
              .build());
      request =
          checkpoint.fixture.operation(
              1, checkpoint.run().getRound(), "MLHNHAFPLKD", inner.build());
    }
    byte[] raw = request.toByteArray();
    if (kind.equals("truncated")) raw = Arrays.copyOf(raw, raw.length - 1);
    String before = saved();
    VersionedPacketHandler.handle(
        completion,
        completion.getProtocol().clientCommand("ClockParkHandleWaitOperationCsReq"),
        raw);
    assertEquals(5, checkpoint.fixture.number(completion.response, "retcode"));
    assertEquals(before, saved());
    assertNull(notification);
  }

  @ParameterizedTest
  @ValueSource(strings = {"pass", "attribute", "source", "sequence", "phase", "receipt", "story"})
  void damagedSavedFailureCannotBeTreatedAsValidOrRepairedByQuery(String kind) throws Exception {
    failedCheckpoint(1);
    submit(request(1));
    var opening = checkpoint.fixture.opening;
    var raw = JsonParser.parseString(saved()).getAsJsonObject();
    var proof = raw.getAsJsonObject("clockPark").getAsJsonObject("lastFailure");
    switch (kind) {
      case "pass" -> proof.addProperty("checkpointPassed", true);
      case "attribute" -> proof.addProperty("attributeA", 50);
      case "source" -> proof.addProperty("checkpointSource", "changed");
      case "sequence" -> proof.addProperty("sequence", 999);
      case "phase" -> proof.addProperty("phase", "STORY");
      case "receipt" -> proof.add("receipts", new JsonArray());
      case "story" -> proof.addProperty("story", 10201);
      default -> fail(kind);
    }
    opening.replaceGameplay(opening.gson.fromJson(raw, GameplayState.class));
    String before = saved();
    completion.request("ClockParkGetInfoCsReq");
    assertEquals(6, checkpoint.fixture.number(completion.response, "retcode"));
    assertEquals(before, saved());
    assertEquals(
        emu.lunarcore.server.packet.Retcode.PLAYER_DATA_ERROR,
        new ClockParkManager(opening.player).start(1).code());
    assertEquals(before, saved());
  }

  @ParameterizedTest
  @ValueSource(strings = {"1-idle", "2-idle", "3-idle", "1-new", "2-new", "3-new"})
  void actualPlayerMorphiaReloadPreservesFailureAlongsideIdleOrANewRun(String value)
      throws Exception {
    int script = Integer.parseInt(value.substring(0, 1));
    failedCheckpoint(script);
    submit(request(script));
    var opening = checkpoint.fixture.opening;
    if (value.endsWith("new"))
      assertEquals(
          emu.lunarcore.server.packet.Retcode.SUCC,
          new ClockParkManager(opening.player).start(script).code());
    var expected = checkpoint.fixture.query();
    String before = saved();
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "clock-park-failure-fixture");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              emu.lunarcore.game.player.Player.class,
              emu.lunarcore.game.quest.QuestState.class,
              emu.lunarcore.util.Position.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.set(document, 199986);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, opening.player.getGameplay());
      store.save(document);
      for (int i = 0; i < 2; i++) {
        var restored =
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(before, opening.gson.toJson(restored.getGameplay()));
        opening.replaceGameplay(restored.getGameplay());
        assertEquals(expected, checkpoint.fixture.query());
        assertEquals(before, saved());
        assertTrue(opening.player.getGameplay().getClockPark().valid(ClockParkManager.catalog()));
      }
    } finally {
      server.shutdownNow();
    }
    opening.replaceGameplay(opening.gson.fromJson(before, GameplayState.class));
    assertEquals(expected, checkpoint.fixture.query());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  void originalFailedCheckpointClosesTheRunAndPublishesFailureOnly(int script) throws Exception {
    failedCheckpoint(script);
    int chapter = checkpoint.run().getChapter(), round = checkpoint.run().getRound();
    submit(request(script));
    assertEquals(0, checkpoint.fixture.number(completion.response, "retcode"));
    assertEquals(
        1,
        ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                checkpoint.fixture.value(completion.response, "LNPGHCLDOKG"))
            .getNumber());
    assertEquals(round, checkpoint.fixture.number(completion.response, "JGPHDBDHKKO"));
    assertNull(checkpoint.run());
    assertEquals(
        List.of("ClockParkHandleWaitOperationScRsp", "ClockParkFinishScriptScNotify"), sent);
    var result = checkpoint.fixture.nested(notification, "NLEOMLNOKCC");
    assertEquals(script, checkpoint.fixture.number(result, "script_id"));
    assertEquals(chapter, checkpoint.fixture.number(result, "DCAHJELNJNP"));
    assertEquals(true, checkpoint.fixture.value(result, "HGPKMHFPMBJ"));
    assertEquals(false, checkpoint.fixture.value(result, "DMNEACGEHEC"));
    assertEquals(0, checkpoint.fixture.number(result, "progress"));
    assertTrue(
        ((List<?>)
                checkpoint.fixture.value(checkpoint.fixture.nested(result, "reward"), "item_list"))
            .isEmpty());
    var state = checkpoint.fixture.opening.player.getGameplay().getClockPark();
    assertTrue(state.valid(ClockParkManager.catalog()));
    assertEquals(List.of(chapter), state.reached(script));
    assertTrue(state.completedEndings().isEmpty());
    assertTrue(checkpoint.fixture.opening.player.getGameplay().getRewards().isEmpty());
    completion.request("ClockParkGetInfoCsReq");
    assertEquals(0, checkpoint.fixture.number(completion.response, "progress"));
    completion.request("ClockParkGetOngoingScriptInfoCsReq");
    assertEquals(0, checkpoint.fixture.number(completion.response, "script_id"));
    var start = checkpoint.fixture.request("ClockParkStartScriptCsReq");
    start.setField(start.getDescriptorForType().findFieldByName("script_id"), script);
    VersionedPacketHandler.handle(
        completion,
        completion.getProtocol().clientCommand("ClockParkStartScriptCsReq"),
        start.build().toByteArray());
    assertEquals(0, checkpoint.fixture.number(completion.response, "retcode"));
  }
}
