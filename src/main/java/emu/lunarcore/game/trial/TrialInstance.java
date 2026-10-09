package emu.lunarcore.game.trial;

import emu.lunarcore.data.excel.AvatarDemoExcel;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.PlayerGameInstance;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.util.Location;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;

@Getter
public class TrialInstance extends PlayerGameInstance {
    private transient AvatarDemoExcel excel;

    private int stageId;
    private boolean finished;
    private final Map<Integer, TrialAvatar> trialAvatars;
    private final Map<Integer, emu.lunarcore.data.common.ExcelMonsterParam> configuredMonsters;
    private final Set<Integer> defeatedMonsters = new HashSet<>();
    private final org.hkrpg.server.gameplay.GameplayState.TrialCompletion rewardPlan;
    private org.hkrpg.server.gameplay.TrialRun run;

    // Player's last location
    private Location lastLocation;

    public TrialInstance(Player player, AvatarDemoExcel excel) {
        this(player, excel, List.of());
    }

    public TrialInstance(Player player, AvatarDemoExcel excel, List<TrialAvatar> trialAvatars) {
        this(player, excel, trialAvatars, null);
    }

    public TrialInstance(Player player, AvatarDemoExcel excel, List<TrialAvatar> trialAvatars, org.hkrpg.server.gameplay.TrialRun run) {
        this.setPlayer(player);
        this.excel = excel;
        this.stageId = excel.getId();
        this.trialAvatars = trialAvatars.stream().collect(Collectors.toUnmodifiableMap(TrialAvatar::getAvatarId, avatar -> avatar));
        this.configuredMonsters = excel.getMonsters() == null ? Map.of() : Map.copyOf(excel.getMonsters());
        this.run = run;
        this.rewardPlan = run == null ? org.hkrpg.server.gameplay.TrialRewards.snapshot(excel.getRewardID()) : run.getRewardPlan();

        // Save player's location
        this.lastLocation = run == null ? new Location(player) : org.hkrpg.server.gameplay.TrialRun.place(run.getHome());
        if (run != null) {
            this.finished = run.getPhase() == org.hkrpg.server.gameplay.TrialRun.Phase.WON;
            this.defeatedMonsters.addAll(run.getDefeated());
        }
    }

    public void attachRun(org.hkrpg.server.gameplay.TrialRun value) {
        if (run != null) throw new IllegalStateException("Trial already has a saved run");
        run = value;
    }

    // Battle events

    @Override
    public void onBattleStart(Battle battle) {

    }

    @Override
    public void onBattleFinish(Battle battle, BattleEndStatus result, BattleStatistics stats) {
        synchronized (getPlayer()) {
            var scene = getPlayer().getScene();
            if (result != BattleEndStatus.BATTLE_END_WIN || this.finished
                    || getPlayer().getInstance() != this || battle == null || battle.getPlayer() != getPlayer()
                    || battle.getLineup() != getPlayer().getCurrentLineup()
                    || scene == null || configuredMonsters.isEmpty() || battle.getNpcMonsters().isEmpty()) return;
            var defeated = new HashSet<Integer>();
            for (var monster : battle.getNpcMonsters()) {
                var expected = configuredMonsters.get(monster.getInstId());
                if (monster.getScene() != scene || monster.getGroupId() != excel.getMazeGroupID1()
                        || expected == null || monster.getExcel() == null
                        || expected.getNpcMonsterId() != monster.getExcel().getId()
                        || expected.getEventId() != monster.getEventId() || monster.isFarmElement()
                        || scene.getEntities().containsValue(monster) || !defeated.add(monster.getInstId())) return;
            }
            defeatedMonsters.addAll(defeated);
            if (defeatedMonsters.containsAll(configuredMonsters.keySet())) {
                if (run != null) run.won();
                org.hkrpg.server.gameplay.TrialRewards.complete(getPlayer(), stageId, rewardPlan);
                this.finished = true;
                returnToWorld();
            }
        }
    }

    public boolean returnToWorld() {
        synchronized (getPlayer()) {
            if (getPlayer().getInstance() != this) return false;
            var teams = getPlayer().getLineupManager();
            int previous = teams.getCurrentExtraIndex();
            boolean returned = false;
            try {
                if (run != null) {
                    run.returning();
                    getPlayer().save();
                }
                teams.setCurrentExtraLineup(0, false);
                returned = getPlayer().loadScene(this.getLastLocation(), true);
                if (returned) getPlayer().setInstance(null);
                return returned;
            } finally {
                if (!returned) teams.setCurrentExtraLineup(previous, false);
            }
        }
    }

    @Override
    public void onLeave() {
        cleanupActors();
        var teams = getPlayer().getLineupManager();
        var lineup = teams.getExtraLineupByType(emu.lunarcore.proto.ExtraLineupTypeOuterClass.ExtraLineupType.LINEUP_STAGE_TRIAL_VALUE);
        lineup.getAvatars().removeIf(trialAvatars::containsKey);
        lineup.setLeader(0);
        lineup.setMp(0);
        lineup.save();
        if (teams.getCurrentExtraIndex() == lineup.getExtraLineupType()) teams.setCurrentExtraLineup(0, false);
        if (getPlayer().getGameplay().getTrialRun() == run && run != null) {
            getPlayer().getGameplay().setTrialRun(null);
            getPlayer().save();
        }
        // Send trial status
        if (this.finished) org.hkrpg.server.protocol.TrialActivity46.finished(getPlayer(), this);
    }

    void cleanupActors() {
        var storage = getPlayer().getAvatars().getTempAvatars();
        for (var actor : trialAvatars.entrySet()) storage.remove(actor.getKey(), actor.getValue());
    }

}
