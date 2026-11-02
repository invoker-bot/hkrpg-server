package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.*;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.hkrpg.server.gameplay.*;

/** All original 4.6 offering IDs can be queried in the client's single table-order request. */
public final class OfferingQuery46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()
        || command != session.getProtocol().clientCommand("GetOfferingInfoCsReq")) return false;
    DynamicMessage request;
    try {
      request = session.getProtocol().clientSchema().parse("GetOfferingInfoCsReq", bytes);
    } catch (IOException malformed) {
      return false;
    }
    var ids =
        ((List<?>) value(request, "offering_id_list")).stream().map(v -> (Integer) v).toList();
    // Preserve previously accepted single-6/single-12 and mixed-6/12 queries and their resource
    // contracts.
    if (ids.stream().allMatch(id -> id == 6 || id == 12)) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    synchronized (session.getPlayer()) {
      var code = Retcode.SUCC;
      var output = builder(session, "GetOfferingInfoScRsp");
      if (!Offering46.wireValid(request.getDescriptorForType(), bytes)
          || ids.size() > 15
          || new HashSet<>(ids).size() != ids.size()
          || ids.stream().anyMatch(id -> id < 1 || id > 15)) code = Retcode.REQ_PARA_INVALID;
      OfferingQueryCatalog catalog = null;
      KraterOfferings.Snapshot krater = null;
      KraterOfferings.Snapshot worldShop = null;
      if (code == Retcode.SUCC) {
        catalog = OfferingQueryCatalog.read();
        if (catalog == null) code = Retcode.REWARD_CONFIG_NOT_EXIST;
      }
      if (code == Retcode.SUCC && ids.contains(6)) {
        krater = KraterOfferings.snapshot(session.getPlayer());
        code = krater.code();
      }
      if (code == Retcode.SUCC
          && ids.contains(10)
          && !session.getPlayer().getGameplay().getWorldShopOffering().pristine()) {
        worldShop = KraterOfferings.snapshot(session.getPlayer(), 10);
        code = worldShop.code();
      }
      if (code == Retcode.SUCC && ids.contains(12)) {
        if (OfferingCatalog.read() == null) code = Retcode.REWARD_CONFIG_NOT_EXIST;
        else if (!Offerings.valid(session.getPlayer())) code = Retcode.PLAYER_DATA_ERROR;
      }
      if (code == Retcode.SUCC && ids.contains(5)
          && session.getPlayer().getGameplay().getOfferings().containsKey(5)) {
        if (OfferingCatalog.read(5) == null) code = Retcode.REWARD_CONFIG_NOT_EXIST;
        else if (!Offerings.valid(session.getPlayer(), 5)) code = Retcode.PLAYER_DATA_ERROR;
      }
      if (code == Retcode.SUCC
          && ids.stream()
              .anyMatch(
                  id ->
                      id != 5
                          && id != 6
                          && id != 12
                          && session.getPlayer().getGameplay().getOfferings().containsKey(id)))
        code = Retcode.PLAYER_DATA_ERROR;
      if (code == Retcode.SUCC) {
        Instant now = Instant.now();
        for (int id : ids) {
          DynamicMessage info;
          if (id == 6) info = KraterOffering46.snapshot(session, krater);
          else if (id == 10 && worldShop != null)
            info = KraterOffering46.snapshot(session, id, worldShop);
          else if (id == 12) info = Offering46.snapshot(session, id);
          else if (id == 5) info = Offering46.snapshot(session, id, catalog.unlocked(session.getPlayer(), id, now));
          else {
            var row = builder(session, "OfferingInfo");
            set(row, "offering_id", id);
            set(
                row,
                "offering_state",
                row.getDescriptorForType()
                    .findFieldByName("offering_state")
                    .getEnumType()
                    .findValueByNumber(catalog.unlocked(session.getPlayer(), id, now) ? 2 : 1));
            info = row.build();
          }
          add(output, "offering_info_list", info);
        }
      }
      set(output, "retcode", code.getVal());
      session.sendClientPacket("GetOfferingInfoScRsp", output.build().toByteArray());
    }
    return true;
  }

  private OfferingQuery46() {}
}
