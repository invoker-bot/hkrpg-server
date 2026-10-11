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

/** Two reviewed original daily phone graphs; old global completion never closes a new acceptance. */
final class DailyMissionMessageRules {
  private record Definition(int mainId, int firstId, int goalId, int sectionId, int startItemId,
      String firstPath, String goalPath, String firstScript, String goalScript,
      Map<Integer, List<Integer>> edges, Set<Integer> choices, Map<Integer, List<Integer>> streamedOffers) {
    String sourceId() { return "4.6:" + mainId + ":" + firstScript + ":" + goalScript; }
  }

  // Complete MissionTask canonical pins at TurnBasedGameData 724b139d8c9c32d12552eb95745a4fee72bfe48b.
  // The authored goal scripts 300060203 and 300060303 are genuinely the same complete source.
  private static final List<Definition> DEFINITIONS = List.of(
      new Definition(3000602, 300060201, 300060202, 1211401, 121140201,
          "Config/Level/Mission/3000602/Mission_300060201.json",
          "Config/Level/Mission/3000602/Mission_300060203.json",
          "e113776a2deadc2cc09a5890856aad4a661a0529f9daf2d06309ff9c14d43a4d",
          "7531517089dcbd63b3fde9889a3923f988e2232a89b075fa865e606840b3e775",
          Map.ofEntries(
              Map.entry(121140201, List.of(121140212)), Map.entry(121140212, List.of(121140213)),
              Map.entry(121140213, List.of(121140202, 121140203)),
              Map.entry(121140202, List.of(121140204)), Map.entry(121140203, List.of(121140204)),
              Map.entry(121140204, List.of(121140205, 121140207)),
              Map.entry(121140205, List.of(121140206)), Map.entry(121140206, List.of(121140209)),
              Map.entry(121140207, List.of(121140208)), Map.entry(121140208, List.of(121140209)),
              Map.entry(121140209, List.of(121140210)), Map.entry(121140210, List.of())),
          Set.of(121140202, 121140203, 121140205, 121140207),
          Map.of(3, List.of(121140202, 121140203), 5, List.of(121140205, 121140207), 9, List.of())),
      new Definition(3000603, 300060301, 300060302, 1211402, 121140301,
          "Config/Level/Mission/3000603/Mission_300060301.json",
          "Config/Level/Mission/3000603/Mission_300060303.json",
          "5f5e23dad522a9f1f22643206d85f84709432c065fb2328cd26f2d84c5feb4d7",
          "7531517089dcbd63b3fde9889a3923f988e2232a89b075fa865e606840b3e775",
          Map.ofEntries(
              Map.entry(121140301, List.of(121140316)), Map.entry(121140316, List.of(121140317)),
              Map.entry(121140317, List.of(121140302, 121140303)),
              Map.entry(121140302, List.of(121140304)), Map.entry(121140303, List.of(121140304)),
              Map.entry(121140304, List.of(121140305)), Map.entry(121140305, List.of(121140306, 121140308)),
              Map.entry(121140306, List.of(121140307)), Map.entry(121140307, List.of(121140310)),
              Map.entry(121140308, List.of(121140309)), Map.entry(121140309, List.of(121140310)),
              Map.entry(121140310, List.of(121140311, 121140312)),
              Map.entry(121140311, List.of(121140313)), Map.entry(121140312, List.of(121140313)),
              Map.entry(121140313, List.of(121140314)), Map.entry(121140314, List.of(121140315)),
              Map.entry(121140315, List.of())),
          Set.of(121140302, 121140303, 121140306, 121140308, 121140311, 121140312),
          Map.of(3, List.of(121140302, 121140303), 6, List.of(121140306, 121140308),
              9, List.of(121140311, 121140312), 13, List.of())));

  private static Definition definition(int mainId) {
    return DEFINITIONS.stream().filter(row -> row.mainId() == mainId).findFirst().orElse(null);
  }

  private static Definition section(int sectionId) {
    return DEFINITIONS.stream().filter(row -> row.sectionId() == sectionId).findFirst().orElse(null);
  }

  static boolean declared(int sectionId) { return section(sectionId) != null; }

  static boolean repeatable(MissionInfo.SubMissionInfo goal) {
    var definition = goal == null ? null : definition(goal.getMainMissionID());
    return definition != null && goal.getId() == definition.goalId()
        && GameplayDefinitions.subs.get(definition.goalId()) == goal && source(definition);
  }

  private static boolean source(Definition definition) {
    try {
      var row = DailyMissionCatalog.mission(definition.mainId());
      var quota = DailyMissionCatalog.count(1);
      var main = GameData.getMainMissionExcelMap().get(definition.mainId());
      var graph = GameData.getMissionInfos().get(definition.mainId());
      var first = GameplayDefinitions.subs.get(definition.firstId());
      var goal = GameplayDefinitions.subs.get(definition.goalId());
      if (row == null || row.ID() != definition.mainId() || row.DailyMissionType() != 1 || row.GroupID() != 30003
          || row.UnlockMainMission() != 1010201 || row.QuestID() != 2100003
          || quota == null || quota.DailyMissionType() != 1 || quota.DailyCount() != 1
          || main == null || main.getId() != definition.mainId() || !"Daily".equals(main.getType()) || main.isInRaid()
          || main.getRewardID() != 231 || !"And".equals(main.getTakeOperation())
          || !List.of(new MainMissionExcel.Gate("Manual", 0)).equals(main.getTakeParam())
          || !"And".equals(main.getBeginOperation())
          || !List.of(new MainMissionExcel.Gate("Auto", 0)).equals(main.getBeginParam())
          || main.getSubRewardList() == null || !main.getSubRewardList().isEmpty()
          || graph == null || graph.getMainMissionID() != definition.mainId()
          || !Arrays.equals(new int[] {definition.firstId()}, graph.getStartSubMissionList())
          || !Arrays.equals(new int[] {definition.goalId()}, graph.getFinishSubMissionList())
          || graph.getSubMissionList() == null || graph.getSubMissionList().length != 2
          || Arrays.stream(graph.getSubMissionList()).filter(sub -> sub == first).count() != 1
          || Arrays.stream(graph.getSubMissionList()).filter(sub -> sub == goal).count() != 1
          || !common(first, definition.firstId(), definition) || !common(goal, definition.goalId(), definition)
          || !"Auto".equals(first.getTakeType()) || first.getTakeParamIntList().length != 0
          || !"Talk".equals(first.getFinishType()) || first.isBackTrack()
          || first.getParamInt1() != 0 || !("Mission_" + definition.firstId()).equals(first.getParamStr1())
          || !definition.firstPath().equals(first.getMissionJsonPath())
          || !"AnySequence".equals(goal.getTakeType())
          || !Arrays.equals(new Integer[] {definition.firstId()}, goal.getTakeParamIntList())
          || !"MessageSectionFinish".equals(goal.getFinishType()) || !goal.isBackTrack()
          || goal.getParamInt1() != definition.sectionId() || !"".equals(goal.getParamStr1())
          // Each authored goal loads its actual MissionJsonPath, including original 302 -> 303.
          || !definition.goalPath().equals(goal.getMissionJsonPath())
          || !script(first, definition.firstScript()) || !script(goal, definition.goalScript())) return false;
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
      var section = GameplayDefinitions.messageSections.get(definition.sectionId());
      var owners = GameplayDefinitions.messageGroups.values().stream()
          .filter(owner -> owner.MessageContactsID() > 0 && owner.MessageSectionIDList() != null
              && owner.MessageSectionIDList().contains(definition.sectionId())).toList();
      var owner = owners.size() == 1 ? owners.getFirst() : null;
      if (section == null || section.ID() != definition.sectionId() || section.IsPerformMessage()
          || section.MainMissionLink() != 0 || !List.of(definition.startItemId()).equals(section.StartMessageItemIDList())
          || owner == null || owner.ID() != 11125 || owner.MessageContactsID() != 48
          || owner.ActivityModuleID() != 0
          || !List.of(1211400, 1211401, 1211402).equals(owner.MessageSectionIDList())) return false;
      for (var edge : definition.edges().entrySet()) {
        var item = GameplayDefinitions.messageItems.get(edge.getKey());
        if (item == null || item.ID() != edge.getKey() || item.SectionID() != definition.sectionId()
            || !"Text".equals(item.ItemType())
            || !(definition.choices().contains(item.ID()) ? "Player" : "NPC").equals(item.Sender())
            || !edge.getValue().equals(item.NextItemIDList())) return false;
      }
      return GameplayDefinitions.messageItems.values().stream().filter(item -> item.SectionID() == definition.sectionId()).count() == definition.edges().size();
    } catch (RuntimeException malformed) {
      return false;
    }
  }

  private static boolean common(MissionInfo.SubMissionInfo sub, int id, Definition definition) {
    return sub != null && sub.getId() == id && sub.getMainMissionID() == definition.mainId()
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

  private record Acceptance(Definition definition, long day, QuestState main, QuestState first, QuestState goal) {}

  private static Acceptance reference(Player player, Definition definition, long day) {
    var periods = player.getGameplay().getDailyMissionPeriods();
    if (periods == null || periods.getArchives() == null || periods.getExpired() == null) return null;
    var archive = periods.getArchives().get(key(definition, day));
    if (archive != null) return new Acceptance(definition, day, archive.getMain(),
        archive.getSubs().get(definition.firstId()), archive.getSubs().get(definition.goalId()));
    var expired = periods.getExpired().get(key(definition, day));
    if (expired != null) return new Acceptance(definition, day, expired.getMain(),
        expired.getSubs().get(definition.firstId()), expired.getSubs().get(definition.goalId()));
    if (!Objects.equals(DailyMissionRepeats.selectedDay(player, definition.mainId()), day)) return null;
    var g = player.getGameplay();
    return new Acceptance(definition, day, g.getMainMissions().get(definition.mainId()),
        g.getSubMissions().get(definition.firstId()), g.getSubMissions().get(definition.goalId()));
  }

  private static String key(Definition definition, long day) { return definition.mainId() + ":" + day; }

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

  /** A valid history is precisely an automatically streamed prefix of its own original choice path. */
  private static boolean phone(GameplayState.PhoneSection phone, Definition definition) {
    if (phone == null || phone.getId() != definition.sectionId() || phone.getRefreshed() <= 0
        || phone.getHistory() == null || phone.getOffered() == null
        || !definition.streamedOffers().containsKey(phone.getHistory().size())) return false;
    List<Integer> offered = List.of(definition.startItemId());
    var visited = new HashSet<Integer>();
    for (var item : phone.getHistory()) {
      if (item == null || !visited.add(item) || !offered.contains(item) || !definition.edges().containsKey(item)) return false;
      offered = definition.edges().get(item);
    }
    return offered.equals(phone.getOffered())
        && offered.equals(definition.streamedOffers().get(phone.getHistory().size()))
        && (offered.isEmpty() || !phone.isFinished());
  }

  private static boolean bound(DailyMissionMessageState.Run run, Acceptance acceptance, Instant now) {
    if (run == null || acceptance == null) return false;
    var definition = acceptance.definition();
    return run.getDay() == acceptance.day()
        && run.getMainId() == definition.mainId() && run.getGoalId() == definition.goalId()
        && run.getSectionId() == definition.sectionId() && definition.sourceId().equals(run.getSource())
        && uuid(run.getMainAcceptance()) && uuid(run.getGoalAcceptance())
        && state(acceptance.main(), definition.mainId(), run.getDay(), now)
        && state(acceptance.goal(), definition.goalId(), run.getDay(), now)
        && MissionStateRecords.completed(acceptance.first(), definition.firstId(), 1)
        && sameDay(acceptance.first().getFinishTime(), run.getDay(), now)
        && Objects.equals(run.getMainAcceptance(), acceptance.main().getObservationKey())
        && Objects.equals(run.getGoalAcceptance(), acceptance.goal().getObservationKey())
        && phone(run.getPhone(), definition)
        && (acceptance.goal().getPhase() == QuestState.Phase.DOING || run.getPhone().isFinished())
        && (acceptance.main().getPhase() == QuestState.Phase.DOING || run.getPhone().isFinished());
  }

  static boolean historyValid(Player player, Instant now) {
    var state = player.getGameplay().getDailyMissionMessages();
    if (state == null || state.getRuns() == null) return false;
    if (state.getRuns().isEmpty()) return true;
    var selections = player.getGameplay().getDailyMissionSelections();
    if (!DailyCommissionAcceptance.valid(selections)) return false;
    try {
      for (var entry : state.getRuns().entrySet()) {
        var run = entry.getValue();
        var definition = run == null ? null : definition(run.getMainId());
        if (definition == null || !source(definition)
            || run.getDay() == Long.MIN_VALUE || run.getDay() > DailyManager.day(now)
            || !Objects.equals(entry.getKey(), key(definition, run.getDay()))
            || !bound(run, reference(player, definition, run.getDay()), now)) return false;
        var choices = selections.getDays().get(Long.toString(run.getDay()));
        if (choices == null || choices.stream().noneMatch(choice -> choice.getId() == definition.mainId()
            && choice.getType() == 1 && choice.getGroup() == 30003)) return false;
      }
      return true;
    } catch (RuntimeException malformed) { return false; }
  }

  private static Acceptance active(Player player, Definition definition) {
    var now = player.getMissionManager().currentInstant();
    if (definition == null || !source(definition) || !historyValid(player, now)) return null;
    var g = player.getGameplay();
    var day = DailyMissionRepeats.selectedDay(player, definition.mainId());
    if (DailyCommissionAcceptance.check(player, definition.mainId(), now) != Retcode.SUCC
        || !DailyCommissionAcceptance.valid(g.getDailyMissionSelections())
        || day == null || day != DailyManager.day(now)
        || g.getDailyMissionSelections().getLatestDay() != day
        || g.getDailyMissionPeriods() == null || g.getDailyMissionPeriods().getRunDays() == null
        || g.getDailyMissionPeriods().getRunDays().containsKey(definition.mainId())
            && !Objects.equals(g.getDailyMissionPeriods().getRunDays().get(definition.mainId()), day)
        || g.getCancelledMissions().contains(definition.mainId()) || g.getCancelledSubs().contains(definition.firstId())
        || g.getCancelledSubs().contains(definition.goalId())
        || !MissionStateRecords.mainDoing(player, definition.mainId())
        || !MissionStateRecords.doing(g.getSubMissions().get(definition.goalId()), definition.goalId(), 1)
        || !MissionStateRecords.subCompleted(player, definition.firstId())
        || !sameDay(g.getSubMissions().get(definition.firstId()).getFinishTime(), day, now)) return null;
    return new Acceptance(definition, day, g.getMainMissions().get(definition.mainId()),
        g.getSubMissions().get(definition.firstId()), g.getSubMissions().get(definition.goalId()));
  }

  static boolean eligible(Player player, GameplayDefinitions.MessageSection section) {
    if (section == null) return false;
    synchronized (player) { return active(player, section(section.ID())) != null; }
  }

  static boolean completed(Player player, MissionInfo.SubMissionInfo goal) {
    if (!repeatable(goal)) return false;
    synchronized (player) {
      var definition = definition(goal.getMainMissionID());
      var acceptance = active(player, definition);
      if (acceptance == null) return false;
      var run = player.getGameplay().getDailyMissionMessages().getRuns().get(key(definition, acceptance.day()));
      return bound(run, acceptance, player.getMissionManager().currentInstant()) && run.getPhone().isFinished();
    }
  }

  static Map<Integer, GameplayState.PhoneSection> view(Player player) {
    synchronized (player) {
      var g = player.getGameplay();
      var result = new HashMap<>(g.getPhoneSections());
      var state = g.getDailyMissionMessages();
      var now = player.getMissionManager().currentInstant();
      for (var definition : DEFINITIONS) {
        boolean hasRun = state != null && state.getRuns() != null && state.getRuns().values().stream()
            .anyMatch(run -> run != null && run.getMainId() == definition.mainId());
        // Preserve unrelated global sections. A known acceptance suppresses only its own old view.
        if (!g.getMainMissions().containsKey(definition.mainId()) && !hasRun) continue;
        result.remove(definition.sectionId());
        var day = DailyMissionRepeats.selectedDay(player, definition.mainId());
        if (day == null || !historyValid(player, now)) continue;
        var run = state.getRuns().get(key(definition, day));
        if (bound(run, reference(player, definition, day), now)) result.put(definition.sectionId(), run.getPhone());
      }
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
      this.key = key(acceptance.definition(), acceptance.day());
      this.previous = previous;
      this.main = acceptance.main(); this.goal = acceptance.goal();
      this.previousMain = main.getObservationKey(); this.previousGoal = goal.getObservationKey();
      String captured = null;
      try {
        captured = main.captureObservationKey();
        this.goalKey = goal.captureObservationKey();
        this.mainKey = captured;
        var definition = acceptance.definition();
        this.replacement = new DailyMissionMessageState.Run(acceptance.day(), definition.mainId(),
            definition.goalId(), definition.sectionId(), definition.sourceId(), mainKey, goalKey, phone.copy());
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
    var next = daily.getFirst();
    var definition = section(next.getId());
    var acceptance = active(player, definition);
    if (acceptance == null || !phone(next, definition)) throw new IllegalStateException("Daily phone run is not eligible");
    var runs = player.getGameplay().getDailyMissionMessages().getRuns();
    var previous = runs.get(key(definition, acceptance.day()));
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
      } else if (next.getHistory().stream().filter(definition.choices()::contains).count()
          - before.getHistory().stream().filter(definition.choices()::contains).count() != 1)
        throw new IllegalStateException("Daily phone requires exactly one actual reply");
    }
    return new Mutation(runs, acceptance, previous, next);
  }

  private DailyMissionMessageRules() {}
}
