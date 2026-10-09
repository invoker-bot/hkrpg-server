package org.hkrpg.server.gameplay;

import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.PropInfo;
import emu.lunarcore.data.excel.PropExcel;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import java.util.List;
import java.util.Objects;

/** A resource map-entry portal retains all adjacent choices instead of selecting one branch. */
public final class RogueMapPortal extends EntityProp {
  public static final String SCRIPT =
      "Config/Level/Maze/MazeRogue/Rogue140/Maze_Rogue140_Portal.json";
  private final String runId;
  private final int map, sourceSite, sourceRoom, sourceEntry;

  public RogueMapPortal(Scene scene, PropExcel excel, GroupInfo group, PropInfo info) {
    super(scene, excel, group, info);
    var run = scene.getPlayer().getGameplay().getRogue();
    runId = run.getRunId();
    map = run.getMapId();
    sourceSite = run.getSiteId();
    sourceRoom = run.getRoomId();
    sourceEntry = GameplayDefinitions.rooms.get(sourceRoom).MapEntrance();
  }

  public boolean valid() {
    var player = getScene().getPlayer();
    var run = player.getGameplay().getRogue();
    return runId.equals(run.getRunId())
        && run.getMapId() == map
        && run.getSiteId() == sourceSite
        && run.getRoomId() == sourceRoom
        && Objects.equals(run.getRooms().get(sourceSite), sourceRoom)
        && player.getEntryId() == sourceEntry
        && getScene().getEntryId() == sourceEntry;
  }

  @Override
  public PropState getState() {
    var player = getScene().getPlayer();
    var run = player.getGameplay().getRogue();
    if (!valid()
        || !run.isInside()
        || run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING
        || !run.getCleared().contains(sourceSite)
        || run.pending()
        || player.isInBattle()) return PropState.Closed;
    var current =
        GameplayDefinitions.maps.getOrDefault(map, List.of()).stream()
            .filter(s -> s.SiteID() == sourceSite)
            .toList();
    return current.size() == 1
            && current.getFirst().NextSiteIDList() != null
            && current.getFirst().NextSiteIDList().stream()
                .anyMatch(
                    id ->
                        id != null
                            && !run.getCleared().contains(id)
                            && run.getRooms().containsKey(id)
                            && GameplayDefinitions.rooms.containsKey(run.getRooms().get(id)))
        ? PropState.Open
        : PropState.Closed;
  }
}
