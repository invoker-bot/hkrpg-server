package emu.lunarcore.server.packet.recv;

import emu.lunarcore.proto.UseItemCsReqOuterClass.UseItemCsReq;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Opcodes;
import emu.lunarcore.server.packet.PacketHandler;
import emu.lunarcore.server.packet.send.PacketUseItemScRsp;
import java.util.List;

@Opcodes(CmdId.UseItemCsReq)
public class HandlerUseItemCsReq extends PacketHandler {

  @Override
  public void handle(GameSession session, byte[] data) throws Exception {
    var req = UseItemCsReq.parseFrom(data);

    var result =
        req.getOptionalRewardId() != 0
                || (req.getUseAvatarTypeValue() != 0 && req.getUseAvatarTypeValue() != 1)
            ? new org.hkrpg.server.gameplay.ConsumableUse.Result(
                emu.lunarcore.server.packet.Retcode.ITEM_INVALID_USE, List.of(), 0)
            : new org.hkrpg.server.gameplay.ConsumableUse(session.getPlayer())
                .use(req.getUseItemId(), req.getUseItemCount(), req.getBaseAvatarId());
    session.send(
        new PacketUseItemScRsp(
            req.getUseItemId(), result.consumed(), result.retcode(), result.items()));
  }
}
