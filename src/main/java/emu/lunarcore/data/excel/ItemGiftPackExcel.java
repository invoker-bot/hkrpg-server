package emu.lunarcore.data.excel;

import emu.lunarcore.data.resource.*;
import java.util.List;
import lombok.Getter;

@Getter
@ResourceType(name = "ItemGiftPackData.json", optionalNames = "ItemGiftPackData.json")
public class ItemGiftPackExcel extends MultiKeyGameResource {
  private int ID, GroupID;
  private List<Integer> RewardList;
  public int getPrimaryKey() { return ID; }
  public int getSecondaryKey() { return GroupID; }
}
