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
      assertFalse(entry.getKey().isRepeated(), "No unlocked records or claimed rewards in an inactive profile");
      assertInstanceOf(DynamicMessage.class, entry.getValue(), "All scalar values must retain their unplayed defaults");
      noInventedProgress((DynamicMessage) entry.getValue());
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
