package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RogueModifierCaster46Test {
  static class World implements RogueModifierCaster46.World {
    final Object owner = new Object(),
        target = new Object(),
        component = new Object(),
        existing = new Object(),
        created = new Object();
    final List<String> calls = new ArrayList<>();
    Object ability;
    Object found = existing;
    boolean turnBased = true;

    public Object ownerAbilityInstance() {
      calls.add("ability");
      return ability;
    }

    public Object ownerEntity() {
      calls.add("owner");
      return owner;
    }

    public Object abilityOwnerEntity(Object instance) {
      calls.add("abilityOwner");
      return owner;
    }

    public boolean isTurnBasedAbility(Object instance) {
      calls.add("cast");
      return turnBased;
    }

    public Object turnBasedAbilityComponent(Object entity) {
      calls.add(entity == owner ? "component:owner" : "component:target");
      return component;
    }

    public Object findAbilityInstance(Object component, String name) {
      assertSame(this.component, component);
      assertEquals("INTERNAL_ModifierCasterTransit", name);
      calls.add("find");
      return found;
    }

    public Object addAbilityWithInheritTarget(
        Object component, Map<String, String> config, Object inheritedTargets, Object option) {
      assertSame(this.component, component);
      assertEquals(Map.of("Name", "INTERNAL_ModifierCasterTransit"), config);
      assertNull(inheritedTargets);
      assertNull(option);
      calls.add("create");
      return created;
    }
  }

  @Test
  void inheritanceRejectsAnIncompatibleOwnerAbilityRatherThanFallingBack() {
    var w = new World();
    w.ability = new Object();
    w.turnBased = false;
    assertThrows(ClassCastException.class, () -> RogueModifierCaster46.select(0, w.target, w));
    assertEquals(List.of("ability", "cast"), w.calls);
  }

  @Test
  void casterSelfUsesContextOwnerAndReusesTransitInstance() {
    var w = new World();
    assertSame(w.existing, RogueModifierCaster46.select(1, w.target, w));
    assertEquals(List.of("owner", "component:owner", "find", "cast"), w.calls);
  }

  @Test
  void transitCreationHappensOnlyAfterTheOriginalNamedLookupReturnsNull() {
    var w = new World();
    w.found = null;
    assertSame(w.created, RogueModifierCaster46.transit(w.component, w));
    assertEquals(List.of("find", "create"), w.calls);
  }

  @Test
  void compatibleOwnerAbilityIsReturnedDirectlyWithoutComponentOrTransitLookup() {
    var w = new World();
    w.ability = new Object();
    assertSame(w.ability, RogueModifierCaster46.select(0, null, w));
    assertEquals(List.of("ability", "cast"), w.calls);
  }

  @Test
  void stableAbsentOwnerAbilityIsReadTwiceBeforeOwnerEntityFallback() {
    var w = new World();
    assertSame(w.existing, RogueModifierCaster46.select(0, w.target, w));
    assertEquals(
        List.of("ability", "ability", "owner", "component:owner", "find", "cast"), w.calls);
  }

  @Test
  void abilityAppearingDuringFallbackUsesTheThirdGetterResultForItsEntity() {
    Object appeared = new Object(), actual = new Object();
    var w =
        new World() {
          int reads;

          public Object ownerAbilityInstance() {
            calls.add("ability");
            return switch (reads++) {
              case 0 -> null;
              case 1 -> appeared;
              default -> actual;
            };
          }

          public Object abilityOwnerEntity(Object instance) {
            assertSame(actual, instance);
            calls.add("abilityOwner");
            return target;
          }
        };
    assertSame(w.existing, RogueModifierCaster46.select(0, w.target, w));
    assertEquals(
        List.of(
            "ability", "ability", "ability", "abilityOwner", "component:target", "find", "cast"),
        w.calls);
  }

  @Test
  void abilityDisappearingOnTheThirdReadThrowsBeforeEntityLookup() {
    var w =
        new World() {
          int reads;

          public Object ownerAbilityInstance() {
            calls.add("ability");
            return reads++ == 1 ? new Object() : null;
          }
        };
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierCaster46.select(0, w.target, w));
    assertEquals(List.of("ability", "ability", "ability"), w.calls);
  }

  @Test
  void targetSelfDoesNotReadTheTaskOwnerOrAbility() {
    var w = new World();
    assertSame(w.existing, RogueModifierCaster46.select(2, w.target, w));
    assertEquals(List.of("component:target", "find", "cast"), w.calls);
  }

  @Test
  void absentTargetSelfAndUnknownModesReturnNullWithoutContextReads() {
    var w = new World();
    assertNull(RogueModifierCaster46.select(2, null, w));
    for (int mode : new int[] {Integer.MIN_VALUE, -1, 3, Integer.MAX_VALUE})
      assertNull(RogueModifierCaster46.select(mode, w.target, w));
    assertTrue(w.calls.isEmpty());
  }

  @Test
  void missingContextOwnerThrowsBeforeReadingComponent() {
    var w =
        new World() {
          public Object ownerEntity() {
            calls.add("owner");
            return null;
          }
        };
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierCaster46.select(1, w.target, w));
    assertEquals(List.of("owner"), w.calls);
    w.calls.clear();
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierCaster46.select(0, w.target, w));
    assertEquals(List.of("ability", "ability", "owner"), w.calls);
  }

  @Test
  void missingAbilityOwnerEntityDoesNotUseContextOwnerAsAnAdditionalFallback() {
    var w =
        new World() {
          int reads;

          public Object ownerAbilityInstance() {
            calls.add("ability");
            return reads++ == 0 ? null : new Object();
          }

          public Object abilityOwnerEntity(Object instance) {
            calls.add("abilityOwner");
            return null;
          }
        };
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierCaster46.select(0, w.target, w));
    assertEquals(List.of("ability", "ability", "ability", "abilityOwner"), w.calls);
  }

  @Test
  void missingComponentStopsBeforeNamedLookup() {
    var w =
        new World() {
          public Object turnBasedAbilityComponent(Object entity) {
            calls.add("component");
            return null;
          }
        };
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierCaster46.select(1, w.target, w));
    assertEquals(List.of("owner", "component"), w.calls);
  }

  @Test
  void incompatibleExistingTransitInstanceIsNotReplacedWithANewOne() {
    var w = new World();
    w.turnBased = false;
    assertThrows(ClassCastException.class, () -> RogueModifierCaster46.transit(w.component, w));
    assertEquals(List.of("find", "cast"), w.calls);
  }

  @Test
  void namedLookupFailureNeverCreatesAReplacementInstance() {
    var problem = new IllegalStateException("lookup");
    var w =
        new World() {
          public Object findAbilityInstance(Object component, String name) {
            super.findAbilityInstance(component, name);
            throw problem;
          }
        };
    assertSame(
        problem,
        assertThrows(
            IllegalStateException.class, () -> RogueModifierCaster46.transit(w.component, w)));
    assertEquals(List.of("find"), w.calls);
  }

  @Test
  void creationFailurePropagatesAfterTheMissingInstanceLookup() {
    var problem = new IllegalStateException("create");
    var w =
        new World() {
          public Object addAbilityWithInheritTarget(
              Object component,
              Map<String, String> config,
              Object inheritedTargets,
              Object option) {
            super.addAbilityWithInheritTarget(component, config, inheritedTargets, option);
            throw problem;
          }
        };
    w.found = null;
    assertSame(
        problem,
        assertThrows(
            IllegalStateException.class, () -> RogueModifierCaster46.transit(w.component, w)));
    assertEquals(List.of("find", "create"), w.calls);
  }

  @Test
  void repeatedSelectionUsesTheLiveInstanceStoreRatherThanAJavaSelectorCache() {
    var w =
        new World() {
          public Object addAbilityWithInheritTarget(
              Object component,
              Map<String, String> config,
              Object inheritedTargets,
              Object option) {
            var created =
                super.addAbilityWithInheritTarget(component, config, inheritedTargets, option);
            found = created;
            return created;
          }
        };
    w.found = null;
    assertSame(w.created, RogueModifierCaster46.select(2, w.target, w));
    assertSame(w.created, RogueModifierCaster46.select(2, w.target, w));
    assertEquals(
        List.of("component:target", "find", "create", "component:target", "find", "cast"), w.calls);
  }

  @Test
  void callerProjectionExceptionStopsAllFurtherFallbackReads() {
    var problem = new IllegalStateException("context");
    var w =
        new World() {
          public Object ownerAbilityInstance() {
            calls.add("ability");
            throw problem;
          }
        };
    assertSame(
        problem,
        assertThrows(
            IllegalStateException.class, () -> RogueModifierCaster46.select(0, w.target, w)));
    assertEquals(List.of("ability"), w.calls);
  }

  @Test
  void originalTransitDefinitionIsOnlyTheImmutableNameAssignment() {
    assertEquals(
        Map.of("Name", "INTERNAL_ModifierCasterTransit"), RogueModifierCaster46.TRANSIT_CONFIG);
    assertThrows(
        UnsupportedOperationException.class,
        () -> RogueModifierCaster46.TRANSIT_CONFIG.put("OnAdd", "fake"));
  }

  static class Inherited extends RogueModifierSelection46Test.Bound
      implements RogueModifierCaster46.Bindings {
    final World world = new World();
    final List<Object> selected = new ArrayList<>();

    public Object ownerAbilityInstance() {
      return world.ownerAbilityInstance();
    }

    public Object ownerEntity() {
      return world.ownerEntity();
    }

    public Object abilityOwnerEntity(Object instance) {
      return world.abilityOwnerEntity(instance);
    }

    public boolean isTurnBasedAbility(Object instance) {
      return world.isTurnBasedAbility(instance);
    }

    public Object turnBasedAbilityComponent(Object entity) {
      return world.turnBasedAbilityComponent(entity);
    }

    public Object findAbilityInstance(Object component, String name) {
      return world.findAbilityInstance(component, name);
    }

    public Object addAbilityWithInheritTarget(
        Object component, Map<String, String> config, Object inheritedTargets, Object option) {
      return world.addAbilityWithInheritTarget(component, config, inheritedTargets, option);
    }

    public void applyQualified(RogueModifierSelection46.Qualified selection) {
      RogueModifierCaster46.Bindings.super.applyQualified(selection);
    }

    public void applyInherited(RogueModifierSelection46.Qualified selection, Object ability) {
      selected.add(ability);
      super.applyQualified(selection);
    }

    RogueTaskSequence46.Task inheritedTask() {
      return RogueModifierCaster46.factory(this)
          .create(new RogueResonanceOnAdd46.Modifier(null, "Caster", "Original"));
    }
  }

  @Test
  void entryFactoryResolvesInheritanceAfterQualificationAndPassesAbilityIdentityToApplication() {
    var b = new Inherited();
    b.flag = true;
    b.world.ability = new Object();
    var task = b.inheritedTask();
    task.begin();
    assertEquals(RogueTaskSequence46.SUCCESS, task.state());
    assertEquals(1, b.applied.size());
    assertSame(b.world.ability, b.selected.getFirst());
    assertEquals(List.of("ability", "cast"), b.world.calls);
    assertEquals(
        List.of("extra", "apply", "finished"), b.calls.subList(b.calls.size() - 3, b.calls.size()));
  }

  @Test
  void entryQualificationAndAdditionalRestrictionRejectionsNeverReadCasterContext() {
    var b = new Inherited();
    b.inheritedTask().begin();
    assertTrue(b.world.calls.isEmpty());
    assertTrue(b.selected.isEmpty());
    b.flag = true;
    b.extra = false;
    b.inheritedTask().begin();
    assertTrue(b.world.calls.isEmpty());
    assertTrue(b.selected.isEmpty());
  }

  @Test
  void entryCharmAndEmptyTargetsDoNotCreateTransitAbilities() {
    var b = new Inherited();
    b.charm = true;
    b.inheritedTask().begin();
    assertTrue(b.world.calls.isEmpty());
    b.charm = false;
    b.evaluated = List.of();
    b.inheritedTask().begin();
    assertTrue(b.world.calls.isEmpty());
  }

  @Test
  void failedInheritanceKeepsRegisteredAliasAndDoesNotApplyOrFinalize() {
    var b = new Inherited();
    b.flag = true;
    b.reference = "Definition";
    b.world.ability = new Object();
    b.world.turnBased = false;
    var task = b.inheritedTask();
    assertThrows(ClassCastException.class, task::begin);
    assertEquals(RogueTaskSequence46.READY, task.state());
    assertTrue(b.calls.contains("alias:Original:Definition"));
    assertTrue(b.calls.contains("extra"));
    assertFalse(b.calls.contains("finished"));
    assertTrue(b.applied.isEmpty());
  }

  @Test
  void entryFallbackUsesContextOwnerThatDiffersFromTheOriginalSourceAndTarget() {
    var b = new Inherited();
    b.flag = true;
    assertNotSame(b.source, b.world.owner);
    assertNotSame(b.target, b.world.owner);
    b.inheritedTask().begin();
    assertSame(b.world.existing, b.selected.getFirst());
    assertEquals(
        List.of("ability", "ability", "owner", "component:owner", "find", "cast"), b.world.calls);
  }

  @Test
  void eachTargetRereadsLiveOwnerAbilityAndDoesNotReusePriorCasterSelection() {
    var b =
        new Inherited() {
          public void applyInherited(RogueModifierSelection46.Qualified q, Object ability) {
            super.applyInherited(q, ability);
            world.ability = world.existing;
          }
        };
    b.flag = true;
    b.world.ability = new Object();
    Object first = b.world.ability;
    b.evaluated = List.of(b.target, new Object());
    b.inheritedTask().begin();
    assertEquals(2, b.selected.size());
    assertSame(first, b.selected.getFirst());
    assertSame(b.world.existing, b.selected.get(1));
    assertEquals(List.of("ability", "cast", "ability", "cast"), b.world.calls);
  }

  @Test
  void applicationFailurePreservesSelectedAbilityAndEarlierActions() {
    var problem = new IllegalStateException("effect");
    var b =
        new Inherited() {
          public void applyInherited(RogueModifierSelection46.Qualified q, Object ability) {
            super.applyInherited(q, ability);
            throw problem;
          }
        };
    b.flag = true;
    b.world.ability = new Object();
    var task = b.inheritedTask();
    assertSame(problem, assertThrows(IllegalStateException.class, task::begin));
    assertEquals(1, b.selected.size());
    assertEquals(1, b.applied.size());
    assertEquals(RogueTaskSequence46.READY, task.state());
    assertFalse(b.calls.contains("finished"));
  }

  @Test
  void bindingsAreRequiredEvenForModesWithNoNativeContextReads() {
    assertThrows(IllegalArgumentException.class, () -> RogueModifierCaster46.select(3, null, null));
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierCaster46.transit(null, new World()));
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierCaster46.transit(new Object(), null));
    assertThrows(IllegalArgumentException.class, () -> RogueModifierCaster46.factory(null));
  }
}
