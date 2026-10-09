package org.hkrpg.server.gameplay;

/** Literal adventure lifetimes use seconds; -1 keeps the buff until explicitly consumed. */
public final class MazeBuffLifetime {
  private MazeBuffLifetime() {}

  public static long milliseconds(double seconds) {
    if (seconds == -1) return -1;
    if (!Double.isFinite(seconds) || seconds < 0 || seconds >= Long.MAX_VALUE / 1000.0)
      throw new IllegalArgumentException("Invalid maze buff lifetime");
    return Math.round(seconds * 1000);
  }

  public static long expiry(long createdAt, double seconds) {
    long milliseconds = milliseconds(seconds);
    if (milliseconds == -1) return Long.MAX_VALUE;
    if (createdAt < 0 || milliseconds > Long.MAX_VALUE - createdAt)
      throw new IllegalArgumentException("Maze buff lifetime overflows expiration");
    return createdAt + milliseconds;
  }
}
