package emu.lunarcore.data.excel;

import com.google.gson.JsonElement;
import emu.lunarcore.data.resource.MultiKeyGameResource;
import emu.lunarcore.data.resource.ResourceType;
import java.util.List;
import java.util.OptionalDouble;
import lombok.Getter;

/** Skill parameters preserve their source units; consumers must interpret the declared action. */
@Getter
@ResourceType(
    name = {"AvatarSkillConfig.json", "AvatarSkillConfigLD.json"},
    optionalNames = {"AvatarSkillConfigLD.json"},
    loadPriority = ResourceType.LoadPriority.HIGH)
public class AvatarSkillParameterExcel extends MultiKeyGameResource {
  private int SkillID;
  private int Level;
  private int MaxLevel;
  private String SkillTriggerKey;
  private String AttackType;
  private List<JsonElement> ParamList;

  @Override
  public int getPrimaryKey() {
    return SkillID;
  }

  @Override
  public int getSecondaryKey() {
    return Level;
  }

  public OptionalDouble getParameter(int index) {
    if (ParamList == null || index < 0 || index >= ParamList.size()) return OptionalDouble.empty();
    var parameter = ParamList.get(index);
    if (parameter == null || !parameter.isJsonObject()) return OptionalDouble.empty();
    var value = parameter.getAsJsonObject().get("Value");
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      return OptionalDouble.empty();
    }
    double number = value.getAsDouble();
    return Double.isFinite(number) ? OptionalDouble.of(number) : OptionalDouble.empty();
  }
}
