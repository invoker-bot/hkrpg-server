package org.hkrpg.server.gameplay;

import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.util.Objects;

/** Runtime-only acceptance snapshot; a battle cannot consume a later player-document record. */
record MissionBattleAcceptance(
    Player owner,
    int mainId,
    int subId,
    QuestState main,
    QuestState sub,
    int progress,
    String period,
    String definition,
    String script) {
  static MissionBattleAcceptance capture(Player player, MissionInfo.SubMissionInfo config) {
    if (player == null
        || config == null
        || !player.getMissionManager().canFinishFromAction(config.getId())) return null;
    var main = player.getGameplay().getMainMissions().get(config.getMainMissionID());
    var sub = player.getGameplay().getSubMissions().get(config.getId());
    if (!MissionStateRecords.mainDoing(player, config.getMainMissionID())
        || !MissionStateRecords.doing(sub, config.getId(), Math.max(1, config.getProgress())))
      return null;
    return new MissionBattleAcceptance(
        player,
        config.getMainMissionID(),
        config.getId(),
        main,
        sub,
        sub.getProgress(),
        DailyMissionRepeats.mainKey(player, config.getMainMissionID()),
        ResourceLoader.getGson().toJson(config),
        script(config));
  }

  boolean matches(Player player, MissionInfo.SubMissionInfo config) {
    return player == owner
        && config != null
        && config.getId() == subId
        && config.getMainMissionID() == mainId
        && player.getGameplay().getMainMissions().get(mainId) == main
        && player.getGameplay().getSubMissions().get(subId) == sub
        && MissionStateRecords.mainDoing(player, mainId)
        && MissionStateRecords.doing(sub, subId, Math.max(1, config.getProgress()))
        && sub.getProgress() == progress
        && Objects.equals(period, DailyMissionRepeats.mainKey(player, mainId))
        && player.getMissionManager().canFinishFromAction(subId)
        && Objects.equals(definition, ResourceLoader.getGson().toJson(config))
        && Objects.equals(script, script(config));
  }

  private static String script(MissionInfo.SubMissionInfo config) {
    return config.getTask() == null ? "" : config.getTask().getSourceDefinition();
  }
}
