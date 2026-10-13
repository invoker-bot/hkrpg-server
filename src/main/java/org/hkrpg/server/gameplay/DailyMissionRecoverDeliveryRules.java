package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.*;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Only the complete original 3011014 graph can use this grant/consume daily bridge.
 * Server reports retain the original parallel menu order; client strings/Act are not executed. */
final class DailyMissionRecoverDeliveryRules {
  private static final Gson GSON=ResourceLoader.getGson();
  private static final int MAIN=3011014,FIRST=301101401,MENU=301101402,END=301101412;
  private record Cache(String hash,JsonElement value){}
  private static final Map<Path,Cache> CACHE=new HashMap<>();
  private record TableCache(String hash,String field,Map<Integer,List<JsonObject>> rows){}
  private static final Map<Path,TableCache> TABLE_CACHE=new HashMap<>();
  private static final ThreadLocal<SourceScope> SCOPE=new ThreadLocal<>();
  /** A refresh reads the complete source once. New requests always read it again.
   * Acceptance/day/UUID/receipt and live entity checks remain uncached. */
  static final class SourceScope implements AutoCloseable {
    private final SourceScope previous;
    private final String resource;
    private final MissionInfo graph;
    private final MainMissionExcel main;
    private final Map<String,FloorInfo> floors=new HashMap<>();
    private final boolean original;
    SourceScope(){
      previous=SCOPE.get();resource=LunarCore.getConfig().getResourceDir();graph=GameData.getMissionInfos().get(MAIN);main=GameData.getMainMissionExcelMap().get(MAIN);
      for(String path:DailyMissionRecoverDeliverySources.FLOORS.keySet()){
        String name=path.substring(path.lastIndexOf('/')+1).replace(".json","");floors.put(name,GameData.getFloorInfos().get(name));
      }
      original=source();SCOPE.set(this);
    }
    boolean unchanged(){
      if(!Objects.equals(resource,LunarCore.getConfig().getResourceDir()) || graph!=GameData.getMissionInfos().get(MAIN) || main!=GameData.getMainMissionExcelMap().get(MAIN))return false;
      for(var entry:floors.entrySet())if(GameData.getFloorInfos().get(entry.getKey())!=entry.getValue())return false;
      if(graph!=null){if(graph.getSubMissionList()==null)return false;for(var goal:graph.getSubMissionList())if(goal==null || GameplayDefinitions.subs.get(goal.getId())!=goal)return false;}
      return true;
    }
    @Override public void close(){if(previous==null)SCOPE.remove();else SCOPE.set(previous);}
  }
  static SourceScope sourceScope(){return new SourceScope();}
  static boolean handles(int id){return id==MAIN;}
  static boolean sub(int id){return id>=FIRST && id<=END;}
  static boolean talk(int id){return id>=FIRST && id<=301101405 || id>=301101409 && id<=301101411;}
  static boolean consume(int id){return id>=301101406 && id<=301101408;}
  private static boolean option(int id){return id>=301101403 && id<=301101405;}
  private static String groupPath(int plane,int floor,int id){return "Config/LevelOutput/SharedRuntimeGroup/Groups_P"+plane+"_F"+floor+"/LevelGroup_P"+plane+"_F"+floor+"_G"+id+".json";}
  private static JsonElement read(Path root,String relative)throws Exception{
    Path file=root.resolve(relative).normalize();if(!file.startsWith(root) || !Files.isRegularFile(file))return null;
    if(!file.toRealPath().startsWith(root))throw new IllegalStateException("Original source outside resource root");
    byte[] bytes=Files.readAllBytes(file);if(bytes.length>67108864)throw new IllegalStateException("Original source too large");
    String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    synchronized(CACHE){
      var cached=CACHE.get(file);if(cached!=null && cached.hash().equals(digest))return cached.value();
      var value=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
      if(CACHE.size()>1024)CACHE.clear();CACHE.put(file,new Cache(digest,value));return value;
    }
  }
  /** Keep only our complete selected rows; the 244380-row original text table is streamed.
   * Every new request checks the entire file's current SHA, including when this cache hits. */
  private static Map<Integer,List<JsonObject>> table(Path root,String relative,String field)throws Exception{
    Path file=root.resolve(relative).normalize();if(!file.startsWith(root) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(root))throw new IllegalStateException("Original table outside resource root");
    byte[] bytes=Files.readAllBytes(file);if(bytes.length>67108864)throw new IllegalStateException("Original table too large");
    String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    synchronized(TABLE_CACHE){
      var old=TABLE_CACHE.get(file);if(old!=null && old.hash().equals(hash) && old.field().equals(field))return old.rows();
      var wanted=new HashSet<Integer>();for(String key:DailyMissionRecoverDeliverySources.ROWS.keySet())if(key.startsWith(relative+"#"+field+":"))wanted.add(Integer.parseInt(key.substring(key.lastIndexOf(':')+1)));
      var rows=new HashMap<Integer,List<JsonObject>>();int count=0;
      try(var reader=new com.google.gson.stream.JsonReader(new java.io.InputStreamReader(new java.io.ByteArrayInputStream(bytes),java.nio.charset.StandardCharsets.UTF_8))){
        reader.beginArray();while(reader.hasNext()){
          if(++count>DailyMissionRecoverDeliverySources.TABLE_MAX_ROWS.get(relative))throw new IllegalStateException("Source table exceeds complete original boundary");
          var row=JsonParser.parseReader(reader).getAsJsonObject();var number=row.get(field);
          // The complete original TalkSentence table has one omitted default-zero ID.
          if(number==null)continue;if(!number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber())throw new IllegalStateException("Invalid original table id");
          int id=number.getAsBigDecimal().intValueExact();if(wanted.contains(id))rows.computeIfAbsent(id,ignored->new ArrayList<>()).add(row);
        }
        reader.endArray();if(reader.peek()!=com.google.gson.stream.JsonToken.END_DOCUMENT)throw new IllegalStateException("Unexpected original table tail");
      }
      if(TABLE_CACHE.size()>256)TABLE_CACHE.clear();TABLE_CACHE.put(file,new TableCache(hash,field,rows));return rows;
    }
  }
  static boolean original(MissionInfo graph){return graph!=null && graph.getMainMissionID()==MAIN && GameData.getMissionInfos().get(MAIN)==graph && source();}
  static boolean source(){
    var scope=SCOPE.get();if(scope!=null && scope.unchanged())return scope.original;
    try{
      var main=GameData.getMainMissionExcelMap().get(MAIN);var graph=GameData.getMissionInfos().get(MAIN);
      if(main==null || graph==null || !DailyMissionRecoverDeliverySources.MAIN_PIN.equals(TimelineActivityRules.fingerprint(GSON.toJsonTree(main))))return false;
      var normalized=GSON.toJsonTree(graph).getAsJsonObject();
      for(var value:normalized.getAsJsonArray("SubMissionList"))value.getAsJsonObject().remove("task");
      if(!DailyMissionRecoverDeliverySources.GRAPH_PIN.equals(TimelineActivityRules.fingerprint(normalized)))return false;
      for(var goal:graph.getSubMissionList())if(GameplayDefinitions.subs.get(goal.getId())!=goal || goal.getTask()==null || goal.getStoryTrialTask()!=null
          || goal.getTask().getSourceDefinition()==null || !DailyMissionRecoverDeliverySources.FILES.get(goal.getMissionJsonPath()).equals(TimelineActivityRules.fingerprint(JsonParser.parseString(goal.getTask().getSourceDefinition()))))return false;
      Path root=Path.of(LunarCore.getConfig().getResourceDir()).toRealPath();
      for(var entry:DailyMissionRecoverDeliverySources.FILES.entrySet()){
        var value=read(root,entry.getKey());
        if(value==null && DailyMissionRecoverDeliverySources.OPTIONAL.contains(entry.getKey()))continue;
        if(value==null || !entry.getValue().equals(TimelineActivityRules.fingerprint(value)))return false;
      }
      for(var entry:DailyMissionRecoverDeliverySources.FLOORS.entrySet()){
        String name=entry.getKey().substring(entry.getKey().lastIndexOf('/')+1).replace(".json","");
        var floor=GameData.getFloorInfos().get(name);
        if(floor==null || !floor.isLoaded() || !entry.getValue().equals(TimelineActivityRules.fingerprint(GSON.toJsonTree(floor))))return false;
      }
      for(var entry:DailyMissionRecoverDeliverySources.GROUPS.entrySet()){
        String path=entry.getKey();String part=path.substring(path.lastIndexOf('/')+1).replace("LevelGroup_","").replace(".json","");String[] ids=part.split("_");
        int plane=Integer.parseInt(ids[0].substring(1)),floorId=Integer.parseInt(ids[1].substring(1)),groupId=Integer.parseInt(ids[2].substring(1));
        var floor=GameData.getFloorInfo(plane,floorId);int index=DailyMissionRecoverDeliverySources.GROUP_INDICES.get(path);
        var group=floor.getGroups(0).get(groupId);
        if(group==null || group.getId()!=groupId || floor.getGroupInfoByIndex(index)!=group || !floor.isGroupInDimension(groupId,0)
            || Collections.frequency(floor.getMainDimension().getGroupIndexList(),index)!=1 || floor.getSimpleGroupList().get(index).isIsDelete()
            || floor.getSimpleGroupList().get(index).getID()!=groupId || !path.equals(floor.getSimpleGroupList().get(index).getGroupPath())
            || !entry.getValue().equals(TimelineActivityRules.fingerprint(GSON.toJsonTree(group))))return false;
      }
      var tables=new HashMap<String,Map<Integer,List<JsonObject>>>();
      for(var entry:DailyMissionRecoverDeliverySources.ROWS.entrySet()){
        String[] key=entry.getKey().split("#",2);String[] index=key[1].split(":",2);int id=Integer.parseInt(index[1]);
        String tableKey=key[0]+"#"+index[0];var rows=tables.get(tableKey);if(rows==null){rows=table(root,key[0],index[0]);tables.put(tableKey,rows);}
        var matches=rows.get(id);if(matches==null || matches.size()!=1)return false;var row=matches.getFirst();
        if(!entry.getValue().equals(TimelineActivityRules.fingerprint(row)) || !loaded(key[0],id,row))return false;
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
      case "ExcelOutput/MazeProp.json" -> {var prop=GSON.fromJson(row,PropExcel.class);prop.onLoad();expected=prop;actual=GameData.getPropExcelMap().get(id);}
      case "ExcelOutput/MazePlane.json" -> {expected=GSON.fromJson(row,MazePlaneExcel.class);actual=GameData.getMazePlaneExcelMap().get(id);}
      case "ExcelOutput/MapEntrance.json" -> {expected=GSON.fromJson(row,MapEntranceExcel.class);actual=GameData.getMapEntranceExcelMap().get(id);}
      case "ExcelOutput/QuestData.json" -> {expected=GSON.fromJson(row,QuestExcel.class);actual=GameData.getQuestExcelMap().get(id);}
      case "ExcelOutput/FinishWay.json" -> {expected=GSON.fromJson(row,FinishWayExcel.class);actual=GameData.getFinishWayExcelMap().get(id);}
      case "ExcelOutput/RewardData.json" -> {var reward=GSON.fromJson(row,RewardExcel.class);reward.onLoad();expected=reward;actual=GameData.getRewardExcelMap().get(id);}
      case "ExcelOutput/DailyMissionData.json" -> {expected=GSON.fromJson(row,DailyMissionCatalog.Mission.class);actual=DailyMissionCatalog.mission(id);}
      case "ExcelOutput/DailyMissionCount.json" -> {expected=GSON.fromJson(row,DailyMissionCatalog.Count.class);actual=DailyMissionCatalog.count(row.get("DailyMissionType").getAsInt());}
      case "ExcelOutput/DailyActiveQuestPool.json" -> {var pool=GSON.fromJson(row,GameplayDefinitions.DailyPool.class);return GameplayDefinitions.dailyPool.stream().filter(value->value.QuestID()==id).toList().equals(List.of(pool));}
      case "ExcelOutput/PerformanceE.json","ExcelOutput/DialogueNPC.json","ExcelOutput/DialogueProp.json","ExcelOutput/DialogueCondition.json","ExcelOutput/TalkSentenceConfig.json" -> {return true;}
      default -> {return false;}
    }
    return actual!=null && TimelineActivityRules.fingerprint(GSON.toJsonTree(expected)).equals(TimelineActivityRules.fingerprint(GSON.toJsonTree(actual)));
  }
  private static boolean place(Player player,int plane,int floorId){
    var scene=player.getScene();var floor=GameData.getFloorInfo(plane,floorId);
    return player.getUid()>0 && scene!=null && scene.getPlayer()==player && player.getPlaneId()==plane && player.getFloorId()==floorId
        && scene.getPlaneId()==plane && scene.getFloorId()==floorId && scene.getFloorInfo()==floor && scene.getDimensionId()==0
        && scene.getStoryLineId()==0 && player.getGameplay().getStoryLines().getCurrentId()==0;
  }
  private static GroupInfo loadedGroup(Player player,int plane,int floor,int id){
    if(!place(player,plane,floor))return null;var scene=player.getScene();var group=scene.getFloorInfo().getGroups(0).get(id);
    return group!=null && scene.getResourceGroups().get(id)==group && scene.getLoadedGroups().contains(id)?group:null;
  }
  private static boolean npc(Player player,int plane,int floor,int groupId,int instance,int npcId){
    var group=loadedGroup(player,plane,floor,groupId);if(group==null)return false;
    var placements=group.getNPCList().stream().filter(info->info.getID()==instance && info.getNPCID()==npcId && info.isLoadOnInitial() && !info.isIsDelete() && !info.isIsClientOnly()).toList();
    var actors=player.getScene().getEntitiesByGroup(EntityNpc.class,groupId).stream().filter(entity->entity.getInstId()==instance).toList();
    if(placements.size()!=1 || actors.size()!=1)return false;var expected=placements.getFirst();var actual=actors.getFirst();
    return actual.getScene()==player.getScene() && actual.getNpcId()==npcId && actual.getPos().equals(expected.getPos()) && actual.getRot().equals(expected.getRot());
  }
  private static boolean menu(Player player){
    var group=loadedGroup(player,10102,10102001,122);if(group==null)return false;
    var props=player.getScene().getEntitiesByGroup(EntityProp.class,122);if(props.size()!=2 || group.getPropList().size()!=2)return false;
    for(var info:group.getPropList()){
      var matches=props.stream().filter(prop->prop.getInstId()==info.getID()).toList();if(matches.size()!=1)return false;var prop=matches.getFirst();
      if(prop.getScene()!=player.getScene() || prop.getPropId()!=100 || prop.getPropInfo()!=info || prop.getPropId()!=info.getPropID()
          || prop.getExcel()!=GameData.getPropExcelMap().get(100) || !prop.getPos().equals(info.getPos()) || !prop.getRot().equals(info.getRot()))return false;
    }
    return true;
  }
  private static boolean contact(Player player,int option){
    return switch(option){
      case 301101403 -> npc(player,20121,20121001,93,400001,11104);
      case 301101404 -> npc(player,20123,20123001,76,400001,11205);
      case 301101405 -> npc(player,10102,10102003,23,400001,11403);
      default -> false;
    };
  }
  static boolean current(Player player,int id,Instant now){
    if(!handles(id))return true;
    if(now==null || !source() || !DailyCommissionAcceptance.valid(player.getGameplay().getDailyMissionSelections()) || !historyValid(player,now))return false;
    var selected=DailyMissionRepeats.selectedDay(player,MAIN);return selected!=null && selected==DailyManager.day(now);
  }
  private static DailyMissionRecoverDeliveryState.Receipt receipt(Player player,long day){return player.getGameplay().getDailyMissionRecoverDelivery().getRecords().get(DailyMissionRecoverDeliveryState.key(day));}
  static boolean allowsTalk(Player player,int id){
    if(!talk(id))return !sub(id);var g=player.getGameplay();var now=player.getMissionManager().currentInstant();
    if(!current(player,MAIN,now) || !MissionStateRecords.mainDoing(player,MAIN) || !MissionStateRecords.doing(g.getSubMissions().get(id),id,1))return false;
    if(id==FIRST)return receipt(player,DailyManager.day(now))==null && npc(player,10102,10102001,99,400002,11302);
    var record=receipt(player,DailyManager.day(now));if(record==null || record.getRetiredAt()!=0)return false;
    if(id==MENU)return record.getMenuAt()==0 && menu(player);
    if(option(id))return (record.getOption()==0 || record.getOption()==id && record.getGrantedAt()==0)
        && place(player,10102,10102001) && (record.getMenuAt()>0 || menu(player));
    return record.getFeedback()==id && record.getFeedbackAt()==0 && record.getGrantedAt()>0 && contact(player,record.getOption())
        && MissionStateRecords.subCompleted(player,record.getConsume()) && DailyMissionRecoverDeliveryState.paid(g.getMissionPayments().get(record.getConsume()),g.getMissionPaymentPlans().get(record.getConsume()),record.getItem());
  }
  static Retcode submission(Player player,MissionInfo.SubMissionInfo goal,Instant now){
    if(!consume(goal.getId()) && !handles(goal.getMainMissionID()))return Retcode.SUCC;
    if(!consume(goal.getId()) || goal.getMainMissionID()!=MAIN)return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
    if(!current(player,MAIN,now))return Retcode.MISSION_NOT_DOING;
    var record=receipt(player,DailyManager.day(now));var g=player.getGameplay();
    if(record==null || record.getConsume()!=goal.getId() || record.getGrantedAt()==0 || record.getFeedbackAt()!=0
        || !contact(player,record.getOption()) || !MissionStateRecords.mainDoing(player,MAIN)
        || !MissionStateRecords.doing(g.getSubMissions().get(goal.getId()),goal.getId(),1))return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
    var accepted=g.getSubMissions().get(goal.getId());if(accepted.getObservationKey()!=null && !DailyMissionRecoverDeliveryState.uuid(accepted.getObservationKey()))return Retcode.PLAYER_DATA_ERROR;
    if(record.getPaymentAt()==0 && (g.getMissionPayments().containsKey(goal.getId()) || g.getMissionPaymentPlans().containsKey(goal.getId())))return Retcode.PLAYER_DATA_ERROR;
    return Retcode.SUCC;
  }
  static void bindPayment(Player player,MissionInfo.SubMissionInfo goal,Map<Integer,Integer> costs,Instant now){
    if(!consume(goal.getId()) && !handles(goal.getMainMissionID()))return;
    if(submission(player,goal,now)!=Retcode.SUCC)throw new IllegalStateException("Original recover-item acceptance changed before payment");
    var g=player.getGameplay();var record=receipt(player,DailyManager.day(now));
    if(record.getPaymentAt()!=0 || !Map.of(record.getItem(),1).equals(costs))throw new IllegalStateException("Original recover-item payment already observed");
    var next=new DailyMissionRecoverDeliveryState(g.getDailyMissionRecoverDelivery());
    next.getRecords().put(DailyMissionRecoverDeliveryState.key(record.getDay()),record.payment(now.getEpochSecond(),g.getSubMissions().get(goal.getId()).captureObservationKey()));g.dailyMissionRecoverDelivery(next);
  }
  static String actionKey(Player player,MissionInfo.SubMissionInfo goal,int index){
    if(!sub(goal.getId()) && !handles(goal.getMainMissionID()))return "story:"+goal.getId()+":"+index;
    var now=player.getMissionManager().currentInstant();var record=receipt(player,DailyManager.day(now));
    if(!option(goal.getId()) || goal.getMainMissionID()!=MAIN || index!=0 || record==null || record.getOption()!=goal.getId()
        || record.getRetiredAt()!=0 || !current(player,MAIN,now) || !place(player,10102,10102001))throw new IllegalStateException("Unobserved original recover-item action");
    return record.getGrantKey();
  }
  static Retcode finishTalk(Player player,MissionInfo.SubMissionInfo goal,QuestState accepted,Map<Integer,Integer> values,Instant now){
    if(values==null || !values.isEmpty())return Retcode.MISSION_CUSTOM_VALUE_NOT_VALID;
    if(goal.getMainMissionID()!=MAIN || !talk(goal.getId()) || accepted!=player.getGameplay().getSubMissions().get(goal.getId()) || !allowsTalk(player,goal.getId()))return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
    var originalMain=player.getGameplay().getMainMissions().get(MAIN);
    if(originalMain.getObservationKey()!=null && !DailyMissionRecoverDeliveryState.uuid(originalMain.getObservationKey())
        || accepted.getObservationKey()!=null && !DailyMissionRecoverDeliveryState.uuid(accepted.getObservationKey()))return Retcode.PLAYER_DATA_ERROR;
    if(option(goal.getId()))return finishOption(player,goal,accepted,now);
    var g=player.getGameplay();var previous=g.getDailyMissionRecoverDelivery();var next=new DailyMissionRecoverDeliveryState(previous);var main=g.getMainMissions().get(MAIN);
    String mainBefore=main.getObservationKey(),goalBefore=accepted.getObservationKey();String mainKey=null,goalKey=null;
    var completed=GSON.fromJson(GSON.toJson(accepted),QuestState.class);
    try{
      mainKey=main.captureObservationKey();goalKey=accepted.captureObservationKey();completed=GSON.fromJson(GSON.toJson(accepted),QuestState.class);
      if(!completed.advance(1,1,now.getEpochSecond()))return Retcode.MISSION_NOT_DOING;
      long day=DailyManager.day(now);String key=DailyMissionRecoverDeliveryState.key(day);var record=receipt(player,day);
      if(goal.getId()==FIRST)record=new DailyMissionRecoverDeliveryState.Receipt(player.getUid(),day,now.getEpochSecond(),mainKey,goalKey);
      else if(goal.getId()==MENU)record=record.menu(now.getEpochSecond(),goalKey);
      else record=record.feedback(now.getEpochSecond(),goalKey);
      if(!record.valid())throw new IllegalStateException("Invalid original recover-item report receipt");
      next.getRecords().put(key,record);g.getSubMissions().put(goal.getId(),completed);g.dailyMissionRecoverDelivery(next);player.save();
    }catch(RuntimeException failure){
      g.getSubMissions().put(goal.getId(),accepted);g.dailyMissionRecoverDelivery(previous);
      if(goalKey!=null)accepted.restoreObservationKey(goalKey,goalBefore);if(mainKey!=null)main.restoreObservationKey(mainKey,mainBefore);throw failure;
    }
    return Retcode.SUCC;
  }
  private static Retcode finishOption(Player player,MissionInfo.SubMissionInfo goal,QuestState accepted,Instant now){
    var g=player.getGameplay();var record=receipt(player,DailyManager.day(now));String key=DailyMissionRecoverDeliveryState.key(record.getDay());
    var menu=g.getSubMissions().get(MENU);if(!accepted(menu,MENU) || menu.getObservationKey()!=null && !DailyMissionRecoverDeliveryState.uuid(menu.getObservationKey()))return Retcode.PLAYER_DATA_ERROR;
    if(accepted.getObservationKey()!=null && !DailyMissionRecoverDeliveryState.uuid(accepted.getObservationKey()))return Retcode.PLAYER_DATA_ERROR;
    if(record.getOption()==0){
      var stock=player.getInventory().getMaterialByItemId(140146+goal.getId()-301101403);
      if(stock!=null && stock.getOwnerUid()!=player.getUid())return Retcode.PLAYER_DATA_ERROR;
      int before=stock==null?0:stock.getCount();if(before<0 || before>=99999)return Retcode.PLAYER_DATA_ERROR;
      var previous=g.getDailyMissionRecoverDelivery();var next=new DailyMissionRecoverDeliveryState(previous);
      String menuBefore=menu.getObservationKey(),choiceBefore=accepted.getObservationKey(),menuKey=null,choiceKey=null;
      try{
        menuKey=menu.getPhase()==QuestState.Phase.DOING?menu.captureObservationKey():menu.captureCompletedObservationKey();choiceKey=accepted.captureObservationKey();
        record=record.choice(goal.getId(),now.getEpochSecond(),menuKey,choiceKey,before);
        if(!record.valid())throw new IllegalStateException("Invalid original recover-item option reservation");
        next.getRecords().put(key,record);g.dailyMissionRecoverDelivery(next);player.save();
      }catch(RuntimeException failure){
        g.dailyMissionRecoverDelivery(previous);if(choiceKey!=null)accepted.restoreObservationKey(choiceKey,choiceBefore);if(menuKey!=null)menu.restoreObservationKey(menuKey,menuBefore);throw failure;
      }
    }
    var result=new StoryActions(player).execute(goal);if(result!=Retcode.SUCC)return result;
    var item=player.getInventory().getMaterialByItemId(record.getItem());var cursor=g.getStoryActions().get(goal.getId());
    if(item==null || item.getId()==null || item.getOwnerUid()!=player.getUid() || item.getCount()!=record.getBeforeCount()+1
        || cursor==null || cursor.isPending() || cursor.getNext()!=1 || !player.getRewardLedger().granted(record.getGrantKey()))return Retcode.PLAYER_DATA_ERROR;
    var previous=g.getDailyMissionRecoverDelivery();var next=new DailyMissionRecoverDeliveryState(previous);
    var completed=GSON.fromJson(GSON.toJson(accepted),QuestState.class);if(!completed.advance(1,1,now.getEpochSecond()))return Retcode.MISSION_NOT_DOING;
    next.getRecords().put(key,record.granted(now.getEpochSecond(),item.getId().toHexString()));g.getSubMissions().put(goal.getId(),completed);g.dailyMissionRecoverDelivery(next);
    try{player.save();}catch(RuntimeException failure){g.getSubMissions().put(goal.getId(),accepted);g.dailyMissionRecoverDelivery(previous);throw failure;}
    return Retcode.SUCC;
  }
  private static boolean accepted(QuestState state,int id){return MissionStateRecords.doing(state,id,1) || MissionStateRecords.completed(state,id,1);}
  private static boolean match(QuestState state,int id,String key){return accepted(state,id) && Objects.equals(key,state.getObservationKey());}
  private static boolean completed(QuestState state,int id,String key,long at){return MissionStateRecords.completed(state,id,1) && Objects.equals(key,state.getObservationKey()) && state.getFinishTime()==at;}
  static boolean historyValid(Player player,Instant now){
    try{
      var g=player.getGameplay();var state=g.getDailyMissionRecoverDelivery();if(now==null || !state.valid())return false;
      for(var record:state.getRecords().values()){
        if(record.getUid()!=player.getUid() || record.getFirstAt()>now.getEpochSecond() || record.getMenuAt()>now.getEpochSecond()
            || record.getChoiceAt()>now.getEpochSecond() || record.getGrantedAt()>now.getEpochSecond() || record.getPaymentAt()>now.getEpochSecond()
            || record.getFeedbackAt()>now.getEpochSecond() || record.getRetiredAt()>now.getEpochSecond())return false;
        QuestState main;Map<Integer,QuestState> subs;var cursor=g.getStoryActions().get(record.getOption());
        var payment=g.getMissionPayments().get(record.getConsume());var plan=g.getMissionPaymentPlans().get(record.getConsume());
        if(record.getRetiredAt()>0){
          String key=DailyMissionRecoverDeliveryState.key(record.getDay());var archive=g.getDailyMissionPeriods().getArchives().get(key);var expired=g.getDailyMissionPeriods().getExpired().get(key);
          if((archive==null)==(expired==null))return false;main=archive!=null?archive.getMain():expired.getMain();subs=archive!=null?archive.getSubs():expired.getSubs();
          cursor=record.getRetiredAction();payment=record.getRetiredPayment();plan=record.getRetiredPlan();
        }else{
          if(!Objects.equals(DailyMissionRepeats.selectedDay(player,MAIN),record.getDay()))return false;
          main=g.getMainMissions().get(MAIN);subs=g.getSubMissions();
        }
        if(!match(main,MAIN,record.getMainAcceptance()) || !completed(subs.get(FIRST),FIRST,record.getFirstAcceptance(),record.getFirstAt()))return false;
        var menu=subs.get(MENU);
        if(record.getMenuAcceptance()!=null && !match(menu,MENU,record.getMenuAcceptance()))return false;
        if(record.getMenuAt()>0 && !completed(menu,MENU,record.getMenuAcceptance(),record.getMenuAt()))return false;
        if(record.getOption()==0){
          if(record.getRetiredAt()==0)for(int id=301101403;id<=301101408;id++)if(g.getMissionPayments().containsKey(id) || g.getMissionPaymentPlans().containsKey(id)
              || option(id) && g.getStoryActions().containsKey(id))return false;
          continue;
        }
        if(!match(subs.get(record.getOption()),record.getOption(),record.getChoiceAcceptance()))return false;
        for(int id=301101403;id<=301101405;id++)if(id!=record.getOption() && (MissionStateRecords.completed(subs.get(id),id,1)
            || record.getRetiredAt()==0 && g.getStoryActions().containsKey(id)))return false;
        var grant=g.getRewards().get(record.getGrantKey());
        if(record.getGrantedAt()>0){
          if(!completed(subs.get(record.getOption()),record.getOption(),record.getChoiceAcceptance(),record.getGrantedAt())
              || cursor==null || cursor.isPending() || cursor.getNext()!=1 || grant==null || grant.getRewardId()!=0 || !grant.isGranted())return false;
        }else{
          if(!MissionStateRecords.doing(subs.get(record.getOption()),record.getOption(),1) || record.getRetiredAt()>0
              || cursor!=null && (cursor.getNext()<0 || cursor.getNext()>1 || cursor.isPending() && cursor.getNext()!=0)
              || grant!=null && grant.getRewardId()!=0)return false;
        }
        if(record.getPaymentAt()==0){if(payment!=null || plan!=null)return false;}
        else{
          var target=subs.get(record.getConsume());
          if(!match(target,record.getConsume(),record.getConsumeAcceptance()) || payment==null || plan==null
              || !"ConsumeMissionItem".equals(plan.getFinishType()) || !Map.of(record.getItem(),1).equals(plan.getCosts()))return false;
          if(payment.isPending()){if(payment.getNext()!=0 || !MissionStateRecords.doing(target,record.getConsume(),1) || record.getRetiredAt()!=0)return false;}
          else if(payment.getNext()!=1)return false;
          if(target.getPhase()!=QuestState.Phase.DOING && (target.getFinishTime()<record.getPaymentAt() || !DailyMissionRecoverDeliveryState.time(target.getFinishTime(),record.getDay())))return false;
        }
        if(record.getFeedbackAt()>0 && (!completed(subs.get(record.getFeedback()),record.getFeedback(),record.getFeedbackAcceptance(),record.getFeedbackAt())
            || !DailyMissionRecoverDeliveryState.paid(payment,plan,record.getItem()) || !MissionStateRecords.completed(subs.get(record.getConsume()),record.getConsume(),1)))return false;
      }
      return true;
    }catch(RuntimeException corrupt){return false;}
  }
  static boolean archivedAction(Player player,int main,long day,int id,GameplayState.ActionCursor action){
    if(main!=MAIN || !option(id) || action==null || action.isPending() || action.getNext()!=1)return false;
    var record=receipt(player,day);
    return record!=null && record.valid() && record.getRetiredAt()>0 && record.getOption()==id && record.getGrantedAt()>0
        && record.getRetiredAction()!=null && !record.getRetiredAction().isPending() && record.getRetiredAction().getNext()==1
        && player.getRewardLedger().granted(record.getGrantKey());
  }
  static boolean reopen(Player player,int id,Instant now){return handles(id) && retire(player,now,false);}
  static boolean expire(Player player,int id,Instant now){return handles(id) && retire(player,now,true);}
  private static boolean retire(Player player,Instant now,boolean expired){
    var g=player.getGameplay();Long day=DailyMissionRepeats.selectedDay(player,MAIN);var periods=g.getDailyMissionPeriods();var main=g.getMainMissions().get(MAIN);
    if(day==null || day>=DailyManager.day(now) || !source() || !historyValid(player,now) || !DailyCommissionAcceptance.valid(g.getDailyMissionSelections())
        || periods.getRunDays().containsKey(MAIN) && !Objects.equals(periods.getRunDays().get(MAIN),day)
        || periods.getArchives().containsKey(DailyMissionRecoverDeliveryState.key(day)) || periods.getExpired().containsKey(DailyMissionRecoverDeliveryState.key(day)))return false;
    String rewardKey=DailyMissionRepeats.mainKey(player,MAIN);var record=receipt(player,day);var graph=GameData.getMissionInfos().get(MAIN);
    if(expired){if(!MissionStateRecords.doing(main,MAIN,1) || g.getRewards().containsKey(rewardKey) || g.getMissionRewardPlans().containsKey(rewardKey))return false;}
    else{
      var plan=g.getMissionRewardPlans().get(rewardKey);
      if(!MissionStateRecords.completed(main,MAIN,1) || main.getPhase()!=QuestState.Phase.CLOSED || !DailyMissionRecoverDeliveryState.time(main.getFinishTime(),day)
          || record==null || record.getFeedbackAt()==0 || !MissionStateRecords.subCompleted(player,END) || plan==null || !plan.isReady()
          || !List.of(231).equals(plan.getRewardIds()) || !plan.getChoices().isEmpty() || !player.getRewardLedger().granted(rewardKey))return false;
    }
    if(record!=null && (record.getChoiceAt()>0 && record.getGrantedAt()==0 || record.getPaymentAt()>0
        && (!DailyMissionRecoverDeliveryState.paid(g.getMissionPayments().get(record.getConsume()),g.getMissionPaymentPlans().get(record.getConsume()),record.getItem())
            || !MissionStateRecords.subCompleted(player,record.getConsume()))))return false;
    if(record==null && !MissionStateRecords.doing(g.getSubMissions().get(FIRST),FIRST,1))return false;
    var oldSubs=new HashMap<Integer,QuestState>();var goals=new HashMap<Integer,Integer>();var actions=new HashMap<Integer,GameplayState.ActionCursor>();
    for(var goal:graph.getSubMissionList()){
      int sid=goal.getId();var state=g.getSubMissions().get(sid);var action=g.getStoryActions().get(sid);goals.put(sid,1);
      if(g.getCancelledSubs().contains(sid) || g.getMissionAcceptedDays().containsKey(sid) || g.getMissionAcquiredItems().containsKey(sid)
          || g.getMissionPayments().containsKey(sid) && (record==null || sid!=record.getConsume())
          || g.getMissionPaymentPlans().containsKey(sid) && (record==null || sid!=record.getConsume()))return false;
      if(state!=null){if(!accepted(state,sid) || state.getPhase()==QuestState.Phase.CLAIMING || state.getPhase()!=QuestState.Phase.DOING
          && (!DailyMissionRecoverDeliveryState.time(state.getFinishTime(),day) || state.getFinishTime()>now.getEpochSecond()))return false;oldSubs.put(sid,state);}
      if(action!=null){
        if(action.isPending() || action.getNext()!=0 && (record==null || sid!=record.getOption() || action.getNext()!=1 || record.getGrantedAt()==0))return false;
        actions.put(sid,action);
      }
    }
    var rollback=new Snapshot(g);var next=new DailyMissionRecoverDeliveryState(g.getDailyMissionRecoverDelivery());
    try{
      var payment=record==null?null:g.getMissionPayments().remove(record.getConsume());var paymentPlan=record==null?null:g.getMissionPaymentPlans().remove(record.getConsume());
      if(record!=null)next.getRecords().put(DailyMissionRecoverDeliveryState.key(day),record.retired(now.getEpochSecond(),actions.get(record.getOption()),payment,paymentPlan));
      if(expired)periods.getExpired().put(DailyMissionRecoverDeliveryState.key(day),new DailyMissionExpiredState(day,now.getEpochSecond(),MAIN,main,oldSubs,goals,actions));
      else periods.getArchives().put(DailyMissionRecoverDeliveryState.key(day),new DailyMissionPeriodState.Snapshot(day,MAIN,main,oldSubs,goals,actions,List.of(rewardKey)));
      g.getMainMissions().remove(MAIN);for(int sid:goals.keySet()){g.getSubMissions().remove(sid);g.getStoryActions().remove(sid);}
      periods.getRunDays().remove(MAIN);g.dailyMissionRecoverDelivery(next);
      if(!expired){
        periods.getRunDays().put(MAIN,DailyManager.day(now));g.getMainMissions().put(MAIN,new QuestState(MAIN));
        g.dailyMissionSelections(GSON.fromJson(GSON.toJson(g.getDailyMissionSelections()),DailyMissionSelections.class));DailyCommissionAcceptance.record(player,MAIN,now);
      }
      player.save();
    }catch(RuntimeException failure){rollback.restore(g);throw failure;}
    if(player.getScene()!=null)player.getScene().refreshMissionGroups();return true;
  }
  private static final class Snapshot {
    final Map<Integer,QuestState> mains,subs;final Map<Integer,GameplayState.ActionCursor> actions,payments;
    final Map<Integer,GameplayState.MissionPaymentPlan> plans;final Map<Integer,Long> days;
    final Map<String,DailyMissionPeriodState.Snapshot> archives;final Map<String,DailyMissionExpiredState> expired;
    final DailyMissionSelections selections;final DailyMissionRecoverDeliveryState receipts;
    Snapshot(GameplayState g){
      mains=new HashMap<>(g.getMainMissions());subs=new HashMap<>(g.getSubMissions());actions=new HashMap<>(g.getStoryActions());payments=new HashMap<>(g.getMissionPayments());plans=new HashMap<>(g.getMissionPaymentPlans());
      days=new HashMap<>(g.getDailyMissionPeriods().getRunDays());archives=new HashMap<>(g.getDailyMissionPeriods().getArchives());expired=new HashMap<>(g.getDailyMissionPeriods().getExpired());selections=g.getDailyMissionSelections();receipts=g.getDailyMissionRecoverDelivery();
    }
    private static <K,V>void restore(Map<K,V> target,Map<K,V> original){target.clear();target.putAll(original);}
    void restore(GameplayState g){restore(g.getMainMissions(),mains);restore(g.getSubMissions(),subs);restore(g.getStoryActions(),actions);restore(g.getMissionPayments(),payments);restore(g.getMissionPaymentPlans(),plans);restore(g.getDailyMissionPeriods().getRunDays(),days);restore(g.getDailyMissionPeriods().getArchives(),archives);restore(g.getDailyMissionPeriods().getExpired(),expired);g.dailyMissionSelections(selections);g.dailyMissionRecoverDelivery(receipts);}
  }
  private DailyMissionRecoverDeliveryRules(){}
}
