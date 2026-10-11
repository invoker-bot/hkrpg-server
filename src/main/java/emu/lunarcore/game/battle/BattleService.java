package emu.lunarcore.game.battle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import emu.lunarcore.GameConstants;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.CocoonExcel;
import emu.lunarcore.data.excel.StageExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.skills.MazeSkill;
import emu.lunarcore.game.enums.StageType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.game.scene.entity.GameEntity;
import emu.lunarcore.proto.AvatarPropertyOuterClass.AvatarProperty;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.game.BaseGameService;
import emu.lunarcore.server.game.GameServer;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.*;
import it.unimi.dsi.fastutil.ints.IntSet;

public class BattleService extends BaseGameService {

    public BattleService(GameServer server) {
        super(server);
    }

    private static boolean activityBlocksBattle(Player player) {
        var state = player.getGameplay();
        return state.getBoxingClub().getActive() != null
                || state.getBoxingClubResonance().getActive() != null
                || state.getStoryLines().isTransitionPending()
                || state.getClockPark().getActive() != null
                || state.getRhythm().getActiveLevel() != 0
                || state.getParkour().getActiveLevel() != 0;
    }

    public void attack(Player player, int casterId, int attackedGroupId, MazeSkill castedSkill, IntSet hitTargets, IntSet assistMonsters) {
        synchronized (player) {
            var result = attackLocked(player,casterId,castedSkill,hitTargets,assistMonsters);
            if (result.battle() != null) player.sendPacket(new PacketSceneCastSkillScRsp(result.battle(), attackedGroupId));
            else if (result.retcode() == Retcode.FAIL) player.sendPacket(new PacketSceneCastSkillScRsp());
            else player.sendPacket(new PacketSceneCastSkillScRsp(attackedGroupId, result.retcode()));
        }
    }
    /** Resolve gameplay once; versioned callers serialize its actual per-monster results. */
    public MazeAttackResult resolveAttack(Player player, int casterId, MazeSkill castedSkill, IntSet hitTargets, IntSet assistMonsters) {
        synchronized (player) { return attackLocked(player,casterId,castedSkill,hitTargets,assistMonsters); }
    }
    public MazeAttackResult resolveAttack(Player player, int casterId, MazeSkill castedSkill, IntSet hitTargets,
            IntSet assistMonsters, MazeSkill.CallbackPhase phase) {
        synchronized (player) { return attackLocked(player,casterId,castedSkill,hitTargets,assistMonsters,phase); }
    }
    private MazeAttackResult attackLocked(Player player, int casterId, MazeSkill castedSkill, IntSet hitTargets, IntSet assistMonsters) {
        // Existing non-native callers retain their established hit dispatch.
        return attackLocked(player,casterId,castedSkill,hitTargets,assistMonsters,MazeSkill.CallbackPhase.PROJECTILE_HIT);
    }
    private MazeAttackResult attackLocked(Player player, int casterId, MazeSkill castedSkill, IntSet hitTargets,
            IntSet assistMonsters, MazeSkill.CallbackPhase phase) {
        if (activityBlocksBattle(player)) {
            return MazeAttackResult.failure(Retcode.SYSTEM_BUSY);
        }
        if (player.isInBattle() || player.getScene() == null) {
            return MazeAttackResult.failure(Retcode.FAIL);
        }
        // Setup variables
        List<GameEntity> targets = new ArrayList<>();
        GameAvatar castingAvatar = null;

        // Check if attacker is the player or not
        if (player.getScene().getAvatarEntityIds().contains(casterId)) {
            // Get casting avatar
            castingAvatar = player.getScene().getAvatarByEntityId(casterId);
            if (castingAvatar == null) {
                return MazeAttackResult.failure(Retcode.REQ_PARA_INVALID);
            }

            // Player is the attacker, add hit targets to the battle
            for (int entityId : hitTargets) {
                GameEntity entity = player.getScene().getEntities().get(entityId);

                if (entity != null) {
                    targets.add(entity);
                }
            }
        } else {
            // A monster attacked the player -> Check if player is ambushed first
            boolean isAmbushed = false;

            for (int entityId : hitTargets) {
                if (player.getScene().getAvatarEntityIds().contains(entityId)) {
                    isAmbushed = true;
                    break;
                }
            }

            // Start battle if player has been ambushed
            if (isAmbushed) {
                GameEntity entity = player.getScene().getEntities().get(casterId);

                if (entity != null) {
                    targets.add(entity);
                }
            } else {
                // Skip battle since the monster didnt attack anyone
                return MazeAttackResult.empty();
            }
        }

        // Skip battle if no attacked entities detected
        if (targets.size() == 0) {
            return MazeAttackResult.empty();
        }

        for (var target : targets) {
            if (target instanceof EntityMonster monster && org.hkrpg.server.gameplay.BoxingClubManager.genericMonsterRequiresMatch(monster)) {
                return MazeAttackResult.failure(Retcode.REQ_PARA_INVALID);
            }
        }
        for (int id : assistMonsters) {
            if (player.getScene().getEntities().get(id) instanceof EntityMonster monster && org.hkrpg.server.gameplay.BoxingClubManager.genericMonsterRequiresMatch(monster)) {
                return MazeAttackResult.failure(Retcode.REQ_PARA_INVALID);
            }
        }

        try (var outcomes = MazeAttackResult.observe(player, targets, assistMonsters)) {
            // Separate entities into monster list
            Set<EntityMonster> monsters = new LinkedHashSet<>();

            // Destroy props
            for (var it = targets.iterator(); it.hasNext();) {
                GameEntity entity = it.next();

                if (entity instanceof EntityMonster monster) {
                    monsters.add(monster);
                } else if (entity instanceof EntityProp prop) {
                    it.remove();
                    player.getScene().destroyProp(prop);
                } else {
                    it.remove();
                }
            }

            if (castedSkill != null && castingAvatar != null) {
                castedSkill.prepareAttack(castingAvatar, targets, phase);
            }

            // Check if we are using a skill that doesnt trigger a battle
            if (castedSkill != null && !castedSkill.isTriggerBattle(castingAvatar)) {
                // Apply buffs to monsters
                if (castingAvatar == null) {
                    return MazeAttackResult.failure(Retcode.REQ_PARA_INVALID);
                }
                castedSkill.onCastHit(castingAvatar, targets);
                // Skip battle if our technique does not trigger a battle
                return outcomes.success(null);
            }

            // Start battle
            if (monsters.size() > 0) {
                // Maze skill attack event
                if (castedSkill != null && castingAvatar != null) {
                    castedSkill.onAttack(castingAvatar, targets);
                }

                // Callbacks may defeat only some targets or remove them administratively. Neither
                // may reappear in the encounter's NPC roster or stage waves.
                monsters.removeIf(monster -> !outcomes.present(monster));
                if (monsters.isEmpty()) {
                    return outcomes.success(null);
                }

                // Add any assisting monsters from monster assist list
                for (int entityId : assistMonsters) {
                    GameEntity entity = player.getScene().getEntities().get(entityId);

                    if (entity instanceof EntityMonster monster && outcomes.present(monster)) {
                        monsters.add(monster);
                    }
                }

                // Create battle and add npc monsters to it
                Battle battle = new Battle(player, player.getLineupManager().getCurrentLineup(), monsters);

                // Make sure we have at least one stage for the battle
                if (battle.getStage() == null) {
                    return MazeAttackResult.failure(Retcode.FAIL);
                }

                if (battle.getStoryChallengeBinding().required() && battle.getStoryChallengeBinding().context() == null) {
                    return MazeAttackResult.failure(Retcode.FAIL);
                }

                if (castedSkill != null && castingAvatar != null) {
                    castedSkill.onBattle(castingAvatar, battle);
                }

                // Add buffs to battle
                if (castingAvatar != null) {
                    // The player is the one attacking
                    if (castedSkill != null) {
                        // Get elemental weakness debuff id
                        int buffId = castingAvatar.getExcel().getDamageType().getEnterBattleBuff();

                        // Replace with a special debuff that ignores all toughness
                        if (castedSkill.hasAdventureModifier(castingAvatar, "ADV_StageAbility_Maze_IgnoreWeakness_MazeSkillMark")) {
                            buffId = 1000119;
                        }

                        // Add buff to battle
                        int casterSlot = battle.getLineup().getAvatars().indexOf(castingAvatar.getAvatarId());
                        MazeBuff buff = battle.addBuff(buffId, casterSlot);
                        if (buff != null) {
                            buff.addTargetIndex(casterSlot);
                            buff.addDynamicValue("SkillIndex", castedSkill.getIndex());
                        }
                    }
                } else {
                    // Ambush debuff (from monsters)
                    battle.addBuff(GameConstants.BATTLE_AMBUSH_BUFF_ID, -1, 1);
                }

                // Player game instance callback
                if (player.getInstance() != null) {
                    player.getInstance().onBattleStart(battle);
                }

                battle.setDailyWeaknessEntry(org.hkrpg.server.gameplay.DailyWeaknessEntries.capture(player, battle, casterId, castedSkill, targets));
                // Set the accepted battle before its response is serialized.
                player.setBattle(battle);
                battle.setActivityKillQuests(org.hkrpg.server.gameplay.ActivityKillQuests.capture(player, battle));
                battle.setMissionChallengeWin(org.hkrpg.server.gameplay.MissionChallengeWin.capture(player, battle));
                return outcomes.success(battle);
            }

            return outcomes.success(null);
        } finally {
            if (castedSkill != null && castingAvatar != null) castedSkill.finishAttack(castingAvatar);
        }
    }

    public Battle startBattle(Player player, int stageId, int worldLevel) {
        synchronized (player) {
            if (org.hkrpg.server.gameplay.StageEventDifficultySettings.applies(player, stageId)) {
                if (worldLevel != player.getWorldLevel()) return null;
                return startEventBattle(player, stageId);
            }
            return startBattleLocked(player,stageId,worldLevel);
        }
    }
    public Battle startEventBattle(Player player, int eventId) {
        synchronized (player) {
            if (player.isInBattle() || activityBlocksBattle(player)
                || org.hkrpg.server.gameplay.StoryChallengeScope.isNativeStory(player)) return null;
            if (org.hkrpg.server.gameplay.StageEventDifficultySettings.applies(player, eventId)) {
                var plan = org.hkrpg.server.gameplay.StageEventDifficultySettings.plan(player, eventId);
                if (plan == null) return null;
                Battle battle = new org.hkrpg.server.gameplay.StageEventDifficultyBattle(player, plan);
                player.setBattle(battle);
                battle.setActivityKillQuests(org.hkrpg.server.gameplay.ActivityKillQuests.capture(player, battle));
                battle.setMissionBattleKills(org.hkrpg.server.gameplay.MissionBattleKills.capture(player, battle, eventId, plan.world()));
                battle.setMissionBattleCustomValues(org.hkrpg.server.gameplay.MissionBattleCustomValues.capture(player, battle, eventId, plan.world()));
                return battle;
            }
            return startBattleLocked(player,eventId,org.hkrpg.server.gameplay.RaidDifficultySettings.world(player),true);
        }
    }
    private Battle startBattleLocked(Player player,int stageId,int worldLevel) {
        return startBattleLocked(player,stageId,worldLevel,false);
    }
    private Battle startBattleLocked(Player player,int stageId,int worldLevel,boolean eventOnly) {
        // Sanity check to make sure player isnt in a battle
        if (player.isInBattle() || activityBlocksBattle(player)
                || org.hkrpg.server.gameplay.StoryChallengeScope.isNativeStory(player)) {
            return null;
        }

        // Use stage event if found
        StageExcel stage;
        if (eventOnly) {
            stage = org.hkrpg.server.gameplay.SceneEventBattles.resolveClientEvent(stageId,worldLevel);
        } else {
            var configEvent = GameData.getPlaneEventExcelMap().get(stageId, worldLevel);
            int battleStageId = configEvent == null ? stageId : configEvent.getStageId();
            stage = GameData.getStageExcelMap().get(battleStageId);
        }
        if (stage == null || stage.getStageType() == StageType.BoxingClub) return null;

        // Create new battle for player
        Battle battle = new Battle(player, player.getCurrentLineup(), stage);
        player.setBattle(battle);
        battle.setActivityKillQuests(org.hkrpg.server.gameplay.ActivityKillQuests.capture(player, battle));
        battle.setMissionBattleKills(org.hkrpg.server.gameplay.MissionBattleKills.capture(player, battle, stageId, worldLevel));
        battle.setMissionBattleCustomValues(org.hkrpg.server.gameplay.MissionBattleCustomValues.capture(player, battle, stageId, worldLevel));

        // Player game instance callback
        if (player.getInstance() != null) {
            player.getInstance().onBattleStart(battle);
        }

        // Send packet
        return battle;
    }

    public Battle startCocoon(Player player, int cocoonId, int worldLevel, int wave) {
        synchronized (player) { return startCocoonLocked(player,cocoonId,worldLevel,wave); }
    }
    private Battle startCocoonLocked(Player player,int cocoonId,int worldLevel,int wave) {
        // Sanity check to make sure player isnt in a battle
        if (player.isInBattle() || activityBlocksBattle(player)
                || org.hkrpg.server.gameplay.StoryChallengeScope.isNativeStory(player)) {
            return null;
        }

        // Get cocoon data
        CocoonExcel cocoonExcel = GameData.getCocoonExcelMap().get(cocoonId, worldLevel);
        if (cocoonId <= 0 || worldLevel < 0 || cocoonExcel == null
                || cocoonExcel.getCocoonId() != cocoonId || cocoonExcel.getWorldLevel() != worldLevel
                || cocoonExcel.getMappingInfoID() <= 0 || cocoonExcel.getStaminaCost() < 0
                || cocoonExcel.getMaxChallengeCnt() <= 0 || cocoonExcel.getMaxChallengeCnt() > 24
                || cocoonExcel.getStageIDList() == null || cocoonExcel.getStageIDList().isEmpty()) {
            return null;
        }

        // Get waves
        wave = Math.min(Math.max(1, wave), cocoonExcel.getMaxChallengeCnt());

        // Sanity check stamina
        long requestedCost = (long) cocoonExcel.getStaminaCost() * wave;
        if (requestedCost > Integer.MAX_VALUE || player.getStamina() < requestedCost) {
            return null;
        }
        int cost = (int) requestedCost;

        // Validate the whole resource pool before randomness; a missing choice must not shorten
        // the requested challenge while charging its full cost. Pinned 4.6 caps all rows at 24.
        for (int stageId : cocoonExcel.getStageIDList()) {
            var candidate = GameData.getStageExcelMap().get(stageId);
            if (stageId <= 0 || candidate == null || candidate.getId() != stageId
                    || candidate.getStageType() != StageType.Cocoon || candidate.getMonsterWaves() == null
                    || candidate.getMonsterWaves().isEmpty()
                    || candidate.getMonsterWaves().stream().anyMatch(monsters -> monsters == null || monsters.isEmpty()))
                return null;
        }

        // Get stages from cocoon
        List<StageExcel> stages = new ArrayList<>();

        for (int i = 0; i < wave; i++) {
            StageExcel stage = GameData.getStageExcelMap().get(cocoonExcel.getRandomStage());

            if (stage == null || !cocoonExcel.getStageIDList().contains(stage.getId())
                    || stage.getStageType() != StageType.Cocoon) return null;
            stages.add(stage);
        }

        // Sanity
        if (stages.size() != wave) {
            return null;
        }

        // Build battle from cocoon data
        Battle battle = new Battle(player, player.getLineupManager().getCurrentLineup(), stages);
        battle.setMappingInfoId(cocoonExcel.getMappingInfoID());
        battle.setCocoonWave(wave);
        battle.setCocoonId(cocoonId);
        battle.setWorldLevel(worldLevel);
        battle.setStaminaCost(cost);

        player.setBattle(battle);

        return battle;
    }

    public Battle finishBattle(Player player, BattleEndStatus result, BattleStatistics stats) {
        synchronized (player) {
            if (!player.isInBattle()) return null;
            return finishBattle(player, player.getBattle().getId(), player.getBattle().getStage().getId(), result, stats);
        }
    }

    public Battle finishBattle(Player player, int battleId, int stageId, BattleEndStatus result, BattleStatistics stats) {
        return finishBattle(player, battleId, stageId, result, stats, List.of());
    }

    public Battle finishBattle(Player player, int battleId, int stageId, BattleEndStatus result, BattleStatistics stats,
            List<org.hkrpg.server.gameplay.MissionBattleKills.Monster> monsters) {
        return finishBattle(player, battleId, stageId, result, stats, monsters, List.of());
    }
    public Battle finishBattle(Player player, int battleId, int stageId, BattleEndStatus result, BattleStatistics stats,
            List<org.hkrpg.server.gameplay.MissionBattleKills.Monster> monsters,
            List<org.hkrpg.server.gameplay.RogueResonance.Energy> energies) {
        synchronized (player) {
            var active = player.getBattle();
            if (active == null || active.getId() != battleId || active.getStage().getId() != stageId
                    || result != BattleEndStatus.BATTLE_END_WIN && result != BattleEndStatus.BATTLE_END_LOSE && result != BattleEndStatus.BATTLE_END_QUIT
                    || stats == null || !validStats(active, stats) || !org.hkrpg.server.gameplay.StoryChallengeReports.valid(active, result, stats) || monsters == null || monsters.size() > 1024
                    || active instanceof org.hkrpg.server.gameplay.LocalLegendBattle legend && !legend.accepts(stats)
                    || active instanceof org.hkrpg.server.gameplay.BoxingClubBattle boxing && !boxing.accepts(result, stats)
                    || active instanceof org.hkrpg.server.gameplay.BoxingClubResonanceBattle resonance && !resonance.accepts(result, stats)
                    || monsters.stream().anyMatch(java.util.Objects::isNull)
                    || !org.hkrpg.server.gameplay.RogueResonance.validReport(active, energies)
                    || active.getRaidDifficultyScope() != null && !active.getRaidDifficultyScope().matches(player)
                    || active instanceof org.hkrpg.server.gameplay.StageEventDifficultyBattle adjusted && !adjusted.matches(player)
                    || result == BattleEndStatus.BATTLE_END_WIN && player.getStamina() < active.getStaminaCost()
                    || active.isSettling()) return null;
            if (!org.hkrpg.server.gameplay.ActivityKillQuests.record(player, active, result, monsters)) return null;
            if (!active.beginSettlement()) return null;
            org.hkrpg.server.gameplay.RogueResonance.saveReport(active, energies);
            active.setReportedMonsters(result == BattleEndStatus.BATTLE_END_WIN ? List.copyOf(monsters) : List.of());
            if (active instanceof org.hkrpg.server.gameplay.BoxingClubBattle boxing)
                return boxing.finish(result, stats);
            if (active instanceof org.hkrpg.server.gameplay.BoxingClubResonanceBattle resonance)
                return resonance.finish(result, stats);
            if (active instanceof org.hkrpg.server.gameplay.LocalLegendBattle legend)
                return legend.finish(result, stats);
            return settleBattle(player, result, stats);
        }
    }

    private static boolean validStats(Battle battle, BattleStatistics stats) {
        if (stats.getRoundCnt() < 0 || !org.hkrpg.server.gameplay.MissionBattleCustomValues.validStatistics(stats)) return false;
        Set<Integer> seen = new HashSet<>();
        for (var row : stats.getBattleAvatarList()) {
            if (!battle.getLineup().getAvatars().contains(row.getId()) || !seen.add(row.getId())) return false;
            var value = row.getAvatarStatus();
            if (!Double.isFinite(value.getMaxHp()) || value.getMaxHp() <= 0
                    || !Double.isFinite(value.getLeftHp()) || value.getLeftHp() < 0 || value.getLeftHp() > value.getMaxHp()
                    || !Double.isFinite(value.getMaxSp()) || value.getMaxSp() < 0
                    || !Double.isFinite(value.getLeftSp()) || value.getLeftSp() < 0 || value.getLeftSp() > value.getMaxSp()) return false;
        }
        return true;
    }

    protected void grantDrops(Battle battle) { getServer().getDropService().calculateDrops(battle); }

    private Battle settleBattle(Player player, BattleEndStatus result, BattleStatistics stats) {
        // Sanity check to make sure player is in a battle
        if (!player.isInBattle()) {
            return null;
        }

        // Get battle object
        Battle battle = player.getBattle();

        // Set results
        var reportedResult = result;
        result = player.getUniverseManager().resolveBattleOutcome(battle, result);
        boolean rescued = reportedResult == BattleEndStatus.BATTLE_END_LOSE
                && result == BattleEndStatus.BATTLE_END_WIN;
        battle.setResult(result);
        battle.setStats(stats);
        player.getDailyManager().onWeaknessEntryOutcome(battle);
        player.getUniverseManager().onBattleKillOutcome(battle);

        // For handling battle result
        int minimumHp = rescued ? 10000 : 0;
        boolean updateStatus = true;
        boolean teleportToAnchor = false;

        // Handle result
        switch (result) {
            case BATTLE_END_WIN -> {
                // Classify before other victory callbacks can accept subsequent objectives.
                player.getQuestManager().onCocoonCategoryVictory(battle);
                player.getDailyManager().onCocoonCategoryVictory(battle);
                player.getMissionManager().onBattleVictory(battle);
                // Remove monsters from the map - Could optimize it a little better
                for (var monster : battle.getNpcMonsters()) {
                    // Dont remove farmable monsters from the scene when they are defeated
                    if (monster.isFarmElement()) continue;
                    // Remove monster
                    if (player.getScene() != null) player.getScene().defeatMonster(monster);
                }
                // Drops
                // Spend stamina
                if (battle.getStaminaCost() > 0) {
                    player.spendStamina(battle.getStaminaCost());
                }
                grantDrops(battle);
                player.getDailyManager().onFarmTypeCocoonVictory(battle);
                player.getQuestManager().onStageWin(battle.getStage().getId());
                player.getDailyManager().onStageWin(battle);
            }
            case BATTLE_END_LOSE -> {
                // Set avatar hp to 20% if the player's party is downed
                minimumHp = 2000;
                teleportToAnchor = true;
                player.getMissionManager().onBattleOutcome(battle.getStage().getId(), result);
            }
            case BATTLE_END_QUIT -> {
                updateStatus = false;
                player.getMissionManager().onBattleOutcome(battle.getStage().getId(), result);
                // Only teleport back to anchor if stage is a random fight
                if (battle.getStage().getStageType() == StageType.Mainline) {
                    teleportToAnchor = true;
                }
            }
            default -> {
                updateStatus = false;
            }
        }

        // Check if avatar hp/sp should be updated after a battle
        if (updateStatus) {
            // Set health/energy for player avatars
            for (var battleAvatar : stats.getBattleAvatarList()) {
                GameAvatar avatar = player.getAvatarById(battleAvatar.getId());
                if (avatar == null) continue;

                AvatarProperty prop = battleAvatar.getAvatarStatus();
                int currentHp = (int) Math.round((prop.getLeftHp() / prop.getMaxHp()) * 10000);
                int currentSp = prop.getMaxSp() == 0 ? 0
                        : (int) Math.round((prop.getLeftSp() / prop.getMaxSp()) * avatar.getMaxSp());

                avatar.setCurrentHp(battle.getLineup(), Math.max(currentHp, minimumHp));
                avatar.setCurrentSp(battle.getLineup(), Math.max(currentSp, 0));
                avatar.save();
            }

            // Sync with player
            player.sendPacket(new PacketSyncLineupNotify(battle.getLineup()));

            // Clear food buffs for player
            player.removeFoodBuffs(1);
        }

        // Teleport to anchor if player has lost/retreated. On official servers, the player party is teleported to the nearest anchor.
        if (teleportToAnchor && player.getScene() != null) {
            var anchorProp = player.getScene().getNearestSpring();
            if (anchorProp != null && anchorProp.getPropInfo() != null) {
                var anchor = player.getScene().getFloorInfo().getAnchorInfo(
                        anchorProp.getPropInfo().getAnchorGroupID(),
                        anchorProp.getPropInfo().getAnchorID()
                );
                if (anchor != null) {
                    player.moveTo(anchor.getPos());
                }
            }
        }

        // Player game instance callback
        if (player.getInstance() != null) {
            player.getInstance().onBattleFinish(battle, result, stats);
        }
        player.getMissionManager().onStoryChallengeSettlement(battle);
        player.getMissionManager().onChallengeVictory(battle);
        player.getDailyManager().onChallengeVictory(battle);

        // Battle callback
        player.getUniverseManager().onBattleFinish(battle);
        if (battle.getOnFinish() != null) {
            battle.getOnFinish().accept(battle);
        }

        // Done - Clear battle object from player
        player.setBattle(null);
        org.hkrpg.server.gameplay.ActivityKillQuests.onSettled(player, battle);
        return battle;
    }

    public void reEnterBattle(Player player, int stageId) {
        synchronized (player) { reEnterBattleLocked(player,stageId); }
    }
    private void reEnterBattleLocked(Player player,int stageId) {
        // Sanity check to make sure player isnt in a battle
        if (player.isInBattle() || activityBlocksBattle(player)
                || org.hkrpg.server.gameplay.StoryChallengeScope.isNativeStory(player)) {
            player.sendPacket(new PacketReEnterLastElementStageScRsp());
            return;
        }

        // Get stage
        StageExcel stage = GameData.getStageExcelMap().get(stageId);
        if (stage == null || stage.getStageType() != StageType.FarmElement) {
            player.sendPacket(new PacketReEnterLastElementStageScRsp());
            return;
        }

        // Create new battle for player
        Battle battle = new Battle(player, player.getCurrentLineup(), stage);
        battle.setStaminaCost(GameConstants.FARM_ELEMENT_STAMINA_COST);
        player.setBattle(battle);

        // Get mapping info id
        int mappingInfoId = ((stageId / 10) % 100) + 1100;
        int mappingInfoLevel = stageId % 10;
        var mappingInfoExcel = GameData.getMappingInfoExcelMap().get(mappingInfoId, mappingInfoLevel);
        if (mappingInfoExcel != null && mappingInfoExcel.getFarmType() != null && mappingInfoExcel.getFarmType().equals("ELEMENT")) {
            battle.setMappingInfoId(mappingInfoId);
            battle.setWorldLevel(mappingInfoLevel);
        }

        // Send packet
        player.sendPacket(new PacketReEnterLastElementStageScRsp(battle));
    }
}
