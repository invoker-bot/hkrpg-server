package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.*;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.util.Position;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual old API entry failures; no synthetic parent completion and no client Act/UI claim. */
class DailyMissionFollowup46Test {
  static final class SourceHolder {
    static final JsonObject VALUE=load();
    private static JsonObject load(){
      try(var stream=DailyMissionFollowup46Test.class.getResourceAsStream("/fixtures/daily-followup-3000604-4.6.json")){
        var value=JsonParser.parseString(new String(Objects.requireNonNull(stream).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        // Validate all original bytes once per JVM. Only Config blobs enter isolated fixture roots.
        for(var e:value.getAsJsonObject("sourceFiles").entrySet()){
          var metadata=e.getValue().getAsJsonObject();byte[] raw;
          if(metadata.has("sourceBlobBase64"))raw=Base64.getDecoder().decode(metadata.get("sourceBlobBase64").getAsString());
          else try(var inflater=new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(metadata.get("sourceBlobDeflateBase64").getAsString())))){raw=inflater.readAllBytes();}
          assertEquals(metadata.get("sourceBlobSHA256").getAsString(),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw)),e.getKey());
        }
        return value;
      }catch(Exception invalid){throw new ExceptionInInitializerError(invalid);}
    }
  }
  final DailyMissionMessageSibling46Test sibling=new DailyMissionMessageSibling46Test();
  final DailyMissionMessage46Test daily=sibling.daily;
  @TempDir Path root;
  JsonObject source;
  JsonObject travel;
  @BeforeEach void setup() throws Exception {
    sibling.root=root;sibling.setup();
    source=SourceHolder.VALUE;
    assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b",source.get("sourceCommit").getAsString());
    var originals=new HashMap<String,JsonElement>();
    for(var entry:source.getAsJsonObject("sourceFiles").entrySet()) {
      var metadata=entry.getValue().getAsJsonObject();
      if(!metadata.has("sourceBlobBase64"))continue;
      byte[] raw=Base64.getDecoder().decode(metadata.get("sourceBlobBase64").getAsString());
      assertEquals(metadata.get("sourceBlobSHA256").getAsString(),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw)));
      originals.put(entry.getKey(),JsonParser.parseString(new String(raw,java.nio.charset.StandardCharsets.UTF_8)));
      if(metadata.get("installed").getAsBoolean()) {
        var target=root.resolve(entry.getKey());Files.createDirectories(target.getParent());Files.write(target,raw);
      }
    }
    for(var raw:source.getAsJsonArray("records")) {
      var record=raw.getAsJsonObject();var row=record.get("row");
      if(record.get("source").getAsString().equals("ExcelOutput/MainMission.json") && row.getAsJsonObject().get("MainMissionID").getAsInt()==3000604)
        GameData.getMainMissionExcelMap().add(daily.gson.fromJson(row,MainMissionExcel.class));
    }
    var full=originals.get("Config/Level/Mission/3000604/MissionInfo_3000604.json").getAsJsonObject().deepCopy();
    full.add("SubMissionList",originals.get("Config/Level/Mission/3000604/MissionInfo_3000604.partial.json").getAsJsonObject().getAsJsonArray("SubMissionList").deepCopy());
    var graph=daily.gson.fromJson(full,MissionInfo.class);GameData.getMissionInfos().put(3000604,graph);
    for(var sub:graph.getSubMissionList()) {
      GameplayDefinitions.subs.put(sub.getId(),sub);
      if(sub.getMissionJsonPath()!=null && !sub.getMissionJsonPath().isEmpty()) {
        var json=originals.get(sub.getMissionJsonPath());assertNotNull(json);
        var task=daily.gson.fromJson(json,MissionTaskInfo.class);task.retainSourceDefinition(json);sub.setTask(task);
      }
    }
    var oldFloor=GameData.getFloorInfo(20002,20002001);
    var floor=daily.gson.fromJson(originals.get("Config/LevelOutput/RuntimeFloor/P20002_F20002001.json"),FloorInfo.class);
    floor.registerGroup(49,oldFloor.getGroupInfoByIndex(49));floor.registerGroup(66,oldFloor.getGroupInfoByIndex(66));
    var group=daily.gson.fromJson(originals.get("Config/LevelOutput/SharedRuntimeGroup/Groups_P20002_F20002001/LevelGroup_P20002_F20002001_G85.json"),GroupInfo.class);
    group.setId(85);floor.registerGroup(67,group);floor.onLoad();GameData.getFloorInfos().put("P20002_F20002001",floor);
    travel=source.getAsJsonObject("travelContext");
    GameData.getMazePlaneExcelMap().add(daily.gson.fromJson(travel.get("plane"),emu.lunarcore.data.excel.MazePlaneExcel.class));
    GameData.getMapEntranceExcelMap().add(daily.gson.fromJson(travel.get("entrance"),emu.lunarcore.data.excel.MapEntranceExcel.class));
    for(var e:travel.getAsJsonObject("sourceFiles").entrySet()){
      var metadata=e.getValue().getAsJsonObject();byte[] raw=Base64.getDecoder().decode(metadata.get("sourceBlobBase64").getAsString());
      assertEquals(metadata.get("sourceBlobSHA256").getAsString(),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw)));
      var train=daily.gson.fromJson(new String(raw,java.nio.charset.StandardCharsets.UTF_8),FloorInfo.class);train.onLoad();
      GameData.getFloorInfos().put("P10000_F10000000",train);
    }
    assertTrue(DailyMissionCatalog.configured());assertNull(DailyMissionCatalog.mission(3000604));
    assertEquals("Daily",GameData.getMainMissionExcelMap().get(3000604).getType());
    assertFalse(Files.exists(root.resolve("Config/Level/NPCDialogue/P20002/F20002001_G85/DialogueMain_F20002001_G85_N400001.json")),
        "Source-only original dialogue is not installed or pretended executed by this draft");
  }
  @AfterEach void cleanup() throws Exception {sibling.cleanup();}
  void completedParent(){
    sibling.open();sibling.pay(121140303,121140306,121140311);
    assertTrue(MissionStateRecords.mainCompleted(daily.actor,3000603));
    assertTrue(sibling.run().getPhone().isFinished());assertEquals(5000,daily.actor.getScoin());
  }
  void settledWithoutFollowup(){
    var followup=GameData.getMainMissionExcelMap().get(3000604);
    assertTrue(GameData.getMainMissionExcelMap().keySet().remove(3000604));
    try {completedParent();}finally {GameData.getMainMissionExcelMap().add(followup);}
    assertNull(daily.actor.getGameplay().getMainMissions().get(3000604));
  }
  void accepted(){completedParent();assertTrue(MissionStateRecords.mainDoing(daily.actor,3000604),"Actual original parent completion must auto-accept the original sequel");}
  void crossOriginalFloors(){
    assertTrue(daily.actor.loadScene(10000,10000000,100000102,new Position(),new Position(),false));
    assertTrue(daily.actor.loadScene(20002,20002001,200020102,new Position(),new Position(),false));
  }
  void entered(){accepted();crossOriginalFloors();assertTrue(MissionStateRecords.subCompleted(daily.actor,300060400));assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(300060401),300060401,1));}
  DynamicMessage report(String talk){return daily.query("FinishTalkMissionCsReq",Map.of("sub_mission_id",300060401,"talk_str",talk));}
  void reported(){
    assertEquals(Retcode.SUCC.getVal(),daily.field(report("Mission_300060401"),"retcode"));
    assertTrue(MissionStateRecords.subCompleted(daily.actor,300060401));
    assertEquals(QuestState.Phase.CLOSED,daily.actor.getGameplay().getMainMissions().get(3000604).getPhase());
    assertEquals(10000,daily.actor.getScoin());assertEquals(200,daily.actor.getGameplay().getDailyPoints());
  }
  @Test void realOriginalPhoneEndingAutomaticallyAcceptsItsAuthoredNonPoolFollowup(){
    completedParent();
    assertTrue(MissionStateRecords.mainDoing(daily.actor,3000604),"Original MultiSequence3000603 + Auto must reach its hidden non-pool Daily sequel");
    assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(300060400),300060400,1));
    assertNull(daily.actor.getMissionManager().sub(300060401));
    assertEquals(1,daily.actor.getGameplay().getDailyMissionSelections().getDays().get(Long.toString(DailyManager.day(daily.now))).size());
  }
  @Test void theExistingClientMissionQueryMustExposeTheAcceptedFollowupAfterRealParentCompletion(){
    completedParent();var data=daily.query("GetMissionDataCsReq",Map.of());
    assertEquals(Retcode.SUCC.getVal(),daily.field(data,"retcode"));
    var states=(List<?>)daily.field(data,"main_mission_list");
    assertTrue(states.stream().map(DynamicMessage.class::cast).anyMatch(row->(int)daily.field(row,"id")==3000604),
        "The actual existing 4.6 client query cannot find the original automatic sequel");
  }
  @Test void allRealRepliesWithoutTheFinalSectionCannotUnlockTheOriginalSequel(){
    sibling.open();sibling.replies(121140303,121140306,121140311);daily.actor.getMissionManager().refresh();
    assertTrue(MissionStateRecords.mainDoing(daily.actor,3000603));
    assertNull(daily.actor.getGameplay().getMainMissions().get(3000604));
    assertEquals(Retcode.MISSION_NOT_DOING,daily.actor.getMissionManager().accept(3000604));
    assertEquals(0,daily.actor.getScoin());assertEquals(0,daily.actor.getGameplay().getDailyPoints());
  }
  @Test void aForeignCompletedQuestObjectCannotSatisfyTheActualOriginalTakeGate() throws Exception {
    settledWithoutFollowup();
    var parent=daily.actor.getGameplay().getMainMissions().get(3000603);
    DailyMissionAcceptance46Test.set(parent,"questId",3000602);daily.actor.getMissionManager().refresh();
    assertEquals(Retcode.MISSION_NOT_DOING,daily.actor.getMissionManager().accept(3000604));
    assertNull(daily.actor.getGameplay().getMainMissions().get(3000604));
    assertEquals(5000,daily.actor.getScoin());assertEquals(200,daily.actor.getGameplay().getDailyPoints());
  }
  @Test void theCompleteSourceRetainsItsNonPoolIdentityAndHasNoInventedClientFinishAction(){
    assertEquals(23,source.getAsJsonObject("sourceFiles").size());
    assertEquals(41,source.getAsJsonArray("records").size());
    assertNull(DailyMissionCatalog.mission(3000604));
    var sub=GameplayDefinitions.subs.get(300060401);var raw=sub.getTask().getSourceDefinition();
    assertTrue(raw.contains("300060401_Finish"));
    assertFalse(raw.contains("ClientFinishMission"));assertFalse(raw.contains("FinishPerformanceMission"));
    assertFalse(source.getAsJsonObject("sourceFiles").getAsJsonObject("Config/Level/NPCDialogue/P20002/F20002001_G85/DialogueMain_F20002001_G85_N400001.json").get("installed").getAsBoolean());
  }
  @Test void realOriginalFloorCrossingMakesOnlyTheOriginalTalkReportEligibleThenPaysOnce(){
    entered();var scene=daily.actor.getScene();
    assertEquals(2,scene.getEntitiesByGroup(EntityNpc.class,85).size());assertEquals(1,scene.getEntitiesByGroup(EntityProp.class,85).size());
    assertEquals(Retcode.MISSION_FINISH_WAY_NOT_MATCH.getVal(),daily.field(report("Mission_300060301"),"retcode"));
    assertEquals(5000,daily.actor.getScoin());reported();
    assertEquals(Retcode.MISSION_NOT_DOING.getVal(),daily.field(report("Mission_300060401"),"retcode"));
    assertEquals(10000,daily.actor.getScoin());assertEquals(200,daily.actor.getGameplay().getDailyPoints());
  }
  @Test void sameFloorReloadAndForeignEventsDoNotInventTheOriginalEnterFloorTransition(){
    accepted();assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(300060400),300060400,1));
    daily.floor();daily.actor.getMissionManager().onEnterFloor(20002,20002002);
    daily.actor.getMissionManager().onSceneTransition(20002,20002001,200020102,20002,20002001,200020102);
    assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(300060400),300060400,1));
    assertNull(daily.actor.getMissionManager().sub(300060401));
    assertEquals(Retcode.MISSION_NOT_DOING.getVal(),daily.field(report("Mission_300060401"),"retcode"));
    crossOriginalFloors();assertTrue(MissionStateRecords.subCompleted(daily.actor,300060400));
    assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(300060401),300060401,1));
  }
  @Test void twoActualMorphiaPlayerReadsPreserveTheNonPoolStagesAndSettledReward() throws Exception {
    entered();
    var server=new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());var address=server.bind();
    try(var client=com.mongodb.client.MongoClients.create("mongodb://"+address.getHostString()+":"+address.getPort())){
      var store=dev.morphia.Morphia.createDatastore(client,"daily_followup_original");store.getMapper().map(Player.class,QuestState.class);
      for(int iteration=0;iteration<2;iteration++){
        var base=new Player();DailyMissionAcceptance46Test.set(base,"uid",daily.actor.getUid());
        DailyMissionAcceptance46Test.set(base,"gameplay",daily.actor.getGameplay());DailyMissionAcceptance46Test.set(base,"scoin",daily.actor.getScoin());store.save(base);
        var restored=store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",daily.actor.getUid())).first();assertNotNull(restored);
        daily.actor=daily.fresh();DailyMissionAcceptance46Test.set(daily.actor,"gameplay",restored.getGameplay());DailyMissionAcceptance46Test.set(daily.actor,"scoin",restored.getScoin());
        daily.clock(DailyMissionMessage46Test.TODAY);daily.floor();
        assertNull(DailyMissionRepeats.selectedDay(daily.actor,3000604));
        assertFalse(daily.actor.getGameplay().getDailyMissionPeriods().getRunDays().containsKey(3000604));
        if(iteration==0){assertTrue(MissionStateRecords.doing(daily.actor.getMissionManager().sub(300060401),300060401,1));reported();}
        else {assertEquals(Retcode.MISSION_NOT_DOING.getVal(),daily.field(report("Mission_300060401"),"retcode"));assertEquals(10000,daily.actor.getScoin());}
      }
    }finally{server.shutdownNow();}
  }
  @Test void noPoolPeriodOrDailyResetIsInventedForThePersistedOriginalSequel(){
    entered();reported();var saved=daily.actor.getGameplay().getMainMissions().get(3000604);
    assertFalse(DailyCommissionAcceptance.applies(3000604));
    assertNull(DailyMissionRepeats.selectedDay(daily.actor,3000604));
    daily.clock(DailyMissionMessage46Test.NEXT);daily.actor.getMissionManager().refresh();
    assertSame(saved,daily.actor.getGameplay().getMainMissions().get(3000604));
    assertEquals(Retcode.MISSION_ALREADY_DONE,daily.actor.getMissionManager().accept(3000604));
    assertFalse(daily.actor.getGameplay().getDailyMissionPeriods().getRunDays().containsKey(3000604));
    assertTrue(daily.actor.getGameplay().getDailyMissionPeriods().getArchives().values().stream().noneMatch(row->row.getMainId()==3000604));
    assertTrue(daily.actor.getGameplay().getDailyMissionPeriods().getExpired().values().stream().noneMatch(row->row.getMainId()==3000604));
    assertEquals(10000,daily.actor.getScoin());
  }
  @Test void theAlreadySpentOriginalPoolQuotaDoesNotConsumeASecondSelectionForItsAuthoredSequel(){
    accepted();var selections=daily.actor.getGameplay().getDailyMissionSelections();
    var choices=selections.getDays().get(Long.toString(DailyManager.day(daily.now)));
    assertEquals(1,choices.size());assertEquals(3000603,choices.getFirst().getId());
    String before=daily.gson.toJson(selections);assertEquals(Retcode.SUCC,daily.actor.getMissionManager().accept(3000604));
    assertEquals(before,daily.gson.toJson(selections));assertNull(DailyMissionRepeats.selectedDay(daily.actor,3000604));
  }
  @Test void aLegacyGlobalPhoneFlagCannotReplaceTheOriginalCompletedParentMission(){
    var legacy=new GameplayState.PhoneSection(1211402,List.of(121140301));legacy.finish();
    daily.actor.getGameplay().getPhoneSections().put(1211402,legacy);daily.actor.getMissionManager().refresh();
    assertFalse(MissionStateRecords.mainCompleted(daily.actor,3000603));assertNull(daily.actor.getGameplay().getMainMissions().get(3000604));
    assertEquals(Retcode.MISSION_NOT_DOING,daily.actor.getMissionManager().accept(3000604));
    assertSame(legacy,daily.actor.getGameplay().getPhoneSections().get(1211402));assertEquals(0,daily.actor.getScoin());
  }
  @ParameterizedTest @ValueSource(strings={"future","noTime","noProgress","cancelled","claimingWithoutRealCompletion"})
  void invalidParentCompletionRecordsNeverUnlockTheNonPoolOriginal(String damaged) throws Exception {
    settledWithoutFollowup();var parent=daily.actor.getGameplay().getMainMissions().get(3000603);
    switch(damaged){
      case "future"->DailyMissionAcceptance46Test.set(parent,"finishTime",daily.now.getEpochSecond()+1);
      case "noTime"->DailyMissionAcceptance46Test.set(parent,"finishTime",0L);
      case "noProgress"->DailyMissionAcceptance46Test.set(parent,"progress",0);
      case "cancelled"->daily.actor.getGameplay().getCancelledMissions().add(3000603);
      case "claimingWithoutRealCompletion"->{DailyMissionAcceptance46Test.set(parent,"phase",QuestState.Phase.CLAIMING);DailyMissionAcceptance46Test.set(parent,"progress",0);}
      default->fail(damaged);
    }
    daily.actor.getMissionManager().refresh();assertEquals(Retcode.MISSION_NOT_DOING,daily.actor.getMissionManager().accept(3000604));
    assertNull(daily.actor.getGameplay().getMainMissions().get(3000604));assertEquals(5000,daily.actor.getScoin());
  }
  @ParameterizedTest @ValueSource(strings={"mainType","take","begin","reward","mainId","duplicateSub","subOwner","finishType","place","starts","task","wholeOriginalFile"})
  void aDifferentLoadedDefinitionOrCompleteOriginalCannotGainTheLimitedException(String damaged) throws Exception {
    settledWithoutFollowup();var main=GameData.getMainMissionExcelMap().get(3000604);var sub=GameplayDefinitions.subs.get(300060401);
    switch(damaged){
      case "mainType"->DailyMissionAcceptance46Test.set(main,"Type","Branch");
      case "take"->DailyMissionAcceptance46Test.set(main,"TakeParam",List.of(new MainMissionExcel.Gate("Auto",0)));
      case "begin"->DailyMissionAcceptance46Test.set(main,"BeginParam",List.of(new MainMissionExcel.Gate("Manual",0)));
      case "reward"->DailyMissionAcceptance46Test.set(main,"RewardID",232);
      case "mainId"->DailyMissionAcceptance46Test.set(main,"MainMissionID",3000605);
      case "duplicateSub"->{var graph=GameData.getMissionInfos().get(3000604);DailyMissionAcceptance46Test.set(graph,"subMissionList",new MissionInfo.SubMissionInfo[]{sub,sub});}
      case "subOwner"->sub.setMainMissionID(3000603);
      case "finishType"->sub.setFinishType("Auto");
      case "place"->sub.setLevelFloorID(20002002);
      case "starts"->DailyMissionAcceptance46Test.set(GameData.getMissionInfos().get(3000604),"startSubMissionList",new int[]{300060401});
      case "task"->sub.setTask(GameplayDefinitions.subs.get(300060301).getTask());
      case "wholeOriginalFile"->{var path=root.resolve("Config/Level/Mission/3000604/Act/Act300060401.json");var value=JsonParser.parseString(Files.readString(path)).getAsJsonObject();value.addProperty("UnrecognizedDifferentOriginal",true);Files.writeString(path,value.toString());}
      default->fail(damaged);
    }
    daily.actor.getMissionManager().refresh();assertEquals(Retcode.MISSION_NOT_DOING,daily.actor.getMissionManager().accept(3000604));
    assertNull(daily.actor.getGameplay().getMainMissions().get(3000604));assertEquals(5000,daily.actor.getScoin());
  }
  @ParameterizedTest @ValueSource(strings={"missingNpc","missingProp","wrongNpc","movedNpc","foreignScene"})
  void aReportNeedsTheActualOriginalLoadedNpcPropAndSceneButDoesNotInventClientStrings(String damaged) throws Exception {
    entered();var scene=daily.actor.getScene();var npc=scene.getEntitiesByGroup(EntityNpc.class,85).getFirst();
    switch(damaged){
      case "missingNpc"->scene.removeEntity(npc);
      case "missingProp"->scene.removeEntity(scene.getEntitiesByGroup(EntityProp.class,85).getFirst());
      case "wrongNpc"->npc.setNpcId(10206);
      case "movedNpc"->npc.getPos().set(new Position());
      case "foreignScene"->DailyMissionAcceptance46Test.set(daily.actor,"scene",new Scene(daily.fresh(),GameData.getMazePlaneExcelMap().get(20002),20002001));
      default->fail(damaged);
    }
    String before=daily.gson.toJson(daily.actor.getGameplay());int saves=daily.actor.saves;
    assertEquals(Retcode.MISSION_FINISH_WAY_NOT_MATCH.getVal(),daily.field(report("Mission_300060401"),"retcode"));
    assertEquals(before,daily.gson.toJson(daily.actor.getGameplay()));assertEquals(saves,daily.actor.saves);assertEquals(5000,daily.actor.getScoin());
  }
  @Test void theExisting3000602PhoneAndG61FirstMeetingAreRetained() throws Exception {
    daily.catalog(3000602);sibling.g61.floor();sibling.g61.succeeds();daily.open();daily.pay(121140202,121140205);
    assertEquals(5000,daily.actor.getScoin());assertEquals(200,daily.actor.getGameplay().getDailyPoints());
    assertNull(daily.actor.getGameplay().getMainMissions().get(3000604));
  }
}
