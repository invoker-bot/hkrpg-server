package emu.lunarcore.command.commands;

import emu.lunarcore.LunarCore;
import emu.lunarcore.command.*;
import java.time.*;
import java.util.*;

/** Console clock projection and read-only archives; isolated smoke classpath only. */
@Command(label="dailytravelclockfixture",requireTarget=false,permission="fixture.console-only",
    desc="仅隔离日常移动网络检查：设置在线测试玩家时钟并读取轮次记录")
public final class DailyTravelClockFixtureCommand implements CommandHandler {
  @Override public void execute(CommandArgs args) {
    if(args.getSender()!=null || args.getList().size()!=3) return;
    int uid=Integer.parseInt(args.getList().get(0)),nonce=Integer.parseInt(args.getList().get(2));
    long second=Long.parseLong(args.getList().get(1));
    var player=LunarCore.getGameServer().getOnlinePlayerByUid(uid);
    if(player==null) throw new IllegalStateException("测试玩家尚未在线");
    synchronized(player) {
      var clock=Clock.fixed(Instant.ofEpochSecond(second),ZoneOffset.UTC);
      player.getMissionManager().setClock(clock); player.getDailyManager().setClock(clock);
      var periods=player.getGameplay().getDailyMissionPeriods();
      var archives=periods.getArchives().values().stream().map(view->Map.of(
          "mainId",view.getMainId(),"day",view.getDay(),"mainPhase",view.getMain().getPhase().name(),
          "mainProgress",view.getMain().getProgress(),"subCount",view.getSubs().size(),
          "rewardKeys",view.getRewardKeys())).toList();
      var expired=periods.getExpired().values().stream().map(view->Map.of(
          "mainId",view.getMainId(),"day",view.getDay(),"expiredAt",view.getExpiredAt(),
          "mainPhase",view.getMain().getPhase().name(),"mainProgress",view.getMain().getProgress(),
          "subCount",view.getSubs().size())).toList();
      System.out.println("HKRPG_DAILY_TRAVEL_CLOCK_VIEW:"+new com.google.gson.Gson().toJson(Map.of(
          "uid",uid,"nonce",nonce,"clock",second,"archives",archives,"expired",expired,
          "runDays",periods.getRunDays())));
    }
    System.out.println("HKRPG_DAILY_TRAVEL_CLOCK_ACK:"+uid+":"+second+":"+nonce);
    System.out.flush();
  }
}
