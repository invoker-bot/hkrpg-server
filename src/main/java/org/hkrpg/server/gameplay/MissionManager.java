package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import java.util.function.Predicate;

/** Supported conditions advance from server events; unsupported script actions stay pending. */
public final class MissionManager {
  private final Player player;
  private java.time.Clock clock = java.time.Clock.systemUTC();

  public void setClock(java.time.Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  java.time.Instant currentInstant() {
    return clock.instant();
  }

  // Runtime call stack only. Saved pending cursors never become resumable through this set.
  private final Set<Integer> activeActions = new HashSet<>();

  public MissionManager(Player player) {
    this.player = player;
  }

  public boolean finished(int id) {
    return MissionStateRecords.mainCompleted(player, id);
  }

  public QuestState sub(int id) {
    return player.getGameplay().getSubMissions().get(id);
  }

  private boolean subFinished(int id) {
    return MissionStateRecords.subCompleted(player, id);
  }

  private boolean gates(List<MainMissionExcel.Gate> list, String op, boolean manual) {
    if (list == null || list.isEmpty() || !("And".equals(op) || "Or".equals(op))) return false;
    Predicate<MainMissionExcel.Gate> test =
        gate ->
            switch (gate.Type()) {
              case "Auto" -> true;
              case "Manual" -> manual;
              case "PlayerLevel" -> player.getLevel() >= gate.Value();
              case "WorldLevel" -> player.getWorldLevel() >= gate.Value();
              case "MultiSequence" -> finished(gate.Value());
              default -> false;
            };
    return "And".equals(op) ? list.stream().allMatch(test) : list.stream().anyMatch(test);
  }

  private boolean eligible(MainMissionExcel config, boolean manual) {
    return config != null
        && DailyCommissionAcceptance.check(player, config.getId(), clock.instant()) == Retcode.SUCC
        && !player.getGameplay().getCancelledMissions().contains(config.getId())
        && GameData.getMissionInfos().containsKey(config.getId())
        && (!config.isInRaid() || new MissionRaid(player).owns(config.getId()))
        && gates(config.getTakeParam(), config.getTakeOperation(), manual)
        && gates(config.getBeginParam(), config.getBeginOperation(), manual);
  }

  boolean canAcceptInRaid(int id) {
    var config = GameData.getMainMissionExcelMap().get(id);
    var state = player.getGameplay().getMainMissions().get(id);
    return config != null
        && config.isInRaid()
        && !player.getGameplay().getCancelledMissions().contains(id)
        && GameData.getMissionInfos().containsKey(id)
        && gates(config.getTakeParam(), config.getTakeOperation(), true)
        && gates(config.getBeginParam(), config.getBeginOperation(), true)
        && (state == null
            || state.getQuestId() == id
                && state.getPhase() == QuestState.Phase.DOING
                && state.getProgress() >= 0
                && state.getFinishTime() == 0);
  }

  public Retcode accept(int id) {
    synchronized (player) {
      var config = GameData.getMainMissionExcelMap().get(id);
      if (config == null) return Retcode.MISSION_NOT_EXIST;
      if (config.isInRaid()
          && (!new MissionRaid(player).owns(id) || !new MissionRaid(player).active()))
        return Retcode.MISSION_NOT_DOING;
      var commission = DailyCommissionAcceptance.check(player, id, clock.instant());
      if (commission != Retcode.SUCC) return commission;
      if (player.getGameplay().getMainMissions().containsKey(id)) {
        if (finished(id)) {
          if (!eligible(config, true) || !DailyMissionRepeats.reopen(player, id, clock.instant()))
            return Retcode.MISSION_ALREADY_DONE;
          player.save();
          refresh();
          sync();
          return Retcode.SUCC;
        }
        if (!MissionStateRecords.mainDoing(player, id)
            || !DailyMissionExpiry.current(player, id, clock.instant()))
          return Retcode.MISSION_NOT_DOING;
        if (DailyCommissionAcceptance.record(player, id, clock.instant())) player.save();
        return Retcode.SUCC;
      }
      if (!eligible(config, true)) return Retcode.MISSION_NOT_DOING;
      DailyCommissionAcceptance.record(player, id, clock.instant());
      player.getGameplay().getMainMissions().put(id, new QuestState(id));
      player.save();
      refresh();
      sync();
      return Retcode.SUCC;
    }
  }

  public void refresh() {
    synchronized (player) {
      player.getMissionTrialManager().reconcile();
      var oldSfx = new MusicComposition(player).unlockedSfxSongs();
      var assignmentTime = clock.instant();
      boolean changed =
          DailyMissionAssignment.assign(
              player,
              assignmentTime,
              id -> eligible(GameData.getMainMissionExcelMap().get(id), true));
      boolean pass;
      try (var recoverySource = DailyMissionRecoverDeliveryRules.sourceScope()) {
      do {
        pass = false;
        pass |= new MissionRaid(player).advance();
        pass |= player.getQuestManager().onMissionProgress();
        for (var config : GameData.getMainMissionExcelMap().values()) {
          if (!player.getGameplay().getMainMissions().containsKey(config.getId())
              && eligible(config, false)) {
            DailyCommissionAcceptance.record(player, config.getId(), clock.instant());
            player
                .getGameplay()
                .getMainMissions()
                .put(config.getId(), new QuestState(config.getId()));
            pass = true;
          }
        }
        for (var config : GameplayDefinitions.subs.values()) {
          if (!MissionStateRecords.mainDoing(player, config.getMainMissionID())
              || !DailyMissionExpiry.current(player, config.getMainMissionID(), clock.instant())
              || !new MissionRaid(player).activeFor(config.getMainMissionID())
              || finished(config.getMainMissionID())
              || player.getGameplay().getCancelledMissions().contains(config.getMainMissionID())
              || player.getGameplay().getCancelledSubs().contains(config.getId())) continue;
          var state = sub(config.getId());
          if (state == null && subEligible(config)) {
            state = new QuestState(config.getId());
            player.getGameplay().getSubMissions().put(config.getId(), state);
            pass = true;
          }
          if (config.getProgress() < 0
              || !MissionStateRecords.doing(state, config.getId(), goal(config))) continue;
          if ("WaitDays".equals(config.getFinishType())
              && !player.getGameplay().getMissionAcceptedDays().containsKey(config.getId())) {
            // Old documents cannot recover a trustworthy acceptance date: begin
            // observing today.
            player
                .getGameplay()
                .getMissionAcceptedDays()
                .put(config.getId(), DailyManager.day(clock.instant()));
            pass = true;
          }
          if ("Auto".equals(config.getFinishType())) pass |= advance(config, state, goal(config));
          if ("SubMissionFinishCnt".equals(config.getFinishType())) {
            int count = MissionAggregateRecords.count(player, config);
            if (count >= 0) pass |= advance(config, state, count - state.getProgress());
          }
          if ("FinishMission".equals(config.getFinishType())) {
            int count = FinishMainMissionCounts.count(player, config);
            if (count >= 0) pass |= advance(config, state, count - state.getProgress());
          }
          if ("FinishQuest".equals(config.getFinishType())) {
            int count = FinishQuestMissionCounts.count(player, config);
            if (count >= 0) pass |= advance(config, state, count - state.getProgress());
          }
          if ("RoguePassAreaProgress".equals(config.getFinishType())) {
            int count = rogueVictoryCount(config);
            if (count >= 0) pass |= advance(config, state, count - state.getProgress());
          }
          if (MessageManager.matchesMultiple(config)) {
            int count = new MessageManager(player).completedCount(config);
            pass |= advance(config, state, count - state.getProgress());
          }
          player.getMissionTrialManager().execute(config);
          player.getMissionTrialManager().remove(config);
          if (currentCondition(config)) pass |= advance(config, state, goal(config));
        }
        pass |= player.getMissionTrialManager().initializeOpening();
        pass |= new MessageManager(player).activateEligible();
        for (var entry : player.getGameplay().getMainMissions().entrySet()) {
          var state = entry.getValue();
          if (!MissionStateRecords.mainDoing(player, entry.getKey())
              || !DailyMissionExpiry.current(player, entry.getKey(), clock.instant())
              || !new MissionRaid(player).activeFor(state.getQuestId())
              || player.getGameplay().getCancelledMissions().contains(state.getQuestId())) continue;
          var config = GameData.getMissionInfos().get(state.getQuestId());
          if (config != null
              && config.getFinishSubMissionList().length > 0
              && Arrays.stream(config.getFinishSubMissionList()).allMatch(this::subFinished)) {
            boolean completed = state.advance(1, 1, now());
            if (completed) player.getDailyManager().onMainMissionCompleted(state.getQuestId());
            var rewardConfig = GameData.getMainMissionExcelMap().get(state.getQuestId());
            if (completed && rewardConfig != null)
              new MissionRewards(player).freezeMain(rewardConfig);
            pass |= completed;
          }
        }
        changed |= pass;
      } while (pass);
      }
      player.getMissionTrialManager().reconcile();
      if (changed) player.save();
      new MissionRewards(player).settleSubRewards();
      for (var entry : List.copyOf(player.getGameplay().getMainMissions().entrySet())) {
        var state = entry.getValue();
        if (!MissionStateRecords.mainCompleted(player, entry.getKey())
            || state.getPhase() != QuestState.Phase.FINISHED
            || player.getGameplay().getCancelledMissions().contains(state.getQuestId())) continue;
        var config = GameData.getMainMissionExcelMap().get(state.getQuestId());
        if (config == null) continue;
        if (!DailyMissionRepeats.payoutAllowed(player, config.getId(), clock.instant())) continue;
        String rewardKey = DailyMissionRepeats.mainKey(player, config.getId());
        if (player.getGameplay().getMissionRewardPlans().containsKey(rewardKey)) {
          new MissionRewards(player).settle(rewardKey, state);
          continue;
        }
        var result = player.getRewardLedger().claim(rewardKey, config.getRewardID());
        if (result.retcode() == Retcode.SUCC || player.getRewardLedger().granted(rewardKey)) {
          state.reserveReward();
          state.closeReward();
          player.save();
        }
      }
      if (changed) sync();
      else if (player.getScene() != null) player.getScene().refreshMissionGroups();
      Mascots.reconcile(player);
      var newSfx = new MusicComposition(player).unlockedSfxSongs();
      newSfx.removeAll(oldSfx);
      org.hkrpg.server.protocol.Music46.syncSfx(player, newSfx);
      org.hkrpg.server.protocol.HeartDial46.syncUnlock(player);
      HeartDialSteps.settleFullSteps(player);
      new MissionRaid(player).settle();
      if (HeartDials.validState(player)) {
        var scripts = new HashSet<>(player.getGameplay().getHeartDialPayments().keySet());
        scripts.addAll(player.getGameplay().getHeartDialLocks().keySet());
        for (int script : scripts) org.hkrpg.server.protocol.HeartDial46.syncScript(player, script);
      }
    }
  }

  private boolean subEligible(MissionInfo.SubMissionInfo config) {
    if (BoxingClubMissionGoals.declaredStart(config)) return true;
    if ("Auto".equals(config.getTakeType())) return true;
    if ("CustomValue".equals(config.getTakeType())) return customGate(config);
    if (config.getTakeParamIntList().length == 0) return false;
    return switch (Objects.toString(config.getTakeType(), "")) {
      case "AnySequence" -> Arrays.stream(config.getTakeParamIntList()).anyMatch(this::subFinished);
      case "MultiSequence" ->
          Arrays.stream(config.getTakeParamIntList()).allMatch(this::subFinished);
      case "CustomValue" -> customGate(config);
      default -> false;
    };
  }

  public Retcode finishTalk(int id, String talk) {
    return finishTalk(id, talk, Map.of());
  }

  public boolean hasSeenFirstTalk(int performanceId) {
    if (DailyRecoverIntroductions.belongs(performanceId)) return DailyRecoverIntroductions.seen(player, performanceId);
    if (NpcFirstTalks.belongs(performanceId)) return NpcFirstTalks.seen(player, performanceId);
    if (WorldFirstTalks.belongs(performanceId)) return WorldFirstTalks.seen(player, performanceId);
    var report = player.getGameplay().getFirstTalkReports().get(performanceId);
    return report != null && report.isCompleted();
  }

  private boolean matchesFirstTalk(MissionInfo.SubMissionInfo c, int performance) {
    return "FinishFirstTalkPerformance".equals(c.getFinishType())
        && "ListContain".equals(c.getParamType())
        && goal(c) == 1
        && c.getParamInt1() == 0
        && c.getParamInt2() == 0
        && c.getParamInt3() == 0
        && c.getParamStr1().isEmpty()
        && c.getParamItemList().isEmpty()
        && c.getLevelFloorID() > 0
        && c.getLevelPlaneID() > 0
        && Arrays.stream(c.getParamIntList()).allMatch(id -> id != null && id > 0)
        && new HashSet<>(Arrays.asList(c.getParamIntList())).size() == c.getParamIntList().length
        && Arrays.asList(c.getParamIntList()).contains(performance);
  }

  public Retcode finishFirstTalkPerformance(int performance) {
    synchronized (player) {
      if (DailyRecoverIntroductions.belongs(performance)) return DailyRecoverIntroductions.finish(player, performance);
      if (NpcFirstTalks.belongs(performance)) return NpcFirstTalks.finish(player, performance);
      if (WorldFirstTalks.belongs(performance)) return WorldFirstTalks.finish(player, performance);
      var definition = FirstTalkDefinitions.performances.get(performance);
      if (definition == null) return Retcode.MISSION_NOT_EXIST;
      if (hasSeenFirstTalk(performance)) return Retcode.SUCC;
      if (!definition.reportable()) return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
      if (player.getBattle() != null) return Retcode.IN_BATTLE_NOW;
      var report = player.getGameplay().getFirstTalkReports().get(performance);
      List<Integer> ids;
      if (report == null) {
        ids =
            player.getGameplay().getSubMissions().values().stream()
                .filter(state -> state.getPhase() == QuestState.Phase.DOING)
                .map(state -> GameplayDefinitions.subs.get(state.getQuestId()))
                .filter(Objects::nonNull)
                .filter(c -> matchesFirstTalk(c, performance) && canFinishFromAction(c.getId()))
                .map(MissionInfo.SubMissionInfo::getId)
                .sorted()
                .toList();
        if (ids.isEmpty()) return Retcode.MISSION_NOT_DOING;
      } else {
        ids = List.copyOf(report.getSubMissionIds());
        if (report.getPlane() != player.getPlaneId() || report.getFloor() != player.getFloorId())
          return Retcode.MISSION_SCENE_NOT_MATCH;
      }
      if (ids.isEmpty() || ids.size() > 100 || new HashSet<>(ids).size() != ids.size())
        return Retcode.PLAYER_DATA_ERROR;
      for (int id : ids) {
        var c = GameplayDefinitions.subs.get(id);
        var state = sub(id);
        if (c == null
            || state == null
            || !matchesFirstTalk(c, performance)
            || player.getGameplay().getCancelledSubs().contains(id)
            || player.getGameplay().getCancelledMissions().contains(c.getMainMissionID()))
          return Retcode.MISSION_NOT_DOING;
        if (c.getLevelPlaneID() != player.getPlaneId()
            || c.getLevelFloorID() != player.getFloorId()) return Retcode.MISSION_SCENE_NOT_MATCH;
        if (state.getPhase() != QuestState.Phase.DOING) continue;
        if (!canFinishFromAction(id)) return Retcode.MISSION_NOT_DOING;
        var validation = new StoryActions(player).validate(c);
        if (validation != Retcode.SUCC) return validation;
      }
      if (report == null) {
        report = new GameplayState.FirstTalkReport(player.getPlaneId(), player.getFloorId(), ids);
        player.getGameplay().getFirstTalkReports().put(performance, report);
        player.save();
      }
      for (int id : ids) {
        var state = sub(id);
        if (state.getPhase() != QuestState.Phase.DOING) continue;
        var c = GameplayDefinitions.subs.get(id);
        if (!canFinishFromAction(id)) return Retcode.MISSION_NOT_DOING;
        var result = new StoryActions(player).execute(c);
        if (result != Retcode.SUCC) return result;
        state.advance(1, 1, now());
        new MissionRewards(player).freezeSub(c);
        player.save();
      }
      report.complete();
      player.save();
      refresh();
      sync();
      return Retcode.SUCC;
    }
  }

  public Retcode finishTalk(int id, String talk, Map<Integer, Integer> values) {
    synchronized (player) {
      if (id == 0) {
        var matches =
            GameplayDefinitions.subs.values().stream()
                .filter(
                    c ->
                        "Talk".equals(c.getFinishType())
                            && c.getParamStr1().equals(talk)
                            && sub(c.getId()) != null
                            && sub(c.getId()).getPhase() == QuestState.Phase.DOING
                            && canFinishFromAction(c.getId())
                            && !player.getGameplay().getCancelledSubs().contains(c.getId())
                            && (c.getLevelFloorID() == 0
                                || c.getLevelFloorID() == player.getFloorId()))
                .toList();
        if (matches.isEmpty()) return Retcode.MISSION_NOT_EXIST;
        if (matches.size() != 1) return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
        id = matches.getFirst().getId();
      }
      var config = GameplayDefinitions.subs.get(id);
      var state = sub(id);
      if (config == null) return Retcode.MISSION_NOT_EXIST;
      if (state == null
          || state.getPhase() != QuestState.Phase.DOING
          || !canFinishFromAction(id)
          || player.getGameplay().getCancelledSubs().contains(id)
          || player.getGameplay().getCancelledMissions().contains(config.getMainMissionID()))
        return Retcode.MISSION_NOT_DOING;
      if (!"Talk".equals(config.getFinishType()) || !config.getParamStr1().equals(talk))
        return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
      if (config.getLevelFloorID() != 0 && config.getLevelFloorID() != player.getFloorId())
        return Retcode.MISSION_SCENE_NOT_MATCH;
      if (!MissionTalkScriptGates.allows(player, id)) return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
      if (DailyMissionSelectedItemRules.talk(id) && DailyMissionSelectedItemRules.handles(config.getMainMissionID())) {
        var result = DailyMissionSelectedItemRules.finishTalk(player, config, state, values, clock.instant());
        if (result == Retcode.SUCC) { refresh(); sync(); }
        return result;
      }
      if (DailyMissionRecoverDeliveryRules.sub(id)) {
        var result = DailyMissionRecoverDeliveryRules.finishTalk(player, config, state, values, clock.instant());
        if (result == Retcode.SUCC) { refresh(); sync(); }
        return result;
      }
      if (DailyOriginEffects.handles(id)) {
        var result = DailyOriginEffects.finishInitial(player, config, state, values, now());
        if (result == Retcode.SUCC) {
          refresh();
          sync();
        }
        return result;
      }
      var customUpdates = new HashMap<String, Integer>();
      var main = GameData.getMissionInfos().get(config.getMainMissionID());
      for (var entry : values.entrySet()) {
        if (main == null
            || main.getMissionCustomValueList() == null
            || entry.getKey() < 0
            || entry.getValue() < 0) return Retcode.MISSION_CUSTOM_VALUE_NOT_VALID;
        var definitions =
            Arrays.stream(main.getMissionCustomValueList())
                .filter(c -> c.getIndex().equals(entry.getKey()))
                .toList();
        if (definitions.size() != 1) return Retcode.MISSION_CUSTOM_VALUE_NOT_VALID;
        var c = definitions.getFirst();
        var allowed = c.getValidValueParamList();
        if (!"Former".equals(c.getType())
            || !Arrays.asList(config.getCustomValueList()).contains(c.getName())
            || allowed == null
            || (c.isRange()
                ? allowed.length != 2
                    || entry.getValue() < allowed[0]
                    || entry.getValue() > allowed[1]
                : !Arrays.asList(allowed).contains(entry.getValue())))
          return Retcode.MISSION_CUSTOM_VALUE_NOT_VALID;
        customUpdates.put(
            "mission:" + config.getMainMissionID() + ":" + c.getName(), entry.getValue());
      }
      var actionResult = new StoryActions(player).execute(config);
      if (actionResult != Retcode.SUCC) return actionResult;
      player.getGameplay().getStoryValues().putAll(customUpdates);
      state.advance(goal(config), goal(config), now());
      new MissionRewards(player).freezeSub(config);
      player.save();
      refresh();
      sync();
      return Retcode.SUCC;
    }
  }

  public void onStageWin(int id) {
    event("StageWin", c -> c.getParamInt1() == id, 1);
  }

  /** One successful debit operation per item, matching the pinned reference's UseItem event. */
  public void onItemsConsumed(Map<Integer, Integer> items) {
    if (items == null || items.isEmpty()) return;
    event(
        "UseItem",
        c ->
            Set.of("Equal", "GreaterEqual").contains(c.getParamType())
                && simpleItemCondition(c)
                && items.getOrDefault(c.getParamInt1(), 0) > 0
                && !executingActions(c.getId()),
        1);
  }

  public void onShopPurchase(int goodsId) {
    if (goodsId <= 0) return;
    event(
        "BuyShopGoods",
        c ->
            "ListContain".equals(c.getParamType())
                && c.getParamInt1() == 0
                && c.getParamInt2() == 0
                && c.getParamInt3() == 0
                && c.getParamStr1().isBlank()
                && c.getParamItemList().isEmpty()
                && c.getParamIntList().length > 0
                && Arrays.stream(c.getParamIntList()).allMatch(v -> v > 0)
                && Arrays.stream(c.getParamIntList()).anyMatch(v -> v == goodsId)
                && matchesPlace(c),
        1);
  }

  public void onTeamLeaderChange(int avatarId) {
    if (avatarId <= 0 || player.getAvatarById(avatarId) == null) return;
    var lineup = player.getCurrentLineup();
    if (lineup == null
        || !lineup.isActiveSlot(lineup.getLeader())
        || lineup.getAvatars().get(lineup.getLeader()) != avatarId) return;
    event(
        "TeamLeaderChange",
        c ->
            "Equal".equals(c.getParamType())
                && simpleItemCondition(c)
                && c.getParamInt1() == avatarId,
        1);
  }

  private boolean simpleItemCondition(MissionInfo.SubMissionInfo c) {
    return c.getParamInt1() > 0
        && c.getParamInt2() == 0
        && c.getParamInt3() == 0
        && c.getParamStr1().isBlank()
        && c.getParamIntList().length == 0
        && c.getParamItemList().isEmpty()
        && matchesPlace(c);
  }

  private boolean matchesPlace(MissionInfo.SubMissionInfo c) {
    return (c.getLevelPlaneID() == 0 || c.getLevelPlaneID() == player.getPlaneId())
        && (c.getLevelFloorID() == 0 || c.getLevelFloorID() == player.getFloorId());
  }

  public void onItemsAcquired(Map<Integer, Integer> items) {
    synchronized (player) {
      boolean changed = false;
      for (var state : List.copyOf(player.getGameplay().getSubMissions().values())) {
        var c = GameplayDefinitions.subs.get(state.getQuestId());
        if (c == null
            || state.getPhase() != QuestState.Phase.DOING
            || !canFinishFromAction(c.getId())) continue;
        if ("GetItem".equals(c.getFinishType())
            && "Equal".equals(c.getParamType())
            && c.getParamStr1().isEmpty()
            && items.getOrDefault(c.getParamInt1(), 0) > 0) changed |= advance(c, state, 1);
        if ("GetItemWithList".equals(c.getFinishType()) && "ListContain".equals(c.getParamType())) {
          var ids = Arrays.asList(c.getParamIntList());
          if (ids.isEmpty()
              || ids.contains(null)
              || ids.stream().anyMatch(id -> id <= 0)
              || new HashSet<>(ids).size() != ids.size()
              || goal(c) > ids.size()) continue;
          var acquired =
              player
                  .getGameplay()
                  .getMissionAcquiredItems()
                  .computeIfAbsent(c.getId(), k -> new HashSet<>());
          for (int id : ids) if (items.getOrDefault(id, 0) > 0) changed |= acquired.add(id);
          changed |= advance(c, state, acquired.size() - state.getProgress());
        }
      }
      if (changed) {
        player.save();
        refresh();
        sync();
      }
    }
  }

  public Retcode submitItems(int id, Map<Integer, Integer> reported) {
    synchronized (player) {
      var c = GameplayDefinitions.subs.get(id);
      var state = sub(id);
      if (c == null) return Retcode.MISSION_NOT_EXIST;
      if (state == null || state.getPhase() != QuestState.Phase.DOING || !canFinishFromAction(id))
        return Retcode.MISSION_NOT_DOING;
      boolean selected = "UseSelectedItem".equals(c.getFinishType());
      if (!(selected
              ? "ListContainList".equals(c.getParamType())
                  && c.getParamInt1() == 1
                  && c.getProgress() == 1
              : "ConsumeMissionItem".equals(c.getFinishType())
                  && "Equal".equals(c.getParamType())
                  && c.getParamInt1() == 0)
          || c.getParamItemList() == null
          || c.getParamItemList().isEmpty()
          || c.getParamInt2() != 0
          || c.getParamInt3() != 0
          || c.getParamIntList().length != 0
          || !c.getParamStr1().isEmpty()) return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
      if (!matchesPlace(c)) return Retcode.MISSION_SCENE_NOT_MATCH;
      var originalRecovery = DailyMissionRecoverDeliveryRules.submission(player, c, clock.instant());
      if (originalRecovery != Retcode.SUCC) return originalRecovery;
      var originalSelection = DailyMissionSelectedItemRules.submission(player, c, clock.instant());
      if (originalSelection != Retcode.SUCC) return originalSelection;
      var costs = new TreeMap<Integer, Integer>();
      try {
        for (var cost : c.getParamItemList()) {
          var excel = GameData.getItemExcelMap().get(cost.ItemID());
          if (cost.ItemNum() <= 0
              || excel == null
              || !(emu.lunarcore.game.inventory.Inventory.isStackType(excel)
                  || selected
                      && cost.ItemID() == 2
                      && excel.getItemMainType() == emu.lunarcore.game.enums.ItemMainType.Virtual)
              || cost.ItemID() == 2
                  && excel.getItemMainType() != emu.lunarcore.game.enums.ItemMainType.Virtual
              || selected && (cost.ItemNum() != 1 || costs.containsKey(cost.ItemID())))
            return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
          costs.merge(cost.ItemID(), cost.ItemNum(), Math::addExact);
        }
      } catch (ArithmeticException e) {
        return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
      }
      if (selected) {
        // All pinned 4.6 UseSelectedItem definitions and SelectMissionItem SlotNum use one
        // choice.
        // The optional item list is mandatory for this finish type; never pick on the
        // client's
        // behalf.
        if (reported == null || reported.size() != 1) return Retcode.REQ_PARA_INVALID;
        var choice = reported.entrySet().iterator().next();
        if (choice.getKey() == null
            || choice.getValue() == null
            || choice.getKey() <= 0
            || choice.getValue() <= 0
            || !Objects.equals(costs.get(choice.getKey()), choice.getValue()))
          return Retcode.REQ_PARA_INVALID;
        costs.clear();
        costs.put(choice.getKey(), choice.getValue());
      } else if (reported != null && !costs.equals(reported)) return Retcode.REQ_PARA_INVALID;
      var payments = player.getGameplay().getMissionPayments();
      var payment = payments.get(id);
      if (payment != null && payment.isPending()) return Retcode.SYSTEM_BUSY;
      if (payment != null && payment.getNext() != 1) return Retcode.PLAYER_DATA_ERROR;
      var plans = player.getGameplay().getMissionPaymentPlans();
      var plan = plans.get(id);
      if (payment == null && plan != null || payment != null && selected && plan == null)
        return Retcode.PLAYER_DATA_ERROR;
      if (plan != null
          && (!c.getFinishType().equals(plan.getFinishType()) || !costs.equals(plan.getCosts())))
        return Retcode.REQ_PARA_INVALID;
      var actions = new StoryActions(player);
      var validation = actions.validate(c);
      if (validation != Retcode.SUCC) return validation;
      if (payment == null) {
        // Hold the inventory while checking all balances and debiting the configured items.
        synchronized (player.getInventory()) {
          for (var cost : costs.entrySet()) {
            if (cost.getKey() == 2) {
              if (player.getScoin() < cost.getValue()) return Retcode.ITEM_NOT_ENOUGH;
              continue;
            }
            var item = player.getInventory().getMaterialByItemId(cost.getKey());
            if (item == null
                || item.getOwnerUid() != player.getUid()
                || item.getCount() < cost.getValue()) return Retcode.ITEM_NOT_ENOUGH;
          }
          payment = new GameplayState.ActionCursor();
          DailyMissionRecoverDeliveryRules.bindPayment(player, c, costs, clock.instant());
          payments.put(id, payment);
          plans.put(id, new GameplayState.MissionPaymentPlan(c.getFinishType(), costs));
          payment.reserve();
          DailyMissionSelectedItemRules.bindPayment(player, c, costs, clock.instant());
          player.save();
          for (var cost : costs.entrySet()) {
            if (cost.getKey() == 2) {
              int before = player.getScoin();
              player.addSCoin(-cost.getValue());
              if (player.getScoin() != before - cost.getValue()) return Retcode.PLAYER_DATA_ERROR;
              continue;
            }
            if (!player
                .getInventory()
                .removeItem(
                    player.getInventory().getMaterialByItemId(cost.getKey()), cost.getValue()))
              return Retcode.PLAYER_DATA_ERROR;
          }
          payment.complete();
          player.save();
          if (costs.containsKey(2)) onItemsConsumed(Map.of(2, costs.get(2)));
        }
      }
      var result = actions.execute(c);
      if (result != Retcode.SUCC) return result;
      state.advance(goal(c), goal(c), now());
      new MissionRewards(player).freezeSub(c);
      player.save();
      refresh();
      sync();
      return Retcode.SUCC;
    }
  }

  public void onBattleOutcome(
      int stage, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus outcome) {
    var type =
        switch (outcome) {
          case BATTLE_END_LOSE -> "BattleLose";
          case BATTLE_END_QUIT -> "BattleQuit";
          default -> "";
        };
    if (!type.isEmpty())
      event(type, c -> "Equal".equals(c.getParamType()) && c.getParamInt1() == stage, 1);
  }

  /** Called only from the once-only settlement of the player's current server battle. */
  public void onBattleVictory(emu.lunarcore.game.battle.Battle battle) {
    synchronized (player) {
      if (battle == null
          || battle.getPlayer() != player
          || player.getBattle() != battle
          || !battle.isSettling()
          || battle.getStats() == null
          || battle.getResult()
              != emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN)
        return;
      var cocoon = GameData.getCocoonExcelMap().get(battle.getCocoonId(), battle.getWorldLevel());
      boolean cocoonWon =
          battle.getCocoonId() > 0
              && battle.getCocoonWave() > 0
              && cocoon != null
              && cocoon.getCocoonId() == battle.getCocoonId()
              && cocoon.getWorldLevel() == battle.getWorldLevel();
      boolean farmWon =
          battle.getStage().getStageType() == emu.lunarcore.game.enums.StageType.FarmElement;
      var category = CocoonCategories.victory(player, battle);
      event(
          c ->
              "StageWin".equals(c.getFinishType())
                  ? c.getParamInt1() == battle.getStage().getId()
                      || battle.getMissionStageWins() != null
                          && battle.getMissionStageWins().matches(player, battle, c)
                      || battle.getMissionBattleCustomValues() != null
                          && battle
                              .getMissionBattleCustomValues()
                              .matchesStageWin(player, battle, c)
                  : "KillMonsterInBattle".equals(c.getFinishType())
                      ? battle.getMissionBattleKills() != null
                          && battle.getMissionBattleKills().matches(player, battle, c)
                      : "BattleWinWithCustomValue".equals(c.getFinishType())
                          ? battle.getMissionBattleCustomValues() != null
                              && battle.getMissionBattleCustomValues().matches(player, battle, c)
                          : c.getProgress() == 1
                              && c.getParamInt1() == 0
                              && c.getParamInt2() == 0
                              && c.getParamInt3() == 0
                              && c.getParamStr1() != null
                              && c.getParamStr1().isBlank()
                              && c.getParamItemList() != null
                              && c.getParamItemList().isEmpty()
                              && c.getParamIntList() != null
                              && matchesPlace(c)
                              && switch (Objects.toString(c.getFinishType(), "")) {
                                case "CocoonFinish" ->
                                    cocoonWon
                                        && "ListContain".equals(c.getParamType())
                                        && c.getParamIntList().length > 0
                                        && Arrays.stream(c.getParamIntList())
                                            .allMatch(id -> id != null && id > 0)
                                        && new HashSet<>(Arrays.asList(c.getParamIntList())).size()
                                            == c.getParamIntList().length
                                        && Arrays.asList(c.getParamIntList())
                                            .contains(battle.getCocoonId());
                                case "AnyFarmElementFinish" ->
                                    farmWon
                                        && "NoPara".equals(c.getParamType())
                                        && c.getParamIntList().length == 0;
                                case "AnyCocoonFinish" ->
                                    category.isPresent()
                                        && CocoonCategories.categories(
                                            c.getParamType(),
                                            Arrays.asList(c.getParamIntList()),
                                            category.get().category());
                                default -> false;
                              },
          1);
    }
  }

  public void onMusicResult(int id, int stars) {
    event(
        "MusicRhythmFinishLevel",
        c ->
            c.getParamInt1() == id
                && ("GreaterEqual".equals(c.getParamType())
                    ? stars >= c.getParamInt2()
                    : "Equal".equals(c.getParamType()) && stars == c.getParamInt2()),
        1);
  }

  private static boolean dicePveVictoryCondition(MissionInfo.SubMissionInfo c, int stage) {
    var ids = c.getParamIntList();
    return "DiceCombatFinishPVEStage".equals(c.getFinishType())
        && "NoPara".equals(c.getParamType())
        && c.getProgress() == 1
        && c.getParamInt1() == 1
        && c.getParamInt2() == 0
        && c.getParamInt3() == 0
        && c.getParamStr1() != null
        && c.getParamStr1().isEmpty()
        && c.getParamItemList() != null
        && c.getParamItemList().isEmpty()
        && c.getIgnoreVerseParamList() != null
        && c.getIgnoreVerseParamList().isEmpty()
        && c.getStoryLineID() == 0
        && c.getStoryLineIDList() != null
        && c.getStoryLineIDList().isEmpty()
        && ids != null
        && ids.length > 0
        && Arrays.stream(ids).allMatch(id -> id != null && id > 0)
        && Arrays.stream(ids).distinct().count() == ids.length
        && Arrays.asList(ids).contains(stage);
  }

  public Set<Integer> dicePveVictoryTargets(int stage) {
    var targets = new HashSet<Integer>();
    for (var entry : player.getGameplay().getSubMissions().entrySet()) {
      var state = entry.getValue();
      var c = GameplayDefinitions.subs.get(entry.getKey());
      var main =
          c == null ? null : player.getGameplay().getMainMissions().get(c.getMainMissionID());
      if (c != null
          && state != null
          && state.getQuestId() == c.getId()
          && main != null
          && main.getQuestId() == c.getMainMissionID()
          && main.getPhase() == QuestState.Phase.DOING
          && main.getFinishTime() == 0
          && state.getPhase() == QuestState.Phase.DOING
          && state.getProgress() == 0
          && state.getFinishTime() == 0
          && canFinishFromAction(c.getId())
          && dicePveVictoryCondition(c, stage)
          && matchesPlace(c)) targets.add(c.getId());
    }
    return Set.copyOf(targets);
  }

  public void onDicePveVictory(GameplayState.DicePveWin receipt) {
    if (receipt == null
        || !receipt.isApplied()
        || receipt.isMissionEventsDone()
        || player.getGameplay().getDiceCombat().getPveWins().get(receipt.getStage()) != receipt
        || player.getPlaneId() != receipt.getPlane()
        || player.getFloorId() != receipt.getFloor()) return;
    event(
        c ->
            receipt.getTargets().contains(c.getId())
                && dicePveVictoryCondition(c, receipt.getStage())
                && matchesPlace(c),
        1);
  }

  public Set<Integer> heartDialTargets(int dialogue) {
    var targets = new HashSet<Integer>();
    for (var state : player.getGameplay().getSubMissions().values()) {
      var config = GameplayDefinitions.subs.get(state.getQuestId());
      if (config != null
          && state.getPhase() == QuestState.Phase.DOING
          && heartDialCondition(config, dialogue)
          && matchesPlace(config)
          && canFinishFromAction(config.getId())) targets.add(config.getId());
    }
    return targets;
  }

  private static boolean heartDialCondition(MissionInfo.SubMissionInfo config, int dialogue) {
    var ids = config.getParamIntList();
    return "HeartDialDialoguePerformanceFinish".equals(config.getFinishType())
        && "ListContain".equals(config.getParamType())
        && config.getProgress() == 1
        && config.getParamInt1() == 0
        && config.getParamInt2() == 0
        && config.getParamInt3() == 0
        && config.getParamStr1() != null
        && config.getParamStr1().isEmpty()
        && config.getParamItemList() != null
        && config.getParamItemList().isEmpty()
        && ids != null
        && ids.length > 0
        && Arrays.stream(ids).allMatch(id -> id > 0)
        && Arrays.stream(ids).distinct().count() == ids.length
        && Arrays.stream(ids).anyMatch(id -> id == dialogue);
  }

  public void onHeartDialDialogue(int dialogue, Set<Integer> targets) {
    var report = player.getGameplay().getHeartDialReports().get(dialogue);
    if (report == null
        || report.isApplied()
        || report.getTargets() == null
        || !report.getTargets().equals(targets)) return;
    event(
        config ->
            targets.contains(config.getId())
                && heartDialCondition(config, dialogue)
                && matchesPlace(config),
        1);
  }

  public void onMatchThreeBirdPosition(int bird, int position) {
    event(c -> matchesPlace(c) && MatchThreeBirdPlacement.matches(player, c, bird, position), 1);
  }

  public Set<Integer> matchThreeTargets(int id, int mode) {
    var targets = new HashSet<Integer>();
    for (var state : player.getGameplay().getSubMissions().values()) {
      var c = GameplayDefinitions.subs.get(state.getQuestId());
      if (c != null
          && state.getPhase() == QuestState.Phase.DOING
          && matchThreeCondition(c, id, mode)
          && matchesPlace(c)
          && canFinishFromAction(c.getId())
          && !player.getGameplay().getCancelledSubs().contains(c.getId())
          && !player.getGameplay().getCancelledMissions().contains(c.getMainMissionID()))
        targets.add(c.getId());
    }
    return targets;
  }

  private static boolean matchThreeCondition(MissionInfo.SubMissionInfo c, int id, int mode) {
    return "MatchThreeFinishLevel".equals(c.getFinishType())
        && "Equal".equals(c.getParamType())
        && c.getParamInt1() == id
        && c.getParamInt2() == mode
        && c.getParamInt3() == 0
        && c.getParamStr1().isBlank()
        && c.getParamIntList().length == 0
        && c.getParamItemList().isEmpty()
        && c.getProgress() <= 1;
  }

  public void onMatchThreeResult(int id, int mode, Set<Integer> targets) {
    if (!player.getGameplay().getMatchThree().getCompleted().containsKey(mode + ":" + id)) return;
    event(c -> targets.contains(c.getId()) && matchThreeCondition(c, id, mode), 1);
  }

  public boolean matchThreeTargetsDone(Set<Integer> targets) {
    return targets.stream()
        .allMatch(
            id -> {
              if (player.getGameplay().getCancelledSubs().contains(id)) return true;
              var c = GameplayDefinitions.subs.get(id);
              if (c != null
                  && player.getGameplay().getCancelledMissions().contains(c.getMainMissionID()))
                return true;
              var state = sub(id);
              return state != null && state.getPhase() != QuestState.Phase.DOING;
            });
  }

  public void onParkourResult(int id) {
    var record = player.getGameplay().getParkour().getRecords().get(id);
    if (record == null || !record.isPassed() || record.getTime() <= 0) return;
    event(
        "ParkourFinishLevel",
        c ->
            "ListContain".equals(c.getParamType())
                && c.getParamInt1() == 0
                && c.getParamInt2() == 0
                && c.getParamInt3() == 0
                && c.getParamStr1().isBlank()
                && c.getParamItemList().isEmpty()
                && c.getParamIntList().length > 0
                && java.util.Arrays.stream(c.getParamIntList()).allMatch(v -> v > 0)
                && java.util.Arrays.stream(c.getParamIntList()).anyMatch(v -> v == id)
                && matchesPlace(c),
        1);
  }

  public void onMusicCompositionSaved(int song) {
    if (!player.getGameplay().getRhythm().getCompositions().containsKey(song)) return;
    event(
        "MusicRhythmSaveSongConfig",
        c ->
            "Equal".equals(c.getParamType())
                && c.getParamInt1() == song
                && c.getParamInt2() == 0
                && c.getParamInt3() == 0
                && c.getParamStr1().isBlank()
                && c.getParamIntList().length == 0
                && c.getParamItemList().isEmpty()
                && c.getProgress() <= 1,
        1);
  }

  public void onEnterFloor(int plane, int floor) {
    if (player.getPlaneId() != plane || player.getFloorId() != floor) return;
    int entry = player.getEntryId();
    event(
        c ->
            switch (Objects.toString(c.getFinishType(), "")) {
              case "EnterFloor" -> c.getParamInt1() == plane && c.getParamInt2() == floor;
              case "EnterMapByEntrance" ->
                  "Equal".equals(c.getParamType()) && entry > 0 && c.getParamInt1() == entry;
              default -> false;
            },
        1);
    // EnterPlane is a location condition, unlike an entrance transition.
    refresh();
  }

  public void onSceneTransition(
      int oldPlane, int oldFloor, int oldEntry, int plane, int floor, int entry) {
    if (plane <= 0
        || floor <= 0
        || player.getPlaneId() != plane
        || player.getFloorId() != floor
        || player.getEntryId() != entry) return;
    boolean floorChanged = oldPlane != plane || oldFloor != floor;
    boolean entryChanged = oldEntry != entry || floorChanged;
    event(
        c ->
            switch (Objects.toString(c.getFinishType(), "")) {
              case "LeavePlane" ->
                  "Equal".equals(c.getParamType())
                      && oldPlane > 0
                      && oldPlane != plane
                      && c.getParamInt1() == oldPlane;
              case "LeaveFloor" ->
                  "Equal".equals(c.getParamType())
                      && oldPlane > 0
                      && oldFloor > 0
                      && floorChanged
                      && c.getParamInt1() == oldPlane
                      && c.getParamInt2() == oldFloor;
              case "EnterFloor" ->
                  floorChanged && c.getParamInt1() == plane && c.getParamInt2() == floor;
              case "EnterMapByEntrance" ->
                  entryChanged
                      && "Equal".equals(c.getParamType())
                      && entry > 0
                      && c.getParamInt1() == entry;
              default -> false;
            },
        1);
    refresh();
  }

  public void onFloorChanged(MissionFloorChanges.Context context) {
    if (context != null) event(c -> context.matches(player, c), 1);
  }

  public void onPropState(int floor, int group, int instance, int state) {
    event(
        "PropState",
        c ->
            c.getLevelFloorID() == floor
                && c.getParamInt1() == group
                && c.getParamInt2() == instance
                && c.getParamInt3() == state,
        1);
  }

  public void onPropInteraction(int floor, int group, int instance, int state) {
    if (floor <= 0 || group <= 0 || instance <= 0 || player.getFloorId() != floor) return;
    event(
        c ->
            c.getLevelFloorID() == floor
                && switch (Objects.toString(c.getFinishType(), "")) {
                  case "PropState" ->
                      c.getParamInt1() == group
                          && c.getParamInt2() == instance
                          && c.getParamInt3() == state;
                  case "InteractPropList" ->
                      "ListContain".equals(c.getParamType())
                          && c.getProgress() <= 1
                          && c.getParamInt1() == group
                          && c.getParamInt2() == 0
                          && c.getParamInt3() == 0
                          && c.getParamStr1().isBlank()
                          && c.getParamItemList().isEmpty()
                          && c.getParamIntList().length > 0
                          && Arrays.stream(c.getParamIntList()).allMatch(v -> v > 0)
                          && new HashSet<>(Arrays.asList(c.getParamIntList())).size()
                              == c.getParamIntList().length
                          && Arrays.stream(c.getParamIntList()).anyMatch(v -> v == instance);
                  default -> false;
                },
        1);
  }

  public void onKillMonster(int floor, int group, int instance) {
    if (floor <= 0 || group <= 0 || instance <= 0 || player.getFloorId() != floor) return;
    event(
        c ->
            c.getLevelFloorID() == floor
                && switch (Objects.toString(c.getFinishType(), "")) {
                  case "KillMonster" -> c.getParamInt1() == group && c.getParamInt2() == instance;
                  case "KillMonsterList", "KillMonsterGroupList" ->
                      "ListContain".equals(c.getParamType())
                          && c.getParamInt2() == 0
                          && c.getParamInt3() == 0
                          && c.getParamStr1().isBlank()
                          && c.getParamIntList().length > 0
                          && Arrays.stream(c.getParamIntList()).allMatch(v -> v > 0)
                          && ("KillMonsterList".equals(c.getFinishType())
                              ? c.getParamInt1() == group
                                  && Arrays.stream(c.getParamIntList()).anyMatch(v -> v == instance)
                              : c.getParamInt1() == 0
                                  && Arrays.stream(c.getParamIntList()).anyMatch(v -> v == group));
                  default -> false;
                },
        1);
  }

  public void onPropDestruct(int floor, int group, int instance) {
    if (floor <= 0 || group <= 0 || instance <= 0 || player.getFloorId() != floor) return;
    event(
        "PropDestruct",
        c ->
            "Equal".equals(c.getParamType())
                && c.getLevelFloorID() == floor
                && c.getParamInt1() == group
                && c.getParamInt2() == instance
                && c.getParamInt3() == 0
                && c.getProgress() <= 1
                && c.getParamStr1().isBlank()
                && c.getParamIntList().length == 0
                && c.getParamItemList().isEmpty(),
        1);
  }

  public void onLevelChange() {
    refresh();
    event("PlayerLevel", c -> player.getLevel() >= c.getParamInt1(), 1);
    event("WorldLevel", c -> player.getWorldLevel() >= c.getParamInt1(), 1);
  }

  private void event(String type, Predicate<MissionInfo.SubMissionInfo> predicate, int amount) {
    event(c -> type.equals(c.getFinishType()) && predicate.test(c), amount);
  }

  public void onClockParkProgress() {
    event(
        c ->
            "ClockParkProgress".equals(c.getFinishType())
                && matchesPlace(c)
                && ClockParkMissionProgress.satisfied(player, c),
        1);
  }

  public void onStoryChallengeSettlement(emu.lunarcore.game.battle.Battle battle) {
    synchronized (player) {
      var targets = StoryChallengeSettlementGoals.completed(player, battle);
      if (targets.isEmpty()) return;
      event(config -> StoryChallengeSettlementGoals.matches(player, targets, config), 1);
    }
  }

  public void onChallengeVictory(emu.lunarcore.game.battle.Battle battle) {
    synchronized (player) {
      var context = battle.getMissionChallengeWin();
      if (context == null || !context.completed(player, battle)) return;
      if (player.getGameplay().recordMemoryChallenge(context.challengeId())) player.save();
      event(config -> context.matchesTarget(player, config), 1);
      refresh();
    }
  }

  private void event(Predicate<MissionInfo.SubMissionInfo> predicate, int amount) {
    synchronized (player) {
      boolean changed = false;
      // Snapshot avoids newly unlocked objectives consuming the same event twice.
      for (var state : List.copyOf(player.getGameplay().getSubMissions().values())) {
        var config = GameplayDefinitions.subs.get(state.getQuestId());
        if (config != null
            && predicate.test(config)
            && canFinishFromAction(config.getId())
            && !player.getGameplay().getCancelledSubs().contains(config.getId())
            && !player.getGameplay().getCancelledMissions().contains(config.getMainMissionID()))
          changed |= advance(config, state, amount);
      }
      if (changed) {
        player.save();
        refresh();
        sync();
      }
    }
  }

  private static int goal(MissionInfo.SubMissionInfo c) {
    return Math.max(c.getProgress(), 1);
  }

  private boolean advance(MissionInfo.SubMissionInfo c, QuestState state, int amount) {
    if (state.getPhase() != QuestState.Phase.DOING || amount <= 0) return false;
    if ((long) state.getProgress() + amount >= goal(c)
        && new StoryActions(player).execute(c) != Retcode.SUCC) return false;
    boolean changed = state.advance(amount, goal(c), now());
    if (changed && state.getPhase() == QuestState.Phase.FINISHED)
      new MissionRewards(player).freezeSub(c);
    return changed;
  }

  private boolean currentCondition(MissionInfo.SubMissionInfo c) {
    return switch (Objects.toString(c.getFinishType(), "")) {
      case "TimeLineSetState" -> PropTimelines.satisfied(player,c);
      case "BoxingClubAccomplish" -> BoxingClubMissionGoals.satisfied(player, c);
      case "GetTrialAvatar", "GetTrialAvatarList" ->
          TrialAvatarMissionConditions.satisfied(player, c);
      case "DelTrialAvatar" -> MissionTrialRemovals.satisfied(player, c);
      case "RogueExploreScore" -> RogueExploreScoreMission.satisfied(player, c);
      case "ClockParkProgress" -> matchesPlace(c) && ClockParkMissionProgress.satisfied(player, c);
      case "StoryLineAddTrialAvatar" -> StoryTrialReplacement.apply(player, c);
      case "EnterRaidScene" -> new MissionRaid(player).transfer(c);
      case "RaidFinishCnt", "RaidFinishedAndTransfer" ->
          new MissionRaid(player).finishCondition(c) && matchesPlace(c);
      case "ChallengeFinish" ->
          c.isBackTrack()
              && MissionChallengeWin.condition(c, c.getParamInt1())
              && matchesPlace(c)
              && player.getGameplay().getStoryLines().getCurrentId() == 0
              && player.getGameplay().getCompletedMemoryChallenges().contains(c.getParamInt1());
      case "CreateCharacter" ->
          "Equal".equals(c.getParamType())
              && c.getParamInt1() == 0
              && c.getParamInt2() == 0
              && c.getParamInt3() == 0
              && c.getParamStr1().isBlank()
              && c.getParamIntList().length == 0
              && c.getParamItemList().isEmpty()
              && c.getProgress() == 1
              && matchesPlace(c)
              && player.hasCreatedCharacter();
      case "HeartDialScriptListStep" -> HeartDialSteps.objective(player, c) && matchesPlace(c);
      case "MusicRhythmFinishAllLevel" ->
          "GreaterEqual".equals(c.getParamType())
              && c.getParamInt3() == 0
              && c.getParamStr1().isBlank()
              && c.getParamIntList().length == 0
              && c.getParamItemList().isEmpty()
              && c.getProgress() <= 1
              && new MusicManager(player).completedAtMost(c.getParamInt1(), c.getParamInt2());
      case "MessageSectionFinish", "MessagePerformSectionFinish" ->
          MessageManager.matches(c) && new MessageManager(player).finished(c);
      case "SubMissionMultiFinish" -> MissionRelations.finished(player, c) && matchesPlace(c);
      case "WaitDays" ->
          "Equal".equals(c.getParamType())
              && c.getParamInt1() > 0
              && c.getParamInt2() == 0
              && c.getParamInt3() == 0
              && c.getParamStr1().isBlank()
              && c.getParamIntList().length == 0
              && c.getParamItemList().isEmpty()
              && c.getProgress() <= 1
              && player.getGameplay().getMissionAcceptedDays().containsKey(c.getId())
              && DailyManager.day(clock.instant())
                      - player.getGameplay().getMissionAcceptedDays().get(c.getId())
                  >= c.getParamInt1();
      case "FloorSavedValue" ->
          Objects.equals(
              player
                  .getGameplay()
                  .getStoryValues()
                  .get("floor:" + c.getLevelFloorID() + ":" + c.getParamStr1()),
              c.getParamInt1());
      case "EnterPlane" ->
          "Equal".equals(c.getParamType())
              && c.getParamInt1() > 0
              && player.getPlaneId() == c.getParamInt1();
      case "NotInPlane" ->
          "Equal".equals(c.getParamType())
              && c.getParamInt1() > 0
              && player.getPlaneId() > 0
              && player.getPlaneId() != c.getParamInt1();
      case "NotInFloor" ->
          "Equal".equals(c.getParamType())
              && c.getParamInt1() > 0
              && c.getParamInt2() > 0
              && player.getPlaneId() > 0
              && player.getFloorId() > 0
              && (player.getPlaneId() != c.getParamInt1()
                  || player.getFloorId() != c.getParamInt2());
      case "GroupState" -> {
        var scene = player.getScene();
        yield "Equal".equals(c.getParamType())
            && c.getParamInt1() > 0
            && c.getParamInt2() >= 0
            && scene != null
            && scene.getFloorId() == c.getLevelFloorID()
            && scene.hasStoryGroup(c.getParamInt1())
            && scene.getGroups().containsKey(c.getParamInt1())
            && scene.getGroups().get(c.getParamInt1()).getState() == c.getParamInt2();
      }
      case "PlayerLevel" -> player.getLevel() >= c.getParamInt1();
      case "WorldLevel" -> player.getWorldLevel() >= c.getParamInt1();
      case "ItemNum" -> {
        var item = player.getInventory().getMaterialByItemId(c.getParamInt1());
        yield item != null && item.getCount() >= goal(c);
      }
      case "GroupPropertyValue" -> {
        var scene = player.getScene();
        yield scene != null
            && scene.getFloorId() == c.getLevelFloorID()
            && scene.hasGroupProperty(c.getParamInt1(), c.getParamStr1())
            && scene.getGroups().get(c.getParamInt1()).getProperties().getInt(c.getParamStr1())
                == c.getParamInt2();
      }
      case "GroupPropertyValueIgnoreVerse" -> StoryGroupConditions.shared(player, c);
      case "PropState" -> ScenePropTransitions.satisfied(player, c);
      default -> false;
    };
  }

  /** The same area-progress identity used by the pinned FinishWayRogue resource. */
  private int rogueVictoryCount(MissionInfo.SubMissionInfo c) {
    if (!"Equal".equals(c.getParamType())
        || c.getParamInt1() < 0
        || c.getParamInt2() != 0
        || c.getParamInt3() != 0
        || !c.getParamStr1().isBlank()
        || c.getParamIntList().length != 0
        || !c.getParamItemList().isEmpty()
        || c.getProgress() <= 0) return -1;
    long count = 0;
    for (var entry : player.getGameplay().getRogueWins().entrySet()) {
      var area = GameplayDefinitions.areas.get(entry.getKey());
      if (area != null
          && area.RogueAreaID() == entry.getKey()
          && area.AreaProgress() == c.getParamInt1()
          && entry.getValue() != null
          && entry.getValue() > 0) count += entry.getValue();
    }
    return (int) Math.min(count, Integer.MAX_VALUE);
  }

  private boolean customGate(MissionInfo.SubMissionInfo sub) {
    if (sub.getTakeParamStrList() == null) return false;
    if (sub.getTakeParamStrList().length != 0) return MissionNamedCustomGates.matches(player, sub);
    var main = GameData.getMissionInfos().get(sub.getMainMissionID());
    if (main == null || main.getMissionCustomValueList() == null) return false;
    var params = sub.getTakeParamIntList();
    int width = params.length > 5 ? 3 : params.length;
    if (width == 0 || params.length % width != 0) return false;
    for (int offset = 0; offset < params.length; offset += width) {
      boolean ok = true;
      int matched = 0;
      for (int index = 0; index < width; index++) {
        if (index == 0 && params[offset + index] == 0) continue;
        int i = index;
        var definition =
            Arrays.stream(main.getMissionCustomValueList())
                .filter(c -> c.getIndex() == i)
                .findFirst();
        Integer actual =
            definition.isEmpty()
                ? null
                : player
                    .getGameplay()
                    .getStoryValues()
                    .get("mission:" + sub.getMainMissionID() + ":" + definition.get().getName());
        if (actual == null || !actual.equals(params[offset + index])) {
          ok = false;
          break;
        }
        matched++;
      }
      if (ok && matched > 0) return true;
    }
    return false;
  }

  boolean beginActions(int id) {
    return activeActions.add(id);
  }

  void endActions(int id) {
    activeActions.remove(id);
  }

  boolean executingActions(int id) {
    return activeActions.contains(id);
  }

  boolean canFinishFromAction(int id) {
    var c = GameplayDefinitions.subs.get(id);
    return c != null
        && c.getId() == id
        && c.getProgress() >= 0
        && new MissionRaid(player).activeFor(c.getMainMissionID())
        && MissionStateRecords.mainDoing(player, c.getMainMissionID())
        && DailyMissionExpiry.current(player, c.getMainMissionID(), clock.instant())
        && (sub(id) == null || MissionStateRecords.subAccepted(player, id))
        && !player.getGameplay().getCancelledSubs().contains(id)
        && !player.getGameplay().getCancelledMissions().contains(c.getMainMissionID());
  }

  public void onBoxingClubCompletion(
      BoxingClubMissionGoals.Context context,
      BoxingClubState.Run first,
      BoxingClubResonanceState.Run second) {
    synchronized (player) {
      if (context != null) event(c -> context.matches(player, c, first, second), 1);
      refresh();
    }
  }

  Retcode finishFromAction(int id) {
    if (DailyMissionRecoverDeliveryRules.sub(id)) return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
    if (!canFinishFromAction(id)) return Retcode.MISSION_NOT_DOING;
    // Resource 8024111's mutually completing loss/quit branches reach an already active root.
    // The root will finish after its own actions succeed; executing it again would deadlock its
    // cursor.
    if (executingActions(id)) return Retcode.SUCC;
    var c = GameplayDefinitions.subs.get(id);
    var state = player.getGameplay().getSubMissions().computeIfAbsent(id, QuestState::new);
    if (state.getPhase() != QuestState.Phase.DOING) return Retcode.SUCC;
    var result = new StoryActions(player).execute(c);
    if (result != Retcode.SUCC) return result;
    state.advance(goal(c), goal(c), now());
    new MissionRewards(player).freezeSub(c);
    player.save();
    return Retcode.SUCC;
  }

  private long now() {
    return clock.instant().getEpochSecond();
  }

  private void sync() {
    if (player.getScene() != null) player.getScene().refreshMissionGroups();
    org.hkrpg.server.protocol.Gameplay46.syncMissions(player);
  }

  public boolean shouldLoadGroup(emu.lunarcore.data.config.GroupInfo group) {
    return shouldLoadGroup(group, player.getScene());
  }

  public boolean shouldLoadGroup(
      emu.lunarcore.data.config.GroupInfo group, emu.lunarcore.game.scene.Scene scene) {
    if (group.getCategory() == emu.lunarcore.data.config.GroupInfo.GroupCategory.Atmosphere)
      return AtmosphereGroups.allowed(player, group, scene);
    boolean deferred = DeferredMissionGroups.tracks(group, scene);
    boolean ordinary = MissionGroupConditions.tracks(group, scene);
    if (group.getCategory() != emu.lunarcore.data.config.GroupInfo.GroupCategory.Mission
        && !deferred
        && !ordinary) return true;
    if (group.getOwnerMainMissionID() != 0) {
      int owner = group.getOwnerMainMissionID();
      var config = GameData.getMainMissionExcelMap().get(owner);
      if (config != null && config.isInRaid() && !new MissionRaid(player).owns(owner)) return false;
      var state = player.getGameplay().getMainMissions().get(owner);
      if (state == null
          || !MissionStateRecords.mainDoing(player, owner)
          || !DailyMissionExpiry.current(player, owner, currentInstant())
          || player.getGameplay().getCancelledMissions().contains(owner)) return false;
    }
    if (deferred && DeferredMissionGroups.retains(group, scene)) return true;
    if (ordinary
        && group.getLoadCondition() != null
        && group.getLoadCondition().isDelayToLevelReload()
        && scene.isLoaded()
        && !scene.getLoadedGroups().contains(group.getId())) return false;
    boolean load =
        group.getLoadCondition() == null
            ? group.isLoadOnInitial()
                && (group.getOwnerMainMissionID() != 0
                    || group.getCategory()
                        != emu.lunarcore.data.config.GroupInfo.GroupCategory.Mission)
            : conditions(group.getLoadCondition());
    if (deferred || (ordinary && group.getUnloadCondition() != null))
      return load
          && MissionGroupConditions.evaluate(player, group.getUnloadCondition())
              == MissionGroupConditions.Result.NO_MATCH;
    return load && (group.getUnloadCondition() == null || !conditions(group.getUnloadCondition()));
  }

  private boolean conditions(emu.lunarcore.data.config.LoadConditionInfo set) {
    return MissionGroupConditions.matches(player, set);
  }
}
