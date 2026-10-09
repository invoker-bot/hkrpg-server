package org.hkrpg.server.gameplay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Finite, explicitly bound prefix of the native dynamic-value context lookup. */
public final class RogueResonanceContext46 implements RogueResonanceLookup46 {
  private final RogueResonanceVariables46 callbackTemporary;
  private final List<RogueResonanceVariables46> extraFramesTopFirst;
  private final RogueResonanceVariables46 template;
  private final RogueResonanceVariables46 modifier;

  public RogueResonanceContext46(
      RogueResonanceVariables46 callbackTemporary,
      List<RogueResonanceVariables46> extraFramesTopFirst,
      RogueResonanceVariables46 template,
      RogueResonanceVariables46 modifier) {
    if (extraFramesTopFirst == null || extraFramesTopFirst.size() > 255) {
      throw new IllegalArgumentException("Invalid finite resonance parameter stack");
    }
    // A frame without a lookup is skipped by the native linked-stack traversal.
    var frames = new ArrayList<>(extraFramesTopFirst);
    if (frames.size() > 255) {
      throw new IllegalArgumentException("Invalid finite resonance parameter stack");
    }
    this.callbackTemporary = callbackTemporary;
    this.extraFramesTopFirst = Collections.unmodifiableList(frames);
    this.template = template;
    this.modifier = modifier;
  }

  public RogueResonanceVariables46 select(int scope, int key) {
    if (scope == 0) {
      var selected = findDefaultPrefix(key);
      if (selected != null) return selected;
      throw new IllegalArgumentException("Unmatched resonance context lookup remainder");
    }
    if (scope == 9) {
      if (contains(callbackTemporary, key)) return callbackTemporary;
      throw new IllegalArgumentException("Missing callback temporary variable");
    }
    if (scope == 1) {
      if (contains(template, key)) return template;
      throw new IllegalArgumentException("Missing template variable");
    }
    if (scope == 2) {
      if (contains(modifier, key)) return modifier;
      throw new IllegalArgumentException("Missing modifier variable");
    }
    // The native Context branch continues through ability/entity/custom/global sources.
    // This prefix has no binding for those sources and must not invent a missing value.
    throw new IllegalArgumentException("Unmatched resonance context lookup remainder or scope");
  }

  RogueResonanceVariables46 findDefaultPrefix(int key) {
    if (contains(callbackTemporary, key)) return callbackTemporary;
    for (var frame : extraFramesTopFirst) if (contains(frame, key)) return frame;
    if (contains(template, key)) return template;
    return contains(modifier, key) ? modifier : null;
  }

  private static boolean contains(RogueResonanceVariables46 lookup, int key) {
    return lookup != null && lookup.contains(key);
  }
}
