package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Finite original SelectMissionItem/paid Talk boundaries. This does not execute client Acts. */
final class DailyMissionSelectedItemRules {
  private static final Gson GSON=ResourceLoader.getGson();
  private record Cached(String bytes,JsonElement json){}
  private static Path cachedRoot;
  private static final Map<String,Cached> CACHE=new HashMap<>();
  static boolean handles(int main){return DailyMissionCatalog.configured() && DailyMissionSelectedItemSources.DEFINITIONS.containsKey(main);}
  static boolean select(int id){return id==301160501 || id==301160801;}
  static boolean talk(int id){return id==301160502 || id==301160802;}
  private static DailyMissionSelectedItemSources.Definition definition(int id){return DailyMissionSelectedItemSources.DEFINITIONS.get(id);}
  private static synchronized JsonElement read(Path root,String relative)throws Exception{
    var path=root.resolve(relative).normalize();
    if(!path.startsWith(root) || !Files.isRegularFile(path) || Files.size(path)>8*1024*1024 || !path.toRealPath().startsWith(root.toRealPath()))return null;
    if(!root.equals(cachedRoot)){cachedRoot=root;CACHE.clear();}
    byte[] raw=Files.readAllBytes(path);String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
    var old=CACHE.get(relative);if(old!=null && old.bytes.equals(digest))return old.json;
    var json=JsonParser.parseString(new String(raw,java.nio.charset.StandardCharsets.UTF_8));CACHE.put(relative,new Cached(digest,json));return json;
  }
  static boolean original(MissionInfo graph){return graph!=null && GameData.getMissionInfos().get(graph.getMainMissionID())==graph && source(graph.getMainMissionID());}
  static boolean source(int id){
    if(!handles(id))return false;
    var d=definition(id);
    try{
      var main=GameData.getMainMissionExcelMap().get(id);var graph=GameData.getMissionInfos().get(id);
      if(main==null || graph==null || !d.mainPin().equals(TimelineActivityRules.fingerprint(GSON.toJsonTree(main))))return false;
      var normalized=GSON.toJsonTree(graph).getAsJsonObject();
      for(var sub:normalized.getAsJsonArray("SubMissionList"))sub.getAsJsonObject().remove("task");
      if(!d.graphPin().equals(TimelineActivityRules.fingerprint(normalized)))return false;
      for(var sub:graph.getSubMissionList())if(GameplayDefinitions.subs.get(sub.getId())!=sub || sub.getStoryTrialTask()!=null || sub.getTask()==null
          || sub.getTask().getSourceDefinition()==null || sub.getTask().getSourceDefinition().length()>262144
          || !DailyMissionSelectedItemSources.FILES.get(sub.getMissionJsonPath()).equals(TimelineActivityRules.fingerprint(JsonParser.parseString(sub.getTask().getSourceDefinition()))))return false;
      var floor=GameData.getFloorInfo(d.plane(),d.floor());
      if(floor==null || !floor.isLoaded() || floor.getFloorID()!=d.floor() || floor.getMainDimension()==null
          || !floor.isGroupInDimension(d.group(),0) || Collections.frequency(floor.getMainDimension().getGroupIndexList(),d.index())!=1
          || floor.getSimpleGroupList()==null || floor.getSimpleGroupList().size()<=d.index())return false;
      var entry=floor.getSimpleGroupList().get(d.index());var group=floor.getGroups(0).get(d.group());
      if(entry==null || entry.isIsDelete() || entry.getID()!=d.group() || !d.groupPath().equals(entry.getGroupPath()) || group==null
          || group.getId()!=d.group() || floor.getGroupInfoByIndex(d.index())!=group
          || !d.groupPin().equals(TimelineActivityRules.fingerprint(GSON.toJsonTree(group))))return false;
      var root=Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      for(String path:d.files())if(!DailyMissionSelectedItemSources.FILES.get(path).equals(TimelineActivityRules.fingerprint(read(root,path))))return false;
      var rawFloor=read(root,"Config/LevelOutput/RuntimeFloor/P"+d.plane()+"_F"+d.floor()+".json");
      if(!TimelineActivityRules.fingerprint(GSON.toJsonTree(floor)).equals(TimelineActivityRules.fingerprint(GSON.toJsonTree(GSON.fromJson(rawFloor,FloorInfo.class)))))return false;
      for(String key:d.rows()){
        String[] split=key.split("#",2);String[] parts=split[1].split(":",2);int rowId=Integer.parseInt(parts[1]);
        var table=read(root,split[0]);if(table==null || !table.isJsonArray() || table.getAsJsonArray().size()>32768)return false;
        JsonObject found=null;
        for(var candidate:table.getAsJsonArray()){
          var row=candidate.getAsJsonObject();var number=row.get(parts[0]);if(number==null || !number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber())return false;
          if(number.getAsBigDecimal().intValueExact()!=rowId)continue;
          if(found!=null || !DailyMissionSelectedItemSources.ROWS.get(key).equals(TimelineActivityRules.fingerprint(row)))return false;found=row;
        }
        if(found==null || !loaded(split[0],rowId,found))return false;
      }
      return true;
    }catch(Exception invalid){return false;}
  }
  private static boolean loaded(String table,int id,JsonObject row){
    Object expected=null,actual=null;
    switch(table){
      case "ExcelOutput/MainMission.json" -> {expected=GSON.fromJson(row,MainMissionExcel.class);actual=GameData.getMainMissionExcelMap().get(id);}
      case "ExcelOutput/ItemConfig.json" -> {expected=GSON.fromJson(row,ItemExcel.class);actual=GameData.getItemExcelMap().get(id);}
      case "ExcelOutput/NPCData.json" -> {expected=GSON.fromJson(row,NpcExcel.class);actual=GameData.getNpcExcelMap().get(id);}
      case "ExcelOutput/MazePlane.json" -> {expected=GSON.fromJson(row,MazePlaneExcel.class);actual=GameData.getMazePlaneExcelMap().get(id);}
      case "ExcelOutput/MapEntrance.json" -> {expected=GSON.fromJson(row,MapEntranceExcel.class);actual=GameData.getMapEntranceExcelMap().get(id);}
      case "ExcelOutput/QuestData.json" -> {expected=GSON.fromJson(row,QuestExcel.class);actual=GameData.getQuestExcelMap().get(id);}
      case "ExcelOutput/FinishWay.json" -> {expected=GSON.fromJson(row,FinishWayExcel.class);actual=GameData.getFinishWayExcelMap().get(id);}
      case "ExcelOutput/RewardData.json" -> {var reward=GSON.fromJson(row,RewardExcel.class);reward.onLoad();expected=reward;actual=GameData.getRewardExcelMap().get(id);}
      case "ExcelOutput/DailyMissionData.json" -> {expected=GSON.fromJson(row,DailyMissionCatalog.Mission.class);actual=DailyMissionCatalog.mission(id);}
      case "ExcelOutput/DailyMissionCount.json" -> {expected=GSON.fromJson(row,DailyMissionCatalog.Count.class);actual=DailyMissionCatalog.count(row.get("DailyMissionType").getAsInt());}
      case "ExcelOutput/DailyActiveQuestPool.json" -> {
        var pool=GSON.fromJson(row,GameplayDefinitions.DailyPool.class);
        return GameplayDefinitions.dailyPool.stream().filter(value->value.QuestID()==id).toList().equals(List.of(pool));
      }
      case "ExcelOutput/PerformanceE.json" -> {return true;} // Original Act lookup is source proof, not execution.
      default -> {return false;}
    }
    return actual!=null && TimelineActivityRules.fingerprint(GSON.toJsonTree(expected)).equals(TimelineActivityRules.fingerprint(GSON.toJsonTree(actual)));
  }
  static boolean loaded(Player player,int id){
    var d=definition(id);if(d==null || !source(id))return false;
    var scene=player.getScene();var floor=GameData.getFloorInfo(d.plane(),d.floor());
    if(scene==null || scene.getPlayer()!=player || player.getUid()<=0 || player.getPlaneId()!=d.plane() || player.getFloorId()!=d.floor()
        || scene.getPlaneId()!=d.plane() || scene.getFloorId()!=d.floor() || scene.getFloorInfo()!=floor || scene.getDimensionId()!=0
        || scene.getStoryLineId()!=0 || player.getGameplay().getStoryLines().getCurrentId()!=0 || !scene.getLoadedGroups().contains(d.group()))return false;
    var group=floor.getGroups(0).get(d.group());if(scene.getResourceGroups().get(d.group())!=group)return false;
    var npcs=scene.getEntitiesByGroup(EntityNpc.class,d.group());var expected=group.getNPCList();
    if(npcs.size()!=1 || expected.size()!=1)return false;
    var npc=npcs.getFirst();var info=expected.getFirst();
    return npc.getScene()==scene && npc.getNpcId()==d.npc() && npc.getNpcId()==info.getNPCID() && npc.getInstId()==400001
        && npc.getInstId()==info.getID() && npc.getPos().equals(info.getPos()) && npc.getRot().equals(info.getRot());
  }
  private static String key(int id,long day){return id+":"+day;}
  static boolean current(Player player,int id,Instant now){
    if(!source(id) || !DailyCommissionAcceptance.valid(player.getGameplay().getDailyMissionSelections()) || !historyValid(player,now))return false;
    Long day=DailyMissionRepeats.selectedDay(player,id);return day!=null && day==DailyManager.day(now);
  }
  static Retcode submission(Player player,MissionInfo.SubMissionInfo sub,Instant now){
    if(!select(sub.getId()) || !DailyMissionCatalog.configured())return Retcode.SUCC;
    int originalMain=sub.getId()/100;
    if(sub.getMainMissionID()!=originalMain || !handles(originalMain))return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
    if(!current(player,sub.getMainMissionID(),now))return Retcode.MISSION_NOT_DOING;
    if(!loaded(player,sub.getMainMissionID()))return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
    var state=player.getGameplay();var receipt=state.getDailyMissionSelectedItems().getRecords().get(key(sub.getMainMissionID(),DailyManager.day(now)));
    var d=definition(sub.getMainMissionID());
    if(!MissionStateRecords.doing(state.getMainMissions().get(d.main()),d.main(),1)
        || !MissionStateRecords.doing(state.getSubMissions().get(d.select()),d.select(),1)
        || !MissionStateRecords.doing(state.getSubMissions().get(d.talk()),d.talk(),1))return Retcode.MISSION_NOT_DOING;
    for(var accepted:List.of(state.getMainMissions().get(d.main()),state.getSubMissions().get(d.select()),state.getSubMissions().get(d.talk())))
      if(accepted.getObservationKey()!=null && !DailyMissionSelectedItemState.uuid(accepted.getObservationKey()))return Retcode.PLAYER_DATA_ERROR;
    if((state.getMissionPayments().containsKey(sub.getId()) || state.getMissionPaymentPlans().containsKey(sub.getId())) && receipt==null)return Retcode.PLAYER_DATA_ERROR;
    return Retcode.SUCC;
  }
  static void bindPayment(Player player,MissionInfo.SubMissionInfo sub,Map<Integer,Integer> costs,Instant now){
    if(!select(sub.getId()) || !DailyMissionCatalog.configured())return;
    if(sub.getMainMissionID()!=sub.getId()/100 || !handles(sub.getId()/100))throw new IllegalStateException("Original selected-item target owner changed before reservation");
    int mainId=sub.getMainMissionID();var d=definition(mainId);var gameplay=player.getGameplay();
    var main=gameplay.getMainMissions().get(mainId);var target=gameplay.getSubMissions().get(d.select());var tail=gameplay.getSubMissions().get(d.talk());
    var records=gameplay.getDailyMissionSelectedItems().getRecords();long day=DailyManager.day(now);
    if(!source(mainId) || !loaded(player,mainId) || !MissionStateRecords.doing(main,mainId,1) || !MissionStateRecords.doing(target,d.select(),1)
        || !MissionStateRecords.doing(tail,d.talk(),1) || records.containsKey(key(mainId,day)) || costs.size()!=1)
      throw new IllegalStateException("Original selected-item acceptance changed before reservation");
    var choice=costs.entrySet().iterator().next();
    if(!DailyMissionSelectedItemState.choices(mainId).contains(choice.getKey()) || choice.getValue()!=1)throw new IllegalStateException("Unproved original item choice");
    var receipt=new DailyMissionSelectedItemState.Receipt(player.getUid(),mainId,day,now.getEpochSecond(),choice.getKey(),
        main.captureObservationKey(),target.captureObservationKey(),tail.captureObservationKey());
    if(!receipt.valid())throw new IllegalStateException("Invalid original selected-item receipt");records.put(key(mainId,day),receipt);
  }
  static boolean allowsTalk(Player player,int id){
    if(!talk(id))return true;int mid=id/100;var gameplay=player.getGameplay();var d=definition(mid);
    var now=player.getMissionManager().currentInstant();
    if(!handles(mid) || !current(player,mid,now) || !loaded(player,mid) || !MissionStateRecords.mainDoing(player,mid)
        || !MissionStateRecords.doing(gameplay.getSubMissions().get(id),id,1) || !MissionStateRecords.subCompleted(player,d.select()))return false;
    var receipt=gameplay.getDailyMissionSelectedItems().getRecords().get(key(mid,DailyManager.day(now)));
    return receipt!=null && receipt.getReportedAt()==0 && receipt.getRetiredAt()==0
        && DailyMissionSelectedItemState.paid(gameplay.getMissionPayments().get(d.select()),gameplay.getMissionPaymentPlans().get(d.select()),receipt.getItem());
  }
  static Retcode finishTalk(Player player,MissionInfo.SubMissionInfo sub,QuestState accepted,Map<Integer,Integer> values,Instant now){
    if(values==null || !values.isEmpty())return Retcode.MISSION_CUSTOM_VALUE_NOT_VALID;
    if(!allowsTalk(player,sub.getId()) || accepted!=player.getGameplay().getSubMissions().get(sub.getId()))return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
    var g=player.getGameplay();var previous=g.getDailyMissionSelectedItems();var next=new DailyMissionSelectedItemState(previous);
    String key=key(sub.getMainMissionID(),DailyManager.day(now));var original=next.getRecords().get(key);
    var completed=GSON.fromJson(GSON.toJson(accepted),QuestState.class);
    if(!completed.advance(1,1,now.getEpochSecond()))return Retcode.MISSION_NOT_DOING;
    next.getRecords().put(key,original.reported(now.getEpochSecond()));g.getSubMissions().put(sub.getId(),completed);g.dailyMissionSelectedItems(next);
    try{player.save();}catch(RuntimeException failure){g.getSubMissions().put(sub.getId(),accepted);g.dailyMissionSelectedItems(previous);throw failure;}
    return Retcode.SUCC;
  }
  static boolean historyValid(Player player,Instant now){
    try{
      var g=player.getGameplay();var state=g.getDailyMissionSelectedItems();if(now==null || !state.valid())return false;
      for(var receipt:state.getRecords().values()){
        if(receipt.getUid()!=player.getUid() || receipt.getAt()>now.getEpochSecond() || receipt.getReportedAt()>now.getEpochSecond() || receipt.getRetiredAt()>now.getEpochSecond())return false;
        QuestState main,target,tail;var periods=g.getDailyMissionPeriods();String key=key(receipt.getMain(),receipt.getDay());
        var cursor=g.getMissionPayments().get(receipt.getSelect());var plan=g.getMissionPaymentPlans().get(receipt.getSelect());
        if(receipt.getRetiredAt()>0){
          var archive=periods.getArchives().get(key);var expired=periods.getExpired().get(key);
          if((archive==null)==(expired==null))return false;
          main=archive!=null?archive.getMain():expired.getMain();var subs=archive!=null?archive.getSubs():expired.getSubs();
          target=subs.get(receipt.getSelect());tail=subs.get(receipt.getTalk());cursor=receipt.getRetiredPayment();plan=receipt.getRetiredPlan();
        }else{
          if(!Objects.equals(DailyMissionRepeats.selectedDay(player,receipt.getMain()),receipt.getDay()))return false;
          main=g.getMainMissions().get(receipt.getMain());target=g.getSubMissions().get(receipt.getSelect());tail=g.getSubMissions().get(receipt.getTalk());
        }
        if(!accepted(main,receipt.getMain()) || !accepted(target,receipt.getSelect()) || !accepted(tail,receipt.getTalk())
            || !Objects.equals(main.getObservationKey(),receipt.getMainAcceptance()) || !Objects.equals(target.getObservationKey(),receipt.getSelectAcceptance())
            || !Objects.equals(tail.getObservationKey(),receipt.getTalkAcceptance()) || cursor==null || plan==null || !"UseSelectedItem".equals(plan.getFinishType())
            || !Map.of(receipt.getItem(),1).equals(plan.getCosts()))return false;
        if(cursor.isPending()){
          if(cursor.getNext()!=0 || target.getPhase()!=QuestState.Phase.DOING || receipt.getRetiredAt()!=0 || receipt.getReportedAt()!=0)return false;
        }else if(cursor.getNext()!=1)return false;
        if(target.getPhase()!=QuestState.Phase.DOING && (!MissionStateRecords.completed(target,receipt.getSelect(),1) || target.getFinishTime()<receipt.getAt()
            || target.getFinishTime()>now.getEpochSecond() || DailyManager.day(Instant.ofEpochSecond(target.getFinishTime()))!=receipt.getDay()))return false;
        if(receipt.getReportedAt()>0){
          if(!MissionStateRecords.completed(target,receipt.getSelect(),1) || !MissionStateRecords.completed(tail,receipt.getTalk(),1) || tail.getFinishTime()!=receipt.getReportedAt())return false;
        }else if(!MissionStateRecords.doing(tail,receipt.getTalk(),1))return false;
      }
      return true;
    }catch(RuntimeException corrupt){return false;}
  }
  private static boolean accepted(QuestState state,int id){return MissionStateRecords.doing(state,id,1) || MissionStateRecords.completed(state,id,1);}
  private static boolean retirementAllowed(Player player,int id,Instant now){
    var g=player.getGameplay();var d=definition(id);Long day=DailyMissionRepeats.selectedDay(player,id);
    if(!source(id) || day==null || day>=DailyManager.day(now) || !historyValid(player,now))return false;
    var cursor=g.getMissionPayments().get(d.select());var plan=g.getMissionPaymentPlans().get(d.select());
    var receipt=g.getDailyMissionSelectedItems().getRecords().get(key(id,day));
    if(cursor==null && plan==null)return receipt==null;
    return receipt!=null && receipt.getRetiredAt()==0 && DailyMissionSelectedItemState.paid(cursor,plan,receipt.getItem())
        && MissionStateRecords.subCompleted(player,d.select());
  }
  static boolean reopen(Player player,int id,Instant now){
    if(!retirementAllowed(player,id,now))return false;
    return retire(player,id,now,()->DailyMissionRepeats.reopenOrdinary(player,id,now),false);
  }
  static boolean expire(Player player,int id,Instant now){
    if(!retirementAllowed(player,id,now))return false;
    return retire(player,id,now,()->DailyMissionExpiry.expireOrdinary(player,now),true);
  }
  private static boolean retire(Player player,int id,Instant now,BooleanSupplier action,boolean expired){
    var g=player.getGameplay();var d=definition(id);Long day=DailyMissionRepeats.selectedDay(player,id);var rollback=new Snapshot(g);
    var payment=g.getMissionPayments().remove(d.select());var plan=g.getMissionPaymentPlans().remove(d.select());
    var next=new DailyMissionSelectedItemState(g.getDailyMissionSelectedItems());var receipt=next.getRecords().get(key(id,day));
    g.dailyMissionSelections(GSON.fromJson(GSON.toJson(g.getDailyMissionSelections()),DailyMissionSelections.class));
    try{
      if(!action.getAsBoolean()){rollback.restore(g);return false;}
      if(receipt!=null)next.getRecords().put(key(id,day),receipt.retired(now.getEpochSecond(),payment,plan));
      g.dailyMissionSelectedItems(next);player.save();
    }catch(RuntimeException failure){rollback.restore(g);throw failure;}
    // The old owner may unload only after its archived source view and payment save commit.
    if(expired && player.getScene()!=null)player.getScene().refreshMissionGroups();return true;
  }
  private static final class Snapshot {
    final Map<Integer,QuestState> mains,subs;final Map<Integer,GameplayState.ActionCursor> actions,payments;
    final Map<Integer,GameplayState.MissionPaymentPlan> plans;final Map<Integer,Long> days;
    final Map<String,DailyMissionPeriodState.Snapshot> archives;final Map<String,DailyMissionExpiredState> expired;
    final DailyMissionSelections selections;final DailyMissionSelectedItemState selected;
    Snapshot(GameplayState g){
      mains=new HashMap<>(g.getMainMissions());subs=new HashMap<>(g.getSubMissions());actions=new HashMap<>(g.getStoryActions());payments=new HashMap<>(g.getMissionPayments());
      plans=new HashMap<>(g.getMissionPaymentPlans());days=new HashMap<>(g.getDailyMissionPeriods().getRunDays());archives=new HashMap<>(g.getDailyMissionPeriods().getArchives());
      expired=new HashMap<>(g.getDailyMissionPeriods().getExpired());selections=g.getDailyMissionSelections();selected=g.getDailyMissionSelectedItems();
    }
    static <K,V>void restore(Map<K,V> target,Map<K,V> original){target.clear();target.putAll(original);}
    void restore(GameplayState g){
      restore(g.getMainMissions(),mains);restore(g.getSubMissions(),subs);restore(g.getStoryActions(),actions);restore(g.getMissionPayments(),payments);restore(g.getMissionPaymentPlans(),plans);
      restore(g.getDailyMissionPeriods().getRunDays(),days);restore(g.getDailyMissionPeriods().getArchives(),archives);restore(g.getDailyMissionPeriods().getExpired(),expired);
      g.dailyMissionSelections(selections);g.dailyMissionSelectedItems(selected);
    }
  }
  private DailyMissionSelectedItemRules(){}
}
