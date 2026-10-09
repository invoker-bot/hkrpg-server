package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import java.util.*;

/** Named former-value triples require the authored completed source dialogue, not a raw value. */
final class MissionNamedCustomGates {
  static boolean matches(Player player, MissionInfo.SubMissionInfo target) {
    var args = target.getTakeParamStrList();
    var integers = target.getTakeParamIntList();
    var graph = GameData.getMissionInfos().get(target.getMainMissionID());
    if (!"CustomValue".equals(target.getTakeType())
        || args == null
        || args.length != 3
        || integers == null
        || integers.length != 0
        || Arrays.stream(args).anyMatch(Objects::isNull)
        || args[0].isBlank()
        || graph == null
        || graph.getMainMissionID() != target.getMainMissionID()
        || graph.getSubMissionById(target.getId()) != target
        || graph.getMissionCustomValueList() == null) return false;
    int value, source;
    try {
      value = Integer.parseInt(args[1]);
      source = Integer.parseInt(args[2]);
      if (value < 0
          || source <= 0
          || !args[1].equals(Integer.toString(value))
          || !args[2].equals(Integer.toString(source))) return false;
    } catch (NumberFormatException invalid) {
      return false;
    }
    var definitions =
        Arrays.stream(graph.getMissionCustomValueList())
            .filter(row -> row != null && args[0].equals(row.getName()))
            .toList();
    if (definitions.size() != 1) return false;
    var definition = definitions.getFirst();
    var valid = definition.getValidValueParamList();
    if (!"Former".equals(definition.getType())
        || valid == null
        || valid.length == 0
        || Arrays.stream(valid).anyMatch(Objects::isNull)
        || (definition.isRange()
            ? valid.length != 2
                || valid[0] < 0
                || valid[0] > valid[1]
                || value < valid[0]
                || value > valid[1]
            : !Arrays.asList(valid).contains(value))) return false;
    var authored = GameplayDefinitions.subs.get(source);
    return authored != null
        && authored.getId() == source
        && authored.getMainMissionID() == target.getMainMissionID()
        && graph.getSubMissionById(source) == authored
        && authored.getCustomValueList() != null
        && Arrays.asList(authored.getCustomValueList()).contains(args[0])
        && MissionStateRecords.subCompleted(player, source)
        && Integer.valueOf(value)
            .equals(
                player
                    .getGameplay()
                    .getStoryValues()
                    .get("mission:" + target.getMainMissionID() + ":" + args[0]));
  }

  private MissionNamedCustomGates() {}
}
