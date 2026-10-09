package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.game.quest.QuestState;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;

/** Keep each day's source identity and claim reservation separate from ordinary quests. */
@Entity(useDiscriminator = false)
@Getter
public final class DailyCycleQuestState {
  private Map<String, Window> windows = new HashMap<>();

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Window {
    private long day = Long.MIN_VALUE;
    private Map<Integer, QuestState> quests = new HashMap<>();
    private Map<Integer, String> sourceSpecs = new HashMap<>();
    private Map<Integer, String> parentSpecs = new HashMap<>();

    protected Window() {}

    public Window(long day) {
      this.day = day;
    }
  }
}
