package org.hkrpg.server.gameplay;

import java.util.Objects;

/** Ordinary equality for explicitly projected OnEventCallBack instances. */
public final class RogueEventDelegateEquality46 {
  public static final int LIMIT = 255;

  private RogueEventDelegateEquality46() {}

  public interface World {
    Object methodObject(RogueBaseModifierDestroy46.Callback callback);

    boolean virtualEquals(RogueBaseModifierDestroy46.Callback callback, Object other);
  }

  public static boolean delegateEquals(
      RogueBaseModifierDestroy46.Callback self, Object other, World world) {
    require(self, "delegate receiver");
    if (!(other instanceof RogueBaseModifierDestroy46.Callback right)) return false;
    if (right.target != self.target) return false;
    World bound = require(world, "delegate method lookup world");
    Object rightMethod = bound.methodObject(right);
    Object leftMethod = bound.methodObject(self);
    if (rightMethod != leftMethod) return false;
    var rightData = right.data;
    var leftData = self.data;
    if (rightData == null) return leftData == null || leftData.targetType == null;
    if (leftData == null) return rightData.targetType == null;
    return rightData.targetType == leftData.targetType
        && Objects.equals(rightData.methodName, leftData.methodName);
  }

  public static boolean multicastEquals(
      RogueBaseModifierDestroy46.Callback self, Object other, World world) {
    if (!delegateEquals(self, other, world)) return false;
    RogueBaseModifierDestroy46.Callback right = (RogueBaseModifierDestroy46.Callback) other;
    var leftDelegates = self.delegates;
    var rightDelegates = right.delegates;
    if (leftDelegates == null || rightDelegates == null)
      return leftDelegates == null && rightDelegates == null;
    if (leftDelegates.length != rightDelegates.length) return false;
    int index = 0;
    while (true) {
      int count = leftDelegates.length;
      if (count > LIMIT) throw new IllegalArgumentException("Excessive finite invocation list");
      if (index >= count) return true;
      var currentRight = require(right.delegates, "current right invocation list");
      if (index >= currentRight.length)
        throw new IllegalArgumentException("Short current right invocation list");
      var left = require(leftDelegates[index], "left invocation delegate");
      if (!world.virtualEquals(left, currentRight[index])) return false;
      index++;
      leftDelegates = require(self.delegates, "current left invocation list");
    }
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
