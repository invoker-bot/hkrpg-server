package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.*;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Finite original non-pool sequel. This never executes a client Act or invents a daily reset. */
final class DailyMissionFollowupRules {
  private static final Gson GSON=ResourceLoader.getGson();
  static boolean handles(int id){return id==DailyMissionFollowupSources.MAIN && DailyMissionCatalog.configured();}
  static Retcode check(Player player,Instant now){
    if(now==null)return Retcode.MISSION_NOT_DOING;
    // MultiSequence is an acceptance gate. The accepted non-pool stage remains independent
    // when the pool parent later starts another period; do not invent a sequel expiry policy.
    if(player.getGameplay().getMainMissions().containsKey(DailyMissionFollowupSources.MAIN))
      return source()?Retcode.SUCC:Retcode.MISSION_NOT_DOING;
    if(!MissionStateRecords.mainCompleted(player,DailyMissionFollowupSources.PARENT))return Retcode.MISSION_NOT_DOING;
    var parent=player.getGameplay().getMainMissions().get(DailyMissionFollowupSources.PARENT);
    return parent.getFinishTime()<=now.getEpochSecond() && source()?Retcode.SUCC:Retcode.MISSION_NOT_DOING;
  }
  private static boolean source(){
    try{
      if(DailyMissionCatalog.mission(DailyMissionFollowupSources.MAIN)!=null)return false;
      var main=GameData.getMainMissionExcelMap().get(DailyMissionFollowupSources.MAIN);
      var graph=GameData.getMissionInfos().get(DailyMissionFollowupSources.MAIN);
      if(main==null || graph==null || !DailyMissionFollowupSources.MAIN_PIN.equals(TimelineActivityRules.fingerprint(GSON.toJsonTree(main))))return false;
      var normalized=GSON.toJsonTree(graph).getAsJsonObject();
      for(var raw:normalized.getAsJsonArray("SubMissionList"))raw.getAsJsonObject().remove("task");
      if(!DailyMissionFollowupSources.GRAPH_PIN.equals(TimelineActivityRules.fingerprint(normalized)))return false;
      for(var sub:graph.getSubMissionList())if(GameplayDefinitions.subs.get(sub.getId())!=sub || sub.getStoryTrialTask()!=null)return false;
      var task=GameplayDefinitions.subs.get(DailyMissionFollowupSources.TALK).getTask();
      if(task==null || task.getSourceDefinition()==null || task.getSourceDefinition().length()>262144
          || !DailyMissionFollowupSources.TASK_PIN.equals(TimelineActivityRules.fingerprint(JsonParser.parseString(task.getSourceDefinition()))))return false;
      var floor=GameData.getFloorInfo(20002,20002001);
      if(floor==null || !floor.isLoaded() || !floor.isGroupInDimension(85,0) || floor.getMainDimension()==null
          || Collections.frequency(floor.getMainDimension().getGroupIndexList(),67)!=1
          || floor.getSimpleGroupList()==null || floor.getSimpleGroupList().size()<=67)return false;
      var entry=floor.getSimpleGroupList().get(67);var group=floor.getGroups(0).get(85);
      if(entry==null || entry.getID()!=85 || entry.isIsDelete() || !DailyMissionFollowupSources.GROUP_PATH.equals(entry.getGroupPath())
          || group==null || group.getId()!=85 || floor.getGroupInfoByIndex(67)!=group
          || !DailyMissionFollowupSources.GROUP_PIN.equals(TimelineActivityRules.fingerprint(GSON.toJsonTree(group))))return false;
      for(var npc:group.getNPCList()){
        var row=GameData.getNpcExcelMap().get(npc.getNPCID());if(row==null || row.getId()!=npc.getNPCID())return false;
      }
      var prop=GameData.getPropExcelMap().get(100);if(prop==null || prop.getId()!=100)return false;
      var root=Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      for(var expected:DailyMissionFollowupSources.FILES.entrySet()){
        var file=root.resolve(expected.getKey()).normalize();
        if(!file.startsWith(root) || !Files.isRegularFile(file) || Files.size(file)>4*1024*1024
            || !file.toRealPath().startsWith(root.toRealPath())
            || !expected.getValue().equals(TimelineActivityRules.fingerprint(JsonParser.parseString(Files.readString(file)))))return false;
      }
      return true;
    }catch(Exception invalid){return false;}
  }
  static boolean loaded(Player player){
    synchronized(player){
      if(!source() || !MissionStateRecords.mainDoing(player,DailyMissionFollowupSources.MAIN)
          || !MissionStateRecords.subCompleted(player,DailyMissionFollowupSources.ENTER)
          || !MissionStateRecords.doing(player.getMissionManager().sub(DailyMissionFollowupSources.TALK),DailyMissionFollowupSources.TALK,1))return false;
      var scene=player.getScene();var floor=GameData.getFloorInfo(20002,20002001);
      if(scene==null || scene.getPlayer()!=player || player.getPlaneId()!=20002 || player.getFloorId()!=20002001
          || scene.getPlaneId()!=20002 || scene.getFloorId()!=20002001 || scene.getDimensionId()!=0 || scene.getStoryLineId()!=0
          || player.getGameplay().getStoryLines().getCurrentId()!=0 || scene.getFloorInfo()!=floor || !scene.getLoadedGroups().contains(85))return false;
      var group=floor.getGroups(0).get(85);
      if(scene.getResourceGroups().get(85)!=group)return false;
      var npcs=scene.getEntitiesByGroup(EntityNpc.class,85);var props=scene.getEntitiesByGroup(EntityProp.class,85);
      if(npcs.size()!=2 || props.size()!=1)return false;
      for(var info:group.getNPCList())if(npcs.stream().filter(npc->npc.getScene()==scene && npc.getInstId()==info.getID()
          && npc.getNpcId()==info.getNPCID() && npc.getPos().equals(info.getPos()) && npc.getRot().equals(info.getRot())).count()!=1)return false;
      for(var info:group.getPropList())if(props.stream().filter(prop->prop.getScene()==scene && prop.getPropInfo()==info
          && prop.getInstId()==info.getID() && prop.getPropId()==info.getPropID()).count()!=1)return false;
      return true;
    }
  }
  private DailyMissionFollowupRules() {}
}
