package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;

/** Original NPC intro/menu history only; client presentation never grants daily progress. */
public final class DailyRecoverIntroductions {
  record Placement(int intro,int plane,int floor,int group,int instance,int npc){}
  static Placement placement(int id){return switch(id){
    case 501028201,501028202 -> new Placement(501028201,10102,10102001,99,400002,11302);
    case 501028001,501028002 -> new Placement(501028001,20121,20121001,93,400001,11104);
    case 501030700,501030701 -> new Placement(501030700,20123,20123001,76,400001,11205);
    default -> null;
  };}
  public static boolean belongs(int id){var p=placement(id);return p!=null && p.intro()==id;}
  static boolean presentation(int id){return placement(id)!=null;}
  private static boolean scripts(){
    if(!DailyRecoverPerformances.scripts())return false;
    try{
      var root=Path.of(LunarCore.getConfig().getResourceDir()).toRealPath();
      for(var entry:DailyRecoverIntroductionSources.FILES.entrySet()){
        var path=root.resolve(entry.getKey());
        if(!Files.isRegularFile(path) || !path.toRealPath().startsWith(root) || Files.size(path)>65536
            || !entry.getValue().equals(TimelineActivityRules.fingerprint(JsonParser.parseString(Files.readString(path)))))return false;
      }
      var table=root.resolve("ExcelOutput/PerformanceE.json");
      if(!table.toRealPath().startsWith(root) || Files.size(table)>8388608)return false;
      var rows=JsonParser.parseString(Files.readString(table)).getAsJsonArray();if(rows.size()>65536)return false;
      var found=new HashSet<Integer>();
      for(var value:rows){
        var row=value.getAsJsonObject();int id=row.has("PerformanceID")?row.get("PerformanceID").getAsInt():0;
        var pin=DailyRecoverIntroductionSources.PERFORMANCES.get(id);
        if(pin!=null && (!found.add(id) || !pin.equals(TimelineActivityRules.fingerprint(row))))return false;
      }
      return found.equals(DailyRecoverIntroductionSources.PERFORMANCES.keySet());
    }catch(Exception invalid){return false;}
  }
  private static EntityNpc actor(Player player,Placement p){
    if(p==null || !DailyRecoverPerformances.ordinaryScene(player)
        || !DailyMissionRecoverDeliveryRules.npc(player,p.plane(),p.floor(),p.group(),p.instance(),p.npc()))return null;
    return player.getScene().getEntitiesByGroup(EntityNpc.class,p.group()).stream().filter(n->n.getInstId()==p.instance()).findFirst().orElse(null);
  }
  public static boolean seen(Player player,int id){
    synchronized(player){var state=player.getGameplay().getDailyRecoverIntroductions();return belongs(id) && state!=null && state.valid(player) && scripts() && state.seen(id);}
  }
  static Retcode start(Player player,int id,int type){
    var p=placement(id);var state=player.getGameplay().getDailyRecoverIntroductions();
    if(type!=4 || p==null || !scripts())return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
    if(state==null || !state.valid(player))return Retcode.PLAYER_DATA_ERROR;
    if(actor(player,p)==null)return Retcode.MISSION_SCENE_NOT_MATCH;
    return id==p.intro() || state.seen(p.intro())?Retcode.SUCC:Retcode.MISSION_NOT_DOING;
  }
  public static Retcode finish(Player player,int id){
    synchronized(player){
      var state=player.getGameplay().getDailyRecoverIntroductions();
      if(!belongs(id) || !scripts())return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
      if(state==null || !state.valid(player))return Retcode.PLAYER_DATA_ERROR;
      if(state.seen(id))return Retcode.SUCC;
      var actor=actor(player,placement(id));if(actor==null)return Retcode.MISSION_SCENE_NOT_MATCH;
      var next=state.recorded(new DailyRecoverIntroductionState.Observation(player,id,actor));
      if(!next.valid(player))return Retcode.PLAYER_DATA_ERROR;
      player.getGameplay().dailyRecoverIntroductions(next);
      try{player.save();}catch(RuntimeException failed){player.getGameplay().dailyRecoverIntroductions(state);return Retcode.SYSTEM_BUSY;}
      return Retcode.SUCC;
    }
  }
  private DailyRecoverIntroductions(){}
}
