package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;
import static org.hkrpg.server.protocol.ChessRogueMerchantEntry46Test.*;

import com.google.gson.JsonParser;
import com.google.protobuf.DynamicMessage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.hkrpg.server.gameplay.ChessRogueRoutes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual initial5547 against the complete original guide fixture; no inherited61 tests. */
class ChessRogueInitialQuery46Test {
  @TempDir Path root;
  ChessRogueMerchantEntry46Test f;

  @BeforeEach void setup() throws Exception {
    try (var input=getClass().getResourceAsStream("/fixtures/chess-rogue-initial-query-4.6.json")) {
      assertNotNull(input,"Complete original native/source proof is required before checking initial Query");
      var proof=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
      assertEquals(COMMIT,proof.get("sourceCommit").getAsString());
      assertEquals(5547,proof.getAsJsonObject("commands").get("ChessRogueQueryCsReq").getAsInt());
      assertEquals("2adbcc34d227447d620718a5947aa5711296f3ca677b49dd10e499d0113f2458",
          proof.get("entryFixtureSHA256").getAsString());
      assertFalse(proof.get("clientGameplayVerified").getAsBoolean());
      assertEquals(15,proof.getAsJsonArray("wholeNativeFunctions").size());
      for (var value:proof.getAsJsonArray("wholeNativeFunctions")) {
        var row=value.getAsJsonObject(); var raw=Base64.getDecoder().decode(row.get("rawBase64").getAsString());
        assertEquals(row.get("byteCount").getAsInt(),raw.length);
        assertEquals(row.get("sha256").getAsString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
      }
    }
    f=new ChessRogueMerchantEntry46Test(); f.root=root; f.setup();
    assertTrue(ChessRogueRoutes.available(),"The whole original guide/resource graph must be valid in both eligibility cases");
  }
  @AfterEach void cleanup() throws Exception { if (f != null) f.cleanup(); }

  private DynamicMessage query() throws Exception { return f.call(5547,EMPTY,"ChessRogueQueryScRsp"); }
  private void completeProfile(DynamicMessage response,List<Integer> eligible) {
    assertEquals(0,f.code(response),"Initial native Query is a read-only profile lookup, not an existing-run-only request");
    assertTrue(response.hasField(response.getDescriptorForType().findFieldByNumber(10)),
        "caf674b calls _SyncGamePlayInfo with mandatory HIM even when _SyncChessRogueInfo returns on null");
    var profile=child(response,10);
    assertEquals("HIMJKAMHLMJ",profile.getDescriptorForType().getName());
    assertEquals(eligible,value(profile,11),"Only the original8013104 eligibility changes IsUnlocked for area101");
    assertEquals(List.of(),value(profile,10),"Eligibility is not payment of original FirstReward109011");
    assertTrue(profile.hasField(profile.getDescriptorForType().findFieldByNumber(9)),"MOOK must exist before RogueNousDiceData.Sync");
    assertTrue(profile.hasField(profile.getDescriptorForType().findFieldByNumber(12)),"DDAF must exist before talent currency sync");
    var dice=child(profile,9); var talent=child(profile,12);
    assertEquals("MOOKMKEKMKC",dice.getDescriptorForType().getName());
    assertEquals("DDAFEPBGOHK",talent.getDescriptorForType().getName());
    for (int field:List.of(3,9,10)) assertEquals(0,dice.getRepeatedFieldCount(dice.getDescriptorForType().findFieldByNumber(field)));
    assertEquals(0,number(talent,8),"No281013 ledger or Nous reward has been implemented by the bounded guide");
  }

  private void initialQuery(List<Integer> eligible) throws Exception {
    var run=f.p().state.getChessRogue();
    assertFalse(run.active()); assertFalse(run.isInside()); assertEquals("",run.getRunId());
    assertEquals(20001,f.p().getPlaneId());
    assertFalse(ChessRogueRoutes.returnSignature(f.p().getScene(),f.p().getEntryId()).isEmpty());
    assertTrue(f.p().getInventory().getItems().isEmpty());
    var before=f.p().snapshot(); var scene=f.p().getScene(); int saves=f.p().saved.size();
    f.p().interruptSave=true; // A pure query must never reach save, even on the first eligible lookup.
    var response=query(); completeProfile(response,eligible);
    assertFalse(response.hasField(response.getDescriptorForType().findFieldByNumber(8)),
        "caf686f/caf6872 accept missing DJE: an initial query must not synthesize a run/board/selected aeon");
    assertSame(run,f.p().state.getChessRogue()); f.assertUnchanged(before,scene,saves);
    assertTrue(f.p().getInventory().getItems().isEmpty());
    var repeated=query(); completeProfile(repeated,eligible);
    assertEquals(response,repeated); assertSame(run,f.p().state.getChessRogue());
    assertFalse(f.p().state.getChessRogue().active()); f.assertUnchanged(before,scene,saves);
  }

  @Test void noRunQualifiedOrdinaryInitialQueryPublishesOnlyTheCompletePermanentProfile() throws Exception {
    assertTrue(f.p().state.getMainMissions().containsKey(8013104));
    initialQuery(List.of(101));
  }
  @Test void noRunUnqualifiedOrdinaryInitialQueryPublishesACompleteLockedProfileWithoutGrantingEligibility() throws Exception {
    f.p().state.getMainMissions().remove(8013104);
    initialQuery(List.of());
    assertFalse(f.p().state.getMainMissions().containsKey(8013104));
  }
  @Test void existingOriginalRunStillPublishesItsExactBoardAndProfileWithoutSaving() throws Exception {
    f.start(); var run=f.p().state.getChessRogue(); var before=f.p().snapshot();
    var scene=f.p().getScene(); int saves=f.p().saved.size(); f.p().interruptSave=true;
    var response=query(); completeProfile(response,List.of(101));
    assertTrue(response.hasField(response.getDescriptorForType().findFieldByNumber(8)));
    f.fullBoard(child(response,8),1011,10111,201,TUTORIAL1);
    assertEquals(0,f.coins(child(response,8))); assertSame(run,f.p().state.getChessRogue());
    f.assertUnchanged(before,scene,saves);
  }
  @Test void pausedPaidRecordStillQueriesItsPersistedBoardOwnershipAndSourceProfile() throws Exception {
    var npc=f.merchantFromActualReports(); f.shop(npc.getEntityId());
    assertEquals(0,f.code(f.buy(npc.getEntityId(),4)));
    assertEquals(0,f.code(f.call(5462,EMPTY,"ChessRogueLeaveScRsp")));
    var saved=f.p().saved.getLast().deepCopy(); f.reload(saved);
    var run=f.p().state.getChessRogue(); var before=f.p().snapshot();
    var scene=f.p().getScene(); int saves=f.p().saved.size(); f.p().interruptSave=true;
    assertFalse(run.isInside()); assertTrue(run.active());
    var response=query(); completeProfile(response,List.of(101));
    assertTrue(response.hasField(response.getDescriptorForType().findFieldByNumber(8)));
    assertEquals(200,f.coins(child(response,8)));
    assertEquals(4,run.getVictories().size()); assertEquals(1,run.getMiracles().size());
    assertSame(run,f.p().state.getChessRogue()); f.assertUnchanged(before,scene,saves);
  }
  @Test void existingRunWithChangedWholeSourceCannotFallBackToTheInitialProfileBranch() throws Exception {
    f.start(); var before=f.p().snapshot(); var scene=f.p().getScene(); int saves=f.p().saved.size();
    Files.writeString(root.resolve(ACT),new String(f.raw(ACT),StandardCharsets.UTF_8)+" ",StandardCharsets.UTF_8);
    var response=query(); assertNotEquals(0,f.code(response));
    assertFalse(response.hasField(response.getDescriptorForType().findFieldByNumber(8)));
    assertFalse(response.hasField(response.getDescriptorForType().findFieldByNumber(10)));
    f.assertUnchanged(before,scene,saves);
  }
  @Test void nonemptyOriginalInitialQueryBodiesCannotCreateOrExposeARecordOrProfile() throws Exception {
    var before=f.p().snapshot(); var scene=f.p().getScene(); int saves=f.p().saved.size();
    for (var bytes:List.of(body(out -> out.writeUInt32(1,1)),new byte[] {10},new byte[4097])) {
      var response=f.call(5547,bytes,"ChessRogueQueryScRsp"); assertNotEquals(0,f.code(response));
      assertFalse(response.hasField(response.getDescriptorForType().findFieldByNumber(8)));
      assertFalse(response.hasField(response.getDescriptorForType().findFieldByNumber(10)));
      assertFalse(f.p().state.getChessRogue().active()); f.assertUnchanged(before,scene,saves);
    }
  }

  @Test void noRunWithChangedWholeOriginalActCannotExposeEvenAnUnlockedProfile() throws Exception {
    Files.writeString(root.resolve(ACT),new String(f.raw(ACT),StandardCharsets.UTF_8)+" ",StandardCharsets.UTF_8);
    rejectWithoutChange();
  }
  @Test void initialProfileRequiresTheCurrentOrdinarySceneEntryToMatchItsPlayer() throws Exception {
    f.p().getScene().setEntryId(8020101);
    rejectWithoutChange();
  }
  @Test void aPausedPaidRecordWithALostRunIdCannotMasqueradeAsAnInitialAccount() throws Exception {
    var npc=f.merchantFromActualReports(); f.shop(npc.getEntityId());
    assertEquals(0,f.code(f.buy(npc.getEntityId(),4)));
    assertEquals(0,f.code(f.call(5462,EMPTY,"ChessRogueLeaveScRsp")));
    var damaged=f.p().saved.getLast().deepCopy();
    damaged.getAsJsonObject("gameplay").getAsJsonObject("chessRogue").addProperty("runId","");
    f.reload(damaged);
    assertFalse(f.p().state.getChessRogue().active());
    assertEquals(200,f.p().state.getChessRogue().getCoins());
    assertEquals(1,f.p().state.getChessRogue().getMiracles().size());
    rejectWithoutChange();
  }
  private void rejectWithoutChange() throws Exception {
    var before=f.p().snapshot(); var scene=f.p().getScene(); int saves=f.p().saved.size();
    f.p().interruptSave=true;
    var response=query(); assertNotEquals(0,f.code(response));
    assertFalse(response.hasField(response.getDescriptorForType().findFieldByNumber(8)));
    assertFalse(response.hasField(response.getDescriptorForType().findFieldByNumber(10)));
    f.assertUnchanged(before,scene,saves);
  }
}
