package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.inventory.GameItem;
import java.util.function.IntUnaryOperator;

/** Validate every crossed level before consuming materials, including the resulting level. */
public final class EquipmentExperience {
  public record Progress(int level, int exp, int applied, int remaining) {}

  public static Progress prepare(GameItem item, int amount) {
    if (item == null || item.getExcel() == null || amount <= 0) return null;
    int minimum, maximum;
    IntUnaryOperator curve;
    if (item.getExcel().isEquipment()) {
      var promotion = GameData.getEquipmentPromotionExcelMap().get(item.getItemId(), item.getPromotion());
      if (promotion == null) return null;
      minimum = 1; maximum = promotion.getMaxLevel();
      int type = item.getExcel().getEquipmentExcel().getExpType();
      curve = level -> GameData.getEquipmentExpRequired(type, level);
    } else if (item.getExcel().isRelic()) {
      minimum = 0; maximum = item.getExcel().getRelicExcel().getMaxLevel();
      int type = item.getExcel().getRelicExcel().getExpType();
      curve = level -> GameData.getRelicExpRequired(type, level);
    } else return null;
    int level = item.getLevel(), exp = item.getExp(), remaining = amount;
    if (level < minimum || maximum < level || maximum > 1000 || exp < 0) return null;
    if (level == maximum) return exp == 0 ? new Progress(level, 0, 0, amount) : null;
    while (level < maximum) {
      int required = curve.applyAsInt(level);
      if (required <= 0 || exp >= required) return null;
      if (remaining == 0) break;
      int gain = Math.min(remaining, required - exp);
      exp += gain; remaining -= gain;
      if (exp == required) { level++; exp = 0; }
    }
    return new Progress(level, exp, amount - remaining, remaining);
  }

  private EquipmentExperience() {}
}
