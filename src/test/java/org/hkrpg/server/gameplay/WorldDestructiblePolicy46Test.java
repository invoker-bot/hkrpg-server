package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.PropInfo;
import emu.lunarcore.server.packet.CmdId;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class WorldDestructiblePolicy46Test {
  @TempDir Path root;
  WorldDestructibleFixture fixture;

  @BeforeAll static void storage() throws Exception { BookPickup46Test.isolatedStorage(); }
  @AfterAll static void closeStorage() throws Exception { BookPickup46Test.closeStorage(); }
  @AfterEach void restore() throws Exception { if (fixture != null) fixture.close(); }

  static Stream<JsonObject> samples() {
    var rows = WorldDestructibleFixture.policySamples();
    assertEquals(13, rows.size(), "Every original world/family/regional-pool combination must be checked");
    return rows.stream();
  }

  static Stream<Arguments> levels() {
    return samples().flatMap(row -> IntStream.rangeClosed(0,6).mapToObj(level -> Arguments.of(row,level)));
  }

  static Stream<Arguments> faults() {
    return samples().flatMap(row -> Stream.of("foreign-pool","empty-pool","stage","reward","unknown-event","source","item","credits","state")
        .map(fault -> Arguments.of(row,fault)));
  }

  static Stream<Arguments> interrupted() {
    return samples().flatMap(row -> Stream.of("grant","complete").map(fault -> Arguments.of(row,fault)));
  }

  void open(JsonObject row) throws Exception { fixture = new WorldDestructibleFixture(root,row); }

  @ParameterizedTest @MethodSource("levels")
  void everyRegionalPoolAtEveryWorldLevelGrantsPersistsAndEncodesTheActualReward(JsonObject row,int world) throws Exception {
    open(row);
    DailyMissionAcceptance46Test.set(fixture.actor,"worldLevel",world);
    var prop = fixture.prop(row);
    assertNotNull(prop);
    assertTrue(OrdinaryDestructibles.applies(prop));
    assertTrue(fixture.actor.scene.destroyProp(prop));
    assertEquals(100,fixture.actor.getScoin());
    assertEquals(1,fixture.metal());
    var packet=fixture.actor.packets.stream().filter(p->p.getCmdId()==CmdId.ScenePlaneEventScNotify).findFirst().orElseThrow();
    var protocol=ProtocolBridge.forVersion("4.6.0");
    var converted=protocol.outbound(packet.getCmdId(),packet.getData().toByteArray());
    var message=protocol.clientSchema().parse("ScenePlaneEventScNotify",converted.body());
    assertTrue(message.getUnknownFields().asMap().isEmpty());
    var list=(DynamicMessage)message.getField(message.getDescriptorForType().findFieldByNumber(7));
    var items=(List<?>)list.getField(list.getDescriptorForType().findFieldByNumber(2));
    var actual=new TreeMap<Integer,Integer>();
    for (var value:items) {
      var item=(DynamicMessage)value;
      actual.merge((Integer)item.getField(item.getDescriptorForType().findFieldByNumber(15)),
          (Integer)item.getField(item.getDescriptorForType().findFieldByNumber(6)),Integer::sum);
    }
    assertEquals(Map.of(2,100,180005,1),actual);
    fixture.reload();
    assertNull(fixture.prop(row));
    assertEquals(100,fixture.actor.getScoin());
    assertEquals(1,fixture.metal());
  }

  @ParameterizedTest @MethodSource("faults")
  void malformedSourcePoolOrInventoryNeverSilentlyConsumesTheProp(JsonObject row,String fault) throws Exception {
    open(row);
    var prop=fixture.prop(row);
    var event=GameData.getPlaneEventExcelMap().get(row.get("event").getAsInt(),0);
    switch (fault) {
      case "foreign-pool" -> DailyMissionAcceptance46Test.set(event,"DropList",
          JsonParser.parseString(row.get("world").getAsInt()==301?"[2000102,101005]":"[2000102,101003]").getAsJsonArray());
      case "empty-pool" -> DailyMissionAcceptance46Test.set(event,"DropList",new JsonArray());
      case "stage" -> DailyMissionAcceptance46Test.set(event,"StageID",1);
      case "reward" -> DailyMissionAcceptance46Test.set(event,"Reward",101003);
      case "unknown-event" -> DailyMissionAcceptance46Test.set(prop.getPropInfo(),"EventID",99999999);
      case "source" -> {
        var changed=fixture.resources.gson.fromJson(fixture.resources.gson.toJson(prop.getPropInfo()),PropInfo.class);
        DailyMissionAcceptance46Test.set(changed,"EventID",10000); prop.setPropInfo(changed);
      }
      case "item" -> GameData.getItemExcelMap().keySet().remove(180005);
      case "credits" -> DailyMissionAcceptance46Test.set(fixture.actor,"scoin",999999999);
      case "state" -> DailyMissionAcceptance46Test.set(fixture.actor.getGameplay(),"destructibleRewards",null);
    }
    int credits=fixture.actor.getScoin(),saves=fixture.actor.saves;
    assertFalse(fixture.actor.scene.destroyProp(prop));
    assertSame(prop,fixture.prop(row));
    assertEquals(credits,fixture.actor.getScoin());
    assertEquals(saves,fixture.actor.saves);
    assertEquals(0,fixture.actor.grants);
    assertEquals(0,fixture.metal());
  }

  @ParameterizedTest @MethodSource("samples")
  void fullStackAndReservationFailureKeepTheActualPropForRetry(JsonObject row) throws Exception {
    open(row);
    assertTrue(fixture.actor.getInventory().addItem(180005,99999));
    var prop=fixture.prop(row);
    assertFalse(fixture.actor.scene.destroyProp(prop));
    assertSame(prop,fixture.prop(row));
    fixture.actor.getInventory().getMaterialByItemId(180005).setCount(99998);
    fixture.actor.failSave=true;
    assertFalse(fixture.actor.scene.destroyProp(prop));
    assertEquals(0,fixture.actor.grants);
    assertTrue(fixture.actor.getGameplay().getDestructibleRewards().getReceipts().isEmpty());
    fixture.actor.failSave=false;
    assertTrue(fixture.actor.scene.destroyProp(prop));
    assertEquals(100,fixture.actor.getScoin());
    assertEquals(99999,fixture.metal());
  }

  @ParameterizedTest @MethodSource("interrupted")
  void interruptedDeliveryCannotRepeatAfterReloadOrAnotherRefreshDay(JsonObject row,String fault) throws Exception {
    open(row);
    fixture.actor.failGrant=fault.equals("grant");
    fixture.actor.failOnSave=fault.equals("complete")?2:-1;
    assertFalse(fixture.actor.scene.destroyProp(fixture.prop(row)));
    assertNotNull(fixture.prop(row));
    fixture.reload();
    int credits=fixture.actor.getScoin(),metal=fixture.metal();
    OrdinaryDestructibles.clock=Clock.fixed(Instant.parse("2026-11-04T03:00:00Z"),ZoneOffset.UTC);
    fixture.resources.scene();
    assertFalse(fixture.actor.scene.destroyProp(fixture.prop(row)));
    assertEquals(credits,fixture.actor.getScoin()); assertEquals(metal,fixture.metal());
    assertEquals(0,fixture.actor.grants);
    assertFalse(fixture.actor.getGameplay().getDestructibleRewards().getReceipts().values().iterator().next().isGranted());
  }

  @ParameterizedTest @MethodSource("samples")
  void simultaneousHitsGrantOnceAndAdministrativeRemovalGrantsNothing(JsonObject row) throws Exception {
    open(row); var prop=fixture.prop(row);
    try (var pool=Executors.newFixedThreadPool(2)) {
      var results=pool.invokeAll(List.<Callable<Boolean>>of(()->fixture.actor.scene.destroyProp(prop),()->fixture.actor.scene.destroyProp(prop)));
      assertEquals(1,results.stream().filter(f->{try{return f.get();}catch(Exception e){throw new AssertionError(e);}}).count());
    }
    assertEquals(100,fixture.actor.getScoin()); assertEquals(1,fixture.metal());
    assertEquals(1,fixture.actor.grants);
    var another=fixture.actor.scene.getEntitiesByGroup(emu.lunarcore.game.scene.entity.EntityProp.class,row.get("group").getAsInt()).stream().findFirst().orElseThrow();
    fixture.actor.scene.removeEntity(another);
    assertEquals(1,fixture.actor.grants);
  }

  @ParameterizedTest @MethodSource("samples")
  void neutralDestructionMarkerIntentionallyHasNoInventoryReward(JsonObject row) throws Exception {
    open(row); var prop=fixture.prop(row);
    // Isolated variant: the neutral event row is original; the placement event is changed here.
    DailyMissionAcceptance46Test.set(prop.getPropInfo(),"EventID",10000);
    assertFalse(OrdinaryDestructibles.applies(prop));
    assertTrue(fixture.actor.scene.destroyProp(prop));
    assertEquals(0,fixture.actor.getScoin()); assertEquals(0,fixture.actor.grants);
    assertTrue(fixture.actor.getGameplay().getDestructibleRewards().getReceipts().isEmpty());
  }
}
