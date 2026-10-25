package emu.lunarcore.data.excel;

import emu.lunarcore.data.resource.GameResource;
import emu.lunarcore.data.resource.ResourceType;
import lombok.Getter;

@Getter
@ResourceType(name = {"PetConfig.json"})
public class PetExcel extends GameResource {
    @com.google.gson.annotations.SerializedName(value = "PetID", alternate = "PBLDLDIEFNC")
    private int PetID;
    @com.google.gson.annotations.SerializedName(value = "PetItemID", alternate = "HHDHIDNHCBI")
    private int PetItemID;
    @com.google.gson.annotations.SerializedName(value = "SummonUnitID", alternate = "FNBGMDDOHEA")
    private int SummonUnitID;

    @Override
    public int getId() {
        return PetID;
    }

}
