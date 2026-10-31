package emu.lunarcore.game.gacha;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.ItemMainType;
import java.util.*;

/** One resource-checked view shared by the draw engine, game queries and GM. */
public record GachaPool(List<Integer> base5, List<Integer> base4, List<Integer> base3,
        List<Integer> up5, List<Integer> up4, List<String> issues, boolean permanent) {
    public GachaPool {
        base5 = List.copyOf(base5); base4 = List.copyOf(base4); base3 = List.copyOf(base3);
        up5 = List.copyOf(up5); up4 = List.copyOf(up4); issues = List.copyOf(issues);
    }

    public List<Integer> fiveStars() { return union(base5, up5); }
    public List<Integer> fourStars() { return union(base4, up4); }
    public boolean canPull() {
        return issues.isEmpty() && !base5.isEmpty() && !base4.isEmpty() && !base3.isEmpty();
    }

    static GachaPool resolve(GachaBanner banner, GachaService service) {
        var issues = new ArrayList<String>();
        boolean permanent = banner.getGachaType() == GachaType.Normal || banner.getGachaType() == GachaType.Newbie;
        List<Integer> five, four, up5 = List.of(), up4 = List.of();
        if (permanent) {
            // Legacy Normal arrays encode a mixed catalogue plus display items, not UP tiers.
            // Reclassify the configured membership in memory; never migrate the operator file.
            var configured = union(box(banner.getRateUpItems5()), box(banner.getRateUpItems4()));
            var valid = new ArrayList<Integer>();
            for (int id : configured) {
                int rarity = rarity(id);
                if (drawable(id) && (rarity == 4 || rarity == 5)) valid.add(id);
                else issues.add("条目 #" + id + " 缺少可用的四星或五星角色/光锥资源。");
            }
            five = valid.stream().filter(id -> rarity(id) == 5)
                    .filter(id -> banner.getGachaType() != GachaType.Newbie
                            || GameData.getItemExcelMap().get(id).getItemMainType() == ItemMainType.AvatarCard).toList();
            four = valid.stream().filter(id -> rarity(id) == 4).toList();
            if (five.isEmpty()) five = defaults(service.getYellowAvatars(), service.getYellowWeapons(), 5,
                    banner.getGachaType() == GachaType.Newbie ? GachaType.AvatarUp : GachaType.Normal, issues);
            if (four.isEmpty()) four = defaults(service.getPurpleAvatars(), service.getPurpleWeapons(), 4, GachaType.Normal, issues);
        } else {
            up5 = checked(box(banner.getRateUpItems5()), 5, banner.getGachaType(), "五星 UP", issues);
            up4 = checked(box(banner.getRateUpItems4()), 4, banner.getGachaType(), "四星 UP", issues);
            five = defaults(service.getYellowAvatars(), service.getYellowWeapons(), 5, banner.getGachaType(), issues);
            four = defaults(service.getPurpleAvatars(), service.getPurpleWeapons(), 4, GachaType.Normal, issues);
        }
        var three = checked(box(service.getBlueWeapons()), 3, GachaType.WeaponUp, "三星基础池", issues);
        return new GachaPool(five, four, three, up5, up4, issues, permanent);
    }

    private static List<Integer> defaults(int[] avatars, int[] weapons, int stars, GachaType type, List<String> issues) {
        var candidates = new ArrayList<Integer>();
        if (type.getMinItemType() <= 1) candidates.addAll(box(avatars));
        if (type.getMaxItemType() >= 2) candidates.addAll(box(weapons));
        return checked(candidates, stars, type, stars + "星基础池", issues);
    }

    private static List<Integer> checked(List<Integer> ids, int stars, GachaType type, String label, List<String> issues) {
        var valid = new LinkedHashSet<Integer>();
        for (int id : ids) {
            var item = GameData.getItemExcelMap().get(id);
            boolean allowed = item != null && (item.getItemMainType() == ItemMainType.AvatarCard
                    ? type.getMinItemType() <= 1 : item.getItemMainType() == ItemMainType.Equipment && type.getMaxItemType() >= 2);
            if (allowed && drawable(id) && rarity(id) == stars) valid.add(id);
            else issues.add(label + "条目 #" + id + " 的星级、类型或资源定义无效。");
        }
        return List.copyOf(valid);
    }

    private static boolean drawable(int id) {
        var item = GameData.getItemExcelMap().get(id);
        return item != null && (item.getItemMainType() == ItemMainType.AvatarCard
                && GameData.getAvatarExcelMap().containsKey(id) || item.isEquipment());
    }

    private static int rarity(int id) {
        var item = GameData.getItemExcelMap().get(id);
        return item == null || item.getRarity() == null ? 0 : item.getRarityNum();
    }

    private static List<Integer> box(int[] values) {
        return values == null ? List.of() : Arrays.stream(values).boxed().toList();
    }

    private static List<Integer> union(List<Integer> left, List<Integer> right) {
        var result = new LinkedHashSet<>(left); result.addAll(right); return List.copyOf(result);
    }
}
