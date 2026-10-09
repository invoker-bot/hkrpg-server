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
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Real source definitions with isolated entity lifecycle, not the full summon ability chain. */
class SummonUnitLifecycleTest {
  @TempDir Path root;
  final MazeCasterBuffRemovalTest fixture = new MazeCasterBuffRemovalTest();
  JsonArray rows;

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    try (var in = getClass().getResourceAsStream("/fixtures/summon-unit-4.6.json")) {
      assertNotNull(in);
      rows =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.cleanup();
  }

  SummonUnitExcel excel(int index) {
    var row = rows.get(index).getAsJsonObject();
    var excel = fixture.gson.fromJson(row.get("row"), SummonUnitExcel.class);
    var config = fixture.gson.fromJson(row.get("config"), SummonUnitInfo.class);
    config.buildMazeSkillActions();
    excel.setInfo(config);
    assertNotNull(excel.getInfo());
    return excel;
  }

  EntitySummonUnit create(MazeCasterBuffRemovalTest.Capture owner, int index, int seconds) {
    owner
        .getScene()
        .summonUnit(
            owner.getCurrentLeaderAvatar(), excel(index), new Position(), new Position(), seconds);
    var units = owner.getScene().getEntities(EntitySummonUnit.class);
    assertEquals(1, units.size(), "Single-unit lifecycle left another active summon behind");
    return units.getFirst();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void creationTracksTheActualSourceUnitWithItsAssignedEntityId(int index) throws Exception {
    var owner = fixture.owner(false);
    var unit = create(owner, index, 20);
    assertSame(unit, owner.getScene().getPlayerSummon(), "Created summon was never registered");
    assertTrue(unit.getEntityId() > 0);
    assertSame(unit, owner.getScene().getEntityById(unit.getEntityId()));
    assertSame(owner.getCurrentLeaderAvatar(), unit.getCaster());
    assertEquals(excel(index).getId(), unit.toSceneEntityProto().getSummonUnit().getSummonUnitId());
  }

  @Test
  void replacingAUnitRemovesTheOldEntityBeforeInstallingTheNewOne() throws Exception {
    var owner = fixture.owner(false);
    var old = create(owner, 0, 20);
    int stale = old.getEntityId();
    owner.sent.clear();
    var next = create(owner, 1, 20);
    assertEquals(0, old.getEntityId());
    assertNull(owner.getScene().getEntityById(stale));
    assertSame(next, owner.getScene().getPlayerSummon());
    assertNotEquals(stale, next.getEntityId());
    assertEquals(2, owner.sent.size());
    var delete =
        emu.lunarcore.proto.SceneGroupRefreshScNotifyOuterClass.SceneGroupRefreshScNotify.parseFrom(
            owner.sent.getFirst().getData().toByteArray());
    assertEquals(
        stale, delete.getGroupRefreshInfo().get(0).getRefreshEntity().get(0).getDelEntity());
  }

  @Test
  void directEntityRemovalClearsTheCurrentPointerAndRepeatedRemovalDoesNotNotifyAgain()
      throws Exception {
    var owner = fixture.owner(false);
    var unit = create(owner, 0, 20);
    int entity = unit.getEntityId();
    owner.sent.clear();
    owner.getScene().removeEntity(entity);
    assertNull(owner.getScene().getPlayerSummon());
    assertEquals(0, unit.getEntityId());
    owner.getScene().removeSummonUnit();
    assertEquals(1, owner.sent.size());
    assertEquals(CmdId.SceneGroupRefreshScNotify, owner.sent.getFirst().getCmdId());
  }

  @Test
  void aZeroDurationUnitIsRemovedByTheSceneTick() throws Exception {
    var owner = fixture.owner(false);
    var unit = create(owner, 0, 0);
    int entity = unit.getEntityId();
    owner.getScene().onTick(System.currentTimeMillis(), 1);
    assertNull(owner.getScene().getEntityById(entity), "An expired summon survived the scene tick");
    assertNull(owner.getScene().getPlayerSummon());
    assertEquals(0, unit.getEntityId());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void actualSourceBattleDestructionFlagControlsRemoval(int index) throws Exception {
    var owner = fixture.owner(false);
    var unit = create(owner, index, 20);
    int entity = unit.getEntityId();
    boolean destroy =
        rows.get(index).getAsJsonObject().getAsJsonObject("row").has("DestroyOnEnterBattle");
    owner.setBattle(new AvatarBattleBuffSnapshotTest().battle(owner));
    if (destroy) {
      assertNull(owner.getScene().getEntityById(entity));
      assertNull(owner.getScene().getPlayerSummon());
      assertEquals(0, unit.getEntityId());
    } else {
      assertSame(unit, owner.getScene().getEntityById(entity));
      assertSame(unit, owner.getScene().getPlayerSummon());
    }
  }

  @Test
  void clearingTheBattleDoesNotDestroyANewOutOfBattleSummon() throws Exception {
    var owner = fixture.owner(false);
    var unit = create(owner, 1, 20);
    int entity = unit.getEntityId();
    owner.setBattle(null);
    assertSame(unit, owner.getScene().getEntityById(entity));
  }

  @Test
  void permanentLifetimeHasNoExpiryAndUsesTheSigned46MillisecondSentinel() throws Exception {
    var owner = fixture.owner(false);
    var unit =
        new EntitySummonUnit(
            owner.getScene(),
            owner.getCurrentLeaderAvatar(),
            excel(0),
            new Position(),
            new Position());
    unit.setDuration(-1);
    assertFalse(unit.isExpired(), "Permanent summon expired immediately");
    assertEquals(-1, unit.getDuration());
    assertEquals(Long.MAX_VALUE, unit.getExpiry());
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var proto = unit.toSceneEntityProto().getSummonUnit();
    var wire =
        bridge
            .clientSchema()
            .parse(
                "SceneSummonUnitInfo",
                bridge.translate("SceneSummonUnitInfo", proto.toByteArray(), true));
    assertEquals(-1, wire.getField(wire.getDescriptorForType().findFieldByName("life_time_ms")));
    assertEquals(
        owner.getCurrentLeaderAvatar().getEntityId(),
        wire.getField(wire.getDescriptorForType().findFieldByName("caster_entity_id")));
  }

  @ParameterizedTest
  @ValueSource(ints = {-2, 2147484, Integer.MAX_VALUE})
  void invalidOrUnrepresentableDurationCannotRemoveAnExistingUnit(int seconds) throws Exception {
    var owner = fixture.owner(false);
    var current = create(owner, 0, 20);
    int entity = current.getEntityId();
    owner.sent.clear();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            owner
                .getScene()
                .summonUnit(
                    owner.getCurrentLeaderAvatar(),
                    excel(1),
                    new Position(),
                    new Position(),
                    seconds));
    assertSame(current, owner.getScene().getEntityById(entity));
    assertSame(current, owner.getScene().getPlayerSummon());
    assertTrue(owner.sent.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 20, 2147483})
  void representableFiniteDurationKeepsMillisecondWireUnitsAndLongExpiry(int seconds)
      throws Exception {
    var owner = fixture.owner(false);
    var unit =
        new EntitySummonUnit(
            owner.getScene(),
            owner.getCurrentLeaderAvatar(),
            excel(0),
            new Position(),
            new Position());
    unit.setDuration(seconds);
    assertEquals(seconds * 1000, unit.getDuration());
    assertEquals(unit.getCreateTime() + seconds * 1000L, unit.getExpiry());
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var proto = unit.toSceneEntityProto().getSummonUnit();
    var wire =
        bridge
            .clientSchema()
            .parse(
                "SceneSummonUnitInfo",
                bridge.translate("SceneSummonUnitInfo", proto.toByteArray(), true));
    assertEquals(
        seconds * 1000, wire.getField(wire.getDescriptorForType().findFieldByName("life_time_ms")));
  }
}
