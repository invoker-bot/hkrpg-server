package org.hkrpg.server.gameplay;

import com.google.gson.JsonObject;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.inventory.Inventory;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Authored item repair and finite mission gates; unknown graph effects stay pending. */
public final class HeartDialSteps {
  public record Rule(int condition, int item, int fullCondition) {}

  static int integer(JsonObject row, String key) {
    if (row == null) return -1;
    if (!row.has(key)) return 0;
    try {
      if (!row.get(key).isJsonPrimitive() || !row.getAsJsonPrimitive(key).isNumber()) return -1;
      return row.get(key).getAsBigDecimal().intValueExact();
    } catch (RuntimeException invalid) {
      return -1;
    }
  }

  static String text(JsonObject row, String name) {
    return row != null
            && row.has(name)
            && row.get(name).isJsonPrimitive()
            && row.getAsJsonPrimitive(name).isString()
        ? row.get(name).getAsString()
        : "";
  }

  static boolean only(JsonObject row, String... fields) {
    return row != null && Set.of(fields).containsAll(row.keySet());
  }

  static boolean auto(int id) {
    var row = HeartDialDefinitions.conditions.get(id);
    return id > 0
        && only(row, "ID", "FinishType", "ParamUint1", "ParamUint2", "ParamUint3")
        && integer(row, "ID") == id
        && "AutoFinish".equals(text(row, "FinishType"))
        && integer(row, "ParamUint1") == 0
        && integer(row, "ParamUint2") == 0
        && integer(row, "ParamUint3") == 0;
  }

  public static Map<Integer, Integer> choices(HeartDialDefinitions.Script script) {
    if (script == null
        || script.TotalEmoInfoList() == null
        || script.TotalEmoInfoList().isEmpty()
        || script.TotalEmoInfoList().size() > 4) return Map.of();
    var result = new HashMap<Integer, Integer>();
    var ids = new HashSet<Integer>();
    for (var emotion : script.TotalEmoInfoList()) {
      if (emotion == null) return Map.of();
      int type = HeartDials.emotion(emotion.type());
      if (type < 0
          || !dialogue(emotion.dialogue())
          || !ids.add(emotion.dialogue())
          || result.putIfAbsent(type, emotion.dialogue()) != null) return Map.of();
    }
    return result.containsKey(HeartDials.emotion(script.DefaultEmoType()))
        ? Map.copyOf(result)
        : Map.of();
  }

  static boolean dialogue(int id) {
    var row = HeartDialDefinitions.dialogues.get(id);
    return id > 0
        && row != null
        && row.ID() == id
        && row.ControlTalkList() != null
        && row.ControlTalkList().isEmpty();
  }

  public static Rule rule(HeartDialDefinitions.Script script) {
    boolean full = script != null && List.of("Missing", "Full", "Normal").equals(script.StepList());
    if (script == null
        || script.ScriptID() <= 0
        || !full && !List.of("Missing", "Normal").equals(script.StepList())
        || script.RaidID() != 0
        || (full ? !dialogue(script.FullDialogueID()) : script.FullDialogueID() != 0)
        || script.LockDialogueID() != 0
        || script.UnLockDialogueID() != 0
        || script.ControlDialogueID() != 0
        || !dialogue(script.MissingDialogueID())
        || script.MissingEmoList() == null
        || script.MissingEmoList().isEmpty()
        || script.MissingEmoList().size() > 4) return null;
    var choices = choices(script);
    var missing = new HashSet<Integer>();
    for (var emotion : script.MissingEmoList()) {
      if (emotion == null) return null;
      int type = HeartDials.emotion(emotion);
      if (type < 0 || !choices.containsKey(type) || !missing.add(type)) return null;
    }
    if (!missing.contains(HeartDials.emotion(script.DefaultEmoType()))
        || choices.containsValue(script.MissingDialogueID())
        || full
            && (script.FullDialogueID() == script.MissingDialogueID()
                || choices.containsValue(script.FullDialogueID()))) return null;
    var condition = HeartDialDefinitions.scriptConditions.get(script.ScriptID());
    if (!only(
            condition,
            "ScriptID",
            "MissingConditionID",
            "FullConditionID",
            "LockConditionID",
            "UnLockConditionID",
            "ControlConditionID")
        || integer(condition, "ScriptID") != script.ScriptID()) return null;
    for (var name : List.of("LockConditionID", "UnLockConditionID", "ControlConditionID"))
      if (!auto(integer(condition, name))) return null;
    int fullCondition = integer(condition, "FullConditionID");
    if (full ? !missionCondition(fullCondition) : !auto(fullCondition)) return null;
    int id = integer(condition, "MissingConditionID");
    var item = HeartDialDefinitions.conditions.get(id);
    if (id <= 0
        || !only(item, "ID", "FinishType", "ParamUint1", "ParamUint2", "ParamUint3")
        || integer(item, "ID") != id
        || !"ConsumeSingleItem".equals(text(item, "FinishType"))
        || integer(item, "ParamUint1") <= 0
        || integer(item, "ParamUint2") != 0
        || integer(item, "ParamUint3") != 0) return null;
    return new Rule(id, integer(item, "ParamUint1"), full ? fullCondition : 0);
  }

  static boolean missionCondition(int id) {
    var row = HeartDialDefinitions.conditions.get(id);
    return id > 0
        && only(row, "ID", "FinishType", "ParamUint1", "ParamUint2", "ParamUint3")
        && integer(row, "ID") == id
        && Set.of("FinishMainMission", "FinishSubMission").contains(text(row, "FinishType"))
        && integer(row, "ParamUint1") > 0
        && integer(row, "ParamUint2") == 0
        && integer(row, "ParamUint3") == 0;
  }

  private static boolean fullFinished(Player player, Rule rule) {
    if (rule.fullCondition() == 0) return true;
    return missionFinished(player, rule.fullCondition());
  }

  static boolean missionFinished(Player player, int condition) {
    var row = HeartDialDefinitions.conditions.get(condition);
    int id = integer(row, "ParamUint1");
    boolean main = "FinishMainMission".equals(text(row, "FinishType"));
    var state =
        (main ? player.getGameplay().getMainMissions() : player.getGameplay().getSubMissions())
            .get(id);
    if (state == null
        || state.getQuestId() != id
        || state.getPhase() == null
        || state.getPhase() == QuestState.Phase.DOING
        || state.getProgress() <= 0
        || state.getFinishTime() <= 0
        || (main
                ? player.getGameplay().getCancelledMissions()
                : player.getGameplay().getCancelledSubs())
            .contains(id)) return false;
    if (!main) {
      var config = GameplayDefinitions.subs.get(id);
      if (config == null
          || config.getId() != id
          || config.getMainMissionID() <= 0
          || player.getGameplay().getCancelledMissions().contains(config.getMainMissionID()))
        return false;
    }
    return true;
  }

  public static int step(Player player, HeartDialDefinitions.Script row) {
    if (!HeartDials.normalDialogues(row).isEmpty()) return 4;
    var rule = rule(row);
    if (rule == null) return HeartDialLocks.step(player, row);
    var payment = player.getGameplay().getHeartDialPayments().get(row.ScriptID());
    if (payment == null || !payment.isPaid()) return 0;
    return payment.isNormalUnlocked() || fullFinished(player, rule) ? 4 : 1;
  }

  /** Freeze an observed completed gate before notifications; never complete the mission itself. */
  public static void settleFullSteps(Player player) {
    if (!HeartDials.validState(player)) return;
    boolean changed = false;
    for (var payment : player.getGameplay().getHeartDialPayments().values()) {
      if (payment == null || !payment.isPaid() || payment.isNormalUnlocked()) continue;
      var rule = rule(HeartDialDefinitions.scripts.get(payment.getScript()));
      if (rule != null && rule.fullCondition() > 0 && fullFinished(player, rule)) {
        payment.unlockNormal();
        changed = true;
      }
    }
    changed |= HeartDialLocks.settle(player);
    if (changed) player.save();
  }

  public static boolean validPayments(Player player) {
    for (var entry : player.getGameplay().getHeartDialPayments().entrySet()) {
      var payment = entry.getValue();
      if (payment == null
          || entry.getKey() == null
          || entry.getKey() != payment.getScript()
          || payment.getPlane() <= 0
          || payment.getFloor() <= 0
          || payment.getGroup() <= 0
          || payment.getInstance() <= 0
          || payment.isNormalUnlocked() && !payment.isPaid()
          || payment.getNotifiedStep() != -1
              && payment.getNotifiedStep() != 1
              && payment.getNotifiedStep() != 4) return false;
      var rule = rule(HeartDialDefinitions.scripts.get(payment.getScript()));
      var binding =
          HeartDialDefinitions.npcs.get(
              payment.getFloor() + ":" + payment.getGroup() + ":" + payment.getInstance());
      if (rule == null
          || rule.fullCondition() == 0
              && (payment.isNormalUnlocked() || payment.getNotifiedStep() == 1)
          || !payment.isPaid() && payment.getNotifiedStep() != -1
          || rule.condition() != payment.getCondition()
          || rule.item() != payment.getItem()
          || binding == null
          || binding.FloorID() != payment.getFloor()
          || binding.GroupID() != payment.getGroup()
          || binding.InstanceID() != payment.getInstance()
          || binding.DefaultScriptID() != payment.getScript()
          || !List.of(payment.getScript()).equals(binding.ScriptIDList())) return false;
    }
    return true;
  }

  public static Retcode submit(Player player, int script, Map<Integer, Integer> reported) {
    synchronized (player) {
      if (!HeartDials.validState(player)) return Retcode.PLAYER_DATA_ERROR;
      if (HeartDialLocks.rule(HeartDialDefinitions.scripts.get(script)) != null)
        return HeartDialLocks.submit(player, script, reported);
      var rule = rule(HeartDialDefinitions.scripts.get(script));
      if (rule == null || !Map.of(rule.item(), 1).equals(reported)) return Retcode.REQ_PARA_INVALID;
      var scene = player.getScene();
      if (scene == null) return Retcode.SCENE_ENTITY_NOT_EXIST;
      var npcs =
          scene.getEntities().values().stream()
              .filter(
                  entity ->
                      entity instanceof EntityNpc
                          && HeartDials.boundNpc(player, script, entity.getEntityId()) == entity)
              .toList();
      if (npcs.size() != 1) return Retcode.SCENE_ENTITY_NOT_EXIST;
      var npc = (EntityNpc) npcs.getFirst();
      var valid = HeartDials.validate(player, script, npc.getEntityId());
      if (valid != Retcode.SUCC) return valid;
      if (player.getGameplay().getHeartDialReports().values().stream()
          .anyMatch(report -> !report.isApplied())) return Retcode.SYSTEM_BUSY;
      if (player.getGameplay().getHeartDialPayments().containsKey(script))
        return Retcode.MISSION_ALREADY_DONE;
      synchronized (player.getInventory()) {
        var item = player.getInventory().getMaterialByItemId(rule.item());
        var excel = GameData.getItemExcelMap().get(rule.item());
        if (excel == null || !Inventory.isStackType(excel)) return Retcode.PLAYER_DATA_ERROR;
        if (item == null
            || item.getOwnerUid() != player.getUid()
            || item.getItemId() != rule.item()
            || item.getCount() < 1) return Retcode.ITEM_NOT_ENOUGH;
        if (item.getExcel() != excel
            || player.getInventory().getItems().get(item.getInternalUid()) != item)
          return Retcode.PLAYER_DATA_ERROR;
        var payment =
            new GameplayState.HeartDialPayment(
                script,
                rule.condition(),
                rule.item(),
                player.getPlaneId(),
                player.getFloorId(),
                npc.getGroupId(),
                npc.getInstId());
        player.getGameplay().getHeartDialPayments().put(script, payment);
        player.save();
        if (!player
            .getInventory()
            .consumeForUse(
                item,
                1,
                () -> {
                  payment.complete();
                  player.save();
                })) return Retcode.PLAYER_DATA_ERROR;
      }
      player.getMissionManager().refresh();
      org.hkrpg.server.protocol.HeartDial46.syncScript(player, script);
      return Retcode.SUCC;
    }
  }

  public static boolean objective(Player player, MissionInfo.SubMissionInfo config) {
    var ids = config.getParamIntList();
    if (!"ListContain".equals(config.getParamType())
        || config.getParamInt1() < 1
        || config.getParamInt1() > 4
        || config.getProgress() != 1
        || config.getParamInt2() != 0
        || config.getParamInt3() != 0
        || config.getParamStr1() == null
        || !config.getParamStr1().isEmpty()
        || config.getParamItemList() == null
        || !config.getParamItemList().isEmpty()
        || ids == null
        || ids.length != 1
        || ids[0] == null
        || ids[0] <= 0
        || !HeartDials.validState(player)) return false;
    var payment = player.getGameplay().getHeartDialPayments().get(ids[0]);
    boolean committed =
        payment != null && payment.isPaid() || HeartDialLocks.committed(player, ids[0]);
    return committed
        && step(player, HeartDialDefinitions.scripts.get(ids[0])) == config.getParamInt1();
  }

  private HeartDialSteps() {}
}
