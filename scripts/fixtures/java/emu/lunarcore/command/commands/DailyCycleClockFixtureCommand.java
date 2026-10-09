package emu.lunarcore.command.commands;

import emu.lunarcore.LunarCore;
import emu.lunarcore.command.Command;
import emu.lunarcore.command.CommandArgs;
import emu.lunarcore.command.CommandHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** Isolated smoke classpath only: clock changes never seed quests, rewards or finished runs. */
@Command(
    label = "cycleclockfixture",
    requireTarget = false,
    permission = "fixture.console-only",
    desc = "仅隔离网络检查：设置日常周期时钟")
public final class DailyCycleClockFixtureCommand implements CommandHandler {
  @Override
  public void execute(CommandArgs args) {
    if (args.getSender() != null || args.getList().size() != 3) return;
    int uid = Integer.parseInt(args.getList().get(0));
    long second = Long.parseLong(args.getList().get(1));
    int nonce = Integer.parseInt(args.getList().get(2));
    var player = LunarCore.getGameServer().getOnlinePlayerByUid(uid);
    if (player == null) throw new IllegalStateException("测试玩家尚未在线");
    synchronized (player) {
      player.getDailyManager().setClock(Clock.fixed(Instant.ofEpochSecond(second), ZoneOffset.UTC));
      System.out.println(
          "HKRPG_CYCLE_CLOCK_FIXTURE_VIEW:"
              + new com.google.gson.Gson()
                  .toJson(
                      java.util.Map.of(
                          "uid",
                          uid,
                          "nonce",
                          nonce,
                          "clock",
                          second,
                          "savedDayBeforeRefresh",
                          player.getGameplay().getDailyDay(),
                          "storedWindowDaysBeforeRefresh",
                          player.getGameplay().getDailyCycleQuests().getWindows().keySet())));
    }
    System.out.println("HKRPG_CYCLE_CLOCK_FIXTURE_ACK:" + uid + ":" + second + ":" + nonce);
    System.out.flush();
  }
}
