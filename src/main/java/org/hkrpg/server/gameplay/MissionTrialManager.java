package org.hkrpg.server.gameplay;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.SpecialAvatarExcel;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.trial.TrialAvatar;
import emu.lunarcore.server.packet.send.PacketSyncLineupNotify;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Executes whole supported trial tasks. Conditions remain observers of actual published actors. */
public final class MissionTrialManager {
  private static final Gson GSON = new Gson();
  private final Player player;
  private MissionTrialState.Party cached;
  private MissionTrialLineup lineup;
  private Map<Integer, TrialAvatar> actors = Map.of();

  public MissionTrialManager(Player player) {
    this.player = player;
  }

  private MissionTrialState state() {
    if (player.getGameplay().getCurrentMissionRaid() != 0) {
      var run = new MissionRaid(player).current();
      return run == null ? null : run.getMissionTrials();
    }
    return player.getGameplay().getMissionTrials();
  }

  public boolean inside() {
    return state() != null && state().getParty() != null;
  }

  public boolean owns(MissionTrialState.Party party) {
    synchronized (player) {
      return party != null && state() != null && state().getParty() == party;
    }
  }

  private GameplayDefinitions.Raid scriptedRaid(int main) {
    var raid = new MissionRaid(player);
    var run = raid.current();
    var row = run == null ? null : MissionRaid.resource(run.getRaidId(), run.getWorldLevel());
    var plan = RaidTeams.plan(row);
    return row != null
            && run.getMainMission() == main
            && raid.owns(main)
            && !run.isCompleted()
            && plan != null
            && Set.of("Player", "TrialOnly", "TrialOrPlayer").contains(plan.type())
            && supportedBase(run, plan)
        ? row
        : null;
  }

  private boolean supportedBase(GameplayState.MissionRaidRun run, RaidTeams.Plan plan) {
    if (!RaidTeams.snapshotRequired(plan)) return run.getTrialParty() == null;
    if ("TrialOrPlayer".equals(plan.type()))
      return !RaidTeams.frozenParty(plan) && plan.captain() == 0
          && plan.levels().isEmpty() && plan.counts().isEmpty() && plan.paths().isEmpty()
          && (run.getTrialParty() == null || player.getRaidTrialManager().allowed(plan));
    return RaidTeams.frozenParty(plan)
        && ("TrialOnly".equals(plan.type()) || plan.captain() == 0)
        && plan.requirements().isEmpty()
        && plan.levels().isEmpty()
        && plan.counts().isEmpty()
        && plan.paths().isEmpty()
        && run.getTrialParty() != null
        && ("TrialOnly".equals(plan.type()) || run.getTrialParty().getConfigs().isEmpty())
        && player.getRaidTrialManager().allowed(plan);
  }

  private boolean context(int main) {
    return player.getInstance() == null
        && player.getGameplay().getTrialRun() == null
        && !player.getGameplay().getRogue().isInside()
        && !player.getGameplay().getParkourVenue().inside()
        && (player.getGameplay().getCurrentMissionRaid() == 0 || scriptedRaid(main) != null)
        && (!player.getRaidTrialManager().inside() || scriptedRaid(main) != null)
        && player.getGameplay().getStoryLines().getCurrentId() == 0
        && !player.getGameplay().getStoryLines().isTransitionPending()
        && player.getLineupManager() != null
        && player.getLineupManager().getCurrentExtraIndex() == 0;
  }

  private static boolean ordinary(PlaneType type) {
    return type == PlaneType.Town || type == PlaneType.Maze || type == PlaneType.Train;
  }

  private boolean sceneMatches(TrialAvatarMissionConditions.Source source) {
    var scene = player.getScene();
    return scene != null
        && scene.getPlayer() == player
        && (ordinary(scene.getPlaneType()) && player.getGameplay().getCurrentMissionRaid() == 0
            || scene.getPlaneType() == PlaneType.Raid
                && scriptedRaid(source.main()) != null
                && new MissionRaid(player).active())
        && scene.getDimensionId() == 0
        && scene.getStoryLineId() == 0
        && scene.getPlaneId() == source.plane()
        && scene.getFloorId() == source.floor();
  }

  private int selected(TrialAvatarMissionConditions.Source source) {
    int chosen = 0;
    for (int id : source.targets()) {
      var row = player.getTrialManager().specialRow(id);
      if (row == null
          || row.getId() != id
          || GameData.getActorForTrial(row.getAvatarID()) == null
          || source.targets().size() == 2 && row.getAvatarID() != id - 1100000) return 0;
      if (player.getTrialManager().matchesGender(row.getAvatarID())) {
        if (chosen != 0) return 0;
        chosen = id;
      }
    }
    return chosen;
  }

  private boolean completeEquipment(TrialAvatar trial) {
    int id = trial.getSpecialAvatarExcel().getEquipmentID();
    if (id == 0) return trial.getEquips().isEmpty();
    var item = GameData.getItemExcelMap().get(id);
    var equip = GameData.getEquipExcelMap().get(id);
    var actual = trial.getEquips().get(100);
    return id > 0
        && item != null
        && item.getId() == id
        && equip != null
        && equip.getId() == id
        && item.getEquipmentExcel() == equip
        && trial.getEquips().size() == 1
        && actual != null
        && actual.getItemId() == id
        && actual.getExcel() == item
        && actual.getLevel() == trial.getSpecialAvatarExcel().getEquipmentLevel()
        && actual.getPromotion() == trial.getSpecialAvatarExcel().getEquipmentPromotion()
        && actual.getRank() == trial.getSpecialAvatarExcel().getEquipmentRank();
  }

  /** Bootstrap after the first task is accepted, including login before the scene is constructed. */
  boolean initializeOpening() {
    synchronized (player) {
      if (!OpeningPlayer.eligible(player) || player.isInBattle() || !context(OpeningPlayer.MAIN))
        return false;
      var scene = player.getScene();
      if (scene != null && (scene.getPlayer() != player || !ordinary(scene.getPlaneType())
          || scene.getPlaneId() != OpeningPlayer.PLANE || scene.getFloorId() != OpeningPlayer.FLOOR
          || scene.getDimensionId() != 0 || scene.getStoryLineId() != 0)) return false;
      String source = OpeningPlayer.source();
      if (source == null || !player.getAvatars().getTempAvatars().isEmpty()) return false;
      var team = player.getCurrentLineup();
      if (team == null || team.isExtraLineup() || team.getOwner() != player
          || team.getOwnerUid() != player.getUid() || team.getAvatars().isEmpty()
          || team.size() > 4 || new HashSet<>(team.getAvatars()).size() != team.size()
          || team.getAvatars().stream().anyMatch(id -> !player.getAvatars().getAvatars().containsKey(id)))
        return false;
      var trial = player.getTrialManager().buildTrialAvatar(OpeningPlayer.KAFKA);
      if (trial == null || trial.getSkillTree().isEmpty() || !completeEquipment(trial)) return false;
      var prepared = new MissionTrialState.Party(OpeningPlayer.MAIN, OpeningPlayer.PLANE,
          OpeningPlayer.FLOOR, player.getLineupManager().getCurrentIndex(), team.getAvatars(), 0, team.getMp());
      prepared.bindOpening(source);
      prepared.select(List.of(OpeningPlayer.KAFKA), 0, false, 0);
      prepared.getActors().put(OpeningPlayer.KAFKA, new MissionTrialState.Actor(trial));
      prepared.getVitals().put(OpeningPlayer.KAFKA, new StoryLineState.Vital(10000, 7000));
      final Map<Integer, TrialAvatar> built;
      try {
        built = rebuild(prepared, false);
      } catch (IllegalArgumentException | IllegalStateException invalidResource) {
        return false;
      }
      state().publish(prepared);
      try {
        player.save();
      } catch (RuntimeException failed) {
        state().retire();
        state().openingSource(null);
        throw failed;
      }
      install(prepared, built);
      sync();
      return true;
    }
  }

  /** Parameterless Talk task with an explicit, bounded compatibility policy. Never finishes dialogue. */
  boolean initializeStation() {
    synchronized (player) {
      if (!StationTrial.eligible(player) || player.isInBattle() || !context(StationTrial.MAIN)) return false;
      var scene = player.getScene();
      if (scene != null && (scene.getPlayer() != player || !ordinary(scene.getPlaneType())
          || scene.getDimensionId() != 0 || scene.getStoryLineId() != 0
          || scene.getPlaneId() != player.getPlaneId() || scene.getFloorId() != player.getFloorId())) return false;
      String source = StationTrial.source();
      if (source == null || !player.getAvatars().getTempAvatars().isEmpty()) return false;
      var team = player.getCurrentLineup();
      if (team == null || team.isExtraLineup() || team.getOwner() != player
          || team.getOwnerUid() != player.getUid() || team.getAvatars().isEmpty() || team.size() > 4
          || team.getLeader() < 0 || team.getLeader() >= team.size()
          || team.getMp() < 0 || team.getMp() > team.getMaxMp()
          || new HashSet<>(team.getAvatars()).size() != team.size()) return false;
      var trial = player.getTrialManager().buildTrialAvatar(StationTrial.HIMEKO);
      if (trial == null || trial.getSkillTree().isEmpty() || !completeEquipment(trial)) return false;
      var prepared = new MissionTrialState.Party(StationTrial.MAIN, StationTrial.PLANE, StationTrial.FLOOR,
          player.getLineupManager().getCurrentIndex(), team.getAvatars(), team.getLeader(), team.getMp());
      for (int id : team.getAvatars()) {
        var formal = player.getAvatars().getAvatars().get(id);
        if (formal == null || formal instanceof TrialAvatar || formal.getAvatarId() != id
            || formal.getOwner() != player || formal.getOwnerUid() != player.getUid()) return false;
        prepared.getVitals().put(id,new StoryLineState.Vital(formal.getCurrentHp(team),formal.getCurrentSp(team)));
      }
      prepared.bindStation(source);
      var roster = StationTrial.roster(team.getAvatars(),team.getLeader());
      int captain = roster.indexOf(team.getAvatars().get(team.getLeader()));
      prepared.select(roster, captain < 0 ? roster.indexOf(StationTrial.HIMEKO) : captain, false, 0);
      prepared.getActors().put(StationTrial.HIMEKO,new MissionTrialState.Actor(trial));
      prepared.getVitals().put(StationTrial.HIMEKO,new StoryLineState.Vital(10000,7000));
      final Map<Integer,TrialAvatar> built;
      try { built = rebuild(prepared,false); }
      catch (IllegalArgumentException | IllegalStateException invalid) { return false; }
      state().publish(prepared);
      try { player.save(); }
      catch (RuntimeException failed) {
        state().retire(); state().stationSource(null); throw failed;
      }
      install(prepared,built);
      sync();
      return true;
    }
  }

  /** Reserve the frozen action before publishing a scene role or completing its condition. */
  boolean execute(MissionInfo.SubMissionInfo c) {
    synchronized (player) {
      var source = TrialAvatarMissionConditions.source(c);
      if (source == null
          || GameplayDefinitions.subs.get(source.sub()) != c
          || !TrialAvatarMissionConditions.eligible(player, c)
          || player.isInBattle()
          || !context(source.main())
          || !sceneMatches(source)
          || !MissionTrialCompanions.ready(player,c)
          || TrialAvatarMissionConditions.satisfied(player, c)) return false;
      if (state().getExecuted().containsKey(source.sub())) {
        if (c.getId() != MissionTrialCompanions.SOURCE) return false;
        try {
          ensure();
          return MissionTrialCompanions.complete(player,c);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
          return false;
        }
      }
      int id = selected(source);
      if (id == 0 || player.getAvatars().getAvatars().containsKey(id)) return false;
      var previous = state().getParty();
      if (previous != null
          && (previous.getMain() != source.main()
              || previous.getPlane() != source.plane()
              || previous.getFloor() != source.floor())) return false;
      final MissionTrialState.Party prepared;
      if (previous == null) {
        var team = player.getCurrentLineup();
        boolean frozenBase =
            scriptedRaid(source.main()) != null
                && player.getRaidTrialManager().inside()
                && team == player.getRaidTrialManager().lineup();
        var base = frozenBase ? new MissionRaid(player).current().getTrialParty() : null;
        if (team == null
            || team.getOwner() != player
            || team.getOwnerUid() != player.getUid()
            || team.isExtraLineup() && !frozenBase
            || team.getAvatars().isEmpty()
            || team.size() > 4
            || new HashSet<>(team.getAvatars()).size() != team.size()
            || team.getLeader() < 0
            || team.getLeader() >= team.size()
            || team.getMp() < 0
            || team.getMp() > team.getMaxMp()
            || !player
                .getAvatars()
                .getTempAvatars()
                .keySet()
                .equals(base == null ? Set.of() : base.getConfigs().keySet())) return false;
        prepared =
            new MissionTrialState.Party(
                source.main(),
                source.plane(),
                source.floor(),
                player.getLineupManager().getCurrentIndex(),
                team.getAvatars(),
                team.getLeader(),
                team.getMp());
        if (player.getGameplay().getCurrentMissionRaid() != 0)
          prepared.bindRaid(
              player.getGameplay().getCurrentMissionRaid(),
              GSON.toJson(scriptedRaid(source.main())));
        if (frozenBase)
          prepared.bindBase(new MissionRaid(player).current().getTrialParty().frozen());
        Map<Integer, TrialAvatar> rebuiltBase = Map.of();
        if (base != null && !base.getConfigs().isEmpty()) {
          try {
            rebuiltBase =
                player
                    .getRaidTrialManager()
                    .rebuild(base, RaidTeams.plan(scriptedRaid(source.main())))
                    .actors();
          } catch (RuntimeException invalidBase) {
            return false;
          }
        }
        for (int member : team.getAvatars()) {
          var actual = player.getAvatarById(member);
          if (rebuiltBase.containsKey(member)) {
            try {
              var frozen = new MissionTrialState.Actor(rebuiltBase.get(member));
              validateActor(member, frozen, actual instanceof TrialAvatar trial ? trial : null);
              prepared.getBaseActors().put(member, frozen);
            } catch (RuntimeException invalidBase) {
              return false;
            }
            prepared
                .getVitals()
                .put(
                    member,
                    new StoryLineState.Vital(actual.getCurrentHp(team), actual.getCurrentSp(team)));
            continue;
          }
          var formal = player.getAvatars().getAvatars().get(member);
          if (formal == null
              || formal instanceof TrialAvatar
              || player.getAvatarById(member) != formal
              || formal.getAvatarId() != member
              || formal.getOwner() != player
              || formal.getOwnerUid() != player.getUid()) return false;
          prepared
              .getVitals()
              .put(
                  member,
                  new StoryLineState.Vital(formal.getCurrentHp(team), formal.getCurrentSp(team)));
        }
      } else {
        ensure();
        prepared = GSON.fromJson(GSON.toJson(previous), MissionTrialState.Party.class);
      }
      var trial = actors.get(id);
      if (trial == null && !prepared.getBaseActors().containsKey(id)) {
        if (player.getAvatars().getTempAvatars().containsKey(id)) return false;
        trial = player.getTrialManager().buildTrialAvatar(id);
        if (trial == null || trial.getSkillTree().isEmpty() || !completeEquipment(trial))
          return false;
        prepared.getActors().put(id, new MissionTrialState.Actor(trial));
        prepared.getVitals().put(id, new StoryLineState.Vital(10000, 7000));
      }
      boolean replace =
          source.task().equals("RPG.GameCore.ReplaceTrialPlayer")
              || source.task().equals("RPG.GameCore.ReplaceVirtualTeam");
      var roster = replace ? new ArrayList<Integer>() : new ArrayList<>(prepared.getRoster());
      if (!roster.contains(id)) roster.add(id);
      if (roster.size() > 4) return false;
      prepared.select(
          roster,
          replace ? 0 : prepared.getLeader(),
          source.task().equals("RPG.GameCore.ReplaceVirtualTeam"),
          source.sub());
      prepared.getSources().put(source.sub(), source.signature());
      prepared.mp(Math.min(prepared.getMp(), maximum(prepared)));
      // Validate the complete frozen party before changing any player state.
      final Map<Integer, TrialAvatar> built;
      try {
        built = rebuild(prepared, false);
      } catch (IllegalArgumentException | IllegalStateException invalidResource) {
        return false;
      }
      var previousExecutions = new HashMap<>(state().getExecuted());
      state().publish(prepared);
      try {
        player.save();
      } catch (RuntimeException failed) {
        state().retire();
        state().getExecuted().clear();
        state().getExecuted().putAll(previousExecutions);
        if (previous != null) state().publish(previous);
        throw failed;
      }
      install(prepared, built);
      sync();
      return MissionTrialCompanions.complete(player,c);
    }
  }

  public MissionTrialLineup lineup() {
    synchronized (player) {
      if (!inside()) return null;
      ensure();
      return lineup;
    }
  }

  /** Only the explicitly approved station compatibility party may be edited by the player. */
  public boolean editableStation() {
    synchronized (player) {
      if (!inside() || state().getParty().getStationSource() == null || player.isInBattle()
          || !context(StationTrial.MAIN) || !StationTrial.active(player) || !StationTrial.place(player))
        return false;
      var scene = player.getScene();
      if (scene == null || scene.getPlayer() != player || !ordinary(scene.getPlaneType())
          || scene.getPlaneId() != player.getPlaneId() || scene.getFloorId() != player.getFloorId()
          || scene.getDimensionId() != 0 || scene.getStoryLineId() != 0) return false;
      ensure();
      return true;
    }
  }

  /** Save a compact station party; permanent equivalents are queued only when actually owned. */
  public Retcode replaceStation(List<Integer> ids, int leader) {
    synchronized (player) {
      if (!editableStation()) return Retcode.SYSTEM_BUSY;
      if (ids == null || ids.isEmpty() || ids.size() > 4 || leader < 0 || leader >= ids.size()
          || new HashSet<>(ids).size() != ids.size()) return Retcode.REQ_PARA_INVALID;
      var previous = state().getParty();
      var next = GSON.fromJson(GSON.toJson(previous), MissionTrialState.Party.class);
      var formal = player.getLineupManager().getLineups()[previous.getIndex()];
      var projection = new ArrayList<Integer>();
      var actorsSeen = new HashSet<Integer>();
      boolean representable = true;
      for (int id : ids) {
        int permanent = id == StationTrial.HIMEKO ? 1003 : id;
        var avatar = player.getAvatars().getAvatars().get(permanent);
        if (!actorsSeen.add(permanent)) return Retcode.REQ_PARA_INVALID;
        if (avatar == null || avatar instanceof TrialAvatar || avatar.getAvatarId() != permanent
            || avatar.getOwner() != player || avatar.getOwnerUid() != player.getUid()) {
          if (id != StationTrial.HIMEKO) return Retcode.REQ_PARA_INVALID;
          representable = false;
        }
        projection.add(permanent);
        if (!next.getVitals().containsKey(id)) {
          if (id == StationTrial.HIMEKO || avatar == null) return Retcode.PLAYER_DATA_ERROR;
          next.getVitals().put(id, new StoryLineState.Vital(
              avatar.getCurrentHp(formal), avatar.getCurrentSp(formal)));
        }
      }
      List<Integer> pending = representable ? projection : null;
      if (previous.isStationEdited() && previous.getRoster().equals(ids)
          && previous.getLeader() == leader && Objects.equals(previous.getStationPreset(), pending))
        return Retcode.SUCC;
      next.select(ids, leader, false, 0);
      next.stationEdit(pending, leader);
      try { rebuild(next, false); }
      catch (IllegalArgumentException | IllegalStateException invalid) { return Retcode.PLAYER_DATA_ERROR; }
      int oldLeader = previous.getRoster().get(previous.getLeader());
      state().publish(next);
      try { player.save(); }
      catch (RuntimeException failed) {
        state().publish(previous);
        return Retcode.SYSTEM_BUSY;
      }
      // Only the party view changed: retain the already published trial actor and its live effects.
      cached = next;
      lineup = new MissionTrialLineup(player, next);
      sync();
      if (oldLeader != ids.get(leader)) player.getMissionManager().onTeamLeaderChange(ids.get(leader));
      return Retcode.SUCC;
    }
  }

  /** Journal a station edit before touching the separate ordinary-team document. */
  private void finalizeStationPreset(MissionTrialState.Party party) {
    var preset = party.getStationPreset();
    if (!party.isStationEdited() || preset == null) return;
    for (int id : preset) {
      var actor = player.getAvatars().getAvatars().get(id);
      if (actor == null || actor instanceof TrialAvatar || actor.getAvatarId() != id
          || actor.getOwner() != player || actor.getOwnerUid() != player.getUid()) return;
    }
    var team = player.getLineupManager().getLineups()[party.getIndex()];
    if (team == null || team.isExtraLineup()) throw new IllegalStateException("Missing station preset team");
    if (!party.isStationFinalizing()) {
      party.stationFinalizing(true);
      try { player.save(); }
      catch (RuntimeException failed) { party.stationFinalizing(false); throw failed; }
    }
    if (team.getAvatars().equals(preset) && team.getLeader() == party.getStationPresetLeader()) return;
    var old = new ArrayList<>(team.getAvatars());
    int oldLeader = team.getLeader();
    team.getAvatars().clear();
    team.getAvatars().addAll(preset);
    team.setLeader(party.getStationPresetLeader());
    try { team.save(); }
    catch (RuntimeException failed) {
      team.getAvatars().clear();
      team.getAvatars().addAll(old);
      team.setLeader(oldLeader);
      throw failed;
    }
  }

  private void ensure() {
    var party = state().getParty();
    if (party == null) throw new IllegalStateException("Missing mission trial party");
    if (cached != party) install(party, rebuild(party, true));
    validate(party, true);
    for (var entry : actors.entrySet()) {
      validateActor(entry.getKey(), party.getActors().get(entry.getKey()), entry.getValue());
    }
  }

  private void validateActor(int id, MissionTrialState.Actor frozen, TrialAvatar actor) {
    if (actor == null || frozen == null)
      throw new IllegalStateException("Missing owned mission actor");
    validateSkills(id, frozen);
    int equipId = actor.getSpecialAvatarExcel().getEquipmentID();
    if (!GSON.toJson(actor.getSpecialAvatarExcel()).equals(frozen.getSpecial())
        || !GSON.toJson(player.getTrialManager().specialRow(id)).equals(frozen.getSpecial())
        || !GSON.toJson(GameData.getActorForTrial(actor.getActorId()))
            .equals(frozen.getDefinition())
        || !GSON.toJson(GameData.getItemExcelMap().get(equipId)).equals(frozen.getItem())
        || !GSON.toJson(GameData.getEquipExcelMap().get(equipId)).equals(frozen.getEquipment())
        || !actor
            .getSkillPlan()
            .equals(GSON.fromJson(frozen.getSkillPlan(), TrialSkillPresets.Plan.class))
        || !actor.getSkillTree().equals(frozen.getSkills())
        || !completeEquipment(actor)
        || actor.getLevel() != actor.getSpecialAvatarExcel().getLevel()
        || actor.getPromotion() != actor.getSpecialAvatarExcel().getPromotion()
        || actor.getRank() != actor.getSpecialAvatarExcel().getRank())
      throw new IllegalStateException("Mission trial actor resource changed: " + id);
    if (actor.getOwner() != player
        || actor.getOwnerUid() != player.getUid()
        || actor.getAvatarId() != id
        || !player.getTrialManager().matchesGender(actor.getActorId())
        || player.getAvatars().getTempAvatars().get(id) != actor)
      throw new IllegalStateException("Mission trial actor cache changed");
  }

  private void validateBaseActors(MissionTrialState.Party party) {
    var run = party.getRaid() == 0 ? null : new MissionRaid(player).current();
    var base = run == null ? null : run.getTrialParty();
    if (party.getBaseActors() == null
        || !party
            .getBaseActors()
            .keySet()
            .equals(base == null ? Set.of() : base.getConfigs().keySet()))
      throw new IllegalStateException("Invalid source mission base actor set");
    for (var entry : party.getBaseActors().entrySet()) {
      int id = entry.getKey();
      var frozen = entry.getValue();
      var actual = player.getAvatarById(id);
      if (frozen == null
          || !base.getConfigs().get(id).equals(frozen.getSpecial())
          || !base.getSkills().get(id).equals(frozen.getSkills())
          || !GSON.fromJson(base.getPlans().get(id), TrialSkillPresets.Plan.class)
              .equals(GSON.fromJson(frozen.getSkillPlan(), TrialSkillPresets.Plan.class)))
        throw new IllegalStateException("Mission base actor snapshot changed");
      validateActor(id, frozen, actual instanceof TrialAvatar trial ? trial : null);
    }
    var plan = run == null ? null : RaidTeams.plan(scriptedRaid(party.getMain()));
    if (plan != null
        && plan.captain() > 0
        && (party.getRoster().size() < base.getRoster().size()
            || !party.getRoster().subList(0, base.getRoster().size()).equals(base.getRoster())
            || party.getLeader() != base.getLeader()))
      throw new IllegalStateException("Mission script changed a fixed raid captain");
  }

  private void validate(MissionTrialState.Party party, boolean restoring) {
    var raid = party.getRaid() == 0 ? null : scriptedRaid(party.getMain());
    var run = party.getRaid() == 0 ? null : new MissionRaid(player).current();
    if (!context(party.getMain())
        || party.getRaid() != player.getGameplay().getCurrentMissionRaid()
        || (party.getRaid() == 0
            ? party.getRaidSource() != null
            : raid == null || !GSON.toJson(raid).equals(party.getRaidSource()))
        || (run == null || run.getTrialParty() == null
            ? party.getRaidBase() != null
            : !run.getTrialParty().frozen().equals(party.getRaidBase()))
        || party.getMain() <= 0
        || party.getPlane() <= 0
        || party.getFloor() <= 0
        || party.getIndex() != player.getLineupManager().getCurrentIndex()
        || (party.getStationSource() == null
            ? !MissionStateRecords.mainDoing(player, party.getMain()) : !StationTrial.active(player))
        || party.getRoster().isEmpty()
        || party.getRoster().size() > 4
        || new HashSet<>(party.getRoster()).size() != party.getRoster().size()
        || party.getLeader() < 0
        || party.getLeader() >= party.getRoster().size()
        || party.getMp() < 0
        || party.getMp() > maximum(party)
        || party.getActors().isEmpty() && party.getBaseActors().isEmpty()
        || party.getActors().size() > 64
        || party.getSources().isEmpty() && party.getOpeningSource() == null && party.getStationSource() == null
        || party.getSources().size() > 64
        || !party.getVitals().keySet().containsAll(party.getActors().keySet())
        || !party.getVitals().keySet().containsAll(party.getRoster()))
      throw new IllegalStateException("Invalid frozen mission party");
    validateBaseActors(party);
    var selectedActors = new HashSet<Integer>();
    boolean opening = party.getOpeningSource() != null;
    boolean station = party.getStationSource() != null;
    if (station) {
      if (!StationTrial.matches(player,party,restoring))
        throw new IllegalStateException("Invalid station compatibility trial source");
      selectedActors.add(StationTrial.HIMEKO);
    }
    if (opening) {
      if (!OpeningPlayer.matches(player, party, restoring))
        throw new IllegalStateException("Invalid opening trial source");
      selectedActors.add(OpeningPlayer.KAFKA);
    }
    for (var entry : party.getSources().entrySet()) {
      var source =
          TrialAvatarMissionConditions.source(GameplayDefinitions.subs.get(entry.getKey()));
      if (source == null
          || source.main() != party.getMain()
          || source.plane() != party.getPlane()
          || source.floor() != party.getFloor()
          || !source.signature().equals(entry.getValue())
          || !MissionStateRecords.subAccepted(player, source.sub())
          || !MissionTrialCompanions.owned(player,GameplayDefinitions.subs.get(source.sub()))
          || selected(source) == 0
          || !party.getActors().containsKey(selected(source)) && !party.getBaseActors().containsKey(selected(source))
          || restoring && !entry.getValue().equals(state().getExecuted().get(entry.getKey())))
        throw new IllegalStateException("Mission trial source or receipt changed");
      selectedActors.add(selected(source));
    }
    var last =
        TrialAvatarMissionConditions.source(GameplayDefinitions.subs.get(party.getLastSub()));
    selectedActors.removeAll(party.getBaseActors().keySet());
    if (!selectedActors.equals(party.getActors().keySet())
        || (last == null
            ? !(opening || station) || party.getLastSub() != 0 || !party.getSources().isEmpty()
            : !party.getSources().containsKey(last.sub())
              || !party.getRoster().contains(selected(last))
              || party.isVirtual() != last.task().equals("RPG.GameCore.ReplaceVirtualTeam")
              || Set.of("RPG.GameCore.ReplaceTrialPlayer", "RPG.GameCore.ReplaceVirtualTeam")
                .contains(last.task())
                && !party.getRoster().equals(List.of(selected(last)))))
      throw new IllegalStateException("Invalid mission trial roster binding");
    var entryPlan = raid == null ? null : RaidTeams.plan(raid);
    if (entryPlan != null && last != null && "TrialOrPlayer".equals(entryPlan.type())
        && "RPG.GameCore.ReplaceTrialPlayer".equals(last.task())
        && !entryAllowed(party, entryPlan))
      throw new IllegalStateException("Invalid original mission trial entry team");
  }

  private void validateSkills(int id, MissionTrialState.Actor frozen) {
    var row = GSON.fromJson(frozen.getSpecial(), SpecialAvatarExcel.class);
    var expected = new HashSet<String>();
    if (row == null
        || row.getId() != id
        || frozen.getSkills() == null
        || frozen.getSkills().isEmpty()
        || frozen.getSkillResources() == null)
      throw new IllegalStateException("Missing mission skill snapshot");
    for (var entry : frozen.getSkills().entrySet()) {
      if (entry.getKey() == null
          || entry.getValue() == null
          || entry.getKey() <= 0
          || entry.getValue() <= 0
          || entry.getValue() > 100) throw new IllegalStateException("Invalid mission skill level");
      for (int level = 1; level <= entry.getValue(); level++) {
        String key = entry.getKey() + ":" + level;
        expected.add(key);
        var skill =
            GameData.getAvatarSkilltree(
                row.getAvatarID(), row.getEnhancedID(), entry.getKey(), level);
        var actual =
            skill == null
                ? null
                : GameData.getActorForTrial(row.getAvatarID())
                        instanceof emu.lunarcore.data.excel.TrialActorExcel
                    ? GameData.getTrialActorSkillTreeExcelMap().get(skill.getPointID(), level)
                    : GameData.getAvatarSkillTreeExcelMap().get(skill.getPointID(), level);
        if (skill == null
            || actual != skill
            || !GSON.toJson(skill).equals(frozen.getSkillResources().get(key)))
          throw new IllegalStateException("Frozen mission skill resource changed");
      }
    }
    if (!expected.equals(frozen.getSkillResources().keySet()))
      throw new IllegalStateException("Invalid mission skill resource set");
  }

  private Map<Integer, TrialAvatar> rebuild(MissionTrialState.Party party, boolean restoring) {
    validate(party, restoring);
    var prepared = new HashMap<Integer, TrialAvatar>();
    for (var entry : party.getActors().entrySet()) {
      int id = entry.getKey();
      var frozen = entry.getValue();
      validateSkills(id, frozen);
      var row =
          frozen == null ? null : GSON.fromJson(frozen.getSpecial(), SpecialAvatarExcel.class);
      var excel = row == null ? null : GameData.getActorForTrial(row.getAvatarID());
      if (row == null
          || row.getId() != id
          || excel == null
          || excel.getAvatarID() != row.getAvatarID()
          || !GSON.toJson(player.getTrialManager().specialRow(id)).equals(frozen.getSpecial())
          || !GSON.toJson(excel).equals(frozen.getDefinition())
          || !GSON.toJson(GameData.getItemExcelMap().get(row.getEquipmentID()))
              .equals(frozen.getItem())
          || !GSON.toJson(GameData.getEquipExcelMap().get(row.getEquipmentID()))
              .equals(frozen.getEquipment())
          || !player.getTrialManager().matchesGender(row.getAvatarID())
          || player.getAvatars().getAvatars().containsKey(id)
          || !TrialSkillTrees.supports(excel, row.getEnhancedID())
          || excel instanceof emu.lunarcore.data.excel.TrialActorExcel plot
              && !plot.supportsTrial(row)
          || frozen.getSkills() == null
          || frozen.getSkills().isEmpty())
        throw new IllegalStateException("Invalid frozen mission actor: " + id);
      var plan = GSON.fromJson(frozen.getSkillPlan(), TrialSkillPresets.Plan.class);
      if (plan == null || plan.custom() != "TYPE_CUSTOM".equals(row.getSkillTreeTemplate()))
        throw new IllegalStateException("Invalid frozen mission skill policy");
      var trial = TrialAvatar.restore(player, row, excel, plan, frozen.getSkills());
      if (!completeEquipment(trial))
        throw new IllegalStateException("Missing frozen mission equipment");
      var cachedActor = player.getAvatars().getTempAvatars().get(id);
      if (cachedActor != null && cachedActor != actors.get(id))
        throw new IllegalStateException("Mission actor overlaps an unrelated trial");
      prepared.put(id, trial);
    }
    for (int id : party.getRoster()) {
      if (!party.getActors().containsKey(id) && !party.getBaseActors().containsKey(id)) {
        var formal = player.getAvatars().getAvatars().get(id);
        if (formal == null
            || formal instanceof TrialAvatar
            || formal.getOwner() != player
            || formal.getOwnerUid() != player.getUid()
            || formal.getAvatarId() != id)
          throw new IllegalStateException("Missing saved formal mission party member");
      }
    }
    for (var vital : party.getVitals().values()) {
      if (vital == null
          || vital.getHp() < 0
          || vital.getHp() > 10000
          || vital.getSp() < 0
          || vital.getSp() > 10000)
        throw new IllegalStateException("Invalid frozen mission vitals");
    }
    return prepared;
  }

  private int maximum(MissionTrialState.Party party) {
    int maximum = emu.lunarcore.GameConstants.MAX_MP;
    for (int id : party.getRoster()) {
      var frozen =
          party.getActors().containsKey(id)
              ? party.getActors().get(id)
              : party.getBaseActors().get(id);
      var trial =
          frozen == null ? null : GSON.fromJson(frozen.getSpecial(), SpecialAvatarExcel.class);
      maximum =
          Math.addExact(
              maximum,
              emu.lunarcore.GameConstants.BONUS_MAX_MP.get(
                  trial == null ? id : trial.getAvatarID()));
    }
    return maximum;
  }

  private void install(MissionTrialState.Party party, Map<Integer, TrialAvatar> prepared) {
    unpublish();
    cached = party;
    actors = Map.copyOf(prepared);
    lineup = new MissionTrialLineup(player, party);
    actors.values().forEach(player.getAvatars()::addTempAvatar);
  }

  private void unpublish() {
    for (var entry : actors.entrySet())
      if (player.getAvatars().getTempAvatars().get(entry.getKey()) == entry.getValue())
        player.getAvatars().getTempAvatars().remove(entry.getKey());
    actors = Map.of();
    cached = null;
    lineup = null;
  }

  /**
   * Main completion, cancellation and leaving the owning floor release only this script's actors.
   */
  public boolean reconcile() {
    synchronized (player) {
      var party = state() == null ? null : state().getParty();
      if (party == null || player.isInBattle()) return false;
      boolean station = party.getStationSource() != null;
      boolean ended = station ? !StationTrial.active(player) :
          MissionStateRecords.mainCompleted(player, party.getMain())
              || player.getGameplay().getCancelledMissions().contains(party.getMain())
              || party.getSources().keySet().stream()
                  .anyMatch(player.getGameplay().getCancelledSubs()::contains)
              || party.getOpeningSource() != null
                  && (player.getGameplay().getCancelledSubs().contains(OpeningPlayer.INTRO)
                      || player.getGameplay().getCancelledSubs().contains(OpeningPlayer.REMOVE));
      boolean left = station ? !StationTrial.place(player) :
          party.getRaid() == 0
              && (party.getPlane() != player.getPlaneId()
                  || party.getFloor() != player.getFloorId());
      var scene = player.getScene();
      if (scene != null)
        left |=
            scene.getDimensionId() != 0
                || scene.getStoryLineId() != 0
                || (party.getRaid() == 0
                    ? !ordinary(scene.getPlaneType())
                    : scene.getPlaneType() != PlaneType.Raid);
      if (!ended && !left) return false;
      if (station) finalizeStationPreset(party);
      state().retire();
      try {
        player.save();
      } catch (RuntimeException failed) {
        // Keep the same frozen party and published actors until retirement is durable.
        // Otherwise a failed save loses the team in memory and revives it on reconnect.
        state().publish(party);
        throw failed;
      }
      unpublish();
      sync();
      return true;
    }
  }

  /**
   * Formal avatars and teams must already be loaded. Restores frozen data; never re-executes a
   * script.
   */
  public void restoreOnLogin() {
    synchronized (player) {
      if (!inside() || reconcile()) return;
      ensure();
    }
  }

  /**
   * A paused raid keeps its frozen party in its own run, while ordinary-world actors are detached.
   */
  public void detach() {
    synchronized (player) {
      unpublish();
    }
  }

  /** A source append does not grant the client permission to replace the declared captain. */
  public boolean leaderAllowed(int slot) {
    synchronized (player) {
      if (!inside() || slot < 0 || slot >= state().getParty().getRoster().size()) return false;
      var party = state().getParty();
      var row = party.getRaid() == 0 ? null : scriptedRaid(party.getMain());
      var plan = row == null ? null : RaidTeams.plan(row);
      return party.getRaid() == 0
          || plan != null
              && (plan.captain() == 0
                  || slot == new MissionRaid(player).current().getTrialParty().getLeader());
    }
  }

  /** Only the exact saved source may authorize dynamic trials in an owned raid team. */
  public boolean allowed(RaidTeams.Plan plan) {
    synchronized (player) {
      if (!inside() || state().getParty().getRaid() == 0 || plan == null) return false;
      var row = scriptedRaid(state().getParty().getMain());
      if (row == null || !plan.equals(RaidTeams.plan(row))) return false;
      ensure();
      var members = new ArrayList<emu.lunarcore.game.avatar.GameAvatar>();
      for (int id : lineup.getAvatars()) {
        var actor = player.getAvatarById(id);
        if (actor == null || actor.getOwner() != player || actor.getOwnerUid() != player.getUid())
          return false;
        members.add(actor);
      }
      if (RaidTeams.membersAllowed(plan, members)) return true;
      var party = state().getParty();
      var last = TrialAvatarMissionConditions.source(GameplayDefinitions.subs.get(party.getLastSub()));
      return last != null && "RPG.GameCore.ReplaceTrialPlayer".equals(last.task())
          && "TrialOrPlayer".equals(plan.type()) && !RaidTeams.frozenParty(plan)
          && plan.captain() == 0 && plan.levels().isEmpty() && plan.counts().isEmpty()
          && plan.paths().isEmpty() && entryAllowed(party, plan);
    }
  }

  /** An authored replacement may change an entry-only inclusion after its original team is proven. */
  private boolean entryAllowed(MissionTrialState.Party party, RaidTeams.Plan plan) {
    var ids = party.getEntryRoster();
    if (ids == null || ids.isEmpty() || ids.size() > 4
        || ids.stream().anyMatch(id -> id == null || id <= 0)
        || new HashSet<>(ids).size() != ids.size()) return false;
    var run = new MissionRaid(player).current();
    var base = run == null ? null : run.getTrialParty();
    if (base != null && (!ids.equals(base.getRoster()) || !base.frozen().equals(party.getRaidBase())))
      return false;
    var members = new ArrayList<emu.lunarcore.game.avatar.GameAvatar>();
    for (int id : ids) {
      var member = player.getAvatarById(id);
      if (member == null || member.getAvatarId() != id || member.getOwner() != player
          || member.getOwnerUid() != player.getUid()
          || (base == null || !base.getConfigs().containsKey(id))
              && player.getAvatars().getAvatars().get(id) != member) return false;
      members.add(member);
    }
    return RaidTeams.membersAllowed(plan, members);
  }

  /** Remove a proven actor; the opening Silver Wolf task leaves Kafka in the temporary party. */
  boolean remove(MissionInfo.SubMissionInfo c) {
    synchronized (player) {
      var source = MissionTrialRemovals.source(player, c);
      if (!MissionTrialRemovals.context(player, source)
          || !MissionStateRecords.doing(player.getMissionManager().sub(c.getId()), c.getId(), 1)
          || state() == null || state().getRemovals() == null
          || state().getRemovals().size() >= 4096 || state().getRemovals().containsKey(c.getId())) return false;
      var party = state().getParty();
      if (party == null || party.getRaid() != 0 || party.getMain() != source.main()
          || party.getPlane() != source.plane() || party.getFloor() != source.floor()
          || !(party.getActors().keySet().equals(Set.of(source.avatar()))
              || party.getOpeningSource() != null && source.sub() == 100010123
                  && party.getActors().keySet().equals(Set.of(OpeningPlayer.KAFKA, 1001006)))
          || !party.getBaseActors().isEmpty() || !party.getRoster().contains(source.avatar())) return false;
      if (source.sub() == OpeningPlayer.REMOVE
          && (party.getOpeningSource() == null || !player.hasCreatedCharacter()
              || !MissionStateRecords.subCompleted(player, 100010112))) return false;
      try {
        ensure();
        var normal = player.getLineupManager().getLineups()[party.getIndex()];
        if (normal == null || normal.isExtraLineup() || party.getEntryRoster() == null
            || party.getEntryRoster().isEmpty() || !normal.getAvatars().equals(party.getEntryRoster())
            || !player.getAvatars().getTempAvatars().keySet().equals(party.getActors().keySet())) return false;
        for (int id : normal.getAvatars()) {
          var formal = player.getAvatars().getAvatars().get(id);
          if (formal == null || formal.getAvatarId() != id || formal.getOwner() != player
              || formal.getOwnerUid() != player.getUid()) return false;
        }
        var receipt = new MissionTrialRemovals.Receipt(source, party);
        MissionTrialState.Party remaining = null;
        Map<Integer, TrialAvatar> rebuilt = Map.of();
        if (party.getActors().size() > 1) {
          remaining = GSON.fromJson(GSON.toJson(party), MissionTrialState.Party.class);
          remaining.getActors().remove(source.avatar());
          remaining.getVitals().remove(source.avatar());
          remaining.getSources().keySet().removeAll(receipt.getCreations().keySet());
          remaining.select(List.of(OpeningPlayer.KAFKA), 0, false, 0);
          rebuilt = rebuild(remaining, false);
        }
        // Removal proof and the remaining party share the same durable player document.
        state().getRemovals().put(c.getId(), receipt);
        if (remaining == null) state().retire(); else state().publish(remaining);
        try {
          player.save();
        } catch (RuntimeException failed) {
          state().getRemovals().remove(c.getId());
          state().publish(party);
          throw failed;
        }
        if (remaining == null) unpublish(); else install(remaining, rebuilt);
        sync();
        return true;
      } catch (IllegalArgumentException | IllegalStateException | NullPointerException invalid) {
        return false;
      }
    }
  }

  private void sync() {
    if (player.getScene() != null) player.getScene().syncLineup();
    if (player.isOnline()) player.sendPacket(new PacketSyncLineupNotify(player.getCurrentLineup()));
  }
}
