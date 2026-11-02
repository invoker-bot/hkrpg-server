package emu.lunarcore.game.scene;

import java.util.ArrayList;
import java.util.List;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.data.excel.SummonUnitExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.scene.entity.*;
import emu.lunarcore.game.scene.triggers.PropTrigger;
import emu.lunarcore.game.scene.triggers.PropTriggerType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import emu.lunarcore.proto.SceneEntityGroupInfoOuterClass.SceneEntityGroupInfo;
import emu.lunarcore.proto.SceneEntityGroupInfoOuterClass.SceneEntityGroupInfo.GroupPropertyMapEntry;
import emu.lunarcore.proto.SceneGroupStateOuterClass.SceneGroupState;
import emu.lunarcore.proto.SceneInfoOuterClass.SceneInfo;
import emu.lunarcore.proto.MapPropDefInfoOuterClass.MapPropDefInfo;
import emu.lunarcore.server.game.Tickable;
import emu.lunarcore.server.packet.send.PacketActivateFarmElementScRsp;
import emu.lunarcore.server.packet.send.PacketRefreshTriggerByClientScNotify;
import emu.lunarcore.server.packet.send.PacketSceneGroupRefreshScNotify;
import emu.lunarcore.util.Position;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import lombok.Getter;
import lombok.Setter;
import us.hebi.quickbuf.RepeatedInt;

@Getter
public class Scene implements Tickable {
    private static final java.util.concurrent.atomic.AtomicLong RUNTIME_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong(new java.security.SecureRandom().nextLong(1, Long.MAX_VALUE));
    private final long runtimeId = RUNTIME_SEQUENCE.updateAndGet(value -> value == Long.MAX_VALUE ? 1 : value + 1);
    private final Player player;
    private final MazePlaneExcel excel;
    private final FloorInfo floorInfo;
    private final SceneEntityLoader entityLoader;
    private final int planeId;
    private final int floorId;
    private final int dimensionId;
    private final int storyLineId;
    private int entryId;
    private int worldId;
    private int clientPosVersion;
    private int acknowledgedClientPosVersion;
    private int teleportMapLayer;
    @Setter private int leaveEntryId;

    private int lastEntityId = 0;
    private boolean loaded = false;

    // Avatar entites
    private final IntSet avatarEntityIds;
    private final Int2ObjectMap<GameAvatar> avatars;
    private EntitySummonUnit playerSummon;

    // Other entities
    private final Int2ObjectMap<GameEntity> entities;
    private final Int2ObjectMap<SceneGroup> groups;
    private final IntSet loadedGroups = new IntOpenHashSet();

    // Marked chests
    private Int2ObjectMap<List<MapPropDefInfo>> markedChests;

    // Cache
    private List<PropTrigger> triggers;
    private List<EntityProp> healingSprings;
    private List<Integer> sceneSubMissions;
    private List<Integer> sceneMainMissions;

    public Scene(Player player, MazePlaneExcel excel, int floorId) {
        this(player, excel, floorId, 0);
    }

    public Scene(Player player, MazePlaneExcel excel, int floorId, int dimensionId) {
        this.player = player;
        this.excel = excel;
        this.planeId = excel.getPlaneID();
        this.floorId = floorId;
        this.dimensionId = dimensionId;
        this.storyLineId = player.getGameplay().getStoryLines().getCurrentId();
        this.floorInfo = GameData.getFloorInfo(this.planeId, this.floorId);
        if (dimensionId < 0 || (dimensionId != 0 && floorInfo == null)
            || (floorInfo != null && floorInfo.getDimensionList() != null
                && !floorInfo.getDimensionList().isEmpty()
                && floorInfo.getDimensionList().stream().noneMatch(d -> d.getID() == dimensionId))
            || (dimensionId != 0 && (floorInfo.getDimensionList() == null || floorInfo.getDimensionList().isEmpty())))
            throw new IllegalArgumentException("Unknown scene dimension: " + dimensionId);
        if (dimensionId != 0 && excel.getPlaneType() != PlaneType.Town
            && excel.getPlaneType() != PlaneType.Maze && excel.getPlaneType() != PlaneType.Train)
            throw new IllegalArgumentException("Unsupported instance dimension: " + dimensionId);
        if (floorInfo != null) floorInfo.getReferencedGroupIndices();

        // Setup avatars
        this.avatarEntityIds = new IntOpenHashSet();
        this.avatars = new Int2ObjectOpenHashMap<>();
        this.entities = new Int2ObjectOpenHashMap<>();
        this.groups = new Int2ObjectOpenHashMap<>();

        this.healingSprings = new ObjectArrayList<>();
        this.triggers = new ObjectArrayList<>();

        this.markedChests = new Int2ObjectOpenHashMap<>();

        this.sceneSubMissions = new ObjectArrayList<>();
        this.sceneMainMissions = new ObjectArrayList<>();

        // Set world id
        if (this.getExcel().getPlaneType() == PlaneType.Train) {
            this.worldId = player.getWorldId();
        } else {
            this.worldId = this.getExcel().getWorldID();
        }

        // Use singleton to avoid allocating memory for a new entity loader everytime we create a scene
        this.entityLoader = getExcel().getPlaneType().getSceneEntityLoader();

        // Add avatar entities
        PlayerLineup lineup = getPlayer().getCurrentLineup();

        for (int avatarId : lineup.getAvatars()) {
            GameAvatar avatar = getPlayer().getAvatarById(avatarId);
            if (avatar == null) continue;

            this.avatars.put(avatarId, avatar);

            // Add entity id
            avatar.setEntityId(this.getNextEntityId());
            this.avatarEntityIds.add(avatar.getEntityId());
        }

        // Set floor info
        if (floorInfo == null) return;

        // Spawn entities from groups
        this.getPlaneType().getSceneEntityLoader().onSceneLoad(this);
    }

    public PlaneType getPlaneType() {
        return this.getExcel().getPlaneType();
    }

    /** Local revision policy: one increment per accepted native teleport, never wrap silently. */
    public void advanceClientPosVersion() {
        if (clientPosVersion == -1) throw new IllegalStateException("Scene position revision exhausted");
        clientPosVersion++;
    }

    public void acknowledgeClientPosVersion(int version) {
        if (version == clientPosVersion) acknowledgedClientPosVersion = version;
    }

    public int getTeleportMapLayer() {
        if (teleportMapLayer > 0) return teleportMapLayer;
        var saved = getPlayer().getSceneTeleportLocation();
        int restored = saved == null ? 1 : saved.layerFor(this);
        if (entryId > 0 && entryId == getPlayer().getEntryId()
                && planeId == getPlayer().getPlaneId() && floorId == getPlayer().getFloorId())
            teleportMapLayer = restored;
        return restored;
    }

    public void setTeleportMapLayer(int layer) { teleportMapLayer = layer; }

    /** Resolve an actual current scene actor, including independent trial parties. */
    public GameAvatar getAvatarByEntityId(int entityId) {
        if (entityId <= 0 || !this.avatarEntityIds.contains(entityId)) return null;
        GameAvatar result = null;
        for (var avatar : this.avatars.values()) {
            if (avatar.getEntityId() != entityId) continue;
            if (result != null) return null;
            result = avatar;
        }
        var lineup = getPlayer().getCurrentLineup();
        return result != null && lineup != null
                && lineup.getAvatars().contains(result.getAvatarId())
                && getPlayer().getAvatarById(result.getAvatarId()) == result ? result : null;
    }

    public int getWorldId() {
        return getPlaneType() == PlaneType.Train
                ? org.hkrpg.server.gameplay.TrainWorlds.current(getPlayer(), this.worldId)
                : this.worldId;
    }

    public void loadGroup(int groupId) {
        GroupInfo group = getResourceGroups().get(groupId);
        if (group != null) {
            this.loadGroup(group);
        }
    }

    public Int2ObjectMap<GroupInfo> getResourceGroups() {
        return getFloorInfo().getGroups(getDimensionId());
    }

    public void loadGroup(GroupInfo group) {
        if (group == null || !org.hkrpg.server.gameplay.Mascots.shouldLoad(this, group)
            || !org.hkrpg.server.gameplay.DebugGroupConditions.allowed(group, this)
            || !org.hkrpg.server.gameplay.MusicSceneGroups.allowed(group, this)
            || !org.hkrpg.server.gameplay.CompatibleMechanisms.shouldLoad(this, group)) return;
        if (org.hkrpg.server.gameplay.MissionGroupConditions.tracks(group, this)
            && (SceneEntityLoader.isExcludedGroup(this, group)
                || !getPlayer().getMissionManager().shouldLoadGroup(group, this))) return;
        // Resources from other story dimensions are retained, but do not belong to the main world.
        // Instance loaders for Rogue/Challenge/Trial already select their own authored groups.
        if (getPlaneType() != PlaneType.Rogue && getPlaneType() != PlaneType.Challenge
            && getPlaneType() != PlaneType.TrialActivity
            && !getFloorInfo().isGroupInDimension(group.getId(), getDimensionId())) return;
        if (!loadedGroups.add(group.getId())) return;
        // Add monsters
        if (group.getMonsterList() != null && group.getMonsterList().size() > 0) {
            for (MonsterInfo monsterInfo : group.getMonsterList()) {
                try {
                    EntityMonster monster = this.getEntityLoader().loadMonster(this, group, monsterInfo);
                    this.addEntity(monster, this.isLoaded());
                } catch (Exception e) {
                    // Ignored
                }
            }
        }

        // Add props
        if (group.getPropList() != null && group.getPropList().size() > 0) {
            for (PropInfo propInfo : group.getPropList()) {
                try {
                    EntityProp prop = this.getEntityLoader().loadProp(this, group, propInfo);
                    this.addEntity(prop, this.isLoaded());
                } catch (Exception e) {
                    // Ignored
                }
            }
        }

        // Add NPCs
        if (group.getNPCList() != null && group.getNPCList().size() > 0) {
            for (NpcInfo npcInfo : group.getNPCList()) {
                try {
                    EntityNpc npc = this.getEntityLoader().loadNpc(this, group, npcInfo);
                    this.addEntity(npc, this.isLoaded());
                } catch (Exception e) {
                    // Ignored
                }
            }
        }

        // A persisted state may belong to a group without any entities or properties.
        var savedState = player.getGameplay().getStoryValues().get("group-state:" + getFloorId() + ":" + group.getId());
        if (savedState != null)
            getGroups().computeIfAbsent(group.getId(), SceneGroup::new).setState(savedState);
        org.hkrpg.server.gameplay.Mascots.restore(this, group);

        // Add group properties
        if (group.getGroupPropertyMap() != null && group.getGroupPropertyMap().size() > 0) {
            var sceneGroup = this.getGroups().computeIfAbsent(group.getId(), i -> new SceneGroup(i));

            for (var property : group.getGroupPropertyMap().values()) {
                sceneGroup.getProperties().put(property.getName(), player.getGameplay().getStoryValues().getOrDefault(
                    "group:" + getFloorId() + ":" + group.getId() + ":" + property.getName(), property.getDefaultValue()));
            }
        }
        org.hkrpg.server.gameplay.WorldGroupProperties.restore(this, group.getId());
    }

    public void setEntryId(int entryId) {
        this.entryId = entryId;
    }

    public boolean hasGroupProperty(int groupId, String name) {
        if (getFloorInfo() == null || !getLoadedGroups().contains(groupId)) return false;
        var group = getResourceGroups().get(groupId);
        return group != null && group.getGroupPropertyMap() != null
            && group.getGroupPropertyMap().values().stream().anyMatch(p -> p.getName().equals(name));
    }

    public boolean setStoryGroupProperty(int groupId, String name, int value) {
        if (!hasGroupProperty(groupId, name)) return false;
        var group = getGroups().get(groupId);
        if (group == null) return false;
        int old = group.getProperties().getInt(name);
        player.getGameplay().getStoryValues().put("group:" + getFloorId() + ":" + groupId + ":" + name, value);
        group.getProperties().put(name, value);
        org.hkrpg.server.protocol.Story46.syncGroup(player, getFloorId(), groupId, name, old, value);
        return true;
    }

    public boolean hasStoryGroup(int groupId) {
        return getFloorInfo() != null && getLoadedGroups().contains(groupId)
            && getResourceGroups().containsKey(groupId);
    }

    public boolean setStoryGroupState(int groupId, int state) {
        if (state < 0 || !hasStoryGroup(groupId)) return false;
        var group = getGroups().computeIfAbsent(groupId, SceneGroup::new);
        int old = group.getState();
        player.getGameplay().getStoryValues().put("group-state:" + getFloorId() + ":" + groupId, state);
        group.setState(state);
        if (old != state) org.hkrpg.server.protocol.Story46.syncState(player, getFloorId(), groupId, state);
        return true;
    }

    private int getNextEntityId() {
        return ++lastEntityId;
    }

    public synchronized GameEntity getEntityById(int id) {
        return this.getEntities().get(id);
    }

    public synchronized <T extends GameEntity> List<T> getEntities(Class<T> entityType) {
        return this.getEntities().values()
                .stream()
                .filter(e -> entityType.isInstance(e))
                .map(entityType::cast)
                .toList();
    }

    public synchronized <T extends GameEntity> List<T> getEntitiesByGroup(Class<T> entityType, int groupId) {
        return this.getEntities().values()
                .stream()
                .filter(e -> entityType.isInstance(e) && e.getGroupId() == groupId)
                .map(entityType::cast)
                .toList();
    }

    public void refreshMissionGroups() {
        if (getFloorInfo() == null) return;
        var toLoad = new ArrayList<GroupInfo>();
        for (var group : getResourceGroups().values()) {
            boolean atmosphere = group.getCategory() == GroupInfo.GroupCategory.Atmosphere;
            boolean deferred = org.hkrpg.server.gameplay.DeferredMissionGroups.tracks(group, this);
            boolean ordinary = org.hkrpg.server.gameplay.MissionGroupConditions.tracks(group, this);
            boolean compatible = org.hkrpg.server.gameplay.CompatibleMechanisms.tracksGroup(group, this);
            boolean debug = org.hkrpg.server.gameplay.DebugGroupConditions.tracks(group, this);
            boolean music = org.hkrpg.server.gameplay.MusicSceneGroups.tracks(group, this);
            if ((!atmosphere && !deferred && !ordinary && !compatible && !debug && !music && group.getCategory()!=GroupInfo.GroupCategory.Mission) || group.getLoadSide()!=GroupInfo.GroupLoadSide.Server) continue;
            if (atmosphere && (getPlaneType() == PlaneType.Rogue || getPlaneType() == PlaneType.Challenge || getPlaneType() == PlaneType.TrialActivity)) continue;
            if (org.hkrpg.server.gameplay.MusicSceneGroups.allowed(group, this)
                    && getPlayer().getMissionManager().shouldLoadGroup(group, this)) toLoad.add(group);
            else if (loadedGroups.remove(group.getId())) {
                for (var entity : List.copyOf(getEntities().values())) if (entity.getGroupId()==group.getId()) {
                    if (entity instanceof EntityProp prop) getHealingSprings().remove(prop);
                    removeEntity(entity);
                }
                groups.remove(group.getId());
                // Entity deltas do not retire the client's group graph or its local blockers.
                player.sendPacket(new PacketSceneGroupRefreshScNotify(this, group.getId()));
            }
        }
        // Remove obsolete actors before spawning replacements with the same NPC id.
        for (var group : toLoad) loadGroup(group);
        org.hkrpg.server.gameplay.DailyRecoverPerformances.reconcile(this);
    }

    public void syncLineup() {
        // Get current lineup
        PlayerLineup lineup = getPlayer().getLineupManager().getCurrentLineup();

        // Setup new avatars list
        var newAvatars = new Int2ObjectOpenHashMap<GameAvatar>();
        for (int avatarId : lineup.getAvatars()) {
            GameAvatar avatar = getPlayer().getAvatarById(avatarId);
            if (avatar == null) continue;

            newAvatars.put(avatarId, avatar);
        }

        // Clear entity id cache
        this.avatarEntityIds.clear();

        // Add/Remove
        List<GameAvatar> toAdd = new ArrayList<>();
        List<GameAvatar> toRemove = new ArrayList<>();

        for (var avatar : newAvatars.values()) {
            if (this.avatars.get(avatar.getAvatarId()) != avatar) {
                toAdd.add(avatar);
                avatar.setEntityId(getNextEntityId());
                // Effects belong to this scene instance, not a detached stored avatar.
                avatar.getBuffs().clear();
            }

            // Add to entity id cache
            this.avatarEntityIds.add(avatar.getEntityId());

        }

        for (var avatar : this.avatars.values()) {
            if (newAvatars.get(avatar.getAvatarId()) != avatar) {
                toRemove.add(avatar);
            }
        }

        // Capture removed IDs before clearing them; refresh and future scene snapshots must use
        // the same actor set as the current lineup.
        var notification = new PacketSceneGroupRefreshScNotify(this, toAdd, toRemove);
        for (var avatar : toRemove) {
            avatar.getBuffs().clear();
            avatar.setEntityId(0);
        }
        this.avatars.clear();
        this.avatars.putAll(newAvatars);
        getPlayer().sendPacket(notification);
    }

    public boolean activateFarmElement(int entityId, int worldLevel) {
        GameEntity entity = this.getEntityById(entityId);
        if (entity == null) {
            player.sendPacket(new PacketActivateFarmElementScRsp());
            return false;
        }

        if (entity instanceof EntityMonster monster) {
            monster.setWorldLevel(worldLevel);
        }

        player.sendPacket(new PacketActivateFarmElementScRsp(entityId, worldLevel));
        return true;
    }

    // Summons

    public synchronized void summonUnit(GameAvatar caster, SummonUnitExcel excel, Position pos, Position rot, int duration) {
        this.summonUnit(caster, excel, pos, rot, (double) duration);
    }

    public synchronized void summonUnit(GameAvatar caster, SummonUnitExcel excel, Position pos, Position rot, double duration) {
        // Validate the replacement before removing the current unit.
        var summon = new EntitySummonUnit(this, caster, excel, pos, rot);
        summon.setDuration(duration);

        this.removeSummonUnit();
        this.playerSummon = summon;
        this.addEntity(summon, true);
    }

    public synchronized void removeSummonUnit() {
        if (this.getPlayerSummon() != null) {
            this.removeEntity(this.getPlayerSummon());
        }
    }

    public synchronized void removeSummonUnit(GameAvatar caster, int summonUnitId) {
        if (this.playerSummon != null && this.playerSummon.getCaster() == caster
                && this.playerSummon.getExcel().getId() == summonUnitId) {
            this.removeSummonUnit();
        }
    }

    public synchronized void setSummonUnitTriggers(GameAvatar caster, int summonUnitId, List<String> names, boolean enabled) {
        var unit = this.playerSummon;
        if (unit == null || unit.getCaster() != caster || unit.getExcel().getId() != summonUnitId || unit.isExpired()) return;
        unit.onTick(System.currentTimeMillis(), 0);
        unit.setTriggersEnabled(names, enabled);
    }

    public synchronized void handleSummonUnitTriggers(int entityId, String name, MotionInfo motion, RepeatedInt targetIds) {
        this.handleSummonUnitTriggers(entityId, name, motion, targetIds, System.currentTimeMillis());
    }

    /** The event time is supplied by the server, never read from the trigger request. */
    public synchronized void handleSummonUnitTriggers(int entityId, String name, MotionInfo motion, RepeatedInt targetIds, long timestamp) {
        // Get summon unit
        EntitySummonUnit summonUnit = null;

        var entity = this.getEntityById(entityId);
        if (entity instanceof EntitySummonUnit) {
            summonUnit = (EntitySummonUnit) entity;
        } else {
            return;
        }
        if (summonUnit.isExpired()) {
            this.removeEntity(summonUnit);
            return;
        }

        // Get trigger
        var trigger = summonUnit.getExcel().getInfo().getTriggerByName(name);
        if (trigger == null || !summonUnit.isTriggerEnabled(name, timestamp)) return;

        // Get targets
        var targets = new ArrayList<GameEntity>();
        var acceptedIds = RepeatedInt.newEmptyInstance();

        for (int targetId : targetIds) {
            var target = this.getEntityById(targetId);
            if (target != null && target.getScene() == this && trigger.acceptsTarget(target) && !targets.contains(target)) {
                targets.add(target);
                // Capture the accepted ID before an action can remove its entity and reset it to zero.
                acceptedIds.add(targetId);
            }
        }

        // Handle task actions
        trigger.apply(summonUnit, targets);

        // Send packet
        this.getPlayer().sendPacket(new PacketRefreshTriggerByClientScNotify(entityId, name, acceptedIds));
    }

    public boolean canDestroyProp(EntityProp prop) {
        return prop != null && prop.getScene() == this && prop.getEntityId() > 0
            && this.getEntities().get(prop.getEntityId()) == prop
            && (prop.getPropType() == emu.lunarcore.game.enums.PropType.PROP_DESTRUCT
                || prop.getPropType() == emu.lunarcore.game.enums.PropType.PROP_NO_REWARD_DESTRUCT);
    }

    public boolean destroyProp(EntityProp prop) {
        synchronized (getPlayer()) {
            if (getPlayer().getScene() != this || !canDestroyProp(prop)) return false;
            int group = prop.getGroupId(), instance = prop.getInstId();
            var recovery = org.hkrpg.server.gameplay.RoguePropRecovery.prepare(getPlayer(), prop);
            if (getPlaneType() == PlaneType.Rogue
                    && org.hkrpg.server.gameplay.RoguePropRecovery.recoveryProp(prop)
                    && recovery == null) return false;
            var destruction = org.hkrpg.server.gameplay.RoguePropMiracle.prepare(getPlayer(), prop);
            var lottery = org.hkrpg.server.gameplay.RogueGalacticLottery.prepare(getPlayer(), prop);
            if (org.hkrpg.server.gameplay.RogueGalacticLottery.requiresRoll(getPlayer(), prop)
                    && lottery == null) return false;
            var permanent = org.hkrpg.server.gameplay.RoguePropDestruction.prepare(getPlayer(), prop);
            if (org.hkrpg.server.gameplay.RoguePropDestruction.requiresReceipt(getPlayer(), prop)
                    && permanent == null) return false;
            var golden = org.hkrpg.server.gameplay.GoldenSpears.prepare(getPlayer(),prop);
            if(org.hkrpg.server.gameplay.GoldenSpears.applies(prop) && golden == null) return false;
            if (!org.hkrpg.server.gameplay.WorldMechanisms.beforeDestroy(this, prop)) return false;
            if (org.hkrpg.server.gameplay.OrdinaryDestructibles.applies(prop)
                    && !org.hkrpg.server.gameplay.OrdinaryDestructibles.collect(prop)) return false;
            var removal = permanent == null ? null : new PacketSceneGroupRefreshScNotify(this, null, prop);
            if (permanent != null) {
                if (!detachDestructibleProp(prop)) return false;
                permanent.complete();
            } else this.removeEntity(prop);
            if(golden != null && !golden.complete()) return false;
            org.hkrpg.server.gameplay.WorldMechanisms.afterDestroy(this, prop);
            prop.onDestroyed();
            if (recovery != null) recovery.complete();
            if (destruction != null) destruction.complete();
            if (lottery != null) lottery.complete();
            if (removal != null) getPlayer().sendPacket(removal);
            getPlayer().getMissionManager().onPropDestruct(getFloorId(), group, instance);
            getPlayer().getDailyManager().onPropDestruct();
            return true;
        }
    }

    /**
     * Returns the nearest spring (Space Anchor) to the player in the scene
     * @return
     */
    public EntityProp getNearestSpring() {
        return getNearestSpring(Long.MAX_VALUE);
    }

    /**
     * Returns the nearest spring (Space Anchor) to the player in the scene
     * @param minDistSq Only checks springs in below this distance
     * @return
     */
    public EntityProp getNearestSpring(long minDistSq) {
        EntityProp spring = null;
        long springDist = 0;

        for (EntityProp prop : this.getHealingSprings()) {
            long dist = getPlayer().getPos().getFast2dDist(prop.getPos());
            if (dist > minDistSq) continue;

            if (spring == null || dist < springDist) {
                spring = prop;
                springDist = dist;
            }
        }

        return spring;
    }

    public void invokePropTrigger(PropTriggerType type, int param1, int param2) {
        for (PropTrigger trigger : this.getTriggers()) {
            if (trigger.getType() == type && trigger.shouldRun(param1, param2)) {
                trigger.run(this);
            }
        }
    }

    public synchronized void addEntity(GameEntity entity) {
        this.addEntity(entity, false);
    }

    public synchronized void addEntity(GameEntity entity, boolean sendPacket) {
        // Sanity checks - Also dont add entity if it already exists
        if (entity == null || entity.getEntityId() != 0) {
            return;
        }

        // Check if any of the this.getEntities() is the entity to avoid dupes
        for (GameEntity e : this.getEntities().values()) {
            if (e.equals(entity)) {
                return;
            }
        }

        // Set entity id and add monster to entity map
        entity.setEntityId(this.getNextEntityId());
        this.getEntities().put(entity.getEntityId(), entity);

        // Entity add callback
        entity.onAdd();

        // Send packet
        if (sendPacket) {
            player.sendPacket(new PacketSceneGroupRefreshScNotify(this, entity, null));
        }
    }

    public synchronized void removeEntity(GameEntity entity) {
        if (entity == null || entity.getScene() != this || entity.getEntityId() <= 0
                || getEntities().get(entity.getEntityId()) != entity) return;
        removeEntity(entity.getEntityId());
    }

    /** Only an actual defeat of this player's live scene monster may publish a death event. */
    public boolean defeatMonster(EntityMonster monster) {
        synchronized (getPlayer()) {
            synchronized (this) {
                if (getPlayer().getScene() != this || monster == null || monster.getScene() != this
                        || monster.getEntityId() <= 0 || getEntities().get(monster.getEntityId()) != monster)
                    return false;
                int group = monster.getGroupId(), instance = monster.getInstId();
                if (!org.hkrpg.server.gameplay.WorldMechanisms.defeat(this, monster)) return false;
                if (!org.hkrpg.server.gameplay.CompatibleMechanisms.defeat(this, monster)) return false;
                removeEntity(monster);
                invokePropTrigger(PropTriggerType.MONSTER_DIE, group, instance);
                getPlayer().getMissionManager().onKillMonster(getFloorId(), group, instance);
                org.hkrpg.server.gameplay.CompatibleMechanisms.afterDefeat(this, group);
                return true;
            }
        }
    }

    /** The destruction transaction saves its receipt before publishing the captured removal packet. */
    private synchronized boolean detachDestructibleProp(EntityProp prop) {
        int entity = prop.getEntityId();
        if (entity <= 0 || getEntities().get(entity) != prop) return false;
        getEntities().remove(entity);
        prop.onRemove();
        prop.setEntityId(0);
        return true;
    }

    public synchronized void removeEntity(int entityId) {
        GameEntity entity = this.getEntities().remove(entityId);

        if (entity != null) {
            if (entity == this.playerSummon) this.playerSummon = null;
            // Run event
            entity.onRemove();
            // Send packet
            player.sendPacket(new PacketSceneGroupRefreshScNotify(this, null, entity));
            // Reset entity id
            entity.setEntityId(0);
        }
    }

    // Player events

    @Override
    public synchronized void onTick(long timestamp, long delta) {
        // Remove summoned unit if it expired
        if (this.getPlayerSummon() != null) {
            if (this.getPlayerSummon().isExpired()) {
                this.removeSummonUnit();
            }
        }
        // Tick entities
        for (GameEntity entity : this.getEntities().values()) {
            if (entity instanceof Tickable tickableEntity) {
                tickableEntity.onTick(timestamp, delta);
            }
        }
    }

    public void onBattleStart(Battle battle) {
        // Remove summoned unit
        if (battle != null && battle.getStage() != null && battle.getStage().getId() > 0
                && this.getPlayerSummon() != null
                && this.getPlayerSummon().getExcel().isDestroyOnEnterBattle()) {
            this.removeSummonUnit();
        }
    }

    private boolean isExistingExtraData(SceneInfo scenedata, String key) {
        for (var entry : scenedata.getFloorSavedValueMap()) {
            if (entry.getKey().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private void enableExtraData(SceneInfo scenedata, String key, int value) {
        if (!isExistingExtraData(scenedata, key)) {
            var entry = SceneInfo.FloorSavedValueMapEntry.newInstance()
                    .setKey(key)
                    .setValue(value);

            scenedata.addFloorSavedValueMap(entry);
        }
    }

    // Marked chests

    public void addMapPropDefInfo(MapPropDefInfo defInfo, int funcId) {
        if (!this.markedChests.containsKey(funcId)) {
            this.markedChests.put(funcId, new ArrayList<>());
        }
        this.markedChests.get(funcId).add(defInfo);
    }

    public void resetMapPropDefInfo(int funcId) {
        this.markedChests.remove(funcId);
    }

    public void removeAllMapPropDefInfo() {
        this.markedChests.clear();
    }

    public void setMapPropDefInfoForFuncId(int funcId, List<MapPropDefInfo> defInfos) {
        this.markedChests.put(funcId, defInfos);
    }

    public List<MapPropDefInfo> getMapPropDefInfo(int funcId) {
        return this.markedChests.get(funcId);
    }

    public List<Integer> getFuncIdsForMapPropDef() {
        return this.markedChests.keySet().intStream().boxed().toList();
    }

    // Proto serialization

    public SceneInfo toProto() {
        // Mission-backed floor values need the player mutex. Match the game timer's
        // player -> scene order instead of holding the scene while waiting for player state.
        synchronized (getPlayer()) {
            synchronized (this) {
                return toProtoLocked();
            }
        }
    }

    private SceneInfo toProtoLocked() {
        // Set loaded flag
        this.loaded = true;

        // Proto
        var proto = SceneInfo.newInstance()
                .setWorldId(this.getWorldId())
                .setGameModeType(this.getExcel().getPlaneType().getVal())
                .setPlaneId(this.getPlaneId())
                .setFloorId(this.getFloorId())
                .setEntryId(this.getEntryId());
                //.setSceneMissionInfo(this.getSceneMissionInfos());

        for (var extradata : this.getFloorInfo().getExtraDatas(getDimensionId())) {
            int value = getPlayer().getGameplay().getStoryValues().getOrDefault("floor:" + getFloorId() + ":" + extradata.getName(), extradata.getDefaultValue());
            value = org.hkrpg.server.gameplay.Mascots.floorValue(this, extradata.getName(), value);
            this.enableExtraData(proto, extradata.getName(), value);
        }
        String storyPrefix = "floor:" + getFloorId() + ":";
        getPlayer().getGameplay().getStoryValues().forEach((key,value) -> {
            if (key.startsWith(storyPrefix)) {
                String name = key.substring(storyPrefix.length());
                this.enableExtraData(proto,name,org.hkrpg.server.gameplay.Mascots.floorValue(this,name,value));
            }
        });

        // Get current lineup
        PlayerLineup lineup = getPlayer().getCurrentLineup();
        int leaderAvatarId = lineup.getAvatars().get(lineup.getLeader());

        // Sort entities into groups
        var groups = new Int2ObjectOpenHashMap<SceneEntityGroupInfo>();

        // Create player group
        var playerGroup = SceneEntityGroupInfo.newInstance();

        for (var avatar : avatars.values()) {
            // Serialize lineup avatar to proto
            var avatarInfo = avatar.toSceneEntityProto();

            // Set leader avatar id and map layer for main avatar
            if (leaderAvatarId == avatar.getAvatarId()) {
                proto.setLeaderEntityId(avatar.getEntityId());
                avatarInfo.getMutableActor().setMapLayer(1);
            }

            playerGroup.addEntityList(avatarInfo);
        }

        groups.put(0, playerGroup);

        // Add rest of the entities to groups
        for (var entity : getEntities().values()) {
            // Get scene group
            var group = groups.computeIfAbsent(entity.getGroupId(), i -> SceneEntityGroupInfo.newInstance().setGroupId(i));

            // Add to group
            group.addEntityList(entity.toSceneEntityProto());
        }

        // Add groups
        for (var sceneGroup : this.getGroups().values()) {
            // Get group
            var group = groups.computeIfAbsent(sceneGroup.getId(), i -> SceneEntityGroupInfo.newInstance().setGroupId(i));

            if (group != null && sceneGroup.getProperties().size() > 0) {
                // Add group properties
                for (var entry : sceneGroup.getProperties().object2IntEntrySet()) {
                    var property = GroupPropertyMapEntry.newInstance()
                            .setKey(entry.getKey())
                            .setValue(entry.getIntValue());

                    group.addGroupPropertyMap(property);
                }
            }

            // Add group states
            if (sceneGroup.getState() != 0) {
                var state = SceneGroupState.newInstance()
                        .setGroupId(sceneGroup.getId())
                        .setState(sceneGroup.getState())
                        .setIsDefault(true);

                proto.addGroupStateList(state);
            }
        }

        // Add group to proto
        for (var group : groups.values()) {
            proto.addEntityGroupList(group);
        }

        // Scene identifier
        proto.getMutableSceneIdentifier()
            .setFloorId(this.getFloorId());

        // Done
        return proto;
    }
}
