package org.hkrpg.server.protocol;

import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import java.util.HashMap;
import org.hkrpg.server.gameplay.SceneInteractions;
import org.hkrpg.server.gameplay.TrainMirror;
import org.hkrpg.server.gameplay.TrainWorkbench;

/** The native config ID and the opaque UInt64 interaction context are distinct fields. */
public final class SceneInteraction46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (command != session.getProtocol().clientCommand("InteractPropCsReq")) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    if (SceneActionWire46.rejectMalformed(session, "InteractPropCsReq", bytes)) return true;
    var req = session.getProtocol().clientSchema().parse("InteractPropCsReq", bytes);
    var player = session.getPlayer();
    synchronized (player) {
      int config = Gameplay46.number(req, "interact_id2");
      int entity = Gameplay46.number(req, "prop_entity_id");
      var scene = player.getScene();
      var target = scene != null && scene.getEntityById(entity) instanceof EntityProp candidate
          ? candidate : null;
      boolean workbench = TrainWorkbench.applies(target);
      boolean mirrorReplay = false;
      var result = Retcode.REQ_PARA_INVALID;
      EntityProp prop = null;
      try {
        if (TrainCakeSocial46.inSocialScene(player)) {
          if (req.getUnknownFields().asMap().isEmpty()
              && Gameplay46.only(req, "prop_entity_id", "interact_id2", "interact_id")) {
            result = TrainCakeSocial46.interact(session, config, entity);
            if (result == Retcode.SUCC) prop = target;
          }
        } else if (req.getUnknownFields().asMap().isEmpty()
            && Gameplay46.only(req, "prop_entity_id", "interact_id2", "interact_id")) {
          mirrorReplay = TrainMirror.replaysSavedOpen(player, config, target);
          result = mirrorReplay ? Retcode.SUCC : workbench ? TrainWorkbench.interact(target, config)
              : SceneInteractions.validateNative(player, config, entity);
        }
        if (result == Retcode.SUCC && !TrainCakeSocial46.inSocialScene(player)) {
          if (workbench || mirrorReplay) {
            prop = target;
          } else if (org.hkrpg.server.gameplay.BookPickups.applies(target)) {
            var transition = org.hkrpg.server.gameplay.BookPickups.pickUp(player, config, entity);
            result = transition.retcode();
            prop = transition.prop();
          } else if (org.hkrpg.server.gameplay.WorldChests.applies(target)) {
            var transition = org.hkrpg.server.gameplay.WorldChests.open(player, config, entity);
            result = transition.retcode();
            prop = transition.prop();
          } else if (org.hkrpg.server.gameplay.GoldenSpears.applies(target)) {
            var transition = org.hkrpg.server.gameplay.GoldenSpears.interact(player, config, entity);
            result = transition.retcode();
            prop = transition.prop();
          } else if (org.hkrpg.server.gameplay.ScenePropTransitions.applies(target)) {
            var transition =
                org.hkrpg.server.gameplay.ScenePropTransitions.interact(player, config, entity);
            result = transition.retcode();
            prop = transition.prop();
          } else prop = player.interactWithProp(config, entity);
        }
      } catch (RuntimeException failed) {
        SceneActionDiagnostics46.interactionFailed(session, config, entity, target);
        throw failed;
      }
      if (result == Retcode.SUCC && prop == null) result = Retcode.PLAYER_DATA_ERROR;
      var fields = new HashMap<String, Object>();
      fields.put("retcode", result.getVal());
      fields.put("prop_entity_id", entity);
      if (prop != null) fields.put("prop_state", prop.getState().getVal());
      SceneActionDiagnostics46.interaction(session, config, entity, target, result);
      Gameplay46.send(session, "InteractPropScRsp", fields);
    }
    return true;
  }

  private SceneInteraction46() {}
}
