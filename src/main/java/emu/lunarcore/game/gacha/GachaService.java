package emu.lunarcore.game.gacha;

import java.io.FileReader;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import emu.lunarcore.GameConstants;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.enums.ItemRarity;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.shop.ShopService;
import emu.lunarcore.proto.GachaItemOuterClass.GachaItem;
import emu.lunarcore.proto.GetGachaInfoScRspOuterClass.GetGachaInfoScRsp;
import emu.lunarcore.proto.ItemListOuterClass.ItemList;
import emu.lunarcore.proto.ItemOuterClass.Item;
import emu.lunarcore.server.game.BaseGameService;
import emu.lunarcore.server.game.GameServer;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.PacketDoGachaScRsp;
import emu.lunarcore.util.JsonUtils;
import emu.lunarcore.util.Utils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import lombok.Getter;

@Getter
public class GachaService extends BaseGameService {
    private final Int2ObjectMap<GachaBanner> gachaBanners;
    // Readers holding a player lock must never take the service lock in reverse order.
    private volatile List<GachaBanner> bannerSnapshot = List.of();
    private WatchService watchService;
    private Thread watchThread;

    private int[] yellowAvatars = new int[] {1003, 1004, 1101, 1107, 1104, 1209, 1211};
    private int[] yellowWeapons = new int[] {23000, 23002, 23003, 23004, 23005, 23012, 23013};
    private int[] purpleAvatars = new int[] {1001, 1002, 1008, 1009, 1013, 1103, 1105, 1106, 1108, 1109, 1110, 1111, 1201, 1202, 1206, 1207, 1210};
    private int[] purpleWeapons = new int[] {21000, 21001, 21002, 21003, 21004, 21005, 21006, 21007, 21008, 21009, 21010, 21011, 21012, 21013, 21014, 21015, 21016, 21017, 21018, 21019, 21020};
    private int[] blueWeapons = new int[] {20000, 20001, 20002, 20003, 20004, 20005, 20006, 20007, 20008, 20009, 20010, 20011, 20012, 20013, 20014, 20015, 20016, 20017, 20018, 20019, 20020};
    private int[] defaultFeaturedIds = new int[] {23002, 1003, 1101, 1104, 23000, 23003};

    private static int starglightId = 252;
    private static int embersId = 251;

    public GachaService(GameServer server) {
        super(server);
        this.gachaBanners = new Int2ObjectOpenHashMap<>();

        try {
            this.watch();
        } catch (Exception e) {
            LunarCore.getLogger().error("Watch service error: ", e);
        }
    }

    public int randomRange(int min, int max) {
        return ThreadLocalRandom.current().nextInt(max - min + 1) + min;
    }

    public int getRandom(int[] array) {
        return array[randomRange(0, array.length - 1)];
    }

    public GachaPool getPool(GachaBanner banner) {
        return GachaPool.resolve(banner, this);
    }

    public int[] getFeatured5(GachaBanner banner, GachaPool pool) {
        if (!pool.permanent()) return pool.up5().stream().mapToInt(Integer::intValue).toArray();
        // Older permanent configurations put the five-star display subset in rateUpItems4.
        var display = java.util.Arrays.stream(banner.getRateUpItems4()).filter(pool.fiveStars()::contains).distinct().toArray();
        if (display.length == 0) display = java.util.Arrays.stream(defaultFeaturedIds).filter(pool.fiveStars()::contains).toArray();
        return display.length == 0 ? pool.base5().stream().mapToInt(Integer::intValue).toArray() : display;
    }

    private int pick(java.util.List<Integer> candidates) {
        return getRandom(candidates.stream().mapToInt(Integer::intValue).toArray());
    }

    private int pickBase(java.util.List<Integer> candidates) {
        var avatars = candidates.stream().filter(id -> GameData.getItemExcelMap().get(id).getItemMainType() == ItemMainType.AvatarCard).toList();
        var weapons = candidates.stream().filter(id -> GameData.getItemExcelMap().get(id).getItemMainType() == ItemMainType.Equipment).toList();
        if (avatars.isEmpty()) return pick(weapons);
        if (weapons.isEmpty()) return pick(avatars);
        return pick(randomRange(1, 2) == 1 ? avatars : weapons);
    }

    private String getBannerFileName() {
        return LunarCore.getConfig().getDataDir() + "/Banners.json";
    }

    public void watch() throws Exception {
        // Load banners first
        this.loadBanners();

        // Create watch service
        this.watchService = FileSystems.getDefault().newWatchService();
        Path watchPath = Paths.get(LunarCore.getConfig().getDataDir());
        watchPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);

        // Start watch thread
        this.watchThread = new Thread(() -> {
            WatchKey key = null;
            try {
                while ((key = watchService.take()) != null) {
                    for (var event : key.pollEvents()) {
                        if (event.context() == null) {
                            continue;
                        }

                        if (event.context() instanceof Path path && path.toString().equals("Banners.json")) {
                            loadBanners();
                        }
                    }

                    key.reset();
                }
            } catch (ClosedWatchServiceException e) {
                // Expected during server shutdown.
            } catch (Exception e) {
                LunarCore.getLogger().error("Watch service thread error: ", e);
            }
        });
        this.watchThread.start();
    }

    public void close() {
        try {
            if (watchService != null) watchService.close();
        } catch (java.io.IOException e) {
            LunarCore.getLogger().warn("Unable to close banner watcher", e);
        }
    }

    public synchronized void loadBanners() {
        try (FileReader fileReader = new FileReader(getBannerFileName())) {
            List<GachaBanner> banners = JsonUtils.loadToList(fileReader, GachaBanner.class);
            replaceBanners(banners);
        } catch (Exception e) {
            LunarCore.getLogger().warn("Error loading gacha banners: ", e);
        }
    }

    /** Validate the whole candidate before publishing; a bad reload keeps the last good table. */
    public synchronized void replaceBanners(List<GachaBanner> banners) {
        validateBanners(banners);
        var candidate = new Int2ObjectOpenHashMap<GachaBanner>();
        for (var banner : banners) candidate.put(banner.getId(), banner);
        getGachaBanners().clear();
        getGachaBanners().putAll(candidate);
        bannerSnapshot = List.copyOf(banners);
    }

    public static void validateBanners(List<GachaBanner> banners) {
        if (banners == null) throw new IllegalArgumentException("卡池配置必须为数组。");
        var ids = new java.util.HashSet<Integer>();
        for (var banner : banners) {
            if (banner == null || banner.getId() <= 0 || !ids.add(banner.getId())
                    || banner.getGachaType() == null || banner.getGachaType() == GachaType.Unknown
                    || banner.getRateUpItems5() == null || banner.getRateUpItems4() == null
                    || banner.isGmTemplatePending() && (!banner.isDisabled() || banner.getBeginTime() != 0
                        || banner.getEndTime() != 0 || banner.getGachaType() != GachaType.AvatarUp
                            && banner.getGachaType() != GachaType.WeaponUp)
                    || banner.getEventChance() < 0 || banner.getEventChance() > 100
                    || java.util.Arrays.stream(banner.getRateUpItems5()).anyMatch(id -> id <= 0)
                    || java.util.Arrays.stream(banner.getRateUpItems4()).anyMatch(id -> id <= 0))
                throw new IllegalArgumentException("卡池配置含无效项目或重复 ID。");
        }
    }

    public synchronized void doPulls(Player player, int gachaId, int times) {
        // Sanity checks
        if (times != 10 && times != 1) {
            player.sendPacket(new PacketDoGachaScRsp(Retcode.GACHA_NUM_INVALID));
            return;
        }

        GachaBanner banner = this.getGachaBanners().get(gachaId);
        if (banner == null || !banner.isActive(System.currentTimeMillis() / 1000L)) {
            player.sendPacket(new PacketDoGachaScRsp(Retcode.GACHA_ID_NOT_EXIST));
            return;
        }

        boolean beginner = banner.getGachaType() == GachaType.Newbie;
        if (beginner && times != 10) {
            player.sendPacket(new PacketDoGachaScRsp(Retcode.GACHA_NUM_INVALID));
            return;
        }
        if (beginner && player.getGachaInfo().getNewbieDrawCount() > PlayerGachaInfo.NEWBIE_DRAW_LIMIT - times) {
            player.sendPacket(new PacketDoGachaScRsp(Retcode.GACHA_NEWBIE_CLOSE));
            return;
        }

        var pool = getPool(banner);
        if (!pool.canPull()) {
            player.sendPacket(new PacketDoGachaScRsp(Retcode.FAIL));
            return;
        }

        // Prevent player from using gacha if they are at max light cones
        if (player.getInventory().getTabByItemType(ItemMainType.Equipment).getSize() >= player.getInventory().getTabByItemType(ItemMainType.Equipment).getMaxCapacity()) {
            player.sendPacket(new PacketDoGachaScRsp(Retcode.EQUIPMENT_EXCEED_LIMIT));
            return;
        }

        // Get banner

        // Spend currency
        int cost = beginner ? 8 : times;
        if (banner.getGachaType().getCostItem() > 0) {
            GameItem costItem = player.getInventory().getMaterialByItemId(banner.getGachaType().getCostItem());
            if (costItem == null || costItem.getCount() < cost) {
                player.sendPacket(new PacketDoGachaScRsp(Retcode.FAIL));
                return;
            }

            if (!player.getInventory().removeItem(costItem, cost)) {
                player.sendPacket(new PacketDoGachaScRsp(Retcode.FAIL));
                return;
            }
        }

        // Add gacha ceiling
        if (banner.getGachaType() == GachaType.Normal) {
            player.getGachaInfo().addCeilingNum(times);
        }

        // Roll
        PlayerGachaBannerInfo gachaInfo = player.getGachaInfo().getBannerInfo(banner.getGachaType());
        IntList wonItems = new IntArrayList(times);

        for (int i = 0; i < times; i++) {
            int random = this.randomRange(1, 10000);
            int itemId = 0;

            boolean weapon = banner.getGachaType() == GachaType.WeaponUp;
            // Official base rates and hard limits are public. The intermediate soft-pity
            // curve is a compatible server approximation, not a published official curve.
            int yellowChance = beginner ? 60 : weapon
                    ? Math.min(10000, 80 + Math.max(0, gachaInfo.getPity5() - 64) * 700)
                    : Math.min(10000, 60 + Math.max(0, gachaInfo.getPity5() - 72) * 600);
            int purpleChance = weapon && gachaInfo.getPity4() == 7 ? 5000
                    : (weapon ? 660 : 510) * (gachaInfo.getPity4() >= 8 ? 11 : 1);
            boolean forceNewbieFive = beginner && player.getGachaInfo().getNewbieDrawCount() == PlayerGachaInfo.NEWBIE_DRAW_LIMIT - 1
                    && !player.getGachaInfo().isNewbieFiveStarObtained();
            boolean fiveStar = random <= yellowChance || forceNewbieFive
                    || !beginner && gachaInfo.getPity5() >= (weapon ? 79 : 89);

            if (fiveStar) {
                if (!pool.up5().isEmpty()) {
                    int eventChance = this.randomRange(1, 100);

                    if (eventChance <= banner.getEventChance() || gachaInfo.getFailedFeaturedItemPulls() >= 1) {
                        itemId = pick(pool.up5());
                        gachaInfo.setFailedFeaturedItemPulls(0);
                    } else {
                        // Lost the 50/50... rip
                        gachaInfo.addFailedFeaturedItemPulls(1);
                    }
                }

                if (itemId == 0) {
                    // The remaining 50%/25% selects uniformly from every obtainable
                    // five-star, including UP. Standard/newbie keep their type split.
                    itemId = pool.permanent() ? pickBase(pool.base5()) : pick(pool.fiveStars());
                }
                if (pool.up5().contains(itemId)) gachaInfo.setFailedFeaturedItemPulls(0);

                // Pity
                gachaInfo.addPity4(1);
                gachaInfo.setPity5(0);
            } else if (random > 10000 - purpleChance || gachaInfo.getPity4() >= 9) {
                if (!pool.up4().isEmpty()) {
                    int eventChance = this.randomRange(1, 100);

                    if (eventChance <= (weapon ? 75 : 50) || gachaInfo.getFailedFeatured4Pulls() > 0) {
                        itemId = pick(pool.up4());
                        gachaInfo.setFailedFeatured4Pulls(0);
                    } else {
                        gachaInfo.setFailedFeatured4Pulls(1);
                    }
                }

                if (itemId == 0) {
                    itemId = pickBase(pool.base4());
                }
                if (pool.up4().contains(itemId)) gachaInfo.setFailedFeatured4Pulls(0);

                // Pity
                gachaInfo.addPity5(1);
                gachaInfo.setPity4(0);
            } else {
                itemId = pick(pool.base3());

                // Pity
                gachaInfo.addPity4(1);
                gachaInfo.addPity5(1);
            }

            if (beginner) player.getGachaInfo().recordNewbieDraw(fiveStar);

            // Add winning item
            wonItems.add(itemId);
        }

        // Add to character
        List<GachaItem> list = new ArrayList<>();
        int stardust = 0, starglitter = 0;

        for (int itemId : wonItems) {
            ItemExcel itemData = GameData.getItemExcelMap().get(itemId);
            if (itemData == null) {
                continue;
            }

            // Create gacha item
            GachaItem gachaItem = GachaItem.newInstance();
            gachaItem.setTransferItemList(ItemList.newInstance());
            gachaItem.setTokenItem(ItemList.newInstance());
            int addStardust = 0, addStarglitter = 0;

            // Dupe check
            if (itemData.getItemMainType() == ItemMainType.AvatarCard) {
                int avatarId = itemData.getId();
                GameAvatar avatar = player.getAvatars().getAvatarById(avatarId);
                if (avatar != null) {
                    int dupeLevel = avatar.getRank();
                    int dupeItemId = avatar.getExcel().getRankUpItemId();
                    GameItem dupeItem = player.getInventory().getMaterialByItemId(avatar.getExcel().getRankUpItemId());
                    if (dupeItem != null) {
                        dupeLevel += dupeItem.getCount();
                    }

                    if (dupeLevel < 6) {
                        // Not max const
                        addStarglitter = 8;
                        // Add 1 rank
                        gachaItem.getTransferItemList().addItemList(Item.newInstance().setItemId(dupeItemId).setNum(1));
                        player.getInventory().addItem(dupeItemId, 1);
                    } else {
                        // Is max rank
                        addStarglitter = 20;
                    }

                    if (itemData.getRarity() == ItemRarity.SuperRare) {
                        addStarglitter *= 5;
                    }
                } else {
                    // New
                    gachaItem.setIsNew(true);
                }
            } else {
                // Is weapon
                switch (itemData.getRarity()) {
                    case SuperRare:
                        addStarglitter = 40;
                        break;
                    case VeryRare:
                        addStarglitter = 8;
                        break;
                    case Rare:
                        addStardust = 20;
                        break;
                    default:
                        break;
                }
            }

            // Create item
            GameItem item = new GameItem(itemData);
            gachaItem.setGachaItem(item.toProto());
            player.getInventory().addItem(item);

            // Add embers/starlight
            stardust += addStardust;
            starglitter += addStarglitter;

			if (addStardust > 0) {
				gachaItem.getTokenItem().addItemList(Item.newInstance().setItemId(embersId).setNum(addStardust));
			} if (addStarglitter > 0) {
			    gachaItem.getTokenItem().addItemList(Item.newInstance().setItemId(starglightId).setNum(addStarglitter));
			}

			// Add to gacha item list rsp
			list.add(gachaItem);
        }

        // Add stardust/starglitter
        if (stardust > 0) {
            player.getInventory().addItem(embersId, stardust);
        } if (starglitter > 0) {
            player.getInventory().addItem(starglightId, starglitter);
        }

        // Packets
        player.save();
        player.sendPacket(new PacketDoGachaScRsp(player, banner, times, list));
    }

    public List<GameItem> exchangeGachaCeiling(Player player, int avatarId) {
        synchronized (player) {
            var history = player.getGachaInfo();
            if (history.getCeilingNum() < GameConstants.GACHA_CEILING_MAX
                    || history.isCeilingClaimed(player)
                    || !Utils.arrayContains(this.getYellowAvatars(), avatarId)) return null;

            // A receipt is reserved before delivery. An interrupted grant is not replayed
            // automatically because inventory and player state are separate documents.
            if (player.getGameplay().getRewards().containsKey(PlayerGachaInfo.CEILING_REWARD_KEY))
                return null;
            int itemId = avatarId;
            int amount = 1;
            var avatar = player.getAvatars().getAvatarById(avatarId);
            if (avatar != null) {
                if (avatar.getExcel() == null || avatar.getRank() < 0 || avatar.getRank() > 6)
                    return null;
                int rankItem = avatar.getExcel().getRankUpItemId();
                var held = player.getInventory().getMaterialByItemId(rankItem);
                int pending = held == null ? 0 : held.getCount();
                if (pending < 0) return null;
                if ((long) avatar.getRank() + pending < 6) itemId = rankItem;
                else { itemId = starglightId; amount = 100; }
            }
            if (!ShopService.canDeliver(player, java.util.Map.of(itemId, amount))) return null;
            var reward = new ItemParamMap();
            reward.put(itemId, amount);
            var claim = player.getRewardLedger().claimItems(PlayerGachaInfo.CEILING_REWARD_KEY, reward);
            if (claim.retcode() != Retcode.SUCC) return null;
            history.setCeilingClaimed(true);
            player.save();
            return claim.items();
        }
    }

    public synchronized GetGachaInfoScRsp toProto(Player player) {
        var proto = GetGachaInfoScRsp.newInstance();

        for (GachaBanner banner : getGachaBanners().values()) {
            if (!banner.isActive(System.currentTimeMillis() / 1000L)) continue;
            if (banner.getGachaType() == GachaType.Newbie && player.getGachaInfo().isNewbieClosed()) continue;
            if (!getPool(banner).canPull()) continue;
            proto.addGachaInfoList(banner.toProto(this, player));
        }

        return proto;
    }
}
