package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ActivityPanelExcel;
import emu.lunarcore.server.packet.Retcode;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class MusicGameplayTest {
  @Test
  void rhythmRequiresOpenWindowThenPersistsRatingsAndGrantsStarsOnlyOnce() {
    var previous = GameData.getActivityPanelExcelMap().get(50022);
    GameData.getActivityPanelExcelMap()
        .add(
            new Gson()
                .fromJson(
                    "{\"PanelID\":50022,\"UnlockConditions\":\"[PlayerLevel:21]\"}",
                    ActivityPanelExcel.class));
    var now = Instant.parse("2026-10-01T00:00:00Z");
    var window =
        new GameplayDefinitions.Window(
            50022, 0, now.minusSeconds(1).getEpochSecond(), now.plusSeconds(60).getEpochSecond());
    GameplayDefinitions.musicLevels.put(
        19996801,
        new GameplayDefinitions.MusicLevel(
            19996801, 199968, 1, List.of(100, 200, 300), List.of(0, 0, 0)));
    GameplayDefinitions.musicGroups.put(
        199968, new GameplayDefinitions.MusicGroup(199968, 1, 0, List.of(11)));
    var p =
        new GameplayProgressTest.TestPlayer() {
          public int getLevel() {
            return 30;
          }
        };
    p.getLoginActivityManager().setClock(Clock.fixed(now, ZoneOffset.UTC));
    var manager = new MusicManager(p);
    try {
      assertEquals(Retcode.MUSIC_LOCKED, manager.start(19996801));
      assertEquals(Retcode.MUSIC_LOCKED, manager.finish(3, true));
      GameplayDefinitions.windows.add(window);
      assertEquals(Retcode.SUCC, manager.start(19996801));
      assertFalse(p.getGameplay().getRhythm().getTransaction().isBlank());
      assertEquals(Retcode.MUSIC_LOCKED, manager.start(19996801));
      assertEquals(Retcode.REQ_PARA_INVALID, manager.finish(4, true));
      assertEquals(0, p.grants);
      assertEquals(Retcode.SUCC, manager.finish(2, false));
      assertEquals(2, p.grants);
      assertEquals(2, p.getGameplay().getRhythm().getStars().get(19996801));
      assertEquals(Retcode.MUSIC_LOCKED, manager.finish(2, false));
      assertEquals(Retcode.SUCC, manager.start(19996801));
      assertEquals(Retcode.SUCC, manager.finish(3, true));
      assertEquals(3, p.grants);
      assertTrue(p.getGameplay().getRhythm().getFullCombos().contains(19996801));
      p.getLoginActivityManager().setClock(Clock.fixed(now.plusSeconds(61), ZoneOffset.UTC));
      assertEquals(Retcode.MUSIC_LOCKED, manager.start(19996801));
    } finally {
      GameplayDefinitions.windows.remove(window);
      GameplayDefinitions.musicLevels.remove(19996801);
      GameplayDefinitions.musicGroups.remove(199968);
      if (previous != null) GameData.getActivityPanelExcelMap().add(previous);
      else GameData.getActivityPanelExcelMap().keySet().remove(50022);
    }
  }
}
