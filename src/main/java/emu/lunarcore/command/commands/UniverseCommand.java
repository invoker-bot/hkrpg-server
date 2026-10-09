package emu.lunarcore.command.commands;

import emu.lunarcore.command.*;
import emu.lunarcore.util.Utils;

@Command(
    label = "universe",
    permission = "player.universe",
    requireTarget = true,
    desc =
        "/universe status|start [area]|enter [site]|resume|leave|quit|select [selection] [buff]|enhance"
            + " [buff]，实验宇宙探索")
public final class UniverseCommand implements CommandHandler {
  public void execute(CommandArgs args) {
    var manager = args.getTarget().getUniverseManager();
    switch (args.get(0)) {
      case "start" -> args.sendMessage(manager.start(Utils.parseSafeInt(args.get(1))).name());
      case "enter" -> args.sendMessage(manager.enter(Utils.parseSafeInt(args.get(1))).name());
      case "resume" -> args.sendMessage(manager.resume() ? "已恢复探索房间" : "当前探索无法恢复");
      case "leave" -> args.sendMessage(manager.leave(true).name());
      case "quit" -> args.sendMessage(manager.abandon().name());
      case "select" ->
          args.sendMessage(
              manager
                  .selectBlessing(Utils.parseSafeInt(args.get(1)), Utils.parseSafeInt(args.get(2)))
                  .name());
      case "enhance" ->
          args.sendMessage(manager.enhanceBlessing(Utils.parseSafeInt(args.get(1))).name());
      default -> {
        var r = args.getTarget().getGameplay().getRogue();
        args.sendMessage(
            "宇宙状态="
                + r.getPhase()
                + " 区域="
                + r.getAreaId()
                + " 站点="
                + r.getSiteId()
                + " 房间="
                + r.getRoomId()
                + " 已完成="
                + r.getCleared()
                + " 选择序号="
                + r.getSelectionId()
                + " 可选祝福="
                + r.getOffers()
                + " 已有祝福="
                + r.getBlessings());
      }
    }
  }
}
