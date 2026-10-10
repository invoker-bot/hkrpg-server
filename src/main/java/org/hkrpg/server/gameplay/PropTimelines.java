package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.proto.PropTimelineInfoOuterClass.PropTimelineInfo;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Validated reports are durable before entity notifications or backtracking mission refresh. */
public final class PropTimelines {
  public static boolean tagged(PropInfo info) {
    try {
      if (info == null
          || info.isIsDelete()
          || info.isIsClientOnly()
          || !info.isLoadOnInitial()
          || info.getID() <= 0
          || info.getPropID() <= 0) return false;
      var tags = info.getTagOverrideComponents();
      if (tags == null || !tags.has("TagComponents") || !tags.get("TagComponents").isJsonArray())
        return false;
      int found = 0;
      for (var value : tags.getAsJsonArray("TagComponents")) {
        if (!value.isJsonObject()) return false;
        var component = value.getAsJsonObject();
        if (!component.has("$type") || !component.get("$type").isJsonPrimitive()) return false;
        if (!"RPG.GameCore.PropTagTimeRewindEntityComponent"
            .equals(component.get("$type").getAsString())) continue;
        var tag = component.getAsJsonObject("Tag");
        if (tag == null
            || !"Prop.Component.TimeRewindEntity".equals(tag.get("Tag").getAsString())
            || tag.get("TagHash").getAsBigDecimal().intValueExact() != 532483231
            || !PropTimelineState.stateName(component.get("InitState").getAsString())) return false;
        found++;
      }
      return found == 1;
    } catch (IllegalArgumentException
        | IllegalStateException
        | NullPointerException
        | ArithmeticException invalid) {
      return false;
    }
  }

  private static String source(PropInfo info) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(ResourceLoader.getGson().toJson(info).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static PropInfo original(Scene scene, int group, int instance) {
    if (scene == null || scene.getFloorInfo() == null || group <= 0 || instance <= 0) return null;
    var registered = GameData.getFloorInfo(scene.getPlaneId(), scene.getFloorId());
    if (registered == null || !registered.isGroupInDimension(group, scene.getDimensionId()))
      return null;
    var data = registered.getGroups().get(group);
    if (data == null || data.getId() != group || data.getPropList() == null) return null;
    var matches =
        data.getPropList().stream().filter(p -> p != null && p.getID() == instance).toList();
    return matches.size() == 1 && tagged(matches.getFirst()) ? matches.getFirst() : null;
  }

  private static boolean world(Scene scene) {
    return scene.getPlaneType() == PlaneType.Town || scene.getPlaneType() == PlaneType.Maze;
  }

  private record Scope(
      PropTimelineState state, GameplayState.MissionRaidRun raid, QuestState main) {}

  private static Scope scope(Scene scene, boolean constructing) {
    var player = scene.getPlayer();
    if (world(scene)) return new Scope(player.getGameplay().getPropTimelines(), null, null);
    if (scene.getPlaneType() != PlaneType.Raid) return null;
    var manager = new MissionRaid(player);
    var run = manager.current();
    if (run == null
        || !manager.owns(run.getMainMission())
        || !constructing && !manager.activeFor(run.getMainMission())) return null;
    if (run.getPlane() != scene.getPlaneId() || run.getFloor() != scene.getFloorId()) return null;
    var main = player.getGameplay().getMainMissions().get(run.getMainMission());
    if (!MissionStateRecords.mainDoing(player, run.getMainMission())) return null;
    return new Scope(run.getPropTimelines(), run, main);
  }

  private static String key(Scene scene, int group, int instance) {
    return scene.getPlaneId()
        + ":"
        + scene.getFloorId()
        + ":"
        + scene.getDimensionId()
        + ":"
        + scene.getStoryLineId()
        + ":"
        + group
        + ":"
        + instance;
  }

  private static boolean matches(PropTimelineState.Snapshot stored, PropInfo info, Scope scope) {
    return stored != null
        && stored.getProp() == info.getPropID()
        && stored.getSource().equals(source(info))
        && (scope.main() == null
            ? stored.getAcceptance().isEmpty()
            : Objects.equals(stored.getAcceptance(), scope.main().getObservationKey()));
  }

  private static PropTimelineInfo proto(String state) {
    return PropTimelineInfo.newInstance().setCOOGIPJIPIA(state.getBytes(StandardCharsets.US_ASCII));
  }

  public static void restore(EntityProp prop) {
    if (!tagged(prop.getPropInfo())) return;
    var scene = prop.getScene();
    var player = scene.getPlayer();
    synchronized (player) {
      var info = original(scene, prop.getGroupId(), prop.getInstId());
      var scope = scope(scene, true);
      if (info == null
          || prop.getPropId() != info.getPropID()
          || scope == null
          || !scope.state().valid()) return;
      var stored = scope.state().get(key(scene, prop.getGroupId(), prop.getInstId()));
      if (matches(stored, info, scope)) prop.setTimelineInfo(proto(stored.getState()), false);
    }
  }

  public static Retcode report(Player player, int entityId, long runtimeId, String state) {
    synchronized (player) {
      var scene = player.getScene();
      if (entityId <= 0 || !PropTimelineState.stateName(state)) return Retcode.REQ_PARA_INVALID;
      if (scene == null
          || scene.getPlayer() != player
          || runtimeId <= 0
          || runtimeId != scene.getRuntimeId()
          || scene.getPlaneId() != player.getPlaneId()
          || scene.getFloorId() != player.getFloorId()
          || scene.getEntryId() != player.getEntryId()
          || scene.getStoryLineId() != player.getGameplay().getStoryLines().getCurrentId())
        return Retcode.MISSION_SCENE_NOT_MATCH;
      var entity = scene.getEntityById(entityId);
      if (!(entity instanceof EntityProp prop) || prop.getScene() != scene)
        return Retcode.REQ_PARA_INVALID;
      var info = original(scene, prop.getGroupId(), prop.getInstId());
      if (info == null
          || prop.getPropId() != info.getPropID()
          || prop.getPropInfo() == null
          || !source(info).equals(source(prop.getPropInfo()))) return Retcode.REQ_PARA_INVALID;
      if (player.isInBattle() || player.getGameplay().getStoryLines().isTransitionPending())
        return Retcode.SYSTEM_BUSY;
      var scope = scope(scene, false);
      if (scope == null) return Retcode.MISSION_SCENE_NOT_MATCH;
      if (!scope.state().valid()) return Retcode.PLAYER_DATA_ERROR;
      String previous = scope.main() == null ? null : scope.main().getObservationKey(),
          acceptance = "";
      try {
        if (scope.main() != null) acceptance = scope.main().captureObservationKey();
      } catch (IllegalArgumentException | IllegalStateException corrupt) {
        return Retcode.PLAYER_DATA_ERROR;
      }
      var stored =
          new PropTimelineState.Snapshot(
              scene.getPlaneId(),
              scene.getFloorId(),
              scene.getDimensionId(),
              scene.getStoryLineId(),
              prop.getGroupId(),
              prop.getInstId(),
              prop.getPropId(),
              source(info),
              state,
              acceptance);
      var old = scope.state().get(stored.key());
      if (!matches(old, info, scope) || !state.equals(old.getState())) {
        var next = scope.state().with(stored);
        replace(player, scope, next);
        try {
          player.save();
        } catch (RuntimeException failed) {
          replace(player, scope, scope.state());
          if (scope.main() != null) scope.main().restoreObservationKey(acceptance, previous);
          return Retcode.SYSTEM_BUSY;
        }
      }
      prop.setTimelineInfo(proto(state));
      player.getMissionManager().refresh();
      return Retcode.SUCC;
    }
  }

  private static void replace(Player player, Scope scope, PropTimelineState next) {
    if (scope.raid() == null) player.getGameplay().propTimelines(next);
    else scope.raid().propTimelines(next);
  }

  static boolean satisfied(Player player, MissionInfo.SubMissionInfo condition) {
    var c = condition;
    var scene = player.getScene();
    if (c == null
        || !"TimeLineSetState".equals(c.getFinishType())
        || !"Equal".equals(c.getParamType())
        || !c.isBackTrack()
        || c.getProgress() != 1
        || c.getLevelPlaneID() <= 0
        || c.getLevelFloorID() <= 0
        || c.getParamInt1() <= 0
        || c.getParamInt2() <= 0
        || c.getParamInt3() != 0 && c.getParamInt3() != c.getParamInt2()
        || c.getParamIntList() == null
        || !(c.getParamIntList().length == 0
            || Arrays.equals(c.getParamIntList(), new Integer[] {0}))
        || c.getParamItemList() == null
        || !c.getParamItemList().isEmpty()
        || !PropTimelineState.stateName(c.getParamStr1())
        || scene == null
        || scene.getPlayer() != player
        || scene.getPlaneId() != c.getLevelPlaneID()
        || scene.getFloorId() != c.getLevelFloorID()
        || scene.getDimensionId() != 0
        || scene.getStoryLineId() != 0
        || player.getGameplay().getStoryLines().getCurrentId() != 0
        || player.getGameplay().getStoryLines().isTransitionPending()
        || c.getMazePlaneID() != 0 && c.getMazePlaneID() != scene.getPlaneId()
        || c.getMazeFloorID() != 0 && c.getMazeFloorID() != scene.getFloorId()) return false;
    var info = original(scene, c.getParamInt1(), c.getParamInt2());
    var scope = scope(scene, false);
    if (info == null
        || scope == null
        || !scope.state().valid()
        || scope.raid() != null && scope.raid().getMainMission() != c.getMainMissionID())
      return false;
    var stored = scope.state().get(key(scene, c.getParamInt1(), c.getParamInt2()));
    return matches(stored, info, scope) && stored.getState().equals(c.getParamStr1());
  }

  private PropTimelines() {}
}
