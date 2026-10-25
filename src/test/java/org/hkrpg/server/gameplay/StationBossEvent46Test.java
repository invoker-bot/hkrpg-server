package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The original boss script names a missing event; its StageWin names the existing event family. */
class StationBossEvent46Test {
  @TempDir Path root;
  final OpeningStageWin46Test f = new OpeningStageWin46Test();
  static final int MAIN=1000400, SUB=100040110, LEGACY=20003012;
  emu.lunarcore.data.config.FloorInfo oldFloor;

  @BeforeEach void prepare() throws Exception {
    f.temp=root; f.load();
    try(var input=getClass().getResourceAsStream("/fixtures/station-boss-event-4.6.json")) {
      var fixture=JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
      for(var raw:fixture.getAsJsonArray("records")) {
        var record=raw.getAsJsonObject();var row=record.getAsJsonObject("row");
        switch(record.get("kind").getAsString()) {
          case "file" -> { var file=root.resolve(record.get("source").getAsString());Files.createDirectories(file.getParent());Files.writeString(file,row.toString()); }
          case "MainMission" -> GameData.getMainMissionExcelMap().add(f.gson.fromJson(row,MainMissionExcel.class));
          case "PlaneEvent" -> GameData.getPlaneEventExcelMap().add(f.gson.fromJson(row,PlaneEventExcel.class));
          case "StageConfig" -> { var stage=f.gson.fromJson(row,StageExcel.class);stage.onLoad();GameData.getStageExcelMap().add(stage); }
          default -> fail("Unknown original fixture row");
        }
      }
    }
    GameplayDefinitions.load(root);
    f.actor=new OpeningStageWin46Test.Actor() { @Override public int getEntryId(){return 2000301;} };
    f.actor.floor=20003001;
    // This battle-focused fixture has no world entities; full floor transitions run in the native probe.
    var floor=f.gson.fromJson("{\"FloorID\":20003001}",emu.lunarcore.data.config.FloorInfo.class);
    MissionTrialProvisioning46Test.setField(floor,emu.lunarcore.data.config.FloorInfo.class,"loaded",true);
    oldFloor=GameData.getFloorInfos().put("P20003_F20003001",floor);
    var plane=f.gson.fromJson("{\"PlaneID\":20003,\"PlaneType\":\"Maze\",\"WorldID\":100}",MazePlaneExcel.class);
    MissionTrialProvisioning46Test.set(f.actor,"scene",new Scene(f.actor,plane,20003001));
    f.actor.getGameplay().getMainMissions().put(MAIN,new QuestState(MAIN));
    var prior=new QuestState(100040109);prior.advance(1,1,100);
    f.actor.getGameplay().getSubMissions().put(100040109,prior);
    f.actor.getGameplay().getSubMissions().put(SUB,new QuestState(SUB));
    assertNull(GameData.getPlaneEventExcelMap().get(LEGACY,0));
    assertTrue(f.actor.getMissionManager().canFinishFromAction(SUB));
  }

  @AfterEach void restore(){f.restore();if(oldFloor==null)GameData.getFloorInfos().remove("P20003_F20003001");else GameData.getFloorInfos().put("P20003_F20003001",oldFloor);}

  @ParameterizedTest @ValueSource(ints={0,1,6})
  void originalBossEventEntersTheConfiguredWorldAndCompletesOnlyOnce(int world) throws Exception {
    f.actor.world=world;
    var session=f.new Session(f.actor);
    var result=session.request("SceneEnterStageCsReq",Map.of("event_id",LEGACY));
    assertEquals(0,OpeningStageWin46Test.field(result,"retcode"),"The authored boss request must be accepted");
    var battle=Objects.requireNonNull(f.actor.getBattle());
    assertEquals(200030110+world,battle.getStage().getId());
    assertNotNull(f.finish(battle,BattleEndStatus.BATTLE_END_WIN));
    assertEquals(QuestState.Phase.FINISHED,f.actor.getMissionManager().sub(SUB).getPhase());
    assertEquals(QuestState.Phase.DOING,f.actor.getMissionManager().sub(100040111).getPhase());
    assertNull(f.finish(battle,BattleEndStatus.BATTLE_END_WIN));
    assertNull(f.service.startEventBattle(f.actor,LEGACY),"Finished boss cannot be restarted through its legacy alias");
  }

  @Test void losingKeepsTheOriginalGoalRetryable() {
    var battle=Objects.requireNonNull(f.service.startEventBattle(f.actor,LEGACY));
    assertNotNull(f.finish(battle,BattleEndStatus.BATTLE_END_LOSE));
    assertEquals(QuestState.Phase.DOING,f.actor.getMissionManager().sub(SUB).getPhase());
    assertNotNull(f.service.startEventBattle(f.actor,LEGACY));
  }

  @ParameterizedTest @ValueSource(strings={"before","cancel","script","target","floor","raid","world","missingStage"})
  void aliasDoesNotBecomeAnUnconditionalBattleBypass(String defect) throws Exception {
    switch(defect) {
      case "before" -> f.actor.getGameplay().getSubMissions().put(100040109,new QuestState(100040109));
      case "cancel" -> f.actor.getGameplay().getCancelledSubs().add(SUB);
      case "script" -> GameplayDefinitions.subs.get(SUB).getTask().retainSourceDefinition(new JsonObject());
      case "target" -> MissionTrialProvisioning46Test.setField(GameplayDefinitions.subs.get(SUB),emu.lunarcore.data.config.MissionInfo.SubMissionInfo.class,"paramInt1",20001001);
      case "floor" -> f.actor.floor=20001001;
      case "raid" -> MissionTrialProvisioning46Test.setField(f.actor.getGameplay(),GameplayState.class,"currentMissionRaid",123);
      case "world" -> f.actor.world=9;
      case "missingStage" -> GameData.getStageExcelMap().keySet().remove(200030110);
    }
    assertNull(f.service.startEventBattle(f.actor,LEGACY));
    assertNull(f.actor.getBattle());
  }
}
