package emu.lunarcore.data.config;

import java.util.ArrayList;
import java.util.List;

import emu.lunarcore.game.battle.skills.MazeSkillAction;
import emu.lunarcore.game.battle.skills.MazeSkillAddBuff;
import emu.lunarcore.game.battle.skills.MazeSkillHitProp;
import lombok.Getter;

/**
 * Original name: SummonUnitConfig
 */
@Getter
public class SummonUnitInfo {
    private String AttachPoint;
    private SummonUnitTriggers TriggerConfig;
    private List<TaskInfo> OnCreate;
    private List<TaskInfo> OnDestroy;

    public List<SummonUnitCustomTrigger> getCustomTriggers() {
        if (TriggerConfig == null || TriggerConfig.getCustomTriggers() == null) {
            return new ArrayList<>();
        }

        return TriggerConfig.getCustomTriggers();
    }

    public SummonUnitCustomTrigger getTriggerByName(String name) {
        return getCustomTriggers().stream()
                .filter(c -> c.getTriggerName().equals(name))
                .findFirst()
                .orElse(null);
    }

    public void buildMazeSkillActions() {
        for (var customTrigger : getCustomTriggers()) {
            customTrigger.buildMazeSkillActions();
        }
    }

    public void applyKnownDestroyActions(emu.lunarcore.game.avatar.GameAvatar caster) {
        if (this.OnDestroy == null) return;
        for (var task : this.OnDestroy) {
            if (!task.IsClientOnly() && "RPG.GameCore.RemoveMazeBuff".equals(task.getType())
                    && task.hasCasterSummonerTarget() && task.getID() > 0) {
                caster.removeBuff(task.getID());
            }
        }
    }

    /**
     * Original name: SummonUnitTriggerConfig
     */
    @Getter
    public static class SummonUnitTriggers {
        private List<SummonUnitCustomTrigger> CustomTriggers;
    }

    /**
     * Original name: UnitCustomTriggerConfig
     */
    @Getter
    public static class SummonUnitCustomTrigger {
        private String TriggerName;
        private boolean DefaultEnable = true;
        private List<String> TargetTypes;
        private List<TaskInfo> OnTriggerEnter;

        private transient List<MazeSkillAction> actions;

        public boolean acceptsTarget(emu.lunarcore.game.scene.entity.GameEntity target) {
            if (this.TargetTypes == null) return true;
            for (var type : this.TargetTypes) {
                if ("NPCMonster".equals(type) && target instanceof emu.lunarcore.game.scene.entity.EntityMonster
                        || "Prop".equals(type) && target instanceof emu.lunarcore.game.scene.entity.EntityProp
                        || "NPC".equals(type) && target instanceof emu.lunarcore.game.scene.entity.EntityNpc) {
                    return true;
                }
            }
            return false;
        }

        public void buildMazeSkillActions() {
            // Create actions list
            this.actions = new ArrayList<>();

            // Sanity check
            if (this.OnTriggerEnter == null) return;

            // Build maze actions
            for (var task : this.OnTriggerEnter) {
                if ("RPG.GameCore.AddMazeBuff".equals(task.getType()) && task.hasTaskActionTarget()
                        && task.getID() > 0 && task.getFixedLifeTimeSeconds().isPresent()) {
                    var actionAddBuff = new MazeSkillAddBuff(task.getID(), task.getFixedLifeTimeSeconds().getAsDouble());
                    if (task.getCondition() != null && !task.getCondition().isJsonNull()) {
                        var condition = org.hkrpg.server.gameplay.MazeBuffConditions.compile(task.getCondition());
                        if (condition.isEmpty()) continue;
                        actionAddBuff.setCondition(condition.get());
                    }
                    actionAddBuff.setSendBuffPacket(true);

                    actions.add(actionAddBuff);
                } else if (task.getType().contains("TriggerHitProp")) {
                    actions.add(new MazeSkillHitProp());
                }
            }
        }

        public void apply(emu.lunarcore.game.scene.entity.EntitySummonUnit unit,
                List<? extends emu.lunarcore.game.scene.entity.GameEntity> targets) {
            if (this.actions == null) return;
            for (var action : this.actions) {
                if (action instanceof MazeSkillAddBuff add) {
                    for (var target : targets) {
                        if (target instanceof emu.lunarcore.game.scene.entity.EntityMonster monster) {
                            var buff = add.applyToMonster(unit.getCaster(), monster);
                            if (buff != null) unit.trackAppliedBuff(monster, buff);
                        }
                    }
                } else {
                    action.onCastHit(unit.getCaster(), targets);
                }
            }
        }
    }

}
