package emu.lunarcore.data.config;

import java.util.List;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.game.battle.skills.*;
import lombok.Getter;

/**
 *  Original name: SkillAbilityConfig
 */
public class SkillAbilityInfo {
    private List<AbilityInfo> AbilityList;

    public boolean parse(AvatarExcel avatarExcel) {
        // Init variable
        MazeSkill skill = null;

        // Canonical first attack is distinct from auxiliary callbacks and combo variants.
        AbilityInfo defaultAttack = null;
        for (AbilityInfo ability : AbilityList) {
            if (ability.getName().endsWith("NormalAtk01")) {
                defaultAttack = ability;
                break;
            }
            if (defaultAttack == null && ability.getName().contains("NormalAtk")) {
                defaultAttack = ability;
            }
        }

        // Look for MazeSkill
        for (AbilityInfo ability : AbilityList) {
            //
            List<MazeSkillAction> actionList = null;

            // Skip if not a maze skill
            if (ability.getName().endsWith("MazeSkill")) {
                skill = new MazeSkill(avatarExcel, 2, ability.getName());
                avatarExcel.setMazeSkill(skill);

                actionList = skill.getCastActions();

            } else if (ability == defaultAttack) {
                skill = new MazeSkill(avatarExcel, 1, ability.getName());
                avatarExcel.setMazeAttack(skill);

                actionList = skill.getAttackActions();
            } else {
                continue;
            }

            // Parse tasks
            for (TaskInfo task : ability.getOnStart()) {
                parseTask(skill, actionList, task);
            }
        }

        return true;
    }

    // "Simple" way to parse maze attacks/skills
    // TODO parse tasks better
    private void parseTask(MazeSkill skill, List<MazeSkillAction> actionList, TaskInfo task) {
        parseTask(skill, actionList, skill.getAttackActions(), task, false);
    }

    private void parseTask(MazeSkill skill, List<MazeSkillAction> actionList,
            List<MazeSkillAction> attackActions, TaskInfo task, boolean conditional) {
        // A known task owns its callbacks. Unknown containers cannot grant their success leaves.
        if (task == null || task.getType() == null || task.IsClientOnly()) return;
        if ("RPG.GameCore.PredicateTaskList".equals(task.getType())) {
            var predicate = org.hkrpg.server.gameplay.MazeBuffConditions.compileBranch(task.getPredicate());
            var targetInverse = MazeSkillInstantKillCondition.parseInverse(task.getPredicate());
            if (predicate.isEmpty() && targetInverse.isEmpty()) return;
            var success = new java.util.ArrayList<MazeSkillAction>();
            var failure = new java.util.ArrayList<MazeSkillAction>();
            var successAttack = new java.util.ArrayList<MazeSkillAction>();
            var failureAttack = new java.util.ArrayList<MazeSkillAction>();
            if (task.getSuccessTaskList() != null) {
                for (var child : task.getSuccessTaskList()) parseTask(skill, success, successAttack, child, true);
            }
            if (task.getFailedTaskList() != null) {
                for (var child : task.getFailedTaskList()) parseTask(skill, failure, failureAttack, child, true);
            }
            actionList.add(targetInverse.isPresent()
                    ? new MazeSkillInstantKillCondition(targetInverse.get(), success, failure)
                    : MazeSkillBuffCondition.fromCondition(predicate.get(), success, failure));
            if (!successAttack.isEmpty() || !failureAttack.isEmpty()) {
                attackActions.add(targetInverse.isPresent()
                        ? new MazeSkillInstantKillCondition(targetInverse.get(), successAttack, failureAttack)
                        : MazeSkillBuffCondition.fromCondition(predicate.get(), successAttack, failureAttack));
            }
            return;
        }
        if ("RPG.GameCore.AddMazeBuff".equals(task.getType())) {
            var lifetime = MazeBuffValues.lifetime(skill, task);
            if (lifetime.isEmpty()) return;
            var fixed = task.getFixedLifeTimeSeconds();
            var action = task.getLifeTimeExpression() == null
                    ? new MazeSkillAddBuff(task.getID(), 20)
                    : fixed.isPresent() ? new MazeSkillAddBuff(task.getID(), fixed.getAsDouble())
                    : new MazeSkillAddBuff(task.getID(), lifetime.get());
            action.setCasterTarget(task.hasCasterTarget());
            if (task.getCondition() != null && !task.getCondition().isJsonNull()) {
                var condition = org.hkrpg.server.gameplay.MazeBuffConditions.compile(task.getCondition());
                if (condition.isEmpty()) return;
                action.setCondition(condition.get());
            }
            actionList.add(action);
        } else if ("RPG.GameCore.RemoveMazeBuff".equals(task.getType())) {
            // Removal is an ordered runtime action. Other target selectors need their own resolver.
            if ("RPG.GameCore.RemoveMazeBuff".equals(task.getType()) && task.hasCasterTarget() && task.getID() > 0) {
                actionList.add(new MazeSkillRemoveBuff(task.getID()));
            }
        } else if ("RPG.GameCore.AdventureModifyTeamPlayerHP".equals(task.getType())) {
            MazeRecoveryValues.hpRatio(skill, task)
                    .ifPresent(value -> actionList.add(new MazeSkillModifyHP(value)));
        } else if ("RPG.GameCore.AdventureModifyTeamPlayerSP".equals(task.getType())) {
            MazeRecoveryValues.spRatio(skill, task)
                    .ifPresent(value -> actionList.add(new MazeSkillModifySP(value)));
        } else if ("RPG.GameCore.CreateSummonUnit".equals(task.getType())) {
            if (!task.IsClientOnly()) {
                var duration = task.getFixedSummonDurationSeconds();
                var excel = GameData.getSummonUnitExcelMap().get(task.getID());
                if ("RPG.GameCore.CreateSummonUnit".equals(task.getType())
                        && task.hasCasterSummonPosition() && duration.isPresent()
                        && excel != null && !excel.isIsClient() && excel.getInfo() != null) {
                    var action = new MazeSkillSummonUnit(excel, duration.getAsDouble());
                    if (conditional) actionList.add(new MazeSkillMetadata(skill, false));
                    else skill.setTriggerBattle(false);
                    actionList.add(action);
                }
            }
        } else if ("RPG.GameCore.DestroySummonUnit".equals(task.getType())) {
            task.getCasterSummonUnitId().ifPresent(id -> actionList.add(new MazeSkillDestroySummonUnit(id)));
        } else if ("RPG.GameCore.SetSummonUnitTriggerEnable".equals(task.getType()) && !task.IsClientOnly()) {
            var enabled = task.getTriggerSwitchEnabled();
            var names = task.getTriggerNames();
            if (enabled.isPresent() && (names == null || names.stream().noneMatch(n -> n == null || n.isBlank()))) {
                task.getCasterSummonUnitId().ifPresent(id -> actionList.add(new MazeSkillSetSummonTrigger(id, names, enabled.get())));
            }
        } else if ("RPG.GameCore.AddAdventureModifier".equals(task.getType())) {
            if (task.getModifierName() == null || task.getModifierName().isBlank()) return;
            // Keep the legacy omitted-selector default, but do not replace an explicit target.
            if (!conditional && (task.getTargetType() == null || task.hasCasterTarget())) {
                skill.addScriptedAdventureModifier(task.getModifierName());
                actionList.add(new MazeSkillMetadata(skill, task.getModifierName()));
            }
            else if ("RPG.GameCore.AddAdventureModifier".equals(task.getType())
                    && !task.IsClientOnly() && task.hasCasterTarget()
                    && task.getModifierName() != null && !task.getModifierName().isBlank()) {
                actionList.add(new MazeSkillMetadata(skill, task.getModifierName()));
            }
        } else if ("RPG.GameCore.RemoveAdventureModifier".equals(task.getType())) {
            if (task.hasExactCasterTarget() && task.getModifierName() != null
                    && !task.getModifierName().isBlank()) {
                actionList.add(MazeSkillMetadata.removeModifier(skill, task.getModifierName()));
            }
        } else if ("RPG.GameCore.AdventureSetAttackTargetMonsterDie".equals(task.getType())) {
            actionList.add(new MazeSkillSetAttackTargetMonsterDie());
        } else if ("RPG.GameCore.AdventureTriggerAttack".equals(task.getType())) {
            if (skill.getIndex() == 2) {
                if (!conditional) skill.setTriggerBattle(task.isTriggerBattle());
                else if (!task.IsClientOnly()) actionList.add(new MazeSkillMetadata(skill, task.isTriggerBattle()));
            }
            if (task.getOnAttack() != null) {
                for (TaskInfo t : task.getOnAttack()) {
                    parseTask(skill, attackActions, attackActions, t, conditional);
                }
            }
            if (task.getOnBattle() != null) {
                for (TaskInfo t : task.getOnBattle()) {
                    parseTask(skill, attackActions, attackActions, t, conditional);
                }
            }
        } else if ("RPG.GameCore.AdventureFireProjectile".equals(task.getType())) {
            if (task.getOnProjectileHit() != null) {
                for (TaskInfo t : task.getOnProjectileHit()) {
                    parseTask(skill, attackActions, attackActions, t, conditional);
                }
            }
            if (task.getOnProjectileLifetimeFinish() != null) {
                for (TaskInfo t : task.getOnProjectileLifetimeFinish()) {
                    parseTask(skill, attackActions, attackActions, t, conditional);
                }
            }
        }
    }

    /**
     * The equivalent of the AbilityConfig class in anime game.
     */
    @Getter
    public class AbilityInfo {
        private String Name;
        private List<TaskInfo> OnStart;
    }
}
