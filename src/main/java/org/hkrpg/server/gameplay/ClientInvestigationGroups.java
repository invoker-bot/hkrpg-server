package org.hkrpg.server.gameplay;

import com.google.gson.JsonObject;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.game.scene.Scene;
import java.util.Map;

/** Prop-only dialogue groups verified against the native 4.6 map data. */
public final class ClientInvestigationGroups {
  private record Source(int groupId, String fingerprint) {}

  private static final Map<Integer, Source> SOURCES = Map.of(
      10101002, new Source(2, "3cb086a06ebc835b5ecdf9ae63e883e2dfc1e19d09de35ff4455e76d50b93d69"),
      10101004, new Source(2, "b232121c3367c18432227bb969cc343ca841b86e2bebde9203b796b4bd140748"),
      10101005, new Source(1, "fcc14f6618caff0ba53dda28a85244ef3804503db9c9c2d627a1cfbba09a7094"),
      10101007, new Source(2, "ae71eecdf6299fe1a5ca49691e82da9c3faa4b809693cde2d5c0fa66e42fce1f"),
      20002001, new Source(92, "90c6819edbf58be40232faf4e00c1c05b197518e88e53f17c87e97820bbc8a89"),
      20003001, new Source(106, "5d871fa3181281bba26342edcc19be88f0764434f83f4f05578caf5c45fe818e"));

  static void markSource(GroupInfo group, JsonObject raw) {
    if (!"Investigative".equalsIgnoreCase(group.getGroupName())) return;
    String fingerprint = TimelineActivityRules.fingerprint(raw);
    for (var candidate : SOURCES.entrySet()) {
      if (candidate.getValue().fingerprint().equals(fingerprint)) {
        group.setClientInvestigationFloor(candidate.getKey());
        return;
      }
    }
  }

  public static boolean clientGroup(Scene scene, int groupId) {
    if (!ClientNpcGroups.supports(scene)) return false;
    int floor = scene.getFloorId();
    var source = SOURCES.get(floor);
    if (source == null || source.groupId() != groupId || scene.getPlaneId() != floor / 1000) return false;
    var group = scene.getResourceGroups().get(groupId);
    return group != null && group.getClientInvestigationFloor() == floor;
  }

  private ClientInvestigationGroups() {}
}
