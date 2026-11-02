package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.InflaterInputStream;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Seven original Amphoreus submitters, actual entry/Inventory debit, captured reward delivery only. */
class MaterialSubmitActivity46Test {
  static final Gson GSON = ResourceLoader.getGson();
  static final String FIXTURE_SHA = "455304ad8bd49e783b91337bd1824d1b6bf843a368f97e1fa40a40577f290d64";
  static final JsonObject FIXTURE;
  static final Map<String, byte[]> FILES = new TreeMap<>();
  static final Map<Integer, JsonObject> ROWS = new TreeMap<>();
  static {
    try (var stream = MaterialSubmitActivity46Test.class.getResourceAsStream("/fixtures/material-submit-activity-4.6.json")) {
      assertNotNull(stream); byte[] raw = stream.readAllBytes();
      assertEquals(FIXTURE_SHA, sha(raw));
      FIXTURE = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
      for (var e : FIXTURE.getAsJsonObject("files").entrySet()) {
        var f = e.getValue().getAsJsonObject();
        try (var input = new InflaterInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(f.get("rawBase64").getAsString())))) {
          byte[] bytes = input.readAllBytes(); assertEquals(f.get("sha256").getAsString(), sha(bytes));
          assertEquals(f.get("bytes").getAsInt(), bytes.length);
          assertEquals(f.get("completeRowCount").getAsInt(), JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonArray().size());
          FILES.put(e.getKey(), bytes);
        }
      }
      for (var row : array("MaterialSubmitter")) ROWS.put(row.getAsJsonObject().get("ID").getAsInt(), row.getAsJsonObject());
    } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
  }
  static String sha(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
  static JsonArray array(String name) {
    return JsonParser.parseString(new String(FILES.get("ExcelOutput/" + name + ".json"), StandardCharsets.UTF_8)).getAsJsonArray();
  }
  @TempDir Path root;
  String oldRoot;
  final List<ItemExcel> oldItems = new ArrayList<>();
  final List<RewardExcel> oldRewards = new ArrayList<>();
  final List<ActivityPanelExcel> oldPanels = new ArrayList<>();
  final List<GameplayDefinitions.Window> oldWindows = new ArrayList<>();
  Actor player;
  Session session;

  static class Actor extends Player {
    int saves, grants, materialSaves, failSaveAt, failMaterialAt;
    boolean failNextSave, failGrant;
    String durable = "";
    final Map<Integer, Integer> durableMaterials = new TreeMap<>();
    final List<Map<Integer, Integer>> deliveries = new ArrayList<>();
    final List<BasePacket> notices = new ArrayList<>();
    final Inventory stock = new Inventory(this) {
      @Override public GrantResult grantItems(ItemParamMap items, boolean hint) {
        assertTrue((durable.contains("material-submit:30014:") || durable.contains("material-submit:30011:")), "Payout before persistent reservation");
        grants++;
        var copy = new TreeMap<Integer, Integer>();
        for (var i : items.int2IntEntrySet()) copy.put(i.getIntKey(), i.getIntValue());
        deliveries.add(copy);
        return new GrantResult(failGrant ? List.of() : items.toItemList(), !failGrant);
      }
    };
    @Override public int getUid() { return 1100081; }
    @Override public Inventory getInventory() { return stock; }
    @Override public void save() {
      if (failNextSave || saves + 1 == failSaveAt) { failNextSave = false; throw new IllegalStateException("isolated material receipt save failure"); }
      saves++; durable = GSON.toJson(getGameplay());
    }
    @Override public void sendPacket(BasePacket packet) { if (notices != null) notices.add(packet); }
  }
  static class Material extends GameItem {
    final Actor actor;
    Material(Actor owner, int id, int amount) { super(GameData.getItemExcelMap().get(id), amount); actor = owner; }
    @Override public void save() { actor.materialSaves++; if(actor.materialSaves==actor.failMaterialAt)throw new IllegalStateException("isolated material write failure"); actor.durableMaterials.put(getItemId(), getCount()); }
  }
  static class Session extends Gameplay46HandlerTest.Session {
    final Actor actor;
    Session(Actor actor) { this.actor = actor; }
    @Override public Player getPlayer() { return actor; }
  }
  @BeforeEach void prepare() throws Exception {
    oldRoot = LunarCore.getConfig().getResourceDir();
    var field = LunarCore.getConfig().getClass().getDeclaredField("resourceDir"); field.setAccessible(true); field.set(LunarCore.getConfig(), root.toString());
    for (var entry : FILES.entrySet()) { var p = root.resolve(entry.getKey()); Files.createDirectories(p.getParent()); Files.write(p, entry.getValue()); }
    oldItems.addAll(GameData.getItemExcelMap().values()); GameData.getItemExcelMap().values().clear();
    oldRewards.addAll(GameData.getRewardExcelMap().values()); GameData.getRewardExcelMap().values().clear();
    oldPanels.addAll(GameData.getActivityPanelExcelMap().values()); GameData.getActivityPanelExcelMap().values().clear();
    for (var row : array("ItemConfig")) GameData.getItemExcelMap().add(GSON.fromJson(row, ItemExcel.class));
    for (var row : array("RewardData")) { var reward = GSON.fromJson(row, RewardExcel.class); reward.onLoad(); GameData.getRewardExcelMap().add(reward); }
    for (var row : array("ActivityPanel")) GameData.getActivityPanelExcelMap().add(GSON.fromJson(row, ActivityPanelExcel.class));
    oldWindows.addAll(GameplayDefinitions.windows); GameplayDefinitions.windows.clear();
    for (int id = 301; id <= 307; id++) GameplayDefinitions.windows.add(new GameplayDefinitions.Window(30014, ROWS.get(id).get("ActivityModuleID").getAsInt(), 1, Long.MAX_VALUE));
    player = new Actor(); player.setLevel(21); session = new Session(player);
    for (int id = 301; id <= 307; id++) for (var element : ROWS.get(id).getAsJsonArray("MaterialList")) {
      int item = element.getAsJsonObject().get("ItemID").getAsInt();
      if (player.stock.getMaterialByItemId(item) == null) player.stock.addItem(new Material(player, item, 30), false);
    }
    player.saves = player.grants = player.materialSaves = 0; player.notices.clear();
  }
  @AfterEach void restore() throws Exception {
    if (oldRoot == null) return;
    var field = LunarCore.getConfig().getClass().getDeclaredField("resourceDir"); field.setAccessible(true); field.set(LunarCore.getConfig(), oldRoot);
    GameData.getItemExcelMap().values().clear(); oldItems.forEach(GameData.getItemExcelMap()::add);
    GameData.getRewardExcelMap().values().clear(); oldRewards.forEach(GameData.getRewardExcelMap()::add);
    GameData.getActivityPanelExcelMap().values().clear(); oldPanels.forEach(GameData.getActivityPanelExcelMap()::add);
    GameplayDefinitions.windows.clear(); GameplayDefinitions.windows.addAll(oldWindows);
  }
  static byte[] uint(int field, long value) throws Exception {
    var out = new ByteArrayOutputStream(); var coded = CodedOutputStream.newInstance(out); coded.writeUInt64(field, value); coded.flush(); return out.toByteArray();
  }
  DynamicMessage request(String name, byte[] bytes) throws Exception {
    session.response = null; session.responseName = null;
    assertTrue(VersionedPacketHandler.handle(session, session.getProtocol().clientCommand(name), bytes), "Missing actual 4.6 material submission handler");
    assertNotNull(session.response, "No actual response from material submission entry");
    return session.response;
  }
  DynamicMessage query() throws Exception { return request("GetMaterialSubmitActivityDataCsReq", new byte[0]); }
  DynamicMessage submit(int id) throws Exception { return request("SubmitMaterialSubmitActivityMaterialCsReq", uint(8, id)); }
  DynamicMessage take(int id) throws Exception { return request("TakeMaterialSubmitActivityRewardCsReq", uint(7, id)); }
  static Object field(DynamicMessage msg, int number) { return msg.getField(msg.getDescriptorForType().findFieldByNumber(number)); }
  int code(DynamicMessage msg) { return ((Number) field(msg, msg.getDescriptorForType().findFieldByName("retcode").getNumber())).intValue(); }
  Map<Integer, Integer> stocks() { var map = new TreeMap<Integer, Integer>(); player.stock.getItems().values().forEach(i -> map.put(i.getItemId(), i.getCount())); return map; }
  Map<Integer, Integer> expectedReward(int id) {
    var values = new TreeMap<Integer, Integer>();
    for (var item : GameData.getRewardExcelMap().get(ROWS.get(id).get("RewardID").getAsInt()).getRewards()) values.put(item.getIntKey(), item.getIntValue());
    return values;
  }
  void through(int id) throws Exception { for (int current = 301; current <= id; current++) assertEquals(0, code(submit(current))); }
  @Test void completeImmutableSourceAndNativeProtocolControl() throws Exception {
    assertEquals(6, FILES.size()); assertEquals(28, ROWS.size());
    assertEquals(58, FIXTURE.getAsJsonArray("wholeFunctions").size());
    for (var f : FIXTURE.getAsJsonArray("wholeFunctions")) { var row = f.getAsJsonObject(); var bytes = Base64.getDecoder().decode(row.get("rawBase64").getAsString()); assertEquals(row.get("sha256").getAsString(), sha(bytes)); assertEquals(row.get("byteCount").getAsInt(), bytes.length); }
    assertEquals(2621, session.getProtocol().clientCommand("GetMaterialSubmitActivityDataCsReq"));
    assertEquals(2611, session.getProtocol().clientCommand("SubmitMaterialSubmitActivityMaterialCsReq"));
    assertEquals(2638, session.getProtocol().clientCommand("TakeMaterialSubmitActivityRewardCsReq"));
    assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b", FIXTURE.get("sourceCommit").getAsString());
    assertFalse(FIXTURE.get("clientAccepted").getAsBoolean());
  }
  @Test void freshQueryIsReadOnlyAndDoesNotFabricateSubmittedRecords() throws Exception {
    var before = GSON.toJson(player.getGameplay()); var stock = stocks();
    var response = query(); assertEquals(0, code(response)); assertEquals(0, response.getRepeatedFieldCount(response.getDescriptorForType().findFieldByNumber(8)));
    assertEquals(before, GSON.toJson(player.getGameplay())); assertEquals(stock, stocks()); assertEquals(0, player.saves); assertEquals(0, player.grants);
  }
  @ParameterizedTest @ValueSource(ints = {301,302,303,304,305,306,307})
  void sourceCostsSubmitOnceAndOriginalRewardsTakeOnce(int id) throws Exception {
    through(id - 1); var before = stocks(); var costs = new TreeMap<Integer, Integer>();
    for (var c : ROWS.get(id).getAsJsonArray("MaterialList")) costs.put(c.getAsJsonObject().get("ItemID").getAsInt(), c.getAsJsonObject().get("ItemNum").getAsInt());
    var response = submit(id); assertEquals(0, code(response));
    var state = (DynamicMessage) field(response, 8); assertEquals(id, field(state, 15)); assertEquals(true, field(state, 8)); assertEquals(false, field(state, 13)); assertEquals(0, field(state, 9));
    var expected = new TreeMap<>(before); costs.forEach((item, n) -> expected.put(item, expected.get(item) - n)); assertEquals(expected, stocks()); assertEquals(0, player.grants);
    var saved = GSON.toJson(player.getGameplay()); int saves = player.saves;
    assertEquals(0, code(submit(id))); assertEquals(expected, stocks()); assertEquals(saved, GSON.toJson(player.getGameplay())); assertEquals(saves, player.saves);
    assertEquals(0, code(take(id))); assertEquals(List.of(expectedReward(id)), player.deliveries); assertEquals(1, player.grants);
    assertNotEquals(0, code(take(id))); assertEquals(1, player.grants); assertEquals(expected, stocks());
    var q = query(); boolean found = false;
    for (var item : (List<?>) field(q, 8)) { var v = (DynamicMessage) item; if (field(v, 15).equals(id)) { found = true; assertEquals(true, field(v, 8)); assertEquals(true, field(v, 13)); } }
    assertTrue(found);
  }
  @ParameterizedTest @ValueSource(strings = {"noWindow","expired","future","wrongPanel","wrongModule","lockedLevel","outOfOrder","outsideGroup","zero","negativeAlias","uintAlias","unknownField","duplicateField","malformed","takeBeforeSubmit"})
  void invalidRequestsCannotDebitOrCreateReceipts(String kind) throws Exception {
    String name = "SubmitMaterialSubmitActivityMaterialCsReq"; byte[] raw = uint(8, 301);
    switch (kind) {
      case "noWindow" -> GameplayDefinitions.windows.clear();
      case "expired" -> { GameplayDefinitions.windows.clear(); GameplayDefinitions.windows.add(new GameplayDefinitions.Window(30014,3001401,1,2)); }
      case "future" -> { GameplayDefinitions.windows.clear(); GameplayDefinitions.windows.add(new GameplayDefinitions.Window(30014,3001401,Long.MAX_VALUE-1,Long.MAX_VALUE)); }
      case "wrongPanel" -> { GameplayDefinitions.windows.clear(); GameplayDefinitions.windows.add(new GameplayDefinitions.Window(30013,3001401,1,Long.MAX_VALUE)); }
      case "wrongModule" -> { GameplayDefinitions.windows.clear(); GameplayDefinitions.windows.add(new GameplayDefinitions.Window(30014,3001402,1,Long.MAX_VALUE)); }
      case "lockedLevel" -> player.setLevel(20);
      case "outOfOrder" -> raw = uint(8,302);
      case "outsideGroup" -> raw = uint(8,1);
      case "zero" -> raw = new byte[0];
      case "negativeAlias" -> raw = uint(8,0xffffffffL);
      case "uintAlias" -> raw = uint(8,0x10000012dL);
      case "unknownField" -> { var out = new ByteArrayOutputStream(); out.write(raw); out.write(uint(4,7)); raw = out.toByteArray(); }
      case "duplicateField" -> { var out = new ByteArrayOutputStream(); out.write(raw); out.write(raw); raw = out.toByteArray(); }
      case "malformed" -> raw = new byte[]{64,(byte)128};
      case "takeBeforeSubmit" -> { name="TakeMaterialSubmitActivityRewardCsReq"; raw=uint(7,301); }
    }
    String before = GSON.toJson(player.getGameplay()); var stock = stocks();
    assertNotEquals(0, code(request(name, raw))); assertEquals(before, GSON.toJson(player.getGameplay())); assertEquals(stock, stocks()); assertEquals(0, player.saves); assertEquals(0, player.grants);
  }
  @ParameterizedTest @ValueSource(strings = {"missingFile","modifiedFile","missingMaterial","shortMaterial","wrongOwner","lockedMaterial","changedLoadedReward"})
  void resourceAndOwnershipFailuresCannotSpend(String kind) throws Exception {
    var material = player.stock.getMaterialByItemId(181006);
    switch (kind) {
      case "missingFile" -> Files.delete(root.resolve("ExcelOutput/MaterialSubmitterGroup.json"));
      case "modifiedFile" -> Files.writeString(root.resolve("ExcelOutput/MaterialSubmitter.json"), "[]");
      case "missingMaterial" -> player.stock.removeItem(material, material.getCount());
      case "shortMaterial" -> material.setCount(0);
      case "wrongOwner" -> { var f=GameItem.class.getDeclaredField("ownerUid");f.setAccessible(true);f.setInt(material,1100091); }
      case "lockedMaterial" -> material.setLocked(true);
      case "changedLoadedReward" -> GameData.getRewardExcelMap().get(3131002).getRewards().put(1,999);
    }
    String before=GSON.toJson(player.getGameplay()); var stock=stocks(); int saves=player.saves;
    assertNotEquals(0, code(submit(301))); assertEquals(before,GSON.toJson(player.getGameplay())); assertEquals(stock,stocks()); assertEquals(saves,player.saves); assertEquals(0,player.grants);
  }
  @Test void saveFailureBeforeDebitCanBeRetriedWithoutLostMaterials() throws Exception {
    String before=GSON.toJson(player.getGameplay()); var stock=stocks(); player.failNextSave=true;
    assertNotEquals(0,code(submit(301))); assertEquals(before,GSON.toJson(player.getGameplay())); assertEquals(stock,stocks()); assertEquals(0,player.materialSaves);
    assertEquals(0,code(submit(301))); assertEquals(stock.get(181006)-1,stocks().get(181006));
  }
  @Test void unauthorizedRequestIsHandledSilently() throws Exception {
    session.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(VersionedPacketHandler.handle(session,session.getProtocol().clientCommand("SubmitMaterialSubmitActivityMaterialCsReq"),uint(8,301)));
    assertNull(session.response); assertEquals(0,player.saves); assertEquals(0,player.grants);
  }
  void replaceState(JsonObject state) throws Exception {
    var field=Player.class.getDeclaredField("gameplay");field.setAccessible(true);field.set(player,GSON.fromJson(state,GameplayState.class));
  }
  @ParameterizedTest @ValueSource(strings={"nullState","nullPhase","unknownPhase","wrongVersion","wrongFingerprint","wrongReward","nullReward","foreignReward"})
  void damagedSavedRecordsAreRejectedWithoutRepairOrPayout(String kind) throws Exception {
    through(301);assertEquals(0,code(take(301)));
    var saved=GSON.toJsonTree(player.getGameplay()).getAsJsonObject();var domain=saved.getAsJsonObject("materialSubmission");
    switch(kind) {
      case "nullState" -> saved.add("materialSubmission",JsonNull.INSTANCE);
      case "nullPhase" -> domain.getAsJsonObject("receipts").getAsJsonObject("301").add("phase",JsonNull.INSTANCE);
      case "unknownPhase" -> domain.getAsJsonObject("receipts").getAsJsonObject("301").addProperty("phase","DONE");
      case "wrongVersion" -> domain.addProperty("version",2);
      case "wrongFingerprint" -> domain.addProperty("fingerprint","foreign-resource-fingerprint");
      case "wrongReward" -> saved.getAsJsonObject("rewards").getAsJsonObject("material-submit:30014:301").addProperty("rewardId",3131007);
      case "nullReward" -> saved.getAsJsonObject("rewards").add("material-submit:30014:301",JsonNull.INSTANCE);
      case "foreignReward" -> saved.getAsJsonObject("rewards").add("material-submit:30014:308",saved.getAsJsonObject("rewards").get("material-submit:30014:301").deepCopy());
    }
    replaceState(saved);String before=GSON.toJson(player.getGameplay());var stock=stocks();int saves=player.saves,grants=player.grants;
    assertNotEquals(0,code(query()));assertEquals(before,GSON.toJson(player.getGameplay()));assertEquals(stock,stocks());assertEquals(saves,player.saves);assertEquals(grants,player.grants);
  }
  @ParameterizedTest @ValueSource(strings={"finalReceiptSave","firstItemSave","secondItemSave","partialReward"})
  void interruptedDebitsAndPayoutsRemainBusyRatherThanReplay(String kind) throws Exception {
    if(kind.equals("partialReward")) {
      through(301);player.failGrant=true;assertNotEquals(0,code(take(301)));var snapshot=GSON.toJson(player.getGameplay());int grants=player.grants,saves=player.saves;
      player.failGrant=false;assertEquals(Retcode.SYSTEM_BUSY.getVal(),code(take(301)));assertEquals(grants,player.grants);assertEquals(saves,player.saves);assertEquals(snapshot,GSON.toJson(player.getGameplay()));
      var row=(DynamicMessage)((List<?>)field(query(),8)).getFirst();assertEquals(true,field(row,8));assertEquals(false,field(row,13));
    } else {
      if(kind.equals("finalReceiptSave"))player.failSaveAt=2;
      else player.failMaterialAt=kind.equals("firstItemSave")?1:2;
      assertNotEquals(0,code(submit(301)));var stock=stocks();String snapshot=GSON.toJson(player.getGameplay());int saves=player.saves,materialSaves=player.materialSaves;
      player.failSaveAt=player.failMaterialAt=0;
      assertEquals(Retcode.SYSTEM_BUSY.getVal(),code(submit(301)));assertEquals(stock,stocks());assertEquals(snapshot,GSON.toJson(player.getGameplay()));assertEquals(saves,player.saves);assertEquals(materialSaves,player.materialSaves);
      assertNotEquals(0,code(take(301)));assertEquals(0,player.grants);
    }
  }
  @Test void historicalSubmittedQueryRemainsReadOnlyWhenOperatorWindowCloses() throws Exception {
    through(301);GameplayDefinitions.windows.clear();String before=GSON.toJson(player.getGameplay());var stock=stocks();int saves=player.saves;
    assertEquals(0,code(query()));assertNotEquals(0,code(take(301)));assertEquals(before,GSON.toJson(player.getGameplay()));assertEquals(stock,stocks());assertEquals(saves,player.saves);
  }
  @Test void realPlayerBsonReadsPreserveNestedDebitsAndOneTimeRewardTwice() throws Exception {
    through(302);assertEquals(0,code(take(301)));
    var server=new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());var address=server.bind();
    try(var client=com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:"+address.getPort()+"/?directConnection=true")) {
      var codecs=org.bson.codecs.configuration.CodecRegistries.fromCodecs(new emu.lunarcore.database.codecs.IntSetCodec(),new emu.lunarcore.database.codecs.IntListCodec(),new emu.lunarcore.database.codecs.Int2IntMapCodec());
      var options=dev.morphia.mapping.MapperOptions.builder().storeEmpties(true).storeNulls(false).codecProvider(codecs).build();
      var store=dev.morphia.Morphia.createDatastore(client,"material-submit-fixture",options);store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class,emu.lunarcore.game.quest.QuestState.class,emu.lunarcore.util.Position.class);
      var document=new Player();var uid=Player.class.getDeclaredField("uid");uid.setAccessible(true);uid.setInt(document,194814);
      var gameplay=Player.class.getDeclaredField("gameplay");gameplay.setAccessible(true);gameplay.set(document,player.getGameplay());store.save(document);
      var collection=client.getDatabase("material-submit-fixture").getCollection("players");var before=Objects.requireNonNull(collection.find().first());JsonElement serialized=GSON.toJsonTree(player.getGameplay());Player previous=null;
      for(int read=0;read<2;read++) {
        var restored=Objects.requireNonNull(store.find(Player.class).first());assertNotSame(previous,restored);previous=restored;assertEquals(serialized,GSON.toJsonTree(restored.getGameplay()));
        var s=new Gameplay46HandlerTest.Session(){public Player getPlayer(){return restored;}};
        assertTrue(VersionedPacketHandler.handle(s,2621,new byte[0]));assertEquals(0,code(s.response));
        var states=(List<?>)field(s.response,8);assertEquals(2,states.size());assertEquals(301,field((DynamicMessage)states.get(0),15));assertEquals(true,field((DynamicMessage)states.get(0),13));assertEquals(false,field((DynamicMessage)states.get(1),13));
        assertEquals(serialized,GSON.toJsonTree(restored.getGameplay()));assertEquals(before,collection.find().first());
      }
    }finally{server.shutdownNow();}
  }
}
