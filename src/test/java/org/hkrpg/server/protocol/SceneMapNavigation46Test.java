package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.excel.MapEntranceExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;

/** Original train entrances share one scene key; the current location and floor are fixtures. */
class SceneMapNavigation46Test {
  static final int PLANE = 10000, FLOOR = 10000000, ENTRY = 1000001;
  final Gson gson = new Gson();
  final Map<Integer, MapEntranceExcel> previous = new HashMap<>();
  FloorInfo previousFloor;
  int currentEntry;
  Gameplay46HandlerTest.Session session;

  @BeforeEach
  void prepare() throws Exception {
    try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
        "/fixtures/scene-map-train-entrances-4.6.json")), StandardCharsets.UTF_8)) {
      for (var row : JsonParser.parseReader(reader).getAsJsonArray()) {
        var entry = gson.fromJson(row, MapEntranceExcel.class);
        previous.put(entry.getId(), GameData.getMapEntranceExcelMap().get(entry.getId()));
        GameData.getMapEntranceExcelMap().add(entry);
      }
    }
    previousFloor = GameData.getFloorInfos().put("P" + PLANE + "_F" + FLOOR,
        gson.fromJson("{\"FloorID\":" + FLOOR + "}", FloorInfo.class));
    var actor = new Player() {
      public int getPlaneId() { return currentEntry == 0 ? 10101 : PLANE; }
      public int getFloorId() { return currentEntry == 0 ? 10101001 : FLOOR; }
      public int getEntryId() { return currentEntry == 0 ? 1010101 : currentEntry; }
      public void save() { fail("A map query must not save the player"); }
    };
    session = new Gameplay46HandlerTest.Session() {
      public Player getPlayer() { return actor; }
    };
  }

  @AfterEach
  void restore() {
    previous.forEach((id, entry) -> {
      if (entry == null) GameData.getMapEntranceExcelMap().keySet().remove(id);
      else GameData.getMapEntranceExcelMap().add(entry);
    });
    String key = "P" + PLANE + "_F" + FLOOR;
    if (previousFloor == null) GameData.getFloorInfos().remove(key);
    else GameData.getFloorInfos().put(key, previousFloor);
  }

  List<?> query(int... floors) throws Exception {
    var request = Gameplay46.builder(session, "GetSceneMapInfoCsReq");
    for (int floor : floors) {
      var identifier = Gameplay46.builder(session, "SceneIdentifier");
      Gameplay46.set(identifier, "floor_id", floor);
      Gameplay46.add(request, "scene_identifiers", identifier.build());
    }
    assertTrue(VersionedPacketHandler.handle(session,
        session.getProtocol().clientCommand("GetSceneMapInfoCsReq"), request.build().toByteArray()));
    return (List<?>) Gameplay46.value(session.response, "scene_map_info");
  }

  DynamicMessage oneMap(int expectedEntry) throws Exception {
    var rows = query(FLOOR);
    assertEquals(Retcode.SUCC.getVal(), Gameplay46.number(session.response, "retcode"));
    assertEquals(1, rows.size(), "The client indexes maps by SceneIdentifier, not entrance ID");
    var map = (DynamicMessage) rows.getFirst();
    assertEquals(expectedEntry, Gameplay46.number(map, "entry_id"));
    var identifier = (DynamicMessage) Gameplay46.value(map, "scene_identifier");
    assertEquals(FLOOR, Gameplay46.number(identifier, "floor_id"));
    return map;
  }

  @Test
  void remoteTrainMapUsesOneOrdinaryEntranceInsteadOfOverwritingItWithStoryAnchors()
      throws Exception {
    var actor = session.getPlayer();
    String before = gson.toJson(actor.getGameplay());
    var map = oneMap(ENTRY);
    assertEquals(0, Gameplay46.number(map, "cur_map_entry_id"));
    assertEquals(List.of(), Gameplay46.value(map, "unlock_teleport_list"));
    assertEquals(List.of(), Gameplay46.value(map, "lighten_section_list"));
    assertNull(actor.getScene());
    assertEquals(before, gson.toJson(actor.getGameplay()));
  }

  @Test
  void currentTrainMapPreservesItsActualStoryEntrance() throws Exception {
    currentEntry = 100000104;
    var map = oneMap(currentEntry);
    assertEquals(currentEntry, Gameplay46.number(map, "cur_map_entry_id"));
  }

  @Test
  void repeatedSceneIdentifiersStillReturnExactlyOneMap() throws Exception {
    var rows = query(FLOOR, FLOOR);
    assertEquals(1, rows.size());
    assertEquals(ENTRY, Gameplay46.number((DynamicMessage) rows.getFirst(), "entry_id"));
  }

  @Test
  void anUnknownFloorRejectsTheWholeBatch() throws Exception {
    assertEquals(List.of(), query(FLOOR, 1999999999));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(),
        Gameplay46.number(session.response, "retcode"));
  }
}
