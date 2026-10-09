package org.hkrpg.server.gameplay;

import com.google.gson.JsonObject;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ChallengeExcel;
import emu.lunarcore.game.challenge.ChallengeType;
import java.nio.file.Path;
import java.util.*;

/** Original two-half Normal story challenges; Fever and Tierce need additional battle rules. */
public final class StoryChallengeCatalog {
  private static final Map<Integer, JsonObject> mazes = new HashMap<>();
  private static final Map<Integer, JsonObject> groups = new HashMap<>();
  private static final Map<Integer, JsonObject> extras = new HashMap<>();

  public static void load(Path root) {
    mazes.clear();
    groups.clear();
    extras.clear();
    GameplayDefinitions.rows(
        root, "ChallengeStoryMazeConfig", JsonObject.class, row -> put(mazes, row, "ID"));
    GameplayDefinitions.rows(
        root, "ChallengeStoryGroupConfig", JsonObject.class, row -> put(groups, row, "GroupID"));
    GameplayDefinitions.rows(
        root, "ChallengeStoryGroupExtra", JsonObject.class, row -> put(extras, row, "GroupID"));
  }

  private static void put(Map<Integer, JsonObject> map, JsonObject row, String key) {
    int id = integer(row, key);
    if (id <= 0 || map.putIfAbsent(id, row.deepCopy()) != null)
      throw new IllegalStateException("Invalid or duplicate story challenge resource identity");
  }

  private static int integer(JsonObject row, String key) {
    return row != null && row.has(key) ? row.get(key).getAsInt() : 0;
  }

  private static List<Integer> list(JsonObject row, String key) {
    if (row == null || !row.has(key) || !row.get(key).isJsonArray()) return null;
    var result = new ArrayList<Integer>();
    row.getAsJsonArray(key).forEach(value -> result.add(value.getAsInt()));
    return result;
  }

  private static boolean named(JsonObject row, String key, String value) {
    return row != null && row.has(key) && value.equals(row.get(key).getAsString());
  }

  public static boolean binding(int id) {
    var row = GameData.getMazeBuffExcelMap().get(id, 1);
    return id > 0 && row != null && row.getID() == id && row.getLv() == 1;
  }

  public static boolean supports(ChallengeExcel excel) {
    if (excel == null
        || excel.getType() != ChallengeType.STORY
        || excel.getStageNum() != 2
        || excel.getStoryExcel() == null
        || excel.getStoryExcel().getId() != excel.getId()
        || excel.getStoryExcel().getTurnLimit() <= 0
        || excel.getStoryExcel().getClearScore() <= 0
        || excel.getStoryExcel().getBattleTargetID() == null
        || excel.getStoryExcel().getBattleTargetID().isEmpty()) return false;
    var maze = mazes.get(excel.getId());
    var group = groups.get(excel.getGroupID());
    var extra = extras.get(excel.getGroupID());
    var options = list(extra, "BuffList");
    var sub = list(extra, "SubMazeBuffList");
    return integer(maze, "ID") == excel.getId()
        && integer(maze, "GroupID") == excel.getGroupID()
        && integer(maze, "StageNum") == 2
        && integer(maze, "MapEntranceID") == excel.getMapEntranceID()
        && integer(maze, "MapEntranceID2") == excel.getMapEntranceID()
        && integer(maze, "MazeBuffID") == excel.getMazeBuffID()
        && named(group, "ChallengeGroupType", "Story")
        && integer(group, "GroupID") == excel.getGroupID()
        && integer(group, "MazeBuffID") == excel.getMazeBuffID()
        && named(extra, "StoryType", "Normal")
        && integer(extra, "GroupID") == excel.getGroupID()
        && sub != null
        && sub.isEmpty()
        && options != null
        && !options.isEmpty()
        && new HashSet<>(options).size() == options.size()
        && options.stream().allMatch(StoryChallengeCatalog::binding)
        && binding(excel.getMazeBuffID());
  }

  public static boolean accepts(ChallengeExcel excel, int first, int second) {
    if (!supports(excel)) return false;
    var options = list(extras.get(excel.getGroupID()), "BuffList");
    return options.contains(first) && options.contains(second) && binding(first) && binding(second);
  }

  private StoryChallengeCatalog() {}
}
