package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class RogueDice46Test {
  @TempDir Path root;
  final UniverseMiracleShopTest merchant = new UniverseMiracleShopTest();
  final RogueDiceCurioTest sources = new RogueDiceCurioTest();

  @BeforeEach
  void setup() throws Exception {
    merchant.root = root;
    merchant.setup();
    sources.setup();
    var run = merchant.run();
    run.getMiracleShops()
        .put(
            run.getSiteId() + ":" + run.getRoomId() + ":8:400001",
            new GameplayState.RogueShopPlan(
                Map.of(
                    21,
                    new GameplayState.RogueShopOffer(
                        1, new GameplayState.RogueFragmentCost(200)))));
  }

  @AfterEach
  void cleanup() throws Exception {
    sources.cleanup();
    merchant.cleanup();
  }

  @Test
  void actualNpcPurchasePaysFrozenPriceAndKeepsPurchasedDiceUnavailable() {
    assertEquals(List.of(21), merchant.quotes().stream().map(RogueMiracleShop.Quote::id).toList());
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, merchant.buy(21));
    assertEquals(100, merchant.run().getCoins());
    assertEquals(1, merchant.run().getMiracles().size());
    assertFalse(merchant.run().getMiracles().containsKey(21));
    var quote = merchant.quotes().getFirst();
    assertTrue(quote.purchased());
    assertFalse(quote.owned());
    assertFalse(quote.available());
    assertNotEquals(emu.lunarcore.server.packet.Retcode.SUCC, merchant.buy(21));
    assertEquals(100, merchant.run().getCoins());
  }

  @Test
  void failedReplacementKeepsActualNpcStockAndWallet() {
    GameplayDefinitions.miracleGroups.put(
        10004, new GameplayDefinitions.MiracleGroup(10004, Map.of(9999, 1)));
    String before = emu.lunarcore.util.JsonUtils.encode(merchant.p().getGameplay());
    assertNotEquals(emu.lunarcore.server.packet.Retcode.SUCC, merchant.buy(21));
    assertEquals(before, emu.lunarcore.util.JsonUtils.encode(merchant.p().getGameplay()));
    assertFalse(merchant.quotes().getFirst().purchased());
  }

  @Test
  void nativeSelectionPublishesAddDiceThenAllReplacementActions() throws Exception {
    var session = onlineSession();
    var run = session.getPlayer().getGameplay().getRogue();
    assertTrue(RogueMiracles.acquire(session.getPlayer(), 4));
    run.offerMiracles(List.of(21));
    var choice = Gameplay46.builder(session, "JINFLJMPPHN");
    Gameplay46.set(choice, "OJPGDDFJCHB", 21);
    var request = Gameplay46.builder(session, "OFEDNNJIJFL");
    Gameplay46.set(request, "MPLGEEGOAMC", run.getSelectionId());
    Gameplay46.set(request, "CCCIFGFHGIL", choice.build());
    assertTrue(VersionedPacketHandler.handle(session, 5676, request.build().toByteArray()));
    var notify =
        session.notifications.get(session.names.indexOf("SyncRogueCommonActionResultScNotify"));
    var actions = (List<?>) InitialData46Test.field(notify, "CLGGKLNPLCK");
    assertEquals(3, actions.size());
    var added =
        (DynamicMessage)
            InitialData46Test.field(
                (DynamicMessage)
                    InitialData46Test.field((DynamicMessage) actions.getFirst(), "detail"),
                "GPJJMPCMAAA");
    assertEquals(
        21,
        InitialData46Test.field(
            (DynamicMessage) InitialData46Test.field(added, "miracle"), "LJCEOGPOGBE"));
    var ids = new HashSet<Integer>();
    var oldIds = new HashSet<Integer>();
    for (var value : actions.subList(1, actions.size())) {
      var detail = (DynamicMessage) InitialData46Test.field((DynamicMessage) value, "detail");
      var replacement = (DynamicMessage) InitialData46Test.field(detail, "OMFLCEBLLMI");
      assertEquals(1957, detail.getAllFields().keySet().iterator().next().getNumber());
      oldIds.add((Integer) InitialData46Test.field(replacement, "BJIBCOHEGIJ"));
      var row = (DynamicMessage) InitialData46Test.field(replacement, "HLCPFLOPGBM");
      int id = (Integer) InitialData46Test.field(row, "LJCEOGPOGBE");
      ids.add(id);
      assertEquals(
          run.getMiracles().get(id).getTotalCount(), InitialData46Test.field(row, "total_count"));
      assertEquals(0, InitialData46Test.field(row, "DGGCFDKJDPO"));
    }
    assertEquals(Set.of(4, 21), oldIds);
    assertEquals(run.getMiracles().keySet(), ids);
    int notificationsBeforeRetry =
        Collections.frequency(session.names, "SyncRogueCommonActionResultScNotify");
    var stateBeforeRetry = emu.lunarcore.util.JsonUtils.encode(session.getPlayer().getGameplay());
    assertTrue(VersionedPacketHandler.handle(session, 5676, request.build().toByteArray()));
    assertEquals(
        notificationsBeforeRetry,
        Collections.frequency(session.names, "SyncRogueCommonActionResultScNotify"));
    assertEquals(
        stateBeforeRetry, emu.lunarcore.util.JsonUtils.encode(session.getPlayer().getGameplay()));
  }

  @Test
  void staleCommittedNotificationCannotPublishIntoAnotherRun() {
    var session = onlineSession();
    var plan = RogueMiracleDice.prepare(session.getPlayer());
    assertNotNull(plan);
    var acquired = plan.commit(session.getPlayer());
    assertNotNull(acquired);
    session.getPlayer().getGameplay().setRogue(new GameplayState.RogueRun(100, 1, Map.of()));
    Universe46.syncMiracleDice(session.getPlayer(), acquired.diceChange());
    assertFalse(session.names.contains("SyncRogueCommonActionResultScNotify"));
  }

  @Test
  void nativeClockBreakIsPublishedBeforeDiceAcquisitionAndReplacement() {
    var session = onlineSession();
    var player = session.getPlayer();
    var run = player.getGameplay().getRogue();
    assertTrue(RogueMiracles.acquire(player, 70));
    run.getMiracles().get(70).recordExtraFragments(450);
    var plan = RogueMiracleDice.prepare(player, RogueMiracles.fragmentPlan(run, 200));
    assertNotNull(plan);
    var acquired = plan.commit(player);
    assertNotNull(acquired);
    acquired.syncEffects(player);
    var notify =
        session.notifications.get(session.names.indexOf("SyncRogueCommonActionResultScNotify"));
    var actions = (List<?>) InitialData46Test.field(notify, "CLGGKLNPLCK");
    assertEquals(4, actions.size());
    var firstDetail =
        (DynamicMessage) InitialData46Test.field((DynamicMessage) actions.get(0), "detail");
    var broken = (DynamicMessage) InitialData46Test.field(firstDetail, "OMFLCEBLLMI");
    assertEquals(70, InitialData46Test.field(broken, "BJIBCOHEGIJ"));
    assertEquals(
        9,
        InitialData46Test.field(
            (DynamicMessage) InitialData46Test.field(broken, "HLCPFLOPGBM"), "LJCEOGPOGBE"));
    var secondDetail =
        (DynamicMessage) InitialData46Test.field((DynamicMessage) actions.get(1), "detail");
    assertTrue(
        secondDetail.hasField(secondDetail.getDescriptorForType().findFieldByName("GPJJMPCMAAA")));
    var oldIds = new ArrayList<Integer>();
    for (var action : actions.subList(2, 4)) {
      var detail = (DynamicMessage) InitialData46Test.field((DynamicMessage) action, "detail");
      var replacement = (DynamicMessage) InitialData46Test.field(detail, "OMFLCEBLLMI");
      oldIds.add((Integer) InitialData46Test.field(replacement, "BJIBCOHEGIJ"));
    }
    assertEquals(List.of(9, 21), oldIds);
    assertEquals(1049, run.getCoins());
    assertFalse(run.getMiracles().containsKey(9));
    assertFalse(run.getMiracles().containsKey(21));
    assertFalse(run.getMiracles().containsKey(70));
  }

  private Universe46Test.OnlineSession onlineSession() {
    var session = new Universe46Test.OnlineSession();
    // The actual fixture's scene/roster has already been validated; this session captures wire
    // data.
    session.getPlayer().getGameplay().setRogue(merchant.run());
    return session;
  }
}
