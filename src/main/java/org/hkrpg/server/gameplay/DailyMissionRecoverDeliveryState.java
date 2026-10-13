package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.time.Instant;
import java.util.*;

/** Source reports, actual grants and debits bind to one original daily acceptance. */
@Entity(useDiscriminator=false)
public final class DailyMissionRecoverDeliveryState {
  private int version=1;
  private Map<String,Receipt> records=new HashMap<>();
  public DailyMissionRecoverDeliveryState(){}
  DailyMissionRecoverDeliveryState(DailyMissionRecoverDeliveryState previous){records.putAll(previous.records);}
  public int getVersion(){return version;}
  public Map<String,Receipt> getRecords(){return records;}
  boolean valid(){
    if(version!=1 || records==null || records.size()>4096)return false;
    for(var e:records.entrySet())if(e.getValue()==null || !e.getValue().valid() || !e.getKey().equals(key(e.getValue().day)))return false;
    return true;
  }
  static String key(long day){return DailyMissionRecoverDeliverySources.MAIN+":"+day;}
  static boolean uuid(String value){
    if(value==null)return false;try{return UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException invalid){return false;}
  }
  static boolean time(long value,long day){try{return value>0 && DailyManager.day(Instant.ofEpochSecond(value))==day;}catch(RuntimeException invalid){return false;}}
  static boolean paid(GameplayState.ActionCursor cursor,GameplayState.MissionPaymentPlan plan,int item){
    return cursor!=null && !cursor.isPending() && cursor.getNext()==1 && plan!=null
        && "ConsumeMissionItem".equals(plan.getFinishType()) && Map.of(item,1).equals(plan.getCosts());
  }
  @Entity(useDiscriminator=false)
  public static final class Receipt {
    private int uid,main,option,item,beforeCount;
    private long day,firstAt,menuAt,choiceAt,grantedAt,paymentAt,feedbackAt,retiredAt;
    private String source,event,mainAcceptance,firstAcceptance,menuAcceptance,choiceAcceptance,consumeAcceptance,feedbackAcceptance,grantItemDocument;
    private GameplayState.ActionCursor retiredAction,retiredPayment;
    private GameplayState.MissionPaymentPlan retiredPlan;
    protected Receipt(){}
    Receipt(int uid,long day,long firstAt,String mainAcceptance,String firstAcceptance){
      this.uid=uid;main=DailyMissionRecoverDeliverySources.MAIN;this.day=day;this.firstAt=firstAt;
      source=DailyMissionRecoverDeliverySources.SOURCE;event=UUID.randomUUID().toString();
      this.mainAcceptance=mainAcceptance;this.firstAcceptance=firstAcceptance;
    }
    Receipt(Receipt previous){
      uid=previous.uid;main=previous.main;option=previous.option;item=previous.item;beforeCount=previous.beforeCount;
      day=previous.day;firstAt=previous.firstAt;menuAt=previous.menuAt;choiceAt=previous.choiceAt;grantedAt=previous.grantedAt;
      paymentAt=previous.paymentAt;feedbackAt=previous.feedbackAt;retiredAt=previous.retiredAt;
      source=previous.source;event=previous.event;mainAcceptance=previous.mainAcceptance;firstAcceptance=previous.firstAcceptance;
      menuAcceptance=previous.menuAcceptance;choiceAcceptance=previous.choiceAcceptance;consumeAcceptance=previous.consumeAcceptance;
      feedbackAcceptance=previous.feedbackAcceptance;grantItemDocument=previous.grantItemDocument;
      retiredAction=previous.retiredAction;retiredPayment=previous.retiredPayment;retiredPlan=previous.retiredPlan;
    }
    Receipt menu(long at,String acceptance){var next=new Receipt(this);next.menuAt=at;next.menuAcceptance=acceptance;return next;}
    Receipt choice(int id,long at,String menuKey,String optionKey,int before){
      var next=new Receipt(this);next.option=id;next.item=140146+(id-301101403);next.choiceAt=at;
      next.menuAcceptance=menuKey;next.choiceAcceptance=optionKey;next.beforeCount=before;return next;
    }
    Receipt granted(long at,String document){var next=new Receipt(this);next.grantedAt=at;next.grantItemDocument=document;return next;}
    Receipt payment(long at,String acceptance){var next=new Receipt(this);next.paymentAt=at;next.consumeAcceptance=acceptance;return next;}
    Receipt feedback(long at,String acceptance){var next=new Receipt(this);next.feedbackAt=at;next.feedbackAcceptance=acceptance;return next;}
    Receipt retired(long at,GameplayState.ActionCursor action,GameplayState.ActionCursor payment,GameplayState.MissionPaymentPlan plan){
      var next=new Receipt(this);next.retiredAt=at;next.retiredAction=action;next.retiredPayment=payment;next.retiredPlan=plan;return next;
    }
    public int getUid(){return uid;} public int getMain(){return main;} public int getOption(){return option;}
    public int getItem(){return item;} public int getBeforeCount(){return beforeCount;}
    public int getConsume(){return option==0?0:option+3;} public int getFeedback(){return option==0?0:option+6;}
    public long getDay(){return day;} public long getFirstAt(){return firstAt;} public long getMenuAt(){return menuAt;}
    public long getChoiceAt(){return choiceAt;} public long getGrantedAt(){return grantedAt;} public long getPaymentAt(){return paymentAt;}
    public long getFeedbackAt(){return feedbackAt;} public long getRetiredAt(){return retiredAt;}
    public String getSource(){return source;} public String getEvent(){return event;} public String getMainAcceptance(){return mainAcceptance;}
    public String getFirstAcceptance(){return firstAcceptance;} public String getMenuAcceptance(){return menuAcceptance;}
    public String getChoiceAcceptance(){return choiceAcceptance;} public String getConsumeAcceptance(){return consumeAcceptance;}
    public String getFeedbackAcceptance(){return feedbackAcceptance;} public String getGrantItemDocument(){return grantItemDocument;}
    public String getGrantKey(){return "daily-mission:"+day+":recover:"+option+":"+event+":"+choiceAcceptance;}
    public GameplayState.ActionCursor getRetiredAction(){return retiredAction;}
    public GameplayState.ActionCursor getRetiredPayment(){return retiredPayment;}
    public GameplayState.MissionPaymentPlan getRetiredPlan(){return retiredPlan;}
    boolean valid(){
      if(uid<=0 || main!=DailyMissionRecoverDeliverySources.MAIN || !DailyMissionRecoverDeliverySources.SOURCE.equals(source)
          || !uuid(event) || !uuid(mainAcceptance) || !uuid(firstAcceptance) || !time(firstAt,day)
          || menuAt<0 || choiceAt<0 || grantedAt<0 || paymentAt<0 || feedbackAt<0 || retiredAt<0
          || menuAt>0 && (menuAt<firstAt || !time(menuAt,day) || !uuid(menuAcceptance)))return false;
      if(choiceAt==0){
        if(option!=0 || item!=0 || beforeCount!=0 || choiceAcceptance!=null || grantedAt!=0 || grantItemDocument!=null
            || paymentAt!=0 || consumeAcceptance!=null || feedbackAt!=0 || feedbackAcceptance!=null || retiredAction!=null
            || retiredPayment!=null || retiredPlan!=null || menuAt==0 && menuAcceptance!=null)return false;
      }else{
        if(option<301101403 || option>301101405 || item!=140146+option-301101403 || choiceAt<firstAt || !time(choiceAt,day)
            || !uuid(menuAcceptance) || !uuid(choiceAcceptance) || beforeCount<0 || beforeCount>=99999)return false;
        if(grantedAt==0){if(grantItemDocument!=null || paymentAt!=0 || consumeAcceptance!=null || feedbackAt!=0 || feedbackAcceptance!=null || retiredAt!=0)return false;}
        else if(grantedAt<choiceAt || !time(grantedAt,day) || grantItemDocument==null || !org.bson.types.ObjectId.isValid(grantItemDocument))return false;
        if(paymentAt==0){if(consumeAcceptance!=null || feedbackAt!=0 || feedbackAcceptance!=null || retiredPayment!=null || retiredPlan!=null)return false;}
        else if(paymentAt<grantedAt || !time(paymentAt,day) || !uuid(consumeAcceptance))return false;
        if(feedbackAt==0){if(feedbackAcceptance!=null)return false;}
        else if(feedbackAt<paymentAt || paymentAt==0 || !time(feedbackAt,day) || !uuid(feedbackAcceptance))return false;
      }
      if(retiredAt==0)return retiredAction==null && retiredPayment==null && retiredPlan==null;
      try{if(retiredAt<firstAt || DailyManager.day(Instant.ofEpochSecond(retiredAt))<=day)return false;}catch(RuntimeException invalid){return false;}
      if(choiceAt>0 && (grantedAt==0 || retiredAction==null || retiredAction.isPending() || retiredAction.getNext()!=1))return false;
      return paymentAt==0 || paid(retiredPayment,retiredPlan,item);
    }
  }
}
