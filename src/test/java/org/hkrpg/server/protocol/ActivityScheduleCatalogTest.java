package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;
import emu.lunarcore.proto.GetActivityScheduleConfigScRspOuterClass.GetActivityScheduleConfigScRsp;
import emu.lunarcore.server.packet.send.PacketGetActivityScheduleConfigScRsp;
import org.junit.jupiter.api.Test;
import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ActivityPanelExcel;
import emu.lunarcore.game.player.Player;
import java.time.Instant;
import java.util.*;
import org.hkrpg.server.gameplay.GameplayDefinitions;

class ActivityScheduleCatalogTest {
  @Test void aQueryWithoutPlayerContextCannotInventHistoricalWindows() throws Exception {
    var packet=new PacketGetActivityScheduleConfigScRsp();
    var reply=GetActivityScheduleConfigScRsp.parseFrom(packet.getData().toByteArray());
    assertEquals(0,reply.getActivityScheduleList().length());
  }

  @Test void inheritedWireUsesCurrentPlayerGateAndExactTimeBoundariesWithoutSharedCache() throws Exception {
    int id=19994016;var json=new Gson();var oldPanel=GameData.getActivityPanelExcelMap().get(id);
    var oldWindows=List.copyOf(GameplayDefinitions.windows);
    try {
      GameData.getActivityPanelExcelMap().add(json.fromJson("{\"PanelID\":"+id+"}",ActivityPanelExcel.class));
      GameplayDefinitions.replaceActivityWindows(List.of(new GameplayDefinitions.Window(id,11,2000000000,2000000060)));
      var player=new Player();
      var before=GetActivityScheduleConfigScRsp.parseFrom(new PacketGetActivityScheduleConfigScRsp(player,Instant.ofEpochSecond(1999999999)).getData().toByteArray());
      assertEquals(0,before.getActivityScheduleList().length());
      var start=GetActivityScheduleConfigScRsp.parseFrom(new PacketGetActivityScheduleConfigScRsp(player,Instant.ofEpochSecond(2000000000)).getData().toByteArray());
      assertEquals(1,start.getActivityScheduleList().length());var row=start.getActivityScheduleList().get(0);
      assertEquals(id,row.getActivityId());assertEquals(11,row.getModuleId());
      assertEquals(2000000000,row.getBeginTime());assertEquals(2000000060,row.getEndTime());
      var end=GetActivityScheduleConfigScRsp.parseFrom(new PacketGetActivityScheduleConfigScRsp(player,Instant.ofEpochSecond(2000000060)).getData().toByteArray());
      assertEquals(0,end.getActivityScheduleList().length());
      GameData.getActivityPanelExcelMap().add(json.fromJson("{\"PanelID\":"+id+",\"UnlockConditions\":\"[PlayerLevel:80]\"}",ActivityPanelExcel.class));
      var gated=GetActivityScheduleConfigScRsp.parseFrom(new PacketGetActivityScheduleConfigScRsp(player,Instant.ofEpochSecond(2000000000)).getData().toByteArray());
      assertEquals(0,gated.getActivityScheduleList().length());
      assertTrue(Arrays.stream(PacketGetActivityScheduleConfigScRsp.class.getAnnotations()).noneMatch(a->a.annotationType().getSimpleName().equals("CacheablePacket")));
    }finally{
      GameData.getActivityPanelExcelMap().keySet().remove(id);if(oldPanel!=null)GameData.getActivityPanelExcelMap().add(oldPanel);
      GameplayDefinitions.replaceActivityWindows(oldWindows);
    }
  }
}
