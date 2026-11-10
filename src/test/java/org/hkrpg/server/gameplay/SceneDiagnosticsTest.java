package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.game.scene.Scene;
import org.junit.jupiter.api.Test;

class SceneDiagnosticsTest {
  @Test void brokenAndMissingPlacementsAreLoggedWithTheOriginalSceneAndOnlyOnce() {
    var gson = ResourceLoader.getGson();
    var group = gson.fromJson("""
        {"GroupName":"DiagnosticsFixture","PropList":[null,{"ID":300003,"PropID":98989898}]}
        """, GroupInfo.class);
    group.setId(87);
    var floor = new FloorInfo(20999001);
    floor.getGroups().put(87, group);
    var plane = gson.fromJson("""
        {"PlaneID":20999,"PlaneType":"Maze","WorldID":201}
        """, MazePlaneExcel.class);
    var logger = (Logger) LunarCore.getLogger();
    var capture = new ListAppender<ILoggingEvent>();
    capture.start();
    logger.addAppender(capture);
    String key = "P20999_F20999001";
    var previous = GameData.getFloorInfos().put(key, floor);
    try {
      var player = new ClientNpcOwnership46Test.Actor();
      player.scene = new Scene(player, plane, floor.getFloorID());
      player.scene = new Scene(player, plane, floor.getFloorID());
      var events = capture.list.stream().map(ILoggingEvent::getFormattedMessage)
          .filter(message -> message.contains("floor=20999001")).toList();
      assertEquals(2, events.size());
      assertTrue(events.get(0).contains("group=87") && events.get(0).contains("NullPointerException"));
      assertTrue(events.get(1).contains("group=87") && events.get(1).contains("instance=300003")
          && events.get(1).contains("resource=98989898"));
    } finally {
      if (previous == null) GameData.getFloorInfos().remove(key);
      else GameData.getFloorInfos().put(key, previous);
      logger.detachAppender(capture);
      capture.stop();
    }
  }
}
