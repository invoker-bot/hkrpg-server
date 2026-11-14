package emu.lunarcore.game.shop;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.enums.*;
import emu.lunarcore.game.inventory.Inventory;
import emu.lunarcore.game.player.Player;

/** Additional catalog collectibles stored in the ordinary item ledger. */
public final class ShopItems {
  /** These collectible exchange tokens have PileLimit=1 despite shop prices of 2..200. */
  public static int stackLimit(ItemExcel item) {
    return item.getId() == 140430 || item.getId() == 140531 ? 99999 : item.getPileLimit();
  }
  public static boolean storedVirtual(int id) {
    return id == 61 || id == 281001 || id == 281036 || id == 281038;
  }
  public static boolean collectible(ItemExcel item) {
    return item != null && item.getItemMainType() == ItemMainType.Usable
        && switch (item.getItemSubType()) {
          case MusicAlbum, PersonalCard, PamSkin, RogueMedal -> true;
          default -> false;
        };
  }
  public static boolean unique(ItemExcel item) {
    return item.getItemMainType() == ItemMainType.Pet || collectible(item)
        || switch (item.getItemSubType()) {
          case AvatarSkin, HeadIcon, HeadIconFrame, PhoneTheme, PhoneCase, ChatBubble, PlayerOutfit -> true;
          default -> false;
        };
  }
  public static boolean owned(Player p, ItemExcel item) {
    int id=item.getId();
    var u=p.getUnlocks();
    return switch (item.getItemSubType()) {
      case AvatarSkin -> u.getAvatarSkins().contains(id);
      case HeadIcon -> u.getHeadIcons().contains(id);
      case HeadIconFrame -> u.getHeadIconFrames().contains(id);
      case PhoneTheme -> u.getPhoneThemes().contains(id);
      case PhoneCase -> u.getPhoneCases().contains(id);
      case ChatBubble -> u.getChatBubbles().contains(id);
      // Older saves may retain the selected Pam skin without a separate item entry.
      // The default uniform is owned even when it has never appeared in inventory.
      case PamSkin -> id == 252000 || id == p.getCurrentPamSkinId()
          || p.getInventory().getMaterialByItemId(id) != null;
      case NormalPet -> GameData.getPetExcelMap().values().stream()
          .anyMatch(e -> e.getPetItemID()==id && u.getPets().contains(e.getPetID()));
      default -> p.getInventory().getMaterialByItemId(id)!=null;
    };
  }
  public static boolean deliverable(ItemExcel item) {
    if (item == null) return false;
    if (Inventory.isStackType(item)) return true;
    return switch(item.getItemMainType()) {
      case Virtual -> item.getId()==1 || item.getId()==2 || item.getId()==3;
      case Equipment -> item.getEquipmentExcel()!=null;
      case Relic -> item.getRelicExcel()!=null;
      case AvatarCard -> GameData.getAvatarExcelMap().get(item.getId())!=null;
      case Pet -> GameData.getPetExcelMap().values().stream().anyMatch(p -> p.getPetItemID()==item.getId());
      case Usable -> switch(item.getItemSubType()) {
        case AvatarSkin, HeadIcon, HeadIconFrame, PhoneTheme, PhoneCase, ChatBubble -> true;
        default -> false;
      };
      default -> false;
    };
  }
  private ShopItems() {}
}
