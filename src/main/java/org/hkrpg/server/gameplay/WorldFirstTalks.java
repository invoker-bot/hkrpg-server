package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;

/** Only a live original prop may report the corresponding standalone presentation. */
public final class WorldFirstTalks {
  public static boolean belongs(int id) {
    return id >= 304002001 && id <= 304002026;
  }

  public static boolean seen(Player p, int id) {
    synchronized (p) {
      var rules = WorldFirstTalkRules.load();
      var domain = p.getGameplay().getWorldFirstTalks();
      return belongs(id) && domain.valid(rules) && domain.getRecords().containsKey(id);
    }
  }

  public static Retcode finish(Player p, int id) {
    synchronized (p) {
      var rules = WorldFirstTalkRules.load();
      var domain = p.getGameplay().getWorldFirstTalks();
      if (!belongs(id) || rules == null) return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
      if (!domain.valid(rules)) return Retcode.PLAYER_DATA_ERROR;
      if (domain.getRecords().containsKey(id)) return Retcode.SUCC;
      if (p.getBattle() != null) return Retcode.IN_BATTLE_NOW;
      var actor = rules.actor(p, id);
      if (actor == null) return Retcode.MISSION_SCENE_NOT_MATCH;
      var now = Instant.now();
      var observation =
          new WorldFirstTalkState.Observation(p, id, actor, rules, now.getEpochSecond());
      var next = domain.recorded(observation, rules.window(p, now));
      if (!next.valid(rules)) return Retcode.PLAYER_DATA_ERROR;
      p.getGameplay().worldFirstTalks(next);
      try {
        p.save();
      } catch (RuntimeException failed) {
        p.getGameplay().worldFirstTalks(domain);
        return Retcode.SYSTEM_BUSY;
      }
      p.getQuestManager().onWorldFirstTalkProgress();
      return Retcode.SUCC;
    }
  }

  private WorldFirstTalks() {}
}
