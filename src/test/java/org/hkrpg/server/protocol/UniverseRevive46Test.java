package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.PropInfo;
import emu.lunarcore.data.excel.PropExcel;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class UniverseRevive46Test {
  static final int ENTITY = 199974, AVATAR = 199961;
  PropExcel prior;
  emu.lunarcore.data.excel.MapEntranceExcel priorEntry;
  Session s;

  static class Explorer extends UniverseLifecycleTest.Explorer {
    Scene cached;
    int saves;

    public Scene getScene() {
      if (cached == null || cached.getPlaneId() != getPlaneId()) cached = super.getScene();
      return cached;
    }

    public void save() {
      saves++;
    }

    public emu.lunarcore.game.avatar.GameAvatar getAvatarById(int id) {
      return getAvatars().getAvatars().get(id);
    }
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final Explorer explorer = new Explorer();

    public Player getPlayer() {
      return explorer;
    }
  }

  @BeforeEach
  void setup() {
    new Universe46LifecycleTest().setup();
    // Isolate revival fees with a fixture balance; the production 4.6 resource base is 50.
    GameplayDefinitions.rogueConstants.put("Start_Rogue_Coin_Base", "100");
    GameplayDefinitions.rogueConstants.put("Rogue_Recover_Times", "0");
    GameplayDefinitions.rogueConstants.put("Rogue_Recover_Effect", "1");
    GameplayDefinitions.rogueConstants.put("Rogue_Recover_Percent", "100");
    GameplayDefinitions.rogueConstants.put("Rogue_Recover_ItemCost", "[31:80]");
    prior = GameData.getPropExcelMap().get(1002);
    var prop =
        new Gson()
            .fromJson(
                """
                {"ID":1002,"PropType":"PROP_ROGUE_OBJECT",
                 "JsonPath":"Config/Props/Rogue/Prop_Rogue_ReviveConsole_01_Config.json",
                 "PropStateList":["Closed","Open","Locked","TriggerDisable","TriggerEnable"]}
                """,
                PropExcel.class);
    prop.onLoad();
    GameData.getPropExcelMap().add(prop);
    priorEntry = GameData.getMapEntranceExcelMap().get(12);
    GameData.getMapEntranceExcelMap()
        .add(
            new Gson()
                .fromJson(
                    "{\"ID\":12,\"PlaneID\":199962,\"FloorID\":199962001}",
                    emu.lunarcore.data.excel.MapEntranceExcel.class));
    s = new Session();
  }

  @AfterEach
  void cleanup() {
    new Universe46LifecycleTest().reset();
    GameplayDefinitions.rogueConstants.clear();
    if (prior == null) GameData.getPropExcelMap().keySet().remove(1002);
    else GameData.getPropExcelMap().add(prior);
    if (priorEntry == null) GameData.getMapEntranceExcelMap().keySet().remove(12);
    else GameData.getMapEntranceExcelMap().add(priorEntry);
  }

  void begin() {
    assertEquals(Retcode.SUCC, s.explorer.getUniverseManager().start(Universe46LifecycleTest.AREA));
    console();
    s.explorer.getGameplay().getRogue().getVitals().get(AVATAR).setHp(0);
  }

  EntityProp console() {
    var group = new GroupInfo();
    group.setId(8);
    var info = new Gson().fromJson("{\"ID\":300002,\"PropID\":1002}", PropInfo.class);
    var prop =
        new EntityProp(s.explorer.getScene(), GameData.getPropExcelMap().get(1002), group, info);
    prop.setEntityId(ENTITY);
    s.explorer.getScene().getEntities().put(ENTITY, prop);
    return prop;
  }

  DynamicMessage revive(List<Integer> ids, int entity) throws Exception {
    var req = Gameplay46.builder(s, "ReviveRogueAvatarCsReq");
    ids.forEach(id -> Gameplay46.add(req, "NOEPBNMJFGL", id));
    Gameplay46.set(req, "interacted_prop_entity_id", entity);
    assertTrue(VersionedPacketHandler.handle(s, 1811, req.build().toByteArray()));
    assertEquals("ReviveRogueAvatarScRsp", s.responseName);
    return s.response;
  }

  static int code(DynamicMessage response) {
    return Gameplay46.number(response, "PLDKENJLKME");
  }

  int price(DynamicMessage info) {
    var costs = (DynamicMessage) Gameplay46.value(info, "LNIEFKBMIPD");
    var item = InitialData46Test.first(costs, "item_list");
    var pile = (DynamicMessage) Gameplay46.value(item, "pile_item");
    assertEquals(31, Gameplay46.number(pile, "item_id"));
    return Gameplay46.number(pile, "item_num");
  }

  @Test
  void nativeRevivalChargesSavedFragmentsRestoresOnlyTheRogueTeamAndCannotRepeat()
      throws Exception {
    var normal = s.explorer.getCurrentLineup();
    s.explorer.getAvatarById(AVATAR).setCurrentHp(normal, 2300);
    begin();
    var run = s.explorer.getGameplay().getRogue();
    run.getVitals().get(AVATAR).setSp(1234);
    int mp = run.getMp(), saves = s.explorer.saves;
    var rsp = revive(List.of(AVATAR), ENTITY);
    assertEquals(0, code(rsp));
    assertEquals(80, price((DynamicMessage) Gameplay46.value(rsp, "PHNAANFIFGB")));
    assertEquals(20, run.getCoins());
    assertEquals(10000, run.getVitals().get(AVATAR).getHp());
    assertEquals(1234, run.getVitals().get(AVATAR).getSp());
    assertEquals(mp, run.getMp());
    assertEquals(2300, s.explorer.getAvatarById(AVATAR).getCurrentHp(normal));
    assertEquals(saves + 1, s.explorer.saves);
    assertNotEquals(0, code(revive(List.of(AVATAR), ENTITY)));
    assertEquals(20, run.getCoins());
  }

  @Test
  void everyTargetAndCurrentConsoleAreValidatedBeforeAnyChargeOrHealthChange() throws Exception {
    begin();
    for (var ids :
        List.of(List.<Integer>of(), List.of(AVATAR, AVATAR), List.of(AVATAR, AVATAR + 1))) {
      assertNotEquals(0, code(revive(ids, ENTITY)));
    }
    assertNotEquals(0, code(revive(List.of(AVATAR), ENTITY + 1)));
    console().setState(PropState.Locked, false);
    assertNotEquals(0, code(revive(List.of(AVATAR), ENTITY)));
    console().setGroupId(9);
    assertNotEquals(0, code(revive(List.of(AVATAR), ENTITY)));
    assertEquals(100, s.explorer.getGameplay().getRogue().getCoins());
    assertEquals(0, s.explorer.getGameplay().getRogue().getVitals().get(AVATAR).getHp());
    console();
    assertEquals(0, code(revive(List.of(AVATAR), ENTITY)));
  }

  @Test
  void insufficientFundsAndPausedOrTerminalRunsStayUnchanged() throws Exception {
    begin();
    var run = s.explorer.getGameplay().getRogue();
    run.spend(30);
    assertEquals(Retcode.ITEM_NOT_ENOUGH.getVal(), code(revive(List.of(AVATAR), ENTITY)));
    assertEquals(70, run.getCoins());
    assertEquals(Retcode.SUCC, s.explorer.getUniverseManager().leave(false));
    assertNotEquals(0, code(revive(List.of(AVATAR), ENTITY)));
    assertEquals(
        Retcode.SUCC, s.explorer.getUniverseManager().resume(Universe46LifecycleTest.AREA, false));
    console();
    run.addCoins(10);
    assertEquals(0, code(revive(List.of(AVATAR), ENTITY)));
    run.getVitals().get(AVATAR).setHp(0);
    run.addCoins(80);
    run.finish(GameplayState.RogueRun.Phase.FAILED);
    assertNotEquals(0, code(revive(List.of(AVATAR), ENTITY)));
    assertEquals(80, run.getCoins());
  }

  @Test
  void hotReloadCannotRepriceTheRunOrChangeTheRestoredHealthRatio() throws Exception {
    GameplayDefinitions.rogueConstants.put("Rogue_Recover_Percent", "25");
    begin();
    GameplayDefinitions.rogueConstants.put("Rogue_Recover_Percent", "100");
    GameplayDefinitions.rogueConstants.put("Rogue_Recover_ItemCost", "[31:1]");
    assertEquals(0, code(revive(List.of(AVATAR), ENTITY)));
    assertEquals(2500, s.explorer.getGameplay().getRogue().getVitals().get(AVATAR).getHp());
    assertEquals(20, s.explorer.getGameplay().getRogue().getCoins());
  }

  @Test
  void initialSnapshotAdvertisesTheActualSavedRevivalCost() {
    begin();
    var game = Universe46.snapshot(s, null);
    var run = (DynamicMessage) Gameplay46.value(game, "CFDIKLBINHK");
    var avatars = (DynamicMessage) Gameplay46.value(run, "EIOHFJMFOKF");
    assertEquals(80, price((DynamicMessage) Gameplay46.value(avatars, "MFEODNJNGDP")));
  }

  @Test
  void nativeCostNotificationUsesTheFrozenPlanAndRejectsRequestsDuringPendingSelection()
      throws Exception {
    begin();
    RogueRevival46.sync(s);
    assertEquals("SyncRogueReviveInfoScNotify", s.responseName);
    assertEquals(80, price((DynamicMessage) Gameplay46.value(s.response, "MFEODNJNGDP")));
    var run = s.explorer.getGameplay().getRogue();
    run.offer(List.of(19997401));
    assertNotEquals(0, code(revive(List.of(AVATAR), ENTITY)));
    assertEquals(100, run.getCoins());
    assertEquals(List.of(19997401), run.getOffers());
  }

  @Test
  void twoDeadAvatarsUseOneAtomicBatchAndOneSavedDebit() throws Exception {
    int second = AVATAR + 1;
    var avatar =
        new emu.lunarcore.game.avatar.GameAvatar() {
          public int getAvatarId() {
            return second;
          }

          public void save() {}
        };
    s.explorer.getAvatars().getAvatars().put(second, avatar);
    s.explorer.getCurrentLineup().getAvatars().add(second);
    begin();
    var run = s.explorer.getGameplay().getRogue();
    run.getVitals().get(second).setHp(5000);
    assertNotEquals(0, code(revive(List.of(AVATAR, second), ENTITY)));
    assertEquals(0, run.getVitals().get(AVATAR).getHp());
    run.getVitals().get(second).setHp(0);
    assertEquals(Retcode.ITEM_NOT_ENOUGH.getVal(), code(revive(List.of(AVATAR, second), ENTITY)));
    assertEquals(100, run.getCoins());
    assertEquals(0, run.getVitals().get(second).getHp());
    run.addCoins(80);
    int saves = s.explorer.saves;
    assertEquals(0, code(revive(List.of(AVATAR, second), ENTITY)));
    assertEquals(20, run.getCoins());
    assertEquals(10000, run.getVitals().get(second).getHp());
    assertEquals(saves + 1, s.explorer.saves);
  }

  @Test
  void concurrentRequestsReviveAndChargeExactlyOnce() throws Exception {
    begin();
    var gate = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<java.util.concurrent.Future<Retcode>>();
      for (int i = 0; i < 6; i++)
        tasks.add(
            executor.submit(
                () -> {
                  gate.await();
                  return RogueRevival.revive(s.explorer, List.of(AVATAR), ENTITY);
                }));
      gate.countDown();
      int success = 0;
      for (var task : tasks)
        if (task.get(10, java.util.concurrent.TimeUnit.SECONDS) == Retcode.SUCC) success++;
      assertEquals(1, success);
      assertEquals(20, s.explorer.getGameplay().getRogue().getCoins());
    }
  }

  @Test
  void invalidResourceVariantsAndUnknownWireFieldsNeverEnableRevival() throws Exception {
    for (var invalid :
        List.of(
            Map.entry("Rogue_Recover_ItemCost", "[32:80]"),
            Map.entry("Rogue_Recover_ItemCost", "[31:-1]"),
            Map.entry("Rogue_Recover_ItemCost", "[31:0]"),
            Map.entry("Rogue_Recover_ItemCost", "[31:2147483648]"),
            Map.entry("Rogue_Recover_Percent", "101"),
            Map.entry("Rogue_Recover_Percent", "NaN"),
            Map.entry("Rogue_Recover_Times", "1"),
            Map.entry("Rogue_Recover_Effect", "2"))) {
      var previous = GameplayDefinitions.rogueConstants.put(invalid.getKey(), invalid.getValue());
      s = new Session();
      begin();
      assertNotEquals(0, code(revive(List.of(AVATAR), ENTITY)));
      assertEquals(100, s.explorer.getGameplay().getRogue().getCoins());
      assertNull(RogueRevival46.info(s));
      GameplayDefinitions.rogueConstants.put(invalid.getKey(), previous);
    }
    s = new Session();
    begin();
    var req = Gameplay46.builder(s, "ReviveRogueAvatarCsReq");
    Gameplay46.add(req, "NOEPBNMJFGL", AVATAR);
    Gameplay46.set(req, "interacted_prop_entity_id", ENTITY);
    req.setUnknownFields(
        com.google.protobuf.UnknownFieldSet.newBuilder()
            .addField(
                199, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build());
    assertTrue(VersionedPacketHandler.handle(s, 1811, req.build().toByteArray()));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), code(s.response));
    assertEquals(100, s.explorer.getGameplay().getRogue().getCoins());
  }

  @Test
  void morphiaRestoreKeepsPriceHealthAndDebitWhileOldRecordsRemainDisabled() throws Exception {
    begin();
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "universe-revival");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456786);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, s.explorer.getGameplay());
      store.save(document);
      gameplay.set(s.explorer, store.find(Player.class).first().getGameplay());
      GameplayDefinitions.rogueConstants.clear();
      assertEquals(80, price(RogueRevival46.info(s)));
      assertEquals(0, code(revive(List.of(AVATAR), ENTITY)));
      gameplay.set(document, s.explorer.getGameplay());
      store.save(document);
      var restored = store.find(Player.class).first();
      gameplay.set(s.explorer, restored.getGameplay());
      assertEquals(20, restored.getGameplay().getRogue().getCoins());
      assertEquals(10000, restored.getGameplay().getRogue().getVitals().get(AVATAR).getHp());
      assertNotEquals(0, code(revive(List.of(AVATAR), ENTITY)));
      // Older documents contain no reserved recovery plan; loading must not guess one.
      client
          .getDatabase("universe-revival")
          .getCollection("players")
          .updateMany(
              new org.bson.Document(),
              new org.bson.Document(
                  "$unset",
                  new org.bson.Document("gameplay.rogue.revivalCost", "")
                      .append("gameplay.rogue.revivalHp", "")));
      restored = store.find(Player.class).first();
      gameplay.set(s.explorer, restored.getGameplay());
      assertNull(RogueRevival46.info(s));
      assertNotEquals(0, code(revive(List.of(AVATAR), ENTITY)));
    } finally {
      mongo.shutdown();
    }
  }
}
