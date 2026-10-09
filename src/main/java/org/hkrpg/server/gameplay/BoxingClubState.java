package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;

/** Four ordered server-accepted results, separate from the delivery receipt. */
@Entity(useDiscriminator = false)
public final class BoxingClubState {
  public static final String SOURCE = "724b139d8c9c32d12552eb95745a4fee72bfe48b/boxing-first/v1";
  private long sequence;
  private Run active;
  private Map<Integer, Run> completed = new TreeMap<>();

  public Run getActive() {
    return active;
  }

  public Map<Integer, Run> getCompleted() {
    return Map.copyOf(completed);
  }

  public BoxingClubState copy() {
    var s = new BoxingClubState();
    s.sequence = sequence;
    s.active = active == null ? null : new Run(active);
    completed.forEach((id, run) -> s.completed.put(id, new Run(run)));
    return s;
  }

  void begin(
      int uid,
      int world,
      int plane,
      int floor,
      int entry,
      BoxingClubCatalog.Challenge challenge,
      BoxingClubCatalog.Group group,
      List<Integer> roster) {
    var run = new Run();
    run.source = SOURCE;
    run.sequence = ++sequence;
    run.uid = uid;
    run.world = world;
    run.plane = plane;
    run.floor = floor;
    run.entry = entry;
    run.challenge = challenge.id();
    run.group = group.id();
    run.roster = new ArrayList<>(roster);
    active = run;
  }

  void cancel() {
    active = null;
  }

  Run win(int event, int stage, int battle, int rounds, Map<Integer, Integer> reward) {
    active.wins.add(new Win(event, stage, battle, rounds));
    if (active.wins.size() != 4) return null;
    active.reward = new TreeMap<>(reward);
    var finished = new Run(active);
    var previous = completed.get(finished.challenge);
    if (previous == null || finished.rounds() < previous.rounds())
      completed.put(finished.challenge, finished);
    active = null;
    return finished;
  }

  boolean valid(BoxingClubRules rules, int uid) {
    try {
      return validInternal(rules, uid);
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private boolean validInternal(BoxingClubRules rules, int uid) {
    if (sequence < 0 || completed == null || completed.size() > 5) return false;
    for (var e : completed.entrySet())
      if (e.getKey() == null
          || e.getKey() != e.getValue().challenge
          || !validRun(rules, e.getValue(), uid, true)) return false;
    return active == null || validRun(rules, active, uid, false);
  }

  private boolean validRun(BoxingClubRules rules, Run run, int uid, boolean finished) {
    if (run == null
        || !SOURCE.equals(run.source)
        || run.sequence <= 0
        || run.sequence > sequence
        || run.uid != uid
        || uid <= 0
        || run.world < 0
        || run.world > 6
        || run.roster == null
        || run.roster.isEmpty()
        || run.roster.size() > 4
        || run.roster.stream().anyMatch(id -> id == null || id <= 0)
        || new HashSet<>(run.roster).size() != run.roster.size()
        || run.wins == null
        || finished && run.wins.size() != 4
        || !finished && run.wins.size() > 3) return false;
    var challenge = rules.catalog.challenges().get(run.challenge);
    var group = rules.catalog.group(run.group);
    if (challenge == null
        || group == null
        || !challenge.groups().contains(group.id())
        || group.weight() <= 0
        || run.rounds() < 0
        || run.rounds() > challenge.turnLimit()
        || !finished && run.rounds() >= challenge.turnLimit()) return false;
    var battleIDs = new HashSet<Integer>();
    for (int i = 0; i < run.wins.size(); i++) {
      var win = run.wins.get(i);
      var stage = rules.stage(group.events().get(i), run.world);
      if (win == null
          || stage == null
          || win.event != group.events().get(i)
          || win.stage != stage.getId()
          || win.battle <= 0
          || !battleIDs.add(win.battle)
          || win.rounds <= 0
          || win.rounds > challenge.turnLimit()) return false;
    }
    var reward = rules.reward(challenge.reward());
    return reward != null
        && (finished
            ? run.reward != null && run.reward.equals(BoxingClubRules.items(reward))
            : run.reward != null && run.reward.isEmpty());
  }

  @Entity(useDiscriminator = false)
  public static final class Run {
    private String source;
    private long sequence;
    private int uid, world, plane, floor, entry, challenge, group;
    private List<Integer> roster = new ArrayList<>();
    private List<Win> wins = new ArrayList<>();
    private Map<Integer, Integer> reward = new TreeMap<>();

    private Run() {}

    private Run(Run r) {
      source = r.source;
      sequence = r.sequence;
      uid = r.uid;
      world = r.world;
      plane = r.plane;
      floor = r.floor;
      entry = r.entry;
      challenge = r.challenge;
      group = r.group;
      roster = new ArrayList<>(r.roster);
      wins = new ArrayList<>(r.wins);
      reward = new TreeMap<>(r.reward);
    }

    public int challenge() {
      return challenge;
    }

    public int group() {
      return group;
    }

    public int world() {
      return world;
    }

    public int index() {
      return wins.size();
    }

    public int rounds() {
      if (wins == null || wins.stream().anyMatch(Objects::isNull)) return -1;
      long total = wins.stream().mapToLong(w -> w.rounds).sum();
      return total > Integer.MAX_VALUE || total < 0 ? -1 : (int) total;
    }

    public List<Integer> roster() {
      return List.copyOf(roster);
    }

    public List<Integer> buffs(BoxingClubCatalog catalog) {
      var result = new ArrayList<Integer>();
      for (var win : wins) {
        int buff = catalog.stage(win.event).buff();
        if (buff > 0) result.add(buff);
      }
      return List.copyOf(result);
    }

    public boolean context(emu.lunarcore.game.player.Player p) {
      return p.getUid() == uid
          && p.getWorldLevel() == world
          && p.getPlaneId() == plane
          && p.getFloorId() == floor
          && p.getEntryId() == entry;
    }
  }

  @Entity(useDiscriminator = false)
  public static final class Win {
    private int event, stage, battle, rounds;

    private Win() {}

    private Win(int event, int stage, int battle, int rounds) {
      this.event = event;
      this.stage = stage;
      this.battle = battle;
      this.rounds = rounds;
    }
  }
}
