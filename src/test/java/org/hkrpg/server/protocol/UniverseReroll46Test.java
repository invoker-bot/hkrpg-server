package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class UniverseReroll46Test {
  Universe46LifecycleTest.ExplorerSession s;

  @BeforeEach
  void setup() {
    s = new Universe46LifecycleTest.ExplorerSession();
    new Universe46LifecycleTest().setup();
    // Isolate fee arithmetic with a fixture balance; the production 4.6 resource base is 50.
    GameplayDefinitions.rogueConstants.put("Start_Rogue_Coin_Base", "100");
    GameplayDefinitions.rogueTalents.put(
        11,
        new Gson()
            .fromJson(
                "{\"TalentID\":11,\"EffectDescParamList\":[]}",
                GameplayDefinitions.RogueTalent.class));
    GameplayDefinitions.rogueConstants.put("Roll_Buff_Max_Count", "0");
    GameplayDefinitions.rogueConstants.put("Roll_Buff_Cost", "[31:30],[31:50],[31:100]");
    s.explorer.getGameplay().getRogueTalents().add(11);
    for (int id = 19997301; id <= 19997310; id++) {
      GameData.getMazeBuffExcelMap()
          .add(new Gson().fromJson("{\"ID\":" + id + ",\"Lv\":1}", MazeBuffExcel.class));
      GameplayDefinitions.blessings.add(
          new GameplayDefinitions.Blessing(id, 1, 100, "Common", 1, List.of()));
    }
  }

  @AfterEach
  void cleanup() {
    new Universe46LifecycleTest().reset();
    GameplayDefinitions.rogueConstants.clear();
    GameplayDefinitions.rogueTalents.clear();
    GameplayDefinitions.blessings.clear();
  }

  DynamicMessage offer() {
    var pending = Universe46.pending(s);
    var action = (DynamicMessage) Gameplay46.value(pending, "action");
    return (DynamicMessage)
        Gameplay46.value((DynamicMessage) Gameplay46.value(action, "detail"), "PCHGEGFBKLF");
  }

  void begin() throws Exception {
    assertEquals(0, Gameplay46.number(s.start(List.of(199961), 0), "retcode"));
    s.explorer.getGameplay().getRogue().clear();
    assertEquals(3, s.explorer.getUniverseManager().offerBlessings().size());
  }

  DynamicMessage roll(Integer batch) throws Exception {
    var req = Gameplay46.builder(s, "OFEDNNJIJFL");
    if (batch != null) Gameplay46.set(req, "MPLGEEGOAMC", batch);
    Gameplay46.set(req, "KBNLGEMNDEN", Gameplay46.builder(s, "DEMENEAAGAA").build());
    assertTrue(VersionedPacketHandler.handle(s, 5676, req.build().toByteArray()));
    assertEquals("HandleRogueCommonPendingActionScRsp", s.responseName);
    return s.response;
  }

  @Test
  void nativeTalent11RerollConsumesOnlySavedFragmentCostAndReturnsTheActualMenu() throws Exception {
    begin();
    var run = s.explorer.getGameplay().getRogue();
    var first = List.copyOf(run.getOffers());
    assertEquals(true, Gameplay46.value(offer(), "can_roll"));
    assertEquals(1, Gameplay46.number(offer(), "EGFOLKEPNCI"));
    var costs = (DynamicMessage) Gameplay46.value(offer(), "DEHJDNIEKHB");
    var pile =
        (DynamicMessage) Gameplay46.value(InitialData46Test.first(costs, "item_list"), "pile_item");
    assertEquals(31, Gameplay46.number(pile, "item_id"));
    assertEquals(30, Gameplay46.number(pile, "item_num"));
    int batch = run.getSelectionId();
    var rsp = roll(null); // Local CN button d9a9030 leaves field 5 at zero.
    assertEquals(0, Gameplay46.number(rsp, "retcode"));
    assertEquals(batch, run.getSelectionId());
    assertEquals(70, run.getCoins());
    assertEquals(3, run.getOffers().size());
    assertTrue(Collections.disjoint(first, run.getOffers()));
    var callback = (DynamicMessage) Gameplay46.value(rsp, "JFHKDEIDMOG");
    var current = (DynamicMessage) Gameplay46.value(callback, "PCHGEGFBKLF");
    assertEquals(1, Gameplay46.number(current, "PCGFIOKLIBE"));
    assertEquals(false, Gameplay46.value(current, "can_roll"));
    assertEquals(3, ((List<?>) Gameplay46.value(current, "LEOFNINDJDB")).size());
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(roll(batch), "retcode"));
    assertEquals(70, run.getCoins());
    assertEquals(
        Retcode.REQ_PARA_INVALID,
        s.explorer.getUniverseManager().selectBlessing(batch, first.getFirst()));
  }

  @Test
  void missingTalentOldBatchAndInsufficientCurrencyCannotChangeThePendingSelection()
      throws Exception {
    s.explorer.getGameplay().getRogueTalents().clear();
    begin();
    var run = s.explorer.getGameplay().getRogue();
    assertEquals(false, Gameplay46.value(offer(), "can_roll"));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(roll(null), "retcode"));
    assertEquals(100, run.getCoins());
    assertEquals(Retcode.SUCC, s.explorer.getUniverseManager().leave(false));
    assertEquals(Retcode.SUCC, s.explorer.getUniverseManager().quit(Universe46LifecycleTest.AREA));
    s.explorer.getGameplay().getRogueTalents().add(11);
    begin();
    var next = s.explorer.getGameplay().getRogue();
    var before = List.copyOf(next.getOffers());
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(),
        Gameplay46.number(roll(run.getSelectionId()), "retcode"));
    next.spend(80);
    assertEquals(Retcode.ITEM_NOT_ENOUGH.getVal(), Gameplay46.number(roll(null), "retcode"));
    assertEquals(before, next.getOffers());
    assertEquals(20, next.getCoins());
    next.addCoins(30);
    assertEquals(0, Gameplay46.number(roll(null), "retcode"));
    assertEquals(20, next.getCoins());
  }

  @Test
  void changingResourcesOrActivatingTalentMidRunDoesNotRefillOrRepriceTheSavedMenu()
      throws Exception {
    begin();
    GameplayDefinitions.rogueConstants.put("Roll_Buff_Cost", "[31:1]");
    GameplayDefinitions.rogueTalents.remove(11);
    assertEquals(0, Gameplay46.number(roll(null), "retcode"));
    assertEquals(70, s.explorer.getGameplay().getRogue().getCoins());
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(roll(null), "retcode"));
  }

  @Test
  void malformedNativeRollAndOutsideRunCannotChargeOrConsumeTheSavedOpportunity() throws Exception {
    begin();
    var run = s.explorer.getGameplay().getRogue();
    var before = List.copyOf(run.getOffers());
    var req = Gameplay46.builder(s, "OFEDNNJIJFL");
    var roll = Gameplay46.builder(s, "DEMENEAAGAA");
    roll.mergeFrom(new byte[] {8, 1});
    Gameplay46.set(req, "KBNLGEMNDEN", roll.build());
    assertTrue(VersionedPacketHandler.handle(s, 5676, req.build().toByteArray()));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(s.response, "retcode"));
    assertEquals(100, run.getCoins());
    assertEquals(before, run.getOffers());
    assertEquals(Retcode.SUCC, s.explorer.getUniverseManager().leave(false));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(roll(null), "retcode"));
    assertEquals(
        Retcode.SUCC, s.explorer.getUniverseManager().resume(Universe46LifecycleTest.AREA, false));
    assertEquals(0, Gameplay46.number(roll(null), "retcode"));
    assertEquals(70, run.getCoins());
  }

  @Test
  void concurrentRollsDebitAndRefreshTheCurrentMenuExactlyOnce() throws Exception {
    begin();
    var run = s.explorer.getGameplay().getRogue();
    int batch = run.getSelectionId();
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var calls = new ArrayList<java.util.concurrent.Callable<Retcode>>();
      for (int i = 0; i < 6; i++)
        calls.add(() -> s.explorer.getUniverseManager().rerollBlessings(batch));
      var results = workers.invokeAll(calls);
      int succeeded = 0;
      for (var result : results) {
        var code = result.get();
        if (code == Retcode.SUCC) succeeded++;
        else assertEquals(Retcode.REQ_PARA_INVALID, code);
      }
      assertEquals(1, succeeded);
    }
    assertEquals(70, run.getCoins());
    assertEquals(1, run.getOfferRerolls());
  }

  @Test
  void invalidCurrencyCostsAndNoAlternativePoolDisableRerollsBeforePayment() throws Exception {
    for (String malformed :
        List.of("[32:30]", "[31:-1]", "[31:0]", "[31:2147483648]", "[31:30],")) {
      GameplayDefinitions.rogueConstants.put("Roll_Buff_Cost", malformed);
      assertEquals(0, Gameplay46.number(s.start(List.of(199961), 0), "retcode"));
      var run = s.explorer.getGameplay().getRogue();
      run.clear();
      s.explorer.getUniverseManager().offerBlessings();
      assertEquals(false, Gameplay46.value(offer(), "can_roll"));
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(roll(null), "retcode"));
      assertEquals(100, run.getCoins());
      s.explorer.getUniverseManager().leave(false);
      s.explorer.getUniverseManager().quit(Universe46LifecycleTest.AREA);
    }
    GameplayDefinitions.rogueConstants.put("Roll_Buff_Cost", "[31:30]");
    GameplayDefinitions.blessings.removeIf(b -> b.MazeBuffID() > 19997303);
    begin();
    assertEquals(false, Gameplay46.value(offer(), "can_roll"));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(roll(null), "retcode"));
    assertEquals(100, s.explorer.getGameplay().getRogue().getCoins());
  }

  @Test
  void morphiaRestorePreservesFrozenPricesPoolAndConsumedCountWithoutRegrantingTalent()
      throws Exception {
    begin();
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "universe-talents-effects");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456788);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, s.explorer.getGameplay());
      store.save(document);
      var restored = store.find(emu.lunarcore.game.player.Player.class).first();
      assertNotSame(document.getGameplay(), restored.getGameplay());
      gameplay.set(s.explorer, restored.getGameplay());
      GameplayDefinitions.rogueConstants.put("Roll_Buff_Cost", "[31:1]");
      s.explorer.getGameplay().getRogueTalents().clear();
      assertEquals(0, Gameplay46.number(roll(null), "retcode"));
      assertEquals(70, s.explorer.getGameplay().getRogue().getCoins());
      gameplay.set(document, s.explorer.getGameplay());
      store.save(document);
      restored = store.find(emu.lunarcore.game.player.Player.class).first();
      gameplay.set(s.explorer, restored.getGameplay());
      assertEquals(1, restored.getGameplay().getRogue().getOfferRerolls());
      assertEquals(10, restored.getGameplay().getRogue().getOfferPool().size());
      assertEquals(false, Gameplay46.value(offer(), "can_roll"));
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(roll(null), "retcode"));
      assertEquals(70, restored.getGameplay().getRogue().getCoins());
    } finally {
      mongo.shutdown();
    }
  }

  @Test
  void heldFaithBondDiscountsTheMenuOnceWithoutResourceChangesRepricingItsDebit() throws Exception {
    GameplayDefinitions.miracles.put(13, new GameplayDefinitions.Miracle(13, 13, 13));
    GameplayDefinitions.miracleEffects.put(
        13,
        new GameplayDefinitions.MiracleEffect(
            13, List.of(new GameplayDefinitions.EffectValue(0.3))));
    try {
      assertEquals(0, Gameplay46.number(s.start(List.of(199961), 0), "retcode"));
      var run = s.explorer.getGameplay().getRogue();
      assertTrue(RogueMiracles.acquire(s.explorer, 13));
      run.clear();
      s.explorer.getUniverseManager().offerBlessings();
      GameplayDefinitions.miracleEffects.put(
          13,
          new GameplayDefinitions.MiracleEffect(
              13, List.of(new GameplayDefinitions.EffectValue(0.9))));
      assertEquals(0, Gameplay46.number(roll(null), "retcode"));
      assertEquals(79, run.getCoins());
      assertEquals(1, run.getOfferRerolls());
    } finally {
      GameplayDefinitions.miracles.remove(13);
      GameplayDefinitions.miracleEffects.remove(13);
    }
  }
}
