package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Source spear state requests and actual shield destruction must precede treasure credit. */
class GoldenSpear46Test {
  @TempDir Path root;
  final WorldChestActivity46Test fixture = new WorldChestActivity46Test();
  static final int[] CONFIGS = {1010, 101101, 1010102, 1020002};
  static final PropState[] STATES = {
    PropState.Open, PropState.CustomState01, PropState.CustomState02, PropState.Locked
  };

  @BeforeEach
  void setup() throws Exception {
    for (String file : new String[] {"time-relay-flame-4.6.json", "golden-spear-4.6.json"}) {
      var extra =
          JsonParser.parseReader(
                  new java.io.InputStreamReader(
                      getClass().getResourceAsStream("/fixtures/" + file), StandardCharsets.UTF_8))
              .getAsJsonObject();
      for (var e : extra.getAsJsonObject("records").entrySet()) {
        var rows = fixture.source.getAsJsonObject("records").getAsJsonArray(e.getKey());
        if (rows == null)
          fixture.source.getAsJsonObject("records").add(e.getKey(), e.getValue().deepCopy());
        else for (var r : e.getValue().getAsJsonArray()) rows.add(r.deepCopy());
      }
      for (String section : new String[] {"groups", "locations"})
        for (var e : extra.getAsJsonObject(section).entrySet())
          fixture.source.getAsJsonObject(section).add(e.getKey(), e.getValue().deepCopy());
    }
    fixture.root = root;
    fixture.load();
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.restore();
  }

  EntityProp actor(int group, int instance) {
    var matches =
        fixture.player.scene.getEntitiesByGroup(EntityProp.class, group).stream()
            .filter(p -> p.getInstId() == instance)
            .toList();
    assertEquals(1, matches.size(), "The original golden-spear actor is absent");
    return matches.getFirst();
  }

  EntityProp shield(int group) {
    return actor(group, 300001);
  }

  EntityProp spear(int group) {
    return actor(group, 300002);
  }

  EntityProp treasure(int group) {
    return actor(group, 300003);
  }

  void destroy(int group) {
    var shield = shield(group);
    assertFalse(OrdinaryDestructibles.applies(shield), "A golden-spear shield is not an ordinary drop");
    assertTrue(fixture.player.scene.destroyProp(shield));
    assertEquals(0, shield.getEntityId());
    assertEquals(PropState.ChestClosed, treasure(group).getState());
  }

  @ParameterizedTest
  @ValueSource(ints = {273, 274})
  void theSourceClosedSpearAcceptsItsNativeStartRequest(int group) {
    var spear = spear(group);
    assertEquals(PropState.Closed, spear.getState());
    assertEquals(
        Retcode.SUCC, SceneInteractions.validateNative(fixture.player, 1010, spear.getEntityId()));
    assertSame(spear, fixture.player.interactWithProp(1010, spear.getEntityId()));
    assertEquals(PropState.Open, spear.getState());
  }

  @ParameterizedTest
  @ValueSource(ints = {273, 274})
  void allFourSourceOverrideTransitionsRestoreWithoutSkippingAStage(int group) {
    var spear = spear(group);
    for (int i = 0; i < CONFIGS.length; i++) {
      assertEquals(
          Retcode.SUCC,
          SceneInteractions.validateNative(fixture.player, CONFIGS[i], spear.getEntityId()));
      assertSame(spear, fixture.player.interactWithProp(CONFIGS[i], spear.getEntityId()));
      assertEquals(STATES[i], spear.getState());
      fixture.scene();
      spear = spear(group);
      assertEquals(STATES[i], spear.getState());
    }
    fixture.scene();
    spear = spear(group);
    assertEquals(PropState.Locked, spear.getState());
    assertNotEquals(
        Retcode.SUCC, SceneInteractions.validateNative(fixture.player, 1010, spear.getEntityId()));
    assertEquals(0, fixture.player.grants);
  }

  @ParameterizedTest
  @ValueSource(ints = {273, 274})
  void actualShieldDestructionRevealsOnlyItsOwnOriginalHiddenChest(int group) {
    fixture.player.getQuestManager().refreshUnlocks();
    assertEquals(PropState.Hidden, treasure(group).getState());
    destroy(group);
    assertEquals(PropState.Hidden, treasure(group == 273 ? 274 : 273).getState());
    assertEquals(0, fixture.progress(6029858));
    assertEquals(0, fixture.player.grants);
  }

  @Test
  void theSourceType226GoalIsOwnedAtZeroWithoutALegacyStub() {
    fixture.player.getQuestManager().refreshUnlocks();
    assertNotNull(fixture.player.getGameplay().getWorldChests().getQuests().get(6029858));
    assertEquals(0, fixture.progress(6029858));
    assertFalse(fixture.player.getQuestStates().containsKey(6029858));
  }

  @Test
  void twoRealShieldDestructionsAndOpeningsYieldASingleTypeGoal() {
    fixture.player.getQuestManager().refreshUnlocks();
    for (int group : new int[] {273, 274}) {
      destroy(group);
      var chest = treasure(group);
      assertSame(chest, fixture.player.interactWithProp(112131, chest.getEntityId()));
      assertEquals(PropState.ChestUsed, chest.getState());
      assertNull(fixture.player.interactWithProp(112131, chest.getEntityId()));
    }
    assertEquals(1, fixture.progress(6029858));
    assertEquals(0, fixture.progress(6029856));
    assertEquals(2, fixture.progress(6029850));
    assertEquals(2, fixture.player.grants);
  }

  @Test
  void theRevealedTreasureAndDestroyedShieldSurviveANewScene() {
    destroy(273);
    fixture.scene();
    assertEquals(PropState.ChestClosed, treasure(273).getState());
    assertTrue(
        fixture.player.scene.getEntitiesByGroup(EntityProp.class, 273).stream()
            .noneMatch(p -> p.getInstId() == 300001));
    assertEquals(0, fixture.player.grants);
  }

  @Test
  void aClaimedGoldenChestAndItsActivityCreditRestoreTogether() {
    fixture.player.getQuestManager().refreshUnlocks();
    destroy(273);
    var chest = treasure(273);
    assertSame(chest, fixture.player.interactWithProp(112131, chest.getEntityId()));
    fixture.player.getGameplay().worldChests(fixture.player.saved.getWorldChests());
    fixture.scene();
    assertEquals(PropState.ChestUsed, treasure(273).getState());
    assertEquals(1, fixture.progress(6029858));
    assertEquals(1, fixture.player.grants);
  }

  @Test
  void aFailedSaveCannotRemoveTheShieldOrRevealTheTreasure() {
    var shield = shield(273);
    int id = shield.getEntityId();
    fixture.player.failSave = true;
    assertFalse(fixture.player.scene.destroyProp(shield));
    assertSame(shield, fixture.player.scene.getEntityById(id));
    assertEquals(PropState.Hidden, treasure(273).getState());
    assertEquals(0, fixture.player.grants);
  }

  @Test
  void theOriginalHiddenChestCannotPayBeforeShieldDestruction() {
    var chest = treasure(273);
    assertNull(fixture.player.interactWithProp(112131, chest.getEntityId()));
    assertEquals(PropState.Hidden, chest.getState());
    assertEquals(0, fixture.player.grants);
  }

  @Test
  void loadingRetainsTheAuthoredCurveAttachmentAndChestIcon() {
    var prop = spear(273).getPropInfo();
    assertNotNull(prop.getValueSource());
    assertEquals("273,300001", prop.getSharedValueByKey("AttachTargetRef"));
    assertEquals("273,300003", actor(273, 300004).getPropInfo().getSharedValueByKey("Chest"));
    assertEquals(
        "Config/Level/Props/Chap04/InitLevelGraph_Prop_Chap04_GoldenSpear_04.json",
        prop.getInitLevelGraph());
  }

  @Test
  void aSpearSaveFailurePreservesThePreviousStageAndReceipt() {
    var spear = spear(273);
    assertSame(spear, fixture.player.interactWithProp(1010, spear.getEntityId()));
    fixture.player.failSave = true;
    assertNull(fixture.player.interactWithProp(101101, spear.getEntityId()));
    assertEquals(PropState.Open, spear.getState());
    assertEquals(
        1,
        fixture.player.getGameplay().getWorldChests().getSpears().get(20411666).getSteps().size());
    fixture.player.failSave = false;
    assertSame(spear, fixture.player.interactWithProp(101101, spear.getEntityId()));
    assertEquals(PropState.CustomState01, spear.getState());
  }

  @Test
  void onlyTheActualOverrideCanCloseItsFourthStage() {
    var spear = spear(273);
    for (int config : new int[] {1010, 101101, 1010102})
      assertSame(spear, fixture.player.interactWithProp(config, spear.getEntityId()));
    assertNull(fixture.player.interactWithProp(1020103, spear.getEntityId()));
    assertEquals(PropState.CustomState02, spear.getState());
    assertSame(spear, fixture.player.interactWithProp(1020002, spear.getEntityId()));
    assertEquals(PropState.Locked, spear.getState());
  }

  @Test
  void aGenericSavedSpearValueCannotSkipTheSourceStages() {
    fixture
        .player
        .getGameplay()
        .getStoryValues()
        .put("prop-state:20411:20411001:273:300002", PropState.Locked.getVal());
    fixture.scene();
    assertEquals(PropState.Closed, spear(273).getState());
    assertNull(fixture.player.interactWithProp(1020002, spear(273).getEntityId()));
  }

  @Test
  void aHiddenTreasureChangedInMemoryStillNeedsItsDestructionReceipt() {
    var chest = treasure(273);
    chest.setState(PropState.ChestClosed, false);
    assertNull(fixture.player.interactWithProp(112131, chest.getEntityId()));
    assertEquals(0, fixture.player.grants);
    assertTrue(fixture.player.getGameplay().getWorldChests().getUnlocks().isEmpty());
  }

  @Test
  void preparingAHitCannotClaimItWithoutActualRemoval() {
    var proof = GoldenSpears.prepare(fixture.player, shield(273));
    assertNotNull(proof);
    assertFalse(proof.complete());
    assertFalse(
        fixture.player.getGameplay().getWorldChests().getUnlocks().get(20411666).completed());
    assertEquals(PropState.Hidden, treasure(273).getState());
    assertEquals(0, fixture.player.grants);
  }

  @Test
  void aFailedRemovalCommitLeavesTheReservationBlockedWithoutPayment() {
    fixture.player.failOnSave = fixture.player.saves + 2;
    assertFalse(fixture.player.scene.destroyProp(shield(273)));
    fixture.player.failOnSave = -1;
    assertFalse(
        fixture.player.getGameplay().getWorldChests().getUnlocks().get(20411666).completed());
    assertEquals(PropState.Hidden, treasure(273).getState());
    fixture.scene();
    assertFalse(fixture.player.scene.destroyProp(shield(273)));
    assertNull(fixture.player.interactWithProp(112131, treasure(273).getEntityId()));
    assertEquals(0, fixture.player.grants);
  }

  @Test
  void expiredActivityDoesNotTurnARealOpeningIntoCredit() {
    fixture.player.getQuestManager().refreshUnlocks();
    GameplayDefinitions.windows.clear();
    destroy(273);
    var chest = treasure(273);
    assertSame(chest, fixture.player.interactWithProp(112131, chest.getEntityId()));
    assertEquals(
        0, fixture.player.getGameplay().getWorldChests().getQuests().get(6029858).getProgress());
    assertTrue(
        fixture
            .player
            .getGameplay()
            .getWorldChests()
            .getRecords()
            .get(20411666)
            .getAcceptances()
            .isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "propSource",
        "groupSource",
        "configSource",
        "config",
        "from",
        "to",
        "runtime",
        "event"
      })
  void changedSpearEvidenceCannotPublishOrAdvanceAStage(String name) {
    var spear = spear(273);
    assertSame(spear, fixture.player.interactWithProp(1010, spear.getEntityId()));
    var raw =
        fixture.gson.toJsonTree(fixture.player.getGameplay().getWorldChests()).getAsJsonObject();
    var step =
        raw.getAsJsonObject("spears")
            .getAsJsonObject("20411666")
            .getAsJsonArray("steps")
            .get(0)
            .getAsJsonObject();
    if (name.endsWith("Source") || name.equals("event")) step.addProperty(name, "unverified");
    else step.addProperty(name, -1);
    fixture.player.getGameplay().worldChests(fixture.gson.fromJson(raw, WorldChestState.class));
    fixture.scene();
    spear = spear(273);
    assertEquals(PropState.Closed, spear.getState());
    assertNull(fixture.player.interactWithProp(1010, spear.getEntityId()));
    assertEquals(0, fixture.player.grants);
  }

  @ParameterizedTest
  @ValueSource(strings = {"triggerSource", "triggerEntity", "completedAt", "phase"})
  void changedDestructionEvidenceCannotPayTheOriginalTreasure(String name) {
    destroy(273);
    var raw =
        fixture.gson.toJsonTree(fixture.player.getGameplay().getWorldChests()).getAsJsonObject();
    var proof = raw.getAsJsonObject("unlocks").getAsJsonObject("20411666");
    if (name.equals("phase")) proof.addProperty(name, "PREPARED");
    else if (name.equals("triggerSource")) proof.addProperty(name, "unverified");
    else proof.addProperty(name, -1);
    fixture.player.getGameplay().worldChests(fixture.gson.fromJson(raw, WorldChestState.class));
    assertNull(fixture.player.interactWithProp(112131, treasure(273).getEntityId()));
    assertEquals(0, fixture.player.grants);
  }

  @Test
  void oldVersionOneStorageInitializesTheNewSpearJournal() {
    var raw =
        fixture.gson.toJsonTree(fixture.player.getGameplay().getWorldChests()).getAsJsonObject();
    raw.remove("spears");
    fixture.player.getGameplay().worldChests(fixture.gson.fromJson(raw, WorldChestState.class));
    assertTrue(fixture.player.getGameplay().getWorldChests().getSpears().isEmpty());
    assertTrue(
        fixture
            .player
            .getGameplay()
            .getWorldChests()
            .valid(WorldChestRules.load(), fixture.player));
    assertSame(spear(273), fixture.player.interactWithProp(1010, spear(273).getEntityId()));
  }
}
