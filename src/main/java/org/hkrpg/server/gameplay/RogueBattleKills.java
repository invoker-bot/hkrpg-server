package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.enums.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import java.util.*;

/** Frozen original encounter roster; death telemetry is accepted evidence, not combat replay. */
public final class RogueBattleKills {
  private record Mob(int stage, int monster) {}

  private record Actor(
      EntityMonster entity,
      int id,
      int group,
      int instance,
      int level,
      GroupInfo sourceGroup,
      MonsterInfo placement,
      NpcMonsterExcel npc,
      StageExcel stage,
      GameplayDefinitions.RogueMonster sourceMapping,
      RogueMonsterSelection.Binding sourceBinding) {
    boolean matches(Scene scene) {
      return entity.getScene() == scene
          && entity.getEntityId() == id
          && scene.getEntities().get(id) == entity
          && entity.getGroupId() == group
          && entity.getInstId() == instance
          && entity.getCustomLevel() == level
          && entity.getExcel() == npc
          && GameData.getNpcMonsterExcelMap().get(npc.getId()) == npc
          && entity.getEventId() == stage.getId()
          && entity.getCustomStage() == stage
          && GameplayDefinitions.rogueMonsters.get(sourceMapping.RogueMonsterID()) == sourceMapping
          && sourceBinding.matches(scene, sourceGroup)
          && entity.getMonsterInfo() == placement
          && scene.getFloorInfo().getGroups().get(group) == sourceGroup
          && sourceGroup.getId() == group
          && sourceGroup.getMonsterList() != null
          && sourceGroup.getMonsterList().stream()
                  .filter(Objects::nonNull)
                  .filter(p -> p.getID() == instance)
                  .count()
              == 1
          && sourceGroup.getMonsterList().contains(placement)
          && placement.getID() == instance
          && placement.isLoadOnInitial()
          && !placement.isIsDelete()
          && !placement.isIsClientOnly();
    }
  }

  private record Wave(BattleMonsterWave wave, StageExcel stage, List<Integer> monsters, int level) {}

  private static String actorKey(GameplayState.RogueRun run, Scene scene, int group, int instance) {
    return run.getSiteId()
        + ":"
        + run.getRoomId()
        + ":"
        + scene.getPlaneId()
        + ":"
        + scene.getFloorId()
        + ":"
        + group
        + ":"
        + instance;
  }

  public static boolean alreadyDefeated(Scene scene, GroupInfo group, MonsterInfo info) {
    var run = scene.getPlayer().getGameplay().getRogue();
    var defeats = run.getDefeatedRogueMonsters();
    return !RoguePropDestruction.validReceipts(defeats)
        || defeats.contains(actorKey(run, scene, group.getId(), info.getID()));
  }

  private static List<List<Integer>> shape(StageExcel stage) {
    if (stage == null
        || stage.getId() <= 0
        || stage.getStageType() != StageType.VerseSimulation
        || GameData.getStageExcelMap().get(stage.getId()) != stage
        || stage.getMonsterWaves() == null
        || stage.getMonsterWaves().isEmpty()
        || stage.getMonsterWaves().size() > 128) return null;
    var result = new ArrayList<List<Integer>>();
    for (var wave : stage.getMonsterWaves()) {
      if (wave == null
          || wave.isEmpty()
          || wave.size() > 5
          || wave.intStream().anyMatch(id -> id <= 0)) return null;
      result.add(List.copyOf(wave));
    }
    return List.copyOf(result);
  }

  public static boolean validReceipts(Set<String> receipts) {
    if (receipts == null || receipts.size() > 4096) return false;
    for (String key : receipts) {
      if (key == null || key.length() > 40) return false;
      var parts = key.split(":", -1);
      if (parts.length != 2) return false;
      try {
        int id = Integer.parseInt(parts[0]);
        long time = Long.parseLong(parts[1]);
        if (id <= 0
            || time <= 0
            || !Integer.toString(id).equals(parts[0])
            || !Long.toString(time).equals(parts[1])) return false;
      } catch (NumberFormatException invalid) {
        return false;
      }
    }
    return true;
  }

  public static Context capture(Player player, Battle battle) {
    var run = player.getGameplay().getRogue();
    var scene = player.getScene();
    var room = GameplayDefinitions.rooms.get(run.getRoomId());
    var entry = room == null ? null : GameData.getMapEntranceExcelMap().get(room.MapEntrance());
    var history = player.getGameplay().getRogueBattleKills();
    if (battle == null
        || battle.getPlayer() != player
        || battle.isSettling()
        || scene == null
        || scene.getPlaneType() != PlaneType.Rogue
        || scene.getDimensionId() != 0
        || scene.getFloorInfo() == null
        || run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING
        || !run.isInside()
        || run.pending()
        || run.getRoster().isEmpty()
        || run.getCleared().contains(run.getSiteId())
        || room == null
        || !Set.of(1, 2, 4, 6, 7).contains(room.RogueRoomType())
        || room.GroupWithContent() == null
        || entry == null
        || entry.getId() != room.MapEntrance()
        || player.getEntryId() != room.MapEntrance()
        || scene.getEntryId() != room.MapEntrance()
        || scene.getPlaneId() != entry.getPlaneID()
        || scene.getFloorId() != entry.getFloorID()
        || !Objects.equals(run.getRooms().get(run.getSiteId()), run.getRoomId())
        || battle.getNpcMonsters().isEmpty()
        || battle.getNpcMonsters().size() > 64
        || !RoguePropDestruction.validReceipts(run.getDefeatedRogueMonsters())) return null;
    var actors = new ArrayList<Actor>();
    var seen = new HashSet<Integer>();
    var placements = new HashSet<String>();
    var stageShapes = new HashMap<StageExcel, List<List<Integer>>>();
    var expectedWaves = new ArrayList<Wave>();
    for (var actor : battle.getNpcMonsters()) {
      if (actor == null
          || actor.isFarmElement()
          || !seen.add(actor.getEntityId())
          || !placements.add(actor.getGroupId() + ":" + actor.getInstId())) return null;
      var group = scene.getFloorInfo().getGroups().get(actor.getGroupId());
      var placement = actor.getMonsterInfo();
      var binding = RogueMonsterSelection.current(scene, group);
      var row = binding == null ? null : binding.mapping();
      if (row == null
          || actor.getExcel() == null
          || row.NpcMonsterID() != actor.getExcel().getId()
          || row.EventID() != actor.getEventId()
          || actor.getCustomLevel() != RogueBossEncounters.level(run, room, actor.getGroupId())
          || !(actor.getCustomStage() instanceof StageExcel stage)
          || group == null
          || placement == null) return null;
      var frozen =
          new Actor(
              actor,
              actor.getEntityId(),
              actor.getGroupId(),
              actor.getInstId(),
              actor.getCustomLevel(),
              group,
              placement,
              actor.getExcel(),
              stage,
              row,
              binding);
      if (!frozen.matches(scene)) return null;
      if (run.getDefeatedRogueMonsters()
          .contains(actorKey(run, scene, actor.getGroupId(), actor.getInstId()))) return null;
      var shape = shape(stage);
      if (shape == null) return null;
      stageShapes.put(stage, shape);
      actors.add(frozen);
      for (var ids : shape) expectedWaves.add(new Wave(null, stage, ids, actor.getCustomLevel()));
    }
    if (battle.getStage() != actors.getFirst().stage()
        || expectedWaves.size() != battle.getWaves().size()) return null;
    var waves = new ArrayList<Wave>();
    var limits = new HashMap<Mob, Integer>();
    var monsters = new HashMap<Integer, MonsterExcel>();
    int total = 0;
    for (int i = 0; i < expectedWaves.size(); i++) {
      var expected = expectedWaves.get(i);
      var actual = battle.getWaves().get(i);
      if (actual == null
          || actual.getStage() != expected.stage()
          || actual.getCustomLevel() != expected.level()
          || actual.getMonsters() == null
          || !expected.monsters().equals(new ArrayList<>(actual.getMonsters()))) return null;
      waves.add(new Wave(actual, expected.stage(), expected.monsters(), expected.level()));
      for (int id : expected.monsters()) {
        var row = GameData.getMonsterExcelMap().get(id);
        if (row == null || row.getId() != id || ++total > 1024) return null;
        monsters.put(id, row);
        limits.merge(new Mob(expected.stage().getId(), id), 1, Integer::sum);
      }
    }
    return new Context(
        player,
        run,
        scene,
        room,
        entry,
        battle,
        List.copyOf(actors),
        List.copyOf(waves),
        Map.copyOf(stageShapes),
        Map.copyOf(limits),
        Map.copyOf(monsters),
        history,
        history == null ? 0 : history.total(),
        player.getGameplay().getRogueBattleDeaths());
  }

  public static final class Context {
    private final Player player;
    private final GameplayState.RogueRun run;
    private final Scene scene;
    private final GameplayDefinitions.Room room;
    private final MapEntranceExcel entry;
    private final Battle battle;
    private final List<Actor> actors;
    private final List<Wave> waves;
    private final Map<StageExcel, List<List<Integer>>> stageShapes;
    private final Map<Mob, Integer> limits;
    private final Map<Integer, MonsterExcel> monsters;
    private final RogueBattleKillState history;
    private final RogueBattleDeathState details;
    private final Map<Integer,Integer> beforeDetails;
    private final int before, site;
    private final Set<String> receipts;
    private final Set<String> defeats;
    private final Map<Integer, Integer> contents;
    private final List<Integer> roster;
    private boolean completed;

    private Context(
        Player player,
        GameplayState.RogueRun run,
        Scene scene,
        GameplayDefinitions.Room room,
        MapEntranceExcel entry,
        Battle battle,
        List<Actor> actors,
        List<Wave> waves,
        Map<StageExcel, List<List<Integer>>> stageShapes,
        Map<Mob, Integer> limits,
        Map<Integer, MonsterExcel> monsters,
        RogueBattleKillState history,
        int before,
        RogueBattleDeathState details) {
      this.player = player;
      this.run = run;
      this.scene = scene;
      this.room = room;
      this.entry = entry;
      this.battle = battle;
      this.actors = actors;
      this.waves = waves;
      this.stageShapes = stageShapes;
      this.limits = limits;
      this.monsters = monsters;
      this.history = history;
      this.before = before;
      this.details = details;
      this.beforeDetails = details == null ? null : details.snapshot();
      this.site = run.getSiteId();
      this.receipts = run.getRecordedBattleKills();
      this.defeats = run.getDefeatedRogueMonsters();
      this.contents = Map.copyOf(room.GroupWithContent());
      this.roster = List.copyOf(run.getRoster());
    }

    public void commit(Battle settled) {
      synchronized (player) {
        if (completed
            || settled != battle
            || player.getBattle() != battle
            || !battle.isSettling()
            || battle.getResult() != BattleEndStatus.BATTLE_END_WIN
            || battle.getStats() == null
            || player.getGameplay().getRogue() != run
            || player.getScene() != scene
            || run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING
            || !run.isInside()
            || run.pending()
            || run.getSiteId() != site
            || run.getRoomId() != room.RogueRoomID()
            || !Objects.equals(run.getRooms().get(site), room.RogueRoomID())
            || !contents.equals(room.GroupWithContent())
            || !roster.equals(run.getRoster())
            || player.getCurrentLineup() != battle.getLineup()
            || !roster.equals(battle.getLineup().getAvatars())
            || run.getCleared().contains(site)
            || GameplayDefinitions.rooms.get(room.RogueRoomID()) != room
            || GameData.getMapEntranceExcelMap().get(entry.getId()) != entry
            || player.getEntryId() != entry.getId()
            || scene.getEntryId() != entry.getId()
            || scene.getPlaneId() != entry.getPlaneID()
            || scene.getFloorId() != entry.getFloorID()
            || scene.getDimensionId() != 0
            || run.getDefeatedRogueMonsters() != defeats
            || !RoguePropDestruction.validReceipts(defeats)
            || defeats.size() + actors.size() > 4096
            || battle.getNpcMonsters().size() != actors.size()
            || battle.getWaves().size() != waves.size()) return;
        for (int i = 0; i < actors.size(); i++)
          if (battle.getNpcMonsters().get(i) != actors.get(i).entity()
              || !actors.get(i).matches(scene)
              || defeats.contains(
                  actorKey(run, scene, actors.get(i).group(), actors.get(i).instance()))) return;
        for (int i = 0; i < waves.size(); i++) {
          var row = waves.get(i);
          var actual = battle.getWaves().get(i);
          if (actual != row.wave()
              || actual.getStage() != row.stage()
              || actual.getCustomLevel() != row.level()
              || actual.getMonsters() == null
              || !row.monsters().equals(new ArrayList<>(actual.getMonsters()))) return;
        }
        if (stageShapes.entrySet().stream().anyMatch(e -> !e.getValue().equals(shape(e.getKey())))
            || monsters.entrySet().stream()
                .anyMatch(
                    e ->
                        GameData.getMonsterExcelMap().get(e.getKey()) != e.getValue()
                            || e.getValue().getId() != e.getKey())) return;
        var currentDetails = player.getGameplay().getRogueBattleDeaths();
        boolean detailsBound = details != null && currentDetails == details
            && beforeDetails != null && beforeDetails.equals(details.snapshot())
            && details.total() <= before;
        if (!detailsBound && currentDetails != null) currentDetails.quarantine();
        var deaths = reportedDeaths();
        int kills = deaths == null ? 0 : deaths.values().stream().mapToInt(Integer::intValue).sum();
        String key = battle.getId() + ":" + battle.getTimestamp();
        boolean counted =
            history != null
                && player.getGameplay().getRogueBattleKills() == history
                && history.total() == before
                && history.canRecord(kills)
                && run.getRecordedBattleKills() == receipts
                && validReceipts(receipts)
                && receipts.size() < 4096
                && !receipts.contains(key);
        if (counted) {
          receipts.add(key);
          if (!history.record(kills))
            throw new IllegalStateException("Prechecked battle kill count changed");
          if(detailsBound && details.canRecord(deaths))
            if(!details.record(deaths))throw new IllegalStateException("Prechecked per-monster deaths changed");
        }
        for (var actor : actors) defeats.add(actorKey(run, scene, actor.group(), actor.instance()));
        completed = true;
        player.save();
        if (counted) player.getQuestManager().onRogueBattleKillProgress();
      }
    }

    private Map<Integer,Integer> reportedDeaths() {
      var seen = new HashSet<Integer>();
      var observed = new HashMap<Mob, Integer>();
      var deaths = new HashMap<Integer,Integer>();
      var reports = battle.getReportedMonsters();
      if (reports == null || reports.size() > 1024) return null;
      for (var report : reports) {
        if (report == null
            || report.entityId() <= 0
            || !seen.add(report.entityId())
            || report.deathSource() < 0
            || report.deathSource() > 3) return null;
        var key = new Mob(report.stageId(), report.monsterId());
        var limit = limits.get(key);
        if (limit == null || observed.merge(key, 1, Integer::sum) > limit) return null;
        if (report.deathSource() == 1 || report.deathSource() == 2)
          deaths.merge(report.monsterId(),1,Integer::sum);
      }
      return deaths;
    }
  }

  private RogueBattleKills() {}
}
