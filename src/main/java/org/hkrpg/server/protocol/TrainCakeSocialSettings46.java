package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import java.io.IOException;
import org.hkrpg.server.gameplay.TrainCakeSocialSettings;

/** Verified native UpdatePlayerSetting oneof 1660 and PlayerSettingInfo field 1800. */
final class TrainCakeSocialSettings46 {
  static TrainCakeSocialSettings read(byte[] bytes) {
    if (bytes == null || bytes.length > 64) return null;
    try {
      var outer = CodedInputStream.newInstance(bytes);
      if (outer.readTag() != 10) return null;
      var setting = CodedInputStream.newInstance(outer.readByteArray());
      if (!outer.isAtEnd() || setting.readTag() != ((1660 << 3) | 2)) return null;
      var social = CodedInputStream.newInstance(setting.readByteArray());
      if (!setting.isAtEnd()) return null;
      boolean[] flags = new boolean[3];
      boolean[] seen = new boolean[3];
      int tag;
      while ((tag = social.readTag()) != 0) {
        if (tag != 8 && tag != 16 && tag != 24) return null;
        int index = (tag >> 3) - 1;
        long value = social.readUInt64();
        if (seen[index] || value > 1 || value < 0) return null;
        seen[index] = true;
        flags[index] = value == 1;
      }
      return new TrainCakeSocialSettings(flags[0], flags[1], flags[2]);
    } catch (IOException invalid) {
      return null;
    }
  }

  static DynamicMessage toProto(GameSession session, TrainCakeSocialSettings settings) {
    var social = builder(session, "JPGACKPLGCD");
    set(social, "KALACJBAPOA", settings.allowStrangers());
    set(social, "FOKJIPGMKAN", settings.allowEntry());
    set(social, "IBLGOCFBOCO", settings.allowBox());
    return social.build();
  }

  private TrainCakeSocialSettings46() {}
}
