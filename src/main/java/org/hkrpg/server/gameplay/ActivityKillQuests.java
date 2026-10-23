package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import java.time.Instant;
import java.util.*;

/** Source template counts observe a validated victory report, never the victory flag alone. */
public final class ActivityKillQuests {
  private record Target(QuestState state, String acceptance, GameplayDefinitions.Window window) {}

  private record Wave(BattleMonsterWave identity, BattleStage stage, List<Integer> monsters) {}

  private record Mob(int stage, int monster) {}

  public static boolean belongs(QuestExcel definition) {
    if (definition == null) return false;
    var finish = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
    return ActivityKillSources.IDS.contains(definition.getId())
        || finish != null && finish.isType("KillMonsterInGameModeList", 414);
  }

  public static boolean refresh(Player p) {
    synchronized (p) {
      if (!ActivityAccess.unlocked(p, 30013)) return false;
      var rules = ActivityKillRules.load();
      var domain = p.getGameplay().getActivityKillQuests();
      if (!domain.valid(rules)) return false;
      boolean changed = false;
      var now = Instant.now();
      for (int id : ActivityKillSources.IDS)
        if (rules.window(p, id, now) != null && !domain.getQuests().containsKey(id)) {
          domain.accept(id);
          changed = true;
        }
      return changed;
    }
  }

  public static Map<Integer, QuestState> states(Player p) {
    synchronized (p) {
      if (!ActivityAccess.unlocked(p, 30013)) return Map.of();
      var rules = ActivityKillRules.load();
      var domain = p.getGameplay().getActivityKillQuests();
      if (!domain.valid(rules)) return Map.of();
      var result = new TreeMap<Integer, QuestState>();
      var now = Instant.now();
      for (var e : domain.getQuests().entrySet())
        if (rules.window(p, e.getKey(), now) != null) result.put(e.getKey(), e.getValue());
      return Map.copyOf(result);
    }
  }

  public static QuestState state(Player p, int id) {
    return states(p).get(id);
  }

  public static QuestState completionState(Player p, int id) {
    synchronized (p) {
      if (!ActivityKillSources.IDS.contains(id) || !ActivityAccess.unlocked(p, 30013)) return null;
      var rules = ActivityKillRules.load();
      var domain = p.getGameplay().getActivityKillQuests();
      if (!domain.valid(rules) || !rules.unlocked(p, id)) return null;
      var state = domain.getQuests().get(id);
      return MissionStateRecords.completed(state, id, rules.goal(id)) ? state : null;
    }
  }

  public static final class Context {
    final Player player;
    final Battle battle;
    final Scene scene;
    final ActivityKillRules rules;
    final ActivityKillState domain;
    final Map<Integer, Target> targets;
    final List<Wave> waves;
    final Map<Mob, Integer> roster;
    final Map<Integer, Integer> copies;
    final List<Integer> party;
    final String identity = UUID.randomUUID().toString();
    final int plane, floor, entry, dimension, story, mode;
    final Object instance;
    final GameplayState.MissionRaidRun raid;

    Context(
        Player player,
        Battle battle,
        Scene scene,
        ActivityKillRules rules,
        ActivityKillState domain,
        Map<Integer, Target> targets,
        List<Wave> waves,
        Map<Mob, Integer> roster,
        Map<Integer, Integer> copies,
        int mode) {
      this.player = player;
      this.battle = battle;
      this.scene = scene;
      this.rules = rules;
      this.domain = domain;
      this.targets = Map.copyOf(targets);
      this.waves = List.copyOf(waves);
      this.roster = Map.copyOf(roster);
      this.copies = Map.copyOf(copies);
      this.party = List.copyOf(battle.getLineup().getAvatars());
      plane = scene.getPlaneId();
      floor = scene.getFloorId();
      entry = scene.getEntryId();
      dimension = scene.getDimensionId();
      story = scene.getStoryLineId();
      this.mode = mode;
      instance = player.getInstance();
      raid = new MissionRaid(player).current();
    }

    boolean matches() {
      if (player.getBattle() != battle
          || battle.isSettling()
          || player.getScene() != scene
          || player.getInstance() != instance
          || new MissionRaid(player).current() != raid
          || mode == 8 && !new MissionRaid(player).active()
          || ActivityKillRules.load() != rules
          || player.getGameplay().getActivityKillQuests() != domain
          || !domain.valid(rules)
          || rules.mode(player, scene) != mode
          || scene.getPlaneId() != plane
          || scene.getFloorId() != floor
          || scene.getEntryId() != entry
          || scene.getDimensionId() != dimension
          || scene.getStoryLineId() != story
          || player.getGameplay().getStoryLines().isTransitionPending()
          || !party.equals(battle.getLineup().getAvatars())
          || !party.equals(player.getCurrentLineup().getAvatars())
          || waves.size() != battle.getWaves().size()) return false;
      for (int i = 0; i < waves.size(); i++) {
        var frozen = waves.get(i);
        var live = battle.getWaves().get(i);
        if (live != frozen.identity()
            || live.getStage() != frozen.stage()
            || !frozen.monsters().equals(live.getMonsters())) return false;
        if (copies.containsKey(live.getStage().getId())
            && rules.stage(live.getStage().getId()) == null) return false;
      }
      return true;
    }
  }

  public static Context capture(Player p, Battle b) {
    synchronized (p) {
      var scene = p.getScene();
      if (b == null
          || b.getPlayer() != p
          || p.getBattle() != b
          || b.isSettling()
          || scene == null
          || scene.getPlaneType() != PlaneType.Maze && scene.getPlaneType() != PlaneType.Raid
          || p.getInstance() != null
          || b.getCocoonId() != 0
          || b.getStaminaCost() != 0
          || p.getGameplay().getStoryLines().isTransitionPending()) return null;
      var rules = ActivityKillRules.load();
      var domain = p.getGameplay().getActivityKillQuests();
      if (!domain.valid(rules)) return null;
      int mode = rules.mode(p, scene);
      if (mode != 2 && mode != 8 || mode == 8 && !new MissionRaid(p).active()) return null;
      var now = Instant.now();
      var targets = new HashMap<Integer, Target>();
      for (var e : domain.getQuests().entrySet()) {
        var window = rules.window(p, e.getKey(), now);
        if (window != null
            && MissionStateRecords.doing(e.getValue(), e.getKey(), rules.goal(e.getKey())))
          targets.put(
              e.getKey(), new Target(e.getValue(), e.getValue().getObservationKey(), window));
      }
      if (targets.isEmpty() || b.getWaves().isEmpty() || b.getWaves().size() > 128) return null;
      var waves = new ArrayList<Wave>();
      var roster = new HashMap<Mob, Integer>();
      var shapes = new HashMap<Integer, List<List<Integer>>>();
      var copies = new HashMap<Integer, Integer>();
      for (var wave : b.getWaves()) {
        if (wave == null
            || wave.getStage() == null
            || wave.getMonsters() == null
            || wave.getMonsters().isEmpty()
            || wave.getMonsters().size() > 5) return null;
        var list = List.copyOf(wave.getMonsters());
        boolean counted = false;
        for (int monster : list) {
          if (monster <= 0) return null;
          int template = rules.template(monster);
          if (template < 0) return null;
          counted |= targets.keySet().stream().anyMatch(id -> rules.target(id) == template);
          roster.merge(new Mob(wave.getStage().getId(), monster), 1, Integer::sum);
        }
        waves.add(new Wave(wave, wave.getStage(), list));
        shapes.computeIfAbsent(wave.getStage().getId(), id -> new ArrayList<>()).add(list);
        if (counted) copies.put(wave.getStage().getId(), 0);
      }
      for (int stage : new ArrayList<>(copies.keySet())) {
        var original = rules.stage(stage);
        if (original == null) return null;
        var shape = original.getMonsterWaves();
        var actual = shapes.get(stage);
        if (shape == null || shape.isEmpty() || actual.size() % shape.size() != 0) return null;
        int count = actual.size() / shape.size();
        if (count <= 0 || count > 64) return null;
        for (int i = 0; i < actual.size(); i++)
          if (!actual.get(i).equals(shape.get(i % shape.size()))) return null;
        copies.put(stage, count);
      }
      return copies.isEmpty()
          ? null
          : new Context(p, b, scene, rules, domain, targets, waves, roster, copies, mode);
    }
  }

  /** Save before beginning settlement; storage failure leaves the same live battle retryable. */
  public static boolean record(
      Player p, Battle b, BattleEndStatus result, List<MissionBattleKills.Monster> reports) {
    synchronized (p) {
      var context = b.getActivityKillQuests();
      if (context == null
          || result != BattleEndStatus.BATTLE_END_WIN
          || reports.isEmpty()
          || !context.matches()) return true;
      var seen = new HashSet<Integer>();
      var counts = new HashMap<Mob, Integer>();
      for (var row : reports) {
        var key = new Mob(row.stageId(), row.monsterId());
        if (row.entityId() <= 0
            || !seen.add(row.entityId())
            || row.deathSource() < 0
            || row.deathSource() > 3
            || !context.roster.containsKey(key)
            || counts.merge(key, 1, Integer::sum) > context.roster.get(key)
            || context.rules.template(row.monsterId()) < 0) return true;
      }
      var now = Instant.now();
      var observations = new ArrayList<ActivityKillState.Receipt>();
      for (var e : context.targets.entrySet()) {
        int id = e.getKey();
        var target = e.getValue();
        var state = context.domain.getQuests().get(id);
        if (state != target.state()
            || !Objects.equals(target.acceptance(), state.getObservationKey())
            || !MissionStateRecords.doing(state, id, context.rules.goal(id))
            || !Objects.equals(target.window(), context.rules.window(p, id, now))) continue;
        for (var row : reports)
          if ((row.deathSource() == 1 || row.deathSource() == 2)
              && context.rules.template(row.monsterId()) == context.rules.target(id)
              && context.copies.containsKey(row.stageId()))
            observations.add(
                new ActivityKillState.Receipt(
                    id,
                    row.stageId(),
                    row.monsterId(),
                    row.entityId(),
                    context.copies.get(row.stageId()),
                    context.plane,
                    context.floor,
                    context.entry,
                    context.dimension,
                    context.story,
                    context.mode,
                    now.getEpochSecond(),
                    target.window(),
                    context.identity,
                    target.acceptance()));
      }
      if (observations.isEmpty()) return true;
      var next = context.domain.recorded(observations, context.rules);
      if (!next.valid(context.rules)) return true;
      p.getGameplay().activityKillQuests(next);
      try {
        p.save();
        return true;
      } catch (RuntimeException failed) {
        p.getGameplay().activityKillQuests(context.domain);
        return false;
      }
    }
  }

  public static void onSettled(Player p, Battle b) {
    var context = b.getActivityKillQuests();
    if (context != null && p.getGameplay().getActivityKillQuests() != context.domain)
      p.getQuestManager().onActivityKillProgress();
  }

  private ActivityKillQuests() {}
}
