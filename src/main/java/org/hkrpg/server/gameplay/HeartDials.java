package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** The authored Normal-only scripts have no item, raid, control or step-transition effects. */
public final class HeartDials {
  public static int emotion(String value) {
    return switch (value == null ? "Peace" : value) {
      case "Peace" -> 0;
      case "Anger" -> 1;
      case "Happy" -> 2;
      case "Sad" -> 3;
      default -> -1;
    };
  }

  public static Map<Integer, Integer> normalDialogues(HeartDialDefinitions.Script row) {
    if (row == null
        || row.ScriptID() <= 0
        || emotion(row.DefaultEmoType()) < 0
        || !List.of("Normal").equals(row.StepList())
        || row.MissingEmoList() == null
        || !row.MissingEmoList().isEmpty()
        || row.RaidID() != 0
        || row.MissingDialogueID() != 0
        || row.FullDialogueID() != 0
        || row.LockDialogueID() != 0
        || row.UnLockDialogueID() != 0
        || row.ControlDialogueID() != 0
        || row.TotalEmoInfoList() == null
        || row.TotalEmoInfoList().isEmpty()
        || row.TotalEmoInfoList().size() > 4) return Map.of();
    var choices = new HashMap<Integer, Integer>();
    var ids = new HashSet<Integer>();
    for (var entry : row.TotalEmoInfoList()) {
      if (entry == null) return Map.of();
      int type = emotion(entry.type());
      var dialogue = HeartDialDefinitions.dialogues.get(entry.dialogue());
      if (type < 0
          || entry.dialogue() <= 0
          || dialogue == null
          || dialogue.ID() != entry.dialogue()
          || dialogue.ControlTalkList() == null
          || !dialogue.ControlTalkList().isEmpty()
          || !ids.add(entry.dialogue())
          || choices.putIfAbsent(type, entry.dialogue()) != null) return Map.of();
    }
    return choices.containsKey(emotion(row.DefaultEmoType())) ? Map.copyOf(choices) : Map.of();
  }

  public static int current(Player player, HeartDialDefinitions.Script row) {
    var saved = player.getGameplay().getHeartDialEmotions().get(row.ScriptID());
    return saved == null ? emotion(row.DefaultEmoType()) : saved;
  }

  public static boolean validState(Player player) {
    if (player.getGameplay().getHeartDialUnlockNotified() < 0
        || player.getGameplay().getHeartDialUnlockNotified() > 2
        || !HeartDialSteps.validPayments(player)
        || !HeartDialLocks.valid(player)) return false;
    for (var entry : HeartDialDefinitions.scripts.entrySet())
      if (entry.getKey() == null
          || entry.getKey() <= 0
          || entry.getValue() == null
          || entry.getKey() != entry.getValue().ScriptID()) return false;
    for (var entry : player.getGameplay().getHeartDialEmotions().entrySet()) {
      var row = HeartDialDefinitions.scripts.get(entry.getKey());
      if (entry.getValue() == null
          || HeartDialSteps.step(player, row) != 4
          || !choices(row).containsKey(entry.getValue())) return false;
    }
    for (var entry : player.getGameplay().getHeartDialReports().entrySet()) {
      var report = entry.getValue();
      if (report == null
          || report.getPlane() <= 0
          || report.getFloor() <= 0
          || report.getGroup() <= 0
          || report.getInstance() <= 0
          || report.getTargets() == null
          || report.getTargets().stream().anyMatch(id -> id == null || id <= 0)) return false;
      var row = HeartDialDefinitions.scripts.get(report.getScript());
      var payment = player.getGameplay().getHeartDialPayments().get(report.getScript());
      if (entry.getKey() == null
          || report.getStep() == 4 && HeartDialSteps.step(player, row) != 4
          || report.getStep() == 1 && (payment == null || !payment.isPaid())
          || (report.getStep() == 2 || report.getStep() == 3)
              && !HeartDialLocks.historical(player, row, report.getStep())
          || !Objects.equals(dialogue(row, report.getStep(), report.getEmotion()), entry.getKey()))
        return false;
      var binding =
          HeartDialDefinitions.npcs.get(
              report.getFloor() + ":" + report.getGroup() + ":" + report.getInstance());
      if (binding == null
          || binding.FloorID() != report.getFloor()
          || binding.GroupID() != report.getGroup()
          || binding.InstanceID() != report.getInstance()
          || binding.DefaultScriptID() != report.getScript()
          || !List.of(report.getScript()).equals(binding.ScriptIDList())) return false;
    }
    return true;
  }

  private static int constant(String name) {
    var value = HeartDialDefinitions.constants.get(name);
    if (value == null || value.size() != 1 || !value.has("IntValue")) return 0;
    try {
      var number = value.get("IntValue").getAsBigDecimal().intValueExact();
      return number > 0 ? number : 0;
    } catch (RuntimeException invalid) {
      return 0;
    }
  }

  private static boolean subFinished(Player player, int id) {
    var state = player.getMissionManager().sub(id);
    return id > 0
        && state != null
        && state.getQuestId() == id
        && state.getPhase() != QuestState.Phase.DOING
        && !player.getGameplay().getCancelledSubs().contains(id);
  }

  public static int unlock(Player player) {
    if (!subFinished(player, constant("ChangeEmotion_Unlock_Sub_Mission"))) return 0;
    return subFinished(player, constant("ChangeEmotion_UnlockAll_Sub_Mission")) ? 2 : 1;
  }

  private static boolean eligible(Player player, int script) {
    int unlocked = unlock(player);
    if (unlocked == 0 || unlocked == 1 && script != constant("ChangeEmotion_Unlock_FirstScript"))
      return false;
    // The pinned all-unlock story-line list is [0]; other verses require their own semantics.
    var value = HeartDialDefinitions.constants.get("ChangeEmotion_UnlockAll_StoryLineList");
    return value != null
        && value.size() == 1
        && value.has("ArrayValue")
        && value.get("ArrayValue").isJsonArray()
        && value.getAsJsonArray("ArrayValue").size() == 1
        && value.getAsJsonArray("ArrayValue").get(0).isJsonObject()
        && value.getAsJsonArray("ArrayValue").get(0).getAsJsonObject().size() == 1
        && value.getAsJsonArray("ArrayValue").get(0).getAsJsonObject().has("IntValue")
        && value
            .getAsJsonArray("ArrayValue")
            .get(0)
            .getAsJsonObject()
            .get("IntValue")
            .toString()
            .equals("0");
  }

  static EntityNpc boundNpc(Player player, int script, int entity) {
    var scene = player.getScene();
    if (scene == null
        || entity <= 0
        || scene.getPlayer() != player
        || scene.getPlaneId() != player.getPlaneId()
        || scene.getFloorId() != player.getFloorId()
        || scene.getEntryId() != player.getEntryId()
        || scene.getDimensionId() != 0
        || scene.getStoryLineId() != 0
        || !(scene.getEntityById(entity) instanceof EntityNpc npc)
        || npc.getScene() != scene
        || npc.getEntityId() != entity
        || npc.getNpcId() <= 0
        || !scene.getLoadedGroups().contains(npc.getGroupId())
        || scene.getFloorInfo() == null) return null;
    var binding =
        HeartDialDefinitions.npcs.get(
            scene.getFloorId() + ":" + npc.getGroupId() + ":" + npc.getInstId());
    if (binding == null
        || binding.FloorID() != scene.getFloorId()
        || binding.GroupID() != npc.getGroupId()
        || binding.InstanceID() != npc.getInstId()
        || binding.DefaultScriptID() != script
        || !List.of(script).equals(binding.ScriptIDList())) return null;
    var group = scene.getResourceGroups().get(npc.getGroupId());
    if (group == null || group.getId() != npc.getGroupId() || group.getNPCList() == null)
      return null;
    var rows =
        group.getNPCList().stream()
            .filter(row -> row != null && row.getID() == npc.getInstId())
            .toList();
    if (rows.size() != 1
        || rows.getFirst().getNPCID() != npc.getNpcId()
        || rows.getFirst().isIsClientOnly()) return null;
    return npc;
  }

  static Retcode validate(Player player, int script, int entity) {
    var state = player.getGameplay();
    if (!validState(player)) return Retcode.PLAYER_DATA_ERROR;
    if (state.getHeartDialPayments().values().stream().anyMatch(payment -> !payment.isPaid())
        || HeartDialLocks.pending(player)
        || player.getBattle() != null
        || state.getStoryLines().isTransitionPending()
        || state.getStoryLines().getCurrentId() != 0
        || state.getRogue().isInside()
        || state.getTrialRun() != null
        || state.getCurrentMissionRaid() != 0
        || state.getParkourVenue().inside()
        || state.getStoryActions().values().stream()
            .anyMatch(GameplayState.ActionCursor::isPending)) return Retcode.SYSTEM_BUSY;
    if (!eligible(player, script)
        || HeartDialSteps.step(player, HeartDialDefinitions.scripts.get(script)) < 0)
      return Retcode.REQ_PARA_INVALID;
    if (boundNpc(player, script, entity) == null) return Retcode.SCENE_ENTITY_NOT_EXIST;
    return Retcode.SUCC;
  }

  public static Retcode change(Player player, int script, int entity, int emotion) {
    synchronized (player) {
      var result = validate(player, script, entity);
      if (result != Retcode.SUCC) return result;
      var row = HeartDialDefinitions.scripts.get(script);
      if (HeartDialSteps.step(player, row) != 4) return Retcode.REQ_PARA_INVALID;
      var choices = choices(row);
      if (!choices.containsKey(current(player, row))) return Retcode.PLAYER_DATA_ERROR;
      if (!choices.containsKey(emotion)) return Retcode.REQ_PARA_INVALID;
      if (player.getGameplay().getHeartDialReports().values().stream()
          .anyMatch(report -> report == null || !report.isApplied())) return Retcode.SYSTEM_BUSY;
      if (!Objects.equals(player.getGameplay().getHeartDialEmotions().get(script), emotion)) {
        player.getGameplay().getHeartDialEmotions().put(script, emotion);
        player.save();
      }
      return Retcode.SUCC;
    }
  }

  public static Retcode finish(Player player, int script, int dialogue, int entity) {
    synchronized (player) {
      var result = validate(player, script, entity);
      if (result != Retcode.SUCC) return result;
      var row = HeartDialDefinitions.scripts.get(script);
      int emotion = current(player, row);
      int step = HeartDialSteps.step(player, row);
      if (!Objects.equals(dialogue(row, step, emotion), dialogue)) return Retcode.REQ_PARA_INVALID;
      var reports = player.getGameplay().getHeartDialReports();
      var report = reports.get(dialogue);
      var npc = boundNpc(player, script, entity);
      if (report != null) {
        if (report.isApplied()) return Retcode.MISSION_ALREADY_DONE;
        if (report.getScript() != script
            || report.getStep() != step
            || report.getEmotion() != emotion
            || report.getPlane() != player.getPlaneId()
            || report.getFloor() != player.getFloorId()
            || report.getGroup() != npc.getGroupId()
            || report.getInstance() != npc.getInstId()
            || report.getTargets() == null) return Retcode.PLAYER_DATA_ERROR;
      } else {
        if (reports.values().stream().anyMatch(saved -> saved == null || !saved.isApplied()))
          return Retcode.SYSTEM_BUSY;
        var targets = player.getMissionManager().heartDialTargets(dialogue);
        report =
            new GameplayState.HeartDialReport(
                script,
                emotion,
                player.getPlaneId(),
                player.getFloorId(),
                npc.getGroupId(),
                npc.getInstId(),
                targets,
                step);
        reports.put(dialogue, report);
        player.save();
      }
      player.getMissionManager().onHeartDialDialogue(dialogue, report.getTargets());
      if (!player.getMissionManager().matchThreeTargetsDone(report.getTargets()))
        return Retcode.SYSTEM_BUSY;
      report.complete();
      player.save();
      return Retcode.SUCC;
    }
  }

  public static Map<Integer, Integer> choices(HeartDialDefinitions.Script row) {
    var normal = normalDialogues(row);
    return !normal.isEmpty()
        ? normal
        : HeartDialSteps.rule(row) == null && HeartDialLocks.rule(row) == null
            ? Map.of()
            : HeartDialSteps.choices(row);
  }

  public static Integer dialogue(HeartDialDefinitions.Script row, int step, int emotion) {
    if (step == 4) return choices(row).get(emotion);
    var rule = HeartDialSteps.rule(row);
    var lock = HeartDialLocks.rule(row);
    if (rule == null && lock == null || emotion != emotion(row.DefaultEmoType())) return null;
    if (lock != null)
      return switch (step) {
        case 0 -> lock.missingCondition() > 0 ? row.MissingDialogueID() : null;
        case 2 -> row.LockDialogueID();
        case 3 -> row.UnLockDialogueID();
        default -> null;
      };
    if (step == 0) return row.MissingDialogueID();
    return step == 1 && rule.fullCondition() > 0 ? row.FullDialogueID() : null;
  }

  private HeartDials() {}
}
