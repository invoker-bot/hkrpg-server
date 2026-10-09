package emu.lunarcore.data.excel;

import emu.lunarcore.data.resource.MultiKeyGameResource;
import emu.lunarcore.data.resource.ResourceType;
import lombok.Getter;

@Getter
@ResourceType(name = {"MazeBuff.json", "RogueMazeBuff.json", "AvatarMazeBuff.json", "AvatarMazeBuffLD.json"},
        optionalNames = {"RogueMazeBuff.json", "AvatarMazeBuff.json", "AvatarMazeBuffLD.json"})
public class MazeBuffExcel extends MultiKeyGameResource {
    private int ID;
    private int Lv;
    private int LvMax;
    private String InBattleBindingType;
    private String InBattleBindingKey;
    private String ModifierName;
    private String MazeBuffType;
    private String UseType;
    private java.util.List<Parameter> ParamList;

    public record Parameter(double Value) {}

    @Override
    public int getPrimaryKey() {
        return ID;
    }

    @Override
    public int getSecondaryKey() {
        return Lv;
    }

    public int getBuffId() {
        return ID;
    }
}
