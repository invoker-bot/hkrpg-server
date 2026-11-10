package org.hkrpg.server.gameplay;

import com.google.gson.JsonObject;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import java.util.Map;
import java.util.Set;

/** Compatibility for the original 4.6 Great Mine placements. */
public final class GreatMineEntrances {
  private static final int PLANE = 20121, FLOOR = 20121001;
  private static final int CART = 11, WHEEL = 53, COUPLER = 67, RIFT = 92;
  private static final int RIFT_RAID = 4012102;
  private static final Set<Integer> CLIENT_GROUPS = Set.of(16, 19, WHEEL, COUPLER);
  private record Source(int group, String fingerprint) {}

  private static final Map<String, Source> SOURCES = Map.of(
      "LevelArea_P20121_F20121001_G11", new Source(CART, "c646445bb4b78f6f82a50748765ee68da029abd05df146d0ad5cda1cf6124e6f"),
      "LevelArea_P20121_F20121001_G16", new Source(16, "5b2603c4d467981b929d0cf119c59f149f7f0f0919f7450410ab092c63ab33d9"),
      "LevelArea_P20121_F20121001_G19", new Source(19, "655584c2bcd56dcef1dd7d5d700b8e1c824ef9770b55e2a407939263c4ac6a66"),
      "LevelArea_P20121_F20121001_G53", new Source(WHEEL, "bb4fc994f08783a7a1b049709f5e08418ec2050bc8bc970b758bae2d5198bf34"),
      "LevelArea_P20121_F20121001_G67", new Source(COUPLER, "4dc26c2637166b9e34c119e8381bacb35c9c75aaedf7473e8ab2032d3e910296"),
      "LevelArea_P20121_F20121001_G92", new Source(RIFT, "caa2cbdb6ffdf386ee8f7730f9f2e1830074a29a923097cc7b46a86ed892d55c"));

  static void markSource(GroupInfo group, JsonObject raw) {
    var area = raw.get("AreaAnchorName");
    if (area == null || !area.isJsonPrimitive() || !area.getAsJsonPrimitive().isString()) return;
    var source = SOURCES.get(area.getAsString());
    if (source != null && source.fingerprint().equals(TimelineActivityRules.fingerprint(raw)))
      group.setGreatMineSourceGroup(source.group());
  }

  private static boolean matches(int plane, int floor, GroupInfo group, int id) {
    return plane == PLANE && floor == FLOOR && group != null
        && group.getId() == id && group.getGreatMineSourceGroup() == id;
  }

  private static boolean nativePlayer(Player player) {
    if (player == null) return false;
    var session = player.getSession();
    return session != null && session.getProtocol().experimental();
  }

  public static boolean unavailableRift(Player player, int plane, int floor, GroupInfo group) {
    return matches(plane, floor, group, RIFT) && nativePlayer(player)
        && GameplayDefinitions.raids.values().stream().noneMatch(raid -> raid.RaidID() == RIFT_RAID);
  }

  public static boolean obsoleteCartDialogues(Scene scene, GroupInfo group) {
    // G11's D125/D126 have no client table entries; E127 requires the mission-owned G53 prop.
    return matches(scene.getPlaneId(), scene.getFloorId(), group, CART)
        && scene.getDimensionId() == 0 && scene.getStoryLineId() == 0
        && nativePlayer(scene.getPlayer());
  }

  public static boolean clientGroup(Scene scene, int id) {
    return CLIENT_GROUPS.contains(id) && ClientNpcGroups.supports(scene)
        && matches(scene.getPlaneId(), scene.getFloorId(), scene.getResourceGroups().get(id), id);
  }

  private GreatMineEntrances() {}
}
