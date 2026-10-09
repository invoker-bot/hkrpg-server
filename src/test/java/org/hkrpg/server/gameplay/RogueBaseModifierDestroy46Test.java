package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueBaseModifierDestroy46Test {
  static final Object ENTITY = new Object(),
      GAME_WORLD = new Object(),
      MANAGER = new Object(),
      FIRST_INFO = new Object(),
      SECOND_INFO = new Object();

  record Withdrawal(
      Object manager, int event, int entityId, RogueBaseModifierDestroy46.Callback callback) {}

  static class World implements RogueBaseModifierDestroy46.World {
    final List<String> order = new ArrayList<>();
    final List<Withdrawal> withdrawn = new ArrayList<>();
    final List<RogueBaseModifierDestroy46.Callback> allocated = new ArrayList<>();
    Object entity = ENTITY, gameWorld = GAME_WORLD, manager = MANAGER;
    int id = 7, allocateCalls, bindingCalls, unregisterCalls;
    String fail;
    int failOrdinal = 1, nullAllocation = 0;
    boolean mismatchedBinding, nullMethodInfo;
    Runnable onFirstWithdrawal = () -> {}, onAllocation = () -> {}, onBinding = () -> {};

    void phase(String name, int ordinal) {
      order.add(name);
      if (name.equals(fail) && ordinal == failOrdinal) throw new IllegalStateException(name);
    }

    public Object ownerAbilityComponent(RogueModifierDestroy46.State s) {
      phase("owner", 1);
      return s.cleanup.ownerAbilityComponent;
    }

    public Object ownerEntity(Object component) {
      phase("entity", 1);
      return entity;
    }

    public Object entityWorld(Object capturedEntity) {
      assertSame(ENTITY, capturedEntity);
      phase("world", 1);
      return gameWorld;
    }

    public Object worldEventManager(Object capturedWorld) {
      assertSame(GAME_WORLD, capturedWorld);
      phase("manager", 1);
      return manager;
    }

    public int entityId(Object capturedEntity) {
      assertSame(ENTITY, capturedEntity);
      phase("id", order.stream().filter("id"::equals).toList().size() + 1);
      return id;
    }

    public RogueBaseModifierDestroy46.Callback allocateCallback() {
      phase("allocate", ++allocateCalls);
      onAllocation.run();
      if (nullAllocation == allocateCalls) return null;
      var c = new RogueBaseModifierDestroy46.Callback();
      allocated.add(c);
      return c;
    }

    public RogueBaseModifierDestroy46.MethodBinding callbackMethodBinding(int method) {
      phase("binding", ++bindingCalls);
      onBinding.run();
      return new RogueBaseModifierDestroy46.MethodBinding(
          mismatchedBinding ? -1 : method,
          nullMethodInfo ? null : method == 508636 ? FIRST_INFO : SECOND_INFO,
          method == 508636 ? 0x1234L : 0x5678L);
    }

    public void unregisterEventListener(
        Object capturedManager,
        int event,
        int capturedId,
        RogueBaseModifierDestroy46.Callback callback) {
      phase("unregister", ++unregisterCalls);
      withdrawn.add(new Withdrawal(capturedManager, event, capturedId, callback));
      if (unregisterCalls == 1) onFirstWithdrawal.run();
    }
  }

  @Test
  void baseDestructionPublishesReasonThenWithdrawsBothDistinctCallbacksInOrder() {
    var s = RogueModifierDestroy46Test.state();
    var w = new World();
    RogueBaseModifierDestroy46.destroy(s, 4, w);
    assertEquals(
        List.of(
            "owner",
            "entity",
            "world",
            "manager",
            "id",
            "allocate",
            "binding",
            "unregister",
            "id",
            "allocate",
            "binding",
            "unregister"),
        w.order);
    assertEquals(4, s.deathSource);
    assertEquals(1, s.cleanup.candidate.state);
    assertEquals(207, w.withdrawn.get(0).event());
    assertEquals(208, w.withdrawn.get(1).event());
    assertNotSame(w.withdrawn.get(0).callback(), w.withdrawn.get(1).callback());
    for (int i = 0; i < 2; i++) {
      var item = w.withdrawn.get(i);
      var c = item.callback();
      assertSame(MANAGER, item.manager());
      assertEquals(7, item.entityId());
      assertSame(s, c.target);
      assertSame(s, c.methodCodeTarget);
      assertSame(i == 0 ? FIRST_INFO : SECOND_INFO, c.methodInfo);
      assertEquals(i == 0 ? 0x1234L : 0x5678L, c.methodPointer);
      assertEquals(c.methodPointer, c.invokePointer);
      assertEquals(0x3d10L, c.trampolineRva);
    }
  }

  @Test
  void originalReasonOverridesCurrentReasonBeforeVirtualOwnerFailure() {
    var s = RogueModifierDestroy46Test.state();
    s.deathSource = 9;
    var w = new World();
    w.fail = "owner";
    assertThrows(IllegalStateException.class, () -> RogueBaseModifierDestroy46.destroy(s, 4, w));
    assertEquals(4, s.deathSource);
    assertEquals(List.of("owner"), w.order);
  }

  @Test
  void nullWorldStillLeavesPublishedReason() {
    var s = RogueModifierDestroy46Test.state();
    assertThrows(
        IllegalArgumentException.class, () -> RogueBaseModifierDestroy46.destroy(s, 4, null));
    assertEquals(4, s.deathSource);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, Integer.MIN_VALUE, Integer.MAX_VALUE})
  void baseFunctionHasNoIndependentLifecycleGuard(int state) {
    var s = RogueModifierDestroy46Test.state();
    s.cleanup.candidate.state = state;
    var w = new World();
    RogueBaseModifierDestroy46.destroy(s, 4, w);
    assertEquals(2, w.withdrawn.size());
    assertEquals(state, s.cleanup.candidate.state);
  }

  @Test
  void missingVirtualOwnerStopsBeforeEntityReadAfterReasonPublish() {
    var s = RogueModifierDestroy46Test.state();
    s.cleanup.ownerAbilityComponent = null;
    var w = new World();
    assertThrows(IllegalArgumentException.class, () -> RogueBaseModifierDestroy46.destroy(s, 4, w));
    assertEquals(List.of("owner"), w.order);
    assertEquals(4, s.deathSource);
  }

  @Test
  void missingEntityStopsBeforeWorldAndAllocations() {
    var s = RogueModifierDestroy46Test.state();
    var w = new World();
    w.entity = null;
    assertThrows(IllegalArgumentException.class, () -> RogueBaseModifierDestroy46.destroy(s, 4, w));
    assertEquals(List.of("owner", "entity"), w.order);
    assertTrue(w.allocated.isEmpty());
  }

  @Test
  void missingWorldStopsBeforeEventManagerAndAllocations() {
    var s = RogueModifierDestroy46Test.state();
    var w = new World();
    w.gameWorld = null;
    assertThrows(IllegalArgumentException.class, () -> RogueBaseModifierDestroy46.destroy(s, 4, w));
    assertEquals(List.of("owner", "entity", "world"), w.order);
    assertTrue(w.allocated.isEmpty());
  }

  @Test
  void nullManagerFailsOnlyAfterFirstDelegateFullyInitialized() {
    var s = RogueModifierDestroy46Test.state();
    var w = new World();
    w.manager = null;
    assertThrows(IllegalArgumentException.class, () -> RogueBaseModifierDestroy46.destroy(s, 4, w));
    assertEquals(1, w.allocated.size());
    var c = w.allocated.getFirst();
    assertSame(s, c.target);
    assertSame(FIRST_INFO, c.methodInfo);
    assertEquals(0x3d10L, c.trampolineRva);
    assertTrue(w.withdrawn.isEmpty());
  }

  @Test
  void nullAllocationStillReadsBindingBeforeDelegateWriteFailure() {
    var s = RogueModifierDestroy46Test.state();
    var w = new World();
    w.nullAllocation = 1;
    assertThrows(IllegalArgumentException.class, () -> RogueBaseModifierDestroy46.destroy(s, 4, w));
    assertEquals(1, w.bindingCalls);
    assertTrue(w.withdrawn.isEmpty());
  }

  @Test
  void bindingFailureLeavesAllocatedDelegateUninitializedAndUnregistered() {
    var s = RogueModifierDestroy46Test.state();
    var w = new World();
    w.fail = "binding";
    assertThrows(IllegalStateException.class, () -> RogueBaseModifierDestroy46.destroy(s, 4, w));
    assertEquals(1, w.allocated.size());
    var c = w.allocated.getFirst();
    assertEquals(0, c.methodPointer);
    assertNull(c.target);
    assertTrue(w.withdrawn.isEmpty());
  }

  @Test
  void mismatchedBindingIdentityIsRejectedBeforeDelegateWrites() {
    var s = RogueModifierDestroy46Test.state();
    var w = new World();
    w.mismatchedBinding = true;
    assertThrows(IllegalArgumentException.class, () -> RogueBaseModifierDestroy46.destroy(s, 4, w));
    assertNull(w.allocated.getFirst().methodInfo);
    assertTrue(w.withdrawn.isEmpty());
  }

  @Test
  void nullMethodInfoIsRejectedBeforeDelegateWrites() {
    var s = RogueModifierDestroy46Test.state();
    var w = new World();
    w.nullMethodInfo = true;
    assertThrows(IllegalArgumentException.class, () -> RogueBaseModifierDestroy46.destroy(s, 4, w));
    assertEquals(0, w.allocated.getFirst().methodPointer);
    assertTrue(w.withdrawn.isEmpty());
  }

  @Test
  void firstWithdrawalFailurePreventsSecondIdReadAndDelegateAllocation() {
    var s = RogueModifierDestroy46Test.state();
    var w = new World();
    w.fail = "unregister";
    assertThrows(IllegalStateException.class, () -> RogueBaseModifierDestroy46.destroy(s, 4, w));
    assertEquals(1, w.allocateCalls);
    assertEquals(1, w.order.stream().filter("id"::equals).count());
    assertEquals(4, s.deathSource);
  }

  @Test
  void secondEntityIdIsRereadButCapturedEventManagerAndEntityRemainShared() {
    var s = RogueModifierDestroy46Test.state();
    var w = new World();
    w.onFirstWithdrawal =
        () -> {
          w.id = 99;
          w.manager = new Object();
          s.cleanup.ownerAbilityComponent = new Object();
        };
    RogueBaseModifierDestroy46.destroy(s, 4, w);
    assertEquals(7, w.withdrawn.getFirst().entityId());
    assertEquals(99, w.withdrawn.getLast().entityId());
    assertSame(MANAGER, w.withdrawn.getLast().manager());
    assertEquals(1, w.order.stream().filter("entity"::equals).count());
  }

  @Test
  void firstEntityIdIsCapturedBeforeDelegateAllocationChangesIt() {
    var s = RogueModifierDestroy46Test.state();
    var w = new World();
    w.onAllocation = () -> w.id = 42;
    RogueBaseModifierDestroy46.destroy(s, 4, w);
    assertEquals(7, w.withdrawn.getFirst().entityId());
    assertEquals(42, w.withdrawn.getLast().entityId());
  }

  @Test
  void secondAllocationFailurePreservesFirstSuccessfulWithdrawal() {
    var s = RogueModifierDestroy46Test.state();
    var w = new World();
    w.fail = "allocate";
    w.failOrdinal = 2;
    assertThrows(IllegalStateException.class, () -> RogueBaseModifierDestroy46.destroy(s, 4, w));
    assertEquals(1, w.withdrawn.size());
    assertEquals(207, w.withdrawn.getFirst().event());
    assertEquals(1, w.allocated.size());
  }

  @Test
  void secondBindingFailurePreservesFirstWithdrawalAndSecondBlankDelegate() {
    var s = RogueModifierDestroy46Test.state();
    var w = new World();
    w.fail = "binding";
    w.failOrdinal = 2;
    assertThrows(IllegalStateException.class, () -> RogueBaseModifierDestroy46.destroy(s, 4, w));
    assertEquals(1, w.withdrawn.size());
    assertEquals(2, w.allocated.size());
    assertNull(w.allocated.getLast().target);
  }

  @Test
  void secondWithdrawalFailureKeepsFirstWithdrawalAndBothInitializedDelegates() {
    var s = RogueModifierDestroy46Test.state();
    var w = new World();
    w.fail = "unregister";
    w.failOrdinal = 2;
    assertThrows(IllegalStateException.class, () -> RogueBaseModifierDestroy46.destroy(s, 4, w));
    assertEquals(1, w.withdrawn.size());
    assertEquals(2, w.allocated.size());
    assertSame(SECOND_INFO, w.allocated.getLast().methodInfo);
    assertSame(s, w.allocated.getLast().target);
  }

  @Test
  void bindingInitializationOverwritesFactoryFieldsInSourceOrder() {
    var s = RogueModifierDestroy46Test.state();
    var w = new World();
    w.onBinding =
        () -> {
          var c = w.allocated.getLast();
          c.target = RogueModifierDestroy46Test.state();
          c.invokePointer = 99;
          c.trampolineRva = 99;
          c.methodInfo = ENTITY;
        };
    RogueBaseModifierDestroy46.destroy(s, 4, w);
    for (var c : w.allocated) {
      assertSame(s, c.target);
      assertNotSame(ENTITY, c.methodInfo);
      assertEquals(c.methodPointer, c.invokePointer);
      assertEquals(0x3d10L, c.trampolineRva);
    }
  }

  @Test
  void ordinaryDestroyUsesActualBaseWithdrawalAndRestoresOriginalReason() {
    var s = RogueModifierDestroy46Test.state();
    var ordinary = new RogueModifierDestroy46Test.World();
    var base = new World();
    ordinary.hooks.put("notifyGlobal", c -> s.deathSource = 9);
    ordinary.hooks.put(
        "baseDestroy",
        c ->
            RogueBaseModifierDestroy46.destroy(
                (RogueModifierDestroy46.State) c.args()[0], (int) c.args()[1], base));
    RogueModifierDestroy46.destroy(s, 4, ordinary.bound);
    assertEquals(4, s.deathSource);
    assertEquals(2, s.cleanup.candidate.state);
    assertNull(s.cleanup.effects);
    assertEquals(2, base.withdrawn.size());
  }

  @Test
  void actualBaseFailureKeepsOrdinaryDestroyCleanupAndClearedChildList() {
    var s = RogueModifierDestroy46Test.state();
    var child = RogueModifierDestroy46Test.state();
    var ordinary = new RogueModifierDestroy46Test.World();
    var base = new World();
    s.children.entries = RogueModifierDestroy46Test.refs(child);
    ordinary.hooks.put(
        "baseDestroy",
        c -> {
          if (c.args()[0] == s) {
            base.fail = "unregister";
            base.failOrdinal = 2;
            RogueBaseModifierDestroy46.destroy(s, (int) c.args()[1], base);
          }
        });
    assertThrows(
        IllegalStateException.class, () -> RogueModifierDestroy46.destroy(s, 4, ordinary.bound));
    assertEquals(0, s.children.entries.size);
    assertEquals(8, s.children.entries.version);
    assertNull(s.cleanup.effects);
    assertEquals(2, s.cleanup.candidate.state);
    assertEquals(1, base.withdrawn.size());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE})
  void entityIdPreservesRawUnsignedThirtyTwoBitPattern(int id) {
    var s = RogueModifierDestroy46Test.state();
    var w = new World();
    w.id = id;
    RogueBaseModifierDestroy46.destroy(s, 4, w);
    assertEquals(id, w.withdrawn.getFirst().entityId());
    assertEquals(id, w.withdrawn.getLast().entityId());
  }

  @Test
  void nullStateRejectedBeforeWorldCalls() {
    var w = new World();
    assertThrows(
        IllegalArgumentException.class, () -> RogueBaseModifierDestroy46.destroy(null, 4, w));
    assertTrue(w.order.isEmpty());
  }
}
