package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityNpc;
import java.util.*;

/** Global first meetings of the three original NPCs, independent of a daily mission acceptance. */
@Entity(useDiscriminator=false)
public final class DailyRecoverIntroductionState {
  private int version=1;
  private Map<Integer,Observation> records=new TreeMap<>();
  boolean seen(int id){return records.containsKey(id);}
  boolean valid(Player player){
    if(version!=1 || records==null || records.size()>3)return false;
    for(var entry:records.entrySet())if(entry.getKey()==null || entry.getValue()==null
        || entry.getKey()!=entry.getValue().performance || !entry.getValue().valid(player))return false;
    return true;
  }
  DailyRecoverIntroductionState recorded(Observation observation){
    var gson=ResourceLoader.getGson();var next=gson.fromJson(gson.toJson(this),DailyRecoverIntroductionState.class);
    if(next.records.putIfAbsent(observation.performance,observation)!=null)throw new IllegalStateException("Already met original NPC");
    return next;
  }
  @Entity(useDiscriminator=false)
  public static final class Observation {
    private int uid,performance,plane,floor,group,instance,npc,entity;
    private long runtime,at;
    private String source,event;
    protected Observation(){}
    Observation(Player player,int id,EntityNpc actor){
      uid=player.getUid();performance=id;plane=actor.getScene().getPlaneId();floor=actor.getScene().getFloorId();
      group=actor.getGroupId();instance=actor.getInstId();npc=actor.getNpcId();entity=actor.getEntityId();runtime=actor.getScene().getRuntimeId();
      at=player.getMissionManager().currentInstant().getEpochSecond();source=DailyRecoverIntroductionSources.SOURCE;event=UUID.randomUUID().toString();
    }
    boolean valid(Player player){
      var placement=DailyRecoverIntroductions.placement(performance);
      return DailyRecoverIntroductions.belongs(performance) && uid>0 && uid==player.getUid() && placement!=null
          && plane==placement.plane() && floor==placement.floor() && group==placement.group() && instance==placement.instance()
          && npc==placement.npc() && entity>0 && runtime>0 && at>0 && at<=player.getMissionManager().currentInstant().getEpochSecond()
          && DailyRecoverIntroductionSources.SOURCE.equals(source) && DailyMissionRecoverDeliveryState.uuid(event);
    }
  }
}
