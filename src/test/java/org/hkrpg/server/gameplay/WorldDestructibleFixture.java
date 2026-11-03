package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.util.Position;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** Complete original source groups; only player placement and the isolated wallet are fixtures. */
final class WorldDestructibleFixture implements AutoCloseable {
  static final JsonObject SOURCE = read("world-destructibles-4.6.json");
  private static final JsonObject COMMON = read("ordinary-destructibles-4.6.json");
  final WorldChestActivity46Test resources = new WorldChestActivity46Test();
  OrdinaryDestructible46Test.Actor actor;
  final int plane, floor, entry;

  static JsonObject read(String name) {
    try (var in = WorldDestructibleFixture.class.getResourceAsStream("/fixtures/" + name);
        var reader = new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    } catch (Exception e) { throw new IllegalStateException(e); }
  }

  static List<JsonObject> placements() {
    return SOURCE.getAsJsonArray("placements").asList().stream().map(JsonElement::getAsJsonObject).toList();
  }

  static List<JsonObject> policySamples() {
    var samples = new LinkedHashMap<String, JsonObject>();
    for (var row : placements()) {
      int event = row.get("event").getAsInt();
      var source = SOURCE.getAsJsonObject("records").getAsJsonArray("ExcelOutput/PlaneEvent.json")
          .asList().stream().map(JsonElement::getAsJsonObject)
          .filter(r -> r.get("EventID").getAsInt() == event).findFirst().orElseThrow();
      String key = row.get("world") + ":" + row.get("prop") + ":" + source.get("DropList");
      samples.putIfAbsent(key, row);
    }
    return List.copyOf(samples.values());
  }

  WorldDestructibleFixture(Path root, JsonObject row) throws Exception {
    plane = row.get("plane").getAsInt(); floor = row.get("floor").getAsInt(); entry = row.get("entry").getAsInt();
    resources.root = root;
    var tables = resources.source.getAsJsonObject("records");
    for (var source : List.of(COMMON, SOURCE))
      for (var table : source.getAsJsonObject("records").entrySet()) {
        if (tables.has(table.getKey())) tables.getAsJsonArray(table.getKey()).addAll(table.getValue().getAsJsonArray());
        else tables.add(table.getKey(), table.getValue());
      }
    String groupPath = row.get("groupPath").getAsString();
    resources.source.getAsJsonObject("groups").add(groupPath, SOURCE.getAsJsonObject("groups").get(groupPath));
    String floorPath = "Config/LevelOutput/RuntimeFloor/P" + plane + "_F" + floor + ".json";
    resources.source.getAsJsonObject("floors").add(floorPath, SOURCE.getAsJsonObject("floors").get(floorPath));
    OrdinaryDestructibles.clock = Clock.fixed(Instant.parse("2026-11-02T03:00:00Z"), ZoneOffset.UTC);
    resources.load();
    fresh();
  }

  private void fresh() throws Exception {
    actor = new OrdinaryDestructible46Test.Actor();
    DailyMissionAcceptance46Test.set(actor, "uid", 123456897);
    DailyMissionAcceptance46Test.set(actor, "name", "world-box-fixture");
    DailyMissionAcceptance46Test.set(actor, "pos", new Position());
    actor.plane = plane; actor.floor = floor; actor.entry = entry;
    resources.player = actor;
    resources.scene();
  }

  EntityProp prop(JsonObject row) {
    return actor.scene.getEntitiesByGroup(EntityProp.class, row.get("group").getAsInt()).stream()
        .filter(p -> p.getInstId() == row.get("instance").getAsInt()).findFirst().orElse(null);
  }

  int metal() {
    var held = actor.getInventory().getMaterialByItemId(180005);
    return held == null ? 0 : held.getCount();
  }

  void reload() throws Exception {
    var stored = BookPickup46Test.database.getObjectByField(Player.class, "_id", actor.getUid());
    assertNotNull(stored);
    fresh();
    DailyMissionAcceptance46Test.set(actor, "gameplay", stored.getGameplay());
    DailyMissionAcceptance46Test.set(actor, "scoin", stored.getScoin());
    for (var item : BookPickup46Test.database.getObjects(GameItem.class).toList())
      if (item.getOwnerUid() == actor.getUid()) assertTrue(actor.getInventory().loadItem(item));
    resources.scene();
  }

  @Override public void close() throws Exception {
    try { resources.restore(); }
    finally {
      OrdinaryDestructibles.clock = Clock.systemUTC();
      BookPickup46Test.database.getDatabase().drop();
    }
  }
}
