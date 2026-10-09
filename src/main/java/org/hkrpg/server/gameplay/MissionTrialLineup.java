package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.proto.LineupInfoOuterClass.LineupInfo;
import java.util.*;

/** An immutable mission party view; vitals and leadership belong to the mission record. */
public final class MissionTrialLineup extends PlayerLineup {
  private final MissionTrialState.Party party;

  MissionTrialLineup(Player player, MissionTrialState.Party party) {
    super(player, party.getIndex());
    this.party = party;
  }

  @Override
  public List<Integer> getAvatars() {
    return Collections.unmodifiableList(party.getRoster());
  }

  @Override
  public int getLeader() {
    return party.getLeader();
  }

  // Prevent ordinary team editing while a script owns the current party. No extra-mode enum is
  // invented.
  @Override
  public boolean isExtraLineup() {
    return true;
  }

  @Override
  public LineupInfo toProto() {
    return super.toProto().setIsVirtual(party.isVirtual());
  }

  @Override
  public int getMp() {
    return party.getMp();
  }

  @Override
  public int getMaxMp() {
    int result = emu.lunarcore.GameConstants.MAX_MP;
    for (int id : getAvatars()) {
      var actor = getOwner().getAvatarById(id);
      if (actor != null)
        result =
            Math.addExact(result, emu.lunarcore.GameConstants.BONUS_MAX_MP.get(actor.getActorId()));
    }
    return result;
  }

  private boolean owned() {
    return getOwner().getMissionTrialManager().owns(party);
  }

  @Override
  public void setLeader(int value) {
    synchronized (getOwner()) {
      if (owned()
          && !getOwner().isInBattle()
          && getOwner().getMissionTrialManager().leaderAllowed(value)) {
        party.leader(value);
        save();
      }
    }
  }

  @Override
  public void setMp(int value) {
    synchronized (getOwner()) {
      if (owned()) {
        party.mp(Math.clamp(value, 0, getMaxMp()));
        save();
      }
    }
  }

  @Override
  public void addMp(int value) {
    setMp(Math.toIntExact(Math.clamp((long) getMp() + value, 0, getMaxMp())));
  }

  @Override
  public void removeMp(int value) {
    setMp(Math.toIntExact(Math.clamp((long) getMp() - value, 0, getMaxMp())));
  }

  @Override
  public void refreshLineup() {
    synchronized (getOwner()) {
      if (owned()) super.refreshLineup();
    }
  }

  public int hp(int id) {
    var vital = party.getVitals().get(id);
    return vital == null ? 0 : vital.getHp();
  }

  public int sp(int id) {
    var vital = party.getVitals().get(id);
    return vital == null ? 0 : vital.getSp();
  }

  public void hp(int id, int value) {
    synchronized (getOwner()) {
      var vital = party.getVitals().get(id);
      if (owned() && vital != null) {
        vital.hp(value);
        save();
      }
    }
  }

  public void sp(int id, int value) {
    synchronized (getOwner()) {
      var vital = party.getVitals().get(id);
      if (owned() && vital != null) {
        vital.sp(value);
        save();
      }
    }
  }

  @Override
  public void replace(List<Integer> ids) {
    throw new IllegalStateException("Mission script owns this party");
  }

  @Override
  public void save() {
    if (owned()) getOwner().save();
  }
}
