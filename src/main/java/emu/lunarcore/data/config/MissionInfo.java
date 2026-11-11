package emu.lunarcore.data.config;

import com.google.gson.annotations.SerializedName;

import lombok.Getter;
import lombok.Setter;

@Getter
public class MissionInfo {
    @SerializedName("MainMissionID")
    private int mainMissionID;

    @SerializedName("StartSubMissionList")
    private int[] startSubMissionList = new int[0];

    @SerializedName("FinishSubMissionList")
    private int[] finishSubMissionList = new int[0];

    @SerializedName("SubMissionList")
    private SubMissionInfo[] subMissionList = new SubMissionInfo[0];

    @SerializedName("MissionCustomValueList")
    private MissionCustomValueInfo[] missionCustomValueList;

    @SerializedName("SubMissionRelationList")
    private java.util.List<SubMissionRelationConfig> subMissionRelationList = java.util.List.of();

    @SerializedName("CustomValueSaveIndexList")
    private String[] missionCustomValueSaveIndexList;

    @Getter
    @Setter
    public static class SubMissionInfo {
        @SerializedName("ID")
        private int id;

        @SerializedName("MainMissionID")
        private int mainMissionID;

        @SerializedName("TakeType")
        private String takeType;

        @SerializedName("TakeParamIntList")
        private Integer[] takeParamIntList = new Integer[0];

        @SerializedName("TakeParamStrList")
        private String[] takeParamStrList = new String[0];

        @SerializedName("ParamStr1") private String paramStr1 = "";
        @SerializedName("ParamIntList") private Integer[] paramIntList = new Integer[0];
        @SerializedName("ParamItemList") private java.util.List<MissionItemCost> paramItemList = java.util.List.of();
        @SerializedName("ParamType") private String paramType;
        @SerializedName("Progress") private int progress = 1;
        @SerializedName("IsBackTrack") private boolean backTrack;

        @SerializedName("FinishType")
        private String finishType;

        @SerializedName("ParamInt1")
        private int paramInt1;

        @SerializedName("ParamInt2")
        private int paramInt2;

        @SerializedName("ParamInt3")
        private int paramInt3;

        @SerializedName("LevelFloorID")
        private int levelFloorID;

        @SerializedName("LevelPlaneID") private int levelPlaneID;
        @SerializedName("MazePlaneID") private int mazePlaneID;
        @SerializedName("MazeFloorID") private int mazeFloorID;
        @SerializedName("IgnoreVerseParamList") private java.util.List<String> ignoreVerseParamList = java.util.List.of();
        @SerializedName("StoryLineIDList") private java.util.List<Integer> storyLineIDList = java.util.List.of();
        @SerializedName("StoryLineID") private int storyLineID;
        @SerializedName("GroupIDList") private java.util.List<Integer> groupIDList = java.util.List.of();
        @SerializedName("RequiredNPCSeriesIDList") private java.util.List<Integer> requiredNPCSeriesIDList = java.util.List.of();
        @SerializedName("SubRewardID") private int subRewardID;
        @SerializedName("CustomValueReward") private CustomValueReward[] customValueReward = new CustomValueReward[0];

        @SerializedName("MissionJsonPath") private String missionJsonPath = "";
        @SerializedName("FinishActionList") private FinishAction[] finishActionList = new FinishAction[0];
        @SerializedName("CustomValueList") private String[] customValueList = new String[0];

        @Setter private MissionTaskInfo task;
        private transient org.hkrpg.server.gameplay.StoryTrialReplacement.Plan storyTrialTask;
        private transient boolean unavailableNativeScript;
    }

    public record MissionItemCost(int ItemID, int ItemNum) {}

    public record SubMissionRelationConfig(
        int MonitorSubMissionID, java.util.List<Integer> MonitoredSubMissionIDList) {}

    public record CustomValueReward(Integer Index, String Name, int Value, int RewardID) {}

    @Getter
    public static class FinishAction {
        @SerializedName("FinishActionType") private String type;
        @SerializedName("FinishActionPara") private int[] params = new int[0];
        @SerializedName("FinishActionParaString") private String[] strings = new String[0];
    }

    @Getter
    public static class MissionCustomValueInfo {
        @SerializedName("Index")
        private Integer index = 0;

        @SerializedName("Name")
        private String name;

        @SerializedName("Type")
        private String type;

        @SerializedName("ValidValueParamList")
        private Integer[] validValueParamList;
        @SerializedName("isRange") private boolean range;
    }


    @Getter
    public static class MissionSimpleInfo {
        private int mainMissionID;
        private int[] startSubMissionList;
        private int[] finishSubMissionList;
        private SubMissionInfo[] subMissionList;
        private MissionCustomValueInfo[] missionCustomValueList;
        private String[] missionCustomValueSaveIndexList;
    }

    /**
     * Finds a sub-mission by its ID.
     *
     * @param id The ID of the sub-mission to find.
     * @return The SubMissionInfo object with the matching ID, or null if not found.
     */
    public SubMissionInfo getSubMissionById(int id) {
        for (SubMissionInfo subMission : subMissionList) {
            if (subMission.getId() == id) {
                return subMission;
            }
        }
        return null; // Return null if no matching sub-mission is found
    }
}
