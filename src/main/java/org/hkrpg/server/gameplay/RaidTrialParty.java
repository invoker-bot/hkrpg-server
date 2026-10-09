package org.hkrpg.server.gameplay;

import com.google.gson.Gson;
import dev.morphia.annotations.Entity;
import emu.lunarcore.game.trial.TrialAvatar;
import java.util.*;
import lombok.Getter;

/** Frozen declared trials, selected members and raid-local vitals; no formal grants. */
@Entity(useDiscriminator = false)
@Getter
public final class RaidTrialParty {
  private int playerWorld, playerGender, leader, mp;
  private List<Integer> roster = new ArrayList<>();
  private Map<Integer, String> configs = new HashMap<>(), plans = new HashMap<>();
  private Map<Integer, Map<Integer, Integer>> skills = new HashMap<>();
  private Map<Integer, StoryLineState.Vital> vitals = new HashMap<>();
  private static final Gson GSON = new Gson();

  protected RaidTrialParty() {}

  public RaidTrialParty(
      int world,
      int mp,
      List<emu.lunarcore.game.avatar.GameAvatar> actors,
      emu.lunarcore.game.player.lineup.PlayerLineup ordinary) {
    playerWorld = world;
    playerGender = ordinary.getOwner().getGender().getVal();
    this.mp = mp;
    for (var actor : actors) {
      int id = actor.getAvatarId();
      roster.add(id);
      if (actor instanceof TrialAvatar trial) {
        configs.put(id, GSON.toJson(trial.getSpecialAvatarExcel()));
        plans.put(id, GSON.toJson(trial.getSkillPlan()));
        skills.put(id, new HashMap<>(trial.getSkillTree()));
        vitals.put(id, new StoryLineState.Vital(10000, 7000));
      } else
        vitals.put(
            id,
            new StoryLineState.Vital(actor.getCurrentHp(ordinary), actor.getCurrentSp(ordinary)));
    }
  }

  public List<Integer> getRoster() {
    return List.copyOf(roster);
  }

  public void leader(int value) {
    leader = value;
  }

  public void mp(int value) {
    mp = value;
  }

  String frozen() {
    return GSON.toJson(List.of(playerWorld, playerGender, roster, configs, plans, skills));
  }
}
