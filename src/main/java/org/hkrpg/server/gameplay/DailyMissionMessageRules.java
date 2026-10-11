package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Finite original 3000602 phone graph; global finished history never completes a new acceptance. */
final class DailyMissionMessageRules {
  static final int MAIN = 3000602, FIRST = 300060201, GOAL = 300060202, SECTION = 1211401;
  // Canonical complete MissionTask blobs at TurnBasedGameData 724b139d8c9c32d12552eb95745a4fee72bfe48b.
  private static final String FIRST_SCRIPT = "e113776a2deadc2cc09a5890856aad4a661a0529f9daf2d06309ff9c14d43a4d";
  private static final String GOAL_SCRIPT = "7531517089dcbd63b3fde9889a3923f988e2232a89b075fa865e606840b3e775";
  private static final String SOURCE = "4.6:3000602:" + FIRST_SCRIPT + ":" + GOAL_SCRIPT;
  private static final Map<Integer, List<Integer>> EDGES = Map.ofEntries(
      Map.entry(121140201, List.of(121140212)), Map.entry(121140212, List.of(121140213)),
      Map.entry(121140213, List.of(121140202, 121140203)),
      Map.entry(121140202, List.of(121140204)), Map.entry(121140203, List.of(121140204)),
      Map.entry(121140204, List.of(121140205, 121140207)),
      Map.entry(121140205, List.of(121140206)), Map.entry(121140206, List.of(121140209)),
      Map.entry(121140207, List.of(121140208)), Map.entry(121140208, List.of(121140209)),
      Map.entry(121140209, List.of(121140210)), Map.entry(121140210, List.of()));
  private static final Set<Integer> CHOICES = Set.of(121140202, 121140203, 121140205, 121140207);

  static boolean declared(int section) { return section == SECTION; }

  static boolean repeatable(MissionInfo.SubMissionInfo goal) {
    return goal != null && goal.getId() == GOAL && goal.getMainMissionID() == MAIN
        && GameplayDefinitions.subs.get(GOAL) == goal && source();
  }

  private static boolean source() {
    try {
      var row = DailyMissionCatalog.mission(MAIN);
      var quota = DailyMissionCatalog.count(1);
      var main = GameData.getMainMissionExcelMap().get(MAIN);
      var graph = GameData.getMissionInfos().get(MAIN);
      var first = GameplayDefinitions.subs.get(FIRST);
      var goal = GameplayDefinitions.subs.get(GOAL);
      if (row == null || row.ID() != MAIN || row.DailyMissionType() != 1 || row.GroupID() != 30003
          || row.UnlockMainMission() != 1010201 || row.QuestID() != 2100003
          || quota == null || quota.DailyMissionType() != 1 || quota.DailyCount() != 1
          || main == null || main.getId() != MAIN || !"Daily".equals(main.getType()) || main.isInRaid()
          || main.getRewardID() != 231 || !"And".equals(main.getTakeOperation())
          || !List.of(new MainMissionExcel.Gate("Manual", 0)).equals(main.getTakeParam())
          || !"And".equals(main.getBeginOperation())
          || !List.of(new MainMissionExcel.Gate("Auto", 0)).equals(main.getBeginParam())
          || main.getSubRewardList() == null || !main.getSubRewardList().isEmpty()
          || graph == null || graph.getMainMissionID() != MAIN
          || !Arrays.equals(new int[] {FIRST}, graph.getStartSubMissionList())
          || !Arrays.equals(new int[] {GOAL}, graph.getFinishSubMissionList())
          || graph.getSubMissionList() == null || graph.getSubMissionList().length != 2
          || Arrays.stream(graph.getSubMissionList()).filter(sub -> sub == first).count() != 1
          || Arrays.stream(graph.getSubMissionList()).filter(sub -> sub == goal).count() != 1
          || !common(first, FIRST) || !common(goal, GOAL)
          || !"Auto".equals(first.getTakeType()) || first.getTakeParamIntList().length != 0
          || !"Talk".equals(first.getFinishType()) || first.isBackTrack()
          || first.getParamInt1() != 0 || !"Mission_300060201".equals(first.getParamStr1())
          || !"Config/Level/Mission/3000602/Mission_300060201.json".equals(first.getMissionJsonPath())
          || !"AnySequence".equals(goal.getTakeType())
          || !Arrays.equals(new Integer[] {FIRST}, goal.getTakeParamIntList())
          || !"MessageSectionFinish".equals(goal.getFinishType()) || !goal.isBackTrack()
          || goal.getParamInt1() != SECTION || !"".equals(goal.getParamStr1())
          // Source goal 202 deliberately points to script 203.
          || !"Config/Level/Mission/3000602/Mission_300060203.json".equals(goal.getMissionJsonPath())
          || !script(first, FIRST_SCRIPT) || !script(goal, GOAL_SCRIPT)) return false;
      var plane = GameData.getMazePlaneExcelMap().get(20002);
      var floor = GameData.getFloorInfo(20002, 20002001);
      if (plane == null || plane.getId() != 20002 || plane.getPlaneType() != PlaneType.Maze
          || plane.getFloorIDList() == null || Collections.frequency(plane.getFloorIDList(), 20002001) != 1
          || floor == null || floor.getFloorID() != 20002001 || !floor.isLoaded()
          || !floor.isGroupInDimension(61, 0)) return false;
      var group = floor.getGroups(0).get(61);
      if (group == null || group.getId() != 61 || group.getCategory() != null
          || group.getOwnerMainMissionID() != 0 || group.getLoadSide() != GroupInfo.GroupLoadSide.Server
          || !group.isLoadOnInitial() || group.getAtmosphereCondition() != null
          || group.getSavedValueCondition() != null || group.getValueSource() != null
          || group.getLevelGraph() != null && !group.getLevelGraph().isBlank()
          || !condition(group.getLoadCondition(), 1000401, LevelGroupMissionCondition.LevelGroupMissionPhase.Finish, false)
          || !condition(group.getUnloadCondition(), 3000604, LevelGroupMissionCondition.LevelGroupMissionPhase.Accept, true)
          || group.getNPCList() == null || group.getNPCList().size() != 1) return false;
      var npc = group.getNPCList().getFirst();
      var npcRow = GameData.getNpcExcelMap().get(10206);
      if (npc == null || npc.getID() != 400001 || npc.getNPCID() != 10206 || npc.isIsDelete()
          || npc.isIsClientOnly() || !npc.isLoadOnInitial()
          || npcRow == null || npcRow.getId() != 10206) return false;
      var section = GameplayDefinitions.messageSections.get(SECTION);
      var owners = GameplayDefinitions.messageGroups.values().stream()
          .filter(owner -> owner.MessageContactsID() > 0 && owner.MessageSectionIDList() != null
              && owner.MessageSectionIDList().contains(SECTION)).toList();
      var owner = owners.size() == 1 ? owners.getFirst() : null;
      if (section == null || section.ID() != SECTION || section.IsPerformMessage()
          || section.MainMissionLink() != 0 || !List.of(121140201).equals(section.StartMessageItemIDList())
          || owner == null || owner.ID() != 11125 || owner.MessageContactsID() != 48
          || owner.ActivityModuleID() != 0
          || !List.of(1211400, SECTION, 1211402).equals(owner.MessageSectionIDList())) return false;
      for (var edge : EDGES.entrySet()) {
        var item = GameplayDefinitions.messageItems.get(edge.getKey());
        if (item == null || item.ID() != edge.getKey() || item.SectionID() != SECTION
            || !"Text".equals(item.ItemType())
            || !(CHOICES.contains(item.ID()) ? "Player" : "NPC").equals(item.Sender())
            || !edge.getValue().equals(item.NextItemIDList())) return false;
      }
      return GameplayDefinitions.messageItems.values().stream().filter(item -> item.SectionID() == SECTION).count() == EDGES.size();
    } catch (RuntimeException malformed) {
      return false;
    }
  }

  private static boolean common(MissionInfo.SubMissionInfo sub, int id) {
    return sub != null && sub.getId() == id && sub.getMainMissionID() == MAIN
        && GameplayDefinitions.subs.get(id) == sub && "Equal".equals(sub.getParamType())
        && sub.getProgress() == 1 && sub.getSubRewardID() == 0
        && sub.getLevelPlaneID() == 20002 && sub.getLevelFloorID() == 20002001
        && sub.getMazePlaneID() == 0 && sub.getMazeFloorID() == 0
        && sub.getStoryLineID() == 0 && sub.getStoryLineIDList() != null && sub.getStoryLineIDList().isEmpty()
        && sub.getIgnoreVerseParamList() != null && sub.getIgnoreVerseParamList().isEmpty()
        && sub.getGroupIDList() != null && sub.getGroupIDList().isEmpty()
        && sub.getRequiredNPCSeriesIDList() != null && sub.getRequiredNPCSeriesIDList().isEmpty()
        && sub.getParamInt2() == 0 && sub.getParamInt3() == 0
        && sub.getParamIntList() != null && sub.getParamIntList().length == 0
        && sub.getParamItemList() != null && sub.getParamItemList().isEmpty()
        && sub.getTakeParamIntList() != null
        && sub.getTakeParamStrList() != null && sub.getTakeParamStrList().length == 0
        && sub.getFinishActionList() != null && sub.getFinishActionList().length == 0
        && sub.getCustomValueList() != null && sub.getCustomValueList().length == 0
        && sub.getCustomValueReward() != null && sub.getCustomValueReward().length == 0
        && sub.getStoryTrialTask() == null;
  }

  private static boolean condition(LoadConditionInfo set, int id,
      LevelGroupMissionCondition.LevelGroupMissionPhase phase, boolean delayed) {
    if (set == null || !"And".equals(set.getOperation()) || set.isDelayToLevelReload() != delayed
        || set.getConditions() == null || set.getConditions().length != 1) return false;
    var item = set.getConditions()[0];
    return item != null && item.getID() == id && item.getPhase() == phase
        && item.getMissionType() == LevelGroupMissionCondition.LevelGroupMissionType.MainMission;
  }

  private static boolean script(MissionInfo.SubMissionInfo sub, String expected) {
    if (sub.getTask() == null) return false;
    var raw = sub.getTask().getSourceDefinition();
    if (raw == null || raw.isBlank() || raw.length() > 8192) return false;
    try {
      var parsed = JsonParser.parseString(raw);
      if (!parsed.isJsonObject()) return false;
      return expected.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical(parsed).getBytes(StandardCharsets.UTF_8))));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String canonical(JsonElement value) {
    if (value.isJsonObject()) {
      var entries = new TreeMap<String, JsonElement>();
      value.getAsJsonObject().entrySet().forEach(entry -> entries.put(entry.getKey(), entry.getValue()));
      return "{" + entries.entrySet().stream()
          .map(entry -> new JsonPrimitive(entry.getKey()) + ":" + canonical(entry.getValue()))
          .collect(java.util.stream.Collectors.joining(",")) + "}";
    }
    if (value.isJsonArray()) return "[" + value.getAsJsonArray().asList().stream()
        .map(DailyMissionMessageRules::canonical).collect(java.util.stream.Collectors.joining(",")) + "]";
    return value.toString();
  }

  private record Acceptance(long day, QuestState main, QuestState first, QuestState goal) {}

  private static Acceptance reference(Player player, long day) {
    var periods = player.getGameplay().getDailyMissionPeriods();
    if (periods == null || periods.getArchives() == null || periods.getExpired() == null) return null;
    var archive = periods.getArchives().get(key(day));
    if (archive != null) return new Acceptance(day, archive.getMain(), archive.getSubs().get(FIRST), archive.getSubs().get(GOAL));
    var expired = periods.getExpired().get(key(day));
    if (expired != null) return new Acceptance(day, expired.getMain(), expired.getSubs().get(FIRST), expired.getSubs().get(GOAL));
    if (!Objects.equals(DailyMissionRepeats.selectedDay(player, MAIN), day)) return null;
    var g = player.getGameplay();
    return new Acceptance(day, g.getMainMissions().get(MAIN), g.getSubMissions().get(FIRST), g.getSubMissions().get(GOAL));
  }

  private static String key(long day) { return MAIN + ":" + day; }

  private static boolean uuid(String key) {
    try { return key != null && UUID.fromString(key).toString().equals(key); }
    catch (IllegalArgumentException invalid) { return false; }
  }

  private static boolean sameDay(long time, long day, Instant now) {
    try { return time > 0 && time <= now.getEpochSecond() && DailyManager.day(Instant.ofEpochSecond(time)) == day; }
    catch (java.time.DateTimeException invalid) { return false; }
  }

  private static boolean state(QuestState value, int id, long day, Instant now) {
    return MissionStateRecords.doing(value, id, 1)
        || MissionStateRecords.completed(value, id, 1) && sameDay(value.getFinishTime(), day, now);
  }

  /** A valid history is precisely an automatically streamed prefix of one original choice path. */
  private static boolean phone(GameplayState.PhoneSection phone) {
    if (phone == null || phone.getId() != SECTION || phone.getRefreshed() <= 0
        || phone.getHistory() == null || phone.getOffered() == null
        || !Set.of(3, 5, 9).contains(phone.getHistory().size())) return false;
    List<Integer> offered = List.of(121140201);
    var visited = new HashSet<Integer>();
    for (var item : phone.getHistory()) {
      if (item == null || !visited.add(item) || !offered.contains(item) || !EDGES.containsKey(item)) return false;
      offered = EDGES.get(item);
    }
    return offered.equals(phone.getOffered())
        && (phone.getHistory().size() == 9 ? offered.isEmpty() : !phone.isFinished())
        && (phone.getHistory().size() != 3 || offered.equals(List.of(121140202, 121140203)))
        && (phone.getHistory().size() != 5 || offered.equals(List.of(121140205, 121140207)));
  }

  private static boolean bound(DailyMissionMessageState.Run run, Acceptance acceptance, Instant now) {
    return run != null && acceptance != null && run.getDay() == acceptance.day()
        && run.getMainId() == MAIN && run.getGoalId() == GOAL && run.getSectionId() == SECTION
        && SOURCE.equals(run.getSource()) && uuid(run.getMainAcceptance()) && uuid(run.getGoalAcceptance())
        && state(acceptance.main(), MAIN, run.getDay(), now)
        && state(acceptance.goal(), GOAL, run.getDay(), now)
        && MissionStateRecords.completed(acceptance.first(), FIRST, 1)
        && sameDay(acceptance.first().getFinishTime(), run.getDay(), now)
        && Objects.equals(run.getMainAcceptance(), acceptance.main().getObservationKey())
        && Objects.equals(run.getGoalAcceptance(), acceptance.goal().getObservationKey())
        && phone(run.getPhone())
        && (acceptance.goal().getPhase() == QuestState.Phase.DOING || run.getPhone().isFinished())
        && (acceptance.main().getPhase() == QuestState.Phase.DOING || run.getPhone().isFinished());
  }

  static boolean historyValid(Player player, Instant now) {
    var state = player.getGameplay().getDailyMissionMessages();
    if (state == null || state.getRuns() == null) return false;
    if (state.getRuns().isEmpty()) return true;
    var selections = player.getGameplay().getDailyMissionSelections();
    if (!source() || !DailyCommissionAcceptance.valid(selections)) return false;
    try {
      for (var entry : state.getRuns().entrySet()) {
        var run = entry.getValue();
        if (run == null || run.getDay() == Long.MIN_VALUE || run.getDay() > DailyManager.day(now)
            || !Objects.equals(entry.getKey(), key(run.getDay()))
            || !bound(run, reference(player, run.getDay()), now)) return false;
        var choices = selections.getDays().get(Long.toString(run.getDay()));
        if (choices == null || choices.stream().noneMatch(choice -> choice.getId() == MAIN
            && choice.getType() == 1 && choice.getGroup() == 30003)) return false;
      }
      return true;
    } catch (RuntimeException malformed) { return false; }
  }

  private static Acceptance active(Player player) {
    var now = player.getMissionManager().currentInstant();
    if (!source() || !historyValid(player, now)) return null;
    var g = player.getGameplay();
    var day = DailyMissionRepeats.selectedDay(player, MAIN);
    if (DailyCommissionAcceptance.check(player, MAIN, now) != Retcode.SUCC
        || !DailyCommissionAcceptance.valid(g.getDailyMissionSelections())
        || day == null || day != DailyManager.day(now)
        || g.getDailyMissionSelections().getLatestDay() != day
        || g.getDailyMissionPeriods() == null || g.getDailyMissionPeriods().getRunDays() == null
        || g.getDailyMissionPeriods().getRunDays().containsKey(MAIN)
            && !Objects.equals(g.getDailyMissionPeriods().getRunDays().get(MAIN), day)
        || g.getCancelledMissions().contains(MAIN) || g.getCancelledSubs().contains(FIRST)
        || g.getCancelledSubs().contains(GOAL)
        || !MissionStateRecords.mainDoing(player, MAIN)
        || !MissionStateRecords.doing(g.getSubMissions().get(GOAL), GOAL, 1)
        || !MissionStateRecords.subCompleted(player, FIRST)
        || !sameDay(g.getSubMissions().get(FIRST).getFinishTime(), day, now)) return null;
    return new Acceptance(day, g.getMainMissions().get(MAIN), g.getSubMissions().get(FIRST), g.getSubMissions().get(GOAL));
  }

  static boolean eligible(Player player, GameplayDefinitions.MessageSection section) {
    if (section == null || section.ID() != SECTION) return false;
    synchronized (player) { return active(player) != null; }
  }

  static boolean completed(Player player, MissionInfo.SubMissionInfo goal) {
    if (!repeatable(goal)) return false;
    synchronized (player) {
      var acceptance = active(player);
      if (acceptance == null) return false;
      var run = player.getGameplay().getDailyMissionMessages().getRuns().get(key(acceptance.day()));
      return bound(run, acceptance, player.getMissionManager().currentInstant()) && run.getPhone().isFinished();
    }
  }

  static Map<Integer, GameplayState.PhoneSection> view(Player player) {
    synchronized (player) {
      var g = player.getGameplay();
      var result = new HashMap<>(g.getPhoneSections());
      var state = g.getDailyMissionMessages();
      // Old global data stays intact. Once a real daily acceptance exists it cannot supply its phone.
      if (!g.getMainMissions().containsKey(MAIN) && (state == null || state.getRuns() == null || state.getRuns().isEmpty()))
        return result;
      result.remove(SECTION);
      var day = DailyMissionRepeats.selectedDay(player, MAIN);
      var now = player.getMissionManager().currentInstant();
      if (day == null || !historyValid(player, now)) return result;
      var run = state.getRuns().get(key(day));
      if (bound(run, reference(player, day), now)) result.put(SECTION, run.getPhone());
      return result;
    }
  }

  static final class Mutation {
    private final Map<String, DailyMissionMessageState.Run> runs;
    private final String key;
    private final DailyMissionMessageState.Run previous, replacement;
    private final QuestState main, goal;
    private final String previousMain, previousGoal, mainKey, goalKey;

    private Mutation() {
      runs = null; key = null; previous = null; replacement = null; main = null; goal = null;
      previousMain = null; previousGoal = null; mainKey = null; goalKey = null;
    }

    private Mutation(Map<String, DailyMissionMessageState.Run> runs, Acceptance acceptance,
        DailyMissionMessageState.Run previous, GameplayState.PhoneSection phone) {
      this.runs = runs;
      this.key = key(acceptance.day());
      this.previous = previous;
      this.main = acceptance.main(); this.goal = acceptance.goal();
      this.previousMain = main.getObservationKey(); this.previousGoal = goal.getObservationKey();
      String captured = null;
      try {
        captured = main.captureObservationKey();
        this.goalKey = goal.captureObservationKey();
        this.mainKey = captured;
        this.replacement = new DailyMissionMessageState.Run(acceptance.day(), SOURCE, mainKey, goalKey, phone.copy());
      } catch (RuntimeException | Error failure) {
        if (captured != null) main.restoreObservationKey(captured, previousMain);
        if (!Objects.equals(goal.getObservationKey(), previousGoal))
          goal.restoreObservationKey(goal.getObservationKey(), previousGoal);
        throw failure;
      }
    }

    void apply() {
      if (runs == null) return;
      if (runs.get(key) != previous || !Objects.equals(main.getObservationKey(), mainKey)
          || !Objects.equals(goal.getObservationKey(), goalKey))
        throw new IllegalStateException("Daily phone acceptance changed before save");
      runs.put(key, replacement);
    }

    void rollback() {
      if (runs == null) return;
      if (previous == null) runs.remove(key); else runs.put(key, previous);
      main.restoreObservationKey(mainKey, previousMain);
      goal.restoreObservationKey(goalKey, previousGoal);
    }
  }

  /** All prepared sections share MessageManager's player-document save and rollback boundary. */
  static Mutation prepare(Player player, Collection<GameplayState.PhoneSection> prepared) {
    var daily = prepared.stream().filter(phone -> declared(phone.getId())).toList();
    if (daily.isEmpty()) return new Mutation();
    if (daily.size() != 1) throw new IllegalStateException("Duplicate daily phone section");
    var acceptance = active(player);
    var next = daily.getFirst();
    if (acceptance == null || !phone(next)) throw new IllegalStateException("Daily phone run is not eligible");
    var runs = player.getGameplay().getDailyMissionMessages().getRuns();
    var previous = runs.get(key(acceptance.day()));
    if (previous == null) {
      if (next.isFinished() || next.getHistory().size() != 3)
        throw new IllegalStateException("Daily phone must start at its original opening");
    } else {
      var before = previous.getPhone();
      if (!bound(previous, acceptance, player.getMissionManager().currentInstant()) || before.isFinished()
          || next.getHistory().size() < before.getHistory().size()
          || !next.getHistory().subList(0, before.getHistory().size()).equals(before.getHistory()))
        throw new IllegalStateException("Daily phone history cannot be replaced");
      if (next.isFinished()) {
        if (!before.getHistory().equals(next.getHistory()) || !before.getOffered().isEmpty())
          throw new IllegalStateException("Daily phone has not reached its original ending");
      } else if (next.getHistory().stream().filter(CHOICES::contains).count()
          - before.getHistory().stream().filter(CHOICES::contains).count() != 1)
        throw new IllegalStateException("Daily phone requires exactly one actual reply");
    }
    return new Mutation(runs, acceptance, previous, next);
  }

  private DailyMissionMessageRules() {}
}
