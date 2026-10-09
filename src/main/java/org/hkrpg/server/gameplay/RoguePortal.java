package org.hkrpg.server.gameplay;

import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.PropExcel;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;

/** A portal is tied to this run and source site, not a client-supplied destination. */
public final class RoguePortal extends EntityProp {
  private final String runId;
  private final int sourceSite, sourceRoom, destinationSite, destinationRoom;

  public RoguePortal(
      Scene scene,
      PropExcel excel,
      GroupInfo group,
      PropInfo info,
      int destinationSite,
      int destinationRoom) {
    super(scene, excel, group, info);
    var run = scene.getPlayer().getGameplay().getRogue();
    this.runId = run.getRunId();
    this.sourceSite = run.getSiteId();
    this.sourceRoom = run.getRoomId();
    this.destinationSite = destinationSite;
    this.destinationRoom = destinationRoom;
  }

  public int destinationSite() {
    return destinationSite;
  }

  public int destinationRoom() {
    return destinationRoom;
  }

  public boolean valid() {
    var run = getScene().getPlayer().getGameplay().getRogue();
    return runId.equals(run.getRunId())
        && run.getSiteId() == sourceSite
        && run.getRoomId() == sourceRoom
        && java.util.Objects.equals(run.getRooms().get(destinationSite), destinationRoom);
  }

  @Override
  public PropState getState() {
    var player = getScene().getPlayer();
    var run = player.getGameplay().getRogue();
    return valid()
            && run.isInside()
            && run.getPhase() == GameplayState.RogueRun.Phase.EXPLORING
            && run.getCleared().contains(sourceSite)
            && !run.pending()
            && !player.isInBattle()
            && !run.getCleared().contains(destinationSite)
        ? PropState.Open
        : PropState.Closed;
  }
}
