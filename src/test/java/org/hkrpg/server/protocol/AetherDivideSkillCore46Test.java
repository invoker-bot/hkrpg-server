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

/** Source-bound empty-slot equip/takeOff, real core stock and exact one-based declared promotion slots. */
class AetherDivideSkillCore46Test {
  static final String FIXTURE = "/fixtures/aether-divide-core-4.6.json";
  static final String FIXTURE_SHA = "ffe44ab7c1a87209b07543a5d622f6074884851cc6d9da4a715b0a00b1c7e208";
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
    public Player getPlayer() { return AetherDivideSkillCore46Test.this.player; }
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
    assertTrue(fixture.getAsJsonObject("scope").get("actualInventoryCoreOwnership").getAsBoolean());
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
                  "6003":{"id":6003,"promotion":4,"exp":0,"fragmentCount":0,"curSp":0,"skills":{}}},
       "skillCores":{"250201":{"itemId":250201,"count":1,"dressedSpirit":0},
         "250202":{"itemId":250202,"count":1,"dressedSpirit":0},"250203":{"itemId":250203,"count":1,"dressedSpirit":0},
         "250204":{"itemId":250204,"count":1,"dressedSpirit":0},"250205":{"itemId":250205,"count":1,"dressedSpirit":0}},
       "lineups":{"1":[6001,0,6003,0],"2":[0,0,0,0],"3":[0,0,0,0],
                  "4":[0,0,0,0],"5":[0,0,0,0],"6":[0,0,0,0]}}
      """).getAsJsonObject();
    roster.addProperty("sourceFingerprint", fingerprint());
    fixtureRoster = roster.deepCopy();
    var document = gson.toJsonTree(state).getAsJsonObject(); document.add("aetherDivide", roster);
    state = gson.fromJson(document, GameplayState.class);
    material(fragmentItem(6001),11);material(250201,9);material(250202,3);material(250203,5);material(250204,4);material(250205,2);
  }
  int fragmentItem(int spirit) {
    return fixture.getAsJsonObject("files").getAsJsonObject("ExcelOutput/AetherDivideSpirit.json")
        .getAsJsonArray("completeRows").asList().stream().map(JsonElement::getAsJsonObject)
        .filter(row->row.get("AvatarID").getAsInt()==spirit).findFirst().orElseThrow().get("ExpItemID").getAsInt();
  }
  void material(int id,int count) {
    var item=gson.fromJson("{\"itemId\":"+id+",\"count\":"+count+",\"ownerUid\":"+player.getUid()+"}",emu.lunarcore.game.inventory.GameItem.class);
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
    assertEquals(name.equals("EquipAetherDividePassiveSkillCsReq")?"EquipSkillCoreScRsp":name.replace("CsReq", "ScRsp"), session.responseName); return session.response;
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

  byte[] equipBody(int avatar,int slot,int item) {
    var type=session.getProtocol().clientSchema().message("EquipAetherDividePassiveSkillCsReq");var row=DynamicMessage.newBuilder(type);
    row.setField(type.findFieldByNumber(3),avatar);row.setField(type.findFieldByNumber(12),slot);row.setField(type.findFieldByNumber(14),item);return row.build().toByteArray();
  }
  byte[] takeOffBody(int avatar,int slot) {
    var type=session.getProtocol().clientSchema().message("TakeOffSkillCoreCsReq");var row=DynamicMessage.newBuilder(type);
    row.setField(type.findFieldByNumber(7),avatar);row.setField(type.findFieldByNumber(1),slot);return row.build().toByteArray();
  }
  DynamicMessage equip(int avatar,int slot,int item) throws Exception {return request("EquipAetherDividePassiveSkillCsReq",equipBody(avatar,slot,item));}
  DynamicMessage off(int avatar,int slot) throws Exception {return request("TakeOffSkillCoreCsReq",takeOffBody(avatar,slot));}
  JsonObject fullView() {var out=gson.toJsonTree(player,Player.class).getAsJsonObject();out.add("gameplay",gson.toJsonTree(state));out.add("actualInventory",gson.toJsonTree(player.getInventory().getItems()));return out;}
  void sourceSpirit(int id,int promotion) {
    var row=new JsonObject();row.addProperty("id",id);row.addProperty("promotion",promotion);row.addProperty("exp",0);
    row.addProperty("fragmentCount",0);row.addProperty("curSp",1000);row.add("skills",new JsonObject());
    fixtureRoster.getAsJsonObject("spirits").add(Integer.toString(id),row);editRoster("spirits",fixtureRoster.get("spirits"));
  }
  void seededBinding(int avatar,int slot,int item) {
    fixtureRoster.getAsJsonObject("spirits").getAsJsonObject(Integer.toString(avatar)).getAsJsonObject("skills").addProperty(Integer.toString(slot),item);
    fixtureRoster.getAsJsonObject("skillCores").getAsJsonObject(Integer.toString(item)).addProperty("dressedSpirit",avatar);
    editRoster("spirits",fixtureRoster.get("spirits"));editRoster("skillCores",fixtureRoster.get("skillCores"));
  }
  void assertCoreReply(DynamicMessage reply,boolean equipped,int avatar,int slot,int item,int actualCount) {
    assertEquals(0,number(reply,equipped?1:14));var core=child(reply,equipped?9:4);var spirit=child(reply,equipped?15:8);
    assertEquals(item,number(core,6));assertEquals(actualCount,number(core,13));assertEquals(equipped?avatar:0,number(core,8));
    assertEquals(avatar,number(spirit,10));assertTrue(spirit.hasField(spirit.getDescriptorForType().findFieldByNumber(6)));
    var entries=rows(spirit,13).stream().map(DynamicMessage.class::cast).toList();
    if(equipped) {assertEquals(1,entries.size());assertEquals(item,number(entries.getFirst(),2));assertEquals(slot,number(entries.getFirst(),5));}
    else assertTrue(entries.isEmpty());
  }
  void rejected(DynamicMessage reply,boolean equip) {
    assertNotEquals(0,number(reply,equip?1:14));assertFalse(reply.hasField(reply.getDescriptorForType().findFieldByNumber(equip?9:4)));
    assertFalse(reply.hasField(reply.getDescriptorForType().findFieldByNumber(equip?15:8)));
  }
  void onlyAetherChanged(JsonObject before) {
    var after=fullView();before=before.deepCopy();before.getAsJsonObject("gameplay").remove("aetherDivide");after.getAsJsonObject("gameplay").remove("aetherDivide");
    assertEquals(before,after,"Binding never changes actual inventory/wallet/ordinary gameplay or team state");assertTrue(notifications.isEmpty());
  }
  @Test void completeFixedRequestWriterConsumerAndOriginalTablesControl() throws Exception {
    assertEquals(6,fixture.getAsJsonObject("files").size());assertEquals(52,fixture.getAsJsonArray("wholeNativeFunctions").size());
    int bytes=0;for(var value:fixture.getAsJsonArray("wholeNativeFunctions")) {
      var row=value.getAsJsonObject();byte[] body=Base64.getDecoder().decode(row.get("rawBase64").getAsString());
      assertEquals(row.get("bodySHA256").getAsString(),sha(body));assertEquals(row.get("bytes").getAsInt(),body.length);bytes+=body.length;
    }
    assertEquals(11257,bytes);assertEquals(4847,session.getProtocol().clientCommand("EquipAetherDividePassiveSkillCsReq"));
    assertEquals(4825,session.getProtocol().clientCommand("EquipSkillCoreScRsp"));assertEquals(4823,session.getProtocol().clientCommand("TakeOffSkillCoreCsReq"));
    assertEquals(4802,session.getProtocol().clientCommand("TakeOffSkillCoreScRsp"));assertFalse(fixture.get("clientAccepted").getAsBoolean());
  }
  @Test void originalSlotTypesAndPromotionRowsAreNotAUniformPolicyControl() {
    var files=fixture.getAsJsonObject("files");var source=files.getAsJsonObject("ExcelOutput/AetherDivideSpirit.json").getAsJsonArray("completeRows");
    var spirit=source.asList().stream().map(JsonElement::getAsJsonObject).filter(r->r.get("AvatarID").getAsInt()==6001).findFirst().orElseThrow();
    assertEquals(gson.toJsonTree(List.of("Trick","Assist")),spirit.get("PassiveSkillSlotList"));
    var fourth=source.asList().stream().map(JsonElement::getAsJsonObject).filter(r->r.get("AvatarID").getAsInt()==6010).findFirst().orElseThrow();
    assertEquals("Defend",fourth.getAsJsonArray("PassiveSkillSlotList").get(3).getAsString());
    var slots=new TreeMap<Integer,Integer>();for(var value:files.getAsJsonObject("ExcelOutput/AetherDivideSpiritPromotion.json").getAsJsonArray("completeRows")) {
      var row=value.getAsJsonObject();if(row.get("AvatarID").getAsInt()==6001 && row.has("Slot"))slots.put(row.get("Slot").getAsInt(),row.get("Promotion").getAsInt());
    }
    assertEquals(Map.of(1,2,2,4),slots);assertEquals(0,fixture.getAsJsonObject("skillTypeEnum").get("Defend").getAsInt());
  }
  @Test void sourceValidSlotOneSnapshotControlBeforeAnyEquipCommand() throws Exception {
    existingRoster();seededBinding(6001,1,250202);String before=gson.toJson(state);var reply=query();assertEquals(0,number(reply,6));
    var spirit=rows(reply,15).stream().map(DynamicMessage.class::cast).filter(v->number(v,10)==6001).findFirst().orElseThrow();
    assertEquals(1,number((DynamicMessage)rows(spirit,13).getFirst(),5));unchanged(before,0);
  }
  @Test void sourceEmptySlotEquipPublishesCompleteRowsAfterSavingOnce() throws Exception {
    existingRoster();var before=fullView();var reply=equip(6001,1,250202);assertCoreReply(reply,true,6001,1,250202,3);
    assertEquals(1,saves);assertEquals(durable,gson.toJson(state));assertEquals(250202,state.getAetherDivide().getSpirits().get(6001).getSkills().get(1));
    assertEquals(6001,state.getAetherDivide().getSkillCores().get(250202).getDressedSpirit());onlyAetherChanged(before);
  }
  @Test void sourceTakeOffPublishesRemovedCoreAndFullSpiritAfterSavingOnce() throws Exception {
    existingRoster();seededBinding(6001,1,250202);var before=fullView();var reply=off(6001,1);assertCoreReply(reply,false,6001,1,250202,3);
    assertEquals(1,saves);assertEquals(durable,gson.toJson(state));assertTrue(state.getAetherDivide().getSpirits().get(6001).getSkills().isEmpty());
    assertEquals(0,state.getAetherDivide().getSkillCores().get(250202).getDressedSpirit());onlyAetherChanged(before);
  }
  @Test void sameBindingEquipAndQueriesAreReadOnlyAfterTheFirstSave() throws Exception {
    existingRoster();equip(6001,1,250202);assertEquals(1,saves);var before=fullView();var pointer=state.getAetherDivide();
    assertCoreReply(equip(6001,1,250202),true,6001,1,250202,3);var first=query();var second=query();assertEquals(first,second);
    assertEquals(before,fullView());assertSame(pointer,state.getAetherDivide());assertEquals(1,saves);
  }
  @Test void failedEquipRestoresExactRunInventoryAndReceiptThenRetries() throws Exception {
    existingRoster();var pointer=state.getAetherDivide();var before=fullView();failSave=true;
    rejected(equip(6001,1,250202),true);assertSame(pointer,state.getAetherDivide());assertEquals(before,fullView());assertEquals(0,saves);assertEquals("",durable);
    failSave=false;assertCoreReply(equip(6001,1,250202),true,6001,1,250202,3);assertEquals(1,saves);onlyAetherChanged(before);
  }
  @Test void failedTakeOffRestoresExactBindingAndCanRetryTheSameRequest() throws Exception {
    existingRoster();seededBinding(6001,1,250202);var pointer=state.getAetherDivide();var before=fullView();failSave=true;
    rejected(off(6001,1),false);assertSame(pointer,state.getAetherDivide());assertEquals(before,fullView());assertEquals(0,saves);
    failSave=false;assertCoreReply(off(6001,1),false,6001,1,250202,3);assertEquals(1,saves);onlyAetherChanged(before);
  }
  @ParameterizedTest @CsvSource({"6001,0,250202","6001,3,250202","6001,2,250205","6001,1,250201","9999,1,250202","6001,1,999999"})
  void invalidOneBasedTypedOrLockedTargetsNeverChangeState(int avatar,int slot,int item) throws Exception {
    existingRoster();var before=fullView();rejected(equip(avatar,slot,item),true);assertEquals(before,fullView());assertEquals(0,saves);
  }
  @Test void insufficientPromotionCannotOpenTheOriginalFirstSlot() throws Exception {
    existingRoster();sourceSpirit(6001,1);var before=fullView();rejected(equip(6001,1,250202),true);assertEquals(before,fullView());assertEquals(0,saves);
  }
  @Test void originalSecondSlotOpensAtPromotionFourForAssistCore() throws Exception {
    existingRoster();sourceSpirit(6001,4);assertCoreReply(equip(6001,2,250205),true,6001,2,250205,2);assertEquals(1,saves);
  }
  @Test void omittedDefendTypeIsTheNativeZeroDefaultOnAnActualDeclaredSlot() throws Exception {
    existingRoster();sourceSpirit(6005,1);assertCoreReply(equip(6005,1,250203),true,6005,1,250203,5);assertEquals(1,saves);
  }
  @Test void actualInventoryWithoutALegacyCoreHintCanBeBoundButNotManufactured() throws Exception {
    existingRoster();fixtureRoster.getAsJsonObject("skillCores").remove("250202");editRoster("skillCores",fixtureRoster.get("skillCores"));
    var before=fullView();assertCoreReply(equip(6001,1,250202),true,6001,1,250202,3);assertEquals(1,saves);onlyAetherChanged(before);
  }
  @Test void legacyHintWithoutActualInventoryCannotGrantOrEquipACore() throws Exception {
    existingRoster();removeMaterial(250202);var before=fullView();rejected(equip(6001,1,250202),true);assertEquals(before,fullView());assertEquals(0,saves);
  }
  @Test void occupiedReplacementIsExplicitlyRejectedWithoutClearingTheOldCore() throws Exception {
    existingRoster();seededBinding(6001,1,250202);var before=fullView();rejected(equip(6001,1,250204),true);assertEquals(before,fullView());assertEquals(0,saves);
  }
  @Test void crossSpiritTransferIsExplicitlyRejectedUntilTheOldBindingIsTakenOff() throws Exception {
    existingRoster();sourceSpirit(6005,3);seededBinding(6001,1,250202);var before=fullView();rejected(equip(6005,2,250202),true);
    assertEquals(before,fullView());assertEquals(0,saves);assertCoreReply(off(6001,1),false,6001,1,250202,3);
    assertCoreReply(equip(6005,2,250202),true,6005,2,250202,3);assertEquals(2,saves);
  }
  @Test void duplicateEmptyTakeOffDoesNotPublishAFakeSuccessfulCore() throws Exception {
    existingRoster();seededBinding(6001,1,250202);off(6001,1);assertEquals(1,saves);var before=fullView();rejected(off(6001,1),false);
    assertEquals(before,fullView());assertEquals(1,saves);
  }
  @ParameterizedTest @CsvSource({"0,250202,2","3,250202,6","1,250201,2","2,250205,2"})
  void sourceInvalidPersistedSlotsAreRejectedByTheExistingRosterQuery(int slot,int item,int promotion) throws Exception {
    existingRoster();sourceSpirit(6001,promotion);seededBinding(6001,slot,item);String before=gson.toJson(state);
    var response=query();assertNotEquals(0,number(response,6),"Source-invalid slot/type/opening cannot be sent into the real client dictionary");
    assertTrue(rows(response,15).isEmpty());unchanged(before,0);
  }
  @Test void sourceValidFourthSlotIsNotRejectedByTheOldZeroThroughThreeLimit() throws Exception {
    existingRoster();sourceSpirit(6010,5);seededBinding(6010,4,250203);String before=gson.toJson(state);var response=query();assertEquals(0,number(response,6));
    var spirit=rows(response,15).stream().map(DynamicMessage.class::cast).filter(v->number(v,10)==6010).findFirst().orElseThrow();
    assertEquals(4,number((DynamicMessage)rows(spirit,13).getFirst(),5));unchanged(before,0);
  }
  @Test void sourceCorruptionRejectsBothRequestsWithoutSaving() throws Exception {
    existingRoster();String before=gson.toJson(state);Files.writeString(root.resolve("ExcelOutput/AetherDividePassiveSkill.json"),"[]");
    rejected(equip(6001,1,250202),true);rejected(off(6001,1),false);unchanged(before,0);
  }
  @Test void emptyBodiesDoNotCreateBindingsOrEmptySuccessRows() throws Exception {
    existingRoster();String before=gson.toJson(state);rejected(request("EquipAetherDividePassiveSkillCsReq",new byte[0]),true);
    rejected(request("TakeOffSkillCoreCsReq",new byte[0]),false);unchanged(before,0);
  }
  @Test void restoredSamePlayerDocumentCanQueryTwiceAndTakeOffTheSavedBinding() throws Exception {
    existingRoster();equip(6001,1,250202);assertEquals(1,saves);var saved=durable;var restored=gson.fromJson(saved,GameplayState.class);
    assertNotSame(state,restored);state=restored;var before=fullView();var first=query();var second=query();assertEquals(first,second);
    assertEquals(before,fullView());assertEquals(1,saves);assertCoreReply(off(6001,1),false,6001,1,250202,3);assertEquals(2,saves);
  }
  @Test void savedBindingRestoresIntoTwoIndependentActualPlayerDocuments() throws Exception {
    var uidField=Player.class.getDeclaredField("uid");uidField.setAccessible(true);uidField.setInt(player,194847);
    existingRoster();equip(6001,1,250202);assertEquals(1,saves);
    var server=new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());var address=server.bind();
    try(var client=com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:"+address.getPort()+"/?directConnection=true")) {
      var providers=org.bson.codecs.configuration.CodecRegistries.fromCodecs(new emu.lunarcore.database.codecs.IntSetCodec(),
          new emu.lunarcore.database.codecs.IntListCodec(),new emu.lunarcore.database.codecs.Int2IntMapCodec());
      var options=dev.morphia.mapping.MapperOptions.builder().storeEmpties(true).storeNulls(false).codecProvider(providers).build();
      var store=dev.morphia.Morphia.createDatastore(client,"aether_core_actual_player",options);store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class,emu.lunarcore.game.quest.QuestState.class,emu.lunarcore.util.Position.class);
      var document=new Player();uidField.setInt(document,194847);var gameplay=Player.class.getDeclaredField("gameplay");gameplay.setAccessible(true);
      gameplay.set(document,gson.fromJson(durable,GameplayState.class));store.save(document);
      var collection=client.getDatabase("aether_core_actual_player").getCollection("players");var before=collection.find().first();Player previous=null;
      for(int read=0;read<2;read++) {
        var restored=Objects.requireNonNull(store.find(Player.class).first());assertNotSame(previous,restored);previous=restored;
        assertEquals(194847,restored.getUid());state=restored.getGameplay();assertEquals(JsonParser.parseString(durable),gson.toJsonTree(state));
        var response=query();assertEquals(0,number(response,6));var spirit=rows(response,15).stream().map(DynamicMessage.class::cast)
            .filter(v->number(v,10)==6001).findFirst().orElseThrow();assertEquals(1,number((DynamicMessage)rows(spirit,13).getFirst(),5));
        assertEquals(before,collection.find().first());assertEquals(1,saves);
      }
    } finally {server.shutdownNow();}
  }
}
