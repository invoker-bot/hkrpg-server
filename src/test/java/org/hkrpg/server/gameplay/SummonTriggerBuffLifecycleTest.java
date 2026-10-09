package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.NpcMonsterExcel;
import emu.lunarcore.game.scene.entity.*;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.util.Position;
import java.nio.file.Path;
import java.util.List;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import us.hebi.quickbuf.RepeatedInt;

/**
 * Original summon configs with real scene callbacks; not a complete collision or ability engine.
 */
class SummonTriggerBuffLifecycleTest {
  @TempDir Path root;
  final MazeSummonActionTest fixture = new MazeSummonActionTest();

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    fixture.setup();
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.cleanup();
  }

  EntityMonster monster(MazeCasterBuffRemovalTest.Capture owner, int id) {
    var group = new GroupInfo();
    group.setId(199963);
    var info =
        fixture.fixture.gson.fromJson(
            "{\"ID\":" + id + ",\"PosX\":0,\"PosY\":0,\"PosZ\":0}", MonsterInfo.class);
    var monster = new EntityMonster(owner.getScene(), new NpcMonsterExcel(), group, info);
    owner.getScene().addEntity(monster);
    return monster;
  }

  EntitySummonUnit summon(MazeCasterBuffRemovalTest.Capture owner, int row) {
    owner
        .getScene()
        .summonUnit(
            owner.getCurrentLeaderAvatar(), fixture.excel(row), new Position(), new Position(), 20);
    return owner.getScene().getPlayerSummon();
  }

  void trigger(
      MazeCasterBuffRemovalTest.Capture owner, EntitySummonUnit unit, EntityMonster... monsters) {
    var ids = RepeatedInt.newEmptyInstance();
    for (var monster : monsters) ids.add(monster.getEntityId());
    owner
        .getScene()
        .handleSummonUnitTriggers(
            unit.getEntityId(),
            unit.getExcel().getId() == 14101 ? "AttractNpcMonsterTrigger" : "HitNpcMonsterTrigger",
            MotionInfo.newInstance(),
            ids);
  }

  long removals(MazeCasterBuffRemovalTest.Capture owner) {
    return owner.sent.stream()
        .filter(p -> p.getCmdId() == CmdId.SyncEntityBuffChangeListScNotify)
        .count();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 3})
  void originalAglaeaAndHarscylineTriggersUseDeclaredFiveHundredSeconds(int row) throws Exception {
    var owner = fixture.fixture.owner(false);
    var unit = summon(owner, row);
    var monster = monster(owner, 1);
    trigger(owner, unit, monster);
    var ids = row == 0 ? List.of(140202) : List.of(141001, 141002);
    for (int id : ids) {
      var buff = monster.getBuffs().get(id);
      assertNotNull(buff);
      assertEquals(
          500_000f, buff.getDuration(), "Source lifetime was replaced by a five-second guess");
      assertEquals(buff.getCreateTime() + 500_000, buff.getExpiry());
      var bridge = ProtocolBridge.forVersion("4.6.0");
      var wire =
          bridge
              .clientSchema()
              .parse("BuffInfo", bridge.translate("BuffInfo", buff.toProto().toByteArray(), true));
      assertEquals(500f, wire.getField(wire.getDescriptorForType().findFieldByName("life_time")));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"direct", "expiry", "replacement", "battle"})
  void declaredDestroyCleanupRemovesOnlyTheAppliedBuffAndNotifiesOnce(String mode)
      throws Exception {
    var owner = fixture.fixture.owner(false);
    var unit = summon(owner, 0);
    var monster = monster(owner, 1);
    trigger(owner, unit, monster);
    monster.addBuff(owner.getCurrentLeaderAvatar().getAvatarId(), 19996301, -1);
    owner.sent.clear();
    switch (mode) {
      case "direct" -> owner.getScene().removeSummonUnit();
      case "expiry" -> {
        unit.setDuration(0);
        owner.getScene().onTick(System.currentTimeMillis(), 1);
      }
      case "replacement" -> summon(owner, 3);
      case "battle" -> owner.setBattle(new AvatarBattleBuffSnapshotTest().battle(owner));
    }
    assertFalse(
        monster.getBuffs().containsKey(140202),
        "Summon-owned effect survived declared destruction cleanup");
    assertTrue(monster.getBuffs().containsKey(19996301));
    assertEquals(1, removals(owner));
    owner.getScene().removeEntity(unit);
    assertEquals(1, removals(owner), "Repeated removal emitted the same effect cleanup twice");
  }

  @Test
  void originalDefinitionWithoutDestroyCleanupRetainsItsEffects() throws Exception {
    var owner = fixture.fixture.owner(false);
    var unit = summon(owner, 3);
    var monster = monster(owner, 1);
    trigger(owner, unit, monster);
    owner.sent.clear();
    var buff = monster.getBuffs().get(141001);
    owner.getScene().removeSummonUnit();
    assertSame(buff, monster.getBuffs().get(141001));
    assertEquals(0, removals(owner));
  }

  @Test
  void laterSameIdEffectFromAnotherOwnerSurvivesOldSummonCleanup() throws Exception {
    var owner = fixture.fixture.owner(false);
    var unit = summon(owner, 0);
    var monster = monster(owner, 1);
    trigger(owner, unit, monster);
    var replacement = monster.addBuff(199963, 140202, 30);
    owner.sent.clear();
    owner.getScene().removeSummonUnit();
    assertSame(replacement, monster.getBuffs().get(140202));
    assertEquals(0, removals(owner));
  }

  @Test
  void detachedMonsterCleanupDoesNotSendAnEntityZeroNotification() throws Exception {
    var owner = fixture.fixture.owner(false);
    var unit = summon(owner, 0);
    var monster = monster(owner, 1);
    trigger(owner, unit, monster);
    owner.getScene().removeEntity(monster);
    owner.sent.clear();
    owner.getScene().removeSummonUnit();
    assertFalse(monster.getBuffs().containsKey(140202));
    assertEquals(0, removals(owner));
  }

  @Test
  void anEffectOverwrittenBeforeItsAdditionReturnsIsNotAdoptedByTheSummon() throws Exception {
    var owner = fixture.fixture.owner(false);
    var unit = summon(owner, 0);
    var group = new GroupInfo();
    group.setId(199963);
    var info = fixture.fixture.gson.fromJson("{\"ID\":2}", MonsterInfo.class);
    var monster =
        new EntityMonster(owner.getScene(), new NpcMonsterExcel(), group, info) {
          @Override
          public synchronized emu.lunarcore.game.scene.SceneBuff addBuff(
              int caster, int id, double seconds) {
            var applied = super.addBuff(caster, id, seconds);
            if (id == 140202) super.addBuff(caster, id, 30d);
            return applied;
          }
        };
    owner.getScene().addEntity(monster);
    trigger(owner, unit, monster);
    var newer = monster.getBuffs().get(140202);
    owner.sent.clear();
    owner.getScene().removeSummonUnit();
    assertSame(
        newer,
        monster.getBuffs().get(140202),
        "Tracking reread the mutable map and adopted a newer same-owner effect");
    assertEquals(0, removals(owner));
  }

  @Test
  void repeatedTargetIdsApplyTheTriggerEffectOnlyOnce() throws Exception {
    var owner = fixture.fixture.owner(false);
    var unit = summon(owner, 0);
    var monster = monster(owner, 1);
    owner.sent.clear();
    trigger(owner, unit, monster, monster);
    assertEquals(1, removals(owner), "Duplicate target IDs applied the same source effect twice");
  }

  @ParameterizedTest
  @ValueSource(strings = {"dynamic", "otherTarget"})
  void unsupportedDurationOrTargetCannotInventATriggerBuff(String kind) throws Exception {
    var owner = fixture.fixture.owner(false);
    var row = fixture.rows.get(0).getAsJsonObject();
    var raw = row.getAsJsonObject("config").deepCopy();
    var task =
        raw.getAsJsonObject("TriggerConfig")
            .getAsJsonArray("CustomTriggers")
            .get(0)
            .getAsJsonObject()
            .getAsJsonArray("OnTriggerEnter")
            .get(0)
            .getAsJsonObject();
    if (kind.equals("dynamic")) task.getAsJsonObject("LifeTime").addProperty("IsDynamic", true);
    else task.getAsJsonObject("TargetType").addProperty("Alias", "CasterSummoner");
    var config = fixture.fixture.gson.fromJson(raw, SummonUnitInfo.class);
    config.buildMazeSkillActions();
    var excel =
        fixture.fixture.gson.fromJson(
            row.get("row"), emu.lunarcore.data.excel.SummonUnitExcel.class);
    excel.setInfo(config);
    owner
        .getScene()
        .summonUnit(owner.getCurrentLeaderAvatar(), excel, new Position(), new Position(), 20);
    var unit = owner.getScene().getPlayerSummon();
    var monster = monster(owner, 1);
    trigger(owner, unit, monster);
    assertTrue(monster.getBuffs() == null || !monster.getBuffs().containsKey(140202));
  }

  @ParameterizedTest
  @ValueSource(doubles = {0.5, 1.1, -1})
  void explicitFractionalAndPermanentTriggerLifetimeRetainsItsPrecision(double seconds)
      throws Exception {
    var owner = fixture.fixture.owner(false);
    var raw = fixture.rows.get(0).getAsJsonObject().getAsJsonObject("config").deepCopy();
    raw.getAsJsonObject("TriggerConfig")
        .getAsJsonArray("CustomTriggers")
        .get(0)
        .getAsJsonObject()
        .getAsJsonArray("OnTriggerEnter")
        .get(0)
        .getAsJsonObject()
        .getAsJsonObject("LifeTime")
        .getAsJsonObject("FixedValue")
        .addProperty("Value", seconds);
    var config = fixture.fixture.gson.fromJson(raw, SummonUnitInfo.class);
    config.buildMazeSkillActions();
    var excel =
        fixture.fixture.gson.fromJson(
            fixture.rows.get(0).getAsJsonObject().get("row"),
            emu.lunarcore.data.excel.SummonUnitExcel.class);
    excel.setInfo(config);
    owner
        .getScene()
        .summonUnit(owner.getCurrentLeaderAvatar(), excel, new Position(), new Position(), 20);
    var unit = owner.getScene().getPlayerSummon();
    var monster = monster(owner, 1);
    trigger(owner, unit, monster);
    var buff = monster.getBuffs().get(140202);
    assertEquals(seconds == -1 ? -1f : (float) (seconds * 1000), buff.getDuration());
    assertEquals(
        seconds == -1 ? Long.MAX_VALUE : buff.getCreateTime() + (long) (seconds * 1000),
        buff.getExpiry());
  }

  @Test
  void expiredSummonCannotApplyAnotherEffectBeforeTheNextSceneTick() throws Exception {
    var owner = fixture.fixture.owner(false);
    var unit = summon(owner, 0);
    var monster = monster(owner, 1);
    unit.setDuration(0);
    owner.sent.clear();
    trigger(owner, unit, monster);
    assertTrue(monster.getBuffs() == null || !monster.getBuffs().containsKey(140202));
    assertEquals(0, removals(owner));
  }
}
