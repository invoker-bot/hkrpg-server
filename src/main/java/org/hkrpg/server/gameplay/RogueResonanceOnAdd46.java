package org.hkrpg.server.gameplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** Finite source OnAdd compiler; modifier tasks and live entities require explicit factories. */
public final class RogueResonanceOnAdd46 {
  private RogueResonanceOnAdd46() {}

  public sealed interface Command permits Query, Create, Modifier {
    Boolean taskEnabled();

    int taskId();

    default boolean enabled() {
      return taskEnabled() == null || taskEnabled();
    }
  }

  public record Query(
      Boolean taskEnabled, List<RogueResonanceMazeQueries46.Binding> queries, int scope)
      implements Command {
    public Query {
      if (queries == null || queries.size() > 255 || scope < 0 || scope > 9)
        throw new IllegalArgumentException("Invalid finite query command");
      queries = List.copyOf(queries);
    }

    public int taskId() {
      return 2847;
    }
  }

  public record Create(
      Boolean taskEnabled,
      int eventId,
      int team,
      int totalDamageTeam,
      List<RogueResonanceEventParameters46.Binding> parameters)
      implements Command {
    public Create {
      if (eventId <= 0
          || team < 0
          || team > 5
          || totalDamageTeam < 0
          || totalDamageTeam > 5
          || parameters == null
          || parameters.size() > 255)
        throw new IllegalArgumentException("Invalid finite event command");
      parameters = List.copyOf(parameters);
    }

    public int taskId() {
      return 1122;
    }
  }

  /** Only the six original Caster/literal configurations, not every AddModifier field. */
  public record Modifier(Boolean taskEnabled, String targetAlias, String literalName)
      implements Command {
    public Modifier {
      if (!"Caster".equals(targetAlias) || literalName == null || literalName.isEmpty())
        throw new IllegalArgumentException("Unmatched finite modifier target or literal");
    }

    public int taskId() {
      return 50;
    }
  }

  public record CreationRequest(
      int eventId,
      int team,
      int totalDamageTeam,
      RogueBattleEventTable46.Prepared prepared,
      Map<Integer, Long> parameters) {
    public CreationRequest {
      if (team < 0
          || team > 5
          || totalDamageTeam < 0
          || totalDamageTeam > 5
          || prepared == null
          || prepared.event() == null
          || prepared.event().id() != eventId
          || parameters == null)
        throw new IllegalArgumentException("Mismatched explicit event request");
      parameters = Map.copyOf(parameters);
    }
  }

  @FunctionalInterface
  public interface Creation {
    Object create(CreationRequest request, Consumer<Object> beforeCreation);
  }

  @FunctionalInterface
  public interface ModifierFactory {
    RogueTaskSequence46.Task create(Modifier command);
  }

  /** No sources, contexts, entities or modifier runtime are synthesized by the compiler. */
  public record Bindings(
      RogueResonanceLookup46 context,
      RogueResonanceMazeQueries46.Rows rows,
      Map<Integer, RogueBattleEventTable46.Config> events,
      int currentWaveStageId,
      IntFunction<RogueBattleEventTable46.WaveStage> stages,
      Creation creation,
      RogueBattleEventLifecycle46.Frames frames,
      ModifierFactory modifiers) {
    public Bindings {
      if (currentWaveStageId < 0) throw new IllegalArgumentException("Invalid current wave stage");
      if (events != null) events = Map.copyOf(events);
    }
  }

  public record Compiled(List<Command> enabledCommands, RogueTaskSequence46.Sequence sequence) {
    public Compiled {
      if (sequence == null)
        throw new IllegalArgumentException("Missing explicit compiled sequence");
      enabledCommands = List.copyOf(enabledCommands);
    }
  }

  public static List<Command> classicCommands(int eventId) {
    var definition =
        RogueResonanceClassicEvents46.find(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown original classic event"));
    var commands = new ArrayList<Command>();
    commands.add(new Query(null, RogueResonanceMazeQueries46.classicQueries(eventId), 0));
    commands.add(new Create(null, eventId, 3, 1, definition.parameterBindings()));
    String name =
        switch (eventId) {
          case 41001 -> "StageAbility_612020_Modifier";
          case 41002 -> "StageAbility_612220_Modifier";
          case 41003 -> "StageAbility_612320_Modifier";
          case 41004 -> "StageAbility_612420_Modifier";
          case 41005 -> "StageAbility_612520_Modifier";
          case 41008 -> "MAvatar_RogueBattleevent127_AimTarget";
          default -> null;
        };
    if (name != null) commands.add(new Modifier(null, "Caster", name));
    return List.copyOf(commands);
  }

  public static Compiled compile(List<? extends Command> commands, Bindings bindings) {
    if (commands == null || commands.size() > 512 || bindings == null)
      throw new IllegalArgumentException("Invalid finite source task compilation");
    List<Command> enabled;
    try {
      enabled = List.<Command>copyOf(commands).stream().filter(Command::enabled).toList();
    } catch (NullPointerException invalid) {
      throw new IllegalArgumentException("Missing finite source command", invalid);
    }
    // Project preflight: reject missing explicit bindings before allocating modifier tasks.
    for (var command : enabled) {
      if (command instanceof Query && (bindings.context() == null || bindings.rows() == null))
        throw new IllegalArgumentException("Missing explicit query bindings");
      if (command instanceof Create
          && (bindings.context() == null
              || bindings.events() == null
              || bindings.creation() == null))
        throw new IllegalArgumentException("Missing explicit event bindings");
      if (command instanceof Modifier && bindings.modifiers() == null)
        throw new IllegalArgumentException("Missing explicit modifier task factory");
    }
    var children = new ArrayList<RogueTaskSequence46.Task>();
    for (var command : enabled) {
      if (command instanceof Query query) children.add(new QueryTask(query, bindings));
      else if (command instanceof Create create) children.add(new CreationTask(create, bindings));
      else if (command instanceof Modifier modifier) {
        var task = bindings.modifiers().create(modifier);
        // A missing implementation must not silently omit an original task.
        if (task == null) throw new IllegalArgumentException("Modifier factory returned no task");
        children.add(task);
      }
    }
    return new Compiled(enabled, new RogueTaskSequence46.Sequence(children, false));
  }

  private static final class QueryTask implements RogueTaskSequence46.Task {
    private final Query config;
    private final Bindings bindings;
    private RogueResonanceMazeQueries46.Task task;

    private QueryTask(Query config, Bindings bindings) {
      this.config = config;
      this.bindings = bindings;
      reset();
    }

    public int state() {
      return task.state();
    }

    public void begin() {
      task.execute(bindings.rows(), bindings.context());
    }

    public void tick(float delta) {}

    public void reset() {
      task = new RogueResonanceMazeQueries46.Task(config.queries(), config.scope());
    }
  }

  private static final class CreationTask implements RogueTaskSequence46.Task {
    private final Create config;
    private final Bindings bindings;
    private RogueBattleEventLifecycle46.Task task;

    private CreationTask(Create config, Bindings bindings) {
      this.config = config;
      this.bindings = bindings;
      reset();
    }

    public int state() {
      return task.state();
    }

    public void begin() {
      task.begin(
          bindings.events(),
          bindings.currentWaveStageId(),
          bindings.stages(),
          (id, prepared, parameters, before) ->
              bindings
                  .creation()
                  .create(
                      new CreationRequest(
                          id, config.team(), config.totalDamageTeam(), prepared, parameters),
                      before));
    }

    public void tick(float delta) {}

    public void reset() {
      task =
          new RogueBattleEventLifecycle46.Task(
              new RogueBattleEventLifecycle46.Configuration(
                  RogueResonanceDynamicCell46.Scalar.fixed((long) config.eventId() << 33),
                  config.parameters(),
                  null,
                  null),
              bindings.context(),
              bindings.frames());
    }

    public void dispose() {
      task.dispose();
    }
  }
}
