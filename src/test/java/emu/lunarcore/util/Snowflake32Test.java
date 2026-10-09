package emu.lunarcore.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class Snowflake32Test {
  @Test
  void aBurstOf512ActualAllocationsCannotRepeatAnAccountId() {
    var ids = new HashSet<Integer>();
    for (int i = 0; i < 512; i++) {
      int id = Snowflake32.newUid();
      assertTrue(id > 0);
      assertTrue(ids.add(id), "Repeated account ID at allocation " + i);
    }
  }

  @Test
  void aBurstCannotMoveBackwardsWhenTheByteSequenceWraps() {
    int previous = Snowflake32.newUid();
    for (int i = 0; i < 512; i++) {
      int next = Snowflake32.newUid();
      assertTrue(next > previous, "Account sequence moved backwards at allocation " + i);
      previous = next;
    }
  }

  private static final long EPOCH = 1672531200000L;

  @Test
  void allSixteenSlotsAreReservedBeforeMovingToTheNextLogicalSecond() {
    var now = new AtomicLong(EPOCH + 100000);
    var ids = new Snowflake32.Allocator(now::get);
    for (int i = 0; i < 16; i++) assertEquals(1600 + i, ids.next());
    assertEquals(1616, ids.next());
    now.addAndGet(1000);
    assertEquals(1617, ids.next());
  }

  @Test
  void laterWallTimeAndRollbackNeverReuseTheReservedSeconds() {
    var now = new AtomicLong(EPOCH + 100000);
    var ids = new Snowflake32.Allocator(now::get);
    assertEquals(1600, ids.next());
    now.set(EPOCH + 200000);
    assertEquals(3200, ids.next());
    now.set(EPOCH + 90000);
    assertEquals(3201, ids.next());
    for (int i = 2; i < 16; i++) assertEquals(3200 + i, ids.next());
    assertEquals(3216, ids.next());
  }

  @Test
  void epochZeroNeverCreatesTheEmptyId() {
    var ids = new Snowflake32.Allocator(() -> EPOCH);
    assertEquals(16, ids.next());
    assertEquals(17, ids.next());
  }

  @Test
  void invalidClockDoesNotConsumeTheNextValidId() {
    var now = new AtomicLong(EPOCH - 1);
    var ids = new Snowflake32.Allocator(now::get);
    assertThrows(IllegalStateException.class, ids::next);
    now.set(EPOCH + 100000);
    assertEquals(1600, ids.next());
  }

  @Test
  void exhaustionNeverWrapsToANegativeOrPreviouslyIssuedId() {
    long last = Integer.MAX_VALUE >>> 4;
    var now = new AtomicLong(EPOCH + last * 1000);
    var ids = new Snowflake32.Allocator(now::get);
    for (int i = 0; i < 16; i++) assertEquals((int) (last * 16 + i), ids.next());
    assertThrows(IllegalStateException.class, ids::next);
    assertThrows(IllegalStateException.class, ids::next);
  }

  @Test
  void clockBeyondThePositiveRangeIsRejectedBeforeAnyAllocation() {
    var ids = new Snowflake32.Allocator(() -> EPOCH + ((Integer.MAX_VALUE >>> 4) + 1L) * 1000);
    assertThrows(IllegalStateException.class, ids::next);
  }

  @Test
  void legacyTimestampDecoderStillMatchesBothNormalAndBorrowedIds() {
    var ids = new Snowflake32.Allocator(() -> EPOCH + 100000);
    int first = ids.next();
    for (int i = 0; i < 15; i++) ids.next();
    assertEquals((int) (EPOCH / 1000) + 100, Snowflake32.toTimestamp(first));
    assertEquals((int) (EPOCH / 1000) + 101, Snowflake32.toTimestamp(ids.next()));
  }

  @Test
  void concurrentBurstsUseUniquePositiveIds() throws Exception {
    var ids = new Snowflake32.Allocator(() -> EPOCH + 100000);
    var allocated = ConcurrentHashMap.<Integer>newKeySet();
    try (var pool = Executors.newFixedThreadPool(8)) {
      var jobs = new java.util.ArrayList<Future<?>>();
      for (int i = 0; i < 32; i++)
        jobs.add(
            pool.submit(
                () -> {
                  for (int j = 0; j < 64; j++) {
                    int id = ids.next();
                    assertTrue(id > 0);
                    assertTrue(allocated.add(id));
                  }
                }));
      for (var job : jobs) job.get();
    }
    assertEquals(2048, allocated.size());
    assertEquals(1600, allocated.stream().mapToInt(Integer::intValue).min().orElseThrow());
    assertEquals(3647, allocated.stream().mapToInt(Integer::intValue).max().orElseThrow());
  }
}
