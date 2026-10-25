package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Client tutorial acknowledgements belong to the player, never to the resource catalog. */
@Entity(useDiscriminator = false)
public final class TutorialProgress {
  private Map<Integer, Integer> tutorials = new HashMap<>();
  private Map<Integer, Integer> guides = new HashMap<>();

  public record Result(Retcode retcode, int status, List<GameItem> rewards) {}

  public Map<Integer, Integer> snapshot(boolean guide) {
    var result = new TreeMap<Integer, Integer>();
    (guide ? guides : tutorials)
        .forEach(
            (id, status) -> {
              if (known(guide, id)
                  && (Integer.valueOf(1).equals(status) || Integer.valueOf(2).equals(status)))
                result.put(id, status);
            });
    return Collections.unmodifiableMap(result);
  }

  private static boolean known(boolean guide, int id) {
    return id > 0
        && (guide
            ? GameData.getTutorialGuideExcelMap().get(id) != null
            : GameData.getTutorialExcelMap().get(id) != null);
  }

  /** Only the original, unambiguous main-mission end condition is resolved here. */
  private static boolean endedWithMainMission(Player player, int id) {
    var config = GameData.getTutorialExcelMap().get(id);
    if (config == null || config.getId() != id) return false;
    var triggers = config.getFinishTriggerParams();
    if (triggers == null || triggers.size() != 1) return false;
    var trigger = triggers.getFirst();
    if (trigger == null
        || !"FinishMainMission".equals(trigger.TriggerType())
        || trigger.TriggerParam() == null
        || !trigger.TriggerParam().matches("[1-9][0-9]{0,9}")) return false;
    try {
      return MissionStateRecords.mainCompleted(player, Integer.parseInt(trigger.TriggerParam()));
    } catch (NumberFormatException invalid) {
      return false;
    }
  }

  /** Retire started teaching after its original end condition; never complete the catalog. */
  public List<Integer> expireFinishedMissions(Player player) {
    synchronized (player) {
      var expired = new ArrayList<Integer>();
      for (var entry : tutorials.entrySet()) {
        if (Integer.valueOf(1).equals(entry.getValue())
            && endedWithMainMission(player, entry.getKey())) expired.add(entry.getKey());
      }
      if (expired.isEmpty()) return List.of();
      Collections.sort(expired);
      expired.forEach(id -> tutorials.put(id, 2));
      try {
        player.save();
      } catch (RuntimeException failure) {
        expired.forEach(id -> tutorials.put(id, 1));
        throw failure;
      }
      return List.copyOf(expired);
    }
  }

  public Result change(Player player, boolean guide, int id, boolean finish) {
    synchronized (player) {
      if (!known(guide, id)) return new Result(Retcode.TUTORIAL_TUTORIAL_NOT_FOUND, 0, List.of());
      var states = guide ? guides : tutorials;
      int previous = states.getOrDefault(id, 0);
      if (previous < 0 || previous > 2) return new Result(Retcode.PLAYER_DATA_ERROR, 0, List.of());
      boolean expired = !guide && endedWithMainMission(player, id);
      if (previous == 2 || !finish && previous == 1 && !expired)
        return new Result(Retcode.SUCC, previous, List.of());
      // Legacy clients report guide completion directly; ordinary tutorials have an unlock RPC.
      // The original client checks finish triggers before unlock and can retire obsolete teaching
      // directly. Accept that only when its server-owned main-mission end condition is proven.
      if (finish && !guide && previous == 0 && !expired)
        return new Result(Retcode.TUTORIAL_NOT_UNLOCK, 0, List.of());
      List<GameItem> rewards = List.of();
      if (finish && guide) {
        int reward = GameData.getTutorialGuideExcelMap().get(id).getRewardID();
        var ledger = new RewardLedger(player);
        String key = "tutorial-guide:" + id;
        if (reward != 0 && !ledger.granted(key)) {
          var claim = ledger.claim(key, reward);
          if (claim.retcode() != Retcode.SUCC)
            return new Result(claim.retcode(), previous, List.of());
          rewards = claim.items();
        }
      }
      int next = finish || expired ? 2 : 1;
      states.put(id, next);
      try {
        player.save();
      } catch (RuntimeException failed) {
        if (previous == 0) states.remove(id);
        else states.put(id, previous);
        throw failed;
      }
      return new Result(Retcode.SUCC, next, rewards);
    }
  }
}
