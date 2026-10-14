package org.hkrpg.server.gameplay;

import emu.lunarcore.LunarCore;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;

/** Original 3011014 E-relay and opening actors. The original client graphs render
 * dialogue, cameras, capture, motion, intermediate replacements and graph-local strings.
 * An acknowledgement or a server actor is not evidence that the client rendered them. */
public final class DailyRecoverPerformances {
  private static final int MAIN=3011014;
  private static final Map<String,String> ENTRANCES=Map.ofEntries(
      Map.entry("Config/Level/NPCDialogue/P10102/F10102001_G99/DialogueItem301101401_F10102001_G99_N400002.json","d987ef893c0613534098f4bbc98f57b76400bc368e7bde513bed0b4a92fa9715"),
      Map.entry("Config/Level/NPCDialogue/P20121/F20121001_G93/DialogueItem301101406_F20121001_G93_N400001.json","f3f2fb5c5b7bfe39b213539b1b2d49cd4518ca03de13b600ca80127b58a1be59"),
      Map.entry("Config/Level/NPCDialogue/P20123/F20123001_G76/DialogueItem301101101_F20123001_G76_N400001.json","88fcaf1cf9f69821176f999ca6be121fb66626d0f29391c1ba14c222a09027e9"));
  static final String SOURCE=DailyMissionRecoverDeliverySources.SOURCE+":performance-entrances-v1";
  private record Opening(int plane,int floor,int group,int instance){}
  static boolean performance(int id){return id==301101401 || id==301101402 || id>=301101406 && id<=301101411;}
  private static Opening opening(int id){return switch(id){
    case 301101401 -> new Opening(10102,10102001,99,0);
    case 301101402 -> new Opening(10102,10102001,122,400001);
    case 301101406,301101409 -> new Opening(20121,20121001,94,400001);
    case 301101407,301101410 -> new Opening(20123,20123001,75,400001);
    case 301101408,301101411 -> new Opening(10102,10102003,23,400002);
    default -> null;
  };}
  static boolean scripts(){
    if(!DailyMissionRecoverDeliveryRules.source())return false;
    try{
      Path root=Path.of(LunarCore.getConfig().getResourceDir()).toRealPath();
      // The common original validator verifies complete objects, including present optional files.
      for(String path:DailyMissionRecoverDeliverySources.OPTIONAL){
        Path file=root.resolve(path);if(!Files.isRegularFile(file) || !file.toRealPath().startsWith(root))return false;
      }
      for(var entry:ENTRANCES.entrySet()){
        Path file=root.resolve(entry.getKey());
        if(!Files.isRegularFile(file) || !file.toRealPath().startsWith(root) || Files.size(file)>65536
            || !entry.getValue().equals(TimelineActivityRules.fingerprint(com.google.gson.JsonParser.parseString(Files.readString(file)))))return false;
      }
      return true;
    }catch(Exception invalid){return false;}
  }
  static boolean ordinary(Player player){
    return ordinaryScene(player) && !player.getGameplay().getCancelledMissions().contains(MAIN);
  }
  static boolean ordinaryScene(Player player){
    var g=player.getGameplay();return !player.isInBattle() && player.getInstance()==null && g.getCurrentMissionRaid()==0
        && g.getTrialRun()==null && !g.getRogue().isInside() && !g.getParkourVenue().inside()
        && g.getStoryLines().getCurrentId()==0 && !g.getStoryLines().isTransitionPending();
  }
  private static boolean matches(Player player,DailyRecoverPerformanceState.Start start,Scene scene){
    if(start==null || !start.valid() || start.uid()!=player.getUid() || start.at()>player.getMissionManager().currentInstant().getEpochSecond()
        || !ordinary(player) || start.day()!=DailyManager.day(player.getMissionManager().currentInstant()) || scene==null || scene.getPlayer()!=player
        || scene.getDimensionId()!=0 || scene.getStoryLineId()!=0 || player.getGameplay().getCancelledSubs().contains(start.performance()))return false;
    var placement=opening(start.performance());var g=player.getGameplay();var main=g.getMainMissions().get(MAIN);var sub=g.getSubMissions().get(start.performance());
    return placement!=null && scene.getPlaneId()==placement.plane() && scene.getFloorId()==placement.floor()
        && MissionStateRecords.mainDoing(player,MAIN) && MissionStateRecords.doing(sub,start.performance(),1)
        && Objects.equals(main.getObservationKey(),start.main()) && Objects.equals(sub.getObservationKey(),start.sub())
        && DailyMissionRecoverDeliveryRules.current(player,MAIN,player.getMissionManager().currentInstant());
  }
  public static Retcode start(Player player,int id,int type){
    synchronized(player){
      if(DailyRecoverIntroductions.presentation(id))return DailyRecoverIntroductions.start(player,id,type);
      // E=4 is read from the fixed local 4.6 ELevelPerformanceType constant table.
      if(type!=4 || !performance(id))return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
      var state=player.getGameplay().getDailyRecoverPerformances();
      if(!state.valid())return Retcode.PLAYER_DATA_ERROR;
      if(!ordinary(player) || !scripts())return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
      var c=GameplayDefinitions.subs.get(id);var now=player.getMissionManager().currentInstant();var scene=player.getScene();
      if(c==null || c.getMainMissionID()!=MAIN || !DailyMissionRecoverDeliveryRules.current(player,MAIN,now))return Retcode.MISSION_NOT_DOING;
      var target=opening(id);
      if(scene==null || scene.getPlayer()!=player || scene.getPlaneId()!=target.plane() || scene.getFloorId()!=target.floor()
          || player.getPlaneId()!=target.plane() || player.getFloorId()!=target.floor())return Retcode.MISSION_SCENE_NOT_MATCH;
      boolean allowed=id>=301101406 && id<=301101408
          ? DailyMissionRecoverDeliveryRules.submission(player,c,now)==Retcode.SUCC
          : DailyMissionRecoverDeliveryRules.allowsTalk(player,id);
      if(!allowed)return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
      String key=DailyRecoverPerformanceState.key(DailyManager.day(now),id);var previous=state.getStarts().get(key);
      if(previous!=null){if(!matches(player,previous,scene))return Retcode.PLAYER_DATA_ERROR;reconcile(scene);return Retcode.SUCC;}
      var main=player.getGameplay().getMainMissions().get(MAIN);var sub=player.getGameplay().getSubMissions().get(id);
      if(main==null || !MissionStateRecords.doing(sub,id,1) || state.getStarts().size()>=32768)return Retcode.PLAYER_DATA_ERROR;
      if(main.getObservationKey()!=null && !DailyMissionRecoverDeliveryState.uuid(main.getObservationKey())
          || sub.getObservationKey()!=null && !DailyMissionRecoverDeliveryState.uuid(sub.getObservationKey()))return Retcode.PLAYER_DATA_ERROR;
      String mainBefore=main.getObservationKey(),subBefore=sub.getObservationKey(),mainKey=null,subKey=null;
      try{
        mainKey=main.captureObservationKey();subKey=sub.captureObservationKey();
        state.getStarts().put(key,new DailyRecoverPerformanceState.Start(player.getUid(),id,DailyManager.day(now),now.getEpochSecond(),mainKey,subKey));player.save();
      }catch(RuntimeException failure){
        state.getStarts().remove(key);if(mainKey!=null)main.restoreObservationKey(mainKey,mainBefore);if(subKey!=null)sub.restoreObservationKey(subKey,subBefore);throw failure;
      }
      reconcile(scene);return Retcode.SUCC;
    }
  }
  /** Legacy report-only saves stay compatible; an observed relay cannot borrow a replaced quest. */
  static boolean allowsReport(Player player,int id){
    if(!performance(id))return true;
    var state=player.getGameplay().getDailyRecoverPerformances();if(!state.valid())return false;
    var record=state.getStarts().get(DailyRecoverPerformanceState.key(DailyManager.day(player.getMissionManager().currentInstant()),id));
    return record==null || matches(player,record,player.getScene()) && scripts();
  }
  private static boolean owned(Scene scene,GroupInfo group,NpcInfo npc){
    return scene!=null && group!=null && npc!=null && group.getOwnerMainMissionID()==MAIN
        && group.getLoadSide()==GroupInfo.GroupLoadSide.Server && group.getNPCList()!=null && group.getNPCList().contains(npc)
        && !npc.isLoadOnInitial() && !npc.isIsDelete() && !npc.isIsClientOnly()
        && scene.getFloorInfo()!=null && scene.getResourceGroups().get(group.getId())==group;
  }
  public static boolean spawns(Scene scene,GroupInfo group,NpcInfo npc){
    if(!owned(scene,group,npc))return false;
    var player=scene.getPlayer();if(player==null)return false;
    synchronized(player){
      var state=player.getGameplay().getDailyRecoverPerformances();if(!state.valid() || state.getStarts().isEmpty())return false;
      for(var start:state.getStarts().values()){
        var opening=opening(start.performance());
        if(opening.group()==group.getId() && opening.instance()==npc.getID() && matches(player,start,scene) && scripts())return true;
      }
      return false;
    }
  }
  public static void reconcile(Scene scene){
    if(scene==null || scene.getFloorInfo()==null || scene.getPlayer()==null)return;
    synchronized(scene.getPlayer()){
      var state=scene.getPlayer().getGameplay().getDailyRecoverPerformances();
      if(state.valid() && state.getStarts().isEmpty())return;
      try(var scope=DailyMissionRecoverDeliveryRules.sourceScope()){
        for(var group:scene.getResourceGroups().values()){
          if(group.getOwnerMainMissionID()!=MAIN || group.getNPCList()==null)continue;
          for(var npc:group.getNPCList()){
            if(!owned(scene,group,npc))continue;
            boolean wanted=scene.getLoadedGroups().contains(group.getId()) && spawns(scene,group,npc);
            var actors=scene.getEntitiesByGroup(EntityNpc.class,group.getId()).stream().filter(n->n.getInstId()==npc.getID()).toList();
            if(!wanted){for(var actor:actors)scene.removeEntity(actor);}
            else if(actors.isEmpty())scene.addEntity(scene.getEntityLoader().loadNpc(scene,group,npc),scene.isLoaded());
          }
        }
      }
    }
  }
  private DailyRecoverPerformances(){}
}
