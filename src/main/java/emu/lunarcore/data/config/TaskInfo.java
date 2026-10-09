package emu.lunarcore.data.config;

import java.util.List;

import com.google.gson.annotations.SerializedName;

import lombok.AccessLevel;
import lombok.Getter;

/**
 * Original name: TaskConfig
 */
@Getter
public class TaskInfo {
    @Getter(AccessLevel.NONE)
    private String $type;

    @SerializedName(value = "ID", alternate = {"SummonUnitID"})
    private int ID;

    private String ModifierName;
    private boolean TriggerBattle = true;
    private com.google.gson.JsonElement LifeTime;
    // Duration is also a scalar on unrelated task kinds such as LookAt.
    private com.google.gson.JsonElement Duration;
    private com.google.gson.JsonElement PosTarget;
    private com.google.gson.JsonElement Summoner;
    private com.google.gson.JsonElement SummonUnit;
    private com.google.gson.JsonElement Predicate;
    private com.google.gson.JsonElement Condition;
    private com.google.gson.JsonElement WaitTime;
    private com.google.gson.JsonElement Enable;
    private com.google.gson.JsonElement AddRatio;
    private com.google.gson.JsonElement MinRatio;
    private com.google.gson.JsonElement ModifyFunction;
    private com.google.gson.JsonElement ModifyValue;
    private com.google.gson.JsonElement SpecifyTargetType;
    private List<String> TriggerNames;
    // Other task kinds use a string in this same field (for example LookAt).
    private com.google.gson.JsonElement TargetType;
    private boolean IsClientOnly = false;

    private List<TaskInfo> OnAttack;
    private List<TaskInfo> OnBattle;
    private List<TaskInfo> SuccessTaskList;
    private List<TaskInfo> FailedTaskList;
    private List<TaskInfo> OnProjectileHit;
    private List<TaskInfo> OnProjectileLifetimeFinish;

    public String getType() {
        return this.$type;
    }

    public boolean IsClientOnly() {
        return this.IsClientOnly;
    }

    public int getLifeTime() {
        if (this.LifeTime == null) {
            return 20; // TODO change
        }

        return (int) emu.lunarcore.data.ResourceLoader.getGson()
                .fromJson(this.LifeTime, DynamicFloat.class).getValue();
    }

    public java.util.OptionalDouble getFixedLifeTimeSeconds() {
        return MazeBuffValues.literalLifetimeSeconds(LifeTime);
    }

    public com.google.gson.JsonElement getLifeTimeExpression() {
        return LifeTime;
    }

    public java.util.OptionalDouble getFixedSummonDurationSeconds() {
        if (Duration == null || !Duration.isJsonObject()
                || !Duration.getAsJsonObject().has("FixedValue")) {
            return java.util.OptionalDouble.empty();
        }
        return emu.lunarcore.data.ResourceLoader.getGson()
                .fromJson(Duration, DynamicFloat.class).getConstantValue();
    }

    public java.util.OptionalDouble getFixedWaitSeconds() {
        // The pinned 4.6 WaitSecond constructor uses the shared fixed-zero DynamicFloat.
        if (WaitTime == null) return java.util.OptionalDouble.of(0);
        if (!WaitTime.isJsonObject()
                || !WaitTime.getAsJsonObject().has("FixedValue")) {
            return java.util.OptionalDouble.empty();
        }
        return emu.lunarcore.data.ResourceLoader.getGson()
                .fromJson(WaitTime, DynamicFloat.class).getConstantValue();
    }

    public java.util.Optional<Boolean> getTriggerSwitchEnabled() {
        if (Enable == null) return java.util.Optional.of(false);
        if (!Enable.isJsonPrimitive() || !Enable.getAsJsonPrimitive().isBoolean()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(Enable.getAsBoolean());
    }

    public boolean hasSummonSelfTarget() {
        return hasAlias(SummonUnit, "Caster");
    }

    public boolean hasCasterSummonPosition() {
        return hasAlias(PosTarget, "Caster") && (Summoner == null || hasAlias(Summoner, "Caster"));
    }

    public java.util.OptionalInt getCasterSummonUnitId() {
        if (SummonUnit == null || !SummonUnit.isJsonObject()) return java.util.OptionalInt.empty();
        var selector = SummonUnit.getAsJsonObject();
        var type = selector.get("$type");
        var id = selector.get("SummonUnitID");
        if (type == null || !type.isJsonPrimitive()
                || !"RPG.GameCore.TargetFetchSummonUnit".equals(type.getAsString())
                || !hasAlias(selector.get("Summoner"), "Caster")
                || id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isNumber()
                || id.getAsInt() <= 0) return java.util.OptionalInt.empty();
        return java.util.OptionalInt.of(id.getAsInt());
    }

    private static boolean hasAlias(com.google.gson.JsonElement element, String name) {
        if (element == null || !element.isJsonObject()) return false;
        var object = element.getAsJsonObject();
        var type = object.get("$type");
        var alias = object.get("Alias");
        return type != null && type.isJsonPrimitive() && type.getAsJsonPrimitive().isString()
                && "RPG.GameCore.TargetAlias".equals(type.getAsString())
                && alias != null && alias.isJsonPrimitive() && alias.getAsJsonPrimitive().isString()
                && name.equals(alias.getAsString());
    }

    public boolean hasCasterTarget() {
        if (TargetType == null || !TargetType.isJsonObject()) return false;
        var target = TargetType.getAsJsonObject();
        var type = target.get("$type");
        var alias = target.get("Alias");
        return type != null && type.isJsonPrimitive() && type.getAsJsonPrimitive().isString()
                && "RPG.GameCore.TargetAlias".equals(type.getAsString())
                && alias != null && alias.isJsonPrimitive() && alias.getAsJsonPrimitive().isString()
                && "Caster".equals(alias.getAsString());
    }

    public boolean hasTaskActionTarget() {
        return hasAlias(TargetType, "TaskActionTarget");
    }

    public boolean hasExactCasterTarget() {
        return hasCasterTarget()
                && TargetType.getAsJsonObject().keySet().equals(java.util.Set.of("$type", "Alias"));
    }

    public boolean hasCasterRecoveryTarget() {
        return SpecifyTargetType != null && SpecifyTargetType.isJsonObject()
                && SpecifyTargetType.getAsJsonObject().keySet().equals(java.util.Set.of("$type", "Alias"))
                && hasAlias(SpecifyTargetType, "Caster");
    }

    public boolean hasCasterSummonerTarget() {
        return hasAlias(TargetType, "CasterSummoner");
    }

    public String getPredicateType() {
        if (Predicate == null || !Predicate.isJsonObject()) return "";
        var type = Predicate.getAsJsonObject().get("$type");
        return type != null && type.isJsonPrimitive() && type.getAsJsonPrimitive().isString()
                ? type.getAsString() : "";
    }

    public record CasterBuffPredicate(int buffId, boolean inverse) {}

    public java.util.Optional<CasterBuffPredicate> getCasterBuffPredicate() {
        if (!"RPG.GameCore.ByCompareCarryMazebuff".equals(getPredicateType())) {
            return java.util.Optional.empty();
        }
        var predicate = Predicate.getAsJsonObject();
        var id = predicate.get("BuffID");
        var inverse = predicate.get("Inverse");
        if (!hasAlias(predicate.get("TargetType"), "Caster")
                || id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isNumber()
                || id.getAsInt() <= 0 || (inverse != null
                && (!inverse.isJsonPrimitive() || !inverse.getAsJsonPrimitive().isBoolean()))) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new CasterBuffPredicate(id.getAsInt(), inverse != null && inverse.getAsBoolean()));
    }
}
