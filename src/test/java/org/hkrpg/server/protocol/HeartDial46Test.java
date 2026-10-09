package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.protobuf.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.Inventory;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.SceneGroup;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.util.Position;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class HeartDial46Test {
  static final int PLANE = 1999710, FLOOR = 199971001, SCRIPT = 20002;
  static final int MAIN = 19997101, SUB = 199971011, SECOND = 199971012;
  static final int UNLOCK = 103040103, ALL = 103040104;
  final Gson gson = new Gson();
  final Map<String, DynamicMessage> sent = new HashMap<>();
  final List<GameplayState> saved = new ArrayList<>();
  final List<GameplayState> packetStates = new ArrayList<>();
  final List<String> packetNames = new ArrayList<>();
  Scene scene;
  EntityNpc npc;
  GameSession session;
  String version = "4.6.0";

  final Player player =
      new Player() {
        final Inventory inventory = new Inventory(this);

        public Inventory getInventory() {
          return inventory;
        }

        final PlayerLineup team = new PlayerLineup(this, 0);
        final Position pos = new Position(), rot = new Position();

        public PlayerLineup getCurrentLineup() {
          return team;
        }

        public Position getPos() {
          return pos;
        }

        public Position getRot() {
          return rot;
        }

        public Scene getScene() {
          return scene;
        }

        public int getPlaneId() {
          return PLANE;
        }

        public int getFloorId() {
          return FLOOR;
        }

        public int getEntryId() {
          return 19997101;
        }

        public boolean isOnline() {
          return true;
        }

        public GameSession getSession() {
          return session;
        }

        public void sendPacket(BasePacket packet) {}

        public void save() {
          saved.add(gson.fromJson(gson.toJson(getGameplay()), GameplayState.class));
        }
      };

  @BeforeEach
  void setup() {
    HeartDialDefinitions.scripts.clear();
    HeartDialDefinitions.dialogues.clear();
    HeartDialDefinitions.npcs.clear();
    HeartDialDefinitions.constants.clear();
    HeartDialDefinitions.conditions.clear();
    HeartDialDefinitions.scriptConditions.clear();
    for (String name :
        List.of(
            "ChangeEmotion_Unlock_Sub_Mission",
            "ChangeEmotion_UnlockAll_Sub_Mission",
            "ChangeEmotion_Unlock_FirstScript")) {
      int id = name.contains("UnlockAll") ? ALL : name.endsWith("FirstScript") ? SCRIPT : UNLOCK;
      HeartDialDefinitions.constants.put(
          name, JsonParser.parseString("{\"IntValue\":" + id + "}").getAsJsonObject());
    }
    HeartDialDefinitions.constants.put(
        "ChangeEmotion_UnlockAll_StoryLineList",
        JsonParser.parseString("{\"ArrayValue\":[{\"IntValue\":0}]}").getAsJsonObject());
    HeartDialDefinitions.scripts.put(
        SCRIPT,
        new HeartDialDefinitions.Script(
            SCRIPT,
            "Anger",
            List.of(
                new HeartDialDefinitions.Emotion(null, 2000201),
                new HeartDialDefinitions.Emotion("Anger", 2000202),
                new HeartDialDefinitions.Emotion("Sad", 2000203),
                new HeartDialDefinitions.Emotion("Happy", 2000204)),
            List.of("Normal"),
            List.of(),
            0,
            0,
            0,
            0,
            0,
            0));
    for (int id = 2000201; id <= 2000204; id++)
      HeartDialDefinitions.dialogues.put(id, new HeartDialDefinitions.Dialogue(id, List.of()));
    var binding = new HeartDialDefinitions.Npc(FLOOR, 47, 400005, List.of(SCRIPT), SCRIPT);
    HeartDialDefinitions.npcs.put(binding.key(), binding);
    var floor = gson.fromJson("{\"FloorID\":" + FLOOR + "}", FloorInfo.class);
    var group =
        gson.fromJson(
            "{\"NPCList\":[{\"ID\":400005,\"NPCID\":1013,\"Pos\":{},\"Rot\":{}}]}",
            GroupInfo.class);
    group.setId(47);
    floor.getGroups().put(47, group);
    GameData.getFloorInfos().put("P" + PLANE + "_F" + FLOOR, floor);
    scene =
        new Scene(
            player,
            gson.fromJson(
                "{\"PlaneID\":" + PLANE + ",\"PlaneType\":\"Town\"}", MazePlaneExcel.class),
            FLOOR);
    scene.setEntryId(player.getEntryId());
    scene.getLoadedGroups().add(47);
    scene.getGroups().put(47, new SceneGroup(47));
    npc = new EntityNpc(scene, group, group.getNPCList().getFirst());
    npc.setEntityId(999);
    scene.getEntities().put(999, npc);
    session =
        new GameSession(null) {
          {
            setState(SessionState.ACTIVE);
          }

          public Player getPlayer() {
            return player;
          }

          public ProtocolBridge getProtocol() {
            return ProtocolBridge.forVersion(version);
          }

          public void send(BasePacket packet) {}

          public void sendClientPacket(String name, byte[] bytes) {
            try {
              packetStates.add(
                  gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class));
              packetNames.add(name);
              sent.put(name, getProtocol().clientSchema().parse(name, bytes));
            } catch (IOException invalid) {
              throw new AssertionError(invalid);
            }
          }
        };
    player.getGameplay().getSubMissions().put(UNLOCK, new QuestState(UNLOCK));
    player.getGameplay().getSubMissions().get(UNLOCK).advance(1, 1, 1);
  }

  @AfterEach
  void cleanup() {
    HeartDialDefinitions.scripts.clear();
    HeartDialDefinitions.dialogues.clear();
    HeartDialDefinitions.npcs.clear();
    HeartDialDefinitions.constants.clear();
    HeartDialDefinitions.conditions.clear();
    HeartDialDefinitions.scriptConditions.clear();
    GameData.getFloorInfos().remove("P" + PLANE + "_F" + FLOOR);
    GameData.getMainMissionExcelMap().keySet().remove(MAIN);
    GameData.getMissionInfos().remove(MAIN);
    GameplayDefinitions.subs.remove(SUB);
    GameplayDefinitions.subs.remove(SECOND);
  }

  byte[] wire(int... fields) throws Exception {
    var bytes = new ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(bytes);
    for (int i = 0; i < fields.length; i += 2) out.writeUInt32(fields[i], fields[i + 1]);
    out.flush();
    return bytes.toByteArray();
  }

  DynamicMessage call(int command, String response, byte[] bytes) throws Exception {
    sent.clear();
    assertTrue(VersionedPacketHandler.handle(session, command, bytes));
    assertNotNull(sent.get(response));
    return sent.get(response);
  }

  int change(int entity, int emotion) throws Exception {
    return Gameplay46.number(
        call(6305, "ChangeScriptEmotionScRsp", wire(1, SCRIPT, 2, entity, 12, emotion)), "retcode");
  }

  int finish(int dialogue, int entity) throws Exception {
    return Gameplay46.number(
        call(
            6358,
            "FinishEmotionDialoguePerformanceScRsp",
            wire(3, SCRIPT, 4, dialogue, 10, entity)),
        "retcode");
  }

  void mission() {
    GameData.getMainMissionExcelMap()
        .add(
            gson.fromJson(
                "{\"MainMissionID\":"
                    + MAIN
                    + ",\"TakeParam\":[{\"Type\":\"Manual\"}],\"BeginParam\":[{\"Type\":\"Auto\"}]}",
                MainMissionExcel.class));
    var info =
        gson.fromJson(
            "{\"MainMissionID\":"
                + MAIN
                + ",\"FinishSubMissionList\":["
                + SUB
                + ","
                + SECOND
                + "],\"SubMissionList\":[{\"ID\":"
                + SUB
                + ",\"MainMissionID\":"
                + MAIN
                + ",\"TakeType\":\"Auto\",\"FinishType\":\"HeartDialDialoguePerformanceFinish\",\"ParamType\":\"ListContain\",\"ParamIntList\":[2000203]},{\"ID\":"
                + SECOND
                + ",\"MainMissionID\":"
                + MAIN
                + ",\"TakeType\":\"MultiSequence\",\"TakeParamIntList\":["
                + SUB
                + "],\"FinishType\":\"HeartDialDialoguePerformanceFinish\",\"ParamType\":\"ListContain\",\"ParamIntList\":[2000203]}]}",
            MissionInfo.class);
    GameData.getMissionInfos().put(MAIN, info);
    for (var row : info.getSubMissionList()) GameplayDefinitions.subs.put(row.getId(), row);
    assertEquals(Retcode.SUCC, player.getMissionManager().accept(MAIN));
  }

  @Test
  void aBoundOrdinaryNpcCannotChangeEmotionWhileAMissionRaidIsActive() throws Exception {
    player
        .getGameplay()
        .beginMissionRaid(
            new GameplayState.MissionRaidRun(49979, 0, 199979, 49979, 49979001, 499791));
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), change(999, 3));
    assertTrue(player.getGameplay().getHeartDialEmotions().isEmpty());
    assertTrue(saved.isEmpty());
    player.getGameplay().endMissionRaid();
    assertEquals(0, change(999, 3));
    assertEquals(3, player.getGameplay().getHeartDialEmotions().get(SCRIPT));
  }

  @Test
  void queryReturnsAuthoredDefaultEmotionAndNormalStepWithoutRecordingCompletion()
      throws Exception {
    var response = call(6334, "GetHeartDialInfoScRsp", new byte[0]);
    assertEquals(0, Gameplay46.number(response, "retcode"));
    var rows = (List<?>) Gameplay46.value(response, "script_info_list");
    assertEquals(1, rows.size());
    var script = (DynamicMessage) rows.getFirst();
    assertEquals(SCRIPT, Gameplay46.number(script, "script_id"));
    assertEquals(
        1,
        ((Descriptors.EnumValueDescriptor) Gameplay46.value(script, "cur_emotion_type"))
            .getNumber());
    assertEquals(
        4, ((Descriptors.EnumValueDescriptor) Gameplay46.value(script, "step")).getNumber());
    assertTrue(player.getGameplay().getHeartDialEmotions().isEmpty());
    assertTrue(player.getGameplay().getHeartDialReports().isEmpty());
  }

  @Test
  void nativeEmotionRequestRequiresItsActualSceneNpcAndSavesBeforeResponse() throws Exception {
    assertEquals(0, change(999, 3));
    assertEquals(3, player.getGameplay().getHeartDialEmotions().get(SCRIPT));
    assertEquals(3, saved.getLast().getHeartDialEmotions().get(SCRIPT));
    assertEquals(SCRIPT, Gameplay46.number(sent.get("ChangeScriptEmotionScRsp"), "script_id"));
    assertEquals(
        3,
        ((Descriptors.EnumValueDescriptor)
                Gameplay46.value(sent.get("ChangeScriptEmotionScRsp"), "emotion_type"))
            .getNumber());
  }

  @Test
  void oneVerifiedCurrentDialogueAdvancesOnlyThePreviouslyAcceptedMission() throws Exception {
    mission();
    assertEquals(0, change(999, 3));
    assertEquals(0, finish(2000203, 999));
    assertEquals(1, player.getMissionManager().sub(SUB).getProgress());
    assertEquals(0, player.getMissionManager().sub(SECOND).getProgress());
    assertTrue(player.getGameplay().getHeartDialReports().get(2000203).isApplied());
    assertNotEquals(0, finish(2000203, 999));
    assertEquals(0, player.getMissionManager().sub(SECOND).getProgress());
  }

  @Test
  void wrongNpcUnknownAndDuplicateWireFieldsNeverChangeEmotion() throws Exception {
    assertNotEquals(0, change(998, 3));
    var response = call(6305, "ChangeScriptEmotionScRsp", wire(1, SCRIPT, 2, 999, 12, 3, 2, 999));
    assertNotEquals(0, Gameplay46.number(response, "retcode"));
    response = call(6305, "ChangeScriptEmotionScRsp", wire(1, SCRIPT, 2, 999, 12, 3, 13, 1));
    assertNotEquals(0, Gameplay46.number(response, "retcode"));
    assertTrue(player.getGameplay().getHeartDialEmotions().isEmpty());
  }

  @Test
  void peaceUsesTheOmittedProto3ZeroAndItsActualDialogue() throws Exception {
    var response = call(6305, "ChangeScriptEmotionScRsp", wire(1, SCRIPT, 2, 999));
    assertEquals(0, Gameplay46.number(response, "retcode"));
    assertEquals(0, player.getGameplay().getHeartDialEmotions().get(SCRIPT));
    assertEquals(0, finish(2000201, 999));
    response = call(6334, "GetHeartDialInfoScRsp", new byte[0]);
    var rows = (List<?>) Gameplay46.value(response, "dialogue_info_list");
    assertEquals(1, rows.size());
    var completed = (DynamicMessage) rows.getFirst();
    assertEquals(2000201, Gameplay46.number(completed, "dialogue_id"));
    assertEquals(true, Gameplay46.value(completed, "GDPHOIMONLN"));
  }

  @Test
  void authoredDefaultDialogueCanFinishWithoutManufacturingAnEmotionChange() throws Exception {
    assertEquals(0, finish(2000202, 999));
    assertTrue(player.getGameplay().getHeartDialEmotions().isEmpty());
    assertEquals(1, player.getGameplay().getHeartDialReports().get(2000202).getEmotion());
    assertTrue(saved.getLast().getHeartDialReports().get(2000202).isApplied());
  }

  @Test
  void aLoadedNpcGroupDoesNotRequireAnOptionalSavedPropertyStateEntry() throws Exception {
    scene.getGroups().remove(47);
    assertTrue(scene.getLoadedGroups().contains(47));
    assertSame(npc, scene.getEntityById(999));
    assertEquals(0, change(999, 3));
    assertEquals(0, finish(2000203, 999));
    assertTrue(player.getGameplay().getHeartDialReports().get(2000203).isApplied());
  }

  @Test
  void wrongDialogueEmotionAndDeletedResourcesCannotFinishAnAcceptedMission() throws Exception {
    mission();
    assertNotEquals(0, finish(2000203, 999));
    assertNotEquals(0, finish(123, 999));
    assertEquals(0, change(999, 3));
    HeartDialDefinitions.dialogues.remove(2000203);
    assertNotEquals(0, finish(2000203, 999));
    assertEquals(0, player.getMissionManager().sub(SUB).getProgress());
    assertTrue(player.getGameplay().getHeartDialReports().isEmpty());
  }

  @Test
  void lockedCancelledAndSingleScriptUnlocksDoNotAuthorizeAnotherScript() throws Exception {
    player.getGameplay().getSubMissions().remove(UNLOCK);
    assertNotEquals(0, change(999, 3));
    player.getGameplay().getSubMissions().put(UNLOCK, new QuestState(UNLOCK));
    player.getGameplay().getSubMissions().get(UNLOCK).advance(1, 1, 1);
    player.getGameplay().getCancelledSubs().add(UNLOCK);
    assertNotEquals(0, change(999, 3));
    player.getGameplay().getCancelledSubs().clear();
    HeartDialDefinitions.constants
        .get("ChangeEmotion_Unlock_FirstScript")
        .addProperty("IntValue", SCRIPT + 1);
    assertNotEquals(0, change(999, 3));
    player.getGameplay().getSubMissions().put(ALL, new QuestState(ALL));
    player.getGameplay().getSubMissions().get(ALL).advance(1, 1, 1);
    assertEquals(0, change(999, 3));
    var response = call(6334, "GetHeartDialInfoScRsp", new byte[0]);
    assertEquals(
        2,
        ((Descriptors.EnumValueDescriptor) Gameplay46.value(response, "unlock_status"))
            .getNumber());
  }

  @Test
  void removedLoadedGroupWrongInstanceAndWrongNpcDefinitionRejectTheRequest() throws Exception {
    scene.getLoadedGroups().remove(47);
    assertNotEquals(0, change(999, 3));
    scene.getLoadedGroups().add(47);
    npc.setInstId(400006);
    assertNotEquals(0, change(999, 3));
    npc.setInstId(400005);
    npc.setNpcId(1014);
    assertNotEquals(0, change(999, 3));
    npc.setNpcId(1013);
    scene.getFloorInfo().getGroups().get(47).getNPCList().clear();
    assertNotEquals(0, change(999, 3));
    assertTrue(player.getGameplay().getHeartDialEmotions().isEmpty());
  }

  @Test
  void otherProtocolUnauthenticatedAndTransitioningSessionsDoNotChangeState() throws Exception {
    version = "4.2.0";
    assertFalse(VersionedPacketHandler.handle(session, 6305, wire(1, SCRIPT, 2, 999, 12, 3)));
    version = "4.6.0";
    session.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(VersionedPacketHandler.handle(session, 6305, wire(1, SCRIPT, 2, 999, 12, 3)));
    assertTrue(sent.isEmpty());
    session.setState(SessionState.ACTIVE);
    player.getGameplay().getStoryLines().begin(0, null, null);
    assertNotEquals(0, change(999, 3));
    assertTrue(player.getGameplay().getHeartDialEmotions().isEmpty());
  }

  @Test
  void complexStepRaidControlAndAmbiguousEmotionsRemainUnsupported() throws Exception {
    var original = HeartDialDefinitions.scripts.get(SCRIPT);
    for (String patch :
        List.of(
            "\"StepList\":[\"Missing\",\"Normal\"]",
            "\"MissingEmoList\":[\"Sad\"]",
            "\"RaidID\":43305",
            "\"ControlDialogueID\":1",
            "\"TotalEmoInfoList\":[{\"POAIHJCEKCF\":2000201},{\"POAIHJCEKCF\":2000202}]")) {
      var row = gson.toJsonTree(original).getAsJsonObject();
      JsonParser.parseString("{" + patch + "}")
          .getAsJsonObject()
          .entrySet()
          .forEach(entry -> row.add(entry.getKey(), entry.getValue()));
      HeartDialDefinitions.scripts.put(
          SCRIPT, gson.fromJson(row, HeartDialDefinitions.Script.class));
      assertNotEquals(0, change(999, 3), patch);
      assertNotEquals(0, finish(2000202, 999), patch);
    }
    assertTrue(player.getGameplay().getHeartDialReports().isEmpty());
  }

  @Test
  void malformedAndOutOfRangeRequestsNeverSave() throws Exception {
    for (byte[] bytes :
        List.of(
            new byte[0],
            wire(1, SCRIPT),
            wire(1, SCRIPT, 2, 0),
            wire(1, SCRIPT, 2, 999, 12, 4),
            wire(1, SCRIPT, 2, 999, 12, -1),
            wire(1, SCRIPT, 2, 999, 12, 3, 1, SCRIPT),
            new byte[] {10, 0},
            new byte[] {8, (byte) 128},
            new byte[129])) {
      assertNotEquals(
          0, Gameplay46.number(call(6305, "ChangeScriptEmotionScRsp", bytes), "retcode"));
    }
    for (byte[] bytes :
        List.of(
            wire(3, SCRIPT, 4, 2000202),
            wire(3, SCRIPT, 4, 2000202, 10, 999, 4, 2000202),
            wire(3, SCRIPT, 4, 2000202, 10, 999, 11, 1))) {
      assertNotEquals(
          0,
          Gameplay46.number(call(6358, "FinishEmotionDialoguePerformanceScRsp", bytes), "retcode"));
    }
    assertNotEquals(
        0, Gameplay46.number(call(6334, "GetHeartDialInfoScRsp", wire(1, 1)), "retcode"));
    assertTrue(saved.isEmpty());
  }

  @Test
  void concurrentCompletionKeepsTheRecordedTargetSnapshotAndRunsItsActionOnce() throws Exception {
    mission();
    GameplayDefinitions.subs
        .get(SUB)
        .setFinishActionList(
            gson.fromJson(
                "[{\"FinishActionType\":\"AddCustomValue\",\"FinishActionParaString\":[\"ClockDone\",\"1\"]}]",
                MissionInfo.FinishAction[].class));
    assertEquals(0, change(999, 3));
    var results = new ArrayList<Future<Retcode>>();
    try (var pool = Executors.newFixedThreadPool(6)) {
      var gate = new CountDownLatch(1);
      for (int i = 0; i < 6; i++)
        results.add(
            pool.submit(
                () -> {
                  assertTrue(gate.await(5, TimeUnit.SECONDS));
                  return HeartDials.finish(player, SCRIPT, 2000203, 999);
                }));
      gate.countDown();
      int success = 0;
      for (var result : results) if (result.get(5, TimeUnit.SECONDS) == Retcode.SUCC) success++;
      assertEquals(1, success);
    }
    assertEquals(1, player.getGameplay().getStoryValues().get("mission:" + MAIN + ":ClockDone"));
    assertEquals(Set.of(SUB), player.getGameplay().getHeartDialReports().get(2000203).getTargets());
    assertEquals(0, player.getMissionManager().sub(SECOND).getProgress());
  }

  @Test
  void actualMorphiaReloadPreservesEmotionCompletionAndDoesNotAdvanceNewTargets() throws Exception {
    mission();
    assertEquals(0, change(999, 3));
    assertEquals(0, finish(2000203, 999));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "clockwork-heart");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      var state = Player.class.getDeclaredField("gameplay");
      uid.setAccessible(true);
      state.setAccessible(true);
      uid.setInt(document, 123456886);
      for (int i = 0; i < 2; i++) {
        state.set(document, player.getGameplay());
        store.save(document);
        state.set(player, store.find(Player.class).first().getGameplay());
        assertEquals(3, player.getGameplay().getHeartDialEmotions().get(SCRIPT));
        assertTrue(player.getGameplay().getHeartDialReports().get(2000203).isApplied());
        assertNotEquals(0, finish(2000203, 999));
        player.getMissionManager().refresh();
        assertEquals(1, player.getMissionManager().sub(SUB).getProgress());
        assertEquals(0, player.getMissionManager().sub(SECOND).getProgress());
      }
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void anUnappliedReportBlocksOtherOperationsAndResumesOnlyItsFrozenTargets() throws Exception {
    mission();
    assertEquals(0, change(999, 3));
    var report =
        new GameplayState.HeartDialReport(SCRIPT, 3, PLANE, FLOOR, 47, 400005, Set.of(SUB));
    player.getGameplay().getHeartDialReports().put(2000203, report);
    assertNotEquals(0, change(999, 2));
    assertNotEquals(0, finish(2000204, 999));
    assertEquals(0, finish(2000203, 999));
    assertTrue(report.isApplied());
    assertEquals(1, player.getMissionManager().sub(SUB).getProgress());
    assertEquals(0, player.getMissionManager().sub(SECOND).getProgress());
  }

  @Test
  void mismatchedCatalogKeysCannotAppearAsDuplicateScriptsOrAuthorizeARequest() throws Exception {
    HeartDialDefinitions.scripts.put(SCRIPT + 1, HeartDialDefinitions.scripts.get(SCRIPT));
    var response = call(6334, "GetHeartDialInfoScRsp", new byte[0]);
    assertNotEquals(0, Gameplay46.number(response, "retcode"));
    assertTrue(((List<?>) Gameplay46.value(response, "script_info_list")).isEmpty());
  }

  @Test
  void aCorruptCompletedReportCannotManufactureAnOfficialDialogueCompletionFlag() throws Exception {
    var corrupt = new GameplayState.HeartDialReport(SCRIPT, 2, PLANE, FLOOR, 47, 400005, Set.of());
    corrupt.complete();
    player.getGameplay().getHeartDialReports().put(2000203, corrupt);
    var response = call(6334, "GetHeartDialInfoScRsp", new byte[0]);
    assertNotEquals(0, Gameplay46.number(response, "retcode"));
    assertTrue(((List<?>) Gameplay46.value(response, "dialogue_info_list")).isEmpty());
    assertNotEquals(0, change(999, 3));
  }

  @Test
  void missionRefreshPublishesTheActualUnlockOnlyAfterSavingIt() {
    player.getMissionManager().refresh();
    var notification = sent.get("HeartDialScriptChangeScNotify");
    assertNotNull(notification);
    assertEquals(
        1,
        ((Descriptors.EnumValueDescriptor) Gameplay46.value(notification, "unlock_status"))
            .getNumber());
    assertEquals(
        1,
        JsonParser.parseString(gson.toJson(packetStates.getLast()))
            .getAsJsonObject()
            .get("heartDialUnlockNotified")
            .getAsInt());
    assertTrue(((List<?>) Gameplay46.value(notification, "changed_script_info_list")).isEmpty());
    sent.clear();
    player.getMissionManager().refresh();
    assertNull(sent.get("HeartDialScriptChangeScNotify"));
    player.getGameplay().getSubMissions().put(ALL, new QuestState(ALL));
    player.getGameplay().getSubMissions().get(ALL).advance(1, 1, 1);
    player.getMissionManager().refresh();
    notification = sent.get("HeartDialScriptChangeScNotify");
    assertNotNull(notification);
    assertEquals(
        2,
        ((Descriptors.EnumValueDescriptor) Gameplay46.value(notification, "unlock_status"))
            .getNumber());
    assertEquals(2, packetNames.stream().filter("HeartDialScriptChangeScNotify"::equals).count());
  }

  @Test
  void automaticMissionCompletionTriggersTheUnlockNotification() {
    player.getGameplay().getSubMissions().remove(UNLOCK);
    mission();
    sent.clear();
    var row =
        gson.fromJson(
            "{\"ID\":"
                + UNLOCK
                + ",\"MainMissionID\":"
                + MAIN
                + ",\"TakeType\":\"Auto\",\"FinishType\":\"Auto\"}",
            MissionInfo.SubMissionInfo.class);
    GameplayDefinitions.subs.put(UNLOCK, row);
    try {
      player.getMissionManager().refresh();
      assertNotEquals(QuestState.Phase.DOING, player.getMissionManager().sub(UNLOCK).getPhase());
      assertNotNull(sent.get("HeartDialScriptChangeScNotify"));
    } finally {
      GameplayDefinitions.subs.remove(UNLOCK);
    }
  }

  @Test
  void aCancelledUnlockIsPublishedAsLockedWithoutErasingTheCompletion() {
    player.getMissionManager().refresh();
    sent.clear();
    player.getGameplay().getCancelledSubs().add(UNLOCK);
    player.getMissionManager().refresh();
    var notification = sent.get("HeartDialScriptChangeScNotify");
    assertNotNull(notification);
    assertEquals(
        0,
        ((Descriptors.EnumValueDescriptor) Gameplay46.value(notification, "unlock_status"))
            .getNumber());
    assertNotEquals(QuestState.Phase.DOING, player.getMissionManager().sub(UNLOCK).getPhase());
  }

  @Test
  void unlockPublicationRemainsOnceAcrossConcurrentRefreshes() throws Exception {
    var pool = Executors.newFixedThreadPool(6);
    try {
      var jobs = new ArrayList<Future<?>>();
      for (int i = 0; i < 6; i++) jobs.add(pool.submit(() -> player.getMissionManager().refresh()));
      for (var job : jobs) job.get(5, TimeUnit.SECONDS);
      assertEquals(1, packetNames.stream().filter("HeartDialScriptChangeScNotify"::equals).count());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void oldOrUnauthenticatedSessionsDoNotConsumeTheUnlockPublication() {
    version = "4.2.0";
    player.getMissionManager().refresh();
    assertNull(sent.get("HeartDialScriptChangeScNotify"));
    version = "4.6.0";
    session.setState(SessionState.WAITING_FOR_TOKEN);
    player.getMissionManager().refresh();
    assertNull(sent.get("HeartDialScriptChangeScNotify"));
    session.setState(SessionState.ACTIVE);
    player.getMissionManager().refresh();
    assertNotNull(sent.get("HeartDialScriptChangeScNotify"));
  }

  @Test
  void resourceLoaderPreservesAuthoredFieldsAndRejectsConflictingBindings(
      @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
    var excel = Files.createDirectories(root.resolve("ExcelOutput"));
    Files.writeString(
        excel.resolve("HeartDialScript.json"), gson.toJson(HeartDialDefinitions.scripts.values()));
    Files.writeString(
        excel.resolve("HeartDialDialogue.json"),
        gson.toJson(HeartDialDefinitions.dialogues.values()));
    Files.writeString(
        excel.resolve("HeartDialNpc.json"), gson.toJson(HeartDialDefinitions.npcs.values()));
    HeartDialDefinitions.load(root);
    assertEquals(1, HeartDialDefinitions.scripts.size());
    assertEquals(4, HeartDials.normalDialogues(HeartDialDefinitions.scripts.get(SCRIPT)).size());
    assertEquals(1, HeartDialDefinitions.npcs.size());
    var duplicate = HeartDialDefinitions.npcs.values().iterator().next();
    Files.writeString(
        excel.resolve("HeartDialNpc.json"), gson.toJson(List.of(duplicate, duplicate)));
    assertThrows(IllegalStateException.class, () -> HeartDialDefinitions.load(root));
  }
}
