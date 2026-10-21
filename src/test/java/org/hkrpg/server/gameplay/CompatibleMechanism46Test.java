package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.*;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CompatibleMechanism46Test {
  @Test
  void futureRoomsAndWavesDoNotSpawnBeforeTheirFloorGate() {
    begin(20423683, 401610106);
    assertFalse(player().scene.getLoadedGroups().contains(166));
    assertFalse(player().scene.getLoadedGroups().contains(169));
    begin(20331626, 0);
    assertTrue(player().scene.getEntitiesByGroup(EntityMonster.class, 98).isEmpty());
    assertTrue(player().scene.getEntitiesByGroup(EntityMonster.class, 147).isEmpty());
    assertEquals(Retcode.SUCC, CompatibleMechanisms.groupState(player(), 39, 2, player().entry, 0));
    assertEquals(3, player().scene.getEntitiesByGroup(EntityMonster.class, 98).size());
    assertTrue(player().scene.getEntitiesByGroup(EntityMonster.class, 147).isEmpty());
  }

  @Test
  void roomClaimsAdvanceAndReplaceTheLoadedRoomWithoutRelogging() {
    begin(20423683, 401610106);
    assertEquals(Retcode.SUCC, report(164, "State", 1));
    assertEquals(
        Retcode.SUCC, player().getMissionManager().finishTalk(401610107, "Mission_401610107"));
    for (int id = 20423683; id <= 20423685; id++) assertEquals(Retcode.SUCC, map().open(id));
    assertEquals(
        1, player().getGameplay().getStoryValues().get("floor:20423001:FSV_SubGameplayRoomState"));
    assertFalse(player().scene.getLoadedGroups().contains(55));
    assertTrue(player().scene.getLoadedGroups().contains(166));
  }

  @TempDir Path root;
  final WorldMechanism46Test fixture = new WorldMechanism46Test();
  Map<Integer, MissionInfo.SubMissionInfo> previous;
  final JsonObject source =
      JsonParser.parseReader(
              new java.io.InputStreamReader(
                  getClass().getResourceAsStream("/fixtures/compatible-mechanisms-4.6.json"),
                  java.nio.charset.StandardCharsets.UTF_8))
          .getAsJsonObject();

  @BeforeEach
  void load() throws Exception {
    fixture.root = root;
    fixture.map.merge("mascot-4.6.json");
    var mascot =
        JsonParser.parseReader(
                new java.io.InputStreamReader(
                    getClass().getResourceAsStream("/fixtures/mascot-4.6.json"),
                    java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
    mascot
        .getAsJsonObject("headers")
        .entrySet()
        .forEach(
            e ->
                fixture
                    .map
                    .fixture
                    .source
                    .getAsJsonObject("headers")
                    .add(e.getKey(), e.getValue()));
    fixture.map.merge("compatible-mechanisms-4.6.json");
    source
        .getAsJsonObject("headers")
        .entrySet()
        .forEach(
            e ->
                fixture
                    .map
                    .fixture
                    .source
                    .getAsJsonObject("headers")
                    .add(e.getKey(), e.getValue()));
    fixture.load();
    previous = new HashMap<>(GameplayDefinitions.subs);
    for (var m : source.getAsJsonObject("missions").entrySet())
      for (var s : m.getValue().getAsJsonObject().getAsJsonArray("SubMissionList")) {
        var def = fixture.map.fixture.gson.fromJson(s, MissionInfo.SubMissionInfo.class);
        GameplayDefinitions.subs.put(def.getId(), def);
      }
  }

  @AfterEach
  void restore() throws Exception {
    if (previous != null) {
      GameplayDefinitions.subs.clear();
      GameplayDefinitions.subs.putAll(previous);
    }
    fixture.restore();
  }

  MapChest46Test map() {
    return fixture.map;
  }

  MapChest46Test.MapPlayer player() {
    return (MapChest46Test.MapPlayer) map().fixture.player;
  }

  void begin(int chest, int sub) {
    if (sub > 0) {
      int main = GameplayDefinitions.subs.get(sub).getMainMissionID();
      player().getGameplay().getMainMissions().put(main, new QuestState(main));
      player().getGameplay().getSubMissions().put(sub, new QuestState(sub));
    }
    if (chest >= 20422686 && chest <= 20422699)
      player().getGameplay().getStoryValues().put("floor:20422001:SceneStateByMainMission", 1);
    if (chest == 20311684)
      player().getGameplay().getStoryValues().put("floor:20311001:ChallengeGravityRoom", 2);
    if (chest >= 20423683 && chest <= 20423691)
      player().getGameplay().getStoryValues().put("floor:20423001:FSV_Region08State", 0);
    map().enter(chest);
  }

  EntityProp actor(int g, int i) {
    return player().scene.getEntitiesByGroup(EntityProp.class, g).stream()
        .filter(p -> p.getInstId() == i)
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing " + g + ":" + i));
  }

  Retcode interact(int g, int i, int config) {
    return ScenePropTransitions.interact(player(), config, actor(g, i).getEntityId()).retcode();
  }

  Retcode report(int g, String key, int value) {
    return WorldGroupProperties.change(
            player(), player().floor, player().scene.getDimensionId(), g, key, value)
        .retcode();
  }

  void claim(int chest) {
    int before = player().grants;
    assertEquals(PropState.ChestClosed, map().chest(chest).getState());
    fixture.reload();
    assertEquals(Retcode.SUCC, map().open(chest));
    fixture.reload();
    assertEquals(PropState.ChestUsed, map().chest(chest).getState());
    assertNotEquals(Retcode.SUCC, map().open(chest));
    assertEquals(before + 1, player().grants);
  }

  @Test
  void gravityCompletionUnlocksOnlyItsOwnChestAndSurvivesReload() {
    begin(20451691, 0);
    assertNotEquals(Retcode.SUCC, map().open(20451691));
    assertNotEquals(Retcode.SUCC, report(53, "CaseState_2", 1));
    assertEquals(Retcode.SUCC, report(53, "CaseState_1", 1));
    assertEquals(PropState.Hidden, map().chest(20451692).getState());
    claim(20451691);
  }

  @Test
  void roomChallengePreservesIndividualRewardsAndRequiresAllThreeClaims() {
    begin(20423683, 401610106);
    assertEquals(Retcode.SUCC, report(164, "State", 1));
    claim(20423683);
    assertEquals(
        0,
        player()
            .getGameplay()
            .getStoryValues()
            .getOrDefault("floor:20423001:FSV_SubChestCheck", 0));
    claim(20423684);
    claim(20423685);
    assertEquals(
        1,
        player()
            .getGameplay()
            .getStoryValues()
            .getOrDefault("floor:20423001:FSV_SubChestCheck", 0));
  }

  @Test
  void crossGroupJigsawRequiresBothPieces() {
    begin(20311684, 0);
    assertEquals(Retcode.SUCC, interact(455, 300001, 1210));
    assertEquals(Retcode.SUCC, interact(379, 300002, 11000));
    fixture.reload();
    assertEquals(PropState.Hidden, map().chest(20311684).getState());
    assertNotEquals(Retcode.SUCC, interact(455, 300001, 11000));
    assertEquals(Retcode.SUCC, interact(455, 300002, 11000));
    assertEquals(Retcode.SUCC, interact(455, 300001, 11000));
    claim(20311684);
  }

  @Test
  void clockBoardsMustBeCompletedInOrder() {
    begin(20331665, 0);
    assertNotEquals(Retcode.SUCC, interact(200, 300001, 11000));
    assertEquals(Retcode.SUCC, interact(199, 300001, 11000));
    fixture.reload();
    assertEquals(PropState.Hidden, map().chest(20331665).getState());
    assertEquals(Retcode.SUCC, interact(200, 300001, 11000));
    claim(20331665);
  }

  @Test
  void collectionUsesTwentyRealMapTargetsAndNeverDisplayModels() {
    begin(10401680, 0);
    int count = 0;
    for (int g = 208; g <= 227; g++) {
      int i = g == 214 || g == 215 ? 300003 : g == 226 ? 300005 : 300001;
      var p = actor(g, i);
      int code =
          switch (p.getState()) {
            case EventClose -> 118191;
            case Open -> 11191;
            case Closed -> 10191;
            default -> throw new AssertionError(p.getState());
          };
      assertEquals(Retcode.SUCC, interact(g, i, code));
      if (++count < 20) assertEquals(PropState.Hidden, map().chest(10401680).getState());
      if (count == 10) fixture.reload();
    }
    assertNotEquals(Retcode.SUCC, interact(208, 300001, 118191));
    claim(10401680);
  }

  @Test
  void allNineDawnCasesKeepSeparateDurableRewards() {
    begin(20451691, 0);
    for (int i = 1; i <= 9; i++) {
      assertEquals(Retcode.SUCC, report(53, "CaseState_" + i, 1));
      assertEquals(Retcode.SUCC, report(53, "CaseState_" + i, 1));
      claim(20451690 + i);
    }
    assertEquals(9, player().getGameplay().getWorldChests().getRecords().size());
  }

  @Test
  void riverCasesRequireTheirAcceptedObjectiveAndMilestonesRequireDialogue() {
    int[] waits = {
      401600102, 401600104, 401600106, 401600108, 401600112, 401600114, 401600118, 401600120,
      401600122
    };
    begin(20422691, waits[0]);
    assertNotEquals(Retcode.SUCC, report(229, "MissionState", 2));
    for (int i = 1; i <= 9; i++) {
      // Each accepted dialogue/wait position is a prerequisite fixture; no completion is seeded.
      player().getGameplay().getSubMissions().put(waits[i - 1], new QuestState(waits[i - 1]));
      assertEquals(Retcode.SUCC, report(229, "MissionState", i), "Case " + i);
      claim(20422690 + i);
      if (i == 4 || i == 6 || i == 9) {
        int stage = i == 4 ? 1 : i == 6 ? 2 : 3,
            sub = i == 4 ? 401600110 : i == 6 ? 401600116 : 401600124;
        assertEquals(PropState.Hidden, map().chest(20422685 + stage).getState());
        player().getGameplay().getSubMissions().put(sub, new QuestState(sub));
        assertEquals(Retcode.SUCC, player().getMissionManager().finishTalk(sub, "Mission_" + sub));
        claim(20422685 + stage);
      }
    }
    assertEquals(12, player().getGameplay().getWorldChests().getRecords().size());
  }

  @Test
  void allThreeRoomsRequireTheirOwnCallbackAndThreeClaims() {
    int[] groups = {164, 30, 167}, subs = {401610106, 401610109, 401610115};
    for (int stage = 1; stage <= 3; stage++) {
      // Travel and acceptance only; earlier challenge and claim receipts remain the live results.
      player()
          .getGameplay()
          .getStoryValues()
          .put("floor:20423001:FSV_SubGameplayRoomState", stage - 1);
      begin(20423680 + stage * 3, subs[stage - 1]);
      assertEquals(Retcode.SUCC, report(groups[stage - 1], "State", 1));
      for (int i = 0; i < 3; i++) claim(20423680 + stage * 3 + i);
      assertEquals(
          stage, player().getGameplay().getStoryValues().get("floor:20423001:FSV_SubChestCheck"));
    }
    assertEquals(9, player().getGameplay().getWorldChests().getRecords().size());
  }

  @Test
  void remainingBoardsAndTransferKeepTheirMissionGates() {
    begin(20322662, 0);
    assertEquals(Retcode.SUCC, interact(340, 300001, 1210));
    assertEquals(Retcode.SUCC, interact(183, 300001, 11000));
    fixture.reload();
    assertEquals(Retcode.SUCC, interact(340, 300001, 11000));
    claim(20322662);
    begin(20332662, 0);
    assertNotEquals(Retcode.SUCC, interact(211, 300001, 11000));
    begin(20332662, 103290250);
    assertEquals(Retcode.SUCC, interact(211, 300001, 11000));
    claim(20332662);
    begin(20481657, 401590401);
    assertNotEquals(Retcode.SUCC, map().open(20481657));
    assertEquals(
        Retcode.SUCC, player().getMissionManager().finishTalk(401590401, "Mission_401590401"));
    claim(20481657);
  }

  @Test
  void threeActualMonsterWavesPersistEveryDefeatAndUnlockAfterTwelve() {
    begin(20331626, 0);
    assertEquals(Retcode.SUCC, CompatibleMechanisms.groupState(player(), 39, 2, player().entry, 0));
    int count = 0;
    for (int group : new int[] {98, 147, 150}) {
      var monsters = List.copyOf(player().scene.getEntitiesByGroup(EntityMonster.class, group));
      assertEquals(group == 150 ? 6 : 3, monsters.size());
      for (var m : monsters) {
        assertTrue(player().scene.defeatMonster(m));
        if (++count < 12) assertEquals(PropState.Hidden, map().chest(20331626).getState());
      }
      fixture.reload();
      assertTrue(player().scene.getEntitiesByGroup(EntityMonster.class, group).isEmpty());
    }
    claim(20331626);
  }

  @Test
  void failedPropertySaveDoesNotUnlockOrChangeFloorOrGroup() {
    begin(20451691, 0);
    player().failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, report(53, "CaseState_1", 1));
    assertEquals(0, player().scene.getGroups().get(53).getProperties().getInt("CaseState_1"));
    assertEquals(PropState.Hidden, map().chest(20451691).getState());
    assertTrue(player().getGameplay().getCompatibleMechanisms().events("gravity-dawn").isEmpty());
    player().failSave = false;
    assertEquals(Retcode.SUCC, report(53, "CaseState_1", 1));
    claim(20451691);
  }

  @Test
  void failedPieceAndCollectionSaveLeavesTheTargetAvailable() {
    begin(20311684, 0);
    assertEquals(Retcode.SUCC, interact(455, 300001, 1210));
    player().failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, interact(379, 300002, 11000));
    assertEquals(PropState.TriggerEnable, actor(379, 300002).getState());
    player().failSave = false;
    fixture.reload();
    assertEquals(Retcode.SUCC, interact(379, 300002, 11000));
    begin(10401680, 0);
    player().failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, interact(208, 300001, 118191));
    assertEquals(PropState.EventClose, actor(208, 300001).getState());
    player().failSave = false;
    assertEquals(Retcode.SUCC, interact(208, 300001, 118191));
    fixture.reload();
    assertEquals(PropState.EventOpen, actor(208, 300001).getState());
  }

  @Test
  void changedSourceAndForgedInMemoryStateCannotUnlock() {
    begin(20451691, 0);
    map().chest(20451691).setState(PropState.ChestClosed, false);
    assertNotEquals(Retcode.SUCC, map().open(20451691));
    player().getGameplay().getStoryValues().put("group:20451001:53:CaseState_1", 1);
    assertNotEquals(Retcode.SUCC, map().open(20451691));
    assertNotEquals(
        Retcode.SUCC,
        WorldGroupProperties.change(player(), 20422001, 0, 53, "CaseState_1", 1).retcode());
    assertNotEquals(Retcode.SUCC, report(53, "CaseState_1", 2));
  }

  @Test
  void sourceChangesAreRejectedEvenAfterRestorationWasCached() throws Exception {
    begin(20451691, 0);
    var path = root.resolve("Config/Level/GroupGraph/F20451001/Group_F20451001_G53.json");
    String original = java.nio.file.Files.readString(path);
    var timestamp = java.nio.file.Files.getLastModifiedTime(path);
    String changed = original.replace("CaseState_1", "CaseState_9");
    assertNotEquals(original, changed);
    assertEquals(original.length(), changed.length());
    java.nio.file.Files.writeString(path, changed);
    java.nio.file.Files.setLastModifiedTime(path, timestamp);
    assertNotEquals(Retcode.SUCC, report(53, "CaseState_1", 1));
    assertEquals(0, player().grants);
    java.nio.file.Files.writeString(path, original);
    assertEquals(Retcode.SUCC, report(53, "CaseState_1", 1));
  }

  @Test
  void failedVictorySaveAndUnloadingNeverCountAsWaveProgress() {
    begin(20331626, 0);
    assertEquals(Retcode.SUCC, CompatibleMechanisms.groupState(player(), 39, 2, player().entry, 0));
    var monster = player().scene.getEntitiesByGroup(EntityMonster.class, 98).getFirst();
    player().failSave = true;
    assertFalse(player().scene.defeatMonster(monster));
    assertSame(monster, player().scene.getEntityById(monster.getEntityId()));
    player().failSave = false;
    player().scene.removeEntity(monster);
    fixture.reload();
    assertEquals(3, player().scene.getEntitiesByGroup(EntityMonster.class, 98).size());
    assertEquals(
        Set.of("started"), player().getGameplay().getCompatibleMechanisms().events("waves"));
  }

  @Test
  void claimedRewardCannotRepeatAfterFinalSaveFailure() {
    begin(20451691, 0);
    assertEquals(Retcode.SUCC, report(53, "CaseState_1", 1));
    player().failOnSave = player().saves + 2;
    assertEquals(Retcode.SYSTEM_BUSY, map().open(20451691));
    assertEquals(1, player().grants);
    player().failOnSave = -1;
    fixture.reload();
    assertNotEquals(Retcode.SUCC, map().open(20451691));
    assertEquals(1, player().grants);
  }
}
