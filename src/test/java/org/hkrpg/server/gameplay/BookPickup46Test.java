package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.database.DatabaseManager;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.util.Position;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Original readings and real inventory writes; placement and storage use isolated fixtures, not UI.
 */
class BookPickup46Test {
  @TempDir Path root;
  final WorldChestActivity46Test fixture = new WorldChestActivity46Test();
  static de.bwaldvogel.mongo.MongoServer mongo;
  static DatabaseManager database, oldDatabase;
  Actor actor;

  static class Actor extends WorldChestActivity46Test.Actor {
    final Inventory inventory =
        new Inventory(this) {
          @Override
          public GrantResult grantItems(ItemParamMap items, boolean hint) {
            grants++;
            return failGrant ? new GrantResult(List.of(), false) : super.grantItems(items, hint);
          }
        };

    @Override
    public Inventory getInventory() {
      return inventory;
    }

    @Override
    public void save() {
      super.save();
      var stored = new Player();
      try {
        DailyMissionAcceptance46Test.set(stored, "uid", getUid());
        DailyMissionAcceptance46Test.set(stored, "gameplay", getGameplay());
      } catch (Exception error) {
        throw new IllegalStateException(error);
      }
      database.save(stored);
    }
  }

  @BeforeAll
  static void isolatedStorage() throws Exception {
    mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    var info = new Config.DatabaseInfo();
    info.uri = "mongodb://" + address.getHostString() + ":" + address.getPort();
    info.collection = "book_pickup_owned_fixture";
    info.useInternal = false;
    oldDatabase = LunarCore.getGameDatabase();
    database = new DatabaseManager(info, LunarCore.ServerType.GAME);
    setDatabase(database);
  }

  static void setDatabase(DatabaseManager value) throws Exception {
    var field = LunarCore.class.getDeclaredField("gameDatabase");
    field.setAccessible(true);
    field.set(null, value);
  }

  @AfterAll
  static void closeStorage() throws Exception {
    try {
      setDatabase(oldDatabase);
    } finally {
      if (database != null) database.close();
      if (mongo != null) mongo.shutdownNow();
    }
  }

  @BeforeEach
  void load() throws Exception {
    try (var input = getClass().getResourceAsStream("/fixtures/book-pickup-4.6.json")) {
      var source =
          JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
      for (String section : new String[] {"records", "groups", "floors", "locations", "headers"})
        for (var row : source.getAsJsonObject(section).entrySet()) {
          var target = fixture.source.getAsJsonObject(section);
          if (section.equals("records") && target.has(row.getKey()))
            target.getAsJsonArray(row.getKey()).addAll(row.getValue().getAsJsonArray());
          else target.add(row.getKey(), row.getValue());
        }
    }
    fixture.root = root;
    fixture.load();
    freshActor();
  }

  @AfterEach
  void restore() throws Exception {
    try {
      fixture.restore();
    } finally {
      database.getDatabase().drop();
    }
  }

  void freshActor() throws Exception {
    actor = new Actor();
    DailyMissionAcceptance46Test.set(actor, "uid", 123456805);
    DailyMissionAcceptance46Test.set(actor, "pos", new Position());
    actor.plane = 20001;
    actor.floor = 20001001;
    actor.entry = 2000101;
    fixture.player = actor;
    fixture.scene();
    near(book());
  }

  void reload() throws Exception {
    var stored = database.getObjectByField(Player.class, "_id", actor.getUid());
    assertNotNull(stored);
    freshActor();
    DailyMissionAcceptance46Test.set(actor, "gameplay", stored.getGameplay());
    for (var item : database.getObjects(GameItem.class).toList())
      if (item.getOwnerUid() == actor.getUid()) assertTrue(actor.getInventory().loadItem(item));
    fixture.scene();
    near(book());
  }

  void near(EntityProp prop) {
    assertNotNull(prop, "Books groups must not be silently excluded from the scene");
    actor.getPos().set(prop.getPos());
  }

  EntityProp book() {
    return book(300002);
  }

  EntityProp book(int instance) {
    return actor.scene.getEntitiesByGroup(EntityProp.class, 44).stream()
        .filter(p -> p.getInstId() == instance)
        .findFirst()
        .orElse(null);
  }

  BookPickups.Result pickup(EntityProp prop) {
    return BookPickups.pickUp(actor, 118191, prop.getEntityId());
  }

  void untouched() {
    assertEquals(0, actor.grants);
    assertEquals(0, actor.saves);
    assertTrue(actor.getGameplay().getBookPickups().getReceipts().isEmpty());
    assertNull(actor.getInventory().getMaterialByItemId(190170));
    assertEquals(PropState.EventClose, book().getState());
  }

  @Test
  void receptionDeskReadingHasAnAuthoritativePickupEntity() {
    assertNotNull(book(), "Books groups must not be silently excluded from the scene");
    assertEquals(5, actor.scene.getEntitiesByGroup(EntityProp.class, 44).size());
    assertNull(book(300003), "An authored delayed reading must keep its spawn gate");
    assertNull(book(300007), "Dummy event 10000 must not become a reward");
    assertEquals(
        Retcode.SUCC, SceneInteractions.validateNative(actor, 118191, book().getEntityId()));
    untouched();
  }

  @ParameterizedTest
  @ValueSource(ints = {300001, 300002, 300004, 300005, 300006})
  void eachAuthoredReadingGrantsItsOwnBookOnceAndRestoresAfterTwoReloads(int instance)
      throws Exception {
    var prop = book(instance);
    var event = GameData.getPlaneEventExcelMap().get(prop.getPropInfo().getEventID(), 0);
    var bookId =
        GameData.getRewardExcelMap()
            .get(event.getReward())
            .getRewards()
            .keySet()
            .iterator()
            .nextInt();
    near(prop);
    assertEquals(Retcode.SUCC, pickup(prop).retcode());
    assertEquals(PropState.EventOpen, prop.getState());
    assertEquals(1, actor.getInventory().getMaterialByItemId(bookId).getCount());
    assertEquals(1, actor.getDisplayInfo().getBookCount());
    assertEquals(1, actor.grants);
    assertNotEquals(Retcode.SUCC, pickup(prop).retcode());
    for (int i = 0; i < 2; i++) {
      reload();
      assertEquals(PropState.EventOpen, book(instance).getState());
      near(book(instance));
      assertNotEquals(Retcode.SUCC, pickup(book(instance)).retcode());
      assertEquals(1, actor.getInventory().getMaterialByItemId(bookId).getCount());
      assertEquals(1, actor.getDisplayInfo().getBookCount());
      assertEquals(0, actor.grants);
      assertEquals(0, actor.saves);
      assertEquals(1, actor.getGameplay().getBookPickups().getReceipts().size());
    }
  }

  @Test
  void bothDeskReadingsCanBeClaimedIndependently() {
    assertEquals(Retcode.SUCC, pickup(book()).retcode());
    near(book(300001));
    assertEquals(Retcode.SUCC, pickup(book(300001)).retcode());
    assertEquals(1, actor.getInventory().getMaterialByItemId(190170).getCount());
    assertEquals(1, actor.getInventory().getMaterialByItemId(190282).getCount());
    assertEquals(2, actor.getDisplayInfo().getBookCount());
    assertEquals(2, actor.getGameplay().getBookPickups().getReceipts().size());
  }

  @Test
  void legacyInteractionAlsoDeliversTheBookBeforeHidingIt() {
    assertNotNull(actor.interactWithProp(118191, book().getEntityId()));
    assertNotNull(actor.getInventory().getMaterialByItemId(190170));
    assertEquals(1, actor.getInventory().getMaterialByItemId(190170).getCount());
    assertTrue(
        actor.getGameplay().getBookPickups().getReceipts().values().iterator().next().isGranted());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
  void originalWorldLevelRowsAllResolveTheSameDeskBook(int world) throws Exception {
    DailyMissionAcceptance46Test.set(actor, "worldLevel", world);
    fixture.scene();
    near(book());
    assertEquals(Retcode.SUCC, pickup(book()).retcode());
    assertEquals(1, actor.getInventory().getMaterialByItemId(190170).getCount());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "distant",
        "extreme",
        "floor",
        "entry",
        "unloaded",
        "source",
        "reward",
        "event",
        "cost",
        "config"
      })
  void invalidContextCannotCollectOrHideTheReading(String fault) throws Exception {
    var prop = book();
    int config = 118191;
    switch (fault) {
      case "distant" -> actor.getPos().set(new Position(500000, 0, 0));
      case "extreme" -> actor.getPos().set(new Position(Integer.MIN_VALUE, 0, 0));
      case "floor" -> actor.floor++;
      case "entry" -> actor.entry++;
      case "unloaded" -> actor.scene.getLoadedGroups().remove(44);
      case "source" -> {
        var changed =
            fixture.gson.fromJson(
                fixture.gson.toJson(prop.getPropInfo()), emu.lunarcore.data.config.PropInfo.class);
        DailyMissionAcceptance46Test.set(changed, "EventID", 20001509);
        prop.setPropInfo(changed);
      }
      case "reward" -> GameData.getRewardExcelMap().keySet().remove(2000149);
      case "event" ->
          DailyMissionAcceptance46Test.set(
              GameData.getPlaneEventExcelMap().get(20001503, 0), "StageID", 100);
      case "cost" ->
          DailyMissionAcceptance46Test.set(
              GameData.getInteractExcelMap().get(118191), "InteractCostType", "Item");
      case "config" -> config = 118192;
      default -> fail(fault);
    }
    assertNotEquals(Retcode.SUCC, BookPickups.pickUp(actor, config, prop.getEntityId()).retcode());
    untouched();
  }

  @Test
  void oldGenericOpenStateCannotHideAnUnpaidReading() {
    actor
        .getGameplay()
        .getStoryValues()
        .put("prop-state:20001:20001001:44:300002", PropState.EventOpen.getVal());
    fixture.scene();
    assertEquals(PropState.EventClose, book().getState());
    assertEquals(Retcode.SUCC, pickup(book()).retcode());
  }

  @Test
  void fullStackRejectsBeforeReservingAndCanRetryAfterSpaceIsAvailable() {
    assertTrue(actor.getInventory().addItem(190170, 99999));
    assertEquals(Retcode.ITEM_EXCEED_LIMIT, pickup(book()).retcode());
    assertEquals(0, actor.saves);
    assertTrue(actor.getGameplay().getBookPickups().getReceipts().isEmpty());
    actor.getInventory().getMaterialByItemId(190170).setCount(99998);
    assertEquals(Retcode.SUCC, pickup(book()).retcode());
    assertEquals(99999, actor.getInventory().getMaterialByItemId(190170).getCount());
  }

  @Test
  void reservationSaveFailureLeavesItemAvailableForRetry() {
    actor.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, pickup(book()).retcode());
    untouched();
    actor.failSave = false;
    assertEquals(Retcode.SUCC, pickup(book()).retcode());
  }

  @ParameterizedTest
  @ValueSource(strings = {"delivery", "completion"})
  void uncertainDeliveryDoesNotHideOrPayAgainAfterReload(String fault) throws Exception {
    actor.failGrant = fault.equals("delivery");
    actor.failOnSave = fault.equals("completion") ? 2 : -1;
    assertNotEquals(Retcode.SUCC, pickup(book()).retcode());
    assertEquals(PropState.EventClose, book().getState());
    assertEquals(1, actor.grants);
    assertFalse(
        actor.getGameplay().getBookPickups().getReceipts().values().iterator().next().isGranted());
    assertEquals(Retcode.SYSTEM_BUSY, pickup(book()).retcode());
    assertEquals(1, actor.grants);
    reload();
    assertEquals(PropState.EventClose, book().getState());
    assertEquals(Retcode.SYSTEM_BUSY, pickup(book()).retcode());
    assertEquals(0, actor.grants);
    var stock = actor.getInventory().getMaterialByItemId(190170);
    if (fault.equals("delivery")) assertNull(stock);
    else assertEquals(1, stock.getCount());
  }

  @Test
  void simultaneousPickupRequestsHaveOneDurableGrant() throws Exception {
    var prop = book();
    try (var threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var one = threads.submit(() -> pickup(prop).retcode());
      var two = threads.submit(() -> pickup(prop).retcode());
      assertEquals(
          1, java.util.stream.Stream.of(one.get(), two.get()).filter(Retcode.SUCC::equals).count());
    }
    assertEquals(1, actor.grants);
    reload();
    assertEquals(1, actor.getInventory().getMaterialByItemId(190170).getCount());
    assertEquals(PropState.EventOpen, book().getState());
  }

  static class Session extends GameSession {
    final Player actor;
    DynamicMessage response;

    Session(Player actor) {
      super(null);
      this.actor = actor;
      setState(SessionState.ACTIVE);
    }

    @Override
    public Player getPlayer() {
      return actor;
    }

    @Override
    public ProtocolBridge getProtocol() {
      return ProtocolBridge.forVersion("4.6.0");
    }

    @Override
    public void sendClientPacket(String name, byte[] bytes) {
      assertEquals("InteractPropScRsp", name);
      try {
        response = getProtocol().clientSchema().parse(name, bytes);
      } catch (Exception error) {
        throw new AssertionError(error);
      }
    }
  }

  @Test
  void nativeInteractPacketReachesPickupAndReturnsActualState() throws Exception {
    var session = new Session(actor);
    var request =
        DynamicMessage.newBuilder(
            session.getProtocol().clientSchema().message("InteractPropCsReq"));
    request.setField(
        request.getDescriptorForType().findFieldByName("prop_entity_id"), book().getEntityId());
    request.setField(request.getDescriptorForType().findFieldByName("interact_id2"), 118191);
    request.setField(request.getDescriptorForType().findFieldByName("interact_id"), 9876543210L);
    assertTrue(VersionedPacketHandler.handle(session, 1405, request.build().toByteArray()));
    assertEquals(
        0,
        session.response.getField(
            session.response.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(
        PropState.EventOpen.getVal(),
        session.response.getField(
            session.response.getDescriptorForType().findFieldByName("prop_state")));
    assertTrue(session.response.getUnknownFields().asMap().isEmpty());
    assertEquals(1, actor.getInventory().getMaterialByItemId(190170).getCount());
    reload();
    assertEquals(PropState.EventOpen, book().getState());
  }
}
