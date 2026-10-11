package org.hkrpg.server.gameplay;

import com.google.gson.Gson;
import dev.morphia.annotations.Entity;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.EntityNpc;
import java.time.Instant;
import java.util.*;
import lombok.Getter;

/** Source-bound payments and reward batches; all level counters are derived from paid receipts. */
@Entity(useDiscriminator = false)
@Getter
public final class KraterOfferingState {
  public enum Phase {
    PREPARED,
    APPLIED
  }

  private int version = 1;
  private String source;
  private List<Payment> payments = new ArrayList<>();
  private List<Claim> claims = new ArrayList<>();
  private QuestState quest;
  private Goal goal;

  @Entity(useDiscriminator = false)
  @Getter
  public static class Context {
    private int owner, entity, dimension, story;
    private long runtime, at;
    private String event;

    protected Context() {}

    Context(Player p, EntityNpc actor) {
      owner = p.getUid();
      entity = actor.getEntityId();
      dimension = actor.getScene().getDimensionId();
      story = actor.getScene().getStoryLineId();
      runtime = actor.getScene().getRuntimeId();
      at = Instant.now().getEpochSecond();
      event = UUID.randomUUID().toString();
    }

    boolean valid(KraterOfferingRules rules, Player p) {
      return owner == p.getUid()
          && entity > 0
          && runtime > 0
          && story >= 0
          && at > 0
          && uuid(event)
          && rules.original(dimension) != null;
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class Payment extends Context {
    private int before, amount, stockBefore, stockAfter;
    private Phase phase = Phase.PREPARED;

    protected Payment() {}

    Payment(Player p, EntityNpc actor, int before, int amount, int stockBefore) {
      super(p, actor);
      this.before = before;
      this.amount = amount;
      this.stockBefore = stockBefore;
      this.stockAfter = Math.subtractExact(stockBefore, amount);
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class Claim extends Context {
    private int paid, tail, playerLevel;
    private String payment;
    private boolean expConverted;
    private List<Integer> levels = new ArrayList<>();
    private Map<Integer, Integer> items = new TreeMap<>();
    private Phase phase = Phase.PREPARED;

    protected Claim() {}

    Claim(
        Player p,
        EntityNpc actor,
        Payment receipt,
        int paid,
        List<Integer> levels,
        int tail,
        Map<Integer, Integer> items) {
      super(p, actor);
      this.paid = paid;
      this.payment = receipt.getEvent();
      this.tail = tail;
      this.playerLevel = p.getLevel();
      this.expConverted = playerLevel >= emu.lunarcore.GameConstants.MAX_TRAILBLAZER_LEVEL;
      this.levels = new ArrayList<>(levels);
      this.items = new TreeMap<>(items);
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class Goal {
    private String acceptance, payment, event;
    private int paid;
    private long at, begin, end;

    protected Goal() {}

    Goal(
        QuestState state,
        Payment payment,
        int paid,
        GameplayDefinitions.Window window,
        Instant now) {
      acceptance = state.getObservationKey();
      this.payment = payment.getEvent();
      this.paid = paid;
      event = UUID.randomUUID().toString();
      at = now.getEpochSecond();
      begin = window.beginTime();
      end = window.endTime();
    }
  }

  public boolean pristine() {
    return version == 1
        && source == null
        && payments != null
        && payments.isEmpty()
        && claims != null
        && claims.isEmpty()
        && quest == null
        && goal == null;
  }

  private static boolean uuid(String value) {
    try {
      return value != null && UUID.fromString(value).toString().equals(value);
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  int paid() {
    int total = 0;
    for (var payment : payments)
      if (payment.phase == Phase.APPLIED) total = Math.addExact(total, payment.amount);
    return total;
  }

  int tails() {
    int total = 0;
    for (var claim : claims)
      if (claim.phase == Phase.APPLIED) total = Math.addExact(total, claim.tail);
    return total;
  }

  int level(KraterOfferingRules rules) {
    return rules.level(paid());
  }

  int exp(KraterOfferingRules rules) {
    return Math.subtractExact(
        Math.subtractExact(paid(), rules.spent(level(rules))),
        Math.multiplyExact(tails(), rules.cost(51)));
  }

  Set<Integer> taken() {
    var out = new TreeSet<Integer>();
    for (var claim : claims)
      if (claim.phase == Phase.APPLIED)
        for (int level : claim.levels) if (level <= 50) out.add(level);
    return out;
  }

  boolean pending() {
    return payments.stream().anyMatch(p -> p.phase == Phase.PREPARED)
        || claims.stream().anyMatch(c -> c.phase == Phase.PREPARED);
  }

  Payment lastPaid() {
    return payments.stream().filter(p -> p.phase == Phase.APPLIED).reduce((a, b) -> b).orElse(null);
  }

  private KraterOfferingState copy() {
    return new Gson().fromJson(new Gson().toJson(this), KraterOfferingState.class);
  }

  KraterOfferingState prepared(KraterOfferingRules rules, Payment payment) {
    var next = copy();
    next.source = rules.source();
    next.payments.add(payment);
    return next;
  }

  KraterOfferingState prepared(KraterOfferingRules rules, Claim claim) {
    var next = copy();
    next.source = rules.source();
    next.claims.add(claim);
    return next;
  }

  KraterOfferingState paid(String event) {
    var next = copy();
    var payment = next.payments.getLast();
    if (payment.phase != Phase.PREPARED || !Objects.equals(payment.getEvent(), event))
      throw new IllegalStateException("Different reserved payment");
    payment.phase = Phase.APPLIED;
    return next;
  }

  KraterOfferingState claimed(String event) {
    var next = copy();
    var claim = next.claims.getLast();
    if (claim.phase != Phase.PREPARED || !Objects.equals(claim.getEvent(), event))
      throw new IllegalStateException("Different reserved claim");
    claim.phase = Phase.APPLIED;
    return next;
  }

  boolean refresh(KraterOfferingRules rules, Player p, Instant now) {
    var window = rules.window(p, now);
    if (window == null || pending()) return false;
    boolean changed = false;
    if (quest == null) {
      source = rules.source();
      quest = new QuestState(KraterOfferingRules.QUEST);
      quest.captureObservationKey();
      changed = true;
    }
    if (MissionStateRecords.doing(quest, KraterOfferingRules.QUEST, 1) && level(rules) >= 6) {
      var payment = lastPaid();
      if (payment == null) return changed;
      goal = new Goal(quest, payment, paid(), window, now);
      quest.advance(1, 1, now.getEpochSecond());
      changed = true;
    }
    return changed;
  }

  boolean valid(KraterOfferingRules rules, Player p) {
    try {
      if (rules == null
          || version != 1
          || payments == null
          || claims == null
          || payments.size() > 4096
          || claims.size() > 4096) return false;
      if (source == null) {
        if (!payments.isEmpty() || !claims.isEmpty() || quest != null || goal != null) return false;
      } else if (!source.equals(rules.source())) return false;
      var events = new HashSet<String>();
      var paidReceipts = new HashMap<String, Payment>();
      int total = 0;
      boolean pending = false;
      for (int index = 0; index < payments.size(); index++) {
        var payment = payments.get(index);
        if (payment == null
            || !payment.valid(rules, p)
            || !events.add(payment.getEvent())
            || payment.before != total
            || payment.amount < 1
            || payment.stockBefore < 1
            || payment.stockBefore > 99999
            || payment.stockAfter < 0
            || payment.stockBefore - payment.stockAfter != payment.amount
            || payment.phase == null
            || (long) total + payment.amount > Integer.MAX_VALUE) return false;
        if (payment.phase == Phase.APPLIED) {
          total = Math.addExact(total, payment.amount);
          paidReceipts.put(payment.getEvent(), payment);
        } else {
          if (index + 1 != payments.size()) return false;
          pending = true;
        }
      }
      var normal = new HashSet<Integer>();
      int tail = 0;
      for (int index = 0; index < claims.size(); index++) {
        var claim = claims.get(index);
        var payment = claim == null ? null : paidReceipts.get(claim.payment);
        if (claim == null
            || !claim.valid(rules, p)
            || !events.add(claim.getEvent())
            || payment == null
            || claim.phase == null
            || claim.getAt() < payment.getAt()
            || claim.paid != Math.addExact(payment.before, payment.amount)
            || claim.levels == null
            || claim.levels.isEmpty()
            || claim.levels.size() > 51
            || new HashSet<>(claim.levels).size() != claim.levels.size()
            || claim.playerLevel < 1
            || claim.playerLevel > emu.lunarcore.GameConstants.MAX_TRAILBLAZER_LEVEL
            || claim.expConverted
                != (claim.playerLevel >= emu.lunarcore.GameConstants.MAX_TRAILBLAZER_LEVEL))
          return false;
        int level = rules.level(claim.paid);
        for (Integer requested : claim.levels) {
          if (requested == null || requested < 1 || requested > 51) return false;
          if (requested <= 50 && (requested > level || !normal.add(requested))) return false;
        }
        if (claim.levels.contains(51)) {
          int available =
              Math.subtractExact(
                  Math.subtractExact(claim.paid, rules.normalCost()),
                  Math.multiplyExact(tail, rules.cost(51)));
          if (level != 50 || claim.tail < 1 || claim.tail != available / rules.cost(51))
            return false;
          tail = Math.addExact(tail, claim.tail);
        } else if (claim.tail != 0) return false;
        if (!Objects.equals(
            claim.items, rules.recipe(claim.levels, claim.tail, claim.expConverted))) return false;
        if (claim.phase == Phase.PREPARED) {
          if (index + 1 != claims.size()) return false;
          pending = true;
        }
      }
      if (rules.id() != 6) return quest == null && goal == null;
      if (quest == null) return goal == null;
      if (!uuid(quest.getObservationKey())) return false;
      if (goal == null) return MissionStateRecords.doing(quest, KraterOfferingRules.QUEST, 1);
      var receipt = paidReceipts.get(goal.payment);
      return receipt != null
          && events.add(goal.event)
          && uuid(goal.event)
          && Objects.equals(goal.acceptance, quest.getObservationKey())
          && goal.paid == Math.addExact(receipt.before, receipt.amount)
          && rules.level(goal.paid) >= 6
          && goal.at >= receipt.getAt()
          && goal.begin > 0
          && goal.end > goal.begin
          && goal.at >= goal.begin
          && goal.at < goal.end
          && MissionStateRecords.completed(quest, KraterOfferingRules.QUEST, 1)
          && quest.getFinishTime() == goal.at;
    } catch (RuntimeException invalid) {
      return false;
    }
  }
}
