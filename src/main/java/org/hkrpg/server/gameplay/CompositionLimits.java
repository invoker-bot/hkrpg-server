package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.data.excel.ItemComposeExcel;
import emu.lunarcore.game.inventory.GameItem;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import lombok.Getter;

/**
 * Per-recipe quota and one unresolved actual debit; refresh uses this server's China 04:00 policy.
 */
@Entity(useDiscriminator = false)
@Getter
public final class CompositionLimits {
  private Map<Integer, Window> windows = new HashMap<>();
  private Attempt lastAttempt;

  public Map<Integer, Window> getWindows() {
    return windows == null ? Map.of() : Collections.unmodifiableMap(windows);
  }

  public boolean pending() {
    return lastAttempt != null && !"COMPLETE".equals(lastAttempt.phase);
  }

  public static boolean supported(ItemComposeExcel recipe) {
    if (recipe == null
        || recipe.getLimitTypeParam() == null
        || !recipe.getLimitTypeParam().isEmpty()) return false;
    return "NotLimit".equals(recipe.getLimitType()) && recipe.getLimitValue() == 0
        || Set.of("Weekly", "Monthly").contains(Objects.toString(recipe.getLimitType(), ""))
            && recipe.getLimitValue() > 0
            && recipe.getFormulaRequire() == 0;
  }

  public static boolean periodic(ItemComposeExcel recipe) {
    return supported(recipe) && !"NotLimit".equals(recipe.getLimitType());
  }

  public static long period(String type, Instant now) {
    var date = LocalDate.ofEpochDay(DailyManager.day(Objects.requireNonNull(now)));
    return switch (type) {
      case "Weekly" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay();
      case "Monthly" -> date.withDayOfMonth(1).toEpochDay();
      default -> throw new IllegalArgumentException("Unknown composition period");
    };
  }

  public OptionalInt used(ItemComposeExcel recipe, Instant now) {
    if (!supported(recipe)) return OptionalInt.empty();
    if (!periodic(recipe))
      return getWindows().containsKey(recipe.getId()) ? OptionalInt.empty() : OptionalInt.of(0);
    try {
      long current = period(recipe.getLimitType(), now);
      if (!getWindows().containsKey(recipe.getId())) return OptionalInt.of(0);
      var saved = getWindows().get(recipe.getId());
      if (saved == null
          || !recipe.getLimitType().equals(saved.type)
          || saved.used < 0
          || saved.used > recipe.getLimitValue()
          || period(
                  saved.type,
                  LocalDate.ofEpochDay(saved.start)
                      .atTime(4, 0)
                      .atOffset(ZoneOffset.ofHours(8))
                      .toInstant())
              != saved.start
          || current < saved.start) return OptionalInt.empty();
      return OptionalInt.of(current == saved.start ? saved.used : 0);
    } catch (DateTimeException | NullPointerException | IllegalArgumentException invalid) {
      return OptionalInt.empty();
    }
  }

  public boolean available(ItemComposeExcel recipe, int count, Instant now) {
    if (pending()
        || count <= 0
        || lastAttempt != null
            && (lastAttempt.sequence < 1 || lastAttempt.sequence == Long.MAX_VALUE)) return false;
    var used = used(recipe, now);
    return used.isPresent()
        && (!periodic(recipe) || (long) used.getAsInt() + count <= recipe.getLimitValue());
  }

  public Attempt reserve(
      ItemComposeExcel recipe, int count, Instant now, Map<Integer, Integer> costs, int coins) {
    if (!periodic(recipe) || !available(recipe, count, now))
      throw new IllegalStateException("Composition quota not prepared");
    long start = period(recipe.getLimitType(), now);
    int before = used(recipe, now).orElseThrow();
    if (windows == null) windows = new HashMap<>();
    var window = windows.get(recipe.getId());
    if (window == null || window.start < start) {
      window = new Window(recipe.getLimitType(), start);
      windows.put(recipe.getId(), window);
    }
    long sequence = lastAttempt == null ? 1 : Math.addExact(lastAttempt.sequence, 1);
    lastAttempt = new Attempt(sequence, recipe, count, start, before, costs, coins);
    return lastAttempt;
  }

  public void debited(Attempt attempt) {
    if (attempt != lastAttempt || !"RESERVED".equals(attempt.phase))
      throw new IllegalStateException("Composition debit was not reserved");
    attempt.phase = "DEBITED";
  }

  public Attempt reserveRelics(
      ItemComposeExcel recipe,
      List<GameItem> outputs,
      Instant now,
      Map<Integer, Integer> costs,
      int coins) {
    if (recipe == null
        || recipe.getFormulaType() != ItemComposeExcel.FormulaType.SelectedRelic
        || !"NotLimit".equals(recipe.getLimitType())
        || outputs == null
        || outputs.isEmpty()
        || !available(recipe, outputs.size(), now))
      throw new IllegalStateException("Relic composition was not prepared");
    long sequence = lastAttempt == null ? 1 : Math.addExact(lastAttempt.sequence, 1);
    var attempt = new Attempt(sequence, recipe, outputs.size(), 0, 0, costs, coins);
    attempt.output = outputs.getFirst().getItemId();
    for (var item : outputs) {
      if (item.getCount() != 1 || item.getItemId() != attempt.output)
        throw new IllegalStateException("Relic composition needs unique source outputs");
      attempt.plannedRelics.add(new RelicOutput(item));
    }
    lastAttempt = attempt;
    return attempt;
  }

  public void settle(Attempt attempt, List<GameItem> received, boolean complete) {
    if (attempt != lastAttempt || !"DEBITED".equals(attempt.phase))
      throw new IllegalStateException("Composition grant was not debited");
    for (var item : received) {
      attempt.received.merge(item.getItemId(), item.getCount(), Math::addExact);
      if (!attempt.getPlannedRelics().isEmpty()) attempt.receivedRelics.add(new RelicOutput(item));
    }
    if (!complete) {
      attempt.phase = "PARTIAL";
      return;
    }
    if (!attempt.getPlannedRelics().isEmpty()) {
      if (!"NotLimit".equals(attempt.type)
          || attempt.limit != 0
          || attempt.before != 0
          || attempt.start != 0
          || attempt.count != attempt.getPlannedRelics().size()
          || !attempt.received.equals(Map.of(attempt.output, attempt.count))
          || received.size() != attempt.count)
        throw new IllegalStateException("Relic composition cannot confirm actual output");
      for (int i = 0; i < received.size(); i++)
        if (!attempt.plannedRelics.get(i).matches(received.get(i)))
          throw new IllegalStateException("Relic composition affixes were not delivered");
      attempt.phase = "COMPLETE";
      return;
    }
    var saved = getWindows().get(attempt.recipe);
    if (saved == null
        || !saved.type.equals(attempt.type)
        || saved.start != attempt.start
        || saved.used != attempt.before
        || attempt.count <= 0
        || (long) saved.used + attempt.count > attempt.limit
        || !attempt.received.equals(Map.of(attempt.output, attempt.count)))
      throw new IllegalStateException(
          "Composition quota cannot be confirmed without actual output");
    saved.used += attempt.count;
    attempt.phase = "COMPLETE";
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Window {
    private String type;
    private long start;
    private int used;

    protected Window() {}

    private Window(String type, long start) {
      this.type = type;
      this.start = start;
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Attempt {
    private long sequence, start;
    private int recipe, output, count, before, limit, coins;
    private String type, phase = "RESERVED";
    private Map<Integer, Integer> costs = new TreeMap<>(), received = new TreeMap<>();
    private List<RelicOutput> plannedRelics = new ArrayList<>(), receivedRelics = new ArrayList<>();

    protected Attempt() {}

    private Attempt(
        long sequence,
        ItemComposeExcel recipe,
        int count,
        long start,
        int before,
        Map<Integer, Integer> costs,
        int coins) {
      this.sequence = sequence;
      this.recipe = recipe.getId();
      this.output = recipe.getItemID();
      this.count = count;
      this.type = recipe.getLimitType();
      this.start = start;
      this.before = before;
      this.limit = recipe.getLimitValue();
      this.coins = coins;
      this.costs.putAll(costs);
    }

    public Map<Integer, Integer> getCosts() {
      return costs == null ? Map.of() : Collections.unmodifiableMap(costs);
    }

    public Map<Integer, Integer> getReceived() {
      return received == null ? Map.of() : Collections.unmodifiableMap(received);
    }

    public List<RelicOutput> getPlannedRelics() {
      return plannedRelics == null ? List.of() : Collections.unmodifiableList(plannedRelics);
    }

    public List<RelicOutput> getReceivedRelics() {
      return receivedRelics == null ? List.of() : Collections.unmodifiableList(receivedRelics);
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static final class RelicOutput {
    private int item, main;
    private String storageId;
    private Map<Integer, Integer> steps = new TreeMap<>();

    protected RelicOutput() {}

    private RelicOutput(GameItem value) {
      item = value.getItemId();
      main = value.getMainAffix();
      storageId = value.getId() == null ? null : value.getId().toHexString();
      if (value.getSubAffixes() == null)
        throw new IllegalStateException("Relic has no sub affixes");
      for (var affix : value.getSubAffixes()) {
        if (affix.getCount() != 1 || steps.put(affix.getId(), affix.getStep()) != null)
          throw new IllegalStateException("Initial relic affixes are not unique");
      }
    }

    public Map<Integer, Integer> getSteps() {
      return steps == null ? Map.of() : Collections.unmodifiableMap(steps);
    }

    public boolean matches(GameItem value) {
      var actual = new RelicOutput(value);
      return value.getCount() == 1
          && item == actual.item
          && main == actual.main
          && getSteps().equals(actual.getSteps());
    }
  }
}
