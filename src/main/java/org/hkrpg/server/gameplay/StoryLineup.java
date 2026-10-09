package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerExtraLineup;
import emu.lunarcore.server.packet.send.PacketSyncLineupNotify;
import java.util.*;

/** A view of a persistent branch party, never an extra-lineup database document. */
public final class StoryLineup extends PlayerExtraLineup {
  private final StoryLineState.Run run;

  public StoryLineup(Player player, StoryLineState.Run run) {
    super(player, 13);
    this.run = run;
  }

  public List<Integer> getAvatars() {
    return Collections.unmodifiableList(run.getRoster());
  }

  public int getLeader() {
    return run.getLeader();
  }

  private boolean active() {
    return getOwner().getStoryLineManager().current() == run
        && !getOwner().getGameplay().getStoryLines().isTransitionPending()
        && !getOwner().isInBattle();
  }

  public void setLeader(int value) {
    synchronized (getOwner()) {
      if (active() && value >= 0 && value < size()) {
        run.leader(value);
        save();
      }
    }
  }

  public int getMp() {
    return run.getMp();
  }

  @Override
  public int getMaxMp() {
    int maximum = emu.lunarcore.GameConstants.MAX_MP;
    for (int id : getAvatars()) {
      var actor = getOwner().getStoryLineManager().avatar(id);
      if (actor != null)
        maximum =
            Math.addExact(
                maximum, emu.lunarcore.GameConstants.BONUS_MAX_MP.get(actor.getActorId()));
    }
    return maximum;
  }

  public void setMp(int value) {
    synchronized (getOwner()) {
      if (getOwner().getStoryLineManager().current() == run) {
        run.mp(Math.clamp(value, 0, getMaxMp()));
        save();
      }
    }
  }

  public void addMp(int value) {
    setMp(Math.toIntExact(Math.clamp((long) getMp() + value, 0, getMaxMp())));
    sync();
  }

  public void removeMp(int value) {
    setMp(Math.toIntExact(Math.clamp((long) getMp() - value, 0, getMaxMp())));
  }

  public int hp(int id) {
    var vital = run.getVitals().get(id);
    return vital == null ? 0 : vital.getHp();
  }

  public int sp(int id) {
    var vital = run.getVitals().get(id);
    return vital == null ? 0 : vital.getSp();
  }

  public void hp(int id, int value) {
    synchronized (getOwner()) {
      var vital = run.getVitals().get(id);
      if (vital != null && getOwner().getStoryLineManager().current() == run) {
        vital.hp(value);
        save();
      }
    }
  }

  public void sp(int id, int value) {
    synchronized (getOwner()) {
      var vital = run.getVitals().get(id);
      if (vital != null && getOwner().getStoryLineManager().current() == run) {
        vital.sp(value);
        save();
      }
    }
  }

  public void save() {
    getOwner().save();
  }

  public boolean tryReplace(List<Integer> ids) {
    synchronized (getOwner()) {
      if (ids == null || ids.isEmpty()) return false;
      int oldLeader = getAvatars().get(getLeader());
      return tryReplace(ids, Math.max(0, ids.indexOf(oldLeader)));
    }
  }

  public boolean tryReplace(List<Integer> ids, int leader) {
    synchronized (getOwner()) {
      if (!active()
          || ids == null
          || ids.isEmpty()
          || ids.size() > 4
          || new HashSet<>(ids).size() != ids.size()
          || !run.getAvailableTrials().containsAll(ids)
          || leader < 0
          || leader >= ids.size()) return false;
      ids = List.copyOf(ids);
      run.getRoster().clear();
      run.getRoster().addAll(ids);
      run.leader(leader);
      run.mp(Math.min(run.getMp(), getMaxMp()));
      save();
      if (getOwner().getScene() != null) getOwner().getScene().syncLineup();
      sync();
      return true;
    }
  }

  public void replace(List<Integer> ids) {
    tryReplace(ids);
  }

  private void sync() {
    if (getOwner().isOnline()) getOwner().sendPacket(new PacketSyncLineupNotify(this));
  }
}
