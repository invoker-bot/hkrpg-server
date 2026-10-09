package emu.lunarcore.command.commands;

import emu.lunarcore.LunarCore;
import emu.lunarcore.command.Command;
import emu.lunarcore.command.CommandArgs;
import emu.lunarcore.command.CommandHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** Compiled into an isolated smoke classpath only; never packaged in the server jar. */
@Command(
    label = "dailyclockfixture",
    requireTarget = false,
    permission = "fixture.console-only",
    desc = "仅隔离网络检查：设置在线测试玩家的任务与日常时钟")
public final class DailyMissionClockFixtureCommand implements CommandHandler {
  @Override
  public void execute(CommandArgs args) {
    if (args.getSender() != null || args.getList().size() != 3) return;
    int uid = Integer.parseInt(args.getList().get(0));
    long second = Long.parseLong(args.getList().get(1));
    int nonce = Integer.parseInt(args.getList().get(2));
    var player = LunarCore.getGameServer().getOnlinePlayerByUid(uid);
    if (player == null) throw new IllegalStateException("测试玩家尚未在线");
    synchronized (player) {
      var clock = Clock.fixed(Instant.ofEpochSecond(second), ZoneOffset.UTC);
      player.getMissionManager().setClock(clock);
      player.getDailyManager().setClock(clock);
      var periods = player.getGameplay().getDailyMissionPeriods();
      var views =
          periods.getExpired().values().stream()
              .map(
                  view ->
                      java.util.Map.of(
                          "mainId",
                          view.getMainId(),
                          "day",
                          view.getDay(),
                          "expiredAt",
                          view.getExpiredAt(),
                          "mainPhase",
                          view.getMain().getPhase().name(),
                          "mainProgress",
                          view.getMain().getProgress(),
                          "subCount",
                          view.getSubs().size(),
                          "aggregateProgress",
                          view.getSubs().get(300020102).getProgress(),
                          "aggregateGoal",
                          view.getGoals().get(300020102)))
              .toList();
      System.out.println(
          "HKRPG_DAILY_CLOCK_FIXTURE_VIEW:"
              + new com.google.gson.Gson()
                  .toJson(
                      java.util.Map.of(
                          "uid",
                          uid,
                          "nonce",
                          nonce,
                          "clock",
                          second,
                          "expired",
                          views,
                          "runDays",
                          periods.getRunDays())));
    }
    System.out.println("HKRPG_DAILY_CLOCK_FIXTURE_ACK:" + uid + ":" + second + ":" + nonce);
    System.out.flush();
  }
}
