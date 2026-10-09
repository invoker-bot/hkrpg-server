package org.hkrpg.server.gameplay;

import java.util.Map;
import java.util.NoSuchElementException;

/** Ordinary default AI initialization; engine allocation and configuration remain explicit. */
public final class RogueAiDefault46 {
  public static final String PRIORITY_KEY = "CrazyAIBehaviorPriority";
  public static final String DEFAULT_KEY = "PROG_Default";
  public static final int NORMAL_SKILL_LAYOUT = 1;

  private RogueAiDefault46() {}

  public static final class Priority {
    public Map<String, Integer> keys;
  }

  public static final class PriorityCatalog {
    public Map<String, Priority> entries;
  }

  public static final class Catalog {
    public RogueModifierRegistrations46.Behavior sharedDefault;
  }

  public interface World {
    RogueModifierRegistrations46.Behavior allocateBehavior();

    PriorityCatalog priorityCatalog();

    int[] allocateSkillLayouts(int length);
  }

  /** The matched registered constructor is an empty return, including for an existing payload. */
  public static void initialize(RogueModifierRegistrations46.Behavior behavior) {}

  /** Finite managed dictionary projection; missing keys fail instead of inventing a priority. */
  public static int lookupPriority(PriorityCatalog catalog, String key) {
    Map<String, Priority> entries =
        require(require(catalog, "priority catalog").entries, "priority entries");
    require(key, "priority key");
    if (!entries.containsKey(key)) throw new NoSuchElementException(key);
    Priority priority = require(entries.get(key), "priority record");
    Map<String, Integer> keys = require(priority.keys, "priority values");
    if (!keys.containsKey(DEFAULT_KEY)) throw new NoSuchElementException(DEFAULT_KEY);
    return require(keys.get(DEFAULT_KEY), "priority value");
  }

  /** Publish only after the priority, array contents and both flags have been initialized. */
  public static RogueModifierRegistrations46.Behavior initializeCatalog(
      Catalog catalog, World world) {
    require(catalog, "AI catalog");
    World bound = require(world, "AI world");
    RogueModifierRegistrations46.Behavior behavior = bound.allocateBehavior();
    int priority = lookupPriority(bound.priorityCatalog(), PRIORITY_KEY);
    require(behavior, "allocated AI behavior").priority = priority;
    int[] layouts = require(bound.allocateSkillLayouts(1), "allocated skill layouts");
    if (layouts.length == 0) throw new IndexOutOfBoundsException("Skill layout element zero");
    layouts[0] = NORMAL_SKILL_LAYOUT;
    behavior.skillLayouts = layouts;
    behavior.firstFlag = true;
    behavior.secondFlag = true;
    catalog.sharedDefault = behavior;
    return behavior;
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
