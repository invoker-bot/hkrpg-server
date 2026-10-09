package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import org.hkrpg.server.gameplay.RaidDifficultySettings;

/** Client-verified 4193/4103; source-1 ID tag 8, source tag 11, difficulty tag 12. */
public final class RaidDifficulty46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()) return false;
    String name =
        command == session.getProtocol().clientCommand("DifficultyAdjustmentGetDataCsReq")
            ? "DifficultyAdjustmentGetDataCsReq"
            : command == session.getProtocol().clientCommand("DifficultyAdjustmentUpdateDataCsReq")
                ? "DifficultyAdjustmentUpdateDataCsReq"
                : null;
    if (name == null) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    synchronized (session.getPlayer()) {
      var schema = session.getProtocol().clientSchema();
      boolean valid = Parkour46.wireValid(schema.message(name), bytes, 0);
      var request = valid ? schema.parse(name, bytes) : null;
      var out = builder(session, name.replace("CsReq", "ScRsp"));
      var code = Retcode.REQ_PARA_INVALID;
      var state = session.getPlayer().getGameplay().getRaidDifficultySettings();
      var events = session.getPlayer().getGameplay().getStageEventDifficultySettings();
      DynamicMessage selected = null;
      if (request != null) {
        if (name.equals("DifficultyAdjustmentGetDataCsReq")) code = Retcode.SUCC;
        else if (request.hasField(request.getDescriptorForType().findFieldByName("data"))) {
          selected = (DynamicMessage) value(request, "data");
          try {
            state.snapshot();
            events.snapshot();
            int source = ((EnumValueDescriptor) value(selected, "source_type")).getNumber();
            int difficulty = ((EnumValueDescriptor) value(selected, "ANJMFPKJHOH")).getNumber();
            code =
                source == 2
                    ? events.update(session.getPlayer(), number(selected, "id"), source, difficulty)
                    : state.update(session.getPlayer(), number(selected, "id"), source, difficulty);
          } catch (IllegalStateException invalid) {
            code = Retcode.PLAYER_DATA_ERROR;
          }
        }
      }
      if (code == Retcode.SUCC) {
        try {
          var raidChoices = state.snapshot();
          var eventChoices = events.snapshot();
          for (var choice : raidChoices) add(out, "content_package_list", choice(session, choice));
          for (var choice : eventChoices)
            add(
                out,
                "content_package_list",
                choice(session, choice.getEvent(), choice.getSource(), choice.getDifficulty()));
          if (selected != null) set(out, "data", selected);
        } catch (IllegalStateException invalid) {
          code = Retcode.PLAYER_DATA_ERROR;
        }
      }
      set(out, "retcode", code.getVal());
      session.sendClientPacket(name.replace("CsReq", "ScRsp"), out.build().toByteArray());
    }
    return true;
  }

  private static DynamicMessage choice(GameSession session, RaidDifficultySettings.Choice choice) {
    return choice(session, choice.getRaid(), choice.getSource(), choice.getDifficulty());
  }

  private static DynamicMessage choice(
      GameSession session, int id, int sourceType, int difficultyType) {
    var b = builder(session, "AMKBFMCOIHK");
    set(b, "id", id);
    var source = b.getDescriptorForType().findFieldByName("source_type");
    var difficulty = b.getDescriptorForType().findFieldByName("ANJMFPKJHOH");
    b.setField(source, source.getEnumType().findValueByNumber(sourceType));
    b.setField(difficulty, difficulty.getEnumType().findValueByNumber(difficultyType));
    return b.build();
  }

  private RaidDifficulty46() {}
}
