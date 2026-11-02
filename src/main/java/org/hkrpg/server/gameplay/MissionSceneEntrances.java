package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.MapEntranceExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.SessionState;
import emu.lunarcore.util.Position;
import java.util.*;

/** An active literal EnterMap may authorize its own arrival before that arrival is completed. */
public final class MissionSceneEntrances {
  /**
   * Captured before login refresh, so newly accepted objectives cannot skip their start sequence.
   */
  public record LoginOrigin(int plane, int floor, int entry, Set<Integer> accepted) {
    public LoginOrigin {
      accepted = Set.copyOf(accepted);
    }
  }

  public static LoginOrigin captureLogin(Player player) {
    synchronized (player) {
      var pending = new HashSet<Integer>();
      player
          .getGameplay()
          .getSubMissions()
          .forEach(
              (id, state) -> {
                if (MissionStateRecords.doing(state, id, 1)) pending.add(id);
              });
      return new LoginOrigin(
          player.getPlaneId(), player.getFloorId(), player.getEntryId(), pending);
    }
  }

  /** Resume an interrupted, already accepted literal transfer before the first login scene. */
  public static boolean resumeOnLogin(Player player, LoginOrigin origin) {
    synchronized (player) {
      if (origin == null
          || player.isOnline()
          || !ordinaryContext(player)
          || origin.plane() != player.getPlaneId()
          || origin.floor() != player.getFloorId()
          || origin.entry() != player.getEntryId()) return false;
      MapEntranceExcel destination = null;
      MissionInfo.SubMissionInfo accepted = null;
      for (int id : origin.accepted()) {
        var sub = GameplayDefinitions.subs.get(id);
        var entry = sub == null ? null : GameData.getMapEntranceExcelMap().get(literalEntry(sub));
        if (entry == null || !authorizes(player, sub, entry)) continue;
        // Two pending starts require explicit client ordering; never choose one arbitrarily.
        if (destination != null) return false;
        destination = entry;
        accepted = sub;
      }
      // loadScene persists the real location, then completes arrival and accepts the next dialogue.
      // No scene push is sent before login: the first ordinary scene response contains the result.
      return destination != null && transfer(player, accepted, destination, false);
    }
  }

  /** Resolve the same source-bound destination for a live request, even if the map is already open. */
  public static boolean enter(Player player, int entryId, int teleport, boolean sendPacket) {
    synchronized (player) {
      if (sendPacket && player.isOnline()
          && player.getSession().getMissionSceneRecovery().isLateRequest(player, entryId, teleport))
        return true;
      var entry = GameData.getMapEntranceExcelMap().get(entryId);
      MissionInfo.SubMissionInfo accepted = null;
      if (entry != null && teleport == 0 && ordinaryContext(player)) {
        for (var state : player.getGameplay().getSubMissions().values()) {
          var sub = state == null ? null : GameplayDefinitions.subs.get(state.getQuestId());
          if (sub == null || !authorizes(player, sub, entry)) continue;
          if (accepted != null) return false;
          accepted = sub;
        }
      }
      return accepted == null ? player.enterScene(entryId, teleport, sendPacket)
          : transfer(player, accepted, entry, sendPacket);
    }
  }

  /** A bounded acknowledgement of the same connection's already applied scripted transition. */
  public static boolean acknowledgesRecovery(GameSession session, MapEntranceExcel entry, int teleport) {
    var player = session.getPlayer();
    synchronized (player) {
      return entry != null && player.isOnline() && player.getSession() == session
          && GameData.getMapEntranceExcelMap().get(entry.getId()) == entry
          && session.getMissionSceneRecovery().isLateRequest(player, entry.getId(), teleport);
    }
  }

  /**
   * Bounded same-session recovery for complete, single-step literal mission transfers.
   * The client has the first opportunity to run the original EnterMap. If it does not, wait for
   * the exact accepted goal and source scene to remain unchanged before sending the ordinary
   * server scene transition. Connection-local state is never persisted as mission progress.
   */
  public static final class LiveRecovery {
    private static final long GRACE_MILLIS = 15_000;
    private static final long RECEIPT_NANOS = 30_000_000_000L;

    private Scene source;
    private QuestState acceptance;
    private QuestState mainAcceptance;
    private MissionInfo.SubMissionInfo definition;
    private String script;
    private int subId;
    private long observedAt;
    private Scene recoveredScene;
    private Position recoveredPosition;
    private int recoveredEntry;
    private long recoveredAt;

    public void tick(Player player, long timestamp) {
      synchronized (player) {
        var session = player.getSession();
        if (!player.isOnline() || session == null || session.getState() != SessionState.ACTIVE
            || !session.getProtocol().experimental() || !ordinaryContext(player)) {
          clearObservation();
          return;
        }
        MissionInfo.SubMissionInfo selected = null;
        MapEntranceExcel destination = null;
        for (int id : player.getGameplay().getSubMissions().keySet()) {
          var sub = GameplayDefinitions.subs.get(id);
          if (sub == null || !MissionStateRecords.doing(
              player.getGameplay().getSubMissions().get(id), id, 1)) continue;
          var literal = DailyMissionTravelRules.transfer(sub);
          var entry = literal == null ? null : GameData.getMapEntranceExcelMap().get(literal.entry());
          if (entry == null || literal == null || literal.loop() || !authorizes(player, sub, entry))
            continue;
          if (selected != null) {
            clearObservation(); // Never choose between two accepted starts.
            return;
          }
          selected = sub;
          destination = entry;
        }
        if (selected == null) {
          clearObservation();
          return;
        }
        var state = player.getGameplay().getSubMissions().get(selected.getId());
        var main = player.getGameplay().getMainMissions().get(selected.getMainMissionID());
        var original = selected.getTask().getSourceDefinition();
        if (source != player.getScene() || acceptance != state || subId != selected.getId()
            || mainAcceptance != main || definition != selected || !Objects.equals(script, original)
            || timestamp < observedAt) {
          source = player.getScene();
          acceptance = state;
          mainAcceptance = main;
          definition = selected;
          script = original;
          subId = selected.getId();
          observedAt = timestamp;
          return;
        }
        if (timestamp - observedAt < GRACE_MILLIS) return;
        // Reset the observation even when persistence fails, so the next tick cannot busy-loop.
        clearObservation();
        if (transfer(player, selected, destination, true)) {
          emu.lunarcore.LunarCore.getLogger().warn(
              "[UID: {}] Recovered stalled story transfer {} to entry {}",
              player.getUid(), selected.getId(), recoveredEntry);
        }
      }
    }

    private void remember(Player player, int entry) {
      recoveredScene = player.getScene();
      recoveredPosition = new Position(player.getPos());
      recoveredEntry = entry;
      recoveredAt = System.nanoTime();
    }

    private boolean isLateRequest(Player player, int entry, int teleport) {
      if (recoveredScene == null) return false;
      long elapsed = System.nanoTime() - recoveredAt;
      if (elapsed < 0 || elapsed > RECEIPT_NANOS || player.getScene() != recoveredScene
          || !player.getPos().equals(recoveredPosition)) {
        recoveredScene = null;
        recoveredPosition = null;
        return false;
      }
      return teleport == 0 && entry == recoveredEntry;
    }

    private void clearObservation() {
      source = null;
      acceptance = null;
      mainAcceptance = null;
      definition = null;
      script = null;
      subId = 0;
      observedAt = 0;
    }
  }

  private static boolean transfer(Player player, MissionInfo.SubMissionInfo sub,
      MapEntranceExcel entry, boolean sendPacket) {
    var task = DailyMissionTravelRules.transfer(sub);
    if (task == null) return false;
    // Anchor identifiers come only from the completely validated original script, never the request.
    boolean applied = task.group() > 0
        ? player.enterSceneAtAnchor(entry.getId(), task.group(), task.anchor(), sendPacket)
        : player.enterScene(entry.getId(), 0, sendPacket);
    if (applied && sendPacket && player.isOnline())
      player.getSession().getMissionSceneRecovery().remember(player, entry.getId());
    return applied;
  }

  public static boolean allows(Player player, MapEntranceExcel entry, int teleport) {
    synchronized (player) {
      if (entry == null
          || teleport != 0
          || !ordinaryContext(player)
          || GameData.getMapEntranceExcelMap().get(entry.getId()) != entry) return false;
      for (var state : player.getGameplay().getSubMissions().values()) {
        var sub = state == null ? null : GameplayDefinitions.subs.get(state.getQuestId());
        if (sub != null && authorizes(player, sub, entry)) return true;
      }
      return false;
    }
  }

  private static boolean ordinaryContext(Player player) {
    var scene = player.getScene();
    var g = player.getGameplay();
    return scene != null
        && scene.getPlayer() == player
        && scene.getPlaneId() == player.getPlaneId()
        && scene.getFloorId() == player.getFloorId()
        && scene.getEntryId() == player.getEntryId()
        && scene.getDimensionId() == 0
        && scene.getStoryLineId() == 0
        && player.getBattle() == null
        && player.getInstance() == null
        && g.getCurrentMissionRaid() == 0
        && g.getTrialRun() == null
        && !g.getRogue().isInside()
        && !g.getParkourVenue().inside()
        && g.getStoryLines().getCurrentId() == 0
        && !g.getStoryLines().isTransitionPending();
  }

  private static boolean authorizes(
      Player player, MissionInfo.SubMissionInfo sub, MapEntranceExcel entry) {
    if (!MissionStateRecords.doing(
            player.getGameplay().getSubMissions().get(sub.getId()), sub.getId(), 1)
        || sub.getLevelPlaneID() != player.getPlaneId()
        || sub.getLevelFloorID() != player.getFloorId()
        || sub.getParamInt1() != entry.getPlaneID()
        || sub.getParamInt2() != entry.getFloorID()
        || !player.getMissionManager().canFinishFromAction(sub.getId())
        || !predecessors(player, sub)
        || literalEntry(sub) != entry.getId()) return false;
    var graph = GameData.getMissionInfos().get(sub.getMainMissionID());
    var main = GameData.getMainMissionExcelMap().get(sub.getMainMissionID());
    if (graph == null
        || main == null
        || main.getId() != sub.getMainMissionID()
        || graph.getMainMissionID() != main.getId()
        || graph.getSubMissionById(sub.getId()) != sub) return false;
    // Unrelated entrance prerequisites still apply. Do not complete any task to gain entry.
    return Arrays.stream(entry.getFinishMainMissionList())
            .allMatch(
                id -> id == sub.getMainMissionID() || MissionStateRecords.mainCompleted(player, id))
        && Arrays.stream(entry.getFinishSubMissionList())
            .allMatch(id -> id == sub.getId() || StoryLineConditions.finished(player, id));
  }

  private static boolean predecessors(Player player, MissionInfo.SubMissionInfo sub) {
    var ids = sub.getTakeParamIntList();
    if (ids == null || sub.getTakeParamStrList() == null || sub.getTakeParamStrList().length != 0)
      return false;
    if ("Auto".equals(sub.getTakeType())) {
      var graph = GameData.getMissionInfos().get(sub.getMainMissionID());
      return graph != null && DailyMissionTravelRules.auto(graph, sub);
    }
    if (!Set.of("AnySequence", "MultiSequence").contains(Objects.toString(sub.getTakeType(), ""))
        || ids.length == 0
        || new HashSet<>(Arrays.asList(ids)).size() != ids.length
        || Arrays.stream(ids)
            .anyMatch(
                id ->
                    id == null
                        || id <= 0
                        || GameplayDefinitions.subs.get(id) == null
                        || GameplayDefinitions.subs.get(id).getMainMissionID()
                            != sub.getMainMissionID())) return false;
    return "AnySequence".equals(sub.getTakeType())
        ? Arrays.stream(ids).anyMatch(id -> MissionStateRecords.subCompleted(player, id))
        : Arrays.stream(ids).allMatch(id -> MissionStateRecords.subCompleted(player, id));
  }

  private static int literalEntry(MissionInfo.SubMissionInfo sub) {
    var transfer = DailyMissionTravelRules.transfer(sub);
    return transfer == null ? 0 : transfer.entry();
  }

  private MissionSceneEntrances() {}
}
