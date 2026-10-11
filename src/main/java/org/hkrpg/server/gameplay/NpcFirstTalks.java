package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;

/** Original G61 standalone initial meeting; no sub-mission or reward is inferred from its ID. */
public final class NpcFirstTalks {
  public static boolean belongs(int id) { return id == NpcFirstTalkSources.PERFORMANCE; }

  public static boolean seen(Player player, int id) {
    synchronized (player) {
      var rules = NpcFirstTalkRules.load();
      var state = player.getGameplay().getNpcFirstTalks();
      return belongs(id) && state.valid(player, rules) && state.getRecords().containsKey(id);
    }
  }

  public static Retcode finish(Player player, int id) {
    synchronized (player) {
      var rules = NpcFirstTalkRules.load();
      if (!belongs(id) || rules == null) return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
      var state = player.getGameplay().getNpcFirstTalks();
      if (!state.valid(player, rules)) return Retcode.PLAYER_DATA_ERROR;
      if (state.getRecords().containsKey(id)) return Retcode.SUCC;
      if (player.getBattle() != null) return Retcode.IN_BATTLE_NOW;
      var actor = rules.actor(player);
      if (actor == null) return Retcode.MISSION_SCENE_NOT_MATCH;
      var observation = new NpcFirstTalkState.Observation(player, actor, rules, Instant.now().getEpochSecond());
      var next = state.recorded(observation);
      if (!next.valid(player, rules)) return Retcode.PLAYER_DATA_ERROR;
      player.getGameplay().npcFirstTalks(next);
      try { player.save(); }
      catch (RuntimeException failed) {
        player.getGameplay().npcFirstTalks(state);
        return Retcode.SYSTEM_BUSY;
      }
      return Retcode.SUCC;
    }
  }

  private NpcFirstTalks() {}
}
