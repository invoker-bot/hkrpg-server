package org.hkrpg.server.gameplay;

import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.util.Map;

/** Bounded recovery for the original first-universe entry after Herta's completed dialogue. */
public final class RogueFirstStoryEntry {
  public static final class LiveRecovery {
    private static final long GRACE_MILLIS = 15_000;
    private Scene source;
    private QuestState acceptance;
    private long observedAt;
    private boolean attempted;

    public void tick(Player player, long timestamp) {
      synchronized (player) {
        if (!eligible(player)) {
          clear();
          return;
        }
        var state = player.getGameplay().getSubMissions().get(403000102);
        if (source != player.getScene() || acceptance != state || timestamp < observedAt) {
          source = player.getScene();
          acceptance = state;
          observedAt = timestamp;
          attempted = false;
          return;
        }
        if (attempted || timestamp - observedAt < GRACE_MILLIS) return;
        attempted = true;
        try {
          var result = player.getUniverseManager().start(100);
          if (result != Retcode.SUCC)
            emu.lunarcore.LunarCore.getLogger().warn(
                "[UID: {}] First story universe entry was rejected: {}", player.getUid(), result);
        } catch (RuntimeException failure) {
          emu.lunarcore.LunarCore.getLogger().warn(
              "[UID: {}] First story universe entry failed", player.getUid(), failure);
        }
      }
    }

    private void clear() {
      source = null;
      acceptance = null;
      observedAt = 0;
      attempted = false;
    }
  }

  private static boolean eligible(Player player) {
    var session = player.getSession();
    var scene = player.getScene();
    var state = player.getGameplay();
    if (!player.isOnline() || session == null || session.getPlayer() != player
        || session.getState() != SessionState.ACTIVE || !session.getProtocol().experimental()
        || scene == null || !scene.isLoaded() || scene.getPlayer() != player
        || scene.getPlaneType() != PlaneType.Town || scene.getPlaneId() != 80112
        || scene.getFloorId() != 80112001 || scene.getEntryId() != 8011201
        || player.getPlaneId() != scene.getPlaneId() || player.getFloorId() != scene.getFloorId()
        || player.getEntryId() != scene.getEntryId() || scene.getDimensionId() != 0
        || scene.getStoryLineId() != 0 || scene.getFloorInfo() == null || player.getPos() == null
        || player.getBattle() != null || player.getInstance() != null
        || state.getCurrentMissionRaid() != 0 || state.getTrialRun() != null
        || state.getStoryLines().getCurrentId() != 0 || state.getStoryLines().isTransitionPending()
        || state.getRogue().getPhase() == GameplayState.RogueRun.Phase.EXPLORING
        || state.getRogue().isInside() || state.getChessRogue().active()
        || state.getParkourVenue().inside() || state.getParkour().getActiveLevel() != 0
        || state.getRhythm().getActiveLevel() != 0 || state.getClockPark().getActive() != null)
      return false;
    var talk = GameplayDefinitions.subs.get(403000101);
    var enter = GameplayDefinitions.subs.get(403000102);
    if (talk == null || enter == null || talk.getId() != 403000101 || enter.getId() != 403000102
        || talk.getMainMissionID() != 4030001 || enter.getMainMissionID() != 4030001
        || talk.getLevelPlaneID() != 80112 || talk.getLevelFloorID() != 80112001
        || enter.getLevelPlaneID() != 80112 || enter.getLevelFloorID() != 80112001
        || !"Talk".equals(talk.getFinishType()) || !"Mission_403000101".equals(talk.getParamStr1())
        || !"EnterFloor".equals(enter.getFinishType()) || !"Equal".equals(enter.getParamType())
        || enter.getParamInt1() != 80001 || enter.getParamInt2() != 80001001
        || talk.getProgress() != 1 || enter.getProgress() != 1
        || !MissionStateRecords.mainDoing(player, 4030001)
        || !MissionStateRecords.subCompleted(player, 403000101)
        || state.getCancelledSubs().contains(403000102)
        || !MissionStateRecords.doing(state.getSubMissions().get(403000102), 403000102, 1))
      return false;
    var group = scene.getResourceGroups().get(18);
    if (group == null || group.getId() != 18 || group.getPropList() == null) return false;
    var props = group.getPropList().stream().filter(p -> p != null && p.getID() == 300002).toList();
    if (props.size() != 1) return false;
    var prop = props.getFirst();
    var origin = prop.getPos();
    var position = player.getPos();
    if (prop.getPropID() != 100 || !"Prop2".equals(prop.getName()) || prop.isIsDelete()
        || origin.getX() != 9250 || origin.getY() != 608 || origin.getZ() != -90
        || Math.abs((long) position.getX() - origin.getX()) > 1500
        || Math.abs((long) position.getY() - origin.getY()) > 500
        || Math.abs((long) position.getZ() - origin.getZ()) > 1500) return false;
    var plan = RogueMapPools.assign(1, player.getUid(), 100);
    return plan.isPresent() && plan.get().rooms().equals(Map.of(1, 100));
  }

  private RogueFirstStoryEntry() {}
}
