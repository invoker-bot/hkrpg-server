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
      return StoryDoors.checkCost(prop, config);
    return Retcode.SUCC;
  }

  public static Retcode validateNative(Player player, int config, int entity) {
    var result = validate(player, config, entity);
    if (result != Retcode.SUCC) {
      if (player.getScene() != null
          && player.getScene().getEntityById(entity) instanceof EntityProp reported
          && (RobotHunts.acknowledgesChestReport(player, config, reported)
              || ControllerFinishRewards.acknowledgesChestReport(player, config, reported)
              || OraclePuzzleRewards.acknowledgesChestReport(player, config, reported))) return Retcode.SUCC;
      if (result == Retcode.UNSUPPORTED_PROP_STATE && player.getScene() != null
          && player.getScene().getEntityById(entity) instanceof EntityProp repeated
          && PadPuzzleBridges.acknowledgesBridgeOpen(repeated, config)) return Retcode.SUCC;
      return result;
    }
    var prop = (EntityProp) player.getScene().getEntityById(entity);
    if (RobotHunts.endpoint(prop)) return RobotHunts.validateFinish(player, config, prop);
    if (ControllerFinishRewards.controller(prop)) return ControllerFinishRewards.validate(player, config, prop);
    if (StationEnergyBridges.applies(prop)) return StationEnergyBridges.validate(prop,config);
    if (BookPickups.applies(prop)) return BookPickups.validate(player, config, prop);
    if (CompatibleMechanisms.controller(prop)) return CompatibleMechanisms.validateControl(player, config, prop);
    var padPuzzle = PadPuzzleBridges.validate(prop, config);
    if (padPuzzle != Retcode.SUCC) return padPuzzle;
    if (WorldMechanisms.controller(prop))
      return WorldMechanisms.validateControl(player, config, prop);
    var doors = StoryDoors.validate(prop, config);
    if (doors != Retcode.SUCC) return doors;
    return switch (prop.getPropType()) {
      case PROP_ORDINARY, PROP_PLATFORM, PROP_ELEVATOR, PROP_SPRING -> Retcode.SUCC;
      case PROP_TREASURE_CHEST -> WorldChests.validate(player, config, entity);
      case PROP_DYNAMIC_POSITION -> GoldenSpears.validate(player, config, entity);
      default -> Retcode.INTERACT_TYPE_INVALID;
    };
  }

  private static boolean persistent(EntityProp prop) {
    var scene = prop.getScene();
    if (scene.getPlaneId() == 10000 && scene.getFloorId() == 10000003 && scene.getDimensionId() == 11001)
      return false;
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
    if (BookPickups.applies(prop)) return;
    if (CompatibleMechanisms.controller(prop)) return;
    if (WorldMechanisms.controller(prop)) return;
    if (GoldenSpears.applies(prop) || WorldChests.golden(prop) || WorldChests.mascot(prop)) return;
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
