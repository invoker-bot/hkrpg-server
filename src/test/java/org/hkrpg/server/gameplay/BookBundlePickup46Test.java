package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.database.DatabaseManager;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.inventory.tabs.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.protocol.VersionedPacketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original Rocky desk bundle; prerequisite completion and proximity are isolated fixtures. */
class BookBundlePickup46Test {
  @TempDir Path root;
  @TempDir static Path storageRoot;
  final BookPickup46Test fixture = new BookPickup46Test();
  static final List<Integer> BOOKS = List.of(190172, 190173, 190174);
  static final String KEY = "10001:10001001:17:300001";

  @BeforeAll
  static void storage() throws Exception {
    BookPickup46Test.oldDatabase = LunarCore.getGameDatabase();
    openStorage();
  }

  static void openStorage() throws Exception {
    BookPickup46Test.mongo =
        new de.bwaldvogel.mongo.MongoServer(
            new de.bwaldvogel.mongo.backend.h2.H2Backend(
                storageRoot.resolve("database.mv").toString()));
    var address = BookPickup46Test.mongo.bind();
    var info = new Config.DatabaseInfo();
    info.uri = "mongodb://" + address.getHostString() + ":" + address.getPort();
    info.collection = "book_pickup_owned_fixture";
    info.useInternal = false;
    BookPickup46Test.database = new DatabaseManager(info, LunarCore.ServerType.GAME);
    BookPickup46Test.setDatabase(BookPickup46Test.database);
  }

  @AfterAll
  static void closeStorage() throws Exception {
    BookPickup46Test.closeStorage();
  }

  @BeforeEach
  void load() throws Exception {
    try (var input = getClass().getResourceAsStream("/fixtures/book-bundle-4.6.json")) {
      var source =
          JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
      for (String section : List.of("records", "groups", "floors", "locations", "headers")) {
        var target = fixture.fixture.source.getAsJsonObject(section);
        for (var row : source.getAsJsonObject(section).entrySet()) {
          if (section.equals("records") && target.has(row.getKey()))
            target.getAsJsonArray(row.getKey()).addAll(row.getValue().getAsJsonArray());
          else target.add(row.getKey(), row.getValue());
        }
      }
    }
    fixture.root = root;
    fixture.load();
    var done = new QuestState(2000110);
    assertTrue(done.advance(1, 1, 1700000000));
    actor().getGameplay().getMainMissions().put(2000110, done);
    visit();
  }

  @AfterEach
  void restore() throws Exception {
    fixture.restore();
  }

  BookPickup46Test.Actor actor() {
    return fixture.actor;
  }

  void visit() {
    actor().plane = 10001;
    actor().floor = 10001001;
    actor().entry = 1000102;
    fixture.fixture.scene();
    if (book() != null) fixture.near(book());
  }

  EntityProp book() {
    return actor().scene.getEntitiesByGroup(EntityProp.class, 17).stream().findFirst().orElse(null);
  }

  Retcode pickup() {
    return BookPickups.pickUp(actor(), 118191, book().getEntityId()).retcode();
  }

  void reload() throws Exception {
    var saved = BookPickup46Test.database.getObjectByField(Player.class, "_id", actor().getUid());
    assertNotNull(saved);
    fixture.freshActor();
    DailyMissionAcceptance46Test.set(actor(), "gameplay", saved.getGameplay());
    for (var item : BookPickup46Test.database.getObjects(GameItem.class).toList())
      if (item.getOwnerUid() == actor().getUid()) assertTrue(actor().getInventory().loadItem(item));
    visit();
  }

  void counts(int expected) {
    for (int id : BOOKS) {
      var item = actor().getInventory().getMaterialByItemId(id);
      assertEquals(expected, item == null ? 0 : item.getCount(), "book " + id);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
  void authoredBundleDeliversEveryVolumeOnceAcrossReloads(int world) throws Exception {
    DailyMissionAcceptance46Test.set(actor(), "worldLevel", world);
    visit();
    assertNotNull(book());
    assertEquals(Retcode.SUCC, pickup());
    counts(1);
    assertEquals(3, actor().getDisplayInfo().getBookCount());
    assertEquals(1, actor().grants);
    assertNotEquals(Retcode.SUCC, pickup());
    for (int i = 0; i < 2; i++) {
      reload();
      assertEquals(PropState.EventOpen, book().getState());
      assertNotEquals(Retcode.SUCC, pickup());
      counts(1);
      assertEquals(0, actor().grants);
      assertTrue(actor().getGameplay().getBookPickups().getReceipts().get(KEY).isGranted());
    }
  }

  @Test
  void nativeRequestReturnsSuccessOnlyAfterTheWholeBundleIsDelivered() throws Exception {
    var session = new BookPickup46Test.Session(actor());
    var schema = session.getProtocol().clientSchema();
    var request = DynamicMessage.newBuilder(schema.message("InteractPropCsReq"));
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
    counts(1);
  }

  @Test
  void legacyEntryAlsoDeliversAllVolumes() {
    assertNotNull(actor().interactWithProp(118191, book().getEntityId()));
    counts(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void capacityIsCheckedForAllNewStacksBeforeAnyReservation(int capacity) {
    actor().getInventory().createTab(InventoryTabType.MATERIAL, new MaterialInventoryTab(capacity));
    assertEquals(Retcode.ITEM_EXCEED_LIMIT, pickup());
    counts(0);
    assertEquals(0, actor().saves);
    assertEquals(0, actor().grants);
    assertTrue(actor().getGameplay().getBookPickups().getReceipts().isEmpty());
    actor().getInventory().createTab(InventoryTabType.MATERIAL, new MaterialInventoryTab(3));
    assertEquals(Retcode.SUCC, pickup());
    counts(1);
  }

  @Test
  void existingStackUsesNoNewSlotButEveryVolumeKeepsItsPileLimit() {
    actor().getInventory().createTab(InventoryTabType.MATERIAL, new MaterialInventoryTab(3));
    assertTrue(actor().getInventory().addItem(190174, 99999));
    assertEquals(Retcode.ITEM_EXCEED_LIMIT, pickup());
    assertNull(actor().getInventory().getMaterialByItemId(190172));
    assertEquals(0, actor().saves);
    actor().getInventory().getMaterialByItemId(190174).setCount(99998);
    assertEquals(Retcode.SUCC, pickup());
    assertEquals(99999, actor().getInventory().getMaterialByItemId(190174).getCount());
    assertEquals(1, actor().getInventory().getMaterialByItemId(190172).getCount());
  }

  @ParameterizedTest
  @ValueSource(strings = {"reservation", "delivery", "completion"})
  void failedDeliveryCannotHideTheBundleOrPayAgain(String fault) throws Exception {
    actor().failSave = fault.equals("reservation");
    actor().failGrant = fault.equals("delivery");
    actor().failOnSave = fault.equals("completion") ? 2 : -1;
    assertNotEquals(Retcode.SUCC, pickup());
    assertEquals(PropState.EventClose, book().getState());
    if (fault.equals("reservation")) {
      counts(0);
      assertTrue(actor().getGameplay().getBookPickups().getReceipts().isEmpty());
      actor().failSave = false;
      assertEquals(Retcode.SUCC, pickup());
      counts(1);
    } else {
      assertEquals(Retcode.SYSTEM_BUSY, pickup());
      reload();
      assertEquals(Retcode.SYSTEM_BUSY, pickup());
      counts(fault.equals("completion") ? 1 : 0);
      assertEquals(0, actor().grants);
    }
  }

  @Test
  void concurrentRequestsHaveOneBundleDelivery() throws Exception {
    try (var threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var a = threads.submit(this::pickup);
      var b = threads.submit(this::pickup);
      assertEquals(
          1, java.util.stream.Stream.of(a.get(), b.get()).filter(Retcode.SUCC::equals).count());
    }
    assertEquals(1, actor().grants);
    reload();
    counts(1);
  }

  @Test
  void completedBundleSurvivesTwoH2BackendReopensWithoutAnotherDelivery() throws Exception {
    assertEquals(Retcode.SUCC, pickup());
    for (int i = 0; i < 2; i++) {
      BookPickup46Test.database.close();
      BookPickup46Test.mongo.shutdownNow();
      openStorage();
      reload();
      assertEquals(PropState.EventOpen, book().getState());
      assertNotEquals(Retcode.SUCC, pickup());
      counts(1);
      assertEquals(0, actor().grants);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing-book", "non-book", "two-copies"})
  void anUnsupportedMemberRejectsTheWholeAuthoredReward(String fault) {
    if (fault.equals("missing-book")) GameData.getItemExcelMap().keySet().remove(190174);
    else {
      var reward = GameData.getRewardExcelMap().get(2000167).getRewards();
      if (fault.equals("non-book")) reward.add(2, 100);
      else reward.add(190174, 1);
    }
    assertNotEquals(Retcode.SUCC, pickup());
    counts(0);
    assertEquals(0, actor().saves);
    assertEquals(0, actor().grants);
    assertEquals(PropState.EventClose, book().getState());
  }

  @Test
  void aSingleBookReceiptCannotPretendThatTheWholeBundleWasDelivered() throws Exception {
    assertEquals(Retcode.SUCC, pickup());
    var json = new Gson().toJsonTree(actor().getGameplay().getBookPickups()).getAsJsonObject();
    json.getAsJsonObject("receipts").getAsJsonObject(KEY).remove("books");
    actor().getGameplay().bookPickups(new Gson().fromJson(json, BookPickupState.class));
    visit();
    assertEquals(PropState.EventClose, book().getState());
    assertNotEquals(Retcode.SUCC, pickup());
    counts(1);
  }

  @Test
  void unfinishedPrerequisiteMustNotShowThePickupEvenOnExplicitGroupLoad() {
    actor().getGameplay().getMainMissions().put(2000110, new QuestState(2000110));
    visit();
    assertNull(book(), "The original G17 requires main mission 2000110 Finish");
    actor().scene.loadGroup(17);
    assertNull(book());
    counts(0);
    assertTrue(actor().getGameplay().getMainMissions().get(2000110).advance(1, 1, 1700000000));
    actor().scene.refreshMissionGroups();
    assertNotNull(book());
    fixture.near(book());
    assertEquals(Retcode.SUCC, pickup());
    counts(1);
  }

  @Test
  void prerequisiteRevocationRetiresTheEntityAndRejectsItsStaleId() {
    int entity = book().getEntityId();
    actor().getGameplay().getCancelledMissions().add(2000110);
    assertNotEquals(Retcode.SUCC, BookPickups.pickUp(actor(), 118191, entity).retcode());
    actor().scene.refreshMissionGroups();
    assertNull(book());
    assertNotEquals(Retcode.SUCC, BookPickups.pickUp(actor(), 118191, entity).retcode());
    counts(0);
  }
}
