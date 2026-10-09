package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.enums.*;
import emu.lunarcore.game.scene.Scene;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import lombok.Getter;

/** Source weighted content choices, saved once per authored group and current run. */
public final class RogueMonsterSelection {
  @Entity(useDiscriminator = false)
  @Getter
  public static class Choice {
    private int content;
    private int monster;
    private int npc;
    private int stage;
    private Map<Integer, Integer> weights = new HashMap<>();

    protected Choice() {}

    private Choice(
        GameplayDefinitions.RogueMonsterGroup group, GameplayDefinitions.RogueMonster row) {
      content = group.RogueMonsterGroupID();
      monster = row.RogueMonsterID();
      npc = row.NpcMonsterID();
      stage = row.EventID();
      weights.putAll(group.RogueMonsterListAndWeight());
    }
  }

  public record Binding(
      GameplayDefinitions.RogueMonster mapping,
      GameplayDefinitions.RogueMonsterGroup group,
      Choice choice,
      Map<String, Choice> choices) {
    boolean matches(Scene scene, GroupInfo source) {
      return source != null && matches(scene, source.getId());
    }

    boolean matches(Scene scene, int sourceGroup) {
      var current = RogueMonsterSelection.current(scene, sourceGroup);
      return current != null
          && current.mapping == mapping
          && current.group == group
          && current.choice == choice
          && current.choices == choices;
    }
  }

  private static boolean originalCatalog(Scene scene) {
    return GameplayDefinitions.rogueMonsterGroupsPresent
        || !GameplayDefinitions.rogueMonsterGroups.isEmpty()
        || scene != null
            && (scene.getPlayer().getGameplay().getRogue().isBossEncounterPlanned()
                || scene.getPlayer().getGameplay().getRogue().getBossEncounter() != null);
  }

  private static String key(Scene scene, GroupInfo group) {
    var run = scene.getPlayer().getGameplay().getRogue();
    return run.getSiteId()
        + ":"
        + run.getRoomId()
        + ":"
        + scene.getPlaneId()
        + ":"
        + scene.getFloorId()
        + ":"
        + group.getId();
  }

  static boolean validChoices(Map<String, Choice> choices) {
    if (choices == null || choices.size() > 4096) return false;
    for (var e : choices.entrySet()) {
      var k = e.getKey();
      var v = e.getValue();
      if (k == null
          || k.length() > 60
          || v == null
          || v.content <= 0
          || v.monster <= 0
          || v.npc <= 0
          || v.stage <= 0
          || weighted(v.weights, 0).isEmpty()) return false;
      var parts = k.split(":", -1);
      if (parts.length != 5) return false;
      try {
        for (var part : parts) {
          int n = Integer.parseInt(part);
          if (n <= 0 || !Integer.toString(n).equals(part)) return false;
        }
      } catch (NumberFormatException invalid) {
        return false;
      }
      if (v.weights.getOrDefault(v.monster, 0) <= 0) return false;
    }
    return true;
  }

  /** A ticket covers one unit of original nonnegative integer weight; zero weights are excluded. */
  static OptionalInt weighted(Map<Integer, Integer> weights, int ticket) {
    if (weights == null || weights.isEmpty() || weights.size() > 256 || ticket < 0)
      return OptionalInt.empty();
    long total = 0;
    for (var e : weights.entrySet()) {
      if (e.getKey() == null || e.getKey() <= 0 || e.getValue() == null || e.getValue() < 0)
        return OptionalInt.empty();
      total += e.getValue();
      if (total > Integer.MAX_VALUE) return OptionalInt.empty();
    }
    if (ticket >= total) return OptionalInt.empty();
    long upper = 0;
    for (int id : new TreeSet<>(weights.keySet())) {
      upper += weights.get(id);
      if (ticket < upper) return OptionalInt.of(id);
    }
    return OptionalInt.empty();
  }

  static boolean complete(GameplayDefinitions.RogueMonster row) {
    if (row == null
        || row.RogueMonsterID() <= 0
        || row.NpcMonsterID() <= 0
        || row.EventID() <= 0
        || GameplayDefinitions.rogueMonsters.get(row.RogueMonsterID()) != row) return false;
    var npc = GameData.getNpcMonsterExcelMap().get(row.NpcMonsterID());
    var stage = GameData.getStageExcelMap().get(row.EventID());
    if (npc == null
        || npc.getId() != row.NpcMonsterID()
        || stage == null
        || stage.getId() != row.EventID()
        || stage.getStageType() != StageType.VerseSimulation
        || stage.getMonsterWaves() == null
        || stage.getMonsterWaves().isEmpty()
        || stage.getMonsterWaves().size() > 128) return false;
    int total = 0;
    for (var wave : stage.getMonsterWaves()) {
      if (wave == null || wave.isEmpty() || wave.size() > 5) return false;
      for (int id : wave) {
        var monster = GameData.getMonsterExcelMap().get(id);
        if (id <= 0 || monster == null || monster.getId() != id || ++total > 1024) return false;
      }
    }
    return true;
  }

  private static GameplayDefinitions.RogueMonsterGroup source(Scene scene, GroupInfo group) {
    if (scene == null
        || group == null
        || scene.getPlaneType() != PlaneType.Rogue
        || group.getId() <= 0
        || scene.getDimensionId() != 0
        || scene.getFloorInfo() == null
        || scene.getFloorInfo().getGroups().get(group.getId()) != group) return null;
    var run = scene.getPlayer().getGameplay().getRogue();
    var room = GameplayDefinitions.rooms.get(run.getRoomId());
    if (run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING
        || !run.isInside()
        || run.getSiteId() <= 0
        || room == null
        || room.RogueRoomID() != run.getRoomId()
        || !Set.of(1, 2, 4, 6, 7).contains(room.RogueRoomType())
        || !Objects.equals(run.getRooms().get(run.getSiteId()), room.RogueRoomID())
        || room.GroupWithContent() == null
        || run.getCleared().contains(run.getSiteId())) return null;
    var entry = GameData.getMapEntranceExcelMap().get(room.MapEntrance());
    if (entry == null
        || entry.getId() != room.MapEntrance()
        || entry.getPlaneID() != scene.getPlaneId()
        || entry.getFloorID() != scene.getFloorId()) return null;
    var content = RogueBossEncounters.content(run, room, group.getId());
    var row = content == null ? null : GameplayDefinitions.rogueMonsterGroups.get(content);
    if (row == null
        || row.RogueMonsterGroupID() != content
        || weighted(row.RogueMonsterListAndWeight(), 0).isEmpty()) return null;
    for (var e : row.RogueMonsterListAndWeight().entrySet()) {
      var candidate = GameplayDefinitions.rogueMonsters.get(e.getKey());
      if (e.getValue() > 0
          && (candidate == null
              || candidate.RogueMonsterID() != e.getKey()
              || !complete(candidate))) return null;
    }
    return row;
  }

  private static Binding legacy(Scene scene, int group) {
    if (scene == null || group <= 0) return null;
    var room =
        GameplayDefinitions.rooms.get(scene.getPlayer().getGameplay().getRogue().getRoomId());
    var content =
        room == null || room.GroupWithContent() == null ? null : room.GroupWithContent().get(group);
    if (content == null || content <= 0 || content > (Integer.MAX_VALUE - 1) / 10) return null;
    var row = GameplayDefinitions.rogueMonsters.get(content * 10 + 1);
    return row == null || row.RogueMonsterID() != content * 10 + 1
        ? null
        : new Binding(row, null, null, null);
  }

  public static Binding current(Scene scene, GroupInfo group) {
    if (group == null) return null;
    if (!originalCatalog(scene)) return legacy(scene, group.getId());
    var source = source(scene, group);
    if (source == null) return null;
    var choices = scene.getPlayer().getGameplay().getRogue().getMonsterSelections();
    if (!validChoices(choices)) return null;
    var choice = choices.get(key(scene, group));
    if (choice == null) return null;
    var row = GameplayDefinitions.rogueMonsters.get(choice.monster);
    if (choice.content != source.RogueMonsterGroupID()
        || !choice.weights.equals(source.RogueMonsterListAndWeight())
        || row == null
        || row.RogueMonsterID() != choice.monster
        || choice.npc != row.NpcMonsterID()
        || choice.stage != row.EventID()
        || source.RogueMonsterListAndWeight().getOrDefault(row.RogueMonsterID(), 0) <= 0
        || !complete(row)) return null;
    return new Binding(row, source, choice, choices);
  }

  public static Binding current(Scene scene, int group) {
    if (!originalCatalog(scene)) return legacy(scene, group);
    return scene == null || scene.getFloorInfo() == null
        ? null
        : current(scene, scene.getFloorInfo().getGroups().get(group));
  }

  public static GameplayDefinitions.RogueMonster resolve(
      Scene scene, GroupInfo group, MonsterInfo placement) {
    if (!originalCatalog(scene)) {
      var b = group == null ? null : legacy(scene, group.getId());
      return b == null ? null : b.mapping;
    }
    synchronized (scene.getPlayer()) {
      var source = source(scene, group);
      if (source == null
          || placement == null
          || group.getMonsterList() == null
          || !group.getMonsterList().contains(placement)
          || placement.getID() <= 0
          || !placement.isLoadOnInitial()
          || placement.isIsDelete()
          || placement.isIsClientOnly()
          || group.getMonsterList().stream()
                  .filter(Objects::nonNull)
                  .filter(p -> p.getID() == placement.getID())
                  .count()
              != 1) return null;
      var choices = scene.getPlayer().getGameplay().getRogue().getMonsterSelections();
      if (!validChoices(choices)) return null;
      var key = key(scene, group);
      if (choices.containsKey(key)) {
        var bound = current(scene, group);
        return bound == null ? null : bound.mapping;
      }
      if (choices.size() == 4096) return null;
      int sum =
          (int)
              source.RogueMonsterListAndWeight().values().stream()
                  .mapToLong(Integer::longValue)
                  .sum();
      var id =
          weighted(source.RogueMonsterListAndWeight(), ThreadLocalRandom.current().nextInt(sum))
              .orElseThrow();
      var row = GameplayDefinitions.rogueMonsters.get(id);
      var choice = new Choice(source, row);
      choices.put(key, choice);
      try {
        scene.getPlayer().save();
      } catch (RuntimeException | Error failure) {
        choices.remove(key, choice);
        throw failure;
      }
      return row;
    }
  }

  private RogueMonsterSelection() {}
}
