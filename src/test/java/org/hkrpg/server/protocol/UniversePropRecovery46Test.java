package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class UniversePropRecovery46Test {
  static final String FLOOR = "P80001_F80001003";

  static class Explorer extends UniverseLifecycleTest.Explorer {
    Scene scene;
    int saves;

    public Scene getScene() {
      return scene;
    }

    public int getEntryId() {
      return 8000103;
    }

    public int getPlaneId() {
      return 80001;
    }

    public int getFloorId() {
      return 80001003;
    }

    public void save() {
      saves++;
    }
  }

  final Gson gson = new Gson();
  final JsonObject fixture =
      JsonParser.parseReader(
              new InputStreamReader(
                  Objects.requireNonNull(
                      getClass().getResourceAsStream("/fixtures/rogue-prop-recovery-4.6.json")),
                  StandardCharsets.UTF_8))
          .getAsJsonObject();
  final Explorer player = new Explorer();
  final Map<Integer, PropExcel> priorProps = new HashMap<>();
  final Map<Integer, GameplayDefinitions.Miracle> priorMiracles = new HashMap<>();
  GameplayDefinitions.MiracleEffect priorEffect;
  GameplayDefinitions.Room priorRoom;
  MapEntranceExcel priorEntry;
  FloorInfo priorFloor;
  GroupInfo group;

  @BeforeEach
  void setup() {
    var rows = fixture.getAsJsonObject("rows");
    for (var row : rows.getAsJsonArray("MazeProp")) {
      var compatible = row.getAsJsonObject().deepCopy();
      compatible.addProperty(
          "PropName", compatible.getAsJsonObject("PropName").get("Hash").getAsLong());
      var prop = gson.fromJson(compatible, PropExcel.class);
      priorProps.put(prop.getId(), GameData.getPropExcelMap().get(prop.getId()));
      prop.onLoad();
      GameData.getPropExcelMap().add(prop);
    }
    for (var row : rows.getAsJsonArray("RogueMiracle")) {
      var miracle = gson.fromJson(row, GameplayDefinitions.Miracle.class);
      priorMiracles.put(miracle.MiracleID(), GameplayDefinitions.miracles.get(miracle.MiracleID()));
      GameplayDefinitions.miracles.put(miracle.MiracleID(), miracle);
    }
    priorEffect =
        GameplayDefinitions.miracleEffects.put(
            64,
            gson.fromJson(
                rows.getAsJsonArray("RogueMiracleEffect").get(0),
                GameplayDefinitions.MiracleEffect.class));
    priorRoom =
        GameplayDefinitions.rooms.put(
            306,
            gson.fromJson(rows.getAsJsonArray("RogueRoom").get(0), GameplayDefinitions.Room.class));
    priorEntry = GameData.getMapEntranceExcelMap().get(8000103);
    GameData.getMapEntranceExcelMap()
        .add(gson.fromJson(rows.getAsJsonArray("MapEntrance").get(0), MapEntranceExcel.class));
    group = gson.fromJson(fixture.get("group"), GroupInfo.class);
    group.setId(22);
    var floor = gson.fromJson("{\"FloorID\":80001003}", FloorInfo.class);
    floor.getGroups().put(22, group);
    priorFloor = GameData.getFloorInfos().put(FLOOR, floor);
    start();
  }

  @AfterEach
  void cleanup() {
    if (priorFloor == null) GameData.getFloorInfos().remove(FLOOR);
    else GameData.getFloorInfos().put(FLOOR, priorFloor);
    GameData.getMapEntranceExcelMap().keySet().remove(8000103);
    if (priorEntry != null) GameData.getMapEntranceExcelMap().add(priorEntry);
    GameplayDefinitions.rooms.remove(306);
    if (priorRoom != null) GameplayDefinitions.rooms.put(306, priorRoom);
    for (var entry : priorProps.entrySet()) {
      GameData.getPropExcelMap().keySet().remove(entry.getKey());
      if (entry.getValue() != null) GameData.getPropExcelMap().add(entry.getValue());
    }
    for (var entry : priorMiracles.entrySet()) {
      GameplayDefinitions.miracles.remove(entry.getKey());
      if (entry.getValue() != null)
        GameplayDefinitions.miracles.put(entry.getKey(), entry.getValue());
    }
    GameplayDefinitions.miracleEffects.remove(64);
    if (priorEffect != null) GameplayDefinitions.miracleEffects.put(64, priorEffect);
  }

  GameplayState.RogueRun run() {
    return player.getGameplay().getRogue();
  }

  void start() {
    var run = new GameplayState.RogueRun(100, 199956, Map.of(1, 306));
    run.setupTeam(player, List.of(199961));
    player.getGameplay().setRogue(run);
    run.enter(1, 306);
    run.setInside(true);
    run.setMp(0);
    run.getVitals().get(199961).setHp(1000);
    reloadScene();
  }

  void reloadScene() {
    player.scene =
        new Scene(
            player,
            gson.fromJson("{\"PlaneID\":80001,\"PlaneType\":\"Rogue\"}", MazePlaneExcel.class),
            80001003);
    player.scene.setEntryId(8000103);
  }

  EntityProp prop(int instance) {
    return player.scene.getEntities().values().stream()
        .filter(EntityProp.class::isInstance)
        .map(EntityProp.class::cast)
        .filter(p -> p.getInstId() == instance)
        .findFirst()
        .orElseThrow();
  }

  boolean hasProp(int instance) {
    return player.scene.getEntities().values().stream()
        .filter(EntityProp.class::isInstance)
        .map(EntityProp.class::cast)
        .anyMatch(p -> p.getInstId() == instance);
  }

  int hp() {
    return run().getVitals().get(199961).getHp();
  }

  @Test
  void sourceRecoverBoxesUseTheExistingBaseRuleAndPersistDestruction() {
    assertTrue(player.scene.destroyProp(prop(300013)));
    assertEquals(3500, hp());
    assertTrue(player.scene.destroyProp(prop(300012)));
    assertEquals(2, run().getMp());
    var data = JsonParser.parseString(gson.toJson(run())).getAsJsonObject();
    assertTrue(data.has("destroyedRecoveryProps"), "Destruction has no persistent receipt");
    assertEquals(2, data.getAsJsonArray("destroyedRecoveryProps").size());
  }

  @Test
  void sourceAllKnowingBag64DoublesBothRecoveriesWithoutChangingFormalVitals() {
    int formalHp =
        player
            .getAvatarById(199961)
            .getCurrentHp(new emu.lunarcore.game.player.lineup.PlayerLineup(player, 0));
    assertTrue(RogueMiracles.acquire(player, 64));
    assertTrue(player.scene.destroyProp(prop(300013)));
    assertEquals(6000, hp());
    assertTrue(player.scene.destroyProp(prop(300012)));
    assertEquals(4, run().getMp());
    assertEquals(
        formalHp,
        player
            .getAvatarById(199961)
            .getCurrentHp(new emu.lunarcore.game.player.lineup.PlayerLineup(player, 0)));
  }

  @Test
  void sourceLegacy18AliasUsesEffect64AndCannotStackWith64() {
    assertTrue(RogueMiracles.acquire(player, 18));
    assertEquals(List.of(0.0), run().getMiracles().get(18).getEffectParameters());
    assertFalse(RogueMiracles.acquire(player, 64));
    assertTrue(player.scene.destroyProp(prop(300013)));
    assertEquals(6000, hp());
  }

  @Test
  void deadMembersStayDeadAndHealthAndTechniqueRespectTheirCaps() {
    run().getMiracles().put(64, new GameplayState.RogueMiracle(0, List.of(0.0)));
    run().getVitals().get(199961).setHp(0);
    assertTrue(player.scene.destroyProp(prop(300013)));
    assertEquals(0, hp());
    run().getVitals().get(199961).setHp(8000);
    run().setMp(4);
    assertTrue(player.scene.destroyProp(prop(300015)));
    assertEquals(10000, hp());
    assertTrue(player.scene.destroyProp(prop(300012)));
    assertEquals(player.getCurrentLineup().getMaxMp(), run().getMp());
  }

  @Test
  void plainSceneRemovalCannotGenerateRecovery() {
    player.scene.removeEntity(prop(300013));
    assertEquals(1000, hp());
    player.scene.removeEntity(prop(300012));
    assertEquals(0, run().getMp());
  }

  @Test
  void jsonReloadAndSceneRecreationDoNotRegenerateConsumedBoxes() throws Exception {
    assertTrue(player.scene.destroyProp(prop(300013)));
    var field = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(player, gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class));
    reloadScene();
    assertFalse(hasProp(300013));
    assertTrue(hasProp(300012));
    assertEquals(3500, hp());
  }

  @Test
  void repeatedAndConcurrentRequestsCannotRecoverTwice() throws Exception {
    var prop = prop(300013);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<java.util.concurrent.Callable<Boolean>>();
      for (int i = 0; i < 6; i++) tasks.add(() -> player.scene.destroyProp(prop));
      int successes = 0;
      for (var result : workers.invokeAll(tasks)) if (result.get()) successes++;
      assertEquals(1, successes);
    }
    assertEquals(3500, hp());
    assertFalse(player.scene.destroyProp(prop));
    reloadScene();
    assertFalse(hasProp(300013));
  }

  @Test
  void pausedOrUnassignedRoomsCannotConsumeOrRecoverTheBox() {
    var prop = prop(300013);
    run().setInside(false);
    assertFalse(player.scene.destroyProp(prop));
    assertEquals(1000, hp());
    run().setInside(true);
    run().getRooms().put(1, 305);
    assertFalse(player.scene.destroyProp(prop));
    assertEquals(1000, hp());
  }

  @Test
  void detachedOrChangedAuthoredPlacementsCannotRecover() {
    var prop = prop(300013);
    var info = prop.getPropInfo();
    group.getPropList().remove(info);
    assertFalse(player.scene.destroyProp(prop));
    assertEquals(1000, hp());
  }

  @Test
  void missingOrCorruptCurioSnapshotsKeepOnlyBaselineRecovery() {
    run().getMiracles().put(64, new GameplayState.RogueMiracle(0, List.of(.2)));
    assertTrue(player.scene.destroyProp(prop(300013)));
    assertEquals(3500, hp());
    run().getMiracles().put(64, new GameplayState.RogueMiracle(0));
    assertTrue(player.scene.destroyProp(prop(300012)));
    assertEquals(2, run().getMp());
  }

  @Test
  void aNewRunReceivesItsOwnRecoveryBoxes() {
    assertTrue(player.scene.destroyProp(prop(300013)));
    start();
    assertTrue(hasProp(300013));
    assertTrue(player.scene.destroyProp(prop(300013)));
    assertEquals(3500, hp());
  }

  @Test
  void morphiaReloadKeepsDestroyedBoxesAndTheirActualRecoveredVitals() throws Exception {
    assertTrue(RogueMiracles.acquire(player, 64));
    assertTrue(player.scene.destroyProp(prop(300013)));
    assertTrue(player.scene.destroyProp(prop(300012)));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "roguePropRecovery");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456771);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, player.getGameplay());
      store.save(document);
      gameplay.set(
          player, store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
      reloadScene();
      assertFalse(hasProp(300013));
      assertFalse(hasProp(300012));
      assertTrue(hasProp(300015));
      assertEquals(6000, hp());
      assertEquals(4, run().getMp());
      assertTrue(player.scene.destroyProp(prop(300015)));
      assertEquals(10000, hp());
    } finally {
      mongo.shutdownNow();
    }
  }
}
