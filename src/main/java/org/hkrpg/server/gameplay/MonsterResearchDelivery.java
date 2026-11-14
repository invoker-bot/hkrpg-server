package org.hkrpg.server.gameplay;

import com.google.gson.JsonParser;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.inventory.Inventory;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.hkrpg.server.GameDiagnostics;

/** Original 30005 research costs, reserved before debit and linked to the matching mission. */
final class MonsterResearchDelivery {
  private static final int ACTIVITY = 30005;
  private static final Map<Integer,Integer> SUBMITTERS = Map.of(
      800017004,1, 800017104,2, 800017204,3, 800017304,4,
      800017404,5, 800017504,6, 800017705,7);

  static boolean handles(int sub) { return SUBMITTERS.containsKey(sub); }
  static int choice(int sub,String talk) {
    if ((sub!=0 && sub!=800017705) || talk==null) return 0;
    return switch (talk) {
      case "ResearchA_800017705" -> 1;
      case "ResearchB_800017705" -> 2;
      case "ResearchC_800017705" -> 3;
      default -> 0;
    };
  }
  private static String key(int id) { return "material-submit:30005:" + id; }

  private static Retcode rejected(Player player,int sub,Retcode result,String reason) {
    GameDiagnostics.researchSubmitRejected(player,sub,reason);
    return result;
  }

  private static boolean original(MissionInfo.SubMissionInfo c,MaterialSubmitDefinitions.Submitter row) {
    return c.getId() > 0 && row.mission()==c.getMainMissionID()
        && c.getLevelPlaneID()==10001 && c.getLevelFloorID()==10001001
        && "Auto".equals(c.getTakeType()) && Arrays.equals(c.getTakeParamIntList(),new Integer[]{row.mission()})
        && "Talk".equals(c.getFinishType()) && "Equal".equals(c.getParamType())
        && ("Mission_"+c.getId()).equals(c.getParamStr1()) && c.getProgress()==1
        && c.getParamInt1()==0 && c.getParamInt2()==0 && c.getParamInt3()==0
        && c.getParamIntList()!=null && c.getParamIntList().length==0
        && c.getParamItemList()!=null && c.getParamItemList().isEmpty()
        && c.getFinishActionList()!=null && c.getFinishActionList().length==0
        && ("Config/Level/Mission/"+row.mission()+"/Mission_"+c.getId()+".json").equals(c.getMissionJsonPath())
        && originalScript(c,row.mission());
  }

  private static boolean originalScript(MissionInfo.SubMissionInfo c,int main) {
    if (c.getTask()==null) return false;
    try {
      var expected=JsonParser.parseString("""
          {"OnInitSequece":[],"OnStartSequece":[{"TaskList":[
            {"$type":"RPG.GameCore.WaitCustomString","CustomString":{"Value":"MainFinish_%d"}},
            {"$type":"RPG.GameCore.FinishPerformanceMission","Key":"Mission_%d"}
          ]}],"Type":"Mission"}
          """.formatted(main,c.getId()));
      return expected.equals(JsonParser.parseString(c.getTask().getSourceDefinition()));
    } catch (RuntimeException invalid) { return false; }
  }

  private static boolean intact(Player player,MaterialSubmitState state,MaterialSubmitDefinitions definitions) {
    if (state==null || !state.valid(definitions,false) || player.getGameplay().getRewards()==null) return false;
    var payouts=player.getGameplay().getRewards();
    for (var entry:payouts.entrySet()) {
      String name=entry.getKey();
      if (name==null || !name.startsWith("material-submit:30005:")) continue;
      var row=definitions.rows().values().stream().filter(r->key(r.id()).equals(name)).findFirst().orElse(null);
      var receipt=row==null?null:state.receipts().get(row.id());
      if (row==null || entry.getValue()==null || entry.getValue().getRewardId()!=row.reward()
          || receipt==null || !receipt.submitted()) return false;
    }
    return true;
  }

  static Retcode submit(Player player,MissionInfo.SubMissionInfo config,int choice) {
    synchronized (player) {
      int sub=config.getId(),id=SUBMITTERS.getOrDefault(sub,0);
      if (id==7 ? choice<1 || choice>3 : choice!=0)
        return rejected(player,sub,Retcode.MISSION_FINISH_WAY_NOT_MATCH,"missing-choice");
      var definitions=MaterialSubmitDefinitions.load(Path.of(LunarCore.getConfig().getResourceDir()),ACTIVITY);
      var row=definitions==null?null:definitions.rows().get(id);
      if (row==null || !original(config,row) || !definitions.loaded(row))
        return rejected(player,sub,Retcode.PLAYER_DATA_ERROR,"source-mismatch");
      if (player.getPlaneId()!=10001 || player.getFloorId()!=10001001)
        return rejected(player,sub,Retcode.MISSION_SCENE_NOT_MATCH,"wrong-scene");
      if (player.isInBattle() || player.getInstance()!=null || player.getGameplay().getStoryLines().isTransitionPending())
        return rejected(player,sub,Retcode.SYSTEM_BUSY,"transition");
      var storage=player.getGameplay().getActivityReplays();
      if (!storage.legacyMaterialsValid()) return rejected(player,sub,Retcode.PLAYER_DATA_ERROR,"damaged-storage");
      boolean existed=storage.hasLegacyMaterial(ACTIVITY);
      var old=existed?storage.existingLegacyMaterial(ACTIVITY):new MaterialSubmitState();
      if (!intact(player,old,definitions)) return rejected(player,sub,Retcode.PLAYER_DATA_ERROR,"damaged-receipt");
      var prior=old.receipts().get(id);
      if (prior!=null && prior.researchChoice()!=choice)
        return rejected(player,sub,Retcode.MISSION_FINISH_WAY_NOT_MATCH,"choice-mismatch");
      if (prior!=null && !prior.submitted()) return rejected(player,sub,Retcode.SYSTEM_BUSY,"pending-debit");
      if (prior==null) {
        var debit=new TreeMap<Integer,GameItem>();
        for (var cost:row.costs().entrySet()) {
          var item=player.getInventory().getMaterialByItemId(cost.getKey());
          if (item==null || item.getExcel()==null || item.getOwnerUid()!=player.getUid()
              || item.getCount()<cost.getValue() || item.isLocked() || item.isEquipped()
              || !Inventory.isStackType(item.getExcel()) || item.getExcel().isEquippable()
              || player.getInventory().getItemByUid(item.getInternalUid())!=item)
            return rejected(player,sub,Retcode.ITEM_COST_NOT_ENOUGH,"missing-material");
          debit.put(cost.getKey(),item);
        }
        var reserved=old.copy(); reserved.reserve(definitions,row,Instant.now().getEpochSecond(),choice);
        storage.legacyMaterial(ACTIVITY,reserved);
        try { player.save(); }
        catch (RuntimeException failed) {
          if (existed) storage.legacyMaterial(ACTIVITY,old); else storage.removeLegacyMaterial(ACTIVITY);
          GameDiagnostics.saveFailed(player,"research-reserve",sub,0,failed);
          return Retcode.PLAYER_DATA_ERROR;
        }
        try {
          for (var cost:new TreeMap<>(row.costs()).entrySet())
            if (!player.getInventory().removeItem(debit.get(cost.getKey()),cost.getValue()))
              return rejected(player,sub,Retcode.PLAYER_DATA_ERROR,"debit-incomplete");
        } catch (RuntimeException failed) {
          return rejected(player,sub,Retcode.PLAYER_DATA_ERROR,"debit-interrupted");
        }
        var completed=reserved.copy(); completed.complete(id); storage.legacyMaterial(ACTIVITY,completed);
        try { player.save(); }
        catch (RuntimeException failed) {
          storage.legacyMaterial(ACTIVITY,reserved);
          GameDiagnostics.saveFailed(player,"research-complete",sub,0,failed);
          return Retcode.PLAYER_DATA_ERROR;
        }
      }
      try {
        var claim=player.getRewardLedger().claim(key(id),row.reward());
        if (claim.retcode()==Retcode.SUCC || claim.retcode()==Retcode.QUEST_REWARD_ALREADY_TAKEN) return Retcode.SUCC;
        return rejected(player,sub,claim.retcode(),"reward-pending");
      } catch (RuntimeException failed) {
        return rejected(player,sub,Retcode.PLAYER_DATA_ERROR,"reward-interrupted");
      }
    }
  }

  private MonsterResearchDelivery() {}
}
