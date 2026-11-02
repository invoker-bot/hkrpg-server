package emu.lunarcore.server.packet.send;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.ActivityScheduleInfoOuterClass.ActivityScheduleInfo;
import emu.lunarcore.proto.GetActivityScheduleConfigScRspOuterClass.GetActivityScheduleConfigScRsp;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import java.time.Instant;
import org.hkrpg.server.gameplay.ActivityAccess;

/** Player-specific operator windows; never cache a historical fixed activity list. */
public class PacketGetActivityScheduleConfigScRsp extends BasePacket {
  public PacketGetActivityScheduleConfigScRsp() { this(null,Instant.now()); }
  public PacketGetActivityScheduleConfigScRsp(Player player) { this(player,Instant.now()); }
  public PacketGetActivityScheduleConfigScRsp(Player player,Instant now) {
    super(CmdId.GetActivityScheduleConfigScRsp);
    var data=GetActivityScheduleConfigScRsp.newInstance();
    for (var window : ActivityAccess.active(player,now)) {
      // The inherited wire stores unsigned 32-bit seconds; existing larger windows are capped.
      data.addActivityScheduleList(ActivityScheduleInfo.newInstance().setActivityId(window.panelId())
          .setModuleId(window.moduleId()).setBeginTime((int)Math.min(window.beginTime(),0xffff_ffffL))
          .setEndTime((int)Math.min(window.endTime(),0xffff_ffffL)));
    }
    setData(data);
  }
}
