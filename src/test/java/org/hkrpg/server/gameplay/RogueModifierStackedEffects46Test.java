package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.RogueModifierStackedEffects46.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class RogueModifierStackedEffects46Test {
  @Test
  void actualStackEventUsesSharedBpListAndBoostModifierWhileFrameIsActive() {
    var s = state();
    s.stack.stackedAdditionalTeamBp = new ArrayList<>(List.of(new AdditionalBp(TEAM, 2)));
    s.boostContexts.add(TEAM);
    var removals = new World();
    removals.bp =
        c -> {
          assertEquals(1, s.stack.shelvedAdditionalTeamBp);
          assertFalse(s.stack.parameterFrames.isEmpty());
        };
    removals.boost =
        m -> {
          assertSame(s.stack.replacement, m);
          assertTrue(s.stack.stackedAdditionalTeamBp.isEmpty());
          assertFalse(s.stack.parameterFrames.isEmpty());
        };
    var w =
        new RogueModifierStackEvent46Test.BoundWorld() {
          public void popStackedAdditionalTeamBp(RogueModifierStackEvent46.State current) {
            assertSame(s.stack, current);
            super.popStackedAdditionalTeamBp(current);
            popAdditionalTeamBp(s, removals);
          }

          public void popBoostPointCallbackDecorate(RogueModifierStackEvent46.State current) {
            assertSame(s.stack, current);
            super.popBoostPointCallbackDecorate(current);
            popBoostCallbacks(s, removals);
          }

          public void postAdditionalTeamBpStack(
              RogueModifierStackEvent46.State current, boolean value) {
            assertTrue(current.parameterFrames.isEmpty());
            super.postAdditionalTeamBpStack(current, value);
          }
        };
    RogueModifierStackEvent46.onStack(s.stack, w);
    assertEquals(List.of("module", 2, "module", s.stack.replacement), removals.order);
    assertTrue(s.boostContexts.isEmpty());
    assertTrue(s.stack.parameterFrames.isEmpty());
    assertEquals("additional", w.order.getLast());
  }

  @Test
  void nullStatesRemainClosedForEveryOperation() {
    assertThrows(IllegalArgumentException.class, () -> popWeakness(null, null));
    assertThrows(IllegalArgumentException.class, () -> popAdditionalTeamBp(null, null));
    assertThrows(IllegalArgumentException.class, () -> popBoostCallbacks(null, null));
  }

  @Test
  void boostNullComponentsSkipWorldAndClear() {
    var s = state();
    s.boostContexts.add(null);
    popBoostCallbacks(s, null);
    assertTrue(s.boostContexts.isEmpty());
  }

  @Test
  void boostRemovalUsesSameSharedModifierIdentity() {
    var s = state();
    s.boostContexts.add(TEAM);
    var w = new World();
    popBoostCallbacks(s, w);
    assertEquals(List.of("module", s.stack.replacement), w.order);
    assertTrue(s.boostContexts.isEmpty());
  }

  @Test
  void boostMissingModuleFailsAndPreservesList() {
    var s = state();
    s.boostContexts.add(TEAM);
    var w = new World();
    w.module = null;
    assertThrows(IllegalArgumentException.class, () -> popBoostCallbacks(s, w));
    assertEquals(1, s.boostContexts.size());
  }

  @Test
  void boostCallbackFailureDoesNotClearList() {
    var s = state();
    s.boostContexts.add(TEAM);
    var w = new World();
    w.boost =
        x -> {
          throw new IllegalStateException("boost");
        };
    assertThrows(IllegalStateException.class, () -> popBoostCallbacks(s, w));
    assertEquals(1, s.boostContexts.size());
  }

  @Test
  void boostAppendIsObservedBeforeClearAndTrim() {
    var s = state();
    s.boostContexts.add(TEAM);
    var w = new World();
    int[] calls = {0};
    w.boost =
        x -> {
          if (++calls[0] == 1) s.boostContexts.add(TEAM);
        };
    popBoostCallbacks(s, w);
    assertEquals(2, calls[0]);
    assertTrue(s.boostContexts.isEmpty());
  }

  @Test
  void boostReplacementKeepsIndexAndClearsCurrentContainer() {
    var s = state();
    var old = s.boostContexts;
    old.add(TEAM);
    var next = new BoostContexts();
    next.add(null);
    next.add(TEAM);
    var w = new World();
    int[] calls = {0};
    w.boost =
        x -> {
          if (++calls[0] == 1) s.boostContexts = next;
        };
    popBoostCallbacks(s, w);
    assertEquals(2, calls[0]);
    assertEquals(1, old.size());
    assertTrue(next.isEmpty());
  }

  @Test
  void boostNullAfterLastCallbackFailsWithoutClearingOldList() {
    var s = state();
    var old = s.boostContexts;
    old.add(TEAM);
    var w = new World();
    w.boost = x -> s.boostContexts = null;
    assertThrows(IllegalArgumentException.class, () -> popBoostCallbacks(s, w));
    assertEquals(1, old.size());
  }

  @Test
  void boostTrimRereadsContainerAfterClear() {
    var s = state();
    var order = new ArrayList<String>();
    var next =
        new BoostContexts() {
          public void trimToSize() {
            order.add("trim-next");
            super.trimToSize();
          }
        };
    s.boostContexts =
        new BoostContexts() {
          public void clear() {
            order.add("clear-old");
            super.clear();
            s.boostContexts = next;
          }

          public void trimToSize() {
            fail("Trim must read current container");
          }
        };
    popBoostCallbacks(s, null);
    assertEquals(List.of("clear-old", "trim-next"), order);
  }

  @Test
  void boostClearFailureSkipsTrim() {
    var s = state();
    int[] trims = {0};
    s.boostContexts =
        new BoostContexts() {
          public void clear() {
            throw new IllegalStateException("clear");
          }

          public void trimToSize() {
            trims[0]++;
          }
        };
    assertThrows(IllegalStateException.class, () -> popBoostCallbacks(s, null));
    assertEquals(0, trims[0]);
  }

  @Test
  void boostNullAfterSuccessfulClearFailsAtTrim() {
    var s = state();
    s.boostContexts =
        new BoostContexts() {
          public void clear() {
            super.clear();
            s.boostContexts = null;
          }
        };
    assertThrows(IllegalArgumentException.class, () -> popBoostCallbacks(s, null));
    assertNull(s.boostContexts);
  }

  @Test
  void bpCountGetterMutationIsObservedBeforeSelectedEntry() {
    var s = state();
    var next = new ArrayList<>(List.of(new AdditionalBp(TEAM, 42)));
    s.stack.stackedAdditionalTeamBp =
        new ArrayList<Object>() {
          public int size() {
            s.stack.stackedAdditionalTeamBp = next;
            return 1;
          }
        };
    var w = new World();
    popAdditionalTeamBp(s, w);
    assertEquals(List.of("module", 42), w.order);
    assertTrue(next.isEmpty());
  }

  @Test
  void boostCountGetterMutationIsObservedBeforeSelectedComponent() {
    var s = state();
    var next = new BoostContexts();
    next.add(TEAM);
    s.boostContexts =
        new BoostContexts() {
          public int size() {
            s.boostContexts = next;
            return 1;
          }
        };
    var w = new World();
    popBoostCallbacks(s, w);
    assertEquals(List.of("module", s.stack.replacement), w.order);
    assertTrue(next.isEmpty());
  }

  @Test
  void excessiveWeaknessListStopsAfter255ExecutedEntries() {
    var s = state();
    s.weaknesses.addAll(Collections.nCopies(256, new Weakness(ABILITY, 2, 1)));
    var w = new World();
    int[] calls = {0};
    w.weak = c -> calls[0]++;
    assertThrows(IllegalArgumentException.class, () -> popWeakness(s, w));
    assertEquals(255, calls[0]);
    assertEquals(256, s.weaknesses.size());
  }

  @Test
  void excessiveBpListStopsAfter255ExecutedEntries() {
    var s = state();
    s.stack.stackedAdditionalTeamBp =
        new ArrayList<>(Collections.nCopies(256, new AdditionalBp(TEAM, 1)));
    var w = new World();
    int[] calls = {0};
    w.bp = c -> calls[0]++;
    assertThrows(IllegalArgumentException.class, () -> popAdditionalTeamBp(s, w));
    assertEquals(255, calls[0]);
    assertEquals(256, s.stack.stackedAdditionalTeamBp.size());
  }

  @Test
  void excessiveBoostListStopsAfter255ExecutedEntries() {
    var s = state();
    s.boostContexts.addAll(Collections.nCopies(256, TEAM));
    var w = new World();
    int[] calls = {0};
    w.boost = c -> calls[0]++;
    assertThrows(IllegalArgumentException.class, () -> popBoostCallbacks(s, w));
    assertEquals(255, calls[0]);
    assertEquals(256, s.boostContexts.size());
  }

  @Test
  void populatedWeaknessRequiresExplicitWorld() {
    var s = state();
    s.weaknesses.add(new Weakness(ABILITY, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> popWeakness(s, null));
    assertEquals(1, s.weaknesses.size());
  }

  @Test
  void populatedBpRequiresExplicitWorld() {
    var s = state();
    s.stack.stackedAdditionalTeamBp = new ArrayList<>(List.of(new AdditionalBp(TEAM, 1)));
    assertThrows(IllegalArgumentException.class, () -> popAdditionalTeamBp(s, null));
    assertEquals(1, s.stack.stackedAdditionalTeamBp.size());
  }

  @Test
  void populatedBoostRequiresExplicitWorld() {
    var s = state();
    s.boostContexts.add(TEAM);
    assertThrows(IllegalArgumentException.class, () -> popBoostCallbacks(s, null));
    assertEquals(1, s.boostContexts.size());
  }

  static final Object ABILITY = new Object(),
      WEAKNESS = new Object(),
      ENTITY = new Object(),
      TEAM = new Object(),
      MODULE = new Object();

  static State state() {
    var s = new State(RogueModifierStackEvent46Test.state());
    s.weaknesses = new ArrayList<>();
    s.stack.stackedAdditionalTeamBp = new ArrayList<>();
    s.boostContexts = new BoostContexts();
    return s;
  }

  static class World implements RogueModifierStackedEffects46.World {
    Object weakness = WEAKNESS, entity = ENTITY, module = MODULE;
    final List<Object> order = new ArrayList<>();
    Consumer<Integer> weak = c -> {}, bp = c -> {};
    Consumer<RogueModifierReplace46.State> boost = s -> {};

    public Object weaknessComponent(Object c) {
      order.add("weakness");
      assertSame(ABILITY, c);
      return weakness;
    }

    public Object abilityEntity(Object c) {
      order.add("entity");
      assertSame(ABILITY, c);
      return entity;
    }

    public void unstackWeakness(Object w, Object e, int op, int count) {
      assertSame(WEAKNESS, w);
      assertSame(entity, e);
      order.add(List.of(op, count));
      weak.accept(count);
    }

    public Object teamBoostPointModule(Object c) {
      assertSame(TEAM, c);
      order.add("module");
      return module;
    }

    public void removeAdditionalTeamBp(Object m, int count) {
      assertSame(MODULE, m);
      order.add(count);
      bp.accept(count);
    }

    public void removeBoostCallback(Object m, RogueModifierReplace46.State s) {
      assertSame(MODULE, m);
      order.add(s);
      boost.accept(s);
    }
  }

  @Test
  void missingSharedStackStateIsClosed() {
    assertThrows(IllegalArgumentException.class, () -> new State(null));
  }

  @Test
  void emptyListsClearWithoutWorldReads() {
    var s = state();
    popWeakness(s, null);
    popAdditionalTeamBp(s, null);
    popBoostCallbacks(s, null);
  }

  @Test
  void absentWeaknessListFailsButOtherAbsentListsSkip() {
    var s = state();
    s.weaknesses = null;
    s.stack.stackedAdditionalTeamBp = null;
    s.boostContexts = null;
    assertThrows(IllegalArgumentException.class, () -> popWeakness(s, null));
    popAdditionalTeamBp(s, null);
    popBoostCallbacks(s, null);
  }

  @Test
  void weaknessEntriesPreserveSignedOperationAndIndexBits() {
    var s = state();
    s.weaknesses.add(new Weakness(ABILITY, Integer.MIN_VALUE, Integer.MAX_VALUE));
    var w = new World();
    popWeakness(s, w);
    assertEquals(
        List.of("weakness", "entity", List.of(Integer.MIN_VALUE, Integer.MAX_VALUE)), w.order);
    assertTrue(s.weaknesses.isEmpty());
  }

  @Test
  void weaknessEntityMayBeExplicitlyNull() {
    var s = state();
    s.weaknesses.add(new Weakness(ABILITY, 2, -3));
    var w = new World();
    w.entity = null;
    popWeakness(s, w);
    assertTrue(s.weaknesses.isEmpty());
  }

  @Test
  void missingWeaknessEntryFailsWithoutClearingList() {
    var s = state();
    s.weaknesses.add(null);
    assertThrows(IllegalArgumentException.class, () -> popWeakness(s, new World()));
    assertEquals(1, s.weaknesses.size());
  }

  @Test
  void missingWeaknessAbilityFailsBeforeWorldReads() {
    var s = state();
    s.weaknesses.add(new Weakness(null, 0, 1));
    var w = new World();
    assertThrows(IllegalArgumentException.class, () -> popWeakness(s, w));
    assertTrue(w.order.isEmpty());
  }

  @Test
  void missingCharacterWeaknessFailsBeforeEntityRead() {
    var s = state();
    s.weaknesses.add(new Weakness(ABILITY, 0, 1));
    var w = new World();
    w.weakness = null;
    assertThrows(IllegalArgumentException.class, () -> popWeakness(s, w));
    assertEquals(List.of("weakness"), w.order);
    assertEquals(1, s.weaknesses.size());
  }

  @Test
  void weaknessCallbackFailurePreservesEntriesAndSuccessfulRemovalPrefix() {
    var s = state();
    s.weaknesses.add(new Weakness(ABILITY, 2, 1));
    s.weaknesses.add(new Weakness(ABILITY, 2, 2));
    var w = new World();
    w.weak =
        c -> {
          if (c == 2) throw new IllegalStateException("remove");
        };
    assertThrows(IllegalStateException.class, () -> popWeakness(s, w));
    assertEquals(2, s.weaknesses.size());
    assertEquals(
        List.of("weakness", "entity", List.of(2, 1), "weakness", "entity", List.of(2, 2)), w.order);
  }

  @Test
  void appendedWeaknessEntriesAreObservedOnNextIteration() {
    var s = state();
    s.weaknesses.add(new Weakness(ABILITY, 1, 1));
    var w = new World();
    w.weak =
        c -> {
          if (c == 1) s.weaknesses.add(new Weakness(ABILITY, 1, 2));
        };
    popWeakness(s, w);
    assertTrue(w.order.contains(List.of(1, 2)));
    assertTrue(s.weaknesses.isEmpty());
  }

  @Test
  void replacingWeaknessListKeepsIndexAndClearsCurrentList() {
    var s = state();
    var old = s.weaknesses;
    old.add(new Weakness(ABILITY, 0, 1));
    var next =
        new ArrayList<Weakness>(List.of(new Weakness(ABILITY, 0, 99), new Weakness(ABILITY, 0, 2)));
    var w = new World();
    w.weak =
        c -> {
          if (c == 1) s.weaknesses = next;
        };
    popWeakness(s, w);
    assertEquals(1, old.size());
    assertTrue(next.isEmpty());
    assertTrue(w.order.contains(List.of(0, 2)));
    assertFalse(w.order.contains(List.of(0, 99)));
  }

  @Test
  void clearingWeaknessListDuringRemovalStopsFollowingEntries() {
    var s = state();
    s.weaknesses.add(new Weakness(ABILITY, 0, 1));
    s.weaknesses.add(new Weakness(ABILITY, 0, 2));
    var w = new World();
    w.weak = c -> s.weaknesses.clear();
    popWeakness(s, w);
    assertFalse(w.order.contains(List.of(0, 2)));
  }

  @Test
  void nullingWeaknessListAfterLastCallbackStillFails() {
    var s = state();
    var old = s.weaknesses;
    old.add(new Weakness(ABILITY, 0, 1));
    var w = new World();
    w.weak = c -> s.weaknesses = null;
    assertThrows(IllegalArgumentException.class, () -> popWeakness(s, w));
    assertEquals(1, old.size());
  }

  @Test
  void weaknessCountGetterReplacementIsObservedBeforeEntryRead() {
    var s = state();
    var next = new ArrayList<Weakness>(List.of(new Weakness(ABILITY, 3, 7)));
    s.weaknesses =
        new ArrayList<>() {
          public int size() {
            s.weaknesses = next;
            return 1;
          }
        };
    var w = new World();
    popWeakness(s, w);
    assertTrue(w.order.contains(List.of(3, 7)));
    assertTrue(next.isEmpty());
  }

  @Test
  void bpNullTeamComponentSkipsWorldAndClearsList() {
    var s = state();
    s.stack.stackedAdditionalTeamBp = new ArrayList<>(List.of(new AdditionalBp(null, 1)));
    popAdditionalTeamBp(s, null);
    assertTrue(s.stack.stackedAdditionalTeamBp.isEmpty());
  }

  @Test
  void bpEntriesPreserveFullSignedIntegerHandle() {
    var s = state();
    s.stack.stackedAdditionalTeamBp =
        new ArrayList<>(List.of(new AdditionalBp(TEAM, Integer.MIN_VALUE)));
    var w = new World();
    popAdditionalTeamBp(s, w);
    assertEquals(List.of("module", Integer.MIN_VALUE), w.order);
    assertTrue(s.stack.stackedAdditionalTeamBp.isEmpty());
  }

  @Test
  void bpMissingModuleFailsBeforeClear() {
    var s = state();
    s.stack.stackedAdditionalTeamBp = new ArrayList<>(List.of(new AdditionalBp(TEAM, 2)));
    var w = new World();
    w.module = null;
    assertThrows(IllegalArgumentException.class, () -> popAdditionalTeamBp(s, w));
    assertEquals(1, s.stack.stackedAdditionalTeamBp.size());
  }

  @Test
  void bpWrongOrNullCompiledRecordIsClosed() {
    var s = state();
    for (Object value : Arrays.asList(new Object(), null)) {
      s.stack.stackedAdditionalTeamBp = new ArrayList<>(Collections.singletonList(value));
      assertThrows(IllegalArgumentException.class, () -> popAdditionalTeamBp(s, new World()));
      assertEquals(1, s.stack.stackedAdditionalTeamBp.size());
    }
  }

  @Test
  void bpListReplacementAfterRemovalUsesCurrentIndexAndList() {
    var s = state();
    var old = new ArrayList<>(List.of(new AdditionalBp(TEAM, 1)));
    var next = new ArrayList<>(List.of(new AdditionalBp(TEAM, 99), new AdditionalBp(TEAM, 2)));
    s.stack.stackedAdditionalTeamBp = old;
    var w = new World();
    w.bp =
        c -> {
          if (c == 1) s.stack.stackedAdditionalTeamBp = next;
        };
    popAdditionalTeamBp(s, w);
    assertEquals(List.of("module", 1, "module", 2), w.order);
    assertEquals(1, old.size());
    assertTrue(next.isEmpty());
  }

  @Test
  void bpNullAfterRemovalFailsAndDoesNotClearOriginalList() {
    var s = state();
    var old = new ArrayList<>(List.of(new AdditionalBp(TEAM, 1)));
    s.stack.stackedAdditionalTeamBp = old;
    var w = new World();
    w.bp = c -> s.stack.stackedAdditionalTeamBp = null;
    assertThrows(IllegalArgumentException.class, () -> popAdditionalTeamBp(s, w));
    assertEquals(1, old.size());
  }

  @Test
  void bpCallbackFailurePreservesCurrentList() {
    var s = state();
    s.stack.stackedAdditionalTeamBp = new ArrayList<>(List.of(new AdditionalBp(TEAM, 1)));
    var w = new World();
    w.bp =
        c -> {
          throw new IllegalStateException("bp");
        };
    assertThrows(IllegalStateException.class, () -> popAdditionalTeamBp(s, w));
    assertEquals(1, s.stack.stackedAdditionalTeamBp.size());
  }
}
