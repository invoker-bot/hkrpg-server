package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;
import lombok.Getter;

/** Opening receipts are separate from ending, score and reward receipts. */
@Entity(useDiscriminator = false)
public final class ClockParkState {
  private long sequence;
  private Run active;
  private Run lastFailure;
  private String nextCardPool = ClockParkCards.BALANCED_RULE;
  private Map<Integer, Set<Integer>> reached = new TreeMap<>();
  private Map<Integer, Map<Integer, Run>> reachedProofs = new TreeMap<>();

  public ClockParkState() {}

  ClockParkState(ClockParkState previous) {
    sequence = previous.sequence;
    active = previous.active == null ? null : new Run(previous.active);
    lastFailure = previous.lastFailure == null ? null : new Run(previous.lastFailure);
    nextCardPool = previous.nextCardPool;
    previous.reached.forEach((id, chapters) -> reached.put(id, new TreeSet<>(chapters)));
    previous.reachedProofs.forEach(
        (id, proofs) -> {
          var copies = new TreeMap<Integer, Run>();
          proofs.forEach((chapter, run) -> copies.put(chapter, new Run(run)));
          reachedProofs.put(id, copies);
        });
  }

  public Run getActive() {
    return active;
  }

  public Run getLastFailure() {
    return lastFailure;
  }

  public List<Integer> reached(int script) {
    var chapters = reached == null ? null : reached.get(script);
    return chapters == null ? List.of() : chapters.stream().sorted().toList();
  }

  public boolean valid(ClockParkCatalog catalog) {
    var sources = new ValidationSources();
    if (catalog == null
        || ClockParkCards.pool(nextCardPool) == null
        || sequence < 0
        || reached == null
        || reachedProofs == null
        || !reached.keySet().containsAll(reachedProofs.keySet())
        || reached.size() > 5
        || sequence < reached.size()) return false;
    for (var entry : reached.entrySet()) {
      var script = entry.getKey() == null ? null : catalog.scripts().get(entry.getKey());
      if (script == null || !script.kind().equals("Normal") || entry.getValue() == null)
        return false;
      var expected = new TreeSet<Integer>();
      expected.add(script.startChapter());
      var proofs = reachedProofs.getOrDefault(script.id(), Map.of());
      if (proofs == null || proofs.size() > catalog.chapters().size()) return false;
      for (var proof : proofs.entrySet()) {
        var run = proof.getValue();
        if (run == null
            || proof.getKey() == null
            || proof.getKey() != run.chapter
            || run.script != script.id()
            || !catalog.chapters().containsKey(run.chapter)
            || run.history == null
            || run.history.isEmpty()
            || run.sequence > sequence
            || !("STORY".equals(run.phase) && !catalog.chapters().get(run.chapter).ending()
                || "FINISHED".equals(run.phase) && catalog.chapters().get(run.chapter).ending())
            || run.roundIndex != 0
            || !run.valid(catalog, sources)) return false;
        expected.add(proof.getKey());
      }
      if (!entry.getValue().equals(expected)) return false;
    }
    if (sequence == 0 && !reached.isEmpty() || sequence > 0 && reached.isEmpty()) return false;
    if (lastFailure != null
        && (lastFailure.sequence > sequence
            || !"CHECKPOINT".equals(lastFailure.phase)
            || !Boolean.FALSE.equals(lastFailure.checkpointPassed)
            || !lastFailure.valid(catalog, sources)
            || !reached(lastFailure.script).containsAll(lastFailure.chapterPath()))) return false;
    return active == null
        || sequence > 0
            && active.sequence == sequence
            && !"FINISHED".equals(active.phase)
            && active.valid(catalog, sources)
            && reached(active.script).containsAll(active.reachedPath(catalog));
  }

  /** One validation shares verified immutable definitions; the next call rereads current bytes. */
  private static final class ValidationSources {
    private java.nio.file.Path root;
    private ClockParkCards cards;
    private ClockParkSlots slots;
    private ClockParkMiniGames games;
    private ClockParkChecks checks;
    private boolean cardsRead, slotsRead, gamesRead, checksRead;

    private java.nio.file.Path root() {
      if (root == null)
        root = java.nio.file.Path.of(emu.lunarcore.LunarCore.getConfig().getResourceDir());
      return root;
    }

    ClockParkCards cards() {
      if (!cardsRead) {
        cards = ClockParkCards.load(root());
        cardsRead = true;
      }
      return cards;
    }

    ClockParkSlots slots() {
      if (!slotsRead) {
        slots = ClockParkSlots.load(root(), cards());
        slotsRead = true;
      }
      return slots;
    }

    ClockParkMiniGames games() {
      if (!gamesRead) {
        games = ClockParkMiniGames.load(root(), cards());
        gamesRead = true;
      }
      return games;
    }

    ClockParkChecks checks() {
      if (!checksRead) {
        checks = ClockParkChecks.load(root());
        checksRead = true;
      }
      return checks;
    }
  }

  void start(ClockParkCatalog.Script script, ClockParkCatalog catalog) {
    sequence = Math.incrementExact(sequence);
    active = new Run(script, catalog, sequence, nextCardPool);
    reached.computeIfAbsent(script.id(), ignored -> new TreeSet<>()).add(script.startChapter());
  }

  void quit() {
    active = null;
  }

  Run completeFailure() {
    lastFailure = new Run(active);
    active = null;
    return new Run(lastFailure);
  }

  void finishStory(ClockParkCards cards) {
    active.finishStory(cards);
  }

  void place(
      ClockParkCatalog catalog,
      ClockParkCards cards,
      List<Integer> indices,
      ClockParkChecks checks,
      ClockParkSlots slots,
      ClockParkMiniGames games) {
    active.place(catalog, cards, indices, checks, slots, games);
  }

  void operateSlot(boolean draw, ClockParkSlots slots, ClockParkChecks checks) {
    if (draw) {
      active.attributeB = slots.attributeB(active.attributeB, active.slot.ticket);
      active.slot.played = true;
      active.slot.lotteryId = slots.lottery(active.slot.ticket);
    } else {
      active.slot.exited = true;
      active.phase = "CHECKPOINT";
      active.checkpoint = ClockParkSlots.stage(active.chapter).checkpoint();
      active.checkpointSource = ClockParkChecks.SOURCE;
      active.checkpointPassed =
          checks.passed(active.checkpoint, active.attributeA, active.attributeB, active.attributeC);
    }
  }

  void finishMiniGame(boolean win, int grade, ClockParkMiniGames games, ClockParkChecks checks) {
    int[] attributes =
        games.apply(
            active.chapter,
            new int[] {active.attributeA, active.attributeB, active.attributeC},
            win,
            grade,
            active.mini.rule);
    active.attributeA = attributes[0];
    active.attributeB = attributes[1];
    active.attributeC = attributes[2];
    active.mini.score = grade;
    active.mini.completed = true;
    active.mini.win = win;
    active.phase = "CHECKPOINT";
    active.checkpoint = ClockParkMiniGames.stage(active.chapter).checkpoint();
    active.checkpointSource = ClockParkChecks.SOURCE;
    active.checkpointPassed =
        checks.passed(active.checkpoint, active.attributeA, active.attributeB, active.attributeC);
  }

  void finishCheckpoint(ClockParkCatalog catalog, ClockParkCards cards) {
    active.phase = "BRANCH";
    active.round = catalog.chapters().get(active.chapter).successRound();
    active.branchRule = cards.branchRule(cards.branchCard(active.chapter));
    active.dice = List.of(java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 7));
  }

  void selectBranch(ClockParkCatalog catalog, ClockParkCards cards, int card, int face) {
    active.enterChapter(catalog, cards.branchTarget(card, face), card, face);
    if (!catalog.chapters().get(active.chapter).ending()) {
      reached.get(active.script).add(active.chapter);
      reachedProofs
          .computeIfAbsent(active.script, ignored -> new TreeMap<>())
          .putIfAbsent(active.chapter, new Run(active));
    }
  }

  public List<Run> completedEndings() {
    return reachedProofs.values().stream()
        .flatMap(v -> v.values().stream())
        .filter(run -> "FINISHED".equals(run.phase))
        .toList();
  }

  public int progress(ClockParkCatalog catalog) {
    return completedEndings().stream()
        .mapToInt(run -> catalog.chapters().get(run.chapter).rewardProgress())
        .sum();
  }

  void reserveEnding() {
    active.phase = "FINISHING";
    active.endingSource = ClockParkEndingRewards.source(active.script, active.chapter);
  }

  Run completeEnding() {
    active.phase = "FINISHED";
    active.endingSource = ClockParkEndingRewards.source(active.script, active.chapter);
    var completed = new Run(active);
    reached.get(active.script).add(active.chapter);
    reachedProofs
        .computeIfAbsent(active.script, ignored -> new TreeMap<>())
        .putIfAbsent(active.chapter, completed);
    active = null;
    return completed;
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Run {
    private int script, chapter, round, story;
    private long sequence;
    private String transaction, source, openingRule;
    // Explicit project rule for the opening only; the original tables do not establish initial
    // values.
    private int attributeA, attributeB, attributeC;
    private String phase = "STORY", cardPool, cardSource;
    private String poolPolicy;
    private int roundIndex;
    private List<Integer> dice = new ArrayList<>();
    private List<Receipt> receipts = new ArrayList<>();
    private int checkpoint;
    private Boolean checkpointPassed;
    private String checkpointSource;
    private String branchRule;
    private String endingSource;
    private List<ChapterReceipt> history = new ArrayList<>();
    private int transitionCard, transitionFace;
    private SlotProof slot;
    private MiniProof mini;

    private Run() {}

    private Run(Run previous) {
      script = previous.script;
      chapter = previous.chapter;
      round = previous.round;
      story = previous.story;
      sequence = previous.sequence;
      transaction = previous.transaction;
      source = previous.source;
      openingRule = previous.openingRule;
      attributeA = previous.attributeA;
      attributeB = previous.attributeB;
      attributeC = previous.attributeC;
      phase = previous.phase;
      cardPool = previous.cardPool;
      cardSource = previous.cardSource;
      poolPolicy = previous.poolPolicy;
      roundIndex = previous.roundIndex;
      dice = new ArrayList<>(previous.dice);
      receipts = new ArrayList<>(previous.receipts);
      checkpoint = previous.checkpoint;
      checkpointPassed = previous.checkpointPassed;
      checkpointSource = previous.checkpointSource;
      branchRule = previous.branchRule;
      endingSource = previous.endingSource;
      history = new ArrayList<>(previous.history);
      transitionCard = previous.transitionCard;
      transitionFace = previous.transitionFace;
      slot = previous.slot == null ? null : new SlotProof(previous.slot);
      mini = previous.mini == null ? null : new MiniProof(previous.mini);
    }

    public List<Integer> getDice() {
      return List.copyOf(dice);
    }

    public String getPoolRule() {
      return poolPolicy == null ? ClockParkCards.POOL_RULE : poolPolicy;
    }

    public List<Integer> chapterPath() {
      var path = new ArrayList<Integer>();
      for (var receipt : history) path.add(receipt.chapter);
      path.add(chapter);
      return List.copyOf(path);
    }

    private List<Integer> reachedPath(ClockParkCatalog catalog) {
      var path = new ArrayList<>(chapterPath());
      if (catalog.chapters().get(chapter).ending()) path.removeLast();
      return path;
    }

    private void enterChapter(ClockParkCatalog catalog, int target, int card, int face) {
      history.add(new ChapterReceipt(this, card, face));
      transitionCard = card;
      transitionFace = face;
      chapter = target;
      var row = catalog.chapters().get(target);
      round = row.ending() ? 0 : row.rounds().getFirst();
      story = row.stories().getFirst();
      source = catalog.stories().get(story).fingerprint();
      phase = "STORY";
      roundIndex = 0;
      dice = List.of();
      receipts = new ArrayList<>();
      cardPool = cardSource = checkpointSource = branchRule = null;
      checkpoint = 0;
      checkpointPassed = null;
      slot = null;
      mini = null;
    }

    private void roll() {
      var random = java.util.concurrent.ThreadLocalRandom.current();
      dice = List.of(random.nextInt(1, 7), random.nextInt(1, 7), random.nextInt(1, 7));
    }

    private void finishStory(ClockParkCards cards) {
      phase = "DICE";
      cardPool = getPoolRule();
      cardSource = cards.fingerprint();
      roll();
    }

    private void place(
        ClockParkCatalog catalog,
        ClockParkCards cards,
        List<Integer> indices,
        ClockParkChecks checks,
        ClockParkSlots slots,
        ClockParkMiniGames games) {
      int[] attributes =
          cards.apply(
              script, new int[] {attributeA, attributeB, attributeC}, dice, indices, getPoolRule());
      receipts.add(new Receipt(round, dice, indices));
      attributeA = attributes[0];
      attributeB = attributes[1];
      attributeC = attributes[2];
      roundIndex = Math.incrementExact(roundIndex);
      var definition = catalog.chapters().get(chapter);
      if (roundIndex == definition.rounds().size()) {
        if (slots != null) {
          slot = new SlotProof(slots, chapter);
          phase = "SLOT";
          round = slot.round;
          dice = List.of();
          return;
        }
        if (games != null) {
          mini = new MiniProof(games, chapter);
          phase = ClockParkMiniGames.stage(chapter).phase();
          round = mini.round;
          dice = List.of();
          return;
        }
        checkpoint = definition.checkpoints().getFirst();
        checkpointPassed =
            Objects.requireNonNull(checks).passed(checkpoint, attributeA, attributeB, attributeC);
        checkpointSource = ClockParkChecks.SOURCE;
        phase = "CHECKPOINT";
        dice = List.of();
      } else {
        round = definition.rounds().get(roundIndex);
        roll();
      }
    }

    private Run(
        ClockParkCatalog.Script definition,
        ClockParkCatalog catalog,
        long sequence,
        String policy) {
      script = definition.id();
      chapter = definition.startChapter();
      var row = catalog.chapters().get(chapter);
      round = row.rounds().getFirst();
      story = row.stories().getFirst();
      source = catalog.stories().get(story).fingerprint();
      transaction = UUID.randomUUID().toString();
      openingRule = "hkrpg.clockpark.opening-zero.v1";
      poolPolicy = policy;
      this.sequence = sequence;
    }

    boolean valid(ClockParkCatalog catalog) {
      return valid(catalog, new ValidationSources());
    }

    private boolean valid(ClockParkCatalog catalog, ValidationSources sources) {
      try {
        var definition = catalog.scripts().get(script);
        if (definition == null || history == null || history.size() >= catalog.chapters().size())
          return false;
        int expected = definition.startChapter();
        int[] attributes = {0, 0, 0};
        var cards = history.isEmpty() ? null : sources.cards();
        var visited = new HashSet<Integer>();
        for (var receipt : history) {
          if (receipt == null
              || cards == null
              || receipt.chapter != expected
              || !visited.add(expected)) return false;
          var previous = receipt.asRun(this);
          if (!previous.validPosition(catalog, attributes, sources)
              || !"BRANCH".equals(previous.phase)
              || receipt.card != cards.branchCard(previous.chapter)
              || !previous.dice.equals(List.of(receipt.face))) return false;
          expected = cards.branchTarget(receipt.card, receipt.face);
          if (!catalog.chapters().get(previous.chapter).next().contains(expected)) return false;
          attributes = new int[] {previous.attributeA, previous.attributeB, previous.attributeC};
        }
        if (chapter != expected || visited.contains(chapter)) return false;
        if (history.isEmpty()
            ? transitionCard != 0 || transitionFace != 0
            : transitionCard != history.getLast().card || transitionFace != history.getLast().face)
          return false;
        return validPosition(catalog, attributes, sources);
      } catch (RuntimeException invalid) {
        return false;
      }
    }

    private boolean validPosition(
        ClockParkCatalog catalog, int[] initialAttributes, ValidationSources sources) {
      var definition = catalog.scripts().get(script);
      var row = catalog.chapters().get(chapter);
      var graph = catalog.stories().get(story);
      try {
        return definition != null
            && ClockParkCards.pool(getPoolRule()) != null
            && definition.kind().equals("Normal")
            && row != null
            && (row.ending()
                ? row.rounds().isEmpty()
                    && row.gameplayRounds().isEmpty()
                    && row.checkpoints().isEmpty()
                    && row.next().equals(List.of(0))
                : !row.rounds().isEmpty())
            && row.stories().size() == 1
            && roundIndex >= 0
            && (row.ending()
                ? Set.of("STORY", "FINISHING", "FINISHED").contains(phase)
                    && round == 0
                    && roundIndex == 0
                : Set.of("FINISHING", "FINISHED").contains(phase)
                    ? false
                    : ("CHECKPOINT".equals(phase)
                            || "BRANCH".equals(phase)
                            || "SLOT".equals(phase)
                            || "MOVIE".equals(phase)
                            || "HANU".equals(phase))
                        ? roundIndex == row.rounds().size()
                            && round
                                == ("BRANCH".equals(phase)
                                    ? row.successRound()
                                    : slot != null
                                        ? slot.round
                                        : mini != null ? mini.round : row.rounds().getLast())
                        : roundIndex < row.rounds().size() && round == row.rounds().get(roundIndex))
            && story == row.stories().getFirst()
            && graph != null
            && graph.fingerprint().equals(source)
            && sequence > 0
            && UUID.fromString(transaction).toString().equals(transaction)
            && "hkrpg.clockpark.opening-zero.v1".equals(openingRule)
            && progressValid(catalog, initialAttributes, sources);
      } catch (IllegalArgumentException | NullPointerException invalid) {
        return false;
      }
    }

    private boolean progressValid(
        ClockParkCatalog catalog, int[] initialAttributes, ValidationSources sources) {
      if (dice == null || receipts == null || receipts.size() != roundIndex) return false;
      boolean branchPhase = "BRANCH".equals(phase);
      boolean checkpointPhase = "CHECKPOINT".equals(phase) || branchPhase;
      boolean slotPhase = "SLOT".equals(phase);
      boolean gamePhase = "MOVIE".equals(phase) || "HANU".equals(phase);
      if (slot != null && mini != null
          || gamePhase != (mini != null && !mini.completed)
          || mini != null && !gamePhase && !checkpointPhase) return false;
      if (slotPhase != (slot != null && !slot.exited)
          || slot != null && !slotPhase && !checkpointPhase) return false;
      if (!branchPhase && branchRule != null) return false;
      if (!checkpointPhase
          && (checkpoint != 0 || checkpointPassed != null || checkpointSource != null))
        return false;
      boolean settled = "FINISHING".equals(phase) || "FINISHED".equals(phase);
      var expectedEndingSource = ClockParkEndingRewards.source(script, chapter);
      if (settled
          ? expectedEndingSource == null || !expectedEndingSource.equals(endingSource)
          : endingSource != null) return false;
      if ("STORY".equals(phase) || settled)
        return roundIndex == 0
            && dice.isEmpty()
            && cardPool == null
            && cardSource == null
            && Arrays.equals(new int[] {attributeA, attributeB, attributeC}, initialAttributes);
      if (!("DICE".equals(phase) || checkpointPhase || slotPhase || gamePhase)
          || !getPoolRule().equals(cardPool)
          || (branchPhase
              ? dice.size() != 1
                  || dice.getFirst() == null
                  || dice.getFirst() < 1
                  || dice.getFirst() > 6
              : checkpointPhase || slotPhase || gamePhase
                  ? !dice.isEmpty()
                  : !ClockParkCards.facesValid(dice))) return false;
      var cards = sources.cards();
      if (cards == null || !cards.fingerprint().equals(cardSource)) return false;
      int[] attributes = initialAttributes.clone();
      var rounds = catalog.chapters().get(chapter).rounds();
      try {
        for (int i = 0; i < receipts.size(); i++) {
          var receipt = receipts.get(i);
          if (receipt == null || receipt.round != rounds.get(i)) return false;
          attributes =
              cards.apply(script, attributes, receipt.faces, receipt.indices, getPoolRule());
        }
        if (slot != null) {
          var slots = sources.slots();
          if (!ClockParkSlots.supports(catalog, script, chapter)
              || roundIndex != rounds.size()
              || slots == null
              || !slot.valid(slots, chapter)) return false;
          if (slot.played) attributes[1] = slots.attributeB(attributes[1], slot.ticket);
        }
        if (mini != null) {
          var games = sources.games();
          var stage = ClockParkMiniGames.stage(chapter);
          if (!ClockParkMiniGames.supports(catalog, script, chapter)
              || roundIndex != rounds.size()
              || games == null
              || !mini.valid(games, chapter)
              || gamePhase && !stage.phase().equals(phase)) return false;
          if (mini.completed)
            attributes = games.apply(chapter, attributes, mini.win, mini.score, mini.rule);
        }
        if (checkpointPhase) {
          var definition = catalog.chapters().get(chapter);
          var checks = sources.checks();
          if ((!definition.gameplayRounds().isEmpty() && slot == null && mini == null)
              || !definition.checkpoints().equals(List.of(checkpoint))
              || checks == null
              || !ClockParkChecks.SOURCE.equals(checkpointSource)
              || checkpointPassed == null
              || checkpointPassed
                  != checks.passed(checkpoint, attributes[0], attributes[1], attributes[2]))
            return false;
          if (branchPhase
              && (!Boolean.TRUE.equals(checkpointPassed)
                  || !cards.branchRule(cards.branchCard(chapter)).equals(branchRule)
                  || cards.branchCard(chapter) == 0
                  || !"BranchSelect".equals(catalog.round(round).get("RoundType").getAsString())))
            return false;
        }
      } catch (RuntimeException invalid) {
        return false;
      }
      return attributeA == attributes[0]
          && attributeB == attributes[1]
          && attributeC == attributes[2];
    }
  }

  /** A flat immutable-by-convention snapshot avoids recursive Morphia codec registration. */
  @Entity(useDiscriminator = false)
  public static final class ChapterReceipt {
    private int chapter,
        round,
        story,
        attributeA,
        attributeB,
        attributeC,
        roundIndex,
        checkpoint,
        card,
        face;
    private String source, phase, cardPool, cardSource, checkpointSource, branchRule;
    private Boolean checkpointPassed;
    private List<Integer> dice;
    private List<Receipt> receipts;
    private SlotProof slot;
    private MiniProof mini;

    private ChapterReceipt() {}

    private ChapterReceipt(Run run, int card, int face) {
      chapter = run.chapter;
      round = run.round;
      story = run.story;
      source = run.source;
      attributeA = run.attributeA;
      attributeB = run.attributeB;
      attributeC = run.attributeC;
      roundIndex = run.roundIndex;
      checkpoint = run.checkpoint;
      checkpointPassed = run.checkpointPassed;
      phase = run.phase;
      cardPool = run.cardPool;
      cardSource = run.cardSource;
      checkpointSource = run.checkpointSource;
      branchRule = run.branchRule;
      dice = List.copyOf(run.dice);
      receipts = List.copyOf(run.receipts);
      slot = run.slot == null ? null : new SlotProof(run.slot);
      mini = run.mini == null ? null : new MiniProof(run.mini);
      this.card = card;
      this.face = face;
    }

    private Run asRun(Run owner) {
      var run = new Run();
      run.script = owner.script;
      run.sequence = owner.sequence;
      run.transaction = owner.transaction;
      run.openingRule = owner.openingRule;
      run.chapter = chapter;
      run.round = round;
      run.story = story;
      run.source = source;
      run.attributeA = attributeA;
      run.attributeB = attributeB;
      run.attributeC = attributeC;
      run.roundIndex = roundIndex;
      run.checkpoint = checkpoint;
      run.checkpointPassed = checkpointPassed;
      run.phase = phase;
      run.cardPool = cardPool;
      run.cardSource = cardSource;
      run.poolPolicy = owner.poolPolicy;
      run.checkpointSource = checkpointSource;
      run.branchRule = branchRule;
      run.dice = dice;
      run.receipts = receipts;
      run.slot = slot == null ? null : new SlotProof(slot);
      run.mini = mini == null ? null : new MiniProof(mini);
      return run;
    }
  }

  /** Persist the owned ticket before exposing it; cached draw replies never mutate this proof. */
  @Entity(useDiscriminator = false)
  @Getter
  public static final class SlotProof {
    private int round, card, face, ticket, lotteryId;
    private String rule, source;
    private boolean played, exited;

    private SlotProof() {}

    private SlotProof(ClockParkSlots slots, int chapter) {
      var stage = ClockParkSlots.stage(chapter);
      round = stage.round();
      card = stage.card();
      face = 1;
      ticket = java.util.concurrent.ThreadLocalRandom.current().nextInt(1000);
      rule = ClockParkSlots.rule(chapter);
      source = slots.fingerprint();
    }

    private SlotProof(SlotProof previous) {
      round = previous.round;
      card = previous.card;
      face = previous.face;
      ticket = previous.ticket;
      lotteryId = previous.lotteryId;
      rule = previous.rule;
      source = previous.source;
      played = previous.played;
      exited = previous.exited;
    }

    private boolean valid(ClockParkSlots slots, int chapter) {
      var stage = ClockParkSlots.stage(chapter);
      return stage != null
          && round == stage.round()
          && card == stage.card()
          && face == 1
          && ticket >= 0
          && ticket < 1000
          && ClockParkSlots.rule(chapter).equals(rule)
          && slots.fingerprint().equals(source)
          && lotteryId == (played ? slots.lottery(ticket) : 0);
    }
  }

  /** Persisted original card and result retain the policy that created the waiting game. */
  @Entity(useDiscriminator = false)
  @Getter
  public static final class MiniProof {
    private int round, card, face, score;
    private String rule, source;
    private boolean completed, win;

    private MiniProof() {}

    private MiniProof(ClockParkMiniGames games, int chapter) {
      var stage = ClockParkMiniGames.stage(chapter);
      round = stage.round();
      card = stage.card();
      face = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 7);
      rule = ClockParkMiniGames.RULE;
      source = games.fingerprint();
    }

    private MiniProof(MiniProof previous) {
      round = previous.round;
      card = previous.card;
      face = previous.face;
      score = previous.score;
      rule = previous.rule;
      source = previous.source;
      completed = previous.completed;
      win = previous.win;
    }

    private boolean valid(ClockParkMiniGames games, int chapter) {
      var stage = ClockParkMiniGames.stage(chapter);
      return stage != null
          && round == stage.round()
          && card == stage.card()
          && face >= 1
          && face <= 6
          && ClockParkMiniGames.knownRule(rule)
          && games.fingerprint(rule).equals(source)
          && (completed
              ? ClockParkMiniGames.settledReport(stage, win, score, rule)
              : !win && score == 0);
    }
  }

  @Entity(useDiscriminator = false)
  public static final class Receipt {
    private int round;
    private List<Integer> faces, indices;

    private Receipt() {}

    private Receipt(int round, List<Integer> faces, List<Integer> indices) {
      this.round = round;
      this.faces = List.copyOf(faces);
      this.indices = List.copyOf(indices);
    }
  }
}
