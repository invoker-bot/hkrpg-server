package org.hkrpg.server.gameplay;

import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;
import org.hkrpg.server.GameDiagnostics;

/** Explicit completion writes start after any independently saved actions or item debits. */
final class MissionCompletionWrites {
  static void complete(Player player, MissionInfo.SubMissionInfo source, QuestState state) {
    complete(player,source,state,Map.of());
  }

  static void complete(Player player, MissionInfo.SubMissionInfo source, QuestState state,
      Map<String,Integer> updates) {
    synchronized (player) {
      var rollbackProgress=state.progressCheckpoint();
      var values=player.getGameplay().getStoryValues();
      var previousValues=new HashMap<String,Integer>();
      for(var key:updates.keySet())if(values.containsKey(key))previousValues.put(key,values.get(key));
      String key=DailyMissionRepeats.subKey(player,source);
      var plans=player.getGameplay().getMissionRewardPlans();
      boolean hadPlan=plans.containsKey(key);var previousPlan=plans.get(key);
      try {
        values.putAll(updates);
        state.advance(Math.max(1,source.getProgress()),Math.max(1,source.getProgress()),
            player.getMissionManager().currentInstant().getEpochSecond());
        new MissionRewards(player).freezeSub(source);
        player.save();
      } catch(RuntimeException failed) {
        rollbackProgress.run();
        for(var name:updates.keySet()) {
          if(previousValues.containsKey(name))values.put(name,previousValues.get(name));else values.remove(name);
        }
        if(hadPlan)plans.put(key,previousPlan);else plans.remove(key);
        GameDiagnostics.saveFailed(player,"mission-complete",source.getId(),0,failed);
        throw failed;
      }
    }
  }

  private MissionCompletionWrites() {}
}
