package org.hkrpg.server.gm;

import java.util.List;

/** Small, explicit HTTP projections. Never serialize Player or Account objects. */
public final class GmModels {
  private GmModels() {}

  public record Status(
      boolean ready,
      boolean gameAvailable,
      int onlinePlayers,
      long uptimeSeconds,
      long usedMemoryMb,
      long maxMemoryMb) {}

  public record PlayerRow(
      int uid,
      String name,
      int level,
      int worldLevel,
      int planeId,
      int floorId,
      boolean inBattle) {}

  public record CatalogItem(
      int id, String name, String category, int rarity, int maxLevel, int maxRank) {}

  public record CommandRow(
      String label, String description, boolean requireTarget, boolean dangerous) {}

  public record Attachment(int itemId, int amount) {}

  public record Operation(
      String requestId,
      String action,
      int uid,
      int itemId,
      int amount,
      int level,
      int rank,
      String title,
      String sender,
      String content,
      List<Attachment> attachments,
      String command,
      String confirm,
      String bannerKey,
      Integer durationDays) {
    public Operation(String requestId, String action, int uid, int itemId, int amount,
        int level, int rank, String title, String sender, String content,
        List<Attachment> attachments, String command, String confirm) {
      this(requestId, action, uid, itemId, amount, level, rank, title, sender, content,
          attachments, command, confirm, null, null);
    }
  }

  public record BannerItem(int id, String name) {}

  public record BannerRow(String key, String version, String phase, String name, int id,
      String type, List<BannerItem> up5, List<BannerItem> up4, boolean replayable,
      String status, long beginTime, long endTime) {}

  /** Returned means the legacy handler returned; it does not imply gameplay success. */
  public record Result(String state, List<String> output) {
    public static Result completed(String text) {
      return new Result("completed", List.of(text));
    }
  }

  public record AuditEntry(
      String requestId,
      String fingerprint,
      String at,
      String action,
      int uid,
      String summary,
      String state,
      List<String> output) {}
}
