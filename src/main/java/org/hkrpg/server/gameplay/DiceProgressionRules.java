package org.hkrpg.server.gameplay;

import emu.lunarcore.LunarCore;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Numeric map keys recovered from the pinned CN 4.6 DesignData entry, not lossy JSON maps. */
public final class DiceProgressionRules {
  public static final int DUST = 264998;
  public static final int CHANGE_PERMISSION = 150093;
  private static final String SOURCE_SHA =
      "cea8b6ffe42464dbec2162be883350db072baa5fbc8663f69a32e0c562ffaa95";

  public static boolean available() {
    return available(Path.of(LunarCore.getConfig().getResourceDir()));
  }

  public static boolean available(Path resourceRoot) {
    try {
      byte[] bytes =
          Files.readAllBytes(
              resourceRoot.resolve("ExcelOutput/ActivityDiceConstValueCommo.json"));
      return SOURCE_SHA.equals(
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    } catch (Exception invalid) {
      return false;
    }
  }

  public static int maximum(int rarity) {
    return switch (rarity) {
      case 1, 2 -> 2;
      case 3 -> 3;
      default -> 0;
    };
  }

  public static int cost(int rarity, int before) {
    if (before < 1 || before >= maximum(rarity)) return 0;
    return before == 1 ? 60 : rarity == 3 && before == 2 ? 180 : 0;
  }

  private DiceProgressionRules() {}
}
