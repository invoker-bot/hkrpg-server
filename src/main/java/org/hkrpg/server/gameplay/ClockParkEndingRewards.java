package org.hkrpg.server.gameplay;

import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.enums.ItemSubType;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Only matched original endings are payable; unique mission items retain their stored identity. */
public final class ClockParkEndingRewards {
  public static final String SOURCE =
      "54ef4bd235ea6e89ce01e0b57140ea966c8a8201e68648c2d5c0a8ccb30573aa:3607f3bf214baca99a82cfe90c957781aa8c3aa1847e712da4acaa35cf7f7142:hkrpg.clockpark.unique-ending-item.v1";
  private static final String SECOND_SOURCE =
      "54ef4bd235ea6e89ce01e0b57140ea966c8a8201e68648c2d5c0a8ccb30573aa:3607f3bf214baca99a82cfe90c957781aa8c3aa1847e712da4acaa35cf7f7142:hkrpg.clockpark.authored-second-endings.v2";
  private static final String FIFTH_SOURCE =
      "54ef4bd235ea6e89ce01e0b57140ea966c8a8201e68648c2d5c0a8ccb30573aa:3607f3bf214baca99a82cfe90c957781aa8c3aa1847e712da4acaa35cf7f7142:hkrpg.clockpark.authored-fifth-endings.v3";

  private static final String LAST_SOURCE =
      "54ef4bd235ea6e89ce01e0b57140ea966c8a8201e68648c2d5c0a8ccb30573aa:3607f3bf214baca99a82cfe90c957781aa8c3aa1847e712da4acaa35cf7f7142:hkrpg.clockpark.authored-third-fourth-endings.v4";

  private record Recipe(
      int script, int chapter, String kind, int reward, int progress, List<Integer> items) {
    Recipe(int script, int chapter, String kind, int reward, int progress, int item) {
      this(script, chapter, kind, reward, progress, item == 0 ? List.of() : List.of(item));
    }

    String key() {
      return "clock-park-ending:" + script + ":" + chapter;
    }

    String source() {
      return switch (script) {
        case 1 -> SOURCE;
        case 2 -> SECOND_SOURCE;
        case 5 -> FIFTH_SOURCE;
        case 3, 4 -> LAST_SOURCE;
        default -> throw new IllegalArgumentException("Unsupported ending source");
      };
    }
  }

  private static final List<Recipe> RECIPES =
      List.of(
          new Recipe(1, 103, "TrueEnding", 8008001, 2000, 140484),
          new Recipe(2, 205, "TrueEnding", 8008002, 2000, 140483),
          new Recipe(2, 206, "FalseEnding", 100, 0, 0),
          new Recipe(5, 550, "TrueEnding", 8008006, 2000, 140485),
          new Recipe(5, 560, "FalseEnding", 100, 0, 0),
          new Recipe(3, 305, "TrueEnding", 8008003, 2000, List.of(140486, 140487)),
          new Recipe(3, 306, "FalseEnding", 100, 0, 0),
          new Recipe(4, 450, "TrueEnding", 8008005, 2000, 140488),
          new Recipe(4, 460, "FalseEnding", 100, 0, 0));
  private static final Map<String, String> INPUTS =
      Map.of(
          "RewardData", "54ef4bd235ea6e89ce01e0b57140ea966c8a8201e68648c2d5c0a8ccb30573aa",
          "ItemConfig", "3607f3bf214baca99a82cfe90c957781aa8c3aa1847e712da4acaa35cf7f7142");

  public record Result(Retcode code, List<GameItem> items) {}

  private static Recipe recipe(int script, int chapter) {
    return RECIPES.stream()
        .filter(r -> r.script == script && r.chapter == chapter)
        .findFirst()
        .orElse(null);
  }

  public static String source(int script, int chapter) {
    var recipe = recipe(script, chapter);
    return recipe == null ? null : recipe.source();
  }

  public static boolean supported(ClockParkCatalog.Chapter chapter, int script) {
    if (chapter == null) return false;
    var recipe = recipe(script, chapter.id());
    return recipe != null
        && chapter.kind().equals(recipe.kind)
        && chapter.rewardId() == recipe.reward
        && chapter.rewardProgress() == recipe.progress;
  }

  public static boolean valid(Player player, ClockParkState state) {
    var receipts = player.getGameplay().getRewards();
    if (receipts == null) return false;
    var active = state.getActive();
    var expected = new HashMap<String, Recipe>();
    var completed = new HashSet<String>();
    for (var run : state.completedEndings()) {
      var recipe = recipe(run.getScript(), run.getChapter());
      if (recipe == null) return false;
      expected.put(recipe.key(), recipe);
      completed.add(recipe.key());
    }
    if (active != null && "FINISHING".equals(active.getPhase())) {
      var recipe = recipe(active.getScript(), active.getChapter());
      if (recipe == null) return false;
      expected.put(recipe.key(), recipe);
    }
    if (!expected.isEmpty() && !originalSources()) return false;
    for (var recipe : expected.values()) if (!loadedRecipe(recipe)) return false;
    for (var entry : receipts.entrySet()) {
      if (entry.getKey() == null || !entry.getKey().startsWith("clock-park-ending:")) continue;
      var recipe = expected.get(entry.getKey());
      if (recipe == null
          || entry.getValue() == null
          || entry.getValue().getRewardId() != recipe.reward
          || completed.contains(entry.getKey()) && !entry.getValue().isGranted()) return false;
      if (entry.getValue().isGranted()) {
        for (int id : recipe.items) {
          var owned = player.getInventory().getMaterialByItemId(id);
          if (owned == null || owned.getCount() != 1) return false;
        }
      }
    }
    return receipts.keySet().containsAll(completed);
  }

  private static boolean originalSources() {
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir());
      for (var entry : INPUTS.entrySet()) {
        var path = root.resolve("ExcelOutput/" + entry.getKey() + ".json");
        long length = Files.size(path);
        if (length <= 0 || length > 4 * 1024 * 1024) return false;
        var raw =
            new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .getBytes(StandardCharsets.UTF_8);
        if (!entry
            .getValue()
            .equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw))))
          return false;
      }
      return true;
    } catch (Exception invalid) {
      return false;
    }
  }

  private static boolean loadedRecipe(Recipe recipe) {
    var reward = GameData.getRewardExcelMap().get(recipe.reward);
    if (reward == null || reward.getRewardID() != recipe.reward || reward.getRewards() == null)
      return false;
    if (reward.getRewards().size() != recipe.items.size()) return false;
    for (int id : recipe.items) {
      var item = GameData.getItemExcelMap().get(id);
      if (reward.getRewards().get(id) != 1
          || item == null
          || item.getId() != id
          || item.getItemMainType() != ItemMainType.Mission
          || item.getItemSubType() != ItemSubType.Mission
          || item.getPileLimit() != 1) return false;
    }
    return true;
  }

  public static Retcode preflight(Player player, boolean first, int script, int chapter) {
    var recipe = recipe(script, chapter);
    if (recipe == null || !originalSources() || !loadedRecipe(recipe))
      return Retcode.REWARD_CONFIG_NOT_EXIST;
    if (recipe.items.isEmpty()) return Retcode.SUCC;
    var receipt = player.getGameplay().getRewards().get(recipe.key());
    for (int id : recipe.items) {
      var item = player.getInventory().getMaterialByItemId(id);
      if (item != null && (item.getCount() < 0 || item.getCount() > 1))
        return Retcode.PLAYER_DATA_ERROR;
      // All unrelated pre-existing items must be rejected before reserving or paying any item.
      if (first && receipt == null && item != null && item.getCount() == 1)
        return Retcode.ITEM_REWARD_EXCEED_LIMIT;
    }
    return Retcode.SUCC;
  }

  public static Result claim(Player player) {
    var state = player.getGameplay().getClockPark();
    if (!valid(player, state)
        || state.getActive() == null
        || !"FINISHING".equals(state.getActive().getPhase()))
      return new Result(Retcode.PLAYER_DATA_ERROR, List.of());
    var recipe = recipe(state.getActive().getScript(), state.getActive().getChapter());
    var code = preflight(player, true, recipe.script, recipe.chapter);
    if (code != Retcode.SUCC) return new Result(code, List.of());
    var receipts = player.getGameplay().getRewards();
    var receipt = receipts.get(recipe.key());
    try {
      if (receipt == null) {
        receipt = new GameplayState.Receipt(recipe.reward);
        receipts.put(recipe.key(), receipt);
      }
      if (!receipt.isGranted()) {
        // Retry the reservation write before a delivery, even if the previous write was
        // interrupted.
        player.save();
        var missing = new ItemParamMap();
        var alreadyOwned = new ArrayList<GameItem>();
        for (int id : recipe.items) {
          var owned = player.getInventory().getMaterialByItemId(id);
          if (owned == null || owned.getCount() == 0) missing.add(id, 1);
          else alreadyOwned.add(owned);
        }
        if (!missing.isEmpty()) {
          var grant = player.getInventory().grantItems(missing, true);
          if (!grant.complete()) return new Result(Retcode.PLAYER_DATA_ERROR, grant.items());
        }
        for (int id : recipe.items) {
          var owned = player.getInventory().getMaterialByItemId(id);
          if (owned == null || owned.getCount() != 1)
            return new Result(Retcode.PLAYER_DATA_ERROR, List.of());
        }
        // Re-save each previously delivered identity. A retry pays only still-missing items.
        for (var owned : alreadyOwned) owned.save();
        receipt.complete();
        player.save();
      }
      return new Result(
          Retcode.SUCC, recipe.items.stream().map(id -> new GameItem(id, 1)).toList());
    } catch (RuntimeException interrupted) {
      return new Result(Retcode.SYSTEM_BUSY, List.of());
    }
  }

  private ClockParkEndingRewards() {}
}
