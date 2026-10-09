package emu.lunarcore.data.excel;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.resource.ResourceType;
import emu.lunarcore.data.resource.ResourceType.LoadPriority;

@ResourceType(name = "AvatarSkillTreeConfigTrial.json", loadPriority = LoadPriority.LOW)
public final class TrialActorSkillTreeExcel extends AvatarSkillTreeExcel {
  @Override
  protected AvatarExcel actorResource() {
    return GameData.getTrialActorExcelMap().get(getAvatarID());
  }
}
