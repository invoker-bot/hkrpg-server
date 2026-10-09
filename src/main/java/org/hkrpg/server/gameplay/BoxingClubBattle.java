package org.hkrpg.server.gameplay;

import emu.lunarcore.data.excel.StageExcel;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.proto.SceneBattleInfoOuterClass.SceneBattleInfo;
import java.time.Instant;
import java.util.*;

/** Frozen matched source round. The client still supplies the battle outcome and cycle count. */
public final class BoxingClubBattle extends Battle {
  private final BoxingClubRules rules;
  private final BoxingClubState.Run run;
  private final int event;
  private final StageExcel source;
  private final BoxingClubParty party;
  private final List<Integer> buffs;
  private final Map<Integer, Integer> reward;
  private final emu.lunarcore.game.scene.Scene scene;
  private final emu.lunarcore.game.player.PlayerGameInstance instance;
  private boolean boxingSettlementStarted;

  @Override
  public boolean beginSettlement() {
    if (boxingSettlementStarted) return false;
    boxingSettlementStarted = true;
    return true;
  }

  BoxingClubBattle(
      Player p,
      BoxingClubRules rules,
      BoxingClubState.Run run,
      int event,
      StageExcel stage,
      BoxingClubParty party,
      List<Integer> buffs) {
    super(p, party, stage);
    this.rules = rules;
    this.run = run;
    this.event = event;
    source = stage;
    this.party = party;
    this.buffs = buffs;
    reward =
        BoxingClubRules.items(
            rules.reward(rules.catalog.challenges().get(run.challenge()).reward()));
    scene = p.getScene();
    instance = p.getInstance();
    setRoundsLimit(rules.catalog.challenges().get(run.challenge()).turnLimit() - run.rounds());
  }

  public void prepareAvatarBuffs() {}

  public SceneBattleInfo toProto() {
    var b =
        SceneBattleInfo.newInstance()
            .setBattleId(getId())
            .setStageId(source.getId())
            .setWorldLevel(run.world())
            .setLogicRandomSeed(getLogicRandomSeed())
            .setRoundsLimit(getRoundsLimit());
    for (int i = 0; i < getWaves().size(); i++)
      b.addMonsterWaveList(getWaves().get(i).toProto().setWaveId(i + 1));
    party.battleActors().forEach(b::addBattleAvatarList);
    for (int id : buffs) b.addBuffList(new MazeBuff(id, 1, -1, 0xffffffff).toProto());
    return b;
  }

  private boolean wavesValid() {
    if (getWaves().size() != source.getMonsterWaves().size()) return false;
    for (int i = 0; i < getWaves().size(); i++) {
      var wave = getWaves().get(i);
      if (wave == null
          || wave.getStage() != source
          || wave.getCustomLevel() != 0
          || !wave.getMonsters().equals(source.getMonsterWaves().get(i))) return false;
    }
    return true;
  }

  private boolean rewardValid() {
    var live = rules.reward(rules.catalog.challenges().get(run.challenge()).reward());
    return live != null && reward.equals(BoxingClubRules.items(live));
  }

  public boolean accepts(BattleEndStatus result, BattleStatistics stats) {
    var p = getPlayer();
    var state = p.getGameplay().getBoxingClub();
    if (p.getBattle() != this
        || state.getActive() != run
        || p.getInstance() != instance
        || stats.getRoundCnt() < 0
        || stats.getRoundCnt() > getRoundsLimit()) return false;
    // A bound loss only discards this attempt. Expired windows or changed definitions must not
    // trap the player, and this path never advances a round or delivers a reward.
    if (result == BattleEndStatus.BATTLE_END_LOSE) return true;
    var challenge = rules.catalog.challenges().get(run.challenge());
    return result == BattleEndStatus.BATTLE_END_WIN
        && stats.getRoundCnt() > 0
        && p.getScene() == scene
        && run.context(p)
        && rules.sourcesValid()
        && state.valid(rules, p.getUid())
        && rules.stage(event, run.world()) == source
        && wavesValid()
        && party.valid()
        && rules.buffsValid(buffs)
        && rewardValid()
        && rules.available(p, challenge, Instant.now())
        && (run.index() == 3 || stats.getRoundCnt() < getRoundsLimit());
  }

  public Battle finish(BattleEndStatus result, BattleStatistics stats) {
    var p = getPlayer();
    synchronized (p) {
      var state = p.getGameplay().getBoxingClub();
      var next = state.copy();
      BoxingClubState.Run finished = null;
      if (result == BattleEndStatus.BATTLE_END_WIN) {
        finished = next.win(event, source.getId(), getId(), stats.getRoundCnt(), reward);
      } else next.cancel();
      try {
        BoxingClubManager.save(
            p,
            next,
            finished == null
                ? p.getGameplay().getBoxingClubActivityQuests()
                : BoxingClubActivityQuests.record(p, finished, null, Instant.now()));
      } catch (RuntimeException failure) {
        // No progress or inventory operation committed. The exact admitted battle can retry.
        boxingSettlementStarted = false;
        throw failure;
      }
      setResult(result);
      setStats(stats);
      p.setBattle(null);
      if (next.getActive() == null) p.setInstance(null);
      if (finished != null) {
        var rewards = new ItemParamMap();
        reward.forEach(rewards::add);
        var claim =
            new RewardLedger(p).claimItems(BoxingClubManager.rewardKey(run.challenge()), rewards);
        if (claim.retcode() == emu.lunarcore.server.packet.Retcode.SUCC
            || claim.retcode() == emu.lunarcore.server.packet.Retcode.QUEST_REWARD_ALREADY_TAKEN)
          org.hkrpg.server.protocol.BoxingClub46.reward(
              p, run.challenge(), finished.rounds(), claim.items());
        p.getQuestManager().onBoxingClubProgress();
      } else if (result == BattleEndStatus.BATTLE_END_WIN)
        org.hkrpg.server.protocol.BoxingClub46.update(p, run.challenge());
      return this;
    }
  }
}
