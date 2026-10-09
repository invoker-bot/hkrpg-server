package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class UniverseMiracle46Test {
  @AfterEach
  void reset() {
    GameplayDefinitions.miracles.clear();
    GameplayDefinitions.miracleEffects.clear();
  }

  @Test
  void nativeSelectionUsesOfferedResourceIdAndRejectsReplay() throws Exception {
    GameplayDefinitions.miracles.put(4, new GameplayDefinitions.Miracle(4, 4, 4));
    GameplayDefinitions.miracleEffects.put(
        4,
        new GameplayDefinitions.MiracleEffect(4, List.of(new GameplayDefinitions.EffectValue(1))));
    var s = new Universe46Test.OnlineSession();
    var run = new GameplayState.RogueRun(100, 1, Map.of());
    s.getPlayer().getGameplay().setRogue(run);
    run.offerMiracles(List.of(4));
    var pending = Universe46.pending(s);
    var action = (DynamicMessage) InitialData46Test.field(pending, "action");
    var detail = (DynamicMessage) InitialData46Test.field(action, "detail");
    assertEquals(
        List.of(4),
        InitialData46Test.field(
            (DynamicMessage) InitialData46Test.field(detail, "AMIBPAGBMFP"), "BPDDBKGDMDD"));
    var choice = Gameplay46.builder(s, "JINFLJMPPHN");
    Gameplay46.set(choice, "OJPGDDFJCHB", 4);
    var request = Gameplay46.builder(s, "OFEDNNJIJFL");
    Gameplay46.set(request, "MPLGEEGOAMC", run.getSelectionId());
    Gameplay46.set(request, "CCCIFGFHGIL", choice.build());
    assertTrue(VersionedPacketHandler.handle(s, 5676, request.build().toByteArray()));
    assertEquals(0, InitialData46Test.field(s.response, "retcode"));
    assertEquals(1, run.getMiracles().get(4).getTotalCount());
    assertFalse(run.pending());
    assertEquals(1, Collections.frequency(s.names, "SyncRogueCommonActionResultScNotify"));
    var delta = s.notifications.get(s.names.indexOf("SyncRogueCommonActionResultScNotify"));
    var resultDetail =
        (DynamicMessage)
            InitialData46Test.field(InitialData46Test.first(delta, "CLGGKLNPLCK"), "detail");
    var miracle =
        (DynamicMessage)
            InitialData46Test.field(
                (DynamicMessage) InitialData46Test.field(resultDetail, "GPJJMPCMAAA"), "miracle");
    assertEquals(4, InitialData46Test.field(miracle, "LJCEOGPOGBE"));
    assertEquals(0, InitialData46Test.field(miracle, "DGGCFDKJDPO"));
    assertEquals(1, InitialData46Test.field(miracle, "total_count"));
    assertTrue(VersionedPacketHandler.handle(s, 5676, request.build().toByteArray()));
    assertNotEquals(0, InitialData46Test.field(s.response, "retcode"));
    assertEquals(1, Collections.frequency(s.names, "SyncRogueCommonActionResultScNotify"));
  }

  @Test
  void repairSelectionNotificationsObserveSavedAcquisitionAndClearedMenu() throws Exception {
    for (int id : List.of(4, 17)) {
      GameplayDefinitions.miracles.put(id, new GameplayDefinitions.Miracle(id, id, id));
      GameplayDefinitions.miracleEffects.put(
          id,
          new GameplayDefinitions.MiracleEffect(
              id, List.of(new GameplayDefinitions.EffectValue(id == 4 ? 1 : 2))));
    }
    int[] saves = {0};
    var actor =
        new emu.lunarcore.game.player.Player() {
          public void save() {
            saves[0]++;
          }
        };
    var run = new GameplayState.RogueRun(100, 1, Map.of());
    actor.getGameplay().setRogue(run);
    assertTrue(RogueMiracles.acquire(actor, 4));
    assertTrue(run.getMiracles().get(4).consume());
    assertTrue(run.getMiracles().get(4).broken());
    run.offerMiracles(List.of(17));
    int savesBeforeSelection = saves[0];
    var notifications = new ArrayList<String>();
    var session =
        new Gameplay46HandlerTest.Session() {
          public emu.lunarcore.game.player.Player getPlayer() {
            return actor;
          }

          public void sendClientPacket(String name, byte[] bytes) {
            assertEquals(savesBeforeSelection + 1, saves[0], "Effect notification preceded player commit");
            assertTrue(
                run.getMiracleOffers().isEmpty(),
                "Selection menu was not cleared before notification");
            assertFalse(run.getMiracles().get(4).broken());
            assertEquals(List.of(2.0), run.getMiracles().get(17).getEffectParameters());
            notifications.add(name);
          }
        };
    actor.setSession(session);
    var online = emu.lunarcore.game.player.Player.class.getDeclaredField("loggedIn");
    online.setAccessible(true);
    online.setBoolean(actor, true);
    try {
      var choice = Gameplay46.builder(session, "JINFLJMPPHN");
      Gameplay46.set(choice, "OJPGDDFJCHB", 17);
      var request = Gameplay46.builder(session, "OFEDNNJIJFL");
      Gameplay46.set(request, "MPLGEEGOAMC", run.getSelectionId());
      Gameplay46.set(request, "CCCIFGFHGIL", choice.build());
      assertTrue(VersionedPacketHandler.handle(session, 5676, request.build().toByteArray()));
      assertEquals(savesBeforeSelection + 1, saves[0]);
      assertEquals(2, Collections.frequency(notifications, "SyncRogueCommonActionResultScNotify"));
      assertEquals(100, run.getCoins());
    } finally {
      online.setBoolean(actor, false);
    }
  }

  @Test
  void revivalConsumptionNotificationObservesSavedLifeCountAndRestoredHp() throws Exception {
    GameplayDefinitions.miracles.put(4, new GameplayDefinitions.Miracle(4, 4, 4));
    GameplayDefinitions.miracleEffects.put(
        4,
        new GameplayDefinitions.MiracleEffect(4, List.of(new GameplayDefinitions.EffectValue(1))));
    int[] saves = {0};
    var actor =
        new emu.lunarcore.game.player.Player() {
          public void save() {
            saves[0]++;
          }

          public void sendPacket(emu.lunarcore.server.packet.BasePacket packet) {}
        };
    var run = new GameplayState.RogueRun(100, 1, Map.of());
    actor.getGameplay().setRogue(run);
    run.getRoster().add(199961);
    run.getVitals().put(199961, new GameplayState.RogueVitals(0, 0));
    assertTrue(RogueMiracles.acquire(actor, 4));
    int savesBeforeRevival = saves[0];
    var notifications = new ArrayList<String>();
    var session =
        new Gameplay46HandlerTest.Session() {
          public emu.lunarcore.game.player.Player getPlayer() {
            return actor;
          }

          public void sendClientPacket(String name, byte[] bytes) {
            assertEquals(savesBeforeRevival + 1, saves[0], "Revival notification preceded player commit");
            assertEquals(10000, run.getVitals().get(199961).getHp());
            assertEquals(1, run.getMiracles().get(4).getUseCount());
            assertTrue(run.getMiracles().get(4).broken());
            notifications.add(name);
          }
        };
    actor.setSession(session);
    var online = emu.lunarcore.game.player.Player.class.getDeclaredField("loggedIn");
    online.setAccessible(true);
    online.setBoolean(actor, true);
    try {
      RogueMiracles.onBattleWon(actor);
      assertEquals(List.of("SyncRogueCommonActionResultScNotify"), notifications);
      assertEquals(savesBeforeRevival + 1, saves[0]);
    } finally {
      online.setBoolean(actor, false);
    }
  }
}
