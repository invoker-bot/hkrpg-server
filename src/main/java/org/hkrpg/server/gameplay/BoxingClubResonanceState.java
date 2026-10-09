package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;

/** Source-bound roster, one resonance, two stage choices, and three accepted results. */
@Entity(useDiscriminator = false)
public final class BoxingClubResonanceState {
  public static final String SOURCE = "724b139d8c9c32d12552eb95745a4fee72bfe48b/boxing-second/v1";
  private long sequence;
  private Run active;
  private Map<Integer, Run> completed = new TreeMap<>();

  public Run getActive() {
    return active;
  }

  public Map<Integer, Run> getCompleted() {
    return Map.copyOf(completed);
  }

  public BoxingClubResonanceState copy() {
    var next = new BoxingClubResonanceState();
    next.sequence = sequence;
    next.active = active == null ? null : new Run(active);
    completed.forEach((id, run) -> next.completed.put(id, new Run(run)));
    return next;
  }

  void begin(emu.lunarcore.game.player.Player p, int id, List<BoxingClubManager.Member> members) {
    var run = new Run();
    run.source = SOURCE;
    run.sequence = ++sequence;
    run.uid = p.getUid();
    run.world = p.getWorldLevel();
    run.plane = p.getPlaneId();
    run.floor = p.getFloorId();
    run.entry = p.getEntryId();
    run.challenge = id;
    run.roster = members.stream().map(BoxingClubManager.Member::id).toList();
    run.rosterTypes = members.stream().map(BoxingClubManager.Member::type).toList();
    active = run;
  }

  void cancel() {
    active = null;
  }

  void chooseResonance(int buff, int group) {
    active.resonance = buff;
    active.group = group;
  }

  void chooseOptional(int buff) {
    active.options.add(buff);
  }

  Run win(int event, int stage, int battle, int rounds, Map<Integer, Integer> reward) {
    active.wins.add(new Win(event, stage, battle, rounds));
    if (active.wins.size() != 3) return null;
    active.reward = new TreeMap<>(reward);
    var finished = new Run(active);
    var prior = completed.get(finished.challenge);
    if (prior == null || finished.rounds() < prior.rounds())
      completed.put(finished.challenge, finished);
    active = null;
    return finished;
  }

  boolean valid(BoxingClubResonanceRules rules, int uid) {
    try {
      if (sequence < 0 || completed == null || completed.size() > 7) return false;
      for (var e : completed.entrySet())
        if (e.getKey() == null
            || e.getValue() == null
            || e.getKey() != e.getValue().challenge
            || !validRun(rules, e.getValue(), uid, true)) return false;
      return active == null || validRun(rules, active, uid, false);
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  boolean validCompletion(BoxingClubResonanceRules rules, Run run, int uid) {
    try { return validRun(rules, run, uid, true); }
    catch (RuntimeException invalid) { return false; }
  }

  private boolean validRun(BoxingClubResonanceRules rules, Run run, int uid, boolean finished) {
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
        || run.roster.stream().anyMatch(i -> i == null || i <= 0)
        || new HashSet<>(run.roster).size() != run.roster.size()
        || run.rosterTypes == null
        || run.rosterTypes.size() != run.roster.size()
        || run.rosterTypes.stream().anyMatch(t -> t == null || t != 1 && t != 3)
        || run.options == null
        || run.options.stream().anyMatch(i -> i == null || i <= 0)
        || run.wins == null
        || finished && run.wins.size() != 3
        || !finished && run.wins.size() > 2
        || run.rounds() < 0) return false;
    var c = rules.catalog.challenges().get(run.challenge);
    if (c == null || run.rounds() > c.turnLimit() || !finished && run.rounds() >= c.turnLimit())
      return false;
    for (var member : run.members())
      if (member.type() == 3 && !BoxingClubResonanceTrials.declared(run.challenge, member.id()))
        return false;
    if (run.resonance == 0)
      return !finished
          && run.group == 0
          && run.wins.isEmpty()
          && run.options.isEmpty()
          && run.reward != null
          && run.reward.isEmpty();
    if (!Objects.equals(c.resonanceGroups().get(run.resonance), run.group)) return false;
    var group = rules.catalog.group(run.group);
    if (group == null
        || run.options.size() > Math.min(run.index(), 2)
        || run.options.size() < Math.max(0, run.index() - 1)) return false;
    for (int i = 0; i < run.options.size(); i++)
      if (!rules.catalog.stage(group.events().get(i + 1)).options().contains(run.options.get(i)))
        return false;
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
          || win.rounds > c.turnLimit()
          || i > 0 && run.options.size() < i) return false;
    }
    var reward = rules.reward(c.reward());
    return reward != null
        && run.reward != null
        && (finished
            ? run.options.size() == 2 && run.reward.equals(BoxingClubResonanceRules.items(reward))
            : run.reward.isEmpty());
  }

  @Entity(useDiscriminator = false)
  public static final class Run {
    private String source;
    private long sequence;
    private int uid, world, plane, floor, entry, challenge, group, resonance;
    private List<Integer> roster = new ArrayList<>(),
        rosterTypes = new ArrayList<>(),
        options = new ArrayList<>();
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
      resonance = r.resonance;
      roster = new ArrayList<>(r.roster);
      rosterTypes = new ArrayList<>(r.rosterTypes);
      options = new ArrayList<>(r.options);
      wins = new ArrayList<>(r.wins);
      reward = new TreeMap<>(r.reward);
    }

    public int challenge() {
      return challenge;
    }

    public int group() {
      return group;
    }

    public int resonance() {
      return resonance;
    }

    public int world() {
      return world;
    }

    public int index() {
      return wins.size();
    }

    public List<Integer> options() {
      return List.copyOf(options);
    }

    public List<Integer> buffs() {
      var result = new ArrayList<Integer>();
      if (resonance > 0) result.add(resonance);
      result.addAll(options);
      return List.copyOf(result);
    }

    public boolean ready() {
      return resonance > 0 && options.size() == index() && index() < 3;
    }

    public int rounds() {
      if (wins == null || wins.stream().anyMatch(Objects::isNull)) return -1;
      long total = wins.stream().mapToLong(w -> w.rounds).sum();
      return total < 0 || total > Integer.MAX_VALUE ? -1 : (int) total;
    }

    public List<BoxingClubManager.Member> members() {
      var result = new ArrayList<BoxingClubManager.Member>();
      for (int i = 0; i < roster.size(); i++)
        result.add(new BoxingClubManager.Member(roster.get(i), rosterTypes.get(i)));
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
