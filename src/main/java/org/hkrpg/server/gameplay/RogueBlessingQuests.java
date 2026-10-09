package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;

/** Distinct visible classic blessings; resource-linked type 3 tiers are permanent records. */
public final class RogueBlessingQuests {
  public record PathRow(int RogueBuffType, List<Integer> RugueBuffTypeRewardQuestList) {}

  private static volatile Map<Integer, PathRow> paths = Map.of();

  public static void load(Path root) {
    if (!Files.isRegularFile(root.resolve("ExcelOutput/RogueBuffType.json"))) {
      paths = Map.of();
      return;
    }
    var next = new TreeMap<Integer, PathRow>();
    var allQuests = new HashSet<Integer>();
    GameplayDefinitions.rows(
        root,
        "RogueBuffType",
        PathRow.class,
        row -> {
          var ids = row.RugueBuffTypeRewardQuestList();
          if (row.RogueBuffType() <= 0
              || ids == null
              || ids.size() > 128
              || ids.stream().anyMatch(id -> id == null || id <= 0)
              || new HashSet<>(ids).size() != ids.size()
              || next.containsKey(row.RogueBuffType())
              || ids.stream().anyMatch(id -> !allQuests.add(id)))
            throw new IllegalArgumentException(
                "Invalid or overlapping rogue blessing quest catalogue");
          next.put(row.RogueBuffType(), new PathRow(row.RogueBuffType(), List.copyOf(ids)));
        });
    if (next.isEmpty()) throw new IllegalStateException("Empty rogue blessing quest catalogue");
    paths = Collections.unmodifiableMap(next);
  }

  private static boolean linked(int id, int path) {
    var row = paths.get(path);
    return row != null && row.RugueBuffTypeRewardQuestList().contains(id);
  }

  public static boolean belongs(QuestExcel definition) {
    if (definition == null) return false;
    var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
    return paths.values().stream()
            .anyMatch(p -> p.RugueBuffTypeRewardQuestList().contains(definition.getId()))
        || rule != null && rule.isType("RogueUnlockBuff", 735);
  }

  private static FinishWayExcel rule(QuestExcel definition) {
    if (definition == null
        || definition.getId() <= 0
        || GameData.getQuestExcelMap().get(definition.getId()) != definition) return null;
    var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
    if (rule == null
        || rule.getId() != definition.getFinishWayID()
        || !rule.isType("RogueUnlockBuff", 735)
        || !"Equal".equals(rule.getParamType())
        || !paths.containsKey(rule.getParamInt1())
        || rule.getProgress() <= 0
        || rule.getParamInt2() != 0
        || rule.getParamInt3() != 0
        || rule.getParamStr1() == null
        || !rule.getParamStr1().isBlank()
        || rule.getParamIntList() == null
        || !rule.getParamIntList().isEmpty()
        || rule.getParamItemList() == null
        || !rule.getParamItemList().isEmpty()) return null;
    if (definition.getQuestType() == 1 || definition.getQuestType() == 4) {
      // A catalogue-linked tier must retain its source path, even if its finish row is replaced.
      if (paths.values().stream()
              .anyMatch(p -> p.RugueBuffTypeRewardQuestList().contains(definition.getId()))
          && !linked(definition.getId(), rule.getParamInt1())) return null;
      return rule;
    }
    return definition.getQuestType() == 3
            && linked(definition.getId(), rule.getParamInt1())
            && rule.isIsBackTrack()
            && "AutoUnlock".equals(definition.getUnlockType())
            && definition.getUnlockParamList() != null
            && definition.getUnlockParamList().isEmpty()
        ? rule
        : null;
  }

  private static Set<Integer> eligible(int path) {
    var rows = new HashMap<Integer, List<GameplayDefinitions.Blessing>>();
    for (var row : GameplayDefinitions.blessings)
      if (row != null
          && row.MazeBuffID() > 0
          && row.MazeBuffLevel() == 1
          && row.RogueVersion() == 1)
        rows.computeIfAbsent(row.MazeBuffID(), ignored -> new ArrayList<>()).add(row);
    var ids = new HashSet<Integer>();
    rows.forEach(
        (id, values) -> {
          if (values.size() != 1) return;
          var row = values.getFirst();
          var maze = GameData.getMazeBuffExcelMap().get(id, 1);
          if (row.IsShow()
              && row.RogueBuffType() == path
              && maze != null
              && maze.getID() == id
              && maze.getLv() == 1) ids.add(id);
        });
    return ids;
  }

  private static boolean historyValid(Player player) {
    var history = player.getGameplay().getRogueCollectedBuffs();
    return history != null
        && history.size() <= 4096
        && history.stream().allMatch(id -> id != null && id > 0);
  }

  public static boolean hasCollection(Player player) {
    synchronized (player) {
      return historyValid(player)
          && GameData.getQuestExcelMap().values().stream()
              .anyMatch(
                  q -> {
                    var r = rule(q);
                    return r != null
                        && eligible(r.getParamInt1()).stream()
                            .anyMatch(player.getGameplay().getRogueCollectedBuffs()::contains);
                  });
    }
  }

  public static boolean refresh(Player player) {
    synchronized (player) {
      if (!historyValid(player)) return false;
      boolean changed = false;
      var pools = new HashMap<Integer, Set<Integer>>();
      for (var definition : GameData.getQuestExcelMap().values()) {
        var rule = rule(definition);
        if (rule == null) continue;
        var pool = pools.computeIfAbsent(rule.getParamInt1(), RogueBlessingQuests::eligible);
        if (rule.getProgress() > pool.size()) continue;
        var state = player.getQuestStates().get(definition.getId());
        if (state == null
            && definition.getQuestType() == 3
            && !player.getQuestStates().containsKey(definition.getId())) {
          state = new QuestState(definition.getId());
          player.getQuestStates().put(definition.getId(), state);
          changed = true;
        }
        if (!MissionStateRecords.doing(state, definition.getId(), rule.getProgress())) continue;
        int count =
            (int)
                pool.stream()
                    .filter(player.getGameplay().getRogueCollectedBuffs()::contains)
                    .count();
        changed |=
            state.advance(
                count - state.getProgress(), rule.getProgress(), System.currentTimeMillis() / 1000);
      }
      return changed;
    }
  }

  public static QuestState state(Player player, int id) {
    synchronized (player) {
      var definition = GameData.getQuestExcelMap().get(id);
      var rule = rule(definition);
      if (rule == null || !historyValid(player)) return null;
      var state = player.getQuestStates().get(id);
      if (!MissionStateRecords.doing(state, id, rule.getProgress())
          && !MissionStateRecords.completed(state, id, rule.getProgress())) return null;
      var pool = eligible(rule.getParamInt1());
      int actual =
          (int)
              pool.stream().filter(player.getGameplay().getRogueCollectedBuffs()::contains).count();
      return rule.getProgress() <= pool.size() && state.getProgress() <= actual ? state : null;
    }
  }

  public static List<QuestState> permanentStates(Player player) {
    synchronized (player) {
      return GameData.getQuestExcelMap().values().stream()
          .filter(q -> q.getQuestType() == 3)
          .map(q -> state(player, q.getId()))
          .filter(Objects::nonNull)
          .toList();
    }
  }

  private RogueBlessingQuests() {}
}
