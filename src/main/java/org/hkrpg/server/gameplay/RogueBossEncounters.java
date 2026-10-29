package org.hkrpg.server.gameplay;

import com.google.gson.*;
import dev.morphia.annotations.Entity;
import emu.lunarcore.data.GameData;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import lombok.Getter;

/**
 * Explicit world-specific source content for shared boss-room geometry, frozen for each new run.
 */
final class RogueBossEncounters {
  @Entity(useDiscriminator = false)
  @Getter
  public static class Plan {
    private int area, map, content;
    private String signature;
    private boolean levelPlanned;
    private int level;
    private Map<Integer, Integer> levels;

    protected Plan() {}

    private Plan(GameplayDefinitions.Area source, int content, String signature) {
      area = source.RogueAreaID();
      map = source.mapId();
      this.content = content;
      this.signature = signature;
      levels = new HashMap<>(source.DisplayMonsterMap());
      level = levels.values().iterator().next();
      levelPlanned = true;
    }
  }

  record Prepared(boolean valid, Plan plan) {}

  private static final Gson GSON = new Gson();

  static void load(Path file) {
    GameplayDefinitions.bossContentByArea.clear();
    if (!Files.exists(file)) return;
    try {
      if (!Files.isRegularFile(file) || Files.size(file) > 65536)
        throw new IllegalArgumentException("Invalid boss pool file size or type");
      JsonObject root;
      try (var reader = Files.newBufferedReader(file)) {
        root = JsonParser.parseReader(reader).getAsJsonObject();
      }
      if (!root.keySet().equals(Set.of("areas")) || !root.get("areas").isJsonObject())
        throw new IllegalArgumentException("Expected areas object");
      var staged = new HashMap<Integer, Integer>();
      for (var entry : root.getAsJsonObject("areas").entrySet()) {
        int id = Integer.parseInt(entry.getKey());
        var area = GameplayDefinitions.areas.get(id);
        if (id <= 0
            || !Integer.toString(id).equals(entry.getKey())
            || area == null
            || area.AreaProgress() < 1
            || area.AreaProgress() > 9
            || !entry.getValue().isJsonPrimitive()
            || !entry.getValue().getAsJsonPrimitive().isNumber())
          throw new IllegalArgumentException("Invalid source boss area");
        int content = entry.getValue().getAsBigDecimal().intValueExact();
        // The nine authored world-specific group rows are recorded in the fixed source fixture.
        if (content != 10901 + area.AreaProgress() * 1000
            || !GameplayDefinitions.rogueMonsterGroups.containsKey(content))
          throw new IllegalArgumentException("Invalid source world boss content");
        staged.put(id, content);
      }
      GameplayDefinitions.bossContentByArea.putAll(staged);
    } catch (Exception invalid) {
      throw new IllegalStateException("Invalid RogueBossGen.json", invalid);
    }
  }

  private static String signature(int content) {
    var group = GameplayDefinitions.rogueMonsterGroups.get(content);
    if (group == null
        || group.RogueMonsterGroupID() != content
        || RogueMonsterSelection.weighted(group.RogueMonsterListAndWeight(), 0).isEmpty())
      return null;
    var snapshot = new ArrayList<Object>();
    snapshot.add(content);
    snapshot.add(new TreeMap<>(group.RogueMonsterListAndWeight()));
    for (var entry : new TreeMap<>(group.RogueMonsterListAndWeight()).entrySet()) {
      if (entry.getValue() == 0) continue;
      var row = GameplayDefinitions.rogueMonsters.get(entry.getKey());
      if (row == null
          || row.RogueMonsterID() != entry.getKey()
          || !RogueMonsterSelection.complete(row)) return null;
      snapshot.add(row);
      // Preserve the exact resource-bound stage settings and original wave order.
      var stage = GameData.getStageExcelMap().get(row.EventID());
      snapshot.add(GSON.toJsonTree(stage));
      snapshot.add(
          stage.getMonsterWaves().stream().map(w -> w.intStream().boxed().toList()).toList());
    }
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(GSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }

  static Prepared prepare(GameplayDefinitions.Area area, Map<Integer, Integer> assignments) {
    if (!GameplayDefinitions.bossContentByArea.containsKey(area.RogueAreaID()))
      return new Prepared(true, null);
    var content = GameplayDefinitions.bossContentByArea.get(area.RogueAreaID());
    if (content == null || content != 10901 + area.AreaProgress() * 1000)
      return new Prepared(false, null);
    String signature = signature(content);
    if (signature == null || sourceLevel(area.DisplayMonsterMap(), content) <= 0)
      return new Prepared(false, null);
    boolean found = false;
    for (int id : assignments.values()) {
      var room = GameplayDefinitions.rooms.get(id);
      if (room == null) return new Prepared(false, null);
      if (room.RogueRoomType() != 7) continue;
      if (!oneCombatGroup(room)) return new Prepared(false, null);
      found = true;
    }
    return new Prepared(found, found ? new Plan(area, content, signature) : null);
  }

  private static boolean oneCombatGroup(GameplayDefinitions.Room room) {
    return room.GroupWithContent() != null
        && room.GroupWithContent().entrySet().stream()
            .noneMatch(
                e ->
                    e.getKey() == null
                        || e.getKey() <= 0
                        || e.getValue() == null
                        || e.getValue() < 0)
        && room.GroupWithContent().values().stream().filter(c -> c > 0).count() == 1;
  }

  // Explicit boss-only flat level policy. Preview IDs can differ from the authored NPC or wave
  // variants; at least one actual source NPC/wave must anchor the uniform preview level.
  private static int sourceLevel(Map<Integer, Integer> levels, int content) {
    if (levels == null || levels.isEmpty() || levels.size() > 5) return -1;
    Integer level = levels.values().iterator().next();
    if (level == null
        || level < 1
        || level > 100
        || levels.entrySet().stream()
            .anyMatch(e -> e.getKey() == null || e.getKey() <= 0 || !level.equals(e.getValue())))
      return -1;
    var group = GameplayDefinitions.rogueMonsterGroups.get(content);
    if (group == null || group.RogueMonsterListAndWeight() == null) return -1;
    for (var weighted : group.RogueMonsterListAndWeight().entrySet()) {
      if (weighted.getValue() == null || weighted.getValue() < 0) return -1;
      if (weighted.getValue() == 0) continue;
      var row = GameplayDefinitions.rogueMonsters.get(weighted.getKey());
      if (row == null || !RogueMonsterSelection.complete(row)) return -1;
      var stage = GameData.getStageExcelMap().get(row.EventID());
      if (!levels.containsKey(row.NpcMonsterID())
          && stage.getMonsterWaves().stream()
              .noneMatch(w -> w.intStream().anyMatch(levels::containsKey))) return -1;
    }
    return level;
  }

  private static boolean levelMatches(Plan plan, GameplayDefinitions.Area area) {
    // Historical plans retain their previous absence of an override.
    if (!plan.levelPlanned)
      return plan.level == 0 && (plan.levels == null || plan.levels.isEmpty());
    return plan.level >= 1
        && plan.level <= 100
        && plan.levels != null
        && plan.levels.equals(area.DisplayMonsterMap())
        && plan.level == sourceLevel(plan.levels, plan.content);
  }

  static int level(GameplayState.RogueRun run, GameplayDefinitions.Room room, int group) {
    if (room == null) return -1;
    if (room.RogueRoomType() != 7
        || room.GroupWithContent() == null
        || room.GroupWithContent().getOrDefault(group, 0) == 0) return 0;
    if (content(run, room, group) == null) return -1;
    var plan = run.getBossEncounter();
    return plan == null || !plan.levelPlanned ? 0 : plan.level;
  }

  static Integer content(GameplayState.RogueRun run, GameplayDefinitions.Room room, int group) {
    if (room == null || room.GroupWithContent() == null) return null;
    var original = room.GroupWithContent().get(group);
    // In the first story room the authored link 1006 has no row in the 4.6 catalogue. Its only
    // level-seven Trampler encounter is 1101/11011; use it solely for that exact missing link.
    // If the original link is supplied later, it takes precedence over this compatibility mapping.
    if (run != null && run.getAreaId() == 100 && run.getMapId() == 1 && run.getSiteId() == 1
        && run.getRoomId() == 100 && Objects.equals(run.getRooms().get(1), 100)
        && room.RogueRoomID() == 100 && room.RogueRoomType() == 1 && room.MapEntrance() == 8000101
        && group == 10 && Objects.equals(original, 1006)
        && !GameplayDefinitions.rogueMonsterGroups.containsKey(1006)) {
      var source = GameplayDefinitions.rogueMonsterGroups.get(1101);
      var monster = GameplayDefinitions.rogueMonsters.get(11011);
      return source != null && source.RogueMonsterGroupID() == 1101
              && Map.of(11011, 1).equals(source.RogueMonsterListAndWeight())
              && new GameplayDefinitions.RogueMonster(11011, 8013010, 80011011).equals(monster)
          ? 1101 : null;
    }
    var plan = run.getBossEncounter();
    if (room.RogueRoomType() != 7 || original == null || original == 0) return original;
    if (!run.isBossEncounterPlanned()) return plan == null ? original : null;
    if (plan == null) return null;
    var area = GameplayDefinitions.areas.get(run.getAreaId());
    if (area == null
        || area.mapId() != run.getMapId()
        || plan.content != 10901 + area.AreaProgress() * 1000
        || !oneCombatGroup(room)
        || plan.area != run.getAreaId()
        || plan.map != run.getMapId()
        || plan.content <= 0
        || !levelMatches(plan, area)
        || plan.signature == null
        || plan.signature.length() != 64
        || !plan.signature.equals(signature(plan.content))) return null;
    return plan.content;
  }

  private RogueBossEncounters() {}
}
