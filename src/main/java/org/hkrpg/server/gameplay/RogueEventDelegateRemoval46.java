package org.hkrpg.server.gameplay;

import org.hkrpg.server.gameplay.RogueBaseModifierDestroy46.Callback;

/**
 * Ordinary event delegate removal; native type lookup, allocation and array primitives stay bound.
 */
public final class RogueEventDelegateRemoval46 {
  public static final int LIMIT = 255;

  private RogueEventDelegateRemoval46() {}

  public interface World extends RogueEventDelegateEquality46.World {
    Object runtimeType(Callback callback);

    Callback virtualRemove(Callback source, Callback value);

    boolean arrayEquals(Callback[] source, Callback[] value);

    int lastSingleIndex(Callback[] source, Callback value, int start, int count);

    Callback allocateLike(Callback source);

    Callback[] allocateInvocationArray(int length);

    void copyPrefix(Callback[] source, Callback[] destination, int count);

    void copyRange(Callback[] source, int start, Callback[] destination, int target, int count);
  }

  public static Callback remove(Callback source, Callback value, World world) {
    if (source == null) return null;
    if (value == null) return source;
    World bound = require(world, "delegate removal world");
    Object leftType = bound.runtimeType(source);
    Object rightType = bound.runtimeType(value);
    if (leftType != rightType)
      throw new IllegalArgumentException("Different delegate runtime types");
    return bound.virtualRemove(source, value);
  }

  public static Callback delegateRemoveImpl(Callback source, Object value, World world) {
    require(source, "delegate removal receiver");
    return require(world, "delegate equality world").virtualEquals(source, value) ? null : source;
  }

  public static int lastIndexOf(Callback[] source, Callback[] value, World world) {
    require(source, "invocation source array");
    require(value, "invocation value array");
    if (source.length < value.length) return -1;
    if (source.length == value.length) {
      if (source.length == 0) return 0;
      finite(source.length);
      for (int i = 0; i < source.length; i++) {
        Callback left = require(source[i], "equal-length source callback");
        if (!require(world, "invocation comparison world").virtualEquals(left, value[i])) return -1;
      }
      return 0;
    }
    finite(source.length);
    finite(value.length);
    if (value.length == 0)
      throw new IllegalArgumentException("Empty shorter invocation value array");
    for (int position = source.length - value.length; position >= 0; position--) {
      boolean matched = true;
      for (int i = 0; i < value.length; i++) {
        Callback needle = require(value[i], "shorter invocation value callback");
        if (!require(world, "invocation comparison world")
            .virtualEquals(needle, source[position + i])) {
          matched = false;
          break;
        }
      }
      if (matched) return position;
    }
    return -1;
  }

  public static Callback multicastRemoveImpl(Callback source, Object value, World world) {
    require(source, "multicast removal receiver");
    if (value == null) return source;
    if (!(value instanceof Callback right))
      throw new IllegalArgumentException("Unmatched multicast delegate projection");
    Callback[] capturedLeft = source.delegates;
    Callback[] capturedRight = right.delegates;
    if (capturedLeft == null) {
      if (capturedRight == null)
        return RogueEventDelegateEquality46.multicastEquals(source, right, world) ? null : source;
      finite(capturedRight.length);
      for (Callback callback : capturedRight) {
        if (RogueEventDelegateEquality46.multicastEquals(source, callback, world)) return null;
      }
      return source;
    }
    World bound = require(world, "multicast removal world");
    if (capturedRight == null) {
      finite(capturedLeft.length);
      int index =
          bound.lastSingleIndex(capturedLeft, right, capturedLeft.length - 1, capturedLeft.length);
      if (index == -1) return source;
      Callback[] current = require(source.delegates, "current single removal invocation array");
      if (current.length <= 1)
        throw new IllegalStateException(
            "Matched single removal from an invocation array of length <= 1");
      if (current.length == 2) return current[index == 0 ? 1 : 0];
      Callback result = clonePrefix(source, bound);
      int count = require(source.delegates, "single removal allocation source").length - 1;
      Callback[] allocated = allocate(count, bound);
      result.delegates = allocated;
      bound.copyPrefix(source.delegates, allocated, index);
      current = require(source.delegates, "single removal suffix source");
      bound.copyRange(current, index + 1, result.delegates, index, current.length - index - 1);
      return result;
    }
    if (bound.arrayEquals(capturedLeft, capturedRight)) return null;
    int index = lastIndexOf(source.delegates, right.delegates, bound);
    if (index == -1) return source;
    Callback result = clonePrefix(source, bound);
    int count =
        require(source.delegates, "sequence removal allocation source").length
            - require(right.delegates, "sequence removal allocation value").length;
    Callback[] allocated = allocate(count, bound);
    result.delegates = allocated;
    bound.copyPrefix(source.delegates, allocated, index);
    Callback[] currentRight = require(right.delegates, "sequence removal suffix value");
    Callback[] currentLeft = require(source.delegates, "sequence removal suffix source");
    int start = index + currentRight.length;
    bound.copyRange(currentLeft, start, result.delegates, index, currentLeft.length - start);
    return result;
  }

  private static Callback clonePrefix(Callback source, World world) {
    Callback result = require(world.allocateLike(source), "delegate clone allocation");
    result.target = source.target;
    result.methodInfo = source.methodInfo;
    result.methodCodeTarget = result;
    result.invokePointer = source.trampolineRva;
    result.trampolineRva = source.trampolineRva;
    return result;
  }

  private static Callback[] allocate(int count, World world) {
    finite(count);
    return world.allocateInvocationArray(count);
  }

  private static void finite(int count) {
    if (count < 0 || count > LIMIT)
      throw new IllegalArgumentException("Unmatched finite invocation count");
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
