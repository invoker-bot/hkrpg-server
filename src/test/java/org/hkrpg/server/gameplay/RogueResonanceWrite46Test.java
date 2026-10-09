package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueResonanceWrite46Test {
  private static final long ONE = 1L << 33;
  private static final int EXTRA_ATTACK = 1555818884;
  private static final int CURRENT_SP = -978103959;

  private static RogueResonanceExpression46.Program originalExtraAttackExpression() {
    return RogueResonanceExpression46.compile(
        HexFormat.of().parseHex("010000000411"), new long[] {100 * ONE}, new int[] {909827949});
  }

  private static RogueResonanceVariables46 lookup(int key, long raw) {
    return new RogueResonanceVariables46(
        Map.of(key, new RogueResonanceVariables46.Value(raw, -100 * ONE, 100 * ONE)), null);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void originalSourceTaskBranchesExecuteWithCallerSelectedInputs(int selectedBranch)
      throws Exception {
    JsonObject profile;
    try (var input = getClass().getResourceAsStream("/fixtures/rogue-resonance-write-4.6.json")) {
      assertNotNull(input);
      profile =
          JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var cells = new HashMap<Integer, RogueResonanceVariables46.Value>();
    for (int key : new int[] {CURRENT_SP, EXTRA_ATTACK, -38167523, -1803181471}) {
      cells.put(key, new RogueResonanceVariables46.Value(0, -100 * ONE, 100 * ONE));
    }
    var selected = new RogueResonanceVariables46(cells, changes::add);
    int sourceTasks = 0;
    for (var element : profile.getAsJsonArray("sourceTasks")) {
      var row = element.getAsJsonObject();
      if (row.getAsJsonArray("path").get(3).getAsInt() != selectedBranch) continue;
      var task = row.getAsJsonObject("task");
      if (task.get("taskId").getAsInt() == 2855) {
        assertEquals("Caster", task.getAsJsonObject("ReadTargetType").get("Alias").getAsString());
        new RogueResonanceWrite46.PropertyTask(
                2855, task.get("DynamicKey").getAsString(), task.get("Value").getAsInt())
            .execute(selected, List.of(Map.of(12, 10 * ONE)));
      } else {
        if (selectedBranch == 1 && task.get("$offset").getAsInt() == 805) {
          new RogueResonanceVariables46.RoundToIntTask(1221, "Extra_AttackTime").execute(selected);
        }
        var value = task.getAsJsonObject("Value");
        assertEquals(1, value.get("mode").getAsInt());
        var sourceConstants = value.getAsJsonArray("fixedSignedConstants");
        var sourceKeys = value.getAsJsonArray("variableKeys");
        long[] constants = new long[sourceConstants.size()];
        int[] keys = new int[sourceKeys.size()];
        for (int i = 0; i < constants.length; i++)
          constants[i] = sourceConstants.get(i).getAsBigDecimal().longValueExact();
        for (int i = 0; i < keys.length; i++)
          keys[i] = sourceKeys.get(i).getAsBigDecimal().intValueExact();
        var program =
            RogueResonanceExpression46.compile(
                HexFormat.of().parseHex(value.get("rawPostfixBytes").getAsString()),
                constants,
                keys);
        var numericInputs = new HashMap<>(selected.snapshot());
        numericInputs.put(122875526, 20 * ONE);
        numericInputs.put(909827949, ONE / 64);
        new RogueResonanceWrite46.ExpressionTask(
                2791, task.getAsJsonObject("DynamicKey").get("Value").getAsString(), program)
            .execute(numericInputs, List.of(selected), false);
      }
      sourceTasks++;
    }
    assertEquals(3, sourceTasks);
    assertEquals(10 * ONE, selected.read(CURRENT_SP));
    assertEquals(5 * ONE, selected.read(selectedBranch == 0 ? -1803181471 : EXTRA_ATTACK));
    assertEquals(selectedBranch == 0 ? 3 : 4, changes.size());
    assertEquals(4, selected.snapshot().size());
  }

  @Test
  void originalExpressionWritesThroughSelectedLookup() {
    Long actual;
    try {
      var selected = lookup(EXTRA_ATTACK, 0);
      var task =
          new RogueResonanceWrite46.ExpressionTask(
              2791, "Extra_AttackTime", originalExtraAttackExpression());
      assertEquals(0x7777, task.state());
      assertEquals(1, task.execute(Map.of(909827949, ONE / 64), List.of(selected), false));
      assertEquals(0x9999, task.state());
      actual = selected.read(EXTRA_ATTACK);
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(25 * ONE / 16), actual);
  }

  @Test
  void originalPropertyTaskUsesCurrentSpFromFirstExplicitCaster() {
    Long actual;
    try {
      var selected = lookup(CURRENT_SP, 0);
      var task = new RogueResonanceWrite46.PropertyTask(2855, "_CurrentSP", 12);
      assertEquals(0x7777, task.state());
      assertTrue(task.execute(selected, List.of(Map.of(12, 10 * ONE), Map.of(12, 20 * ONE))));
      assertEquals(0x9999, task.state());
      actual = selected.read(CURRENT_SP);
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(10 * ONE), actual);
  }

  @Test
  void expressionCharmGuardSkipsEvaluationAndWrites() {
    Boolean actual;
    try {
      var selected = lookup(EXTRA_ATTACK, ONE);
      var task =
          new RogueResonanceWrite46.ExpressionTask(
              2791, "Extra_AttackTime", originalExtraAttackExpression());
      actual =
          task.execute(null, null, true) == 0
              && task.state() == 0x9999
              && selected.read(EXTRA_ATTACK) == ONE;
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Boolean.TRUE, actual);
  }

  @Test
  void expressionEvaluatesOnceBeforeOrderedTargetWrites() {
    Integer actual;
    try {
      var context = new HashMap<Integer, Long>();
      context.put(909827949, ONE / 64);
      var order = new ArrayList<Integer>();
      var first =
          new RogueResonanceVariables46(
              Map.of(EXTRA_ATTACK, new RogueResonanceVariables46.Value(0, 0, 100 * ONE)),
              change -> {
                order.add(1);
                context.put(909827949, ONE);
              });
      var second =
          new RogueResonanceVariables46(
              Map.of(EXTRA_ATTACK, new RogueResonanceVariables46.Value(0, 0, 100 * ONE)),
              change -> order.add(2));
      var task =
          new RogueResonanceWrite46.ExpressionTask(
              2791, "Extra_AttackTime", originalExtraAttackExpression());
      actual = task.execute(context, List.of(first, second), false);
      assertEquals(List.of(1, 2), order);
      assertEquals(25 * ONE / 16, first.read(EXTRA_ATTACK));
      assertEquals(first.read(EXTRA_ATTACK), second.read(EXTRA_ATTACK));
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Integer.valueOf(2), actual);
  }

  @Test
  void bothWriteTasksClampAndNotifyEvenWhenUnchanged() {
    Integer actual;
    try {
      var count = new AtomicInteger();
      var selected =
          new RogueResonanceVariables46(
              Map.of(CURRENT_SP, new RogueResonanceVariables46.Value(ONE, 0, ONE)),
              change -> count.incrementAndGet());
      var property = new RogueResonanceWrite46.PropertyTask(2855, "_CurrentSP", 12);
      property.execute(selected, List.of(Map.of(12, 100 * ONE)));
      var constant =
          RogueResonanceExpression46.compile(
              HexFormat.of().parseHex("000011"), new long[] {100 * ONE}, new int[0]);
      new RogueResonanceWrite46.ExpressionTask(2791, "_CurrentSP", constant)
          .execute(Map.of(), List.of(selected), false);
      assertEquals(ONE, selected.read(CURRENT_SP));
      actual = count.get();
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Integer.valueOf(2), actual);
  }

  @Test
  void originalPropertyExpressionRoundAndFloorTasksRunWithExplicitInputs() {
    Long actual;
    try {
      var selected =
          new RogueResonanceVariables46(
              Map.of(
                  EXTRA_ATTACK, new RogueResonanceVariables46.Value(0, 0, 100 * ONE),
                  CURRENT_SP, new RogueResonanceVariables46.Value(0, 0, 100 * ONE)),
              null);
      new RogueResonanceWrite46.PropertyTask(2855, "_CurrentSP", 12)
          .execute(selected, List.of(Map.of(12, 10 * ONE)));
      new RogueResonanceWrite46.ExpressionTask(
              2791, "Extra_AttackTime", originalExtraAttackExpression())
          .execute(Map.of(909827949, ONE / 64), List.of(selected), false);
      new RogueResonanceVariables46.RoundToIntTask(1221, "Extra_AttackTime").execute(selected);
      var floor =
          RogueResonanceExpression46.compile(
              HexFormat.of().parseHex("01000101010205100111"),
              new long[0],
              new int[] {1776456860, CURRENT_SP, EXTRA_ATTACK});
      new RogueResonanceWrite46.ExpressionTask(2791, "Extra_AttackTime", floor)
          .execute(selected.snapshot(), List.of(selected), false);
      actual = selected.read(EXTRA_ATTACK);
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(5 * ONE), actual);
  }

  @Test
  void missingNumericInputDoesNotWriteAndStateFollowsNativeEntryAssignment() {
    var selected = lookup(EXTRA_ATTACK, ONE);
    var task =
        new RogueResonanceWrite46.ExpressionTask(
            2791, "Extra_AttackTime", originalExtraAttackExpression());
    assertThrows(
        IllegalArgumentException.class, () -> task.execute(Map.of(), List.of(selected), false));
    assertEquals(ONE, selected.read(EXTRA_ATTACK));
    assertEquals(0x9999, task.state());
  }

  @Test
  void missingPropertyIsNotTurnedIntoZeroAndStateFollowsNativeEntryAssignment() {
    var selected = lookup(CURRENT_SP, ONE);
    var task = new RogueResonanceWrite46.PropertyTask(2855, "_CurrentSP", 12);
    assertThrows(IllegalArgumentException.class, () -> task.execute(selected, List.of(Map.of())));
    assertEquals(ONE, selected.read(CURRENT_SP));
    assertEquals(0x9999, task.state());
  }

  @Test
  void observerFailureStopsLaterTargetsAndKeepsPriorWrite() {
    var first =
        new RogueResonanceVariables46(
            Map.of(EXTRA_ATTACK, new RogueResonanceVariables46.Value(0, 0, 100 * ONE)),
            change -> {
              throw new IllegalStateException("observer failed");
            });
    var second = lookup(EXTRA_ATTACK, 0);
    var task =
        new RogueResonanceWrite46.ExpressionTask(
            2791, "Extra_AttackTime", originalExtraAttackExpression());
    assertThrows(
        IllegalStateException.class,
        () -> task.execute(Map.of(909827949, ONE / 64), List.of(first, second), false));
    assertEquals(25 * ONE / 16, first.read(EXTRA_ATTACK));
    assertEquals(0, second.read(EXTRA_ATTACK));
    assertEquals(0x9999, task.state());
  }

  @Test
  void propertyObserverFailureKeepsWriteAndNativeState() {
    var selected =
        new RogueResonanceVariables46(
            Map.of(CURRENT_SP, new RogueResonanceVariables46.Value(0, 0, 100 * ONE)),
            change -> {
              throw new IllegalStateException("observer failed");
            });
    var task = new RogueResonanceWrite46.PropertyTask(2855, "_CurrentSP", 12);
    assertThrows(
        IllegalStateException.class, () -> task.execute(selected, List.of(Map.of(12, ONE))));
    assertEquals(ONE, selected.read(CURRENT_SP));
    assertEquals(0x9999, task.state());
  }

  @Test
  void missingCellsAreNeverCreatedByWriteTasks() {
    var selected = new RogueResonanceVariables46(Map.of(), null);
    var expression =
        new RogueResonanceWrite46.ExpressionTask(
            2791, "Extra_AttackTime", originalExtraAttackExpression());
    assertEquals(0, expression.execute(Map.of(909827949, ONE / 64), List.of(selected), false));
    assertFalse(
        new RogueResonanceWrite46.PropertyTask(2855, "_CurrentSP", 12)
            .execute(selected, List.of(Map.of(12, ONE))));
    assertTrue(selected.snapshot().isEmpty());
  }

  @Test
  void rejectsUnmatchedTaskVariantsPropertyIdsKeysAndPrograms() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RogueResonanceWrite46.ExpressionTask(
                1221, "Extra_AttackTime", originalExtraAttackExpression()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueResonanceWrite46.ExpressionTask(2791, "Extra_AttackTime", null));
    for (int variant : new int[] {0, 2791, 1221})
      assertThrows(
          IllegalArgumentException.class,
          () -> new RogueResonanceWrite46.PropertyTask(variant, "_CurrentSP", 12));
    for (int property : new int[] {0, 11, 13, -1})
      assertThrows(
          IllegalArgumentException.class,
          () -> new RogueResonanceWrite46.PropertyTask(2855, "_CurrentSP", property));
    for (String key : new String[] {null, "", "角色", "x".repeat(65)}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new RogueResonanceWrite46.ExpressionTask(2791, key, originalExtraAttackExpression()));
      assertThrows(
          IllegalArgumentException.class,
          () -> new RogueResonanceWrite46.PropertyTask(2855, key, 12));
    }
  }

  @Test
  void rejectsMalformedExplicitTargetsAndExtendedPropertyValuesWithoutWrites() {
    var selected = lookup(CURRENT_SP, ONE);
    var property = new RogueResonanceWrite46.PropertyTask(2855, "_CurrentSP", 12);
    assertThrows(
        IllegalArgumentException.class, () -> property.execute(null, List.of(Map.of(12, ONE))));
    assertThrows(IllegalArgumentException.class, () -> property.execute(selected, null));
    assertThrows(IllegalArgumentException.class, () -> property.execute(selected, List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> property.execute(selected, List.of(Map.of(12, 1L))));
    var nullValue = new HashMap<Integer, Long>();
    nullValue.put(12, null);
    assertThrows(
        IllegalArgumentException.class, () -> property.execute(selected, List.of(nullValue)));
    assertEquals(ONE, selected.read(CURRENT_SP));
    var expression =
        new RogueResonanceWrite46.ExpressionTask(
            2791, "_CurrentSP", originalExtraAttackExpression());
    assertThrows(
        IllegalArgumentException.class,
        () -> expression.execute(Map.of(909827949, ONE / 64), null, false));
    var nullLookup = new ArrayList<RogueResonanceVariables46>();
    nullLookup.add(null);
    assertThrows(
        IllegalArgumentException.class,
        () -> expression.execute(Map.of(909827949, ONE / 64), nullLookup, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            expression.execute(
                Map.of(909827949, ONE / 64), java.util.Collections.nCopies(256, selected), false));
    assertEquals(ONE, selected.read(CURRENT_SP));
  }

  @Test
  void emptyExplicitTargetSelectionEvaluatesButDoesNotInventTarget() {
    var task =
        new RogueResonanceWrite46.ExpressionTask(
            2791, "Extra_AttackTime", originalExtraAttackExpression());
    assertEquals(0, task.execute(Map.of(909827949, ONE / 64), List.of(), false));
    assertEquals(0x9999, task.state());
    assertThrows(IllegalArgumentException.class, () -> task.execute(Map.of(), List.of(), false));
  }
}
