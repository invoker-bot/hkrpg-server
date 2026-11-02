package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import org.junit.jupiter.api.Test;

class LoginBootstrapNested46Test {
  private static DynamicMessage child(DynamicMessage parent, String name) {
    var field = parent.getDescriptorForType().findFieldByName(name);
    assertNotNull(field);
    assertTrue(parent.hasField(field), "Native callback dereferences " + parent.getDescriptorForType().getName() + "." + name);
    return (DynamicMessage) parent.getField(field);
  }

  static void noInventedProgress(DynamicMessage value) {
    for (var entry : value.getAllFields().entrySet()) {
      if (entry.getKey().isRepeated()
          && value.getDescriptorForType().getName().equals("TrainPartyInfo")
          && entry.getKey().getName().equals("area_list")) {
        // The original root room is a required display reference, with no earned progress.
        var rooms = (java.util.List<?>) entry.getValue();
        assertEquals(1, rooms.size());
        var room = (DynamicMessage) rooms.getFirst();
        assertEquals(1, room.getAllFields().size());
        assertEquals(12, InitialData46Test.field(room, "area_id"));
        assertEquals(0, InitialData46Test.field(room, "progress"));
        continue;
      }
      assertFalse(entry.getKey().isRepeated(), "No unlocked records or claimed rewards in an inactive profile");
      if (entry.getValue() instanceof DynamicMessage nested) noInventedProgress(nested);
      else {
        // The native callbacks require real configuration IDs, even with zero progress.
        String key = value.getDescriptorForType().getName() + "." + entry.getKey().getName();
        int expected = switch (key) {
          case "TrainPartyData.unlock_area_num", "GridFightStaticGameInfo.season",
              "GridFightStaticGameInfo.division_id" -> 1;
          case "GridFightStaticGameInfo.sub_season" -> 4;
          default -> fail("Unexpected scalar in an inactive profile: " + key);
        };
        assertEquals(expected, entry.getValue());
      }
    }
  }

  @Test void gridFightRequiresStaticExperienceAndBothTalentContainersEvenWithoutAnActiveRun()
      throws Exception {
    var session = new PlayerReturnInitial46Test.Session();
    assertTrue(VersionedPacketHandler.handle(session, 8680, new byte[0]));
    var reply = session.bridge.clientSchema().parse("GridFightGetDataScRsp", session.nativePackets.getFirst().body());
    var system = child(reply, "system_info");
    var persistent = child(system, "permanent_info");
    child(persistent, "talent");
    var initial = child(system, "static_info");
    child(initial, "exp");
    child(initial, "season_talent");
    assertFalse(reply.hasField(reply.getDescriptorForType().findFieldByName("current_info")));
    noInventedProgress(reply);
  }

  @Test void trainPartyRequiresItsBuildProfileWithoutStartingAnActivity() throws Exception {
    var session = new PlayerReturnInitial46Test.Session();
    assertTrue(VersionedPacketHandler.handle(session, 8034, new byte[0]));
    var reply = session.bridge.clientSchema().parse("TrainPartyGetDataScRsp", session.nativePackets.getFirst().body());
    var data = child(reply, "train_party_data");
    child(data, "train_party_info");
    assertFalse(data.hasField(data.getDescriptorForType().findFieldByName("HCJJPLKELGC")),
        "Optional gameplay state must remain absent for an inactive party");
    noInventedProgress(reply);
  }
}
