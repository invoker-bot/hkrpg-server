package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.config.MissionTaskInfo;
import emu.lunarcore.data.excel.MapEntranceExcel;
import emu.lunarcore.data.excel.NpcExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.util.Position;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RockyTruthPerformance46Test {
  private static final int MAIN = 2000111, FOLLOWUP = 2000119, RETURN_TO_ROCKY = 200011009;
  private static final Branch RED = new Branch(200011010, 200011012, 200011029, 200011031);
  private static final Branch BLUE = new Branch(200011011, 200011013, 200011030, 200011032);
  private final Map<Integer, MissionInfo> previousMains = new HashMap<>();
  private final Map<Integer, MissionInfo.SubMissionInfo> previousSubs = new HashMap<>();
  private Actor actor;

  record Branch(int delivery, int talk, int observation, int nextDelivery) {
    String key() {
      return "Mission_" + talk;
    }
  }

  static Stream<Branch> branches() {
    return Stream.of(RED, BLUE);
  }

  private static class Actor extends Player {
    int saves;
    int plane = 10001;
    final Position position = new Position();
    GameSession session;

    @Override public int getPlaneId() { return plane; }
    @Override public int getFloorId() { return 10001001; }
    @Override public Position getPos() { return position; }
    @Override public GameSession getSession() { return session; }
    @Override public void save() { saves++; }
  }

  @BeforeEach
  void prepare() throws Exception {
    var gson = ResourceLoader.getGson();
    try (var reader =
        new InputStreamReader(
            Objects.requireNonNull(getClass().getResourceAsStream("/fixtures/rocky-truth-4.6.json")),
            StandardCharsets.UTF_8)) {
      var source = JsonParser.parseReader(reader).getAsJsonObject();
      for (var row : source.getAsJsonArray("missions")) {
        var mission = gson.fromJson(row, MissionInfo.class);
        int main = mission.getMainMissionID();
        previousMains.put(main, GameData.getMissionInfos().put(main, mission));
        for (var sub : mission.getSubMissionList()) {
          previousSubs.put(sub.getId(), GameplayDefinitions.subs.put(sub.getId(), sub));
          var script = source.getAsJsonObject("scripts").get(Integer.toString(sub.getId()));
          if (script != null) {
            var task = gson.fromJson(script, MissionTaskInfo.class);
            task.retainSourceDefinition(script);
            sub.setTask(task);
          }
        }
      }
    }
    actor = new Actor();
    actor.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    actor.getGameplay().getMainMissions().put(FOLLOWUP, new QuestState(FOLLOWUP));
    complete(200011008);
    actor.getMissionManager().refresh();
  }

  @AfterEach
  void restore() {
    previousMains.forEach((id, row) -> restore(GameData.getMissionInfos(), id, row));
    previousSubs.forEach((id, row) -> restore(GameplayDefinitions.subs, id, row));
  }

  private static <T> void restore(Map<Integer, T> rows, int id, T old) {
    if (old == null) rows.remove(id);
    else rows.put(id, old);
  }

  private void complete(int id) {
    var state = new QuestState(id);
    assertTrue(state.advance(1, 1, 100));
    actor.getGameplay().getSubMissions().put(id, state);
  }

  private void assertDoing(int id) {
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(id).getPhase());
  }

  @ParameterizedTest
  @MethodSource("branches")
  void oneNativeKeyFinishesTheSelectedPerformanceAndUnlocksTheOtherLetter(Branch branch) {
    complete(branch.delivery());
    var manager = actor.getMissionManager();
    manager.refresh();
    assertDoing(branch.talk());
    assertDoing(branch.observation());
    assertDoing(RETURN_TO_ROCKY);

    assertEquals(Retcode.SUCC, manager.finishTalk(0, branch.key()));
    assertTrue(MissionStateRecords.subCompleted(actor, branch.talk()));
    assertTrue(MissionStateRecords.subCompleted(actor, branch.observation()));
    assertTrue(MissionStateRecords.subCompleted(actor, RETURN_TO_ROCKY));
    assertTrue(manager.finished(MAIN));
    assertDoing(branch.nextDelivery());
    var other = branch.equals(RED) ? BLUE : RED;
    assertDoing(other.observation());
    assertNull(manager.sub(other.nextDelivery()));

    int saves = actor.saves;
    assertEquals(Retcode.SUCC, manager.finishTalk(0, branch.key()));
    assertEquals(saves, actor.saves);
  }

  @ParameterizedTest
  @MethodSource("branches")
  void noFinishBeforeDeliveringTheSelectedLetter(Branch branch) {
    var manager = actor.getMissionManager();
    int saves = actor.saves;
    for (int target : List.of(0, branch.talk(), branch.observation()))
      assertEquals(Retcode.MISSION_NOT_DOING, manager.finishTalk(target, branch.key()));
    assertDoing(branch.observation());
    assertNull(manager.sub(branch.nextDelivery()));
    assertEquals(saves, actor.saves);
  }

  @Test
  void existingSavedPerformanceRepairsOnlyItsMissingObservation() {
    complete(RED.delivery());
    complete(RED.talk());
    assertDoing(RED.observation());
    actor.getMissionManager().refresh();
    assertTrue(MissionStateRecords.subCompleted(actor, RED.observation()));
    assertDoing(RED.nextDelivery());
    assertDoing(BLUE.observation());
  }

  @Test
  void aRequestCannotFinishFromAnotherPlaneWithTheSameFloorNumber() {
    complete(RED.delivery());
    actor.getMissionManager().refresh();
    actor.plane = 10002;
    assertEquals(Retcode.MISSION_SCENE_NOT_MATCH, actor.getMissionManager().finishTalk(0, RED.key()));
    assertDoing(RED.talk());
    assertDoing(RED.observation());
  }

  @Test
  void aSharedKeyCannotCarryAChoiceOrAdvanceAnUnpaidBranch() {
    complete(RED.delivery());
    actor.getMissionManager().refresh();
    assertEquals(Retcode.MISSION_CUSTOM_VALUE_NOT_VALID,
        actor.getMissionManager().finishTalk(0, RED.key(), Map.of(0, 1)));
    assertEquals(Retcode.MISSION_NOT_DOING, actor.getMissionManager().finishTalk(0, BLUE.key()));
    assertDoing(RED.talk());
    assertDoing(RED.observation());
  }

  @Test
  void resumeUsesTheOriginalNearbyAnchorOnlyWhileThePaidReadingIsPending() throws Exception {
    var gson = ResourceLoader.getGson();
    var floor = new FloorInfo(10001001);
    try (var reader =
        new InputStreamReader(Objects.requireNonNull(
            getClass().getResourceAsStream("/fixtures/client-npc-ownership-4.6.json")),
            StandardCharsets.UTF_8)) {
      var source = JsonParser.parseReader(reader).getAsJsonObject();
      var original = source.getAsJsonObject("groups").getAsJsonObject("18");
      var group = gson.fromJson(original, GroupInfo.class);
      group.setId(18);
      ClientNpcGroups.markSource(group, original);
      floor.getGroups().put(18, group);
    }
    var nearby = gson.fromJson("""
        {"AnchorList":[{"Name":"CheckPoint3","RotY":0.804422,"ID":3,
          "PosX":-52.997,"PosY":4.91183,"PosZ":-55.158}]}
        """, GroupInfo.class);
    nearby.setId(11);
    floor.getGroups().put(11, nearby);
    var entrance = gson.fromJson("""
        {"ID":1000102,"PlaneID":10001,"FloorID":10001001,"StartGroupID":11,"StartAnchorID":3}
        """, MapEntranceExcel.class);
    var npc = gson.fromJson("{\"ID\":10106}", NpcExcel.class);
    var previousFloor = GameData.getFloorInfos().put("P10001_F10001001", floor);
    var entrances = GameData.getMapEntranceExcelMap();
    var npcs = GameData.getNpcExcelMap();
    var previousEntrance = entrances.get(1000102);
    var previousNpc = npcs.get(10106);
    entrances.add(entrance);
    npcs.add(npc);
    try {
      actor.session = new GameSession(null) {
        @Override public ProtocolBridge getProtocol() { return ProtocolBridge.forVersion("4.6.0"); }
      };
      var away = new Position(29470, -893, -11120);
      actor.getPos().set(away);
      assertNull(RockyTruthPerformance.resumeAnchor(actor));

      complete(RED.delivery());
      actor.getMissionManager().refresh();
      var anchor = RockyTruthPerformance.resumeAnchor(actor);
      assertSame(nearby.getAnchorList().getFirst(), anchor);
      assertEquals(away, actor.getPos());
      assertDoing(RED.talk());
      actor.getPos().set(anchor.getPos());
      assertNull(RockyTruthPerformance.resumeAnchor(actor));
      actor.getPos().set(away);
      assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(0, RED.key()));
      assertNull(RockyTruthPerformance.resumeAnchor(actor));
    } finally {
      if (previousFloor == null) GameData.getFloorInfos().remove("P10001_F10001001");
      else GameData.getFloorInfos().put("P10001_F10001001", previousFloor);
      if (previousEntrance == null) entrances.values().remove(entrance);
      else entrances.add(previousEntrance);
      if (previousNpc == null) npcs.values().remove(npc);
      else npcs.add(previousNpc);
    }
  }
}
