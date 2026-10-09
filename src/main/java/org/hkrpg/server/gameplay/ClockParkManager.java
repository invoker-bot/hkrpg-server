package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Saved openings, normal dice, original checkpoints and first chapter branches; games remain
 * partial.
 */
public final class ClockParkManager {
  public record Result(
      Retcode code,
      ClockParkState.Run run,
      List<emu.lunarcore.game.inventory.GameItem> rewards,
      boolean newEnding,
      boolean failed,
      int lotteryId) {
    public Result(
        Retcode code,
        ClockParkState.Run run,
        List<emu.lunarcore.game.inventory.GameItem> rewards,
        boolean newEnding,
        boolean failed) {
      this(code, run, rewards, newEnding, failed, 0);
    }

    public Result(Retcode code, ClockParkState.Run run) {
      this(code, run, List.of(), false, false);
    }

    public Result(
        Retcode code,
        ClockParkState.Run run,
        List<emu.lunarcore.game.inventory.GameItem> rewards,
        boolean newEnding) {
      this(code, run, rewards, newEnding, false);
    }
  }

  private static final Map<String, String> INPUTS =
      Map.of(
          "ExcelOutput/ActivityPanel.json",
              "80a899976fbc5d61fe81483b9e5dd35679c96eed7eb3ef6285c008b475fbc1e4",
          "ExcelOutput/ActivityConfig.json",
              "a94d11500502e07789cc38a28211fb3f6cef69400de1be988cc6b8d9634afd3e",
          "Config/Level/Mission/8022101/MissionInfo_8022101.partial.json",
              "dd8bfa85f5b300ced4e5ebdda6dc7a051fdebd34e7bb58becfa80ceabccd8855",
          "Config/Level/Mission/8022108/MissionInfo_8022108.partial.json",
              "797ec4e4893f03331f403cd87ce7b179abb5c2a8e650562b63dfa265c080359a",
          "Config/Level/Mission/8022120/MissionInfo_8022120.partial.json",
              "57ada1de03f65418bc58c0555de3bd34eb158600e67b52e339343c3a5f525232",
          "Config/Level/Mission/8022124/MissionInfo_8022124.partial.json",
              "c3b0ec247e246f111e3374eee8d49e27716c14f864697427c68aea733cdf4fe4",
          "Config/Level/Mission/8022103/MissionInfo_8022103.partial.json",
              "f81972ede74b281ef25ecaa148edb5f709e552cf9608d851c03c53e39aaa8f29");
  private final Player player;

  public ClockParkManager(Player player) {
    this.player = player;
  }

  public static ClockParkCatalog catalog() {
    return ClockParkCatalog.load(Path.of(LunarCore.getConfig().getResourceDir()));
  }

  private static boolean sources(ClockParkCatalog catalog) {
    if (catalog == null) return false;
    Path root = Path.of(LunarCore.getConfig().getResourceDir());
    try {
      for (var input : INPUTS.entrySet()) {
        var path = root.resolve(input.getKey());
        long size = Files.size(path);
        if (size <= 0 || size > 1024 * 1024) return false;
        byte[] bytes = Files.readAllBytes(path);
        if (!input
            .getValue()
            .equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
          return false;
      }
      var panel = GameData.getActivityPanelExcelMap().get(50012);
      if (panel == null
          || panel.getId() != 50012
          || !"[PlayerLevel:21]".equals(panel.getUnlockConditions())
          || !"UI/Quest/Widget/QuestClockParkPanel.prefab".equals(panel.getUIPrefab()))
        return false;
      // Verify loaded prerequisite identities against the same pinned original objects.
      for (var script : catalog.scripts().values())
        if (script.kind().equals("Normal")) {
          var sub = GameplayDefinitions.subs.get(script.prerequisiteId());
          if (sub == null || sub.getId() != script.prerequisiteId() || sub.getProgress() != 1)
            return false;
          String name =
              "Config/Level/Mission/"
                  + sub.getMainMissionID()
                  + "/MissionInfo_"
                  + sub.getMainMissionID()
                  + ".partial.json";
          if (!INPUTS.containsKey(name)) return false;
          var original =
              JsonParser.parseString(Files.readString(root.resolve(name)))
                  .getAsJsonObject()
                  .getAsJsonArray("SubMissionList");
          var matches =
              original.asList().stream()
                  .map(JsonElement::getAsJsonObject)
                  .filter(r -> r.get("ID").getAsInt() == sub.getId())
                  .toList();
          if (matches.size() != 1
              || matches.getFirst().get("MainMissionID").getAsInt() != sub.getMainMissionID()
              || matches.getFirst().get("Progress").getAsInt() != sub.getProgress()
              || !matches.getFirst().get("FinishType").getAsString().equals(sub.getFinishType()))
            return false;
        }
      return true;
    } catch (Exception invalid) {
      return false;
    }
  }

  private boolean open() {
    return player.getLoginActivityManager().activeWindows().stream()
        .anyMatch(w -> w.panelId() == 50012 && w.moduleId() == 5001202);
  }

  private Result rejected(Retcode code) {
    return new Result(code, null);
  }

  private Result commit(ClockParkState previous, ClockParkState next) {
    player.getGameplay().clockPark(next);
    try {
      player.save();
    } catch (RuntimeException failed) {
      player.getGameplay().clockPark(previous);
      LunarCore.getLogger().debug("ClockPark state save failed", failed);
      return rejected(Retcode.SYSTEM_BUSY);
    }
    return new Result(Retcode.SUCC, next.getActive());
  }

  public Result start(int id) {
    synchronized (player) {
      var catalog = catalog();
      if (!sources(catalog)) return rejected(Retcode.REQ_PARA_INVALID);
      var state = player.getGameplay().getClockPark();
      if (state == null || !state.valid(catalog) || !ClockParkEndingRewards.valid(player, state))
        return rejected(Retcode.PLAYER_DATA_ERROR);
      var script = catalog.scripts().get(id);
      if (script == null || !script.kind().equals("Normal"))
        return rejected(Retcode.REQ_PARA_INVALID);
      var chapter = catalog.chapters().get(script.startChapter());
      if (chapter == null || chapter.rounds().isEmpty() || chapter.stories().size() != 1)
        return rejected(Retcode.REQ_PARA_INVALID);
      if (!open() || !MissionStateRecords.subCompleted(player, script.prerequisiteId()))
        return rejected(Retcode.FUNC_CHECK_FAILED);
      if (state.getActive() != null
          || player.isInBattle()
          || player.getGameplay().getCurrentMissionRaid() != 0
          || player.getGameplay().getParkourVenue().inside()
          || player.getGameplay().getRhythm().getActiveLevel() != 0
          || player.getGameplay().getRogue().getPhase() == GameplayState.RogueRun.Phase.EXPLORING)
        return rejected(Retcode.SYSTEM_BUSY);
      var next = new ClockParkState(state);
      try {
        next.start(script, catalog);
      } catch (ArithmeticException overflow) {
        return rejected(Retcode.PLAYER_DATA_ERROR);
      }
      return commit(state, next);
    }
  }

  public Result ongoing() {
    synchronized (player) {
      var catalog = catalog();
      if (catalog == null) return rejected(Retcode.REQ_PARA_INVALID);
      var state = player.getGameplay().getClockPark();
      if (state == null || !state.valid(catalog) || !ClockParkEndingRewards.valid(player, state))
        return rejected(Retcode.PLAYER_DATA_ERROR);
      return new Result(Retcode.SUCC, state.getActive());
    }
  }

  public Result quit(int id) {
    synchronized (player) {
      var catalog = catalog();
      if (catalog == null) return rejected(Retcode.REQ_PARA_INVALID);
      var state = player.getGameplay().getClockPark();
      if (state == null || !state.valid(catalog) || !ClockParkEndingRewards.valid(player, state))
        return rejected(Retcode.PLAYER_DATA_ERROR);
      if (state.getActive() == null || state.getActive().getScript() != id)
        return rejected(Retcode.REQ_PARA_INVALID);
      if ("FINISHING".equals(state.getActive().getPhase())) return rejected(Retcode.SYSTEM_BUSY);
      var next = new ClockParkState(state);
      next.quit();
      return commit(state, next);
    }
  }

  public Result finishStory(int script, int round) {
    synchronized (player) {
      var current = ongoing();
      if (current.code() != Retcode.SUCC) return current;
      if (current.run() == null
          || current.run().getScript() != script
          || current.run().getRound() != round
          || !Set.of("STORY", "FINISHING").contains(current.run().getPhase()))
        return rejected(Retcode.REQ_PARA_INVALID);
      if (catalog().chapters().get(current.run().getChapter()).ending())
        return finishEnding(current.run());
      var cards = ClockParkCards.load(Path.of(LunarCore.getConfig().getResourceDir()));
      if (cards == null) return rejected(Retcode.REQ_PARA_INVALID);
      if (!open()) return rejected(Retcode.FUNC_CHECK_FAILED);
      var state = player.getGameplay().getClockPark();
      var next = new ClockParkState(state);
      next.finishStory(cards);
      return commit(state, next);
    }
  }

  private Result finishEnding(ClockParkState.Run run) {
    var catalog = catalog();
    var chapter = catalog.chapters().get(run.getChapter());
    if (!ClockParkEndingRewards.supported(chapter, run.getScript()))
      return rejected(Retcode.FEATURE_SWITCH_CLOSED);
    if (!open()) return rejected(Retcode.FUNC_CHECK_FAILED);
    var state = player.getGameplay().getClockPark();
    boolean first = !state.reached(run.getScript()).contains(run.getChapter());
    var code = ClockParkEndingRewards.preflight(player, first, run.getScript(), run.getChapter());
    if (code != Retcode.SUCC) return rejected(code);
    if (first && "STORY".equals(run.getPhase())) {
      var pending = new ClockParkState(state);
      pending.reserveEnding();
      var saved = commit(state, pending);
      if (saved.code() != Retcode.SUCC) return saved;
      state = pending;
    }
    List<emu.lunarcore.game.inventory.GameItem> items = List.of();
    if (first) {
      var reward = ClockParkEndingRewards.claim(player);
      if (reward.code() != Retcode.SUCC) return rejected(reward.code());
      items = reward.items();
    }
    var next = new ClockParkState(state);
    var completed = next.completeEnding();
    var saved = commit(state, next);
    if (saved.code() == Retcode.SUCC) player.getMissionManager().onClockParkProgress();
    return saved.code() == Retcode.SUCC ? new Result(Retcode.SUCC, completed, items, first) : saved;
  }

  public Result placeDice(int script, int round, List<Integer> placedFaces) {
    synchronized (player) {
      var current = ongoing();
      if (current.code() != Retcode.SUCC) return current;
      if (current.run() == null
          || current.run().getScript() != script
          || current.run().getRound() != round
          || !"DICE".equals(current.run().getPhase())) return rejected(Retcode.REQ_PARA_INVALID);
      var indices = ClockParkCards.assignFaces(current.run().getDice(), placedFaces);
      if (indices == null) return rejected(Retcode.REQ_PARA_INVALID);
      var catalog = catalog();
      var cards = ClockParkCards.load(Path.of(LunarCore.getConfig().getResourceDir()));
      if (catalog == null || cards == null) return rejected(Retcode.REQ_PARA_INVALID);
      if (!open()) return rejected(Retcode.FUNC_CHECK_FAILED);
      var chapter = catalog.chapters().get(current.run().getChapter());
      ClockParkChecks checks = null;
      ClockParkSlots slots = null;
      ClockParkMiniGames games = null;
      if (current.run().getRoundIndex() + 1 == chapter.rounds().size()) {
        // A chapter's original random game must run before its checkpoint.
        if (!chapter.gameplayRounds().isEmpty()) {
          var root = Path.of(LunarCore.getConfig().getResourceDir());
          if (ClockParkSlots.supports(catalog, script, chapter.id())) {
            if ((slots = ClockParkSlots.load(root)) == null)
              return rejected(Retcode.FEATURE_SWITCH_CLOSED);
          } else if (ClockParkMiniGames.supports(catalog, script, chapter.id())) {
            if ((games = ClockParkMiniGames.load(root)) == null)
              return rejected(Retcode.FEATURE_SWITCH_CLOSED);
          } else return rejected(Retcode.FEATURE_SWITCH_CLOSED);
        }
        if (chapter.checkpoints().size() != 1) return rejected(Retcode.FEATURE_SWITCH_CLOSED);
        checks = ClockParkChecks.load(Path.of(LunarCore.getConfig().getResourceDir()));
        if (checks == null) return rejected(Retcode.REQ_PARA_INVALID);
      } else {
        var nextRound = catalog.round(chapter.rounds().get(current.run().getRoundIndex() + 1));
        if (nextRound.has("RoundType")
            && !"Normal".equals(nextRound.get("RoundType").getAsString()))
          return rejected(Retcode.FEATURE_SWITCH_CLOSED);
      }
      var state = player.getGameplay().getClockPark();
      var next = new ClockParkState(state);
      try {
        next.place(catalog, cards, indices, checks, slots, games);
      } catch (ArithmeticException overflow) {
        return rejected(Retcode.PLAYER_DATA_ERROR);
      }
      return commit(state, next);
    }
  }

  public Result finishMiniGame(int script, int round, String phase, boolean win, int score) {
    synchronized (player) {
      var current = ongoing();
      if (current.code() != Retcode.SUCC) return current;
      var run = current.run();
      if (run == null
          || run.getScript() != script
          || run.getRound() != round
          || !Set.of("MOVIE", "HANU").contains(phase)
          || !phase.equals(run.getPhase())
          || run.getMini() == null) return rejected(Retcode.REQ_PARA_INVALID);
      var stage = ClockParkMiniGames.stage(run.getChapter());
      if (!ClockParkMiniGames.legalReport(stage, win, score))
        return rejected(Retcode.REQ_PARA_INVALID);
      if (!ClockParkMiniGames.settledReport(stage, win, score, run.getMini().getRule()))
        return rejected(Retcode.FEATURE_SWITCH_CLOSED);
      if (!open()) return rejected(Retcode.FUNC_CHECK_FAILED);
      var root = Path.of(LunarCore.getConfig().getResourceDir());
      var games = ClockParkMiniGames.load(root);
      if (games == null) return rejected(Retcode.REQ_PARA_INVALID);
      var checks = ClockParkChecks.load(root);
      if (checks == null) return rejected(Retcode.REQ_PARA_INVALID);
      var previous = player.getGameplay().getClockPark();
      var next = new ClockParkState(previous);
      next.finishMiniGame(win, score, games, checks);
      return commit(previous, next);
    }
  }

  public Result slotOperation(int script, int round, boolean draw, int ticket) {
    synchronized (player) {
      var current = ongoing();
      if (current.code() != Retcode.SUCC) return current;
      var run = current.run();
      if (run == null
          || run.getScript() != script
          || run.getRound() != round
          || !"SLOT".equals(run.getPhase())
          || run.getSlot() == null
          || run.getSlot().getTicket() != ticket) return rejected(Retcode.REQ_PARA_INVALID);
      if (draw && run.getSlot().isPlayed())
        return new Result(Retcode.SUCC, run, List.of(), false, false, run.getSlot().getLotteryId());
      if (!open()) return rejected(Retcode.FUNC_CHECK_FAILED);
      var root = Path.of(LunarCore.getConfig().getResourceDir());
      var slots = ClockParkSlots.load(root);
      var checks = ClockParkChecks.load(root);
      if (slots == null || checks == null) return rejected(Retcode.REQ_PARA_INVALID);
      var previous = player.getGameplay().getClockPark();
      var next = new ClockParkState(previous);
      try {
        next.operateSlot(draw, slots, checks);
      } catch (ArithmeticException invalid) {
        return rejected(Retcode.PLAYER_DATA_ERROR);
      }
      var saved = commit(previous, next);
      return saved.code() == Retcode.SUCC && draw
          ? new Result(
              Retcode.SUCC,
              saved.run(),
              List.of(),
              false,
              false,
              saved.run().getSlot().getLotteryId())
          : saved;
    }
  }

  public Result finishCheckpoint(int script, int round) {
    synchronized (player) {
      var current = ongoing();
      if (current.code() != Retcode.SUCC) return current;
      if (current.run() == null
          || current.run().getScript() != script
          || current.run().getRound() != round
          || !"CHECKPOINT".equals(current.run().getPhase()))
        return rejected(Retcode.REQ_PARA_INVALID);
      if (Boolean.FALSE.equals(current.run().getCheckpointPassed())) {
        if (!open()) return rejected(Retcode.FUNC_CHECK_FAILED);
        var state = player.getGameplay().getClockPark();
        var next = new ClockParkState(state);
        var failure = next.completeFailure();
        var saved = commit(state, next);
        return saved.code() == Retcode.SUCC
            ? new Result(Retcode.SUCC, failure, List.of(), false, true)
            : saved;
      }
      var catalog = catalog();
      var cards = ClockParkCards.load(Path.of(LunarCore.getConfig().getResourceDir()));
      if (catalog == null || cards == null) return rejected(Retcode.REQ_PARA_INVALID);
      int card = cards.branchCard(current.run().getChapter());
      var chapter = catalog.chapters().get(current.run().getChapter());
      var branch = catalog.round(chapter.successRound());
      if (card == 0
          || branch == null
          || !branch.has("RoundType")
          || !"BranchSelect".equals(branch.get("RoundType").getAsString())
          || cards.branchTargets(card).isEmpty()
          || !chapter.next().containsAll(cards.branchTargets(card)))
        return rejected(Retcode.FEATURE_SWITCH_CLOSED);
      if (!open()) return rejected(Retcode.FUNC_CHECK_FAILED);
      var state = player.getGameplay().getClockPark();
      var next = new ClockParkState(state);
      next.finishCheckpoint(catalog, cards);
      return commit(state, next);
    }
  }

  public Result selectBranch(int script, int round, int card, int face) {
    synchronized (player) {
      var current = ongoing();
      if (current.code() != Retcode.SUCC) return current;
      if (current.run() == null
          || current.run().getScript() != script
          || current.run().getRound() != round
          || !"BRANCH".equals(current.run().getPhase())
          || !current.run().getDice().equals(List.of(face)))
        return rejected(Retcode.REQ_PARA_INVALID);
      var catalog = catalog();
      var cards = ClockParkCards.load(Path.of(LunarCore.getConfig().getResourceDir()));
      if (catalog == null || cards == null || card != cards.branchCard(current.run().getChapter()))
        return rejected(Retcode.REQ_PARA_INVALID);
      var chapter = catalog.chapters().get(cards.branchTarget(card, face));
      if (chapter == null
          || !chapter.ending() && chapter.rounds().isEmpty()
          || chapter.stories().size() != 1
          || !catalog.chapters().get(current.run().getChapter()).next().contains(chapter.id()))
        return rejected(Retcode.FEATURE_SWITCH_CLOSED);
      if (!open()) return rejected(Retcode.FUNC_CHECK_FAILED);
      var state = player.getGameplay().getClockPark();
      var next = new ClockParkState(state);
      next.selectBranch(catalog, cards, card, face);
      return commit(state, next);
    }
  }
}
