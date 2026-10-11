package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.*;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original report-side NPC lifecycle, never a claim of client CustomString/Act execution. */
class DailyMissionInitialNpcLifecycle46Test {
  final DailyMissionMessageOrigin46Test origin=new DailyMissionMessageOrigin46Test();
  @TempDir Path root;
  static class RecordingActor extends DailyMissionMessage46Test.Actor {
    @dev.morphia.annotations.Transient final List<emu.lunarcore.server.packet.BasePacket> emitted=new ArrayList<>();
    RecordingActor(){super();}
    RecordingActor(Gson gson,java.util.function.Supplier<emu.lunarcore.server.game.GameSession> session){super(gson,session);}
    @Override public void sendPacket(emu.lunarcore.server.packet.BasePacket packet){if(emitted!=null)emitted.add(packet);}
  }
  RecordingActor recording;
  @BeforeEach void setup() throws Exception {
    origin.root=root;origin.setup();
    try(var input=getClass().getResourceAsStream("/fixtures/daily-origin-effects-4.6.json")){
      var fixture=JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
      assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b",fixture.get("sourceCommit").getAsString());
      assertEquals(6,fixture.getAsJsonObject("sourceFiles").size());assertEquals(6,fixture.getAsJsonArray("records").size());
      for(var entry:fixture.getAsJsonObject("sourceFiles").entrySet()){
        var data=entry.getValue().getAsJsonObject();if(!data.has("sourceBlobBase64"))continue;
        byte[] raw=Base64.getDecoder().decode(data.get("sourceBlobBase64").getAsString());
        assertEquals(data.get("sourceBlobSHA256").getAsString(),java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw)));
        var target=root.resolve(entry.getKey());Files.createDirectories(target.getParent());Files.write(target,raw);
      }
      var perf=fixture.getAsJsonArray("records").asList().stream().map(JsonElement::getAsJsonObject).filter(row->row.get("kind").getAsString().equals("PerformanceE")).findFirst().orElseThrow().getAsJsonObject("row");
      var table=JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/PerformanceE.json"))).getAsJsonArray();
      var existing=table.asList().stream().map(JsonElement::getAsJsonObject).filter(row->row.get("PerformanceID").getAsInt()==300060001).toList();
      assertTrue(existing.size()<=1);if(existing.isEmpty())table.add(perf.deepCopy());else assertEquals(perf,existing.getFirst());
      origin.g61.write("ExcelOutput/PerformanceE.json",table);
    }
    var previous=origin.daily.actor;recording=new RecordingActor(origin.gson,()->origin.daily.wire);
    DailyMissionAcceptance46Test.set(recording,"uid",previous.getUid());DailyMissionAcceptance46Test.set(recording,"gameplay",previous.getGameplay());
    DailyMissionAcceptance46Test.set(recording,"scoin",previous.getScoin());origin.daily.actor=recording;origin.daily.clock(DailyMissionMessage46Test.TODAY);
  }
  @AfterEach void cleanup() throws Exception { origin.cleanup(); }
  List<EntityNpc> npcs(){return origin.daily.actor.getScene().getEntitiesByGroup(EntityNpc.class,84);}
  void exactSourceActors(){
    assertEquals(Set.of(400001,400002),new HashSet<>(npcs().stream().map(EntityNpc::getInstId).toList()));
    assertEquals(Set.of(10209,10215),new HashSet<>(npcs().stream().map(EntityNpc::getNpcId).toList()));
  }
  void removalWithPropsRetained(){
    assertEquals(0,npcs().size(),"The complete original Act ends by destroying both G84 NPC instances before its original finish report");
    assertEquals(Set.of(300001,300002,300003),new HashSet<>(origin.daily.actor.getScene().getEntitiesByGroup(EntityProp.class,84).stream().map(EntityProp::getInstId).toList()),
        "The source removes two NPCs, not the whole mission group or its three props");
    assertTrue(MissionStateRecords.subCompleted(origin.daily.actor,300060001));
    assertTrue(MissionStateRecords.doing(origin.sub(300060101),300060101,1));
    assertNull(origin.sub(300060102));assertNull(origin.daily.phone(1211400));assertEquals(0,origin.daily.actor.getScoin());
  }
  @Test void bothOriginalOptionBranchesMergeIntoTheSameTwoNpcDestructions() throws Exception {
    var act=JsonParser.parseString(Files.readString(root.resolve("Config/Level/Mission/3000600/Act/Act300060001.json"))).getAsJsonObject();
    var seq=act.getAsJsonArray("OnStartSequece");assertEquals(5,seq.size());
    var options=seq.get(0).getAsJsonObject().getAsJsonArray("TaskList").get(2).getAsJsonObject().getAsJsonArray("OptionList");
    assertEquals(List.of("TalkSentence_300060007","TalkSentence_300060009"),options.asList().stream().map(JsonElement::getAsJsonObject).map(row->row.get("TriggerCustomString").getAsString()).toList());
    for(int i=1;i<=2;i++){
      var tasks=seq.get(i).getAsJsonObject().getAsJsonArray("TaskList");
      assertEquals("TalkSentence_300060010",tasks.get(4).getAsJsonObject().getAsJsonObject("CustomString").get("Value").getAsString());
    }
    var finalTasks=seq.get(3).getAsJsonObject().getAsJsonArray("TaskList");
    var transition=finalTasks.get(2).getAsJsonObject();
    assertEquals("RPG.GameCore.PerformanceTransition",transition.get("$type").getAsString());
    assertEquals(Set.of("84:400001","84:400002"),new HashSet<>(transition.getAsJsonArray("DestroyNpcList").asList().stream().map(JsonElement::getAsJsonObject)
        .map(row->row.get("GroupID").getAsInt()+":"+row.get("GroupNpcID").getAsInt()).toList()));
    assertTrue(transition.getAsJsonArray("CreateNpcList").isEmpty());assertTrue(transition.getAsJsonArray("CaptureNpcList").isEmpty());
    assertEquals("RPG.GameCore.EndPerformance",finalTasks.get(3).getAsJsonObject().get("$type").getAsString());
  }
  @Test void theActualOriginalFinishRequestRemovesOnlyItsTwoNpcInstances(){
    origin.accept();exactSourceActors();
    var response=origin.daily.query("FinishTalkMissionCsReq",Map.of("sub_mission_id",300060001,"talk_str","Mission_300060001"));
    assertEquals(Retcode.SUCC.getVal(),origin.daily.field(response,"retcode"));removalWithPropsRetained();
  }
  @Test void aRealSceneReentryDoesNotResurrectTheAcceptedReportEffects(){
    origin.accept();origin.initial();origin.g61.floor();removalWithPropsRetained();
  }
  @Test void twoRealPlayerDocumentReadsMustRetainTheSourceActorRemoval() throws Exception {
    origin.accept();origin.initial();var server=new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());var address=server.bind();
    try(var client=com.mongodb.client.MongoClients.create("mongodb://"+address.getHostString()+":"+address.getPort())){
      var store=dev.morphia.Morphia.createDatastore(client,"daily_initial_npc_draft");store.getMapper().map(Player.class,QuestState.class);
      for(int iteration=0;iteration<2;iteration++){
        var base=new Player();DailyMissionAcceptance46Test.set(base,"uid",origin.daily.actor.getUid());DailyMissionAcceptance46Test.set(base,"gameplay",origin.daily.actor.getGameplay());store.save(base);
        var restored=store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",origin.daily.actor.getUid())).first();assertNotNull(restored);
        origin.daily.actor=origin.daily.fresh();DailyMissionAcceptance46Test.set(origin.daily.actor,"gameplay",restored.getGameplay());origin.daily.clock(DailyMissionMessage46Test.TODAY);origin.g61.floor();
        removalWithPropsRetained();
      }
    }finally{server.shutdownNow();}
  }
  @Test void genericProp100MutationCannotRemoveActorsOrFinishTheOriginalInitialGraph(){
    origin.accept();var prop=origin.daily.actor.getScene().getEntitiesByGroup(EntityProp.class,84).getFirst();
    var next=prop.getExcel().getPropStateList().stream().filter(state->state!=prop.getState()).findFirst().orElseThrow();
    assertTrue(prop.setState(next,false));origin.daily.actor.getMissionManager().refresh();exactSourceActors();
    assertEquals(0,origin.sub(300060001).getProgress());assertNull(origin.sub(300060101));
  }
  @Test void nextAcceptedDayMustRespawnBothOriginalActorsForItsOwnInitialStage(){
    origin.open();origin.pay(121140102);assertTrue(npcs().isEmpty());
    origin.daily.clock(DailyMissionMessage46Test.NEXT);origin.daily.actor.getDailyManager().ensureDay();origin.daily.actor.getMissionManager().refresh();
    exactSourceActors();assertEquals(0,origin.sub(300060001).getProgress());assertNull(origin.sub(300060101));
  }
  @Test void aWrongReportKeyDoesNotRemoveAnyNpc(){
    origin.accept();assertEquals(Retcode.MISSION_FINISH_WAY_NOT_MATCH,origin.daily.actor.getMissionManager().finishTalk(300060001,"Mission_300060101"));
    exactSourceActors();assertEquals(0,origin.sub(300060001).getProgress());assertNull(origin.sub(300060101));
  }
  @ParameterizedTest @ValueSource(booleans={false,true})
  void initialReportSaveFailureLeavesItsRealStageAndActorsRetryable(boolean observed){
    origin.accept();var accepted=origin.sub(300060001);var main=origin.daily.actor.getGameplay().getMainMissions().get(3000601);
    if(observed){accepted.captureObservationKey();main.captureObservationKey();}
    var oldNpcs=List.copyOf(npcs());var entityIds=oldNpcs.stream().map(EntityNpc::getEntityId).toList();
    String before=origin.saved(),durable=recording.durableGameplay;int saves=recording.saves,credit=recording.getScoin(),points=recording.getGameplay().getDailyPoints();
    var mainKey=main.getObservationKey();var subKey=accepted.getObservationKey();int emitted=recording.emitted.size(),wire=origin.daily.packets.size();
    recording.failSave=true;assertThrows(IllegalStateException.class,origin::initial);recording.failSave=false;
    assertSame(accepted,origin.sub(300060001));assertSame(main,recording.getGameplay().getMainMissions().get(3000601));
    assertEquals(before,origin.saved(),"A failed original report write must not publish completion or source effects");
    assertEquals(durable,recording.durableGameplay);assertEquals(saves,recording.saves);
    assertEquals(credit,recording.getScoin());assertEquals(points,recording.getGameplay().getDailyPoints());
    assertEquals(mainKey,main.getObservationKey());assertEquals(subKey,accepted.getObservationKey());
    assertEquals(QuestState.Phase.DOING,accepted.getPhase());assertEquals(0,accepted.getProgress());assertEquals(0,accepted.getFinishTime());
    assertEquals(oldNpcs,npcs());assertEquals(entityIds,oldNpcs.stream().map(EntityNpc::getEntityId).toList());
    assertEquals(emitted,recording.emitted.size(),"Failed save cannot publish NPC removal or any other packet");assertEquals(wire,origin.daily.packets.size());
    exactSourceActors();assertNull(origin.sub(300060101));assertNull(origin.sub(300060102));
    origin.initial();removalWithPropsRetained();
  }
  @ParameterizedTest @ValueSource(strings={"actMissing","oneNpc","foreignNpc","endMissing","performancePath"})
  void anUnreviewedWholeActOrPerformanceBindingCannotCompleteTheInitialReport(String damage) throws Exception {
    origin.accept();var path=root.resolve("Config/Level/Mission/3000600/Act/Act300060001.json");
    if(damage.equals("actMissing"))Files.delete(path);
    else if(damage.equals("performancePath")){
      var table=JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/PerformanceE.json"))).getAsJsonArray();
      var row=table.asList().stream().map(JsonElement::getAsJsonObject).filter(value->value.get("PerformanceID").getAsInt()==300060001).findFirst().orElseThrow();
      row.addProperty("PerformancePath","Config/Level/Mission/3000602/Act/Act300060201.json");origin.g61.write("ExcelOutput/PerformanceE.json",table);
    }else{
      var act=JsonParser.parseString(Files.readString(path)).getAsJsonObject();var tasks=act.getAsJsonArray("OnStartSequece").get(3).getAsJsonObject().getAsJsonArray("TaskList");
      if(damage.equals("oneNpc"))tasks.get(2).getAsJsonObject().getAsJsonArray("DestroyNpcList").remove(1);
      else if(damage.equals("foreignNpc"))tasks.get(2).getAsJsonObject().getAsJsonArray("DestroyNpcList").get(0).getAsJsonObject().addProperty("GroupID",61);
      else tasks.remove(3);
      Files.writeString(path,act.toString());
    }
    String before=origin.saved();int packets=recording.emitted.size(),saves=recording.saves;
    assertEquals(Retcode.MISSION_FINISH_WAY_NOT_MATCH,recording.getMissionManager().finishTalk(300060001,"Mission_300060001"));
    assertEquals(before,origin.saved());assertEquals(saves,recording.saves);assertEquals(packets,recording.emitted.size());exactSourceActors();
  }
  @Test void duplicateOriginalReportDoesNotReplaceItsSavedEventOrRepublishRemovals(){
    origin.accept();origin.initial();String before=origin.saved();int packets=recording.emitted.size(),saves=recording.saves;
    assertEquals(Retcode.MISSION_NOT_DOING,recording.getMissionManager().finishTalk(300060001,"Mission_300060001"));
    assertEquals(before,origin.saved());assertEquals(packets,recording.emitted.size());assertEquals(saves,recording.saves);removalWithPropsRetained();
  }
  @Test void aCompletedOldStageWithoutAnActualEffectReceiptCannotInventNpcRemoval(){
    origin.accept();origin.initial();recording.getGameplay().getDailyOriginEffects().getRecords().clear();
    String before=origin.saved();int saves=recording.saves;
    var restored=new emu.lunarcore.game.scene.Scene(recording,emu.lunarcore.data.GameData.getMazePlaneExcelMap().get(20002),20002001);
    assertEquals(2,restored.getEntitiesByGroup(EntityNpc.class,84).size());assertEquals(before,origin.saved());assertEquals(saves,recording.saves);
  }
  @ParameterizedTest @ValueSource(strings={"mainAcceptance","initialAcceptance","day","uid","source"})
  void aForeignEffectReceiptCannotSuppressSourceActorsOrSaveDuringSceneConstruction(String field) throws Exception {
    origin.accept();origin.initial();var receipt=recording.getGameplay().getDailyOriginEffects().getRecords().values().iterator().next();
    Object value=switch(field){case "day"->receipt.getDay()+1;case "uid"->receipt.getUid()+1;case "source"->"foreign-original";default->UUID.randomUUID().toString();};
    DailyMissionAcceptance46Test.set(receipt,field,value);String before=origin.saved();int saves=recording.saves;
    var rebuilt=new emu.lunarcore.game.scene.Scene(recording,emu.lunarcore.data.GameData.getMazePlaneExcelMap().get(20002),20002001);
    assertEquals(2,rebuilt.getEntitiesByGroup(EntityNpc.class,84).size());assertEquals(before,origin.saved());assertEquals(saves,recording.saves);
  }
  @Test void changingTheWholeOriginalActAfterCommitDoesNotAuthorizeOldSuppression() throws Exception {
    origin.accept();origin.initial();var path=root.resolve("Config/Level/Mission/3000600/Act/Act300060001.json");
    var act=JsonParser.parseString(Files.readString(path)).getAsJsonObject();act.getAsJsonArray("OnStartSequece").remove(4);Files.writeString(path,act.toString());
    String before=origin.saved();int saves=recording.saves;
    var rebuilt=new emu.lunarcore.game.scene.Scene(recording,emu.lunarcore.data.GameData.getMazePlaneExcelMap().get(20002),20002001);
    assertEquals(2,rebuilt.getEntitiesByGroup(EntityNpc.class,84).size());assertEquals(before,origin.saved());assertEquals(saves,recording.saves);
  }
  @Test void anObservedAcceptanceKeepsItsUuidAndPublishesOnlyAfterItsReceiptIsDurable(){
    origin.accept();var accepted=origin.sub(300060001);var key=accepted.captureObservationKey();var main=recording.getGameplay().getMainMissions().get(3000601);var mainKey=main.captureObservationKey();
    var oldIds=new HashSet<>(npcs().stream().map(EntityNpc::getEntityId).toList());recording.emitted.clear();origin.initial();
    assertEquals(key,origin.sub(300060001).getObservationKey());assertSame(main,recording.getGameplay().getMainMissions().get(3000601));assertEquals(mainKey,main.getObservationKey());
    var receipt=recording.getGameplay().getDailyOriginEffects().getRecords().values().iterator().next();
    assertEquals(key,receipt.getInitialAcceptance());assertEquals(mainKey,receipt.getMainAcceptance());
    assertEquals(oldIds,new HashSet<>(receipt.getActors().values().stream().map(DailyOriginEffectsState.Actor::getEntity).toList()));
    assertTrue(recording.durableGameplay.contains(receipt.getEvent()));
    var removals=recording.emitted.stream().filter(packet->packet instanceof emu.lunarcore.server.packet.send.PacketSceneGroupRefreshScNotify).toList();
    assertEquals(2,removals.size());removalWithPropsRetained();
  }
  void completeOriginalPeriod(boolean pendingReward){
    origin.open();
    if(pendingReward){
      origin.reply(121140102);
      // A real pending reward receipt holds FINISHED without inventing mission completion.
      // All three original stages still complete through the existing Talk/Phone APIs.
      recording.getGameplay().getRewards().put(DailyMissionRepeats.mainKey(recording,3000601),new GameplayState.Receipt(231));
      recording.save();
      assertEquals(Retcode.SUCC,origin.messages().finishSection(1211400));
      assertEquals(0,recording.getScoin());
      assertEquals(Retcode.SUCC,recording.getDailyManager().claimQuest(2100003).retcode());
    }else origin.pay(121140102);
    var main=recording.getGameplay().getMainMissions().get(3000601);
    assertEquals(pendingReward?QuestState.Phase.FINISHED:QuestState.Phase.CLOSED,main.getPhase());
    assertEquals(1,main.getProgress());assertEquals(DailyMissionMessage46Test.TODAY.getEpochSecond(),main.getFinishTime());
    for(int id:List.of(300060001,300060101,300060102))assertTrue(MissionStateRecords.subCompleted(recording,id));
    assertTrue(origin.run().getPhone().isFinished());assertEquals(200,recording.getGameplay().getDailyPoints());
    assertTrue(npcs().isEmpty());
  }
  @ParameterizedTest @ValueSource(booleans={false,true})
  void theOriginalOwnerGateKeepsTheCompletedPeriodUnloadedDuringNormalSameDayReentry(boolean pendingReward){
    completeOriginalPeriod(pendingReward);var main=recording.getGameplay().getMainMissions().get(3000601);
    var receipt=recording.getGameplay().getDailyOriginEffects().getRecords().values().iterator().next();
    origin.g61.floor();
    assertSame(main,recording.getGameplay().getMainMissions().get(3000601));
    assertSame(receipt,recording.getGameplay().getDailyOriginEffects().getRecords().values().iterator().next());
    assertFalse(recording.getMissionManager().shouldLoadGroup(origin.group(),recording.getScene()));
    assertTrue(npcs().isEmpty());assertFalse(recording.getScene().getLoadedGroups().contains(84));
  }
  @ParameterizedTest @ValueSource(booleans={false,true})
  void theExistingExplicitGroupLoaderCannotResurrectCompletedSameDayReportActors(boolean pendingReward){
    completeOriginalPeriod(pendingReward);origin.g61.floor();
    String before=origin.saved();int saves=recording.saves,credit=recording.getScoin(),points=recording.getGameplay().getDailyPoints();
    // The public loader is an existing server API; this is not a claim of client reentry.
    recording.getScene().loadGroup(84);
    assertTrue(recording.getScene().getLoadedGroups().contains(84));
    assertEquals(0,npcs().size(),"A completed accepted-period report still owns its actor removals until the day changes");
    assertEquals(3,recording.getScene().getEntitiesByGroup(EntityProp.class,84).size());
    assertEquals(before,origin.saved());assertEquals(saves,recording.saves);assertEquals(credit,recording.getScoin());assertEquals(points,recording.getGameplay().getDailyPoints());
  }
  @ParameterizedTest @ValueSource(booleans={false,true})
  void completedPeriodEffectsSurviveTwoRealPlayerReadsAndRestoreActorsOnlyForANewAcceptance(boolean pendingReward) throws Exception {
    completeOriginalPeriod(pendingReward);var mainKey=recording.getGameplay().getMainMissions().get(3000601).getObservationKey();
    var event=recording.getGameplay().getDailyOriginEffects().getRecords().values().iterator().next().getEvent();
    var server=new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());var address=server.bind();
    try(var client=com.mongodb.client.MongoClients.create("mongodb://"+address.getHostString()+":"+address.getPort())){
      var store=dev.morphia.Morphia.createDatastore(client,"daily_initial_npc_completed_period");store.getMapper().map(Player.class,QuestState.class);
      for(int iteration=0;iteration<2;iteration++){
        var base=new Player();DailyMissionAcceptance46Test.set(base,"uid",origin.daily.actor.getUid());DailyMissionAcceptance46Test.set(base,"gameplay",origin.daily.actor.getGameplay());
        DailyMissionAcceptance46Test.set(base,"scoin",origin.daily.actor.getScoin());store.save(base);
        var restored=store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",origin.daily.actor.getUid())).first();assertNotNull(restored);
        recording=new RecordingActor(origin.gson,()->origin.daily.wire);DailyMissionAcceptance46Test.set(recording,"uid",restored.getUid());
        DailyMissionAcceptance46Test.set(recording,"gameplay",restored.getGameplay());DailyMissionAcceptance46Test.set(recording,"scoin",restored.getScoin());
        origin.daily.actor=recording;origin.daily.clock(DailyMissionMessage46Test.TODAY);origin.g61.floor();
        assertEquals(mainKey,recording.getGameplay().getMainMissions().get(3000601).getObservationKey());
        assertEquals(event,recording.getGameplay().getDailyOriginEffects().getRecords().values().iterator().next().getEvent());
        assertEquals(pendingReward?QuestState.Phase.FINISHED:QuestState.Phase.CLOSED,recording.getGameplay().getMainMissions().get(3000601).getPhase());
        assertTrue(npcs().isEmpty());String before=origin.saved();int saves=recording.saves;
        recording.getScene().loadGroup(84);
        assertEquals(0,npcs().size(),"Morphia must restore the receipt that suppresses an explicit same-day reload");
        assertEquals(before,origin.saved());assertEquals(saves,recording.saves);
      }
      if(!pendingReward){
        var oldMain=recording.getGameplay().getMainMissions().get(3000601);
        origin.daily.clock(DailyMissionMessage46Test.NEXT);recording.getDailyManager().ensureDay();recording.getMissionManager().refresh();
        exactSourceActors();assertNotSame(oldMain,recording.getGameplay().getMainMissions().get(3000601));
        assertEquals(0,origin.sub(300060001).getProgress());assertNull(origin.sub(300060101));assertNull(origin.sub(300060102));
        assertEquals(1,recording.getGameplay().getDailyOriginEffects().getRecords().size());
        assertEquals(event,recording.getGameplay().getDailyOriginEffects().getRecords().values().iterator().next().getEvent());
        assertEquals(5000,recording.getScoin());assertEquals(0,recording.getGameplay().getDailyPoints());
      }
    }finally{server.shutdownNow();}
  }
}
