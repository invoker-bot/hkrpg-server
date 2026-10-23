package org.hkrpg.server.gm;

import com.google.gson.stream.JsonReader;
import emu.lunarcore.LunarCore;
import emu.lunarcore.command.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.inventory.Inventory;
import emu.lunarcore.game.mail.Mail;
import emu.lunarcore.game.player.Player;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gm.GmModels.*;

final class LiveGmBackend implements GmBackend {
  private static final Set<String> DANGEROUS =
      Set.of("account", "clear", "permission", "giveall", "reload", "kick");
  private volatile List<CatalogItem> catalog;
  private final java.util.function.IntFunction<GameAvatar> avatarFactory;

  LiveGmBackend() {
    this(GameAvatar::new);
  }

  LiveGmBackend(java.util.function.IntFunction<GameAvatar> avatarFactory) {
    this.avatarFactory = avatarFactory;
  }

  public Status status() {
    var runtime = Runtime.getRuntime();
    var server = LunarCore.getGameServer();
    return new Status(
        LunarCore.isReady(),
        server != null,
        server == null ? 0 : server.getPlayerCount(),
        ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
        (runtime.totalMemory() - runtime.freeMemory()) / 1048576,
        runtime.maxMemory() / 1048576);
  }

  public List<PlayerRow> players() {
    var server = LunarCore.getGameServer();
    if (server == null) return List.of();
    return server.getRandomOnlinePlayers(Integer.MAX_VALUE, null).stream()
        .map(
            player -> {
              synchronized (player) {
                return new PlayerRow(
                    player.getUid(),
                    Objects.toString(player.getName(), "未命名玩家"),
                    player.getLevel(),
                    player.getWorldLevel(),
                    player.getPlaneId(),
                    player.getFloorId(),
                    player.getBattle() != null);
              }
            })
        .sorted(Comparator.comparingInt(PlayerRow::uid))
        .toList();
  }

  public List<CatalogItem> catalog() {
    if (!LunarCore.isReady()) throw new GmError(503, "游戏资源正在加载，请稍后刷新。");
    if (catalog == null)
      synchronized (this) {
        if (catalog == null) catalog = loadCatalog();
      }
    return catalog;
  }

  private List<CatalogItem> loadCatalog() {
    var hashes = new HashSet<Long>();
    GameData.getItemExcelMap().values().forEach(item -> hashes.add(item.getItemName()));
    var names = new HashMap<Long, String>();
    Path root = Path.of(LunarCore.getConfig().getResourceDir()).resolve("TextMap");
    String language = LunarCore.getConfig().getServerOptions().language;
    if (language == null || !language.matches("[A-Z]{2,4}")) language = "CHS";
    for (String candidate : new LinkedHashSet<>(List.of("CHS", language, "EN"))) {
      Path file = root.resolve("TextMap" + candidate + ".json");
      if (!Files.isRegularFile(file)) continue;
      try (var reader = new JsonReader(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
        reader.beginObject();
        while (reader.hasNext()) {
          String raw = reader.nextName();
          long hash;
          try {
            hash = Long.parseUnsignedLong(raw);
          } catch (NumberFormatException e) {
            reader.skipValue();
            continue;
          }
          if (hashes.contains(hash) && !names.containsKey(hash))
            names.put(hash, reader.nextString());
          else reader.skipValue();
        }
        reader.endObject();
      } catch (Exception e) {
        LunarCore.getLogger().warn("GM catalog: could not read TextMap{}", candidate);
      }
    }
    return GameData.getItemExcelMap().values().stream()
        .filter(LiveGmBackend::catalogSupported)
        .map(
            item -> {
              String category = category(item);
              int maxLevel =
                  category.equals("avatar") || category.equals("equipment")
                      ? 80
                      : category.equals("relic")
                          ? Math.min(
                              15, LunarCore.getConfig().getServerOptions().maxCustomRelicLevel)
                          : 0;
              int maxRank =
                  category.equals("avatar")
                      ? GameData.getAvatarExcelMap().get(item.getId()).getMaxRank()
                      : category.equals("equipment") ? item.getEquipmentExcel().getMaxRank() : 0;
              return new CatalogItem(
                  item.getId(),
                  cleanName(names.getOrDefault(item.getItemName(), "物品 #" + item.getId())),
                  category,
                  item.getRarity() == null ? 0 : item.getRarityNum(),
                  maxLevel,
                  maxRank);
            })
        .sorted(Comparator.comparingInt(CatalogItem::id))
        .toList();
  }

  private static String cleanName(String name) {
    return name.replaceAll("<[^>]*>", "").replace("\\n", " ");
  }

  static boolean catalogSupported(ItemExcel item) {
    return switch (item.getItemMainType()) {
      case Unknown, Display -> false;
      case Virtual -> Set.of(1, 2, 3, 11, 22, 32, 33, 34).contains(item.getId());
      case AvatarCard -> GameData.getAvatarExcelMap().containsKey(item.getId());
      case Equipment -> item.isEquipment();
      case Relic -> item.isRelic();
      default -> true;
    };
  }

  private static String category(ItemExcel item) {
    return switch (item.getItemMainType()) {
      case AvatarCard -> "avatar";
      case Equipment -> "equipment";
      case Relic -> "relic";
      default -> "item";
    };
  }

  public List<CommandRow> commands() {
    var manager = LunarCore.getCommandManager();
    if (manager == null) return List.of();
    return manager.getLabels().values().stream()
        .map(CommandHandler::getData)
        .filter(c -> !c.label().equals("stop"))
        .map(
            c ->
                new CommandRow(
                    c.label(), c.desc(), c.requireTarget(), DANGEROUS.contains(c.label())))
        .sorted(Comparator.comparing(CommandRow::label))
        .toList();
  }

  public Result execute(Operation operation) {
    GmValidation.validate(operation);
    if (operation.action().equals("banner-rerun")) return banners().rerun(operation);
    if (Set.of("banner-stop", "banner-enable").contains(operation.action())) return banners().setEnabled(operation);
    if (operation.action().equals("command")) return command(operation);
    return withPlayer(
        operation.uid(),
        player ->
            switch (operation.action()) {
              case "grant" -> grant(player, operation);
              case "mail" -> mail(player, operation);
              case "heal", "refill" -> legacy(operation.action() + " @" + player.getUid());
              case "unstuck" -> unstuck(player);
              default -> throw new GmError(400, "未知操作。");
            });
  }

  private GmBanners banners() {
    var server = LunarCore.getGameServer();
    if (server == null) throw new GmError(503, "游戏服务不可用。");
    return new GmBanners(Path.of(LunarCore.getConfig().getDataDir()), server.getGachaService(),
        java.time.Clock.systemUTC());
  }

  public List<BannerRow> bannerHistory() { return banners().history(); }

  public List<CurrentBannerRow> currentBanners() {
    var names = new HashMap<Integer, String>();
    for (var item : catalog()) names.put(item.id(), item.name());
    return banners().current(id -> names.getOrDefault(id, "物品 #" + id));
  }

  private Result withPlayer(int uid, java.util.function.Function<Player, Result> action) {
    var server = LunarCore.getGameServer();
    if (server == null) throw new GmError(503, "游戏服务不可用。");
    Player player = server.getOnlinePlayerByUid(uid);
    if (player == null) throw new GmError(409, "目标玩家已离线，请刷新玩家列表。");
    synchronized (player) {
      if (server.getOnlinePlayerByUid(uid) != player) throw new GmError(409, "目标会话已改变，请刷新后重试。");
      Result result = action.apply(player);
      player.save();
      return result;
    }
  }

  private Result grant(Player player, Operation op) {
    var excel = GameData.getItemExcelMap().get(op.itemId());
    if (excel == null || !catalogSupported(excel)) throw new GmError(400, "物品不存在或缺少可用资源定义。");
    switch (excel.getItemMainType()) {
      case AvatarCard -> {
        GmValidation.range(op.level(), 1, 80, "角色等级");
        GmValidation.range(
            op.rank(), 0, GameData.getAvatarExcelMap().get(op.itemId()).getMaxRank(), "星魂");
      }
      case Equipment -> {
        GmValidation.range(op.level(), 1, 80, "光锥等级");
        GmValidation.range(op.rank(), 1, excel.getEquipmentExcel().getMaxRank(), "叠影");
      }
      case Relic -> {
        GmValidation.range(
            op.level(),
            0,
            Math.min(15, LunarCore.getConfig().getServerOptions().maxCustomRelicLevel),
            "遗器等级");
        GmValidation.range(op.rank(), 0, 0, "遗器阶数");
      }
      default -> {
        if (op.level() != 0 || op.rank() != 0) throw new GmError(400, "普通物品无需填写等级或阶数。");
      }
    }
    if (excel.getItemMainType() == ItemMainType.AvatarCard) {
      if (op.amount() != 1) throw new GmError(400, "角色每次只能发放 1 位。");
      if (player.getAvatars().hasAvatar(op.itemId()))
        throw new GmError(409, "玩家已拥有该角色，请通过 avatar 指令修改属性。");
      var avatar = avatarFactory.apply(op.itemId());
      // Taking ownership binds the player's existing default-path data object.
      // Apply and persist the requested properties after that binding.
      if (!player.addAvatar(avatar)) throw new GmError(409, "角色未能添加。");
      new CommandArgs(
              null, new ArrayList<>(List.of("lv" + Math.max(1, op.level()), "r" + op.rank())))
          .setProperties(avatar);
      avatar.save();
      player.sendPacket(new emu.lunarcore.server.packet.send.PacketPlayerSyncScNotify(avatar));
      return Result.completed("已为 UID " + player.getUid() + " 添加角色 #" + op.itemId() + "。");
    }
    if (excel.isEquippable() && op.amount() > 100) throw new GmError(400, "装备每次最多发放 100 件。");
    if (excel.isEquippable()
        && player.getInventory().getTabByItemType(excel.getItemMainType()).getAvailableCapacity()
            < op.amount()) throw new GmError(409, "背包容量不足，未执行发放。");
    var items = new ArrayList<GameItem>();
    var properties =
        new CommandArgs(null, new ArrayList<>(List.of("lv" + op.level(), "r" + op.rank())));
    int count = excel.isEquippable() ? op.amount() : 1;
    for (int i = 0; i < count; i++) {
      var item = new GameItem(excel, excel.isEquippable() ? 1 : op.amount());
      properties.setProperties(item);
      items.add(item);
    }
    int before = virtualBalance(player, excel);
    if (excel.getItemMainType() == ItemMainType.Virtual
        && excel.getId() == 22
        && (long) before + op.amount() > Integer.MAX_VALUE)
      throw new GmError(409, "经验将超出存储上限，未执行发放。");
    var delivered = player.getInventory().addItems(items, true);
    int amount =
        before >= 0
            ? (int) Math.max(0L, (long) virtualBalance(player, excel) - before)
            : delivered.stream().mapToInt(GameItem::getCount).sum();
    if (amount == 0) throw new GmError(409, "物品未入库：可能已达上限或当前物品类型不支持发放。");
    return new Result(
        amount == op.amount() ? "completed" : "partial",
        List.of(
            "UID "
                + player.getUid()
                + " · #"
                + op.itemId()
                + " 请求 "
                + op.amount()
                + "，实际发放 "
                + amount
                + "。"));
  }

  private static int virtualBalance(Player player, ItemExcel excel) {
    if (excel.getItemMainType() != ItemMainType.Virtual) return -1;
    return switch (excel.getId()) {
      case 1 -> player.getHcoin();
      case 2 -> player.getScoin();
      case 3 -> player.getMcoin();
      case 11 -> player.getStamina();
      case 22 -> player.getExp();
      case 32 -> player.getTalentPoints();
      default -> -1;
    };
  }

  private Result mail(Player player, Operation op) {
    var mail = new Mail(op.title().strip(), op.sender().strip(), op.content());
    if (op.attachments() != null)
      for (var attachment : op.attachments()) {
        var excel = GameData.getItemExcelMap().get(attachment.itemId());
        if (excel == null
            || !catalogSupported(excel)
            || !(Inventory.isStackType(excel) || excel.getItemMainType() == ItemMainType.Virtual))
          throw new GmError(400, "邮件附件仅支持货币和可堆叠材料、消耗品。");
        mail.addAttachment(new GameItem(excel, attachment.amount()));
      }
    player.getMailbox().sendMail(mail);
    return Result.completed("邮件已保存并通知 UID " + player.getUid() + "，有效期 30 天。");
  }

  private Result unstuck(Player player) {
    if (player.getBattle() != null) throw new GmError(409, "战斗中无法解除卡死，请先结束战斗。");
    var floor = GameData.getFloorInfo(player.getPlaneId(), player.getFloorId());
    var anchor = floor == null ? null : floor.getStartAnchorInfo();
    if (anchor == null) throw new GmError(409, "当前场景缺少起点，未修改玩家位置。");
    if (!player.loadScene(
        player.getPlaneId(), player.getFloorId(), 0, anchor.getPos(), anchor.getRot(), true))
      throw new GmError(409, "场景重载失败。");
    return Result.completed("已通知 UID " + player.getUid() + " 返回当前场景起点。");
  }

  private Result command(Operation op) {
    var manager = LunarCore.getCommandManager();
    String message = op.command().strip();
    String label = message.split("\\s+", 2)[0].replaceFirst("^[/!]", "").toLowerCase(Locale.ROOT);
    var handler = manager.getCommands().get(label);
    if (handler == null) throw new GmError(400, "指令不存在。");
    String canonical = handler.getData().label();
    if (canonical.equals("stop")) throw new GmError(400, "停服请使用服务器终端或部署工具。");
    if (DANGEROUS.contains(canonical) && !message.equals(op.confirm()))
      throw new GmError(409, "该指令需要再次确认完整指令文本。");
    for (var token : message.split("\\s+")) {
      if (token.matches("x[0-9]+")) {
        try {
          if (Long.parseLong(token.substring(1)) > 10000) throw new NumberFormatException();
        } catch (NumberFormatException e) {
          throw new GmError(400, "网页指令单次数量上限为 10000，请使用资源发放表单。");
        }
      }
    }
    String command = message + (op.uid() > 0 ? " @" + op.uid() : "");
    if (handler.getData().requireTarget()) return withPlayer(op.uid(), player -> legacy(command));
    return legacy(command);
  }

  private Result legacy(String command) {
    var result = LunarCore.getCommandManager().invokeConsole(command);
    var output = result.output().isEmpty() ? List.of("指令已返回，无文本输出。请核对玩家状态。") : result.output();
    boolean error =
        !result.accepted()
            || output.stream()
                .anyMatch(line -> line.startsWith("Error:") || line.equals("Invalid Command!"));
    return new Result(error ? "failed" : "returned", output);
  }
}
