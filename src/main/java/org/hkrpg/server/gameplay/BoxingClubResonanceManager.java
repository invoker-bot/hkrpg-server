package org.hkrpg.server.gameplay;

import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.player.*;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;
import java.util.*;

/** Dedicated second-season flow; choices never constitute a win or bypass their source round. */
public final class BoxingClubResonanceManager {
  public record Entry(Retcode retcode, BoxingClubResonanceBattle battle) {}

  static void save(Player p, BoxingClubResonanceState next) {
    save(p, next, p.getGameplay().getBoxingClubActivityQuests());
  }

  static void save(Player p, BoxingClubResonanceState next, BoxingClubActivityQuestState activity) {
    var old = p.getGameplay().getBoxingClubResonance();
    var oldActivity = p.getGameplay().getBoxingClubActivityQuests();
    p.getGameplay().boxingClubResonance(next);
    p.getGameplay().boxingClubActivityQuests(activity);
    try {
      p.save();
    } catch (RuntimeException failure) {
      p.getGameplay().boxingClubResonance(old);
      p.getGameplay().boxingClubActivityQuests(oldActivity);
      throw failure;
    }
  }

  public static boolean valid(Player p) {
    synchronized (p) {
      var state = p.getGameplay().getBoxingClubResonance();
      if (state.getActive() == null && state.getCompleted().isEmpty()) return true;
      var rules = BoxingClubResonanceRules.load();
      return rules != null
          && state.valid(rules, p.getUid())
          && !(state.getActive() != null && p.getGameplay().getBoxingClub().getActive() != null);
    }
  }

  public static void restore(Player p) {
    synchronized (p) {
      if (p.getGameplay().getBoxingClubResonance().getActive() != null && p.getInstance() == null) {
        var instance = new Instance();
        instance.setPlayer(p);
        p.setInstance(instance);
      }
    }
  }

  public static Retcode seed(
      Player p, int id, List<BoxingClubManager.Member> members, Instant time) {
    synchronized (p) {
      var g = p.getGameplay();
      if (g.getBoxingClubResonance().getActive() != null
          || g.getBoxingClub().getActive() != null
          || p.isInBattle()
          || p.getInstance() != null
          || g.getCurrentMissionRaid() != 0
          || g.getTrialRun() != null
          || g.getRogue().isInside()
          || g.getClockPark().getActive() != null
          || g.getStoryLines().getCurrentId() != 0
          || g.getStoryLines().isTransitionPending()
          || g.getParkourVenue().inside()
          || g.getParkour().getActiveLevel() != 0
          || g.getRhythm().getActiveLevel() != 0) return Retcode.SYSTEM_BUSY;
      var rules = BoxingClubResonanceRules.load();
      var c = rules == null ? null : rules.catalog.challenges().get(id);
      if (rules == null
          || !g.getBoxingClubResonance().valid(rules, p.getUid())
          || !rules.available(p, c, time)
          || c.previous() > 0
              && !g.getBoxingClubResonance().getCompleted().containsKey(c.previous())
          || members == null
          || members.stream().anyMatch(m -> m == null || m.type() != 1 && m.type() != 3)
          || BoxingClubParty.prepare(p, id, members, time) == null) return Retcode.REQ_PARA_INVALID;
      var buffs = new ArrayList<Integer>();
      for (var choice : c.resonanceGroups().entrySet()) {
        buffs.add(choice.getKey());
        var group = rules.catalog.group(choice.getValue());
        for (int event : group.events()) {
          if (rules.stage(event, p.getWorldLevel()) == null) return Retcode.REQ_PARA_INVALID;
          rules.catalog.stage(event).options().stream().filter(n -> n > 0).forEach(buffs::add);
        }
      }
      var reward = rules.reward(c.reward());
      if (!rules.buffsValid(buffs)
          || reward == null
          || !BoxingClubResonanceRules.items(reward).isEmpty()) return Retcode.REQ_PARA_INVALID;
      var next = g.getBoxingClubResonance().copy();
      next.begin(p, id, members);
      save(p, next);
      restore(p);
      return Retcode.SUCC;
    }
  }

  private static boolean selectable(
      Player p, int id, BoxingClubResonanceRules rules, Instant time) {
    var state = p.getGameplay().getBoxingClubResonance();
    var run = state.getActive();
    return rules != null
        && run != null
        && run.challenge() == id
        && run.context(p)
        && state.valid(rules, p.getUid())
        && rules.available(p, rules.catalog.challenges().get(id), time)
        && p.getInstance() instanceof Instance
        && p.getGameplay().getBoxingClub().getActive() == null
        && BoxingClubParty.prepare(p, id, run.members(), time) != null;
  }

  public static Retcode resonance(Player p, int id, int buff, Instant time) {
    synchronized (p) {
      if (p.isInBattle()) return Retcode.SYSTEM_BUSY;
      var rules = BoxingClubResonanceRules.load();
      if (!selectable(p, id, rules, time)) return Retcode.REQ_PARA_INVALID;
      var run = p.getGameplay().getBoxingClubResonance().getActive();
      var group = rules.catalog.challenges().get(id).resonanceGroups().get(buff);
      if (group == null || run.index() != 0 || !rules.buffsValid(List.of(buff)))
        return Retcode.REQ_PARA_INVALID;
      if (run.resonance() == buff) return Retcode.SUCC;
      if (run.resonance() != 0) return Retcode.REQ_PARA_INVALID;
      var next = p.getGameplay().getBoxingClubResonance().copy();
      next.chooseResonance(buff, group);
      save(p, next);
      return Retcode.SUCC;
    }
  }

  public static Retcode optional(Player p, int id, int buff, Instant time) {
    synchronized (p) {
      if (p.isInBattle()) return Retcode.SYSTEM_BUSY;
      var rules = BoxingClubResonanceRules.load();
      if (!selectable(p, id, rules, time)) return Retcode.REQ_PARA_INVALID;
      var run = p.getGameplay().getBoxingClubResonance().getActive();
      int index = run.index();
      if (run.resonance() == 0 || index < 1 || index > 2) return Retcode.REQ_PARA_INVALID;
      int event = rules.catalog.group(run.group()).events().get(index);
      if (!rules.catalog.stage(event).options().contains(buff)
          || buff <= 0
          || !rules.buffsValid(List.of(buff))) return Retcode.REQ_PARA_INVALID;
      if (run.options().size() == index)
        return run.options().get(index - 1) == buff ? Retcode.SUCC : Retcode.REQ_PARA_INVALID;
      if (run.options().size() != index - 1) return Retcode.REQ_PARA_INVALID;
      var next = p.getGameplay().getBoxingClubResonance().copy();
      next.chooseOptional(buff);
      save(p, next);
      return Retcode.SUCC;
    }
  }

  public static Entry start(Player p, int id, Instant time) {
    synchronized (p) {
      if (p.isInBattle()) return new Entry(Retcode.SYSTEM_BUSY, null);
      var rules = BoxingClubResonanceRules.load();
      if (!selectable(p, id, rules, time)) return new Entry(Retcode.REQ_PARA_INVALID, null);
      var run = p.getGameplay().getBoxingClubResonance().getActive();
      if (!run.ready()) return new Entry(Retcode.REQ_PARA_INVALID, null);
      int event = rules.catalog.group(run.group()).events().get(run.index());
      var stage = rules.stage(event, run.world());
      var party = BoxingClubParty.prepare(p, id, run.members(), time);
      if (stage == null || party == null || !rules.buffsValid(run.buffs()))
        return new Entry(Retcode.REQ_PARA_INVALID, null);
      var battle = new BoxingClubResonanceBattle(p, rules, run, event, stage, party);
      p.setBattle(battle);
      return new Entry(Retcode.SUCC, battle);
    }
  }

  public static Retcode giveUp(Player p, int id) {
    synchronized (p) {
      if (p.isInBattle()) return Retcode.SYSTEM_BUSY;
      var state = p.getGameplay().getBoxingClubResonance();
      var run = state.getActive();
      if (run == null || run.challenge() != id) return Retcode.REQ_PARA_INVALID;
      var next = state.copy();
      next.cancel();
      save(p, next);
      if (p.getInstance() instanceof Instance) p.setInstance(null);
      return Retcode.SUCC;
    }
  }

  static String rewardKey(int id) {
    return "boxing:second:" + id;
  }

  private static final class Instance extends PlayerGameInstance {
    public void onBattleStart(Battle ignored) {}

    public void onBattleFinish(Battle ignored, BattleEndStatus result, BattleStatistics stats) {}

    public void onLeave() {
      var p = getPlayer();
      synchronized (p) {
        var state = p.getGameplay().getBoxingClubResonance();
        if (state.getActive() != null) {
          var next = state.copy();
          next.cancel();
          save(p, next);
        }
      }
    }
  }

  private BoxingClubResonanceManager() {}
}
