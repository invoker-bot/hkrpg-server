package emu.lunarcore.data.config;

import lombok.Getter;
import java.util.List;

/**
 *  Original name: LevelMonsterInfo
 */
@Getter
public class MonsterInfo extends ObjectInfo {
    private int NPCMonsterID;
    private int EventID;
    private int FarmElementID;
    private boolean IsClientOnly;
    private boolean LoadOnInitial = true;
    private List<String> TagList = List.of();
}
