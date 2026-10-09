package org.hkrpg.server.gameplay;

import com.google.gson.Gson;
import dev.morphia.annotations.Entity;
import emu.lunarcore.game.trial.TrialAvatar;
import java.util.*;
import lombok.Getter;

/**
 * Mission actors and their execution receipts share the player document; formal teams are
 * untouched.
 */
@Entity(useDiscriminator = false)
@Getter
public final class MissionTrialState {
  private Party party;
  private Map<Integer, String> executed = new HashMap<>();
  private Map<Integer, MissionTrialRemovals.Receipt> removals = new HashMap<>();

  void publish(Party prepared) {
    party = Objects.requireNonNull(prepared);
    executed.putAll(prepared.getSources());
  }

  void retire() {
    party = null;
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Actor {
    private String special, definition, item, equipment, skillPlan;
    private Map<Integer, Integer> skills = new HashMap<>();
    private Map<String, String> skillResources = new HashMap<>();

    protected Actor() {}

    Actor(TrialAvatar trial) {
      var gson = new Gson();
      special = gson.toJson(trial.getSpecialAvatarExcel());
      definition = gson.toJson(trial.getExcel());
      int equip = trial.getSpecialAvatarExcel().getEquipmentID();
      item = gson.toJson(emu.lunarcore.data.GameData.getItemExcelMap().get(equip));
      equipment = gson.toJson(emu.lunarcore.data.GameData.getEquipExcelMap().get(equip));
      skillPlan = gson.toJson(trial.getSkillPlan());
      skills.putAll(trial.getSkillTree());
      for (var entry : skills.entrySet()) {
        for (int level = 1; level <= entry.getValue(); level++) {
          var row =
              emu.lunarcore.data.GameData.getAvatarSkilltree(
                  trial.getActorId(), trial.getEnhanceId(), entry.getKey(), level);
          if (row == null)
            throw new IllegalStateException("Incomplete mission trial skill resource");
          skillResources.put(entry.getKey() + ":" + level, gson.toJson(row));
        }
      }
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Party {
    private int main, plane, floor, index, leader, mp, lastSub, raid;
    private String raidSource, raidBase;
    private boolean virtual;
    private List<Integer> roster = new ArrayList<>();
    private List<Integer> entryRoster = new ArrayList<>();
    private Map<Integer, Actor> actors = new HashMap<>();
    private Map<Integer, Actor> baseActors = new HashMap<>();
    private Map<Integer, StoryLineState.Vital> vitals = new HashMap<>();
    private Map<Integer, String> sources = new HashMap<>();

    protected Party() {}

    Party(int main, int plane, int floor, int index, List<Integer> ids, int leader, int mp) {
      this.main = main;
      this.plane = plane;
      this.floor = floor;
      this.index = index;
      roster.addAll(ids);
      entryRoster.addAll(ids);
      this.leader = leader;
      this.mp = mp;
    }

    void select(List<Integer> ids, int leader, boolean virtual, int sub) {
      roster = new ArrayList<>(ids);
      this.leader = leader;
      this.virtual = virtual;
      lastSub = sub;
    }

    void leader(int value) {
      leader = value;
    }

    void bindRaid(int id, String source) {
      raid = id;
      raidSource = source;
    }

    void bindBase(String source) {
      raidBase = source;
    }

    void mp(int value) {
      mp = value;
    }
  }
}
