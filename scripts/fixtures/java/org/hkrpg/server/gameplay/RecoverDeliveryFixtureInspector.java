package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import dev.morphia.Morphia;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.util.Position;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.h2.mvstore.MVStore;

/** Two independent real Player reads from an owned stopped database opened read-only. */
public final class RecoverDeliveryFixtureInspector {
  private static void require(boolean ok,String reason){if(!ok)throw new IllegalStateException(reason);}
  private static String sha(Path p)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
  private static void quest(QuestState q,int id,boolean done){
    require(q!=null && q.getQuestId()==id && q.getProgress()==(done?1:0)
        && (done?q.getPhase()==QuestState.Phase.FINISHED || q.getPhase()==QuestState.Phase.CLOSED:q.getPhase()==QuestState.Phase.DOING),"Wrong stored original quest "+id);
  }
  private static void reward(GameplayState g,long day){
    var archive=g.getDailyMissionPeriods().getArchives().get("3011014:"+day);String key;
    if(archive!=null){require(archive.getRewardKeys().size()==1,"Wrong archived main reward keys");key=archive.getRewardKeys().getFirst();}
    else key=g.getDailyMissionPeriods().getRunDays().containsKey(3011014)?"daily-mission:"+day+":mission:3011014":"mission:3011014";
    var r=g.getRewards().get(key);var p=g.getMissionRewardPlans().get(key);
    require(r!=null && r.isGranted() && p!=null && p.isReady() && p.getRewardIds().equals(List.of(231)) && p.getItems().equals(Map.of(2,5000)),"Wrong actual original main reward");
  }
  private static void run(GameplayState g,int uid,long day,int choice,boolean settled,boolean retired,QuestState main,Map<Integer,QuestState> subs){
    var record=g.getDailyMissionRecoverDelivery().getRecords().get("3011014:"+day);
    require(record!=null && record.valid() && record.getUid()==uid && record.getDay()==day && record.getOption()==301101403+choice
        && record.getItem()==140146+choice && record.getSource().equals(DailyMissionRecoverDeliverySources.SOURCE),"Wrong source-bound recovery receipt");
    quest(main,3011014,settled);if(settled)require(main.getPhase()==QuestState.Phase.CLOSED,"Stored main reward not closed");
    quest(subs.get(301101401),301101401,true);quest(subs.get(301101402),301101402,true);quest(subs.get(record.getOption()),record.getOption(),true);
    quest(subs.get(record.getConsume()),record.getConsume(),settled);
    if(settled){quest(subs.get(record.getFeedback()),record.getFeedback(),true);quest(subs.get(301101412),301101412,true);}
    require(record.getMainAcceptance().equals(main.getObservationKey()) && record.getFirstAcceptance().equals(subs.get(301101401).getObservationKey())
        && record.getMenuAcceptance().equals(subs.get(301101402).getObservationKey()) && record.getChoiceAcceptance().equals(subs.get(record.getOption()).getObservationKey()),"Stored report acceptance identity changed");
    require((record.getRetiredAt()>0)==retired && record.getGrantedAt()>0 && (record.getFeedbackAt()>0)==settled,"Wrong original receipt phase");
    var action=retired?record.getRetiredAction():g.getStoryActions().get(record.getOption());
    require(action!=null && !action.isPending() && action.getNext()==1 && g.getRewards().get(record.getGrantKey()).isGranted(),"Original actual gift not durably confirmed");
    for(int option=301101403;option<=301101405;option++)if(option!=record.getOption()){
      var q=subs.get(option);require(q==null || q.getPhase()==QuestState.Phase.DOING,"Unselected original option fabricated as done");
    }
    if(settled){
      var payment=retired?record.getRetiredPayment():g.getMissionPayments().get(record.getConsume());
      var plan=retired?record.getRetiredPlan():g.getMissionPaymentPlans().get(record.getConsume());
      require(DailyMissionRecoverDeliveryState.paid(payment,plan,record.getItem()),"Missing real single-item debit");reward(g,day);
    }else require(record.getPaymentAt()==0 && !g.getMissionPayments().containsKey(record.getConsume()),"Unpaid gift acquired debit evidence");
  }
  public static void main(String[] args)throws Exception {
    require(args.length==2,"Expected owned directory and observation controls");
    var root=Path.of("").toRealPath();var dir=Path.of(args[0]).toRealPath();var control=Path.of(args[1]).toRealPath();
    require(dir.startsWith(root.resolve("build/smoke")) && dir.getFileName().toString().startsWith("daily-recover-delivery-native-") && control.startsWith(dir),"Unowned stopped fixture");
    var marker=JsonParser.parseString(Files.readString(dir.resolve("resources-fixture/resource-manifest.json"))).getAsJsonObject();
    require(marker.get("testFixture").getAsBoolean() && marker.get("recoverDeliveryFixture").getAsBoolean(),"Missing recovery fixture marker");
    var c=JsonParser.parseString(Files.readString(control)).getAsJsonObject();int uid=c.get("uid").getAsInt(),index=c.get("dayIndex").getAsInt(),firstChoice=c.get("caseIndex").getAsInt();
    String phase=c.get("phase").getAsString();long firstDay=c.get("firstDay").getAsLong(),day=firstDay+index-1;boolean settled=!phase.equals("grant");int choice=index==1?firstChoice:(firstChoice+1)%3;
    require(uid>0 && firstChoice>=0 && firstChoice<3 && Set.of(1,2).contains(index) && Set.of("grant","settled","repeat","restored").contains(phase),"Wrong finite inspection lane");
    var file=dir.resolve("data/database.mv");String before=sha(file),configBefore=sha(dir.resolve("config.json"));var result=new TreeMap<String,Object>();
    var mv=new MVStore.Builder().fileName(file.toString()).readOnly().open();var server=new MongoServer(new H2Backend(mv));var addr=server.bind();
    try(var client=MongoClients.create("mongodb://127.0.0.1:"+addr.getPort()+"/?directConnection=true")){
      var db=client.getDatabase("hkrpg");var players=db.getCollection("players");var filter=Filters.eq("_id",uid);require(players.countDocuments()==1,"Unexpected player count");var document=Objects.requireNonNull(players.find(filter).first());
      var store=Morphia.createDatastore(client,"hkrpg");store.getMapper().mapPackage("org.hkrpg.server.gameplay");store.getMapper().map(Player.class,QuestState.class,Position.class);
      var first=store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first();var second=store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first();
      require(first!=null && second!=null && first!=second && new Gson().toJson(first.getGameplay()).equals(new Gson().toJson(second.getGameplay())) && first.getScoin()==second.getScoin(),"Independent actual Player reads differ");
      var g=first.getGameplay();require(g.getDailyMissionRecoverDelivery().valid() && Objects.equals(DailyMissionRepeats.selectedDay(first,3011014),day),"Invalid stored recovery day");
      if(c.has("introductions") && c.get("introductions").getAsBoolean()){
        var meetings=g.getDailyRecoverIntroductions();require(meetings!=null && meetings.valid(first),"Invalid original NPC introduction history");
        for(int id:new int[]{501028201,501028001,501030700})require(meetings.seen(id),"Original introduction missing after real H2 restart: "+id);
        result.put("npcIntroductions",new Gson().toJsonTree(meetings));
      }
      run(g,uid,day,choice,settled,false,g.getMainMissions().get(3011014),g.getSubMissions());
      if(c.has("performances") && c.get("performances").getAsBoolean()){
        var starts=g.getDailyRecoverPerformances();require(starts.valid(),"Invalid saved performance witnesses");
        require(starts.getStarts().size()==(index==1?(settled?4:2):8),"Wrong actual performance witness count");
        for(var witness:starts.getStarts().values()){
          var archive=g.getDailyMissionPeriods().getArchives().get("3011014:"+witness.day());
          var main=archive==null?g.getMainMissions().get(3011014):archive.getMain();
          var subs=archive==null?g.getSubMissions():archive.getSubs();
          require(witness.uid()==uid && witness.main().equals(main.getObservationKey()) && witness.sub().equals(subs.get(witness.performance()).getObservationKey()),"Saved E relay acceptance mismatch");
        }
        result.put("performanceStarts",new Gson().toJsonTree(starts));
      }
      if(index==2){
        var archive=g.getDailyMissionPeriods().getArchives().get("3011014:"+firstDay);require(archive!=null && archive.getMainId()==3011014 && archive.getDay()==firstDay,"First settled source run missing archive");
        run(g,uid,firstDay,firstChoice,true,true,archive.getMain(),archive.getSubs());
        var old=g.getDailyMissionRecoverDelivery().getRecords().get("3011014:"+firstDay);var current=g.getDailyMissionRecoverDelivery().getRecords().get("3011014:"+day);
        require(!old.getEvent().equals(current.getEvent()) && !old.getMainAcceptance().equals(current.getMainAcceptance()) && !old.getFirstAcceptance().equals(current.getFirstAcceptance())
            && !old.getChoiceAcceptance().equals(current.getChoiceAcceptance()) && !old.getGrantKey().equals(current.getGrantKey()),"New day borrowed original report/grant identity");
        require(!g.getMissionPayments().containsKey(old.getConsume()),"Retired debit still occupies a live slot");
      }
      int credit=phase.equals("grant")?0:index*5000;require(first.getScoin()==credit && g.getDailyPoints()==(settled?200:0) && g.getDailyDay()==day,"Wrong saved credit/active points/day");
      var counts=new TreeMap<Integer,Integer>();for(int item=140146;item<=140148;item++){
        var rows=db.getCollection("items").find(Filters.and(Filters.eq("ownerUid",uid),Filters.eq("itemId",item))).into(new ArrayList<>());require(rows.size()<=1,"Duplicate gift inventory document");
        int count=rows.isEmpty()?0:rows.getFirst().getInteger("count");require(count==(phase.equals("grant") && item==140146+firstChoice?1:0),"Wrong stored actual gift count "+item);counts.put(item,count);
      }
      require(document.equals(players.find(filter).first()),"Read-only inspection changed Player");
      result.put("receipts",new Gson().toJsonTree(g.getDailyMissionRecoverDelivery()));result.put("inventoryCounts",counts);result.put("credit",credit);result.put("activePoints",g.getDailyPoints());result.put("independentMorphiaReadCount",2);
    }finally{server.shutdownNow();if(!mv.isClosed())mv.close();}
    require(before.equals(sha(file)) && configBefore.equals(sha(dir.resolve("config.json"))),"Read-only database/config bytes changed");
    result.put("passed",true);result.put("uid",uid);result.put("caseIndex",firstChoice);result.put("dayIndex",index);result.put("phase",phase);result.put("databaseSHA256",before);result.put("databaseFileBytesUnchanged",true);result.put("mvStoreReadOnly",true);
    System.out.println(new Gson().toJson(result));
  }
}
