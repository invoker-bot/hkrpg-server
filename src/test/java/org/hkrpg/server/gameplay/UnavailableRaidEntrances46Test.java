package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;

class UnavailableRaidEntrances46Test {
  private static final int PLANE = 20134, FLOOR = 20134001, GROUP = 15, RAID = 4010102;
  private final Gson gson = ResourceLoader.getGson();
  private final Map<Long, GameplayDefinitions.Raid> savedRaids = new HashMap<>();
  private final ClientNpcOwnership46Test.Actor actor = new ClientNpcOwnership46Test.Actor();
  private FloorInfo savedFloor;
  private PropExcel savedProp;
  private JsonObject source;
  private PropExcel prop;

  @BeforeEach void prepare() throws Exception {
    try (var in = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
        "/fixtures/unavailable-raid-entrances-4.6.json")), StandardCharsets.UTF_8)) {
      var fixture = JsonParser.parseReader(in).getAsJsonObject();
      source = fixture.getAsJsonObject("groups").getAsJsonObject("20134");
      prop = gson.fromJson(fixture.get("prop"), PropExcel.class);
    }
    savedFloor = GameData.getFloorInfos().get("P20134_F20134001");
    savedProp = GameData.getPropExcelMap().get(3001);
    prop.onLoad();
    GameData.getPropExcelMap().add(prop);
    GameplayDefinitions.raids.entrySet().removeIf(e -> {
      if (e.getValue().RaidID() != RAID) return false;
      savedRaids.put(e.getKey(), e.getValue());
      return true;
    });
  }

  @AfterEach void restore() {
    if (savedFloor == null) GameData.getFloorInfos().remove("P20134_F20134001");
    else GameData.getFloorInfos().put("P20134_F20134001", savedFloor);
    GameData.getPropExcelMap().keySet().remove(3001);
    if (savedProp != null) GameData.getPropExcelMap().add(savedProp);
    GameplayDefinitions.raids.values().removeIf(r -> r.RaidID() == RAID);
    GameplayDefinitions.raids.putAll(savedRaids);
  }

  @Test void missingRaidDoesNotPublishTheOriginalPortalEvenAfterExplicitLoad() {
    enter();
    actor.scene.loadGroup(GROUP);
    assertTrue(actor.scene.getEntitiesByGroup(EntityProp.class, GROUP).isEmpty());
  }

  @Test void configuredRaidKeepsItsPortal() {
    var raid = new GameplayDefinitions.Raid(RAID, 0, "TreasureChallenge", "Player",
        "Default", List.of(), List.of(), List.of(), List.of(), false);
    GameplayDefinitions.raids.put(raid.key(), raid);
    enter();
    assertEquals(1, actor.scene.getEntitiesByGroup(EntityProp.class, GROUP).size());
  }

  @Test void modifiedSourceAndLegacyProtocolKeepTheirExistingPlacement() {
    source.getAsJsonArray("PropList").get(0).getAsJsonObject().addProperty("RaidID", 41003);
    enter();
    assertEquals(1, actor.scene.getEntitiesByGroup(EntityProp.class, GROUP).size());
    source.getAsJsonArray("PropList").get(0).getAsJsonObject().addProperty("RaidID", RAID);
    actor.wire = ProtocolBridge.forVersion("4.2.0");
    enter();
    assertEquals(1, actor.scene.getEntitiesByGroup(EntityProp.class, GROUP).size());
  }

  private void enter() {
    var floor = gson.fromJson("""
        {"FloorID":20134001,"GroupInstanceList":[{"ID":15}],
         "DimensionList":[{"GroupIndexList":[0]}]}
        """, FloorInfo.class);
    var group = gson.fromJson(source, GroupInfo.class);
    group.setId(GROUP);
    floor.registerGroup(0, group);
    floor.onLoad();
    GameData.getFloorInfos().put("P20134_F20134001", floor);
    var plane = gson.fromJson("{\"PlaneID\":20134,\"PlaneType\":\"Maze\",\"WorldID\":201}", MazePlaneExcel.class);
    actor.scene = new Scene(actor, plane, FLOOR);
  }
}
