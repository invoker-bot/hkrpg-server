package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.server.packet.Retcode;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original first-world G22 binding; DLC G35 is used only to exercise the existing source shop. */
class UniverseRestMerchantRecipe46Test {
  static final String COMMIT = "724b139d8c9c32d12552eb95745a4fee72bfe48b";
  static final String GROUP = "Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F80001003/LevelGroup_P80001_F80001003_G22.json";
  static final String DIALOGUE = "Config/Level/RogueDialogue/RogueDialogueEvent/Act/Act403810002.json";
  static final String SOURCE_GROUP = "Config/LevelOutput/SharedRuntimeGroup/Groups_P80201_F80201001/LevelGroup_P80201_F80201001_G35.json";
  static final int ENTITY = 199973, BUFF = 612050, SOURCE_ROOM = 1999935;
  static final Gson GSON = new Gson();
  static final Gson RESOURCE_GSON = ResourceLoader.getGson();
  @TempDir Path root;
  final JsonObject fixture = JsonParser.parseReader(new java.io.InputStreamReader(
      Objects.requireNonNull(getClass().getResourceAsStream("/fixtures/rogue-rest-merchants-4.6.json")),
      StandardCharsets.UTF_8)).getAsJsonObject();
  final Map<java.lang.reflect.Field, Object> definitionsBefore = new HashMap<>();
  final Map<String, FloorInfo> floorsBefore = new HashMap<>();
  final List<MapEntranceExcel> entriesBefore = new ArrayList<>();
  final List<NpcExcel> npcsBefore = new ArrayList<>();
  final List<MazeBuffExcel> buffsBefore = new ArrayList<>();
  Object handbookBefore;
  String oldResources, oldData;
  Session session;
  EntityNpc npc;

  static class Explorer extends Player {
    GameplayState state = new GameplayState();
    Scene scene;
    FloorInfo floor;
    int entry, saves;
    boolean interruptSave;
    final List<JsonElement> saved = new ArrayList<>();
    final PlayerLineup lineup = new PlayerLineup(this, 0);
    public GameplayState getGameplay() { return state; }
    public PlayerLineup getCurrentLineup() { return lineup; }
    public Scene getScene() { return scene; }
    public int getEntryId() { return entry; }
    public void save() {
      if (interruptSave) throw new IllegalStateException("Injected rest-merchant save failure");
      saved.add(GSON.toJsonTree(state));
      saves++;
    }
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final Explorer explorer = new Explorer();
    public Player getPlayer() { return explorer; }
  }

  JsonArray rows(String table) {
    return fixture.getAsJsonArray("rows").asList().stream().map(JsonElement::getAsJsonObject)
        .filter(row -> row.get("table").getAsString().equals(table)).findFirst().orElseThrow()
        .getAsJsonArray("rows");
  }

  JsonObject sourceFile(String path) {
    return fixture.getAsJsonArray("files").asList().stream().map(JsonElement::getAsJsonObject)
        .filter(row -> row.get("path").getAsString().equals(path)).findFirst().orElseThrow();
  }

  JsonObject recipe(boolean enabled, int price, int stock) {
    var rule = JsonParser.parseString("""
        {"schema":1,"enabled":true,"ruleId":"hkrpg-first-world-rest-curio-v1",
          "fragmentBasePrice":200,"stockLimit":3,
          "binding":{"area":110,"map":101,"site":6,"room":306,"entry":8000103,
            "plane":80001,"floor":80001003,"group":22,"npc":1013,"instance":400001,
            "sourceCommit":"724b139d8c9c32d12552eb95745a4fee72bfe48b",
            "groupPath":"Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F80001003/LevelGroup_P80001_F80001003_G22.json",
            "groupNormalizedSHA256":"50aeab6d84c933dacdbac2ce2dac505448199170045cc43e72f66ce21ea4de59",
            "dialoguePath":"Config/Level/RogueDialogue/RogueDialogueEvent/Act/Act403810002.json",
            "dialogueNormalizedSHA256":"fe0ecd2145033bfbc886a0d40cc0c21d6b1677deae00f0b59d22942eb6e21361"}}
        """).getAsJsonObject();
    rule.addProperty("enabled", enabled);
    rule.addProperty("fragmentBasePrice", price);
    rule.addProperty("stockLimit", stock);
    return rule;
  }

  void writeRecipe(JsonObject rule) throws Exception {
    Files.writeString(root.resolve("data/RogueRestMerchants.json"), GSON.toJson(rule));
  }

  void reloadDefinitions() {
    GameplayDefinitions.load(root);
    RogueHandbookCatalog.load(root);
  }

  void catalog(int... ids) {
    GameplayDefinitions.miracles.clear();
    GameplayDefinitions.miracleEffects.clear();
    var enabled = Arrays.stream(ids).boxed().collect(java.util.stream.Collectors.toSet());
    for (var row : rows("RogueMiracle")) {
      var value = GSON.fromJson(row, GameplayDefinitions.Miracle.class);
      if (enabled.contains(value.MiracleID())) GameplayDefinitions.miracles.put(value.MiracleID(), value);
    }
    for (var row : rows("RogueMiracleEffect")) {
      var value = GSON.fromJson(row, GameplayDefinitions.MiracleEffect.class);
      if (enabled.contains(value.MiracleEffectID()))
        GameplayDefinitions.miracleEffects.put(value.MiracleEffectID(), value);
    }
  }

  @BeforeEach
  void setup() throws Exception {
    oldResources = LunarCore.getConfig().resourceDir;
    oldData = LunarCore.getConfig().dataDir;
    for (var field : GameplayDefinitions.class.getFields()) {
      if (!Modifier.isStatic(field.getModifiers())) continue;
      var value = field.get(null);
      if (value instanceof Map<?, ?> map) definitionsBefore.put(field, new HashMap<>(map));
      if (value instanceof Collection<?> list) definitionsBefore.put(field, new ArrayList<>(list));
    }
    var handbook = RogueHandbookCatalog.class.getDeclaredField("miracles");
    handbook.setAccessible(true);
    handbookBefore = handbook.get(null);
    floorsBefore.putAll(GameData.getFloorInfos());
    entriesBefore.addAll(GameData.getMapEntranceExcelMap().values());
    npcsBefore.addAll(GameData.getNpcExcelMap().values());
    buffsBefore.addAll(GameData.getMazeBuffExcelMap().values());
    LunarCore.getConfig().resourceDir = root.toString();
    LunarCore.getConfig().dataDir = root.resolve("data").toString();
    Files.createDirectories(root.resolve("data"));
    Files.createDirectories(root.resolve("ExcelOutput"));
    for (var raw : fixture.getAsJsonArray("files")) {
      var file = raw.getAsJsonObject();
      var path = root.resolve(file.get("path").getAsString());
      Files.createDirectories(path.getParent());
      Files.write(path, Base64.getDecoder().decode(file.get("rawBase64").getAsString()));
    }
    for (var raw : fixture.getAsJsonArray("rows")) {
      var table = raw.getAsJsonObject();
      Files.writeString(root.resolve(table.get("path").getAsString()), GSON.toJson(table.get("rows")));
    }
    writeRecipe(recipe(true, 200, 3));
    reloadDefinitions();
    catalog(4);
    for (var row : rows("MapEntrance")) GameData.getMapEntranceExcelMap().add(RESOURCE_GSON.fromJson(row, MapEntranceExcel.class));
    for (var row : rows("NPCData")) GameData.getNpcExcelMap().add(RESOURCE_GSON.fromJson(row, NpcExcel.class));
    for (var row : rows("RogueMazeBuff")) GameData.getMazeBuffExcelMap().add(RESOURCE_GSON.fromJson(row, MazeBuffExcel.class));
    session = new Session();
    var run = new GameplayState.RogueRun(110, 101,
        Map.of(1, 304, 2, 302, 3, 303, 4, 304, 5, 305, 6, 306, 7, 307));
    run.enter(6, 306);
    run.setInside(true);
    run.getRoster().add(1001);
    run.addCoins(200); // Isolated wallet fixture; not the production seven-site reward balance.
    p().state.setRogue(run);
    scene(8000103, 80001, 80001003, 22, GROUP);
  }

  @AfterEach
  @SuppressWarnings({"rawtypes", "unchecked"})
  void cleanup() throws Exception {
    LunarCore.getConfig().resourceDir = oldResources;
    LunarCore.getConfig().dataDir = oldData;
    for (var saved : definitionsBefore.entrySet()) {
      var current = saved.getKey().get(null);
      if (current instanceof Map map) { map.clear(); map.putAll((Map) saved.getValue()); }
      if (current instanceof Collection list) { list.clear(); list.addAll((Collection) saved.getValue()); }
    }
    var handbook = RogueHandbookCatalog.class.getDeclaredField("miracles");
    handbook.setAccessible(true);
    handbook.set(null, handbookBefore);
    GameData.getFloorInfos().clear();
    GameData.getFloorInfos().putAll(floorsBefore);
    GameData.getMapEntranceExcelMap().keySet().clear();
    entriesBefore.forEach(GameData.getMapEntranceExcelMap()::add);
    GameData.getNpcExcelMap().keySet().clear();
    npcsBefore.forEach(GameData.getNpcExcelMap()::add);
    GameData.getMazeBuffExcelMap().values().clear();
    buffsBefore.forEach(GameData.getMazeBuffExcelMap()::add);
  }

  Explorer p() { return session.explorer; }
  GameplayState.RogueRun run() { return p().state.getRogue(); }
  JsonElement state() { return GSON.toJsonTree(p().state); }
  List<RogueMiracleShop.Quote> quotes() { return RogueMiracleShop.quotes(p(), ENTITY); }
  Retcode buy(int id) { return RogueMiracleShop.buy(p(), ENTITY, id); }

  void scene(int entry, int plane, int floor, int groupId, String source) {
    p().entry = entry;
    p().floor = GSON.fromJson("{\"FloorID\":" + floor + "}", FloorInfo.class);
    GameData.getFloorInfos().put("P" + plane + "_F" + floor, p().floor);
    var planeRow = rows("MazePlane").asList().stream()
        .filter(r -> r.getAsJsonObject().get("PlaneID").getAsInt() == plane).findFirst().orElseThrow();
    p().scene = new Scene(p(), RESOURCE_GSON.fromJson(planeRow, MazePlaneExcel.class), floor);
    p().scene.setEntryId(entry);
    var group = RESOURCE_GSON.fromJson(new String(Base64.getDecoder().decode(
        sourceFile(source).get("rawBase64").getAsString()), StandardCharsets.UTF_8), GroupInfo.class);
    group.setId(groupId);
    p().floor.getGroups().put(groupId, group);
    // Use the existing production post-load transformation before comparing the whole source group.
    p().floor.onLoad();
    var info = group.getNPCList().stream().filter(n -> n.getID() == 400001 && n.getNPCID() == 1013)
        .findFirst().orElseThrow();
    npc = new EntityNpc(p().scene, group, info);
    npc.setEntityId(ENTITY);
    p().scene.getEntities().put(ENTITY, npc);
  }

  void originalSourceMerchant() {
    // This synthetic room explicitly selects an unchanged original DLC placement for transaction tests.
    // The production room306 and all seven assignments in the source fixture remain unchanged.
    GameplayDefinitions.rooms.put(SOURCE_ROOM, new GameplayDefinitions.Room(SOURCE_ROOM, 5, 8020101, 35));
    run().getRooms().put(6, SOURCE_ROOM);
    run().enter(6, SOURCE_ROOM);
    scene(8020101, 80201, 80201001, 35, SOURCE_GROUP);
    assertEquals(Retcode.SUCC, RogueMiracleShop.status(p(), ENTITY));
  }

  byte[] request(int entity, int id, boolean refresh) throws Exception {
    var buffer = new java.io.ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(buffer);
    out.writeUInt32(12, entity);
    if (id > 0) out.writeUInt32(6, id);
    if (refresh) out.writeBool(13, true);
    out.flush();
    return buffer.toByteArray();
  }

  DynamicMessage call(int command, byte[] body, String response) throws Exception {
    session.response = null;
    session.responseName = null;
    assertTrue(VersionedPacketHandler.handle(session, command, body));
    assertEquals(response, session.responseName);
    return session.response;
  }

  DynamicMessage query() throws Exception {
    var response = call(5675, request(ENTITY, 0, false), "GetRogueShopMiracleInfoScRsp");
    assertEquals(0, Gameplay46.number(response, "retcode"), "Original G22 must admit an enabled server recipe");
    return response;
  }

  DynamicMessage row(DynamicMessage response) {
    return InitialData46Test.first((DynamicMessage) Gameplay46.value(response, "DNPCCFFFBHN"), "MAJGMMCOCIG");
  }

  @Test
  void originalSixthSiteNativeQueryAndPurchaseHaveActualCostOwnershipAndUuid() throws Exception {
    var queried = query();
    assertEquals(1, p().saves, "Stock and its source lease must be persisted before the native response");
    assertEquals(4, Gameplay46.number(row(queried), "LJCEOGPOGBE"));
    var cost = (DynamicMessage) Gameplay46.value(row(queried), "cost_data");
    var pile = (DynamicMessage) Gameplay46.value(InitialData46Test.first(cost, "item_list"), "pile_item");
    assertEquals(31, Gameplay46.number(pile, "item_id"));
    assertEquals(200, Gameplay46.number(pile, "item_num"));
    var bought = call(5656, request(ENTITY, 4, false), "BuyRogueShopMiracleScRsp");
    assertEquals(0, Gameplay46.number(bought, "retcode"));
    assertEquals(true, Gameplay46.value(row(bought), "BELPGDAGFIH"));
    assertEquals(true, Gameplay46.value(row(bought), "GCIHGKCJOGB"));
    assertEquals(100, run().getCoins());
    assertFalse(run().getMiracles().get(4).getAcquisitionId().isBlank());
    assertEquals(Set.of(4), p().state.getRogueHandbook().getMiracles());
    assertEquals(2, p().saves);
    assertEquals(List.of(4), quotes().stream().map(RogueMiracleShop.Quote::id).toList());
    assertTrue(quotes().getFirst().purchased());
  }

  @Test
  void actualFloorPostLoadKeepsTheOriginalG22MerchantSourceUsable() throws Exception {
    var group = RESOURCE_GSON.fromJson(new String(Base64.getDecoder().decode(
        sourceFile(GROUP).get("rawBase64").getAsString()), StandardCharsets.UTF_8), GroupInfo.class);
    group.setId(22);
    assertNotNull(group.getPropList().get(6).getValueSource());
    var originalFloor = "Config/LevelOutput/RuntimeFloor/P80001_F80001003.json";
    p().floor = RESOURCE_GSON.fromJson(new String(Base64.getDecoder().decode(
        sourceFile(originalFloor).get("rawBase64").getAsString()), StandardCharsets.UTF_8), FloorInfo.class);
    GameData.getFloorInfos().put("P80001_F80001003", p().floor);
    var plane = rows("MazePlane").asList().stream()
        .filter(r -> r.getAsJsonObject().get("PlaneID").getAsInt() == 80001).findFirst().orElseThrow();
    p().scene = new Scene(p(), RESOURCE_GSON.fromJson(plane, MazePlaneExcel.class), 80001003);
    p().scene.setEntryId(8000103);
    int index = java.util.stream.IntStream.range(0, p().floor.getSimpleGroupList().size())
        .filter(i -> p().floor.getSimpleGroupList().get(i).getID() == 22).findFirst().orElseThrow();
    assertEquals(GROUP, p().floor.getSimpleGroupList().get(index).getGroupPath());
    p().floor.registerGroup(index, group);
    p().floor.onLoad();
    assertNull(group.getPropList().get(6).getValueSource());
    npc = new EntityNpc(p().scene, group, group.getNPCList().getFirst());
    npc.setEntityId(ENTITY);
    p().scene.getEntities().put(ENTITY, npc);
    assertEquals(Retcode.SUCC, RogueMiracleShop.status(p(), ENTITY));
    assertEquals(1, quotes().size());
    assertEquals(Retcode.SUCC, buy(4));
    assertEquals(100, run().getCoins());
    assertTrue(run().getMiracleShops().get("6:306:22:400001").getRestMerchant().getPurchases().containsKey(4));
  }

  @Test
  void configuredPriceAndStockLimitAreExplicitServerPolicy() throws Exception {
    writeRecipe(recipe(true, 137, 1));
    reloadDefinitions();
    catalog(4, 17, 50);
    query();
    var offered = quotes();
    assertEquals(1, offered.size());
    assertEquals(137, offered.getFirst().cost());
    assertEquals(Retcode.SUCC, buy(offered.getFirst().id()));
    assertEquals(163, run().getCoins());
  }

  @Test
  void aRecipePriceReloadCannotRepriceTheAlreadyPersistedStock() throws Exception {
    query();
    var frozen = quotes();
    writeRecipe(recipe(true, 137, 1));
    reloadDefinitions();
    catalog(4);
    assertEquals(frozen, quotes());
    assertEquals(Retcode.SUCC, buy(4));
    assertEquals(100, run().getCoins());
    var room = GameplayDefinitions.rooms.get(306);
    assertEquals(8000103, room.MapEntrance());
    assertEquals(Map.of(22, 0), room.GroupWithContent());
  }

  @Test
  void disablingTheRecipeClosesItsFrozenStockWithoutDebiting() throws Exception {
    query();
    var before = state();
    writeRecipe(recipe(false, 200, 3));
    reloadDefinitions();
    catalog(4);
    assertNotEquals(Retcode.SUCC, buy(4));
    assertEquals(before, state());
    assertTrue(quotes().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"wrong-group", "wrong-dialogue", "wrong-room", "wrong-map", "wrong-site", "wrong-actor",
      "wrong-entry", "wrong-npc", "missing-roster", "wrong-runtime-graph"})
  void aFrozenRecipeStillRequiresTheOriginalSceneSourceAndActor(String wrong) throws Exception {
    query();
    int saves = p().saves;
    if (wrong.equals("wrong-group")) Files.writeString(root.resolve(GROUP), Files.readString(root.resolve(GROUP)) + " ");
    if (wrong.equals("wrong-dialogue")) Files.writeString(root.resolve(DIALOGUE), Files.readString(root.resolve(DIALOGUE)) + " ");
    if (wrong.equals("wrong-room")) run().getRooms().put(6, 303);
    if (wrong.equals("wrong-map")) {
      var field = GameplayState.RogueRun.class.getDeclaredField("mapId");
      field.setAccessible(true); field.setInt(run(), 102);
    }
    if (wrong.equals("wrong-site")) run().enter(3, 303);
    if (wrong.equals("wrong-actor")) npc.setInstId(400002);
    if (wrong.equals("wrong-entry")) p().entry = 8000102;
    if (wrong.equals("wrong-npc")) npc.setNpcId(1007);
    if (wrong.equals("missing-roster")) run().getRoster().clear();
    if (wrong.equals("wrong-runtime-graph")) {
      var info = p().floor.getGroups().get(22).getNPCList().stream().filter(n -> n.getID() == 400001)
          .findFirst().orElseThrow();
      var field = NpcInfo.DialogInfo.class.getDeclaredField("LevelGraph");
      field.setAccessible(true); field.set(info.getDialog(), "Config/Level/fake-shop.json");
    }
    var before = state();
    assertNotEquals(Retcode.SUCC, buy(4));
    assertTrue(quotes().isEmpty());
    assertEquals(saves, p().saves);
    assertEquals(before, state());
  }

  @Test
  void originalThirdSiteTutorialNeverInheritsTheSixthSiteRecipe() throws Exception {
    query();
    run().enter(3, 303);
    scene(8000102, 80001, 80001002, 34,
        "Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F80001002/LevelGroup_P80001_F80001002_G34.json");
    assertNotEquals(Retcode.SUCC, RogueMiracleShop.status(p(), ENTITY));
    assertTrue(quotes().isEmpty());
    assertNotEquals(Retcode.SUCC, buy(4));
    assertEquals(300, run().getCoins());
  }

  @Test
  void aPausedRunOrPendingRewardCannotUseTheRecipe() throws Exception {
    query();
    run().setInside(false);
    assertNotEquals(Retcode.SUCC, buy(4));
    run().setInside(true);
    run().getMiracleOffers().add(17);
    assertNotEquals(Retcode.SUCC, buy(4));
    run().getMiracleOffers().clear();
    assertEquals(Retcode.SUCC, buy(4));
  }

  @Test
  void duplicateAndConcurrentPurchasesAcquireOneUuidAndDebitOnce() throws Exception {
    query();
    try (var executor = Executors.newFixedThreadPool(6)) {
      var calls = new ArrayList<Future<Retcode>>();
      for (int i = 0; i < 6; i++) calls.add(executor.submit(() -> buy(4)));
      int successful = 0;
      for (var result : calls) if (result.get(8, TimeUnit.SECONDS) == Retcode.SUCC) successful++;
      assertEquals(1, successful);
    }
    String uuid = run().getMiracles().get(4).getAcquisitionId();
    assertNotEquals(Retcode.SUCC, buy(4));
    assertEquals(uuid, run().getMiracles().get(4).getAcquisitionId());
    assertEquals(100, run().getCoins());
    assertEquals(2, p().saves);
  }

  @Test
  void malformedNativeRequestsCannotConsumeFrozenStock() throws Exception {
    query();
    var good = request(ENTITY, 4, false);
    var doubled = new byte[good.length * 2];
    System.arraycopy(good, 0, doubled, 0, good.length);
    System.arraycopy(good, 0, doubled, good.length, good.length);
    var wide = UnknownFieldSet.newBuilder().addField(12,
        UnknownFieldSet.Field.newBuilder().addVarint(ENTITY).build()).addField(6,
        UnknownFieldSet.Field.newBuilder().addVarint((1L << 32) + 4).build()).build().toByteArray();
    for (var invalid : List.of(doubled, wide, new byte[] {96, (byte) 128}, request(ENTITY, 4, true),
        request(ENTITY + 1, 4, false), new byte[0])) {
      var response = call(5656, invalid, "BuyRogueShopMiracleScRsp");
      assertNotEquals(0, Gameplay46.number(response, "retcode"));
    }
    assertEquals(300, run().getCoins());
    assertTrue(run().getMiracles().isEmpty());
    assertEquals(Retcode.SUCC, buy(4));
  }

  @ParameterizedTest
  @ValueSource(strings = {"fractional-price", "negative-price", "stock-four", "stock-zero", "string-enabled", "unknown-field"})
  void malformedServerRecipesFailLoadingWithoutBeingSilentlyIgnored(String invalid) throws Exception {
    var rule = recipe(true, 200, 3);
    switch (invalid) {
      case "fractional-price" -> rule.addProperty("fragmentBasePrice", .5);
      case "negative-price" -> rule.addProperty("fragmentBasePrice", -1);
      case "stock-four" -> rule.addProperty("stockLimit", 4);
      case "stock-zero" -> rule.addProperty("stockLimit", 0);
      case "string-enabled" -> rule.addProperty("enabled", "true");
      case "unknown-field" -> rule.addProperty("inventedOpenUiCommand", 3259);
      default -> throw new AssertionError(invalid);
    }
    writeRecipe(rule);
    assertThrows(IllegalStateException.class, this::reloadDefinitions);
  }

  @Test
  void bundledRecipeStartsDisabledAndDefaultInstallPreservesExactOperatorBytes() throws Exception {
    var directory = root.resolve("new-defaults");
    org.hkrpg.server.DefaultData.install(directory);
    var bundled = JsonParser.parseString(Files.readString(directory.resolve("RogueRestMerchants.json"))).getAsJsonObject();
    assertFalse(bundled.get("enabled").getAsBoolean());
    assertEquals(200, bundled.get("fragmentBasePrice").getAsInt());
    assertEquals(3, bundled.get("stockLimit").getAsInt());
    var operator = " {\n  \"operator\": \"do not replace this file\"\n} \n";
    Files.writeString(directory.resolve("RogueRestMerchants.json"), operator);
    org.hkrpg.server.DefaultData.install(directory);
    assertEquals(operator, Files.readString(directory.resolve("RogueRestMerchants.json")));
  }

  @Test
  void failedServerRecipeFirstQuoteCannotExposeOrRetainAnUnacknowledgedLease() throws Exception {
    var before = state();
    p().interruptSave = true;
    assertThrows(IllegalStateException.class, this::query);
    assertNull(session.response);
    assertEquals(before, state());
    assertTrue(run().getMiracleShops().isEmpty());
    p().interruptSave = false;
    query();
    assertEquals(1, p().saves);
    assertNotNull(run().getMiracleShops().get("6:306:22:400001").getRestMerchant());
  }

  @Test
  void oldUnboundStockAlsoNeedsASuccessfulSourceLeaseSaveBeforeExposure() throws Exception {
    run().getMiracleShops().put("6:306:22:400001",
        new GameplayState.RogueShopPlan(Map.of(4, new GameplayState.RogueShopOffer(1, 200))));
    assertNotEquals(Retcode.SUCC, buy(4), "An old stock shape is not an acknowledged source lease");
    var before = state();
    p().interruptSave = true;
    assertThrows(IllegalStateException.class, this::query);
    assertEquals(before, state());
    assertNull(session.response);
    assertNull(run().getMiracleShops().get("6:306:22:400001").getRestMerchant());
    p().interruptSave = false;
    query();
    assertEquals(1, p().saves);
    assertNotNull(run().getMiracleShops().get("6:306:22:400001").getRestMerchant());
    assertEquals(Retcode.SUCC, buy(4));
  }

  @Test
  void serverRecipePurchaseFailureRestoresTheReceiptAndCanRetryOnce() throws Exception {
    query();
    failedPurchase(4);
    var lease = run().getMiracleShops().get("6:306:22:400001").getRestMerchant();
    assertEquals(1, lease.getPurchases().size());
    assertEquals(List.of(run().getMiracles().get(4).getAcquisitionId()), lease.getPurchases().get(4).getAcquisitions());
    assertEquals(100, run().getCoins());
  }

  @Test
  void aDeletedReceiptDoesNotRegenerateAnAlreadySoldServerQuote() throws Exception {
    query();
    assertEquals(Retcode.SUCC, buy(4));
    run().getMiracleShops().get("6:306:22:400001").getRestMerchant().getPurchases().clear();
    var before = state();
    assertNotEquals(Retcode.SUCC, RogueMiracleShop.status(p(), ENTITY));
    assertNotEquals(Retcode.SUCC, buy(4));
    assertTrue(quotes().isEmpty());
    assertEquals(before, state());
  }

  @Test
  void failedSourceShopFirstQuoteRollsBackUnacknowledgedStockAndCanRetry() throws Exception {
    originalSourceMerchant();
    var before = state();
    p().interruptSave = true;
    assertThrows(IllegalStateException.class, () -> quotes());
    assertEquals(before, state(), "The failed save must not leave an apparently persisted stock");
    assertTrue(run().getMiracleShops().isEmpty());
    p().interruptSave = false;
    assertEquals(1, quotes().size());
    assertEquals(1, p().saves);
    quotes();
    assertEquals(1, p().saves);
  }

  @Test
  void failedSourceShopNativeQuoteSendsNoSuccessfulMenu() throws Exception {
    originalSourceMerchant();
    var before = state();
    p().interruptSave = true;
    assertThrows(IllegalStateException.class,
        () -> call(5675, request(ENTITY, 0, false), "GetRogueShopMiracleInfoScRsp"));
    assertNull(session.response);
    assertEquals(before, state());
  }

  void failedPurchase(int id) {
    assertTrue(quotes().stream().anyMatch(quote -> quote.id() == id), "The actual effect must be offered before save injection");
    var before = state();
    var sameRun = run();
    int saves = p().saves;
    p().interruptSave = true;
    assertThrows(IllegalStateException.class, () -> buy(id));
    assertEquals(before, state(), "All real curio effects, receipt, stock and wallet must roll back together");
    assertSame(sameRun, run(), "Rollback must preserve live run and scene references");
    assertEquals(saves, p().saves);
    p().interruptSave = false;
    assertEquals(Retcode.SUCC, buy(id));
    assertTrue(quotes().stream().anyMatch(quote -> quote.id() == id && quote.purchased()));
  }

  @Test
  void failedSourceShopPurchaseDoesNotLeakWalletMiracleUuidOrHandbook() {
    originalSourceMerchant();
    failedPurchase(4);
    assertEquals(100, run().getCoins());
    assertEquals(Set.of(4), p().state.getRogueHandbook().getMiracles());
  }

  @Test
  void failedSourceShopRepairRestoresTheActuallyBrokenMiracle() {
    originalSourceMerchant();
    catalog(4, 17);
    var broken = new GameplayState.RogueMiracle(1, List.of(1.0));
    broken.consume();
    run().getMiracles().put(4, broken);
    String uuid = broken.getAcquisitionId();
    failedPurchase(17);
    assertEquals(uuid, run().getMiracles().get(4).getAcquisitionId());
    assertFalse(run().getMiracles().get(4).broken());
  }

  @Test
  void failedSourceShopUpgradeRestoresBlessingLevelAndPermanentEnhancementCounter() {
    originalSourceMerchant();
    catalog(20);
    run().getBlessings().put(BUFF, 1);
    failedPurchase(20);
    assertEquals(2, run().getBlessings().get(BUFF));
    assertEquals(1, p().state.getRogueEnhancements().total());
  }

  @Test
  void failedSourceShopImmediateBlessingRestoresCollectionAndCurioRecord() {
    originalSourceMerchant();
    catalog(5);
    failedPurchase(5);
    assertEquals(Map.of(BUFF, 1), run().getBlessings());
    assertEquals(Set.of(BUFF), p().state.getRogueCollectedBuffs());
  }

  @Test
  void failedSourceShopThirdPathBlessingRestoresTheQueuedResonanceAndExistingPlan() throws Exception {
    originalSourceMerchant();
    var aeons = RogueAeonCatalog.class.getDeclaredField("catalogue");
    var events = RogueResonance.class.getDeclaredField("events");
    aeons.setAccessible(true); events.setAccessible(true);
    var oldAeons = aeons.get(null); var oldEvents = events.get(null);
    try {
      JsonObject proof;
      try (var input = getClass().getResourceAsStream("/fixtures/rogue-resonance-4.6.json")) {
        proof = JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
      }
      GameplayDefinitions.blessings.clear();
      GameplayDefinitions.rogueBuffGroups.clear();
      for (var raw : proof.getAsJsonArray("groups")) {
        var group = RESOURCE_GSON.fromJson(raw, GameplayDefinitions.RogueBuffGroup.class);
        GameplayDefinitions.rogueBuffGroups.put(group.GMLOGNJAIGI(), group);
      }
      var normals = new ArrayList<Integer>();
      for (String table : List.of("buffs", "normalBuffs")) {
        for (var raw : proof.getAsJsonArray(table)) {
          var row = RESOURCE_GSON.fromJson(raw, GameplayDefinitions.Blessing.class);
          if (row.RogueBuffType() != 120 || table.equals("normalBuffs") && normals.size() >= 3) continue;
          GameplayDefinitions.blessings.add(row);
          assertNotNull(GameData.getMazeBuffExcelMap().get(row.MazeBuffID(), 1), "Complete original MazeBuff row must exist");
          if (table.equals("normalBuffs")) normals.add(row.MazeBuffID());
        }
      }
      for (var source : Map.of("RogueAeon", "aeons", "BattleEventData", "events",
          "BattleEventConfig", "eventConfigs", "BattleEventSkillConfig", "skills").entrySet())
        Files.writeString(root.resolve("ExcelOutput/" + source.getKey() + ".json"), proof.get(source.getValue()).toString());
      RogueAeonCatalog.load(root);
      var next = new GameplayState.RogueRun(110, 101, run().getRooms(), null, 1);
      next.enter(6, SOURCE_ROOM); next.setInside(true); next.getRoster().add(1001); next.addCoins(200);
      next.getBlessings().put(normals.get(0), 1); next.getBlessings().put(normals.get(1), 1);
      p().state.setRogue(next);
      p().state.getRogueCollectedBuffs().addAll(normals.subList(0, 2));
      catalog(5);
      assertEquals(List.of(5), quotes().stream().map(RogueMiracleShop.Quote::id).toList());
      var before = state(); var plan = run().getResonance();
      assertNotNull(plan); assertTrue(plan.valid(1));
      p().interruptSave = true;
      assertThrows(IllegalStateException.class, () -> buy(5));
      assertEquals(before, state(), "A curio grant and the resulting resonance selection must roll back together");
      assertSame(plan, run().getResonance());
      p().interruptSave = false;
      assertEquals(Retcode.SUCC, buy(5));
      assertEquals(100, run().getCoins());
      assertTrue(run().getBlessings().containsKey(normals.get(2)));
      assertEquals(List.of(plan.getBasic()), run().getOffers());
    } finally { aeons.set(null, oldAeons); events.set(null, oldEvents); }
  }

  @Test
  void failedSourceShopClockPaymentRestoresTheAcquisitionCounterAndFrozenCost() {
    originalSourceMerchant();
    catalog(4, 70);
    var clock = new GameplayState.RogueMiracle(0, List.of(.25));
    run().getMiracles().put(70, clock);
    failedPurchase(4);
    assertEquals(50, run().getCoins());
    assertEquals(50, run().getMiracles().get(70).getExtraFragmentsSpent());
    assertEquals(clock.getAcquisitionId(), run().getMiracles().get(70).getAcquisitionId());
  }

  @Test
  void failedSourceShopClockBreakRestoresRemovedCurioReplacementUuidAndFragmentGrant() throws Exception {
    originalSourceMerchant();
    catalog(4, 9, 70);
    var clock = new GameplayState.RogueMiracle(0, List.of(.25));
    var breakParameters = GameplayState.RogueMiracle.class.getDeclaredField("clockBreakParameters");
    breakParameters.setAccessible(true);
    breakParameters.set(clock, new ArrayList<>(List.of(499.0, 999.0)));
    clock.recordExtraFragments(490);
    run().getMiracles().put(70, clock);
    failedPurchase(4);
    assertEquals(1049, run().getCoins());
    assertFalse(run().getMiracles().containsKey(70));
    assertFalse(run().getMiracles().get(9).getAcquisitionId().isBlank());
    assertEquals(1, run().getMiracleFragmentGrants().size());
  }

  @Test
  void failedSourceShopDiceRestoresTheOriginalAcquisitionAndReplacementHandbook() {
    originalSourceMerchant();
    catalog(4, 5, 17, 21);
    var original = new GameplayState.RogueMiracle(1, List.of(1.0));
    run().getMiracles().put(4, original);
    assertNotNull(RogueMiracleDice.prepare(p()), "A source-bound replacement must be executable before injection");
    failedPurchase(21);
    assertEquals(100, run().getCoins());
    assertFalse(run().getMiracles().containsKey(4));
    assertFalse(run().getMiracles().containsKey(21));
    assertEquals(Set.of(5, 17), run().getMiracles().keySet());
    assertEquals(Set.of(5, 17, 21), p().state.getRogueHandbook().getMiracles());
  }

  @Test
  void failedSourceShopNativePurchasePublishesNoSuccessAndCanRetry() throws Exception {
    originalSourceMerchant();
    quotes();
    var before = state();
    p().interruptSave = true;
    assertThrows(IllegalStateException.class,
        () -> call(5656, request(ENTITY, 4, false), "BuyRogueShopMiracleScRsp"));
    assertNull(session.response);
    assertEquals(before, state());
    p().interruptSave = false;
    assertEquals(0, Gameplay46.number(call(5656, request(ENTITY, 4, false), "BuyRogueShopMiracleScRsp"), "retcode"));
  }

  @Test
  void sourceShopNotificationsObserveTheSavedWalletReceiptAndCurio() throws Exception {
    originalSourceMerchant();
    quotes();
    int saves = p().saves;
    var observed = new ArrayList<String>();
    var observer = new Gameplay46HandlerTest.Session() {
      public Player getPlayer() { return p(); }
      public void sendClientPacket(String name, byte[] bytes) {
        assertEquals(saves + 1, p().saves);
        assertEquals(state(), p().saved.getLast());
        assertEquals(100, run().getCoins());
        assertTrue(quotes().getFirst().purchased());
        observed.add(name);
      }
    };
    var online = Player.class.getDeclaredField("loggedIn");
    online.setAccessible(true);
    p().setSession(observer);
    online.setBoolean(p(), true);
    try {
      p().interruptSave = true;
      assertThrows(IllegalStateException.class, () -> buy(4));
      assertTrue(observed.isEmpty());
      p().interruptSave = false;
      assertEquals(Retcode.SUCC, buy(4));
      assertFalse(observed.isEmpty());
    } finally { online.setBoolean(p(), false); }
  }

  @Test
  void frozenRecipeStockAndSoldUuidSurviveTwoPlayerDocumentReadsAndEntityRenumbering() throws Exception {
    query();
    var frozen = quotes();
    var mongo = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client = com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "rogue-rest-merchant");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true); uid.setInt(document, 123456791);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true); gameplay.set(document, p().state);
      store.save(document);
      p().state = store.find(Player.class).first().getGameplay();
      assertEquals(frozen, quotes());
      p().scene.getEntities().remove(ENTITY);
      npc.setEntityId(ENTITY + 1);
      p().scene.getEntities().put(ENTITY + 1, npc);
      assertEquals(frozen, RogueMiracleShop.quotes(p(), ENTITY + 1));
      assertEquals(Retcode.SUCC, RogueMiracleShop.buy(p(), ENTITY + 1, 4));
      String uuid = run().getMiracles().get(4).getAcquisitionId();
      gameplay.set(document, p().state); store.save(document);
      p().state = store.find(Player.class).first().getGameplay();
      assertEquals(uuid, run().getMiracles().get(4).getAcquisitionId());
      assertEquals(100, run().getCoins());
      assertTrue(RogueMiracleShop.quotes(p(), ENTITY + 1).getFirst().purchased());
      assertNotEquals(Retcode.SUCC, RogueMiracleShop.buy(p(), ENTITY + 1, 4));
      assertEquals(Set.of(4), p().state.getRogueHandbook().getMiracles());
    } finally { mongo.shutdownNow(); }
  }
}
