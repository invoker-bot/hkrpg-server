package emu.lunarcore.command.commands;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.command.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.hkrpg.server.gameplay.GameplayDefinitions;
import org.hkrpg.server.gameplay.StoryLineConditions;

/** Original entrance history only. Never seeds a gun, run, ammunition, defeat or pickup. */
@Command(label="wolfbroammofixture", requireTarget=false, permission="fixture.console-only",
    desc="仅隔离 Level11 弹药验收：原1031501/103150120章节完成历史")
public final class WolfBroAmmoFixtureCommand implements CommandHandler {
  private static void require(boolean ok, String why) { if (!ok) throw new IllegalStateException(why); }
  @Override public void execute(CommandArgs args) {
    if (args.getSender() != null || args.getList().size() != 3) return;
    int uid = Integer.parseInt(args.getList().get(0)), nonce = Integer.parseInt(args.getList().get(2));
    require(uid == 10001 && nonce > 0 && args.getList().get(1).equals("unlock"), "Invalid owned ammo fixture request");
    try {
      var cwd = Path.of("").toRealPath();
      var marker = JsonParser.parseString(Files.readString(cwd.resolve("resources-fixture/resource-manifest.json"))).getAsJsonObject();
      require(cwd.getFileName().toString().startsWith("wolfbro-ammo-native-")
          && marker.get("testFixture").getAsBoolean() && marker.get("wolfBroAmmoFixture").getAsBoolean()
          && marker.get("sourceCommit").getAsString().equals("724b139d8c9c32d12552eb95745a4fee72bfe48b"), "Not an owned Level11 fixture");
    } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    var entry = GameData.getMapEntranceExcelMap().get(2032101);
    var sub = GameplayDefinitions.subs.get(103150120);
    require(entry != null && entry.getPlaneID() == 20321 && entry.getFloorID() == 20321001
        && Arrays.equals(entry.getFinishMainMissionList(), new int[] {1031501})
        && Arrays.equals(entry.getFinishSubMissionList(), new int[] {103150120})
        && sub != null && sub.getId() == 103150120 && sub.getMainMissionID() == 1031501
        && sub.getProgress() == 1 && sub.getFinishType().equals("Talk")
        && sub.getParamStr1().equals("Mission_103150120")
        && GameData.getMissionInfos(1031501) != null, "Actual full/partial source entrance prerequisites not loaded");
    var player = LunarCore.getGameServer().getOnlinePlayerByUid(uid);
    require(player != null && player.getUid() == uid, "Owned fixture player is not online");
    synchronized (player) {
      var gson = new Gson();
      var before = gson.toJsonTree(player.getGameplay().getWolfBro());
      require(player.getGameplay().getWolfBro().getActiveId() == 0
          && player.getGameplay().getWolfBro().getRuns().isEmpty(), "Ammo validation already created a run");
      require(!player.getGameplay().getMainMissions().containsKey(1031501)
          && !player.getGameplay().getSubMissions().containsKey(103150120), "History fixture would overwrite an existing prerequisite");
      int credit = player.getScoin(), jades = player.getHcoin();
      long at = Instant.now().getEpochSecond();
      for (int id : List.of(1031501, 103150120)) {
        var state = new QuestState(id);
        require(state.advance(1, 1, at) && state.reserveReward(), "Source history fixture could not finish");
        state.closeReward();
        (id == 1031501 ? player.getGameplay().getMainMissions() : player.getGameplay().getSubMissions()).put(id, state);
      }
      require(player.getMissionManager().finished(1031501) && StoryLineConditions.finished(player, 103150120), "Original entrance remains locked");
      require(before.equals(gson.toJsonTree(player.getGameplay().getWolfBro()))
          && player.getScoin() == credit && player.getHcoin() == jades, "Prerequisite history mutated WolfBro or currency");
      player.save();
      System.out.println("HKRPG_WOLFBRO_AMMO_FIXTURE_VIEW:" + gson.toJson(Map.of("uid", uid, "nonce", nonce,
          "seededOriginalMainMission", 1031501, "seededOriginalSubMission", 103150120,
          "wolfBroStateSeeded", false, "ammoOrDropSeeded", false, "currencyGranted", false,
          "originalSourceRowsModified", false)));
    }
    System.out.println("HKRPG_WOLFBRO_AMMO_FIXTURE_ACK:" + uid + ":unlock:" + nonce);
    System.out.flush();
  }
}
