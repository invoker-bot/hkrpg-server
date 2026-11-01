package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.gameplay.GameplayDefinitions;
import org.hkrpg.server.gameplay.GameplayState;
import org.hkrpg.server.gameplay.RogueUnlocks;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Reproduces the returned tutorial record behind the real 4.6 world-selection Lua failure. */
class UniverseTutorialCatalog46Test {
  final Gson gson = new Gson();
  final Map<Integer, GameplayDefinitions.Area> oldAreas = new HashMap<>(GameplayDefinitions.areas);
  final Map<Integer, GameplayDefinitions.RogueUnlock> oldUnlocks =
      new HashMap<>(GameplayDefinitions.rogueUnlocks);
  final Map<Integer, GameplayDefinitions.RogueRule> oldRules =
      new HashMap<>(GameplayDefinitions.rogueRules);
  final List<GameplayDefinitions.RogueSeason> oldSeasons =
      new ArrayList<>(GameplayDefinitions.rogueSeasons);
  final GameplayDefinitions.RogueSeason season = new GameplayDefinitions.RogueSeason(
      99999, 1, List.of(100, 110), "2020-01-01 04:00:00", "2099-01-01 03:59:59");

  static class Session extends Gameplay46HandlerTest.Session {
    int saves;
    final Player actor = new Player() {
      @Override public void save() { saves++; }
    };
    @Override public Player getPlayer() { return actor; }
  }

  @BeforeEach void resources() throws Exception {
    GameplayDefinitions.areas.clear();
    GameplayDefinitions.rogueUnlocks.clear();
    GameplayDefinitions.rogueRules.clear();
    GameplayDefinitions.rogueSeasons.clear();
    GameplayDefinitions.rogueSeasons.add(season);
    try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
        "/fixtures/rogue-tutorial-catalog-4.6.json")), StandardCharsets.UTF_8)) {
      var records = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
      for (var row : records.getAsJsonArray("RogueAreaConfig")) {
        var area = gson.fromJson(row, GameplayDefinitions.Area.class);
        GameplayDefinitions.areas.put(area.RogueAreaID(), area);
      }
      for (var row : records.getAsJsonArray("RogueUnlockConfig")) {
        var unlock = gson.fromJson(row, GameplayDefinitions.RogueUnlock.class);
        GameplayDefinitions.rogueUnlocks.put(unlock.RogueUnlockID(), unlock);
      }
      for (var row : records.getAsJsonArray("FinishWayRogue")) {
        var rule = gson.fromJson(row, GameplayDefinitions.RogueRule.class);
        GameplayDefinitions.rogueRules.put(rule.ID(), rule);
      }
    }
  }

  @AfterEach void restore() {
    GameplayDefinitions.areas.clear();
    GameplayDefinitions.areas.putAll(oldAreas);
    GameplayDefinitions.rogueUnlocks.clear();
    GameplayDefinitions.rogueUnlocks.putAll(oldUnlocks);
    GameplayDefinitions.rogueRules.clear();
    GameplayDefinitions.rogueRules.putAll(oldRules);
    GameplayDefinitions.rogueSeasons.clear();
    GameplayDefinitions.rogueSeasons.addAll(oldSeasons);
  }

  private Session finishedTutorial(int map) {
    var session = new Session();
    var run = new GameplayState.RogueRun(100, map, Map.of(1, map == 1 ? 100 : 201));
    run.enter(1, map == 1 ? 100 : 201);
    run.clear();
    run.finish(GameplayState.RogueRun.Phase.FINISHED);
    session.actor.getGameplay().setRogue(run);
    session.actor.getGameplay().getRogueWins().put(100, 2);
    var receipt = new GameplayState.Receipt(0);
    receipt.complete();
    session.actor.getGameplay().getRewards().put("rogue-first:100", receipt);
    complete(session.actor, 4030001);
    complete(session.actor, 4030002);
    return session;
  }

  private void complete(Player player, int mission) {
    var record = new QuestState(mission);
    assertTrue(record.advance(1, 1, 100));
    player.getGameplay().getMainMissions().put(mission, record);
  }

  private DynamicMessage query(Session session) throws Exception {
    session.request("GetRogueInfoCsReq");
    assertEquals("GetRogueInfoScRsp", session.responseName);
    assertEquals(0, Gameplay46.number(session.response, "retcode"));
    return (DynamicMessage) Gameplay46.value(session.response, "rogue_game_info");
  }

  private List<DynamicMessage> areas(DynamicMessage snapshot) {
    var basic = (DynamicMessage) Gameplay46.value(snapshot, "LFLCDNGALME");
    var catalog = (DynamicMessage) Gameplay46.value(basic, "ACLKMFCDMED");
    return ((List<?>) Gameplay46.value(catalog, "OJGOAOPLMLB")).stream()
        .map(DynamicMessage.class::cast).toList();
  }

  @ParameterizedTest @ValueSource(ints = {1, 2})
  void returnedTutorialDoesNotBecomeANonexistentWorldSelection(int map) throws Exception {
    var session = finishedTutorial(map);
    var original = gson.toJsonTree(session.actor.getGameplay());
    for (int repeat = 0; repeat < 2; repeat++) {
      var snapshot = query(session);
      var rows = areas(snapshot);
      assertEquals(List.of(110), rows.stream().map(row -> Gameplay46.number(row, "area_id")).toList(),
          "Progress 0 is absent from the 4.6 world's indexed list; it must not be a default selection");
      assertEquals("RogueAreaStatus_Lock", Gameplay46.value(rows.getFirst(), "area_status").toString());
      assertFalse(snapshot.hasField(snapshot.getDescriptorForType().findFieldByName("CFDIKLBINHK")),
          "An exited terminal tutorial must not remain the current selectable universe");
    }
    assertEquals(original, gson.toJsonTree(session.actor.getGameplay()));
    assertEquals(0, session.saves);
    assertFalse(RogueUnlocks.open(session.actor, 12001));
    assertEquals(Retcode.ROGUE_AREA_INVALID, session.actor.getUniverseManager().start(110, List.of(), 0, false));
    assertEquals(original, gson.toJsonTree(session.actor.getGameplay()));
  }

  @Test void completingTheOriginalPrerequisiteExposesTheFirstWorldWithoutGrantingFirstClear() throws Exception {
    var session = finishedTutorial(2);
    complete(session.actor, 1010403);
    assertTrue(RogueUnlocks.open(session.actor, 12001));
    var snapshot = query(session);
    assertEquals(List.of(110), areas(snapshot).stream().map(row -> Gameplay46.number(row, "area_id")).toList());
    var first = areas(snapshot).getFirst();
    assertEquals("RogueAreaStatus_Unlock", Gameplay46.value(first, "area_status").toString());
    assertEquals(false, Gameplay46.value(first, "ALAJJMKOMML"));
    assertFalse(session.actor.getRewardLedger().granted("rogue-first:110"));
    assertEquals(0, session.saves);
  }

  @ParameterizedTest @EnumSource(value = GameplayState.RogueRun.Phase.class,
      names = {"FINISHED", "FAILED", "ABANDONED"})
  void terminalTutorialHistoryIsHiddenOnlyAfterReturningOutside(GameplayState.RogueRun.Phase phase)
      throws Exception {
    var session = finishedTutorial(2);
    var run = session.actor.getGameplay().getRogue();
    run.finish(phase);
    run.setInside(true);
    var inside = query(session);
    assertEquals(List.of(100, 110), areas(inside).stream().map(row -> Gameplay46.number(row, "area_id")).toList());
    if (phase != GameplayState.RogueRun.Phase.ABANDONED)
      assertTrue(inside.hasField(inside.getDescriptorForType().findFieldByName("CFDIKLBINHK")));
    run.setInside(false);
    var outside = query(session);
    assertEquals(List.of(110), areas(outside).stream().map(row -> Gameplay46.number(row, "area_id")).toList());
    assertFalse(outside.hasField(outside.getDescriptorForType().findFieldByName("CFDIKLBINHK")));
    assertEquals(phase, run.getPhase());
    assertFalse(run.isDismissed());
  }

  @Test void activeAndPausedTutorialsKeepTheirOriginalResumeInformation() throws Exception {
    var session = finishedTutorial(2);
    var run = session.actor.getGameplay().getRogue();
    run.finish(GameplayState.RogueRun.Phase.EXPLORING);
    for (boolean inside : List.of(true, false)) {
      run.setInside(inside);
      var snapshot = query(session);
      assertEquals(List.of(100, 110), areas(snapshot).stream().map(row -> Gameplay46.number(row, "area_id")).toList());
      var current = (DynamicMessage) Gameplay46.value(snapshot, "CFDIKLBINHK");
      assertEquals("RogueStatus_Doing", Gameplay46.value(current, "status").toString());
      assertEquals(2, Gameplay46.number((DynamicMessage) Gameplay46.value(current, "COOOMDKGODF"), "map_id"));
    }
  }

  @Test void completedRegularWorldKeepsItsResultAndQuitInformation() throws Exception {
    var session = finishedTutorial(2);
    var run = new GameplayState.RogueRun(110, 101, Map.of(1, 304));
    run.enter(1, 304);
    run.finish(GameplayState.RogueRun.Phase.FINISHED);
    session.actor.getGameplay().setRogue(run);
    var snapshot = query(session);
    assertEquals(List.of(100, 110), areas(snapshot).stream().map(row -> Gameplay46.number(row, "area_id")).toList());
    var current = (DynamicMessage) Gameplay46.value(snapshot, "CFDIKLBINHK");
    assertEquals("RogueStatus_Finish", Gameplay46.value(current, "status").toString());
    assertEquals(110, Gameplay46.number((DynamicMessage) Gameplay46.value(current, "COOOMDKGODF"), "area_id"));
  }
}
