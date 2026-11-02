package org.hkrpg.server.gm;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ActivityPanelExcel;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.*;
import org.hkrpg.server.gm.GmModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class GmActivitiesTest {
  @TempDir Path directory;
  static final int PANEL=19994011, ACTIVITY=19994012, LOGIN=19994013;
  static final Instant NOW=Instant.parse("2026-11-01T12:00:00Z");
  final Gson json=new Gson();
  final List<GmActivityCatalog.Template> catalog=List.of(
      new GmActivityCatalog.Template(ACTIVITY,PANEL,"往期活动","event",List.of(11,12),Set.of(12),true,"保留进度",""),
      new GmActivityCatalog.Template(ACTIVITY+1,PANEL,"独立活动","resident",List.of(13),Set.of(13),true,"保留进度",""),
      new GmActivityCatalog.Template(ACTIVITY+2,PANEL,"共享活动","event",List.of(14),Set.of(),false,"共享，仅查询",""));
  List<GameplayDefinitions.Window> oldWindows;
  ActivityPanelExcel oldPanel;
  GmActivities manager;
  Path file(){return directory.resolve("Gameplay46.json");}
  Operation op(String action,String fields){return json.fromJson("{\"requestId\":\""+UUID.randomUUID()+"\",\"action\":\"activity-"+action+"\",\"activityId\":"+ACTIVITY+fields+"}",Operation.class);}
  ActivityRow row(){return manager.current().getFirst();}
  JsonObject stored() throws Exception{return JsonParser.parseString(Files.readString(file())).getAsJsonObject();}
  long backups() throws Exception {try(var paths=Files.list(directory.resolve("gm-activity-backups"))){return paths.count();}}

  @BeforeEach void prepare(){
    oldWindows=List.copyOf(GameplayDefinitions.windows);GameplayDefinitions.replaceActivityWindows(List.of());
    oldPanel=GameData.getActivityPanelExcelMap().get(PANEL);
    GameData.getActivityPanelExcelMap().add(json.fromJson("{\"PanelID\":"+PANEL+"}",ActivityPanelExcel.class));
    manager=new GmActivities(directory,catalog,Clock.fixed(NOW,ZoneOffset.UTC));
  }
  @AfterEach void restore(){
    GameplayDefinitions.replaceActivityWindows(oldWindows);GameplayDefinitions.activities.remove(LOGIN);
    GameData.getActivityPanelExcelMap().keySet().remove(PANEL);
    if(oldPanel!=null)GameData.getActivityPanelExcelMap().add(oldPanel);
  }

  @Test void openStopRestoreAndRestartUseTheSameFullModuleWindow() throws Exception {
    assertEquals("unconfigured",row().state());
    manager.update(op("open",",\"durationDays\":7"));
    var original=List.copyOf(GameplayDefinitions.windows);
    assertEquals(2,original.size());assertEquals(List.of(11,12),original.stream().map(GameplayDefinitions.Window::moduleId).toList());
    assertEquals(NOW.getEpochSecond(),row().beginTime());assertEquals(NOW.plusSeconds(7*86400).getEpochSecond(),row().endTime());
    manager.update(op("stop",""));
    assertTrue(GameplayDefinitions.windows.isEmpty());assertEquals("stopped",row().state());assertTrue(row().canEnable());
    assertTrue(stored().getAsJsonArray("activities").get(0).getAsJsonObject().get("disabled").getAsBoolean());
    GameplayDefinitions.replaceActivityWindows(original);ActivitySchedules.load(file());
    assertTrue(GameplayDefinitions.windows.isEmpty());
    manager.update(op("enable",""));assertEquals(original,GameplayDefinitions.windows);assertEquals("active",row().state());
    assertEquals(3,backups());
    manager.update(op("enable",""));assertEquals(3,backups());
    GameplayDefinitions.replaceActivityWindows(List.of());manager.update(op("enable",""));
    assertEquals(original,GameplayDefinitions.windows);assertEquals(3,backups());
  }

  @Test void futureWindowUsesInclusiveStartExclusiveEndAndExpiredEnableCannotExtendIt() {
    long begin=NOW.plusSeconds(60).getEpochSecond(),end=begin+120;
    manager.update(op("open",",\"beginTime\":"+begin+",\"endTime\":"+end));
    var player=new Player();
    assertEquals("scheduled",row().state());assertTrue(ActivityAccess.active(player,NOW).isEmpty());
    assertEquals(2,ActivityAccess.active(player,Instant.ofEpochSecond(begin)).size());
    assertEquals(2,ActivityAccess.active(player,Instant.ofEpochSecond(end-1)).size());
    assertTrue(ActivityAccess.active(player,Instant.ofEpochSecond(end)).isEmpty());
    var expired=new GmActivities(directory,catalog,Clock.fixed(Instant.ofEpochSecond(end),ZoneOffset.UTC));
    assertEquals("expired",expired.current().getFirst().state());
    assertEquals(409,assertThrows(GmError.class,()->expired.update(op("enable",""))).status);
    var before=List.copyOf(GameplayDefinitions.windows);
    assertThrows(GmError.class,()->manager.update(op("open",",\"beginTime\":1,\"endTime\":2")));
    assertEquals(before,GameplayDefinitions.windows);
  }

  @Test void originalUnlockAndRewardReceiptsSurviveCloseAndReopen() {
    var player=new Player(){
      final Inventory inventory=new Inventory(this){@Override public GrantResult grantItems(ItemParamMap items,boolean hint){return new GrantResult(List.of(),true);}};
      @Override public Inventory getInventory(){return inventory;}
      @Override public void save(){}
    };
    GameplayDefinitions.activities.put(LOGIN,new GameplayDefinitions.LoginActivity(LOGIN,List.of(0),11));
    player.getLoginActivityManager().setClock(Clock.fixed(NOW,ZoneOffset.UTC));
    manager.update(op("open",""));player.getLoginActivityManager().recordLogin();
    assertEquals(Retcode.SUCC,player.getLoginActivityManager().claim(LOGIN,1).retcode());
    String progress=json.toJson(player.getGameplay());
    manager.update(op("stop",""));assertTrue(player.getLoginActivityManager().activeIds().isEmpty());
    assertEquals(Retcode.REQ_PARA_INVALID,player.getLoginActivityManager().claim(LOGIN,1).retcode());
    manager.update(op("open",",\"durationDays\":7"));player.getLoginActivityManager().recordLogin();
    assertEquals(progress,json.toJson(player.getGameplay()));
    assertEquals(Retcode.LOGIN_ACTIVITY_HAS_TAKEN,player.getLoginActivityManager().claim(LOGIN,1).retcode());
    GameData.getActivityPanelExcelMap().add(json.fromJson("{\"PanelID\":"+PANEL+",\"UnlockConditions\":\"[PlayerLevel:80]\"}",ActivityPanelExcel.class));
    assertTrue(player.getLoginActivityManager().activeIds().isEmpty());
    assertTrue(ActivityAccess.active(player,NOW).isEmpty());assertEquals(progress,json.toJson(player.getGameplay()));
  }

  @Test void wholeFileBackupAndUnknownFieldsPreserveOtherSchedules() throws Exception {
    String original="{\"operatorExtension\":{\"keep\":7},\"activities\":[{\"panelId\":"+PANEL
        +",\"moduleId\":11,\"beginTime\":1,\"endTime\":2,\"extra\":\"keep\"},"
        +"{\"panelId\":9,\"moduleId\":9,\"beginTime\":1,\"endTime\":2,\"note\":\"other\"}]}";
    Files.writeString(file(),original);var operation=op("open",",\"durationDays\":2");manager.update(operation);
    assertEquals(original,Files.readString(directory.resolve("gm-activity-backups/Gameplay46-"+operation.requestId()+".json")));
    var root=stored();assertEquals(7,root.getAsJsonObject("operatorExtension").get("keep").getAsInt());
    assertEquals("keep",root.getAsJsonArray("activities").get(0).getAsJsonObject().get("extra").getAsString());
    assertEquals(JsonParser.parseString(original).getAsJsonObject().getAsJsonArray("activities").get(1),root.getAsJsonArray("activities").get(1));
  }

  @Test void invalidFileReadOnlyEntryOrBackupFailureCannotMutatePublishedWindows() throws Exception {
    manager.update(op("open",""));var before=GameplayDefinitions.windows;
    String original=Files.readString(file());
    Files.writeString(file(),original.replaceFirst("\\[","[{} ,"));
    assertEquals(503,assertThrows(GmError.class,()->manager.update(op("stop",""))).status);
    assertSame(before,GameplayDefinitions.windows);
    Files.writeString(file(),original);
    var readOnly=json.fromJson("{\"requestId\":\""+UUID.randomUUID()+"\",\"action\":\"activity-open\",\"activityId\":"+(ACTIVITY+2)+"}",Operation.class);
    assertEquals(409,assertThrows(GmError.class,()->manager.update(readOnly)).status);
    var operation=op("stop","");Files.writeString(directory.resolve("gm-activity-backups/Gameplay46-"+operation.requestId()+".json"),"occupied");
    assertEquals(503,assertThrows(GmError.class,()->manager.update(operation)).status);
    assertSame(before,GameplayDefinitions.windows);assertEquals(original,Files.readString(file()));
  }

  @Test void journalReplayAfterCloseAndJournalReloadCannotReopenTheActivity() throws Exception {
    var journal=new GmJournal(directory,Clock.fixed(NOW,ZoneOffset.UTC));
    GmBackend backend=new GmBackend(){
      public Status status(){return null;}public List<PlayerRow> players(){return List.of();}
      public List<CatalogItem> catalog(){return List.of();}public List<CommandRow> commands(){return List.of();}
      public Result execute(Operation op){return manager.update(op);}
    };
    var open=op("open","");assertEquals("completed",journal.execute(open,backend).state());
    assertEquals("completed",journal.execute(op("stop",""),backend).state());
    assertEquals("completed",new GmJournal(directory,Clock.fixed(NOW,ZoneOffset.UTC)).execute(open,backend).state());
    assertTrue(GameplayDefinitions.windows.isEmpty());assertEquals("stopped",row().state());assertEquals(2,backups());
  }

  @Test void simultaneousUpdatesSerializeWithoutLosingUnrelatedModulesOrMutatingOldSnapshots() throws Exception {
    manager.update(op("open",""));var old=GameplayDefinitions.windows;
    var executor=Executors.newFixedThreadPool(2);
    try{
      var stop=executor.submit(()->manager.update(op("stop","")));
      var other=executor.submit(()->manager.update(json.fromJson("{\"requestId\":\""+UUID.randomUUID()+"\",\"action\":\"activity-open\",\"activityId\":"+(ACTIVITY+1)+"}",Operation.class)));
      stop.get(10,TimeUnit.SECONDS);other.get(10,TimeUnit.SECONDS);
      assertEquals(List.of(13),GameplayDefinitions.windows.stream().map(GameplayDefinitions.Window::moduleId).toList());
      assertEquals(List.of(11,12),old.stream().map(GameplayDefinitions.Window::moduleId).toList());
      ActivitySchedules.load(file());assertEquals(1,GameplayDefinitions.windows.size());
    }finally{executor.shutdownNow();}
  }
}
