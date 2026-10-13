package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.util.Position;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Original select-one-item dailies through existing 1261/1205 APIs and real Inventory.
 * Prerequisite histories, physical position, one eligible catalog, clock and Player.save are
 * fixtures. A real one-item stack reaches zero, so GameItem.save has no operator DB write.
 * Source-only NPC client graphs/Act/CustomString/UI are not installed or executed. */
class DailyMissionSelectedItem46Test {
  static final int[] MAINS={3011605,3011608};
  static final Map<Integer,List<Integer>> ITEMS=Map.of(3011605,List.of(401004,401012),3011608,List.of(401007,401009,401011,402001,402005));
  static final class SourceHolder {
    static final JsonObject VALUE=load();
    static JsonObject load(){
      try{
        JsonObject fixture;
        try(var stream=DailyMissionSelectedItem46Test.class.getResourceAsStream("/fixtures/daily-selected-item-3011605-8-4.6.json")){
          fixture=JsonParser.parseString(new String(Objects.requireNonNull(stream).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b",fixture.get("sourceCommit").getAsString());
        assertEquals(36,fixture.getAsJsonObject("sourceFiles").size());assertEquals(131,fixture.getAsJsonArray("records").size());
        for(var e:fixture.getAsJsonObject("sourceFiles").entrySet()){
          byte[] raw=bytes(e.getValue().getAsJsonObject());
          assertEquals(e.getValue().getAsJsonObject().get("sourceBlobSHA256").getAsString(),sha(raw),e.getKey());
        }
        return fixture;
      }catch(Exception failure){throw new ExceptionInInitializerError(failure);}
    }
  }
  static byte[] bytes(JsonObject metadata)throws Exception{
    if(metadata.has("sourceBlobBase64"))return Base64.getDecoder().decode(metadata.get("sourceBlobBase64").getAsString());
    try(var inflater=new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(metadata.get("sourceBlobDeflateBase64").getAsString())))){return inflater.readAllBytes();}
  }
  static String sha(byte[] raw)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw));}
  final DailyMissionMessage46Test daily=new DailyMissionMessage46Test();
  @TempDir Path root;
  JsonObject source;
  final Map<String,JsonObject> originals=new HashMap<>();
  final Map<Integer,Integer> entranceIds=new HashMap<>();
  String oldResourceDir;
  @BeforeEach void setup()throws Exception{
    daily.temp=root;daily.setup();source=SourceHolder.VALUE;
    oldResourceDir=LunarCore.getConfig().getResourceDir();resourceDir(root.toString());
    for(var e:source.getAsJsonObject("sourceFiles").entrySet()){
      if(!e.getKey().startsWith("Config/"))continue;
      var metadata=e.getValue().getAsJsonObject();byte[] raw=bytes(metadata);
      originals.put(e.getKey(),JsonParser.parseString(new String(raw,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());
      if(metadata.get("installed").getAsBoolean()){
        var target=root.resolve(e.getKey());Files.createDirectories(target.getParent());Files.write(target,raw);
      }
    }
    var excelRows=new HashMap<String,JsonArray>();
    for(var raw:source.getAsJsonArray("records")){
      var e=raw.getAsJsonObject();var row=e.getAsJsonObject("row");
      if(e.get("source").getAsString().startsWith("ExcelOutput/"))excelRows.computeIfAbsent(e.get("source").getAsString(),ignored->new JsonArray()).add(row);
      switch(e.get("kind").getAsString()){
        case "main" -> GameData.getMainMissionExcelMap().add(daily.gson.fromJson(row,MainMissionExcel.class));
        case "daily" -> daily.daily.put(row.get("ID").getAsInt(),row);
        case "quest" -> GameData.getQuestExcelMap().add(daily.gson.fromJson(row,QuestExcel.class));
        case "rule" -> GameData.getFinishWayExcelMap().add(daily.gson.fromJson(row,FinishWayExcel.class));
        case "reward" -> {var r=daily.gson.fromJson(row,RewardExcel.class);r.onLoad();GameData.getRewardExcelMap().add(r);}
        case "item" -> GameData.getItemExcelMap().add(daily.gson.fromJson(row,ItemExcel.class));
        case "npc" -> GameData.getNpcExcelMap().add(daily.gson.fromJson(row,NpcExcel.class));
        case "plane" -> GameData.getMazePlaneExcelMap().add(daily.gson.fromJson(row,MazePlaneExcel.class));
        case "entrance" -> {
          var entrance=daily.gson.fromJson(row,MapEntranceExcel.class);GameData.getMapEntranceExcelMap().add(entrance);
          entranceIds.merge(entrance.getPlaneID()==20122?3011605:3011608,entrance.getId(),Math::min);
        }
        default -> {}
      }
    }
    // Complete selected original rows provide the isolated source guard; the original full
    // table bytes remain in the immutable source witness, never overwrite operator files.
    for(var e:excelRows.entrySet())Files.writeString(root.resolve(e.getKey()),e.getValue().toString());
    for(int mid:MAINS){
      var full=originals.get("Config/Level/Mission/"+mid+"/MissionInfo_"+mid+".json").deepCopy();
      full.add("SubMissionList",originals.get("Config/Level/Mission/"+mid+"/MissionInfo_"+mid+".partial.json").get("SubMissionList"));
      var graph=daily.gson.fromJson(full,MissionInfo.class);GameData.getMissionInfos().put(mid,graph);
      for(var sub:graph.getSubMissionList()){
        GameplayDefinitions.subs.put(sub.getId(),sub);var script=originals.get(sub.getMissionJsonPath());assertNotNull(script);
        var task=daily.gson.fromJson(script,MissionTaskInfo.class);task.retainSourceDefinition(script);sub.setTask(task);
      }
      int plane=plane(mid),floorId=floorId(mid),groupId=group(mid);
      var group=daily.gson.fromJson(originals.get(groupPath(mid)),GroupInfo.class);group.setId(groupId);
      var floor=daily.gson.fromJson(originals.get("Config/LevelOutput/RuntimeFloor/P"+plane+"_F"+floorId+".json"),FloorInfo.class);
      int index=source.getAsJsonObject("groupIndices").get(Integer.toString(mid)).getAsInt();
      floor.registerGroup(index,group);floor.onLoad();GameData.getFloorInfos().put("P"+plane+"_F"+floorId,floor);
    }
    daily.actor.getGameplay().getMainMissions().put(1011501,DailyMissionAcceptance46Test.closed(1011501));
    assertTrue(daily.actor.getGameplay().getMissionPayments().isEmpty());
    assertTrue(daily.actor.getGameplay().getMissionPaymentPlans().isEmpty());
    daily.catalog(3011605);
  }
  static void resourceDir(String value)throws Exception{
    var field=LunarCore.getConfig().getClass().getDeclaredField("resourceDir");field.setAccessible(true);field.set(LunarCore.getConfig(),value);
  }
  @AfterEach void cleanup()throws Exception{
    try{daily.cleanup();}finally{if(oldResourceDir!=null)resourceDir(oldResourceDir);}
  }
  static int plane(int mid){return mid==3011605?20122:20132;}
  static int floorId(int mid){return plane(mid)*1000+1;}
  static int group(int mid){return mid==3011605?102:74;}
  static int select(int mid){return mid*100+1;}
  static int talk(int mid){return mid*100+2;}
  static String groupPath(int mid){return "Config/LevelOutput/SharedRuntimeGroup/Groups_P"+plane(mid)+"_F"+floorId(mid)+"/LevelGroup_P"+plane(mid)+"_F"+floorId(mid)+"_G"+group(mid)+".json";}
  void floor(int mid)throws Exception{
    daily.catalog(mid);
    assertTrue(daily.actor.loadScene(plane(mid),floorId(mid),entranceIds.get(mid),new Position(),new Position(),false));
    assertEquals(0,daily.actor.getScene().getDimensionId());assertEquals(0,daily.actor.getScene().getStoryLineId());
  }
  void accepted(int mid)throws Exception{
    floor(mid);assertEquals(Retcode.SUCC,daily.actor.getMissionManager().accept(mid));
    assertTrue(MissionStateRecords.mainDoing(daily.actor,mid));
    assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(select(mid)),select(mid),1));
    assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(talk(mid)),talk(mid),1));
    var npcs=daily.actor.getScene().getEntitiesByGroup(EntityNpc.class,group(mid));
    assertEquals(1,npcs.size());assertEquals(400001,npcs.getFirst().getInstId());
    assertEquals(mid==3011605?11103:2108,npcs.getFirst().getNpcId());
  }
  GameItem stock(int id){
    var item=new GameItem(id,1);item.setOwner(daily.actor);
    var inventory=daily.actor.getInventory();inventory.getItems().put(item.getInternalUid(),item);
    inventory.getTabByItemType(item.getExcel().getItemMainType()).onAddItem(item);
    assertSame(item,inventory.getMaterialByItemId(id));return item;
  }
  int submit(int mid,Map<Integer,Integer> choice){
    var protocol=daily.session().getProtocol();assertEquals(1261,protocol.clientCommand("FinishCosumeItemMissionCsReq"));
    var values=new HashMap<String,Object>();values.put("sub_mission_id",select(mid));
    if(choice!=null){
      var list=DynamicMessage.newBuilder(protocol.clientSchema().message("ItemList"));
      for(var entry:choice.entrySet()){
        var item=DynamicMessage.newBuilder(protocol.clientSchema().message("Item"));
        item.setField(item.getDescriptorForType().findFieldByName("item_id"),entry.getKey());
        item.setField(item.getDescriptorForType().findFieldByName("num"),entry.getValue());
        list.addRepeatedField(list.getDescriptorForType().findFieldByName("item_list"),item.build());
      }
      values.put("item_list",list.build());
    }
    var response=daily.query("FinishCosumeItemMissionCsReq",values);
    assertEquals(select(mid),daily.field(response,"sub_mission_id"));return (int)daily.field(response,"retcode");
  }
  int report(int mid){return (int)daily.field(daily.query("FinishTalkMissionCsReq",Map.of("sub_mission_id",talk(mid),"talk_str","Mission_"+talk(mid))),"retcode");}
  void paid(int mid,int item)throws Exception{
    accepted(mid);stock(item);assertEquals(Retcode.SUCC.getVal(),submit(mid,Map.of(item,1)));
    assertTrue(MissionStateRecords.subCompleted(daily.actor,select(mid)));assertEquals(0,daily.actor.getScoin());
  }
  void settled(int mid)throws Exception{
    paid(mid,ITEMS.get(mid).getFirst());assertEquals(Retcode.SUCC.getVal(),report(mid));
    assertEquals(Retcode.SUCC,daily.actor.getDailyManager().claimQuest(2100003).retcode());
    assertEquals(5000,daily.actor.getScoin());assertEquals(200,daily.actor.getGameplay().getDailyPoints());
  }

  @Test void wholeOriginalSourceAndItsDistinctSevenCostsStayUnchanged(){
    assertEquals(36,source.getAsJsonObject("sourceFiles").size());assertEquals(131,source.getAsJsonArray("records").size());
    for(int mid:MAINS){
      assertEquals("Manual",GameData.getMainMissionExcelMap().get(mid).getTakeParam().getFirst().Type());
      assertEquals(List.of(mid),Arrays.asList(GameplayDefinitions.subs.get(select(mid)).getTakeParamIntList()));
      assertEquals(ITEMS.get(mid),GameplayDefinitions.subs.get(select(mid)).getParamItemList().stream().map(MissionInfo.MissionItemCost::ItemID).toList());
    }
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void originalTwoAutoStartsBecomeQualifiedForTheAuthoredRepeatableDaily(int mid)throws Exception{
    daily.catalog(mid);assertTrue(DailyMissionRepeats.supportedGraph(mid),"Original use-selected-item graph remains excluded from actual daily qualification");
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void originalUnlockAndCatalogAllowActualAutomaticAssignmentWithoutChangingManualHeader(int mid)throws Exception{
    floor(mid);daily.actor.getMissionManager().refresh();
    assertTrue(MissionStateRecords.mainDoing(daily.actor,mid),"Real refresh cannot assign this original source daily");
    assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(select(mid)),select(mid),1));
    assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(talk(mid)),talk(mid),1));
    assertEquals(0,daily.actor.getScoin());
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void nativeTalkCannotBypassTheOriginalRequiredPaidSubmission(int mid)throws Exception{
    accepted(mid);stock(ITEMS.get(mid).getFirst());
    assertEquals(Retcode.MISSION_FINISH_WAY_NOT_MATCH.getVal(),report(mid),"Mission script requires the actual selected-item target to be Finish");
    assertTrue(MissionStateRecords.mainDoing(daily.actor,mid));assertEquals(0,daily.actor.getScoin());
    assertTrue(daily.actor.getGameplay().getMissionPayments().isEmpty());
  }
  @ParameterizedTest @CsvSource({"3011605,401004","3011605,401012","3011608,401007","3011608,401009","3011608,401011","3011608,402001","3011608,402005"})
  void allSevenOriginalChoicesUseActualInventoryAnd1261ThenAllowTheOriginalTalk(int mid,int id)throws Exception{
    accepted(mid);var stacks=new HashMap<Integer,GameItem>();for(int item:ITEMS.get(mid))stacks.put(item,stock(item));
    assertEquals(Retcode.SUCC.getVal(),submit(mid,Map.of(id,1)));
    assertEquals(0,stacks.get(id).getCount());assertNull(daily.actor.getInventory().getMaterialByItemId(id));
    assertEquals(ITEMS.get(mid).size()-1,daily.actor.getInventory().getItems().size());
    for(int other:ITEMS.get(mid))if(other!=id)assertEquals(1,stacks.get(other).getCount());
    assertTrue(MissionStateRecords.subCompleted(daily.actor,select(mid)));assertEquals(0,daily.actor.getScoin());
    assertEquals(Map.of(id,1),daily.actor.getGameplay().getMissionPaymentPlans().get(select(mid)).getCosts());
    assertEquals(1,daily.actor.getGameplay().getMissionPayments().get(select(mid)).getNext());
    assertEquals(Retcode.SUCC.getVal(),report(mid));assertEquals(5000,daily.actor.getScoin());
    assertEquals(QuestState.Phase.CLOSED,daily.actor.getGameplay().getMainMissions().get(mid).getPhase());
    assertEquals(Retcode.SUCC,daily.actor.getDailyManager().claimQuest(2100003).retcode());assertEquals(200,daily.actor.getGameplay().getDailyPoints());
    assertNotEquals(Retcode.SUCC.getVal(),submit(mid,Map.of(id,1)));assertNotEquals(Retcode.SUCC.getVal(),report(mid));
    assertEquals(5000,daily.actor.getScoin());assertEquals(200,daily.actor.getGameplay().getDailyPoints());
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void missingChoiceAndMultipleChoicesCannotPickForTheClientOrDebitAnyRealStack(int mid)throws Exception{
    accepted(mid);var items=ITEMS.get(mid);var first=stock(items.get(0));var second=stock(items.get(1));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(),submit(mid,null));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(),submit(mid,Map.of(items.get(0),1,items.get(1),1)));
    assertEquals(1,first.getCount());assertEquals(1,second.getCount());assertTrue(daily.actor.getGameplay().getMissionPayments().isEmpty());
    assertEquals(0,daily.actor.getScoin());
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void anUnpaidCompletedGoalObjectCannotStandInForAnActualSelectedItemDebit(int mid)throws Exception{
    accepted(mid);var target=daily.actor.getMissionManager().sub(select(mid));
    target.advance(1,1,daily.now.getEpochSecond());
    assertTrue(daily.actor.getGameplay().getMissionPayments().isEmpty());
    assertTrue(daily.actor.getGameplay().getMissionPaymentPlans().isEmpty());
    assertNotEquals(Retcode.SUCC.getVal(),report(mid));assertEquals(0,daily.actor.getScoin());
    assertTrue(MissionStateRecords.mainDoing(daily.actor,mid));
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void aForeignNpcInstanceCannotAuthorizeTheOriginalPaidTalk(int mid)throws Exception{
    paid(mid,ITEMS.get(mid).getFirst());
    var npc=daily.actor.getScene().getEntitiesByGroup(EntityNpc.class,group(mid)).getFirst();
    npc.setInstId(400002);
    assertNotEquals(Retcode.SUCC.getVal(),report(mid));assertEquals(0,daily.actor.getScoin());
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void aChangedCompleteOriginalScriptCannotAuthorizeThePaidTalk(int mid)throws Exception{
    paid(mid,ITEMS.get(mid).getFirst());
    var definition=GameplayDefinitions.subs.get(talk(mid));
    var altered=originals.get(definition.getMissionJsonPath()).deepCopy();
    altered.getAsJsonArray("OnStartSequece").get(0).getAsJsonObject().getAsJsonArray("TaskList").add(JsonParser.parseString("{\"$type\":\"RPG.GameCore.Unproven\"}"));
    var task=daily.gson.fromJson(altered,MissionTaskInfo.class);task.retainSourceDefinition(altered);definition.setTask(task);
    Files.writeString(root.resolve(definition.getMissionJsonPath()),altered.toString());
    assertNotEquals(Retcode.SUCC.getVal(),report(mid));assertEquals(0,daily.actor.getScoin());
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void aForeignAutoOwnershipParameterCannotConsumeAnActuallyOwnedItem(int mid)throws Exception{
    accepted(mid);var item=stock(ITEMS.get(mid).getFirst());
    GameplayDefinitions.subs.get(select(mid)).getTakeParamIntList()[0]=mid+1;
    assertNotEquals(Retcode.SUCC.getVal(),submit(mid,Map.of(item.getItemId(),1)));
    assertEquals(1,item.getCount());assertTrue(daily.actor.getGameplay().getMissionPayments().isEmpty());
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void aSettledDailyReopensWithFreshGoalsAndDoesNotBorrowItsOldPayment(int mid)throws Exception{
    settled(mid);var oldMain=daily.actor.getGameplay().getMainMissions().get(mid);
    var oldSub=daily.actor.getMissionManager().sub(select(mid));
    var oldPlan=daily.actor.getGameplay().getMissionPaymentPlans().get(select(mid));
    var oldCursor=daily.actor.getGameplay().getMissionPayments().get(select(mid));
    daily.clock(DailyMissionMessage46Test.NEXT);daily.actor.getDailyManager().ensureDay();daily.actor.getMissionManager().refresh();
    assertTrue(MissionStateRecords.mainDoing(daily.actor,mid),"Settled original commission cannot be reaccepted on the next reset day");
    assertNotSame(oldMain,daily.actor.getGameplay().getMainMissions().get(mid));assertNotSame(oldSub,daily.actor.getMissionManager().sub(select(mid)));
    assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(select(mid)),select(mid),1));
    assertFalse(daily.actor.getGameplay().getMissionPayments().containsValue(oldCursor));assertFalse(daily.actor.getGameplay().getMissionPaymentPlans().containsValue(oldPlan));
    assertNotEquals(Retcode.SUCC.getVal(),report(mid));assertEquals(5000,daily.actor.getScoin());
    var newItem=stock(ITEMS.get(mid).getLast());assertEquals(Retcode.SUCC.getVal(),submit(mid,Map.of(newItem.getItemId(),1)));
    assertEquals(0,newItem.getCount());assertEquals(Retcode.SUCC.getVal(),report(mid));assertEquals(10000,daily.actor.getScoin());
    assertEquals(Retcode.SUCC,daily.actor.getDailyManager().claimQuest(2100003).retcode());assertEquals(200,daily.actor.getGameplay().getDailyPoints());
    var snapshot=daily.actor.getGameplay().getDailyMissionPeriods().getArchives().get(mid+":"+DailyManager.day(DailyMissionMessage46Test.TODAY));
    assertNotNull(snapshot);assertSame(oldMain,snapshot.getMain());assertSame(oldSub,snapshot.getSubs().get(select(mid)));
    assertEquals(QuestState.Phase.CLOSED,oldMain.getPhase());assertEquals(QuestState.Phase.FINISHED,oldSub.getPhase());
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void aPaidButUnreportedDailyExpiresWithoutRefundAndNeedsAFreshNextDayChoice(int mid)throws Exception{
    paid(mid,ITEMS.get(mid).getFirst());var oldMain=daily.actor.getGameplay().getMainMissions().get(mid);
    var oldSub=daily.actor.getMissionManager().sub(select(mid));
    daily.clock(DailyMissionMessage46Test.NEXT);daily.actor.getDailyManager().ensureDay();daily.actor.getMissionManager().refresh();
    assertNotSame(oldMain,daily.actor.getGameplay().getMainMissions().get(mid),"An unreported paid target otherwise stays active forever");
    assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(select(mid)),select(mid),1));
    assertNull(daily.actor.getInventory().getMaterialByItemId(ITEMS.get(mid).getFirst()));assertEquals(0,daily.actor.getScoin());
    var expired=daily.actor.getGameplay().getDailyMissionPeriods().getExpired().get(mid+":"+DailyManager.day(DailyMissionMessage46Test.TODAY));
    assertNotNull(expired);assertSame(oldMain,expired.getMain());assertSame(oldSub,expired.getSubs().get(select(mid)));
    assertFalse(daily.actor.getGameplay().getMissionPayments().containsKey(select(mid)));
    assertNotEquals(Retcode.SUCC.getVal(),report(mid));
    var item=stock(ITEMS.get(mid).getLast());assertEquals(Retcode.SUCC.getVal(),submit(mid,Map.of(item.getItemId(),1)));
    assertEquals(Retcode.SUCC.getVal(),report(mid));assertEquals(5000,daily.actor.getScoin());
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void anUnselectedExpiredDailyAlsoRestartsItsRealSourceView(int mid)throws Exception{
    accepted(mid);var oldMain=daily.actor.getGameplay().getMainMissions().get(mid);var oldSub=daily.actor.getMissionManager().sub(select(mid));
    daily.clock(DailyMissionMessage46Test.NEXT);daily.actor.getDailyManager().ensureDay();daily.actor.getMissionManager().refresh();
    assertNotSame(oldMain,daily.actor.getGameplay().getMainMissions().get(mid));assertNotSame(oldSub,daily.actor.getMissionManager().sub(select(mid)));
    assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(select(mid)),select(mid),1));
    assertTrue(daily.actor.getGameplay().getMissionPayments().isEmpty());assertTrue(daily.actor.getGameplay().getMissionPaymentPlans().isEmpty());
    assertEquals(0,daily.actor.getScoin());
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void anEarlierClockCannotDebitTheCurrentAcceptedDaily(int mid)throws Exception{
    accepted(mid);var item=stock(ITEMS.get(mid).getFirst());daily.clock(DailyMissionMessage46Test.TODAY.minusSeconds(86400));
    assertNotEquals(Retcode.SUCC.getVal(),submit(mid,Map.of(item.getItemId(),1)));assertEquals(1,item.getCount());
    assertTrue(daily.actor.getGameplay().getMissionPayments().isEmpty());assertEquals(0,daily.actor.getScoin());
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void aMissingActualItemAndForeignOwnedStackCannotDebitOrFinish(int mid)throws Exception{
    accepted(mid);int id=ITEMS.get(mid).getFirst();
    assertEquals(Retcode.ITEM_NOT_ENOUGH.getVal(),submit(mid,Map.of(id,1)));
    var item=stock(id);DailyMissionAcceptance46Test.set(item,"ownerUid",daily.actor.getUid()+1);
    assertEquals(Retcode.ITEM_NOT_ENOUGH.getVal(),submit(mid,Map.of(id,1)));assertEquals(1,item.getCount());
    assertTrue(daily.actor.getGameplay().getMissionPayments().isEmpty());assertEquals(0,daily.actor.getScoin());
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void theOriginalUnlockMainMissionMustReallyBeCompleteBeforeManualAccept(int mid)throws Exception{
    daily.catalog(mid);daily.actor.getGameplay().getMainMissions().remove(1011501);
    assertEquals(Retcode.MISSION_NOT_DOING,daily.actor.getMissionManager().accept(mid));
    assertNull(daily.actor.getGameplay().getMainMissions().get(mid));assertTrue(daily.actor.getGameplay().getDailyMissionSelections().getDays().isEmpty());
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void theRealSubmissionAtAnotherOriginalFloorIsRejectedWithoutConsumption(int mid)throws Exception{
    accepted(mid);var item=stock(ITEMS.get(mid).getFirst());daily.floor();
    assertEquals(Retcode.MISSION_SCENE_NOT_MATCH.getVal(),submit(mid,Map.of(item.getItemId(),1)));assertEquals(1,item.getCount());
    assertTrue(daily.actor.getGameplay().getMissionPayments().isEmpty());
  }
  @Test void theSameOriginalDailyGroupCannotConsumeTwoSelectionsOnOneDay()throws Exception{
    daily.catalog(MAINS);floor(3011605);daily.catalog(MAINS);assertEquals(Retcode.SUCC,daily.actor.getMissionManager().accept(3011605));
    assertEquals(Retcode.MISSION_NOT_DOING,daily.actor.getMissionManager().accept(3011608));
    assertNull(daily.actor.getGameplay().getMainMissions().get(3011608));
    assertEquals(1,daily.actor.getGameplay().getDailyMissionSelections().getDays().get(Long.toString(DailyManager.day(daily.now))).size());
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void aRejectedPlayerWriteKeepsTheOriginalTalkAcceptanceDoingAndPermitsAnHonestRetry(int mid)throws Exception{
    paid(mid,ITEMS.get(mid).getFirst());var main=daily.actor.getGameplay().getMainMissions().get(mid);
    var oldGoal=daily.actor.getMissionManager().sub(talk(mid));var before=daily.gson.toJson(daily.actor.getGameplay());
    var durable=daily.actor.durableGameplay;int saves=daily.actor.saves;daily.packets.clear();daily.actor.failSave=true;
    assertThrows(IllegalStateException.class,()->daily.actor.getMissionManager().finishTalk(talk(mid),"Mission_"+talk(mid)));
    assertSame(oldGoal,daily.actor.getMissionManager().sub(talk(mid)));assertEquals(QuestState.Phase.DOING,oldGoal.getPhase());
    assertEquals(0,oldGoal.getProgress());assertEquals(0,oldGoal.getFinishTime());assertSame(main,daily.actor.getGameplay().getMainMissions().get(mid));
    assertEquals(before,daily.gson.toJson(daily.actor.getGameplay()));assertEquals(durable,daily.actor.durableGameplay);assertEquals(saves,daily.actor.saves);
    assertEquals(0,daily.actor.getScoin());assertEquals(0,daily.actor.getGameplay().getDailyPoints());assertTrue(daily.packets.isEmpty());
    daily.actor.failSave=false;assertEquals(Retcode.SUCC.getVal(),report(mid));assertEquals(5000,daily.actor.getScoin());
    assertNotEquals(Retcode.SUCC.getVal(),report(mid));assertEquals(5000,daily.actor.getScoin());
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void aMissingCompletePartialFileCannotBeBypassedWithManualAcceptance(int mid)throws Exception{
    daily.catalog(mid);Files.delete(root.resolve("Config/Level/Mission/"+mid+"/MissionInfo_"+mid+".partial.json"));
    assertNotEquals(Retcode.SUCC,daily.actor.getMissionManager().accept(mid));
    assertNull(daily.actor.getGameplay().getMainMissions().get(mid));assertTrue(daily.actor.getGameplay().getDailyMissionSelections().getDays().isEmpty());
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void aNextDayReopenWriteFailureRestoresAllOriginalQuestPaymentAndSelectionObjects(int mid)throws Exception{
    settled(mid);daily.clock(DailyMissionMessage46Test.NEXT);daily.actor.getDailyManager().ensureDay();
    var g=daily.actor.getGameplay();var oldMain=g.getMainMissions().get(mid);var oldGoal=g.getSubMissions().get(select(mid));
    var oldTail=g.getSubMissions().get(talk(mid));var oldPayment=g.getMissionPayments().get(select(mid));var oldPlan=g.getMissionPaymentPlans().get(select(mid));
    var selection=g.getDailyMissionSelections();var before=daily.gson.toJson(g);var durable=daily.actor.durableGameplay;int saves=daily.actor.saves;
    daily.packets.clear();daily.actor.failSave=true;
    assertThrows(IllegalStateException.class,()->daily.actor.getMissionManager().accept(mid));
    assertSame(oldMain,g.getMainMissions().get(mid));assertSame(oldGoal,g.getSubMissions().get(select(mid)));assertSame(oldTail,g.getSubMissions().get(talk(mid)));
    assertSame(oldPayment,g.getMissionPayments().get(select(mid)));assertSame(oldPlan,g.getMissionPaymentPlans().get(select(mid)));assertSame(selection,g.getDailyMissionSelections());
    assertEquals(before,daily.gson.toJson(g));assertEquals(durable,daily.actor.durableGameplay);assertEquals(saves,daily.actor.saves);assertTrue(daily.packets.isEmpty());
    assertEquals(5000,daily.actor.getScoin());daily.actor.failSave=false;assertEquals(Retcode.SUCC,daily.actor.getMissionManager().accept(mid));
    assertTrue(MissionStateRecords.mainDoing(daily.actor,mid));assertNotSame(oldMain,g.getMainMissions().get(mid));assertNotEquals(0,report(mid));
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void anExpiredPaidViewWriteFailureDoesNotClearItsDebitOrPublishNpcRemoval(int mid)throws Exception{
    paid(mid,ITEMS.get(mid).getFirst());daily.clock(DailyMissionMessage46Test.NEXT);daily.actor.getDailyManager().ensureDay();
    var g=daily.actor.getGameplay();var oldMain=g.getMainMissions().get(mid);var oldGoal=g.getSubMissions().get(select(mid));
    var oldPayment=g.getMissionPayments().get(select(mid));var oldPlan=g.getMissionPaymentPlans().get(select(mid));
    var selection=g.getDailyMissionSelections();var before=daily.gson.toJson(g);var durable=daily.actor.durableGameplay;int saves=daily.actor.saves;
    var npc=daily.actor.getScene().getEntitiesByGroup(EntityNpc.class,group(mid)).getFirst();daily.packets.clear();daily.actor.failSave=true;
    assertThrows(IllegalStateException.class,()->daily.actor.getMissionManager().refresh());
    assertSame(oldMain,g.getMainMissions().get(mid));assertSame(oldGoal,g.getSubMissions().get(select(mid)));
    assertSame(oldPayment,g.getMissionPayments().get(select(mid)));assertSame(oldPlan,g.getMissionPaymentPlans().get(select(mid)));assertSame(selection,g.getDailyMissionSelections());
    assertEquals(before,daily.gson.toJson(g));assertEquals(durable,daily.actor.durableGameplay);assertEquals(saves,daily.actor.saves);assertTrue(daily.packets.isEmpty());
    assertSame(npc,daily.actor.getScene().getEntitiesByGroup(EntityNpc.class,group(mid)).getFirst());assertEquals(0,daily.actor.getScoin());
    assertNull(daily.actor.getInventory().getMaterialByItemId(ITEMS.get(mid).getFirst()));daily.actor.failSave=false;daily.actor.getMissionManager().refresh();
    assertNotSame(oldMain,g.getMainMissions().get(mid));assertTrue(MissionStateRecords.doing(g.getSubMissions().get(select(mid)),select(mid),1));assertNotEquals(0,report(mid));
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void threeActualMorphiaPlayerReadsPreserveTheChoiceAndItsRetiredPaymentAcrossBothDays(int mid)throws Exception{
    paid(mid,ITEMS.get(mid).getFirst());
    var server=new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());var address=server.bind();
    try(var client=com.mongodb.client.MongoClients.create("mongodb://"+address.getHostString()+":"+address.getPort())){
      var store=dev.morphia.Morphia.createDatastore(client,"selected_item_daily");store.getMapper().map(emu.lunarcore.game.player.Player.class,QuestState.class);
      for(int iteration=0;iteration<2;iteration++){
        var base=new emu.lunarcore.game.player.Player();DailyMissionAcceptance46Test.set(base,"uid",daily.actor.getUid());
        DailyMissionAcceptance46Test.set(base,"gameplay",daily.actor.getGameplay());DailyMissionAcceptance46Test.set(base,"scoin",daily.actor.getScoin());store.save(base);
        var restored=store.find(emu.lunarcore.game.player.Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",daily.actor.getUid())).first();assertNotNull(restored);
        daily.actor=daily.fresh();DailyMissionAcceptance46Test.set(daily.actor,"gameplay",restored.getGameplay());DailyMissionAcceptance46Test.set(daily.actor,"scoin",restored.getScoin());
        daily.clock(DailyMissionMessage46Test.TODAY);floor(mid);
        assertTrue(MissionStateRecords.subCompleted(daily.actor,select(mid)));
        assertEquals(Map.of(ITEMS.get(mid).getFirst(),1),daily.actor.getGameplay().getMissionPaymentPlans().get(select(mid)).getCosts());
        if(iteration==0){assertEquals(0,daily.actor.getScoin());assertEquals(Retcode.SUCC.getVal(),report(mid));assertEquals(5000,daily.actor.getScoin());}
        else{assertNotEquals(0,report(mid));assertEquals(5000,daily.actor.getScoin());}
      }
      daily.clock(DailyMissionMessage46Test.NEXT);daily.actor.getDailyManager().ensureDay();daily.actor.getMissionManager().refresh();
      assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(select(mid)),select(mid),1));assertNotEquals(0,report(mid));
      var next=stock(ITEMS.get(mid).getLast());assertEquals(Retcode.SUCC.getVal(),submit(mid,Map.of(next.getItemId(),1)));assertEquals(0,next.getCount());
      assertEquals(Retcode.SUCC.getVal(),report(mid));assertEquals(10000,daily.actor.getScoin());
      var base=new emu.lunarcore.game.player.Player();DailyMissionAcceptance46Test.set(base,"uid",daily.actor.getUid());DailyMissionAcceptance46Test.set(base,"gameplay",daily.actor.getGameplay());
      DailyMissionAcceptance46Test.set(base,"scoin",daily.actor.getScoin());store.save(base);
      var restored=store.find(emu.lunarcore.game.player.Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",daily.actor.getUid())).first();assertNotNull(restored);
      daily.actor=daily.fresh();DailyMissionAcceptance46Test.set(daily.actor,"gameplay",restored.getGameplay());DailyMissionAcceptance46Test.set(daily.actor,"scoin",restored.getScoin());
      daily.clock(DailyMissionMessage46Test.NEXT);floor(mid);assertTrue(DailyMissionSelectedItemRules.historyValid(daily.actor,daily.now));
      assertNotEquals(0,report(mid));assertEquals(10000,daily.actor.getScoin());
      var state=daily.gson.toJsonTree(daily.actor.getGameplay()).getAsJsonObject().getAsJsonObject("dailyMissionSelectedItems");assertNotNull(state);
      assertEquals(2,state.getAsJsonObject("records").size());
      var prior=state.getAsJsonObject("records").getAsJsonObject(mid+":"+DailyManager.day(DailyMissionMessage46Test.TODAY));
      assertTrue(prior.get("retiredAt").getAsLong()>0);assertNotNull(prior.get("retiredPayment"));assertNotNull(prior.get("retiredPlan"));
    }finally{server.shutdownNow();}
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void aMissingTailAcceptanceReturnsARejectionWithoutThrowingOrConsuming(int mid)throws Exception{
    accepted(mid);var item=stock(ITEMS.get(mid).getFirst());daily.actor.getGameplay().getSubMissions().remove(talk(mid));
    var before=daily.gson.toJson(daily.actor.getGameplay());int saves=daily.actor.saves;
    var result=assertDoesNotThrow(()->daily.actor.getMissionManager().submitItems(select(mid),Map.of(item.getItemId(),1)));
    assertNotEquals(Retcode.SUCC,result);assertEquals(1,item.getCount());assertEquals(before,daily.gson.toJson(daily.actor.getGameplay()));assertEquals(saves,daily.actor.saves);
  }
  @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void aRejectedInitialPaymentReservationKeepsStockAndRequiresExplicitReconciliation(int mid)throws Exception{
    accepted(mid);var item=stock(ITEMS.get(mid).getFirst());var durable=daily.actor.durableGameplay;daily.actor.failSave=true;
    assertThrows(IllegalStateException.class,()->daily.actor.getMissionManager().submitItems(select(mid),Map.of(item.getItemId(),1)));
    assertEquals(1,item.getCount());assertSame(item,daily.actor.getInventory().getMaterialByItemId(item.getItemId()));assertEquals(durable,daily.actor.durableGameplay);
    var payment=daily.actor.getGameplay().getMissionPayments().get(select(mid));assertNotNull(payment);assertTrue(payment.isPending());assertEquals(0,payment.getNext());
    daily.actor.failSave=false;assertEquals(Retcode.SYSTEM_BUSY,daily.actor.getMissionManager().submitItems(select(mid),Map.of(item.getItemId(),1)));
    assertNotEquals(Retcode.SUCC.getVal(),report(mid));assertEquals(1,item.getCount());assertEquals(0,daily.actor.getScoin());
    daily.clock(DailyMissionMessage46Test.NEXT);daily.actor.getDailyManager().ensureDay();daily.actor.getMissionManager().refresh();
    assertSame(payment,daily.actor.getGameplay().getMissionPayments().get(select(mid)));assertTrue(payment.isPending());assertEquals(1,item.getCount());assertEquals(0,daily.actor.getScoin());
  }
  @Tag("selected-alias") @ParameterizedTest @ValueSource(ints={3011605,3011608})
  void aKnownSelectIdCannotBorrowTheOtherOriginalMainsQualificationToDebitRealStock(int mid)throws Exception{
    accepted(mid);int foreignId=select(mid==3011605?3011608:3011605);var item=stock(ITEMS.get(mid).getFirst());
    var original=GameplayDefinitions.subs.get(select(mid));var json=daily.gson.toJsonTree(original).getAsJsonObject();json.addProperty("ID",foreignId);
    var alias=daily.gson.fromJson(json,MissionInfo.SubMissionInfo.class);alias.setTask(original.getTask());
    assertEquals(mid,alias.getMainMissionID());assertEquals(foreignId,alias.getId());
    GameplayDefinitions.subs.put(foreignId,alias);daily.actor.getGameplay().getSubMissions().put(foreignId,new QuestState(foreignId));
    var before=daily.gson.toJson(daily.actor.getGameplay());int saves=daily.actor.saves;
    var protocol=daily.session().getProtocol();var selected=DynamicMessage.newBuilder(protocol.clientSchema().message("Item"));
    selected.setField(selected.getDescriptorForType().findFieldByName("item_id"),item.getItemId());selected.setField(selected.getDescriptorForType().findFieldByName("num"),1);
    var list=DynamicMessage.newBuilder(protocol.clientSchema().message("ItemList"));list.addRepeatedField(list.getDescriptorForType().findFieldByName("item_list"),selected.build());
    var response=daily.query("FinishCosumeItemMissionCsReq",Map.of("sub_mission_id",foreignId,"item_list",list.build()));
    assertNotEquals(Retcode.SUCC.getVal(),daily.field(response,"retcode"),"The real 1261 must validate the one original owner of its known submission id");
    assertEquals(1,item.getCount());assertSame(item,daily.actor.getInventory().getMaterialByItemId(item.getItemId()));
    assertEquals(before,daily.gson.toJson(daily.actor.getGameplay()));assertEquals(saves,daily.actor.saves);assertEquals(0,daily.actor.getScoin());
  }
}
