package org.hkrpg.server.gameplay;

import com.google.gson.JsonObject;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.game.player.Player;
import java.util.Map;
import org.hkrpg.server.GameDiagnostics;

/** Original obsolete portals, independently checked against the installed 4.6 client. */
public final class UnavailableRaidEntrances {
  private record Source(int floor, int group, int raid, String fingerprint) {}
  private static final Map<String, Source> SOURCES = Map.of(
      "LevelArea_P20121_F20121001_G92", new Source(20121001, 92, 4012102,
          "caa2cbdb6ffdf386ee8f7730f9f2e1830074a29a923097cc7b46a86ed892d55c"),
      "LevelArea_P20134_F20134001_G15", new Source(20134001, 15, 4010102,
          "732f03ad27167c79fd42d12a768ca63280c10da824347e9b87bc549495962345"));

  static void markSource(GroupInfo group, JsonObject raw) {
    var area = raw.get("AreaAnchorName");
    if (area == null || !area.isJsonPrimitive() || !area.getAsJsonPrimitive().isString()) return;
    var source = SOURCES.get(area.getAsString());
    if (source != null && source.fingerprint().equals(TimelineActivityRules.fingerprint(raw)))
      group.setUnavailableRaidSourceFloor(source.floor());
  }

  public static boolean unavailable(Player player, int plane, int floor, GroupInfo group) {
    if (player == null || group == null || plane != floor / 1000
        || group.getUnavailableRaidSourceFloor() != floor
        || player.getSession() == null || !player.getSession().getProtocol().experimental()) return false;
    for (var source : SOURCES.values()) {
      if (source.floor() != floor || source.group() != group.getId()) continue;
      if (GameplayDefinitions.raids.values().stream().anyMatch(raid -> raid.RaidID() == source.raid()))
        return false;
      GameDiagnostics.unavailableRaid(player, plane, floor, source.group(), source.raid());
      return true;
    }
    return false;
  }

  private UnavailableRaidEntrances() {}
}
