package org.hkrpg.server.gameplay;

import emu.lunarcore.LunarCore;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.util.*;

/** Player-document mutations on an existing roster; a failed save leaves the old objects intact. */
public final class AetherDivideManager {
  public record CoreStock(int itemId,int count,int dressedSpirit) {}
  public record Stock(Map<Integer,Integer> fragments,Map<Integer,CoreStock> cores) {}
  public record Result(Retcode code,AetherDivideState state,int slot,AetherDivideDefinitions definitions,Stock stock) {
    public Result(Retcode code,AetherDivideState state,int slot) { this(code,state,slot,null,null); }
    public Result(Retcode code,AetherDivideState state,int slot,AetherDivideDefinitions definitions) {
      this(code,state,slot,definitions,null);
    }
  }
  private final Player player;
  public AetherDivideManager(Player player) { this.player=Objects.requireNonNull(player); }
  public Result snapshot() {
    synchronized(player) {
      var definitions=AetherDivideDefinitions.load(Path.of(LunarCore.getConfig().getResourceDir()));
      if(definitions==null) return new Result(Retcode.REQ_PARA_INVALID,null,0);
      var state=player.getGameplay().getAetherDivide();
      if(state==null || !state.valid(definitions)) return new Result(Retcode.PLAYER_DATA_ERROR,null,0);
      var stock=stock(state,definitions);
      return stock!=null ? new Result(Retcode.SUCC,state,0,definitions,stock) : new Result(Retcode.PLAYER_DATA_ERROR,null,0);
    }
  }
  private Stock stock(AetherDivideState state,AetherDivideDefinitions definitions) {
    try {
      var fragments=new TreeMap<Integer,Integer>();var cores=new TreeMap<Integer,CoreStock>();
      for(int id:state.getSpirits().keySet()) {
        int count=materialCount(definitions.spirits().get(id).fragmentItem());
        if(count<0) return null;fragments.put(id,count);
      }
      for(int item:definitions.skills()) {
        int count=materialCount(item);if(count<0) return null;
        var saved=state.getSkillCores().get(item);int dressed=saved==null?0:saved.getDressedSpirit();
        if(dressed!=0 && count==0) return null;
        if(count>0) cores.put(item,new CoreStock(item,count,dressed));
      }
      return new Stock(Collections.unmodifiableMap(fragments),Collections.unmodifiableMap(cores));
    } catch(RuntimeException invalid) { return null; }
  }
  private int materialCount(int id) {
    var item=player.getInventory().getMaterialByItemId(id);
    if(item==null) return 0;return item.getItemId()==id?item.getCount():-1;
  }
  public Result lineup(int slot,List<Integer> ids) {
    synchronized(player) {
      var definitions=AetherDivideDefinitions.load(Path.of(LunarCore.getConfig().getResourceDir()));
      if(definitions==null) return new Result(Retcode.REQ_PARA_INVALID,null,0);
      var old=player.getGameplay().getAetherDivide();
      if(old==null || !old.valid(definitions)) return new Result(Retcode.PLAYER_DATA_ERROR,null,0);
      if(player.getBattle()!=null || slot<1 || slot>6 || ids==null || ids.size()>4) return new Result(Retcode.REQ_PARA_INVALID,null,0);
      var seen=new HashSet<Integer>();
      for(Integer id:ids)
        if(id==null || id<0 || id!=0 && (!old.getSpirits().containsKey(id) || !seen.add(id)))
          return new Result(Retcode.REQ_PARA_INVALID,null,0);
      // The native request builder sends the supplied uint array, including a short or empty
      // array. This server replaces the complete lineup; explicit zero tail positions ensure
      // the native response consumer clears previously selected members.
      var complete=new ArrayList<Integer>(ids);while(complete.size()<4) complete.add(0);
      if(old.getLineups().get(slot).equals(complete)) return new Result(Retcode.SUCC,old,slot,definitions);
      var next=old.copy();next.lineup(slot,complete,definitions.fingerprint());
      return save(old,next,slot,definitions);
    }
  }
  public Result switchSlot(int slot) {
    synchronized(player) {
      var definitions=AetherDivideDefinitions.load(Path.of(LunarCore.getConfig().getResourceDir()));
      if(definitions==null) return new Result(Retcode.REQ_PARA_INVALID,null,0);
      var old=player.getGameplay().getAetherDivide();
      if(old==null || !old.valid(definitions)) return new Result(Retcode.PLAYER_DATA_ERROR,null,0);
      if(player.getBattle()!=null || slot<1 || slot>6) return new Result(Retcode.REQ_PARA_INVALID,null,0);
      if(old.getCurrentSlot()==slot) return new Result(Retcode.SUCC,old,slot,definitions);
      var next=old.copy();next.currentSlot(slot,definitions.fingerprint());return save(old,next,slot,definitions);
    }
  }
  private Result save(AetherDivideState old,AetherDivideState next,int slot,AetherDivideDefinitions definitions) {
    player.getGameplay().aetherDivide(next);
    try { player.save(); }
    catch(RuntimeException failure) {
      player.getGameplay().aetherDivide(old); return new Result(Retcode.PLAYER_DATA_ERROR,null,0);
    }
    return new Result(Retcode.SUCC,next,slot,definitions);
  }
}
