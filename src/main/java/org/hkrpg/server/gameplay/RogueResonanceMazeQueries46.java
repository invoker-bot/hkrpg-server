package org.hkrpg.server.gameplay;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Explicit finite projection of task 2847; no live battle resources are installed. */
public final class RogueResonanceMazeQueries46 {
  private RogueResonanceMazeQueries46() {}

  public record Key(int id, int level, int index) {
    public Key {
      if (id < 0 || level < 0 || index < 0)
        throw new IllegalArgumentException("Unmatched finite unsigned query key");
    }
  }

  public record Binding(String name, Key query) {
    public Binding {
      if (name == null || name.isEmpty() || query == null)
        throw new IllegalArgumentException("Invalid finite MazeBuff query binding");
      RogueResonanceVariables46.nameKey(name);
    }
  }

  public enum Reason {
    MISSING_ROW,
    MISSING_PARAMETERS,
    INVALID_INDEX,
    SOURCE_READ_FAILED
  }

  public record Failure(String name, Reason reason) {}

  public record Result(int writeRequests, List<Failure> failures) {
    public Result {
      failures = List.copyOf(failures);
    }
  }

  @FunctionalInterface
  public interface Rows {
    RogueMazeBuffTable46.Row find(int id, int level);
  }

  public static final class Task {
    private final List<Binding> queries;
    private final int scope;
    private volatile int state = 0x7777;

    public Task(List<Binding> queries, int scope) {
      if ((queries != null && queries.size() > 255) || scope < 0 || scope > 9)
        throw new IllegalArgumentException("Unmatched finite MazeBuff query task");
      this.queries = queries == null ? List.of() : List.copyOf(queries);
      this.scope = scope;
    }

    public int state() {
      return state;
    }

    public Result execute(Rows rows, RogueResonanceLookup46 context) {
      // OnTaskBegin marks DONE before querying. DONE is not evidence of a successful effect.
      state = 0x9999;
      return RogueResonanceMazeQueries46.execute(queries, scope, rows, context);
    }
  }

  public static Key sourceKey(int id, Integer level, int index) {
    return new Key(id, level == null ? 1 : level, index);
  }

  public static List<Binding> classicQueries(int eventId) {
    return switch (eventId) {
      case 41001 ->
          List.of(
              new Binding("ADF_612024_1", new Key(612024, 1, 1)),
              new Binding("ADF_612024_2", new Key(612024, 1, 2)),
              new Binding("ADF_612024_3", new Key(612024, 1, 3)),
              new Binding("ADF_612025_1", new Key(612025, 1, 1)),
              new Binding("ADF_612026_1", new Key(612026, 1, 1)),
              new Binding("ADF_612026_2", new Key(612026, 1, 2)),
              new Binding("ADF_612026_3", new Key(612026, 1, 3)),
              new Binding("ADF_612027_1", new Key(612027, 1, 1)),
              new Binding("ADF_612027_2", new Key(612027, 1, 2)));
      case 41002 ->
          List.of(
              new Binding("ADF_612224_1", new Key(612224, 1, 1)),
              new Binding("ADF_612225_1", new Key(612225, 1, 1)),
              new Binding("ADF_612226_1", new Key(612226, 1, 1)),
              new Binding("ADF_612226_2", new Key(612226, 1, 2)),
              new Binding("ADF_612227_1", new Key(612227, 1, 1)));
      case 41003 ->
          List.of(
              new Binding("ADF_612324_1", new Key(612324, 1, 1)),
              new Binding("ADF_612324_2", new Key(612324, 1, 2)),
              new Binding("ADF_612324_3", new Key(612324, 1, 3)),
              new Binding("ADF_612325_1", new Key(612325, 1, 1)),
              new Binding("ADF_612325_2", new Key(612325, 1, 2)),
              new Binding("ADF_612325_3", new Key(612325, 1, 3)),
              new Binding("ADF_612325_4", new Key(612325, 1, 4)),
              new Binding("ADF_612326_1", new Key(612326, 1, 1)),
              new Binding("ADF_612326_2", new Key(612326, 1, 2)),
              new Binding("ADF_612326_3", new Key(612326, 1, 3)),
              new Binding("ADF_612327_1", new Key(612327, 1, 1)),
              new Binding("ADF_612327_2", new Key(612327, 1, 2)));
      case 41004 ->
          List.of(
              new Binding("ADF_612424_1", new Key(612424, 1, 1)),
              new Binding("ADF_612424_2", new Key(612424, 1, 2)),
              new Binding("ADF_612425_1", new Key(612425, 1, 1)),
              new Binding("ADF_612426_1", new Key(612426, 1, 1)),
              new Binding("ADF_612426_2", new Key(612426, 1, 2)),
              new Binding("ADF_612427_1", new Key(612427, 1, 1)));
      case 41005 ->
          List.of(
              new Binding("ADF_612524_1", new Key(612524, 1, 1)),
              new Binding("ADF_612524_2", new Key(612524, 1, 2)),
              new Binding("ADF_612524_3", new Key(612524, 1, 3)),
              new Binding("ADF_612525_1", new Key(612525, 1, 1)),
              new Binding("ADF_612525_2", new Key(612525, 1, 2)),
              new Binding("ADF_612525_3", new Key(612525, 1, 3)),
              new Binding("ADF_612526_1", new Key(612526, 1, 1)),
              new Binding("ADF_612527_1", new Key(612527, 1, 1)),
              new Binding("ADF_612527_2", new Key(612527, 1, 2)));
      case 41006 ->
          List.of(
              new Binding("ADF_612124_1", new Key(612124, 1, 1)),
              new Binding("ADF_612124_2", new Key(612124, 1, 2)),
              new Binding("ADF_612124_3", new Key(612124, 1, 3)),
              new Binding("ADF_612125_1", new Key(612125, 1, 1)),
              new Binding("ADF_612125_2", new Key(612125, 1, 2)),
              new Binding("ADF_612126_1", new Key(612126, 1, 1)),
              new Binding("ADF_612126_2", new Key(612126, 1, 2)),
              new Binding("ADF_612127_1", new Key(612127, 1, 1)),
              new Binding("ADF_612127_2", new Key(612127, 1, 2)));
      case 41007 ->
          List.of(
              new Binding("ADF_612625_1", new Key(612625, 1, 1)),
              new Binding("ADF_612626_1", new Key(612626, 1, 1)),
              new Binding("ADF_612626_2", new Key(612626, 1, 2)),
              new Binding("ADF_612626_3", new Key(612626, 1, 3)),
              new Binding("ADF_612626_4", new Key(612626, 1, 4)),
              new Binding("ADF_612627_1", new Key(612627, 1, 1)),
              new Binding("ADF_612627_2", new Key(612627, 1, 2)));
      case 41008 ->
          List.of(
              new Binding("ADF_612721_1", new Key(612721, 1, 1)),
              new Binding("ADF_612722_1", new Key(612722, 1, 1)),
              new Binding("ADF_612723_1", new Key(612723, 1, 1)),
              new Binding("ADF_612723_2", new Key(612723, 1, 2)),
              new Binding("ADF_612723_3", new Key(612723, 1, 3)),
              new Binding("ADF_612724_1", new Key(612724, 1, 1)),
              new Binding("ADF_612724_2", new Key(612724, 1, 2)),
              new Binding("ADF_612725_1", new Key(612725, 1, 1)),
              new Binding("ADF_612725_2", new Key(612725, 1, 2)),
              new Binding("ADF_612726_1", new Key(612726, 1, 1)),
              new Binding("ADF_612726_2", new Key(612726, 1, 2)),
              new Binding("ADF_612727_1", new Key(612727, 1, 1)),
              new Binding("ADF_612727_2", new Key(612727, 1, 2)),
              new Binding("ADF_612727_3", new Key(612727, 1, 3)));
      case 41009 ->
          List.of(
              new Binding("ADF_612821_1", new Key(612821, 1, 1)),
              new Binding("ADF_612822_1", new Key(612822, 1, 1)),
              new Binding("ADF_612823_1", new Key(612823, 1, 1)));
      default -> List.of();
    };
  }

  public static Result execute(
      List<Binding> queries, int scope, Rows rows, RogueResonanceLookup46 context) {
    if (queries == null || queries.isEmpty()) return new Result(0, List.of());
    if (queries.size() > 255 || scope < 0 || scope > 9 || rows == null || context == null)
      throw new IllegalArgumentException("Invalid finite MazeBuff query projection");
    var bindings = List.copyOf(queries);
    var names = new HashSet<String>();
    for (var binding : bindings)
      if (!names.add(binding.name()))
        throw new IllegalArgumentException("Duplicate MazeBuff query name");
    int writes = 0;
    var failures = new ArrayList<Failure>();
    for (var binding : bindings) {
      long raw;
      var query = binding.query();
      // Only source-row resolution is caught by the matched native helper. A destination
      // lookup/write failure aborts the task, preserving earlier writes.
      try {
        var row = rows.find(query.id(), query.level());
        if (row == null) {
          failures.add(new Failure(binding.name(), Reason.MISSING_ROW));
          continue;
        }
        if (row.id() != query.id() || row.level() != query.level())
          throw new IllegalArgumentException("Mismatched explicit MazeBuff row identity");
        if (row.parameters() == null) {
          failures.add(new Failure(binding.name(), Reason.MISSING_PARAMETERS));
          continue;
        }
        if (query.index() == 0 || query.index() > row.parameters().size()) {
          failures.add(new Failure(binding.name(), Reason.INVALID_INDEX));
          continue;
        }
        raw = row.parameters().get(query.index() - 1);
      } catch (RuntimeException unresolved) {
        failures.add(new Failure(binding.name(), Reason.SOURCE_READ_FAILED));
        continue;
      }
      int key = RogueResonanceVariables46.nameKey(binding.name());
      var lookup = context.select(scope, key);
      if (lookup == null || !lookup.write(key, raw))
        throw new IllegalArgumentException("Unresolved MazeBuff query destination");
      writes++;
    }
    return new Result(writes, failures);
  }
}
