package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.*;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** First-save cuts in source-qualified rewards; synthetic placement/pool is explicit. */
class RogueResonanceTransaction46Test {
  static final int ROOM = RogueResonanceRecovery46Test.ROOM, ENTITY = 400001, PROP = 1999758;
  @TempDir Path root;
  final RogueResonanceRecovery46Test fixture = new RogueResonanceRecovery46Test();
  final Map<Integer, GameplayDefinitions.MiracleGroup> previousPools = new HashMap<>();
  MapEntranceExcel previousEntry;
  PropExcel previousProp;
  final Map<Integer, MazeBuffExcel> previousUpgradeMaze = new HashMap<>();
  Object previousConfig;
  java.lang.reflect.Field configField;

  static class Explorer extends RogueResonanceRecovery46Test.Explorer {
    final FloorInfo floor = new FloorInfo();
    final List<String> savedGameplay = new ArrayList<>();
    Scene scene;

    @Override
    public void save() {
      savedGameplay.add(new Gson().toJson(getGameplay()));
      super.save();
    }

    @Override
    public Scene getScene() {
      if (scene == null) {
        scene =
            new Scene(
                this,
                new Gson()
                    .fromJson("{\"PlaneID\":199962,\"PlaneType\":\"Rogue\"}", MazePlaneExcel.class),
                199962001) {
              @Override
              public FloorInfo getFloorInfo() {
                return floor;
              }
            };
      }
      return scene;
    }
  }

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    fixture.setup();
    previousPools.putAll(GameplayDefinitions.miracleGroups);
    previousEntry = GameData.getMapEntranceExcelMap().get(12);
    previousProp = GameData.getPropExcelMap().get(PROP);
    GameData.getMapEntranceExcelMap()
        .add(
            new Gson()
                .fromJson(
                    "{\"ID\":12,\"PlaneID\":199962,\"FloorID\":199962001}",
                    MapEntranceExcel.class));
    var prop =
        new Gson()
            .fromJson("{\"ID\":" + PROP + ",\"PropType\":\"PROP_DESTRUCT\"}", PropExcel.class);
    prop.onLoad();
    GameData.getPropExcelMap().add(prop);
    GameplayDefinitions.rooms.put(ROOM, new GameplayDefinitions.Room(ROOM, 5, 12, 8, Map.of(8, 1)));
    configField = LunarCore.class.getDeclaredField("config");
    configField.setAccessible(true);
    previousConfig = configField.get(null);
    var config = new Config();
    config.resourceDir = root.toString();
    configField.set(null, config);
    Path graph = root.resolve("Config/Level/RogueDialogue/Shop.json");
    Files.createDirectories(graph.getParent());
    Files.writeString(
        graph,
        "{\"OnStartSequece\":[{\"TaskList\":[{\"$type\":\"RPG.GameCore.RoguePropOpenPage\",\"Type\":\"MiracleShop\"}]}]}");
  }

  @AfterEach
  void cleanup() throws Exception {
    configField.set(null, previousConfig);
    GameData.getMapEntranceExcelMap().keySet().remove(12);
    if (previousEntry != null) GameData.getMapEntranceExcelMap().add(previousEntry);
    GameData.getPropExcelMap().keySet().remove(PROP);
    if (previousProp != null) GameData.getPropExcelMap().add(previousProp);
    GameplayDefinitions.miracleGroups.clear();
    GameplayDefinitions.miracleGroups.putAll(previousPools);
    if (!previousUpgradeMaze.isEmpty())
      GameData.getMazeBuffExcelMap().values().removeIf(b -> b.getID() == 600000);
    previousUpgradeMaze.values().stream()
        .filter(Objects::nonNull)
        .forEach(GameData.getMazeBuffExcelMap()::add);
    fixture.cleanup();
  }

  void upgradeSource(Explorer p) throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/rogue-dice-curio-4.6.json")) {
      for (var raw :
          JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonArray()) {
        var record = raw.getAsJsonObject();
        String kind = record.get("kind").getAsString();
        var row = record.getAsJsonObject("row");
        if (kind.equals("blessing"))
          GameplayDefinitions.blessings.add(
              new Gson().fromJson(row, GameplayDefinitions.Blessing.class));
        if (kind.equals("maze-buff")) {
          var maze = new Gson().fromJson(row, MazeBuffExcel.class);
          previousUpgradeMaze.put(
              maze.getLv(), GameData.getMazeBuffExcelMap().get(maze.getID(), maze.getLv()));
          GameData.getMazeBuffExcelMap().add(maze);
        }
      }
    }
    p.getGameplay().getRogue().getBlessings().put(600000, 1);
  }

  @ParameterizedTest
  @ValueSource(ints = {6, 21})
  void directAcquisitionPersistsOwnershipEffectsAndMenuBeforeNotification(int id) throws Exception {
    var p = player();
    sourceCurios("rogue-dice-curio-4.6.json", Set.of(6, 20, 21));
    if (id == 21) {
      upgradeSource(p);
      p.getGameplay()
          .getRogue()
          .getMiracles()
          .put(6, new GameplayState.RogueMiracle(0, List.of(.3, 5.0)));
      GameplayDefinitions.miracleGroups.put(
          10004, new GameplayDefinitions.MiracleGroup(10004, Map.of(20, 1, 22, 1)));
    }
    p.stopAfterSave = true;
    assertThrows(
        RogueResonanceRecovery46Test.SavedInterruption.class, () -> RogueMiracles.acquire(p, id));
    if (id == 21) {
      var stored = saved(p);
      assertEquals(2, stored.getBlessings().get(600000));
      assertEquals(Set.of(20, 22), stored.getMiracles().keySet());
      assertEquals(1, p.getGameplay().getRogueEnhancements().total());
    } else {
      assertEquals(1, p.saved.size());
      var stored = new Gson().fromJson(p.saved.getFirst(), GameplayState.RogueRun.class);
      assertTrue(stored.getMiracles().containsKey(6));
      assertTrue(stored.getOffers().isEmpty());
    }
    actualPlayerReads(p);
  }

  Explorer player() throws Exception {
    var p = new Explorer();
    p.getGameplay().setRogue(fixture.paused(2).getGameplay().getRogue());
    p.getGameplay().getRogue().setInside(true);
    p.entry = 12;
    p.getGameplay().getRogue().addCoins(300 - p.getGameplay().getRogue().getCoins());
    fixture.qualifyFirewax(p);
    var group =
        new Gson()
            .fromJson(
                "{\"NPCList\":[{\"ID\":400001,\"NPCID\":1013,\"LoadOnInitial\":true,\"Dialog\":{\"LevelGraph\":\"Config/Level/RogueDialogue/Shop.json\"}}],\"PropList\":[{\"ID\":31,\"PropID\":"
                    + PROP
                    + ",\"LoadOnInitial\":true,\"Pos\":{},\"Rot\":{}}]}",
                GroupInfo.class);
    group.setId(8);
    p.floor.getGroups().put(8, group);
    var npc = new EntityNpc(p.getScene(), group, group.getNPCList().getFirst());
    npc.setEntityId(ENTITY);
    p.getScene().getEntities().put(ENTITY, npc);
    return p;
  }

  void sourceCurios(String name, Set<Integer> ids) throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/" + name)) {
      var root =
          JsonParser.parseString(
              new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8));
      var entries =
          root.isJsonArray()
              ? root.getAsJsonArray()
              : root.getAsJsonObject().getAsJsonArray("records");
      for (var raw : entries) {
        var record = raw.getAsJsonObject();
        String kind = record.get("kind").getAsString();
        var row = record.getAsJsonObject("row");
        if (kind.equals("miracle") && ids.contains(row.get("MiracleID").getAsInt())) {
          var value = new Gson().fromJson(row, GameplayDefinitions.Miracle.class);
          GameplayDefinitions.miracles.put(value.MiracleID(), value);
        } else if (kind.equals("effect") && ids.contains(row.get("MiracleEffectID").getAsInt())) {
          var value = new Gson().fromJson(row, GameplayDefinitions.MiracleEffect.class);
          GameplayDefinitions.miracleEffects.put(value.MiracleEffectID(), value);
        }
      }
    }
  }

  GameplayState.RogueRun saved(Explorer p) {
    assertEquals(1, p.saved.size());
    var run = new Gson().fromJson(p.saved.getFirst(), GameplayState.RogueRun.class);
    assertEquals(
        3,
        run.getBlessings().keySet().stream().filter(fixture.source.normals(1)::contains).count());
    assertEquals(
        List.of(fixture.source.basic(1)),
        run.getOffers(),
        "The transaction's first save must contain the earned menu");
    assertFalse(run.getBlessings().containsKey(fixture.source.basic(1)));
    assertEquals(-1, run.getResonanceSp());
    return run;
  }

  void actualPlayerReads(Explorer p) throws Exception {
    assertEquals(1, p.savedGameplay.size());
    var expected = new Gson().fromJson(p.savedGameplay.getFirst(), GameplayState.class);
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "rogue-transaction-first-save");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = document.getClass().getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456798);
      var state = document.getClass().getDeclaredField("gameplay");
      state.setAccessible(true);
      state.set(document, expected);
      store.save(document);
      for (int read = 0; read < 2; read++) {
        var restored = store.find(emu.lunarcore.game.player.Player.class).first().getGameplay();
        assertEquals(expected.getRogue().getRunId(), restored.getRogue().getRunId());
        assertEquals(
            new Gson().toJsonTree(expected.getRogue()), new Gson().toJsonTree(restored.getRogue()));
        assertEquals(
            expected.getRogueEnhancements().total(), restored.getRogueEnhancements().total());
        assertEquals(expected.getRogueCollectedBuffs(), restored.getRogueCollectedBuffs());
        assertEquals(
            new Gson().toJsonTree(expected.getRogueHandbook()),
            new Gson().toJsonTree(restored.getRogueHandbook()));
      }
    } finally {
      mongo.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {22, 21})
  void merchantPaymentPurchaseAndEarnedMenuSurviveTheFirstSaveCut(int id) throws Exception {
    var p = player();
    quote(p, id);
    String key = "1:" + ROOM + ":8:400001";
    p.stopAfterSave = true;
    assertThrows(
        RogueResonanceRecovery46Test.SavedInterruption.class,
        () -> RogueMiracleShop.buy(p, ENTITY, id));
    var stored = saved(p);
    assertEquals(100, stored.getCoins());
    assertTrue(stored.getMiracleShops().get(key).getOffers().get(id).isPurchased());
    assertTrue(stored.getMiracles().containsKey(22));
    assertFalse(stored.getMiracles().containsKey(21));
    actualPlayerReads(p);
  }

  void quote(Explorer p, int id) throws Exception {
    sourceCurios("rogue-dice-curio-4.6.json", Set.of(21));
    // The original pool contains firewax22; restricting eligibility is explicit unit qualification.
    GameplayDefinitions.miracleGroups.put(
        10004, new GameplayDefinitions.MiracleGroup(10004, Map.of(22, 1)));
    String key = "1:" + ROOM + ":8:400001";
    p.getGameplay()
        .getRogue()
        .getMiracleShops()
        .put(
            key,
            new GameplayState.RogueShopPlan(
                Map.of(
                    id,
                    new GameplayState.RogueShopOffer(
                        1, new GameplayState.RogueFragmentCost(200)))));
  }

  @ParameterizedTest
  @ValueSource(ints = {22, 21})
  void successfulPurchaseSavesOnceAndNotificationDoesNotChangeTheSavedMenu(int id)
      throws Exception {
    var p = player();
    quote(p, id);
    assertEquals(Retcode.SUCC, RogueMiracleShop.buy(p, ENTITY, id));
    var stored = saved(p);
    assertEquals(new Gson().toJsonTree(stored), new Gson().toJsonTree(p.getGameplay().getRogue()));
    int batch = stored.getSelectionId();
    assertEquals(
        Retcode.SUCC, p.getUniverseManager().selectBlessing(batch, fixture.source.basic(1)));
    String after = new Gson().toJson(p.getGameplay());
    int saves = p.saved.size();
    assertNotEquals(Retcode.SUCC, RogueMiracleShop.buy(p, ENTITY, id));
    assertEquals(after, new Gson().toJson(p.getGameplay()));
    assertEquals(saves, p.saved.size());
    assertEquals(100, p.getGameplay().getRogue().getCoins());
  }

  @Test
  void directCurioIsSavedOnceAndDuplicateAcquisitionDoesNotSave() throws Exception {
    var p = player();
    sourceCurios("rogue-dice-curio-4.6.json", Set.of(6));
    assertTrue(RogueMiracles.acquire(p, 6));
    assertEquals(1, p.savedGameplay.size());
    assertEquals(
        new Gson().fromJson(p.savedGameplay.getFirst(), JsonObject.class),
        new Gson().toJsonTree(p.getGameplay()));
    assertFalse(RogueMiracles.acquire(p, 6));
    assertEquals(1, p.savedGameplay.size());
  }

  @Test
  void lotteryReceiptAwardAndEarnedMenuSurviveTheFirstSaveCut() throws Exception {
    var p = player();
    sourceCurios("rogue-galactic-lottery-4.6.json", Set.of(63));
    GameplayDefinitions.miracleGroups.put(
        10004, new GameplayDefinitions.MiracleGroup(10004, Map.of(22, 1)));
    String key = "1:" + ROOM + ":199962:199962001:8:31";
    GameplayState.RogueMiracle item;
    do {
      item = new GameplayState.RogueMiracle(1, List.of(.99));
    } while (RogueGalacticLottery.outcome(item.getAcquisitionId(), key)
        != RogueLotteryState.Outcome.GAIN);
    item.freezeLottery();
    p.getGameplay().getRogue().getMiracles().put(63, item);
    var group = p.floor.getGroups().get(8);
    var info = group.getPropList().getFirst();
    info.setGroup(group);
    var prop = new EntityProp(p.getScene(), GameData.getPropExcelMap().get(PROP), group, info);
    prop.setEntityId(220031);
    p.getScene().getEntities().put(prop.getEntityId(), prop);
    var draw = RogueGalacticLottery.prepare(p, prop);
    assertNotNull(draw);
    // Cut the lottery commit after physical removal, without claiming a crash during scene I/O.
    p.getScene().removeEntity(prop);
    p.stopAfterSave = true;
    assertThrows(RogueResonanceRecovery46Test.SavedInterruption.class, draw::complete);
    var stored = saved(p);
    var receipt = stored.getMiracles().get(63).getLottery().getRolls().get(key);
    assertNotNull(receipt);
    assertEquals(22, receipt.getAwardedId());
    assertEquals(stored.getMiracles().get(22).getAcquisitionId(), receipt.getAwardedAcquisition());
    actualPlayerReads(p);
  }
}
