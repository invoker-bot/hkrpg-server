package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;

/** Target bits from confirmed battle reports; queries never create progress or completion. */
@Entity(useDiscriminator = false)
public final class LocalLegendProgress {
  @Entity(useDiscriminator = false)
  public static final class Entry {
    private int group, difficulty, mask;
    private long changedAt;
    private String resourceIdentity;

    protected Entry() {}

    private Entry(LocalLegendCatalog.Level level, int mask, long time) {
      group = level.group();
      difficulty = level.difficulty();
      this.mask = mask;
      changedAt = time;
      resourceIdentity = level.identity();
    }

    public int group() {
      return group;
    }

    public int difficulty() {
      return difficulty;
    }

    public int mask() {
      return mask;
    }

    public long changedAt() {
      return changedAt;
    }

    public String resourceIdentity() {
      return resourceIdentity;
    }
  }

  private Map<String, Entry> entries = new HashMap<>();

  @Entity(useDiscriminator = false)
  public static final class AchievementEntry {
    private int quest, achievement, group, difficulty;
    private long changedAt;
    private String resourceIdentity, levelIdentity;

    protected AchievementEntry() {}

    private AchievementEntry(
        LocalLegendAchievementRules.Rule rule, LocalLegendCatalog.Level level, long time) {
      quest = rule.quest();
      achievement = rule.achievement();
      group = level.group();
      difficulty = level.difficulty();
      changedAt = time;
      resourceIdentity = rule.resourceIdentity();
      levelIdentity = level.identity();
    }

    public int quest() {
      return quest;
    }

    public int achievement() {
      return achievement;
    }

    public long changedAt() {
      return changedAt;
    }
  }

  private Map<String, AchievementEntry> achievements = new HashMap<>();

  private static String key(LocalLegendCatalog.Level level) {
    return level.group() + ":" + level.difficulty();
  }

  private static boolean valid(LocalLegendCatalog.Level level, Entry entry) {
    return level != null
        && entry != null
        && entry.group == level.group()
        && entry.difficulty == level.difficulty()
        && entry.mask != 0
        && level.validMask(entry.mask)
        && entry.changedAt > 0
        && level.identity().equals(entry.resourceIdentity);
  }

  public boolean valid() {
    return entries != null
        && entries.size() <= 64
        && entries.entrySet().stream()
            .allMatch(
                e -> {
                  var value = e.getValue();
                  if (value == null) return false;
                  var level = LocalLegendCatalog.level(value.group, value.difficulty);
                  return valid(level, value) && e.getKey().equals(key(level));
                })
        && achievements != null
        && achievements.size() <= 64
        && achievements.entrySet().stream()
            .allMatch(
                e -> {
                  var value = e.getValue();
                  if (value == null || !e.getKey().equals(Integer.toString(value.quest)))
                    return false;
                  var rule = LocalLegendAchievementRules.rule(value.quest);
                  var level = LocalLegendCatalog.level(value.group, value.difficulty);
                  return rule != null
                      && level != null
                      && LocalLegendAchievementRules.allowed(rule, level)
                      && value.achievement == rule.achievement()
                      && value.changedAt > 0
                      && rule.resourceIdentity().equals(value.resourceIdentity)
                      && level.identity().equals(value.levelIdentity);
                });
  }

  public int mask(LocalLegendCatalog.Level level) {
    if (level == null
        || LocalLegendCatalog.level(level.group(), level.difficulty()) != level
        || !valid()) throw new IllegalStateException("Invalid Local Legend progress binding");
    var entry = entries.get(key(level));
    return entry == null ? 0 : entry.mask;
  }

  /** Internal merge; caller must bind the accepted report to the current battle before saving. */
  public boolean merge(LocalLegendCatalog.Level level, int mask, long time) {
    if (level == null
        || LocalLegendCatalog.level(level.group(), level.difficulty()) != level
        || !valid()
        || !level.validMask(mask)
        || time <= 0) throw new IllegalArgumentException("Invalid Local Legend progress merge");
    var old = entries.get(key(level));
    int previous = old == null ? 0 : old.mask;
    int next = previous | mask;
    if (next == previous) return false;
    if (old != null && time < old.changedAt)
      throw new IllegalArgumentException("Local Legend progress time moved backwards");
    entries.put(key(level), new Entry(level, next, time));
    return true;
  }

  public boolean hasTarget(int id) {
    if (id <= 0 || !valid()) return false;
    for (var entry : entries.values()) {
      var level = LocalLegendCatalog.level(entry.group, entry.difficulty);
      for (int i = 0; i < level.goals().size(); i++)
        if (level.goals().get(i).id() == id && (entry.mask & (1 << i)) != 0) return true;
    }
    return false;
  }

  public List<Entry> entries() {
    return entries == null ? List.of() : List.copyOf(entries.values());
  }

  public boolean mergeAchievement(
      LocalLegendAchievementRules.Rule rule, LocalLegendCatalog.Level level, long time) {
    if (rule == null
        || LocalLegendAchievementRules.rule(rule.quest()) != rule
        || level == null
        || LocalLegendCatalog.level(level.group(), level.difficulty()) != level
        || !LocalLegendAchievementRules.allowed(rule, level)
        || !valid()
        || time <= 0) throw new IllegalArgumentException("Invalid Local Legend achievement merge");
    String key = Integer.toString(rule.quest());
    if (achievements.containsKey(key)) return false;
    achievements.put(key, new AchievementEntry(rule, level, time));
    return true;
  }

  public boolean hasAchievement(int quest) {
    return quest > 0 && valid() && achievements.containsKey(Integer.toString(quest));
  }

  public List<AchievementEntry> achievementEntries() {
    return achievements == null ? List.of() : List.copyOf(achievements.values());
  }

  public LocalLegendProgress copy() {
    var result = new LocalLegendProgress();
    if (entries == null) result.entries = null;
    else result.entries = new HashMap<>(entries); // Entries are replaced, never mutated.
    result.achievements = achievements == null ? null : new HashMap<>(achievements);
    return result;
  }
}
