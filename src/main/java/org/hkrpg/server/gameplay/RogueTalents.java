package org.hkrpg.server.gameplay;

import emu.lunarcore.GameConstants;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/**
 * Acquired talents and their cost are saved with the same player document. Effects remain separate.
 */
public final class RogueTalents {
  public enum Status {
    LOCK,
    UNLOCK,
    ENABLE
  }

  public static Status status(Player player, int id) {
    var talent = GameplayDefinitions.rogueTalents.get(id);
    if (talent == null || id <= 0) return Status.LOCK;
    if (player.getGameplay().getRogueTalents().contains(id)) return Status.ENABLE;
    try {
      int gate = Integer.parseInt(GameplayDefinitions.rogueConstants.get("Rogue_Talent_Unlock_ID"));
      if (gate <= 0 || !RogueUnlocks.open(player, gate)) return Status.LOCK;
    } catch (RuntimeException e) {
      return Status.LOCK;
    }
    var unlocks = talent.UnlockIDList();
    if (cost(talent).isEmpty()
        || unlocks == null
        || new HashSet<>(unlocks).size() != unlocks.size()
        || unlocks.stream().anyMatch(u -> u == null || u <= 0 || !RogueUnlocks.open(player, u)))
      return Status.LOCK;
    return Status.UNLOCK;
  }

  public static Retcode enable(Player player, int id) {
    synchronized (player) {
      var talent = GameplayDefinitions.rogueTalents.get(id);
      if (talent == null || id <= 0) return Retcode.REQ_PARA_INVALID;
      if (player.getGameplay().getRogueTalents().contains(id))
        return Retcode.QUEST_REWARD_ALREADY_TAKEN;
      if (status(player, id) != Status.UNLOCK) return Retcode.ROGUE_STATUS_NOT_MATCH;
      if (!player.tryChangeTalentPoints(-cost(talent).orElseThrow()))
        return Retcode.ITEM_NOT_ENOUGH;
      player.getGameplay().getRogueTalents().add(id);
      // Debit and activation are persisted together, before sending a response or triggering
      // effects.
      player.save();
      org.hkrpg.server.protocol.RogueTalent46.syncCurrency(player);
      player.getQuestManager().onRogueTalentProgress();
      return Retcode.SUCC;
    }
  }

  private static OptionalInt cost(GameplayDefinitions.RogueTalent talent) {
    if (talent.Cost() == null || talent.Cost().isEmpty()) return OptionalInt.empty();
    long total = 0;
    for (var item : talent.Cost()) {
      if (item == null
          || item.ItemID() != GameConstants.ROGUE_TALENT_COIN_ID
          || item.ItemNum() <= 0) return OptionalInt.empty();
      total += item.ItemNum();
      if (total > Integer.MAX_VALUE) return OptionalInt.empty();
    }
    return OptionalInt.of((int) total);
  }

  private RogueTalents() {}
}
