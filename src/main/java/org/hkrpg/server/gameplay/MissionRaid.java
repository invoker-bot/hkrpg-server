package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.MapEntranceExcel;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Unsaved finite mission entrances with verified teams; task history is retained. */
public final class MissionRaid {
  private final Player player;

  public MissionRaid(Player player) {
    this.player = player;
  }

  private static boolean empty(List<?> values) {
    return values != null && values.isEmpty();
  }

  public static GameplayDefinitions.Raid resource(int raid, int world) {
    var row = GameplayDefinitions.raids.get((long) raid * 100 + world);
    if (raid <= 0
        || world != 0
        || row == null
        || row.RaidID() != raid
        || row.WorldLevel() != world
        || !"Mission".equals(row.Type())
        || RaidTeams.plan(row) == null
        || !RaidEntranceSources.supported(row)
        || row.SkipJoinLineup()
        || !MissionRaidPayout.supported(row)
        || row.MainMissionIDList() == null
        || row.MainMissionIDList().isEmpty()
        || row.MainMissionIDList().getFirst() == null
        || row.MainMissionIDList().getFirst() <= 0
        || row.MainMissionIDBefore() <= 0
        || row.MainMissionIDAfter() <= 0
        || !empty(row.RaidTargetID())
        || !empty(row.LimitIDList())
        || !empty(row.BuffParamList())
        || !empty(row.UnlockWorldLevel())
        || !RaidEntryRecovery.supported(row)) return null;
    if (MissionRaidSequence.required(row)) {
      if (!MissionRaidSequence.supported(row)
          || GameData.getMainMissionExcelMap().get(row.MainMissionIDBefore()) == null
          || GameData.getMainMissionExcelMap().get(row.MainMissionIDAfter()) == null) return null;
      return row;
    }
    if (row.MainMissionIDAfter() != row.MainMissionIDBefore()) return null;
    int main = row.MainMissionIDList().getFirst();
    if (MissionRaidPayout.required(row) && !MissionRaidSequence.sourceSupported(main)) return null;
    var config = GameData.getMainMissionExcelMap().get(main);
    var info = GameData.getMissionInfos().get(main);
    if (config == null
        || config.getId() != main
        || !config.isInRaid()
        || info == null
        || info.getMainMissionID() != main
        || info.getStartSubMissionList() == null
        || info.getStartSubMissionList().length == 0
        || info.getFinishSubMissionList() == null
        || info.getFinishSubMissionList().length == 0
        || GameData.getMainMissionExcelMap().get(row.MainMissionIDBefore()) == null) return null;
    return row;
  }

  private static boolean valid(QuestState state, int id) {
    return state != null
        && state.getQuestId() == id
        && state.getPhase() != null
        && state.getProgress() >= 0
        && (state.getPhase() == QuestState.Phase.DOING
            ? state.getFinishTime() == 0
            : state.getProgress() > 0 && state.getFinishTime() > 0);
  }

  private boolean parent(GameplayDefinitions.Raid row) {
    int id = row.MainMissionIDBefore();
    return valid(player.getGameplay().getMainMissions().get(id), id)
        && !player.getGameplay().getCancelledMissions().contains(id);
  }

  public GameplayState.MissionRaidRun current() {
    int id = player.getGameplay().getCurrentMissionRaid();
    var run = player.getGameplay().getMissionRaids().get(id);
    return id > 0 && run != null && run.getRaidId() == id ? run : null;
  }

  /**
   * Ownership is also usable while constructing the destination scene, before Player.scene changes.
   */
  public boolean owns(int main) {
    var run = current();
    var row = run == null ? null : resource(run.getRaidId(), run.getWorldLevel());
    var state = player.getGameplay().getMainMissions().get(main);
    return row != null
        && RaidEntranceSources.permits(player, row)
        && RaidTeams.matches(run, row)
        && run.getMainMission() == main
        && sequence(run, row)
        && payout(run, row)
        && parent(row)
        && (state == null || valid(state, main))
        && !player.getGameplay().getCancelledMissions().contains(main);
  }

  private boolean sequence(GameplayState.MissionRaidRun run, GameplayDefinitions.Raid row) {
    return !MissionRaidSequence.required(row)
        ? run.getSequence() == null && row.MainMissionIDList().getFirst() == run.getMainMission()
        : run.getSequence() != null && run.getSequence().consistent(player, run, row);
  }

  private boolean payout(GameplayState.MissionRaidRun run, GameplayDefinitions.Raid row) {
    return MissionRaidPayout.required(row)
        ? run.getPayout() != null && run.getPayout().matches(row)
        : run.getPayout() == null;
  }

  private static boolean entryMatches(MapEntranceExcel entry, int plane, int floor) {
    return entry != null && entry.getPlaneID() == plane && entry.getFloorID() == floor;
  }

  private boolean ordinary(GameplayState.RogueReturn location) {
    if (location == null || location.getPos() == null || location.getRot() == null) return false;
    var entry = GameData.getMapEntranceExcelMap().get(location.getEntry());
    var plane = GameData.getMazePlaneExcelMap().get(location.getPlane());
    return entryMatches(entry, location.getPlane(), location.getFloor())
        && plane != null
        && (plane.getPlaneType() == PlaneType.Town || plane.getPlaneType() == PlaneType.Maze);
  }

  private static boolean raidFloor(int main, int plane, int floor) {
    var info = GameData.getMissionInfos().get(main);
    var excel = GameData.getMazePlaneExcelMap().get(plane);
    return info != null
        && excel != null
        && excel.getPlaneType() == PlaneType.Raid
        && Arrays.stream(info.getSubMissionList())
            .anyMatch(
                sub ->
                    sub != null
                        && sub.getMainMissionID() == main
                        && sub.getLevelPlaneID() == plane
                        && sub.getLevelFloorID() == floor
                        && GameplayDefinitions.subs.get(sub.getId()) == sub);
  }

  private boolean location(GameplayState.MissionRaidRun run) {
    return run != null
        && owns(run.getMainMission())
        && raidFloor(run, run.getPlane(), run.getFloor())
        && entryMatches(
            GameData.getMapEntranceExcelMap().get(run.getEntry()), run.getPlane(), run.getFloor())
        && ordinary(run.getReturnTo())
        && player.getEntryId() == run.getEntry()
        && player.getPlaneId() == run.getPlane()
        && player.getFloorId() == run.getFloor();
  }

  private boolean raidFloor(GameplayState.MissionRaidRun run, int plane, int floor) {
    var row = resource(run.getRaidId(), run.getWorldLevel());
    return row != null
        && sequence(run, row)
        && row.MainMissionIDList().stream().anyMatch(main -> raidFloor(main, plane, floor));
  }

  public boolean active() {
    return location(current());
  }

  /**
   * Called before saving Player; reserving a transition must not overwrite its source checkpoint.
   */
  public void recordLocation() {
    synchronized (player) {
      var run = current();
      if (active() && run.getPendingTransfer() == 0) run.checkpoint(player);
    }
  }

  public boolean activeFor(int main) {
    var config = GameData.getMainMissionExcelMap().get(main);
    return config == null
        || !config.isInRaid()
        || owns(main)
            && active()
            && RaidTeams.allowed(player, resource(current().getRaidId(), current().getWorldLevel()))
            && valid(player.getGameplay().getMainMissions().get(main), main);
  }

  private MapEntranceExcel initial(GameplayDefinitions.Raid row) {
    int main = row.MainMissionIDList().getFirst();
    var info = GameData.getMissionInfos().get(main);
    var first = GameplayDefinitions.subs.get(info.getStartSubMissionList()[0]);
    if (first == null
        || first.getMainMissionID() != main
        || !raidFloor(main, first.getLevelPlaneID(), first.getLevelFloorID())) return null;
    var entries =
        GameData.getMapEntranceExcelMap().values().stream()
            .filter(entry -> entryMatches(entry, first.getLevelPlaneID(), first.getLevelFloorID()))
            .toList();
    return entries.size() == 1 ? entries.getFirst() : null;
  }

  private boolean checkpoint(GameplayState.MissionRaidRun run) {
    var point = run.getCheckpoint();
    return point != null
        && point.getPos() != null
        && point.getRot() != null
        && point.getEntry() == run.getEntry()
        && point.getPlane() == run.getPlane()
        && point.getFloor() == run.getFloor()
        && run.getPendingTransfer() == 0
        && raidFloor(run, point.getPlane(), point.getFloor())
        && entryMatches(
            GameData.getMapEntranceExcelMap().get(point.getEntry()),
            point.getPlane(),
            point.getFloor());
  }

  public Retcode enter(int raid, int world, int saved, int prop, List<Integer> selected) {
    return enterSelected(
        raid,
        world,
        saved,
        prop,
        selected == null
            ? null
            : selected.stream().map(id -> new RaidTeams.Selection(id, false)).toList());
  }

  public Retcode enterSelected(
      int raid, int world, int saved, int prop, List<RaidTeams.Selection> selected) {
    synchronized (player) {
      var row = resource(raid, world);
      if (row == null || !RaidEntranceSources.permits(player, row) || saved != 0 || prop != 0)
        return Retcode.REQ_PARA_INVALID;
      var teamPlan = RaidTeams.plan(row);
      if (teamPlan == null) return Retcode.REQ_PARA_INVALID;
      if (player.getGameplay().getCurrentMissionRaid() != 0
          || player.getGameplay().getParkourVenue().inside()) return Retcode.RAID_DOING;
      var team = player.getCurrentLineup();
      if (player.isInBattle()
          || player.getInstance() != null
          || player.getGameplay().getTrialRun() != null
          || player.getGameplay().getStoryLines().getCurrentId() != 0
          || player.getGameplay().getStoryLines().isTransitionPending()
          || player.getGameplay().getRogue().isInside()
          || player.getGameplay().getClockPark().getActive() != null
          || player.getGameplay().getRhythm().getActiveLevel() != 0
          || player.getGameplay().getParkour().getActiveLevel() != 0
          || team == null
          || team.isExtraLineup()
          || player.getScene() == null) return Retcode.REQ_PARA_INVALID;
      var roster = List.copyOf(team.getAvatars());
      if (selected == null || selected.stream().anyMatch(Objects::isNull))
        return Retcode.RAID_AVATAR_NOT_MATCH;
      if ("TrialOnly".equals(teamPlan.type())) {
        try {
          var automatic = player.getRaidTrialManager().defaults(teamPlan);
          if (!selected.isEmpty() && !selected.equals(automatic))
            return Retcode.RAID_AVATAR_NOT_MATCH;
          selected = automatic;
        } catch (RuntimeException invalid) {
          return Retcode.RAID_AVATAR_NOT_MATCH;
        }
      }
      boolean trialChoice =
          selected != null
              && selected.stream().anyMatch(choice -> choice != null && choice.trial());
      boolean partyChoice = trialChoice || RaidTeams.frozenParty(teamPlan);
      if (roster.isEmpty()
          || roster.size() > 4
          || new HashSet<>(roster).size() != roster.size()
          || team.getLeader() < 0
          || team.getLeader() >= roster.size()
          || selected == null
          || !partyChoice
              && !selected.isEmpty()
              && !selected.stream().map(RaidTeams.Selection::id).toList().equals(roster)
          || roster.stream()
              .anyMatch(
                  id ->
                      player.getAvatarById(id) == null
                          || player.getAvatarById(id).getAvatarId() != id
                          || player.getAvatarById(id).getAvatarType()
                              != AvatarType.AVATAR_FORMAL_TYPE
                          || player.getAvatars().getAvatars().get(id) != player.getAvatarById(id)))
        return Retcode.RAID_AVATAR_NOT_MATCH;
      if (!partyChoice && !RaidTeams.allowed(player, teamPlan))
        return Retcode.RAID_AVATAR_NOT_MATCH;
      var previous = player.getGameplay().getMissionRaids().get(raid);
      int main = previous == null ? row.MainMissionIDList().getFirst() : previous.getMainMission();
      boolean finishedRetry =
          previous != null
              && MissionRaidPayout.required(row)
              && payout(previous, row)
              && MissionRaidSequence.finished(player, previous, main);
      if (!parent(row) || !finishedRetry && !player.getMissionManager().canAcceptInRaid(main))
        return Retcode.MISSION_NOT_DOING;
      if (!ordinary(new GameplayState.RogueReturn(player))) return Retcode.SCENE_ENTRY_ID_NOT_MATCH;
      var first = initial(row);
      if (first == null) return Retcode.SCENE_ENTRY_ID_NOT_MATCH;
      if (row.FinishEntranceID() > 0) {
        var end = GameData.getMapEntranceExcelMap().get(row.FinishEntranceID());
        var plane = end == null ? null : GameData.getMazePlaneExcelMap().get(end.getPlaneID());
        if (plane == null
            || plane.getPlaneType() != PlaneType.Town && plane.getPlaneType() != PlaneType.Maze)
          return Retcode.SCENE_ENTRY_ID_NOT_MATCH;
      }
      var state = player.getGameplay().getMainMissions().get(main);
      if (previous != null
          && (previous.getRaidId() != raid
              || previous.getWorldLevel() != world
              || previous.getMainMission() != main
              || !sequence(previous, row)
              || !payout(previous, row)
              || !RaidTeams.matches(previous, row)
              || previous.isCompleted()
              || !checkpoint(previous))) return Retcode.PLAYER_DATA_ERROR;
      if (previous == null
          && (state != null
              || row.MainMissionIDList().stream()
                  .anyMatch(
                      id ->
                          player.getGameplay().getMainMissions().containsKey(id)
                              || player.getGameplay().getCancelledMissions().contains(id))))
        return Retcode.PLAYER_DATA_ERROR;
      if (partyChoice && selected.isEmpty()) {
        try {
          var savedParty = previous == null ? null : previous.getTrialParty();
          selected =
              savedParty == null
                  ? roster.stream().map(id -> new RaidTeams.Selection(id, false)).toList()
                  : savedParty.getRoster().stream()
                      .map(
                          id ->
                              new RaidTeams.Selection(id, savedParty.getConfigs().containsKey(id)))
                      .toList();
        } catch (RuntimeException invalid) {
          return Retcode.PLAYER_DATA_ERROR;
        }
      }
      RaidTrialManager.Prepared trial = null;
      if (previous != null && (previous.getTrialParty() != null) != partyChoice)
        return Retcode.PLAYER_DATA_ERROR;
      if (partyChoice) {
        try {
          if (previous == null) {
            trial = player.getRaidTrialManager().prepare(teamPlan, selected);
            RaidEntryRecovery.initialize(row, trial);
          } else {
            if (selected.stream()
                    .anyMatch(
                        choice ->
                            choice.trial()
                                != previous.getTrialParty().getConfigs().containsKey(choice.id()))
                || !selected.stream()
                    .map(RaidTeams.Selection::id)
                    .toList()
                    .equals(previous.getTrialParty().getRoster())) return Retcode.PLAYER_DATA_ERROR;
            trial = player.getRaidTrialManager().rebuild(previous.getTrialParty(), teamPlan);
          }
        } catch (RuntimeException invalid) {
          return Retcode.RAID_AVATAR_NOT_MATCH;
        }
      }
      var run =
          previous == null
              ? new GameplayState.MissionRaidRun(
                  raid, world, main, first.getPlaneID(), first.getFloorID(), first.getId())
              : previous;
      if (previous == null && RaidTeams.snapshotRequired(teamPlan)) run.freezeFormalTeam(teamPlan);
      if (previous == null && trial != null) run.freezeTrialParty(trial.party());
      if (previous == null && MissionRaidSequence.required(row)) run.freezeSequence(row);
      if (previous == null && MissionRaidPayout.required(row)) run.freezePayout(row);
      if (previous == null) run.freezeDifficulty(RaidDifficultySettings.freeze(player, row));
      run.returnFrom(player);
      player.getGameplay().beginMissionRaid(run);
      if (trial != null) player.getRaidTrialManager().install(run, trial);
      boolean moved =
          previous == null
              ? player.enterScene(first.getId(), 0, false)
              : player.loadScene(
                  run.getPlane(),
                  run.getFloor(),
                  run.getEntry(),
                  run.getCheckpoint().getPos(),
                  run.getCheckpoint().getRot(),
                  false);
      if (!moved) {
        player.getGameplay().endMissionRaid();
        player.getMissionTrialManager().detach();
        player.getRaidTrialManager().detach();
        if (previous == null) player.getGameplay().getMissionRaids().remove(raid);
        player.save();
        return Retcode.SCENE_ENTRY_ID_NOT_MATCH;
      }
      run.checkpoint(player);
      if (!finishedRetry) {
        var accepted = player.getMissionManager().accept(main);
        if (accepted != Retcode.SUCC)
          throw new IllegalStateException("Validated raid mission could not be accepted");
      }
      player.getMissionManager().refresh();
      player.save();
      return Retcode.SUCC;
    }
  }

  /** EnterRaidScene is an authored server transition, not a client-reported arrival counter. */
  public boolean transfer(MissionInfo.SubMissionInfo sub) {
    synchronized (player) {
      var run = current();
      if (!active()
          || !activeFor(sub.getMainMissionID())
          || run.getMainMission() != sub.getMainMissionID()
          || run.getPendingTransfer() != 0
          || player.isInBattle()
          || !"Equal".equals(sub.getParamType())
          || sub.getParamInt1() != run.getRaidId()
          || sub.getParamInt2() <= 0
          || sub.getParamInt3() != 0
          || !sub.getParamStr1().isBlank()
          || sub.getParamIntList().length != 0
          || !sub.getParamItemList().isEmpty()
          || sub.getProgress() != 1
          || run.getSequence() != null && !MissionRaidSequence.transferTask(sub)
          || new StoryActions(player).validate(sub) != Retcode.SUCC) return false;
      var target = GameData.getMapEntranceExcelMap().get(sub.getParamInt2());
      if (target == null || !raidFloor(run, target.getPlaneID(), target.getFloorID())) return false;
      var performed = run.getTransferredSubs().get(sub.getId());
      if (performed != null)
        return performed == target.getId() && player.getEntryId() == target.getId();
      if (sub.getLevelPlaneID() != run.getPlane() || sub.getLevelFloorID() != run.getFloor())
        return false;
      run.reserveTransfer(sub.getId(), target.getPlaneID(), target.getFloorID(), target.getId());
      player.save();
      if (!player.enterScene(target.getId(), 0, true)) {
        run.cancelTransfer();
        player.save();
        return false;
      }
      run.completeTransfer(sub.getId(), player);
      player.save();
      return true;
    }
  }

  public boolean completed(GameplayState.MissionRaidRun run) {
    if (!objectivesCompleted(run)) return false;
    var row = resource(run.getRaidId(), run.getWorldLevel());
    return !MissionRaidPayout.required(row) || run.getPayout().settled(player, run, row);
  }

  private boolean objectivesCompleted(GameplayState.MissionRaidRun run) {
    var row = run == null ? null : resource(run.getRaidId(), run.getWorldLevel());
    if (row == null || !RaidTeams.matches(run, row) || !sequence(run, row) || !payout(run, row))
      return false;
    if (run.getSequence() != null) return run.getSequence().completed(player, run, row);
    if (MissionRaidPayout.required(row))
      return MissionRaidSequence.finished(player, run, run.getMainMission());
    int main = run.getMainMission();
    var state = player.getGameplay().getMainMissions().get(main);
    if (!valid(state, main)
        || state.getPhase() == QuestState.Phase.DOING
        || player.getGameplay().getCancelledMissions().contains(main)) return false;
    var info = GameData.getMissionInfos().get(main);
    return info != null
        && Arrays.stream(info.getFinishSubMissionList())
            .allMatch(
                id -> {
                  var sub = player.getGameplay().getSubMissions().get(id);
                  var config = GameplayDefinitions.subs.get(id);
                  return config != null
                      && config.getMainMissionID() == main
                      && valid(sub, id)
                      && sub.getPhase() != QuestState.Phase.DOING
                      && !player.getGameplay().getCancelledSubs().contains(id);
                });
  }

  /**
   * Advance only after actual finish-sub proofs and arrival at the next authored starting floor.
   */
  boolean advance() {
    synchronized (player) {
      var run = current();
      if (run == null
          || run.getSequence() == null
          || run.isCompleted()
          || run.getPendingTransfer() != 0
          || player.isInBattle()
          || !active()
          || !MissionRaidSequence.finished(player, run, run.getMainMission())) return false;
      int next = run.getSequence().next();
      if (next == 0 || !player.getMissionManager().canAcceptInRaid(next)) return false;
      var start = GameData.getMissionInfos().get(next).getStartSubMissionList();
      if (Arrays.stream(start)
          .mapToObj(GameplayDefinitions.subs::get)
          .anyMatch(
              sub ->
                  sub == null
                      || sub.getLevelPlaneID() != player.getPlaneId()
                      || sub.getLevelFloorID() != player.getFloorId())) return false;
      player.getMissionTrialManager().reconcile();
      var row = resource(run.getRaidId(), run.getWorldLevel());
      if (!RaidTeams.allowed(player, row)) return false;
      run.advanceSequence();
      player.save();
      return true;
    }
  }

  public void settle() {
    synchronized (player) {
      var run = current();
      if (active() && objectivesCompleted(run) && !run.isCompleted()) {
        var row = resource(run.getRaidId(), run.getWorldLevel());
        if (MissionRaidPayout.required(row) && !run.getPayout().settle(player, run, row)) return;
        run.complete();
        player.save();
        org.hkrpg.server.protocol.Raid46.sync(player);
      }
    }
  }

  private static boolean samePosition(
      emu.lunarcore.util.Position a, emu.lunarcore.util.Position b) {
    return a != null
        && b != null
        && a.getX() == b.getX()
        && a.getY() == b.getY()
        && a.getZ() == b.getZ();
  }

  private boolean returned(GameplayState.MissionRaidRun run, GameplayDefinitions.Raid row) {
    var receipt = run.getFinishedReturn();
    if (!ordinary(receipt)) return false;
    if (row.FinishEntranceID() > 0) return receipt.getEntry() == row.FinishEntranceID();
    var home = run.getReturnTo();
    return ordinary(home)
        && receipt.getPlane() == home.getPlane()
        && receipt.getFloor() == home.getFloor()
        && receipt.getEntry() == home.getEntry()
        && samePosition(receipt.getPos(), home.getPos())
        && samePosition(receipt.getRot(), home.getRot());
  }

  /**
   * A completion flag alone cannot satisfy a task; a transfer also needs the saved actual return.
   */
  public boolean finishCondition(MissionInfo.SubMissionInfo config) {
    if (config.getProgress() != 1
        || config.getParamInt2() != 0
        || config.getParamInt3() != 0
        || config.getParamStr1() == null
        || !config.getParamStr1().isEmpty()
        || config.getParamItemList() == null
        || !config.getParamItemList().isEmpty()
        || config.getParamIntList() == null) return false;
    boolean transfer = "RaidFinishedAndTransfer".equals(config.getFinishType());
    int raid;
    if (transfer) {
      if (!"Equal".equals(config.getParamType())
          || config.getParamInt1() <= 0
          || config.getParamIntList().length != 0) return false;
      raid = config.getParamInt1();
    } else {
      var ids = config.getParamIntList();
      if (!"RaidFinishCnt".equals(config.getFinishType())
          || !"ListContain".equals(config.getParamType())
          || config.getParamInt1() != 0
          || ids.length != 1
          || ids[0] == null
          || ids[0] <= 0) return false;
      raid = ids[0];
    }
    var run = player.getGameplay().getMissionRaids().get(raid);
    var row = run == null ? null : resource(raid, run.getWorldLevel());
    return run != null
        && run.getRaidId() == raid
        && run.getPendingTransfer() == 0
        && row != null
        && run.isCompleted()
        && completed(run)
        && (!transfer || returned(run, row));
  }

  public Retcode leave(int raid, boolean saved) {
    synchronized (player) {
      var run = current();
      if (run == null) return Retcode.NOT_IN_RAID;
      if (raid != run.getRaidId()) return Retcode.RAID_ID_NOT_MATCH;
      if (saved) return Retcode.RAID_CAN_NOT_SAVE;
      if (player.isInBattle() || !active()) return Retcode.SCENE_ENTRY_ID_NOT_MATCH;
      var row = resource(raid, run.getWorldLevel());
      boolean finished = completed(run);
      var home = run.getReturnTo();
      recordLocation();
      // Pause ownership before loading home so ordinary-world events cannot complete raid tasks.
      player.getGameplay().endMissionRaid();
      player.getMissionTrialManager().detach();
      player.getRaidTrialManager().detach();
      boolean moved =
          finished && row.FinishEntranceID() > 0
              ? player.enterScene(row.FinishEntranceID(), 0, false)
              : player.loadScene(
                  home.getPlane(),
                  home.getFloor(),
                  home.getEntry(),
                  home.getPos(),
                  home.getRot(),
                  false);
      if (!moved) {
        player.getGameplay().beginMissionRaid(run);
        player.getMissionTrialManager().restoreOnLogin();
        if (run.getTrialParty() != null)
          player
              .getRaidTrialManager()
              .install(
                  run,
                  player.getRaidTrialManager().rebuild(run.getTrialParty(), RaidTeams.plan(row)));
        player.save();
        return Retcode.SCENE_ENTRY_ID_NOT_MATCH;
      }
      if (finished) {
        run.complete();
        run.finishReturn(player);
      }
      player.save();
      player.getMissionManager().refresh();
      return Retcode.SUCC;
    }
  }

  public boolean restoreOnLogin() {
    synchronized (player) {
      if (player.getGameplay().getCurrentMissionRaid() == 0) return false;
      var run = current();
      if (run == null) throw new IllegalStateException("Missing saved mission raid context");
      if (player.getGameplay().getRogue().isInside()
          || player.getGameplay().getParkourVenue().inside()
          || player.getGameplay().getTrialRun() != null
          || player.getInstance() != null
          || player.getGameplay().getStoryLines().getCurrentId() != 0
          || player.getGameplay().getStoryLines().isTransitionPending())
        throw new IllegalStateException("Overlapping saved mission raid context");
      if (active()
          && RaidTeams.allowed(player, resource(run.getRaidId(), run.getWorldLevel()))
          && checkpoint(run)
          && valid(
              player.getGameplay().getMainMissions().get(run.getMainMission()),
              run.getMainMission())) return true;
      var home = run.getReturnTo();
      if (!ordinary(home)) throw new IllegalStateException("Invalid saved mission raid home");
      player.getGameplay().endMissionRaid();
      player.getMissionTrialManager().detach();
      player.getRaidTrialManager().detach();
      if (!player.loadScene(
          home.getPlane(), home.getFloor(), home.getEntry(), home.getPos(), home.getRot(), false)) {
        player.getGameplay().beginMissionRaid(run);
        throw new IllegalStateException("Could not return from invalid saved mission raid");
      }
      player.save();
      return false;
    }
  }
}
