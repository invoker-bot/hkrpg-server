package org.hkrpg.server.gm;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.inventory.Inventory;
import emu.lunarcore.game.shop.ShopItems;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gm.GmModels.*;

/** Read-only indexes of resource visibility, acquisition text and existing banner provenance. */
final class GmItemShortcuts {
  static final Map<String,String> TYPES = Map.ofEntries(
      Map.entry("avatar","角色"),Map.entry("equipment","光锥"),Map.entry("skin","角色时装"),
      Map.entry("icon","头像"),Map.entry("frame","头像框"),Map.entry("bubble","聊天气泡"),
      Map.entry("theme","手机主题"),Map.entry("case","手机壳"),Map.entry("outfit","开拓者装扮"),
      Map.entry("pet","宠物"),Map.entry("pam","帕姆服装"),Map.entry("card","个人名片"),
      Map.entry("music","唱片"),Map.entry("medal","勋章"),Map.entry("mission","任务物品"),
      Map.entry("material","材料"),Map.entry("consumable","消耗品"),Map.entry("other","其他物品"));
  static final Set<String> TAGS = Set.of("hidden","limited","activity","collectible","event-warp");
  private static final Map<String,String> SEARCH_ALIASES = Map.of(
      "skin","服饰 服装 衣服 皮肤","outfit","服饰 服装 衣服","pam","服饰 服装 衣服 皮肤");
  private record Hint(Set<String> tags, Set<String> sources) {
    Hint() { this(new LinkedHashSet<>(),new LinkedHashSet<>()); }
  }

  static List<ItemShortcut> load(Path resources, List<CatalogItem> catalog) throws IOException {
    var hints = new HashMap<Integer,Hint>();
    var origins = rows(resources.resolve("ExcelOutput/ItemComeFrom.json"));
    var hashes = new HashSet<String>();
    for (var row : origins) hashes.add(hash(row.getAsJsonObject().get("Desc")));
    GameData.getShopExcelMap().values().forEach(shop -> hashes.add(Long.toUnsignedString(shop.getShopName())));
    var descriptions = texts(resources.resolve("TextMap/TextMapCHS.json"),hashes);
    for (var row : origins) {
      var source = row.getAsJsonObject();
      String description = clean(descriptions.getOrDefault(hash(source.get("Desc")),""));
      if (description.isBlank()) continue;
      var hint = hints.computeIfAbsent(source.get("ID").getAsInt(),id -> new Hint());
      hint.sources().add(description);
      if (description.contains("活动")) hint.tags().add("activity");
      if (List.of("限时","限定","联动","无名勋礼","无名客").stream().anyMatch(description::contains))
        hint.tags().add("limited");
    }
    // A shop listing is acquisition provenance, not a claim of limited or currently listed stock.
    GameData.getShopExcelMap().values().stream().sorted(Comparator.comparingInt(shop -> shop.getId())).forEach(shop -> {
      String name=clean(descriptions.getOrDefault(Long.toUnsignedString(shop.getShopName()),"商店"));
      shop.getGoods().values().stream().filter(goods -> goods.getItemID()>0)
          .sorted(Comparator.comparingInt(goods -> goods.getId())).forEach(goods ->
              hints.computeIfAbsent(goods.getItemID(),id -> new Hint()).sources()
                  .add("商店目录："+name+" #"+shop.getId()+" · 商品 #"+goods.getId()));
    });
    for (var table : Map.of("ChatBubbleConfig","ID","PhoneThemeConfig","ID",
        "PhoneCaseConfig","CaseID","PlayerPersonalCard","CardID").entrySet()) {
      for (var row : rows(resources.resolve("ExcelOutput/"+table.getKey()+".json"))) {
        var source = row.getAsJsonObject();
        String show = source.has("ShowType") ? source.get("ShowType").getAsString() : "";
        if (!Set.of("UnlockedOnly","InSchedule","AfterStart").contains(show)) continue;
        var hint = hints.computeIfAbsent(source.get(table.getValue()).getAsInt(),id -> new Hint());
        hint.tags().add(show.equals("UnlockedOnly") ? "hidden" : show.equals("InSchedule") ? "limited" : "activity");
        hint.sources().add(table.getKey()+"："+switch(show) {
          case "UnlockedOnly" -> "解锁后显示";case "InSchedule" -> "显示受活动排期约束";default -> "活动开始后显示";
        });
      }
    }
    addBannerHints(hints);
    var names = new HashMap<Integer,String>();
    catalog.forEach(entry -> names.put(entry.id(),entry.name()));
    var result = new ArrayList<ItemShortcut>();
    for (var entry : catalog) {
      var item = GameData.getItemExcelMap().get(entry.id());
      if (item == null) continue;
      var hint = hints.getOrDefault(entry.id(),new Hint());
      String type = type(item);
      if (item.getItemSubType()!=null && ShopItems.unique(item)) {
        hint.tags().add("collectible");
        hint.sources().add("收藏类型："+TYPES.getOrDefault(type,type));
      }
      if (type.equals("skin")) {
        var skin = GameData.getAvatarSkinExcelMap().get(entry.id());
        if (skin!=null) hint.sources().add("对应角色："+names.getOrDefault(skin.getAvatarID(),"角色")+" #"+skin.getAvatarID());
      }
      // Avatar cards and virtual balances omit visibility by design; do not label them hidden.
      if (!Boolean.TRUE.equals(item.getIsVisible())
          && item.getItemMainType()!=emu.lunarcore.game.enums.ItemMainType.AvatarCard
          && item.getItemMainType()!=emu.lunarcore.game.enums.ItemMainType.Virtual) {
        hint.tags().add("hidden");
        hint.sources().add(item.getIsVisible()==null ? "物品资源未标记 isVisible=true" : "物品资源 isVisible=false");
      }
      if (hint.tags().isEmpty()) continue;
      boolean supported = grantable(item);
      result.add(new ItemShortcut(entry,type,List.copyOf(hint.tags()),List.copyOf(hint.sources()),supported,
          supported ? ShopItems.unique(item) ? "每次 1 件，已拥有时拒绝重复发放。" : "发放前检查背包容量和堆叠上限。"
              : item.getItemSubType()==emu.lunarcore.game.enums.ItemSubType.HeadIconFrame
                  ? "头像框尚未接通 4.6 解锁同步，仅供查询。" : "当前类型或配套资源尚不支持发放，仅供查询。",maxAmount(item)));
    }
    return List.copyOf(result);
  }

  static boolean matches(ItemShortcut row, String query) {
    return query.isEmpty() || (row.item().id()+" "+row.item().name()+" "+TYPES.getOrDefault(row.type(),row.type())
        +" "+SEARCH_ALIASES.getOrDefault(row.type(),"")+" "+String.join(" ",row.sources()))
        .toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
  }

  static boolean grantable(ItemExcel item) {
    return item!=null && item.getItemSubType()!=null
        && item.getItemSubType()!=emu.lunarcore.game.enums.ItemSubType.HeadIconFrame && ShopItems.deliverable(item)
        && (!Inventory.isStackType(item) || ShopItems.stackLimit(item)>0);
  }

  static int maxAmount(ItemExcel item) {
    return item.getItemSubType()!=null && ShopItems.unique(item) || item.getItemMainType()==emu.lunarcore.game.enums.ItemMainType.AvatarCard ? 1
        : item.isEquippable() ? 100 : Math.max(1,Math.min(1_000_000,ShopItems.stackLimit(item)));
  }

  private static String type(ItemExcel item) {
    if (item.getItemSubType()==null) return "other";
    return switch(item.getItemSubType()) {
      case AvatarSkin -> "skin";case HeadIcon -> "icon";case HeadIconFrame -> "frame";
      case ChatBubble -> "bubble";case PhoneTheme -> "theme";case PhoneCase -> "case";
      case PlayerOutfit -> "outfit";case NormalPet -> "pet";case PamSkin -> "pam";
      case PersonalCard -> "card";case MusicAlbum -> "music";case RogueMedal -> "medal";
      default -> switch(item.getItemMainType()) {
        case AvatarCard -> "avatar";case Equipment -> "equipment";case Mission -> "mission";
        case Material -> "material";case Usable -> "consumable";default -> "other";
      };
    };
  }

  private static void addBannerHints(Map<Integer,Hint> hints) throws IOException {
    var normal = new HashSet<Integer>();
    try (var stream=GmItemShortcuts.class.getResourceAsStream("/defaults/Banners.json")) {
      if (stream==null) throw new IOException("Missing default banners");
      for (var element : JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonArray()) {
        var row=element.getAsJsonObject();
        if (!"Normal".equals(row.get("gachaType").getAsString())) continue;
        for (var field : List.of("rateUpItems5","rateUpItems4"))
          if (row.has(field)) row.getAsJsonArray(field).forEach(id -> normal.add(id.getAsInt()));
      }
    }
    try (var stream=GmItemShortcuts.class.getResourceAsStream("/gm/banner-history.json")) {
      if (stream==null) throw new IOException("Missing banner history");
      var archive=JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
      for (var element : archive.getAsJsonArray("banners")) {
        var row=element.getAsJsonObject();
        for (var id : row.getAsJsonArray("rateUpItems5")) {
          if (normal.contains(id.getAsInt())) continue;
          var hint=hints.computeIfAbsent(id.getAsInt(),key -> new Hint());
          hint.tags().add("event-warp");hint.tags().add("limited");
          hint.sources().add("历史活动五星 UP，未列入内置常驻目录（版本 "+row.get("version").getAsString()+"）");
        }
      }
    }
  }

  private static JsonArray rows(Path path) throws IOException {
    if (!Files.exists(path)) return new JsonArray();
    if (Files.size(path)>20_000_000) throw new IOException("Shortcut source too large: "+path.getFileName());
    try { return JsonParser.parseString(Files.readString(path,StandardCharsets.UTF_8)).getAsJsonArray(); }
    catch (RuntimeException e) { throw new IOException("Invalid shortcut source: "+path.getFileName(),e); }
  }

  private static String hash(JsonElement element) {
    if (element==null || element.isJsonNull()) return "0";
    return element.isJsonObject() ? element.getAsJsonObject().get("Hash").getAsString() : element.getAsString();
  }

  private static Map<String,String> texts(Path path, Set<String> hashes) throws IOException {
    var result=new HashMap<String,String>();
    if (!Files.exists(path)) return result;
    try (var reader=new JsonReader(Files.newBufferedReader(path,StandardCharsets.UTF_8))) {
      reader.beginObject();
      while (reader.hasNext()) {var key=reader.nextName();if(hashes.contains(key))result.put(key,reader.nextString());else reader.skipValue();}
      reader.endObject();
    }
    return result;
  }

  private static String clean(String value) {return value.replaceAll("<[^>]*>","").replace("\\n"," ");}
  private GmItemShortcuts() {}
}
