package org.hkrpg.server.gameplay;

import java.util.List;

/** Finite name selection and target qualification; actual modifier instances remain bound. */
public final class RogueModifierSelection46 {
  private RogueModifierSelection46() {}

  public record Override(String replacementName, boolean keepOriginalReference) {}

  public record Names(String lookupName, String instanceName, String referenceName) {
    public boolean referenceChanged() {
      return !referenceName.equals(instanceName);
    }
  }

  public interface World {
    int aliveState(Object target);

    int entityType(Object target);

    Boolean muteDarkTeamModifierAdd(Object target);

    int team(Object source);

    boolean containsBehaviorFlag(Object definition, int flag);
  }

  public record Qualified(
      RogueAddModifierEntry46.Request request, Names names, Object definition) {}

  public interface Bindings extends World {
    RogueAddModifierEntry46.Source source();

    List<Object> targets(String alias, int aliveMask);

    Override overrideFor(Object target, String literalName);

    String referenceModifierName(RogueAddModifierEntry46.Request request);

    Object resolveDefinition(String name);

    void addModifierAlias(String instanceName, String lookupName);

    boolean additionalRestriction(Qualified selection);

    void applyQualified(Qualified selection);

    void finished(List<Object> targets);
  }

  public static Names names(String literalName, String referenceModifierName, Override override) {
    if (literalName == null || literalName.isEmpty())
      throw new IllegalArgumentException("Missing finite literal modifier name");
    String instance =
        override != null && override.replacementName() != null
            ? override.replacementName()
            : literalName;
    String reference =
        override != null && override.keepOriginalReference() ? literalName : instance;
    return new Names(
        referenceModifierName == null ? instance : referenceModifierName, instance, reference);
  }

  public static boolean allowed(Object target, Object source, Object definition, World world) {
    if (world == null) throw new IllegalArgumentException("Missing explicit world projection");
    if (target == null) return false;
    int state = world.aliveState(target);
    if (state < 1 || state > 4) return false;
    if (world.entityType(target) != 12) return true;
    if (!Boolean.TRUE.equals(world.muteDarkTeamModifierAdd(target))) return true;
    if (source == null) throw new IllegalArgumentException("Missing source for muted battle event");
    if (world.team(source) != 2) return true;
    return definition != null && world.containsBehaviorFlag(definition, 84);
  }

  public static RogueAddModifierEntry46.Context context(Bindings bindings) {
    if (bindings == null) throw new IllegalArgumentException("Missing explicit selection bindings");
    return new RogueAddModifierEntry46.Context() {
      public RogueAddModifierEntry46.Source source() {
        return bindings.source();
      }

      public List<Object> targets(String alias, int aliveMask) {
        return bindings.targets(alias, aliveMask);
      }

      public void apply(RogueAddModifierEntry46.Request request) {
        if (request == null)
          throw new IllegalArgumentException("Missing explicit modifier request");
        var override = bindings.overrideFor(request.targetEntity(), request.literalName());
        var names = names(request.literalName(), bindings.referenceModifierName(request), override);
        var definition = bindings.resolveDefinition(names.lookupName());
        if (!allowed(request.targetEntity(), request.sourceEntity(), definition, bindings)) return;
        if (!names.lookupName().equals(names.instanceName()))
          bindings.addModifierAlias(names.instanceName(), names.lookupName());
        // The following native restriction helper dereferences the resolved configuration first.
        if (definition == null)
          throw new IllegalArgumentException("Missing resolved modifier definition");
        var qualified = new Qualified(request, names, definition);
        if (bindings.additionalRestriction(qualified)) bindings.applyQualified(qualified);
      }

      public void finished(List<Object> targets) {
        bindings.finished(targets);
      }
    };
  }

  public static RogueResonanceOnAdd46.ModifierFactory factory(Bindings bindings) {
    return RogueAddModifierEntry46.factory(context(bindings));
  }
}
