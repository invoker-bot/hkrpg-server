package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.UnknownFieldSet;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Replay the native area-presence visibility gate against the original initial-room catalogue. */
class TrainPartyInitialRoom46Test {
  record Query(PlayerReturnInitial46Test.Session session, DynamicMessage reply) {}

  static Object field(DynamicMessage value, String name) {
    return value.getField(value.getDescriptorForType().findFieldByName(name));
  }

  static DynamicMessage child(DynamicMessage value, String name) {
    return (DynamicMessage) field(value, name);
  }

  static List<DynamicMessage> areas(DynamicMessage reply) {
    var info = child(child(reply, "train_party_data"), "train_party_info");
    return ((List<?>) field(info, "area_list")).stream().map(DynamicMessage.class::cast).toList();
  }

  static Query query() throws Exception {
    var session = new PlayerReturnInitial46Test.Session();
    String before = new Gson().toJson(session.player.getGameplay());
    assertTrue(VersionedPacketHandler.handle(session, 8034, new byte[0]));
    assertEquals(1, session.nativePackets.size());
    assertEquals(8025, session.nativePackets.getFirst().cmdId());
    assertEquals(before, new Gson().toJson(session.player.getGameplay()),
        "Initial room display must not rewrite construction, inventory or mission state");
    return new Query(session, session.bridge.clientSchema().parse(
        "TrainPartyGetDataScRsp", session.nativePackets.getFirst().body()));
  }

  static List<JsonObject> originalRoots() throws Exception {
    try (var input = new InputStreamReader(TrainPartyInitialRoom46Test.class.getResourceAsStream(
        "/fixtures/train-party-initial-area-4.6.json"), StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(input).getAsJsonObject().getAsJsonArray("records").asList()
          .stream().map(JsonElement::getAsJsonObject)
          .filter(row -> !row.has("RequireAreaID") || row.get("RequireAreaID").getAsInt() == 0)
          .toList();
    }
  }

  @Test
  void originalInitialRoomRemainsVisibleAtZeroProgressInsteadOfHidingEveryBuildingBlock()
      throws Exception {
    var roots = originalRoots();
    assertEquals(List.of(12), roots.stream().map(r -> r.get("ID").getAsInt()).toList());
    assertEquals("Bar_2F_Bedroom", roots.getFirst().getAsJsonArray("ShowBlockList").get(0).getAsString());
    var actual = areas(query().reply());
    // The native manager resets IsUnlock=false, then sets it true only for received area IDs.
    assertEquals(roots.stream().map(r -> r.get("ID").getAsInt()).toList(),
        actual.stream().map(a -> (Integer) field(a, "area_id")).toList(),
        "Empty areas leave the original room's model block hidden");
    assertEquals(0, field(actual.getFirst(), "progress"));
  }

  @Test
  void roomReferenceHasNoFinishedStepsFurnitureRewardsFundsOrDependentRoomUnlocks()
      throws Exception {
    var reply = query().reply();
    var data = child(reply, "train_party_data");
    var info = child(data, "train_party_info");
    var actual = areas(reply);
    assertEquals(1, actual.size());
    assertEquals(12, field(actual.getFirst(), "area_id"));
    assertEquals(Set.of("area_id"), actual.getFirst().getAllFields().keySet().stream()
        .map(f -> f.getName()).collect(java.util.stream.Collectors.toSet()));
    assertEquals(Set.of("area_list"), info.getAllFields().keySet().stream()
        .map(f -> f.getName()).collect(java.util.stream.Collectors.toSet()));
    assertEquals(1, field(data, "unlock_area_num")); // Required original initial turn reference.
    assertEquals(0, field(data, "record_id"));
    assertFalse(data.hasField(data.getDescriptorForType().findFieldByName("HCJJPLKELGC")));
  }

  @Test
  void physicalNativeFieldsCarryTheInitialRoomThroughTheExistingPacketEncoder() throws Exception {
    var query = query();
    var body = UnknownFieldSet.parseFrom(query.session().nativePackets.getFirst().body());
    var data = UnknownFieldSet.parseFrom(body.getField(7).getLengthDelimitedList().getFirst());
    var info = UnknownFieldSet.parseFrom(data.getField(2).getLengthDelimitedList().getFirst());
    var rooms = info.getField(8).getLengthDelimitedList();
    assertEquals(1, rooms.size());
    var room = UnknownFieldSet.parseFrom(rooms.getFirst());
    assertEquals(List.of(12L), room.getField(9).getVarintList());
    assertFalse(room.hasField(10), "Progress remains protobuf zero, not completed construction");
  }
}
