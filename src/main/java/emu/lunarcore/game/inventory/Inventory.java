package emu.lunarcore.game.inventory;

import emu.lunarcore.GameConstants;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.data.common.ItemParam.ItemParamType;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.avatar.AvatarStorage;
import emu.lunarcore.game.avatar.BaseAvatar;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.enums.ItemSubType;
import emu.lunarcore.game.inventory.tabs.EquipInventoryTab;
import emu.lunarcore.game.inventory.tabs.InventoryTab;
import emu.lunarcore.game.inventory.tabs.InventoryTabType;
import emu.lunarcore.game.inventory.tabs.MaterialInventoryTab;
import emu.lunarcore.game.player.BasePlayerManager;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.send.PacketPlayerSyncScNotify;
import emu.lunarcore.server.packet.send.PacketScenePlaneEventScNotify;
import emu.lunarcore.util.Utils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

public class Inventory extends BasePlayerManager {
    private final Int2ObjectMap<GameItem> store;
    private final Int2ObjectMap<InventoryTab> inventoryTypes;
    private int nextInternalUid;

    public Inventory(Player player) {
        super(player);

        this.store = new Int2ObjectOpenHashMap<>();
        this.inventoryTypes = new Int2ObjectOpenHashMap<>();

        this.createTab(InventoryTabType.EQUIPMENT, new EquipInventoryTab(GameConstants.INVENTORY_MAX_EQUIPMENT));
        this.createTab(InventoryTabType.RELIC, new EquipInventoryTab(GameConstants.INVENTORY_MAX_RELIC));
        this.createTab(InventoryTabType.MATERIAL, new MaterialInventoryTab(GameConstants.INVENTORY_MAX_MATERIAL));
    }

    public AvatarStorage getAvatarStorage() {
        return this.getPlayer().getAvatars();
    }

    public Int2ObjectMap<GameItem> getItems() {
        return store;
    }

    public int getNextItemInternalUid() {
        return ++nextInternalUid;
    }

    public boolean hasItemIdCapacity(int count) {
        synchronized (getPlayer()) {
            return count > 0 && nextInternalUid >= 0
                && (long) nextInternalUid + count <= Integer.MAX_VALUE;
        }
    }

    // Inventory tabs

    public InventoryTab getTabByItemType(ItemMainType type) {
        return getTab(type.getTabType());
    }

    public InventoryTab getTab(InventoryTabType type) {
        if (type == null || type == InventoryTabType.NONE) {
            return null;
        }

        return this.inventoryTypes.get(type.getVal());
    }

    public void createTab(InventoryTabType type, InventoryTab tab) {
        this.inventoryTypes.put(type.getVal(), tab);
    }

    // Items

    /**
     * Returns an item using its internal uid
     * @param uid
     * @return
     */
    public synchronized GameItem getItemByUid(int uid) {
        return this.getItems().get(uid);
    }

    public synchronized GameItem getMaterialByItemId(int id) {
        return this.getTab(InventoryTabType.MATERIAL).getItemById(id);
    }

    public GameItem getItemByParam(ItemParam param) {
        if (param.getType() == ItemParamType.PILE) {
            return this.getMaterialByItemId(param.getId());
        } else if (param.getType() == ItemParamType.UNIQUE) {
            return this.getItemByUid(param.getId());
        }

        return null;
    }

    // Add/Remove items

    public boolean addItem(int itemId, int count) {
        return addItem(itemId, count, false);
    }

    public boolean addItem(int itemId, int count, boolean showHint) {
        ItemExcel itemExcel = GameData.getItemExcelMap().get(itemId);

        if (itemExcel == null) {
            return false;
        }

        return addItem(itemExcel, count, showHint);
    }

    public boolean addItem(ItemExcel itemExcel, int count, boolean showHint) {
        GameItem item = new GameItem(itemExcel, count);
        return addItem(item, showHint);
    }

    public boolean addItem(GameItem item) {
        return addItem(item, false);
    }

    public boolean addItem(GameItem item, boolean showHint) {
        synchronized (getPlayer()) {
            return addItemLocked(item, showHint);
        }
    }

    private boolean addItemLocked(GameItem item, boolean showHint) {
        // Set excel in case its missing
        if (item.getExcel() == null) {
            item.setExcel(GameData.getItemExcelMap().get(item.getItemId()));
        }

        // Put item into inventory
        var delivery = deliver(item);
        GameItem result = delivery.stored();

        if (result != null && delivery.amount() > 0) {
            // Send packets
            if (item.getExcel().getItemMainType().getTabType() != InventoryTabType.NONE) {
                getPlayer().sendPacket(new PacketPlayerSyncScNotify(result));
            }
            if (showHint) {
                getPlayer().sendPacket(new PacketScenePlaneEventScNotify(stackItem(result) ? new GameItem(result.getExcel(),delivery.amount()) : result));
            }

            // Success
            getPlayer().getMissionManager().onItemsAcquired(java.util.Map.of(item.getItemId(),delivery.amount()));
            return true;
        }

        return false;
    }

    /**
     * Adds all items from this list to the inventory.
     * @param items List of items to add
     * @return List of items that were added.
     */
    public List<GameItem> addItems(List<GameItem> items) {
        return addItems(items, false);
    }

    /**
     * Adds all items from this list to the inventory.
     * @param items List of items to add
     * @param showHint Whether or not to notify the player that items were added
     * @return List of items that were added.
     */
    public List<GameItem> addItems(List<GameItem> items, boolean showHint) {
        synchronized (getPlayer()) {
            return addItemsLocked(items, showHint);
        }
    }

    private List<GameItem> addItemsLocked(List<GameItem> items, boolean showHint) {
        // Init results
        List<GameItem> results = new ArrayList<>(items.size());
        List<GameItem> updates = new ArrayList<>(items.size());
        var acquired = new java.util.HashMap<Integer,Integer>();

        // Sanity check
        if (items.size() == 0) return results;

        // Add to inventory
        for (GameItem item : items) {
            var delivery = deliver(item);
            GameItem result = delivery.stored();
            if (result != null && delivery.amount() > 0) {
                updates.add(result);
                results.add(stackItem(result) ? new GameItem(result.getExcel(),delivery.amount()) : result);
                acquired.merge(item.getItemId(),delivery.amount(),Math::addExact);
            }
        }

        // Send packet (update)
        if (results.size() > 0) {
            getPlayer().sendPacket(new PacketPlayerSyncScNotify(updates));
            if (showHint) {
                getPlayer().sendPacket(new PacketScenePlaneEventScNotify(results));
            }
        }

        if (!acquired.isEmpty()) getPlayer().getMissionManager().onItemsAcquired(acquired);

        return results;
    }

    private record Delivery(GameItem stored, int amount) {}

    private static boolean stackItem(GameItem item) {
        return isStackType(item.getExcel());
    }

    public static boolean isStackType(ItemExcel excel) {
        var type = excel.getItemMainType();
        return type == ItemMainType.Material || type == ItemMainType.Mission
            || org.hkrpg.server.gameplay.BookItems.storable(excel)
            || (type == ItemMainType.Usable && (excel.getItemSubType() == ItemSubType.Food
                || excel.getItemSubType() == ItemSubType.Gift
                || excel.getItemSubType() == ItemSubType.PlayerOutfit
                || excel.getItemSubType() == ItemSubType.Formula
                    && excel.getUseMethod() == emu.lunarcore.game.enums.ItemUseMethod.Recipe));
    }

    private synchronized Delivery deliver(GameItem item) {
        if (item == null || item.getExcel() == null || item.getCount() <= 0) return new Delivery(null,0);
        boolean stack = stackItem(item);
        int before = stack && getMaterialByItemId(item.getItemId()) != null
            ? getMaterialByItemId(item.getItemId()).getCount() : 0;
        var stored = putItem(item);
        return new Delivery(stored,stored == null ? 0 : stack ? Math.max(stored.getCount()-before,0) : item.getCount());
    }

    /**
     * Adds all items from this item param map to the inventory.
     * @param map A map of item ids/amounts
     * @return List of items that were added.
     */
    public List<GameItem> addItems(ItemParamMap map) {
        return addItems(map, false);
    }

    public List<GameItem> addItems(ItemParamMap map, boolean showHint) {
        return grantItems(map,showHint).items();
    }

    public record GrantResult(List<GameItem> items, boolean complete) {}

    public GrantResult grantItems(ItemParamMap map, boolean showHint) {
        var owned = new ItemParamMap();
        owned.add(map);
        map = owned;
        // Convert player exp to coins if player is max level
        if (this.getPlayer().getLevel() >= GameConstants.MAX_TRAILBLAZER_LEVEL) {
            if (map.get(GameConstants.TRAILBLAZER_EXP_ID) > 0) {
                int exp = map.get(GameConstants.TRAILBLAZER_EXP_ID);
                map.add(GameConstants.MATERIAL_COIN_ID, exp * 10);
                map.remove(GameConstants.TRAILBLAZER_EXP_ID);
            }
        }

        var proposed=map.toItemList();
        var expected=new ItemParamMap();
        expected.add(map);
        var results=addItems(proposed,showHint);
        var received=new ItemParamMap();
        for(var item:results)received.add(item.getItemId(),item.getCount());
        return new GrantResult(results,expected.equals(received));
    }

    private synchronized GameItem putItem(GameItem item) {
        // Dont add items that dont have a valid item definition.
        if (item.getExcel() == null) {
            return null;
        }

        // Add item to inventory store
        ItemMainType type = item.getExcel().getItemMainType();
        ItemSubType subType = item.getExcel().getItemSubType();
        InventoryTab tab = getTabByItemType(type);

        // Add
        switch (type) {
            case Equipment:
            case Relic:
                if (tab.getSize() >= tab.getMaxCapacity()) {
                    return null;
                }
                // Duplicates cause problems
                item.setCount(1);
                // Adds to inventory
                this.putItem(item, tab);
                // Set ownership and save to database
                item.save();
                return item;
            case Virtual:
                // Handle
                if (!this.addVirtualItem(item.getItemId(), item.getCount())) return null;
                return item;
            case AvatarCard:
                // Add avatar
                AvatarExcel avatarExcel = GameData.getAvatarExcelMap().get(item.getItemId());
                if (avatarExcel != null && !getPlayer().getAvatars().hasAvatar(avatarExcel.getId())) {
                    getPlayer().addAvatar(new GameAvatar(avatarExcel));
                    return item;
                }
                return null;
            case Pet:
                getPlayer().getUnlocks().addPet(item.getItemId());
                return item;
            case Usable:
                // Add usable
                switch (subType) {
                    case HeadIcon -> {
                        getPlayer().getUnlocks().addHeadIcon(item.getItemId());
                        return item;
                    }
                    case HeadIconFrame -> {
                        getPlayer().getUnlocks().addHeadIconFrame(item.getItemId());
                        return item;
                    }
                    case ChatBubble -> {
                        getPlayer().getUnlocks().addChatBubble(item.getItemId());
                        return item;
                    }
                    case PhoneTheme -> {
                        getPlayer().getUnlocks().addPhoneTheme(item.getItemId());
                        return item;
                    }
                    case AvatarSkin -> {
                        getPlayer().getUnlocks().addAvatarSkin(item.getItemId());
                        return item;
                    }
                    default -> {
                        // Skip
                    }
                }

                // Consumable gifts and food remain in inventory until actually used.
                if (subType != ItemSubType.Food && subType != ItemSubType.Gift
                    && subType != ItemSubType.PlayerOutfit
                    && !org.hkrpg.server.gameplay.BookItems.storable(item.getExcel())
                    && !(subType == ItemSubType.Formula
                        && item.getExcel().getUseMethod() == emu.lunarcore.game.enums.ItemUseMethod.Recipe)) {
                    return null;
                }
                if ((subType == ItemSubType.Gift || subType == ItemSubType.PlayerOutfit || subType == ItemSubType.Formula
                    || org.hkrpg.server.gameplay.BookItems.storable(item.getExcel()))
                    && (item.getExcel().getPileLimit() <= 0 || item.getCount() > item.getExcel().getPileLimit())) return null;
            default:
                if (tab == null) {
                    return null;
                }

                GameItem existingItem = tab.getItemById(item.getItemId());

                if (existingItem == null) {
                    // Item type didnt exist before, we will add it to main inventory map if there is enough space
                    if (tab.getSize() >= tab.getMaxCapacity()) {
                        return null;
                    }
                    // Put item to inventory
                    this.putItem(item, tab);
                    // Set ownership and save to db
                    item.save();
                    return item;
                } else {
                    // Add count to item
                    int amount = Utils.safeAdd(existingItem.getCount(), item.getCount(), item.getExcel().getPileLimit(), 0);
                    if (existingItem.setCount(amount)) {
                        existingItem.save();
                    }
                    return existingItem;
                }
        }
    }

    private synchronized void putItem(GameItem item, InventoryTab tab) {
        // Set owner and internal uid first
        item.setOwner(this.getPlayer());

        // Add if tab exists
        if (tab != null) {
            // Put in item store
            getItems().put(item.getInternalUid(), item);
            // Add to tab
            tab.onAddItem(item);
        }
    }

    private boolean addVirtualItem(int itemId, int count) {
        switch (itemId) {
            case 1: // Stellar Jade
                getPlayer().addHCoin(count);
                break;
            case 2: // Credit
                getPlayer().addSCoin(count);
                break;
            case 3: // Oneiric Shard
                getPlayer().addMCoin(count);
                break;
            case 11: // Trailblaze Power
                getPlayer().addStamina(count);
                break;
            case 22: // Trailblaze EXP
                getPlayer().addExp(count);
                break;
            case GameConstants.ROGUE_TALENT_COIN_ID:
                if (!getPlayer().tryChangeTalentPoints(count)) return false;
                getPlayer().save();
                org.hkrpg.server.protocol.RogueTalent46.syncCurrency(getPlayer());
                break;
            case 33:
            case 34:
                if (!org.hkrpg.server.gameplay.RogueCurrencies.change(getPlayer(), itemId, count)) return false;
                getPlayer().save();
                org.hkrpg.server.protocol.RogueTalent46.syncCurrency(getPlayer());
                break;
            default:
                return false;
        }
        return true;
    }

  public void removeItemsByParams(Collection<ItemParam> items) {
    removeItemsByParams(items, 1);
  }

  public void removeItemsByParams(Collection<ItemParam> items, int multiplier) {
    synchronized (getPlayer()) {
      var consumed = new java.util.HashMap<Integer, Integer>();
      // Sanity
      if (items.size() == 0
          || multiplier <= 0
          || items.stream()
              .anyMatch(
                  p -> p.getCount() < 0 || (long) p.getCount() * multiplier > Integer.MAX_VALUE)) {
        return;
      }

      // Init results and remove items from inventory
      List<GameItem> results = new ArrayList<GameItem>(items.size());

      for (ItemParam param : items) {
        // Remove virtual items first
        if (param.getType() == ItemParamType.PILE) {
          if (param.getId() == GameConstants.MATERIAL_COIN_ID) {
            // Remove credits
            int before = getPlayer().getScoin();
            getPlayer().addSCoin(-param.getCount() * multiplier);
            if (before > getPlayer().getScoin())
              consumed.merge(param.getId(), before - getPlayer().getScoin(), Math::addExact);
            continue;
          } else if (param.getId() == GameConstants.MATERIAL_HCOIN_ID) {
            // Remove credits
            int before = getPlayer().getHcoin();
            getPlayer().addHCoin(-param.getCount() * multiplier);
            if (before > getPlayer().getHcoin())
              consumed.merge(param.getId(), before - getPlayer().getHcoin(), Math::addExact);
            continue;
          }
        }

        // Remove param items
        GameItem item = this.getItemByParam(param);
        if (item == null) continue;

        int before = item.getCount();
        GameItem result = this.deleteItem(item, param.getCount() * multiplier);
        if (result != null) {
          results.add(result);
          consumed.merge(item.getItemId(), before - item.getCount(), Math::addExact);
        }
      }

      // Send packet (update)
      if (results.size() > 0) {
        getPlayer().sendPacket(new PacketPlayerSyncScNotify(results));
      }
      getPlayer().getMissionManager().onItemsConsumed(consumed);
    }
  }

  public void removeItems(Collection<GameItem> items) {
    synchronized (getPlayer()) {
      var consumed = new java.util.HashMap<Integer, Integer>();
      // Sanity
      if (items.size() == 0) {
        return;
      }

      // Init results and remove items from inventory
      List<GameItem> results = new ArrayList<GameItem>(items.size());

      for (GameItem item : items) {
        int before = item.getCount();
        GameItem result = deleteItem(item, item.getCount());
        if (result != null) {
          results.add(result);
          consumed.merge(item.getItemId(), before - item.getCount(), Math::addExact);
        }
      }

      // Send packet (update)
      if (results.size() > 0) {
        getPlayer().sendPacket(new PacketPlayerSyncScNotify(results));
      }
      getPlayer().getMissionManager().onItemsConsumed(consumed);
    }
  }

  public boolean removePileItem(int uid, int count) {
    synchronized (getPlayer()) {
      GameItem item = this.getMaterialByItemId(uid);

      if (item == null) {
        return false;
      }

      return removeItem(item, count);
    }
  }

  public boolean removeUniqueItem(int uid, int count) {
    synchronized (getPlayer()) {
      GameItem item = this.getItemByUid(uid);

      if (item == null) {
        return false;
      }

      return removeItem(item, count);
    }
  }

  public boolean removeItem(GameItem item, int count) {
    synchronized (getPlayer()) {
      int before = item == null ? 0 : item.getCount();
      GameItem result = deleteItem(item, count);

      if (result != null) {
        getPlayer().sendPacket(new PacketPlayerSyncScNotify(result));
        getPlayer()
            .getMissionManager()
            .onItemsConsumed(java.util.Map.of(item.getItemId(), before - item.getCount()));
        return true;
      }

      return false;
    }
  }

  /** Apply a prepared effect before the debit's mission callback can change its target lineup. */
  public boolean consumeForUse(GameItem item, int count, Runnable effect) {
    synchronized (getPlayer()) {
      GameItem result = deleteItem(item, count);
      if (result == null) return false;
      effect.run();
      getPlayer().sendPacket(new PacketPlayerSyncScNotify(result));
      getPlayer().getMissionManager().onItemsConsumed(java.util.Map.of(item.getItemId(), count));
      return true;
    }
  }

  private synchronized GameItem deleteItem(GameItem item, int count) {
    // Sanity check
    if (count <= 0
        || item == null
        || item.getOwnerUid() != getPlayer().getUid()
        || count > item.getCount()
        || this.getItems().get(item.getInternalUid()) != item) {
      return null;
    }

    if (item.getExcel() == null || item.getExcel().isEquippable()) {
      item.setCount(0);
    } else {
      item.setCount(Utils.safeSubtract(item.getCount(), count));
    }

    if (item.getCount() <= 0) {
      // Remove from inventory tab too
      InventoryTab tab = null;
      if (item.getExcel() != null) {
        tab = getTabByItemType(item.getExcel().getItemMainType());

        if (tab != null) {
          tab.onRemoveItem(item);
        }
      }
      // Remove from items map
      getItems().remove(item.getInternalUid());
    }

    // Update in db
    item.save();

    // Returns true on success
    return item;
  }

  // Verifying items

  public boolean hasItem(int itemId, int count) {
    // Sanity check
    if (count == 0) {
      return true;
    } else if (count < 0) {
      // Return false if we are trying to check if the player has a negative amount of this item
      return false;
    }

    // Get item excel
    var excel = GameData.getItemExcelMap().get(itemId);

    if (excel == null) {
      return false;
    }

    boolean result =
        switch (excel.getItemMainType()) {
          case Virtual -> {
            if (itemId == GameConstants.MATERIAL_COIN_ID) {
              // Check credits
              yield this.getPlayer().getScoin() >= count;
            } else if (itemId == GameConstants.MATERIAL_HCOIN_ID) {
              // Check jades
              yield this.getPlayer().getHcoin() >= count;
            } else if (itemId == GameConstants.ROGUE_TALENT_COIN_ID) {
              // Check rogue talents
              yield this.getPlayer().getTalentPoints() >= count;
            } else if (itemId == 33 || itemId == 34) {
              yield org.hkrpg.server.gameplay.RogueCurrencies.balance(this.getPlayer(), itemId)
                  >= count;
            }

            // Not supported
            yield false;
          }
          case AvatarCard -> {
            yield this.getPlayer().getAvatars().hasAvatar(itemId);
          }
          case Usable, Material, Mission -> {
            GameItem item = this.getMaterialByItemId(itemId);
            if (item == null) {
              yield false;
            }

            yield item.getCount() >= count;
          }
          default -> {
            yield false;
          }
        };

        // Player has enough of this item
        return result;
    }

    public boolean hasItems(List<ItemParam> items) {
        return hasItems(items, 1);
    }

    public boolean hasItems(List<ItemParam> items, int multiplier) {
        for (var param : items) {
            // Make sure we have enough of this item
            if (!this.hasItem(param.getId(), param.getCount() * multiplier)) {
                return false;
            }
        }

        return true;
    }

    public boolean hasScoin(int cost) {
        return this.getPlayer().getScoin() >= cost;
    }

    public boolean hasHcoin(int cost) {
        return this.getPlayer().getHcoin() >= cost;
    }

    // Use item

    public List<GameItem> useItem(int itemId, int count, int avatarId) {
        var result = new org.hkrpg.server.gameplay.ConsumableUse(getPlayer()).use(itemId, count, avatarId);
        return result.retcode() == emu.lunarcore.server.packet.Retcode.SUCC ? result.items() : null;
    }

    // Equips

    public boolean equipItem(int avatarId, int equipId) {
        BaseAvatar avatar = getPlayer().getAvatars().getBaseAvatarById(avatarId);
        GameItem item = this.getItemByUid(equipId);

        if (avatar != null && item != null) {
            return avatar.equipItem(item);
        }

        return false;
    }

    public boolean unequipItem(int avatarId, int slot) {
        BaseAvatar avatar = getPlayer().getAvatars().getBaseAvatarById(avatarId);

        if (avatar != null) {
            GameItem unequipped = avatar.unequipItem(slot);
            if (unequipped != null) {
                getPlayer().sendPacket(new PacketPlayerSyncScNotify(avatar, unequipped));
                return true;
            }
        }

        return false;
    }

    // Database

    public void loadFromDatabase() {
    Stream<GameItem> stream =
        LunarCore.getGameDatabase()
            .getObjects(GameItem.class, "ownerUid", this.getPlayer().getUid());
        stream.forEach(this::loadItem);
    }

    public boolean loadItem(GameItem item) {
        // Should never happen
        if (item.getId() == null) {
            return false;
        }

        // Check item owner
        if (item.getOwnerUid() != this.getPlayer().getUid()) {
            return false;
        }

        // Load item excel data
        ItemExcel excel = GameData.getItemExcelMap().get(item.getItemId());
        if (excel == null) {
            // Delete item if it has no excel data
            item.setCount(0);
            item.save();
            return false;
        }

        // Set ownerships
        item.setExcel(excel);

        // Put in inventory
        InventoryTab tab = getTabByItemType(item.getExcel().getItemMainType());
        putItem(item, tab);

        // Equip to a character if possible
        if (item.isEquipped() || item.getEquipAvatarExcelId() > 0) {
            BaseAvatar avatar = null;
            boolean hasEquipped = false;

            if (item.getEquipAvatarExcelId() > 0) {
                // Legacy equip handler
                avatar = getPlayer().getAvatars().getBaseAvatarById(item.getEquipAvatarExcelId());
                item.setEquipAvatar(avatar);
                item.save();
            } else {
                avatar = getPlayer().getAvatars().getBaseAvatarById(item.getEquipAvatarId());
            }

            // Make sure the avatar that we equipped this item to actually exists
            if (avatar != null) {
                hasEquipped = avatar.equipItem(item);
            }

            // Unset equipped flag on item since we couldnt find an avatar to equip it to
            if (!hasEquipped) {
                item.setEquipAvatar(null);
                item.save();
            }
        }

        // Done
        return true;
    }
}
