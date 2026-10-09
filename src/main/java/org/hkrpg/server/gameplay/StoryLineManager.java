package org.hkrpg.server.gameplay;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.SpecialAvatarExcel;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.trial.TrialAvatar;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Resource-checked story transitions; temporary actors never enter formal avatar storage. */
public final class StoryLineManager {
  private static final Gson GSON = new Gson();
  private final Player player;
  private StoryLineState.Run cachedRun;
  private StoryLineup cachedLineup;
  private Map<Integer, TrialAvatar> actors = Map.of();

  private record Plan(
      StoryLineState.Run run, StoryLineState.Place destination, Map<Integer, TrialAvatar> actors) {}

  public StoryLineManager(Player player) {
    this.player = player;
  }

  private StoryLineState state() {
    return player.getGameplay().getStoryLines();
  }

  public StoryLineState.Run current() {
    var state = state();
    return state.getCurrentId() == 0 ? null : state.getRuns().get(state.getCurrentId());
  }

  public TrialAvatar avatar(int id) {
    synchronized (player) {
      if (state().getCurrentId() == 0) return null;
      ensureActors();
      return current().getAvailableTrials().contains(id) ? actors.get(id) : null;
    }
  }

  public StoryLineup lineup() {
    synchronized (player) {
      if (state().getCurrentId() == 0) return null;
      ensureActors();
      return cachedLineup;
    }
  }

  boolean replaceTrial(StoryTrialReplacement.Plan source) {
    synchronized (player) {
      var run = current();
      var scene = player.getScene();
      if (!contextAllowed()
          || player.getGameplay().getTrialRun() != null
          || run == null
          || run.getId() != source.story()
          || scene == null
          || scene.getStoryLineId() != source.story()
          || player.getPlaneId() != source.plane()
          || player.getFloorId() != source.floor()
          || run.getLocation() == null
          || run.getLocation().getDimension() != scene.getDimensionId()
          || !run.getTrialConfigs().containsKey(source.trial())
          || !run.getVitals().containsKey(source.trial())
          || run.getSkills() == null
          || !run.getSkills().containsKey(source.trial())
          || run.getSkillPlans() == null
          || !run.getSkillPlans().containsKey(source.trial())) return false;
      ensureActors();
      var actor = actors.get(source.trial());
      if (actor == null || actor.getAvatarId() != source.trial()) return false;
      if (run.getAvailableTrials().contains(source.trial())
          && run.getRoster().equals(List.of(source.trial()))
          && run.getLeader() == 0) return true;
      if (!run.unlockTrial(source.trial())) return false;
      run.getRoster().clear();
      run.getRoster().add(source.trial());
      run.leader(0);
      run.mp(Math.min(run.getMp(), cachedLineup.getMaxMp()));
      player.save();
      scene.syncLineup();
      if (player.isOnline())
        player.sendPacket(
            new emu.lunarcore.server.packet.send.PacketSyncLineupNotify(cachedLineup));
      return true;
    }
  }

  private void ensureActors() {
    var run = current();
    if (run == null || run.getId() != state().getCurrentId())
      throw new IllegalStateException("Missing or mismatched active story record");
    if (cachedRun != run) install(run, rebuild(run));
    else if (actors.values().stream()
        .anyMatch(actor -> !player.getTrialManager().matchesGender(actor.getActorId())))
      throw new IllegalStateException("Story trial gender no longer matches");
  }

  private void install(StoryLineState.Run run, Map<Integer, TrialAvatar> prepared) {
    if (run != null) run.freezeAvailability();
    cachedRun = run;
    actors = Map.copyOf(prepared);
    cachedLineup = run == null ? null : new StoryLineup(player, run);
  }

  private Map<Integer, TrialAvatar> rebuild(StoryLineState.Run run) {
    if (run.getId() <= 0
        || run.getRoster().isEmpty()
        || run.getRoster().size() > 4
        || new HashSet<>(run.getRoster()).size() != run.getRoster().size()
        || run.getLeader() < 0
        || run.getLeader() >= run.getRoster().size()
        || run.getMp() < 0
        || !run.getAvailableTrials().containsAll(run.getRoster())
        || !run.getTrialConfigs().keySet().containsAll(run.getAvailableTrials())
        || !run.getVitals().keySet().equals(run.getTrialConfigs().keySet()))
      throw new IllegalStateException("Invalid saved story party");
    boolean frozen = run.getSkills() != null || run.getSkillPlans() != null;
    if (frozen
        && (run.getSkills() == null
            || run.getSkillPlans() == null
            || !run.getSkills().keySet().equals(run.getTrialConfigs().keySet())
            || !run.getSkillPlans().keySet().equals(run.getTrialConfigs().keySet())))
      throw new IllegalStateException("Incomplete frozen story skills");
    var prepared = new HashMap<Integer, TrialAvatar>();
    for (var entry : run.getTrialConfigs().entrySet()) {
      var row = GSON.fromJson(entry.getValue(), SpecialAvatarExcel.class);
      var excel = row == null ? null : GameData.getActorForTrial(row.getAvatarID());
      var vital = run.getVitals().get(entry.getKey());
      if (row == null
          || row.getId() != entry.getKey()
          || excel == null
          || !player.getTrialManager().matchesGender(row.getAvatarID())
          || !TrialSkillTrees.supports(excel, row.getEnhancedID())
          || excel instanceof emu.lunarcore.data.excel.TrialActorExcel plot
              && !plot.supportsTrial(row)
          || vital == null
          || vital.getHp() < 0
          || vital.getHp() > 10000
          || vital.getSp() < 0
          || vital.getSp() > 10000)
        throw new IllegalStateException("Invalid frozen story actor: " + entry.getKey());
      final TrialAvatar actor;
      if (frozen) {
        var skillPlan =
            GSON.fromJson(run.getSkillPlans().get(entry.getKey()), TrialSkillPresets.Plan.class);
        if (skillPlan == null
            || skillPlan.custom() != "TYPE_CUSTOM".equals(row.getSkillTreeTemplate()))
          throw new IllegalStateException("Invalid frozen story skill preset");
        actor =
            TrialAvatar.restore(player, row, excel, skillPlan, run.getSkills().get(entry.getKey()));
      } else {
        if (row.getSkillTreeTemplate() != null && !row.getSkillTreeTemplate().isBlank())
          throw new IllegalStateException("Missing frozen custom story skills");
        actor = new TrialAvatar(player, row, excel);
      }
      prepared.put(entry.getKey(), actor);
    }
    int maximum = emu.lunarcore.GameConstants.MAX_MP;
    for (int id : run.getRoster())
      maximum =
          Math.addExact(
              maximum, emu.lunarcore.GameConstants.BONUS_MAX_MP.get(prepared.get(id).getActorId()));
    if (run.getMp() > maximum) throw new IllegalStateException("Invalid saved story secret points");
    return prepared;
  }

  private static boolean ordinary(PlaneType type) {
    return type == PlaneType.Town || type == PlaneType.Maze || type == PlaneType.Train;
  }

  private boolean validPlace(StoryLineState.Place place, int story) {
    if (place == null || place.getPos() == null || place.getRot() == null) return false;
    var entrance = GameData.getMapEntranceExcelMap().get(place.getEntry());
    var plane = GameData.getMazePlaneExcelMap().get(place.getPlane());
    var floor = GameData.getFloorInfo(place.getPlane(), place.getFloor());
    if (entrance == null
        || plane == null
        || !ordinary(plane.getPlaneType())
        || floor == null
        || entrance.getPlaneID() != place.getPlane()
        || entrance.getFloorID() != place.getFloor()) return false;
    var dimension = StoryLineDefinitions.dimension(player, story, floor);
    if (dimension.isEmpty() || dimension.getAsInt() != place.getDimension()) return false;
    floor.getReferencedGroupIndices();
    return true;
  }

  private StoryLineState.Place anchorPlace(int id, int entryId, int group, int anchor) {
    var entry = GameData.getMapEntranceExcelMap().get(entryId);
    var floor =
        entry == null ? null : GameData.getFloorInfo(entry.getPlaneID(), entry.getFloorID());
    if (floor == null) return null;
    var dimension = StoryLineDefinitions.dimension(player, id, floor);
    if (dimension.isEmpty()) return null;
    if (group == 0 && anchor == 0) {
      group = entry.getStartGroupID();
      anchor = entry.getStartAnchorID();
      if (anchor == 0) {
        var first = floor.getGroupInfoByIndex(floor.getStartGroupIndex());
        if (first == null) return null;
        group = first.getId();
        anchor = floor.getStartAnchorID();
      }
    }
    if (group <= 0 || anchor <= 0 || !floor.isGroupInDimension(group, dimension.getAsInt()))
      return null;
    var found = floor.getAnchorInfo(group, anchor);
    if (found == null) return null;
    var place =
        new StoryLineState.Place(
            entry.getPlaneID(),
            entry.getFloorID(),
            entryId,
            dimension.getAsInt(),
            found.getPos(),
            found.getRot());
    return validPlace(place, id) ? place : null;
  }

  private Plan plan(int id, int entry, int group, int anchor) {
    if (id < 0
        || entry < 0
        || group < 0
        || anchor < 0
        || (group == 0) != (anchor == 0)
        || (entry == 0 && (group != 0 || anchor != 0))) return null;
    var state = state();
    if (id == 0) {
      var destination = entry > 0 ? anchorPlace(0, entry, group, anchor) : state.getHome();
      return validPlace(destination, 0) ? new Plan(null, destination, Map.of()) : null;
    }
    if (!StoryLineDefinitions.canEnter(player, id)) return null;
    var run = state.getRuns().get(id);
    var story = StoryLineDefinitions.stories().get(id);
    StoryLineState.Place destination;
    if (entry > 0) destination = anchorPlace(id, entry, group, anchor);
    else if (run != null)
      destination =
          state.getCurrentId() == id && player.getScene().getStoryLineId() == id
              ? new StoryLineState.Place(player)
              : run.getLocation();
    else
      destination =
          anchorPlace(id, story.InitEntranceID(), story.InitGroupID(), story.InitAnchorID());
    if (!validPlace(destination, id)) return null;
    if (run != null) return new Plan(run, destination, rebuild(run));
    var trials = StoryLineDefinitions.trials(id);
    // SkipJoinLineup's client semantics are not proven. Do not guess a formal/trial mixed party.
    if (trials == null
        || trials.SkipJoinLineup()
        || trials.InitTrialAvatarList().isEmpty()
        || !trials.InitTrialAvatarList().contains(trials.CaptainAvatarID())) return null;
    var prepared = new HashMap<Integer, TrialAvatar>();
    var configs = new HashMap<Integer, String>();
    for (int special : trials.TrialAvatarList()) {
      var actor = player.getTrialManager().buildTrialAvatar(special);
      if (actor == null) return null;
      prepared.put(special, actor);
      configs.put(special, GSON.toJson(actor.getSpecialAvatarExcel()));
    }
    run =
        new StoryLineState.Run(
            id,
            trials.InitTrialAvatarList(),
            trials.InitTrialAvatarList().indexOf(trials.CaptainAvatarID()),
            configs,
            destination);
    run.freezeSkills(prepared);
    return new Plan(run, destination, prepared);
  }

  public boolean canChange(int id, int entry, int group, int anchor) {
    synchronized (player) {
      if (!contextAllowed()) return false;
      if (id == 0 && state().getCurrentId() == 0 && entry == 0 && group == 0 && anchor == 0)
        return true;
      try {
        return plan(id, entry, group, anchor) != null;
      } catch (IllegalArgumentException | IllegalStateException invalid) {
        return false;
      }
    }
  }

  private boolean contextAllowed() {
    return !player.isInBattle()
        && player.getInstance() == null
        && !player.getGameplay().getRogue().isInside()
        && !player.getGameplay().getParkourVenue().inside()
        && player.getGameplay().getClockPark().getActive() == null
        && player.getGameplay().getParkour().getActiveLevel() == 0
        && player.getGameplay().getRhythm().getActiveLevel() == 0
        && player.getGameplay().getCurrentMissionRaid() == 0
        && !state().isTransitionPending()
        && player.getScene() != null
        && ordinary(player.getScene().getPlaneType())
        && (state().getCurrentId() != 0
            || player.getCurrentLineup() != null && !player.getCurrentLineup().isExtraLineup());
  }

  public Retcode change(int id, int entry, int group, int anchor, boolean send) {
    synchronized (player) {
      if (!contextAllowed()) return Retcode.SYSTEM_BUSY;
      var state = state();
      if (id == 0 && state.getCurrentId() == 0 && entry == 0 && group == 0 && anchor == 0)
        return Retcode.SUCC;
      final Plan plan;
      try {
        plan = plan(id, entry, group, anchor);
      } catch (IllegalArgumentException | IllegalStateException invalid) {
        return Retcode.PLAYER_DATA_ERROR;
      }
      if (plan == null) return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
      var origin = new StoryLineState.Place(player);
      if (state.getCurrentId() == 0 && !validPlace(origin, 0)) return Retcode.PLAYER_DATA_ERROR;
      var previous = current();
      if (previous != null) previous.locate(origin);
      if (plan.run != null) {
        plan.run.locate(plan.destination);
        state.getRuns().put(id, plan.run);
      }
      state.begin(id, origin, plan.destination);
      install(plan.run, plan.actors);
      // Destination, home, actors and vitals survive failure before any scene notification.
      player.save();
      if (send) org.hkrpg.server.protocol.StoryLine46.changing(player);
      if (!load(plan.destination, send)) return Retcode.PLAYER_DATA_ERROR;
      if (id == 0) finishReturn();
      else state.arrived();
      player.save();
      if (send) org.hkrpg.server.protocol.StoryLine46.changed(player);
      return Retcode.SUCC;
    }
  }

  private void finishReturn() {
    var state = state();
    var story = StoryLineDefinitions.stories().get(state.getReturningId());
    if (story != null && story.EndCondition().matches(player))
      state.getRuns().remove(state.getReturningId());
    state.returned();
    install(null, Map.of());
  }

  private boolean load(StoryLineState.Place place, boolean send) {
    return player.loadScene(
        place.getPlane(),
        place.getFloor(),
        place.getEntry(),
        place.getPos(),
        place.getRot(),
        send,
        place.getDimension());
  }

  /** After avatar/lineup loading, before ordinary scene fallback. */
  public boolean restoreOnLogin() {
    synchronized (player) {
      var state = state();
      if (state.getCurrentId() == 0 && !state.isTransitionPending()) return false;
      if (player.getGameplay().getRogue().isInside()
          || player.getGameplay().getParkourVenue().inside()
          || player.getGameplay().getCurrentMissionRaid() != 0)
        throw new IllegalStateException("Overlapping saved story instance");
      if (state.getCurrentId() == 0) {
        if (!validPlace(state.getReturnDestination(), 0)
            || !load(state.getReturnDestination(), false))
          throw new IllegalStateException("Cannot restore story return");
        finishReturn();
      } else {
        var saved = current();
        if (saved == null || saved.getId() != state.getCurrentId())
          throw new IllegalStateException("Missing or mismatched active story record");
        var definition = StoryLineDefinitions.stories().get(saved.getId());
        if (definition != null && definition.EndCondition().matches(player)) {
          var home = state.getHome();
          if (!validPlace(home, 0))
            throw new IllegalStateException("Cannot restore completed story return");
          state.begin(0, saved.getLocation(), home);
          install(null, Map.of());
          player.save();
          if (!load(home, false))
            throw new IllegalStateException("Cannot restore completed story return");
          finishReturn();
          player.save();
          return true;
        }
        ensureActors();
        var run = current();
        if (!validPlace(run.getLocation(), run.getId()) || !load(run.getLocation(), false))
          throw new IllegalStateException("Cannot restore active story scene");
        state.arrived();
      }
      player.save();
      return true;
    }
  }

  public void onSceneLoaded() {
    synchronized (player) {
      var run = current();
      if (run != null) {
        run.locate(new StoryLineState.Place(player));
        player.save();
      }
    }
  }

  public void recordLocation() {
    synchronized (player) {
      var run = current();
      var scene = player.getScene();
      if (run != null
          && !state().isTransitionPending()
          && scene != null
          && scene.getStoryLineId() == run.getId()) run.locate(new StoryLineState.Place(player));
    }
  }
}
