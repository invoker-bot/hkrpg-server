package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.HeartDialSteps.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.inventory.Inventory;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Separate receipts for authored Missing -> Lock -> UnLock -> Normal player-raid graphs. */
public final class HeartDialLocks {
  public record Rule(
      int script,
      int raid,
      int missingCondition,
      int missingItem,
      int lockCondition,
      int lockItem,
      int unlockCondition,
      int unlockMission) {}

  private static int singleItem(int id) {
    var row = HeartDialDefinitions.conditions.get(id);
    return id > 0
            && only(row, "ID", "FinishType", "ParamUint1", "ParamUint2", "ParamUint3")
            && integer(row, "ID") == id
            && "ConsumeSingleItem".equals(text(row, "FinishType"))
            && integer(row, "ParamUint1") > 0
            && integer(row, "ParamUint2") == 0
            && integer(row, "ParamUint3") == 0
        ? integer(row, "ParamUint1")
        : 0;
  }

  public static Rule rule(HeartDialDefinitions.Script script) {
    boolean missing =
        script != null && List.of("Missing", "Lock", "UnLock", "Normal").equals(script.StepList());
    if (script == null
        || script.ScriptID() <= 0
        || !missing && !List.of("Lock", "UnLock", "Normal").equals(script.StepList())
        || script.FullDialogueID() != 0
        || script.ControlDialogueID() != 0
        || !dialogue(script.LockDialogueID())
        || !dialogue(script.UnLockDialogueID())
        || script.MissingEmoList() == null
        || (missing
            ? !dialogue(script.MissingDialogueID()) || script.MissingEmoList().isEmpty()
            : script.MissingDialogueID() != 0 || !script.MissingEmoList().isEmpty())) return null;
    var raid = MissionRaid.resource(script.RaidID(), 0);
    // Paid lock graphs currently implement ordinary player-raid teams only.
    if (raid == null
        || !"Player".equals(raid.TeamType())
        || RaidTeams.frozenParty(RaidTeams.plan(raid))) return null;
    var choices = choices(script);
    if (choices.isEmpty()) return null;
    var special = new HashSet<Integer>(choices.values());
    if (!special.add(script.LockDialogueID())
        || !special.add(script.UnLockDialogueID())
        || missing && !special.add(script.MissingDialogueID())) return null;
    var missingEmotions = new HashSet<Integer>();
    for (var name : script.MissingEmoList()) {
      int emotion = name == null ? -1 : HeartDials.emotion(name);
      if (!choices.containsKey(emotion) || !missingEmotions.add(emotion)) return null;
    }
    if (missing && !missingEmotions.contains(HeartDials.emotion(script.DefaultEmoType())))
      return null;
    var conditions = HeartDialDefinitions.scriptConditions.get(script.ScriptID());
    if (!only(
            conditions,
            "ScriptID",
            "MissingConditionID",
            "FullConditionID",
            "LockConditionID",
            "UnLockConditionID",
            "ControlConditionID")
        || integer(conditions, "ScriptID") != script.ScriptID()
        || !auto(integer(conditions, "FullConditionID"))
        || !auto(integer(conditions, "ControlConditionID"))) return null;
    int missingCondition = integer(conditions, "MissingConditionID");
    int missingItem = missing ? singleItem(missingCondition) : 0;
    int lockCondition = integer(conditions, "LockConditionID");
    int lockItem = singleItem(lockCondition);
    int unlockCondition = integer(conditions, "UnLockConditionID");
    var unlock = HeartDialDefinitions.conditions.get(unlockCondition);
    int unlockMission = integer(unlock, "ParamUint1");
    if ((missing ? missingItem <= 0 : !auto(missingCondition))
        || lockItem <= 0
        || !missionCondition(unlockCondition)
        || !"FinishMainMission".equals(text(unlock, "FinishType"))
        || unlockMission != raid.MainMissionIDList().getFirst()
            && unlockMission != raid.MainMissionIDBefore()) return null;
    return new Rule(
        script.ScriptID(),
        script.RaidID(),
        missing ? missingCondition : 0,
        missingItem,
        lockCondition,
        lockItem,
        unlockCondition,
        unlockMission);
  }

  private static boolean paid(GameplayState.HeartDialPayment payment) {
    return payment != null && payment.isPaid();
  }

  public static int step(Player player, HeartDialDefinitions.Script script) {
    var rule = rule(script);
    if (rule == null) return -1;
    var lock = player.getGameplay().getHeartDialLocks().get(rule.script());
    if (rule.missingCondition() > 0 && (lock == null || !paid(lock.getMissingPayment()))) return 0;
    if (lock == null || !paid(lock.getLockPayment())) return 2;
    return lock.isNormalUnlocked() || missionFinished(player, rule.unlockCondition()) ? 4 : 3;
  }

  private static boolean receipt(
      GameplayState.HeartDialPayment payment, int script, int condition, int item) {
    if (payment == null) return true;
    var binding =
        HeartDialDefinitions.npcs.get(
            payment.getFloor() + ":" + payment.getGroup() + ":" + payment.getInstance());
    return payment.getScript() == script
        && payment.getCondition() == condition
        && payment.getItem() == item
        && payment.getPlane() > 0
        && payment.getFloor() > 0
        && payment.getGroup() > 0
        && payment.getInstance() > 0
        && !payment.isNormalUnlocked()
        && payment.getNotifiedStep() == -1
        && binding != null
        && binding.FloorID() == payment.getFloor()
        && binding.GroupID() == payment.getGroup()
        && binding.InstanceID() == payment.getInstance()
        && binding.DefaultScriptID() == script
        && List.of(script).equals(binding.ScriptIDList());
  }

  public static boolean valid(Player player) {
    for (var entry : player.getGameplay().getHeartDialLocks().entrySet()) {
      var lock = entry.getValue();
      if (lock == null
          || entry.getKey() == null
          || entry.getKey() != lock.getScript()
          || player.getGameplay().getHeartDialPayments().containsKey(entry.getKey())) return false;
      var rule = rule(HeartDialDefinitions.scripts.get(lock.getScript()));
      if (rule == null
          || lock.getRaid() != rule.raid()
          || lock.getMissingCondition() != rule.missingCondition()
          || lock.getMissingItem() != rule.missingItem()
          || lock.getLockCondition() != rule.lockCondition()
          || lock.getLockItem() != rule.lockItem()
          || lock.getUnlockCondition() != rule.unlockCondition()
          || lock.getUnlockMission() != rule.unlockMission()
          || lock.getMissingPayment() == null && lock.getLockPayment() == null
          || rule.missingCondition() == 0 && lock.getMissingPayment() != null
          || rule.missingCondition() > 0
              && lock.getLockPayment() != null
              && !paid(lock.getMissingPayment())
          || !receipt(
              lock.getMissingPayment(), rule.script(), rule.missingCondition(), rule.missingItem())
          || !receipt(lock.getLockPayment(), rule.script(), rule.lockCondition(), rule.lockItem())
          || lock.isNormalUnlocked() && !paid(lock.getLockPayment())) return false;
      int cursor = lock.getNotifiedStep();
      if (cursor != -1 && cursor != 2 && cursor != 3 && cursor != 4
          || cursor == 2 && (rule.missingCondition() == 0 || !paid(lock.getMissingPayment()))
          || cursor == 3 && !paid(lock.getLockPayment())
          || cursor == 4 && !lock.isNormalUnlocked()) return false;
    }
    return true;
  }

  public static boolean pending(Player player) {
    return player.getGameplay().getHeartDialLocks().values().stream()
        .anyMatch(GameplayState.HeartDialLock::pending);
  }

  public static boolean committed(Player player, int script) {
    var lock = player.getGameplay().getHeartDialLocks().get(script);
    return lock != null
        && !lock.pending()
        && (paid(lock.getMissingPayment()) || paid(lock.getLockPayment()));
  }

  static boolean historical(Player player, HeartDialDefinitions.Script script, int step) {
    var rule = rule(script);
    if (rule == null) return false;
    var lock = player.getGameplay().getHeartDialLocks().get(script.ScriptID());
    return switch (step) {
      case 0 -> rule.missingCondition() > 0;
      case 2 -> rule.missingCondition() == 0 || lock != null && paid(lock.getMissingPayment());
      case 3 -> lock != null && paid(lock.getLockPayment());
      default -> false;
    };
  }

  static boolean settle(Player player) {
    boolean changed = false;
    for (var lock : player.getGameplay().getHeartDialLocks().values()) {
      if (lock.isNormalUnlocked() || !paid(lock.getLockPayment())) continue;
      if (missionFinished(player, lock.getUnlockCondition())) {
        lock.unlockNormal();
        changed = true;
      }
    }
    return changed;
  }

  public static Retcode submit(Player player, int script, Map<Integer, Integer> reported) {
    synchronized (player) {
      if (!HeartDials.validState(player)) return Retcode.PLAYER_DATA_ERROR;
      var rule = rule(HeartDialDefinitions.scripts.get(script));
      if (rule == null) return Retcode.REQ_PARA_INVALID;
      int step = step(player, HeartDialDefinitions.scripts.get(script));
      if (step != 0 && step != 2) return Retcode.MISSION_ALREADY_DONE;
      int condition = step == 0 ? rule.missingCondition() : rule.lockCondition();
      int itemId = step == 0 ? rule.missingItem() : rule.lockItem();
      if (!Map.of(itemId, 1).equals(reported)) return Retcode.REQ_PARA_INVALID;
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
      synchronized (player.getInventory()) {
        var excel = GameData.getItemExcelMap().get(itemId);
        var item = player.getInventory().getMaterialByItemId(itemId);
        if (excel == null || !Inventory.isStackType(excel)) return Retcode.PLAYER_DATA_ERROR;
        if (item == null
            || item.getOwnerUid() != player.getUid()
            || item.getItemId() != itemId
            || item.getCount() < 1) return Retcode.ITEM_NOT_ENOUGH;
        if (item.getExcel() != excel
            || player.getInventory().getItems().get(item.getInternalUid()) != item)
          return Retcode.PLAYER_DATA_ERROR;
        var lock = player.getGameplay().getHeartDialLocks().get(script);
        if (lock == null) {
          lock = new GameplayState.HeartDialLock(rule);
          player.getGameplay().getHeartDialLocks().put(script, lock);
        }
        var payment =
            new GameplayState.HeartDialPayment(
                script,
                condition,
                itemId,
                player.getPlaneId(),
                player.getFloorId(),
                npc.getGroupId(),
                npc.getInstId());
        lock.reserve(step, payment);
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

  private HeartDialLocks() {}
}
