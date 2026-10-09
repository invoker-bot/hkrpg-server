package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Authored placement targets; prior unlock levels and accepted story are explicit fixtures. */
class MatchThreeBirdPosition46Test {
  @TempDir Path root;
  final MatchThree46Test f = new MatchThree46Test();
  final Gson gson = new Gson();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  JsonObject source;

  @BeforeEach
  void setup() throws Exception {
    oldSubs.putAll(GameplayDefinitions.subs);
    f.root = root;
    f.setup();
    source = read("match-three-bird-position-4.6.json");
    copy(source);
    MatchThreeCatalog.load(root);
    // These previously passed levels are prerequisites, not the placement targets under test.
    for (int id : List.of(1100, 1200, 1300))
      player()
          .getGameplay()
          .getMatchThree()
          .getCompleted()
          .put("1:" + id, new GameplayState.MatchThreeLevel(id, 1));
    int gate = MatchThreeCatalog.constants.get("MatchThree_MainPageUnlock_SubMissionID");
    var done = new QuestState(gate);
    done.advance(1, 1, 1);
    player().getGameplay().getSubMissions().put(gate, done);
    GameplayDefinitions.subs.clear();
    place(10341, 10341001);
  }

  JsonObject read(String name) throws Exception {
    try (var input = getClass().getResourceAsStream("/fixtures/" + name)) {
      assertNotNull(input);
      return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
  }

  void copy(JsonObject fixture) throws Exception {
    for (var e : fixture.getAsJsonObject("files").entrySet()) {
      var p = root.resolve(e.getKey());
      Files.createDirectories(p.getParent());
      Files.write(
          p, HexFormat.of().parseHex(e.getValue().getAsJsonObject().get("rawHex").getAsString()));
    }
  }

  @AfterEach
  void cleanup() {
    f.cleanup();
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
  }

  Player player() {
    return f.session.player;
  }

  void place(int plane, int floor) throws Exception {
    for (var e : Map.of("planeId", plane, "floorId", floor).entrySet()) {
      var field = Player.class.getDeclaredField(e.getKey());
      field.setAccessible(true);
      field.setInt(player(), e.getValue());
    }
  }

  MissionInfo.SubMissionInfo target(int id) {
    return source.getAsJsonArray("rows").asList().stream()
        .filter(v -> v.getAsJsonObject().get("ID").getAsInt() == id)
        .map(v -> gson.fromJson(v, MissionInfo.SubMissionInfo.class))
        .findFirst()
        .orElseThrow();
  }

  void accept(MissionInfo.SubMissionInfo c) {
    GameplayDefinitions.subs.put(c.getId(), c);
    player()
        .getGameplay()
        .getMainMissions()
        .put(c.getMainMissionID(), new QuestState(c.getMainMissionID()));
    player().getGameplay().getSubMissions().put(c.getId(), new QuestState(c.getId()));
  }

  void position(int bird, int pos) throws Exception {
    assertEquals(0, Gameplay46.number(f.position(bird, pos), "retcode"));
  }

  @ParameterizedTest
  @ValueSource(ints = {802310205, 802310206, 802310305, 802310405, 802310506})
  void originalNativePlacementCompletesItsAuthoredTargetAndActionsOnce(int id) throws Exception {
    var c = target(id);
    accept(c);
    position(c.getParamInt3(), c.getParamInt2());
    var q = player().getMissionManager().sub(id);
    assertEquals(QuestState.Phase.FINISHED, q.getPhase());
    assertEquals(1, q.getProgress());
    assertTrue(q.getFinishTime() > 0);
    if (id == 802310206 || id == 802310305)
      assertEquals(
          id == 802310206 ? 2 : 3,
          player().getGameplay().getStoryValues().get("floor:10341001:Activity230DIYEntrance"));
    long time = q.getFinishTime();
    var saved = gson.toJson(player().getGameplay());
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(player(), gson.fromJson(saved, GameplayState.class));
    position(c.getParamInt3(), c.getParamInt2());
    assertEquals(time, player().getMissionManager().sub(id).getFinishTime());
    assertEquals(saved, gson.toJson(player().getGameplay()));
    assertEquals(c.getParamInt2(), Gameplay46.number(f.bird(c.getParamInt3()), "pos"));
    assertEquals(0, f.session.player.calls);
  }

  @ParameterizedTest
  @ValueSource(ints = {802310205, 802310206, 802310305, 802310405, 802310506})
  void earlierSavedPlacementDoesNotBacktrackButAnAcceptedRetryCanFinish(int id) throws Exception {
    var c = target(id);
    assertFalse(c.isBackTrack());
    position(c.getParamInt3(), c.getParamInt2());
    accept(c);
    player().getMissionManager().refresh();
    assertEquals(QuestState.Phase.DOING, player().getMissionManager().sub(id).getPhase());
    position(c.getParamInt3(), c.getParamInt2());
    assertEquals(QuestState.Phase.FINISHED, player().getMissionManager().sub(id).getPhase());
  }

  @ParameterizedTest
  @ValueSource(ints = {802310205, 802310206, 802310305, 802310405, 802310506})
  void wrongBirdWrongNestAndRemovalCannotFinishTheTarget(int id) throws Exception {
    var c = target(id);
    accept(c);
    int wrongNest = c.getParamInt2() == 6 ? 5 : 6;
    position(c.getParamInt3(), wrongNest);
    position(c.getParamInt3(), 0);
    position(310, c.getParamInt2());
    assertEquals(QuestState.Phase.DOING, player().getMissionManager().sub(id).getPhase());
    assertEquals(0, player().getMissionManager().sub(id).getProgress());
    assertTrue(player().getGameplay().getStoryValues().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "plane",
        "floor",
        "main-cancelled",
        "sub-cancelled",
        "main-finished",
        "not-accepted"
      })
  void onlyAcceptedCurrentStoryConsumesThePlacement(String mode) throws Exception {
    var c = target(802310206);
    accept(c);
    switch (mode) {
      case "plane" -> place(10342, 10341001);
      case "floor" -> place(10341, 10341002);
      case "main-cancelled" ->
          player().getGameplay().getCancelledMissions().add(c.getMainMissionID());
      case "sub-cancelled" -> player().getGameplay().getCancelledSubs().add(c.getId());
      case "main-finished" ->
          player().getGameplay().getMainMissions().get(c.getMainMissionID()).advance(1, 1, 1);
      case "not-accepted" -> player().getGameplay().getSubMissions().remove(c.getId());
      default -> throw new IllegalArgumentException();
    }
    position(c.getParamInt3(), c.getParamInt2());
    var q = player().getMissionManager().sub(c.getId());
    assertTrue(q == null || q.getPhase() == QuestState.Phase.DOING);
    assertTrue(player().getGameplay().getStoryValues().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "id",
        "parent",
        "type",
        "param-type",
        "operation",
        "nest",
        "bird",
        "progress",
        "backtrack",
        "plane",
        "floor",
        "actions"
      })
  void alteredAuthoredConditionNeverConsumesTheNativePlacement(String mode) throws Exception {
    var c = target(802310206);
    switch (mode) {
      case "id" -> c.setId(199973001);
      case "parent" -> c.setMainMissionID(199973);
      case "type" -> c.setFinishType("Auto");
      case "param-type" -> c.setParamType("GreaterEqual");
      case "operation" -> c.setParamInt1(1);
      case "nest" -> c.setParamInt2(3);
      case "bird" -> c.setParamInt3(310);
      case "progress" -> c.setProgress(2);
      case "backtrack" -> c.setBackTrack(true);
      case "plane" -> c.setLevelPlaneID(10342);
      case "floor" -> c.setLevelFloorID(10341002);
      case "actions" -> c.setFinishActionList(new MissionInfo.FinishAction[0]);
      default -> throw new IllegalArgumentException();
    }
    accept(c);
    position(301, 1);
    assertEquals(QuestState.Phase.DOING, player().getMissionManager().sub(c.getId()).getPhase());
    assertTrue(player().getGameplay().getStoryValues().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "rhythm",
        "parkour",
        "universe",
        "mission-raid",
        "clock",
        "story",
        "transition",
        "instance"
      })
  void placementWaitsForOtherOccupiedGameplayWithoutChangingItsSavedBirds(String kind)
      throws Exception {
    String unoccupied = gson.toJson(player().getGameplay());
    occupy(kind);
    String saved = gson.toJson(player().getGameplay());
    int saves = f.session.player.saves;
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(f.position(300, 2), "retcode"));
    assertEquals(saved, gson.toJson(player().getGameplay()));
    assertEquals(saves, f.session.player.saves);
    restoreUnoccupied(unoccupied);
    position(300, 2);
    assertEquals(2, Gameplay46.number(f.bird(300), "pos"));
  }

  void restoreUnoccupied(String saved) throws Exception {
    // Controlled fixture release; the tests do not claim a real gameplay exit sequence.
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(player(), gson.fromJson(saved, GameplayState.class));
    player().setInstance(null);
  }

  void occupy(String kind) throws Exception {
    switch (kind) {
      case "rhythm" ->
          player().getGameplay().getRhythm().start(19997301, List.of(Map.of(), Map.of(), Map.of()));
      case "parkour" ->
          player()
              .getGameplay()
              .getParkour()
              .start(19997301, 1, 4, 199973001, 0, Long.MAX_VALUE, 0);
      case "universe" -> player().getGameplay().getRogue().setInside(true);
      case "mission-raid" -> {
        var field = GameplayState.class.getDeclaredField("currentMissionRaid");
        field.setAccessible(true);
        field.setInt(player().getGameplay(), 199973);
      }
      case "clock" -> {
        copy(read("clock-park-catalog-4.6.json"));
        var catalog = ClockParkCatalog.load(root);
        var m =
            ClockParkState.class.getDeclaredMethod(
                "start", ClockParkCatalog.Script.class, ClockParkCatalog.class);
        m.setAccessible(true);
        m.invoke(player().getGameplay().getClockPark(), catalog.scripts().get(1), catalog);
      }
      case "story", "transition" -> {
        var p =
            new StoryLineState.Place(
                199973,
                199973001,
                199973001,
                0,
                new emu.lunarcore.util.Position(),
                new emu.lunarcore.util.Position());
        player().getGameplay().getStoryLines().begin(199973, p, p);
        if (kind.equals("story")) player().getGameplay().getStoryLines().arrived();
      }
      case "instance" ->
          player()
              .setInstance(
                  new emu.lunarcore.game.player.PlayerGameInstance() {
                    public void onBattleStart(emu.lunarcore.game.battle.Battle battle) {}

                    public void onBattleFinish(
                        emu.lunarcore.game.battle.Battle battle,
                        emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus result,
                        emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics stats) {}

                    public void onLeave() {}
                  });
      default -> throw new IllegalArgumentException();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "rhythm",
        "parkour",
        "universe",
        "mission-raid",
        "clock",
        "story",
        "transition",
        "instance"
      })
  void newNativeResultWaitsWithoutReservingReportOrRewardWhileOtherGameplayIsOccupied(String kind)
      throws Exception {
    String unoccupied = gson.toJson(player().getGameplay());
    occupy(kind);
    String saved = gson.toJson(player().getGameplay());
    int saves = f.session.player.saves;
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(),
        Gameplay46.number(f.finish(1, 1100, 300, 1, "busy-result-" + kind, Map.of()), "retcode"));
    assertEquals(saved, gson.toJson(player().getGameplay()));
    assertEquals(saves, f.session.player.saves);
    assertEquals(0, f.session.player.calls);
    restoreUnoccupied(unoccupied);
    assertEquals(
        0,
        Gameplay46.number(f.finish(1, 1100, 300, 1, "busy-result-" + kind, Map.of()), "retcode"));
    assertEquals(1, player().getGameplay().getMatchThree().getReports().size());
  }
}
