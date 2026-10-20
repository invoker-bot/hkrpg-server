package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import java.util.*;

/** The prologue starts with an already-present plot actor; no GetTrialAvatar task creates Kafka. */
final class OpeningPlayer {
  static final int MAIN = 1000101, INTRO = 100010102, KAFKA = 1001005, REMOVE = 100010120;
  static final int PLANE = 20001, FLOOR = 20001001;
  private static final Gson GSON = new Gson();
  private static final JsonElement REMOVAL =
      JsonParser.parseString(
          """
          {"Type":"Mission","OnInitSequece":[],"OnStartSequece":[{"TaskList":[
            {"$type":"RPG.GameCore.DestroyTrialPlayer"},
            {"$type":"RPG.GameCore.FinishPerformanceMission","Key":"Mission_100010112"}
          ]}]}
          """);

  static boolean removal(MissionInfo.SubMissionInfo sub) {
    return sub != null
        && sub.getId() == REMOVE
        && sub.getMainMissionID() == MAIN
        && sub.getParamInt1() == KAFKA
        && sub.getLevelPlaneID() == PLANE
        && sub.getLevelFloorID() == FLOOR
        && sub.getTask() != null
        && sub.getTask().getSourceDefinition() != null
        && REMOVAL.equals(JsonParser.parseString(sub.getTask().getSourceDefinition()));
  }

  static String source() {
    var main = GameData.getMissionInfos().get(MAIN);
    var intro = GameplayDefinitions.subs.get(INTRO);
    var remove = GameplayDefinitions.subs.get(REMOVE);
    var kafka = GameData.getSpecialAvatarExcelMap().get(KAFKA, 0);
    if (main == null
        || !Arrays.equals(main.getStartSubMissionList(), new int[] {INTRO})
        || intro == null
        || intro.getMainMissionID() != MAIN
        || intro.getLevelPlaneID() != PLANE
        || intro.getLevelFloorID() != FLOOR
        || !"Talk".equals(intro.getFinishType())
        || !"Mission_100010102".equals(intro.getParamStr1())
        || intro.getTask() == null
        || intro.getTask().getSourceDefinition() == null
        || remove == null
        || !"DelTrialAvatar".equals(remove.getFinishType())
        || !removal(remove)
        || kafka == null
        || kafka.getAvatarID() != 7005) return null;
    var signature = new JsonObject();
    signature.addProperty("initialTrial", KAFKA);
    var mainDefinition = GSON.toJsonTree(main).getAsJsonObject();
    mainDefinition.remove("SubMissionList");
    signature.add("main", mainDefinition);
    signature.add("intro", GSON.toJsonTree(intro));
    signature.add("introTask", JsonParser.parseString(intro.getTask().getSourceDefinition()));
    signature.add("removal", GSON.toJsonTree(remove));
    signature.add("removalTask", REMOVAL);
    return signature.toString();
  }

  static boolean eligible(Player player) {
    var state = player.getGameplay().getMissionTrials();
    return state.getParty() == null
        && state.getOpeningSource() == null
        && !player.hasCreatedCharacter()
        && player.getGameplay().getCurrentMissionRaid() == 0
        && player.getPlaneId() == PLANE
        && player.getFloorId() == FLOOR
        && MissionStateRecords.mainDoing(player, MAIN)
        && MissionStateRecords.subAccepted(player, INTRO)
        && !player.getGameplay().getSubMissions().containsKey(REMOVE)
        && !player.getGameplay().getCancelledSubs().contains(REMOVE)
        && !state.getExecuted().containsKey(100010110)
        && !state.getRemovals().containsKey(REMOVE);
  }

  static boolean matches(Player player, MissionTrialState.Party party, boolean restoring) {
    return party.getOpeningSource() != null
        && party.getOpeningSource().equals(source())
        && party.getMain() == MAIN
        && party.getPlane() == PLANE
        && party.getFloor() == FLOOR
        && party.getRaid() == 0
        && MissionStateRecords.subAccepted(player, INTRO)
        && !MissionStateRecords.subCompleted(player, REMOVE)
        && !player.getGameplay().getCancelledSubs().contains(REMOVE)
        && !player.getGameplay().getMissionTrials().getRemovals().containsKey(REMOVE)
        && (!restoring
            || party
                .getOpeningSource()
                .equals(player.getGameplay().getMissionTrials().getOpeningSource()))
        && party.getRoster().contains(KAFKA)
        && !party.isVirtual()
        && Set.of(KAFKA, 1001006).containsAll(party.getRoster())
        && party.getActors().keySet().equals(new HashSet<>(party.getRoster()));
  }

  private OpeningPlayer() {}
}
