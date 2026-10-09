package emu.lunarcore.data.excel;

import emu.lunarcore.data.resource.GameResource;
import emu.lunarcore.data.resource.ResourceType;
import lombok.Getter;

@Getter
@ResourceType(name = {"MonsterConfig.json"})
public class MonsterExcel extends GameResource {
    private int MonsterID;
    private long MonsterName;
    private java.util.List<emu.lunarcore.game.enums.DamageType> StanceWeakList = java.util.List.of();

    @Override
    public int getId() {
        return MonsterID;
    }
}
