package org.hkrpg.server.protocol;

import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import java.util.HashMap;
import org.hkrpg.server.gameplay.SceneInteractions;

/** The native config ID and the opaque UInt64 interaction context are distinct fields. */
public final class SceneInteraction46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (command != session.getProtocol().clientCommand("InteractPropCsReq")) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var req = session.getProtocol().clientSchema().parse("InteractPropCsReq", bytes);
    var player = session.getPlayer();
    synchronized (player) {
      int config = Gameplay46.number(req, "interact_id2");
      int entity = Gameplay46.number(req, "prop_entity_id");
      var result =
          req.getUnknownFields().asMap().isEmpty()
                  && Gameplay46.only(req, "prop_entity_id", "interact_id2", "interact_id")
              ? SceneInteractions.validateNative(player, config, entity)
              : Retcode.REQ_PARA_INVALID;
      emu.lunarcore.game.scene.entity.EntityProp prop = null;
      if (result == Retcode.SUCC) {
        var target =
            (emu.lunarcore.game.scene.entity.EntityProp) player.getScene().getEntityById(entity);
        if (org.hkrpg.server.gameplay.ScenePropTransitions.applies(target)) {
          var transition =
              org.hkrpg.server.gameplay.ScenePropTransitions.interact(player, config, entity);
          result = transition.retcode();
          prop = transition.prop();
        } else prop = player.interactWithProp(config, entity);
      }
      if (result == Retcode.SUCC && prop == null) result = Retcode.PLAYER_DATA_ERROR;
      var fields = new HashMap<String, Object>();
      fields.put("retcode", result.getVal());
      fields.put("prop_entity_id", entity);
      if (prop != null) fields.put("prop_state", prop.getState().getVal());
      Gameplay46.send(session, "InteractPropScRsp", fields);
    }
    return true;
  }

  private SceneInteraction46() {}
}
