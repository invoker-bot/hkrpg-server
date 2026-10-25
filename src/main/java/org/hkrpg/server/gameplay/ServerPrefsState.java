package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Opaque client preferences, separate from authoritative unlocks, rewards and tutorial progress. */
@Entity(useDiscriminator = false)
public final class ServerPrefsState {
  public static final int MAX_VALUE_BYTES = 65536;
  private static final int MAX_ENTRIES = 512, MAX_TOTAL_BYTES = 1048576;
  private Map<Integer, byte[]> values = new HashMap<>();

  public boolean valid() {
    if (values == null || values.size() > MAX_ENTRIES) return false;
    long total = 0;
    for (var entry : values.entrySet()) {
      var data = entry.getValue();
      if (entry.getKey() == null || entry.getKey() <= 0 || data == null
          || data.length > MAX_VALUE_BYTES) return false;
      total += data.length;
    }
    return total <= MAX_TOTAL_BYTES;
  }

  /** Call under the owning player's lock; callers cannot mutate persisted byte arrays. */
  public Map<Integer, byte[]> snapshot() {
    if (!valid()) throw new IllegalStateException("Invalid saved server preferences");
    var result = new TreeMap<Integer, byte[]>();
    values.forEach((id, data) -> result.put(id, data.clone()));
    return Collections.unmodifiableMap(result);
  }

  public Retcode update(Player player, int id, byte[] data) {
    synchronized (player) {
      if (id <= 0 || data == null || data.length > MAX_VALUE_BYTES) return Retcode.REQ_PARA_INVALID;
      if (!valid()) return Retcode.PLAYER_DATA_ERROR;
      byte[] previous = values.get(id);
      if (Arrays.equals(previous, data)) return Retcode.SUCC;
      if (previous == null && values.size() >= MAX_ENTRIES) return Retcode.REQ_PARA_INVALID;
      long total = data.length;
      for (var entry : values.entrySet()) if (entry.getKey() != id) total += entry.getValue().length;
      if (total > MAX_TOTAL_BYTES) return Retcode.REQ_PARA_INVALID;
      values.put(id, data.clone());
      try {
        player.save();
      } catch (RuntimeException failed) {
        if (previous == null) values.remove(id);
        else values.put(id, previous);
        return Retcode.SERVER_INTERNAL_ERROR;
      }
      return Retcode.SUCC;
    }
  }
}
