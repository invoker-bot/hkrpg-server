package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import java.io.IOException;
import java.nio.file.*;

/** A retired optional auto branch references E404011800, absent from the native catalog. */
public final class UnavailableMissionScripts {
  static void markSource(Path root, JsonObject raw, MissionInfo mission) throws IOException {
    if (mission.getMainMissionID()!=4040118
        || !TimelineActivityRules.fingerprint(raw).equals(
            "c7e89ebe33c0570014c1fc816baf849ed94c46a64abbeb5af1a75cc9f9f07c85")) return;
    var source=java.util.Arrays.stream(mission.getSubMissionList())
        .filter(sub->sub.getId()==404011802).findFirst().orElse(null);
    if (source==null || source.getTask()==null || source.getTask().getSourceDefinition().isBlank()
        || !TimelineActivityRules.fingerprint(JsonParser.parseString(source.getTask().getSourceDefinition()))
            .equals("ceeef58f55bac702f3b965cd1535d63d769568a092ec32821246263376624fdd")) return;
    var table=root.resolve("ExcelOutput/PerformanceE.json");
    if (!Files.isRegularFile(table)) return;
    try(var reader=Files.newBufferedReader(table)) {
      for(var row:JsonParser.parseReader(reader).getAsJsonArray())
        if(row.getAsJsonObject().get("PerformanceID").getAsInt()==404011800) return;
    }
    // The pinned main graph starts 801 and finishes 803 via 804/805; none depends on 802.
    source.setUnavailableNativeScript(true);
  }

  static boolean suppressed(Player player, MissionInfo.SubMissionInfo source) {
    if (source==null || !source.isUnavailableNativeScript() || player.getSession()==null
        || !player.getSession().getProtocol().experimental()) return false;
    org.hkrpg.server.GameDiagnostics.missionScriptUnavailable(player,source.getMainMissionID(),source.getId(),404011800);
    return true;
  }

  private UnavailableMissionScripts() {}
}
