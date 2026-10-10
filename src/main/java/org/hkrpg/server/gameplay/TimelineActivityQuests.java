package org.hkrpg.server.gameplay;

import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.QuestExcel;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;
import java.util.*;

/** Native completion marker followed by the changed timeline save; neither request counts alone. */
public final class TimelineActivityQuests {
  record Pending(
      int quest,
      Scene scene,
      EntityProp prop,
      TimelineActivityState domain,
      QuestState state,
      PropTimelineState timelines,
      String acceptance,
      String source,
      String groupSource,
      String previous,
      int entry,
      int dimension,
      int story,
      GameplayDefinitions.Window window) {}

  static final class Prepared {
    final TimelineActivityState original, next;
    final Map<Integer, Pending> consumed;

    Prepared(
        TimelineActivityState original,
        TimelineActivityState next,
        Map<Integer, Pending> consumed) {
      this.original = original;
      this.next = next;
      this.consumed = Map.copyOf(consumed);
    }

    boolean changed() {
      return original != next;
    }

    void committed() {
      consumed.forEach((key, value) -> original.pending().remove(key, value));
    }
  }

  public static boolean belongs(QuestExcel definition) {
    if (definition == null) return false;
    var finish = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
    return TimelineActivityRules.IDS.contains(definition.getId())
        || finish != null && "TimeLineSetStateCnt".equals(finish.getFinishType());
  }

  public static boolean refresh(Player p) {
    synchronized (p) {
      var rules = TimelineActivityRules.load();
      var domain = p.getGameplay().getTimelineActivityQuests();
      if (!domain.valid(rules)) return false;
      boolean changed = false;
      var now = Instant.now();
      for (int id : TimelineActivityRules.IDS)
        if (rules.window(p, id, now) != null && !domain.getQuests().containsKey(id)) {
          domain.accept(id);
          changed = true;
        }
      return changed;
    }
  }

  public static Map<Integer, QuestState> states(Player p) {
    synchronized (p) {
      var rules = TimelineActivityRules.load();
      var domain = p.getGameplay().getTimelineActivityQuests();
      if (!domain.valid(rules)) return Map.of();
      var result = new TreeMap<Integer, QuestState>();
      var now = Instant.now();
      for (var e : domain.getQuests().entrySet())
        if (rules.window(p, e.getKey(), now) != null) result.put(e.getKey(), e.getValue());
      return Map.copyOf(result);
    }
  }

  public static QuestState state(Player p, int id) {
    return states(p).get(id);
  }

  private static boolean world(Player p, Scene scene) {
    return scene != null
        && scene.getPlayer() == p
        && (scene.getPlaneType() == PlaneType.Town || scene.getPlaneType() == PlaneType.Maze)
        && scene.getPlaneId() == p.getPlaneId()
        && scene.getFloorId() == p.getFloorId()
        && scene.getEntryId() == p.getEntryId()
        && scene.getStoryLineId() == p.getGameplay().getStoryLines().getCurrentId();
  }

  public static Retcode arm(Player p, int id, int group, int instance) {
    synchronized (p) {
      if (!TimelineActivityRules.IDS.contains(id) || group <= 0 || instance <= 0)
        return Retcode.REQ_PARA_INVALID;
      var rules = TimelineActivityRules.load();
      if (rules == null) return Retcode.PLAYER_DATA_ERROR;
      if (p.isInBattle() || p.getGameplay().getStoryLines().isTransitionPending())
        return Retcode.SYSTEM_BUSY;
      var scene = p.getScene();
      if (!world(p, scene)
          || scene.getPlaneId() != rules.plane(id)
          || scene.getFloorId() != rules.floor(id)) return Retcode.MISSION_SCENE_NOT_MATCH;
      var window = rules.window(p, id, Instant.now());
      if (window == null) return Retcode.QUEST_NOT_ACCEPT;
      try {
        p.getQuestManager().refreshUnlocks();
      } catch (RuntimeException failed) {
        return Retcode.SYSTEM_BUSY;
      }
      var domain = p.getGameplay().getTimelineActivityQuests();
      if (!domain.valid(rules) || !p.getGameplay().getPropTimelines().valid())
        return Retcode.PLAYER_DATA_ERROR;
      var accepted = domain.getQuests().get(id);
      if (accepted == null) return Retcode.QUEST_NOT_ACCEPT;
      if (MissionStateRecords.completed(accepted, id, 3)) {
        domain.pending().remove(id);
        return Retcode.SUCC;
      }
      var matches =
          scene.getEntities().values().stream()
              .filter(
                  e ->
                      e instanceof EntityProp
                          && e.getGroupId() == group
                          && e.getInstId() == instance)
              .map(e -> (EntityProp) e)
              .toList();
      if (matches.size() != 1) return Retcode.REQ_PARA_INVALID;
      var prop = matches.getFirst();
      var live = PropTimelines.original(scene, group, instance);
      var original = rules.prop(scene, group, instance);
      if (live == null
          || original == null
          || prop.getScene() != scene
          || prop.getPropId() != original.getPropID()
          || !PropTimelines.source(original).equals(PropTimelines.source(live))
          || !PropTimelines.source(original).equals(PropTimelines.source(prop.getPropInfo())))
        return Retcode.REQ_PARA_INVALID;
      String source = PropTimelines.source(original),
          previous = PropTimelines.initial(original),
          acceptance;
      var timelines = p.getGameplay().getPropTimelines();
      var stored = timelines.get(PropTimelines.key(scene, group, instance));
      if (stored != null) {
        if (!source.equals(stored.getSource()) || !stored.getAcceptance().isEmpty())
          return Retcode.PLAYER_DATA_ERROR;
        previous = stored.getState();
      }
      try {
        acceptance = accepted.captureObservationKey();
      } catch (RuntimeException corrupt) {
        return Retcode.PLAYER_DATA_ERROR;
      }
      var pending =
          new Pending(
              id,
              scene,
              prop,
              domain,
              accepted,
              timelines,
              acceptance,
              source,
              rules.groupSource(
                  scene.getPlaneId(), scene.getFloorId(), scene.getDimensionId(), group),
              previous,
              scene.getEntryId(),
              scene.getDimensionId(),
              scene.getStoryLineId(),
              window);
      domain.pending().put(id, pending);
      return Retcode.SUCC;
    }
  }

  static Prepared observe(Player p, EntityProp prop, PropTimelineState.Snapshot report) {
    var domain = p.getGameplay().getTimelineActivityQuests();
    var consumed = new HashMap<>(domain.pending());
    if (consumed.isEmpty()) return new Prepared(domain, domain, consumed);
    var rules = TimelineActivityRules.load();
    var scene = p.getScene();
    if (!domain.valid(rules) || !world(p, scene)) return new Prepared(domain, domain, consumed);
    for (var marker : consumed.values()) {
      if (marker.scene() != scene
          || marker.prop() != prop
          || marker.domain() != domain
          || marker.state() != domain.getQuests().get(marker.quest())
          || marker.timelines() != p.getGameplay().getPropTimelines()
          || marker.entry() != scene.getEntryId()
          || marker.dimension() != scene.getDimensionId()
          || marker.story() != scene.getStoryLineId()
          || !Objects.equals(marker.acceptance(), marker.state().getObservationKey())
          || !MissionStateRecords.doing(marker.state(), marker.quest(), 3)
          || !Objects.equals(marker.source(), report.getSource())
          || !report.getAcceptance().isEmpty()
          || !Objects.equals(
              marker.groupSource(),
              rules.groupSource(
                  report.getPlane(), report.getFloor(), report.getDimension(), report.getGroup()))
          || Objects.equals(marker.previous(), report.getState())) continue;
      var now = Instant.now();
      var window = rules.window(p, marker.quest(), now);
      if (!Objects.equals(window, marker.window())) continue;
      var original = rules.prop(scene, report.getGroup(), report.getInstance());
      if (original == null || !marker.source().equals(PropTimelines.source(original))) continue;
      var receipt =
          new TimelineActivityState.Receipt(
              marker.quest(),
              report,
              marker.previous(),
              marker.acceptance(),
              now.getEpochSecond(),
              window,
              marker.groupSource());
      var next = domain.recorded(marker.quest(), receipt);
      return next.valid(rules)
          ? new Prepared(domain, next, consumed)
          : new Prepared(domain, domain, consumed);
    }
    return new Prepared(domain, domain, consumed);
  }

  private TimelineActivityQuests() {}
}
