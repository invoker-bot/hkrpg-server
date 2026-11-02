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
      // CN4.6 FarmModule sends challenge count in field10 (ordinary) / field6 (quick).
      // The local native writers and the observed 38e90740215001 click agree; wave is absent.
      int count = request == null ? 0 : number(request, "HECCOBFBJFI");
      boolean countValid = (count > 0 && wave == 0) || (count == 0 && wave > 0);
      int challenges = count > 0 ? count : wave;
      boolean ordinary = name.equals("StartCocoonStageCsReq");
      boolean valid =
          request != null
              && (ordinary
                  ? only(request, "cocoon_id", "world_level", "wave", "prop_entity_id", "HECCOBFBJFI")
                  : only(request, "cocoon_id", "world_level", "wave", "HECCOBFBJFI"))
              && cocoon > 0
              && world >= 0
              && countValid
              && (!ordinary || number(request, "prop_entity_id") >= 0);
      var resource = valid ? GameData.getCocoonExcelMap().get(cocoon, world) : null;
      valid = resource != null && challenges <= resource.getMaxChallengeCnt();
      var result =
          valid
              ? session
                  .getServer()
                  .getBattleService()
                  .startCocoonWithStaminaRecovery(session.getPlayer(), cocoon, world, challenges)
              : null;
      var battle = result == null ? null : result.battle();
      var response = builder(session, name.replace("CsReq", "ScRsp"));
      set(response, "retcode", result == null ? Retcode.REQ_PARA_INVALID.getVal() : result.retcode().getVal());
      if (battle != null) {
        // Honey fe00bdb echoes the requested cocoon ID, not the mapping/teleport ID.
        set(response, "cocoon_id", battle.getCocoonId());
        set(response, "wave", battle.getCocoonWave());
        if (ordinary) {
          set(response, "challenge_cnt", battle.getCocoonWave());
          set(response, "prop_entity_id", number(request, "prop_entity_id"));
        }
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
