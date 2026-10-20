package emu.lunarcore.game.scene;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.data.config.GroupInfo.GroupLoadSide;
import emu.lunarcore.data.excel.NpcMonsterExcel;
import emu.lunarcore.data.excel.PropExcel;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.enums.PropType;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.game.scene.entity.GameEntity;

public class SceneEntityLoader {

    // Existing map-loading exclusions must also hold when ordinary groups refresh after a quest.
    static boolean isExcludedGroup(Scene scene, GroupInfo group) {
        return group.getGroupName().contains("Bug") || group.getGroupName().contains("Book")
            || group.getGroupName().contains("AngryBlock") || group.getGroupName().contains("ShelfDoor")
            || (scene.getPlaneId() == 20501 && group.getId() >= 360);
    }

    public void onSceneLoad(Scene scene) {
        for (GroupInfo group : scene.getResourceGroups().values()) {
            boolean isToLoad = true;

            if (group.getCategory() == GroupInfo.GroupCategory.Mission || group.getCategory() == GroupInfo.GroupCategory.Atmosphere
                || org.hkrpg.server.gameplay.DeferredMissionGroups.tracks(group, scene)
                || org.hkrpg.server.gameplay.MissionGroupConditions.tracks(group, scene)) {
                isToLoad = scene.getPlayer().getMissionManager().shouldLoadGroup(group, scene);
            }

            // These cause the map to get stuck loading
            if (isExcludedGroup(scene, group)) {
                isToLoad = false;
            }

            // Load groups
            if (group.getLoadSide() == GroupLoadSide.Server && isToLoad) {
                scene.loadGroup(group);
            }
        }
    }

    public EntityMonster loadMonster(Scene scene, GroupInfo group, MonsterInfo monsterInfo) {
        // Don't spawn entity if they have certain flags in their info
        if (monsterInfo.isIsDelete() || monsterInfo.isIsClientOnly() || !monsterInfo.isLoadOnInitial()) {
            return null;
        }
        if (!org.hkrpg.server.gameplay.DailyMissionMonsterRules.spawns(scene, group, monsterInfo)) {
            return null;
        }
        if (!org.hkrpg.server.gameplay.WolfBroAmmunition.spawns(scene, group, monsterInfo)) return null;
        if (!org.hkrpg.server.gameplay.WorldMechanisms.spawns(scene, group, monsterInfo)) return null;

        // Get excels from game data
        NpcMonsterExcel npcMonsterExcel = GameData.getNpcMonsterExcelMap().get(monsterInfo.getNPCMonsterID());
        if (npcMonsterExcel == null) return null;

        // Create monster from group monster info
        EntityMonster monster = new EntityMonster(scene, npcMonsterExcel, group, monsterInfo);
        monster.setEventId(monsterInfo.getEventID());
        int world = org.hkrpg.server.gameplay.RaidDifficultySettings.world(scene);
        if (world < 0) return null;
        monster.setWorldLevel(world);

        return monster;
    }

    public EntityProp loadProp(Scene scene, GroupInfo group, PropInfo propInfo) {
        // Don't spawn entity if they have certain flags in their info
        if (propInfo.isIsDelete() /* || propInfo.isIsClientOnly() */ || !propInfo.isLoadOnInitial()) {
            return null;
        }
        if(org.hkrpg.server.gameplay.GoldenSpears.removed(scene,group,propInfo)) return null;
        if (!org.hkrpg.server.gameplay.WorldMechanisms.spawns(scene, group, propInfo)) return null;

        // Get prop excel to make sure prop exists
        PropExcel propExcel = GameData.getPropExcelMap().get(propInfo.getPropID());
        if (propExcel == null) return null;

        // Remove these annoying props from the map
        if (propExcel.isDisabled() && !org.hkrpg.server.gameplay.PropTimelines.tagged(propInfo)) {
            return null;
        }

        // Create prop from group prop info
        EntityProp prop = new EntityProp(scene, propExcel, group, propInfo);
        prop.setState(propInfo.getState(), false);

        // Cache
        if (prop.getPropId() == 1003) {
            // Hacky fix to open simulated universe
            if (propInfo.getMappingInfoID() == 2220) {
                // Regular simulated universe is locked behind a mission requirement by default
                prop.setState(PropState.Open, false);
            } else {
                // Skip tutorial simulated universe
                return null;
            }
        } else if (prop.getPropId() == 1025)  {
            // Show divergent universe prop
            prop.setState(PropState.Open, false);
        } else if (prop.getPropId() == 104029) { // Era flippers
            prop.setState(PropState.Open, false);
        } else if (prop.getPropId() == 104022) { // Mirage orb
            prop.setState(PropState.Closed, false);
        } else if (prop.getExcel().isDoor() && group.getCategory()!=GroupInfo.GroupCategory.Mission) {
            // Hacky fix to always open doors
            prop.setState(PropState.Open, false);
        } else if (prop.getExcel().getPropType() == PropType.PROP_SPRING) {
            // Cache teleport anchors
            scene.getHealingSprings().add(prop);
        }

        if (scene.getPlaneType() != PlaneType.Raid && propExcel.getPropType() == PropType.PROP_ELEVATOR) {
            prop.setState(PropState.Elevator1);
        }

        // Add trigger to scene
        if (propInfo.getTrigger() != null) {
            scene.getTriggers().add(propInfo.getTrigger());
        }

        org.hkrpg.server.gameplay.SceneInteractions.restore(prop);
        org.hkrpg.server.gameplay.ScenePropTransitions.restore(prop);
        org.hkrpg.server.gameplay.WorldChests.restore(prop);
        org.hkrpg.server.gameplay.GoldenSpears.restore(prop);
        org.hkrpg.server.gameplay.WorldMechanisms.restore(prop);
        return prop;
    }

    public EntityNpc loadNpc(Scene scene, GroupInfo group, NpcInfo npcInfo) {
        if (org.hkrpg.server.gameplay.DailyOriginEffects.suppresses(scene, group, npcInfo)) return null;
        // Don't spawn entity if they have certain flags in their info
        if (npcInfo.isIsDelete() || npcInfo.isIsClientOnly()
            || !npcInfo.isLoadOnInitial() && !org.hkrpg.server.gameplay.MissionTrialRemovals.spawns(scene, group, npcInfo)
                && !org.hkrpg.server.gameplay.DailyRecoverPerformances.spawns(scene, group, npcInfo)) {
            return null;
        }

        // Sanity check npc id
        if (!GameData.getNpcExcelMap().containsKey(npcInfo.getNPCID())) {
            return null;
        }

        // A definition can have multiple authored placements; group/instance identifies an actor.
        boolean hasDuplicateInstance = false;
        for (GameEntity entity : scene.getEntities().values()) {
            if (entity instanceof EntityNpc eNpc && eNpc.getGroupId() == group.getId() && eNpc.getInstId() == npcInfo.getID()) {
                hasDuplicateInstance = true;
                break;
            }
        }
        if (hasDuplicateInstance) return null;

        // Create npc from group and npc info
        return new EntityNpc(scene, group, npcInfo);
    }
}
