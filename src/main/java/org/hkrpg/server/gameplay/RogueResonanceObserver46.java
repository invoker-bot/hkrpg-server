package org.hkrpg.server.gameplay;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Finite instance-listener membership; general delegate types and native factories stay closed. */
public final class RogueResonanceObserver46 {
  public static final class Listener {
    private final Object target, method;
    private final Consumer<RogueResonanceVariables46.Change> callback;

    public Listener(
        Object target, Object method, Consumer<RogueResonanceVariables46.Change> callback) {
      if (target == null || method == null || callback == null)
        throw new IllegalArgumentException("Missing explicit instance-listener identity");
      this.target = target;
      this.method = method;
      this.callback = callback;
    }

    private boolean matches(Listener other) {
      return target == other.target && method == other.method;
    }
  }

  private List<Listener> current = List.of();

  public synchronized void combine(Listener listener) {
    if (listener == null) throw new IllegalArgumentException("Missing finite instance listener");
    if (current.size() >= 255) throw new IllegalArgumentException("Excessive finite listeners");
    var next = new ArrayList<>(current);
    next.add(listener);
    current = List.copyOf(next);
  }

  public synchronized boolean remove(Listener listener) {
    if (listener == null) throw new IllegalArgumentException("Missing finite instance listener");
    for (int index = current.size() - 1; index >= 0; index--) {
      if (!current.get(index).matches(listener)) continue;
      var next = new ArrayList<>(current);
      next.remove(index);
      current = List.copyOf(next);
      return true;
    }
    return false;
  }

  public synchronized List<Listener> snapshot() {
    return current;
  }

  public static void dispatch(List<Listener> snapshot, RogueResonanceVariables46.Change change) {
    if (snapshot == null || change == null)
      throw new IllegalArgumentException("Missing listener invocation snapshot");
    for (Listener listener : snapshot) {
      if (listener == null) throw new IllegalArgumentException("Unmatched listener snapshot entry");
      listener.callback.accept(change);
    }
  }
}
