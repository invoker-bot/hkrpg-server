package org.hkrpg.server.gm;

import java.util.Set;
import java.util.UUID;
import org.hkrpg.server.gm.GmModels.*;

final class GmValidation {
  private GmValidation() {}

  static void validate(Operation op) {
    if (op == null || op.requestId() == null) fail("缺少请求编号。");
    try {
      if (!UUID.fromString(op.requestId()).toString().equals(op.requestId())) fail("请求编号格式不正确。");
    } catch (IllegalArgumentException e) {
      fail("请求编号格式不正确。");
    }
    if (op.action() == null
        || !Set.of("heal", "refill", "unstuck", "grant", "mail", "command", "banner-rerun", "banner-stop", "banner-enable", "banner-batch-stop", "banner-batch-enable", "banner-complete", "shop-list", "shop-unlist").contains(op.action()))
      fail("未知操作。");
    if (op.uid() < 0 || !op.action().startsWith("banner-") && !op.action().startsWith("shop-") && !op.action().equals("command") && op.uid() == 0)
      fail("请选择目标玩家。");
    switch (op.action()) {
      case "shop-list", "shop-unlist" -> {
        if (op.uid() != 0) fail("商品上下架是全服操作，无需指定玩家。");
        if (op.shopGoods() == null || op.shopGoods().isEmpty() || op.shopGoods().size() > 500)
          fail("请选择 1–500 个商品。");
        var targets = new java.util.HashSet<ShopTarget>();
        for (var target : op.shopGoods()) {
          if (target == null || target.shopId() <= 0 || target.goodsId() <= 0 || !targets.add(target))
            fail("商品列表包含无效或重复的商店/商品编号。");
        }
      }
      case "banner-batch-stop", "banner-batch-enable" -> {
        if (op.uid() != 0) fail("卡池启停是全服操作，无需指定玩家。");
        if (op.bannerId() != null || op.bannerIds() == null || op.bannerIds().isEmpty()
            || op.bannerIds().size() > 500) fail("请选择 1–500 个卡池。");
        var ids = new java.util.HashSet<Integer>();
        for (var id : op.bannerIds())
          if (id == null || id <= 0 || !ids.add(id)) fail("卡池列表包含无效或重复的 ID。");
        range(op.durationDays() == null ? 30 : op.durationDays(), 1, 365, "首次开放天数");
      }
      case "banner-complete" -> {
        if (op.uid() != 0) fail("补齐卡池是全服操作，无需指定玩家。");
      }
      case "banner-stop", "banner-enable" -> {
        if (op.uid() != 0) fail("卡池启停是全服操作，无需指定玩家。");
        if (op.bannerId() == null) fail("请选择当前卡池。");
        range(op.bannerId(), 1, Integer.MAX_VALUE, "卡池 ID");
        range(op.durationDays() == null ? 30 : op.durationDays(), 1, 365, "首次开放天数");
      }
      case "banner-rerun" -> {
        if (op.uid() != 0) fail("卡池复刻是全服操作，无需指定玩家。");
        if (op.bannerKey() == null || !op.bannerKey().matches("[0-9]{1,2}\\.[0-9]{1,2}-[0-9]{4}"))
          fail("请选择历史卡池。");
        range(op.durationDays() == null ? 30 : op.durationDays(), 1, 365, "复刻天数");
      }
      case "grant" -> {
        range(op.itemId(), 1, Integer.MAX_VALUE, "物品 ID");
        range(op.amount(), 1, 1_000_000, "数量");
        range(op.level(), 0, 80, "等级");
        range(op.rank(), 0, 6, "阶数");
      }
      case "mail" -> {
        text(op.title(), 1, 80, "邮件标题");
        text(op.sender(), 1, 40, "发件人");
        text(op.content(), 1, 4000, "邮件正文");
        if (op.attachments() != null) {
          if (op.attachments().size() > 5) fail("邮件最多包含 5 种附件。");
          var ids = new java.util.HashSet<Integer>();
          for (var attachment : op.attachments()) {
            if (attachment == null) fail("附件格式不正确。");
            range(attachment.itemId(), 1, Integer.MAX_VALUE, "附件 ID");
            range(attachment.amount(), 1, 1_000_000, "附件数量");
            if (!ids.add(attachment.itemId())) fail("请合并相同物品的附件。");
          }
        }
      }
      case "command" -> {
        text(op.command(), 1, 2048, "指令");
        if (op.command().contains("\n") || op.command().contains("\r")) fail("一次只能执行一条指令。");
        if (java.util.Arrays.stream(op.command().split("\\s+")).anyMatch(t -> t.startsWith("@")))
          fail("请在目标 UID 栏指定玩家，指令中无需填写 @UID。");
        if (op.confirm() != null && op.confirm().length() > 2048) fail("确认文本过长。");
      }
      default -> {}
    }
  }

  static void text(String value, int min, int max, String label) {
    if (value == null
        || value.strip().length() < min
        || value.length() > max
        || value.indexOf('\0') >= 0) fail(label + "长度必须为 " + min + "–" + max + " 个字符。");
  }

  static void range(int value, int min, int max, String label) {
    if (value < min || value > max) fail(label + "必须在 " + min + "–" + max + " 之间。");
  }

  static void fail(String message) {
    throw new GmError(400, message);
  }
}
