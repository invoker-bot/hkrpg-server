package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import java.util.*;

/** A server scene load freezes already accepted ordinary-world outgoing-floor objectives. */
public final class MissionFloorChanges {
  private record Clause(
      int main,
      int plane,
      int floor,
      int goal,
      String take,
      List<Integer> integers,
      List<String> strings) {}

  private record Target(
      MissionInfo.SubMissionInfo definition,
      MissionInfo graph,
      MainMissionExcel mainDefinition,
      QuestState main,
      QuestState sub,
      Clause clause) {}

  public static final class Context {
    private final Player owner;
    private final Scene source;
    private final MazePlaneExcel sourcePlane, destinationPlane;
    private final FloorInfo sourceFloor, destinationFloor;
    private final int plane, floor, entry;
    private final Map<Integer, Target> targets;

    private Context(Player owner, int plane, int floor, int entry, Map<Integer, Target> targets) {
      this.owner = owner;
      this.source = owner.getScene();
      this.sourcePlane = source == null ? null : source.getExcel();
      this.sourceFloor = source == null ? null : source.getFloorInfo();
      this.destinationPlane = GameData.getMazePlaneExcelMap().get(plane);
      this.destinationFloor = GameData.getFloorInfo(plane, floor);
      this.plane = plane;
      this.floor = floor;
      this.entry = entry;
      this.targets = Map.copyOf(targets);
    }

    boolean matches(Player player, MissionInfo.SubMissionInfo definition) {
      var target = definition == null ? null : targets.get(definition.getId());
      var current = player.getScene();
      if (player != owner
          || target == null
          || current == source
          || !ordinary(player, current)
          || current.getPlaneId() != plane
          || current.getFloorId() != floor
          || current.getEntryId() != entry
          || player.getEntryId() != entry
          || current.getExcel() != destinationPlane
          || current.getFloorInfo() != destinationFloor
          || GameData.getMazePlaneExcelMap().get(plane) != destinationPlane
          || GameData.getFloorInfo(plane, floor) != destinationFloor
          || source.getExcel() != sourcePlane
          || source.getFloorInfo() != sourceFloor
          || GameData.getMazePlaneExcelMap().get(source.getPlaneId()) != sourcePlane
          || GameData.getFloorInfo(source.getPlaneId(), source.getFloorId()) != sourceFloor
          || definition != target.definition()
          || !supported(definition)
          || !clause(definition).equals(target.clause())
          || !eligible(player, definition)
          || GameData.getMissionInfos().get(definition.getMainMissionID()) != target.graph()
          || target.graph().getSubMissionById(definition.getId()) != definition
          || GameData.getMainMissionExcelMap().get(definition.getMainMissionID())
              != target.mainDefinition()) return false;
      return player.getGameplay().getMainMissions().get(definition.getMainMissionID())
              == target.main()
          && player.getGameplay().getSubMissions().get(definition.getId()) == target.sub()
          && MissionStateRecords.doing(target.sub(), definition.getId(), 1)
          && player.getMissionManager().canFinishFromAction(definition.getId());
    }
  }

  public static Context capture(Player player, int plane, int floor, int entry, int dimension) {
    synchronized (player) {
      var targets = new HashMap<Integer, Target>();
      var scene = player.getScene();
      var destination = GameData.getMazePlaneExcelMap().get(plane);
      if (dimension == 0
          && entry > 0
          && ordinary(player, scene)
          && scene.getEntryId() == player.getEntryId()
          && GameData.getMazePlaneExcelMap().get(scene.getPlaneId()) == scene.getExcel()
          && GameData.getFloorInfo(scene.getPlaneId(), scene.getFloorId()) == scene.getFloorInfo()
          && destination != null
          && ordinary(destination.getPlaneType())
          && destination.getFloorIDList() != null
          && destination.getFloorIDList().contains(floor)
          && GameData.getFloorInfo(plane, floor) != null
          && (scene.getPlaneId() != plane || scene.getFloorId() != floor)) {
        for (var state : player.getGameplay().getSubMissions().values()) {
          var c = state == null ? null : GameplayDefinitions.subs.get(state.getQuestId());
          var graph = c == null ? null : GameData.getMissionInfos().get(c.getMainMissionID());
          var main = c == null ? null : GameData.getMainMissionExcelMap().get(c.getMainMissionID());
          if (supported(c)
              && graph != null
              && graph.getMainMissionID() == c.getMainMissionID()
              && main != null
              && graph.getSubMissionById(c.getId()) == c
              && main.getId() == c.getMainMissionID()
              && c.getLevelPlaneID() == scene.getPlaneId()
              && c.getLevelFloorID() == scene.getFloorId()
              && eligible(player, c)
              && MissionStateRecords.doing(state, c.getId(), 1)
              && player.getMissionManager().canFinishFromAction(c.getId()))
            targets.put(
                c.getId(),
                new Target(
                    c,
                    graph,
                    main,
                    player.getGameplay().getMainMissions().get(c.getMainMissionID()),
                    state,
                    clause(c)));
        }
      }
      return new Context(player, plane, floor, entry, targets);
    }
  }

  private static boolean ordinary(PlaneType type) {
    return type == PlaneType.Town || type == PlaneType.Maze || type == PlaneType.Train;
  }

  private static boolean ordinary(Player player, Scene scene) {
    var g = player.getGameplay();
    var rogue = g.getRogue();
    return scene != null
        && scene.getPlayer() == player
        && scene.getFloorInfo() != null
        && scene.getExcel().getId() == scene.getPlaneId()
        && scene.getFloorInfo().getFloorID() == scene.getFloorId()
        && scene.getPlaneId() == player.getPlaneId()
        && scene.getFloorId() == player.getFloorId()
        && scene.getDimensionId() == 0
        && scene.getStoryLineId() == 0
        && ordinary(scene.getPlaneType())
        && player.getInstance() == null
        && player.getBattle() == null
        && g.getCurrentMissionRaid() == 0
        && g.getTrialRun() == null
        && g.getStoryLines().getCurrentId() == 0
        && !g.getStoryLines().isTransitionPending()
        && (rogue == null || !rogue.isInside());
  }

  private static boolean eligible(Player player, MissionInfo.SubMissionInfo c) {
    return switch (c.getTakeType()) {
      case "Auto" -> c.getTakeParamIntList().length == 0 && c.getTakeParamStrList().length == 0;
      case "CustomValue" -> MissionNamedCustomGates.matches(player, c);
      case "AnySequence", "MultiSequence" -> {
        var ids = Arrays.asList(c.getTakeParamIntList());
        boolean valid =
            !ids.isEmpty()
                && c.getTakeParamStrList().length == 0
                && new HashSet<>(ids).size() == ids.size()
                && ids.stream()
                    .allMatch(
                        id ->
                            id > 0
                                && GameplayDefinitions.subs.containsKey(id)
                                && GameplayDefinitions.subs.get(id).getMainMissionID()
                                    == c.getMainMissionID());
        yield valid
            && ("AnySequence".equals(c.getTakeType())
                ? ids.stream().anyMatch(id -> MissionStateRecords.subCompleted(player, id))
                : ids.stream().allMatch(id -> MissionStateRecords.subCompleted(player, id)));
      }
      default -> false;
    };
  }

  private static Clause clause(MissionInfo.SubMissionInfo c) {
    return new Clause(
        c.getMainMissionID(),
        c.getLevelPlaneID(),
        c.getLevelFloorID(),
        c.getProgress(),
        c.getTakeType(),
        List.copyOf(Arrays.asList(c.getTakeParamIntList())),
        List.copyOf(Arrays.asList(c.getTakeParamStrList())));
  }

  private static boolean supported(MissionInfo.SubMissionInfo c) {
    return c != null
        && c.getId() > 0
        && "SwitchFloor".equals(c.getFinishType())
        && "NoPara".equals(c.getParamType())
        && c.getProgress() == 1
        && c.getLevelPlaneID() > 0
        && c.getLevelFloorID() > 0
        && c.getParamInt1() == 0
        && c.getParamInt2() == 0
        && c.getParamInt3() == 0
        && c.getParamStr1() != null
        && c.getParamStr1().isBlank()
        && c.getParamIntList() != null
        && c.getParamIntList().length == 0
        && c.getParamItemList() != null
        && c.getParamItemList().isEmpty()
        && c.getTakeType() != null
        && c.getTakeParamIntList() != null
        && Arrays.stream(c.getTakeParamIntList()).noneMatch(Objects::isNull)
        && c.getTakeParamStrList() != null
        && Arrays.stream(c.getTakeParamStrList()).noneMatch(Objects::isNull)
        && c.getIgnoreVerseParamList() != null
        && c.getIgnoreVerseParamList().isEmpty()
        && c.getStoryLineID() == 0
        && c.getStoryLineIDList() != null
        && c.getStoryLineIDList().isEmpty()
        && c.getMazePlaneID() == 0
        && c.getMazeFloorID() == 0
        && !c.isBackTrack()
        && c.getTask() == null
        && c.getStoryTrialTask() == null;
  }

  private MissionFloorChanges() {}
}
