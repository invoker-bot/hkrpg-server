package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.avatar.GameAvatar;

/** Shared character XP curve. Prepare every crossed level before charging or saving anything. */
public final class AvatarExperience {
  public record Progress(int level, int exp, int applied, int remaining) {}

  public static Progress prepare(GameAvatar avatar, int amount) {
    if (avatar == null || avatar.getExcel() == null || amount <= 0) return null;
    var excel = avatar.getExcel();
    var promotion = GameData.getAvatarPromotionExcelMap().get(excel.getId(), avatar.getPromotion());
    if (promotion == null) return null;
    int level = avatar.getLevel(), exp = avatar.getExp(), maximum = promotion.getMaxLevel();
    if (level < 1 || maximum < level || exp < 0 || maximum > 1000) return null;
    if (level == maximum) return exp == 0 ? new Progress(level, 0, 0, amount) : null;
    int remaining = amount;
    while (level < maximum) {
      int required = GameData.getAvatarExpRequired(excel.getExpGroup(), level);
      if (required <= 0 || exp >= required) return null;
      if (remaining == 0) break;
      int gained = Math.min(remaining, required - exp);
      exp += gained;
      remaining -= gained;
      if (exp == required) { level++; exp = 0; }
    }
    return new Progress(level, exp, amount - remaining, remaining);
  }

  private AvatarExperience() {}
}
