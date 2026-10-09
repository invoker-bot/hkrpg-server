package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.PropInfo;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.data.excel.PropExcel;
import emu.lunarcore.game.scene.entity.EntityProp;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueDestructionCurioTest {
  static final int PROP = 1999758;
  static final String COUNT_KEY = "RoguePropCounter_0_7394D591";
  final RogueBattleMiraclesTest base = new RogueBattleMiraclesTest();
  GameplayDefinitions.Miracle oldMiracle;
  GameplayDefinitions.MiracleEffect oldEffect;
  MazeBuffExcel oldBuff;
  PropExcel oldProp;

  @BeforeEach
  void setup() {
    base.setup();
    oldMiracle = GameplayDefinitions.miracles.get(58);
    oldEffect = GameplayDefinitions.miracleEffects.get(58);
    oldBuff = GameData.getMazeBuffExcelMap().get(633112, 1);
    oldProp = GameData.getPropExcelMap().get(PROP);
    GameplayDefinitions.miracles.put(58, new GameplayDefinitions.Miracle(58, 39, 58));
    GameplayDefinitions.miracleEffects.put(
        58,
        new GameplayDefinitions.MiracleEffect(
            58, List.of(new GameplayDefinitions.EffectValue(.03))));
    GameData.getMazeBuffExcelMap()
        .add(
            base.fixture.gson.fromJson(
                "{\"ID\":633112,\"Lv\":1,\"LvMax\":1,"
                    + "\"InBattleBindingType\":\"StageAbilityBeforeCharacterBorn\","
                    + "\"InBattleBindingKey\":\"StageAbility_633112\",\"ParamList\":[{\"Value\":0.3}]}",
                MazeBuffExcel.class));
    var prop =
        base.fixture.gson.fromJson(
            "{\"ID\":" + PROP + ",\"PropType\":\"PROP_DESTRUCT\"}", PropExcel.class);
    prop.onLoad();
    GameData.getPropExcelMap().add(prop);
  }

  @AfterEach
  void cleanup() {
    base.cleanup();
    GameplayDefinitions.miracles.remove(58);
    GameplayDefinitions.miracleEffects.remove(58);
    if (oldMiracle != null) GameplayDefinitions.miracles.put(58, oldMiracle);
    if (oldEffect != null) GameplayDefinitions.miracleEffects.put(58, oldEffect);
    GameData.getMazeBuffExcelMap().values().removeIf(v -> v.getID() == 633112 && v.getLv() == 1);
    if (oldBuff != null) GameData.getMazeBuffExcelMap().add(oldBuff);
    GameData.getPropExcelMap().keySet().remove(PROP);
    if (oldProp != null) GameData.getPropExcelMap().add(oldProp);
  }

  void owned() {
    base.run().getMiracles().put(58, new GameplayState.RogueMiracle(0, List.of(.03)));
  }

  EntityProp prop(int groupId, int instance) {
    var group = new GroupInfo();
    group.setId(groupId);
    var info =
        base.fixture.gson.fromJson(
            "{\"ID\":" + instance + ",\"PropID\":" + PROP + ",\"Pos\":{},\"Rot\":{}}",
            PropInfo.class);
    info.setGroup(group);
    var prop =
        new EntityProp(
            base.fixture.p.getScene(), GameData.getPropExcelMap().get(PROP), group, info);
    prop.setEntityId(200000 + instance);
    base.fixture.p.getScene().getEntities().put(prop.getEntityId(), prop);
    return prop;
  }

  int savedCount() {
    var item =
        JsonParser.parseString(base.fixture.gson.toJson(base.run().getMiracles().get(58)))
            .getAsJsonObject();
    assertTrue(
        item.has("destroyedProps"), "The acquired curio has no persisted destruction identities");
    return item.getAsJsonArray("destroyedProps").size();
  }

  Map<String, Float> parameters(emu.lunarcore.game.battle.Battle battle) {
    var buff = battle.getBuffs().get(633112);
    assertNotNull(buff, "The destruction-dependent damage ability is missing");
    var result = new HashMap<String, Float>();
    for (var row : buff.toProto().getDynamicValues())
      assertNull(result.put(row.getKey(), row.getValue()));
    return result;
  }

  Map<String, Float> nativeParameters(emu.lunarcore.game.battle.Battle battle) throws Exception {
    var bridge = org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
    var message =
        bridge
            .clientSchema()
            .parse(
                "SceneBattleInfo",
                bridge.translate("SceneBattleInfo", battle.toProto().toByteArray(), true));
    var rows =
        (List<com.google.protobuf.DynamicMessage>)
            message.getField(message.getDescriptorForType().findFieldByName("buff_list"));
    var matches =
        rows.stream()
            .filter(
                row ->
                    Objects.equals(
                        633112, row.getField(row.getDescriptorForType().findFieldByName("id"))))
            .toList();
    assertEquals(1, matches.size());
    var buff = matches.getFirst();
    assertEquals(1, buff.getField(buff.getDescriptorForType().findFieldByName("level")));
    assertEquals(-1, buff.getField(buff.getDescriptorForType().findFieldByName("owner_index")));
    assertEquals(-1, buff.getField(buff.getDescriptorForType().findFieldByName("wave_flag")));
    var values =
        (List<com.google.protobuf.DynamicMessage>)
            buff.getField(buff.getDescriptorForType().findFieldByName("dynamic_values"));
    var result = new HashMap<String, Float>();
    for (var row : values)
      assertNull(
          result.put(
              (String) row.getField(row.getDescriptorForType().findFieldByName("key")),
              (Float) row.getField(row.getDescriptorForType().findFieldByName("value"))));
    return result;
  }

  @Test
  void exactAcquisitionFreezesParametersWithoutGrantingDamageBeforeAnyDestruction() {
    assertTrue(RogueMiracles.acquire(base.fixture.p, 58));
    assertFalse(RogueMiracles.acquire(base.fixture.p, 58));
    assertEquals(List.of(.03), base.run().getMiracles().get(58).getEffectParameters());
    assertEquals(0, savedCount());
    assertEquals(Map.of(COUNT_KEY, 0f, "#ADF_1", .03f), parameters(base.encounter()));
  }

  @Test
  void actualSceneDestructionCountsOnceAndPersistsTheSourceIdentity() {
    owned();
    var prop = prop(8, 31);
    int priorSaves = base.fixture.p.saves, coins = base.run().getCoins(), mp = base.run().getMp();
    assertTrue(base.fixture.p.getScene().destroyProp(prop));
    assertFalse(base.fixture.p.getScene().destroyProp(prop));
    assertEquals(0, prop.getEntityId());
    assertEquals(1, savedCount());
    assertTrue(base.fixture.p.saves > priorSaves);
    assertEquals(coins, base.run().getCoins());
    assertEquals(mp, base.run().getMp());
    assertEquals(Map.of(COUNT_KEY, 1f, "#ADF_1", .03f), parameters(base.encounter()));
  }

  @Test
  void theSameResourcePropAfterSceneReloadDoesNotCreateAnotherDamageStack() {
    owned();
    assertTrue(base.fixture.p.getScene().destroyProp(prop(8, 31)));
    assertTrue(base.fixture.p.getScene().destroyProp(prop(8, 31)));
    assertTrue(base.fixture.p.getScene().destroyProp(prop(8, 32)));
    assertTrue(base.fixture.p.getScene().destroyProp(prop(9, 31)));
    assertEquals(3, savedCount());
    assertEquals(Map.of(COUNT_KEY, 3f, "#ADF_1", .03f), parameters(base.encounter()));
  }

  @Test
  void destructionsBeforeAcquisitionAreNotRetroactivelyGranted() {
    assertTrue(base.fixture.p.getScene().destroyProp(prop(8, 31)));
    assertTrue(RogueMiracles.acquire(base.fixture.p, 58));
    assertEquals(0, savedCount());
    assertTrue(base.fixture.p.getScene().destroyProp(prop(8, 32)));
    assertEquals(1, savedCount());
  }

  @Test
  void preparedDamageParametersCannotChangeWhenAnotherPropIsDestroyedLater() {
    owned();
    assertTrue(base.fixture.p.getScene().destroyProp(prop(8, 31)));
    var prepared = base.encounter();
    assertTrue(base.fixture.p.getScene().destroyProp(prop(8, 32)));
    base.fixture.p.getUniverseManager().onBattlePrepared(prepared);
    assertEquals(Map.of(COUNT_KEY, 1f, "#ADF_1", .03f), parameters(prepared));
  }

  @Test
  void concurrentReportsForOneActualPropCreateOnePersistedStack() throws Exception {
    owned();
    var prop = prop(8, 31);
    var executor = Executors.newFixedThreadPool(6);
    try {
      var tasks = new ArrayList<Callable<Boolean>>();
      for (int i = 0; i < 20; i++) tasks.add(() -> base.fixture.p.getScene().destroyProp(prop));
      assertEquals(
          1,
          executor.invokeAll(tasks).stream()
              .filter(
                  task -> {
                    try {
                      return task.get();
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  })
              .count());
      assertEquals(1, savedCount());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void currentRunAndAcquisitionIdentitiesSurviveReloadAlongWithTheDamageCount() throws Exception {
    assertTrue(RogueMiracles.acquire(base.fixture.p, 58));
    assertTrue(base.fixture.p.getScene().destroyProp(prop(8, 31)));
    String acquisition = base.run().getMiracles().get(58).getAcquisitionId();
    var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(
        base.fixture.p,
        base.fixture.gson.fromJson(
            base.fixture.gson.toJson(base.fixture.p.getGameplay()), GameplayState.class));
    assertEquals(acquisition, base.run().getMiracles().get(58).getAcquisitionId());
    assertEquals(1, savedCount());
    assertTrue(base.fixture.p.getScene().destroyProp(prop(8, 31)));
    assertEquals(1, savedCount());
    assertEquals(Map.of(COUNT_KEY, 1f, "#ADF_1", .03f), parameters(base.encounter()));
  }

  @Test
  void wrongRoomGroupsPauseAndPendingSelectionDoNotCreateDamageStacks() {
    owned();
    assertTrue(base.fixture.p.getScene().destroyProp(prop(77, 31)));
    base.run().setInside(false);
    assertTrue(base.fixture.p.getScene().destroyProp(prop(8, 32)));
    base.run().setInside(true);
    base.run().offer(List.of(600000));
    assertTrue(base.fixture.p.getScene().destroyProp(prop(8, 33)));
    assertEquals(0, savedCount());
  }

  @Test
  void nonDestructibleAndNoncurrentEntitiesAreNotEligible() {
    owned();
    var ordinary =
        base.fixture.gson.fromJson(
            "{\"ID\":" + PROP + ",\"PropType\":\"PROP_ORDINARY\"}", PropExcel.class);
    ordinary.onLoad();
    GameData.getPropExcelMap().add(ordinary);
    assertFalse(base.fixture.p.getScene().destroyProp(prop(8, 31)));
    assertEquals(0, savedCount());
  }

  @Test
  void wrongDisplayEffectAndIncompleteParameterRecordsDoNotGainTheAbility() {
    GameplayDefinitions.miracles.put(58, new GameplayDefinitions.Miracle(58, 40, 58));
    assertFalse(RogueMiracles.acquire(base.fixture.p, 58));
    base.run().getMiracles().put(58, new GameplayState.RogueMiracle(0));
    assertFalse(base.encounter().hasBuff(633112));
  }

  @Test
  void preparingAReceiptCannotCountAnEntityThatHasNotBeenRemoved() {
    owned();
    var prop = prop(8, 31);
    var receipt = RoguePropMiracle.prepare(base.fixture.p, prop);
    assertNotNull(receipt);
    int saves = base.fixture.p.saves;
    receipt.complete();
    assertEquals(0, savedCount());
    assertEquals(saves, base.fixture.p.saves);
    assertTrue(base.fixture.p.getScene().destroyProp(prop));
    receipt.complete();
    receipt.complete();
    assertEquals(1, savedCount());
  }

  @Test
  void aPreparedReceiptCannotBeTransferredToAnotherAcquisition() {
    owned();
    var prop = prop(8, 31);
    var receipt = RoguePropMiracle.prepare(base.fixture.p, prop);
    assertNotNull(receipt);
    base.fixture.p.getScene().removeEntity(prop);
    owned();
    receipt.complete();
    assertEquals(0, savedCount());
    assertTrue(base.fixture.p.getScene().destroyProp(prop(8, 32)));
    assertEquals(1, savedCount());
  }

  @Test
  void nativeBattleOverridesTheSourceThirtyPercentWithTheAcquiredThreePercent() throws Exception {
    owned();
    assertTrue(base.fixture.p.getScene().destroyProp(prop(8, 31)));
    assertTrue(base.fixture.p.getScene().destroyProp(prop(8, 32)));
    assertEquals(Map.of(COUNT_KEY, 2f, "#ADF_1", .03f), nativeParameters(base.encounter()));
    assertEquals(
        .3, GameData.getMazeBuffExcelMap().get(633112, 1).getParamList().getFirst().Value());
  }

  @Test
  void theCompatibilityKeyHasTheExactPinnedNativeHashWithoutAssumingItsOriginalName() {
    assertEquals(-924445233, nativeHash(RoguePropMiracle.COUNT_KEY));
    assertEquals(462955996, nativeHash("#ADF_1"));
    assertEquals(631141772, nativeHash("ItemNumber"));
    assertEquals(1776456860, nativeHash("floor"));
    assertEquals(COUNT_KEY, RoguePropMiracle.COUNT_KEY);
  }

  private static int nativeHash(String value) {
    int a = 5381, b = 5381;
    for (int i = 0; i < value.length(); i++) {
      if ((i & 1) == 0) b = (b * 33) ^ value.charAt(i);
      else a = (a * 33) ^ value.charAt(i);
    }
    return b + a * 0x5d588b65;
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "1:2:3:4:5",
        "1:2:3:4:5:0",
        "1:2:3:4:5:-1",
        "1:2:3:4:5:01",
        "1:2:3:4:5:2147483648",
        "1:2:3:4:5:6:7",
        "a:2:3:4:5:6"
      })
  void corruptSavedIdentitiesCannotBecomeDamageStacks(String key) {
    owned();
    var document =
        JsonParser.parseString(base.fixture.gson.toJson(base.run().getMiracles().get(58)))
            .getAsJsonObject();
    var keys = new com.google.gson.JsonArray();
    keys.add(key);
    document.add("destroyedProps", keys);
    var restored = base.fixture.gson.fromJson(document, GameplayState.RogueMiracle.class);
    base.run().getMiracles().put(58, restored);
    assertEquals(-1, restored.destructionCount());
    assertFalse(RogueMiracles.active(base.run(), 58));
    assertFalse(base.encounter().hasBuff(633112));
    assertFalse(restored.rememberDestruction("1:2:3:4:5:6"));
  }

  @Test
  void aNullCounterIsInactiveWhileAnOlderAcquisitionWithoutTheFieldStartsAtZero() {
    owned();
    var document =
        JsonParser.parseString(base.fixture.gson.toJson(base.run().getMiracles().get(58)))
            .getAsJsonObject();
    document.remove("destroyedProps");
    var restored = base.fixture.gson.fromJson(document, GameplayState.RogueMiracle.class);
    assertEquals(0, restored.destructionCount());
    assertThrows(
        UnsupportedOperationException.class, () -> restored.getDestroyedProps().add("1:2:3:4:5:6"));
    document.add("destroyedProps", com.google.gson.JsonNull.INSTANCE);
    var corrupt = base.fixture.gson.fromJson(document, GameplayState.RogueMiracle.class);
    base.run().getMiracles().put(58, corrupt);
    assertEquals(-1, corrupt.destructionCount());
    assertFalse(base.encounter().hasBuff(633112));
  }

  @Test
  void actualMorphiaTwoLoadsKeepTheAcquisitionIdentitiesAndNativeDamageCount() throws Exception {
    assertTrue(RogueMiracles.acquire(base.fixture.p, 58));
    assertTrue(base.fixture.p.getScene().destroyProp(prop(8, 31)));
    String acquisition = base.run().getMiracles().get(58).getAcquisitionId();
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "destruction-curio");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456781);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      for (int i = 0; i < 2; i++) {
        gameplay.set(document, base.fixture.p.getGameplay());
        store.save(document);
        gameplay.set(
            base.fixture.p,
            store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
        var item = base.run().getMiracles().get(58);
        assertEquals(acquisition, item.getAcquisitionId());
        assertEquals(List.of(.03), item.getEffectParameters());
        assertEquals(1, item.destructionCount());
        assertEquals(Set.of("1:19996001:199962:199962001:8:31"), item.getDestroyedProps());
        assertTrue(base.fixture.p.getScene().destroyProp(prop(8, 31)));
        assertEquals(1, item.destructionCount());
        assertEquals(Map.of(COUNT_KEY, 1f, "#ADF_1", .03f), nativeParameters(base.encounter()));
      }
    } finally {
      mongo.shutdownNow();
    }
  }
}
