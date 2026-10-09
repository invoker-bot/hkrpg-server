package emu.lunarcore.data.excel;

import emu.lunarcore.data.resource.GameResource;
import emu.lunarcore.data.resource.ResourceType;
import java.util.List;
import lombok.Getter;

@Getter
@ResourceType(name = {"FinishWay.json"})
public class FinishWayExcel extends GameResource {
  private int ID;
  private String FinishType = "Unknown";
  private int Progress;
  private int ParamInt1;
  private int ParamInt2;
  private int ParamInt3;
  private String ParamType = "Unknown";
  private String ParamStr1 = "";
  private List<Integer> ParamIntList = List.of();
  private List<com.google.gson.JsonElement> ParamItemList = List.of();
  private boolean IsBackTrack;

  @Override
  public int getId() {
    return ID;
  }

  public boolean isType(String name, int id) {
    return name.equals(FinishType) || Integer.toString(id).equals(FinishType);
  }
}
