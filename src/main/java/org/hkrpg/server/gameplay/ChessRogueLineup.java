package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerExtraLineup;
import java.util.List;

/** A runtime view of the independent Chess document; ordinary and classic vitals stay separate. */
public final class ChessRogueLineup extends PlayerExtraLineup {
  private final ChessRogueState run;
  public ChessRogueLineup(Player player, ChessRogueState run) { super(player, 2); this.run = run; }
  public List<Integer> getAvatars() { return run.getRoster(); }
  public int getLeader() { return run.getLeader(); }
  public int getMp() { return run.getMp(); }
  public void setLeader(int value) {
    if (!getOwner().isInBattle() && value >= 0 && value < size()) mutate(() -> run.leader(value));
  }
  public void setMp(int value) { mutate(() -> run.mp(Math.clamp(value, 0, getMaxMp()))); }
  public void addMp(int value) { setMp(getMp() + value); }
  public void removeMp(int value) { setMp(getMp() - value); }
  public int hp(int id) { var vital = run.getVitals().get(id); return vital == null ? 0 : vital.getHp(); }
  public int sp(int id) { var vital = run.getVitals().get(id); return vital == null ? 0 : vital.getSp(); }
  public void hp(int id, int value) { if (run.getVitals().containsKey(id)) mutate(() -> run.hp(id, value)); }
  public void sp(int id, int value) { if (run.getVitals().containsKey(id)) mutate(() -> run.sp(id, value)); }
  public void save() { if (!getOwner().isInBattle()) getOwner().save(); }
  public void replace(List<Integer> ids) { /* The bounded original tutorial does not expose downloads/editor. */ }
  private void mutate(Runnable action) {
    synchronized (getOwner()) {
      if (getOwner().getGameplay().getChessRogue() != run || !run.isInside()) return;
      var prior = run.copy();
      try { action.run(); save(); }
      catch (RuntimeException failed) { run.restore(prior); throw failed; }
    }
  }
}
