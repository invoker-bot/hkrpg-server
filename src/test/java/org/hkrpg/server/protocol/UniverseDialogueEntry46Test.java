package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.server.packet.*;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Original room 305 / event 40398; additional actor placements are isolated test copies. */
class UniverseDialogueEntry46Test {
  @TempDir Path root;
  final Gson gson = new Gson();
  final JsonObject fixture =
      JsonParser.parseReader(
              new java.io.InputStreamReader(
                  Objects.requireNonNull(
                      getClass().getResourceAsStream("/fixtures/rogue-dialogue-4.6.json")),
                  java.nio.charset.StandardCharsets.UTF_8))
          .getAsJsonObject();
  static final String FLOOR = "P80001_F80001003";
  Session session;
  GroupInfo group;
  NpcInfo info;
  EntityNpc npc;
  FloorInfo priorFloor;
  NpcExcel priorNpc;
  MapEntranceExcel priorEntry;

  static class Explorer extends Player {
    GameplayState state = new GameplayState();
    Scene scene;
    int saves;
    final PlayerLineup lineup = new PlayerLineup(this, 0);

    public GameplayState getGameplay() {
      return state;
    }

    public PlayerLineup getCurrentLineup() {
      return lineup;
    }

    public Scene getScene() {
      return scene;
    }

    public int getEntryId() {
      return 8000103;
    }

    public void save() {
      saves++;
    }
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final Explorer explorer = new Explorer();

    public Player getPlayer() {
      return explorer;
    }

    DynamicMessage query() throws Exception {
      response = null;
      responseName = null;
      request("GetRogueCommonDialogueDataCsReq");
      assertEquals("GetRogueCommonDialogueDataScRsp", responseName);
      assertEquals(0, Gameplay46.number(response, "retcode"));
      return response;
    }

    DynamicMessage choose(int unique, int option) throws Exception {
      var req = Gameplay46.builder(this, "SelectRogueCommonDialogueOptionCsReq");
      Gameplay46.set(req, "AIAFEKGBFIF", unique);
      Gameplay46.set(req, "HAFOKMHCGFM", option);
      assertTrue(
          VersionedPacketHandler.handle(
              this,
              getProtocol().clientCommand("SelectRogueCommonDialogueOptionCsReq"),
              req.build().toByteArray()));
      assertEquals("SelectRogueCommonDialogueOptionScRsp", responseName);
      return response;
    }
  }

  @BeforeEach
  void setup() throws Exception {
    for (var row : fixture.getAsJsonArray("files")) {
      var f = row.getAsJsonObject();
      var target = root.resolve(f.get("path").getAsString());
      Files.createDirectories(target.getParent());
      Files.writeString(target, gson.toJson(f.get("data")));
    }
    var rows = fixture.getAsJsonObject("rows");
    Files.createDirectories(root.resolve("ExcelOutput"));
    for (String name : List.of("RogueNPC", "RogueRoom"))
      Files.writeString(
          root.resolve("ExcelOutput/" + name + ".json"),
          gson.toJson(rows.getAsJsonObject(name).get("data")));
    GameplayDefinitions.load(root);
    group =
        gson.fromJson(
            fixture.getAsJsonArray("files").get(2).getAsJsonObject().get("data"), GroupInfo.class);
    group.setId(21);
    info = group.getNPCList().getFirst();
    var floor = gson.fromJson("{\"FloorID\":80001003}", FloorInfo.class);
    floor.getGroups().put(21, group);
    priorFloor = GameData.getFloorInfos().put(FLOOR, floor);
    priorNpc = GameData.getNpcExcelMap().get(3013);
    GameData.getNpcExcelMap()
        .add(
            gson.fromJson(
                rows.getAsJsonObject("NPCData").getAsJsonArray("data").get(0), NpcExcel.class));
    priorEntry = GameData.getMapEntranceExcelMap().get(8000103);
    GameData.getMapEntranceExcelMap()
        .add(
            gson.fromJson(
                rows.getAsJsonObject("MapEntrance").getAsJsonArray("data").get(0),
                MapEntranceExcel.class));
    session = new Session();
    start();
  }

  void start() {
    var run = new GameplayState.RogueRun(100, 199959, Map.of(1, 305));
    run.enter(1, 305);
    run.setInside(true);
    session.explorer.state.setRogue(run);
    reloadScene();
  }

  void reloadScene() {
    session.explorer.scene =
        new Scene(
            session.explorer,
            gson.fromJson("{\"PlaneID\":80001,\"PlaneType\":\"Rogue\"}", MazePlaneExcel.class),
            80001003);
    session.explorer.scene.setEntryId(8000103);
    npc =
        session.explorer.scene.getEntities().values().stream()
            .filter(EntityNpc.class::isInstance)
            .map(EntityNpc.class::cast)
            .findFirst()
            .orElseThrow();
  }

  @AfterEach
  void cleanup() {
    if (priorFloor == null) GameData.getFloorInfos().remove(FLOOR);
    else GameData.getFloorInfos().put(FLOOR, priorFloor);
    GameData.getNpcExcelMap().keySet().remove(3013);
    if (priorNpc != null) GameData.getNpcExcelMap().add(priorNpc);
    GameData.getMapEntranceExcelMap().keySet().remove(8000103);
    if (priorEntry != null) GameData.getMapEntranceExcelMap().add(priorEntry);
  }

  List<DynamicMessage> dialogues(DynamicMessage response) {
    assertTrue(response.getUnknownFields().asMap().isEmpty());
    return ((List<?>) Gameplay46.value(response, "dialogue_list"))
        .stream().map(DynamicMessage.class::cast).toList();
  }

  DynamicMessage nativeNpc(EntityNpc actor) throws Exception {
    var source = actor.toSceneEntityProto().toByteArray();
    var protocol = session.getProtocol();
    var bytes =
        Scene46.enrich(
            protocol.clientSchema(),
            "SceneEntityInfo",
            protocol.translate("SceneEntityInfo", source, true),
            session.explorer);
    return (DynamicMessage)
        Gameplay46.value(protocol.clientSchema().parse("SceneEntityInfo", bytes), "npc");
  }

  DynamicMessage nativeEvent(EntityNpc actor) throws Exception {
    var n = nativeNpc(actor);
    var field = n.getDescriptorForType().findFieldByName("extra_info");
    assertTrue(n.hasField(field), "Authored event NPC needs 4.6 event metadata");
    var extra = (DynamicMessage) n.getField(field);
    assertTrue(extra.hasField(extra.getDescriptorForType().findFieldByName("rogue_game_info")));
    return (DynamicMessage) Gameplay46.value(extra, "rogue_game_info");
  }

  int unique(DynamicMessage event) {
    return Gameplay46.number(event, "AIAFEKGBFIF");
  }

  @Test
  void originalNpcMetadataUsesNativeDialogueIdProgressAndUniqueFields() throws Exception {
    assertEquals(3013, npc.getNpcId());
    assertEquals(21, npc.getGroupId());
    assertEquals(400001, npc.getInstId());
    var event = nativeEvent(npc);
    assertEquals(40398, Gameplay46.number(event, "OEANPIHCHHF"));
    assertEquals(0, Gameplay46.number(event, "MJBKNMJHOGP"));
    assertTrue(unique(event) > 0);
    assertEquals(5, event.getDescriptorForType().findFieldByName("AIAFEKGBFIF").getNumber());
    assertEquals(12, event.getDescriptorForType().findFieldByName("OEANPIHCHHF").getNumber());
    assertTrue(event.getUnknownFields().asMap().isEmpty());
  }

  @Test
  void queryReturnsOriginalOptionsBoundToThatNpcWithoutInventingEffects() throws Exception {
    var rows = dialogues(session.query());
    assertEquals(1, rows.size());
    var dialogue = rows.getFirst();
    assertEquals(unique(nativeEvent(npc)), unique(dialogue));
    var owner = (DynamicMessage) Gameplay46.value(dialogue, "owner");
    var basic = (DynamicMessage) Gameplay46.value(owner, "MFAEGBJBJBE");
    assertEquals(40398, Gameplay46.number(basic, "OEANPIHCHHF"));
    var options = (List<?>) Gameplay46.value(dialogue, "KFNMIEMBJGD");
    assertEquals(
        List.of(14101, 14102, 14103),
        options.stream()
            .map(DynamicMessage.class::cast)
            .map(o -> Gameplay46.number(o, "HAFOKMHCGFM"))
            .toList());
    for (var object : options) {
      var option = (DynamicMessage) object;
      assertFalse((boolean) Gameplay46.value(option, "is_selected"));
      assertFalse((boolean) Gameplay46.value(option, "is_valid"));
      assertTrue(((List<?>) Gameplay46.value(option, "LDCJONHGDAN")).isEmpty());
    }
    assertTrue(session.explorer.state.getRogue().getCleared().isEmpty());
    assertTrue(session.explorer.state.getRewards().isEmpty());
  }

  @Test
  void repeatedQueryDoesNotCreateNewInstancesOrRepeatPersistence() throws Exception {
    var first = session.query();
    int saves = session.explorer.saves;
    assertEquals(first, session.query());
    assertEquals(saves, session.explorer.saves);
    assertEquals(1, dialogues(first).size());
    assertTrue(saves > 0);
  }

  @Test
  void persistedStateRebindsSamePlacementAfterSceneRecreation() throws Exception {
    int id = unique(dialogues(session.query()).getFirst());
    session.explorer.state =
        gson.fromJson(gson.toJson(session.explorer.state), GameplayState.class);
    reloadScene();
    assertEquals(id, unique(dialogues(session.query()).getFirst()));
    assertEquals(id, unique(nativeEvent(npc)));
  }

  @Test
  void newRunNeverReusesPreviousUniqueDialogueId() throws Exception {
    int before = unique(dialogues(session.query()).getFirst());
    start();
    int next = unique(dialogues(session.query()).getFirst());
    assertTrue(next > before);
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(),
        Gameplay46.number(session.choose(before, 14101), "retcode"));
  }

  @Test
  void oldStoredRunRecoversDialogueHighWaterMarkBeforeStartingAnotherRun() throws Exception {
    int before = unique(dialogues(session.query()).getFirst());
    var stored = gson.toJsonTree(session.explorer.state).getAsJsonObject();
    stored.remove("rogueDialogueSequence");
    session.explorer.state = gson.fromJson(stored, GameplayState.class);
    start();
    assertTrue(unique(dialogues(session.query()).getFirst()) > before);
  }

  @Test
  void twoRealActorPlacementsHaveDistinctStableInstances() throws Exception {
    var copy = gson.toJsonTree(info).getAsJsonObject();
    copy.addProperty("ID", 400002);
    var second = gson.fromJson(copy, NpcInfo.class);
    group.getNPCList().add(second);
    var other = new UniverseEntityLoader().loadNpc(session.explorer.scene, group, second);
    assertNotNull(other);
    session.explorer.scene.addEntity(other);
    var rows = dialogues(session.query());
    assertEquals(2, rows.size());
    assertEquals(2, rows.stream().map(this::unique).distinct().count());
    assertEquals(rows, dialogues(session.query()));
  }

  @Test
  void detachedOrMutatedActorIsExcludedFromQueriesAndMetadata() throws Exception {
    assertEquals(1, dialogues(session.query()).size());
    npc.setNpcId(999999);
    assertTrue(dialogues(session.query()).isEmpty());
    assertFalse(
        nativeNpc(npc)
            .hasField(nativeNpc(npc).getDescriptorForType().findFieldByName("extra_info")));
    npc.setNpcId(3013);
    session.explorer.scene.removeEntity(npc);
    assertTrue(dialogues(session.query()).isEmpty());
  }

  @Test
  void leavingOrChangingRoomDoesNotExposeStaleInstances() throws Exception {
    assertEquals(1, dialogues(session.query()).size());
    var run = session.explorer.state.getRogue();
    run.setInside(false);
    assertTrue(dialogues(session.query()).isEmpty());
    run.setInside(true);
    run.enter(2, 305);
    assertTrue(dialogues(session.query()).isEmpty());
  }

  @Test
  void unknownUnlockConditionDoesNotAttachEventMetadata() throws Exception {
    GameplayDefinitions.rooms.put(
        305, new GameplayDefinitions.Room(305, 3, 8000103, 21, Map.of(21, 10101)));
    reloadScene();
    var n = nativeNpc(npc);
    assertFalse(n.hasField(n.getDescriptorForType().findFieldByName("extra_info")));
  }

  @Test
  void unsupportedOriginalEffectIsRejectedWithoutGrantOrCompletion() throws Exception {
    int id = unique(dialogues(session.query()).getFirst());
    int saves = session.explorer.saves;
    var response = session.choose(id, 14101);
    assertEquals(Retcode.ROGUE_STATUS_NOT_MATCH.getVal(), Gameplay46.number(response, "retcode"));
    assertEquals(id, unique(response));
    assertEquals(14101, Gameplay46.number(response, "HAFOKMHCGFM"));
    assertTrue(session.explorer.state.getRogue().getCleared().isEmpty());
    assertTrue(session.explorer.state.getRogue().getMiracleOffers().isEmpty());
    assertTrue(session.explorer.state.getRewards().isEmpty());
    assertEquals(saves, session.explorer.saves);
  }

  @Test
  void foreignOptionAndInstanceAreRejectedWithoutMutation() throws Exception {
    int id = unique(dialogues(session.query()).getFirst());
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(session.choose(id, 14104), "retcode"));
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(),
        Gameplay46.number(session.choose(id + 1, 14101), "retcode"));
    assertEquals(1, dialogues(session.query()).size());
  }

  @Test
  void malformedQueryIsRejectedBeforePersistingOrResponding() {
    assertThrows(
        ProtocolException.class,
        () ->
            VersionedPacketHandler.handle(
                session,
                session.getProtocol().clientCommand("GetRogueCommonDialogueDataCsReq"),
                new byte[] {8, 1}));
    assertNull(session.response);
    assertEquals(0, session.explorer.saves);
  }

  @Test
  void inactiveSessionRecognizesQueryWithoutExposingDialogueState() throws Exception {
    session.setState(SessionState.INACTIVE);
    assertTrue(
        VersionedPacketHandler.handle(
            session,
            session.getProtocol().clientCommand("GetRogueCommonDialogueDataCsReq"),
            new byte[0]));
    assertNull(session.response);
  }
}
