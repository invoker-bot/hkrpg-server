package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.*;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Real original three-stage daily entry/phone/period tests. Client Act execution is not claimed. */
class DailyMissionMessageOrigin46Test {
  static final int MAIN=3000601, PRE=300060001, FIRST=300060101, GOAL=300060102, SECTION=1211400;
  final DailyMissionMessageSibling46Test sibling = new DailyMissionMessageSibling46Test();
  final G61NpcFirstTalk46Test g61 = sibling.g61;
  final DailyMissionMessage46Test daily = sibling.daily;
  final Gson gson = sibling.gson;
  @TempDir Path root;
  JsonObject source;

  @BeforeEach void setup() throws Exception {
    sibling.root=root; sibling.setup();
    try (var stream=getClass().getResourceAsStream("/fixtures/daily-message-3000601-4.6.json")) {
      source=JsonParser.parseString(new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b",source.get("sourceCommit").getAsString());
    assertEquals(51,source.getAsJsonObject("sourceFiles").size());
    var extra=source.getAsJsonObject("sourceFiles").getAsJsonObject("Config/Level/Mission/3000600/Act/Act300060001.json");
    var raw=Base64.getDecoder().decode(extra.get("sourceBlobBase64").getAsString());
    assertEquals(extra.get("sourceBlobSHA256").getAsString(), java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw)));
    var extraPath=root.resolve("Config/Level/Mission/3000600/Act/Act300060001.json");
    Files.createDirectories(extraPath.getParent()); Files.write(extraPath,raw);
    assertEquals(List.of(PRE,FIRST,GOAL),Arrays.stream(GameData.getMissionInfos().get(MAIN).getSubMissionList()).map(MissionInfo.SubMissionInfo::getId).toList());
    assertEquals(11,GameplayDefinitions.messageItems.values().stream().filter(row->row.SectionID()==SECTION).count());
    daily.catalog(MAIN); daily.clock(DailyMissionMessage46Test.TODAY);
  }
  @AfterEach void cleanup() throws Exception { sibling.cleanup(); }
  MessageManager messages(){return new MessageManager(daily.actor);}
  String saved(){return gson.toJson(daily.actor.getGameplay());}
  QuestState sub(int id){return daily.actor.getMissionManager().sub(id);}
  GroupInfo group(){return GameData.getFloorInfo(20002,20002001).getGroups(0).get(84);}
  void accept(){g61.floor(); assertEquals(Retcode.SUCC,daily.actor.getMissionManager().accept(MAIN));}
  void initial(){assertEquals(Retcode.SUCC,daily.actor.getMissionManager().finishTalk(PRE,"Mission_"+PRE));}
  void open(){accept(); initial(); assertEquals(Retcode.SUCC,daily.actor.getMissionManager().finishTalk(FIRST,"Mission_"+FIRST));}
  DailyMissionMessageState.Run run(){
    var run=daily.actor.getGameplay().getDailyMissionMessages().getRuns().get(MAIN+":"+DailyManager.day(daily.now));
    assertNotNull(run,"Original1211400 must have its own accepted-period receipt, not global completion");
    assertEquals(MAIN,run.getMainId()); assertEquals(GOAL,run.getGoalId()); assertEquals(SECTION,run.getSectionId());
    assertEquals(daily.actor.getGameplay().getMainMissions().get(MAIN).getObservationKey(),run.getMainAcceptance());
    assertEquals(sub(GOAL).getObservationKey(),run.getGoalAcceptance()); return run;
  }
  void g84Actors(){
    var scene=daily.actor.getScene();
    assertEquals(Set.of(400001,400002),new HashSet<>(scene.getEntitiesByGroup(EntityNpc.class,84).stream().map(EntityNpc::getInstId).toList()));
    assertEquals(Set.of(10209,10215),new HashSet<>(scene.getEntitiesByGroup(EntityNpc.class,84).stream().map(EntityNpc::getNpcId).toList()));
    assertEquals(Set.of(300001,300002,300003),new HashSet<>(scene.getEntitiesByGroup(EntityProp.class,84).stream().map(EntityProp::getInstId).toList()));
    assertTrue(scene.getEntitiesByGroup(EntityProp.class,84).stream().allMatch(prop->prop.getPropId()==100));
  }
  JsonObject menu() throws Exception {
    var tasks=g61.graph(G61NpcFirstTalk46Test.PACK_GRAPH).getAsJsonArray("OnStartSequece").get(0).getAsJsonObject().getAsJsonArray("TaskList");
    return tasks.asList().stream().map(JsonElement::getAsJsonObject).filter(row->row.has("Predicate") && row.getAsJsonObject("Predicate").has("SubMissionID") && row.getAsJsonObject("Predicate").get("SubMissionID").getAsInt()==FIRST).findFirst().orElseThrow();
  }
  void reply(int id){
    assertEquals(List.of(121140101,121140111),daily.history(daily.phone(SECTION)));
    assertEquals(List.of(121140102,121140104,121140106),daily.offered(daily.phone(SECTION)));
    assertEquals(Retcode.MESSAGE_SECTION_CAN_NOT_FINISH,messages().finishSection(SECTION));
    var response=daily.query("FinishItemIdCsReq",Map.of("item_id",id));
    assertEquals(Retcode.SUCC.getVal(),daily.field(response,"retcode"));
    assertEquals(7,daily.history(daily.phone(SECTION)).size());
    assertEquals(121140110,daily.history(daily.phone(SECTION)).getLast());
    assertTrue(daily.offered(daily.phone(SECTION)).isEmpty());
    assertFalse(messages().finished(SECTION)); assertEquals(0,sub(GOAL).getProgress());
  }
  void pay(int id){
    int before=daily.actor.getScoin(); reply(id);
    var response=daily.query("FinishSectionIdCsReq",Map.of("section_id",SECTION));
    assertEquals(Retcode.SUCC.getVal(),daily.field(response,"retcode"));
    assertEquals(before+5000,daily.actor.getScoin());
    assertEquals(QuestState.Phase.CLOSED,daily.actor.getGameplay().getMainMissions().get(MAIN).getPhase());
    assertEquals(QuestState.Phase.FINISHED,daily.actor.getGameplay().getDailyQuests().get(2100003).getPhase());
    assertEquals(Retcode.SUCC,daily.actor.getDailyManager().claimQuest(2100003).retcode());
    assertEquals(200,daily.actor.getGameplay().getDailyPoints());
    assertTrue(run().getPhone().isFinished());
    assertFalse(daily.actor.getGameplay().getPhoneSections().containsKey(SECTION));
  }
  GameplayState.PhoneSection legacy() throws Exception {
    var result=new GameplayState.PhoneSection(SECTION,List.of(121140101));
    DailyMissionAcceptance46Test.set(result,"refreshed",daily.now.minusSeconds(86400).getEpochSecond());
    for(int id:List.of(121140101,121140111,121140102,121140103,121140108,121140109,121140110))
      result.step(id,GameplayDefinitions.messageItems.get(id).NextItemIDList());
    result.finish(); return result;
  }
  void task(int id,String other){
    JsonElement raw;
    // Whole raw source is loaded from the actual fixture file, not a copied partial task.
    try {raw=JsonParser.parseString(Files.readString(root.resolve(other)));} catch(Exception failure){throw new RuntimeException(failure);}
    var task=gson.fromJson(raw,MissionTaskInfo.class); task.retainSourceDefinition(raw); GameplayDefinitions.subs.get(id).setTask(task);
  }

  @Test void theCompleteOriginalThreeStageGraphQualifies(){
    assertTrue(DailyMissionRepeats.supportedGraph(MAIN));
    assertArrayEquals(new int[]{PRE},GameData.getMissionInfos().get(MAIN).getStartSubMissionList());
    assertEquals("Config/Level/Mission/3000600/Mission_300060001.json",GameplayDefinitions.subs.get(PRE).getMissionJsonPath());
    assertEquals("Manual",GameData.getMainMissionExcelMap().get(MAIN).getTakeParam().getFirst().Type());
    assertTrue(GameplayDefinitions.subs.get(GOAL).isBackTrack());
  }
  @Test void refreshAssignsTheOriginalInitialStageAndRealG84Actors() throws Exception {
    g61.floor(); daily.actor.getMissionManager().refresh();
    assertTrue(MissionStateRecords.mainDoing(daily.actor,MAIN));
    assertTrue(MissionStateRecords.doing(sub(PRE),PRE,1)); assertNull(sub(FIRST)); assertNull(sub(GOAL)); g84Actors();
    assertEquals("Started",menu().getAsJsonObject("Predicate").get("SubMissionState").getAsString());
  }
  @ParameterizedTest @ValueSource(strings={"catalog","g84","owner","prePath","preTask","goalTask","duplicateNpc","phoneNode"})
  void nonOriginalThreeStageSourceCannotQualify(String damage) throws Exception {
    switch(damage){
      case "catalog"->daily.catalog(3000602);
      case "g84"->{var floor=GameData.getFloorInfo(20002,20002001); floor.getIndexedGroups().remove(66); DailyMissionAcceptance46Test.set(floor,"resourceGroups",Map.of());}
      case "owner"->DailyMissionAcceptance46Test.set(group(),"OwnerMainMissionID",3000602);
      case "prePath"->GameplayDefinitions.subs.get(PRE).setMissionJsonPath("Config/Level/Mission/3000601/Mission_300060001.json");
      case "preTask"->task(PRE,"Config/Level/Mission/3000603/Mission_300060301.json");
      case "goalTask"->task(GOAL,"Config/Level/Mission/3000600/Mission_300060001.json");
      case "duplicateNpc"->{var npcs=new ArrayList<>(group().getNPCList()); npcs.add(npcs.getFirst()); DailyMissionAcceptance46Test.set(group(),"NPCList",npcs);}
      case "phoneNode"->GameplayDefinitions.messageItems.remove(121140110);
    }
    assertFalse(DailyMissionRepeats.supportedGraph(MAIN));
  }
  @Test void anUnreviewedMainDoesNotGainTheSameMessageEligibility() throws Exception {
    var goal=gson.fromJson(gson.toJson(GameplayDefinitions.subs.get(GOAL)),MissionInfo.SubMissionInfo.class);
    goal.setMainMissionID(3000699);
    assertFalse(DailyMissionMessageRules.repeatable(goal));
  }
  @Test void beforeInitialCompletionOnlyG84IsActive(){
    accept(); g84Actors(); assertTrue(MissionStateRecords.doing(sub(PRE),PRE,1));
    assertNull(sub(FIRST)); assertNull(sub(GOAL)); assertNull(daily.phone(SECTION)); assertEquals(0,daily.actor.getScoin());
  }
  @Test void g61FirstMeetingCannotCompleteTheInitialMission(){
    accept(); assertEquals(Retcode.SUCC.getVal(),daily.field(g61.finish(),"retcode"));
    assertEquals(0,sub(PRE).getProgress()); assertNull(sub(FIRST)); assertNull(daily.phone(SECTION));
  }
  @Test void aGenericProp100StateChangeCannotCompleteTheOriginalCustomStringGraph(){
    accept(); g84Actors();
    var prop=daily.actor.getScene().getEntitiesByGroup(EntityProp.class,84).stream().filter(value->value.getInstId()==300001).findFirst().orElseThrow();
    var state=prop.getExcel().getPropStateList().stream().filter(value->value!=prop.getState()).findFirst().orElseThrow();
    assertTrue(prop.setState(state,false)); daily.actor.getMissionManager().refresh();
    assertEquals(0,sub(PRE).getProgress()); assertTrue(MissionStateRecords.doing(sub(PRE),PRE,1));
    assertNull(sub(FIRST)); assertNull(sub(GOAL)); assertNull(daily.phone(SECTION)); assertEquals(0,daily.actor.getScoin());
  }
  @Test void earlyG61TalkCannotSkipInitialStage(){
    accept(); String before=saved();
    assertEquals(Retcode.MISSION_NOT_DOING,daily.actor.getMissionManager().finishTalk(FIRST,"Mission_"+FIRST));
    assertEquals(before,saved());
  }
  @Test void theOriginalInitialReportOpensOnlyTheG61Stage() throws Exception {
    accept(); g84Actors();
    var response=daily.query("FinishTalkMissionCsReq",Map.of("sub_mission_id",PRE,"talk_str","Mission_"+PRE));
    assertEquals(Retcode.SUCC.getVal(),daily.field(response,"retcode"));
    assertTrue(MissionStateRecords.subCompleted(daily.actor,PRE)); assertTrue(MissionStateRecords.doing(sub(FIRST),FIRST,1));
    assertNull(sub(GOAL)); assertNull(daily.phone(SECTION));
    assertEquals(FIRST,menu().getAsJsonArray("SuccessTaskList").get(0).getAsJsonObject().get("MissionID").getAsInt());
    // Literal source contains both original CustomString graphs; no generic Prop100 transition completes it.
    String original=GameplayDefinitions.subs.get(PRE).getTask().getSourceDefinition();
    assertTrue(original.contains("300060001_Bubble")); assertTrue(original.contains("300060001_Finish"));
  }
  @Test void unloadedInitialSourceCannotCompleteTheInitialMission(){
    accept(); for(var entity:List.copyOf(daily.actor.getScene().getEntities().values()))if(entity.getGroupId()==84)daily.actor.getScene().removeEntity(entity);
    String before=saved(); int saves=daily.actor.saves;
    assertNotEquals(Retcode.SUCC,daily.actor.getMissionManager().finishTalk(PRE,"Mission_"+PRE));
    assertEquals(before,saved()); assertEquals(saves,daily.actor.saves);
  }
  @Test void wrongKeyAndWrongFloorCannotAdvance() throws Exception {
    accept(); String before=saved();
    assertNotEquals(Retcode.SUCC,daily.actor.getMissionManager().finishTalk(PRE,"Mission_300060101"));
    DailyMissionAcceptance46Test.set(daily.actor,"floorId",20002002);
    assertEquals(Retcode.MISSION_SCENE_NOT_MATCH,daily.actor.getMissionManager().finishTalk(PRE,"Mission_"+PRE)); assertEquals(before,saved());
  }
  @Test void finishedLegacyGlobalDoesNotPayThisNewAcceptance() throws Exception {
    var old=legacy(); daily.actor.getGameplay().getPhoneSections().put(SECTION,old); String oldJson=gson.toJson(old);
    open(); assertTrue(MissionStateRecords.mainDoing(daily.actor,MAIN)); assertEquals(0,sub(GOAL).getProgress());
    assertEquals(0,daily.actor.getScoin()); assertSame(old,daily.actor.getGameplay().getPhoneSections().get(SECTION));
    assertEquals(oldJson,gson.toJson(old)); assertNotSame(old,messages().states().get(SECTION)); assertFalse(run().getPhone().isFinished());
  }
  @ParameterizedTest @ValueSource(ints={PRE,FIRST})
  void aFreshReceiptCannotUseReplacementCompletedPriorStages(int id){
    open(); var replacement=new QuestState(id); replacement.advance(1,1,daily.now.getEpochSecond());
    daily.actor.getGameplay().getSubMissions().put(id,replacement); String before=saved();
    assertNotEquals(Retcode.SUCC,messages().choose(121140102,0)); assertEquals(before,saved()); assertEquals(0,daily.actor.getScoin());
  }
  @ParameterizedTest @ValueSource(ints={121140102,121140104,121140106})
  void everyOriginalSingleReplyClosesOnlyItsOwnAcceptedPeriod(int reply){open(); pay(reply);}
  @Test void duplicateFinishSectionDoesNotResetOrPayAgain(){
    open(); pay(121140102); String before=saved();
    assertEquals(Retcode.MESSAGE_SECTION_CAN_NOT_FINISH,messages().finishSection(SECTION));
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN,daily.actor.getDailyManager().claimQuest(2100003).retcode()); assertEquals(before,saved());
  }
  @Test void siblingChoicesCannotEnterThisOriginalPhone(){
    open(); String before=saved();
    assertNotEquals(Retcode.SUCC,messages().choose(121140202,0)); assertNotEquals(Retcode.SUCC,messages().choose(121140302,0)); assertEquals(before,saved());
  }
  @Test void lastReplyWithoutFinishSectionIsStillUnpaid(){
    open(); reply(121140106); assertEquals(0,daily.actor.getScoin());
    assertTrue(MissionStateRecords.mainDoing(daily.actor,MAIN)); assertFalse(messages().states().get(SECTION).isFinished()); assertNotNull(run());
  }
  @ParameterizedTest @ValueSource(ints={0,1,2,3})
  void allUnpaidOriginalStagesExpireAndRecreateG84OnTheSameFloor(int stage){
    accept(); if(stage>=1)initial(); if(stage>=2)assertEquals(Retcode.SUCC,daily.actor.getMissionManager().finishTalk(FIRST,"Mission_"+FIRST));
    if(stage==3)reply(121140102);
    var oldMain=daily.actor.getGameplay().getMainMissions().get(MAIN); String oldKey=oldMain.getObservationKey();
    var oldPre=sub(PRE); var oldNpc=daily.actor.getScene().getEntitiesByGroup(EntityNpc.class,84).getFirst();
    var oldPhone=stage>=2?messages().states().get(SECTION):null; String before=saved();
    daily.clock(DailyMissionMessage46Test.NEXT);
    assertNotEquals(Retcode.SUCC,stage>=2?messages().choose(121140104,0):daily.actor.getMissionManager().finishTalk(stage==0?PRE:FIRST,"Mission_"+(stage==0?PRE:FIRST)));
    assertEquals(before,saved());
    daily.actor.getDailyManager().ensureDay(); daily.actor.getMissionManager().refresh();
    var expired=daily.actor.getGameplay().getDailyMissionPeriods().getExpired().get(MAIN+":"+DailyManager.day(DailyMissionMessage46Test.TODAY));
    assertNotNull(expired); assertSame(oldMain,expired.getMain()); assertSame(oldPre,expired.getSubs().get(PRE));
    var newMain=daily.actor.getGameplay().getMainMissions().get(MAIN);
    assertNotSame(oldMain,newMain); assertNotSame(oldPre,sub(PRE));
    // Before phone observation these accepted states intentionally have no allocated UUID yet.
    if(oldKey!=null)assertNotEquals(oldKey,newMain.getObservationKey());
    if(oldPre.getObservationKey()!=null)assertNotEquals(oldPre.getObservationKey(),sub(PRE).getObservationKey());
    assertEquals(0,sub(PRE).getProgress());
    assertNull(sub(FIRST)); assertNull(sub(GOAL)); g84Actors();
    assertTrue(daily.actor.getScene().getEntitiesByGroup(EntityNpc.class,84).stream().noneMatch(value->value==oldNpc));
    if(oldPhone!=null)assertSame(oldPhone,daily.actor.getGameplay().getDailyMissionMessages().getRuns().get(MAIN+":"+DailyManager.day(DailyMissionMessage46Test.TODAY)).getPhone());
    assertEquals(0,daily.actor.getScoin()); assertNull(daily.phone(SECTION));
  }
  @Test void settledNextDayReacceptsAllThreeOriginalStages(){
    open(); pay(121140104); var old=run(); var oldPre=sub(PRE); String oldJson=gson.toJson(old);
    assertTrue(daily.actor.getScene().getEntitiesByGroup(EntityNpc.class,84).isEmpty());
    daily.clock(DailyMissionMessage46Test.NEXT); daily.actor.getDailyManager().ensureDay(); daily.actor.getMissionManager().refresh();
    assertTrue(MissionStateRecords.doing(sub(PRE),PRE,1)); assertNotEquals(oldPre.getObservationKey(),sub(PRE).getObservationKey()); g84Actors();
    initial(); assertEquals(Retcode.SUCC,daily.actor.getMissionManager().finishTalk(FIRST,"Mission_"+FIRST)); pay(121140106);
    assertEquals(10000,daily.actor.getScoin()); assertNotEquals(old.getMainAcceptance(),run().getMainAcceptance());
    assertEquals(oldJson,gson.toJson(old)); assertNotNull(daily.actor.getGameplay().getDailyMissionPeriods().getArchives().get(MAIN+":"+DailyManager.day(DailyMissionMessage46Test.TODAY)));
  }
  @ParameterizedTest @ValueSource(ints={3000602,3000603})
  void anOriginalSiblingUsesTheSameAuthoredTypeQuota(int chosen) throws Exception {
    daily.catalog(chosen,MAIN); g61.floor();
    // Use a fresh actor before automatic refresh so the exact chosen row is reserved by the real API.
    daily.actor=daily.fresh(); daily.clock(DailyMissionMessage46Test.TODAY);
    assertEquals(Retcode.SUCC,daily.actor.getMissionManager().accept(chosen)); String before=saved();
    assertEquals(Retcode.MISSION_NOT_DOING,daily.actor.getMissionManager().accept(MAIN)); assertEquals(before,saved());
  }
  @Test void twoRealPlayerDocumentReadsPreserveInitialAndPhoneIdentities() throws Exception {
    accept(); initial(); var server=new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend()); var address=server.bind();
    try(var client=com.mongodb.client.MongoClients.create("mongodb://"+address.getHostString()+":"+address.getPort())){
      var store=dev.morphia.Morphia.createDatastore(client,"daily_message_origin"); store.getMapper().map(Player.class,QuestState.class);
      for(int iteration=0;iteration<2;iteration++){
        String before=saved(); var base=new Player();
        DailyMissionAcceptance46Test.set(base,"uid",daily.actor.getUid()); DailyMissionAcceptance46Test.set(base,"gameplay",daily.actor.getGameplay()); DailyMissionAcceptance46Test.set(base,"scoin",daily.actor.getScoin()); store.save(base);
        var restored=store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",daily.actor.getUid())).first(); assertNotNull(restored);
        daily.actor=daily.fresh(); DailyMissionAcceptance46Test.set(daily.actor,"gameplay",restored.getGameplay()); DailyMissionAcceptance46Test.set(daily.actor,"scoin",restored.getScoin()); daily.clock(DailyMissionMessage46Test.TODAY); g61.floor();
        assertEquals(before,saved());
        if(iteration==0)assertEquals(Retcode.SUCC,daily.actor.getMissionManager().finishTalk(FIRST,"Mission_"+FIRST));
        else assertFalse(run().getPhone().isFinished());
      }
      pay(121140106);
    }finally{server.shutdownNow();}
  }
  @ParameterizedTest @ValueSource(strings={"reply","section"})
  void saveRejectionRestoresTheExactPeriodAndRemainsRetryable(String action){
    open(); if(action.equals("section"))reply(121140102); var old=run(); String before=saved(); daily.actor.failSave=true;
    assertThrows(IllegalStateException.class,()->{if(action.equals("reply"))messages().choose(121140102,0); else messages().finishSection(SECTION);});
    daily.actor.failSave=false; assertEquals(before,saved()); assertSame(old,run()); assertEquals(0,daily.actor.getScoin());
    if(action.equals("reply"))reply(121140102); assertEquals(Retcode.SUCC,messages().finishSection(SECTION)); assertEquals(5000,daily.actor.getScoin());
  }
  @Test void anInitialPhoneWriteFailureRestoresBothCompletedPrerequisiteKeys(){
    open(); var previous=run();
    // Recoverable old document: real original reports already finished, no phone receipt saved yet.
    daily.actor.getGameplay().getDailyMissionMessages().getRuns().remove(MAIN+":"+DailyManager.day(daily.now));
    daily.actor.getGameplay().getMainMissions().get(MAIN).restoreObservationKey(previous.getMainAcceptance(),null);
    sub(GOAL).restoreObservationKey(previous.getGoalAcceptance(),null);
    sub(PRE).restoreObservationKey(previous.getInitialAcceptance(),null);
    sub(FIRST).restoreObservationKey(previous.getFirstAcceptance(),null);
    String before=saved(); var pre=sub(PRE); var first=sub(FIRST); daily.actor.failSave=true;
    assertThrows(IllegalStateException.class,()->messages().activateEligible());
    daily.actor.failSave=false; assertEquals(before,saved()); assertSame(pre,sub(PRE)); assertSame(first,sub(FIRST));
    assertNull(pre.getObservationKey()); assertNull(first.getObservationKey());
    assertTrue(daily.actor.getGameplay().getDailyMissionMessages().getRuns().isEmpty());
    assertTrue(messages().activateEligible());
    assertEquals(pre.getObservationKey(),run().getInitialAcceptance()); assertEquals(first.getObservationKey(),run().getFirstAcceptance());
    assertNotEquals(previous.getInitialAcceptance(),run().getInitialAcceptance());
    pay(121140104);
  }
  @Test void sameD0LateUnlockAddsOriginalActivityAndCommission(){
    try {daily.actor=daily.fresh();}catch(Exception failure){throw new RuntimeException(failure);}
    daily.actor.getGameplay().getMainMissions().remove(1010403); daily.clock(DailyMissionMessage46Test.TODAY);
    daily.actor.getDailyManager().ensureDay(); assertFalse(daily.actor.getGameplay().getDailyQuests().containsKey(2100003));
    daily.actor.getGameplay().getMainMissions().put(1010403,DailyMissionAcceptance46Test.closed(1010403));
    daily.actor.getDailyManager().ensureDay(); g61.floor(); assertTrue(MissionStateRecords.doing(sub(PRE),PRE,1));
    initial(); assertEquals(Retcode.SUCC,daily.actor.getMissionManager().finishTalk(FIRST,"Mission_"+FIRST)); pay(121140104);
  }
  @Test void original3000602And3000603RemainSeparateValidControls() throws Exception {
    daily.catalog(3000602,3000603); assertTrue(DailyMissionRepeats.supportedGraph(3000602)); assertTrue(DailyMissionRepeats.supportedGraph(3000603));
    assertFalse(DailyMissionRepeats.supportedGraph(MAIN));
  }
}
