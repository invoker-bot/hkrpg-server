package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;

/**
 * Mission-authorized classic victories only. This protocol does not provide battle input replay.
 */
public final class DicePveMissionReports {
  public record Stage(int id, int reward) {}

  public record Result(Retcode code, List<GameItem> items) {}

  public static Map<Integer, Stage> stages() {
    try {
      var json =
          JsonParser.parseString(
              Files.readString(
                  Path.of(
                      LunarCore.getConfig().getResourceDir(),
                      "ExcelOutput",
                      "ActivityDiceStageConfig.json")));
      var rows =
          json.isJsonArray()
              ? json.getAsJsonArray().asList()
              : json.getAsJsonObject().asMap().values();
      var result = new HashMap<Integer, Stage>();
      for (var element : rows) {
        var row = element.getAsJsonObject();
        int id = number(row, "DiceStageID"),
            reward = row.has("RewardID") ? number(row, "RewardID") : 0;
        if (id <= 0 || reward < 0 || result.putIfAbsent(id, new Stage(id, reward)) != null)
          return Map.of();
      }
      return Map.copyOf(result);
    } catch (java.io.IOException | RuntimeException invalid) {
      return Map.of();
    }
  }

  private static int number(JsonObject row, String field) {
    var value = row.get(field);
    if (value == null || !value.isJsonPrimitive() || !value.getAsString().matches("0|[1-9][0-9]*"))
      throw new IllegalArgumentException("Invalid classic PVE resource integer");
    return Integer.parseInt(value.getAsString());
  }

  public static boolean valid(Player player) {
    var wins = player.getGameplay().getDiceCombat().getPveWins();
    if (wins.isEmpty()) return true;
    var stages = stages();
    for (var entry : wins.entrySet()) {
      var win = entry.getValue();
      var row = stages.get(entry.getKey());
      if (win == null
          || row == null
          || entry.getKey() != win.getStage()
          || row.reward() != win.getReward()
          || win.getPlane() <= 0
          || win.getFloor() <= 0
          || win.getNativeScalar() < 0
          || win.getNativeScalar() > 0xffffffffL
          || win.getItems() == null
          || win.getTargets() == null
          || win.getTargets().isEmpty()
          || win.getTargets().stream().anyMatch(id -> id == null || id <= 0)
          || win.getItems().entrySet().stream()
              .anyMatch(
                  e ->
                      e.getKey() == null
                          || e.getKey() <= 0
                          || e.getValue() == null
                          || e.getValue() <= 0)
          || win.isApplied() && !player.getRewardLedger().granted(key(win.getStage()))
          || win.isMissionEventsDone() && !win.isApplied()) return false;
    }
    return true;
  }

  private static String key(int id) {
    return "dice-pve:" + id;
  }

  public static boolean pending(Player player) {
    return player.getGameplay().getDiceCombat().getPveWins().values().stream()
        .anyMatch(r -> r == null || !r.isApplied());
  }

  private static Result rejected(Retcode code) {
    return new Result(code, List.of());
  }

  public static Result finish(Player player, int id, boolean won, boolean nativeFlag, long scalar) {
    synchronized (player) {
      if (id <= 0 || !won || nativeFlag || scalar < 0 || scalar > 0xffffffffL)
        return rejected(Retcode.REQ_PARA_INVALID);
      var stages = stages();
      if (stages.isEmpty()) return rejected(Retcode.REWARD_CONFIG_NOT_EXIST);
      var row = stages.get(id);
      if (row == null) return rejected(Retcode.REQ_PARA_INVALID);
      if (!valid(player)) return rejected(Retcode.PLAYER_DATA_ERROR);
      var state = player.getGameplay().getDiceCombat();
      var previous = state.getPveWins().get(id);
      if (previous != null) {
        if (!previous.isApplied()) return rejected(Retcode.SYSTEM_BUSY);
        settleMissions(player, previous);
        return rejected(Retcode.SUCC);
      }
      if (player.getBattle() != null
          || player.getGameplay().getStoryLines().isTransitionPending()
          || player.getGameplay().getRogue().isInside()
          || player.getGameplay().getTrialRun() != null
          || player.getGameplay().getCurrentMissionRaid() != 0
          || player.getGameplay().getRhythm().getActiveLevel() != 0
          || player.getGameplay().getParkour().getActiveLevel() != 0
          || player.getGameplay().getParkourVenue().inside()
          || DiceProgression.pending(player)
          || state.getPurchases() == null
          || state.getPurchases().values().stream().anyMatch(r -> r == null || !r.isGranted())
          || state.getPveWins().values().stream().anyMatch(r -> !r.isApplied()))
        return rejected(Retcode.SYSTEM_BUSY);
      var targets = player.getMissionManager().dicePveVictoryTargets(id);
      if (targets.isEmpty() || player.getPlaneId() <= 0 || player.getFloorId() <= 0)
        return rejected(Retcode.MISSION_NOT_DOING);
      if (player.getGameplay().getRewards().containsKey(key(id)))
        return rejected(Retcode.PLAYER_DATA_ERROR);
      var config = GameData.getRewardExcelMap().get(row.reward());
      if (row.reward() != 0 && (config == null || config.getRewards() == null))
        return rejected(Retcode.REWARD_CONFIG_NOT_EXIST);
      var rewards = new HashMap<Integer, Integer>();
      try {
        if (config != null)
          for (var item : config.getRewards()) {
            if (item.getIntKey() <= 0
                || item.getIntValue() <= 0
                || GameData.getItemExcelMap().get(item.getIntKey()) == null)
              return rejected(Retcode.REWARD_CONFIG_NOT_EXIST);
            rewards.merge(item.getIntKey(), item.getIntValue(), Math::addExact);
          }
      } catch (ArithmeticException invalid) {
        return rejected(Retcode.REWARD_CONFIG_NOT_EXIST);
      }
      var receipt =
          new GameplayState.DicePveWin(
              id, row.reward(), player.getPlaneId(), player.getFloorId(), scalar, rewards, targets);
      state.reserve(receipt);
      player.save();
      var items = new ItemParamMap();
      rewards.forEach(items::put);
      var claim = player.getRewardLedger().claimItems(key(id), items);
      if (claim.retcode() != Retcode.SUCC) return rejected(claim.retcode());
      receipt.applied(true);
      try {
        player.save();
      } catch (RuntimeException interrupted) {
        receipt.applied(false);
        throw interrupted;
      }
      settleMissions(player, receipt);
      return new Result(Retcode.SUCC, claim.items());
    }
  }

  private static void settleMissions(Player player, GameplayState.DicePveWin receipt) {
    if (receipt.isMissionEventsDone()) return;
    player.getMissionManager().onDicePveVictory(receipt);
    if (player.getMissionManager().matchThreeTargetsDone(receipt.getTargets())) {
      receipt.finishMissionEvents();
      player.save();
    }
  }

  private DicePveMissionReports() {}
}
