package org.hkrpg.server.gameplay;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Explicit operator pools per source map, with legacy pools for unconfigured sites. */
final class RogueMapPools {
  record Assignment(int start, Map<Integer, Integer> rooms) {}

  static void load(Path file) {
    GameplayDefinitions.roomPoolByMap.clear();
    if (!Files.exists(file)) return;
    try {
      if (!Files.isRegularFile(file) || Files.size(file) > 262144)
        throw new IllegalArgumentException("Invalid pool file size or type");
      JsonObject root;
      try (var reader = Files.newBufferedReader(file)) {
        root = JsonParser.parseReader(reader).getAsJsonObject();
      }
      if (!root.keySet().equals(Set.of("maps")) || !root.get("maps").isJsonObject())
        throw new IllegalArgumentException("Expected maps object");
      var staged = new HashMap<Integer, Map<Integer, List<Integer>>>();
      for (var map : root.getAsJsonObject("maps").entrySet()) {
        int id = key(map.getKey());
        var sites = GameplayDefinitions.maps.get(id);
        if (sites == null || !map.getValue().isJsonObject())
          throw new IllegalArgumentException("Unknown source map " + id);
        var configured = new HashMap<Integer, List<Integer>>();
        for (var entry : map.getValue().getAsJsonObject().entrySet()) {
          int site = key(entry.getKey());
          if (sites.stream().noneMatch(s -> s.SiteID() == site)
              || !entry.getValue().isJsonArray()
              || entry.getValue().getAsJsonArray().size() > 128)
            throw new IllegalArgumentException("Invalid source site " + site);
          var rooms = new ArrayList<Integer>();
          for (var value : entry.getValue().getAsJsonArray()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
              throw new IllegalArgumentException("Room ID must be an integer");
            int room = value.getAsBigDecimal().intValueExact();
            if (room <= 0 || !GameplayDefinitions.rooms.containsKey(room))
              throw new IllegalArgumentException("Unknown source room " + room);
            rooms.add(room);
          }
          configured.put(site, List.copyOf(rooms));
        }
        staged.put(id, Map.copyOf(configured));
      }
      GameplayDefinitions.roomPoolByMap.putAll(staged);
    } catch (Exception invalid) {
      throw new IllegalStateException("Invalid RogueMapGenByMap.json", invalid);
    }
  }

  private static int key(String value) {
    int id = Integer.parseInt(value);
    if (id <= 0 || !Integer.toString(id).equals(value))
      throw new IllegalArgumentException("Map and site IDs must be positive canonical integers");
    return id;
  }

  static Optional<Assignment> assign(int mapId, int uid, int area) {
    var sites = GameplayDefinitions.maps.get(mapId);
    if (sites == null || sites.isEmpty() || sites.size() > 128) return Optional.empty();
    var indexed = new HashMap<Integer, GameplayDefinitions.Site>();
    int start = 0;
    for (var site : sites) {
      if (site == null
          || site.RogueMapID() != mapId
          || site.SiteID() <= 0
          || indexed.putIfAbsent(site.SiteID(), site) != null) return Optional.empty();
      if (site.IsStart()) {
        if (start != 0) return Optional.empty();
        start = site.SiteID();
      }
    }
    if (start == 0) return Optional.empty();
    var degrees = new HashMap<Integer, Integer>();
    indexed.keySet().forEach(id -> degrees.put(id, 0));
    for (var site : sites) {
      var next = site.NextSiteIDList();
      if (next == null) continue; // Legacy resource records allow an omitted terminal list.
      var distinct = new HashSet<Integer>();
      for (var id : next) {
        if (id == null || id == site.SiteID() || !indexed.containsKey(id) || !distinct.add(id))
          return Optional.empty();
        degrees.compute(id, (key, count) -> count + 1);
      }
    }
    if (degrees.get(start) != 0) return Optional.empty();
    var reachable = new HashSet<Integer>();
    var queue = new ArrayDeque<Integer>();
    queue.add(start);
    while (!queue.isEmpty()) {
      int id = queue.remove();
      if (!reachable.add(id)) continue;
      var next = indexed.get(id).NextSiteIDList();
      if (next != null) queue.addAll(next);
    }
    if (reachable.size() != indexed.size()) return Optional.empty();
    degrees.forEach(
        (id, count) -> {
          if (count == 0) queue.add(id);
        });
    int traversed = 0;
    while (!queue.isEmpty()) {
      int id = queue.remove();
      traversed++;
      var next = indexed.get(id).NextSiteIDList();
      if (next == null) continue;
      for (var target : next)
        if (degrees.compute(target, (key, count) -> count - 1) == 0) queue.add(target);
    }
    if (traversed != indexed.size()) return Optional.empty();
    var scoped = GameplayDefinitions.roomPoolByMap.getOrDefault(mapId, Map.of());
    // The first story universe is a single authored room, not the legacy site-1 pool used by
    // random worlds. Preserve an explicit operator override, including an empty disabled pool.
    if (mapId == 1 && area == 100 && !scoped.containsKey(1)) {
      var source = GameplayDefinitions.areas.get(area);
      var room = GameplayDefinitions.rooms.get(100);
      if (source == null || source.RogueAreaID() != 100 || source.AreaProgress() != 0
          || source.Difficulty() != 1 || start != 1 || sites.size() != 1
          || room == null || room.RogueRoomID() != 100 || room.RogueRoomType() != 1
          || room.MapEntrance() != 8000101 || room.GroupID() != 10
          || !Map.of(10, 1006, 3, 1001, 4, 1002, 5, 1003, 11, 1004, 12, 1005)
              .equals(room.GroupWithContent())) return Optional.empty();
      return Optional.of(new Assignment(1, Map.of(1, 100)));
    }
    // The second story test has three fixed rooms, distinct from the first area-100 run.
    // An explicit operator pool retains precedence and is checked by the story entry.
    if (mapId == 2 && area == 100 && !GameplayDefinitions.roomPoolByMap.containsKey(2)) {
      var source = GameplayDefinitions.areas.get(area);
      var a = GameplayDefinitions.rooms.get(201);
      var b = GameplayDefinitions.rooms.get(202);
      var c = GameplayDefinitions.rooms.get(203);
      if (source == null || source.RogueAreaID() != 100 || source.AreaProgress() != 0
          || source.Difficulty() != 1 || start != 1 || sites.size() != 3
          || !indexed.containsKey(2) || !indexed.containsKey(3)
          || !List.of(2).equals(indexed.get(1).NextSiteIDList())
          || !List.of(3).equals(indexed.get(2).NextSiteIDList())
          || indexed.get(3).NextSiteIDList() == null || !indexed.get(3).NextSiteIDList().isEmpty()
          || a == null || a.RogueRoomID() != 201 || a.RogueRoomType() != 1
          || a.MapEntrance() != 8000102 || a.GroupID() != 19
          || !Map.of(19, 2003, 3, 2001, 4, 2002).equals(a.GroupWithContent())
          || b == null || b.RogueRoomID() != 202 || b.RogueRoomType() != 1
          || b.MapEntrance() != 8000102 || b.GroupID() != 18
          || !Map.of(18, 2006, 6, 2004, 7, 2005).equals(b.GroupWithContent())
          || c == null || c.RogueRoomID() != 203 || c.RogueRoomType() != 7
          || c.MapEntrance() != 8000102 || c.GroupID() != 29
          || !Map.of(29, 2007).equals(c.GroupWithContent())) return Optional.empty();
      return Optional.of(new Assignment(1, Map.of(1, 201, 2, 202, 3, 203)));
    }
    if (mapId == 3 && area == 110 && !GameplayDefinitions.roomPoolByMap.containsKey(3))
      return RogueTeachingFlow.assignment();
    var rooms = new HashMap<Integer, Integer>();
    for (var site : sites) {
      var pool =
          scoped.containsKey(site.SiteID())
              ? scoped.get(site.SiteID())
              : GameplayDefinitions.roomPool.get(site.SiteID());
      if (pool == null
          || pool.isEmpty()
          || pool.stream()
              .anyMatch(id -> id == null || id <= 0 || !GameplayDefinitions.rooms.containsKey(id)))
        return Optional.empty();
      rooms.put(
          site.SiteID(),
          pool.get(Math.floorMod(Objects.hash(uid, area, site.SiteID()), pool.size())));
    }
    return Optional.of(new Assignment(start, Map.copyOf(rooms)));
  }

  private RogueMapPools() {}
}
