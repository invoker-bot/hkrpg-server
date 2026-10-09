package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RogueResonanceStageParams46Test {
  private static final long ONE = 1L << 33;

  @Test
  void factoryUsesOriginalPrefixedKeysAndCopiesRawWords() {
    assertArrayEquals(
        new RogueResonanceStageParams46.Entry[] {
          new RogueResonanceStageParams46.Entry("#ADF_1", ONE),
          new RogueResonanceStageParams46.Entry("#ADF_2", -2 * ONE)
        },
        RogueResonanceStageParams46.create(new long[] {ONE, -2 * ONE}));
  }

  @Test
  void assignmentStartsAtSourceIndexAndPreservesEarlierDestinationEntries() {
    var sentinel = new RogueResonanceStageParams46.Entry("original", 0);
    var destination = new RogueResonanceStageParams46.Entry[] {sentinel, sentinel, sentinel};
    RogueResonanceStageParams46.assign(new long[] {ONE, 2 * ONE, 3 * ONE}, destination, 1);
    assertArrayEquals(
        new RogueResonanceStageParams46.Entry[] {
          sentinel,
          new RogueResonanceStageParams46.Entry("#ADF_2", 2 * ONE),
          new RogueResonanceStageParams46.Entry("#ADF_3", 3 * ONE)
        },
        destination);
  }

  @Test
  void listAppendPreservesExistingEntriesAndUsesSourceIndexForNames() {
    var sentinel = new RogueResonanceStageParams46.Entry("original", 0);
    var destination = new ArrayList<>(List.of(sentinel));
    RogueResonanceStageParams46.append(new long[] {ONE, 2 * ONE, 3 * ONE}, destination, 1);
    assertEquals(
        List.of(
            sentinel,
            new RogueResonanceStageParams46.Entry("#ADF_2", 2 * ONE),
            new RogueResonanceStageParams46.Entry("#ADF_3", 3 * ONE)),
        destination);
  }

  @Test
  void nullAndEmptyFactoryInputsReturnNullAsNativeWrapperDoes() {
    assertNull(RogueResonanceStageParams46.create(null));
    assertNull(RogueResonanceStageParams46.create(new long[0]));
  }

  @Test
  void inactiveInputsReturnBeforeLookingAtDestination() {
    for (long[] source : new long[][] {null, new long[0], new long[] {1}}) {
      int start = source == null ? 0 : source.length;
      assertDoesNotThrow(() -> RogueResonanceStageParams46.assign(source, null, start));
      assertDoesNotThrow(() -> RogueResonanceStageParams46.append(source, null, start));
    }
    assertDoesNotThrow(() -> RogueResonanceStageParams46.assign(null, null, -1));
    assertDoesNotThrow(() -> RogueResonanceStageParams46.append(new long[0], null, -1));
    assertDoesNotThrow(
        () -> RogueResonanceStageParams46.assign(new long[] {ONE}, null, Integer.MAX_VALUE));
  }

  @Test
  void activeInputRequiresDestinationAndNonnegativeSourceIndex() {
    assertThrows(
        NullPointerException.class,
        () -> RogueResonanceStageParams46.assign(new long[] {ONE}, null, 0));
    assertThrows(
        NullPointerException.class,
        () -> RogueResonanceStageParams46.append(new long[] {ONE}, null, 0));
    assertThrows(
        IndexOutOfBoundsException.class,
        () ->
            RogueResonanceStageParams46.assign(
                new long[] {ONE}, new RogueResonanceStageParams46.Entry[1], -1));
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> RogueResonanceStageParams46.append(new long[] {ONE}, new ArrayList<>(), -1));
  }

  @Test
  void fullSignedStandardWordsAreCopiedWithoutFloatConversionOrRounding() {
    long[] raw = {0, Long.MIN_VALUE, Long.MAX_VALUE - 1, ONE / 4, -ONE / 8, ONE + 2};
    var entries = RogueResonanceStageParams46.create(raw);
    assertNotNull(entries);
    for (int i = 0; i < raw.length; i++) assertEquals(raw[i], entries[i].raw());
  }

  @Test
  void factoryDetachesEntriesFromLaterSourceArrayChanges() {
    var raw = new long[] {ONE};
    var entries = RogueResonanceStageParams46.create(raw);
    raw[0] = 2 * ONE;
    assertEquals(ONE, entries[0].raw());
    assertNotSame(entries, RogueResonanceStageParams46.create(raw));
  }

  @Test
  void keyHashIncludesLeadingPrefixAndDoesNotBorrowUnprefixedExpressionName() {
    var raw = new long[5];
    var fifth = RogueResonanceStageParams46.create(raw)[4];
    assertEquals("#ADF_5", fifth.name());
    assertEquals(-1862642832, fifth.key());
    assertNotEquals(RogueResonanceVariables46.nameKey("ADF_5"), fifth.key());
  }

  @Test
  void sourcePositionsDriveDecimalNamesAcrossSingleDigitBoundary() {
    var raw = new long[11];
    var destination = new ArrayList<RogueResonanceStageParams46.Entry>();
    RogueResonanceStageParams46.append(raw, destination, 8);
    assertEquals(
        List.of("#ADF_9", "#ADF_10", "#ADF_11"),
        destination.stream().map(RogueResonanceStageParams46.Entry::name).toList());
  }

  @Test
  void arrayAssignmentFailureRetainsEarlierSuccessfulAssignments() {
    var entries = new RogueResonanceStageParams46.Entry[1];
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> RogueResonanceStageParams46.assign(new long[] {ONE, 2 * ONE}, entries, 0));
    assertEquals(new RogueResonanceStageParams46.Entry("#ADF_1", ONE), entries[0]);
  }

  @Test
  void listAppendFailureRetainsEarlierSuccessfulEntries() {
    var entries =
        new ArrayList<RogueResonanceStageParams46.Entry>() {
          @Override
          public boolean add(RogueResonanceStageParams46.Entry entry) {
            if (size() == 1) throw new IllegalStateException("list insertion failed");
            return super.add(entry);
          }
        };
    assertThrows(
        IllegalStateException.class,
        () -> RogueResonanceStageParams46.append(new long[] {ONE, 2 * ONE}, entries, 0));
    assertEquals(List.of(new RogueResonanceStageParams46.Entry("#ADF_1", ONE)), entries);
  }

  @Test
  void skippedSourcePrefixIsNotConvertedOrValidated() {
    var entries = new ArrayList<RogueResonanceStageParams46.Entry>();
    RogueResonanceStageParams46.append(new long[] {1, ONE}, entries, 1);
    assertEquals(List.of(new RogueResonanceStageParams46.Entry("#ADF_2", ONE)), entries);
  }

  @Test
  void unsupportedExtendedWordStopsAtThatEntryWithoutErasingEarlierOutput() {
    var entries = new ArrayList<RogueResonanceStageParams46.Entry>();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceStageParams46.append(new long[] {ONE, 1, 2 * ONE}, entries, 0));
    assertEquals(List.of(new RogueResonanceStageParams46.Entry("#ADF_1", ONE)), entries);
    assertThrows(
        IllegalArgumentException.class, () -> RogueResonanceStageParams46.create(new long[] {1}));
  }

  @Test
  void finiteCapacityIsExplicitAndDoesNotTruncate() {
    var entries = RogueResonanceStageParams46.create(new long[255]);
    assertEquals(255, entries.length);
    assertEquals("#ADF_255", entries[254].name());
    assertThrows(
        IllegalArgumentException.class, () -> RogueResonanceStageParams46.create(new long[256]));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceStageParams46.append(new long[256], new ArrayList<>(), 0));
    assertDoesNotThrow(() -> RogueResonanceStageParams46.append(new long[256], null, 256));
  }

  @Test
  void explicitEntryRejectsUnmatchedNamesAndExtendedWords() {
    assertThrows(
        IllegalArgumentException.class, () -> new RogueResonanceStageParams46.Entry(null, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueResonanceStageParams46.Entry("", 0));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueResonanceStageParams46.Entry("中文", 0));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueResonanceStageParams46.Entry("#ADF_1", 1));
  }
}
