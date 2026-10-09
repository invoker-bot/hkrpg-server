package emu.lunarcore.data.excel;

import emu.lunarcore.data.resource.GameResource;
import emu.lunarcore.data.resource.ResourceType;
import lombok.Getter;

/** Achievement identity and its quest come from AchievementData, not QuestType. */
@Getter
@ResourceType(name = {"AchievementData.json"})
public class AchievementExcel extends GameResource {
    private int AchievementID;
    private int QuestID;
    @Override public int getId() { return AchievementID; }
}
