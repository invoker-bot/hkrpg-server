package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class UniverseEnhance46Test {
  static final int BUFF = 19997201, ENTITY = 199973, INSTANCE = 400001;
  Session s;
  MapEntranceExcel priorEntry;

  static class Explorer extends UniverseRevive46Test.Explorer {
    final FloorInfo floor = new FloorInfo();

    public Scene getScene() {
      if (cached == null || cached.getPlaneId() != getPlaneId()) {
        var parent = super.getScene();
        cached =
            new Scene(this, parent.getExcel(), getFloorId()) {
              public FloorInfo getFloorInfo() {
                return floor;
              }

              public void syncLineup() {}
            };
      }
      return cached;
    }
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final Explorer explorer = new Explorer();
    int rawCommand;
    byte[] rawResponse;

    public Player getPlayer() {
      return explorer;
    }

    public void sendClientPacket(int command, byte[] bytes) {
      rawCommand = command;
      rawResponse = bytes;
    }
  }

  @BeforeEach
  void setup() {
    new Universe46LifecycleTest().setup();
    GameplayDefinitions.rogueConstants.put("Start_Rogue_Coin_Base", "300");
    GameplayDefinitions.rooms.put(
        Universe46LifecycleTest.ROOM,
        new GameplayDefinitions.Room(Universe46LifecycleTest.ROOM, 5, 12, 8));
    priorEntry = GameData.getMapEntranceExcelMap().get(12);
    GameData.getMapEntranceExcelMap()
        .add(
            new Gson()
                .fromJson(
                    "{\"ID\":12,\"PlaneID\":199962,\"FloorID\":199962001}",
                    MapEntranceExcel.class));
    buff(1, "Common");
    buff(2, "Common");
    buff(3, "Common");
    s = new Session();
  }

  void buff(int level, String category) {
    GameplayDefinitions.blessings.removeIf(
        b -> b.MazeBuffID() == BUFF && b.MazeBuffLevel() == level);
    GameplayDefinitions.blessings.add(
        new GameplayDefinitions.Blessing(BUFF, level, 100, category, 1, List.of()));
    GameData.getMazeBuffExcelMap()
        .add(
            new Gson().fromJson("{\"ID\":" + BUFF + ",\"Lv\":" + level + "}", MazeBuffExcel.class));
  }

  @AfterEach
  void cleanup() {
    new Universe46LifecycleTest().reset();
    GameplayDefinitions.blessings.removeIf(b -> b.MazeBuffID() == BUFF);
    GameData.getMazeBuffExcelMap().values().removeIf(b -> b.getID() == BUFF);
    if (priorEntry == null) GameData.getMapEntranceExcelMap().keySet().remove(12);
    else GameData.getMapEntranceExcelMap().add(priorEntry);
    GameplayDefinitions.miracles.remove(13);
    GameplayDefinitions.miracleEffects.remove(13);
  }

  void begin() {
    assertEquals(Retcode.SUCC, s.explorer.getUniverseManager().start(Universe46LifecycleTest.AREA));
    s.explorer.getGameplay().getRogue().getBlessings().put(BUFF, 1);
    var group =
        new Gson().fromJson("{\"NPCList\":[{\"ID\":400001,\"NPCID\":1013}]}", GroupInfo.class);
    group.setId(8);
    s.explorer.floor.getGroups().put(8, group);
    var npc = new EntityNpc(s.explorer.getScene(), group, group.getNPCList().getFirst());
    npc.setEntityId(ENTITY);
    s.explorer.getScene().getEntities().put(ENTITY, npc);
  }

  DynamicMessage query() throws Exception {
    assertTrue(VersionedPacketHandler.handle(s, 1804, new byte[0]));
    assertEquals("GetRogueBuffEnhanceInfoScRsp", s.responseName);
    return s.response;
  }

  int enhance(byte[] bytes) throws Exception {
    assertTrue(VersionedPacketHandler.handle(s, 1885, bytes));
    assertEquals(1807, s.rawCommand);
    var fields = UnknownFieldSet.parseFrom(s.rawResponse);
    return fields.getField(6).getVarintList().isEmpty()
        ? 0
        : fields.getField(6).getVarintList().getFirst().intValue();
  }

  byte[] request(int field, int id) {
    return UnknownFieldSet.newBuilder()
        .addField(field, UnknownFieldSet.Field.newBuilder().addVarint(id).build())
        .build()
        .toByteArray();
  }

  int price(DynamicMessage response) {
    assertEquals(0, Gameplay46.number(response, "PLDKENJLKME"));
    var list = (DynamicMessage) Gameplay46.value(response, "retcode");
    var quote = InitialData46Test.first(list, "EMAGFIBLNCJ");
    assertEquals(BUFF, Gameplay46.number(quote, "buff_id"));
    var cost = (DynamicMessage) Gameplay46.value(quote, "cost_data");
    var item = InitialData46Test.first(cost, "item_list");
    var pile = (DynamicMessage) Gameplay46.value(item, "pile_item");
    assertEquals(31, Gameplay46.number(pile, "item_id"));
    return Gameplay46.number(pile, "item_num");
  }

  @Test
  void nativeClassicField13EnhancesOnceAndReturnsTheActualField3BuffAndLevel() throws Exception {
    begin();
    assertEquals(100, price(query()));
    int saves = s.explorer.saves;
    assertEquals(0, enhance(request(13, BUFF)));
    var fields = UnknownFieldSet.parseFrom(s.rawResponse);
    var result =
        s.getProtocol()
            .clientSchema()
            .parse(
                "KJAMNOGFAMJ",
                fields.getField(3).getLengthDelimitedList().getFirst().toByteArray());
    assertEquals(BUFF, Gameplay46.number(result, "buff_id"));
    assertEquals(2, Gameplay46.number(result, "HAGDKKBAAKK"));
    assertEquals(200, s.explorer.getGameplay().getRogue().getCoins());
    assertEquals(saves + 1, s.explorer.saves);
    assertNotEquals(0, enhance(request(13, BUFF)));
    assertEquals(200, s.explorer.getGameplay().getRogue().getCoins());
    assertEquals(2, s.explorer.getGameplay().getRogue().getBlessings().get(BUFF));
  }

  @Test
  void pendingMiracleCannotBeBypassedThroughTheExistingEnhancementDomainMethod() {
    begin();
    var run = s.explorer.getGameplay().getRogue();
    run.getMiracleOffers().add(4);
    assertNotEquals(Retcode.SUCC, s.explorer.getUniverseManager().enhanceBlessing(BUFF));
    assertEquals(300, run.getCoins());
    assertEquals(1, run.getBlessings().get(BUFF));
  }

  @Test
  void requestFromTheOtherUniverseDefinitionOrWithoutQuotedPriceCannotCharge() throws Exception {
    begin();
    assertNotEquals(0, enhance(request(13, BUFF)));
    assertEquals(100, price(query()));
    assertNotEquals(0, enhance(request(9, BUFF)));
    assertEquals(300, s.explorer.getGameplay().getRogue().getCoins());
  }

  @Test
  void enhancementRequiresTheActualRestRoomHertaAndNoPendingChoice() throws Exception {
    begin();
    s.explorer.getScene().getEntities().remove(ENTITY);
    assertNotEquals(0, Gameplay46.number(query(), "PLDKENJLKME"));
    assertNotEquals(0, enhance(request(13, BUFF)));
    assertEquals(300, s.explorer.getGameplay().getRogue().getCoins());
  }

  @Test
  void advertisedCategoryAndDiscountAreFrozenUntilPurchaseDespiteResourceReload() throws Exception {
    begin();
    assertEquals(100, price(query()));
    buff(1, "Legendary");
    GameplayDefinitions.miracles.put(13, new GameplayDefinitions.Miracle(13, 13, 13));
    GameplayDefinitions.miracleEffects.put(
        13,
        new GameplayDefinitions.MiracleEffect(
            13, List.of(new GameplayDefinitions.EffectValue(.3))));
    assertTrue(RogueMiracles.acquire(s.explorer, 13));
    assertEquals(100, price(query()));
    assertEquals(0, enhance(request(13, BUFF)));
    assertEquals(200, s.explorer.getGameplay().getRogue().getCoins());
    s = new Session();
    begin();
    assertTrue(RogueMiracles.acquire(s.explorer, 13));
    assertEquals(112, price(query()));
    GameplayDefinitions.miracleEffects.clear();
    assertEquals(0, enhance(request(13, BUFF)));
    assertEquals(188, s.explorer.getGameplay().getRogue().getCoins());
  }

  @Test
  void insufficientFundsPausedRunsAndPendingMenusDoNotConsumeTheQuote() throws Exception {
    begin();
    query();
    var run = s.explorer.getGameplay().getRogue();
    run.spend(250);
    assertEquals(Retcode.ITEM_NOT_ENOUGH.getVal(), enhance(request(13, BUFF)));
    run.addCoins(100);
    run.getOffers().add(BUFF + 1);
    assertNotEquals(0, enhance(request(13, BUFF)));
    run.getOffers().clear();
    run.setInside(false);
    assertNotEquals(0, enhance(request(13, BUFF)));
    run.setInside(true);
    run.getMiracleOffers().add(4);
    assertNotEquals(0, enhance(request(13, BUFF)));
    run.getMiracleOffers().clear();
    assertEquals(150, run.getCoins());
    assertEquals(1, run.getBlessings().get(BUFF));
    assertEquals(0, enhance(request(13, BUFF)));
    assertEquals(50, run.getCoins());
  }

  @Test
  void wrongRoomGroupNpcInstanceAndResourceTargetNeverDebit() throws Exception {
    begin();
    query();
    var run = s.explorer.getGameplay().getRogue();
    var npc = (EntityNpc) s.explorer.getScene().getEntityById(ENTITY);
    npc.setGroupId(9);
    assertNotEquals(0, enhance(request(13, BUFF)));
    npc.setGroupId(8);
    npc.setNpcId(3011);
    assertNotEquals(0, enhance(request(13, BUFF)));
    npc.setNpcId(1013);
    npc.setInstId(INSTANCE + 1);
    assertNotEquals(0, enhance(request(13, BUFF)));
    npc.setInstId(INSTANCE);
    GameplayDefinitions.rooms.put(
        Universe46LifecycleTest.ROOM,
        new GameplayDefinitions.Room(Universe46LifecycleTest.ROOM, 1, 12, 8));
    assertNotEquals(0, enhance(request(13, BUFF)));
    GameplayDefinitions.rooms.put(
        Universe46LifecycleTest.ROOM,
        new GameplayDefinitions.Room(Universe46LifecycleTest.ROOM, 5, 12, 8));
    GameplayDefinitions.blessings.removeIf(b -> b.MazeBuffID() == BUFF && b.MazeBuffLevel() == 2);
    assertNotEquals(0, enhance(request(13, BUFF)));
    assertEquals(300, run.getCoins());
    assertEquals(1, run.getBlessings().get(BUFF));
  }

  @Test
  void unknownDuplicateTruncatedAndOverflowedNativeRequestsCannotAliasARealBuff() throws Exception {
    begin();
    query();
    var good = request(13, BUFF);
    var doubled = new byte[good.length * 2];
    System.arraycopy(good, 0, doubled, 0, good.length);
    System.arraycopy(good, 0, doubled, good.length, good.length);
    var oversized =
        UnknownFieldSet.newBuilder()
            .addField(13, UnknownFieldSet.Field.newBuilder().addVarint((1L << 32) + BUFF).build())
            .build()
            .toByteArray();
    for (var invalid :
        List.of(
            new byte[0],
            request(13, 0),
            request(9, BUFF),
            doubled,
            new byte[] {104, (byte) 128},
            oversized,
            request(13, -1))) assertNotEquals(0, enhance(invalid));
    assertTrue(VersionedPacketHandler.handle(s, 1804, good));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(s.response, "PLDKENJLKME"));
    assertEquals(300, s.explorer.getGameplay().getRogue().getCoins());
  }

  @Test
  void concurrentEnhancementRequestsSpendExactlyOneSavedQuote() throws Exception {
    begin();
    query();
    var gate = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<java.util.concurrent.Future<Retcode>>();
      for (int i = 0; i < 6; i++)
        tasks.add(
            executor.submit(
                () -> {
                  gate.await();
                  return s.explorer.getUniverseManager().enhanceBlessing(BUFF);
                }));
      gate.countDown();
      int successes = 0;
      for (var task : tasks)
        if (task.get(10, java.util.concurrent.TimeUnit.SECONDS) == Retcode.SUCC) successes++;
      assertEquals(1, successes);
      assertEquals(200, s.explorer.getGameplay().getRogue().getCoins());
      assertEquals(2, s.explorer.getGameplay().getRogue().getBlessings().get(BUFF));
    }
  }

  @Test
  void morphiaReloadKeepsOriginalQuoteAndCompletedDebitAndOldRecordsRequireAQuote()
      throws Exception {
    begin();
    query();
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "rogue-enhancement");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456785);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, s.explorer.getGameplay());
      store.save(document);
      gameplay.set(s.explorer, store.find(Player.class).first().getGameplay());
      buff(1, "Legendary");
      assertEquals(100, price(query()));
      assertEquals(0, enhance(request(13, BUFF)));
      gameplay.set(document, s.explorer.getGameplay());
      store.save(document);
      gameplay.set(s.explorer, store.find(Player.class).first().getGameplay());
      assertNotEquals(0, enhance(request(13, BUFF)));
      assertEquals(200, s.explorer.getGameplay().getRogue().getCoins());
      store
          .getDatabase()
          .getCollection(store.getMapper().getEntityModel(Player.class).getCollectionName())
          .updateOne(
              new org.bson.Document("_id", 123456785),
              new org.bson.Document(
                  "$unset", new org.bson.Document("gameplay.rogue.enhancementPlans", "")));
      gameplay.set(s.explorer, store.find(Player.class).first().getGameplay());
      var run = s.explorer.getGameplay().getRogue();
      assertTrue(run.getEnhancementPlans().isEmpty());
      run.getBlessings().put(BUFF, 1);
      assertNotEquals(0, enhance(request(13, BUFF)));
      assertEquals(160, price(query()));
      assertEquals(0, enhance(request(13, BUFF)));
      assertEquals(40, run.getCoins());
    } finally {
      mongo.shutdownNow();
    }
  }
}
