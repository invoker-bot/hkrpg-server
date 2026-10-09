package org.hkrpg.server.gameplay;

import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.game.player.Player;
import java.nio.file.Path;
import java.util.*;

/** Immutable, atomically installed story-line resource tables. */
public final class StoryLineDefinitions {
  public record Condition(String Type, String Param) {
    public int id() {
      try {
        return Param != null && Param.matches("[1-9][0-9]{0,9}") ? Integer.parseInt(Param) : 0;
      } catch (NumberFormatException invalid) {
        return 0;
      }
    }

    public boolean matches(Player player) {
      return switch (Objects.toString(Type, "")) {
        case "SubMissionTaken" -> StoryLineConditions.taken(player, id());
        case "FinishSubMission" -> StoryLineConditions.finished(player, id());
        default -> false;
      };
    }
  }

  public record Story(
      int StoryLineID,
      Condition BeginCondition,
      Condition EndCondition,
      String ShowCondition,
      int InitEntranceID,
      int InitGroupID,
      int InitAnchorID,
      String PerformanceStoryAvatar,
      int EarlyAccessContentID) {}

  public record StoryFloor(
      int FloorID, int StoryLineID, String ConditionExpression, int DimensionID) {}

  public record TrialData(
      int StoryLineID,
      List<Integer> TrialAvatarList,
      List<Integer> InitTrialAvatarList,
      int CaptainAvatarID,
      boolean SkipJoinLineup) {
    public TrialData {
      TrialAvatarList = TrialAvatarList == null ? List.of() : List.copyOf(TrialAvatarList);
      InitTrialAvatarList =
          InitTrialAvatarList == null ? List.of() : List.copyOf(InitTrialAvatarList);
    }
  }

  private record FloorKey(int story, int floor) {}

  private record Catalog(
      Map<Integer, Story> stories,
      Map<FloorKey, StoryFloor> floors,
      Map<Integer, TrialData> trials) {}

  private static volatile Catalog catalog = new Catalog(Map.of(), Map.of(), Map.of());

  public static Map<Integer, Story> stories() {
    return catalog.stories;
  }

  public static TrialData trials(int id) {
    return catalog.trials.get(id);
  }

  public static StoryFloor floor(int story, int floor) {
    return catalog.floors.get(new FloorKey(story, floor));
  }

  public static int floorCount() {
    return catalog.floors.size();
  }

  public static void load(Path root) {
    var stories = new TreeMap<Integer, Story>();
    var floors = new HashMap<FloorKey, StoryFloor>();
    var trials = new TreeMap<Integer, TrialData>();
    GameplayDefinitions.rows(
        root,
        "StoryLine",
        Story.class,
        row -> {
          if (row.StoryLineID <= 0
              || row.BeginCondition == null
              || row.EndCondition == null
              || row.BeginCondition.id() <= 0
              || row.EndCondition.id() <= 0
              || !"SubMissionTaken".equals(row.BeginCondition.Type)
              || !"FinishSubMission".equals(row.EndCondition.Type)
              || !StoryLineConditions.supported(row.ShowCondition)
              || row.InitEntranceID < 0
              || row.InitGroupID < 0
              || row.InitAnchorID < 0
              || stories.putIfAbsent(row.StoryLineID, row) != null)
            throw new IllegalStateException("Invalid or duplicate story line: " + row.StoryLineID);
        });
    GameplayDefinitions.rows(
        root,
        "StoryLineFloorData",
        StoryFloor.class,
        row -> {
          var key = new FloorKey(row.StoryLineID, row.FloorID);
          if (row.FloorID <= 0
              || row.DimensionID < 0
              || !stories.containsKey(row.StoryLineID)
              || !StoryLineConditions.supported(row.ConditionExpression)
              || floors.putIfAbsent(key, row) != null)
            throw new IllegalStateException(
                "Invalid or duplicate story floor: " + row.StoryLineID + "/" + row.FloorID);
        });
    GameplayDefinitions.rows(
        root,
        "StroyLineTrialAvatarData",
        TrialData.class,
        row -> {
          if (!stories.containsKey(row.StoryLineID)
              || !validIds(row.TrialAvatarList)
              || !validIds(row.InitTrialAvatarList)
              || row.InitTrialAvatarList.size() > 4
              || !row.TrialAvatarList.containsAll(row.InitTrialAvatarList)
              || !row.TrialAvatarList.contains(row.CaptainAvatarID)
              || trials.putIfAbsent(row.StoryLineID, row) != null)
            throw new IllegalStateException("Invalid or duplicate story trial: " + row.StoryLineID);
        });
    catalog =
        new Catalog(
            Collections.unmodifiableMap(stories),
            Map.copyOf(floors),
            Collections.unmodifiableMap(trials));
  }

  public static boolean canEnter(Player player, int id) {
    var story = catalog.stories.get(id);
    if (story == null || player == null) return false;
    synchronized (player) {
      return StoryLineConditions.known(player, story.EndCondition.id())
          && story.BeginCondition.matches(player)
          && !story.EndCondition.matches(player);
    }
  }

  public static boolean visible(Player player, int id) {
    var story = catalog.stories.get(id);
    return story != null
        && canEnter(player, id)
        && StoryLineConditions.matches(player, story.ShowCondition);
  }

  /** A configured but locked/missing dimension never falls back to the main world. */
  public static OptionalInt dimension(Player player, int storyId, FloorInfo floor) {
    if (floor == null || player == null) return OptionalInt.empty();
    if (storyId == 0) return OptionalInt.of(0);
    var current = catalog;
    var row = current.floors.get(new FloorKey(storyId, floor.getFloorID()));
    if (row == null
        || !canEnter(player, storyId)
        || !StoryLineConditions.matches(player, row.ConditionExpression)
        || floor.getDimensionList() == null
        || floor.getDimensionList().stream().noneMatch(d -> d.getID() == row.DimensionID))
      return OptionalInt.empty();
    return OptionalInt.of(row.DimensionID);
  }

  private static boolean validIds(List<Integer> ids) {
    return !ids.isEmpty()
        && ids.stream().allMatch(id -> id != null && id > 0)
        && new HashSet<>(ids).size() == ids.size();
  }

  private StoryLineDefinitions() {}
}
