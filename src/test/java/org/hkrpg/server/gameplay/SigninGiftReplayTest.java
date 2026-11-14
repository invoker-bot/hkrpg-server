package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SigninGiftReplayTest {
  static final Instant NOW=Instant.parse("2026-11-02T12:00:00Z");
  final Gson gson=ResourceLoader.getGson();
  final List<Runnable> cleanup=new ArrayList<>();
  JsonObject records,editions;
  String profileResource(){return "/gameplay/signin-gift-replay-4.6.json";}
  static final class Actor extends Player {
    final Map<Integer,Integer> delivered=new TreeMap<>();
    final Inventory inventory=new Inventory(this){
      @Override public GrantResult grantItems(ItemParamMap values,boolean notify){
        values.forEach((id,count)->delivered.merge(id,count,Integer::sum));return new GrantResult(List.of(),true);
      }
    };
    @Override public Inventory getInventory(){return inventory;}
    @Override public void save(){}
  }
  @BeforeEach void prepare()throws Exception{
    try(var reader=new InputStreamReader(getClass().getResourceAsStream(profileResource()),StandardCharsets.UTF_8)){
      var source=JsonParser.parseReader(reader).getAsJsonObject();records=source.getAsJsonObject("records");editions=source.getAsJsonObject("editions");
    }
    replace("ActivityPanel",ActivityPanelExcel.class,GameData.getActivityPanelExcelMap());
    replace("RewardData",RewardExcel.class,GameData.getRewardExcelMap());
    replace("ItemConfig",ItemExcel.class,GameData.getItemExcelMap());
    var old=new HashMap<>(GameplayDefinitions.activities);cleanup.add(()->{GameplayDefinitions.activities.clear();GameplayDefinitions.activities.putAll(old);});
    for(var raw:records.getAsJsonArray("ActivityLoginConfig")){
      var row=gson.fromJson(raw,GameplayDefinitions.LoginActivity.class);GameplayDefinitions.activities.put(row.ID(),row);
    }
    var windows=List.copyOf(GameplayDefinitions.windows);cleanup.add(()->GameplayDefinitions.replaceActivityWindows(windows));
  }
  <T extends GameResource>void replace(String name,Class<T> type,ExcelMap<T> map){
    for(var raw:records.getAsJsonArray(name)){
      T value=gson.fromJson(raw,type);value.onLoad();int id=value.getId();T old=map.get(id);
      cleanup.add(()->{map.keySet().remove(id);if(old!=null)map.add(old);});map.add(value);
    }
  }
  @AfterEach void restore(){Collections.reverse(cleanup);cleanup.forEach(Runnable::run);}
  void window(int id,String cycle){GameplayDefinitions.replaceActivityWindows(List.of(new GameplayDefinitions.Window(
      id,id*100+1,NOW.minusSeconds(1).getEpochSecond(),NOW.plusSeconds(30*86400L).getEpochSecond(),cycle,
      cycle==null?null:editions.get(Integer.toString(id)).getAsString())));}
  Map<Integer,Integer> fullReward(int id){
    var expected=new TreeMap<Integer,Integer>();for(int reward:GameplayDefinitions.activities.get(id).RewardList())
      GameData.getRewardExcelMap().get(reward).getRewards().forEach((item,count)->expected.merge(item,count,Integer::sum));
    return expected;
  }
  void complete(Actor player,int id){
    var manager=player.getLoginActivityManager();int days=GameplayDefinitions.activities.get(id).RewardList().size();
    manager.setClock(Clock.fixed(NOW,ZoneOffset.UTC));
    assertEquals(0,manager.days(id));assertEquals(List.of(),manager.claimedDays(id));
    assertEquals(Retcode.LOGIN_ACTIVITY_DAYS_LACK,manager.claim(id,1).retcode());
    for(int day=1;day<=days;day++){
      manager.setClock(Clock.fixed(NOW.plusSeconds((day-1)*86400L),ZoneOffset.UTC));manager.recordLogin();manager.recordLogin();
      assertEquals(day,manager.days(id));assertEquals(Retcode.SUCC,manager.claim(id,day).retcode());
      assertEquals(Retcode.LOGIN_ACTIVITY_HAS_TAKEN,manager.claim(id,day).retcode());
      if(day<days)assertEquals(Retcode.LOGIN_ACTIVITY_DAYS_LACK,manager.claim(id,day+1).retcode());
    }
    assertEquals(java.util.stream.IntStream.rangeClosed(1,days).boxed().toList(),manager.claimedDays(id));
  }
  @ParameterizedTest @ValueSource(ints={10048,10051,10072,10085,10095,10131,10148})
  void originalAndTwoRerunsRetainAllSourceDaysRewardsAndOldSaves(int id){
    var player=new Actor();var main=new QuestState(1000510);main.advance(1,1,1);player.getGameplay().getMainMissions().put(1000510,main);
    var expected=fullReward(id);window(id,null);complete(player,id);assertEquals(expected,player.delivered);
    String original=gson.toJson(player.getGameplay().getActivityDays());var receipts=new HashMap<>(player.getGameplay().getRewards());
    var first=UUID.randomUUID().toString();window(id,first);complete(player,id);
    window(id,UUID.randomUUID().toString());complete(player,id);
    expected.replaceAll((item,count)->count*3);assertEquals(expected,player.delivered);
    assertEquals(original,gson.toJson(player.getGameplay().getActivityDays()));
    receipts.forEach((key,value)->assertSame(value,player.getGameplay().getRewards().get(key)));
    var saved=gson.toJson(player.getGameplay());assertEquals(saved,gson.toJson(gson.fromJson(saved,GameplayState.class)));
    window(id,first);assertEquals(Retcode.LOGIN_ACTIVITY_HAS_TAKEN,player.getLoginActivityManager().claim(id,1).retcode());
    assertEquals(expected,player.delivered);
  }
}
