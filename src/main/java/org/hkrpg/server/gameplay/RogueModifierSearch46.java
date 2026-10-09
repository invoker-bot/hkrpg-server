package org.hkrpg.server.gameplay;

import java.util.List;
import java.util.Objects;

/** Ordinary name/state/origin/source search over explicitly bound modifier collections. */
public final class RogueModifierSearch46 {
  private RogueModifierSearch46() {}

  public static final class Candidate {
    public String name;
    public int state, stackingFlag;

    public Candidate(String name, int state, int stackingFlag) {
      this.name = name;
      this.state = state;
      this.stackingFlag = stackingFlag;
    }
  }

  public static final class Entity {
    public int runtimeId, aliveState;

    public Entity(int runtimeId, int aliveState) {
      this.runtimeId = runtimeId;
      this.aliveState = aliveState;
    }
  }

  public record Search(
      String name, int casterId, int stateFilter, int stackingFlag, Object ability) {}

  public interface World {
    List<Candidate> modifiers(Object component);

    String currentModifierReservedName();

    Candidate currentModifier(Object component);

    Object taskContext(Candidate candidate);

    Entity casterEntity(Object context);

    Integer sourceEntityId(Entity entity);

    Object contextAbility(Object context);
  }

  public static boolean matches(Candidate candidate, Search search, World world) {
    if (candidate == null || search == null)
      throw new IllegalArgumentException("Missing explicit modifier search input");
    if (!Objects.equals(candidate.name, search.name())) return false;
    if (search.stateFilter() == 0 && Integer.compareUnsigned(candidate.state, 1) > 0) return false;
    if (search.stateFilter() == 1 && candidate.state != 1) return false;
    if (search.stackingFlag() != 100 && candidate.stackingFlag != search.stackingFlag())
      return false;
    if (search.casterId() == -1 || search.casterId() == 0) return true;
    Entity first = world(world).casterEntity(context(candidate, world));
    boolean casterMatches = false;
    if (first != null) {
      casterMatches = first.runtimeId == search.casterId();
      if (!casterMatches) {
        Entity second = world.casterEntity(context(candidate, world));
        int owner = 0;
        if (second != null) {
          if (second.aliveState == 8) owner = second.runtimeId;
          else {
            Integer explicitOwner = world.sourceEntityId(second);
            owner = explicitOwner == null ? second.runtimeId : explicitOwner;
          }
        }
        casterMatches = owner == search.casterId();
      }
    }
    if (search.ability() != null) {
      Object ability = world.contextAbility(context(candidate, world));
      casterMatches = casterMatches & (ability == search.ability());
    }
    return casterMatches;
  }

  public static Candidate find(Object component, Search search, World world) {
    if (component == null || search == null)
      throw new IllegalArgumentException("Missing modifier component search");
    world(world);
    List<Candidate> candidates = collection(world.modifiers(component));
    int index = 0;
    while (index < candidates.size()) {
      Candidate candidate = candidates.get(index);
      if (matches(candidate, search, world)) return candidate;
      index++;
      candidates = collection(world.modifiers(component));
    }
    if (!Objects.equals(world.currentModifierReservedName(), search.name())) return null;
    if (world.currentModifier(component) == null) return null;
    return world.currentModifier(component);
  }

  private static Object context(Candidate candidate, World world) {
    Object context = world(world).taskContext(candidate);
    if (context == null) throw new IllegalArgumentException("Missing modifier source context");
    return context;
  }

  private static World world(World world) {
    if (world == null) throw new IllegalArgumentException("Missing modifier search world");
    return world;
  }

  private static List<Candidate> collection(List<Candidate> candidates) {
    if (candidates == null) throw new IllegalArgumentException("Missing modifier list");
    return candidates;
  }
}
