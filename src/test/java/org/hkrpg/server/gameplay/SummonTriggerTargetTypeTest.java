package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.SummonUnitExcel;
import emu.lunarcore.game.scene.entity.*;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.util.Position;
import io.netty.buffer.Unpooled;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import us.hebi.quickbuf.RepeatedInt;

/**
 * Original trigger type lists and actual outgoing 4.6 notifications; collision checks are separate.
 */
class SummonTriggerTargetTypeTest {
  @TempDir Path root;
  final SummonTriggerBuffLifecycleTest fixture = new SummonTriggerBuffLifecycleTest();
  final SummonTriggerEnableTest props = new SummonTriggerEnableTest();
  final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    fixture.setup();
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.cleanup();
  }

  MazeCasterBuffRemovalTest.Capture owner() throws Exception {
    return fixture.fixture.fixture.owner(false);
  }

  EntityNpc npc(MazeCasterBuffRemovalTest.Capture owner) {
    var group = new GroupInfo();
    group.setId(199967);
    var info =
        props.fixture.gson.fromJson(
            "{\"ID\":1,\"NPCID\":199967,\"PosX\":0,\"PosY\":0,\"PosZ\":0}", NpcInfo.class);
    var npc = new EntityNpc(owner.getScene(), group, info);
    owner.getScene().addEntity(npc);
    return npc;
  }

  void trigger(
      MazeCasterBuffRemovalTest.Capture owner, EntitySummonUnit unit, String name, int... targets) {
    var ids = RepeatedInt.newEmptyInstance();
    for (int id : targets) ids.add(id);
    owner.sent.clear();
    owner
        .getScene()
        .handleSummonUnitTriggers(unit.getEntityId(), name, MotionInfo.newInstance(), ids);
  }

  List<?> notified(MazeCasterBuffRemovalTest.Capture owner, EntitySummonUnit unit, String name)
      throws Exception {
    var packets =
        owner.sent.stream()
            .filter(p -> p.getCmdId() == CmdId.RefreshTriggerByClientScNotify)
            .toList();
    assertEquals(1, packets.size());
    var bytes =
        Unpooled.wrappedBuffer(
            assertDoesNotThrow(() -> bridge.outboundFrames(packets.getFirst().build())));
    try {
      var frame = PacketCodec.decodeNext(bytes);
      assertNotNull(frame);
      assertFalse(bytes.isReadable());
      assertEquals(bridge.clientCommand("RefreshTriggerByClientScNotify"), frame.cmdId());
      var message = bridge.clientSchema().parse("RefreshTriggerByClientScNotify", frame.body());
      assertEquals(unit.getEntityId(), field(message, "trigger_entity_id"));
      assertEquals(name, field(message, "trigger_name"));
      assertTrue(message.getUnknownFields().asMap().isEmpty());
      return (List<?>) field(message, "trigger_target_id_list");
    } finally {
      bytes.release();
    }
  }

  Object field(DynamicMessage message, String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }

  @Test
  void originalMonsterTriggerReportsAndAppliesOnlyItsMonsterTargets() throws Exception {
    var owner = owner();
    var unit = fixture.summon(owner, 0);
    var monster = fixture.monster(owner, 1);
    var prop = props.prop(owner, 1);
    var npc = npc(owner);
    trigger(
        owner,
        unit,
        "HitNpcMonsterTrigger",
        prop.getEntityId(),
        monster.getEntityId(),
        npc.getEntityId(),
        unit.getEntityId());
    assertEquals(List.of(monster.getEntityId()), notified(owner, unit, "HitNpcMonsterTrigger"));
    assertNotNull(monster.getBuffs().get(140202));
    assertSame(prop, owner.getScene().getEntityById(prop.getEntityId()));
  }

  @Test
  void originalPropTriggerReportsTheAcceptedIdEvenAfterThePropIsDestroyed() throws Exception {
    var owner = owner();
    var unit = fixture.summon(owner, 0);
    var monster = fixture.monster(owner, 1);
    var prop = props.prop(owner, 1);
    int originalId = prop.getEntityId();
    var npc = npc(owner);
    trigger(owner, unit, "HitPropTrigger", monster.getEntityId(), npc.getEntityId(), originalId);
    assertEquals(List.of(originalId), notified(owner, unit, "HitPropTrigger"));
    assertNull(owner.getScene().getEntityById(originalId));
    assertEquals(0, prop.getEntityId());
    assertTrue(monster.getBuffs() == null || monster.getBuffs().isEmpty());
  }

  @Test
  void originalNpcTriggerDoesNotTreatPropsOrMonstersAsNpcTargets() throws Exception {
    var owner = owner();
    var unit = fixture.summon(owner, 0);
    var monster = fixture.monster(owner, 1);
    var prop = props.prop(owner, 1);
    var npc = npc(owner);
    trigger(
        owner, unit, "HitNPCTrigger", monster.getEntityId(), prop.getEntityId(), npc.getEntityId());
    assertEquals(List.of(npc.getEntityId()), notified(owner, unit, "HitNPCTrigger"));
    assertSame(prop, owner.getScene().getEntityById(prop.getEntityId()));
  }

  @Test
  void duplicateAndMissingPropIdsAreNotEchoedAsAdditionalAcceptedTargets() throws Exception {
    var owner = owner();
    var unit = fixture.summon(owner, 0);
    var prop = props.prop(owner, 1);
    int id = prop.getEntityId();
    trigger(owner, unit, "HitPropTrigger", id, Integer.MAX_VALUE, id, 0, -1);
    assertEquals(List.of(id), notified(owner, unit, "HitPropTrigger"));
    assertNull(owner.getScene().getEntityById(id));
  }

  @Test
  void missingAndInvalidMonsterIdsDoNotEnterTheOutgoingAcceptedList() throws Exception {
    var owner = owner();
    var unit = fixture.summon(owner, 0);
    var monster = fixture.monster(owner, 1);
    int id = monster.getEntityId();
    trigger(owner, unit, "HitNpcMonsterTrigger", Integer.MAX_VALUE, 0, id, -1);
    assertEquals(List.of(id), notified(owner, unit, "HitNpcMonsterTrigger"));
    assertNotNull(monster.getBuffs().get(140202));
  }

  @Test
  void aTriggerWithNoMatchingTargetsReportsAnEmptyAcceptedList() throws Exception {
    var owner = owner();
    var unit = fixture.summon(owner, 0);
    var prop = props.prop(owner, 1);
    var npc = npc(owner);
    trigger(
        owner,
        unit,
        "HitNpcMonsterTrigger",
        unit.getEntityId(),
        prop.getEntityId(),
        npc.getEntityId());
    assertEquals(List.of(), notified(owner, unit, "HitNpcMonsterTrigger"));
    assertSame(prop, owner.getScene().getEntityById(prop.getEntityId()));
  }

  @Test
  void originalSilverWolfMixedTypeListKeepsBothAcceptedTypesInRequestOrder() throws Exception {
    var owner = owner();
    JsonArray rows;
    try (var in = getClass().getResourceAsStream("/fixtures/summon-unit-4.6.json")) {
      assertNotNull(in);
      rows =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
    var row = rows.get(1).getAsJsonObject();
    var excel = props.fixture.gson.fromJson(row.get("row"), SummonUnitExcel.class);
    var config = props.fixture.gson.fromJson(row.get("config"), SummonUnitInfo.class);
    config.buildMazeSkillActions();
    excel.setInfo(config);
    owner
        .getScene()
        .summonUnit(owner.getCurrentLeaderAvatar(), excel, new Position(), new Position(), 20);
    var unit = owner.getScene().getPlayerSummon();
    var prop = props.prop(owner, 1);
    var npc = npc(owner);
    var monster = fixture.monster(owner, 1);
    trigger(
        owner,
        unit,
        "HitNpcMonsterTrigger",
        prop.getEntityId(),
        npc.getEntityId(),
        monster.getEntityId(),
        prop.getEntityId());
    assertEquals(
        List.of(prop.getEntityId(), monster.getEntityId()),
        notified(owner, unit, "HitNpcMonsterTrigger"));
  }
}
