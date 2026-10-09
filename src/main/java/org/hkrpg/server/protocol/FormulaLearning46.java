package org.hkrpg.server.protocol;

import emu.lunarcore.game.player.Player;
import java.io.IOException;
import org.hkrpg.server.gameplay.FormulaLearning;

/**
 * Native ItemComposeModule reads bag field 13 into its learned book list; field 15 is unrelated.
 */
public final class FormulaLearning46 {
  public static byte[] enrich(ProtocolSchema schema, String name, byte[] bytes, Player player) {
    if (player == null || !"GetBagScRsp".equals(name)) return bytes;
    synchronized (player) {
      try {
        var bag = schema.parse(name, bytes);
        var type = bag.getDescriptorForType();
        if ((Integer) bag.getField(type.findFieldByName("retcode")) != 0) return bytes;
        var field = type.findFieldByName("KOFNOJCMEFA");
        var result = bag.toBuilder().clearField(field);
        player.getGameplay().getLearnedFormulas().stream()
            .sorted()
            .filter(id -> FormulaLearning.recipe(id) != null)
            .forEach(id -> result.addRepeatedField(field, id));
        return result.build().toByteArray();
      } catch (IOException invalid) {
        throw new ProtocolException("Malformed outgoing bag formulas");
      }
    }
  }

  private FormulaLearning46() {}
}
