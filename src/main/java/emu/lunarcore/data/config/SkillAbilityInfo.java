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
    private com.google.gson.JsonObject GlobalModifiers;

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
                collectProjectileDeclaration(skill, task);
                parseTask(skill, actionList, task);
            }
        }

        // This is the fixed 4.6 source link, not a suffix rule for arbitrary avatars.
        var base = avatarExcel.getMazeSkill();
        if (avatarExcel.getAvatarID() == 1510 && base != null
                && "LocalPlayer_HimekoNova_MazeSkill".equals(base.getAbilityName())
                && hasHimekoNovaInputLink()) {
            for (var ability : AbilityList) {
                if (!"LocalPlayer_HimekoNova_MazeSkill_Input".equals(ability.getName())
                        || !"Free".equals(ability.getManualTriggerType()) || ability.getOnStart() == null) continue;
                var manual = new MazeSkill(avatarExcel, 2, ability.getName());
                for (var task : ability.getOnStart()) {
                    collectProjectileDeclaration(manual, task);
                    parseTask(manual, manual.getCastActions(), task);
                }
                if (manual.isProjectileDeclared()) base.addManualAbility(manual);
            }
        }

        // Both original ByHaveAbilityTarget branches have the same END Attack contract.
        // Only that callback is admitted; neither target selection nor projectile physics is
        // inferred by selecting the visual branch. Whole original blobs are checked at ingress.
        var normal = avatarExcel.getMazeAttack();
        if (avatarExcel.getAvatarID() == 1415 && base != null && normal != null
                && "LocalPlayer_Cyrene_00_MazeSkill".equals(base.getAbilityName())
                && "LocalPlayer_Cyrene_00_NormalAtk01".equals(normal.getAbilityName())
                && hasCyreneInputLink()) {
            for (var ability : AbilityList) {
                if (!"LocalPlayer_Cyrene_00_NormalAtk02".equals(ability.getName())
                        || !"Free".equals(ability.getManualTriggerType())) continue;
                var projectile = cyreneEndProjectile(ability);
                if (projectile == null) continue;
                var manual = new MazeSkill(avatarExcel, 1, ability.getName());
                collectProjectileDeclaration(manual, projectile);
                parseTask(manual, manual.getCastActions(), projectile);
                if (manual.isProjectileDeclared()) normal.addManualAbility(manual);
            }
        }

        // The original1407 input is a direct Attack followed by caster-buff cleanup.
        // It has no projectile declaration; only the full fixed ingress contract admits it.
        if (avatarExcel.getAvatarID() == 1407 && base != null
                && "LocalPlayer_Castorice_MazeSkill".equals(base.getAbilityName())
                && hasCastoriceInputLink()) {
            for (var ability : AbilityList) {
                if (!"LocalPlayer_Castorice_MazeSkill_Input".equals(ability.getName())
                        || !"Free".equals(ability.getManualTriggerType()) || ability.getOnStart() == null) continue;
                var manual = new MazeSkill(avatarExcel, 2, ability.getName());
                for (var task : ability.getOnStart()) {
                    collectProjectileDeclaration(manual, task);
                    parseTask(manual, manual.getCastActions(), task);
                }
                if (!manual.isProjectileDeclared()) base.addManualAbility(manual);
            }
            // Original Input.OnAbort starts this separate Free Ability. Its only server
            // effect is the caster140704 removal after a leader-conditioned Submit.
            if (hasCastoriceOutLink()) for (var ability : AbilityList) {
                var remove = castoriceOutCleanupTask(ability);
                if (remove == null) continue;
                var cleanup = new MazeSkill(avatarExcel, 2, ability.getName());
                cleanup.setTriggerBattle(false);
                cleanup.getCastActions().add(new MazeSkillRemoveBuff(140704));
                base.addManualAbility(cleanup);
            }
        }

        return true;
    }

    private boolean hasCastoriceOutLink() {
        for (var ability : AbilityList) {
            if (!"LocalPlayer_Castorice_MazeSkill_Input".equals(ability.getName()) || ability.getOnAbort() == null
                    || ability.getOnAbort().isEmpty()) continue;
            return ability.getOnAbort().get(0).equals(com.google.gson.JsonParser.parseString(
                    "{\"$type\":\"RPG.GameCore.AdventureTriggerTargetAbility\",\"SkillType\":\"MazeSkill\","
                    + "\"AbilityName\":\"LocalPlayer_Castorice_MazeSkill_Out\"}"));
        }
        return false;
    }

    private TaskInfo castoriceOutCleanupTask(AbilityInfo ability) {
        if (!"LocalPlayer_Castorice_MazeSkill_Out".equals(ability.getName())
                || !"Free".equals(ability.getManualTriggerType()) || ability.getOnStart() == null) return null;
        try {
            var branch = ability.getOnStart().get(2);
            if (!"RPG.GameCore.PredicateTaskList".equals(branch.getType())
                    || !com.google.gson.JsonParser.parseString("{\"$type\":\"RPG.GameCore.ByCompareTarget\","
                        + "\"TargetType\":{\"$type\":\"RPG.GameCore.TargetAlias\",\"Alias\":\"Caster\"},"
                        + "\"CompareType\":{\"$type\":\"RPG.GameCore.TargetAlias\",\"Alias\":\"LatestAdvTeamLeader\"}}")
                        .equals(branch.getPredicate())
                    || branch.getSuccessTaskList() == null || branch.getSuccessTaskList().size() != 2
                    || (branch.getFailedTaskList() != null && !branch.getFailedTaskList().isEmpty())
                    || !"RPG.GameCore.AdventureSubmitSkill".equals(branch.getSuccessTaskList().get(0).getType())) return null;
            var remove = branch.getSuccessTaskList().get(1);
            return "RPG.GameCore.RemoveMazeBuff".equals(remove.getType()) && remove.getID() == 140704
                    && remove.hasExactCasterTarget() && !remove.IsClientOnly() ? remove : null;
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    private boolean hasCastoriceInputLink() {
        try {
            var wait = GlobalModifiers.getAsJsonObject("ADV_StageAbility_Maze_Castorice_Radius")
                    .getAsJsonArray("ModifierTaskList").get(0).getAsJsonObject();
            if (!"RPG.GameCore.WaitSkillInput".equals(wait.get("$type").getAsString())
                    || !wait.get("CheckAllowUseSkill").getAsBoolean()) return false;
            var trigger = wait.getAsJsonArray("OnInput").get(0).getAsJsonObject();
            return "RPG.GameCore.AdventureTriggerTargetAbility".equals(trigger.get("$type").getAsString())
                    && "MazeSkill".equals(trigger.get("SkillType").getAsString())
                    && "LocalPlayer_Castorice_MazeSkill_Input".equals(trigger.get("AbilityName").getAsString());
        } catch (RuntimeException unsupported) {
            return false;
        }
    }

    private boolean hasCyreneInputLink() {
        try {
            var modifier = GlobalModifiers.getAsJsonObject("ADV_StageAbility_Maze_Cyrene");
            var loop = modifier.getAsJsonArray("ModifierTaskList").get(0).getAsJsonObject();
            if (!"RPG.GameCore.LoopExecuteTaskList".equals(loop.get("$type").getAsString())) return false;
            var wait = loop.getAsJsonArray("TaskList").get(0).getAsJsonObject();
            if (!"RPG.GameCore.WaitSkillInput".equals(wait.get("$type").getAsString())
                    || !wait.get("CheckAllowUseSkill").getAsBoolean()) return false;
            var trigger = wait.getAsJsonArray("OnInput").get(0).getAsJsonObject();
            return "RPG.GameCore.AdventureTriggerTargetAbility".equals(trigger.get("$type").getAsString())
                    && !trigger.has("SkillType")
                    && "LocalPlayer_Cyrene_00_NormalAtk02".equals(trigger.get("AbilityName").getAsString());
        } catch (RuntimeException unsupported) {
            return false;
        }
    }

    private TaskInfo cyreneEndProjectile(AbilityInfo ability) {
        try {
            var branch = ability.getOnStart().get(1);
            var predicate = branch.getPredicate().getAsJsonObject();
            if (!"RPG.GameCore.PredicateTaskList".equals(branch.getType())
                    || !predicate.keySet().equals(java.util.Set.of("$type"))
                    || !"RPG.GameCore.ByHaveAbilityTarget".equals(predicate.get("$type").getAsString())) return null;
            TaskInfo selected = null;
            for (var tasks : java.util.List.of(branch.getSuccessTaskList(), branch.getFailedTaskList())) {
                var projectile = tasks.get(7);
                if (!projectile.hasProjectileDeclaration()
                        || !"RPG.GameCore.AdventureFireProjectile".equals(projectile.getType())
                        || projectile.getOnProjectileHit() != null
                        || projectile.getOnProjectileLifetimeFinish() == null
                        || projectile.getOnProjectileLifetimeFinish().size() != 1
                        || !"RPG.GameCore.AdventureTriggerAttack".equals(
                            projectile.getOnProjectileLifetimeFinish().getFirst().getType())) return null;
                selected = projectile;
            }
            return selected;
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    private boolean hasHimekoNovaInputLink() {
        try {
            var modifier = GlobalModifiers.getAsJsonObject("ADV_StageAbility_Maze_HimekoNova_AttackRadius");
            var loop = modifier.getAsJsonArray("ModifierTaskList").get(0).getAsJsonObject();
            if (!"RPG.GameCore.LoopExecuteTaskList".equals(loop.get("$type").getAsString())) return false;
            var wait = loop.getAsJsonArray("TaskList").get(0).getAsJsonObject();
            if (!"RPG.GameCore.WaitSkillInput".equals(wait.get("$type").getAsString())
                    || !wait.get("CheckAllowUseSkill").getAsBoolean() || !wait.get("CheckMPNeed").getAsBoolean()) return false;
            var trigger = wait.getAsJsonArray("OnInput").get(0).getAsJsonObject();
            return "RPG.GameCore.AdventureTriggerTargetAbility".equals(trigger.get("$type").getAsString())
                    && "MazeSkill".equals(trigger.get("SkillType").getAsString())
                    && "LocalPlayer_HimekoNova_MazeSkill_Input".equals(trigger.get("AbilityName").getAsString());
        } catch (RuntimeException unsupported) {
            return false;
        }
    }

    // "Simple" way to parse maze attacks/skills
    // TODO parse tasks better
    private void parseTask(MazeSkill skill, List<MazeSkillAction> actionList, TaskInfo task) {
        parseTask(skill, actionList, skill.getAttackActions(), task, false);
    }

    private void parseTask(MazeSkill skill, List<MazeSkillAction> actionList,
            List<MazeSkillAction> attackActions, TaskInfo task, boolean conditional) {
        parseTask(skill, actionList, attackActions, task, conditional, false);
    }

    private void parseTask(MazeSkill skill, List<MazeSkillAction> actionList,
            List<MazeSkillAction> attackActions, TaskInfo task, boolean conditional, boolean projectileCallback) {
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
                for (var child : task.getSuccessTaskList()) parseTask(skill, success, successAttack, child, true, projectileCallback);
            }
            if (task.getFailedTaskList() != null) {
                for (var child : task.getFailedTaskList()) parseTask(skill, failure, failureAttack, child, true, projectileCallback);
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
            if (skill.getIndex() == 2 && !projectileCallback) {
                if (!conditional) skill.setTriggerBattle(task.isTriggerBattle());
                else if (!task.IsClientOnly()) actionList.add(new MazeSkillMetadata(skill, task.isTriggerBattle()));
            }
            var attack = parseCallbacks(skill, task.getOnAttack(), projectileCallback);
            var battle = parseCallbacks(skill, task.getOnBattle(), projectileCallback);
            attackActions.add(new MazeSkillTriggerAttack(skill, task.isTriggerBattle(), projectileCallback, attack, battle));
        } else if ("RPG.GameCore.AdventureFireProjectile".equals(task.getType())
                || "RPG.GameCore.NewAdventureFireProjectile".equals(task.getType())) {
            var hit = parseCallbacks(skill, task.getOnProjectileHit(), true);
            var finish = parseCallbacks(skill, task.getOnProjectileLifetimeFinish(), true);
            attackActions.add(new MazeSkillProjectileCallbacks(skill, hit, finish));
        }
    }

    private List<MazeSkillAction> parseCallbacks(MazeSkill skill, List<TaskInfo> tasks, boolean projectileCallback) {
        var actions = new java.util.ArrayList<MazeSkillAction>();
        if (tasks != null) {
            for (var task : tasks) parseTask(skill, actions, actions, task, true, projectileCallback);
        }
        return actions;
    }

    private void collectProjectileDeclarations(MazeSkill skill, List<TaskInfo> tasks) {
        if (tasks != null) for (var task : tasks) collectProjectileDeclaration(skill, task);
    }

    private void collectProjectileDeclaration(MazeSkill skill, TaskInfo task) {
        if (task == null || task.getType() == null || task.IsClientOnly()) return;
        // A declaration under an unsupported predicate is still source-owned. Its leaves remain
        // unsupported: this scan never grants actions or evaluates a predicate as successful.
        switch (task.getType()) {
            case "RPG.GameCore.PredicateTaskList" -> {
                collectProjectileDeclarations(skill, task.getSuccessTaskList());
                collectProjectileDeclarations(skill, task.getFailedTaskList());
            }
            case "RPG.GameCore.AdventureTriggerAttack" -> {
                collectProjectileDeclarations(skill, task.getOnAttack());
                collectProjectileDeclarations(skill, task.getOnBattle());
            }
            case "RPG.GameCore.AdventureFireProjectile", "RPG.GameCore.NewAdventureFireProjectile" -> {
                if (task.hasProjectileDeclaration()) skill.setProjectileDeclared(true);
                collectProjectileDeclarations(skill, task.getOnProjectileHit());
                collectProjectileDeclarations(skill, task.getOnProjectileLifetimeFinish());
            }
            default -> { }
        }
    }

    /**
     * The equivalent of the AbilityConfig class in anime game.
     */
    @Getter
    public class AbilityInfo {
        private String Name;
        private String ManualTriggerType;
        private List<TaskInfo> OnStart;
        private com.google.gson.JsonArray OnAbort;
    }
}
