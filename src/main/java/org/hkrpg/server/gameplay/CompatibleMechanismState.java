package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.data.ResourceLoader;
import java.util.*;

/** One durable, source-bound receipt per real step; no reward amounts or arbitrary world values. */
@Entity(useDiscriminator = false)
public final class CompatibleMechanismState {
  private int version = 1;
  private Map<String, Progress> records = new TreeMap<>();

  @Entity(useDiscriminator = false)
  public static final class Progress {
    private String source;
    private long at;
    private Set<String> events = new TreeSet<>();
    private Map<String, Integer> states = new TreeMap<>();

    private Progress() {}
  }

  boolean valid() {
    if (version != 1 || records == null || records.size() > CompatibleMechanismSources.FLOWS.size())
      return false;
    for (var entry : records.entrySet()) {
      var f = CompatibleMechanismSources.FLOWS.get(entry.getKey());
      var p = entry.getValue();
      if (f == null
          || p == null
          || p.at <= 0
          || !f.source().equals(p.source)
          || p.events == null
          || p.states == null
          || !f.events().containsAll(p.events)) return false;
      for (var r : f.reports())
        if (p.events.contains(r.key()) && !p.events.containsAll(r.requires())) return false;
      for (var m : f.missions().values())
        if (p.events.contains(m.key()) && !p.events.containsAll(m.requires())) return false;
      for (var a : f.actors().entrySet())
        if (p.events.contains("done:" + a.getKey())) {
          if (a.getValue().role().equals("bug") && !Objects.equals(p.states.get(a.getKey()), 19))
            return false;
          if (a.getValue().requires() != null && !p.events.containsAll(a.getValue().requires()))
            return false;
          if (a.getValue().role().equals("board")) {
            if (f.basePieces() < 3 && !p.events.contains("open:" + a.getKey())) return false;
            for (var piece : f.actors().entrySet())
              if (piece.getValue().role().equals("piece")
                  && !p.events.contains("done:" + piece.getKey())) return false;
          }
        }
      if (f.presentationGroup() > 0)
        for (int i = 1; i <= 3; i++)
          if (p.events.contains("claimed:" + i) && !p.events.contains("room:" + i)) return false;
      for (var s : p.states.entrySet()) {
        if (f.waves() != null
            && s.getKey().equals("@39:state")
            && p.events.contains("started")
            && (Objects.equals(s.getValue(), 1) || Objects.equals(s.getValue(), 2))) continue;
        if (f.presentationGroup() > 0
            && s.getKey().equals("@" + f.presentationGroup() + ":State")
            && s.getValue() != null
            && s.getValue() >= 0
            && s.getValue() <= 1) continue;
        var a = f.actors().get(s.getKey());
        if (a == null
            || !a.role().equals("bug")
            || s.getValue() == null
            || !Set.of(0, 1, 2, 18, 19).contains(s.getValue())) return false;
        if ((s.getValue() == 19) != p.events.contains("done:" + s.getKey())) return false;
      }
      if (f.waves() != null) {
        var before = new HashSet<String>();
        before.add("started");
        for (var wave : f.waves()) {
          if (wave.stream().anyMatch(p.events::contains) && !p.events.containsAll(before))
            return false;
          before.addAll(wave);
        }
      }
    }
    return true;
  }

  Set<String> events(String flow) {
    return records.containsKey(flow)
        ? Collections.unmodifiableSet(records.get(flow).events)
        : Set.of();
  }

  Integer state(String flow, String actor) {
    return records.containsKey(flow) ? records.get(flow).states.get(actor) : null;
  }

  CompatibleMechanismState advance(String flow, Set<String> events, String actor, Integer state) {
    if (!valid()) throw new IllegalStateException("Invalid compatibility journal");
    var next =
        ResourceLoader.getGson()
            .fromJson(ResourceLoader.getGson().toJson(this), CompatibleMechanismState.class);
    var p =
        next.records.computeIfAbsent(
            flow,
            k -> {
              var v = new Progress();
              v.source = CompatibleMechanismSources.FLOWS.get(k).source();
              v.at = java.time.Instant.now().getEpochSecond();
              return v;
            });
    p.events.addAll(events);
    if (actor != null) p.states.put(actor, state);
    if (!next.valid()) throw new IllegalStateException("Invalid compatibility step");
    return next;
  }
}
