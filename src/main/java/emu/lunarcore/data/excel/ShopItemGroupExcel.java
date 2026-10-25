package emu.lunarcore.data.excel;

import emu.lunarcore.data.resource.*;
import lombok.Getter;

@Getter
@ResourceType(name = "ShopItemGroupConfig.json", optionalNames = "ShopItemGroupConfig.json")
public class ShopItemGroupExcel extends MultiKeyGameResource {
  private int GroupID, ItemID, RotateOrder;
  private String GroupType;
  public int getPrimaryKey() { return GroupID; }
  public int getSecondaryKey() { return RotateOrder; }
}
