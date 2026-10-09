package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class StoryLineDefinitionsTest {
  static final int STORY = 199938, START = 19993801, END = 19993802, OTHER = 19993803;
  @TempDir Path root;
  final Gson gson = new Gson();
  Field catalogField;
  Object oldCatalog;
  Map<Integer, MissionInfo.SubMissionInfo> oldSubs;
  Player player;
  String story, floor, trials;

  @BeforeEach
  void prepare() throws Exception {
    catalogField = StoryLineDefinitions.class.getDeclaredField("catalog");
    catalogField.setAccessible(true);
    oldCatalog = catalogField.get(null);
    oldSubs = new HashMap<>(GameplayDefinitions.subs);
    GameplayDefinitions.subs.clear();
    player = new Player();
    player.getGameplay().getMainMissions().put(STORY, new QuestState(STORY));
    for (int id : List.of(START, END, OTHER))
      GameplayDefinitions.subs.put(
          id,
          gson.fromJson(
              "{\"ID\":" + id + ",\"MainMissionID\":" + STORY + "}",
              MissionInfo.SubMissionInfo.class));
    story =
        "{\"StoryLineID\":199938,\"BeginCondition\":{\"Type\":\"SubMissionTaken\",\"Param\":\"19993801\"},\"EndCondition\":{\"Type\":\"FinishSubMission\",\"Param\":\"19993802\"},\"ShowCondition\":\"[BetweenSubMission:19993803,19993802]\",\"InitEntranceID\":19993801,\"InitGroupID\":8,\"InitAnchorID\":1,\"PerformanceStoryAvatar\":\"TestActor\"}";
    floor =
        "{\"StoryLineID\":199938,\"FloorID\":19993801,\"DimensionID\":4,\"ConditionExpression\":\"[BetweenSubMission:19993803,19993802]\"}";
    trials =
        "{\"StoryLineID\":199938,\"TrialAvatarList\":[1021313,1021304],\"InitTrialAvatarList\":[1021313],\"CaptainAvatarID\":1021313,\"SkipJoinLineup\":true}";
    write("StoryLine", story);
    write("StoryLineFloorData", floor);
    write("StroyLineTrialAvatarData", trials);
    StoryLineDefinitions.load(root);
  }

  @AfterEach
  void restore() throws Exception {
    catalogField.set(null, oldCatalog);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
  }

  void write(String table, String rows) throws Exception {
    Files.createDirectories(root.resolve("ExcelOutput"));
    Files.writeString(root.resolve("ExcelOutput/" + table + ".json"), "[" + rows + "]");
  }

  QuestState take(int id) {
    var state = new QuestState(id);
    player.getGameplay().getSubMissions().put(id, state);
    return state;
  }

  FloorInfo sceneFloor(boolean alternate) {
    return gson.fromJson(
        "{\"FloorID\":19993801,\"DimensionList\":[{\"ID\":0,\"GroupIndexList\":[0]}"
            + (alternate ? ",{\"ID\":4,\"GroupIndexList\":[1]}" : "")
            + "],\"GroupInstanceList\":[{\"ID\":8},{\"ID\":9}]}",
        FloorInfo.class);
  }

  @Test
  void threeActualResourceTablesRetainTheirIndependentSemanticsAndCannotBeMutated() {
    assertEquals(Set.of(STORY), StoryLineDefinitions.stories().keySet());
    assertEquals(1, StoryLineDefinitions.floorCount());
    assertEquals(START, StoryLineDefinitions.stories().get(STORY).BeginCondition().id());
    assertEquals(4, StoryLineDefinitions.floor(STORY, START).DimensionID());
    var t = StoryLineDefinitions.trials(STORY);
    assertEquals(List.of(1021313, 1021304), t.TrialAvatarList());
    assertEquals(List.of(1021313), t.InitTrialAvatarList());
    assertTrue(t.SkipJoinLineup());
    assertThrows(UnsupportedOperationException.class, () -> t.TrialAvatarList().clear());
    assertThrows(UnsupportedOperationException.class, () -> StoryLineDefinitions.stories().clear());
  }

  @Test
  void entryEligibilityVisibilityAndTheTargetFloorHaveSeparateResourceGates() {
    assertFalse(StoryLineDefinitions.canEnter(player, STORY));
    take(START);
    assertTrue(StoryLineDefinitions.canEnter(player, STORY));
    assertFalse(StoryLineDefinitions.visible(player, STORY));
    assertTrue(StoryLineDefinitions.dimension(player, STORY, sceneFloor(true)).isEmpty());
    take(OTHER);
    assertTrue(StoryLineDefinitions.visible(player, STORY));
    assertEquals(4, StoryLineDefinitions.dimension(player, STORY, sceneFloor(true)).orElseThrow());
    take(END).advance(1, 1, 1);
    assertFalse(StoryLineDefinitions.canEnter(player, STORY));
    assertFalse(StoryLineDefinitions.visible(player, STORY));
    assertTrue(StoryLineDefinitions.dimension(player, STORY, sceneFloor(true)).isEmpty());
  }

  @Test
  void aMissingDimensionOrUnconfiguredFloorDoesNotFallBackToTheMainWorld() {
    take(START);
    take(OTHER);
    assertTrue(StoryLineDefinitions.dimension(player, STORY, sceneFloor(false)).isEmpty());
    var otherFloor = gson.fromJson("{\"FloorID\":19993802}", FloorInfo.class);
    assertTrue(StoryLineDefinitions.dimension(player, STORY, otherFloor).isEmpty());
    assertTrue(StoryLineDefinitions.dimension(player, 199939, sceneFloor(true)).isEmpty());
    assertEquals(0, StoryLineDefinitions.dimension(player, 0, otherFloor).orElseThrow());
  }

  @Test
  void missingOrCancelledEndObjectivesCannotUnlockAStory() {
    take(START);
    take(OTHER);
    assertTrue(StoryLineDefinitions.canEnter(player, STORY));
    player.getGameplay().getCancelledSubs().add(END);
    assertFalse(StoryLineDefinitions.canEnter(player, STORY));
    player.getGameplay().getCancelledSubs().clear();
    GameplayDefinitions.subs.remove(END);
    assertFalse(StoryLineDefinitions.canEnter(player, STORY));
  }

  @Test
  void duplicateOrForeignRowsRejectTheWholeUpdateAndKeepThePreviousCatalog() throws Exception {
    var original = StoryLineDefinitions.stories();
    write("StoryLineFloorData", floor + "," + floor);
    assertThrows(IllegalStateException.class, () -> StoryLineDefinitions.load(root));
    assertSame(original, StoryLineDefinitions.stories());
    assertEquals(4, StoryLineDefinitions.floor(STORY, START).DimensionID());
    write("StoryLineFloorData", floor.replace("\"StoryLineID\":199938", "\"StoryLineID\":199939"));
    assertThrows(IllegalStateException.class, () -> StoryLineDefinitions.load(root));
    assertSame(original, StoryLineDefinitions.stories());
    write("StoryLineFloorData", floor);
    write("StoryLine", story + "," + story);
    assertThrows(IllegalStateException.class, () -> StoryLineDefinitions.load(root));
    assertSame(original, StoryLineDefinitions.stories());
  }

  @Test
  void unsupportedConditionsAndInvalidTrialChoicesCannotReplaceAValidCatalog() throws Exception {
    var original = StoryLineDefinitions.stories();
    for (String bad :
        List.of(
            trials.replace("1021313,1021304", "1021313,1021313"),
            trials.replace("\"CaptainAvatarID\":1021313", "\"CaptainAvatarID\":1029999"),
            trials.replace(
                "\"InitTrialAvatarList\":[1021313]", "\"InitTrialAvatarList\":[1029999]"))) {
      write("StroyLineTrialAvatarData", bad);
      assertThrows(IllegalStateException.class, () -> StoryLineDefinitions.load(root));
      assertSame(original, StoryLineDefinitions.stories());
    }
    write("StroyLineTrialAvatarData", trials);
    write("StoryLineFloorData", floor.replace("BetweenSubMission", "Unknown"));
    assertThrows(IllegalStateException.class, () -> StoryLineDefinitions.load(root));
    assertSame(original, StoryLineDefinitions.stories());
    write("StoryLineFloorData", floor);
    write("StoryLine", story.replace("\"19993801\"", "\"4294967297\""));
    assertThrows(IllegalStateException.class, () -> StoryLineDefinitions.load(root));
    assertSame(original, StoryLineDefinitions.stories());
  }
}
