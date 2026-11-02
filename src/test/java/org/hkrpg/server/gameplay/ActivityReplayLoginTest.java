package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ActivityPanelExcel;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ActivityReplayLoginTest {
  static final int PANEL=19994020,LOGIN=19994021,MODULE=19994022;
  static final Instant NOW=Instant.parse("2026-11-01T12:00:00Z");
  final Gson json=new Gson();
  List<GameplayDefinitions.Window> original;
  ActivityPanelExcel originalPanel;
  final Player player=new Player(){
    final Inventory inventory=new Inventory(this){
      @Override public GrantResult grantItems(ItemParamMap items,boolean notify){return new GrantResult(List.of(),true);}
    };
    @Override public Inventory getInventory(){return inventory;}
    @Override public void save(){}
  };
  @BeforeEach void prepare(){
    original=List.copyOf(GameplayDefinitions.windows);originalPanel=GameData.getActivityPanelExcelMap().get(PANEL);
    GameData.getActivityPanelExcelMap().add(json.fromJson("{\"PanelID\":"+PANEL+"}",ActivityPanelExcel.class));
    GameplayDefinitions.activities.put(LOGIN,new GameplayDefinitions.LoginActivity(LOGIN,List.of(0,0),MODULE));
    player.getLoginActivityManager().setClock(Clock.fixed(NOW,ZoneOffset.UTC));
  }
  @AfterEach void restore(){
    GameplayDefinitions.replaceActivityWindows(original);GameplayDefinitions.activities.remove(LOGIN);
    GameData.getActivityPanelExcelMap().keySet().remove(PANEL);if(originalPanel!=null)GameData.getActivityPanelExcelMap().add(originalPanel);
  }
  void window(String run){
    var root=new JsonObject();var rows=new JsonArray();root.add("activities",rows);var row=new JsonObject();rows.add(row);
    row.addProperty("panelId",PANEL);row.addProperty("moduleId",MODULE);row.addProperty("beginTime",NOW.minusSeconds(1).getEpochSecond());row.addProperty("endTime",NOW.plusSeconds(864000).getEpochSecond());
    if(run!=null){row.addProperty("replayId",run);row.addProperty("replayKey","1.1-gift-of-odyssey");}
    GameplayDefinitions.replaceActivityWindows(ActivitySchedules.parse(root));
  }
  @Test void twoRerunsHaveIndependentDaysAndRewardReceiptsAndNeverEraseTheOriginalSave(){
    window(null);var manager=player.getLoginActivityManager();manager.recordLogin();
    assertEquals(Retcode.SUCC,manager.claim(LOGIN,1).retcode());
    String legacyDays=json.toJson(player.getGameplay().getActivityDays());
    var legacyRewards=new HashMap<>(player.getGameplay().getRewards());
    String first=UUID.randomUUID().toString(),second=UUID.randomUUID().toString();
    window(first);assertEquals(0,manager.days(LOGIN));assertEquals(List.of(),manager.claimedDays(LOGIN));manager.recordLogin();assertEquals(1,manager.days(LOGIN));
    assertEquals(Retcode.SUCC,manager.claim(LOGIN,1).retcode());
    assertEquals(Retcode.LOGIN_ACTIVITY_HAS_TAKEN,manager.claim(LOGIN,1).retcode());
    assertEquals(List.of(1),manager.claimedDays(LOGIN));
    window(second);assertEquals(0,manager.days(LOGIN));assertEquals(List.of(),manager.claimedDays(LOGIN));manager.recordLogin();
    assertEquals(Retcode.SUCC,manager.claim(LOGIN,1).retcode());
    assertEquals(legacyDays,json.toJson(player.getGameplay().getActivityDays()));
    for(var receipt:legacyRewards.entrySet())assertSame(receipt.getValue(),player.getGameplay().getRewards().get(receipt.getKey()));
    window(first);assertEquals(1,manager.days(LOGIN));
    assertEquals(Retcode.LOGIN_ACTIVITY_HAS_TAKEN,manager.claim(LOGIN,1).retcode());
  }

  @Test void allSevenActualLoginDaysCanBeClaimedOncePerCycleAndSurviveReload(){
    GameplayDefinitions.activities.put(LOGIN,new GameplayDefinitions.LoginActivity(LOGIN,Collections.nCopies(7,0),MODULE));
    var manager=player.getLoginActivityManager();window(UUID.randomUUID().toString());
    for(int day=1;day<=7;day++){
      manager.setClock(Clock.fixed(NOW.plusSeconds((day-1)*86400L),ZoneOffset.UTC));
      manager.recordLogin();manager.recordLogin();assertEquals(day,manager.days(LOGIN));
      assertEquals(Retcode.SUCC,manager.claim(LOGIN,day).retcode());
      assertEquals(Retcode.LOGIN_ACTIVITY_HAS_TAKEN,manager.claim(LOGIN,day).retcode());
      if(day<7)assertEquals(Retcode.LOGIN_ACTIVITY_DAYS_LACK,manager.claim(LOGIN,day+1).retcode());
    }
    var saved=json.toJson(player.getGameplay());var restored=json.fromJson(saved,GameplayState.class);
    assertEquals(saved,json.toJson(restored));
    window(UUID.randomUUID().toString());assertEquals(0,manager.days(LOGIN));
    manager.recordLogin();assertEquals(Retcode.SUCC,manager.claim(LOGIN,1).retcode());
    assertEquals(8,player.getGameplay().getRewards().size());
  }
}
