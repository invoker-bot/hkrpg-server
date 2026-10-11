package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;
import static org.hkrpg.server.protocol.ChessRogueMerchantEntry46Test.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.DynamicMessage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual source-bound LeaveAndSave regressions; does not inherit or rerun the existing61 tests. */
class ChessRogueLeaveAndSave46Test {
  @TempDir Path root;
  ChessRogueMerchantEntry46Test f;
  @BeforeEach void setup() throws Exception {
    try (var input=getClass().getResourceAsStream("/fixtures/chess-rogue-leave-and-save-4.6.json")) {
      assertNotNull(input,"Complete source/native fixture is required before entering the real tutorial");
      var proof=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
      assertEquals(COMMIT,proof.get("sourceCommit").getAsString());
      assertEquals(88,proof.get("sourceOriginalBlobCount").getAsInt());
      assertEquals(23,proof.get("sourceOriginalTableCount").getAsInt());
      assertEquals("2adbcc34d227447d620718a5947aa5711296f3ca677b49dd10e499d0113f2458",proof.get("entryFixtureSHA256").getAsString());
      assertEquals(5462,proof.getAsJsonObject("commands").get("ChessRogueLeaveCsReq").getAsInt());
      assertEquals(5419,proof.getAsJsonObject("commands").get("ChessRogueLeaveScRsp").getAsInt());
      assertEquals(2,proof.get("nativeRecordStatus").getAsInt());
      assertFalse(proof.get("clientGameplayVerified").getAsBoolean());
      assertEquals(11,proof.getAsJsonArray("wholeNativeFunctions").size());
      for (var value:proof.getAsJsonArray("wholeNativeFunctions")) {
        var row=value.getAsJsonObject(); var raw=Base64.getDecoder().decode(row.get("rawBase64").getAsString());
        assertEquals(row.get("byteCount").getAsInt(),raw.length);
        assertEquals(row.get("sha256").getAsString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
      }
      var lua=proof.getAsJsonObject("wholeLua");
      var page=Base64.getDecoder().decode(lua.get("rawBase64").getAsString());
      assertEquals(13145,page.length); assertEquals(30,lua.get("fullyConsumedPrototypeCount").getAsInt());
      assertEquals(lua.get("sha256").getAsString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(page)));
    }
    f = new ChessRogueMerchantEntry46Test(); f.root = root; f.setup();
  }
  @AfterEach void cleanup() throws Exception { if (f != null) f.cleanup(); }

  private void acquiredByActualReports() throws Exception {
    var npc = f.merchantFromActualReports(); f.shop(npc.getEntityId());
    assertEquals(0,f.code(f.buy(npc.getEntityId(),4)));
    assertEquals(200,f.coins(f.query()));
    assertEquals(4,f.chess().getAsJsonObject("victories").size());
  }
  private DynamicMessage leave(byte[] bytes) throws Exception {
    return f.call(5462,bytes,"ChessRogueLeaveScRsp");
  }
  private DynamicMessage resume() throws Exception {
    return f.call(5437,body(out -> out.writeUInt32(8,101)),"ChessRogueEnterScRsp");
  }
  private void qualifiedProfile(DynamicMessage response,int field) {
    var descriptor = response.getDescriptorForType().findFieldByNumber(field);
    assertTrue(response.hasField(descriptor),"The complete native consumer requires source-qualified HIM profile");
    var profile = child(response,field);
    assertEquals("HIMJKAMHLMJ",profile.getDescriptorForType().getName());
    assertEquals(List.of(101),value(profile,11));
    assertEquals(List.of(),value(profile,10),"No original FirstReward109011 has been paid");
    assertTrue(profile.hasField(profile.getDescriptorForType().findFieldByNumber(9)));
    assertTrue(profile.hasField(profile.getDescriptorForType().findFieldByNumber(12)));
  }
  private void originalReturn(DynamicMessage response,JsonObject beforeStart,int saves) {
    assertEquals(0,f.code(response));
    var returnTo = f.chess().getAsJsonObject("returnTo");
    assertFalse(f.chess().get("inside").getAsBoolean());
    assertEquals(returnTo.get("plane").getAsInt(),f.p().getPlaneId());
    assertEquals(returnTo.get("floor").getAsInt(),f.p().getFloorId());
    assertEquals(returnTo.get("entry").getAsInt(),f.p().getEntryId());
    assertEquals(returnTo.get("pos"),GSON.toJsonTree(f.p().getPos()));
    assertEquals(returnTo.get("rot"),GSON.toJsonTree(f.p().getRot()));
    assertEquals(20001,f.p().getScene().getPlaneId());
    assertEquals(20001001,f.p().getScene().getFloorId());
    assertSame(f.p().teams.getLineupByIndex(0),f.p().getCurrentLineup());
    assertEquals(beforeStart.get("ordinaryRoster"),f.p().snapshot().get("ordinaryRoster"));
    var stageField=response.getDescriptorForType().findFieldByNumber(14);
    assertTrue(response.hasField(stageField),"5462 must publish an actual ordinary scene after its save");
    var scene=child(child(response,14),14);
    assertEquals(f.p().getPlaneId(),number(scene,10));
    assertEquals(f.p().getFloorId(),number(scene,2));
    assertEquals(f.p().getEntryId(),number(scene,8));
    assertEquals(1001,Gameplay46.number(children(child(child(response,14),2),4).getFirst(),"id"));
    assertTrue(response.hasField(response.getDescriptorForType().findFieldByNumber(6)),
        "The native Leave consumer calls Aeon.Sync without permitting a null AEM");
    assertEquals(6,number(child(response,6),14),"Saving a record retains its selected source aeon");
    qualifiedProfile(response,12);
    var sent=f.session.clientSends.stream().filter(s -> s.name().equals("ChessRogueLeaveScRsp")).reduce((a,b)->b).orElseThrow();
    assertEquals(saves+1,sent.committedSaves());
    assertEquals(f.p().saved.getLast(),sent.stateAtSend(),"Return scene and inside=false must already be durable together");
  }
  private static JsonObject withoutInside(JsonObject value) {
    var copy=value.deepCopy(); copy.remove("inside"); return copy;
  }

  @Test
  void actual5462AfterFourReportsAndNativePurchaseCommitsBeforeOriginalReturnStageAndProfile() throws Exception {
    var ordinary=f.p().snapshot(); acquiredByActualReports();
    var retained=f.chess().deepCopy(); var classic=f.state().get("rogue").deepCopy();
    int saves=f.p().saved.size(); var response=leave(EMPTY);
    originalReturn(response,ordinary,saves);
    assertEquals(withoutInside(retained),withoutInside(f.chess()),"No coins/receipt/UUID/vitals/stock are reset by saving a record");
    assertEquals(classic,f.state().get("rogue"));
    var returned=f.p().snapshot(); var scene=f.p().getScene(); int committed=f.p().saved.size();
    assertNotEquals(0,f.code(leave(EMPTY)),"A duplicate leave cannot save or publish another return stage");
    f.assertUnchanged(returned,scene,committed);
  }

  @Test
  void failed5462SaveKeepsLiveRunSceneLineupAndActorIdsThenSameRequestRetriesOnce() throws Exception {
    var ordinary=f.p().snapshot(); acquiredByActualReports();
    var before=f.p().snapshot(); var scene=f.p().getScene(); var run=f.p().state.getChessRogue();
    var lineup=f.p().getCurrentLineup(); int saves=f.p().saved.size(),sends=f.session.clientSends.size();
    var ids=new IdentityHashMap<emu.lunarcore.game.avatar.GameAvatar,Integer>();
    f.p().getAvatars().getAvatars().values().forEach(a -> ids.put(a,a.getEntityId()));
    f.p().interruptSave=true;
    assertThrows(IllegalStateException.class,() -> leave(EMPTY),"The actual5462 must reach the synchronous commit barrier");
    f.assertUnchanged(before,scene,saves);
    assertSame(run,f.p().state.getChessRogue()); assertSame(lineup,f.p().getCurrentLineup());
    ids.forEach((a,id) -> assertEquals(id,a.getEntityId()));
    assertEquals(sends,f.session.clientSends.size(),"Failed save cannot publish a candidate successful leave/profile/stage");
    f.p().interruptSave=false;
    originalReturn(leave(EMPTY),ordinary,saves);
  }

  @Test
  void normalPlayerDocumentReloadAfterLeavePublishesExistingRecordBefore5437AndKeepsPaidOwnership() throws Exception {
    acquiredByActualReports(); assertEquals(0,f.code(leave(EMPTY)));
    var saved=f.p().saved.getLast().deepCopy(); var retained=f.chess().deepCopy(); var classic=f.state().get("rogue").deepCopy();
    f.reload(saved); assertFalse(f.chess().get("inside").getAsBoolean());
    var before=f.p().snapshot(); var scene=f.p().getScene(); int saves=f.p().saved.size();
    var query=f.call(5547,EMPTY,"ChessRogueQueryScRsp");
    assertEquals(0,f.code(query)); qualifiedProfile(query,10);
    assertEquals(2,number(child(child(query,8),2),1),"CurRecordArea consumes source status2, not a guessed enum alias");
    assertEquals(200,f.coins(child(query,8))); f.assertUnchanged(before,scene,saves);
    var resumed=resume(); assertEquals(0,f.code(resumed));
    assertTrue(f.chess().get("inside").getAsBoolean());
    f.actualStage(child(resumed,6),java.util.Set.of(34,35));
    assertEquals(withoutInside(retained),withoutInside(f.chess())); assertEquals(classic,f.state().get("rogue"));
    var npc=f.p().getScene().getEntities().values().stream().filter(emu.lunarcore.game.scene.entity.EntityNpc.class::isInstance)
        .map(emu.lunarcore.game.scene.entity.EntityNpc.class::cast).filter(n -> n.getGroupId()==35 && n.getInstId()==400001 && n.getNpcId()==1013).findFirst().orElseThrow();
    assertNotEquals(0,f.code(f.buy(npc.getEntityId(),4)),"Same held miracle cannot be charged or granted twice after resuming");
    assertEquals(withoutInside(retained),withoutInside(f.chess()));
  }

  @Test
  void failed5437ResumeAfterSavedLeaveRestoresOrdinarySceneAndRecordAndCanRetry() throws Exception {
    acquiredByActualReports(); assertEquals(0,f.code(leave(EMPTY)));
    var before=f.p().snapshot(); var scene=f.p().getScene(); var run=f.p().state.getChessRogue();
    var lineup=f.p().getCurrentLineup(); int saves=f.p().saved.size(),sends=f.session.clientSends.size();
    f.p().interruptSave=true; assertThrows(IllegalStateException.class,() -> resume());
    f.assertUnchanged(before,scene,saves); assertSame(run,f.p().state.getChessRogue()); assertSame(lineup,f.p().getCurrentLineup());
    assertEquals(sends,f.session.clientSends.size());
    f.p().interruptSave=false; assertEquals(0,f.code(resume()));
    assertTrue(f.chess().get("inside").getAsBoolean()); f.actualStage(child(f.session.packets.get("ChessRogueEnterScRsp"),6),java.util.Set.of(34,35));
  }

  @Test
  void leaveWithWholeActDriftCannotPublishAStageOrChangeCurrentScene() throws Exception {
    acquiredByActualReports(); var before=f.p().snapshot(); var scene=f.p().getScene(); int saves=f.p().saved.size();
    Files.writeString(root.resolve(ACT),new String(f.raw(ACT),StandardCharsets.UTF_8)+" ",StandardCharsets.UTF_8);
    var rejected=leave(EMPTY); assertNotEquals(0,f.code(rejected));
    assertFalse(rejected.hasField(rejected.getDescriptorForType().findFieldByNumber(14)));
    assertFalse(rejected.hasField(rejected.getDescriptorForType().findFieldByNumber(12)));
    f.assertUnchanged(before,scene,saves);
  }

  @Test
  void leaveRejectsBattleAndNonemptyOriginalEmptyBody() throws Exception {
    f.start(); var active=f.unreportedBattle();
    var before=f.p().snapshot(); var scene=f.p().getScene(); int saves=f.p().saved.size();
    assertNotEquals(0,f.code(leave(EMPTY))); f.assertUnchanged(before,scene,saves); assertSame(active,f.p().getBattle());
    f.p().setBattle(null);
    for (byte[] bytes:List.of(body(out -> out.writeUInt32(1,0)),new byte[]{8,(byte)128},new byte[4097])) {
      assertNotEquals(0,f.code(leave(bytes))); f.assertUnchanged(before,scene,saves);
    }
  }

  @Test
  void oldSavedRecordWithoutReturnLeaseBindsOnlyInTheSuccessfulLeaveCommit() throws Exception {
    var ordinary=f.p().snapshot(); acquiredByActualReports();
    var old=f.chess().deepCopy(); var back=old.getAsJsonObject("returnTo");
    back.remove("dimension"); back.remove("sourceSignature");
    f.p().state.setChessRogue(GSON.fromJson(old,org.hkrpg.server.gameplay.ChessRogueState.class));
    var live=f.p().state.getChessRogue(); var before=f.p().snapshot(); var scene=f.p().getScene();
    int saves=f.p().saved.size(),sends=f.session.clientSends.size(); f.p().interruptSave=true;
    assertThrows(IllegalStateException.class,() -> leave(EMPTY));
    f.assertUnchanged(before,scene,saves); assertSame(live,f.p().state.getChessRogue());
    assertEquals("",f.chess().getAsJsonObject("returnTo").get("sourceSignature").getAsString());
    assertEquals(sends,f.session.clientSends.size());
    f.p().interruptSave=false; originalReturn(leave(EMPTY),ordinary,saves);
    assertEquals(old.get("runId"),f.chess().get("runId"));
    assertEquals(old.get("miracles"),f.chess().get("miracles"));
    assertEquals(64,f.chess().getAsJsonObject("returnTo").get("sourceSignature").getAsString().length());
    assertEquals(0,f.chess().getAsJsonObject("returnTo").get("dimension").getAsInt());
  }

  @Test
  void returnEntryDimensionAndCapturedSourceSignatureCannotBeChangedIntoAnotherDestination() throws Exception {
    acquiredByActualReports(); var original=f.chess().deepCopy();
    for (var changed:List.of("entry","floor","dimension","sourceSignature")) {
      var candidate=original.deepCopy(); var back=candidate.getAsJsonObject("returnTo");
      switch (changed) {
        case "entry" -> back.addProperty(changed,8020101);
        case "floor" -> back.addProperty(changed,80201001);
        case "dimension" -> back.addProperty(changed,100);
        default -> back.addProperty(changed,"0".repeat(64));
      }
      f.p().state.setChessRogue(GSON.fromJson(candidate,org.hkrpg.server.gameplay.ChessRogueState.class));
      var before=f.p().snapshot(); var scene=f.p().getScene(); int saves=f.p().saved.size();
      var rejected=leave(EMPTY); assertNotEquals(0,f.code(rejected),changed);
      assertFalse(rejected.hasField(rejected.getDescriptorForType().findFieldByNumber(14)));
      f.assertUnchanged(before,scene,saves);
    }
  }

  @Test
  void completeLoadedOrdinaryReturnTreeDriftRejectsLeaveDespiteUnchangedChessSource() throws Exception {
    acquiredByActualReports(); var before=f.p().snapshot(); var scene=f.p().getScene(); int saves=f.p().saved.size();
    var anchor=emu.lunarcore.data.GameData.getFloorInfo(20001,20001001).getStartAnchorInfo();
    float x=anchor.PosX; anchor.PosX=x+1;
    try {
      assertTrue(org.hkrpg.server.gameplay.ChessRogueRoutes.available(),"The independent original Chess source remains valid");
      var rejected=leave(EMPTY); assertNotEquals(0,f.code(rejected));
      assertFalse(rejected.hasField(rejected.getDescriptorForType().findFieldByNumber(14)));
      assertFalse(rejected.hasField(rejected.getDescriptorForType().findFieldByNumber(12)));
      f.assertUnchanged(before,scene,saves);
    } finally { anchor.PosX=x; }
  }

  @Test
  void legacyReturnWithoutLeaseCannotAdoptAChangedOriginalLoadedEntryGroup() throws Exception {
    acquiredByActualReports(); var legacy=f.chess().deepCopy();
    legacy.getAsJsonObject("returnTo").remove("sourceSignature");
    f.p().state.setChessRogue(GSON.fromJson(legacy,org.hkrpg.server.gameplay.ChessRogueState.class));
    var before=f.p().snapshot(); var scene=f.p().getScene(); int saves=f.p().saved.size();
    var anchor=emu.lunarcore.data.GameData.getFloorInfo(20001,20001001).getStartAnchorInfo();
    float x=anchor.PosX; anchor.PosX=x+1;
    try {
      assertTrue(org.hkrpg.server.gameplay.ChessRogueRoutes.available());
      var rejected=leave(EMPTY); assertNotEquals(0,f.code(rejected),"Missing old lease does not authorize changed original G2");
      assertFalse(rejected.hasField(rejected.getDescriptorForType().findFieldByNumber(14)));
      f.assertUnchanged(before,scene,saves);
    } finally { anchor.PosX=x; }
  }
}
