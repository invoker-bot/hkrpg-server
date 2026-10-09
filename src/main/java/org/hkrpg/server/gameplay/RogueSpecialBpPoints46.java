package org.hkrpg.server.gameplay;

/** Ordinary linked point withdrawal and release of special reservations with shared BP state. */
public final class RogueSpecialBpPoints46 {
  private RogueSpecialBpPoints46() {}

  public interface World {
    void costAdditionalTeamBp(RogueModifierCleanup46.State modifier, int registrationId);
  }

  public static void removePoint(RogueSpecialBpWithdrawal46.State state, boolean includeSpecial) {
    var list = require(require(state, "BP module").points, "BP points");
    var head = list.head;
    if (head == null) return;
    var point = head.previous;
    if (point == null) return;
    if (includeSpecial) {
      removeNode(list, point);
      return;
    }
    int examined = 0;
    while (true) {
      bound(++examined);
      if ((point.special & 1) == 0) {
        removeNode(list, point);
        return;
      }
      var previous = point.previous;
      if (previous == null) return;
      var owner = require(point.owner, "BP point owner");
      boolean isHead = owner.head == point;
      point = previous;
      if (isHead) return;
    }
  }

  public static void removeNode(
      RogueSpecialBpWithdrawal46.Points list, RogueSpecialBpWithdrawal46.Point point) {
    require(list, "BP points");
    require(point, "BP point");
    if (point.owner != list) throw new IllegalArgumentException("Foreign BP point");
    var next = point.next;
    if (next == point) list.head = null;
    else {
      require(next, "next BP point").previous = point.previous;
      var previous = require(point.previous, "previous BP point");
      previous.next = next;
      if (list.head == point) list.head = point.next;
    }
    point.previous = null;
    point.next = null;
    point.owner = null;
    list.size--;
    list.version++;
  }

  public static void refresh(RogueSpecialBpWithdrawal46.State state, World world) {
    if (RogueSpecialBpWithdrawal46.specialPointCount(state) == 0) return;
    int normal =
        require(state.points, "BP points").size
            - RogueSpecialBpWithdrawal46.specialPointCount(state);
    int maximum = state.maximumPresent ? state.optionalMaximum : state.fallbackMaximum;
    int needed = maximum - RogueSpecialBpWithdrawal46.reserved(state) - normal;
    if (needed <= 0) return;
    var point = require(state.points, "BP points").head;
    int converted = 0, examined = 0;
    while (point != null && converted < needed) {
      bound(++examined);
      if ((point.special & 1) != 0) {
        var modifier = point.modifier;
        point.special = 0;
        point.modifier = null;
        if (modifier != null && modifier.candidate.state == 1)
          require(world, "BP modifier update world")
              .costAdditionalTeamBp(modifier, point.registrationId);
        converted++;
      }
      var next = point.next;
      if (next == null) return;
      var owner = require(point.owner, "BP point owner");
      if (next == owner.head) return;
      point = next;
    }
  }

  private static void bound(int count) {
    if (count > RogueSpecialBpWithdrawal46.LIMIT)
      throw new IllegalArgumentException("Excessive finite BP points");
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
