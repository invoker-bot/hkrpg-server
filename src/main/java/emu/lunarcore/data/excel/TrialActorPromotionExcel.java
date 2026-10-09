package emu.lunarcore.data.excel;

import emu.lunarcore.data.resource.ResourceType;
import emu.lunarcore.data.resource.ResourceType.LoadPriority;

@ResourceType(name = "AvatarPromotionConfigTrial.json", loadPriority = LoadPriority.HIGHEST)
public final class TrialActorPromotionExcel extends AvatarPromotionExcel {}
