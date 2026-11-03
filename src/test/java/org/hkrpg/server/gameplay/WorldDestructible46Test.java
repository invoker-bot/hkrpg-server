package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import emu.lunarcore.server.packet.CmdId;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** One independently reported regression per original placement; source groups load once in order. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorldDestructible46Test {
  @TempDir Path root;
  WorldDestructibleFixture active;
  String group;
  int loaded;

  @BeforeAll void storage() throws Exception { BookPickup46Test.isolatedStorage(); }
  @AfterAll void cleanup() throws Exception {
    try { if (active != null) active.close(); }
    finally { BookPickup46Test.closeStorage(); }
  }

  @TestFactory Stream<DynamicTest> allAuthoredOrdinaryWorldPlacementsDeliverAndSurviveReload() {
    var rows = WorldDestructibleFixture.placements();
    assertEquals(1895, rows.size());
    assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b", WorldDestructibleFixture.SOURCE.get("sourceCommit").getAsString());
    var counts = new TreeMap<Integer, Integer>();
    rows.forEach(r -> counts.merge(r.get("world").getAsInt(), 1, Integer::sum));
    assertEquals(Map.of(301,173,401,410,501,962,601,350), counts);
    return rows.stream().map(row -> DynamicTest.dynamicTest(
        "world="+row.get("world")+" plane="+row.get("plane")+" group="+row.get("group")+" instance="+row.get("instance"),
        () -> verify(row)));
  }

  void verify(JsonObject row) throws Exception {
    String key = row.get("groupPath").getAsString();
    if (!key.equals(group)) {
      if (active != null) { active.close(); active = null; }
      active = new WorldDestructibleFixture(root.resolve("source-" + loaded++), row);
      group = key;
    }
    var prop = active.prop(row);
    assertNotNull(prop, "The complete original source placement did not load");
    assertEquals(row.get("prop").getAsInt(), prop.getPropId());
    assertEquals(row.get("event").getAsInt(), prop.getPropInfo().getEventID());
    active.actor.getPos().set(prop.getPos());
    int credits = active.actor.getScoin(), metal = active.metal(), grants = active.actor.grants;
    long hints = active.actor.packets.stream().filter(p -> p.getCmdId() == CmdId.ScenePlaneEventScNotify).count();
    assertTrue(active.actor.scene.destroyProp(prop));
    assertEquals(credits + 100, active.actor.getScoin(), "The authored prop disappeared without its credit drop");
    assertEquals(metal + 1, active.metal());
    assertEquals(grants + 1, active.actor.grants);
    assertEquals(hints + 1, active.actor.packets.stream().filter(p -> p.getCmdId() == CmdId.ScenePlaneEventScNotify).count());
    assertFalse(active.actor.scene.destroyProp(prop));
    active.reload();
    assertNull(active.prop(row), "A paid ordinary prop respawned on the same day");
    assertEquals(credits + 100, active.actor.getScoin());
    assertEquals(metal + 1, active.metal());
    String receipt = active.plane + ":" + active.floor + ":" + row.get("group").getAsInt() + ":" + row.get("instance").getAsInt();
    assertTrue(active.actor.getGameplay().getDestructibleRewards().getReceipts().get(receipt).isGranted());
    assertEquals(0, active.actor.grants);
  }
}
