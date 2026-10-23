package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.config.MissionTaskInfo;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original final prologue console and ambient dialogue, without inventing a door binding. */
class StoryConsole46Test {
  final StoryDoors46Test f = new StoryDoors46Test();
  JsonObject script;

  @BeforeEach void setup() throws Exception {
    f.prepare();
    try (var in = new InputStreamReader(getClass().getResourceAsStream(
        "/fixtures/story-console-4.6.json"), StandardCharsets.UTF_8)) {
      var records = JsonParser.parseReader(in).getAsJsonObject().getAsJsonObject("records");
      script = records.getAsJsonObject("Config/Level/Mission/1000101/Mission_100010126.json");
      for (int id : List.of(100010126, 100010133)) {
        var source = records.get("Config/Level/Mission/1000101/Mission_" + id + ".json");
        var task = f.gson.fromJson(source, MissionTaskInfo.class);
        task.retainSourceDefinition(source);
        GameplayDefinitions.subs.get(id).setTask(task);
      }
    }
  }

  @AfterEach void cleanup() { f.cleanup(); }

  void ready() {
    f.completed(100010128);
    f.accepted(100010126);
    f.scene();
  }

  ScenePropTransitions.Result interact() {
    return ScenePropTransitions.interact(f.player, 1010, f.prop(300018).getEntityId());
  }

  @Test void acceptedOriginalObjectiveEnablesConsoleButDoesNotTakeTheStellaron() {
    ready();
    assertEquals(PropState.Closed, f.prop(300018).getState());
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(100010126).getPhase());
    assertTrue(f.player.getGameplay().getScenePropStates().getRecords().isEmpty());
    f.reload();
    assertEquals(PropState.Closed, f.prop(300018).getState());
    assertEquals(PropState.Closed, f.prop(300001).getState());
  }

  @Test void nativeInteractionFinishesObjectiveAndAcceptsOriginalCutsceneWithoutOpeningOtherDoors()
      throws Exception {
    ready();
    var protocol = ProtocolBridge.forVersion("4.6.0");
    var replies = new ArrayList<DynamicMessage>();
    var session = new GameSession(null) {
      public Player getPlayer() { return f.player; }
      public ProtocolBridge getProtocol() { return protocol; }
      public void sendClientPacket(String name, byte[] body) {
        try {
          if (name.equals("InteractPropScRsp")) replies.add(protocol.clientSchema().parse(name, body));
        } catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
      }
    };
    session.setState(SessionState.ACTIVE);
    var type = protocol.clientSchema().message("InteractPropCsReq");
    var request = DynamicMessage.newBuilder(type)
        .setField(type.findFieldByName("prop_entity_id"), f.prop(300018).getEntityId())
        .setField(type.findFieldByName("interact_id2"), 1010)
        .setField(type.findFieldByName("interact_id"), -1L).build();
    assertTrue(VersionedPacketHandler.handle(session, 1405, request.toByteArray()));
    assertEquals(1, replies.size());
    var reply = replies.getFirst();
    assertEquals(0, reply.getField(reply.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(1, reply.getField(reply.getDescriptorForType().findFieldByName("prop_state")));
    assertEquals(QuestState.Phase.FINISHED, f.player.getMissionManager().sub(100010126).getPhase());
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(100010133).getPhase());
    assertNull(f.player.getMissionManager().sub(100010134), "Character choice must wait for the cutscene");
    assertEquals(PropState.Closed, f.prop(300001).getState(), "Unused shared value is not a door link");
    assertEquals(Set.of("20001:20001001:0:0:5:300018"),
        f.player.getGameplay().getScenePropStates().getRecords().keySet());
  }

  @Test void failedSavePreservesReadyConsoleAndUnfinishedStory() {
    ready();
    String before = f.gson.toJson(f.player.getGameplay());
    f.player.packets.clear();
    f.player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, interact().retcode());
    assertEquals(before, f.gson.toJson(f.player.getGameplay()));
    assertEquals(PropState.Closed, f.prop(300018).getState());
    assertTrue(f.player.packets.isEmpty());
  }

  @Test void savedInteractionRecoversAnInterruptedMissionCallback() {
    ready();
    assertEquals(Retcode.SUCC, interact().retcode());
    f.accepted(100010126);
    f.player.getGameplay().getSubMissions().remove(100010133);
    f.reload();
    f.player.getMissionManager().refresh();
    assertEquals(QuestState.Phase.FINISHED, f.player.getMissionManager().sub(100010126).getPhase());
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(100010133).getPhase());
    assertEquals(PropState.Open, f.prop(300018).getState());
    assertEquals(PropState.Closed, f.prop(300001).getState());
    assertNotEquals(Retcode.SUCC, interact().retcode());
  }

  @Test void consoleAndStorySurviveActualPlayerStorageReload() throws Exception {
    ready();
    assertEquals(Retcode.SUCC, interact().retcode());
    var server = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client = com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "story-console");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var saved = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(saved, 123456784);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(saved, f.player.getGameplay());
      store.save(saved);
      for (int round = 0; round < 2; round++) {
        var loaded = Objects.requireNonNull(store.find(Player.class).first());
        f.player = new StoryDoors46Test.Actor();
        gameplay.set(f.player, loaded.getGameplay());
        f.scene();
        assertEquals(PropState.Open, f.prop(300018).getState());
        assertEquals(QuestState.Phase.FINISHED, f.player.getMissionManager().sub(100010126).getPhase());
        assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(100010133).getPhase());
        assertEquals(1, loaded.getGameplay().getScenePropStates().getRecords().size());
        store.save(loaded);
      }
    } finally { server.shutdownNow(); }
  }

  @ParameterizedTest @ValueSource(strings = {"not-accepted", "cancelled", "extra-action", "conditional", "wrong-target"})
  void unsupportedOrUnreadyStoryDoesNotUnlockConsole(String variant) {
    ready();
    switch (variant) {
      case "not-accepted" -> f.player.getGameplay().getSubMissions().remove(100010126);
      case "cancelled" -> f.player.getGameplay().getCancelledSubs().add(100010126);
      default -> {
        var changed = script.deepCopy();
        var sequences = changed.getAsJsonArray("OnStartSequece");
        if (variant.equals("extra-action"))
          sequences.get(1).getAsJsonObject().getAsJsonArray("TaskList").add(
              JsonParser.parseString("{\"$type\":\"RPG.GameCore.TriggerBattle\"}"));
        if (variant.equals("conditional")) sequences.get(0).getAsJsonObject().add("Condition", new JsonObject());
        if (variant.equals("wrong-target")) sequences.get(0).getAsJsonObject()
            .getAsJsonArray("TaskList").get(0).getAsJsonObject().addProperty("GroupID", 99);
        GameplayDefinitions.subs.get(100010126).getTask().retainSourceDefinition(changed);
      }
    }
    f.scene();
    assertEquals(PropState.Locked, f.prop(300018).getState());
    assertNotEquals(Retcode.SUCC, interact().retcode());
    assertTrue(f.player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }
}
