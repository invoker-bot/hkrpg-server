package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;
import static org.hkrpg.server.protocol.ChessRogueMerchantEntry46Test.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.hkrpg.server.gameplay.ChessRogueRoutes;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Original source101 qualification with the existing server saved-record integrity policy. */
class ChessRogueQualificationIntegrity46Test {
  @TempDir Path root;
  ChessRogueMerchantEntry46Test f;
  @BeforeEach void setup() throws Exception {
    var candidate=new ChessRogueMerchantEntry46Test();
    verifyCompleteSource(candidate);
    f=candidate; f.root=root; f.setup(); assertTrue(ChessRogueRoutes.available());
  }
  @AfterEach void cleanup() throws Exception { if(f!=null) f.cleanup(); }
  private static String sha(byte[] raw) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
  }
  private static JsonObject proof(String resource,String expectedSHA) throws Exception {
    try (var input=ChessRogueQualificationIntegrity46Test.class.getResourceAsStream(resource)) {
      assertNotNull(input,"Complete fixed-source proof must exist before testing qualification");
      var raw=input.readAllBytes(); assertEquals(expectedSHA,sha(raw));
      return JsonParser.parseString(new String(raw,StandardCharsets.UTF_8)).getAsJsonObject();
    }
  }
  private static void verifyCompleteSource(ChessRogueMerchantEntry46Test candidate) throws Exception {
    var proof=proof("/fixtures/chess-rogue-qualification-integrity-4.6.json",
        "efadaca6362d229be85fe58d84e5ad63b7ae57c6371f8094695611c6e83ec999");
    var primary=proof("/fixtures/chess-rogue-initial-query-4.6.json",
        "c8499dd6035cdb7dfc4eb63d763b31556fd780c1fb40d580d38e16e33b15a703");
    assertEquals(COMMIT,proof.get("sourceCommit").getAsString());
    assertEquals(primary.get("descriptorSHA256"),proof.get("descriptorSHA256"));
    assertEquals(primary.get("clientFiles"),proof.get("clientFiles"));
    var originals=proof.getAsJsonArray("completeOriginalRows"); assertEquals(4,originals.size());
    for(int i=0;i<3;i++) {
      var source=originals.get(i).getAsJsonObject(); var row=source.getAsJsonObject("row");
      var path=source.get("path").getAsString();
      var table=path.substring("ExcelOutput/".length(),path.length()-".json".length());
      assertEquals(1,candidate.rows(table).asList().stream().filter(row::equals).count(),
          "The regression must use the complete original row, including unconsumed fields");
      var matching=primary.getAsJsonArray("completeSelectedSourceRows").asList().stream()
          .map(value -> value.getAsJsonObject()).filter(value -> value.get("path").getAsString().equals(path))
          .findFirst().orElseThrow();
      assertEquals(matching,source);
    }
    var area=originals.get(0).getAsJsonObject().getAsJsonObject("row");
    var unlock=originals.get(1).getAsJsonObject().getAsJsonObject("row");
    var finish=originals.get(2).getAsJsonObject().getAsJsonObject("row");
    var main=originals.get(3).getAsJsonObject().getAsJsonObject("row");
    assertEquals(101,area.get("AreaID").getAsInt()); assertEquals(1000020,area.get("UnlockID").getAsInt());
    assertEquals(109011,area.get("FirstReward").getAsInt());
    assertEquals(1000020,unlock.get("RogueUnlockID").getAsInt());
    assertEquals(1000020,unlock.get("UnlockFinishWay").getAsInt());
    assertEquals(1000020,finish.get("ID").getAsInt()); assertEquals("FinishMission",finish.get("FinishType").getAsString());
    assertEquals("ListContain",finish.get("ParamType").getAsString());
    assertEquals(JsonParser.parseString("[8013104]"),finish.get("ParamIntList"));
    assertEquals(1,finish.get("Progress").getAsInt()); assertEquals(8013104,main.get("MainMissionID").getAsInt());
    assertEquals(18013104,main.get("RewardID").getAsInt());
    var consumer=proof.getAsJsonObject("wholeNativeUnlockedConsumer");
    assertEquals(614962,consumer.getAsJsonObject("method").get("index").getAsInt());
    var raw=Base64.getDecoder().decode(consumer.get("rawBase64").getAsString());
    assertEquals(137,raw.length); assertEquals(consumer.get("byteCount").getAsInt(),raw.length);
    assertEquals("415f065221fb104f5a6ec512feeedd16803f78fbe1a4b430d0e3daffcd195ea4",sha(raw));
    assertEquals(consumer,primary.getAsJsonArray("wholeNativeFunctions").asList().stream()
        .map(value -> value.getAsJsonObject()).filter(value -> value.getAsJsonObject("method").get("index").getAsInt()==614962)
        .findFirst().orElseThrow());
    var policy=proof.getAsJsonObject("serverRecordIntegrityPolicy");
    assertEquals("existing MissionStateRecords.mainCompleted(player,8013104)",policy.get("rule").getAsString());
    assertFalse(policy.get("officialQuestRecordSerializationRecovered").getAsBoolean());
    assertFalse(policy.get("requiresFirstReward109011").getAsBoolean());
    assertFalse(policy.get("requiresQualificationPayoutReceipt").getAsBoolean());
    assertFalse(proof.get("clientGameplayVerified").getAsBoolean());
    assertFalse(proof.get("sourceInstalled").getAsBoolean()); assertFalse(proof.get("clientStarted").getAsBoolean());
  }
  static Stream<Arguments> qualifications() {
    return Stream.of(
        Arguments.of(null,8013104,1,1700000000L,false,false),
        Arguments.of("FINISHED",8013104,0,1700000000L,false,false),
        Arguments.of("FINISHED",8013104,1,0L,false,false),
        Arguments.of("CLOSED",8013104,0,1700000000L,false,false),
        Arguments.of("CLOSED",8013104,1,0L,false,false),
        Arguments.of("FINISHED",8013103,1,1700000000L,false,false),
        Arguments.of("FINISHED",8013104,2,1700000000L,false,false),
        Arguments.of("FINISHED",8013104,1,1700000000L,true,false),
        Arguments.of("FINISHED",8013104,1,1700000000L,false,true),
        Arguments.of("CLAIMING",8013104,1,1700000000L,false,true),
        Arguments.of("CLOSED",8013104,1,1700000000L,false,true),
        Arguments.of("DOING",8013104,0,0L,false,false));
  }
  private void qualification(String phase,int id,int progress,long finishTime,boolean cancelled) {
    String phaseValue=phase==null ? "null" : "\""+phase+"\"";
    var record=new Gson().fromJson("{\"questId\":"+id+",\"progress\":"+progress
        +",\"finishTime\":"+finishTime+",\"phase\":"+phaseValue+"}",QuestState.class);
    f.p().state.getMainMissions().put(8013104,record);
    if(cancelled) f.p().state.getCancelledMissions().add(8013104);
  }
  @ParameterizedTest @MethodSource("qualifications")
  void actualQueryOnlyProjectsAnIntactCompletedOriginalEligibilityRecord(
      String phase,int id,int progress,long finishTime,boolean cancelled,boolean allowed) throws Exception {
    f.start(); qualification(phase,id,progress,finishTime,cancelled);
    var before=f.p().snapshot(); var scene=f.p().getScene(); int saves=f.p().saved.size();
    f.p().interruptSave=true;
    var response=f.call(5547,EMPTY,"ChessRogueQueryScRsp"); assertEquals(0,f.code(response));
    assertEquals(allowed?List.of(101):List.of(),value(child(response,10),11),
        "Corrupt/cancelled saved data is not the source FinishMission8013104 qualification");
    assertEquals(List.of(),value(child(response,10),10)); f.assertUnchanged(before,scene,saves);
  }
  @ParameterizedTest @MethodSource("qualifications")
  void actualFirstStartUsesTheSameOriginalQualificationBeforeAllocatingOrSavingARun(
      String phase,int id,int progress,long finishTime,boolean cancelled,boolean allowed) throws Exception {
    qualification(phase,id,progress,finishTime,cancelled);
    var before=f.p().snapshot(); var scene=f.p().getScene(); int saves=f.p().saved.size();
    var response=f.call(5426,startBody(101,6,1001),"ChessRogueStartScRsp");
    if(allowed) {
      assertEquals(0,f.code(response)); assertTrue(f.p().state.getChessRogue().active());
      assertTrue(f.p().state.getChessRogue().isInside()); assertFalse(f.p().state.getChessRogue().getRunId().isEmpty());
      assertFalse(f.p().saved.isEmpty());
    } else {
      assertNotEquals(0,f.code(response),"A damaged original qualification must not create a first run or enter its scene");
      assertFalse(f.p().state.getChessRogue().active()); assertFalse(f.p().state.getChessRogue().isInside());
      f.assertUnchanged(before,scene,saves);
    }
  }
}
