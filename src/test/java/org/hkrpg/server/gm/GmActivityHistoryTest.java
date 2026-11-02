package org.hkrpg.server.gm;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ActivityPanelExcel;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.hkrpg.server.gm.GmModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class GmActivityHistoryTest {
  @TempDir Path directory;
  final Gson json=new Gson();
  final List<GameplayDefinitions.Window> original=new ArrayList<>();
  final Map<Integer,ActivityPanelExcel> panels=new HashMap<>();
  List<GmActivityHistory.Entry> entries;
  List<GmActivityCatalog.Template> catalog;
  GmActivities manager;
  @BeforeEach void prepare(){
    original.addAll(GameplayDefinitions.windows);GameplayDefinitions.replaceActivityWindows(List.of());
    entries=GmActivityHistory.entries().stream().filter(e->e.family().equals("login")&&e.modules().contains(1008001)).limit(2).toList();
    assertEquals(2,entries.size());
    catalog=entries.stream().map(e->{panels.put(e.panelId(),GameData.getActivityPanelExcelMap().get(e.panelId()));
      GameData.getActivityPanelExcelMap().add(json.fromJson("{\"PanelID\":"+e.panelId()+"}",ActivityPanelExcel.class));
      return new GmActivityCatalog.Template(e.activityId(),e.panelId(),e.name(),"event",e.modules(),Set.of(),false,"共享模块","");}).toList();
    manager=new GmActivities(directory,catalog,Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"),ZoneOffset.UTC));
  }
  @AfterEach void restore(){
    GameplayDefinitions.replaceActivityWindows(original);panels.forEach((id,row)->{GameData.getActivityPanelExcelMap().keySet().remove(id);if(row!=null)GameData.getActivityPanelExcelMap().add(row);});
  }
  Operation op(String action,GmActivityHistory.Entry entry){
    return json.fromJson("{\"requestId\":\""+UUID.randomUUID()+"\",\"action\":\"activity-"+action+"\",\"activityKey\":\""+entry.key()+"\""
        +(action.equals("rerun")?",\"durationDays\":7":",\"activityId\":"+entry.activityId())+"}",Operation.class);
  }
  @Test void sharedEditionsSwitchAsOneModuleWhileEachRerunKeepsANewPersistentIdentity() throws Exception {
    var first=entries.getFirst();var second=entries.getLast();var open=op("rerun",first);
    assertEquals("completed",GmActivityHistory.rerun(manager,open,catalog).state());
    assertEquals(open.requestId(),GameplayDefinitions.windows.getFirst().replayId());
    assertEquals(first.key(),GameplayDefinitions.windows.getFirst().replayKey());
    assertTrue(GmActivityHistory.current(manager).stream().filter(e->e.key().equals(first.key())).findFirst().orElseThrow().canStop());
    var next=op("rerun",second);GmActivityHistory.rerun(manager,next,catalog);
    assertEquals(1,GameplayDefinitions.windows.size());assertEquals(second.panelId(),GameplayDefinitions.windows.getFirst().panelId());
    manager.update(op("enable",first));assertEquals(1,GameplayDefinitions.windows.size());
    assertEquals(open.requestId(),GameplayDefinitions.windows.getFirst().replayId());
    manager.update(op("stop",first));assertTrue(GameplayDefinitions.windows.isEmpty());
    ActivitySchedules.load(directory.resolve("Gameplay46.json"));assertTrue(GameplayDefinitions.windows.isEmpty());
    var root=JsonParser.parseString(Files.readString(directory.resolve("Gameplay46.json"))).getAsJsonObject();
    assertEquals(2,root.getAsJsonArray("activityReplayRuns").size());
  }
  @Test void staleEditionAndUnknownHistoryCannotStopOrReopenAnotherCycle() {
    var first=entries.getFirst();var open=op("rerun",first);GmActivityHistory.rerun(manager,open,catalog);
    assertEquals(409,assertThrows(GmError.class,()->GmActivityHistory.rerun(manager,open,catalog)).status);
    assertThrows(GmError.class,()->manager.update(op("stop",entries.getLast())));
    assertEquals(open.requestId(),GameplayDefinitions.windows.getFirst().replayId());
    assertEquals(31,GmActivityHistory.versions().size());
    assertThrows(GmError.class,()->GmActivityHistory.entry("4.7-does-not-exist"));
  }
}
