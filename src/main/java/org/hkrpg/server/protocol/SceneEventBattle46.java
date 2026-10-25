package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.Descriptors.EnumValueDescriptor;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import org.hkrpg.server.gameplay.SceneEventBattles;

/** Native 1404 carries an event ID (field 11), rather than a direct battle stage. */
public final class SceneEventBattle46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()
        || command != session.getProtocol().clientCommand("SceneEnterStageCsReq")) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var schema = session.getProtocol().clientSchema();
    var request =
        Parkour46.wireValid(schema.message("SceneEnterStageCsReq"), bytes, 0)
            ? schema.parse("SceneEnterStageCsReq", bytes)
            : null;
    synchronized (session.getPlayer()) {
      var player = session.getPlayer();
      int event = request == null ? 0 : number(request, "event_id");
      int canonical = org.hkrpg.server.gameplay.StoryBattleEvents.canonicalEvent(player, event);
      boolean valid =
          request != null
              && only(request, "event_id", "NKDAAAGPCDB")
              && ((EnumValueDescriptor) value(request, "NKDAAAGPCDB")).getNumber() == 0
              && SceneEventBattles.resolveClientEvent(
                      canonical,
                      org.hkrpg.server.gameplay.StageEventDifficultySettings.world(player, canonical))
                  != null;
      var battle =
          valid ? session.getServer().getBattleService().startEventBattle(player, event) : null;
      var response = builder(session, "SceneEnterStageScRsp");
      set(response, "retcode", battle == null ? Retcode.REQ_PARA_INVALID.getVal() : 0);
      if (battle != null)
        set(
            response,
            "battle_info",
            schema.parse(
                "SceneBattleInfo",
                session
                    .getProtocol()
                    .translate("SceneBattleInfo", battle.toProto().toByteArray(), true)));
      session.sendClientPacket("SceneEnterStageScRsp", response.build().toByteArray());
    }
    return true;
  }

  private SceneEventBattle46() {}
}
