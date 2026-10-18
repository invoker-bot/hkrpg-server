package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;
import static org.hkrpg.server.protocol.ChessRogueMerchantEntry46Test.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.hkrpg.server.gameplay.ChessRogueRoutes;
import org.hkrpg.server.gameplay.ChessRogueState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A disabled guide blocks Start, not the source-bound read-only initial Query. No client runs. */
class ChessRogueDisabledInitialQuery46Test {
  @TempDir Path root;
  ChessRogueInitialQuery46Test initial;
  ChessRogueMerchantEntry46Test f;

  @BeforeEach void setup() throws Exception {
    initial = new ChessRogueInitialQuery46Test();
    initial.root = root;
    initial.setup(); // Complete fixed original files, rows, native proof, and real ordinary scene.
    f = initial.f;
  }

  @AfterEach void cleanup() throws Exception {
    if (initial != null) initial.cleanup();
  }

  private void loadPolicy(boolean enabled) throws Exception {
    var policy = f.fixture.getAsJsonObject("route").deepCopy();
    policy.addProperty("enabled", enabled);
    Files.writeString(root.resolve(POLICY), GSON.toJson(policy));
    ChessRogueRoutes.load(root, root.resolve(POLICY));
    assertEquals(enabled, com.google.gson.JsonParser.parseString(
        Files.readString(root.resolve(POLICY))).getAsJsonObject().get("enabled").getAsBoolean());
  }

  private DynamicMessage query() throws Exception {
    return f.call(5547, EMPTY, "ChessRogueQueryScRsp");
  }

  private void completeProfile(DynamicMessage response, boolean qualified) {
    assertEquals(0, code(response), "Disabled Start must not fail a valid no-run initial Query");
    assertFalse(response.hasField(response.getDescriptorForType().findFieldByNumber(8)),
        "A profile query must not fabricate a run, board, or selected aeon");
    assertTrue(response.hasField(response.getDescriptorForType().findFieldByNumber(10)),
        "The native initial Query consumer requires its permanent profile even without a run");
    var profile = child(response, 10);
    assertEquals("HIMJKAMHLMJ", profile.getDescriptorForType().getName());
    assertEquals(qualified ? List.of(101) : List.of(), value(profile, 11));
    assertEquals(List.of(), value(profile, 10), "Eligibility must not create first-reward payment");
    assertTrue(profile.hasField(profile.getDescriptorForType().findFieldByNumber(9)));
    assertTrue(profile.hasField(profile.getDescriptorForType().findFieldByNumber(12)));
    var dice = child(profile, 9);
    var talent = child(profile, 12);
    assertEquals("MOOKMKEKMKC", dice.getDescriptorForType().getName());
    assertEquals("DDAFEPBGOHK", talent.getDescriptorForType().getName());
    for (int field : List.of(3, 9, 10))
      assertEquals(0, dice.getRepeatedFieldCount(dice.getDescriptorForType().findFieldByNumber(field)));
    assertEquals(0, number(talent, 8), "No invented Nous acquisitions or talent currency");
  }

  private void readOnlyInitial(boolean qualified) throws Exception {
    if (!qualified) f.p().state.getMainMissions().remove(8013104);
    var run = f.p().state.getChessRogue();
    assertFalse(run.active());
    assertFalse(run.isInside());
    assertEquals("", run.getRunId());
    assertEquals(20001, f.p().getPlaneId());
    assertTrue(f.p().getInventory().getItems().isEmpty());
    var before = f.p().snapshot();
    var scene = f.p().getScene();
    int saves = f.p().saved.size();
    f.p().interruptSave = true;
    var first = query();
    completeProfile(first, qualified);
    var repeated = query();
    completeProfile(repeated, qualified);
    assertEquals(first, repeated);
    assertSame(run, f.p().state.getChessRogue());
    assertFalse(run.active());
    assertFalse(run.isInside());
    assertEquals(qualified, f.p().state.getMainMissions().containsKey(8013104));
    assertTrue(f.p().getInventory().getItems().isEmpty());
    f.assertUnchanged(before, scene, saves);
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void disabledOriginalGuideStillReturnsACompleteReadOnlyInitialProfile(boolean qualified) throws Exception {
    loadPolicy(false);
    assertFalse(ChessRogueRoutes.available());
    assertNull(ChessRogueRoutes.rule());
    assertNull(ChessRogueRoutes.board(0));
    readOnlyInitial(qualified);
    assertFalse(ChessRogueRoutes.available(), "A Query must not enable operator-disabled gameplay");
    assertNotEquals(0, code(f.call(5426, startBody(101, 6, 1001), "ChessRogueStartScRsp")));
    assertFalse(f.p().state.getChessRogue().active());
    assertTrue(f.p().saved.isEmpty());
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void disabledStartRemainsRejectedWithOrWithoutTheOriginalPrerequisite(boolean qualified) throws Exception {
    loadPolicy(false);
    if (!qualified) f.p().state.getMainMissions().remove(8013104);
    var before = f.p().snapshot(); var scene = f.p().getScene(); int saves = f.p().saved.size();
    f.p().interruptSave = true;
    assertNotEquals(0, code(f.call(5426, startBody(101, 6, 1001), "ChessRogueStartScRsp")));
    assertFalse(f.p().state.getChessRogue().active());
    f.assertUnchanged(before, scene, saves);
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void enabledOriginalGuideKeepsItsExistingReadOnlyEligibilityProjection(boolean qualified) throws Exception {
    loadPolicy(true);
    assertTrue(ChessRogueRoutes.available());
    readOnlyInitial(qualified);
  }

  @Test void enabledOriginalGuideCanStillStartAfterAnInitialQuery() throws Exception {
    loadPolicy(true);
    readOnlyInitial(true);
    f.p().interruptSave = false;
    f.start();
    assertTrue(f.p().state.getChessRogue().active());
    assertTrue(f.p().state.getChessRogue().isInside());
    assertFalse(f.p().saved.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"changed-before-load", "missing-before-load", "changed-after-load", "loaded-floor"})
  void disabledPolicyCannotTurnChangedOriginalSourceIntoAValidProfile(String mode) throws Exception {
    if (mode.equals("changed-before-load"))
      Files.writeString(root.resolve(ACT), new String(f.raw(ACT), StandardCharsets.UTF_8) + " ");
    if (mode.equals("missing-before-load")) Files.delete(root.resolve(ACT));
    loadPolicy(false);
    if (mode.equals("changed-after-load"))
      Files.writeString(root.resolve(ACT), new String(f.raw(ACT), StandardCharsets.UTF_8) + " ");
    if (mode.equals("loaded-floor")) {
      var layers = GameData.getFloorInfo(80201, 80201001).getLayerToAreaMask();
      assertNotNull(layers); assertTrue(layers.length > 0);
      layers[0] ^= 256;
    }
    rejectQueryWithoutChange();
  }

  @Test void disabledQueryCannotTreatAPartialPreviousRecordAsAnEmptyAccount() throws Exception {
    loadPolicy(false);
    var damaged = GSON.toJsonTree(f.p().state.getChessRogue()).getAsJsonObject();
    damaged.addProperty("coins", 1);
    f.p().state.setChessRogue(GSON.fromJson(damaged, ChessRogueState.class));
    assertFalse(f.p().state.getChessRogue().active());
    assertEquals(1, f.p().state.getChessRogue().getCoins());
    rejectQueryWithoutChange();
  }

  private void rejectQueryWithoutChange() throws Exception {
    var before = f.p().snapshot(); var scene = f.p().getScene(); int saves = f.p().saved.size();
    f.p().interruptSave = true;
    var response = query();
    assertNotEquals(0, code(response));
    assertFalse(response.hasField(response.getDescriptorForType().findFieldByNumber(8)));
    assertFalse(response.hasField(response.getDescriptorForType().findFieldByNumber(10)));
    f.assertUnchanged(before, scene, saves);
  }
}
