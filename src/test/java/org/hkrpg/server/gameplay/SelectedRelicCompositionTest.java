package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.inventory.tabs.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.SessionState;
import it.unimi.dsi.fastutil.ints.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import us.hebi.quickbuf.RepeatedInt;

class SelectedRelicCompositionTest {
  final Gson gson = ResourceLoader.getGson();
  JsonArray fixture;
  final List<Runnable> restorations = new ArrayList<>();
  boolean interruptDebit;
  String interruptPhase;
  int deliveryLimit = Integer.MAX_VALUE;
  final Player player =
      new Player() {
        final Inventory actual =
            new Inventory(this) {
              public List<GameItem> addItems(List<GameItem> values, boolean hint) {
                var stored = new ArrayList<GameItem>();
                for (var v : values.subList(0, Math.min(deliveryLimit, values.size()))) {
                  var copy =
                      new GameItem(v.getExcel(), v.getCount(), v.getMainAffix()) {
                        public void save() {}
                      };
                  if (v.getSubAffixes() != null)
                    try {
                      var field = GameItem.class.getDeclaredField("subAffixes");
                      field.setAccessible(true);
                      field.set(copy, new ArrayList<>(v.getSubAffixes()));
                    } catch (Exception e) {
                      throw new AssertionError(e);
                    }
                  stored.add(copy);
                }
                return super.addItems(stored, hint);
              }
            };

        public Inventory getInventory() {
          return actual;
        }

        public void save() {
          var a = getGameplay().getCompositionLimits().getLastAttempt();
          if (a != null
              && (interruptDebit && "DEBITED".equals(a.getPhase())
                  || Objects.equals(interruptPhase, a.getPhase())))
            throw new IllegalStateException("Isolated actual debit save interruption");
        }

        public void sendPacket(BasePacket packet) {}
      };
  final InventoryService service = new InventoryService(null);

  <T extends GameResource> void install(ExcelMap<T> map, T r) {
    var old = map.get(r.getId());
    restorations.add(
        () -> {
          map.keySet().remove(r.getId());
          if (old != null) map.add(old);
        });
    map.add(r);
  }

  <T extends MultiKeyGameResource> void install(MultiKeyExcelMap<T> map, T r) {
    var old = map.get(r.getPrimaryKey(), r.getSecondaryKey());
    restorations.add(
        () -> {
          map.values()
              .removeIf(
                  v ->
                      v.getPrimaryKey() == r.getPrimaryKey()
                          && v.getSecondaryKey() == r.getSecondaryKey());
          if (old != null) map.add(old);
        });
    map.add(r);
  }

  void snapshotDepot(String field, int group) throws Exception {
    var f = GameDepot.class.getDeclaredField(field);
    f.setAccessible(true);
    @SuppressWarnings("unchecked")
    var map = (Int2ObjectMap<Object>) f.get(null);
    var old = map.get(group);
    restorations.add(
        () -> {
          if (old == null) map.remove(group);
          else map.put(group, old);
        });
    map.put(group, new ArrayList<>());
  }

  JsonObject row(String kind, int id) {
    return fixture.asList().stream()
        .map(JsonElement::getAsJsonObject)
        .filter(
            r ->
                kind.equals(r.get("kind").getAsString())
                    && r.getAsJsonObject("row").get("ID").getAsInt() == id)
        .findFirst()
        .orElseThrow()
        .getAsJsonObject("row")
        .deepCopy();
  }

  @BeforeEach
  void setup() throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/selected-relic-4.6.json")) {
      assertNotNull(in);
      fixture =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
    for (int group = 51; group <= 56; group++) snapshotDepot("relicMainAffixDepot", group);
    snapshotDepot("relicSubAffixDepot", 5);
    for (var v : fixture) {
      var r = v.getAsJsonObject();
      var raw = r.getAsJsonObject("row");
      switch (r.get("kind").getAsString()) {
        case "item" -> install(GameData.getItemExcelMap(), gson.fromJson(raw, ItemExcel.class));
        case "recipe" ->
            install(GameData.getItemComposeExcelMap(), gson.fromJson(raw, ItemComposeExcel.class));
        case "main" -> {
          var a = gson.fromJson(raw, RelicMainAffixExcel.class);
          install(GameData.getRelicMainAffixExcelMap(), a);
          a.onLoad();
        }
        case "sub" -> {
          var a = gson.fromJson(raw, RelicSubAffixExcel.class);
          install(GameData.getRelicSubAffixExcelMap(), a);
          a.onLoad();
        }
      }
    }
    for (var v : fixture) {
      var r = v.getAsJsonObject();
      if (!"relic".equals(r.get("kind").getAsString())) continue;
      var a = gson.fromJson(r.get("row"), RelicExcel.class);
      install(GameData.getRelicExcelMap(), a);
      a.onLoad();
    }
    player.setWorldLevel(3);
    player.setNickname("Relic regression");
    player.addSCoin(100000);
  }

  @AfterEach
  void cleanup() {
    Collections.reverse(restorations);
    restorations.forEach(Runnable::run);
  }

  GameItem stock(int id, int amount) {
    var item =
        new GameItem(GameData.getItemExcelMap().get(id), amount) {
          public void save() {}
        };
    assertTrue(player.getInventory().addItem(item));
    return player.getInventory().getMaterialByItemId(id);
  }

  int count(int id) {
    var item = player.getInventory().getMaterialByItemId(id);
    return item == null ? 0 : item.getCount();
  }

  RepeatedInt subs(int... ids) {
    return RepeatedInt.newEmptyInstance().addAll(ids);
  }

  List<GameItem> compose(int count, int main, int... sub) {
    return service.composeRelic(player, 9001, 61011, count, main, subs(sub));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2})
  void originalRecipeActuallyStoresAValidRelicAndPaysItsSourceCosts(int selected) {
    stock(235, 100);
    stock(236, 1);
    stock(237, 4);
    var output = selected == 0 ? compose(1, 1) : compose(1, 1, 2, 8);
    assertNotNull(output);
    assertEquals(1, output.size());
    var relics =
        player.getInventory().getItems().values().stream()
            .filter(v -> v.getItemId() == 61011)
            .toList();
    assertEquals(1, relics.size());
    var relic = relics.getFirst();
    assertEquals(1, relic.getMainAffix());
    assertEquals(player.getUid(), relic.getOwnerUid());
    if (selected == 2)
      assertTrue(
          relic.getSubAffixes().stream()
              .map(GameItemSubAffix::getId)
              .toList()
              .containsAll(List.of(2, 8)));
    assertEquals(0, count(235));
    assertEquals(0, count(236));
    assertEquals(selected == 0 ? 4 : 0, count(237));
    assertEquals(99000, player.getScoin());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "main",
        "sub",
        "conflict",
        "duplicate",
        "world",
        "max",
        "locked",
        "owner",
        "capacity",
        "overflow",
        "function",
        "pending"
      })
  void invalidOrUnresolvedCompositionCannotDebitOrCreateRelics(String kind) {
    int amount = kind.equals("max") ? 21 : kind.equals("overflow") ? 2 : 1;
    stock(235, kind.equals("pending") ? 900 : amount * 100);
    stock(236, amount);
    stock(237, amount * 4);
    int main = kind.equals("main") ? 999 : 1;
    int[] chosen =
        switch (kind) {
          case "sub" -> new int[] {999};
          case "conflict" -> new int[] {1};
          case "duplicate" -> new int[] {2, 2};
          default -> new int[0];
        };
    var recipe = row("recipe", 9001);
    if (kind.equals("world")) player.setWorldLevel(2);
    if (kind.equals("overflow")) recipe.addProperty("CoinCost", Integer.MAX_VALUE);
    if (kind.equals("function")) recipe.addProperty("FuncType", "Unknown46");
    if (kind.equals("locked")) player.getInventory().getMaterialByItemId(235).setLocked(true);
    if (kind.equals("owner"))
      player
          .getInventory()
          .getMaterialByItemId(235)
          .setOwner(
              new Player() {
                public int getUid() {
                  return 88;
                }
              });
    if (kind.equals("capacity"))
      player.getInventory().createTab(InventoryTabType.RELIC, new EquipInventoryTab(0));
    GameData.getItemComposeExcelMap().add(gson.fromJson(recipe, ItemComposeExcel.class));
    if (kind.equals("pending")) {
      interruptDebit = true;
      assertThrows(
          IllegalStateException.class, () -> ItemComposition.compose(player, 101, 1, List.of()));
      interruptDebit = false;
      assertEquals(100, count(235));
      assertTrue(player.getGameplay().getCompositionLimits().pending());
    }
    var before = new HashMap<Integer, Integer>();
    player.getInventory().getItems().values().forEach(v -> before.put(v.getItemId(), v.getCount()));
    int coins = player.getScoin();
    assertNull(compose(amount, main, chosen), kind);
    var after = new HashMap<Integer, Integer>();
    player.getInventory().getItems().values().forEach(v -> after.put(v.getItemId(), v.getCount()));
    assertEquals(before, after, kind);
    assertEquals(coins, player.getScoin(), kind);
    assertEquals(
        0,
        player.getInventory().getItems().values().stream()
            .filter(v -> v.getItemId() == 61011)
            .count());
  }

  static java.util.stream.Stream<Arguments> sourceRelics() throws Exception {
    try (var in =
        SelectedRelicCompositionTest.class.getResourceAsStream(
            "/fixtures/selected-relic-4.6.json")) {
      var fixture =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
      var values = new ArrayList<Arguments>();
      for (var entry : fixture) {
        var e = entry.getAsJsonObject();
        if (!"recipe".equals(e.get("kind").getAsString())) continue;
        var r = e.getAsJsonObject("row");
        if (!"SelectedRelic".equals(r.get("FormulaType").getAsString())) continue;
        for (var id : r.getAsJsonArray("RelicList"))
          values.add(Arguments.of(r.get("ID").getAsInt(), id.getAsInt()));
      }
      assertEquals(192, values.size());
      return values.stream();
    }
  }

  @ParameterizedTest
  @MethodSource("sourceRelics")
  void everyOriginalRecipeRelicPairPaysAndStoresMatchingSourceAffixes(int recipe, int relic) {
    stock(235, 100);
    stock(236, 1);
    stock(237, 4);
    var row = GameData.getRelicExcelMap().get(relic);
    var main = GameDepot.getRelicMainAffixesByGroup(row.getMainAffixGroup()).getFirst();
    int[] subs =
        GameDepot.getRelicSubAffixesByGroup(row.getSubAffixGroup()).stream()
            .filter(s -> s.getProperty() != main.getProperty())
            .limit(2)
            .mapToInt(RelicSubAffixExcel::getAffixID)
            .toArray();
    var result = service.composeRelic(player, recipe, relic, 1, main.getAffixID(), subs(subs));
    assertNotNull(result);
    assertEquals(1, result.size());
    var stored = result.getFirst();
    assertSame(stored, player.getInventory().getItemByUid(stored.getInternalUid()));
    assertEquals(relic, stored.getItemId());
    assertEquals(main.getAffixID(), stored.getMainAffix());
    assertTrue(
        stored.getSubAffixes().stream()
            .map(GameItemSubAffix::getId)
            .toList()
            .containsAll(List.of(subs[0], subs[1])));
    assertEquals(0, count(235));
    assertEquals(0, count(236));
    assertEquals(0, count(237));
    assertEquals(99000, player.getScoin());
    var attempt = player.getGameplay().getCompositionLimits().getLastAttempt();
    assertEquals("COMPLETE", attempt.getPhase());
    assertEquals(relic, attempt.getOutput());
    assertEquals(Map.of(235, 100, 236, 1, 237, 4), attempt.getCosts());
    assertTrue(attempt.getPlannedRelics().getFirst().matches(stored));
    assertTrue(attempt.getReceivedRelics().getFirst().matches(stored));
    assertTrue(player.getGameplay().getCompositionLimits().getWindows().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"RESERVED", "DEBITED"})
  void actualSaveInterruptionKeepsFrozenCostsAndRollsAndBlocksOtherCompositions(String phase) {
    stock(235, 900);
    stock(236, 1);
    stock(237, 4);
    interruptPhase = phase;
    assertThrows(IllegalStateException.class, () -> compose(1, 1, 2, 8));
    interruptPhase = null;
    var attempt = player.getGameplay().getCompositionLimits().getLastAttempt();
    assertEquals(phase, attempt.getPhase());
    assertTrue(player.getGameplay().getCompositionLimits().pending());
    assertEquals(Map.of(235, 100, 236, 1, 237, 4), attempt.getCosts());
    assertEquals(1, attempt.getPlannedRelics().size());
    assertEquals("RESERVED".equals(phase) ? 900 : 800, count(235));
    assertEquals("RESERVED".equals(phase) ? 100000 : 99000, player.getScoin());
    assertNull(compose(1, 1));
    assertNull(ItemComposition.compose(player, 101, 1, List.of()));
    assertSame(attempt, player.getGameplay().getCompositionLimits().getLastAttempt());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void actualRefusedOrPartialUniqueDeliveryIsPendingAndNeverReportedAsTwoRelics(int delivered) {
    stock(235, 1000);
    stock(236, 2);
    stock(237, 8);
    deliveryLimit = delivered;
    assertNull(compose(2, 1, 2, 8));
    var attempt = player.getGameplay().getCompositionLimits().getLastAttempt();
    assertEquals("PARTIAL", attempt.getPhase());
    assertTrue(player.getGameplay().getCompositionLimits().pending());
    assertEquals(800, count(235));
    assertEquals(0, count(236));
    assertEquals(0, count(237));
    assertEquals(98000, player.getScoin());
    assertEquals(delivered, attempt.getReceivedRelics().size());
    assertEquals(
        delivered,
        player.getInventory().getItems().values().stream()
            .filter(v -> v.getItemId() == 61011)
            .count());
    deliveryLimit = Integer.MAX_VALUE;
    assertNull(compose(1, 1));
    assertNull(ItemComposition.compose(player, 101, 1, List.of()));
  }

  @Test
  void simultaneousLastMaterialRequestsProduceOneOwnedRelic() throws Exception {
    stock(235, 100);
    stock(236, 1);
    var ready = new java.util.concurrent.CountDownLatch(2);
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var job =
          (java.util.concurrent.Callable<List<GameItem>>)
              () -> {
                ready.countDown();
                start.await();
                return compose(1, 1);
              };
      var a = pool.submit(job);
      var b = pool.submit(job);
      assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
      start.countDown();
      assertEquals(1, (a.get() != null ? 1 : 0) + (b.get() != null ? 1 : 0));
    }
    assertEquals(0, count(235));
    assertEquals(0, count(236));
    assertEquals(99000, player.getScoin());
    assertEquals(
        1,
        player.getInventory().getItems().values().stream()
            .filter(v -> v.getItemId() == 61011)
            .count());
  }

  class NativeSession extends GameSession {
    final Map<String, DynamicMessage> messages = new LinkedHashMap<>();

    NativeSession() {
      super(null);
      setState(SessionState.ACTIVE);
    }

    public Player getPlayer() {
      return player;
    }

    public ProtocolBridge getProtocol() {
      return ProtocolBridge.forVersion("4.6.0");
    }

    public void send(BasePacket packet) {}

    public void sendClientPacket(String name, byte[] bytes) {
      try {
        messages.put(name, getProtocol().clientSchema().parse(name, bytes));
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }

    DynamicMessage.Builder request(int main, int... subs) {
      var t = getProtocol().clientSchema().message("ComposeSelectedRelicCsReq");
      var b =
          DynamicMessage.newBuilder(t)
              .setField(t.findFieldByName("compose_id"), 9001)
              .setField(t.findFieldByName("compose_relic_id"), 61011)
              .setField(t.findFieldByName("count"), 1)
              .setField(t.findFieldByName("main_affix_id"), main);
      for (int id : subs) b.addRepeatedField(t.findFieldByName("sub_affix_id_list"), id);
      return b;
    }

    void sendCompose(byte[] body) throws Exception {
      assertTrue(VersionedPacketHandler.handle(this, 519, body));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void nativeSelectedAffixListActuallyStoresAndReturnsItsUniqueRelic(int selected)
      throws Exception {
    stock(235, 100);
    stock(236, 1);
    stock(237, 4);
    var s = new NativeSession();
    s.sendCompose(
        (selected == 0 ? s.request(1) : selected == 1 ? s.request(1, 2) : s.request(1, 2, 8))
            .build()
            .toByteArray());
    var response = s.messages.get("ComposeSelectedRelicScRsp");
    assertNotNull(response);
    assertEquals(0, response.getField(response.getDescriptorForType().findFieldByName("retcode")));
    var items =
        (DynamicMessage)
            response.getField(response.getDescriptorForType().findFieldByName("return_item_list"));
    assertEquals(
        1, items.getRepeatedFieldCount(items.getDescriptorForType().findFieldByName("item_list")));
    assertEquals(0, count(235));
    assertEquals(0, count(236));
    assertEquals(selected == 0 ? 4 : selected == 1 ? 3 : 0, count(237));
    assertEquals(
        1,
        player.getInventory().getItems().values().stream()
            .filter(v -> v.getItemId() == 61011)
            .count());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "unknown",
        "duplicate",
        "wire",
        "overflow",
        "duplicateSub",
        "uniqueCost",
        "wrongCost",
        "wrCost"
      })
  void malformedOrUnverifiedNativeRelicFieldsCannotDebit(String kind) throws Exception {
    stock(235, 100);
    stock(236, 1);
    stock(237, 4);
    var s = new NativeSession();
    var out = new ByteArrayOutputStream();
    out.write(s.request(1).build().toByteArray());
    var coded = com.google.protobuf.CodedOutputStream.newInstance(out);
    switch (kind) {
      case "unknown" -> coded.writeUInt32(99, 1);
      case "duplicate" -> coded.writeUInt32(3, 1);
      case "wire" -> coded.writeByteArray(3, new byte[0]);
      case "overflow" -> {
        out.reset();
        coded.writeUInt32(13, 9001);
        coded.writeUInt32(1, 61011);
        coded.writeUInt64(3, 0x100000001L);
        coded.writeUInt32(2, 1);
      }
      case "duplicateSub" -> {
        coded.writeUInt32(11, 2);
        coded.writeUInt32(11, 2);
      }
      case "uniqueCost" -> coded.writeByteArray(14, new byte[] {18, 2, 16, 1});
      case "wrongCost" -> coded.writeByteArray(14, new byte[] {18, 6, 82, 4, 8, 1, 16, 1});
      case "wrCost" -> coded.writeByteArray(6, new byte[0]);
    }
    coded.flush();
    s.sendCompose(out.toByteArray());
    var response = s.messages.get("ComposeSelectedRelicScRsp");
    assertNotNull(response);
    assertNotEquals(
        0, response.getField(response.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(100, count(235));
    assertEquals(1, count(236));
    assertEquals(4, count(237));
    assertEquals(100000, player.getScoin());
    assertNull(player.getGameplay().getCompositionLimits().getLastAttempt());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 20})
  void originalRandomOrSelectedMainProducesItsActualRequestedCount(int amount) {
    int count = amount == 0 ? 1 : amount;
    stock(235, count * 100);
    stock(236, count);
    var result = compose(count, amount == 0 ? 0 : 1);
    assertNotNull(result);
    assertEquals(count, result.size());
    assertEquals(0, count(235));
    assertEquals(amount == 0 ? count : 0, count(236));
    assertEquals(100000 - count * 1000, player.getScoin());
    for (var relic : result) {
      assertSame(relic, player.getInventory().getItemByUid(relic.getInternalUid()));
      assertEquals(1, relic.getCount());
      assertEquals(1, relic.getMainAffix());
      assertTrue(relic.getSubAffixes().size() >= 3 && relic.getSubAffixes().size() <= 4);
      assertFalse(relic.getSubAffixes().stream().anyMatch(a -> a.getId() == 1));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "unknownLimit",
        "specialCount",
        "specialIds",
        "formula",
        "emptyCost",
        "uniqueCost",
        "overflowCost",
        "emptyMains",
        "emptySubs",
        "missingRelic",
        "subWithoutMain",
        "tooManySubs"
      })
  void unknownOrDamagedSourceCannotCreateAFreeOrIncompleteRelic(String kind) {
    stock(235, 100);
    stock(236, 1);
    stock(237, 4);
    var r = row("recipe", 9001);
    switch (kind) {
      case "unknownLimit" -> r.addProperty("LimitType", "Daily");
      case "specialCount" -> r.addProperty("SpecialMaterialCostNumber", 2);
      case "specialIds" -> r.getAsJsonArray("SpecialMaterialCost").add(237);
      case "formula" -> r.addProperty("FormulaRequire", 404005);
      case "emptyCost" -> r.add("MaterialCost", new JsonArray());
      case "uniqueCost" ->
          r.getAsJsonArray("MaterialCost").get(0).getAsJsonObject().addProperty("ItemID", 61011);
      case "overflowCost" ->
          r.getAsJsonArray("MaterialCost")
              .get(0)
              .getAsJsonObject()
              .addProperty("ItemNum", Integer.MAX_VALUE);
      case "emptyMains" -> GameDepot.getRelicMainAffixesByGroup(51).clear();
      case "emptySubs" -> GameDepot.getRelicSubAffixesByGroup(5).clear();
      case "missingRelic" -> GameData.getRelicExcelMap().keySet().remove(61011);
    }
    GameData.getItemComposeExcelMap().add(gson.fromJson(r, ItemComposeExcel.class));
    var result =
        kind.equals("subWithoutMain")
            ? compose(1, 0, 2)
            : kind.equals("tooManySubs")
                ? compose(1, 1, 2, 3, 4)
                : compose(kind.equals("overflowCost") ? 2 : 1, 1);
    assertNull(result);
    assertEquals(100, count(235));
    assertEquals(1, count(236));
    assertEquals(4, count(237));
    assertEquals(100000, player.getScoin());
    assertNull(player.getGameplay().getCompositionLimits().getLastAttempt());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualMorphiaReloadKeepsThePlannedAndDeliveredRelicOrPendingDebit(boolean pending)
      throws Exception {
    stock(235, 100);
    stock(236, 1);
    stock(237, 4);
    if (pending) {
      interruptPhase = "DEBITED";
      assertThrows(IllegalStateException.class, () -> compose(1, 1, 2, 8));
      interruptPhase = null;
    } else assertNotNull(compose(1, 1, 2, 8));
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "selected-relic");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var persisted = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(persisted, 123460031);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(persisted, player.getGameplay());
      store.save(persisted);
      var loaded = store.find(Player.class).first();
      assertNotNull(loaded);
      var actual = loaded.getGameplay().getCompositionLimits();
      var a = actual.getLastAttempt();
      assertEquals(pending ? "DEBITED" : "COMPLETE", a.getPhase());
      assertEquals(pending, actual.pending());
      assertEquals(Map.of(235, 100, 236, 1, 237, 4), a.getCosts());
      assertEquals(61011, a.getOutput());
      assertEquals(
          player
              .getGameplay()
              .getCompositionLimits()
              .getLastAttempt()
              .getPlannedRelics()
              .getFirst()
              .getSteps(),
          a.getPlannedRelics().getFirst().getSteps());
      assertEquals(pending ? 0 : 1, a.getReceivedRelics().size());
      assertTrue(actual.getWindows().isEmpty());
      assertThrows(UnsupportedOperationException.class, () -> a.getPlannedRelics().clear());
      assertThrows(
          UnsupportedOperationException.class,
          () -> a.getPlannedRelics().getFirst().getSteps().clear());
      assertNull(compose(1, 1, 2, 8));
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void nativePendingActualDebitHasBusyResponseAndNoRelicOutput() throws Exception {
    stock(235, 100);
    stock(236, 1);
    interruptPhase = "DEBITED";
    assertThrows(IllegalStateException.class, () -> compose(1, 1));
    interruptPhase = null;
    var s = new NativeSession();
    s.sendCompose(s.request(1).build().toByteArray());
    var r = s.messages.get("ComposeSelectedRelicScRsp");
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY.getVal(),
        r.getField(r.getDescriptorForType().findFieldByName("retcode")));
    var list =
        (DynamicMessage) r.getField(r.getDescriptorForType().findFieldByName("return_item_list"));
    assertEquals(
        0, list.getRepeatedFieldCount(list.getDescriptorForType().findFieldByName("item_list")));
    assertEquals(
        "DEBITED", player.getGameplay().getCompositionLimits().getLastAttempt().getPhase());
  }

  @Test
  void sourceStepOverflowCannotThrowOrDebitBeforeRelicPreparation() {
    stock(235, 100);
    stock(236, 1);
    stock(237, 1);
    var original = GameData.getRelicSubAffixExcelMap().get(5, 2);
    var changed = gson.toJsonTree(original).getAsJsonObject();
    changed.addProperty("StepNum", Integer.MAX_VALUE);
    var bad = gson.fromJson(changed, RelicSubAffixExcel.class);
    install(GameData.getRelicSubAffixExcelMap(), bad);
    var list = GameDepot.getRelicSubAffixesByGroup(5);
    list.set(list.indexOf(original), bad);
    assertNull(assertDoesNotThrow(() -> compose(1, 1, 2)));
    assertEquals(100, count(235));
    assertEquals(1, count(236));
    assertEquals(1, count(237));
    assertEquals(100000, player.getScoin());
    assertNull(player.getGameplay().getCompositionLimits().getLastAttempt());
  }

  @Test
  void exhaustedUniqueInventoryIdsCannotDebitAndCreateNegativeClientIds() throws Exception {
    stock(235, 100);
    stock(236, 1);
    var next = Inventory.class.getDeclaredField("nextInternalUid");
    next.setAccessible(true);
    next.setInt(player.getInventory(), Integer.MAX_VALUE);
    assertNull(compose(1, 1));
    assertEquals(100, count(235));
    assertEquals(1, count(236));
    assertEquals(100000, player.getScoin());
    assertNull(player.getGameplay().getCompositionLimits().getLastAttempt());
  }
}
