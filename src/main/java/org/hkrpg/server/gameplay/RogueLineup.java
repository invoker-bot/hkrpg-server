package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerExtraLineup;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.PacketSyncLineupNotify;
import java.util.*;

/** Runtime view of the run embedded in Player; no second lineup or avatar document to restore. */
public final class RogueLineup extends PlayerExtraLineup {
  private final GameplayState.RogueRun run;

  public RogueLineup(Player player, GameplayState.RogueRun run) {
    super(player, 2);
    this.run = run;
  }

  public List<Integer> getAvatars() {
    return run.getRoster();
  }

  public int getLeader() {
    return run.getLeader();
  }

  public void setLeader(int value) {
    if (getOwner().getGameplay().getRogue() == run
        && run.getPhase() == GameplayState.RogueRun.Phase.EXPLORING
        && run.isInside()
        && !run.pending()
        && !getOwner().isInBattle()
        && value >= 0
        && value < size()) {
      run.setLeader(value);
      save();
    }
  }

  public int getMp() {
    return run.getMp();
  }

  public void setMp(int value) {
    run.setMp(Math.clamp(value, 0, getMaxMp()));
    save();
  }

  public void addMp(int value) {
    setMp(getMp() + value);
    sync();
  }

  public void removeMp(int value) {
    setMp(getMp() - value);
  }

  public void save() {
    getOwner().save();
  }

  public void replace(List<Integer> ids) {
    tryReplace(ids, -1);
  }

  public Retcode tryReplace(List<Integer> ids, int leaderSlot) {
    synchronized (getOwner()) {
      if (getOwner().getGameplay().getRogue() != run
          || run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING
          || !run.isInside()
          || run.pending()
          || getOwner().isInBattle()) return Retcode.ROGUE_STATUS_NOT_MATCH;
      var room = GameplayDefinitions.rooms.get(run.getRoomId());
      var scene = getOwner().getScene();
      if (room == null
          || scene == null
          || scene.getPlaneType() != emu.lunarcore.game.enums.PlaneType.Rogue
          || getOwner().getEntryId() != room.MapEntrance()) return Retcode.ROGUE_STATUS_NOT_MATCH;
      // Only members downloaded into this run can be deployed through the generic lineup editor.
      if (ids == null
          || ids.isEmpty()
          || ids.size() > 4
          || new HashSet<>(ids).size() != ids.size()
          || ids.stream()
              .anyMatch(
                  id ->
                      id == null
                          || !run.getVitals().containsKey(id)
                          || getOwner().getAvatarById(id) == null
                          || getOwner().getAvatarById(id).getAvatarId() != id
                          || getOwner().getAvatarById(id).getAvatarType()
                              != emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType
                                  .AVATAR_FORMAL_TYPE
                              && !(RogueTutorialDownload.downloaded(run, id)
                                  && getOwner().getAvatarById(id) == getOwner().getUniverseManager().downloadedTrial(id)))
          || leaderSlot < -1
          || leaderSlot >= ids.size()) return Retcode.REQ_PARA_INVALID;
      int oldLeader = getLeader() < size() ? getAvatars().get(getLeader()) : 0;
      var actors = new HashSet<Integer>();
      for (int id : ids) {
        int actor = getOwner().getAvatarById(id).getActorId();
        if (actor > 0 && !actors.add(actor)) return Retcode.REQ_PARA_INVALID;
      }
      var previous = List.copyOf(run.getRoster()); int previousLeader = run.getLeader();
      ids = List.copyOf(ids);
      run.getRoster().clear();
      run.getRoster().addAll(ids);
      run.setLeader(leaderSlot >= 0 ? leaderSlot : Math.max(ids.indexOf(oldLeader), 0));
      try { save(); }
      catch (RuntimeException failed) {
        run.getRoster().clear(); run.getRoster().addAll(previous); run.setLeader(previousLeader);
        throw failed;
      }
      if (getOwner().getScene() != null) getOwner().getScene().syncLineup();
      sync();
      return Retcode.SUCC;
    }
  }

  public int hp(int id) {
    return run.getVitals().containsKey(id) ? run.getVitals().get(id).getHp() : 0;
  }

  public int sp(int id) {
    return run.getVitals().containsKey(id) ? run.getVitals().get(id).getSp() : 0;
  }

  public void hp(int id, int value) {
    var vital = run.getVitals().get(id);
    if (vital != null) {
      vital.setHp(value);
      save();
    }
  }

  public void sp(int id, int value) {
    var vital = run.getVitals().get(id);
    if (vital != null) {
      vital.setSp(value);
      save();
    }
  }

  private void sync() {
    if (getOwner().isOnline()) getOwner().sendPacket(new PacketSyncLineupNotify(this));
  }
}
