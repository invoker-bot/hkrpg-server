package org.hkrpg.server.gameplay;

import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.enums.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.*;
import java.util.*;

/** Persist a whole ordinary prop transition before sending state changes or mission events. */
public final class ScenePropTransitions {
  public record Result(Retcode retcode, EntityProp prop) {}

  private record Scope(ScenePropState state, GameplayState.MissionRaidRun raid, QuestState main) {}

  public static boolean supported(EntityProp prop) {
    return prop != null
        && (WorldMechanisms.controller(prop)
            || switch (prop.getPropType()) {
              case PROP_ORDINARY, PROP_PLATFORM, PROP_ELEVATOR, PROP_SPRING -> true;
              default -> false;
            });
  }

  public static boolean applies(EntityProp prop) {
    if (!supported(prop)) return false;
    var scene = prop.getScene();
    return scene.getPlaneType() == PlaneType.Town
        || scene.getPlaneType() == PlaneType.Maze
        || scene.getPlaneType() == PlaneType.Raid
            && scene.getPlayer().getGameplay().getCurrentMissionRaid() > 0;
  }

  private static Scope scope(Scene scene, boolean constructing) {
    var player = scene.getPlayer();
    if (scene.getPlaneType() == PlaneType.Town || scene.getPlaneType() == PlaneType.Maze)
      return new Scope(player.getGameplay().getScenePropStates(), null, null);
    if (scene.getPlaneType() != PlaneType.Raid) return null;
    var manager = new MissionRaid(player);
    var run = manager.current();
    if (run == null
        || !manager.owns(run.getMainMission())
        || !constructing && !manager.activeFor(run.getMainMission())
        || run.getPlane() != scene.getPlaneId()
        || run.getFloor() != scene.getFloorId()
        || !(MissionStateRecords.mainDoing(player, run.getMainMission())
            || constructing
                && run.isCompleted()
                && MissionStateRecords.mainCompleted(player, run.getMainMission())
                && manager.completed(run))) return null;
    return new Scope(
        run.getScenePropStates(),
        run,
        player.getGameplay().getMainMissions().get(run.getMainMission()));
  }

  private static PropInfo original(Scene scene, int group, int instance) {
    var floor = GameData.getFloorInfo(scene.getPlaneId(), scene.getFloorId());
    if (floor == null
        || group <= 0
        || instance <= 0
        || !floor.isGroupInDimension(group, scene.getDimensionId())) return null;
    var definition = floor.getGroups(scene.getDimensionId()).get(group);
    if (definition == null || definition.getId() != group || definition.getPropList() == null)
      return null;
    var matches =
        definition.getPropList().stream().filter(p -> p != null && p.getID() == instance).toList();
    if (matches.size() != 1) return null;
    var info = matches.getFirst();
    return info.getPropID() > 0
            && !info.isIsDelete()
            && !info.isIsClientOnly()
            && info.isLoadOnInitial()
        ? info
        : null;
  }

  private static boolean matches(ScenePropState.Snapshot record, PropInfo info, Scope scope) {
    return record != null
        && record.getProp() == info.getPropID()
        && record.getSource().equals(PropTimelines.source(info))
        && (scope.main() == null
            ? record.getAcceptance().isEmpty()
            : Objects.equals(record.getAcceptance(), scope.main().getObservationKey()));
  }

  public static void restore(EntityProp prop) {
    if (!supported(prop)) return;
    var scene = prop.getScene();
    var player = scene.getPlayer();
    synchronized (player) {
      var info = original(scene, prop.getGroupId(), prop.getInstId());
      var scope = scope(scene, true);
      if (info == null
          || prop.getPropId() != info.getPropID()
          || scope == null
          || !scope.state().valid()) return;
      var record = scope.state().get(PropTimelines.key(scene, prop.getGroupId(), prop.getInstId()));
      if (matches(record, info, scope))
        for (var state : prop.getExcel().getPropStateList())
          if (state.getVal() == record.getState()) {
            prop.setState(state, false);
            return;
          }
    }
  }

  private static void replace(Player player, Scope scope, ScenePropState state) {
    if (scope.raid() == null) player.getGameplay().scenePropStates(state);
    else scope.raid().scenePropStates(state);
  }

  public static Result interact(Player player, int config, int entity) {
    synchronized (player) {
      var result = SceneInteractions.validateNative(player, config, entity);
      if (result != Retcode.SUCC) return new Result(result, null);
      var scene = player.getScene();
      var prop = (EntityProp) scene.getEntityById(entity);
      if (scene.getPlayer() != player
          || scene.getPlaneId() != player.getPlaneId()
          || scene.getFloorId() != player.getFloorId()
          || scene.getEntryId() != player.getEntryId()
          || scene.getStoryLineId() != player.getGameplay().getStoryLines().getCurrentId()
          || player.getGameplay().getStoryLines().isTransitionPending())
        return new Result(Retcode.MISSION_SCENE_NOT_MATCH, null);
      var scope = scope(scene, false);
      if (scope == null) return new Result(Retcode.MISSION_SCENE_NOT_MATCH, null);
      if (!scope.state().valid()) return new Result(Retcode.PLAYER_DATA_ERROR, null);
      var target = GameData.getInteractExcelMap().get(config).getTargetState();
      var changed = new LinkedHashMap<EntityProp, PropState>();
      changed.put(prop, prop.getState());
      if (prop.getPropType() == PropType.PROP_ORDINARY && prop.getPropInfo() != null) {
        for (var candidate : scene.getEntitiesByGroup(EntityProp.class, prop.getGroupId())) {
          if (prop.getPropInfo().isCommonConsole()
                  && candidate.getPropType() == PropType.PROP_PLATFORM
              || prop.getPropId() == 104039 && candidate.getPropId() == 104038) {
            if (candidate.getState() != target) changed.put(candidate, candidate.getState());
          }
        }
      }
      var definitions = new LinkedHashMap<EntityProp, PropInfo>();
      for (var candidate : changed.keySet()) {
        var info = original(scene, candidate.getGroupId(), candidate.getInstId());
        if (candidate.getScene() != scene
            || info == null
            || candidate.getPropId() != info.getPropID()
            || candidate.getPropInfo() == null
            || !PropTimelines.source(info).equals(PropTimelines.source(candidate.getPropInfo()))
            || !candidate.getExcel().getPropStateList().contains(target))
          return new Result(Retcode.REQ_PARA_INVALID, null);
        definitions.put(candidate, info);
      }
      String previous = scope.main() == null ? null : scope.main().getObservationKey(),
          acceptance = "";
      try {
        if (scope.main() != null) acceptance = scope.main().captureObservationKey();
      } catch (IllegalStateException | IllegalArgumentException corrupt) {
        return new Result(Retcode.PLAYER_DATA_ERROR, null);
      }
      var snapshots = new ArrayList<ScenePropState.Snapshot>();
      for (var e : definitions.entrySet())
        snapshots.add(
            new ScenePropState.Snapshot(
                scene.getPlaneId(),
                scene.getFloorId(),
                scene.getDimensionId(),
                scene.getStoryLineId(),
                e.getKey().getGroupId(),
                e.getKey().getInstId(),
                e.getKey().getPropId(),
                target.getVal(),
                PropTimelines.source(e.getValue()),
                acceptance));
      var next = scope.state().with(snapshots);
      var previousMechanisms = player.getGameplay().getWorldMechanisms();
      var nextMechanisms = WorldMechanisms.controlProgress(player, config, prop);
      replace(player, scope, next);
      player.getGameplay().worldMechanisms(nextMechanisms);
      for (var candidate : changed.keySet()) candidate.setState(target, false);
      try {
        player.save();
      } catch (RuntimeException failed) {
        replace(player, scope, scope.state());
        player.getGameplay().worldMechanisms(previousMechanisms);
        changed.forEach((candidate, state) -> candidate.setState(state, false));
        if (scope.main() != null) scope.main().restoreObservationKey(acceptance, previous);
        return new Result(Retcode.SYSTEM_BUSY, null);
      }
      var session = player.getSession();
      if (session != null && !session.getProtocol().experimental())
        player.sendPacket(
            new PacketGroupStateChangeScNotify(player.getEntryId(), prop.getGroupId(), target));
      if (scene.isLoaded())
        for (var candidate : changed.keySet())
          player.sendPacket(new PacketSceneGroupRefreshScNotify(scene, candidate, null));
      if (nextMechanisms != previousMechanisms) WorldMechanisms.publish(scene);
      player
          .getMissionManager()
          .onPropInteraction(
              scene.getFloorId(), prop.getGroupId(), prop.getInstId(), prop.getState().getVal());
      return new Result(Retcode.SUCC, prop);
    }
  }

  static boolean satisfied(Player player, MissionInfo.SubMissionInfo c) {
    var scene = player.getScene();
    if (c == null
        || !"PropState".equals(c.getFinishType())
        || !"Equal".equals(c.getParamType())
        || !c.isBackTrack()
        || c.getProgress() != 1
        || c.getLevelPlaneID() <= 0
        || c.getLevelFloorID() <= 0
        || c.getParamInt1() <= 0
        || c.getParamInt2() <= 0
        || c.getParamInt3() < 0
        || c.getParamStr1() == null
        || !c.getParamStr1().isBlank()
        || c.getParamIntList() == null
        || c.getParamIntList().length != 0
        || c.getParamItemList() == null
        || !c.getParamItemList().isEmpty()
        || scene == null
        || scene.getPlayer() != player
        || scene.getPlaneId() != c.getLevelPlaneID()
        || scene.getFloorId() != c.getLevelFloorID()
        || c.getMazePlaneID() != 0 && c.getMazePlaneID() != scene.getPlaneId()
        || c.getMazeFloorID() != 0 && c.getMazeFloorID() != scene.getFloorId()
        || player.getGameplay().getStoryLines().isTransitionPending()) return false;
    var info = original(scene, c.getParamInt1(), c.getParamInt2());
    var scope = scope(scene, false);
    if (info == null || scope == null || !scope.state().valid()) return false;
    var record = scope.state().get(PropTimelines.key(scene, c.getParamInt1(), c.getParamInt2()));
    if (!matches(record, info, scope) || record.getState() != c.getParamInt3()) return false;
    var excel = GameData.getPropExcelMap().get(info.getPropID());
    return excel != null
        && excel.getPropStateList().stream().anyMatch(s -> s.getVal() == record.getState());
  }

  private ScenePropTransitions() {}
}
