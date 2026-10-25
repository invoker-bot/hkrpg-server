package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Bounded compatibility for the missing event in the original station boss script. */
public final class StoryBattleEvents {
  private static final int LEGACY = 20003012, EVENT = 20003011, SUB = 100040110, MAIN = 1000400;
  // Full original Mission_100040110.json, serialized by Gson's retained source definition.
  private static final String SCRIPT = "63f3a07ab496ccf4a08a9ea627175dff54b3122fc9f5f5a397edfe13b77d80ae";

  public static int canonicalEvent(Player player, int requested) {
    if (requested != LEGACY) return requested;
    int world = player.getWorldLevel();
    // A future real event row takes precedence; never replace an existing battle by its neighbour.
    if (GameData.getPlaneEventExcelMap().get(requested, world) != null) return requested;
    var scene = player.getScene();
    var g = player.getGameplay();
    var sub = GameplayDefinitions.subs.get(SUB);
    var main = GameData.getMainMissionExcelMap().get(MAIN);
    var graph = GameData.getMissionInfos().get(MAIN);
    if (world < 0 || world > 6 || player.isInBattle() || !StoryDoors.world(scene)
        || scene.getPlayer() != player || scene.getPlaneId() != 20003 || scene.getFloorId() != 20003001
        || player.getPlaneId() != 20003 || player.getFloorId() != 20003001
        || player.getInstance() != null || g.getCurrentMissionRaid() != 0 || g.getTrialRun() != null
        || g.getRogue().isInside() || g.getParkourVenue().inside() || g.getStoryLines().getCurrentId() != 0
        || main == null || main.getId() != MAIN || main.isInRaid() || !"Main".equals(main.getType())
        || graph == null || graph.getMainMissionID() != MAIN || graph.getSubMissionById(SUB) != sub
        || sub == null || sub.getId() != SUB || sub.getMainMissionID() != MAIN
        || sub.getLevelPlaneID() != 20003 || sub.getLevelFloorID() != 20003001
        || !"StageWin".equals(sub.getFinishType()) || !"Equal".equals(sub.getParamType())
        || sub.getParamInt1() != EVENT || sub.getParamInt2() != 0 || sub.getParamInt3() != 0
        || sub.getProgress() != 1 || !"AnySequence".equals(sub.getTakeType())
        || !Arrays.equals(sub.getTakeParamIntList(), new Integer[]{100040109})
        || !MissionStateRecords.mainDoing(player, MAIN) || !MissionStateRecords.subCompleted(player, 100040109)
        || !MissionStateRecords.doing(g.getSubMissions().get(SUB), SUB, 1)
        || !player.getMissionManager().canFinishFromAction(SUB)
        || sub.getTask() == null || !sub.getTask().declaresBattle(LEGACY)
        || !SCRIPT.equals(digest(sub.getTask().getSourceDefinition()))
        || SceneEventBattles.resolveClientEvent(EVENT, world) == null) return 0;
    return EVENT;
  }

  private static String digest(String text) {
    if (text == null) return null;
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }

  private StoryBattleEvents() {}
}
