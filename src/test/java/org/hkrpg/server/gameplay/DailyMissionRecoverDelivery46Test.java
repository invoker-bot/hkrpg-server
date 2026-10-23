package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.database.DatabaseManager;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.*;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.util.Position;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Fixed originals through the existing 1205/1261 handlers and real inventory.
 * Clock, one eligible catalog, completed prerequisites and scene positioning are fixtures.
 * Real GameItem writes are isolated in an owned MemoryMongo datastore; no operator DB,
 * NPC source-only graph installation, CustomString execution or game UI acceptance occurs. */
class DailyMissionRecoverDelivery46Test {
  @Test void sourceScopeChecksResourcesWhenFirstUsed() throws Exception {
    assertTrue(DailyMissionRecoverDeliveryRules.source());
    var path = root.resolve("Config/Level/Mission/3011014/MissionInfo_3011014.json");
    var bytes = Files.readAllBytes(path);
    var timestamp = Files.getLastModifiedTime(path);
    try (var scope = DailyMissionRecoverDeliveryRules.sourceScope()) {
      // An unused scope must not bind a snapshot before its first source-dependent action.
      var changed = bytes.clone(); changed[0] = '!';
      Files.write(path, changed); Files.setLastModifiedTime(path, timestamp);
      assertFalse(DailyMissionRecoverDeliveryRules.source());
    } finally { Files.write(path, bytes); Files.setLastModifiedTime(path, timestamp); }
  }

  @Test void firstSourceUseStillChecksTheNextScopeAfterSourceChanges() throws Exception {
    try (var scope = DailyMissionRecoverDeliveryRules.sourceScope()) {
      assertTrue(DailyMissionRecoverDeliveryRules.source());
      assertTrue(DailyMissionRecoverDeliveryRules.source());
    }
    var path = root.resolve("Config/Level/Mission/3011014/MissionInfo_3011014.json");
    var bytes = Files.readAllBytes(path);
    var timestamp = Files.getLastModifiedTime(path);
    var changed = bytes.clone(); changed[0] = '!';
    Files.write(path, changed); Files.setLastModifiedTime(path, timestamp);
    try (var scope = DailyMissionRecoverDeliveryRules.sourceScope()) {
      assertFalse(DailyMissionRecoverDeliveryRules.source());
    } finally { Files.write(path, bytes); Files.setLastModifiedTime(path, timestamp); }
    try (var scope = DailyMissionRecoverDeliveryRules.sourceScope()) {
      assertTrue(DailyMissionRecoverDeliveryRules.source());
    }
  }

  static String sourceSHA(byte[] bytes)throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
  static byte[] sourceBytes(JsonObject metadata)throws Exception{
    if(metadata.has("sourceBlobBase64"))return java.util.Base64.getDecoder().decode(metadata.get("sourceBlobBase64").getAsString());
    try(var input=new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(metadata.get("sourceBlobDeflateBase64").getAsString())))){return input.readAllBytes();}
  }
  static final int MAIN=3011014,FIRST=301101401,MENU=301101402,END=301101412;
  static final int[] OPTIONS={301101403,301101404,301101405};
  static final int[] ITEMS={140146,140147,140148};
  static final int[] FLOORS={20121001,20123001,10102003};
  static final int[] PLANES={20121,20123,10102};
  static final int[] CONTACT_GROUPS={93,76,23};
  static final int[] CONTACT_NPCS={11104,11205,11403};
  static de.bwaldvogel.mongo.MongoServer mongo;
  static DatabaseManager database,oldDatabase;
  static final class SourceHolder {
    static final JsonObject VALUE=read();
    static final Map<String,byte[]> CONFIG_BYTES=configBytes();
    static final Map<String,JsonElement> CONFIG_OBJECTS=configObjects();
    static JsonObject read(){try{
      var input=DailyMissionRecoverDelivery46Test.class.getResourceAsStream("/fixtures/daily-recover-delivery-3011014-4.6.json");
      assertNotNull(input);JsonObject value;
      try(input){value=JsonParser.parseString(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();}
      assertEquals(68,value.getAsJsonObject("sourceFiles").size());assertEquals(195,value.getAsJsonArray("records").size());
      for(var e:value.getAsJsonObject("sourceFiles").entrySet())assertEquals(e.getValue().getAsJsonObject().get("sourceBlobSHA256").getAsString(),sourceSHA(sourceBytes(e.getValue().getAsJsonObject())),e.getKey());
      return value;
    }catch(Exception failure){throw new ExceptionInInitializerError(failure);}}
    static Map<String,byte[]> configBytes(){try{
      var result=new HashMap<String,byte[]>();for(var e:VALUE.getAsJsonObject("sourceFiles").entrySet())if(e.getKey().startsWith("Config/"))result.put(e.getKey(),sourceBytes(e.getValue().getAsJsonObject()));return Map.copyOf(result);
    }catch(Exception failure){throw new ExceptionInInitializerError(failure);}}
    static Map<String,JsonElement> configObjects(){var result=new HashMap<String,JsonElement>();CONFIG_BYTES.forEach((path,raw)->result.put(path,JsonParser.parseString(new String(raw,java.nio.charset.StandardCharsets.UTF_8))));return Map.copyOf(result);}
  }
  final DailyMissionMessage46Test daily=new DailyMissionMessage46Test();
  final Map<String,JsonElement> originals=new HashMap<>();
  final Map<Integer,Integer> entries=new HashMap<>();
  @TempDir Path root;
  String oldResource;
  JsonObject source;

  @BeforeAll static void isolateRealItemWrites()throws Exception{
    mongo=new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());var address=mongo.bind();
    var info=new Config.DatabaseInfo();info.uri="mongodb://"+address.getHostString()+":"+address.getPort();info.collection="recover_delivery_owned_3011014";info.useInternal=false;
    oldDatabase=LunarCore.getGameDatabase();database=new DatabaseManager(info,LunarCore.ServerType.GAME);
    var field=LunarCore.class.getDeclaredField("gameDatabase");field.setAccessible(true);field.set(null,database);
    assertFalse(info.useInternal);assertTrue(database.getDatabase().getName().startsWith("recover_delivery_owned_"));
  }
  @AfterAll static void closeOwnedDatabase()throws Exception{
    try{var field=LunarCore.class.getDeclaredField("gameDatabase");field.setAccessible(true);field.set(null,oldDatabase);}
    finally{if(database!=null)database.close();if(mongo!=null)mongo.shutdownNow();}
  }
  static void resourceDir(String value)throws Exception{
    var field=LunarCore.getConfig().getClass().getDeclaredField("resourceDir");field.setAccessible(true);field.set(LunarCore.getConfig(),value);
  }
  @BeforeEach void setup()throws Exception{
    daily.temp=root;daily.setup();source=SourceHolder.VALUE;
    oldResource=LunarCore.getConfig().getResourceDir();resourceDir(root.toString());
    originals.putAll(SourceHolder.CONFIG_OBJECTS);
    for(var e:SourceHolder.CONFIG_BYTES.entrySet()){
      var metadata=source.getAsJsonObject("sourceFiles").getAsJsonObject(e.getKey());var raw=e.getValue();
      if(metadata.get("installed").getAsBoolean()){
        var target=root.resolve(e.getKey());Files.createDirectories(target.getParent());Files.write(target,raw);
      }
    }
    var tables=new HashMap<String,JsonArray>();
    daily.fixture.countRows=new JsonArray();GameplayDefinitions.dailyPool.clear();
    for(var raw:source.getAsJsonArray("records")){
      var e=raw.getAsJsonObject();var row=e.get("row");String path=e.get("source").getAsString();
      if(path.startsWith("ExcelOutput/"))tables.computeIfAbsent(path,ignored->new JsonArray()).add(row);
      switch(e.get("kind").getAsString()){
        case "main" -> GameData.getMainMissionExcelMap().add(daily.gson.fromJson(row,MainMissionExcel.class));
        case "daily" -> daily.daily.put(row.getAsJsonObject().get("ID").getAsInt(),row.getAsJsonObject());
        case "count" -> daily.fixture.countRows.add(row);
        case "quest" -> GameData.getQuestExcelMap().add(daily.gson.fromJson(row,QuestExcel.class));
        case "rule" -> GameData.getFinishWayExcelMap().add(daily.gson.fromJson(row,FinishWayExcel.class));
        case "reward" -> {var r=daily.gson.fromJson(row,RewardExcel.class);r.onLoad();GameData.getRewardExcelMap().add(r);}
        case "item" -> GameData.getItemExcelMap().add(daily.gson.fromJson(row,ItemExcel.class));
        case "npc" -> GameData.getNpcExcelMap().add(daily.gson.fromJson(row,NpcExcel.class));
        case "prop" -> {var p=daily.gson.fromJson(row,PropExcel.class);p.onLoad();GameData.getPropExcelMap().add(p);}
        case "plane" -> GameData.getMazePlaneExcelMap().add(daily.gson.fromJson(row,MazePlaneExcel.class));
        case "pool" -> GameplayDefinitions.dailyPool.add(daily.gson.fromJson(row,GameplayDefinitions.DailyPool.class));
        case "entrance" -> {var entry=daily.gson.fromJson(row,MapEntranceExcel.class);GameData.getMapEntranceExcelMap().add(entry);entries.merge(entry.getFloorID(),entry.getId(),Math::min);}
        default -> {}
      }
    }
    for(var e:tables.entrySet()){var target=root.resolve(e.getKey());Files.createDirectories(target.getParent());Files.writeString(target,e.getValue().toString());}
    var full=originals.get("Config/Level/Mission/3011014/MissionInfo_3011014.json").getAsJsonObject().deepCopy();
    full.add("SubMissionList",originals.get("Config/Level/Mission/3011014/MissionInfo_3011014.partial.json").getAsJsonObject().get("SubMissionList"));
    var graph=daily.gson.fromJson(full,MissionInfo.class);GameData.getMissionInfos().put(MAIN,graph);
    for(var sub:graph.getSubMissionList()){
      GameplayDefinitions.subs.put(sub.getId(),sub);var script=originals.get(sub.getMissionJsonPath());assertNotNull(script);
      var task=daily.gson.fromJson(script,MissionTaskInfo.class);task.retainSourceDefinition(script);sub.setTask(task);
    }
    for(var raw:source.getAsJsonArray("records")){
      var e=raw.getAsJsonObject();if(!e.get("kind").getAsString().equals("floor"))continue;
      var floor=daily.gson.fromJson(e.get("row"),FloorInfo.class);
      for(var index:source.getAsJsonObject("groupIndices").entrySet()){
        String path=index.getKey();if(!path.contains("Groups_P"+(floor.getFloorID()/1000)+"_F"+floor.getFloorID()+"/"))continue;
        int groupId=Integer.parseInt(path.replaceAll(".*_G([0-9]+)\\.json","$1"));
        var group=daily.gson.fromJson(originals.get(path),GroupInfo.class);group.setId(groupId);floor.registerGroup(index.getValue().getAsInt(),group);
      }
      floor.onLoad();GameData.getFloorInfos().put("P"+(floor.getFloorID()/1000)+"_F"+floor.getFloorID(),floor);
    }
    daily.catalog(MAIN);
    for(int id:List.of(2010501,1010501,1010902))daily.actor.getGameplay().getMainMissions().put(id,DailyMissionAcceptance46Test.closed(id));
    assertEquals(0,daily.actor.getScoin());assertTrue(daily.actor.getGameplay().getMissionPayments().isEmpty());
  }
  @AfterEach void cleanup()throws Exception{
    try{daily.cleanup();}finally{if(oldResource!=null)resourceDir(oldResource);if(database!=null)database.getDatabase().drop();}
  }
  void floor(int plane,int floor){
    assertTrue(daily.actor.loadScene(plane,floor,entries.get(floor),new Position(),new Position(),false));
    assertEquals(0,daily.actor.getScene().getDimensionId());assertEquals(0,daily.actor.getScene().getStoryLineId());
  }
  void accept(){
    floor(10102,10102001);assertEquals(Retcode.SUCC,daily.actor.getMissionManager().accept(MAIN));
    assertTrue(MissionStateRecords.mainDoing(daily.actor,MAIN));assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(FIRST),FIRST,1));
  }
  int report(int id){return (int)daily.field(daily.query("FinishTalkMissionCsReq",Map.of("sub_mission_id",id,"talk_str","Mission_"+id)),"retcode");}
  int consume(int id,Map<Integer,Integer> costs){
    var protocol=daily.session().getProtocol();assertEquals(1261,protocol.clientCommand("FinishCosumeItemMissionCsReq"));
    var request=new HashMap<String,Object>();request.put("sub_mission_id",id);
    if(costs!=null){var list=DynamicMessage.newBuilder(protocol.clientSchema().message("ItemList"));
      for(var e:costs.entrySet()){var item=DynamicMessage.newBuilder(protocol.clientSchema().message("Item"));
        item.setField(item.getDescriptorForType().findFieldByName("item_id"),e.getKey());item.setField(item.getDescriptorForType().findFieldByName("num"),e.getValue());
        list.addRepeatedField(list.getDescriptorForType().findFieldByName("item_list"),item.build());}
      request.put("item_list",list.build());}
    var response=daily.query("FinishCosumeItemMissionCsReq",request);assertEquals(id,daily.field(response,"sub_mission_id"));return (int)daily.field(response,"retcode");
  }
  void first(){accept();assertEquals(0,report(FIRST));assertTrue(MissionStateRecords.subCompleted(daily.actor,FIRST));assertEquals(2,daily.actor.getScene().getEntitiesByGroup(EntityProp.class,122).size());}
  void choose(int index){first();assertEquals(0,report(OPTIONS[index]));var item=daily.actor.getInventory().getMaterialByItemId(ITEMS[index]);assertNotNull(item);assertEquals(1,item.getCount());assertEquals(daily.actor.getUid(),item.getOwnerUid());}
  void paid(int index){choose(index);assertEquals(0,report(MENU));floor(PLANES[index],FLOORS[index]);assertEquals(0,consume(301101406+index,Map.of(ITEMS[index],1)));assertNull(daily.actor.getInventory().getMaterialByItemId(ITEMS[index]));}
  void settle(int index){paid(index);assertEquals(0,report(301101409+index));assertEquals(5000,daily.actor.getScoin());assertEquals(QuestState.Phase.CLOSED,daily.actor.getGameplay().getMainMissions().get(MAIN).getPhase());assertEquals(0,daily.actor.getGameplay().getDailyPoints());assertEquals(Retcode.SUCC,daily.actor.getDailyManager().claimQuest(2100003).retcode());assertEquals(200,daily.actor.getGameplay().getDailyPoints());}
  void removeNpc(int group,int inst){for(var npc:List.copyOf(daily.actor.getScene().getEntitiesByGroup(EntityNpc.class,group)))if(npc.getInstId()==inst)daily.actor.getScene().removeEntity(npc);}

  @Test void wholeSourceHasThreeAuthoredGrantsAndOnlyOneFeedbackRequired(){
    var graph=GameData.getMissionInfos().get(MAIN);assertEquals(12,graph.getSubMissionList().length);assertArrayEquals(new int[]{FIRST},graph.getStartSubMissionList());assertArrayEquals(new int[]{END},graph.getFinishSubMissionList());
    assertEquals(1,GameplayDefinitions.subs.get(END).getProgress());assertEquals(List.of(301101409,301101410,301101411),Arrays.asList(GameplayDefinitions.subs.get(END).getParamIntList()));
    for(int i=0;i<3;i++){var sub=GameplayDefinitions.subs.get(OPTIONS[i]);assertEquals("addRecoverMissionItem",sub.getFinishActionList()[0].getType());assertArrayEquals(new int[]{ITEMS[i],1},sub.getFinishActionList()[0].getParams());assertEquals("Mission",GameData.getItemExcelMap().get(ITEMS[i]).getItemMainType().name());}
    assertEquals(2010501,DailyMissionCatalog.mission(MAIN).UnlockMainMission());assertEquals(1,DailyMissionCatalog.count(1).DailyCount());
  }
  @Test void originalRecoverGraphIsAQualifiedFiniteDaily(){assertTrue(DailyMissionRepeats.supportedGraph(MAIN),"Original recover-item actions and consume costs exclude this authored graph");}
  @Tag("full-source") @Test void allCompleteInstalledTablesAlsoQualifyIncludingOriginalRowsWithAnOmittedDefaultId()throws Exception{
    for(var e:source.getAsJsonObject("sourceFiles").entrySet())if(e.getKey().startsWith("ExcelOutput/"))Files.write(root.resolve(e.getKey()),sourceBytes(e.getValue().getAsJsonObject()));
    assertTrue(DailyMissionRepeats.supportedGraph(MAIN),"The full original installed tables must work, including unrelated rows whose omitted ID denotes default zero");
    floor(10102,10102001);daily.actor.getMissionManager().refresh();assertTrue(MissionStateRecords.mainDoing(daily.actor,MAIN));
  }
  @Test void originalUnlockedPoolActuallyAssignsTheFirstTarget(){floor(10102,10102001);daily.actor.getMissionManager().refresh();assertTrue(MissionStateRecords.mainDoing(daily.actor,MAIN),"Real daily assignment cannot select the original recover-item chain");assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(FIRST),FIRST,1));assertEquals(0,daily.actor.getScoin());}
  @ParameterizedTest @ValueSource(strings={"MissionInfo_3011014.json","MissionInfo_3011014.partial.json","Mission_301101403.json","Act/Act301101402.json","Talk/Talk_301101406.json"})
  void aMissingRequiredWholeSourceCannotBeManuallyAccepted(String path)throws Exception{Files.delete(root.resolve("Config/Level/Mission/3011014/"+path));assertNotEquals(Retcode.SUCC,daily.actor.getMissionManager().accept(MAIN));assertNull(daily.actor.getGameplay().getMainMissions().get(MAIN));}
  @Test void aChangedWholeBranchCannotBorrowOriginalLoadedSubIds()throws Exception{var path=root.resolve("Config/Level/Mission/3011014/Mission_301101403.json");var graph=JsonParser.parseString(Files.readString(path)).getAsJsonObject();graph.addProperty("ForeignSource",true);Files.writeString(path,graph.toString());assertNotEquals(Retcode.SUCC,daily.actor.getMissionManager().accept(MAIN));}
  @Test void theOriginalInitialContactMustActuallyRemainLoaded(){accept();removeNpc(99,400002);var before=daily.gson.toJson(daily.actor.getGameplay());assertNotEquals(0,report(FIRST));assertEquals(before,daily.gson.toJson(daily.actor.getGameplay()));}
  @Test void anExternalInitialInstanceCannotStandInForOriginalNpc(){accept();var npc=daily.actor.getScene().getEntitiesByGroup(EntityNpc.class,99).getFirst();npc.setInstId(400001);assertNotEquals(0,report(FIRST));assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(FIRST),FIRST,1));}
  @ParameterizedTest @ValueSource(ints={300001,300002})
  void optionReportRequiresBothOriginalMenuPropsNotOrdinaryProp100(int instance){first();for(var prop:List.copyOf(daily.actor.getScene().getEntitiesByGroup(EntityProp.class,122)))if(prop.getInstId()==instance)daily.actor.getScene().removeEntity(prop);assertNotEquals(0,report(OPTIONS[0]));assertNull(daily.actor.getInventory().getMaterialByItemId(ITEMS[0]));}
  @ParameterizedTest @ValueSource(ints={0,1,2})
  void eachOriginalOptionReallyGrantsOneThen1261AndOneFeedbackCompleteProgressOne(int index){settle(index);assertTrue(MissionStateRecords.subCompleted(daily.actor,END));for(int other=0;other<3;other++)if(other!=index){assertFalse(MissionStateRecords.subCompleted(daily.actor,301101409+other));assertNull(daily.actor.getInventory().getMaterialByItemId(ITEMS[other]));}assertNotEquals(0,report(301101409+index));assertEquals(5000,daily.actor.getScoin());}
  @ParameterizedTest @ValueSource(ints={0,1,2})
  void aSecondOriginalOptionInTheSamePackCannotGrantAnotherItem(int index){choose(index);int other=(index+1)%3;assertNotEquals(0,report(OPTIONS[other]),"Original PlayOptionTalk selects one branch of this pack");assertNull(daily.actor.getInventory().getMaterialByItemId(ITEMS[other]));assertEquals(1,daily.actor.getInventory().getMaterialByItemId(ITEMS[index]).getCount());}
  @ParameterizedTest @ValueSource(ints={0,1,2})
  void aMissingOriginalDeliveryContactDoesNotDebitTheActualGrantedItem(int index){choose(index);assertEquals(0,report(MENU));floor(PLANES[index],FLOORS[index]);removeNpc(CONTACT_GROUPS[index],400001);var item=daily.actor.getInventory().getMaterialByItemId(ITEMS[index]);assertNotEquals(0,consume(301101406+index,Map.of(ITEMS[index],1)));assertEquals(1,item.getCount());assertTrue(daily.actor.getGameplay().getMissionPayments().isEmpty());}
  @ParameterizedTest @ValueSource(ints={0,1,2})
  void aMissingFeedbackContactCannotPayTheMainReward(int index){paid(index);removeNpc(CONTACT_GROUPS[index],400001);assertNotEquals(0,report(301101409+index));assertEquals(0,daily.actor.getScoin());assertTrue(MissionStateRecords.mainDoing(daily.actor,MAIN));}
  @ParameterizedTest @ValueSource(ints={0,1,2})
  void aFalseSubmissionListIsRejectedWithoutRemovingTheOriginalOne(int index){choose(index);assertEquals(0,report(MENU));floor(PLANES[index],FLOORS[index]);var item=daily.actor.getInventory().getMaterialByItemId(ITEMS[index]);assertEquals(Retcode.REQ_PARA_INVALID.getVal(),consume(301101406+index,Map.of(ITEMS[index],2)));assertEquals(1,item.getCount());assertTrue(daily.actor.getGameplay().getMissionPayments().isEmpty());}
  @ParameterizedTest @ValueSource(ints={0,1,2})
  void feedbackIsNotAssignedBeforeItsActualPayment(int index){choose(index);assertNull(daily.actor.getMissionManager().sub(301101409+index));floor(PLANES[index],FLOORS[index]);assertNotEquals(0,report(301101409+index));assertEquals(0,daily.actor.getScoin());assertEquals(1,daily.actor.getInventory().getMaterialByItemId(ITEMS[index]).getCount());}
  @Test void aRejectedInitialTalkSaveRestoresTheExactAcceptanceAndAllDomains(){accept();var quest=daily.actor.getMissionManager().sub(FIRST);var before=daily.gson.toJson(daily.actor.getGameplay());var durable=daily.actor.durableGameplay;int saves=daily.actor.saves;daily.packets.clear();daily.actor.failSave=true;assertThrows(IllegalStateException.class,()->daily.actor.getMissionManager().finishTalk(FIRST,"Mission_"+FIRST));assertSame(quest,daily.actor.getMissionManager().sub(FIRST));assertEquals(QuestState.Phase.DOING,quest.getPhase());assertEquals(before,daily.gson.toJson(daily.actor.getGameplay()));assertEquals(durable,daily.actor.durableGameplay);assertEquals(saves,daily.actor.saves);assertTrue(daily.packets.isEmpty());daily.actor.failSave=false;assertEquals(0,report(FIRST));}
  @Test void aRejectedChoiceReservationRestoresTheQuestAndGivesNoItem(){first();var quest=daily.actor.getMissionManager().sub(OPTIONS[0]);var main=daily.actor.getGameplay().getMainMissions().get(MAIN);var before=daily.gson.toJson(daily.actor.getGameplay());var durable=daily.actor.durableGameplay;int saves=daily.actor.saves;daily.packets.clear();daily.actor.failSave=true;assertThrows(IllegalStateException.class,()->daily.actor.getMissionManager().finishTalk(OPTIONS[0],"Mission_"+OPTIONS[0]));assertSame(quest,daily.actor.getMissionManager().sub(OPTIONS[0]));assertSame(main,daily.actor.getGameplay().getMainMissions().get(MAIN));assertEquals(before,daily.gson.toJson(daily.actor.getGameplay()));assertNull(daily.actor.getInventory().getMaterialByItemId(ITEMS[0]));assertEquals(durable,daily.actor.durableGameplay);assertEquals(saves,daily.actor.saves);assertTrue(daily.packets.isEmpty());daily.actor.failSave=false;assertEquals(0,report(OPTIONS[0]));assertEquals(1,daily.actor.getInventory().getMaterialByItemId(ITEMS[0]).getCount());}
  @Test void aRejectedFeedbackSavePreservesTheActualPaidReceiptAndRetry(){paid(0);var quest=daily.actor.getMissionManager().sub(301101409);var payment=daily.actor.getGameplay().getMissionPayments().get(301101406);var plan=daily.actor.getGameplay().getMissionPaymentPlans().get(301101406);var before=daily.gson.toJson(daily.actor.getGameplay());int saves=daily.actor.saves;daily.packets.clear();daily.actor.failSave=true;assertThrows(IllegalStateException.class,()->daily.actor.getMissionManager().finishTalk(301101409,"Mission_301101409"));assertSame(quest,daily.actor.getMissionManager().sub(301101409));assertSame(payment,daily.actor.getGameplay().getMissionPayments().get(301101406));assertSame(plan,daily.actor.getGameplay().getMissionPaymentPlans().get(301101406));assertEquals(QuestState.Phase.DOING,quest.getPhase());assertEquals(before,daily.gson.toJson(daily.actor.getGameplay()));assertEquals(saves,daily.actor.saves);assertTrue(daily.packets.isEmpty());assertEquals(0,daily.actor.getScoin());daily.actor.failSave=false;assertEquals(0,report(301101409));assertEquals(5000,daily.actor.getScoin());}
  @ParameterizedTest @ValueSource(ints={0,1,2})
  void theNextSourceDayAllocatesANewRunAndCannotReuseTheOldStoryGrantOrPayment(int index){settle(index);var oldMain=daily.actor.getGameplay().getMainMissions().get(MAIN);var oldPayment=daily.actor.getGameplay().getMissionPayments().get(301101406+index);daily.clock(DailyMissionMessage46Test.NEXT);daily.actor.getDailyManager().ensureDay();floor(10102,10102001);daily.actor.getMissionManager().refresh();assertTrue(MissionStateRecords.mainDoing(daily.actor,MAIN),"Settled source recovery actions/payment prevent actual next-day assignment");assertNotSame(oldMain,daily.actor.getGameplay().getMainMissions().get(MAIN));assertTrue(daily.actor.getGameplay().getMissionPayments().isEmpty());assertEquals(0,report(FIRST));assertEquals(0,report(OPTIONS[index]));assertEquals(1,daily.actor.getInventory().getMaterialByItemId(ITEMS[index]).getCount());assertEquals(0,report(MENU));floor(PLANES[index],FLOORS[index]);assertNotEquals(0,report(301101409+index));assertEquals(5000,daily.actor.getScoin());assertEquals(0,consume(301101406+index,Map.of(ITEMS[index],1)));assertNotSame(oldPayment,daily.actor.getGameplay().getMissionPayments().get(301101406+index));assertEquals(0,report(301101409+index));assertEquals(10000,daily.actor.getScoin());assertEquals(Retcode.SUCC,daily.actor.getDailyManager().claimQuest(2100003).retcode());assertEquals(200,daily.actor.getGameplay().getDailyPoints());}
  @Test void anOldDayPacketBeforeAnyQueryCannotGrantTheOldOption(){first();daily.clock(DailyMissionMessage46Test.NEXT);var before=daily.gson.toJson(daily.actor.getGameplay());assertNotEquals(0,report(OPTIONS[0]));assertNull(daily.actor.getInventory().getMaterialByItemId(ITEMS[0]));assertEquals(before,daily.gson.toJson(daily.actor.getGameplay()));}
  @Test void theMenuMayReportBeforeTheParallelChoiceWithoutRespawningTheOriginalUnloadedProps(){first();assertEquals(0,report(MENU));assertTrue(daily.actor.getScene().getEntitiesByGroup(EntityProp.class,122).isEmpty());assertEquals(0,report(OPTIONS[1]));assertEquals(1,daily.actor.getInventory().getMaterialByItemId(ITEMS[1]).getCount());floor(PLANES[1],FLOORS[1]);assertEquals(0,consume(301101407,Map.of(ITEMS[1],1)));assertEquals(0,report(301101410));assertEquals(5000,daily.actor.getScoin());}
  @Test void anUnobservedMenuReportCannotInventTheLateChoiceWitness(){first();for(var prop:List.copyOf(daily.actor.getScene().getEntitiesByGroup(EntityProp.class,122)))daily.actor.getScene().removeEntity(prop);assertNotEquals(0,report(MENU));assertNotEquals(0,report(OPTIONS[0]));assertEquals(0,daily.actor.getScoin());assertNull(daily.actor.getInventory().getMaterialByItemId(ITEMS[0]));}
  @Test void aChangedCompletedInitialAcceptanceCannotAuthorizeTheExistingRealItemDebit()throws Exception{choose(0);assertEquals(0,report(MENU));var original=daily.actor.getMissionManager().sub(FIRST);var replacement=daily.gson.fromJson(daily.gson.toJson(original),QuestState.class);DailyMissionAcceptance46Test.set(replacement,"observationKey",UUID.randomUUID().toString());daily.actor.getGameplay().getSubMissions().put(FIRST,replacement);floor(PLANES[0],FLOORS[0]);var item=daily.actor.getInventory().getMaterialByItemId(ITEMS[0]);assertNotEquals(0,consume(301101406,Map.of(ITEMS[0],1)));assertEquals(1,item.getCount());}
  @Test void aChangedCompletedChoiceAcceptanceCannotAuthorizeTheExistingRealItemDebit()throws Exception{choose(0);assertEquals(0,report(MENU));var original=daily.actor.getMissionManager().sub(OPTIONS[0]);var replacement=daily.gson.fromJson(daily.gson.toJson(original),QuestState.class);DailyMissionAcceptance46Test.set(replacement,"observationKey",UUID.randomUUID().toString());daily.actor.getGameplay().getSubMissions().put(OPTIONS[0],replacement);floor(PLANES[0],FLOORS[0]);var item=daily.actor.getInventory().getMaterialByItemId(ITEMS[0]);assertNotEquals(0,consume(301101406,Map.of(ITEMS[0],1)));assertEquals(1,item.getCount());}
  @Test void anOldGlobalStoryReceiptRemainsUnchangedAndCannotReplaceTheCurrentActualGrant(){first();String key="story:"+OPTIONS[0]+":0";var old=new GameplayState.Receipt(0);old.complete();daily.actor.getGameplay().getRewards().put(key,old);assertEquals(0,report(OPTIONS[0]));assertNotNull(daily.actor.getInventory().getMaterialByItemId(ITEMS[0]),"An old global grant must not be borrowed for the newly observed source run");assertEquals(1,daily.actor.getInventory().getMaterialByItemId(ITEMS[0]).getCount());assertSame(old,daily.actor.getGameplay().getRewards().get(key));assertTrue(old.isGranted());}
  @ParameterizedTest @ValueSource(ints={0,1,2})
  void anUnpaidGrantedRunExpiresWithoutAnInventedRefundAndTheNewRunGrantsOneAgain(int index){choose(index);var item=daily.actor.getInventory().getMaterialByItemId(ITEMS[index]);daily.clock(DailyMissionMessage46Test.NEXT);daily.actor.getDailyManager().ensureDay();floor(10102,10102001);daily.actor.getMissionManager().refresh();assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(FIRST),FIRST,1));assertEquals(1,item.getCount());assertEquals(0,daily.actor.getScoin());assertEquals(0,report(FIRST));assertEquals(0,report(OPTIONS[index]));assertEquals(2,item.getCount());assertEquals(0,report(MENU));floor(PLANES[index],FLOORS[index]);assertNotEquals(0,report(301101409+index));assertEquals(0,consume(301101406+index,Map.of(ITEMS[index],1)));assertEquals(1,item.getCount());assertEquals(0,report(301101409+index));assertEquals(5000,daily.actor.getScoin());}
  @Test void aChangedCompleteItemRowCannotAuthorizeTheOriginalFinishAction()throws Exception{first();var path=root.resolve("ExcelOutput/ItemConfig.json");var rows=JsonParser.parseString(Files.readString(path)).getAsJsonArray();for(var row:rows)if(row.getAsJsonObject().get("ID").getAsInt()==ITEMS[0])row.getAsJsonObject().addProperty("PileLimit",1);Files.writeString(path,rows.toString());assertNotEquals(0,report(OPTIONS[0]));assertNull(daily.actor.getInventory().getMaterialByItemId(ITEMS[0]));}
  @Test void aMalformedPresentNpcGraphIsRejectedWhileAbsentOriginalGraphIsSourceOnly()throws Exception{accept();String path="Config/Level/NPCDialogue/P10102/F10102001_G99/DialogueMain_F10102001_G99_N400002.json";assertFalse(Files.exists(root.resolve(path)));Files.createDirectories(root.resolve(path).getParent());var graph=originals.get(path).getAsJsonObject().deepCopy();graph.addProperty("ForeignGraph",true);Files.writeString(root.resolve(path),graph.toString());assertNotEquals(0,report(FIRST));assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(FIRST),FIRST,1));}
  @Tag("owned-extra") @ParameterizedTest @ValueSource(ints={MAIN,FIRST})
  void aMalformedUnobservedAcceptanceIsRejectedWithoutAnExceptionOrMutation(int id)throws Exception{
    accept();var quest=id==MAIN?daily.actor.getGameplay().getMainMissions().get(MAIN):daily.actor.getMissionManager().sub(FIRST);
    DailyMissionAcceptance46Test.set(quest,"observationKey","not-a-real-uuid");var before=daily.gson.toJson(daily.actor.getGameplay());int saves=daily.actor.saves;
    assertNotEquals(Retcode.SUCC,assertDoesNotThrow(()->daily.actor.getMissionManager().finishTalk(FIRST,"Mission_"+FIRST)));
    assertEquals(before,daily.gson.toJson(daily.actor.getGameplay()));assertEquals(saves,daily.actor.saves);assertEquals(QuestState.Phase.DOING,quest.getPhase());
  }
  @Tag("owned-extra") @ParameterizedTest @ValueSource(booleans={false,true})
  void aRejectedRetirementWritePreservesEveryExactOldObjectAndLiveScene(boolean expired){
    if(expired)choose(0);else settle(0);daily.clock(DailyMissionMessage46Test.NEXT);daily.actor.getDailyManager().ensureDay();
    var g=daily.actor.getGameplay();var oldMain=g.getMainMissions().get(MAIN);var oldSubs=new HashMap<>(g.getSubMissions());
    var oldActions=new HashMap<>(g.getStoryActions());var oldPayments=new HashMap<>(g.getMissionPayments());var oldPlans=new HashMap<>(g.getMissionPaymentPlans());
    var selections=g.getDailyMissionSelections();var state=g.getDailyMissionRecoverDelivery();var before=daily.gson.toJson(g);var durable=daily.actor.durableGameplay;
    var actors=new HashMap<>(daily.actor.getScene().getEntities());int saves=daily.actor.saves;daily.packets.clear();daily.actor.failSave=true;
    assertThrows(IllegalStateException.class,()->{if(expired)DailyMissionExpiry.expire(daily.actor,daily.now);else daily.actor.getMissionManager().accept(MAIN);});
    assertSame(oldMain,g.getMainMissions().get(MAIN));assertSame(selections,g.getDailyMissionSelections());assertSame(state,g.getDailyMissionRecoverDelivery());
    oldSubs.forEach((id,quest)->assertSame(quest,g.getSubMissions().get(id)));oldActions.forEach((id,cursor)->assertSame(cursor,g.getStoryActions().get(id)));
    oldPayments.forEach((id,cursor)->assertSame(cursor,g.getMissionPayments().get(id)));oldPlans.forEach((id,plan)->assertSame(plan,g.getMissionPaymentPlans().get(id)));
    assertEquals(before,daily.gson.toJson(g));assertEquals(durable,daily.actor.durableGameplay);assertEquals(saves,daily.actor.saves);assertTrue(daily.packets.isEmpty());
    assertEquals(actors,daily.actor.getScene().getEntities());daily.actor.failSave=false;
    if(expired)assertTrue(DailyMissionExpiry.expire(daily.actor,daily.now));else assertEquals(Retcode.SUCC,daily.actor.getMissionManager().accept(MAIN));
  }
  @Tag("owned-extra") @Test void anInitialDebitReservationFailureKeepsTheItemPendingAndNeverAutoReconciles(){
    choose(0);assertEquals(0,report(MENU));floor(PLANES[0],FLOORS[0]);var item=daily.actor.getInventory().getMaterialByItemId(ITEMS[0]);daily.actor.failSave=true;
    assertThrows(IllegalStateException.class,()->daily.actor.getMissionManager().submitItems(301101406,Map.of(ITEMS[0],1)));
    assertEquals(1,item.getCount());var cursor=daily.actor.getGameplay().getMissionPayments().get(301101406);assertNotNull(cursor);assertTrue(cursor.isPending());assertEquals(0,cursor.getNext());
    assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(301101406),301101406,1));daily.actor.failSave=false;
    assertEquals(Retcode.SYSTEM_BUSY,daily.actor.getMissionManager().submitItems(301101406,Map.of(ITEMS[0],1)));assertEquals(1,item.getCount());assertEquals(0,daily.actor.getScoin());
    daily.clock(DailyMissionMessage46Test.NEXT);daily.actor.getDailyManager().ensureDay();daily.actor.getMissionManager().refresh();
    assertSame(cursor,daily.actor.getGameplay().getMissionPayments().get(301101406));assertTrue(cursor.isPending());assertEquals(1,item.getCount());assertEquals(0,daily.actor.getScoin());
  }
  @Tag("owned-extra") @Test void theOriginalSourceOnlyNpcGraphMayBePresentMatchingWithoutBeingExecuted()throws Exception{
    accept();String path="Config/Level/NPCDialogue/P10102/F10102001_G99/DialogueMain_F10102001_G99_N400002.json";
    Files.createDirectories(root.resolve(path).getParent());Files.write(root.resolve(path),SourceHolder.CONFIG_BYTES.get(path));assertEquals(0,report(FIRST));assertEquals(0,daily.actor.getScoin());
  }
  @Tag("owned-extra") @Test void aDuplicatedWholeRequiredItemRowCannotAuthorizeTheFinishAction()throws Exception{
    first();var path=root.resolve("ExcelOutput/ItemConfig.json");var rows=JsonParser.parseString(Files.readString(path)).getAsJsonArray();JsonElement duplicate=null;
    for(var row:rows)if(row.getAsJsonObject().get("ID").getAsInt()==ITEMS[0])duplicate=row.deepCopy();assertNotNull(duplicate);rows.add(duplicate);Files.writeString(path,rows.toString());
    assertNotEquals(0,report(OPTIONS[0]));assertNull(daily.actor.getInventory().getMaterialByItemId(ITEMS[0]));
  }
  @Test void aGrantedItemAndOneActualDebitSurviveTwoIndependentPlayerDocumentReads()throws Exception{
    choose(0);assertEquals(0,report(MENU));var store=database.getDatastore();
    for(int read=0;read<2;read++){
      var original=daily.actor;var base=new Player();DailyMissionAcceptance46Test.set(base,"uid",original.getUid());DailyMissionAcceptance46Test.set(base,"gameplay",original.getGameplay());DailyMissionAcceptance46Test.set(base,"scoin",original.getScoin());store.save(base);
      var restored=store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",original.getUid())).first();assertNotNull(restored);assertNotSame(base,restored);
      daily.actor=daily.fresh();DailyMissionAcceptance46Test.set(daily.actor,"gameplay",restored.getGameplay());DailyMissionAcceptance46Test.set(daily.actor,"scoin",restored.getScoin());daily.clock(DailyMissionMessage46Test.TODAY);
      for(var persisted:store.find(GameItem.class).filter(dev.morphia.query.filters.Filters.eq("ownerUid",daily.actor.getUid())).iterator().toList()){persisted.setExcel(GameData.getItemExcelMap().get(persisted.getItemId()));persisted.setOwner(daily.actor);daily.actor.getInventory().getItems().put(persisted.getInternalUid(),persisted);daily.actor.getInventory().getTabByItemType(persisted.getExcel().getItemMainType()).onAddItem(persisted);}
      floor(PLANES[0],FLOORS[0]);
      if(read==0){assertEquals(1,daily.actor.getInventory().getMaterialByItemId(ITEMS[0]).getCount());assertEquals(0,consume(301101406,Map.of(ITEMS[0],1)));assertNull(daily.actor.getInventory().getMaterialByItemId(ITEMS[0]));}
      else{assertNull(daily.actor.getInventory().getMaterialByItemId(ITEMS[0]));assertNotEquals(0,consume(301101406,Map.of(ITEMS[0],1)));assertEquals(0,report(301101409));assertEquals(5000,daily.actor.getScoin());}
    }
  }
}
