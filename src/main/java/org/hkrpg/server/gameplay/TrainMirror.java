package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityProp;

/** The original 401010506 performance can resume after its mirror was saved open. */
public final class TrainMirror {
  private static final String GRAPH =
      "Config/Level/Maze/Chapter01/Town/Town_Chapter00_ChallengeMaze 1.json";
  private static final String SAVED = "prop-state:10000:10000000:47:300001";

  public static boolean replaysSavedOpen(Player player, int config, EntityProp prop) {
    if (player == null || prop == null || config != 1010 || player.getBattle() != null) return false;
    var scene = player.getScene();
    if (scene == null || scene != prop.getScene() || scene.getPlayer() != player
        || scene.getPlaneType() != PlaneType.Train || scene.getPlaneId() != 10000
        || scene.getFloorId() != 10000000 || scene.getDimensionId() != 0
        || scene.getStoryLineId() != 0 || prop.getGroupId() != 47 || prop.getInstId() != 300001
        || prop.getPropId() != 2004 || prop.getState() != PropState.Open
        || scene.getEntityById(prop.getEntityId()) != prop) return false;
    var info = prop.getPropInfo();
    var group = scene.getResourceGroups().get(47);
    if (info == null || group == null || group != info.getGroup() || group.getPropList() == null
        || !group.getPropList().contains(info) || !GRAPH.equals(info.getInitLevelGraph())
        || info.getMappingInfoID() != 2206
        || !Integer.valueOf(PropState.Open.getVal()).equals(player.getGameplay().getStoryValues().get(SAVED)))
      return false;
    var row = GameData.getInteractExcelMap().get(config);
    var sub = GameplayDefinitions.subs.get(401010506);
    return row != null && row.getSrcState() == PropState.Closed && row.getTargetState() == PropState.Open
        && row.getItemCostList() != null && row.getItemCostList().isEmpty()
        && row.getInteractCostType() != null
        && (row.getInteractCostType().isBlank() || row.getInteractCostType().equals("None"))
        && sub != null && sub.getId() == 401010506 && sub.getMainMissionID() == 4010105
        && sub.getLevelPlaneID() == 10000 && sub.getLevelFloorID() == 10000000
        && "Talk".equals(sub.getFinishType()) && "Mission_401010506".equals(sub.getParamStr1())
        && sub.getProgress() == 1 && MissionStateRecords.mainDoing(player, 4010105)
        && MissionStateRecords.subCompleted(player, 401010505)
        && !player.getGameplay().getCancelledSubs().contains(401010506)
        && MissionStateRecords.doing(player.getGameplay().getSubMissions().get(401010506), 401010506, 1);
  }

  private TrainMirror() {}
}
