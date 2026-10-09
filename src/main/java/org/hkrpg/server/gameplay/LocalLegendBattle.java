package org.hkrpg.server.gameplay;

import emu.lunarcore.data.excel.StageExcel;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.proto.BattleTargetListOuterClass.BattleTargetList;
import emu.lunarcore.proto.BattleTargetOuterClass.BattleTarget;
import emu.lunarcore.proto.SceneBattleInfoOuterClass.SceneBattleInfo;
import emu.lunarcore.proto.SceneBattleInfoOuterClass.SceneBattleInfo.BattleTargetInfoEntry;
import java.util.Objects;

/** Source-bound, transient activity battle; generic world settlement must not mutate this party. */
public final class LocalLegendBattle extends Battle {
  private final LocalLegendCatalog.Level level;
  private final StageExcel source;
  private final LocalLegendParty party;
  private final String stageIdentity;
  private final int world;

  LocalLegendBattle(
      Player player, LocalLegendCatalog.Level level, StageExcel stage, LocalLegendParty party) {
    super(player, party, stage);
    this.level = level;
    source = stage;
    this.party = party;
    stageIdentity = Objects.requireNonNull(LocalLegendStageBinding.identity(level, stage));
    world = player.getWorldLevel();
  }

  public LocalLegendCatalog.Level level() {
    return level;
  }

  @Override
  public void prepareAvatarBuffs() {
    /* Ordinary exploration effects are not consumed. */
  }

  @Override
  public SceneBattleInfo toProto() {
    var proto =
        SceneBattleInfo.newInstance()
            .setBattleId(getId())
            .setStageId(source.getId())
            .setLogicRandomSeed(getLogicRandomSeed())
            .setWorldLevel(world);
    for (int i = 0; i < getWaves().size(); i++)
      proto.addMonsterWaveList(getWaves().get(i).toProto().setWaveId(i + 1));
    party.battleActors().forEach(proto::addBattleAvatarList);
    // Native BMLFJNDCCJO (1580d0b0) consumes SceneBattleInfo field 2004, category 5,
    // into LineUpContext.BattleTargetList and LevelDataComponent.InitComponent.
    // Historical completion bits are not this battle's accumulated progress.
    var targets = BattleTargetList.newInstance();
    for (var goal : level.goals())
      targets.addBattleTargetList(
          BattleTarget.newInstance()
              .setId(goal.id())
              .setProgress(0)
              .setTotalProgress((int) goal.threshold()));
    proto.addBattleTargetInfo(BattleTargetInfoEntry.newInstance().setKey(5).setValue(targets));
    return proto;
  }

  public boolean accepts(BattleStatistics stats) {
    return getPlayer().getBattle() == this
        && LocalLegendCatalog.level(level.group(), level.difficulty()) == level
        && level.stage(world) == source
        && party.valid()
        && stageIdentity.equals(LocalLegendStageBinding.identity(level, source))
        && getPlayer().getGameplay().getLocalLegendProgress().valid()
        && level.reportedMask(stats).isPresent()
        && LocalLegendWinnerTargets.mask(level).isPresent()
        && LocalLegendAchievementRules.winningRules(level).isPresent()
        && LocalLegendAchievementRules.reported(level, stats).isPresent();
  }

  /** Called only after the service has validated identity, avatars and begun this settlement. */
  public Battle finish(BattleEndStatus result, BattleStatistics stats) {
    var player = getPlayer();
    synchronized (player) {
      setResult(result);
      setStats(stats);
      int mask =
          result == BattleEndStatus.BATTLE_END_WIN
              ? level.reportedMask(stats).orElseThrow()
                  | LocalLegendWinnerTargets.mask(level).orElseThrow()
              : 0;
      var before = player.getGameplay().getLocalLegendProgress();
      var candidate = before.copy();
      long time = System.currentTimeMillis() / 1000;
      boolean targetsChanged = candidate.merge(level, mask, time);
      boolean changed = targetsChanged;
      if (result == BattleEndStatus.BATTLE_END_WIN)
        for (var rule : LocalLegendAchievementRules.winningRules(level).orElseThrow())
          changed |= candidate.mergeAchievement(rule, level, time);
      if (changed) {
        player.getGameplay().localLegendProgress(candidate);
        try {
          player.save();
        } catch (RuntimeException failure) {
          player.getGameplay().localLegendProgress(before);
          throw failure;
        }
        player.getQuestManager().onLocalLegendProgress();
        if (targetsChanged) org.hkrpg.server.protocol.LocalLegend46.notify(player, level);
      }
      player.setBattle(null);
      return this;
    }
  }
}
