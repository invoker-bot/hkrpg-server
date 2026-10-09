package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.scene.entity.*;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.util.Position;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import us.hebi.quickbuf.RepeatedInt;

/**
 * Original switch scripts run through real scene callbacks; unrelated source actions stay pending.
 */
class SummonTriggerEnableTest {
  @TempDir Path root;
  final MazeCasterBuffRemovalTest fixture = new MazeCasterBuffRemovalTest();
  JsonArray rows;
  JsonArray toggles;
  long triggerTimestamp;

  JsonArray read(String resource) throws Exception {
    try (var in = getClass().getResourceAsStream(resource)) {
      assertNotNull(in);
      return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
          .getAsJsonArray();
    }
  }

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    rows = read("/fixtures/summon-trigger-enable-4.6.json");
    toggles = read("/fixtures/summon-trigger-toggle-4.6.json");
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.cleanup();
  }

  JsonObject raw(int index) {
    return rows.get(index).getAsJsonObject().getAsJsonObject("config").deepCopy();
  }

  EntitySummonUnit summon(MazeCasterBuffRemovalTest.Capture owner, int index, JsonObject config) {
    var excel =
        fixture.gson.fromJson(rows.get(index).getAsJsonObject().get("row"), SummonUnitExcel.class);
    var info = fixture.gson.fromJson(config, SummonUnitInfo.class);
    info.buildMazeSkillActions();
    excel.setInfo(info);
    owner
        .getScene()
        .summonUnit(owner.getCurrentLeaderAvatar(), excel, new Position(), new Position(), 60);
    var unit = owner.getScene().getPlayerSummon();
    triggerTimestamp = unit.getCreateTime();
    return unit;
  }

  EntityProp prop(MazeCasterBuffRemovalTest.Capture owner, int id) {
    var group = new GroupInfo();
    group.setId(199968);
    var excel =
        fixture.gson.fromJson(
            "{\"ID\":199968,\"PropType\":\"PROP_DESTRUCT\",\"PropStateList\":[\"Closed\",\"Open\"]}",
            PropExcel.class);
    var info = fixture.gson.fromJson("{\"ID\":" + id + "}", PropInfo.class);
    var prop = new EntityProp(owner.getScene(), excel, group, info);
    owner.getScene().addEntity(prop);
    return prop;
  }

  void trigger(
      MazeCasterBuffRemovalTest.Capture owner,
      EntitySummonUnit unit,
      String name,
      EntityProp prop) {
    var ids = RepeatedInt.newEmptyInstance();
    if (prop != null) ids.add(prop.getEntityId());
    owner
        .getScene()
        .handleSummonUnitTriggers(
            unit.getEntityId(), name, MotionInfo.newInstance(), ids, triggerTimestamp);
  }

  void advance(MazeCasterBuffRemovalTest.Capture owner, EntitySummonUnit unit, long elapsed) {
    triggerTimestamp = unit.getCreateTime() + elapsed;
    owner.getScene().onTick(triggerTimestamp, 1);
  }

  long notifications(MazeCasterBuffRemovalTest.Capture owner) {
    return owner.sent.stream()
        .filter(p -> p.getCmdId() == CmdId.RefreshTriggerByClientScNotify)
        .count();
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 565})
  void sourceJiaoqiuCannotDestroyPropsBeforeItsDeclaredEnableTime(long elapsed) throws Exception {
    var owner = fixture.owner(false);
    var unit = summon(owner, 0, raw(0));
    var prop = prop(owner, 1);
    advance(owner, unit, elapsed);
    owner.sent.clear();
    trigger(owner, unit, "HitPropTrigger", prop);
    assertSame(
        prop,
        owner.getScene().getEntityById(prop.getEntityId()),
        "Disabled source trigger destroyed a prop");
    assertEquals(0, notifications(owner));
  }

  @ParameterizedTest
  @ValueSource(longs = {566, 567})
  void sourceJiaoqiuEnablesAtItsFixedWaitBoundary(long elapsed) throws Exception {
    var owner = fixture.owner(false);
    var unit = summon(owner, 0, raw(0));
    var prop = prop(owner, 1);
    int id = prop.getEntityId();
    advance(owner, unit, elapsed);
    owner.sent.clear();
    trigger(owner, unit, "HitPropTrigger", prop);
    assertNull(owner.getScene().getEntityById(id));
    assertEquals(1, notifications(owner));
  }

  @ParameterizedTest
  @ValueSource(longs = {19499, 19500})
  void sourceRappaDisablesOnlyItsNamedMonsterTriggerAtNineteenPointFiveSeconds(long elapsed)
      throws Exception {
    var owner = fixture.owner(false);
    var unit = summon(owner, 2, raw(2));
    advance(owner, unit, elapsed);
    owner.sent.clear();
    trigger(owner, unit, "HitNpcMonsterTrigger", null);
    assertEquals(
        elapsed < 19500 ? 1 : 0, notifications(owner), "Rappa's source disable switch was ignored");
    var prop = prop(owner, 1);
    int id = prop.getEntityId();
    trigger(owner, unit, "HitPropTrigger", prop);
    assertNull(owner.getScene().getEntityById(id), "A named switch disabled unrelated triggers");
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 250})
  void sourcePlayerBoyPreservesTheQuarterSecondEnableBoundary(long elapsed) throws Exception {
    var owner = fixture.owner(false);
    var unit = summon(owner, 5, raw(5));
    advance(owner, unit, elapsed);
    owner.sent.clear();
    trigger(owner, unit, "PlayerBoy_30_FieldTrigger", null);
    assertEquals(elapsed >= 250 ? 1 : 0, notifications(owner));
  }

  @Test
  void omittedDefaultEnableRetainsAnInitiallyEnabledConstanceTrigger() throws Exception {
    var owner = fixture.owner(false);
    var unit = summon(owner, 4, raw(4));
    var prop = prop(owner, 1);
    int id = prop.getEntityId();
    trigger(owner, unit, "HitPropTrigger", prop);
    assertNull(owner.getScene().getEntityById(id));
  }

  @Test
  void sourceMydeimosStartsWithItsPropTriggerDisabled() throws Exception {
    var owner = fixture.owner(false);
    var unit = summon(owner, 1, raw(1));
    var prop = prop(owner, 1);
    trigger(owner, unit, "HitPropTrigger", prop);
    assertSame(prop, owner.getScene().getEntityById(prop.getEntityId()));
  }

  @Test
  void sourceMydeimosSwitchesAllTriggersWhenItsNamesAreOmitted() throws Exception {
    var owner = fixture.owner(false);
    var unit = summon(owner, 1, raw(1));
    var enable = toggles.get(0).getAsJsonObject().getAsJsonObject("task");
    var disable = toggles.get(1).getAsJsonObject().getAsJsonObject("task");
    fixture
        .parse(fixture.excel(), "LeafFixture_MazeSkill", enable)
        .onCast(owner.getCurrentLeaderAvatar(), MotionInfo.newInstance());
    var first = prop(owner, 1);
    int id = first.getEntityId();
    trigger(owner, unit, "HitPropTrigger", first);
    assertNull(owner.getScene().getEntityById(id));
    fixture
        .parse(fixture.excel(), "LeafFixture_MazeSkill", disable)
        .onCast(owner.getCurrentLeaderAvatar(), MotionInfo.newInstance());
    var second = prop(owner, 2);
    trigger(owner, unit, "HitPropTrigger", second);
    assertSame(
        second,
        owner.getScene().getEntityById(second.getEntityId()),
        "Source all-trigger disable was ignored");
  }

  @Test
  void anUnresolvedWaitDoesNotEnableSourceTriggersAtAPlaceholderTime() throws Exception {
    var owner = fixture.owner(false);
    var config = raw(0);
    var wait =
        config.getAsJsonArray("OnCreate").get(0).getAsJsonObject().getAsJsonObject("WaitTime");
    wait.addProperty("IsDynamic", true);
    var unit = summon(owner, 0, config);
    advance(owner, unit, 10000);
    var prop = prop(owner, 1);
    trigger(owner, unit, "HitPropTrigger", prop);
    assertSame(prop, owner.getScene().getEntityById(prop.getEntityId()));
  }

  @Test
  void sourceGilgameshRemainsClosedUntilTheSecondDeclaredWaitElapses() throws Exception {
    var owner = fixture.owner(false);
    var unit = summon(owner, 3, raw(3));
    advance(owner, unit, 459);
    var prop = prop(owner, 1);
    trigger(owner, unit, "HitPropTrigger", prop);
    assertSame(prop, owner.getScene().getEntityById(prop.getEntityId()));
  }

  @Test
  void replacementGetsFreshDefaultStateInsteadOfThePreviousUnitsEnabledState() throws Exception {
    var owner = fixture.owner(false);
    var previous = summon(owner, 0, raw(0));
    advance(owner, previous, 566);
    var unit = summon(owner, 0, raw(0));
    var prop = prop(owner, 1);
    trigger(owner, unit, "HitPropTrigger", prop);
    assertSame(prop, owner.getScene().getEntityById(prop.getEntityId()));
  }

  @Test
  void advancingTheCreateTimelineAgainDoesNotUndoALaterExplicitDisable() throws Exception {
    var owner = fixture.owner(false);
    var unit = summon(owner, 0, raw(0));
    advance(owner, unit, 566);
    var disable = toggles.get(1).getAsJsonObject().getAsJsonObject("task").deepCopy();
    disable.getAsJsonObject("SummonUnit").addProperty("SummonUnitID", 12181);
    fixture
        .parse(fixture.excel(), "LeafFixture_MazeSkill", disable)
        .onCast(owner.getCurrentLeaderAvatar(), MotionInfo.newInstance());
    advance(owner, unit, 10000);
    var prop = prop(owner, 1);
    trigger(owner, unit, "HitPropTrigger", prop);
    assertSame(prop, owner.getScene().getEntityById(prop.getEntityId()));
  }
}
