package emu.lunarcore.data.config;

import com.google.gson.annotations.SerializedName;

import lombok.Getter;

@Getter
public class MissionTaskInfo {
    private transient String sourceDefinition = "";

    /** Preserve the original task shape before typed deserialization discards unknown actions. */
    public void retainSourceDefinition(com.google.gson.JsonElement source) {
        sourceDefinition = source == null ? "" : source.toString();
    }

    /** Only literal top-level battle tasks are supported; this is not a script interpreter. */
    public boolean declaresBattle(int eventId) {
        if (eventId <= 0 || !"Mission".equals(type) || onStartSequece == null) return false;
        for (var sequence : onStartSequece) {
            if (sequence == null || sequence.taskList == null) continue;
            for (var task : sequence.taskList) {
                if (task != null && "RPG.GameCore.TriggerBattle".equals(task.type)
                        && literal(task.eventId) == eventId
                        && literal(task.groupId) > 0
                        && literal(task.battleAreaId) > 0) return true;
            }
        }
        return false;
    }

    private static int literal(com.google.gson.JsonElement value) {
        if (value == null || !value.isJsonObject()) return 0;
        var object = value.getAsJsonObject();
        var dynamic = object.get("IsDynamic");
        var fixed = object.get("FixedValue");
        if (dynamic == null || !dynamic.isJsonPrimitive() || !dynamic.getAsJsonPrimitive().isBoolean()
                || dynamic.getAsBoolean() || fixed == null || !fixed.isJsonObject()) return 0;
        var number = fixed.getAsJsonObject().get("Value");
        if (number == null || !number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber()) return 0;
        try { return Math.max(0, number.getAsBigDecimal().intValueExact()); }
        catch (ArithmeticException | NumberFormatException ex) { return 0; }
    }
    // @SerializedName("OnInitSequece")
    // private OnInitSequece[] onInitSequece;

    @SerializedName("OnStartSequece")
    private OnStartSequece[] onStartSequece;

    @SerializedName("Type")
    private String type;

    @Getter
    public static class OnStartSequece {
        @SerializedName("Order")
        private int order;

        @SerializedName("TaskList")
        private TaskList[] taskList;

        @Getter
        public static class TaskList {
            @SerializedName("EventID") private com.google.gson.JsonElement eventId;
            @SerializedName("GroupID") private com.google.gson.JsonElement groupId;
            @SerializedName("BattleAreaID") private com.google.gson.JsonElement battleAreaId;
            @SerializedName("$type")
            private String type;

            //@SerializedName("ValueSource")
            //private Object valueSource;

            @SerializedName("PerformanceType")
            private String PerformanceType;

            @SerializedName("PerformanceID")
            private int performanceId;

            @SerializedName("Key")
            private String key;

            @SerializedName("EntranceID")
            private int entranceId;

            // need dynamic class
            //@SerializedName("GroupID")
            //private int groupId;

            @SerializedName("AnchorID")
            private int anchorId;
        }
    }
}
