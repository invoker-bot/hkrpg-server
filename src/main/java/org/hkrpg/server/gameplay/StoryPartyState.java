package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;
import lombok.Getter;
import org.bson.types.ObjectId;

/** Durable intents bridge player, avatar and lineup documents without replaying item payouts. */
@Entity(useDiscriminator = false)
@Getter
public final class StoryPartyState {
  private Map<String, Grant> grants = new HashMap<>();
  private Map<String, Change> changes = new HashMap<>();

  @Entity(useDiscriminator = false)
  @Getter
  public static class Grant {
    private String source;
    private int avatar;
    private String objectId;
    private boolean complete;

    protected Grant() {}
    Grant(String source, int avatar) {
      this.source = source;
      this.avatar = avatar;
      this.objectId = new ObjectId().toHexString();
    }
    void complete(boolean value) { complete = value; }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class Change {
    private String source;
    private int index;
    private List<Integer> before = new ArrayList<>(), after = new ArrayList<>();
    private int beforeLeader, afterLeader;
    private boolean complete;

    protected Change() {}
    Change(String source, int index, List<Integer> before, int beforeLeader,
        List<Integer> after, int afterLeader) {
      this.source = source;
      this.index = index;
      this.before.addAll(before);
      this.after.addAll(after);
      this.beforeLeader = beforeLeader;
      this.afterLeader = afterLeader;
    }
    void complete(boolean value) { complete = value; }
  }
}
