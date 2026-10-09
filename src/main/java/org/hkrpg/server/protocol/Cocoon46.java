package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;

/** Native farming replies avoid the absent 4.2 ordinary response command. */
public final class Cocoon46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()) return false;
    String name;
    if (command == session.getProtocol().clientCommand("StartCocoonStageCsReq"))
      name = "StartCocoonStageCsReq";
    else if (command == session.getProtocol().clientCommand("QuickStartCocoonStageCsReq"))
      name = "QuickStartCocoonStageCsReq";
    else return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    synchronized (session.getPlayer()) {
      var schema = session.getProtocol().clientSchema();
      DynamicMessage request = null;
      if (Parkour46.wireValid(schema.message(name), bytes, 0)) request = schema.parse(name, bytes);
      int cocoon = request == null ? 0 : number(request, "cocoon_id");
      int world = request == null ? -1 : number(request, "world_level");
      int wave = request == null ? 0 : number(request, "wave");
      boolean ordinary = name.equals("StartCocoonStageCsReq");
      boolean valid =
          request != null
              && (ordinary
                  ? only(request, "cocoon_id", "world_level", "wave", "prop_entity_id")
                  : only(request, "cocoon_id", "world_level", "wave"))
              && cocoon > 0
              && world >= 0
              && wave > 0
              && (!ordinary || number(request, "prop_entity_id") >= 0);
      var resource = valid ? GameData.getCocoonExcelMap().get(cocoon, world) : null;
      valid = resource != null && wave <= resource.getMaxChallengeCnt();
      var battle =
          valid
              ? session
                  .getServer()
                  .getBattleService()
                  .startCocoon(session.getPlayer(), cocoon, world, wave)
              : null;
      var response = builder(session, name.replace("CsReq", "ScRsp"));
      set(response, "retcode", battle == null ? Retcode.REQ_PARA_INVALID.getVal() : 0);
      if (battle != null) {
        // Honey fe00bdb echoes the requested cocoon ID, not the mapping/teleport ID.
        set(response, "cocoon_id", battle.getCocoonId());
        set(response, "wave", battle.getCocoonWave());
        set(
            response,
            "battle_info",
            schema.parse(
                "SceneBattleInfo",
                session
                    .getProtocol()
                    .translate("SceneBattleInfo", battle.toProto().toByteArray(), true)));
      }
      session.sendClientPacket(name.replace("CsReq", "ScRsp"), response.build().toByteArray());
    }
    return true;
  }

  private Cocoon46() {}
}
