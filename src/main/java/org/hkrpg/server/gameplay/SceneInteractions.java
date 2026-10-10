package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;

/** Resource-backed state transitions; unsupported costs and puzzle effects stay pending. */
public final class SceneInteractions {
  public static Retcode validate(Player player, int config, int entity) {
    var scene = player.getScene();
    if (scene == null) return Retcode.NOT_IN_SCENE;
    if (!(scene.getEntityById(entity) instanceof EntityProp prop))
      return Retcode.SCENE_ENTITY_NOT_EXIST;
    var row = GameData.getInteractExcelMap().get(config);
    if (config <= 0 || row == null) return Retcode.INTERACT_CONFIG_NOT_EXIST;
    if (player.getBattle() != null) return Retcode.SYSTEM_BUSY;
    if (row.getSrcState() == null
        || row.getTargetState() == null
        || row.getSrcState() != prop.getState()
        || row.getTargetState() == prop.getState()
        || !prop.getExcel().getPropStateList().contains(row.getTargetState()))
      return Retcode.UNSUPPORTED_PROP_STATE;
    if (row.getItemCostList() == null
        || !row.getItemCostList().isEmpty()
        || row.getInteractCostType() == null
        || !(row.getInteractCostType().isBlank() || row.getInteractCostType().equals("None")))
      return Retcode.INTERACT_TYPE_INVALID;
    return Retcode.SUCC;
  }

  public static Retcode validateNative(Player player, int config, int entity) {
    var result = validate(player, config, entity);
    if (result != Retcode.SUCC) return result;
    var prop = (EntityProp) player.getScene().getEntityById(entity);
    return switch (prop.getPropType()) {
      case PROP_ORDINARY, PROP_PLATFORM, PROP_ELEVATOR, PROP_SPRING -> Retcode.SUCC;
      default -> Retcode.INTERACT_TYPE_INVALID;
    };
  }

  private static boolean persistent(EntityProp prop) {
    return switch (prop.getScene().getPlaneType()) {
      case Town, Maze, Train -> true;
      default -> false;
    };
  }

  private static String key(EntityProp prop) {
    var scene = prop.getScene();
    return "prop-state:"
        + scene.getPlaneId()
        + ":"
        + scene.getFloorId()
        + ":"
        + prop.getGroupId()
        + ":"
        + prop.getInstId();
  }

  public static void persist(EntityProp prop) {
    if (persistent(prop))
      prop.getScene()
          .getPlayer()
          .getGameplay()
          .getStoryValues()
          .put(key(prop), prop.getState().getVal());
  }

  public static void restore(EntityProp prop) {
    if (!persistent(prop)) return;
    var scene = prop.getScene();
    if (ScenePropTransitions.applies(prop)) {
      var states = scene.getPlayer().getGameplay().getScenePropStates();
      if (scene.getDimensionId() != 0
          || scene.getStoryLineId() != 0
          || !states.valid()
          || states
              .getRecords()
              .containsKey(PropTimelines.key(scene, prop.getGroupId(), prop.getInstId()))) return;
    }
    var saved = prop.getScene().getPlayer().getGameplay().getStoryValues().get(key(prop));
    if (saved == null) return;
    for (var state : prop.getExcel().getPropStateList())
      if (state.getVal() == saved) {
        prop.setState(state, false);
        return;
      }
  }

  private SceneInteractions() {}
}
