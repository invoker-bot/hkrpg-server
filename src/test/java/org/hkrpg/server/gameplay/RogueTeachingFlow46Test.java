package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.quest.QuestState;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import emu.lunarcore.server.packet.Retcode;

/** Original handoff scripts and real room anchors; saves/scene transport are the only doubles. */
class RogueTeachingFlow46Test {
  final Gson gson = ResourceLoader.getGson();
  final RogueRoomSpawn46Test geometry = new RogueRoomSpawn46Test();
  final List<MainMissionExcel> oldMains = new ArrayList<>();
  final Map<Integer, MissionInfo> oldHeaders = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  final Map<Integer, List<Integer>> oldPools = new HashMap<>();
  JsonObject source;
  RogueRoomSpawn46Test.Actor player;

  @BeforeEach void load() throws Exception {
    geometry.prepare();
    oldMains.addAll(GameData.getMainMissionExcelMap().values());
    oldHeaders.putAll(GameData.getMissionInfos()); oldSubs.putAll(GameplayDefinitions.subs);
    oldPools.putAll(GameplayDefinitions.roomPool);
    GameData.getMainMissionExcelMap().keySet().clear();
    GameData.getMissionInfos().clear(); GameplayDefinitions.subs.clear();
    try (var input = getClass().getResourceAsStream("/fixtures/rogue-teaching-flow-4.6.json")) {
      source = JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),
          StandardCharsets.UTF_8)).getAsJsonObject();
    }
    for (var raw : source.getAsJsonObject("mains").entrySet())
      GameData.getMainMissionExcelMap().add(gson.fromJson(raw.getValue(), MainMissionExcel.class));
    for (var raw : source.getAsJsonObject("missions").entrySet()) {
      var header = gson.fromJson(raw.getValue(), MissionInfo.class);
      GameData.getMissionInfos().put(header.getMainMissionID(), header);
      for (var sub : header.getSubMissionList()) {
        var script = source.getAsJsonObject("scripts").get(Integer.toString(sub.getId()));
        if (script != null) {
          sub.setTask(gson.fromJson(script, MissionTaskInfo.class));
          sub.getTask().retainSourceDefinition(script);
        }
        GameplayDefinitions.subs.put(sub.getId(), sub);
      }
    }
    for (int map : List.of(3, 101)) {
      var sites = new ArrayList<GameplayDefinitions.Site>();
      for (var raw : source.getAsJsonArray("maps")) {
        var site = gson.fromJson(raw, GameplayDefinitions.Site.class);
        if (site.RogueMapID() == map) sites.add(site);
      }
      GameplayDefinitions.maps.put(map, sites);
    }
    for (int i = 1; i <= 7; i++) GameplayDefinitions.roomPool.put(i, List.of(303 + i % 5));
    GameplayDefinitions.roomPool.put(1, List.of(304));
    player = new RogueRoomSpawn46Test.Actor();
    doneMain(4030001); doneMain(4030002); doneMain(1010403);
    doneSub(403000222);
    player.getGameplay().getMainMissions().put(4010116, new QuestState(4010116));
    player.getGameplay().getSubMissions().put(401011603, new QuestState(401011603));
    var run = new GameplayState.RogueRun(110, 101,
        Map.of(1,304,2,302,3,303,4,304,5,305,6,306,7,307));
    run.setupTeam(player, List.of(199961)); run.enter(3, 303);
    run.getCleared().addAll(Set.of(1,2));
    player.getGameplay().setRogue(run);
    assertTrue(player.enterSceneAtAnchor(8000102, 34, 1, false));
  }

  @AfterEach void restore() {
    GameData.getMainMissionExcelMap().keySet().clear(); oldMains.forEach(GameData.getMainMissionExcelMap()::add);
    GameData.getMissionInfos().clear(); GameData.getMissionInfos().putAll(oldHeaders);
    GameplayDefinitions.subs.clear(); GameplayDefinitions.subs.putAll(oldSubs);
    GameplayDefinitions.roomPool.clear(); GameplayDefinitions.roomPool.putAll(oldPools);
    geometry.restore();
  }

  void doneMain(int id) { var s = new QuestState(id); s.advance(1,1,1);
    player.getGameplay().getMainMissions().put(id,s); }
  void doneSub(int id) { var s = new QuestState(id); s.advance(1,1,1);
    player.getGameplay().getSubMissions().put(id,s); }
  void introCompleted() {
    doneMain(4010116);
    player.getGameplay().getMainMissions().put(4030003, new QuestState(4030003));
    doneSub(403000300); doneSub(403000301);
  }

  @Test void savedRoamingCompletionFinishesOnlyTheAutomaticHandoff() {
    var run = player.getGameplay().getRogue(); var before = gson.toJsonTree(run);
    player.getMissionManager().refresh();
    assertTrue(MissionStateRecords.mainCompleted(player,4010116), "Automatic original handoff stayed DOING");
    assertEquals(1,player.getGameplay().getStoryValues().get("mission:4010116:OldValue_0"));
    assertTrue(MissionStateRecords.mainDoing(player,4030003));
    assertTrue(MissionStateRecords.doing(player.getMissionManager().sub(403000300),403000300,1));
    assertNull(player.getMissionManager().sub(403000304), "Unplayed introduction must not be invented");
    assertSame(run,player.getGameplay().getRogue()); assertEquals(before,gson.toJsonTree(run));
  }

  @Test void firstWorldTeachingUsesTheAuthoredSevenRoomsInsteadOfTheOrdinaryWorldPool() {
    var assignment = RogueMapPools.assign(3,10001,110).orElseThrow();
    assertEquals(Map.of(1,301,2,302,3,303,4,304,5,305,6,306,7,307),assignment.rooms());
  }

  @Test void earnedLegacyRestRoomEntryActivatesTheOriginalHertaTeachingAfterTheIntroduction() {
    introCompleted();
    var before = gson.toJsonTree(player.getGameplay().getRogue());
    player.getMissionManager().refresh();
    assertTrue(MissionStateRecords.subCompleted(player,403000303),"Earned rest-room arrival was ignored");
    assertTrue(MissionStateRecords.doing(player.getMissionManager().sub(403000304),403000304,1));
    assertEquals(before,gson.toJsonTree(player.getGameplay().getRogue()));
  }

  @Test void repeatedRefreshAndDocumentRestoreDoNotRepeatOrChooseAnotherBranch() {
    player.getMissionManager().refresh();
    var before = gson.toJsonTree(player.getGameplay().getSubMissions());
    var values = Map.copyOf(player.getGameplay().getStoryValues());
    var copy = gson.fromJson(gson.toJsonTree(player.getGameplay()),GameplayState.class);
    player.getGameplay().getSubMissions().clear(); player.getGameplay().getSubMissions().putAll(copy.getSubMissions());
    for (int i=0;i<3;i++) player.getMissionManager().refresh();
    assertEquals(before,gson.toJsonTree(player.getGameplay().getSubMissions()));
    assertEquals(values,player.getGameplay().getStoryValues());
    assertNull(player.getMissionManager().sub(401011604));
  }

  @Test void theOriginalFalseBranchAlsoRunsButWaitsForActualRoamingCompletion() {
    player.getGameplay().getMainMissions().put(4030002,new QuestState(4030002));
    player.getGameplay().getSubMissions().remove(403000222);
    player.getMissionManager().refresh();
    assertTrue(MissionStateRecords.subCompleted(player,401011603));
    assertTrue(MissionStateRecords.subCompleted(player,401011604));
    assertEquals(2,player.getGameplay().getStoryValues().get("mission:4010116:OldValue_0"));
    assertFalse(MissionStateRecords.mainCompleted(player,4010116));
    assertNull(player.getMissionManager().sub(401011601));
    doneMain(4030002);
    player.getMissionManager().refresh();
    assertTrue(MissionStateRecords.mainCompleted(player,4010116));
    assertTrue(MissionStateRecords.mainDoing(player,4030003));
  }

  @ParameterizedTest @ValueSource(strings={"unfinished-main","cancel-main","cancel-goal","bad-goal-record",
      "bad-prerequisite-record","changed-script","missing-script","changed-main","changed-header","changed-row","conflicting-value"})
  void automaticHandoffRejectsUnprovenOrChangedSources(String change) {
    switch(change) {
      case "unfinished-main" -> player.getGameplay().getMainMissions().put(1010403,new QuestState(1010403));
      case "cancel-main" -> player.getGameplay().getCancelledMissions().add(4010116);
      case "cancel-goal" -> player.getGameplay().getCancelledSubs().add(401011603);
      case "bad-goal-record" -> player.getGameplay().getSubMissions().put(401011603,new QuestState(401011604));
      case "bad-prerequisite-record" -> player.getGameplay().getSubMissions().put(403000222,
          gson.fromJson("{\"questId\":403000222,\"phase\":\"FINISHED\",\"progress\":1,\"finishTime\":0}",QuestState.class));
      case "changed-script" -> GameplayDefinitions.subs.get(401011603).getTask().retainSourceDefinition(JsonParser.parseString("{\"Type\":\"Mission\"}"));
      case "missing-script" -> GameplayDefinitions.subs.get(401011601).setTask(null);
      case "changed-main" -> {
        var raw = source.getAsJsonObject("mains").getAsJsonObject("4010116").deepCopy();
        raw.add("TakeParam",JsonParser.parseString("[{\"Type\":\"Auto\"}]"));
        GameData.getMainMissionExcelMap().add(gson.fromJson(raw,MainMissionExcel.class));
      }
      case "changed-header" -> {
        var raw = source.getAsJsonObject("missions").getAsJsonObject("4010116").deepCopy();
        raw.add("FinishSubMissionList",JsonParser.parseString("[401011603]"));
        GameData.getMissionInfos().put(4010116,gson.fromJson(raw,MissionInfo.class));
      }
      case "changed-row" -> GameplayDefinitions.subs.get(401011603).setParamStr1("changed");
      case "conflicting-value" -> player.getGameplay().getStoryValues().put("mission:4010116:OldValue_0",2);
    }
    player.getMissionManager().refresh();
    assertFalse(MissionStateRecords.subCompleted(player,401011603));
    assertNull(player.getGameplay().getMainMissions().get(4030003));
  }

  @Test void automaticSaveFailureRestoresTheGoalAndVariableThenCanRetry() {
    var state = player.getMissionManager().sub(401011603);
    player.failSave = true;
    assertThrows(IllegalStateException.class,()->player.getMissionManager().refresh());
    assertSame(state,player.getMissionManager().sub(401011603));
    assertTrue(MissionStateRecords.doing(state,401011603,1));
    assertFalse(player.getGameplay().getStoryValues().containsKey("mission:4010116:OldValue_0"));
    player.failSave = false; player.getMissionManager().refresh();
    assertTrue(MissionStateRecords.mainCompleted(player,4010116));
  }

  @Test void aClientCannotForgeTheAutomaticBranchValue() {
    player.plane=10102; player.floor=10102001;
    assertEquals(Retcode.MISSION_FINISH_WAY_NOT_MATCH,
        player.getMissionManager().finishTalk(401011603,"Mission_401011603",Map.of(0,2)));
    assertTrue(MissionStateRecords.doing(player.getMissionManager().sub(401011603),401011603,1));
    assertTrue(player.getGameplay().getStoryValues().isEmpty());
  }

  @ParameterizedTest @ValueSource(strings={"no-intro","not-arrived","missing-clear","paused","wrong-map","changed-room","wrong-plane","changed-goal","cancelled"})
  void hertaTeachingRequiresTheOriginalIntroductionAndEarnedArrival(String change) {
    introCompleted();
    switch(change) {
      case "no-intro" -> player.getGameplay().getSubMissions().put(403000300,new QuestState(403000300));
      case "not-arrived" -> player.getGameplay().getRogue().enter(2,302);
      case "missing-clear" -> player.getGameplay().getRogue().getCleared().remove(1);
      case "paused" -> player.getGameplay().getRogue().setInside(false);
      case "wrong-map" -> { var run=new GameplayState.RogueRun(110,999,Map.of(3,303));
        run.setupTeam(player,List.of(199961));run.enter(3,303);run.getCleared().addAll(Set.of(1,2));player.getGameplay().setRogue(run); }
      case "changed-room" -> GameplayDefinitions.rooms.put(303,new GameplayDefinitions.Room(303,5,8000102,35));
      case "wrong-plane" -> player.plane=80002;
      case "changed-goal" -> GameplayDefinitions.subs.get(403000303).setParamInt1(101);
      case "cancelled" -> player.getGameplay().getCancelledSubs().add(403000303);
    }
    player.getMissionManager().refresh();
    assertFalse(MissionStateRecords.subCompleted(player,403000303));
    assertNull(player.getMissionManager().sub(403000304));
  }

  @Test void authoredTeachingMapAlsoActivatesHertaWithoutCompletingTheConversation() {
    introCompleted();
    var run = new GameplayState.RogueRun(110,3,Map.of(1,301,2,302,3,303,4,304,5,305,6,306,7,307));
    run.setupTeam(player,List.of(199961));run.enter(3,303);run.getCleared().addAll(Set.of(1,2));
    player.getGameplay().setRogue(run);player.getMissionManager().refresh();
    assertTrue(MissionStateRecords.subCompleted(player,403000303));
    assertTrue(MissionStateRecords.doing(player.getMissionManager().sub(403000304),403000304,1));
  }

  @Test void explicitOperatorPoolIsNotSilentlyOverwritten() {
    GameplayDefinitions.roomPoolByMap.put(3,Map.of(1,List.of(304)));
    assertEquals(304,RogueMapPools.assign(3,10001,110).orElseThrow().rooms().get(1));
  }

  @Test void nativeStartUsesTheTeachingMapOnlyAfterTheRealOfficeIntroduction() {
    introCompleted();
    player.getGameplay().setRogue(new GameplayState.RogueRun());
    GameplayDefinitions.areas.put(110,new GameplayDefinitions.Area(110,1,1,0,0));
    GameData.getMazePlaneExcelMap().add(gson.fromJson("{\"PlaneID\":80112,\"PlaneType\":\"Town\"}",MazePlaneExcel.class));
    assertTrue(player.loadScene(80112,80112001,8011201,new emu.lunarcore.util.Position(),new emu.lunarcore.util.Position(),false));
    assertEquals(Retcode.SUCC,player.getUniverseManager().start(110));
    assertEquals(3,player.getGameplay().getRogue().getMapId());
    assertEquals(301,player.getGameplay().getRogue().getRoomId());
    assertEquals(8000102,player.getEntryId());
    geometry.atRoom(player,301);
  }
}
