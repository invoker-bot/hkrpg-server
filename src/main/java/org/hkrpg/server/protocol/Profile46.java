package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.server.packet.send.PacketGetBasicInfoScRsp;
import java.io.IOException;

/** Initial nickname confirmation and explicit character selection are separate client steps. */
public final class Profile46 {
  private record Choice(String nickname, int gender, boolean modify) {}

  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental() || command != 41 && command != 80) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var player = session.getPlayer();
    synchronized (player) {
      if (command == 80) {
        var response = builder(session, "GetBasicInfoScRsp");
        if (bytes.length == 0) {
          var core = new PacketGetBasicInfoScRsp(player);
          // The inherited empty unk1/unk2 setting messages have no verified 4.6 meaning.
          ((emu.lunarcore.proto.GetBasicInfoScRspOuterClass.GetBasicInfoScRsp) core.getData())
              .clearPlayerSettingInfo();
          response.mergeFrom(
              session
                  .getProtocol()
                  .translate("GetBasicInfoScRsp", core.getData().toByteArray(), true));
          // The native basic-info callback unconditionally reads field 11 and its field 13.
          // Build verified 4.6 containers instead of translating the unrelated legacy unk1/unk2.
          var settingsField = response.getDescriptorForType().findFieldByName("player_setting_info");
          var settings = DynamicMessage.newBuilder(settingsField.getMessageType());
          var nested = settings.getDescriptorForType().findFieldByName("OJPAODIHAJE");
          settings.setField(nested, DynamicMessage.getDefaultInstance(nested.getMessageType()));
          response.setField(settingsField, settings.build());
          set(response, "is_gender_set", player.hasCreatedCharacter());
        } else set(response, "retcode", Retcode.REQ_PARA_INVALID.getVal());
        session.sendClientPacket("GetBasicInfoScRsp", response.build().toByteArray());
        return true;
      }
      var choice = read(bytes);
      Retcode result = Retcode.REQ_PARA_INVALID;
      if (choice != null) {
        if (!choice.modify() && choice.gender() == 0)
          result = player.setInitialNickname(choice.nickname());
        else if (choice.modify() && choice.gender() >= 1 && choice.gender() <= 2)
          result = player.setInitialProfile(choice.nickname(), choice.gender() == 1 ? 8001 : 8002);
      }
      var response = builder(session, "SetPlayerInfoScRsp");
      set(response, "retcode", result.getVal());
      set(response, "is_modify", choice != null && choice.modify());
      var type = response.getDescriptorForType().findFieldByName("cur_avatar_path");
      var path = type.getEnumType().findValueByNumber(player.getCurAvatarPathId(8001));
      if (path != null) response.setField(type, path);
      session.sendClientPacket("SetPlayerInfoScRsp", response.build().toByteArray());
      return true;
    }
  }

  private static Choice read(byte[] bytes) {
    if (bytes.length > 256) return null;
    try {
      var input = CodedInputStream.newInstance(bytes);
      boolean modifySeen = false, genderSeen = false, nameSeen = false, modify = false;
      String nickname = "";
      int gender = 0, tag;
      while ((tag = input.readTag()) != 0) {
        if (tag == 24) {
          long value = input.readUInt64();
          if (modifySeen || value < 0 || value > 1) return null;
          modifySeen = true;
          modify = value == 1;
        } else if (tag == 80) {
          long value = input.readUInt64();
          if (genderSeen || value < 0 || value > 2) return null;
          genderSeen = true;
          gender = (int) value;
        } else if (tag == 98) {
          if (nameSeen) return null;
          nameSeen = true;
          nickname = input.readStringRequireUtf8();
        } else return null;
      }
      // Proto3 omits false/zero fields in the nickname-only confirmation request.
      // Presence still guards duplicate wire fields; neither default confirms a character.
      return nameSeen && input.isAtEnd()
          ? new Choice(nickname, gender, modify)
          : null;
    } catch (IOException invalid) {
      return null;
    }
  }

  private Profile46() {}
}
