package org.hkrpg.server.protocol;

import com.google.protobuf.CodedInputStream;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import org.hkrpg.server.gameplay.TrainWorlds;

/** CN 571991/234374: the train-world notification consumes uint32 field 1. */
public final class TrainWorld46 {
  private record Request(long context, int world) {}

  public static boolean handle(GameSession session, int command, byte[] bytes) {
    if (!session.getProtocol().experimental() || command != 1489) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var player = session.getPlayer();
    synchronized (player) {
      var request = read(bytes);
      var result =
          request == null
              ? Retcode.REQ_PARA_INVALID
              : TrainWorlds.select(player, request.context(), request.world());
      var response = Gameplay46.builder(session, "SetTrainWorldIdScRsp");
      Gameplay46.set(response, "retcode", result.getVal());
      Gameplay46.set(response, "CFDCEEMIOCO", TrainWorlds.current(player, player.getWorldId()));
      session.sendClientPacket("SetTrainWorldIdScRsp", response.build().toByteArray());
    }
    return true;
  }

  private static Request read(byte[] bytes) {
    if (bytes.length > 128) return null;
    try {
      var input = CodedInputStream.newInstance(bytes);
      boolean contextSeen = false, worldSeen = false;
      long context = 0;
      int world = 0;
      int tag;
      while ((tag = input.readTag()) != 0) {
        if (tag == 40) {
          if (contextSeen || (context = input.readUInt64()) == 0) return null;
          contextSeen = true;
        } else if (tag == 72) {
          long value = input.readUInt64();
          if (worldSeen || value <= 0 || value > Integer.MAX_VALUE) return null;
          world = (int) value;
          worldSeen = true;
        } else return null;
      }
      return contextSeen && worldSeen && input.isAtEnd() ? new Request(context, world) : null;
    } catch (IOException invalid) {
      return null;
    }
  }

  public static void sync(Player player) {
    synchronized (player) {
      if (!player.isOnline()
          || player.getSession() == null
          || !player.getSession().getProtocol().experimental()) return;
      int world = TrainWorlds.current(player, 0);
      if (world == 0) return;
      var session = player.getSession();
      var notify = Gameplay46.builder(session, "TrainWorldIdChangeScNotify");
      Gameplay46.set(notify, "CFDCEEMIOCO", world);
      session.sendClientPacket("TrainWorldIdChangeScNotify", notify.build().toByteArray());
    }
  }

  private TrainWorld46() {}
}
