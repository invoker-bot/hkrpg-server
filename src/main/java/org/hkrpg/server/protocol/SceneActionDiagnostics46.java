package org.hkrpg.server.protocol;

import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import java.util.List;
import java.util.Set;
import org.hkrpg.server.GameDiagnostics;

/** Bounded rejection metadata only; no packet dumps or client ability strings. */
final class SceneActionDiagnostics46 {
  static void rejected(GameSession session, String command, String reason, Retcode error,
      int use, int actor, int hits, int assists) {
    if (error != Retcode.SUCC)
      GameDiagnostics.outcome(session, command, 0, error, reason,
          "use=" + Integer.toUnsignedString(use) + " actor=" + actor
              + " hits=" + hits + " assists=" + assists);
  }

  static void interaction(GameSession session, int config, int entity, EntityProp target, Retcode result) {
    String place = target == null ? "target-missing"
        : "prop-" + target.getGroupId() + "-" + target.getInstId();
    GameDiagnostics.outcome(session, "InteractPropCsReq", config, result, place,
        "entity=" + entity + (target == null ? "" : " group=" + target.getGroupId()
            + " instance=" + target.getInstId() + " prop=" + target.getPropId()
            + " propState=" + target.getState()));
  }

  static void interactionFailed(GameSession session, int config, int entity, EntityProp target) {
    String place = target == null ? "target-missing"
        : "prop-" + target.getGroupId() + "-" + target.getInstId();
    GameDiagnostics.outcome(session, "InteractPropCsReq", config, null, "interaction-exception-" + place,
        "entity=" + entity + (target == null ? "" : " group=" + target.getGroupId()
            + " instance=" + target.getInstId() + " prop=" + target.getPropId()
            + " propState=" + target.getState()));
  }

  static void battleRejected(GameSession session, String reason, int battleId, int stageId,
      List<Integer> reportedAvatars) {
    var active = session.getPlayer().getBattle();
    int separator = reason.indexOf(' ');
    String category = separator < 0 ? reason : reason.substring(0, separator);
    GameDiagnostics.outcome(session, "PVEBattleResultCsReq", stageId, null, category,
        "battle=" + battleId + " activeBattle=" + (active == null ? 0 : active.getId())
            + " participants=" + (active == null ? Set.of() : active.getParticipantIds().stream().sorted().limit(8).toList())
            + " reported=" + reportedAvatars.stream().limit(8).toList()
            + (separator < 0 ? "" : reason.substring(separator)));
  }

  static void rogueEntry(GameSession session, int area, List<Integer> regular, List<Integer> trials,
      int aeon, int otherCount, Set<Integer> unknown, boolean shapeValid, Retcode result) {
    GameDiagnostics.outcome(session, "StartRogueCsReq", area, result, "second-tutorial",
        "regular=" + regular.stream().limit(4).toList() + " trials=" + trials.stream().limit(4).toList()
            + " aeon=" + aeon + " otherCount=" + otherCount
            + " unknownFields=" + unknown.stream().sorted().limit(12).toList()
            + " shapeValid=" + shapeValid);
  }

  private SceneActionDiagnostics46() {}
}
