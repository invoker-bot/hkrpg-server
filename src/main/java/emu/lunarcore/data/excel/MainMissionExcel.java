package emu.lunarcore.data.excel;

import emu.lunarcore.data.resource.GameResource;
import emu.lunarcore.data.resource.ResourceType;
import lombok.Getter;

@Getter
@ResourceType(name = {"MainMission.json"})
public class MainMissionExcel extends GameResource {
    private int MainMissionID;
    private int RewardID;
    // Reward previews include child and mutually exclusive branch rewards, not a payout request.
    private java.util.List<Integer> SubRewardList = java.util.List.of();
    private int NextTrackMainMission;
    private String Type;
    @com.google.gson.annotations.SerializedName("IsInRaid") private boolean inRaid;
    private String TakeOperation = "And";
    private java.util.List<Gate> TakeParam = java.util.List.of();
    private String BeginOperation = "And";
    private java.util.List<Gate> BeginParam = java.util.List.of();
    public record Gate(String Type, int Value) {}

    @Override
    public int getId() {
        return MainMissionID;
    }
}
