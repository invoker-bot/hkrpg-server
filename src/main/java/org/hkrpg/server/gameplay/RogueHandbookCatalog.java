package org.hkrpg.server.gameplay;

import java.nio.file.*;
import java.util.*;

/** Only the original explicit unlock link supplies a classic-universe handbook identity. */
public final class RogueHandbookCatalog {
  public record Miracle(
      int MiracleHandbookID,
      int MiracleReward,
      List<Integer> MiracleTypeList,
      int MiracleDisplayID,
      int MiracleEffectDisplayID) {}

  private static volatile Map<Integer, Miracle> miracles = Map.of();

  public static void load(Path root) {
    if (!Files.isRegularFile(root.resolve("ExcelOutput/RogueHandbookMiracle.json"))) {
      miracles = Map.of();
      return;
    }
    var next = new TreeMap<Integer, Miracle>();
    GameplayDefinitions.rows(
        root,
        "RogueHandbookMiracle",
        Miracle.class,
        row -> {
          if (row.MiracleHandbookID() <= 0
              || row.MiracleReward() < 0
              || row.MiracleDisplayID() <= 0
              || row.MiracleEffectDisplayID() <= 0
              || row.MiracleTypeList() == null
              || row.MiracleTypeList().isEmpty()
              || row.MiracleTypeList().stream().anyMatch(id -> id == null || id <= 0)
              || new HashSet<>(row.MiracleTypeList()).size() != row.MiracleTypeList().size()
              || next.putIfAbsent(
                      row.MiracleHandbookID(),
                      new Miracle(
                          row.MiracleHandbookID(),
                          row.MiracleReward(),
                          List.copyOf(row.MiracleTypeList()),
                          row.MiracleDisplayID(),
                          row.MiracleEffectDisplayID()))
                  != null)
            throw new IllegalArgumentException("Invalid or duplicate rogue handbook miracle");
        });
    if (next.isEmpty()) throw new IllegalStateException("Empty rogue handbook catalogue");
    miracles = Collections.unmodifiableMap(next);
  }

  public static Miracle miracle(int id) {
    return miracles.get(id);
  }

  public static Map<Integer, Miracle> snapshot() {
    return miracles;
  }

  public static boolean canRecord(emu.lunarcore.game.player.Player player, int miracleId) {
    int id = unlock(miracleId);
    return id == 0
        || player.getGameplay().getRogueHandbook() != null
            && player.getGameplay().getRogueHandbook().canRecord(id);
  }

  public static int unlock(int id) {
    var source = GameplayDefinitions.miracles.get(id);
    if (source == null || source.MiracleID() != id || source.UnlockHandbookMiracleID() <= 0)
      return 0;
    var row = miracles.get(source.UnlockHandbookMiracleID());
    return row != null
            && row.MiracleHandbookID() == source.UnlockHandbookMiracleID()
            && row.MiracleDisplayID() == source.MiracleDisplayID()
            && row.MiracleEffectDisplayID() == source.MiracleEffectDisplayID()
        ? row.MiracleHandbookID()
        : 0;
  }

  private RogueHandbookCatalog() {}
}
