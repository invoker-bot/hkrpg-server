package org.hkrpg.server.protocol;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemComposeExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.SessionState;
import java.time.Instant;
import java.util.Comparator;
import org.hkrpg.server.gameplay.CompositionLimits;

/** Native fields carry confirmed used quantities; unresolved debits do not manufacture output. */
public final class CompositionLimit46 {
  private static String periodKey(Instant now) {
    return CompositionLimits.period("Weekly", now) + ":" + CompositionLimits.period("Monthly", now);
  }

  public static void sync(GameSession session) {
    sync(session, Instant.now());
  }

  public static void sync(GameSession session, Instant now) {
    if (!session.getProtocol().experimental() || session.getState() != SessionState.ACTIVE) return;
    synchronized (session.getPlayer()) {
      var snapshot = Gameplay46.builder(session, "ComposeLimitNumCompleteScNotify");
      var ledger = session.getPlayer().getGameplay().getCompositionLimits();
      GameData.getItemComposeExcelMap().values().stream()
          .filter(CompositionLimits::periodic)
          .filter(
              r ->
                  r.getFormulaType() == ItemComposeExcel.FormulaType.Normal
                      || r.getFormulaType() == ItemComposeExcel.FormulaType.Sepcial)
          .sorted(Comparator.comparingInt(ItemComposeExcel::getId))
          .forEach(
              r ->
                  ledger
                      .used(r, now)
                      .ifPresent(
                          used ->
                              Gameplay46.add(
                                  snapshot, "MGLCGPMNHBB", record(session, r.getId(), used))));
      session.sendClientPacket("ComposeLimitNumCompleteScNotify", snapshot.build().toByteArray());
      session.setComposeLimitPeriod(periodKey(now));
    }
  }

  private static com.google.protobuf.DynamicMessage record(GameSession session, int id, int used) {
    var value = Gameplay46.builder(session, "DPGAHDIJJDL");
    Gameplay46.set(value, "DJLNCCEIDNO", used);
    Gameplay46.set(value, "KAHMGPGPOII", id);
    return value.build();
  }

  public static void updated(GameSession session, ItemComposeExcel recipe, Instant now) {
    if (!CompositionLimits.periodic(recipe)) return;
    var ledger = session.getPlayer().getGameplay().getCompositionLimits();
    ledger
        .used(recipe, now)
        .ifPresent(
            used -> {
              var update = Gameplay46.builder(session, "ComposeLimitNumUpdateScNotify");
              Gameplay46.set(update, "PLJFOAPFELP", record(session, recipe.getId(), used));
              session.sendClientPacket(
                  "ComposeLimitNumUpdateScNotify", update.build().toByteArray());
            });
  }

  public static void refresh(Player player, Instant now) {
    var session = player.getSession();
    if (session == null
        || !session.getProtocol().experimental()
        || session.getState() != SessionState.ACTIVE) return;
    synchronized (player) {
      if (!periodKey(now).equals(session.getComposeLimitPeriod())) sync(session, now);
    }
  }

  private CompositionLimit46() {}
}
