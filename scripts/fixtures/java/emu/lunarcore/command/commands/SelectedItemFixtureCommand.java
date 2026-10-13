package emu.lunarcore.command.commands;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.command.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.MapEntranceExcel;
import java.nio.file.*;
import java.util.*;

/** Only owned console fixtures may supply prerequisite history and one real stock item. */
@Command(label="selecteditemfixture",requireTarget=false,permission="fixture.console-only",desc="隔离原选择日常：前置与真实库存夹具")
public final class SelectedItemFixtureCommand implements CommandHandler{
  static Map<String,List<Integer>> prerequisitePlan(int main,MapEntranceExcel entrance){
    if(entrance==null || entrance.getId()!=(main==3011605?2012201:2013201)
        || entrance.getPlaneID()!=(main==3011605?20122:20132))throw new IllegalStateException("Wrong actual source entrance");
    var mains=new ArrayList<>(List.of(1010201,1010403,1011501));
    for(int id:entrance.getFinishMainMissionList())if(!mains.contains(id))mains.add(id);
    var subs=Arrays.stream(entrance.getFinishSubMissionList()).boxed().toList();
    if(mains.stream().anyMatch(id->id<=0 || id==3011605 || id==3011608)
        || subs.stream().anyMatch(id->id<=0 || id==301160501 || id==301160502 || id==301160801 || id==301160802))throw new IllegalStateException("Entrance fixture cannot seed a current daily objective");
    return Map.of("main",List.copyOf(mains),"sub",subs);
  }
  public static void main(String[] args)throws Exception{
    if(args.length!=2 || !args[0].equals("--prerequisite-plan"))throw new IllegalArgumentException("Read-only plan mode only");
    var source=Path.of(args[1]).toRealPath();var entries=ResourceLoader.getGson().fromJson(Files.readString(source.resolve("ExcelOutput/MapEntrance.json")),MapEntranceExcel[].class);
    var plans=new TreeMap<Integer,Object>();
    for(int mid:List.of(3011605,3011608))plans.put(mid,prerequisitePlan(mid,Arrays.stream(entries).filter(e->e.getId()==(mid==3011605?2012201:2013201)).findFirst().orElseThrow()));
    System.out.println(new Gson().toJson(plans));
  }
  @Override public void execute(CommandArgs args){
    if(args.getSender()!=null || args.getList().size()!=6)return;
    int uid=Integer.parseInt(args.getList().get(0)),mid=Integer.parseInt(args.getList().get(1)),item=Integer.parseInt(args.getList().get(2));
    long epoch=Long.parseLong(args.getList().get(3));int nonce=Integer.parseInt(args.getList().get(5));String mode=args.getList().get(4);
    if(!Set.of(3011605,3011608).contains(mid) || !Set.of("initial","stock","inspect").contains(mode)
        || !(mid==3011605?Set.of(401004,401012):Set.of(401007,401009)).contains(item))throw new IllegalArgumentException("Not one declared original native choice");
    try{
      var cwd=Path.of("").toRealPath();var marker=JsonParser.parseString(Files.readString(cwd.resolve("resources-fixture/resource-manifest.json"))).getAsJsonObject();
      if(!cwd.getFileName().toString().startsWith("daily-selected-item-native-") || !marker.get("testFixture").getAsBoolean()
          || !marker.get("selectedItemFixture").getAsBoolean() || marker.get("originalMission").getAsInt()!=mid)throw new IllegalStateException("Not an owned finite fixture");
    }catch(java.io.IOException error){throw new IllegalStateException(error);}
    var player=LunarCore.getGameServer().getOnlinePlayerByUid(uid);if(player==null)throw new IllegalStateException("Fixture player is not online");
    synchronized(player){
      var g=player.getGameplay();var gson=new Gson();String before=gson.toJson(g);
      var plan=prerequisitePlan(mid,GameData.getMapEntranceExcelMap().get(mid==3011605?2012201:2013201));
      var prerequisites=mode.equals("initial")?plan.get("main"):List.<Integer>of();
      var subPrerequisites=mode.equals("initial")?plan.get("sub"):List.<Integer>of();
      for(int id:prerequisites){
        if(g.getMainMissions().containsKey(id))throw new IllegalStateException("Fixture cannot overwrite prerequisite "+id);
        var state=new QuestState(id);if(!state.advance(1,1,epoch) || !state.reserveReward())throw new IllegalStateException("Prerequisite fixture failed");
        state.closeReward();g.getMainMissions().put(id,state);
      }
      for(int id:subPrerequisites){
        if(g.getSubMissions().containsKey(id))throw new IllegalStateException("Fixture cannot overwrite entrance prerequisite "+id);
        var state=new QuestState(id);if(!state.advance(1,1,epoch))throw new IllegalStateException("Entrance prerequisite fixture failed");g.getSubMissions().put(id,state);
      }
      if(!prerequisites.isEmpty() || !subPrerequisites.isEmpty())player.save();
      if(!mode.equals("inspect")){
        var existing=player.getInventory().getMaterialByItemId(item);
        if(existing!=null && existing.getCount()!=0)throw new IllegalStateException("Stock fixture would overwrite real inventory");
        if(!player.getInventory().addItem(item,1,false))throw new IllegalStateException("Real inventory fixture delivery failed");
      }
      var actual=player.getInventory().getMaterialByItemId(item);var view=new TreeMap<String,Object>();
      view.put("uid",uid);view.put("mainMission",mid);view.put("nonce",nonce);view.put("mode",mode);
      view.put("stockItem",item);view.put("stockCount",actual==null?0:actual.getCount());view.put("stockOwnerUid",actual==null?uid:actual.getOwnerUid());
      view.put("seededPrerequisiteHistory",prerequisites);view.put("seededSubPrerequisiteHistory",subPrerequisites);view.put("credit",player.getScoin());view.put("targetOrPaymentSeeded",false);view.put("sourceRowsModified",false);
      if(mode.equals("inspect") && !before.equals(gson.toJson(g)))throw new IllegalStateException("Read-only fixture inspection mutated gameplay");
      System.out.println("HKRPG_SELECTED_ITEM_FIXTURE_VIEW:"+gson.toJson(view));
    }
    System.out.println("HKRPG_SELECTED_ITEM_FIXTURE_ACK:"+uid+":"+mode+":"+nonce);System.out.flush();
  }
}
