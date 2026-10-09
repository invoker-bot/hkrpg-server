package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;
import lombok.Getter;

/** Nonperiodic quests retain one reward reservation across days and operator windows. */
@Entity(useDiscriminator = false)
@Getter
public final class BoxingClubActivityQuestState {
  static final String SOURCE = "724b139d8c9c32d12552eb95745a4fee72bfe48b/boxing-activity/v1";
  private Map<Integer, Completion> completed = new TreeMap<>();
  private Map<Integer, QuestState> quests = new TreeMap<>();

  BoxingClubActivityQuestState copy() {
    var gson = ResourceLoader.getGson();
    return gson.fromJson(gson.toJson(this), BoxingClubActivityQuestState.class);
  }

  void record(Completion value) {
    var old = completed.get(value.challenge());
    if (old == null || value.rounds() < old.rounds()) completed.put(value.challenge(), value);
  }

  @Entity(useDiscriminator = false)
  static final class Completion {
    private String source = SOURCE;
    private int panel, module;
    private long begin, end, acceptedAt;
    private BoxingClubState.Run first;
    private BoxingClubResonanceState.Run second;

    private Completion() {}

    Completion(
        GameplayDefinitions.Window window,
        long at,
        BoxingClubState.Run first,
        BoxingClubResonanceState.Run second) {
      panel = window.panelId();
      module = window.moduleId();
      begin = window.beginTime();
      end = window.endTime();
      acceptedAt = at;
      this.first = first;
      this.second = second;
    }

    int challenge() {
      return first != null ? first.challenge() : second.challenge();
    }

    int rounds() {
      return first != null ? first.rounds() : second.rounds();
    }

    boolean valid(
        emu.lunarcore.game.player.Player p,
        BoxingClubRules firstRules,
        BoxingClubResonanceRules secondRules) {
      try {
        if (!SOURCE.equals(source)
            || begin <= 0
            || end <= begin
            || acceptedAt < begin
            || acceptedAt >= end
            || acceptedAt > java.time.Instant.now().getEpochSecond()
            || (first == null) == (second == null)) return false;
        if (first != null) {
          return panel == 50000
              && module == 5000001
              && firstRules != null
              && p.getGameplay().getBoxingClub().validCompletion(firstRules, first, p.getUid());
        }
        return panel == 40003
            && module == 4000308
            && secondRules != null
            && p.getGameplay()
                .getBoxingClubResonance()
                .validCompletion(secondRules, second, p.getUid());
      } catch (RuntimeException invalid) {
        return false;
      }
    }
  }
}
