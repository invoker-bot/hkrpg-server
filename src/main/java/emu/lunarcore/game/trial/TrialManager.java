package emu.lunarcore.game.trial;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.BasePlayerManager;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.PlayerGender;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.proto.ExtraLineupTypeOuterClass.ExtraLineupType;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import com.google.gson.Gson;
import org.hkrpg.server.gameplay.TrialRun;

public class TrialManager extends BasePlayerManager {

    public TrialManager(Player player) {
        super(player);
    }

    /** Called before saving the player; never snapshots the ordinary party during return. */
    public void recordState() {
        synchronized (getPlayer()) {
            var trial = getPlayer().getInstance(TrialInstance.class);
            var run = getPlayer().getGameplay().getTrialRun();
            if (trial == null || run == null || trial.getRun() != run
                    || getPlayer().getLineupManager().getCurrentExtraIndex() != ExtraLineupType.LINEUP_STAGE_TRIAL_VALUE) return;
            run.record(getPlayer().getCurrentLineup(), trial.getTrialAvatars().values());
            run.getDefeated().clear();
            run.getDefeated().addAll(trial.getDefeatedMonsters());
            var scene = getPlayer().getScene();
            if (scene != null && scene.getPlaneType() == emu.lunarcore.game.enums.PlaneType.TrialActivity
                    && getPlayer().getEntryId() == trial.getExcel().getMapEntranceID()) run.locate(getPlayer());
        }
    }

    /** Restore after formal avatars/lineups load, before the generic raid unstuck path. */
    public boolean restoreOnLogin() {
        synchronized (getPlayer()) {
            var player = getPlayer();
            var run = player.getGameplay().getTrialRun();
            if (run == null) return false;
            if (player.getInstance() != null || player.getGameplay().getRogue().isInside()
                    || player.getGameplay().getParkourVenue().inside()
                    || player.getGameplay().getCurrentMissionRaid() != 0
                    || player.getGameplay().getStoryLines().getCurrentId() != 0
                    || player.getGameplay().getStoryLines().isTransitionPending())
                throw new IllegalStateException("Overlapping saved trial instance");
            if (!validHome(run.getHome())) throw new IllegalStateException("Invalid saved trial home");
            if (run.getPhase() == TrialRun.Phase.RETURNING || run.getPhase() == TrialRun.Phase.WON) {
                if (run.getPhase() == TrialRun.Phase.WON) {
                    if (run.getRewardPlan() == null) throw new IllegalStateException("Missing confirmed trial reward");
                    org.hkrpg.server.gameplay.TrialRewards.complete(player, run.getStageId(), run.getRewardPlan());
                }
                player.getLineupManager().setCurrentExtraLineup(0, false);
                if (!player.loadScene(TrialRun.place(run.getHome()), false))
                    throw new IllegalStateException("Cannot restore trial return");
                var old = player.getLineupManager().getExtraLineupByType(ExtraLineupType.LINEUP_STAGE_TRIAL_VALUE);
                old.getAvatars().removeIf(run.getTrialConfigs()::containsKey);
                old.setLeader(0);
                old.setMp(0);
                old.save();
                player.getGameplay().setTrialRun(null);
                player.save();
                return true;
            }
            if (run.getPhase() != TrialRun.Phase.ENTERING && run.getPhase() != TrialRun.Phase.ACTIVE)
                throw new IllegalStateException("Invalid saved trial phase");
            var demo = run.demo();
            var entrance = GameData.getMapEntranceExcelMap().get(demo.getMapEntranceID());
            var plane = entrance == null ? null : GameData.getMazePlaneExcelMap().get(entrance.getPlaneID());
            if (entrance == null || plane == null
                    || plane.getPlaneType() != emu.lunarcore.game.enums.PlaneType.TrialActivity)
                throw new IllegalStateException("Invalid frozen trial entrance");
            var savedPlace = run.getLocation();
            if (savedPlace != null && (savedPlace.getEntry() != demo.getMapEntranceID()
                    || savedPlace.getPlane() != entrance.getPlaneID()
                    || savedPlace.getFloor() != entrance.getFloorID()
                    || savedPlace.getDimension() != 0 || !finite(savedPlace)))
                throw new IllegalStateException("Invalid saved trial location");
            if (run.getStageId() <= 0 || demo.getMapEntranceID() <= 0 || demo.getMazeGroupID1() <= 0
                    || demo.getTrialAvatarList() == null || !demo.getTrialAvatarList().containsAll(run.getRoster())
                    || run.getRoster().isEmpty() || run.getRoster().size() > 4
                    || new HashSet<>(run.getRoster()).size() != run.getRoster().size()
                    || !new HashSet<>(run.getRoster()).equals(run.getTrialConfigs().keySet())
                    || !run.getVitals().keySet().equals(run.getTrialConfigs().keySet())
                    || run.getSkills() == null || !run.getSkills().keySet().equals(run.getTrialConfigs().keySet())
                    || run.getSkillPlans() == null || !run.getSkillPlans().isEmpty()
                        && !run.getSkillPlans().keySet().equals(run.getTrialConfigs().keySet())
                    || run.getLeader() < 0 || run.getLeader() >= run.getRoster().size()
                    || run.getMp() < 0 || run.getRewardPlan() == null || demo.getMonsters().isEmpty()
                    || !demo.getMonsters().keySet().containsAll(run.getDefeated())
                    || run.getDefeated().containsAll(demo.getMonsters().keySet()))
                throw new IllegalStateException("Invalid saved trial party or encounter");
            var prepared = new ArrayList<TrialAvatar>();
            int maximum = emu.lunarcore.GameConstants.MAX_MP;
            for (int id : run.getRoster()) {
                var row = new Gson().fromJson(run.getTrialConfigs().get(id), emu.lunarcore.data.excel.SpecialAvatarExcel.class);
                var actor = row == null ? null : GameData.getActorForTrial(row.getAvatarID());
                var vital = run.getVitals().get(id);
                if (row == null || row.getId() != id || actor == null || player.getAvatars().hasAvatar(id)
                        || !org.hkrpg.server.gameplay.TrialSkillTrees.supports(actor, row.getEnhancedID())
                        || actor instanceof emu.lunarcore.data.excel.TrialActorExcel plot && !plot.supportsTrial(row)
                        || vital == null || vital.getHp() < 0 || vital.getHp() > 10000 || vital.getSp() < 0 || vital.getSp() > 10000)
                    throw new IllegalStateException("Invalid frozen trial actor: " + id);
                var encodedPlan = run.getSkillPlans().get(id);
                var plan = encodedPlan == null ? null : new Gson().fromJson(encodedPlan, org.hkrpg.server.gameplay.TrialSkillPresets.Plan.class);
                if (plan == null) {
                    if (row.getSkillTreeTemplate() != null && !row.getSkillTreeTemplate().isBlank())
                        throw new IllegalStateException("Missing frozen trial skill preset");
                    plan = org.hkrpg.server.gameplay.TrialSkillPresets.legacy();
                }
                if (plan.custom() != "TYPE_CUSTOM".equals(row.getSkillTreeTemplate()))
                    throw new IllegalStateException("Mismatched frozen trial skill preset");
                var restoredActor = TrialAvatar.restore(player, row, actor, plan, run.getSkills().get(id));
                prepared.add(restoredActor);
                maximum = Math.addExact(maximum, emu.lunarcore.GameConstants.BONUS_MAX_MP.get(actor.getAvatarID()));
            }
            if (run.getMp() > maximum) throw new IllegalStateException("Invalid saved trial secret points");
            var lineup = player.getLineupManager().getExtraLineupByType(ExtraLineupType.LINEUP_STAGE_TRIAL_VALUE);
            var trial = new TrialInstance(player, demo, prepared, run);
            try {
                for (var avatar : prepared) {
                    player.getAvatars().addTempAvatar(avatar);
                    var vital = run.getVitals().get(avatar.getAvatarId());
                    avatar.setCurrentHp(lineup, vital.getHp());
                    avatar.setCurrentSp(lineup, vital.getSp());
                }
                lineup.replace(new IntArrayList(run.getRoster()));
                lineup.setLeader(run.getLeader());
                lineup.setMp(run.getMp());
                player.setInstance(trial);
                player.getLineupManager().setCurrentExtraLineup(ExtraLineupType.LINEUP_STAGE_TRIAL_VALUE, false);
                var place = run.getLocation();
                boolean loaded;
                if (place == null || run.getPhase() == TrialRun.Phase.ENTERING)
                    loaded = player.enterScene(demo.getMapEntranceID(), 0, false);
                else {
                    loaded = player.loadScene(TrialRun.place(place), false);
                }
                if (!loaded) throw new IllegalStateException("Cannot restore active trial scene");
                run.arrived();
                player.save();
                return true;
            } catch (RuntimeException error) {
                if (player.getInstance() == trial) player.setInstance(null);
                else trial.cleanupActors();
                player.getGameplay().setTrialRun(run);
                player.getLineupManager().setCurrentExtraLineup(0, false);
                player.save();
                throw error;
            }
        }
    }

    private boolean validHome(org.hkrpg.server.gameplay.StoryLineState.Place place) {
        if (place == null || !finite(place) || place.getDimension() != 0) return false;
        var entry = GameData.getMapEntranceExcelMap().get(place.getEntry());
        var plane = GameData.getMazePlaneExcelMap().get(place.getPlane());
        return entry != null && plane != null && entry.getPlaneID() == place.getPlane()
                && entry.getFloorID() == place.getFloor()
                && (plane.getPlaneType() == emu.lunarcore.game.enums.PlaneType.Town
                || plane.getPlaneType() == emu.lunarcore.game.enums.PlaneType.Maze
                || plane.getPlaneType() == emu.lunarcore.game.enums.PlaneType.Train);
    }

    private static boolean finite(org.hkrpg.server.gameplay.StoryLineState.Place place) {
        if (place.getPos() == null || place.getRot() == null) return false;
        for (var p : List.of(place.getPos(), place.getRot()))
            if (!Double.isFinite(p.getX()) || !Double.isFinite(p.getY()) || !Double.isFinite(p.getZ())) return false;
        return true;
    }

    public TrialAvatar createTrialAvatar(int specialAvatarId) {
        TrialAvatar avatar = buildTrialAvatar(specialAvatarId);
        if (avatar != null) getPlayer().getAvatars().addTempAvatar(avatar);
        return avatar;
    }

    /** Build without publishing a temporary actor; callers can preflight a whole party. */
    public TrialAvatar buildTrialAvatar(int specialAvatarId) {
        // Get excels
        var specialAvatarExcel = specialRow(specialAvatarId);
        if (specialAvatarExcel == null || !matchesGender(specialAvatarExcel.getAvatarID())) return null;

        var avatarExcel = GameData.getActorForTrial(specialAvatarExcel.getAvatarID());
        if (avatarExcel == null) return null;
        if (!org.hkrpg.server.gameplay.TrialSkillTrees.supports(avatarExcel, specialAvatarExcel.getEnhancedID())) return null;
        if (avatarExcel instanceof emu.lunarcore.data.excel.TrialActorExcel plot
                && !plot.supportsTrial(specialAvatarExcel)) return null;

        try {
            return new TrialAvatar(getPlayer(), specialAvatarExcel, avatarExcel);
        } catch (IllegalArgumentException invalidResource) {
            return null;
        }
    }

    public emu.lunarcore.data.excel.SpecialAvatarExcel specialRow(int specialAvatarId) {
        int worldLevel = getPlayer().getWorldLevel();
        if (worldLevel < 0) return null;
        var specialAvatarExcel = GameData.getSpecialAvatarExcelMap().get(specialAvatarId, worldLevel);
        if (specialAvatarExcel == null) {
            // Fixed-level trials have only a zero-world row. Scaled trials need an exact row.
            var fixed = GameData.getSpecialAvatarExcelMap().get(specialAvatarId, 0);
            if (fixed != null && !fixed.isIsUseWorldLevel()) specialAvatarExcel = fixed;
        }
        return specialAvatarExcel;
    }

    public boolean matchesGender(int actorId) {
        var path = GameData.getMultiplePathAvatarExcelMap().get(actorId);
        if (path != null && path.getGender() != null && path.getGender() != PlayerGender.GENDER_NONE)
            return path.getGender() == getPlayer().getGender();
        // Compatibility with older resource packs that omit the path directory.
        if (actorId >= 8001 && actorId <= 8010)
            return getPlayer().getGender() == (actorId % 2 == 1 ? PlayerGender.GENDER_MAN : PlayerGender.GENDER_WOMAN);
        return true;
    }

    public TrialInstance startTrial(int stageId) {
        synchronized (getPlayer()) {
            return startTrialLocked(stageId);
        }
    }

    public emu.lunarcore.server.packet.Retcode leaveTrial(int stageId) {
        synchronized (getPlayer()) {
            var trial = getPlayer().getInstance(TrialInstance.class);
            if (stageId <= 0 || trial == null || trial.getStageId() != stageId)
                return emu.lunarcore.server.packet.Retcode.REQ_PARA_INVALID;
            if (getPlayer().isInBattle()) return emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY;
            return trial.returnToWorld() ? emu.lunarcore.server.packet.Retcode.SUCC
                    : emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY;
        }
    }

    private TrialInstance startTrialLocked(int stageId) {
        if (getPlayer().isInBattle() || getPlayer().getInstance() != null
                || getPlayer().getMissionTrialManager().inside()
                || getPlayer().getGameplay().getTrialRun() != null
                || getPlayer().getLineupManager().getCurrentExtraIndex() != 0
                || getPlayer().getGameplay().getStoryLines().getCurrentId() != 0
                || getPlayer().getGameplay().getStoryLines().isTransitionPending()
                || getPlayer().getGameplay().getRogue().isInside()
                || getPlayer().getGameplay().getParkourVenue().inside()
                || getPlayer().getGameplay().getCurrentMissionRaid() != 0) return null;
        // Get excel
        var excel = GameData.getAvatarDemoExcelMap().get(stageId);
        if (excel == null || excel.getTrialAvatarList() == null || excel.getTrialAvatarList().isEmpty()
                || new HashSet<>(excel.getTrialAvatarList()).size() != excel.getTrialAvatarList().size()) return null;
        if (excel.getSPList() == null || excel.getSPList().size() != excel.getTrialAvatarList().size()) return null;
        for (int energy : excel.getSPList()) if (energy < 0 || energy > 10000) return null;

        // Preflight every eligible member before publishing any actor or changing the party.
        var avatars = new IntArrayList();
        var prepared = new ArrayList<TrialAvatar>();
        var energies = new IntArrayList();
        int candidate = 0;
        for (int id : excel.getTrialAvatarList()) {
            int energy = excel.getSPList().getInt(candidate++);
            var row = specialRow(id);
            if (row == null) return null;
            if (!matchesGender(row.getAvatarID())) continue;
            if (getPlayer().getAvatars().hasAvatar(id)) return null;
            TrialAvatar avatar = this.buildTrialAvatar(id);
            if (avatar == null) return null;
            prepared.add(avatar);
            energies.add(energy);
            avatars.add(id);
        }
        if (avatars.isEmpty() || avatars.size() > emu.lunarcore.GameConstants.MAX_AVATARS_IN_TEAM) return null;

        // Get trial lineup
        PlayerLineup lineup = getPlayer().getLineupManager().getExtraLineupByType(ExtraLineupType.LINEUP_STAGE_TRIAL_VALUE);
        var oldRoster = new IntArrayList(lineup.getAvatars());
        int oldLeader = lineup.getLeader(), oldMp = lineup.getMp();
        TrialInstance trial = new TrialInstance(getPlayer(), excel, prepared);
        boolean success = false;
        try {
            for (var avatar : prepared) getPlayer().getAvatars().addTempAvatar(avatar);
            for (int i = 0; i < prepared.size(); i++) prepared.get(i).setCurrentSp(lineup, energies.getInt(i));
            lineup.replace(avatars);
            lineup.setMp(lineup.getMaxMp());
            var run = new org.hkrpg.server.gameplay.TrialRun(getPlayer(), excel, prepared, lineup, trial.getRewardPlan());
            trial.attachRun(run);
            getPlayer().getGameplay().setTrialRun(run);
            getPlayer().save();
            getPlayer().getLineupManager().setCurrentExtraLineup(ExtraLineupType.LINEUP_STAGE_TRIAL_VALUE, false);
            getPlayer().setInstance(trial);
            success = getPlayer().enterScene(excel.getMapEntranceID(), 0, true);
            if (success) {
                run.arrived();
                getPlayer().save();
            }
            return success ? trial : null;
        } finally {
            if (!success) {
                if (getPlayer().getInstance() == trial) getPlayer().setInstance(null);
                else trial.cleanupActors();
                if (getPlayer().getGameplay().getTrialRun() == trial.getRun()) {
                    getPlayer().getGameplay().setTrialRun(null);
                    getPlayer().save();
                }
                getPlayer().getLineupManager().setCurrentExtraLineup(0, false);
                lineup.getAvatars().clear();
                lineup.getAvatars().addAll(oldRoster);
                lineup.setLeader(oldLeader);
                lineup.setMp(oldMp);
                lineup.save();
            }
        }
    }

}
