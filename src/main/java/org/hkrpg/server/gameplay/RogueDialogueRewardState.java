package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;

/** One frozen server recipe and its receipt, owned by an authored dialogue lease. */
@Entity(useDiscriminator = false)
public final class RogueDialogueRewardState {
  private int schema;
  private String runId = "", leaseKey = "", source = "";
  private int fragments;
  private List<Integer> candidates = new ArrayList<>();
  private int option, batch, blessing;
  private boolean complete;

  protected RogueDialogueRewardState() {}

  RogueDialogueRewardState(GameplayState.RogueRun run, GameplayState.RogueDialogue lease,
      String source, int fragments, List<Integer> candidates) {
    this.schema = 1;
    this.runId = run.getRunId();
    this.leaseKey = lease.key();
    this.source = source;
    this.fragments = fragments;
    this.candidates = new ArrayList<>(candidates);
  }

  public int getOption() { return option; }
  public int getBatch() { return batch; }
  public int getBlessing() { return blessing; }
  public boolean isComplete() { return complete; }
  int fragments() { return fragments; }
  List<Integer> candidates() { return candidates; }

  boolean valid(GameplayState.RogueRun run, GameplayState.RogueDialogue lease, String currentSource) {
    if (schema != 1 || !Objects.equals(runId, run.getRunId())
        || !Objects.equals(leaseKey, lease.key()) || !Objects.equals(source, currentSource)
        || currentSource.isEmpty() || fragments <= 0 || candidates == null
        || candidates.stream().anyMatch(id -> id == null || id <= 0)
        || new HashSet<>(candidates).size() != candidates.size()) return false;
    return switch (option) {
      case 0 -> batch == 0 && blessing == 0 && !complete;
      case 14101, 14103 -> batch == 0 && blessing == 0 && complete;
      case 14102 -> batch > 0 && blessing > 0 && candidates.contains(blessing);
      default -> false;
    };
  }

  void choose(int option, int batch, int blessing, boolean complete) {
    this.option = option;
    this.batch = batch;
    this.blessing = blessing;
    this.complete = complete;
  }

  void complete() { complete = true; }

  record Mark(int option, int batch, int blessing, boolean complete) {}
  Mark mark() { return new Mark(option, batch, blessing, complete); }
  void restore(Mark prior) { choose(prior.option(), prior.batch(), prior.blessing(), prior.complete()); }
}
