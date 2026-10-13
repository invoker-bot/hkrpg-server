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
import java.time.Instant;
import java.util.*;
import org.h2.mvstore.MVStore;

/** Independent real Player reads from an owned stopped MVStore, opened read-only. */
public final class SelectedItemFixtureInspector{
  private static void require(boolean value,String why){if(!value)throw new IllegalStateException(why);}
  private static String sha(Path file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
  private static Map<String,Object> state(QuestState s,int id,QuestState.Phase phase,long day){
    boolean done=phase!=QuestState.Phase.DOING;
    require(s!=null && s.getQuestId()==id && s.getPhase()==phase && s.getProgress()==(done?1:0)
        && (done?s.getFinishTime()>0 && DailyManager.day(Instant.ofEpochSecond(s.getFinishTime()))==day:s.getFinishTime()==0),"Stored source Quest differs: "+id);
    var result=new TreeMap<String,Object>();result.put("id",id);result.put("phase",phase.name());result.put("progress",s.getProgress());result.put("finishTime",s.getFinishTime());
    if(s.getObservationKey()!=null)result.put("acceptance",s.getObservationKey());return result;
  }
  private static void reward(GameplayState g,int mid,long day,boolean earned){
    var periods=g.getDailyMissionPeriods();var archive=periods.getArchives().get(mid+":"+day);String key;
    if(archive!=null){
      require(archive.getRewardKeys().size()==1,"Wrong original archived reward key count");key=archive.getRewardKeys().getFirst();
      require(key.equals("mission:"+mid) || key.equals("daily-mission:"+day+":mission:"+mid),"Wrong original archived reward namespace");
    }else{
      Long current=periods.getRunDays().get(mid);require(current==null || current==day,"Reward belongs to a different actual run day");
      key=current==null?"mission:"+mid:"daily-mission:"+day+":mission:"+mid;
    }
    var receipt=g.getRewards().get(key);var plan=g.getMissionRewardPlans().get(key);
    if(!earned){require(receipt==null && plan==null,"Unreported item obtained a source main reward");return;}
    require(receipt!=null && receipt.isGranted() && plan!=null && plan.isReady() && plan.getRewardIds().equals(List.of(231))
        && plan.getChoices().isEmpty() && plan.getItems().equals(Map.of(2,5000)),"Original stored main reward differs");
  }
  private static Map<String,Object> run(GameplayState g,int uid,int mid,long day,int item,boolean paid,boolean settled,
      QuestState main,Map<Integer,QuestState> subs,boolean retired){
    var result=new TreeMap<String,Object>();int selected=mid*100+1,tail=mid*100+2;
    result.put("main",state(main,mid,settled?QuestState.Phase.CLOSED:QuestState.Phase.DOING,day));
    result.put("select",state(subs.get(selected),selected,paid?QuestState.Phase.FINISHED:QuestState.Phase.DOING,day));
    result.put("talk",state(subs.get(tail),tail,settled?QuestState.Phase.FINISHED:QuestState.Phase.DOING,day));
    var receipt=g.getDailyMissionSelectedItems().getRecords().get(mid+":"+day);
    if(!paid){require(receipt==null && !g.getMissionPayments().containsKey(selected) && !g.getMissionPaymentPlans().containsKey(selected),"Unpaid run acquired a payment or receipt");return result;}
    require(receipt!=null && receipt.valid() && receipt.getUid()==uid && receipt.getMain()==mid && receipt.getDay()==day && receipt.getItem()==item
        && receipt.getSource().equals(DailyMissionSelectedItemSources.DEFINITIONS.get(mid).source())
        && receipt.getMainAcceptance().equals(main.getObservationKey()) && receipt.getSelectAcceptance().equals(subs.get(selected).getObservationKey())
        && receipt.getTalkAcceptance().equals(subs.get(tail).getObservationKey()),"Stored payment does not bind this UID/source/day/three actual Quest identities");
    require(new HashSet<>(List.of(receipt.getMainAcceptance(),receipt.getSelectAcceptance(),receipt.getTalkAcceptance())).size()==3,"Three source acceptances share one identity");
    require((receipt.getReportedAt()>0)==settled && (!settled || receipt.getReportedAt()==subs.get(tail).getFinishTime())
        && (receipt.getRetiredAt()>0)==retired,"Stored report or retirement timestamp differs");
    var cursor=retired?receipt.getRetiredPayment():g.getMissionPayments().get(selected);
    var plan=retired?receipt.getRetiredPlan():g.getMissionPaymentPlans().get(selected);
    require(DailyMissionSelectedItemState.paid(cursor,plan,item),"Stored actual core cursor/plan do not prove the one selected debit");
    result.put("receipt",new Gson().toJsonTree(receipt));result.put("payment",new Gson().toJsonTree(cursor));result.put("plan",new Gson().toJsonTree(plan));return result;
  }
  public static void main(String[] args)throws Exception{
    if(args.length==2 && args[0].equals("--verify-stored-reward")){
      var file=Path.of(args[1]).toRealPath();require(file.startsWith(Path.of("").toRealPath().resolve("build")),"Unowned stored shape guard");
      var g=new Gson().fromJson(Files.readString(file),GameplayState.class);reward(g,3011605,20738,true);System.out.println("verified");return;
    }
    var root=Path.of("").toRealPath();var directory=Path.of(args[0]).toRealPath();var input=Path.of(args[1]).toRealPath();
    require(directory.startsWith(root.resolve("build/smoke")) && directory.getFileName().toString().startsWith("daily-selected-item-native-") && input.startsWith(directory),"Unowned stopped selected-item fixture");
    var c=JsonParser.parseString(Files.readString(input)).getAsJsonObject();int uid=c.get("uid").getAsInt(),mid=c.get("mainMission").getAsInt(),index=c.get("dayIndex").getAsInt();
    int firstItem=c.get("firstItem").getAsInt(),secondItem=c.get("secondItem").getAsInt();long firstDay=c.get("firstDay").getAsLong(),day=firstDay+index-1;
    String phase=c.get("phase").getAsString();
    require(uid>0 && Set.of(3011605,3011608).contains(mid) && Set.of(1,2).contains(index)
        && Set.of("open","paid","settled","repeat","restored").contains(phase)
        && (mid==3011605?firstItem==401004 && secondItem==401012:firstItem==401007 && secondItem==401009),"Invalid original stopped inspection controls");
    boolean paid=!phase.equals("open"),settled=Set.of("settled","repeat","restored").contains(phase);
    var marker=JsonParser.parseString(Files.readString(directory.resolve("resources-fixture/resource-manifest.json"))).getAsJsonObject();
    require(marker.get("testFixture").getAsBoolean() && marker.get("selectedItemFixture").getAsBoolean() && marker.get("originalMission").getAsInt()==mid,"Wrong original fixture marker");
    var file=directory.resolve("data/database.mv");var config=directory.resolve("config.json");String before=sha(file),configBefore=sha(config);var result=new TreeMap<String,Object>();
    var mv=new MVStore.Builder().fileName(file.toString()).readOnly().open();var server=new MongoServer(new H2Backend(mv));var address=server.bind();
    try(var client=MongoClients.create("mongodb://127.0.0.1:"+address.getPort()+"/?directConnection=true")){
      var db=client.getDatabase("hkrpg");var players=db.getCollection("players");var filter=Filters.eq("_id",uid);
      require(players.countDocuments()==1 && players.countDocuments(filter)==1,"Not exactly one owned Player");var original=Objects.requireNonNull(players.find(filter).first());
      var store=Morphia.createDatastore(client,"hkrpg");store.getMapper().mapPackage("org.hkrpg.server.gameplay");store.getMapper().map(Player.class,QuestState.class,Position.class);
      var first=store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first();var second=store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first();
      require(first!=null && second!=null && first!=second && new Gson().toJson(first.getGameplay()).equals(new Gson().toJson(second.getGameplay())) && first.getScoin()==second.getScoin(),"Independent actual Player reads differ");
      var g=first.getGameplay();require(g.getDailyMissionSelectedItems().valid(),"Actual Morphia selected-item domain invalid");
      require(Objects.equals(DailyMissionRepeats.selectedDay(first,mid),day),"Actual current assignment day differs");
      result.put("currentRun",run(g,uid,mid,day,index==1?firstItem:secondItem,paid,settled,g.getMainMissions().get(mid),g.getSubMissions(),false));
      var periods=g.getDailyMissionPeriods();require(periods.getExpired().isEmpty(),"Settled native lane unexpectedly created an unpaid expiry");
      if(index==2){
        var archive=periods.getArchives().get(mid+":"+firstDay);require(archive!=null && archive.getDay()==firstDay && archive.getMainId()==mid,"First settled source day was not archived");
        result.put("archivedRun",run(g,uid,mid,firstDay,firstItem,true,true,archive.getMain(),archive.getSubs(),true));
        var old=g.getDailyMissionSelectedItems().getRecords().get(mid+":"+firstDay);var current=g.getDailyMissionSelectedItems().getRecords().get(mid+":"+day);
        require(!old.getEvent().equals(current.getEvent()) && !old.getMainAcceptance().equals(current.getMainAcceptance())
            && !old.getSelectAcceptance().equals(current.getSelectAcceptance()) && !old.getTalkAcceptance().equals(current.getTalkAcceptance()),"New source day borrowed old payment acceptance");
        reward(g,mid,firstDay,true);
      }else require(periods.getArchives().isEmpty(),"First source day already archived");
      reward(g,mid,day,settled);int credit=(index-1)*5000+(settled?5000:0);
      require(first.getScoin()==credit && g.getDailyPoints()==(settled?200:0) && g.getDailyDay()==day,"Stored actual credit/active points/day differ");
      result.put("dailyQuest",state(g.getDailyQuests().get(2100003),2100003,settled?QuestState.Phase.CLOSED:QuestState.Phase.DOING,day));
      var inventory=db.getCollection("items");var itemViews=new TreeMap<Integer,Integer>();
      for(int item:List.of(firstItem,secondItem)){
        var documents=inventory.find(Filters.and(Filters.eq("ownerUid",uid),Filters.eq("itemId",item))).into(new ArrayList<>());
        require(documents.size()<=1,"Duplicated actual food item document");int count=documents.isEmpty()?0:documents.getFirst().getInteger("count");
        int expected=item==firstItem && phase.equals("open")?1:0;require(count==expected,"Actual stored food count differs: "+item+" count="+count);itemViews.put(item,count);
      }
      require(original.equals(players.find(filter).first()) && players.countDocuments()==1,"Read-only inspector mutated Player documents");
      result.put("inventoryCounts",itemViews);result.put("credit",credit);result.put("activePoints",g.getDailyPoints());result.put("runDays",periods.getRunDays());
      result.put("selection",new Gson().toJsonTree(g.getDailyMissionSelections()));result.put("selectedItemReceipts",new Gson().toJsonTree(g.getDailyMissionSelectedItems()));
      result.put("independentMorphiaReadCount",2);result.put("changedPlayerDocumentCount",0);
    }finally{server.shutdownNow();if(!mv.isClosed())mv.close();}
    require(before.equals(sha(file)) && configBefore.equals(sha(config)),"Read-only MVStore/config bytes changed");
    result.put("uid",uid);result.put("mainMission",mid);result.put("phase",phase);result.put("day",day);result.put("databaseSHA256",before);
    result.put("databaseFileBytesUnchanged",true);result.put("mvStoreReadOnly",true);result.put("passed",true);System.out.println(new Gson().toJson(result));
  }
}
