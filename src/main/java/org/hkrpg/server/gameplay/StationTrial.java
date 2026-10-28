package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import java.util.*;

/** Operator-approved compatibility for the parameterless station Himeko scripts, not a generic task interpreter. */
final class StationTrial {
  static final int MAIN = 1000300, CREATE = 100030100, UNBIND = 100040112, REMOVE = 100040125;
  static final int HIMEKO = 1011003, PLANE = 10001, FLOOR = 10001001;
  private static final Gson GSON = new Gson();
  private static final Set<Integer> MAINS = Set.of(MAIN,1000301,1000302,1000303,1000400,1000401);

  private static JsonElement graph(int id) {
    String action = id == CREATE
        ? "{\"$type\":\"RPG.GameCore.CreateTrialPlayer\"},{\"$type\":\"RPG.GameCore.SetVirtualLineupBindPlane\"}"
        : "{\"$type\":\"RPG.GameCore." + (id == UNBIND ? "RemoveVirtualLineupBindPlane" : "DestroyTrialPlayer") + "\"}";
    return JsonParser.parseString("{\"Type\":\"Mission\",\"OnInitSequece\":[],\"OnStartSequece\":[{"
        + (id == REMOVE ? "" : "\"Order\":1,") + "\"TaskList\":[" + action
        + ",{\"$type\":\"RPG.GameCore.FinishPerformanceMission\",\"Key\":\"Mission_"+id+"\"}]}]}");
  }

  static String source() {
    var signature = new JsonObject();
    signature.addProperty("compatibility", "station-himeko-v1");
    signature.addProperty("trial", HIMEKO);
    for (int id : List.of(CREATE, UNBIND, REMOVE)) {
      var sub = GameplayDefinitions.subs.get(id);
      int main = id == CREATE ? MAIN : id == UNBIND ? 1000400 : 1000401;
      int prior = id == CREATE ? 100030101 : id == UNBIND ? 100040111 : 100040124;
      int plane = id == UNBIND ? 20003 : PLANE, floor = id == UNBIND ? 20003001 : FLOOR;
      if (sub == null || sub.getId() != id || sub.getMainMissionID() != main
          || !"Talk".equals(sub.getFinishType()) || !"AnySequence".equals(sub.getTakeType())
          || !Arrays.equals(sub.getTakeParamIntList(),new Integer[]{prior})
          || sub.getProgress() != 1 || sub.getLevelPlaneID() != plane || sub.getLevelFloorID() != floor
          || !("Mission_"+id).equals(sub.getParamStr1()) || sub.getTask() == null
          || sub.getTask().getSourceDefinition() == null
          || !graph(id).equals(JsonParser.parseString(sub.getTask().getSourceDefinition()))) return null;
      signature.add("sub-"+id, GSON.toJsonTree(sub));
      signature.add("task-"+id, graph(id));
    }
    var role = GameData.getSpecialAvatarExcelMap().get(HIMEKO,0);
    if (role == null || role.getId() != HIMEKO || role.getAvatarID() != 1003
        || role.getLevel() != 8 || role.getPromotion() != 0 || role.isIsUseWorldLevel()
        || !"TYPE_TRIAL".equals(role.getType())) return null;
    return signature.toString();
  }

  static boolean active(Player p) {
    return MissionStateRecords.subAccepted(p,CREATE)
        && MissionStateRecords.subCompleted(p,100030101)
        && MAINS.stream().anyMatch(id -> MissionStateRecords.mainDoing(p,id))
        && !MissionStateRecords.subAccepted(p,REMOVE)
        && !MissionStateRecords.mainCompleted(p,1000401)
        && !MissionStateRecords.mainAccepted(p,1000402)
        && !MissionStateRecords.mainAccepted(p,1000501)
        && !p.getGameplay().getCancelledSubs().contains(UNBIND)
        && !p.getGameplay().getCancelledSubs().contains(REMOVE);
  }

  static boolean unbound(Player p) {
    // The original post-battle task unbinds the virtual lineup before the player seeks Himeko.
    return MissionStateRecords.subAccepted(p, UNBIND);
  }

  static boolean place(Player p) {
    return p.getPlaneId() == PLANE && p.getFloorId() == FLOOR
        || p.getPlaneId() == 20003 && p.getFloorId() == 20003001
            && MissionStateRecords.subCompleted(p,CREATE)
        || p.getPlaneId() == 80112 && p.getFloorId() == 80112001
            && MissionStateRecords.subCompleted(p,UNBIND);
  }

  static boolean eligible(Player p) {
    return p.getGameplay().getMissionTrials().getParty() == null
        && p.getGameplay().getMissionTrials().getStationSource() == null
        && p.getGameplay().getCurrentMissionRaid() == 0 && active(p) && !unbound(p) && place(p);
  }

  // A full operator-edited team retains its formal document; reserve one temporary slot and keep its captain.
  static List<Integer> roster(List<Integer> entry, int leader) {
    var roster = new ArrayList<>(entry);
    int captain = entry.get(leader);
    roster.remove(Integer.valueOf(1003));
    while (roster.size() > 3) {
      int last = roster.size()-1;
      if (roster.get(last) == captain) last--;
      roster.remove(last);
    }
    roster.add(HIMEKO);
    return roster;
  }

  static boolean matches(Player p, MissionTrialState.Party party, boolean restoring) {
    var normal = p.getLineupManager().getLineups()[party.getIndex()];
    boolean original = normal != null && normal.getAvatars().equals(party.getEntryRoster());
    boolean finalized = party.isStationFinalizing() && party.getStationPreset() != null
        && normal != null && normal.getAvatars().equals(party.getStationPreset())
        && normal.getLeader() == party.getStationPresetLeader();
    return active(p) && place(p) && party.getMain() == MAIN && party.getPlane() == PLANE
        && party.getFloor() == FLOOR && party.getRaid() == 0 && !party.isVirtual()
        && party.getOpeningSource() == null && party.getSources().isEmpty()
        && party.getBaseActors().isEmpty() && party.getActors().keySet().equals(Set.of(HIMEKO))
        && party.getStationSource().equals(source())
        && (!restoring || party.getStationSource().equals(p.getGameplay().getMissionTrials().getStationSource()))
        && (original || finalized)
        && normal.getLeader() >= 0 && normal.getLeader() < normal.size()
        && (party.isStationEdited() ? validEdit(p, party)
            : !party.isStationFinalizing() && party.getStationPreset() == null
                && roster(party.getEntryRoster(),normal.getLeader()).equals(party.getRoster()));
  }

  private static boolean validEdit(Player p, MissionTrialState.Party party) {
    var ids = party.getRoster();
    if (ids == null || ids.isEmpty() || ids.size() > 4 || new HashSet<>(ids).size() != ids.size())
      return false;
    var actorIds = new HashSet<Integer>();
    for (int id : ids) {
      int actorId = id == HIMEKO ? 1003 : id;
      if (!actorIds.add(actorId)) return false;
      if (id != HIMEKO) {
        var actor = p.getAvatars().getAvatars().get(id);
        if (actor == null || actor.getAvatarId() != id || actor.getOwner() != p
            || actor.getOwnerUid() != p.getUid()) return false;
      }
    }
    var preset = party.getStationPreset();
    if (preset == null) return !party.isStationFinalizing();
    if (preset.size() != ids.size() || party.getStationPresetLeader() != party.getLeader()
        || new HashSet<>(preset).size() != preset.size()) return false;
    for (int i = 0; i < ids.size(); i++)
      if (preset.get(i) != (ids.get(i) == HIMEKO ? 1003 : ids.get(i))) return false;
    return true;
  }

  private StationTrial() {}
}
