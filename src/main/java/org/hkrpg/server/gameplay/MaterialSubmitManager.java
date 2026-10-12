package org.hkrpg.server.gameplay;

import emu.lunarcore.LunarCore;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Real owned material debit and persistent reservation; ambiguous interrupted debits stay busy. */
public final class MaterialSubmitManager {
  public record Result(Retcode code,MaterialSubmitState state,List<GameItem> items) {
    public Result(Retcode code){this(code,null,List.of());}
  }
  private final Player player;
  public MaterialSubmitManager(Player player){this.player=Objects.requireNonNull(player);}
  private MaterialSubmitDefinitions definitions(){return MaterialSubmitDefinitions.load(Path.of(LunarCore.getConfig().getResourceDir()));}
  public static String rewardKey(int id){return "material-submit:30014:"+id;}
  private boolean rewardIntegrity(MaterialSubmitState state,MaterialSubmitDefinitions definitions) {
    var payouts=player.getGameplay().getRewards();if(payouts==null)return false;
    for(var key:payouts.keySet())if(key!=null && key.startsWith("material-submit:30014:")
        && definitions.rows().keySet().stream().noneMatch(id->rewardKey(id).equals(key)))return false;
    for(int id=301;id<=307;id++) {
      var payout=payouts.get(rewardKey(id));
      var submission=state.receipts().get(id);
      if(payouts.containsKey(rewardKey(id)) && (payout==null || payout.getRewardId()!=definitions.rows().get(id).reward()
          || submission==null || !submission.submitted())) return false;
    }
    return true;
  }
  private boolean open(MaterialSubmitDefinitions.Submitter row) {
    long now=Instant.now().getEpochSecond();
    var panel=emu.lunarcore.data.GameData.getActivityPanelExcelMap().get(30014);
    if(panel==null || !"[PlayerLevel:21]".equals(panel.getUnlockConditions())) return false;
    return player.getLevel()>=21 && GameplayDefinitions.windows.stream().anyMatch(w->
        w.panelId()==30014 && w.moduleId()==row.module() && w.beginTime()<=now && now<w.endTime());
  }
  public Result query() {
    synchronized(player) {
      var definitions=definitions();var state=player.getGameplay().getMaterialSubmission();
      if(definitions==null || state==null || !state.valid(definitions) || !rewardIntegrity(state,definitions)) return new Result(Retcode.PLAYER_DATA_ERROR);
      return new Result(Retcode.SUCC,state,List.of());
    }
  }
  public Result submit(int id) {
    synchronized(player) {
      var definitions=definitions();var old=player.getGameplay().getMaterialSubmission();
      if(definitions==null || old==null || !old.valid(definitions) || !rewardIntegrity(old,definitions)) return new Result(Retcode.PLAYER_DATA_ERROR);
      var row=definitions.rows().get(id);
      if(row==null || !open(row)) return new Result(Retcode.REQ_PARA_INVALID);
      if(!definitions.loaded(row)) return new Result(Retcode.PLAYER_DATA_ERROR);
      var previous=old.receipts().get(id);
      if(previous!=null) return previous.submitted()?new Result(Retcode.SUCC,old,List.of()):new Result(Retcode.SYSTEM_BUSY);
      if(id>301 && (old.receipts().get(id-1)==null || !old.receipts().get(id-1).submitted())) return new Result(Retcode.REQ_PARA_INVALID);
      if(player.isInBattle() || player.getInstance()!=null || player.getGameplay().getStoryLines().isTransitionPending()) return new Result(Retcode.SYSTEM_BUSY);
      var debit=new TreeMap<Integer,GameItem>();
      for(var cost:row.costs().entrySet()) {
        var item=player.getInventory().getMaterialByItemId(cost.getKey());
        if(item==null || item.getExcel()==null || item.getOwnerUid()!=player.getUid()
            || item.getCount()<cost.getValue() || item.isLocked() || item.isEquipped()
            || !Inventory.isStackType(item.getExcel()) || item.getExcel().isEquippable()
            || player.getInventory().getItemByUid(item.getInternalUid())!=item) return new Result(Retcode.ITEM_COST_NOT_ENOUGH);
        debit.put(cost.getKey(),item);
      }
      var reserved=old.copy();reserved.reserve(definitions,row,Instant.now().getEpochSecond());
      player.getGameplay().materialSubmission(reserved);
      try{player.save();}catch(RuntimeException failed){player.getGameplay().materialSubmission(old);return new Result(Retcode.PLAYER_DATA_ERROR);}
      try {
        for(var cost:new TreeMap<>(row.costs()).entrySet())
          if(!player.getInventory().removeItem(debit.get(cost.getKey()),cost.getValue())) return new Result(Retcode.PLAYER_DATA_ERROR);
      } catch(RuntimeException failed){return new Result(Retcode.PLAYER_DATA_ERROR);}
      var completed=reserved.copy();completed.complete(id);player.getGameplay().materialSubmission(completed);
      try{player.save();}catch(RuntimeException failed){player.getGameplay().materialSubmission(reserved);return new Result(Retcode.PLAYER_DATA_ERROR);}
      return new Result(Retcode.SUCC,completed,List.of());
    }
  }
  public Result take(int id) {
    synchronized(player) {
      var definitions=definitions();var state=player.getGameplay().getMaterialSubmission();
      if(definitions==null || state==null || !state.valid(definitions) || !rewardIntegrity(state,definitions)) return new Result(Retcode.PLAYER_DATA_ERROR);
      var row=definitions.rows().get(id);var receipt=state.receipts().get(id);
      if(row==null || !open(row) || receipt==null || !receipt.submitted()) return new Result(Retcode.REQ_PARA_INVALID);
      if(!definitions.loaded(row))return new Result(Retcode.PLAYER_DATA_ERROR);
      if(player.isInBattle() || player.getInstance()!=null || player.getGameplay().getStoryLines().isTransitionPending())return new Result(Retcode.SYSTEM_BUSY);
      try {
        var result=player.getRewardLedger().claim(rewardKey(id),row.reward());
        return new Result(result.retcode(),state,result.items());
      }catch(RuntimeException failed){return new Result(Retcode.PLAYER_DATA_ERROR);}
    }
  }
}
