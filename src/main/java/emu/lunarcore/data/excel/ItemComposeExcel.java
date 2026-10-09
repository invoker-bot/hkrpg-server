package emu.lunarcore.data.excel;

import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.data.resource.GameResource;
import emu.lunarcore.data.resource.ResourceType;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.List;
import lombok.Getter;

@Getter
@ResourceType(name = {"ItemComposeConfig.json"})
public class ItemComposeExcel extends GameResource {
  private int ID;
  private int ItemID;
  private FormulaType FormulaType;
  private List<ItemParam> MaterialCost;
  private IntOpenHashSet SpecialMaterialCost;
  private int SpecialMaterialCostNumber;
  private int CoinCost;
  private int WorldLevelRequire;
  private IntOpenHashSet RelicList;
  private int FormulaRequire;
  private int MaxCount;
  private String LimitType;
  private List<Integer> LimitTypeParam;
  private int LimitValue;
  private String FuncType;

  @Override
  public int getId() {
    return ID;
  }

  public enum FormulaType {
    Unknown,
    Normal,
    Sepcial,
    SelectedRelic;
  }
}
