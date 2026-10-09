package emu.lunarcore.util;

import java.util.Objects;
import java.util.function.LongSupplier;

public class Snowflake32 {
  private static final long EPOCH = 1672531200000L; // Sunday, January 1, 2023 12:00:00 AM (GMT)
  private static final Allocator ALLOCATOR = new Allocator(System::currentTimeMillis);

  public static int newUid() {
    return ALLOCATOR.next();
  }

  public static synchronized int toTimestamp(int snowflake) {
    return (snowflake >> 4) + (int) (EPOCH / 1000);
  }

  /** Preserve the positive legacy numeric format, reserving a logical second after 16 IDs. */
  static final class Allocator {
    private final LongSupplier clock;
    private long timestamp = -1;
    private int sequence;

    Allocator(LongSupplier clock) {
      this.clock = Objects.requireNonNull(clock);
    }

    synchronized int next() {
      long wall = Math.floorDiv(Math.subtractExact(clock.getAsLong(), EPOCH), 1000);
      if (wall < 0) throw new IllegalStateException("Account clock precedes ID epoch");
      long nextTimestamp;
      int nextSequence;
      if (wall > timestamp) {
        nextTimestamp = Math.max(1, wall);
        nextSequence = 0;
      } else if (sequence < 15) {
        nextTimestamp = timestamp;
        nextSequence = sequence + 1;
      } else {
        nextTimestamp = timestamp + 1;
        nextSequence = 0;
      }
      if (nextTimestamp > (Integer.MAX_VALUE >>> 4))
        throw new IllegalStateException("Positive legacy account ID range exhausted");
      int result = Math.toIntExact((nextTimestamp << 4) + nextSequence);
      timestamp = nextTimestamp;
      sequence = nextSequence;
      return result;
    }
  }
}
