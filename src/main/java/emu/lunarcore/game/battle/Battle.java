package emu.lunarcore.game.battle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import emu.lunarcore.GameConstants;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleEventBattleInfoOuterClass.BattleEventBattleInfo;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.proto.BattleTargetListOuterClass.BattleTargetList;
import emu.lunarcore.proto.BattleTargetOuterClass.BattleTarget;
import emu.lunarcore.proto.SceneBattleInfoOuterClass.SceneBattleInfo;
import emu.lunarcore.proto.SceneBattleInfoOuterClass.SceneBattleInfo.BattleTargetInfoEntry;
import emu.lunarcore.util.Utils;
import it.unimi.dsi.fastutil.ints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
public class Battle {
    private final int id;
    private final Player player;
    private final PlayerLineup lineup;
    private final List<EntityMonster> npcMonsters;
    @Getter(lombok.AccessLevel.NONE)
    private final java.util.Map<EntityMonster, IntList> npcWaveNumbers = new java.util.IdentityHashMap<>();
    private final List<BattleMonsterWave> waves;
    private final List<GameItem> drops;
    private final Int2ObjectMap<MazeBuff> buffs;
    @Getter(lombok.AccessLevel.NONE)
    private final List<MazeBuff> avatarBuffs = new ArrayList<>();
    @Getter(lombok.AccessLevel.NONE)
    private volatile boolean avatarBuffsPrepared;
    @Getter(lombok.AccessLevel.NONE)
    private GameAvatar castoriceRadiusOwner;
    private final long timestamp;
    private final int logicRandomSeed;
    private boolean settling;
    private final org.hkrpg.server.gameplay.RaidDifficultySettings.BattleScope raidDifficultyScope;

    public boolean beginSettlement() {
        if (settling) return false;
        settling = true;
        return true;
    }

    private BattleStage stage; // Main battle stage
    private IntList battleEvents; // TODO maybe turn it into a map?
    private Int2ObjectMap<BattleTargetList> battleTargets; // TODO use custom battle target object as value type in case we need to save battles to the db

    // Internal battle data
    @Setter private int staminaCost;
    @Setter private int roundsLimit;

    // Used for calculating cocoon/farm element drops
    @Setter private int mappingInfoId;
    @Setter private int worldLevel;
    @Setter private int cocoonWave;
    @Setter private int cocoonId;

    // Results
    @Setter private BattleEndStatus result;
    @Setter private BattleStatistics stats;
    @Setter private org.hkrpg.server.gameplay.MissionBattleKills.Context missionBattleKills;
    @Setter private org.hkrpg.server.gameplay.ActivityKillQuests.Context activityKillQuests;
    @Setter private org.hkrpg.server.gameplay.MissionBattleCustomValues.Context missionBattleCustomValues;
    @Setter private org.hkrpg.server.gameplay.MissionChallengeWin.Context missionChallengeWin;
    private org.hkrpg.server.gameplay.StoryChallengeScope.Binding storyChallengeBinding;
    private java.util.Map<Integer, org.hkrpg.server.gameplay.StoryChallengeSettlementGoals.Target> storySettlementTargets = java.util.Map.of();
    @Setter private org.hkrpg.server.gameplay.DailyWeaknessEntries.Context dailyWeaknessEntry;
    private org.hkrpg.server.gameplay.MissionStageWins.Context missionStageWins;
    @Setter private List<org.hkrpg.server.gameplay.MissionBattleKills.Monster> reportedMonsters = List.of();
    @Setter private org.hkrpg.server.gameplay.RogueResonance.BattleBinding rogueResonance;

    // Callbacks
    @Setter private Consumer<SceneBattleInfo> onSerialize;
    @Setter private Consumer<Battle> onFinish;

    private Battle(Player player, PlayerLineup lineup) {
        this.id = player.getNextBattleId();
        this.player = player;
        this.raidDifficultyScope = org.hkrpg.server.gameplay.RaidDifficultySettings.battleScope(player);
        this.lineup = lineup;
        this.npcMonsters = new ArrayList<>();
        this.buffs = new Int2ObjectLinkedOpenHashMap<>();
        this.waves = new ArrayList<>();
        this.drops = new ArrayList<>();
        this.timestamp = System.currentTimeMillis();
        this.logicRandomSeed = Utils.randomRange(1, Short.MAX_VALUE);
        player.getUniverseManager().onBattleStart(this);
    }

    public Battle(Player player, PlayerLineup lineup, BattleStage stage) {
        this(player, lineup, stage, true);
    }

    public Battle(Player player, PlayerLineup lineup, BattleStage stage, boolean loadStage) {
        this(player, lineup);
        this.stage = stage;

        if (loadStage) {
            this.loadStage(stage);
        }
        this.missionStageWins = org.hkrpg.server.gameplay.MissionStageWins.capture(player, this);
        this.storyChallengeBinding = org.hkrpg.server.gameplay.StoryChallengeScope.capture(player, this);
        if (this.storyChallengeBinding.required() && this.storyChallengeBinding.context() != null)
            this.storySettlementTargets = org.hkrpg.server.gameplay.StoryChallengeSettlementGoals.capture(player);
    }

    public Battle(Player player, PlayerLineup lineup, List<? extends BattleStage> stages) {
        this(player, lineup);
        this.stage = stages.get(0);

        for (var stage : stages) {
            this.loadStage(stage);
        }
        this.missionStageWins = org.hkrpg.server.gameplay.MissionStageWins.capture(player, this);
        this.storyChallengeBinding = org.hkrpg.server.gameplay.StoryChallengeScope.capture(player, this);
        if (this.storyChallengeBinding.required() && this.storyChallengeBinding.context() != null)
            this.storySettlementTargets = org.hkrpg.server.gameplay.StoryChallengeSettlementGoals.capture(player);
    }

    public Battle(Player player, PlayerLineup lineup, Collection<EntityMonster> npcMonsters) {
        this(player, lineup);

        // Parse npc monster
        for (EntityMonster npcMonster : npcMonsters) {
            // Add monster
            this.npcMonsters.add(npcMonster);

            // Check farm element
            if (npcMonster.getFarmElementId() != 0) {
                this.setMappingInfoId(npcMonster.getFarmElementId());
                this.setWorldLevel(npcMonster.getWorldLevel());
                this.setStaminaCost(GameConstants.FARM_ELEMENT_STAMINA_COST);
            }

            // Get stage
            BattleStage stage = npcMonster.getCustomStage();

            if (stage == null) {
                // Get stage id from event id
                var eventId = npcMonster.getEventId();
                var configEvent = GameData.getPlaneEventExcelMap().get(eventId, npcMonster.getWorldLevel());

                if (configEvent != null) {
                    var eventStageId = configEvent.getStageId();
                    stage = GameData.getStageExcelMap().get(eventStageId);
                }

                // Fallback - Old method of getting stage id from event id
                if (stage == null) {
                    stage = GameData.getStageExcelMap().get(npcMonster.getStageId());
                }

                // Could'nt find any stage associated with this monster
                if (stage == null) continue;
            }

            // Set main battle stage if we havent already
            if (this.stage == null) {
                this.stage = stage;
            }

            // Create monster waves from stage
            this.loadStage(stage, npcMonster);
        }
        // The base constructor runs before NPCs are loaded. Bind actual combatants only now.
        player.getUniverseManager().onBattlePrepared(this);
        this.missionStageWins = org.hkrpg.server.gameplay.MissionStageWins.capture(player, this);
        this.storyChallengeBinding = org.hkrpg.server.gameplay.StoryChallengeScope.capture(player, this);
        if (this.storyChallengeBinding.required() && this.storyChallengeBinding.context() != null)
            this.storySettlementTargets = org.hkrpg.server.gameplay.StoryChallengeSettlementGoals.capture(player);
    }

    private void loadStage(BattleStage stage) {
        this.loadStage(stage, null);
    }

    private void loadStage(BattleStage stage, EntityMonster npcMonster) {
        // Build monster waves
        for (IntList stageMonsterWave : stage.getMonsterWaves()) {
            // Create battle wave
            var wave = new BattleMonsterWave(stage);
            wave.getMonsters().addAll(stageMonsterWave);

            // Add wave to battle
            this.getWaves().add(wave);

            // Add buffs from npc monsters
            if (npcMonster != null) {
                this.npcWaveNumbers.computeIfAbsent(npcMonster, ignored -> new IntArrayList())
                        .add(this.getWaves().size());
                // Set wave custom level
                wave.setCustomLevel(npcMonster.getCustomLevel());

                // Handle monster buffs
                npcMonster.applyBuffs(this, this.getWaves().size());
            }
        }
    }

    public IntList getBattleEvents() {
        if (this.battleEvents == null) {
            this.battleEvents = new IntArrayList();
        }
        return this.battleEvents;
    }

    public Int2ObjectMap<BattleTargetList> getBattleTargets() {
        if (this.battleTargets == null) {
            this.battleTargets = new Int2ObjectOpenHashMap<>();
        }
        return this.battleTargets;
    }

    public void addBattleTarget(int key, int targetId, int progress) {
        var list = getBattleTargets().computeIfAbsent(key, x -> BattleTargetList.newInstance());
        var battleTarget = BattleTarget.newInstance()
                .setId(targetId)
                .setProgress(progress);

        list.addBattleTargetList(battleTarget);
    }

    public void setCustomLevel(int level) {
        for (var wave : this.getWaves()) {
            wave.setCustomLevel(level);
        }
    }

    // Battle buffs

    public MazeBuff addBuff(int buffId) {
        return addBuff(buffId, -1, 0xffffffff);
    }

    public MazeBuff addBuff(int buffId, int ownerIndex) {
        return addBuff(buffId, ownerIndex, 0xffffffff);
    }

    public MazeBuff addBuff(int buffId, int ownerIndex, int waveFlag) {
        MazeBuff buff = new MazeBuff(buffId, 1, ownerIndex, waveFlag);
        return addBuff(buff);
    }

    public MazeBuff addBuff(MazeBuff buff) {
        this.buffs.put(buff.getId(), buff);
        return buff;
    }

    /** Bind the same caster's NPC buff to its actual waves without replacing its metadata. */
    public MazeBuff addNpcWaveBuff(int buffId, int ownerIndex, int waveNum) {
        if (waveNum < 1 || waveNum > Integer.SIZE) return null;
        int waveFlag = 1 << (waveNum - 1);
        var existing = this.buffs.get(buffId);
        if (existing != null && existing.getOwnerIndex() == ownerIndex && existing.getLevel() == 1) {
            existing.includeWaveFlag(waveFlag);
            return existing;
        }
        return addBuff(buffId, ownerIndex, waveFlag);
    }

    public boolean hasMazeSkillTarget(EntityMonster monster) {
        return this.npcWaveNumbers.containsKey(monster);
    }

    /** OnBattle runs after construction, so bind directly to the NPC's constructed waves. */
    public void addMazeSkillNpcBuff(EntityMonster monster, int buffId, int ownerIndex) {
        var waveNumbers = this.npcWaveNumbers.get(monster);
        if (waveNumbers == null) return;
        for (int waveNumber : waveNumbers) addNpcWaveBuff(buffId, ownerIndex, waveNumber);
    }

    public boolean hasBuff(int buffId) {
        return this.buffs.containsKey(buffId)
                || this.avatarBuffs.stream().anyMatch(buff -> buff.getId() == buffId);
    }

    public void clearBuffs() {
        this.buffs.clear();
        this.avatarBuffs.clear();
    }

    private record AvatarBuffSnapshot(GameAvatar avatar, int id, long expiry,
                                      MazeBuff binding, boolean consumable) {}

    /** The proven1407 Input removes only its own radius after an accepted Attack. */
    public boolean excludeCastoriceRadius(GameAvatar caster, org.hkrpg.server.gameplay.MazeCastState.Cast input) {
        if (this.avatarBuffsPrepared || caster == null || input == null || input.released()
                || this.stage == null || this.stage.getId() <= 0 || this.player.getBattle() != null
                || caster.getOwner() != this.player || caster.getActorId() != 1407
                || this.player.getAvatarById(caster.getAvatarId()) != caster
                || !this.lineup.getAvatars().contains(caster.getAvatarId())) return false;
        var original = org.hkrpg.server.gameplay.MazeSkillCosts.castoriceFreeDirectInput(
                caster, "LocalPlayer_Castorice_MazeSkill_Input");
        var current = this.player.getGameplay().getMazeCasts();
        if (original.isEmpty() || !current.isCurrentCast(this.player.getSession(), input)
                || !input.matches(caster, original.get().skill(), 1)
                || !current.hasReleasedBuffOrigin(this.player.getSession(), caster, original.get().origin(), 1, 140704))
            return false;
        this.castoriceRadiusOwner = caster;
        return true;
    }

    private List<AvatarBuffSnapshot> snapshotAvatarBuffs() {
        synchronized (this.player) {
            var snapshots = new ArrayList<AvatarBuffSnapshot>();
            for (int slot = 0; slot < this.lineup.getAvatars().size(); slot++) {
                var avatar = this.player.getAvatarById(this.lineup.getAvatars().get(slot));
                if (avatar == null) continue;
                synchronized (avatar.getBuffs()) {
                    for (var entry : avatar.getBuffs().int2LongEntrySet()) {
                        if (entry.getLongValue() < this.timestamp) continue;
                        if (avatar == this.castoriceRadiusOwner && entry.getIntKey() == 140704) continue;
                        var binding = new MazeBuff(entry.getIntKey(), 1, slot, -1);
                        binding.setOwner(avatar);
                        binding.addTargetIndex(slot);
                        var excel = GameData.getMazeBuffExcelMap().get(entry.getIntKey(), 1);
                        // Explicit one-use character-skill declarations only; unknown lifecycles
                        // retain their scene state until their own behavior is implemented.
                        boolean consumable = excel != null
                                && "AddBattleBuff".equals(excel.getUseType())
                                && ("CharacterSkill".equals(excel.getInBattleBindingType())
                                    || "CharacterSkillAdv".equals(excel.getInBattleBindingType()))
                                && excel.getInBattleBindingKey() != null
                                && !excel.getInBattleBindingKey().isBlank();
                        snapshots.add(new AvatarBuffSnapshot(avatar, entry.getIntKey(),
                                entry.getLongValue(), binding, consumable));
                    }
                }
            }
            return snapshots;
        }
    }

    /** Freeze scene effects once at actual entry, before consuming their one-use scene state. */
    public void prepareAvatarBuffs() {
        synchronized (this.player) {
            if (this.avatarBuffsPrepared || this.player.getBattle() != this
                    || this.stage == null || this.stage.getId() <= 0) return;
            var snapshots = snapshotAvatarBuffs();
            for (var snapshot : snapshots) {
                this.avatarBuffs.add(snapshot.binding());
                // Preserve the legacy ID lookup without overwriting explicit battle bindings.
                this.buffs.putIfAbsent(snapshot.id(), snapshot.binding());
            }
            this.avatarBuffsPrepared = true;
            for (var snapshot : snapshots) {
                if (!snapshot.consumable()) continue;
                var avatar = snapshot.avatar();
                synchronized (avatar.getBuffs()) {
                    if (avatar.getBuffs().containsKey(snapshot.id())
                            && avatar.getBuffs().get(snapshot.id()) == snapshot.expiry())
                        avatar.removeBuff(snapshot.id());
                }
            }
        }
    }

    // Serialization

    public SceneBattleInfo toProto() {
        // Build battle info
        var proto = SceneBattleInfo.newInstance()
                .setBattleId(this.getId())
                .setStageId(this.getStage().getId())
                .setRoundsLimit(this.getRoundsLimit())
                .setLogicRandomSeed(this.logicRandomSeed)
                .setWorldLevel(raidDifficultyScope == null ? player.getWorldLevel() : raidDifficultyScope.world());

        // Add monster waves
        for (int i = 0; i < this.getWaves().size(); i++) {
            proto.addMonsterWaveList(this.getWaves().get(i).toProto().setWaveId(i + 1));
        }

        // Avatars
        for (int i = 0; i < lineup.getAvatars().size(); i++) {
            GameAvatar avatar = getPlayer().getAvatarById(lineup.getAvatars().get(i));
            if (avatar == null) continue;

            // Add to proto
            proto.addBattleAvatarList(avatar.toBattleProto(lineup, i));

        }

        this.prepareAvatarBuffs();
        var sceneBuffs = this.avatarBuffsPrepared ? this.avatarBuffs
                : snapshotAvatarBuffs().stream().map(AvatarBuffSnapshot::binding).toList();

        // Apply food buffs to battle
        if (player.getFoodBuffs().size() > 0) {
            for (var buff : player.getFoodBuffs().values()) {
                this.addBuff(buff.getBuffId(), -1);
            }
        }

        // Buffs
        for (var buff : this.getBuffs().values()) {
            if (this.avatarBuffsPrepared && this.avatarBuffs.contains(buff)) continue;
            proto.addBuffList(buff.toProto());
        }
        for (var buff : sceneBuffs) proto.addBuffList(buff.toProto());

        // Add global buffs
        for (int avatarId : this.getPlayer().getAvatars().getGlobalBuffAvatars()) {
            var globalBuffExcel = GameData.getAvatarGlobalBuffExcelMap().get(avatarId);
            if (globalBuffExcel == null) continue;

            var globalBuff = new MazeBuff(globalBuffExcel.getMazeBuffID(), 1, -1, 0xffffffff);
            proto.addBuffList(globalBuff.toProto());
        }

        // Client turn snapshots
        if (this.battleEvents != null) {
            for (int id : this.battleEvents) {
                var event = BattleEventBattleInfo.newInstance()
                        .setBattleEventId(id);

                event.getMutableStatus().getMutableSpBar()
                        .setCurSp(this.rogueResonance != null && this.rogueResonance.eventId() == id
                                ? this.rogueResonance.initialSp() : 10000)
                        .setMaxSp(10000);

                proto.addEventBattleInfoList(event);
            }
        }

        // Battle target map
        if (this.battleTargets != null) {
            // Build battle target map
            for (int i = 1; i <= 5; i++) {
                var battleTargetList = this.battleTargets.get(i);
                var battleTargetEntry = BattleTargetInfoEntry.newInstance().setKey(i);

                if (battleTargetList == null) {
                    battleTargetEntry.getMutableValue();
                } else {
                    battleTargetEntry.setValue(battleTargetList);
                }

                proto.addBattleTargetInfo(battleTargetEntry);
            }
        }

        // Callback
        if (this.onSerialize != null) {
            this.onSerialize.accept(proto);
        }

        // Complete
        return proto;
    }
}
