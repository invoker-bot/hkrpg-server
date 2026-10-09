package emu.lunarcore.data.excel;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.resource.ResourceType;

/** Plot-only actors have a separate catalog and cannot become owned formal characters. */
@ResourceType(name = "AvatarConfigTrial.json")
public final class TrialActorExcel extends AvatarExcel {
  @Override
  public AvatarPromotionExcel getPromotionData(int promotion) {
    return GameData.getTrialActorPromotionExcelMap().get(getId(), promotion);
  }

  public boolean supportsTrial(SpecialAvatarExcel config) {
    var promotion = getPromotionData(config.getPromotion());
    return config.getAvatarID() == getId()
        && config.getPromotion() >= 0
        && config.getPromotion() <= getMaxPromotion()
        && promotion != null
        && promotion.getAvatarID() == getId()
        && promotion.getPromotion() == config.getPromotion()
        && config.getLevel() > 0
        && config.getLevel() <= promotion.getMaxLevel();
  }
}
