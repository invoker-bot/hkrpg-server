package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;
import static org.hkrpg.server.protocol.ChessRogueMerchantEntry46Test.*;

import com.google.protobuf.DynamicMessage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

/** Actual native Query profile regressions using the complete original entry/source harness. */
class ChessRogueNativeProfile46Test {
  @TempDir Path root;
  ChessRogueMerchantEntry46Test f;
  @BeforeEach void setup() throws Exception {
    f = new ChessRogueMerchantEntry46Test();
    f.root = root;
    f.setup();
  }
  @AfterEach void cleanup() throws Exception {
    if (f != null) f.cleanup();
  }
  private DynamicMessage requiredProfile(DynamicMessage response) {
    assertEquals(0, f.code(response), "The actual 5547 handler must have succeeded before its profile is inspected");
    var field = response.getDescriptorForType().findFieldByNumber(10);
    assertNotNull(field);
    assertEquals("HIMJKAMHLMJ", field.getMessageType().getName());
    assertTrue(response.hasField(field),
        "A successful native Query must include field10 HIM: caf674b calls caf6980 without a null guard");
    return (DynamicMessage) response.getField(field);
  }

  private void qualifiedGuideProfile(DynamicMessage profile) {
    var unlocked = profile.getDescriptorForType().findFieldByNumber(11);
    var claimed = profile.getDescriptorForType().findFieldByNumber(10);
    var nousDice = profile.getDescriptorForType().findFieldByNumber(9);
    var nousTalent = profile.getDescriptorForType().findFieldByNumber(12);
    assertAll("Source-qualified profile is not an empty protobuf shell",
        () -> assertEquals(List.of(101), profile.getField(unlocked),
            "Original area101 unlock1000020 requires the already completed8013104; no unrelated area is granted"),
        () -> assertEquals(List.of(), profile.getField(claimed),
            "Guide fragment credits are not the original FirstReward109011 and cannot mark it claimed"),
        () -> assertTrue(profile.hasField(nousDice),
            "The shared Data.SyncGamePlay unconditionally calls RogueNousDiceData.Sync; a null MOOK is rejected"),
        () -> assertTrue(profile.hasField(nousTalent),
            "caf69b4/caf69c4 require nonnull DDAF before syncing the independent281013 talent currency"));
    var dice = (DynamicMessage) profile.getField(nousDice);
    var talent = (DynamicMessage) profile.getField(nousTalent);
    assertEquals("MOOKMKEKMKC", dice.getDescriptorForType().getName());
    assertEquals("DDAFEPBGOHK", talent.getDescriptorForType().getName());
    assertAll("Unimplemented Nous acquisition is never invented for a Chess201 guide",
        () -> assertEquals(0, dice.getRepeatedFieldCount(dice.getDescriptorForType().findFieldByNumber(3))),
        () -> assertEquals(0, dice.getRepeatedFieldCount(dice.getDescriptorForType().findFieldByNumber(9))),
        () -> assertEquals(0, dice.getRepeatedFieldCount(dice.getDescriptorForType().findFieldByNumber(10))),
        () -> assertEquals(0, talent.getField(talent.getDescriptorForType().findFieldByNumber(8))));
  }

  @Test
  void successfulNativeQueryCarriesTheRequiredSourceQualifiedProfileAndDoesNotWriteProgress() throws Exception {
    f.start();
    var before = f.p().snapshot();
    var scene = f.p().getScene();
    int saves = f.p().saved.size();
    var response = f.call(5547, EMPTY, "ChessRogueQueryScRsp");
    var profile = requiredProfile(response);
    qualifiedGuideProfile(profile);
    f.assertUnchanged(before, scene, saves);
    var repeated = f.call(5547, EMPTY, "ChessRogueQueryScRsp");
    assertEquals(profile, requiredProfile(repeated));
    f.assertUnchanged(before, scene, saves);
  }

  @Test
  void actualReentryAndDocumentReloadKeepTheProfileAndAlreadyPaidMiracleIndependentOfClassic() throws Exception {
    var npc = f.merchant();
    f.shop(npc.getEntityId());
    assertEquals(0, f.code(f.buy(npc.getEntityId(), 4)));
    var saved = f.p().saved.getLast().deepCopy();
    var held = f.chess().getAsJsonObject("miracles").deepCopy();
    var classic = f.state().get("rogue").deepCopy();
    f.reload(saved);
    assertEquals(0, f.code(f.call(5437, body(out -> out.writeUInt32(8,101)), "ChessRogueEnterScRsp")));
    var before = f.p().snapshot();
    var scene = f.p().getScene();
    int saves = f.p().saved.size();
    var response = f.call(5547, EMPTY, "ChessRogueQueryScRsp");
    var profile = requiredProfile(response);
    qualifiedGuideProfile(profile);
    assertEquals(200, f.coins(child(response,8)));
    assertEquals(held,f.chess().getAsJsonObject("miracles"));
    assertEquals(classic,f.state().get("rogue"));
    f.assertUnchanged(before,scene,saves);
  }

  @Test
  void queryWithChangedWholeMerchantSourceCannotPublishAProfileOrConsumeTheRun() throws Exception {
    var npc = f.merchant();
    f.shop(npc.getEntityId());
    var before = f.p().snapshot();
    var scene = f.p().getScene();
    int saves = f.p().saved.size();
    // Retain all original fields: a single real Act edit invalidates its whole-file source lease.
    Files.writeString(root.resolve(ACT),new String(f.raw(ACT),StandardCharsets.UTF_8)+" ",StandardCharsets.UTF_8);
    var response = f.call(5547,EMPTY,"ChessRogueQueryScRsp");
    assertNotEquals(0,f.code(response));
    assertFalse(response.hasField(response.getDescriptorForType().findFieldByNumber(10)));
    assertFalse(response.hasField(response.getDescriptorForType().findFieldByNumber(8)));
    f.assertUnchanged(before,scene,saves);
  }
}
