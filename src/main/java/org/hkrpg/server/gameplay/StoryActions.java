package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Server finish actions from the pinned resource, never instructions supplied by a client. */
public final class StoryActions {
  private final Player player;

  public StoryActions(Player player) {
    this.player = player;
  }

  public Retcode execute(MissionInfo.SubMissionInfo sub) {
    synchronized (player) {
      var cursor = player.getGameplay().getStoryActions().get(sub.getId());
      if (cursor != null && cursor.isPending()) return Retcode.SYSTEM_BUSY;
      var manager = player.getMissionManager();
      if (!manager.beginActions(sub.getId())) return Retcode.SYSTEM_BUSY;
      try {
        return executeChecked(sub);
      } finally {
        manager.endActions(sub.getId());
      }
    }
  }

  public Retcode validate(MissionInfo.SubMissionInfo sub) {
    synchronized (player) {
      return validateSequence(
          sub, new HashSet<>(), new HashMap<>(player.getGameplay().getStoryValues()));
    }
  }

  private Retcode executeChecked(MissionInfo.SubMissionInfo sub) {
    var actions = sub.getFinishActionList();
    // Include referenced finish-action graphs: a direct action may recursively complete a branch.
    var validation =
        validateSequence(
            sub, new HashSet<>(), new HashMap<>(player.getGameplay().getStoryValues()));
    if (validation != Retcode.SUCC) return validation;
    var cursor =
        player
            .getGameplay()
            .getStoryActions()
            .computeIfAbsent(sub.getId(), k -> new GameplayState.ActionCursor());
    if (cursor.isPending()) return Retcode.SYSTEM_BUSY;
    while (cursor.getNext() < actions.length) {
      var action = actions[cursor.getNext()];
      if (variable(action)) {
        try {
          variableResult(sub, action, player.getGameplay().getStoryValues());
        } catch (ArithmeticException invalid) {
          return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
        }
      }
      String key = "story:" + sub.getId() + ":" + cursor.getNext();
      cursor.reserve();
      player.save();
      // Interrupted actions remain reserved for explicit operator reconciliation.
      if (!apply(sub, action, key)) return Retcode.PLAYER_DATA_ERROR;
      cursor.complete();
      player.save();
      if ("ChangeTrainWorldId".equals(action.getType()))
        org.hkrpg.server.protocol.TrainWorld46.sync(player);
    }
    return Retcode.SUCC;
  }

  private Retcode validateSequence(
      MissionInfo.SubMissionInfo sub, Set<Integer> visited, Map<String, Integer> values) {
    var pending = player.getGameplay().getStoryActions().get(sub.getId());
    if (pending != null
        && pending.isPending()
        && !player.getMissionManager().executingActions(sub.getId())) return Retcode.SYSTEM_BUSY;
    if (!visited.add(sub.getId())) return Retcode.SUCC;
    var actions = sub.getFinishActionList();
    for (int index = pending == null ? 0 : pending.getNext(); index < actions.length; index++) {
      var action = actions[index];
      if (!valid(action)) return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
      if (variable(action)) {
        try {
          values.put(variableKey(sub, action), variableResult(sub, action, values));
        } catch (ArithmeticException invalid) {
          return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
        }
      }
      if (!Set.of("delSubMission", "delMission").contains(Objects.toString(action.getType(), "")))
        continue;
      for (int id : action.getParams()) {
        if (!player.getMissionManager().canFinishFromAction(id))
          return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
        var state = player.getMissionManager().sub(id);
        if (state != null && state.getPhase() != emu.lunarcore.game.quest.QuestState.Phase.DOING)
          continue;
        var result = validateSequence(GameplayDefinitions.subs.get(id), visited, values);
        if (result != Retcode.SUCC) return result;
      }
    }
    return Retcode.SUCC;
  }

  private static boolean variable(MissionInfo.FinishAction action) {
    return Set.of("SetCustomValue", "AddCustomValue")
        .contains(Objects.toString(action.getType(), ""));
  }

  private static String variableKey(
      MissionInfo.SubMissionInfo sub, MissionInfo.FinishAction action) {
    return "mission:" + sub.getMainMissionID() + ":" + action.getStrings()[0];
  }

  private static int variableResult(
      MissionInfo.SubMissionInfo sub,
      MissionInfo.FinishAction action,
      Map<String, Integer> values) {
    int value = Integer.parseInt(action.getStrings()[1]);
    return "AddCustomValue".equals(action.getType())
        ? Math.addExact(values.getOrDefault(variableKey(sub, action), 0), value)
        : value;
  }

  private boolean valid(MissionInfo.FinishAction a) {
    var p = a.getParams();
    var s = a.getStrings();
    try {
      return switch (Objects.toString(a.getType(), "")) {
        case "" -> p.length == 0 && s.length == 0;
        case "MoveToAnchor", "EnterEntryIfNotThere" ->
            p.length == 3
                && p[0] > 0
                && p[1] > 0
                && p[2] > 0
                && (("EnterEntryIfNotThere".equals(a.getType()) && player.getEntryId() == p[0])
                    || player.hasSceneAnchor(p[0], p[1], p[2]));
        case "addMissionItem", "addRecoverMissionItem", "delMissionItem" ->
            p.length > 0 && p.length % 2 == 0 && validItems(p);
        case "SetFloorSavedValue" ->
            s.length == 4
                && Integer.parseInt(s[0]) > 0
                && Integer.parseInt(s[1]) > 0
                && !s[2].isBlank()
                && integer(s[3]);
        case "SetCustomValue", "AddCustomValue" ->
            s.length == 2 && !s[0].isBlank() && integer(s[1]);
        case "SetGroupProperty" ->
            s.length == 3
                && player.getScene() != null
                && player.getScene().hasGroupProperty(Integer.parseInt(s[0]), s[1])
                && integer(s[2]);
        case "SetGroupState" ->
            p.length == 2
                && s.length == 0
                && p[1] >= 0
                && player.getScene() != null
                && player.getScene().hasStoryGroup(p[0]);
        case "ChangeLineup" -> s.length == 0 && validLineup(p);
        case "ChangeStoryLine" ->
            p.length == 4
                && s.length == 0
                && player.getStoryLineManager().canChange(p[0], p[1], p[2], p[3]);
        case "delSubMission", "delMission" ->
            p.length > 0
                && s.length == 0
                && Arrays.stream(p).allMatch(GameplayDefinitions.subs::containsKey);
        case "DisableMission" ->
            p.length > 0
                && Arrays.stream(p).allMatch(i -> GameData.getMainMissionExcelMap().get(i) != null);
        case "Recover" -> p.length == 0 && s.length == 0;
        case "ActivateAnchor" ->
            p.length == 3 && s.length == 0 && SceneAnchors.find(p[0], p[1], p[2]) != null;
        case "ChangeTrainWorldId" -> p.length == 1 && s.length == 0 && TrainWorlds.known(p[0]);
        default -> false;
      };
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private boolean validLineup(int[] p) {
    if (p.length != 5
        || player.getCurrentLineup() == null
        || player.getCurrentLineup().isExtraLineup()
        || player.getScene() == null) return false;
    var ids = Arrays.stream(p).limit(4).filter(id -> id != 0).boxed().toList();
    return !ids.isEmpty()
        && new HashSet<>(ids).size() == ids.size()
        && ids.contains(p[4])
        && ids.stream()
            .allMatch(
                id ->
                    player.getAvatarById(id) != null
                        && player.getAvatarById(id).getAvatarId() == id
                        && player.getAvatarById(id).getAvatarType()
                            == emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType
                                .AVATAR_FORMAL_TYPE);
  }

  private static boolean integer(String s) {
    Integer.parseInt(s);
    return true;
  }

  private static boolean validItems(int[] p) {
    for (int i = 0; i < p.length; i += 2)
      if (p[i + 1] <= 0 || GameData.getItemExcelMap().get(p[i]) == null) return false;
    return true;
  }

  private boolean apply(
      MissionInfo.SubMissionInfo sub, MissionInfo.FinishAction a, String receipt) {
    var p = a.getParams();
    var s = a.getStrings();
    var state = player.getGameplay();
    switch (Objects.toString(a.getType(), "")) {
      case "" -> {}
      case "MoveToAnchor", "EnterEntryIfNotThere" -> {
        if (!("EnterEntryIfNotThere".equals(a.getType()) && player.getEntryId() == p[0])
            && !player.enterSceneAtAnchor(p[0], p[1], p[2], player.isOnline())) return false;
      }
      case "addMissionItem", "addRecoverMissionItem" -> {
        var items = new ItemParamMap();
        for (int i = 0; i < p.length; i += 2) items.add(p[i], p[i + 1]);
        var result = player.getRewardLedger().claimItems(receipt, items);
        if (result.retcode() != Retcode.SUCC && !player.getRewardLedger().granted(receipt))
          return false;
      }
      case "delMissionItem" -> {
        var items = new ItemParamMap();
        for (int i = 0; i < p.length; i += 2) items.add(p[i], p[i + 1]);
        for (var item : items) {
          var owned = player.getInventory().getMaterialByItemId(item.getIntKey());
          if (owned == null || owned.getCount() < item.getIntValue()) return false;
        }
        for (var item : items)
          if (!player
              .getInventory()
              .removeItem(
                  player.getInventory().getMaterialByItemId(item.getIntKey()), item.getIntValue()))
            return false;
      }
      case "SetFloorSavedValue" -> {
        state.getStoryValues().put("floor:" + s[1] + ":" + s[2], Integer.parseInt(s[3]));
        org.hkrpg.server.protocol.Story46.syncFloor(
            player, Integer.parseInt(s[0]), Integer.parseInt(s[1]), s[2], Integer.parseInt(s[3]));
      }
      case "SetCustomValue", "AddCustomValue" -> {
        state
            .getStoryValues()
            .put(variableKey(sub, a), variableResult(sub, a, state.getStoryValues()));
      }
      case "SetGroupProperty" -> {
        if (!player
            .getScene()
            .setStoryGroupProperty(Integer.parseInt(s[0]), s[1], Integer.parseInt(s[2])))
          return false;
      }
      case "SetGroupState" -> {
        if (!player.getScene().setStoryGroupState(p[0], p[1])) return false;
      }
      case "ChangeLineup" -> {
        // 4.6 Mission 100020133 has four slots followed by a leader base ID. Trial characters
        // and automatic character grants need separate lifecycle support and remain unsupported.
        var team = player.getCurrentLineup();
        team.getAvatars().clear();
        Arrays.stream(p).limit(4).filter(id -> id != 0).forEach(id -> team.getAvatars().add(id));
        team.setLeader(team.getAvatars().indexOf(p[4]));
        team.save();
        player.getScene().syncLineup();
        player.sendPacket(new emu.lunarcore.server.packet.send.PacketSyncLineupNotify(team));
      }
      case "ChangeStoryLine" -> {
        if (player.getStoryLineManager().change(p[0], p[1], p[2], p[3], player.isOnline())
            != Retcode.SUCC) return false;
      }
      case "delSubMission", "delMission" -> {
        for (int id : p)
          if (player.getMissionManager().finishFromAction(id) != Retcode.SUCC) return false;
      }
      case "DisableMission" -> {
        for (int id : p) state.getCancelledMissions().add(id);
      }
      case "Recover" -> {
        if (player.getCurrentLineup() == null) return false;
        player.getCurrentLineup().heal(10000, false);
      }
      case "ActivateAnchor" -> {
        if (!SceneAnchors.activate(player, p[0], p[1], p[2])) return false;
      }
      case "ChangeTrainWorldId" -> TrainWorlds.declare(player, p[0]);
      default -> {
        return false;
      }
    }
    return true;
  }
}
