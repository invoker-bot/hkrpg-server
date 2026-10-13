package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.EntityNpc;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original E-performance relay, real scene actors, and saved Player state.
 * This protocol test does not execute the official client renderer. */
class DailyMissionRecoverPerformance46Test {
  final DailyMissionRecoverDelivery46Test f=new DailyMissionRecoverDelivery46Test();
  @TempDir Path root;
  @BeforeAll static void database()throws Exception{DailyMissionRecoverDelivery46Test.isolateRealItemWrites();}
  @AfterAll static void close()throws Exception{DailyMissionRecoverDelivery46Test.closeOwnedDatabase();}
  @BeforeEach void setup()throws Exception{
    f.root=root;f.setup();
    for(var e:DailyMissionRecoverDelivery46Test.SourceHolder.CONFIG_BYTES.entrySet()){
      var p=root.resolve(e.getKey());Files.createDirectories(p.getParent());Files.write(p,e.getValue());
    }
    try(var input=getClass().getResourceAsStream("/fixtures/daily-recover-performance-entrances-4.6.json")){
      var extra=JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
      for(var e:extra.getAsJsonObject("sourceFiles").entrySet()){
        var metadata=e.getValue().getAsJsonObject();var bytes=Base64.getDecoder().decode(metadata.get("sourceBlobBase64").getAsString());
        assertEquals(metadata.get("sourceBlobSHA256").getAsString(),DailyMissionRecoverDelivery46Test.sourceSHA(bytes));
        var path=root.resolve(e.getKey());Files.createDirectories(path.getParent());Files.write(path,bytes);
      }
    }
  }
  @AfterEach void cleanup()throws Exception{f.cleanup();}
  int start(int id,int type){return (int)f.daily.field(f.daily.query("StarPerformanceRelayCsReq",Map.of("performance_id",id,"performance_type",type)),"retcode");}
  List<EntityNpc> actors(int group,int inst){return f.daily.actor.getScene().getEntitiesByGroup(EntityNpc.class,group).stream().filter(n->n.getInstId()==inst).toList();}
  @Test void originalInitialRelayAcknowledgesWithoutCompletingTheMission(){
    f.accept();assertEquals(0,start(301101401,4));
    assertEquals(QuestState.Phase.DOING,f.daily.actor.getMissionManager().sub(301101401).getPhase());
    assertEquals(0,f.daily.actor.getScoin());assertEquals(0,f.report(301101401));
  }
  @Test void originalMenuRelayCreatesOnlyTheAuthoredTemporaryNpcAndEndsItAfterTheReport(){
    f.first();assertTrue(actors(122,400001).isEmpty());assertEquals(0,start(301101402,4));
    var actor=actors(122,400001);assertEquals(1,actor.size());assertEquals(11302,actor.getFirst().getNpcId());
    int saves=f.daily.actor.saves;assertEquals(0,start(301101402,4));assertSame(actor.getFirst(),actors(122,400001).getFirst());assertEquals(saves,f.daily.actor.saves);
    assertEquals(0,f.report(301101403));assertEquals(0,f.report(301101402));assertTrue(actors(122,400001).isEmpty());
  }
  @ParameterizedTest @ValueSource(ints={0,1,2})
  void eachOriginalRecipientAndFeedbackRelayCreatesItsOwnPlacementAndCleansUp(int branch){
    f.choose(branch);assertEquals(0,f.report(301101402));f.floor(f.PLANES[branch],f.FLOORS[branch]);
    int group=new int[]{94,75,23}[branch],inst=branch==2?400002:400001;
    assertTrue(actors(group,inst).isEmpty());assertEquals(0,start(301101406+branch,4));assertEquals(1,actors(group,inst).size());
    assertEquals(0,f.consume(301101406+branch,Map.of(f.ITEMS[branch],1)));assertTrue(actors(group,inst).isEmpty());
    assertEquals(0,start(301101409+branch,4));assertEquals(1,actors(group,inst).size());
    assertEquals(0,f.report(301101409+branch));assertTrue(actors(group,inst).isEmpty());assertEquals(5000,f.daily.actor.getScoin());
  }
  @Test void rejectedRelaySavePublishesNoActorAndRestoresAcceptanceAndGameplay(){
    f.first();var before=f.daily.gson.toJson(f.daily.actor.getGameplay());f.daily.packets.clear();f.daily.actor.failSave=true;
    var failure=assertThrows(AssertionError.class,()->start(301101402,4));assertInstanceOf(IllegalStateException.class,failure.getCause());assertEquals(before,f.daily.gson.toJson(f.daily.actor.getGameplay()));assertTrue(actors(122,400001).isEmpty());assertTrue(f.daily.packets.isEmpty());
    f.daily.actor.failSave=false;assertEquals(0,start(301101402,4));assertEquals(1,actors(122,400001).size());
  }
  @Test void anActiveMenuRelayRestoresAfterARealPlayerDocumentRead()throws Exception{
    f.first();assertEquals(0,start(301101402,4));var store=f.database.getDatastore();
    var player=new emu.lunarcore.game.player.Player();DailyMissionAcceptance46Test.set(player,"uid",f.daily.actor.getUid());DailyMissionAcceptance46Test.set(player,"gameplay",f.daily.actor.getGameplay());store.save(player);
    var restored=store.find(emu.lunarcore.game.player.Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",f.daily.actor.getUid())).first();assertNotNull(restored);
    f.daily.actor=f.daily.fresh();DailyMissionAcceptance46Test.set(f.daily.actor,"gameplay",restored.getGameplay());f.daily.clock(DailyMissionMessage46Test.TODAY);f.floor(10102,10102001);
    assertEquals(1,actors(122,400001).size());assertEquals(0,start(301101402,4));assertEquals(1,actors(122,400001).size());assertEquals(0,f.report(301101402));assertTrue(actors(122,400001).isEmpty());
  }
  @Test void oldDayAndReplacedAcceptanceCannotRestoreAnActor()throws Exception{
    f.first();assertEquals(0,start(301101402,4));var original=f.daily.actor.getMissionManager().sub(301101402);
    var replaced=f.daily.gson.fromJson(f.daily.gson.toJson(original),QuestState.class);DailyMissionAcceptance46Test.set(replaced,"observationKey",UUID.randomUUID().toString());f.daily.actor.getGameplay().getSubMissions().put(301101402,replaced);
    f.floor(10102,10102001);assertTrue(actors(122,400001).isEmpty());assertNotEquals(0,start(301101402,4));
    f.daily.actor.getGameplay().getSubMissions().put(301101402,original);f.daily.clock(DailyMissionMessage46Test.NEXT);f.floor(10102,10102001);assertTrue(actors(122,400001).isEmpty());assertNotEquals(0,start(301101402,4));
  }
  @Test void foreignPerformanceTypeSceneAndUnacceptedGraphAreRejected(){
    f.accept();var before=f.daily.gson.toJson(f.daily.actor.getGameplay());assertNotEquals(0,start(301101402,4));assertNotEquals(0,start(301101401,1));assertNotEquals(0,start(301101403,4));assertEquals(before,f.daily.gson.toJson(f.daily.actor.getGameplay()));
    f.first();f.floor(20121,20121001);assertNotEquals(0,start(301101402,4));
  }
  @Test void missingOrChangedOriginalNpcEntranceCannotStartThePerformance()throws Exception{
    f.first();var path=root.resolve("Config/Level/NPCDialogue/P10102/F10102001_G99/DialogueMain_F10102001_G99_N400002.json");var bytes=Files.readAllBytes(path);Files.delete(path);assertNotEquals(0,start(301101402,4));
    var changed=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();changed.addProperty("ForeignGraph",true);Files.writeString(path,changed.toString());assertNotEquals(0,start(301101402,4));assertTrue(actors(122,400001).isEmpty());
  }
  @Test void aRejectedMenuCompletionPreservesTheSavedStartAndTheLiveActor(){
    f.first();assertEquals(0,start(301101402,4));var actor=actors(122,400001).getFirst();var before=f.daily.gson.toJson(f.daily.actor.getGameplay());f.daily.actor.failSave=true;
    var failure=assertThrows(AssertionError.class,()->f.report(301101402));assertInstanceOf(IllegalStateException.class,failure.getCause());assertSame(actor,actors(122,400001).getFirst());assertEquals(before,f.daily.gson.toJson(f.daily.actor.getGameplay()));
    f.daily.actor.failSave=false;assertEquals(0,f.report(301101402));assertTrue(actors(122,400001).isEmpty());
  }
  @Test void aChangedStartedAcceptanceCannotBorrowTheLegacyMenuReport()throws Exception{
    f.first();assertEquals(0,start(301101402,4));var quest=f.daily.actor.getMissionManager().sub(301101402);
    DailyMissionAcceptance46Test.set(quest,"observationKey",UUID.randomUUID().toString());assertNotEquals(0,f.report(301101402));assertEquals(QuestState.Phase.DOING,quest.getPhase());
  }
  @Test void aMissingOriginalDialogueItemCannotAuthorizeAnAlreadyStartedReport()throws Exception{
    f.first();assertEquals(0,start(301101402,4));Files.delete(root.resolve("Config/Level/NPCDialogue/P20123/F20123001_G76/DialogueItem301101101_F20123001_G76_N400001.json"));
    assertNotEquals(0,f.report(301101402));f.floor(10102,10102001);assertTrue(actors(122,400001).isEmpty());
  }
  @Test void duplicateWireIdentityIsRejectedWithoutSavingOrPublishingAnActor()throws Exception{
    f.first();var before=f.daily.gson.toJson(f.daily.actor.getGameplay());var bytes=new java.io.ByteArrayOutputStream();var coded=com.google.protobuf.CodedOutputStream.newInstance(bytes);
    coded.writeUInt32(1,301101402);coded.writeUInt32(1,301101402);coded.writeUInt32(12,4);coded.flush();var session=f.daily.session();f.daily.packets.clear();
    assertTrue(org.hkrpg.server.protocol.VersionedPacketHandler.handle(session,1224,bytes.toByteArray()));assertNotEquals(0,f.daily.field(f.daily.packets.getLast().getValue(),"retcode"));assertEquals(before,f.daily.gson.toJson(f.daily.actor.getGameplay()));assertTrue(actors(122,400001).isEmpty());
  }
}
