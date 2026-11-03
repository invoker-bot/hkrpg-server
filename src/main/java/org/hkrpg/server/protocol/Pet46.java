package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import emu.lunarcore.server.packet.send.PacketGetPetDataScRsp;
import java.io.IOException;
import org.hkrpg.server.gameplay.PetSelection;

/** CN4.6 inventory followers: query 7625/7617, summon 7616/7608, recall 7604/7621. */
public final class Pet46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) {
    if (!session.getProtocol().experimental()
        || command != 7625 && command != 7616 && command != 7604) return false;
    var player = session.getPlayer();
    if (session.getState() != SessionState.ACTIVE || player == null) return true;
    synchronized (player) {
      boolean query = command == 7625, recall = command == 7604;
      String name = query ? "GetPetDataScRsp" : recall ? "RecallPetScRsp" : "SummonPetScRsp";
      int id = recall ? 0 : readNormalPet(bytes);
      var result = Retcode.SUCC;
      if (query || recall) {
        if (bytes == null || bytes.length != 0) result = Retcode.REQ_PARA_INVALID;
      } else if (id <= 0) result = Retcode.REQ_PARA_INVALID;

      if (query && result == Retcode.SUCC) {
        session.send(new PacketGetPetDataScRsp(player));
        return true;
      }
      int previous = player.getPetId();
      if (!query && result == Retcode.SUCC) result = PetSelection.select(player,id);
      // A notification is published only after the new selection is durably saved.
      if (result == Retcode.SUCC && previous != player.getPetId())
        notifySelection(session,player.getPetId());
      var response = builder(session,name);
      set(response,"retcode",result.getVal());
      if (!query) {
        var stateField = response.getDescriptorForType().findFieldByNumber(recall ? 11 : 5);
        response.setField(stateField,selection(stateField.getMessageType(),player.getPetId()));
      }
      session.sendClientPacket(name,response.build().toByteArray());
    }
    return true;
  }

  /** Native NetworkManager's normal sender sets type=1 and uint32 oneof field 1. */
  private static int readNormalPet(byte[] bytes) {
    if (bytes == null || bytes.length > 16) return -1;
    try {
      var input = CodedInputStream.newInstance(bytes);
      boolean seenId = false, seenType = false;
      int id = -1;
      for (int tag; (tag = input.readTag()) != 0;) {
        if (tag == 8 && !seenId) {
          seenId = true;
          long value = input.readUInt64();
          if (value <= 0 || value > Integer.MAX_VALUE) return -1;
          id = (int)value;
        } else if (tag == 104 && !seenType) {
          seenType = true;
          if (input.readUInt64() != 1) return -1;
        } else return -1;
      }
      return seenId && seenType && input.isAtEnd() ? id : -1;
    } catch (IOException invalid) {
      return -1;
    }
  }

  /** EHDNPMEKPCA: normal type 1 in field 9, selected uint32 oneof in field 13. */
  static DynamicMessage selection(Descriptor type, int id) {
    if (id < 0) throw new ProtocolException("Invalid stored pet selection");
    var state = DynamicMessage.newBuilder(type);
    if (id > 0) {
      var kind = type.findFieldByNumber(9);
      state.setField(kind,kind.getEnumType().findValueByNumber(1));
      state.setField(type.findFieldByNumber(13),id);
    }
    return state.build();
  }

  private static void notifySelection(GameSession session, int id) {
    var notify = builder(session,"PetDataChangeScNotify");
    var list = notify.getDescriptorForType().findFieldByNumber(6);
    var change = DynamicMessage.newBuilder(list.getMessageType());
    var state = change.getDescriptorForType().findFieldByNumber(11);
    change.setField(state,selection(state.getMessageType(),id));
    notify.addRepeatedField(list,change.build());
    session.sendClientPacket("PetDataChangeScNotify",notify.build().toByteArray());
  }

  private Pet46() {}
}
