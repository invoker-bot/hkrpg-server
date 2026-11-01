package emu.lunarcore.data.excel;

import emu.lunarcore.data.resource.GameResource;
import emu.lunarcore.data.resource.ResourceType;
import lombok.Getter;

/** Album type is resource authored; old resource bundles may omit the split group table. */
@Getter
@ResourceType(name = {"BackGroundMusicGroup.json"}, optionalNames = {"BackGroundMusicGroup.json"})
public class BackGroundMusicGroupExcel extends GameResource {
    private int ID;
    private String Type;

    @Override public int getId() { return ID; }
}
