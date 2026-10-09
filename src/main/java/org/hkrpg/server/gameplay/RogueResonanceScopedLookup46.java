package org.hkrpg.server.gameplay;

/** Explicit fixed-container projections of the matched native context lookup remainder. */
public final class RogueResonanceScopedLookup46 implements RogueResonanceLookup46 {
  public record Ability(
      RogueResonanceVariables46 values, RogueResonanceVariables46 casterCustomData) {
    public Ability {
      if (values == null) throw new IllegalArgumentException("Missing ability value lookup");
    }
  }

  public record Entity(
      Object identity, RogueResonanceVariables46 customData, RogueResonanceVariables46 aiTag) {
    public Entity(Object identity, RogueResonanceVariables46 customData) {
      this(identity, customData, null);
    }

    public Entity {
      if (identity == null) throw new IllegalArgumentException("Missing projected entity identity");
    }
  }

  public record Tail(
      Ability ability,
      RogueResonanceVariables46 primaryOwnerCustomData,
      Entity summoner,
      Entity owner,
      RogueResonanceVariables46 globalAliases) {}

  private final RogueResonanceContext46 prefix;
  private final Tail tail;

  public RogueResonanceScopedLookup46(RogueResonanceContext46 prefix, Tail tail) {
    if (prefix == null || tail == null) {
      throw new IllegalArgumentException("Missing fixed context projections");
    }
    this.prefix = prefix;
    this.tail = tail;
  }

  public RogueResonanceVariables46 select(int scope, int key) {
    if (scope == 1 || scope == 2 || scope == 9) return prefix.select(scope, key);
    if (scope == 0) {
      var selected = prefix.findDefaultPrefix(key);
      if (selected != null) return selected;
      if (tail.ability() != null) {
        if (contains(tail.ability().values(), key)) return tail.ability().values();
        if (contains(tail.ability().casterCustomData(), key))
          return tail.ability().casterCustomData();
      } else if (contains(tail.primaryOwnerCustomData(), key)) {
        return tail.primaryOwnerCustomData();
      }
      return summonerOwnerOrGlobal(key, true);
    }
    if (scope == 3 && tail.ability() != null && contains(tail.ability().values(), key)) {
      return tail.ability().values();
    }
    if (scope == 4 && tail.ability() != null && contains(tail.ability().casterCustomData(), key)) {
      return tail.ability().casterCustomData();
    }
    if (scope == 5 && contains(tail.primaryOwnerCustomData(), key)) {
      return tail.primaryOwnerCustomData();
    }
    if (scope == 8) return summonerOwnerOrGlobal(key, false);
    if (scope == 7 && tail.owner() != null) return required(tail.owner().aiTag());
    // TargetEntity requires the separate matched entity-aware overload.
    throw new IllegalArgumentException("Missing or unmatched scoped resonance lookup");
  }

  /** Explicit target projection; component acquisition and type arguments remain external. */
  public RogueResonanceVariables46 selectForEntity(Entity target, int scope, int key) {
    if (scope < 0 || scope > 9) throw new IllegalArgumentException("Unmatched entity lookup scope");
    Object identity = target == null ? null : target.identity();
    Object summonerIdentity = tail.summoner() == null ? null : tail.summoner().identity();
    Object ownerIdentity = tail.owner() == null ? null : tail.owner().identity();
    if (scope != 6 && (identity == summonerIdentity || identity == ownerIdentity)) {
      return select(scope, key);
    }
    if (scope == 9) return select(9, key);
    if (target == null) throw new IllegalArgumentException("Missing projected lookup target");
    return required(scope == 7 ? target.aiTag() : target.customData());
  }

  public long readForEntity(Entity target, int scope, int key) {
    return selectForEntity(target, scope, key).readBound(key, true, aliasEvaluator());
  }

  private static RogueResonanceVariables46 required(RogueResonanceVariables46 lookup) {
    if (lookup == null)
      throw new IllegalArgumentException("Missing projected entity component lookup");
    return lookup;
  }

  private RogueResonanceVariables46 summonerOwnerOrGlobal(int key, boolean allowGlobal) {
    if (tail.summoner() != null && contains(tail.summoner().customData(), key)) {
      return tail.summoner().customData();
    }
    Object summonerIdentity = tail.summoner() == null ? null : tail.summoner().identity();
    if (tail.owner() != null
        && tail.owner().identity() != summonerIdentity
        && contains(tail.owner().customData(), key)) {
      return tail.owner().customData();
    }
    if (allowGlobal && contains(tail.globalAliases(), key)) return tail.globalAliases();
    throw new IllegalArgumentException("Missing projected summoner, owner or global variable");
  }

  private static boolean contains(RogueResonanceVariables46 lookup, int key) {
    return lookup != null && lookup.contains(key);
  }
}
