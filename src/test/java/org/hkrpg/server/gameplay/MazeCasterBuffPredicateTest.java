package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.config.SummonUnitInfo;
import emu.lunarcore.data.excel.SummonUnitExcel;
import emu.lunarcore.game.scene.entity.EntitySummonUnit;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.util.Position;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real source predicate and destruction config with an explicitly created existing unit. */
class MazeCasterBuffPredicateTest {
  @TempDir Path root;
  final MazeSummonActionTest fixture = new MazeSummonActionTest();
  JsonObject source;
  JsonArray units;

  JsonElement read(String path) throws Exception {
    try (var in = getClass().getResourceAsStream(path)) {
      assertNotNull(in);
      return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
  }

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    fixture.setup();
    source = read("/fixtures/maze-caster-buff-predicate-4.6.json").getAsJsonObject();
    units = read("/fixtures/summon-unit-4.6.json").getAsJsonArray();
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.cleanup();
  }

  SummonUnitExcel excel(int index) {
    var row = units.get(index).getAsJsonObject();
    var excel = fixture.fixture.gson.fromJson(row.get("row"), SummonUnitExcel.class);
    var config = fixture.fixture.gson.fromJson(row.get("config"), SummonUnitInfo.class);
    config.buildMazeSkillActions();
    excel.setInfo(config);
    return excel;
  }

  EntitySummonUnit summon(MazeCasterBuffRemovalTest.Capture owner) {
    owner
        .getScene()
        .summonUnit(owner.getCurrentLeaderAvatar(), excel(1), new Position(), new Position(), 20);
    return owner.getScene().getPlayerSummon();
  }

  JsonObject task() {
    return source.getAsJsonObject("task").deepCopy();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void originalSilverWolfPredicateChoosesItsBranchFromTheActualCasterBuff(boolean active)
      throws Exception {
    var owner = fixture.fixture.owner(false);
    var unit = summon(owner);
    var caster = owner.getCurrentLeaderAvatar();
    if (active) caster.addBuff(150601, -1);
    fixture.parse(task()).onCast(caster, fixture.motion());
    if (active) {
      assertNull(owner.getScene().getPlayerSummon());
      assertEquals(0, unit.getEntityId());
      assertFalse(
          caster.getBuffs().containsKey(150601), "Source OnDestroy caster marker was not removed");
    } else {
      assertSame(
          unit,
          owner.getScene().getPlayerSummon(),
          "Success tasks executed without their source predicate");
    }
  }

  @Test
  void anExpiredCasterMarkerDoesNotChooseTheSuccessBranch() throws Exception {
    var owner = fixture.fixture.owner(false);
    var unit = summon(owner);
    owner.getCurrentLeaderAvatar().getBuffs().put(150601, System.currentTimeMillis() - 60_000);
    fixture.parse(task()).onCast(owner.getCurrentLeaderAvatar(), fixture.motion());
    assertSame(unit, owner.getScene().getPlayerSummon());
  }

  @Test
  void aDifferentAvatarMarkerCannotSatisfyTheCasterCondition() throws Exception {
    var owner = fixture.fixture.owner(false);
    var unit = summon(owner);
    var other = new emu.lunarcore.game.avatar.GameAvatar(fixture.fixture.excel());
    other.setOwner(owner);
    other.addBuff(150601, -1);
    fixture.parse(task()).onCast(owner.getCurrentLeaderAvatar(), fixture.motion());
    assertSame(unit, owner.getScene().getPlayerSummon());
    assertTrue(other.getBuffs().containsKey(150601));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void explicitInverseReversesOnlyTheCasterBuffCheck(boolean active) throws Exception {
    var owner = fixture.fixture.owner(false);
    var unit = summon(owner);
    var caster = owner.getCurrentLeaderAvatar();
    if (active) caster.addBuff(150601, -1);
    var task = task();
    task.getAsJsonObject("Predicate").addProperty("Inverse", true);
    fixture.parse(task).onCast(caster, fixture.motion());
    if (active) assertSame(unit, owner.getScene().getPlayerSummon());
    else assertNull(owner.getScene().getPlayerSummon());
  }

  @Test
  void theSameCompiledSkillReevaluatesItsConditionAtEachCast() throws Exception {
    var owner = fixture.fixture.owner(false);
    var unit = summon(owner);
    var skill = fixture.parse(task());
    var caster = owner.getCurrentLeaderAvatar();
    skill.onCast(caster, fixture.motion());
    assertSame(unit, owner.getScene().getPlayerSummon());
    caster.addBuff(150601, -1);
    skill.onCast(caster, fixture.motion());
    assertNull(owner.getScene().getPlayerSummon());
    assertFalse(caster.getBuffs().containsKey(150601));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void normalAttackAndCastHitCallbacksPreserveTheCondition(boolean hit) throws Exception {
    var owner = fixture.fixture.owner(false);
    var unit = summon(owner);
    var skill = fixture.fixture.parse(fixture.fixture.excel(), "LeafFixture_NormalAtk01", task());
    if (hit) skill.onCastHit(owner.getCurrentLeaderAvatar(), List.of());
    else skill.onAttack(owner.getCurrentLeaderAvatar(), List.of());
    assertSame(unit, owner.getScene().getPlayerSummon());
  }

  @ParameterizedTest
  @ValueSource(strings = {"direct", "expiry", "replacement", "battle"})
  void sourceOnDestroyClearsItsCasterMarkerAndNotifiesExactlyOnce(String mode) throws Exception {
    var owner = fixture.fixture.owner(false);
    var unit = summon(owner);
    var caster = owner.getCurrentLeaderAvatar();
    caster.addBuff(150601, -1);
    caster.addBuff(150602, -1);
    owner.sent.clear();
    switch (mode) {
      case "direct" -> owner.getScene().removeSummonUnit();
      case "expiry" -> {
        unit.setDuration(0);
        owner.getScene().onTick(System.currentTimeMillis(), 1);
      }
      case "replacement" ->
          owner.getScene().summonUnit(caster, excel(0), new Position(), new Position(), 20);
      case "battle" ->
          owner.getScene().onBattleStart(new AvatarBattleBuffSnapshotTest().battle(owner));
    }
    assertFalse(caster.getBuffs().containsKey(150601));
    assertTrue(caster.getBuffs().containsKey(150602));
    assertEquals(
        1,
        owner.sent.stream()
            .filter(p -> p.getCmdId() == CmdId.SyncEntityBuffChangeListScNotify)
            .count());
    owner.getScene().removeEntity(unit);
    assertEquals(
        1,
        owner.sent.stream()
            .filter(p -> p.getCmdId() == CmdId.SyncEntityBuffChangeListScNotify)
            .count());
  }

  @Test
  void anUnsupportedTargetOnTheKnownPredicateDoesNotBecomeUnconditionalSuccess() throws Exception {
    var owner = fixture.fixture.owner(false);
    var unit = summon(owner);
    owner.getCurrentLeaderAvatar().addBuff(150601, -1);
    var task = task();
    task.getAsJsonObject("Predicate")
        .getAsJsonObject("TargetType")
        .addProperty("Alias", "AdvLineup");
    fixture.parse(task).onCast(owner.getCurrentLeaderAvatar(), fixture.motion());
    assertSame(unit, owner.getScene().getPlayerSummon());
  }
}
