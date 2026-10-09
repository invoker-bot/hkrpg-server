package org.hkrpg.server.gameplay;

import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.player.*;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;
import java.util.*;

/** Dedicated first-season match/start/abandon path, with no generic world battle settlement. */
public final class BoxingClubManager {
  public record Entry(Retcode retcode, BoxingClubBattle battle) {}

  static void save(Player p, BoxingClubState next) {
    var old = p.getGameplay().getBoxingClub();
    p.getGameplay().boxingClub(next);
    try {
      p.save();
    } catch (RuntimeException failure) {
      p.getGameplay().boxingClub(old);
      throw failure;
    }
  }

  public static boolean genericMonsterRequiresMatch(
      emu.lunarcore.game.scene.entity.EntityMonster monster) {
    var stage = monster.getCustomStage();
    if (stage == null) {
      var event =
          emu.lunarcore.data.GameData.getPlaneEventExcelMap()
              .get(monster.getEventId(), monster.getWorldLevel());
      stage =
          emu.lunarcore.data.GameData.getStageExcelMap()
              .get(event == null ? monster.getStageId() : event.getStageId());
    }
    return stage != null && stage.getStageType() == emu.lunarcore.game.enums.StageType.BoxingClub;
  }

  public static boolean valid(Player p) {
    synchronized (p) {
      var rules = BoxingClubRules.load();
      try {
        return rules != null && p.getGameplay().getBoxingClub().valid(rules, p.getUid());
      } catch (RuntimeException invalid) {
        return false;
      }
    }
  }

  public static void restore(Player p) {
    synchronized (p) {
      if (p.getGameplay().getBoxingClub().getActive() != null && p.getInstance() == null) {
        var instance = new Instance();
        instance.setPlayer(p);
        p.setInstance(instance);
      }
    }
  }

  public static Retcode match(Player p, int id, List<Integer> roster, Instant time) {
    synchronized (p) {
      var g = p.getGameplay();
      if (g.getBoxingClub().getActive() != null
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
      var rules = BoxingClubRules.load();
      var challenge = rules == null ? null : rules.catalog.challenges().get(id);
      if (rules == null
          || !g.getBoxingClub().valid(rules, p.getUid())
          || !rules.available(p, challenge, time)
          || challenge.previous() > 0
              && !g.getBoxingClub().getCompleted().containsKey(challenge.previous())
          || BoxingClubParty.prepare(p, roster) == null) return Retcode.REQ_PARA_INVALID;
      var groups =
          challenge.groups().stream()
              .map(rules.catalog::group)
              .filter(group -> group.weight() > 0)
              .toList();
      // The pinned first-season definitions have exactly one nonzero original weight.
      if (groups.size() != 1) return Retcode.REQ_PARA_INVALID;
      var group = groups.get(0);
      var reward = rules.reward(challenge.reward());
      var buffIDs = new ArrayList<Integer>();
      buffIDs.add(challenge.buff());
      for (int event : group.events()) {
        if (rules.stage(event, p.getWorldLevel()) == null) return Retcode.REQ_PARA_INVALID;
        int buff = rules.catalog.stage(event).buff();
        if (buff > 0) buffIDs.add(buff);
      }
      if (reward == null
          || !rules.buffsValid(buffIDs)
          || BoxingClubRules.items(reward).entrySet().stream()
              .anyMatch(
                  e ->
                      e.getValue() <= 0
                          || emu.lunarcore.data.GameData.getItemExcelMap().get(e.getKey()) == null))
        return Retcode.REQ_PARA_INVALID;
      var next = g.getBoxingClub().copy();
      next.begin(
          p.getUid(),
          p.getWorldLevel(),
          p.getPlaneId(),
          p.getFloorId(),
          p.getEntryId(),
          challenge,
          group,
          roster);
      save(p, next);
      restore(p);
      return Retcode.SUCC;
    }
  }

  public static Entry start(Player p, int id, Instant time) {
    synchronized (p) {
      var state = p.getGameplay().getBoxingClub();
      var run = state.getActive();
      if (p.isInBattle()) return new Entry(Retcode.SYSTEM_BUSY, null);
      var rules = BoxingClubRules.load();
      var challenge = rules == null ? null : rules.catalog.challenges().get(id);
      if (rules == null
          || run == null
          || run.challenge() != id
          || !state.valid(rules, p.getUid())
          || !run.context(p)
          || !rules.available(p, challenge, time)
          || !(p.getInstance() instanceof Instance))
        return new Entry(Retcode.REQ_PARA_INVALID, null);
      var group = rules.catalog.group(run.group());
      int event = group.events().get(run.index());
      var stage = rules.stage(event, run.world());
      var party = BoxingClubParty.prepare(p, run.roster());
      var buffIDs = new ArrayList<Integer>();
      buffIDs.add(challenge.buff());
      buffIDs.addAll(run.buffs(rules.catalog));
      if (stage == null || party == null || !rules.buffsValid(buffIDs))
        return new Entry(Retcode.REQ_PARA_INVALID, null);
      var battle = new BoxingClubBattle(p, rules, run, event, stage, party, List.copyOf(buffIDs));
      p.setBattle(battle);
      return new Entry(Retcode.SUCC, battle);
    }
  }

  public static Retcode giveUp(Player p, int id) {
    synchronized (p) {
      var state = p.getGameplay().getBoxingClub();
      var run = state.getActive();
      if (p.isInBattle()) return Retcode.SYSTEM_BUSY;
      if (run == null || run.challenge() != id) return Retcode.REQ_PARA_INVALID;
      var next = state.copy();
      next.cancel();
      save(p, next);
      if (p.getInstance() instanceof Instance) p.setInstance(null);
      return Retcode.SUCC;
    }
  }

  static String rewardKey(int id) {
    return "boxing:first:" + id;
  }

  private static final class Instance extends PlayerGameInstance {
    public void onBattleStart(Battle ignored) {}

    public void onBattleFinish(Battle ignored, BattleEndStatus result, BattleStatistics stats) {}

    public void onLeave() {
      var p = getPlayer();
      synchronized (p) {
        var state = p.getGameplay().getBoxingClub();
        if (state.getActive() != null) {
          var next = state.copy();
          next.cancel();
          save(p, next);
        }
      }
    }
  }

  private BoxingClubManager() {}
}
