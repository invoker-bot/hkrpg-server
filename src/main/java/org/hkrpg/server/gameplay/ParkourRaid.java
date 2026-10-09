package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Only the resource-authored, unsaved Player/SkipUI parkour venue is supported. */
public final class ParkourRaid {
  private final Player player;

  public ParkourRaid(Player player) {
    this.player = player;
  }

  public static boolean supported(int raid, int world) {
    var resource = GameplayDefinitions.raids.get((long) raid * 100 + world);
    int id = GameplayDefinitions.parkourConstants.getOrDefault("parkour_raid_id", 0);
    return id > 0
        && raid == id
        && world == 0
        && resource != null
        && resource.RaidID() == raid
        && resource.WorldLevel() == world
        && "TreasureChallenge".equals(resource.Type())
        && "Player".equals(resource.TeamType())
        && "SkipUI".equals(resource.EnterType())
        && resource.SkipRewardOnFinish()
        && empty(resource.MainMissionIDList())
        && empty(resource.TrialAvatarList())
        && empty(resource.RaidTargetID())
        && empty(resource.RewardList());
  }

  private static boolean empty(List<Integer> values) {
    return values != null && values.isEmpty();
  }

  private boolean sourceMatches(GameplayState.RogueReturn location) {
    if (location == null || location.getPos() == null || location.getRot() == null) return false;
    var entry = GameData.getMapEntranceExcelMap().get(location.getEntry());
    var plane = GameData.getMazePlaneExcelMap().get(location.getPlane());
    return entry != null
        && plane != null
        && entry.getPlaneID() == location.getPlane()
        && entry.getFloorID() == location.getFloor()
        && (plane.getPlaneType() == PlaneType.Town || plane.getPlaneType() == PlaneType.Maze);
  }

  private boolean venueMatches(GameplayState.ParkourVenue venue) {
    var entry = GameData.getMapEntranceExcelMap().get(venue.getEntry());
    return venue.inside()
        && supported(venue.getRaidId(), venue.getWorldLevel())
        && entry != null
        && entry.getPlaneID() == venue.getPlane()
        && entry.getFloorID() == venue.getFloor()
        && venue.getEntry()
            == GameplayDefinitions.parkourConstants.getOrDefault("parkour_gameplay_entrance_id", 0)
        && venue.getFloor()
            == GameplayDefinitions.parkourConstants.getOrDefault("parkour_gameplay_floor_id", 0)
        && sourceMatches(venue.getReturnTo());
  }

  public Retcode enter(int raid, int world, int saved, int prop, List<Integer> selected) {
    synchronized (player) {
      if (!supported(raid, world) || saved != 0 || prop != 0) return Retcode.REQ_PARA_INVALID;
      var venue = player.getGameplay().getParkourVenue();
      if (venue.inside()) return Retcode.RAID_DOING;
      if (player.getGameplay().getCurrentMissionRaid() != 0) return Retcode.RAID_DOING;
      if (new ParkourManager(player).activeWindow().isEmpty()
          || GameplayDefinitions.parkourLevels.keySet().stream()
              .noneMatch(new ParkourManager(player)::unlocked))
        return Retcode.ACTIVITY_RAID_NOT_OPEN;
      var team = player.getCurrentLineup();
      if (player.isInBattle()
          || player.getGameplay().getStoryLines().getCurrentId() != 0
          || player.getGameplay().getStoryLines().isTransitionPending()
          || player.getGameplay().getRogue().isInside()
          || player.getGameplay().getRhythm().getActiveLevel() != 0
          || player.getGameplay().getParkour().getActiveLevel() != 0
          || team == null
          || team.isExtraLineup()
          || player.getScene() == null) return Retcode.REQ_PARA_INVALID;
      var actual = List.copyOf(team.getAvatars());
      if (actual.isEmpty()
          || actual.size() > 4
          || new HashSet<>(actual).size() != actual.size()
          || team.getLeader() < 0
          || team.getLeader() >= actual.size()
          || selected == null
          || !selected.isEmpty() && !selected.equals(actual)
          || actual.stream()
              .anyMatch(
                  id ->
                      player.getAvatarById(id) == null
                          || player.getAvatarById(id).getAvatarId() != id
                          || player.getAvatarById(id).getAvatarType()
                              != AvatarType.AVATAR_FORMAL_TYPE))
        return Retcode.RAID_AVATAR_NOT_MATCH;
      if (!sourceMatches(new GameplayState.RogueReturn(player)))
        return Retcode.SCENE_ENTRY_ID_NOT_MATCH;
      int id = GameplayDefinitions.parkourConstants.getOrDefault("parkour_gameplay_entrance_id", 0);
      var entry = GameData.getMapEntranceExcelMap().get(id);
      var plane = entry == null ? null : GameData.getMazePlaneExcelMap().get(entry.getPlaneID());
      if (entry == null
          || plane == null
          || plane.getPlaneType() != PlaneType.Raid
          || entry.getFloorID()
              != GameplayDefinitions.parkourConstants.getOrDefault("parkour_gameplay_floor_id", 0))
        return Retcode.SCENE_ENTRY_ID_NOT_MATCH;
      venue.enter(raid, world, entry.getPlaneID(), entry.getFloorID(), id, player);
      if (!player.enterScene(id, 0, false)) {
        venue.close();
        return Retcode.SCENE_ENTRY_ID_NOT_MATCH;
      }
      player.save();
      return Retcode.SUCC;
    }
  }

  public Retcode leave(int raid, boolean saved) {
    synchronized (player) {
      var venue = player.getGameplay().getParkourVenue();
      if (!venue.inside()) return Retcode.NOT_IN_RAID;
      if (raid != venue.getRaidId()) return Retcode.RAID_ID_NOT_MATCH;
      if (saved) return Retcode.RAID_CAN_NOT_SAVE;
      if (player.isInBattle()
          || !venueMatches(venue)
          || player.getEntryId() != venue.getEntry()
          || player.getFloorId() != venue.getFloor()
          || player.getPlaneId() != venue.getPlane()) return Retcode.SCENE_ENTRY_ID_NOT_MATCH;
      var home = venue.getReturnTo();
      if (!player.loadScene(
          home.getPlane(), home.getFloor(), home.getEntry(), home.getPos(), home.getRot(), false))
        return Retcode.SCENE_ENTRY_ID_NOT_MATCH;
      player.getGameplay().getParkour().close();
      venue.close();
      player.save();
      return Retcode.SUCC;
    }
  }

  /**
   * Preserve a validated venue on login; an unfinished client race is abandoned without a result.
   */
  public boolean restoreOnLogin() {
    synchronized (player) {
      var venue = player.getGameplay().getParkourVenue();
      if (!venueMatches(venue)
          || player.getEntryId() != venue.getEntry()
          || player.getPlaneId() != venue.getPlane()
          || player.getFloorId() != venue.getFloor()) {
        // Resource removal or an interrupted return cannot turn the saved home into a new entry.
        var home = venue.getReturnTo();
        if (venue.inside()
            && sourceMatches(home)
            && !player.getGameplay().getRogue().isInside()
            && player.loadScene(
                home.getPlane(),
                home.getFloor(),
                home.getEntry(),
                home.getPos(),
                home.getRot(),
                false)) {
          player.getGameplay().getParkour().close();
          venue.close();
          player.save();
        }
        return false;
      }
      if (player.getGameplay().getParkour().getActiveLevel() != 0) {
        player.getGameplay().getParkour().close();
        player.save();
      }
      return true;
    }
  }

  public boolean active() {
    synchronized (player) {
      var venue = player.getGameplay().getParkourVenue();
      return venueMatches(venue)
          && player.getEntryId() == venue.getEntry()
          && player.getPlaneId() == venue.getPlane()
          && player.getFloorId() == venue.getFloor();
    }
  }
}
