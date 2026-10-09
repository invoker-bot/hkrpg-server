package emu.lunarcore.data.config;

import lombok.Getter;

/**
 *  Original name: LevelGroupMissionConditionSet
 */
@Getter
public class LoadConditionInfo {
    private LevelGroupMissionCondition[] Conditions;
    private String Operation = "And";
    private boolean DelayToLevelReload;
}
