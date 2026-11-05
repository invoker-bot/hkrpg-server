package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;

/** Unlock and interaction for the authored workbench in the Astral Express parlor car. */
public final class TrainWorkbench {
  private static final int GROUP = 30;
  private static final String GRAPH = "Config/Level/Maze/Chapter00/Train/Train_Chapter00_";

  private static boolean parlor(Scene scene) {
    return scene != null && scene.getPlaneType() == PlaneType.Train
        && scene.getPlaneId() == 10000 && scene.getFloorId() == 10000000
        && scene.getFloorInfo() != null;
  }

  public static boolean applies(EntityProp prop) {
    if (prop == null || prop.getGroupId() != GROUP || prop.getInstId() != 300002
        || prop.getPropId() != 2002 || !parlor(prop.getScene())) return false;
    var info = prop.getPropInfo();
    var group = prop.getScene().getResourceGroups().get(GROUP);
    return info != null && group != null && group == info.getGroup()
        && group.getPropList() != null && group.getPropList().contains(info)
        && (GRAPH + "FunctionProp_Unlock.json").equals(group.getLevelGraph())
        && (GRAPH + "Workbench.json").equals(info.getInitLevelGraph())
        && info.getState() == PropState.Locked && info.getMappingInfoID() == 2243;
  }

  private static Retcode unlock(Player player) {
    // FuncUnlockData 4100. The workbench script opens its UI in the Closed state.
    if (player.getLevel() < 14) return Retcode.PLAYER_LEVEL_NOT_MEET;
    return MissionStateRecords.mainCompleted(player, 1010403)
        ? Retcode.SUCC : Retcode.PLOT_NOT_UNLOCK;
  }

  private static boolean nativeClient(Scene scene) {
    var session = scene.getPlayer().getSession();
    return session != null && session.getProtocol().experimental();
  }

  private static void project(EntityProp prop, boolean notify) {
    var state = unlock(prop.getScene().getPlayer()) == Retcode.SUCC
        ? PropState.Closed : PropState.Locked;
    prop.setState(state, notify);
  }

  public static void restore(EntityProp prop) {
    if (applies(prop) && nativeClient(prop.getScene())) project(prop, false);
  }

  public static void reconcile(Scene scene) {
    if (!parlor(scene) || !nativeClient(scene)) return;
    synchronized (scene.getPlayer()) {
      if (scene.getPlayer().getScene() != scene) return;
      for (var prop : scene.getEntitiesByGroup(EntityProp.class, GROUP))
        if (applies(prop)) project(prop, scene.isLoaded());
    }
  }

  /** Called for the matched prop after the native wire and current entity checks. */
  public static Retcode interact(EntityProp prop, int config) {
    var player = prop.getScene().getPlayer();
    var row = GameData.getInteractExcelMap().get(config);
    if (row == null) return Retcode.INTERACT_CONFIG_NOT_EXIST;
    if (player.getBattle() != null) return Retcode.SYSTEM_BUSY;
    if (config != 1100 || row.getSrcState() != PropState.Open || row.getTargetState() != PropState.Closed
        || row.getItemCostList() == null || !row.getItemCostList().isEmpty()
        || row.getInteractCostType() == null
        || !(row.getInteractCostType().isBlank() || row.getInteractCostType().equals("None")))
      return Retcode.INTERACT_TYPE_INVALID;
    if (!prop.getExcel().getPropStateList().contains(PropState.Closed))
      return Retcode.UNSUPPORTED_PROP_STATE;
    var result = unlock(player);
    // The same confirmation is safe before or after the usable state reaches the client.
    if (result == Retcode.SUCC) prop.setState(PropState.Closed);
    return result;
  }

  private TrainWorkbench() {}
}
