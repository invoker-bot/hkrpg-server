package org.hkrpg.server.gameplay;

import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.enums.*;

/**
 * Authored ordinary books are actual bounded bag stacks; reading and collection UI are separate.
 */
public final class BookItems {
  public static boolean storable(ItemExcel item) {
    return item != null
        && item.getId() > 0
        && item.getItemMainType() == ItemMainType.Usable
        && item.getItemSubType() == ItemSubType.Book
        && item.getUseMethod() == ItemUseMethod.AutoConversionItem
        && item.getUseDataID() == 0
        && item.getPileLimit() > 0
        && item.getReturnItemIDList() != null
        && item.getReturnItemIDList().isEmpty();
  }

  private BookItems() {}
}
