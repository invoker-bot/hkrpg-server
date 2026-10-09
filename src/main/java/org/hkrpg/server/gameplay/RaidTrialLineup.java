package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerExtraLineup;
import emu.lunarcore.proto.ExtraLineupTypeOuterClass.ExtraLineupType;
import java.util.*;

/** Raid-local vitals and points; the ordinary owned party remains intact. */
public final class RaidTrialLineup extends PlayerExtraLineup {
  private final GameplayState.MissionRaidRun run;
  private final RaidTrialParty party;

  public RaidTrialLineup(Player player, GameplayState.MissionRaidRun run, RaidTrialParty party) {
    super(player, ExtraLineupType.LINEUP_HELIOBUS_VALUE);
    this.run = run;
    this.party = party;
  }

  private boolean owned() {
    return new MissionRaid(getOwner()).current() == run
        && run.getTrialParty() == party
        && !getOwner().getMissionTrialManager().inside();
  }

  public List<Integer> getAvatars() {
    return party.getRoster();
  }

  @Override
  public int getIndex() {
    return getOwner().getLineupManager().getCurrentIndex();
  }

  public int getLeader() {
    return party.getLeader();
  }

  public void setLeader(int value) {
    synchronized (getOwner()) {
      if (owned()
          && !getOwner().isInBattle()
          && value >= 0
          && value < size()
          && getOwner().getRaidTrialManager().leaderAllowed(value)) {
        party.leader(value);
        save();
      }
    }
  }

  public int getMp() {
    return party.getMp();
  }

  public int getMaxMp() {
    int maximum = emu.lunarcore.GameConstants.MAX_MP;
    for (int id : getAvatars()) {
      var actor = getOwner().getAvatarById(id);
      if (actor != null)
        maximum =
            Math.addExact(
                maximum, emu.lunarcore.GameConstants.BONUS_MAX_MP.get(actor.getActorId()));
    }
    return maximum;
  }

  public void setMp(int value) {
    synchronized (getOwner()) {
      if (owned()) {
        party.mp(Math.clamp(value, 0, getMaxMp()));
        save();
      }
    }
  }

  public void addMp(int value) {
    setMp((int) Math.clamp((long) getMp() + value, 0, getMaxMp()));
    refreshLineup();
  }

  @Override
  public void refreshLineup() {
    synchronized (getOwner()) {
      if (owned()) super.refreshLineup();
    }
  }

  public void removeMp(int value) {
    setMp((int) Math.clamp((long) getMp() - value, 0, getMaxMp()));
  }

  public int hp(int id) {
    return party.getVitals().get(id).getHp();
  }

  public int sp(int id) {
    return party.getVitals().get(id).getSp();
  }

  public void hp(int id, int value) {
    synchronized (getOwner()) {
      if (owned() && party.getVitals().containsKey(id)) {
        party.getVitals().get(id).hp(value);
        save();
      }
    }
  }

  public void sp(int id, int value) {
    synchronized (getOwner()) {
      if (owned() && party.getVitals().containsKey(id)) {
        party.getVitals().get(id).sp(value);
        save();
      }
    }
  }

  public void save() {
    if (owned()) getOwner().save();
  }

  public void replace(List<Integer> ids) {
    /* Party selection is frozen for this raid. */
  }
}
