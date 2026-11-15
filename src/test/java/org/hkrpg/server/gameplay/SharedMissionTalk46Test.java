package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.config.MissionTaskInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SharedMissionTalk46Test {
  private final Map<Integer, MissionInfo.SubMissionInfo> original = new HashMap<>();

  private static class Actor extends Player {
    int plane, floor, saves;
    boolean failSave;
    @Override public int getPlaneId() { return plane; }
    @Override public int getFloorId() { return floor; }
    @Override public boolean isOnline() { return false; }
    @Override public void save() {
      if (failSave) throw new IllegalStateException("test save failure");
      saves++;
    }
  }

  private record Scenario(Actor player, String key, MissionInfo.SubMissionInfo primary,
      MissionInfo.SubMissionInfo secondary) {}

  private JsonObject fixture() {
    try (var reader = new InputStreamReader(getClass().getResourceAsStream(
        "/fixtures/shared-mission-talk-4.6.json"), StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    } catch (java.io.IOException e) {
      throw new AssertionError(e);
    }
  }

  private void register(MissionInfo.SubMissionInfo sub) {
    original.putIfAbsent(sub.getId(), GameplayDefinitions.subs.get(sub.getId()));
    GameplayDefinitions.subs.put(sub.getId(), sub);
  }

  private Scenario scenario(String key) {
    for (var raw : fixture().getAsJsonArray("groups")) {
      var group = raw.getAsJsonObject();
      if (!key.equals(group.get("key").getAsString())) continue;
      var gson = ResourceLoader.getGson();
      var primary = gson.fromJson(group.getAsJsonObject("primary").get("row"), MissionInfo.SubMissionInfo.class);
      var secondary = gson.fromJson(group.getAsJsonObject("secondary").get("row"), MissionInfo.SubMissionInfo.class);
      var task = gson.fromJson(group.get("script"), MissionTaskInfo.class);
      task.retainSourceDefinition(group.get("script"));
      primary.setTask(task);
      register(primary);
      register(secondary);
      var player = new Actor();
      player.plane = primary.getLevelPlaneID();
      player.floor = primary.getLevelFloorID();
      for (var sub : new MissionInfo.SubMissionInfo[] {primary, secondary}) {
        player.getGameplay().getMainMissions().putIfAbsent(sub.getMainMissionID(), new QuestState(sub.getMainMissionID()));
        player.getGameplay().getSubMissions().put(sub.getId(), new QuestState(sub.getId()));
      }
      return new Scenario(player, key, primary, secondary);
    }
    throw new AssertionError("Missing original pair: " + key);
  }

  @AfterEach void restore() {
    original.forEach((id, old) -> {
      if (old == null) GameplayDefinitions.subs.remove(id);
      else GameplayDefinitions.subs.put(id, old);
    });
  }

  @ParameterizedTest
  @ValueSource(strings = {"Mission_200010823", "Mission_200011012", "Mission_200011013",
      "Mission_401010506", "Mission_407131202", "Mission_407131203"})
  void oneReportCompletesOnlyBothAcceptedOriginalObjectives(String key) {
    var s = scenario(key);
    assertEquals(Retcode.SUCC, s.player().getMissionManager().finishTalk(0, key));
    assertEquals(QuestState.Phase.FINISHED, s.player().getMissionManager().sub(s.primary().getId()).getPhase());
    assertEquals(QuestState.Phase.FINISHED, s.player().getMissionManager().sub(s.secondary().getId()).getPhase());
    assertTrue(s.player().getGameplay().getRewards().isEmpty());
    assertEquals(Retcode.MISSION_NOT_EXIST, s.player().getMissionManager().finishTalk(0, key));
  }

  @Test void failedSaveRollsBackBothObjectivesAndAllowsRetry() {
    var s = scenario("Mission_401010506");
    s.player().failSave = true;
    assertThrows(IllegalStateException.class, () -> s.player().getMissionManager().finishTalk(0, s.key()));
    for (var sub : new MissionInfo.SubMissionInfo[] {s.primary(), s.secondary()}) {
      var state = s.player().getMissionManager().sub(sub.getId());
      assertEquals(QuestState.Phase.DOING, state.getPhase());
      assertEquals(0, state.getProgress());
      assertEquals(0, state.getFinishTime());
    }
    s.player().failSave = false;
    assertEquals(Retcode.SUCC, s.player().getMissionManager().finishTalk(0, s.key()));
  }

  @Test void changedSourceWrongSceneCustomValuesAndPendingActionsCannotAdvanceEither() {
    var s = scenario("Mission_401010506");
    var manager = s.player().getMissionManager();
    s.player().plane++;
    assertEquals(Retcode.MISSION_FINISH_WAY_NOT_MATCH, manager.finishTalk(0, s.key()));
    s.player().plane--;
    assertEquals(Retcode.MISSION_CUSTOM_VALUE_NOT_VALID, manager.finishTalk(0, s.key(), Map.of(0, 1)));
    s.secondary().setParamInt2(123);
    assertEquals(Retcode.MISSION_FINISH_WAY_NOT_MATCH, manager.finishTalk(0, s.key()));
    s.secondary().setParamInt2(0);
    var cursor = new GameplayState.ActionCursor();
    cursor.reserve();
    s.player().getGameplay().getStoryActions().put(s.secondary().getId(), cursor);
    assertEquals(Retcode.SYSTEM_BUSY, manager.finishTalk(0, s.key()));
    assertEquals(0, s.player().saves);
    assertEquals(QuestState.Phase.DOING, manager.sub(s.primary().getId()).getPhase());
    assertEquals(QuestState.Phase.DOING, manager.sub(s.secondary().getId()).getPhase());
  }

  @Test void anUnexpectedThirdAcceptedObjectiveDoesNotTurnAmbiguityIntoCompletion() {
    var s = scenario("Mission_401010506");
    var extra = ResourceLoader.getGson().fromJson("""
        {"ID":99999101,"MainMissionID":999991,"FinishType":"Talk",
         "ParamStr1":"Mission_401010506","LevelFloorID":10000000,"Progress":1}
        """, MissionInfo.SubMissionInfo.class);
    register(extra);
    s.player().getGameplay().getMainMissions().put(999991, new QuestState(999991));
    s.player().getGameplay().getSubMissions().put(extra.getId(), new QuestState(extra.getId()));
    assertEquals(Retcode.MISSION_FINISH_WAY_NOT_MATCH, s.player().getMissionManager().finishTalk(0, s.key()));
    assertEquals(0, s.player().saves);
    for (int id : new int[] {s.primary().getId(), s.secondary().getId(), extra.getId()})
      assertEquals(QuestState.Phase.DOING, s.player().getMissionManager().sub(id).getPhase());
  }

  @Test void changedPerformanceScriptCannotAuthorizeAGroupCompletion() {
    var s = scenario("Mission_401010506");
    s.primary().getTask().retainSourceDefinition(JsonParser.parseString("{\"Type\":\"Mission\"}"));
    assertEquals(Retcode.MISSION_FINISH_WAY_NOT_MATCH, s.player().getMissionManager().finishTalk(0, s.key()));
    assertEquals(0, s.player().saves);
    assertEquals(QuestState.Phase.DOING, s.player().getMissionManager().sub(s.primary().getId()).getPhase());
    assertEquals(QuestState.Phase.DOING, s.player().getMissionManager().sub(s.secondary().getId()).getPhase());
  }

  @Test void anExplicitSubMissionReportRetainsItsExistingSingleTargetMeaning() {
    var s = scenario("Mission_401010506");
    assertEquals(Retcode.SUCC, s.player().getMissionManager().finishTalk(s.primary().getId(), s.key()));
    assertEquals(QuestState.Phase.FINISHED, s.player().getMissionManager().sub(s.primary().getId()).getPhase());
    assertEquals(QuestState.Phase.DOING, s.player().getMissionManager().sub(s.secondary().getId()).getPhase());
  }

  @Test void sequentialOriginalObjectivesRequireSeparateReports() {
    var p = new Actor();
    p.plane = 10202;
    p.floor = 10202004;
    p.getGameplay().getMainMissions().put(8003263, new QuestState(8003263));
    for (var raw : fixture().getAsJsonArray("sequential"))
      register(ResourceLoader.getGson().fromJson(raw, MissionInfo.SubMissionInfo.class));
    p.getGameplay().getSubMissions().put(800326302, new QuestState(800326302));
    assertEquals(Retcode.SUCC, p.getMissionManager().finishTalk(0, "Mission_800326302"));
    assertEquals(QuestState.Phase.FINISHED, p.getMissionManager().sub(800326302).getPhase());
    assertEquals(QuestState.Phase.DOING, p.getMissionManager().sub(800326303).getPhase());
    assertEquals(Retcode.SUCC, p.getMissionManager().finishTalk(0, "Mission_800326302"));
    assertEquals(QuestState.Phase.FINISHED, p.getMissionManager().sub(800326303).getPhase());
  }

  @Test void simultaneouslyCorruptSequentialStatesRemainAmbiguous() {
    var p = new Actor();
    p.plane = 10202;
    p.floor = 10202004;
    p.getGameplay().getMainMissions().put(8003263, new QuestState(8003263));
    for (var raw : fixture().getAsJsonArray("sequential"))
      register(ResourceLoader.getGson().fromJson(raw, MissionInfo.SubMissionInfo.class));
    p.getGameplay().getSubMissions().put(800326302, new QuestState(800326302));
    p.getGameplay().getSubMissions().put(800326303, new QuestState(800326303));
    assertEquals(Retcode.MISSION_FINISH_WAY_NOT_MATCH,
        p.getMissionManager().finishTalk(0, "Mission_800326302"));
    assertEquals(0, p.saves);
  }
}
