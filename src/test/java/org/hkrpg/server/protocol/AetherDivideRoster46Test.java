package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.hkrpg.server.gameplay.GameplayState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Existing saved spirits, not fabricated rewards or fragment-derived ownership. */
class AetherDivideRoster46Test {
  static final String FIXTURE = "/fixtures/aether-divide-roster-4.6.json";
  static final String FIXTURE_SHA = "39230a643489ee248812836830154a5433a093e1b3aa315de9deaf068588333d";
  @TempDir Path root;
  final Gson gson = new Gson();
  JsonObject fixture;
  JsonObject fixtureRoster;
  String oldResources;
  int saves;
  boolean failSave;
  String durable = "";
  final List<BasePacket> notifications = new ArrayList<>();
  GameplayState state = new GameplayState();
  final Player player = new Player() {
    public GameplayState getGameplay() { return state; }
    public void save() {
      if (failSave) throw new IllegalStateException("isolated roster save failure");
      saves++; durable = gson.toJson(state);
    }
    public void sendPacket(BasePacket packet) {
      if (notifications != null) notifications.add(packet);
    }
  };
  final Gameplay46HandlerTest.Session session = new Gameplay46HandlerTest.Session() {
    public Player getPlayer() { return AetherDivideRoster46Test.this.player; }
  };
  static String sha(byte[] raw) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
  }
  @BeforeEach void prepare() throws Exception {
    byte[] raw;
    try (var stream = getClass().getResourceAsStream(FIXTURE)) {
      assertNotNull(stream); raw = stream.readAllBytes();
    }
    assertEquals(FIXTURE_SHA, sha(raw));
    fixture = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
    assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b", fixture.get("sourceCommit").getAsString());
    assertFalse(fixture.get("clientAccepted").getAsBoolean());
    assertFalse(fixture.getAsJsonObject("scope").get("fragmentIsSpiritOwnership").getAsBoolean());
    for (var entry : fixture.getAsJsonObject("files").entrySet()) {
      var proof = entry.getValue().getAsJsonObject();
      byte[] installed = Base64.getDecoder().decode(proof.get("rawBase64").getAsString());
      byte[] original = Base64.getDecoder().decode(proof.get("originalRawBase64").getAsString());
      assertEquals(proof.get("sha256").getAsString(), sha(installed));
      assertEquals(proof.get("originalSHA256").getAsString(), sha(original));
      assertEquals(proof.get("completeRows"), JsonParser.parseString(new String(installed, StandardCharsets.UTF_8)));
      assertEquals(proof.get("completeRows"), JsonParser.parseString(new String(original, StandardCharsets.UTF_8)));
      var path = root.resolve(entry.getKey()); Files.createDirectories(path.getParent()); Files.write(path, installed);
    }
    oldResources = LunarCore.getConfig().getResourceDir();
    var field = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
    field.setAccessible(true); field.set(LunarCore.getConfig(), root.toString());
  }
  @AfterEach void cleanup() throws Exception {
    if (oldResources == null) return;
    var field = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
    field.setAccessible(true); field.set(LunarCore.getConfig(), oldResources);
  }
  String fingerprint() throws Exception {
    var values = new TreeMap<String, String>();
    fixture.getAsJsonObject("files").entrySet().forEach(entry ->
        values.put(entry.getKey(), entry.getValue().getAsJsonObject().get("originalSHA256").getAsString()));
    var text = new StringBuilder("aether-divide-roster-4.6-v1\n");
    values.forEach((path, hash) -> text.append(path).append(':').append(hash).append('\n'));
    return sha(text.toString().getBytes(StandardCharsets.UTF_8));
  }
  void existingRoster() throws Exception {
    JsonObject roster = JsonParser.parseString("""
      {"version":1,"sourceFingerprint":"","currentSlot":1,"trainer":0,"infiniteMission":0,
       "spirits":{"6001":{"id":6001,"promotion":2,"exp":7,"fragmentCount":11,"curSp":1500,"skills":{}},
                  "6003":{"id":6003,"promotion":1,"exp":0,"fragmentCount":0,"curSp":0,"skills":{}}},
       "skillCores":{"250201":{"itemId":250201,"count":1,"dressedSpirit":0}},
       "lineups":{"1":[6001,0,6003,0],"2":[0,0,0,0],"3":[0,0,0,0],
                  "4":[0,0,0,0],"5":[0,0,0,0],"6":[0,0,0,0]}}
      """).getAsJsonObject();
    roster.addProperty("sourceFingerprint", fingerprint());
    fixtureRoster = roster.deepCopy();
    var document = gson.toJsonTree(state).getAsJsonObject(); document.add("aetherDivide", roster);
    state = gson.fromJson(document, GameplayState.class);
    material(fragmentItem(6001),11); material(250201,1);
  }
  int fragmentItem(int spirit) {
    return fixture.getAsJsonObject("files").getAsJsonObject("ExcelOutput/AetherDivideSpirit.json")
        .getAsJsonArray("completeRows").asList().stream().map(JsonElement::getAsJsonObject)
        .filter(row->row.get("AvatarID").getAsInt()==spirit).findFirst().orElseThrow().get("ExpItemID").getAsInt();
  }
  void material(int id,int count) {
    var item=gson.fromJson("{\"itemId\":"+id+",\"count\":"+count+"}",emu.lunarcore.game.inventory.GameItem.class);
    player.getInventory().getItems().put(id,item);
    player.getInventory().getTab(emu.lunarcore.game.inventory.tabs.InventoryTabType.MATERIAL).onAddItem(item);
  }
  void removeMaterial(int id) {
    var item=player.getInventory().getMaterialByItemId(id);if(item==null) return;
    player.getInventory().getTab(emu.lunarcore.game.inventory.tabs.InventoryTabType.MATERIAL).onRemoveItem(item);
    player.getInventory().getItems().remove(id);
  }
  JsonObject savedRoster() { return gson.toJsonTree(state).getAsJsonObject().getAsJsonObject("aetherDivide"); }
  void editRoster(String property, JsonElement value) {
    fixtureRoster.add(property, value);
    var document = gson.toJsonTree(state).getAsJsonObject(); document.add("aetherDivide", fixtureRoster.deepCopy());
    state = gson.fromJson(document, GameplayState.class);
  }
  DynamicMessage request(String name, byte[] bytes) throws Exception {
    session.response = null; session.responseName = null;
    assertTrue(VersionedPacketHandler.handle(session, session.getProtocol().clientCommand(name), bytes),
        "The actual native Aether roster command must be handled");
    assertEquals(name.replace("CsReq", "ScRsp"), session.responseName); return session.response;
  }
  DynamicMessage query() throws Exception { return request("GetAetherDivideInfoCsReq", new byte[0]); }
  static int number(DynamicMessage value, int field) { return (Integer)value.getField(value.getDescriptorForType().findFieldByNumber(field)); }
  static List<?> rows(DynamicMessage value, int field) { return (List<?>)value.getField(value.getDescriptorForType().findFieldByNumber(field)); }
  static DynamicMessage child(DynamicMessage value, int field) { return (DynamicMessage)value.getField(value.getDescriptorForType().findFieldByNumber(field)); }
  byte[] set(int slot, int... ids) {
    var type = session.getProtocol().clientSchema().message("SetAetherDivideLineUpCsReq");
    var field = type.findFieldByNumber(11);
    var lineup = DynamicMessage.newBuilder(field.getMessageType());
    lineup.setField(field.getMessageType().findFieldByNumber(2), slot);
    for (int id : ids) lineup.addRepeatedField(field.getMessageType().findFieldByNumber(10), id);
    return DynamicMessage.newBuilder(type).setField(field, lineup.build()).build().toByteArray();
  }
  byte[] switchBody(int slot) {
    var type = session.getProtocol().clientSchema().message("SwitchAetherDivideLineUpSlotCsReq");
    return DynamicMessage.newBuilder(type).setField(type.findFieldByNumber(4), slot).build().toByteArray();
  }
  void unchanged(String before, int writes) {
    assertEquals(before, gson.toJson(state)); assertEquals(writes, saves); assertTrue(notifications.isEmpty());
  }
  @Test void wholeOriginalObjectsAndWholeNativeBodiesStayMatched() throws Exception {
    assertEquals(6, fixture.getAsJsonObject("files").size());
    assertEquals(27, fixture.getAsJsonArray("wholeNativeFunctions").size());
    int bytes = 0;
    for (var element : fixture.getAsJsonArray("wholeNativeFunctions")) {
      var proof = element.getAsJsonObject(); byte[] raw = Base64.getDecoder().decode(proof.get("rawBase64").getAsString());
      assertEquals(proof.get("bytes").getAsInt(), raw.length);
      assertEquals(proof.get("bodySHA256").getAsString(), sha(raw)); bytes += raw.length;
    }
    assertEquals(8101, bytes);
    var requestProof=fixture.getAsJsonObject("requestAndCtorSource");
    assertFalse(requestProof.get("normalUiExactlyFourPayloadSemanticsVerified").getAsBoolean());
    assertFalse(requestProof.get("opcodeInterpretationVerified").getAsBoolean());
    assertEquals(4840,requestProof.getAsJsonObject("setRequest").get("command").getAsInt());
    assertTrue(requestProof.getAsJsonObject("setRequest").get("nativeSenderCopiesArrayLength").getAsBoolean());
    assertFalse(requestProof.getAsJsonObject("setRequest").get("nativeSenderPadsOrRequiresFour").getAsBoolean());
    assertEquals(5,requestProof.getAsJsonArray("completeLuaScripts").size());
    int luaBytes=0;
    for(var element:requestProof.getAsJsonArray("completeLuaScripts")) {
      var proof=element.getAsJsonObject();byte[] raw=Base64.getDecoder().decode(proof.get("rawBase64").getAsString());
      assertEquals(proof.get("length").getAsInt(),raw.length);assertEquals(proof.get("sha256").getAsString(),sha(raw));
      assertTrue(proof.get("fullyConsumed").getAsBoolean());luaBytes+=raw.length;
    }
    assertEquals(19323,luaBytes);
    var schema = session.getProtocol().clientSchema();
    assertEquals("OCCAABPFBLF", schema.message("GetAetherDivideInfoScRsp").findFieldByNumber(4).getMessageType().getName());
    assertEquals("GHBICGMEFPL", schema.message("GetAetherDivideInfoScRsp").findFieldByNumber(15).getMessageType().getName());
    assertEquals("retcode", schema.message("SwitchAetherDivideLineUpSlotScRsp").findFieldByNumber(9).getName());
  }
  @Test void emptySnapshotIsReadOnlyAndDoesNotGrantReferenceDefaultSpirits() throws Exception {
    String before = gson.toJson(state); int writes = saves;
    var response = query(); assertEquals(0, number(response, 6));
    assertTrue(rows(response, 15).isEmpty()); assertTrue(rows(response, 14).isEmpty());
    assertEquals(1, number(response, 10)); assertEquals(6, rows(response, 4).size());
    for (Object value : rows(response, 4)) assertEquals(List.of(0,0,0,0), rows((DynamicMessage)value, 10));
    assertEquals(0, number(response, 11)); assertEquals(0, number(response, 12)); assertEquals(0, number(response, 13));
    unchanged(before, writes);
  }
  @Test void storedSpiritPromotionExpFragmentsAndSpUseSeparatelyRecoveredFields() throws Exception {
    existingRoster(); String before = gson.toJson(state);
    var response = query(); assertEquals(0, number(response, 6));
    var spirit = (DynamicMessage)rows(response, 15).getFirst();
    assertEquals(6001, number(spirit, 10)); assertEquals(2, number(spirit, 3));
    assertEquals(11, number(spirit, 4)); assertEquals(7, number(spirit, 11));
    var sp = child(spirit, 6);
    assertEquals(1500, Gameplay46.number(sp, "cur_sp")); assertEquals(3000, Gameplay46.number(sp, "max_sp"));
    assertEquals(2, rows(response, 15).size()); unchanged(before, 0);
  }
  @Test void spiritFragmentFieldProjectsCurrentInventoryWithoutChangingOwnedRoster() throws Exception {
    existingRoster(); String before=gson.toJson(state);
    for(int count:List.of(13,2)) {
      material(fragmentItem(6001),count);String inventory=gson.toJson(player.getInventory().getItems());
      var response=query();assertEquals(0,number(response,6));
      assertEquals(count,number((DynamicMessage)rows(response,15).getFirst(),4));
      assertEquals(2,rows(response,15).size()); assertEquals(inventory,gson.toJson(player.getInventory().getItems()));unchanged(before,0);
    }
    removeMaterial(fragmentItem(6001));var response=query();
    assertEquals(0,number((DynamicMessage)rows(response,15).getFirst(),4)); assertEquals(2,rows(response,15).size());unchanged(before,0);
  }
  @Test void passiveCoreCountsUseCurrentInventoryAndDoNotAdvertiseGhostStock() throws Exception {
    existingRoster();String before=gson.toJson(state);material(250201,7);
    var response=query();assertEquals(0,number(response,6));
    assertEquals(7,number((DynamicMessage)rows(response,14).getFirst(),13));unchanged(before,0);
    removeMaterial(250201);response=query();assertEquals(0,number(response,6));assertTrue(rows(response,14).isEmpty());unchanged(before,0);
  }
  @Test void newlyOwnedSourceKnownCoreIsProjectedWithoutFabricatingSavedSpiritsOrCoreReceipts() throws Exception {
    existingRoster();String before=gson.toJson(state);material(250202,3);material(fragmentItem(6010),9);
    var response=query();assertEquals(0,number(response,6));assertEquals(2,rows(response,14).size());
    var core=(DynamicMessage)rows(response,14).get(1);assertEquals(250202,number(core,6));assertEquals(3,number(core,13));assertEquals(0,number(core,8));
    assertEquals(2,rows(response,15).size());unchanged(before,0);
  }
  @Test void equippedCoreWithoutActualStockIsRefusedWithoutRepairOrUnequip() throws Exception {
    existingRoster();fixtureRoster.getAsJsonObject("spirits").getAsJsonObject("6001").getAsJsonObject("skills").addProperty("0",250201);
    fixtureRoster.getAsJsonObject("skillCores").getAsJsonObject("250201").addProperty("dressedSpirit",6001);
    editRoster("skillCores",fixtureRoster.get("skillCores"));removeMaterial(250201);
    String before=gson.toJson(state);assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(),number(query(),6));unchanged(before,0);
  }
  @ParameterizedTest @ValueSource(ints={250201,250300})
  void negativeMaterialStockCannotBecomeUnsignedNativeCounts(int item) throws Exception {
    existingRoster();assertEquals(250300,fragmentItem(6001));material(item,-1);
    String before=gson.toJson(state);assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(),number(query(),6));unchanged(before,0);
  }
  @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6})
  void allSixNativeSlotsSaveActualOwnedSpiritsWithZeroPlaceholders(int slot) throws Exception {
    existingRoster(); var response = request("SetAetherDivideLineUpCsReq", set(slot,6003,0,6001,0));
    assertEquals(0, number(response, 7)); assertEquals(slot, number(child(response,1),2));
    assertEquals(List.of(6003,0,6001,0),rows(child(response,1),10)); assertEquals(1,saves);
    state=gson.fromJson(durable,GameplayState.class);
    var info=query(); var lineup=(DynamicMessage)rows(info,4).get(slot-1);
    assertEquals(List.of(6003,0,6001,0),rows(lineup,10)); assertEquals(1,saves);
  }
  @ParameterizedTest @ValueSource(ints={0,7,2147483647,-1})
  void invalidSlotsAreRefusedWithoutWrites(int slot) throws Exception {
    existingRoster(); String before=gson.toJson(state);
    assertNotEquals(0,number(request("SetAetherDivideLineUpCsReq",set(slot,6001,0,0,0)),7)); unchanged(before,0);
  }
  @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6})
  void switchingEveryRecoveredNativeSlotPersistsOnlyActualChanges(int slot) throws Exception {
    existingRoster(); var response=request("SwitchAetherDivideLineUpSlotCsReq",switchBody(slot));
    assertEquals(0,number(response,9)); assertEquals(slot,number(response,12)); assertEquals(slot==1?0:1,saves);
    int writes=saves; request("SwitchAetherDivideLineUpSlotCsReq",switchBody(slot)); assertEquals(writes,saves);
    assertEquals(slot,number(query(),10));
  }
  @Test void duplicateOwnedIdsForeignIdsAndOversizedLineupsCannotReplaceASavedLineup() throws Exception {
    existingRoster(); String before=gson.toJson(state);
    for(int[] ids: List.of(new int[]{6001,6001,0,0},new int[]{6001,6010,0,0},new int[]{6001,0,6003,0,0})) {
      assertNotEquals(0,number(request("SetAetherDivideLineUpCsReq",set(2,ids)),7)); unchanged(before,0);
    }
  }
  @Test void repeatedSetAndClearingASlotAreSavedExactlyOnce() throws Exception {
    existingRoster(); byte[] body=set(4,6003,0,0,0);
    request("SetAetherDivideLineUpCsReq",body); request("SetAetherDivideLineUpCsReq",body); assertEquals(1,saves);
    var response=request("SetAetherDivideLineUpCsReq",set(4,0,0,0,0)); assertEquals(0,number(response,7));
    assertEquals(List.of(0,0,0,0),rows(child(response,1),10)); assertEquals(2,saves);
  }
  @ParameterizedTest @ValueSource(ints={0,1,2,3})
  void nativeShortArraysReplaceAWholeLineupWithExplicitEmptyTailPositions(int count) throws Exception {
    existingRoster();
    int[] members=Arrays.copyOf(new int[]{6003,0,6001},count);
    var response=request("SetAetherDivideLineUpCsReq",set(1,members));
    assertEquals(0,number(response,7),"The native builder copies the supplied array without enforcing four members");
    var expected=new ArrayList<Integer>(); for(int id:members) expected.add(id);
    while(expected.size()<4) expected.add(0);
    assertEquals(expected,rows(child(response,1),10)); assertEquals(1,saves);
    request("SetAetherDivideLineUpCsReq",set(1,members)); assertEquals(1,saves);
    state=gson.fromJson(durable,GameplayState.class);
    assertEquals(expected,rows((DynamicMessage)rows(query(),4).getFirst(),10)); assertEquals(1,saves);
  }
  @Test void saveFailureRestoresExactStateAndAllowsTheSameOriginalRequestToRetry() throws Exception {
    existingRoster(); String before=gson.toJson(state); Object original=state;
    failSave=true; assertNotEquals(0,number(request("SetAetherDivideLineUpCsReq",set(3,6003,0,6001,0)),7));
    assertSame(original,state); unchanged(before,0); assertEquals("",durable);
    failSave=false; assertEquals(0,number(request("SetAetherDivideLineUpCsReq",set(3,6003,0,6001,0)),7)); assertEquals(1,saves);
  }
  @Test void malformedAndAliasedNativeRequestsNeverWriteOrReturnSuccess() throws Exception {
    existingRoster(); String before=gson.toJson(state);
    for(byte[] raw:List.of(new byte[0],new byte[]{0x20,1,0x20,2},new byte[]{0x20,(byte)0x80},new byte[]{0x28,1})) {
      assertNotEquals(0,number(request("SwitchAetherDivideLineUpSlotCsReq",raw),9)); unchanged(before,0);
    }
    assertNotEquals(0,number(request("GetAetherDivideInfoCsReq",new byte[]{8,1}),6)); unchanged(before,0);
    assertNotEquals(0,number(request("SetAetherDivideLineUpCsReq",new byte[0]),7)); unchanged(before,0);
  }
  @ParameterizedTest @ValueSource(strings={"AetherDivideSpirit","AetherDivideSpiritPromotion","AetherDividePassiveSkill",
      "AetherDivideTrainerLevel","AetherDivideConstCommon","AetherDivideChallengeList"})
  void missingOrChangedSourceObjectsCannotBeAdvertisedOrMutated(String name) throws Exception {
    existingRoster(); String before=gson.toJson(state); var path=root.resolve("ExcelOutput/"+name+".json");
    byte[] original=Files.readAllBytes(path); Files.writeString(path,new String(original,StandardCharsets.UTF_8)+" ");
    assertNotEquals(0,number(query(),6)); unchanged(before,0);
    assertNotEquals(0,number(request("SetAetherDivideLineUpCsReq",set(2,6001,0,0,0)),7)); unchanged(before,0);
    Files.delete(path); assertNotEquals(0,number(query(),6)); unchanged(before,0);
  }
  @Test void fragmentStockAloneNeverCreatesAnOwnedSpiritForLineupUse() throws Exception {
    existingRoster(); editRoster("spirits",new JsonObject()); editRoster("lineups",JsonParser.parseString("""
      {"1":[0,0,0,0],"2":[0,0,0,0],"3":[0,0,0,0],"4":[0,0,0,0],"5":[0,0,0,0],"6":[0,0,0,0]}
      """));
    String before=gson.toJson(state); assertTrue(rows(query(),15).isEmpty());
    assertNotEquals(0,number(request("SetAetherDivideLineUpCsReq",set(2,6001,0,0,0)),7)); unchanged(before,0);
  }
  @Test void nullSavedRosterIsRejectedWithoutRepairOrSave() throws Exception {
    existingRoster(); var document=gson.toJsonTree(state).getAsJsonObject(); document.add("aetherDivide",JsonNull.INSTANCE);
    state=gson.fromJson(document,GameplayState.class); String before=gson.toJson(state);
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(),number(query(),6)); unchanged(before,0);
  }
  @Test void actualPlayerBsonRestoresNestedSpiritsAndSixLineupsTwiceWithoutQueryWrites() throws Exception {
    existingRoster();request("SetAetherDivideLineUpCsReq",set(6,6003,0,6001));
    request("SwitchAetherDivideLineUpSlotCsReq",switchBody(6));
    var server=new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address=server.bind();
    try(var client=com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:"+address.getPort()+"/?directConnection=true")) {
      var codecs=org.bson.codecs.configuration.CodecRegistries.fromCodecs(
          new emu.lunarcore.database.codecs.IntSetCodec(),new emu.lunarcore.database.codecs.IntListCodec(),
          new emu.lunarcore.database.codecs.Int2IntMapCodec());
      var options=dev.morphia.mapping.MapperOptions.builder().storeEmpties(true).storeNulls(false).codecProvider(codecs).build();
      var store=dev.morphia.Morphia.createDatastore(client,"aether-roster-fixture",options);
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class,emu.lunarcore.game.quest.QuestState.class,emu.lunarcore.util.Position.class);
      var document=new Player();
      var uid=Player.class.getDeclaredField("uid");uid.setAccessible(true);uid.setInt(document,194846);
      var gameplay=Player.class.getDeclaredField("gameplay");gameplay.setAccessible(true);gameplay.set(document,state);
      store.save(document);var collection=client.getDatabase("aether-roster-fixture").getCollection("players");
      var before=Objects.requireNonNull(collection.find().first());String serialized=gson.toJson(state);
      Player previous=null;
      for(int read=0;read<2;read++) {
        var restored=Objects.requireNonNull(store.find(Player.class).first());assertNotSame(previous,restored);previous=restored;
        assertEquals(serialized,gson.toJson(restored.getGameplay()));
        var restoredSession=new Gameplay46HandlerTest.Session() { public Player getPlayer() { return restored; } };
        assertTrue(VersionedPacketHandler.handle(restoredSession,4848,new byte[0]));
        var response=restoredSession.response;assertEquals(0,number(response,6));assertEquals(6,number(response,10));
        assertEquals(2,rows(response,15).size());
        assertEquals(List.of(6003,0,6001,0),rows((DynamicMessage)rows(response,4).get(5),10));
        assertEquals(serialized,gson.toJson(restored.getGameplay()));assertEquals(before,collection.find().first());
      }
    } finally { server.shutdownNow(); }
  }
  @Test void unauthenticatedRecognizedCommandCannotEmitAPlayerSnapshot() throws Exception {
    session.setState(SessionState.WAITING_FOR_TOKEN); String before=gson.toJson(state);
    assertTrue(VersionedPacketHandler.handle(session,4848,new byte[0])); assertNull(session.response); unchanged(before,0);
  }
}
