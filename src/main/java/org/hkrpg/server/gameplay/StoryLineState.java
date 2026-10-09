package org.hkrpg.server.gameplay;

import com.google.gson.Gson;
import dev.morphia.annotations.Entity;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.trial.TrialAvatar;
import emu.lunarcore.util.Position;
import java.util.*;
import lombok.Getter;

/** Story parties and return locations share the player's persistence boundary. */
@Entity(useDiscriminator = false)
@Getter
public final class StoryLineState {
  private int currentId;
  private int returningId;
  private boolean transitionPending;
  private Place home;
  private Place returnDestination;
  private Map<Integer, Run> runs = new HashMap<>();

  public void begin(int id, Place origin, Place destination) {
    if (home == null) home = origin;
    returningId = id == 0 ? currentId : 0;
    returnDestination = id == 0 ? destination : null;
    currentId = id;
    transitionPending = true;
  }

  public void arrived() {
    transitionPending = false;
  }

  public void returned() {
    transitionPending = false;
    home = null;
    returningId = 0;
    returnDestination = null;
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Place {
    private int plane, floor, entry, dimension;
    private Position pos, rot;

    protected Place() {}

    public Place(int plane, int floor, int entry, int dimension, Position pos, Position rot) {
      this.plane = plane;
      this.floor = floor;
      this.entry = entry;
      this.dimension = dimension;
      this.pos = pos.clone();
      this.rot = rot.clone();
    }

    public Place(Player player) {
      this(
          player.getPlaneId(),
          player.getFloorId(),
          player.getEntryId(),
          player.getScene() == null ? 0 : player.getScene().getDimensionId(),
          player.getPos(),
          player.getRot());
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Vital {
    private int hp, sp;

    protected Vital() {}

    public Vital(int hp, int sp) {
      this.hp = hp;
      this.sp = sp;
    }

    public void hp(int value) {
      hp = Math.clamp(value, 0, 10000);
    }

    public void sp(int value) {
      sp = Math.clamp(value, 0, 10000);
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Run {
    private int id, leader, mp;
    private List<Integer> roster = new ArrayList<>();
    private Set<Integer> availableTrials;
    private Map<Integer, String> trialConfigs = new HashMap<>();
    private Map<Integer, Vital> vitals = new HashMap<>();
    private Map<Integer, Map<Integer, Integer>> skills;
    private Map<Integer, String> skillPlans;
    private Place location;

    protected Run() {}

    public Run(
        int id, List<Integer> roster, int leader, Map<Integer, String> configs, Place place) {
      this.id = id;
      this.roster.addAll(roster);
      availableTrials = new LinkedHashSet<>(roster);
      this.leader = leader;
      trialConfigs.putAll(configs);
      for (int avatar : configs.keySet()) vitals.put(avatar, new Vital(10000, 7000));
      mp = 5;
      location = place;
    }

    public void locate(Place place) {
      location = place;
    }

    public void freezeSkills(Map<Integer, TrialAvatar> actors) {
      if (skills != null || skillPlans != null)
        throw new IllegalStateException("Story skills already frozen");
      if (!actors.keySet().equals(trialConfigs.keySet()))
        throw new IllegalStateException("Incomplete story skill snapshot");
      var gson = new Gson();
      skills = new HashMap<>();
      skillPlans = new HashMap<>();
      for (var actor : actors.values()) {
        skills.put(actor.getAvatarId(), new HashMap<>(actor.getSkillTree()));
        skillPlans.put(actor.getAvatarId(), gson.toJson(actor.getSkillPlan()));
      }
    }

    public Set<Integer> getAvailableTrials() {
      // Old saves prove only the actors actually in their saved party.
      return Collections.unmodifiableSet(
          availableTrials == null ? new LinkedHashSet<>(roster) : availableTrials);
    }

    public void freezeAvailability() {
      if (availableTrials == null) availableTrials = new LinkedHashSet<>(roster);
    }

    boolean unlockTrial(int id) {
      if (!trialConfigs.containsKey(id)
          || !vitals.containsKey(id)
          || skills == null
          || !skills.containsKey(id)
          || skillPlans == null
          || !skillPlans.containsKey(id)) return false;
      freezeAvailability();
      availableTrials.add(id);
      return true;
    }

    public void leader(int value) {
      leader = value;
    }

    public void mp(int value) {
      mp = value;
    }
  }
}
