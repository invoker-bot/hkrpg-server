package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;

/** Start witnesses only. Actual quest completion determines when the server actor is released. */
@Entity(useDiscriminator=false)
public final class DailyRecoverPerformanceState {
  private int version=1;
  private Map<String,Start> starts=new HashMap<>();
  public Map<String,Start> getStarts(){return starts;}
  boolean valid(){
    if(version!=1 || starts==null || starts.size()>32768)return false;
    for(var e:starts.entrySet())if(e.getValue()==null || !e.getValue().valid() || !e.getKey().equals(key(e.getValue().day,e.getValue().performance)))return false;
    return true;
  }
  static String key(long day,int performance){return day+":"+performance;}
  @Entity(useDiscriminator=false)
  public static final class Start {
    private int uid,performance;
    private long day,startedAt;
    private String source,mainAcceptance,subAcceptance;
    protected Start(){}
    Start(int uid,int performance,long day,long at,String main,String sub){
      this.uid=uid;this.performance=performance;this.day=day;startedAt=at;
      source=DailyRecoverPerformances.SOURCE;mainAcceptance=main;subAcceptance=sub;
    }
    boolean valid(){return uid>0 && DailyRecoverPerformances.performance(performance)
        && DailyMissionRecoverDeliveryState.time(startedAt,day)
        && DailyRecoverPerformances.SOURCE.equals(source)
        && DailyMissionRecoverDeliveryState.uuid(mainAcceptance) && DailyMissionRecoverDeliveryState.uuid(subAcceptance);}
    int uid(){return uid;} int performance(){return performance;} long day(){return day;} long at(){return startedAt;}
    String main(){return mainAcceptance;} String sub(){return subAcceptance;}
  }
}
