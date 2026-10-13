package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;

/** Actual selected-item debits bind to one source daily acceptance in the Player document. */
@Entity(useDiscriminator=false)
public final class DailyMissionSelectedItemState {
  private int version=1;
  private Map<String,Receipt> records=new HashMap<>();
  public DailyMissionSelectedItemState(){}
  DailyMissionSelectedItemState(DailyMissionSelectedItemState previous){records.putAll(previous.records);}
  public int getVersion(){return version;}
  public Map<String,Receipt> getRecords(){return records;}
  static boolean uuid(String value){
    if(value==null)return false;
    try{return UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException bad){return false;}
  }
  boolean valid(){
    if(version!=1 || records==null || records.size()>4096)return false;
    for(var e:records.entrySet())if(e.getValue()==null || !e.getValue().valid() || !e.getKey().equals(e.getValue().main+":"+e.getValue().day))return false;
    return true;
  }
  @Entity(useDiscriminator=false)
  public static final class Receipt {
    private int uid,main,select,talk,item;
    private long day,at,reportedAt,retiredAt;
    private String source,event,mainAcceptance,selectAcceptance,talkAcceptance;
    private GameplayState.ActionCursor retiredPayment;
    private GameplayState.MissionPaymentPlan retiredPlan;
    protected Receipt(){}
    Receipt(int uid,int main,long day,long at,int item,String mainAcceptance,String selectAcceptance,String talkAcceptance){
      this.uid=uid;this.main=main;select=main*100+1;talk=main*100+2;this.item=item;this.day=day;this.at=at;
      this.source=DailyMissionSelectedItemSources.DEFINITIONS.get(main).source();event=UUID.randomUUID().toString();
      this.mainAcceptance=mainAcceptance;this.selectAcceptance=selectAcceptance;this.talkAcceptance=talkAcceptance;
    }
    Receipt(Receipt previous){
      uid=previous.uid;main=previous.main;select=previous.select;talk=previous.talk;item=previous.item;
      day=previous.day;at=previous.at;reportedAt=previous.reportedAt;retiredAt=previous.retiredAt;
      source=previous.source;event=previous.event;mainAcceptance=previous.mainAcceptance;
      selectAcceptance=previous.selectAcceptance;talkAcceptance=previous.talkAcceptance;
      retiredPayment=previous.retiredPayment;retiredPlan=previous.retiredPlan;
    }
    Receipt reported(long value){var copy=new Receipt(this);copy.reportedAt=value;return copy;}
    Receipt retired(long value,GameplayState.ActionCursor payment,GameplayState.MissionPaymentPlan plan){
      var copy=new Receipt(this);copy.retiredAt=value;copy.retiredPayment=payment;copy.retiredPlan=plan;return copy;
    }
    public int getUid(){return uid;} public int getMain(){return main;} public int getSelect(){return select;}
    public int getTalk(){return talk;} public int getItem(){return item;} public long getDay(){return day;}
    public long getAt(){return at;} public long getReportedAt(){return reportedAt;} public long getRetiredAt(){return retiredAt;}
    public String getSource(){return source;} public String getEvent(){return event;}
    public String getMainAcceptance(){return mainAcceptance;} public String getSelectAcceptance(){return selectAcceptance;}
    public String getTalkAcceptance(){return talkAcceptance;}
    public GameplayState.ActionCursor getRetiredPayment(){return retiredPayment;}
    public GameplayState.MissionPaymentPlan getRetiredPlan(){return retiredPlan;}
    boolean valid(){
      var definition=DailyMissionSelectedItemSources.DEFINITIONS.get(main);
      if(definition==null || uid<=0 || select!=definition.select() || talk!=definition.talk() || at<=0 || day==Long.MIN_VALUE
          || DailyManager.day(java.time.Instant.ofEpochSecond(at))!=day || !definition.source().equals(source)
          || !uuid(event) || !uuid(mainAcceptance) || !uuid(selectAcceptance) || !uuid(talkAcceptance)
          || !choices(main).contains(item) || reportedAt<0 || retiredAt<0
          || reportedAt>0 && (reportedAt<at || DailyManager.day(java.time.Instant.ofEpochSecond(reportedAt))!=day)
          || retiredAt>0 && (retiredAt<at || DailyManager.day(java.time.Instant.ofEpochSecond(retiredAt))<=day))return false;
      if(retiredAt==0)return retiredPayment==null && retiredPlan==null;
      return paid(retiredPayment,retiredPlan,item);
    }
  }
  static Set<Integer> choices(int main){return main==3011605?Set.of(401004,401012):main==3011608?Set.of(401007,401009,401011,402001,402005):Set.of();}
  static boolean paid(GameplayState.ActionCursor cursor,GameplayState.MissionPaymentPlan plan,int item){
    return cursor!=null && !cursor.isPending() && cursor.getNext()==1 && plan!=null
        && "UseSelectedItem".equals(plan.getFinishType()) && Map.of(item,1).equals(plan.getCosts());
  }
}
