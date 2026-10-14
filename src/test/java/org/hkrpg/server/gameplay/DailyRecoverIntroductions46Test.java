package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityNpc;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The three original introductions behind the recovered NPC menus. No client renderer is run. */
class DailyRecoverIntroductions46Test {
  final DailyMissionRecoverPerformance46Test p=new DailyMissionRecoverPerformance46Test();
  final DailyMissionRecoverDelivery46Test f=p.f;
  @TempDir Path root;
  JsonObject source;
  static final int[] IDS={501028201,501028001,501030700};
  @BeforeAll static void database()throws Exception{DailyMissionRecoverDelivery46Test.isolateRealItemWrites();}
  @AfterAll static void close()throws Exception{DailyMissionRecoverDelivery46Test.closeOwnedDatabase();}
  @BeforeEach void setup()throws Exception{
    p.root=root;p.setup();
    try(var input=getClass().getResourceAsStream("/fixtures/daily-recover-introductions-4.6.json")){
      source=JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
    }
    for(var entry:source.getAsJsonObject("sourceFiles").entrySet()){
      var metadata=entry.getValue().getAsJsonObject();var bytes=Base64.getDecoder().decode(metadata.get("sourceBlobBase64").getAsString());
      assertEquals(metadata.get("sourceBlobSHA256").getAsString(),DailyMissionRecoverDelivery46Test.sourceSHA(bytes));
      var path=root.resolve(entry.getKey());Files.createDirectories(path.getParent());Files.write(path,bytes);
    }
    var path=root.resolve("ExcelOutput/PerformanceE.json");var rows=JsonParser.parseString(Files.readString(path)).getAsJsonArray();
    source.getAsJsonArray("performances").forEach(rows::add);Files.writeString(path,rows.toString());
  }
  @AfterEach void cleanup()throws Exception{p.cleanup();}
  void floor(int index){f.floor(index==0?10102:index==1?20121:20123,index==0?10102001:index==1?20121001:20123001);}
  int finish(int id){return (int)f.daily.field(f.daily.query("FinishFirstTalkByPerformanceNpcCsReq",Map.of("performance_id",id)),"retcode");}
  boolean seen(int id){
    var response=f.daily.query("GetFirstTalkByPerformanceNpcCsReq",Map.of("performance_id_list",List.of(id)));
    @SuppressWarnings("unchecked") var rows=(List<com.google.protobuf.DynamicMessage>)f.daily.field(response,"npc_meet_status_list");
    assertEquals(1,rows.size());return (boolean)f.daily.field(rows.getFirst(),"is_meet");
  }
  @ParameterizedTest @ValueSource(ints={0,1,2})
  void originalLiveNpcIntroductionIsRememberedWithoutCompletingOrRewardingTheDaily(int index)throws Exception{
    floor(index);int id=IDS[index];assertFalse(seen(id));var before=f.daily.gson.toJson(f.daily.actor.getGameplay().getMainMissions());
    assertEquals(0,p.start(id,4));assertNotEquals(0,p.start(id+1,4));
    assertEquals(0,finish(id));assertTrue(seen(id));assertEquals(before,f.daily.gson.toJson(f.daily.actor.getGameplay().getMainMissions()));assertEquals(0,f.daily.actor.getScoin());
    assertEquals(0,p.start(id+1,4));
    int saves=f.daily.actor.saves;assertEquals(0,finish(id));assertEquals(saves,f.daily.actor.saves);
    var stored=new Player();DailyMissionAcceptance46Test.set(stored,"uid",f.daily.actor.getUid());DailyMissionAcceptance46Test.set(stored,"gameplay",f.daily.actor.getGameplay());
    f.database.getDatastore().save(stored);var restored=f.database.getDatastore().find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",f.daily.actor.getUid())).first();assertNotNull(restored);
    f.daily.actor=f.daily.fresh();DailyMissionAcceptance46Test.set(f.daily.actor,"gameplay",restored.getGameplay());f.daily.clock(DailyMissionMessage46Test.NEXT);floor(index);
    assertTrue(seen(id));assertEquals(0,finish(id));assertEquals(0,f.daily.actor.getScoin());
  }
  @Test void missingLiveNpcAndAnotherFloorCannotReportAnIntroduction(){
    floor(0);assertNotEquals(0,finish(IDS[1]));var actors=f.daily.actor.getScene().getEntitiesByGroup(EntityNpc.class,99);assertEquals(1,actors.size());
    actors.forEach(f.daily.actor.getScene()::removeEntity);assertNotEquals(0,finish(IDS[0]));assertFalse(seen(IDS[0]));
  }
  @Test void changedOriginalIntroOrPerformanceRowCannotBeReported()throws Exception{
    floor(1);var row=source.getAsJsonArray("performances").get(0).getAsJsonObject();int id=row.get("PerformanceID").getAsInt();assertEquals(IDS[1],id);
    var path=root.resolve(row.get("PerformancePath").getAsString());Files.writeString(path,Files.readString(path).replace("EndPerformance","ForeignEffect"));
    assertNotEquals(0,finish(id));assertFalse(seen(id));
  }
  @Test void changedPerformanceRowAndMissingRecoveredMenuCannotAuthorizeTheIntro()throws Exception{
    floor(0);var table=root.resolve("ExcelOutput/PerformanceE.json");var bytes=Files.readAllBytes(table);var rows=JsonParser.parseString(Files.readString(table)).getAsJsonArray();
    for(var raw:rows){var row=raw.getAsJsonObject();if(row.get("PerformanceID").getAsInt()==IDS[0])row.addProperty("IsIntroDialogue",false);}
    Files.writeString(table,rows.toString());assertNotEquals(0,finish(IDS[0]));Files.write(table,bytes);
    Files.delete(root.resolve("Config/Level/NPCDialogue/P10102/F10102001_G99/DialogueMain_F10102001_G99_N400002.json"));assertNotEquals(0,finish(IDS[0]));assertFalse(seen(IDS[0]));
  }
  @Test void duplicateWireIdentityDoesNotRecordAMeeting()throws Exception{
    floor(0);var session=f.daily.session();var type=session.getProtocol().clientSchema().message("FinishFirstTalkByPerformanceNpcCsReq");var bytes=new java.io.ByteArrayOutputStream();var coded=com.google.protobuf.CodedOutputStream.newInstance(bytes);
    int tag=type.findFieldByName("performance_id").getNumber();coded.writeUInt32(tag,IDS[0]);coded.writeUInt32(tag,IDS[0]);coded.flush();f.daily.packets.clear();
    assertTrue(org.hkrpg.server.protocol.VersionedPacketHandler.handle(session,session.getProtocol().clientCommand(type.getName()),bytes.toByteArray()));
    assertNotEquals(0,f.daily.field(f.daily.packets.getLast().getValue(),"retcode"));assertFalse(seen(IDS[0]));
  }
  @Test void copiedUidAndExplicitNullHistoryCannotBecomeAMeeting()throws Exception{
    floor(0);assertEquals(0,finish(IDS[0]));var state=f.daily.actor.getGameplay().getDailyRecoverIntroductions();var json=f.daily.gson.toJsonTree(state).getAsJsonObject();
    json.getAsJsonObject("records").getAsJsonObject(String.valueOf(IDS[0])).addProperty("uid",f.daily.actor.getUid()+1);
    f.daily.actor.getGameplay().dailyRecoverIntroductions(f.daily.gson.fromJson(json,DailyRecoverIntroductionState.class));assertFalse(seen(IDS[0]));assertNotEquals(0,finish(IDS[0]));
    DailyMissionAcceptance46Test.set(f.daily.actor.getGameplay(),"dailyRecoverIntroductions",null);assertFalse(seen(IDS[0]));assertNotEquals(0,finish(IDS[0]));
  }
  @Test void rejectedSaveDoesNotMarkTheNpcAsMet(){
    floor(0);f.daily.actor.failSave=true;assertNotEquals(0,finish(IDS[0]));assertFalse(seen(IDS[0]));f.daily.actor.failSave=false;
    assertEquals(0,finish(IDS[0]));assertTrue(seen(IDS[0]));
  }
  @Test void originalNpcIntroductionIsIndependentOfACancelledDaily(){
    f.daily.actor.getGameplay().getCancelledMissions().add(3011014);floor(0);
    assertEquals(0,p.start(IDS[0],4));assertEquals(0,finish(IDS[0]));assertTrue(seen(IDS[0]));
    assertNull(f.daily.actor.getMissionManager().sub(301101401));assertEquals(0,f.daily.actor.getScoin());
  }
}
