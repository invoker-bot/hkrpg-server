package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.data.excel.EquipmentExcel;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.inventory.tabs.InventoryTabType;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** The native cabinet page must expose usable slots and keep only owned placements. */
class PlayerRoomDisplay46Test {
  @TempDir Path resources;
  final Gson gson = ResourceLoader.getGson();
  final Map<Integer, ItemExcel> previousItems = new HashMap<>();
  String previousRoot;

  static class Actor extends Player {
    int level = 21, saves;
    boolean failSave, failInventory;
    Scene scene;
    final LineupManager teams = new LineupManager(this);
    Actor() { teams.getCurrentLineup().getAvatars().add(0); }
    @Override public int getUid() { return 10001; }
    @Override public int getLevel() { return level; }
    @Override public Scene getScene() { return scene; }
    @Override public LineupManager getLineupManager() { return teams; }
    @Override public GameAvatar getAvatarById(int id) { return null; }
    @Override public emu.lunarcore.game.inventory.Inventory getInventory() {
      if (failInventory) throw new IllegalStateException("fixture failure");
      return super.getInventory();
    }
    @Override public void save() { saves++; if (failSave) throw new IllegalStateException(); }
  }

  static class Session extends PlayerReturnInitial46Test.Session {
    final Actor actor = new Actor();
    Session() { actor.setSession(this); }
    @Override public Player getPlayer() { return actor; }
  }

  @BeforeEach void setup() throws Exception {
    previousRoot = LunarCore.getConfig().resourceDir;
    JsonObject records;
    try (var in = getClass().getResourceAsStream("/fixtures/player-room-display-4.6.json")) {
      records = JsonParser.parseString(new String(Objects.requireNonNull(in).readAllBytes(),
          StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("records");
    }
    for (var entry : records.entrySet()) {
      var path = resources.resolve(entry.getKey());
      Files.createDirectories(path.getParent());
      Files.writeString(path, gson.toJson(entry.getValue()));
      if (entry.getKey().endsWith("ItemConfigPlayerRoomDynamic.json")
          || entry.getKey().endsWith("ItemConfigBadge.json")) {
        for (var raw : entry.getValue().getAsJsonArray()) {
          var row = gson.fromJson(raw, ItemExcel.class);
          previousItems.put(row.getId(), GameData.getItemExcelMap().get(row.getId()));
          GameData.getItemExcelMap().add(row);
        }
      }
    }
    LunarCore.getConfig().resourceDir = resources.toString();
  }

  @AfterEach void teardown() {
    LunarCore.getConfig().resourceDir = previousRoot;
    previousItems.forEach((id, value) -> {
      GameData.getItemExcelMap().keySet().remove((int) id);
      if (value != null) GameData.getItemExcelMap().add(value);
    });
  }

  Session session() {
    var s = new Session();
    var plane = gson.fromJson("{\"PlaneID\":10000,\"PlaneType\":\"Train\"}", MazePlaneExcel.class);
    s.actor.scene = new Scene(s.actor, plane, 10000003);
    return s;
  }

  static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }
  static DynamicMessage child(DynamicMessage m, String name) { return (DynamicMessage) field(m, name); }
  static List<?> list(DynamicMessage m, String name) { return (List<?>) field(m, name); }

  DynamicMessage profile(Session s) throws Exception {
    s.nativePackets.clear();
    assertTrue(VersionedPacketHandler.handle(s, 8034, new byte[0]));
    var packet = s.nativePackets.getLast();
    assertEquals(8025, packet.cmdId());
    return child(child(s.bridge.clientSchema().parse("TrainPartyGetDataScRsp", packet.body()),
        "train_party_data"), "train_party_info");
  }

  void own(Session s, int id, int count) {
    var item = new GameItem(GameData.getItemExcelMap().get(id), count);
    item.setOwner(s.actor);
    s.actor.getInventory().getItems().put(item.getInternalUid(), item);
    s.actor.getInventory().getTab(InventoryTabType.MATERIAL).onAddItem(item);
  }

  void ownBadge(Session s, int id, long expiry) {
    var item = new GameItem(GameData.getItemExcelMap().get(id), 1) { @Override public void save() {} };
    item.setBadgeExpireTime(expiry);
    item.setOwner(s.actor);
    s.actor.getInventory().getItems().put(item.getInternalUid(), item);
    s.actor.getInventory().getTab(InventoryTabType.MATERIAL).onAddItem(item);
  }

  DynamicMessage replace(Session s, int[][] items) throws Exception {
    return replace(s, items, new int[0][0]);
  }

  DynamicMessage replace(Session s, int[][] items, int[][] pictures) throws Exception {
    var display = Gameplay46.builder(s, "OIIPJFDNPAH");
    for (var item : items) {
      var row = Gameplay46.builder(s, "AreaDynamicInfo");
      Gameplay46.set(row, "dice_slot_id", item[0]);
      Gameplay46.set(row, "diy_dynamic_id", item[1]);
      Gameplay46.add(display, "GOEJIMNENDC", row.build());
    }
    for (var picture : pictures) {
      var row = Gameplay46.builder(s, "CLLBJDEJFNF");
      Gameplay46.set(row, "slot", picture[0]);
      Gameplay46.set(row, "id", picture[1]);
      Gameplay46.set(row, "type", picture[2]);
      Gameplay46.add(display, "OFOKOHGPEDO", row.build());
    }
    var request = Gameplay46.builder(s, "TrainPartyBuildSelfDisplayAreaDiyCsReq");
    Gameplay46.set(request, "ACLKMFCDMED", display.build());
    s.nativePackets.clear();
    assertTrue(VersionedPacketHandler.handle(s, 8096, request.build().toByteArray()));
    var packet = s.nativePackets.getLast();
    assertEquals(8047, packet.cmdId());
    return s.bridge.clientSchema().parse("TrainPartyBuildSelfDisplayAreaDiyScRsp", packet.body());
  }

  DynamicMessage badgeInfo(Session s) throws Exception {
    s.nativePackets.clear();
    assertTrue(VersionedPacketHandler.handle(s, 7172, new byte[0]));
    var packet = s.nativePackets.getLast();
    assertEquals(7173, packet.cmdId());
    return s.bridge.clientSchema().parse("GetBadgeInfoScRsp", packet.body());
  }

  DynamicMessage replaceBadges(Session s, long[][] badges) throws Exception {
    var display = Gameplay46.builder(s, "OIIPJFDNPAH");
    for (var badge : badges) {
      var row = Gameplay46.builder(s, "POFNNGOCEOK");
      Gameplay46.set(row, "dice_slot_id", (int) badge[0]);
      Gameplay46.set(row, "EIKLMFPMJJD", (int) badge[1]);
      Gameplay46.set(row, "expire_time", badge[2]);
      Gameplay46.add(display, "POLMJFIKNHN", row.build());
    }
    var req = Gameplay46.builder(s, "TrainPartyBuildSelfDisplayAreaDiyCsReq");
    Gameplay46.set(req, "ACLKMFCDMED", display.build());
    s.nativePackets.clear();
    assertTrue(VersionedPacketHandler.handle(s, 8096, req.build().toByteArray()));
    var rsp = s.nativePackets.getLast();
    assertEquals(8047, rsp.cmdId());
    return s.bridge.clientSchema().parse("TrainPartyBuildSelfDisplayAreaDiyScRsp", rsp.body());
  }

  DynamicMessage setBadgeAutoFillClosed(Session s, boolean closed) throws Exception {
    var req = Gameplay46.builder(s, "TrainPartySetBadgeAutoFillCsReq");
    Gameplay46.set(req, "PELJACANAFH", closed);
    s.nativePackets.clear();
    assertTrue(VersionedPacketHandler.handle(s, 8026, req.build().toByteArray()));
    var packet = s.nativePackets.stream().filter(p -> p.cmdId() == 8080).findFirst().orElseThrow();
    return s.bridge.clientSchema().parse("TrainPartySetBadgeAutoFillScRsp", packet.body());
  }

  @Test void displaySlotsAppearOnlyAfterTheOriginalLevelGateWithoutFinishingConstruction() throws Exception {
    var s = session();
    s.actor.level = 20;
    assertEquals(1, list(profile(s), "area_list").size());
    s.actor.level = 21;
    String before = gson.toJson(s.actor.getGameplay());
    var info = profile(s);
    var areas = list(info, "area_list");
    assertEquals(2, areas.size());
    var exhibit = (DynamicMessage) areas.get(1);
    assertEquals(16, field(exhibit, "area_id"));
    assertEquals(List.of(16001, 16002, 16003), list(exhibit, "static_prop_id_list"));
    assertEquals(0, field(exhibit, "progress"));
    assertTrue(list(exhibit, "step_id_list").isEmpty());
    assertTrue(info.hasField(info.getDescriptorForType().findFieldByName("CMCNNJKJNPK")));
    assertEquals(before, gson.toJson(s.actor.getGameplay()));
    assertEquals(0, s.actor.saves);
  }

  @Test void ownedFigurineCanBePlacedReplacedAndRemovedAcrossQueries() throws Exception {
    var s = session();
    own(s, 295603, 1);
    own(s, 295604, 1);
    var choices = list(profile(s), "MHABCMLHCGD");
    assertEquals(2, choices.size(), "The native selection cache needs the actual owned item identities");
    assertEquals(List.of(295603, 295604), choices.stream()
        .map(row -> field((DynamicMessage) row, "prop_id")).toList());
    assertTrue(choices.stream().allMatch(row -> (Long) field((DynamicMessage) row, "time") == 0L));
    assertEquals(0, field(replace(s, new int[][] {{1600101, 295603}}), "retcode"));
    var saved = list(child(profile(s), "CMCNNJKJNPK"), "GOEJIMNENDC");
    assertEquals(1, saved.size());
    assertEquals(295603, field((DynamicMessage) saved.getFirst(), "diy_dynamic_id"));
    assertEquals(0, field(replace(s, new int[][] {{1600102, 295604}}), "retcode"));
    saved = list(child(profile(s), "CMCNNJKJNPK"), "GOEJIMNENDC");
    assertEquals(1, saved.size());
    assertEquals(1600102, field((DynamicMessage) saved.getFirst(), "dice_slot_id"));
    assertEquals(0, field(replace(s, new int[0][0]), "retcode"));
    assertTrue(list(child(profile(s), "CMCNNJKJNPK"), "GOEJIMNENDC").isEmpty());
    assertEquals(3, s.actor.saves);
  }

  @Test void unownedWrongSlotAndDuplicatedPlacementCannotOverwriteSavedDisplay() throws Exception {
    var s = session(); own(s, 295603, 1);
    assertEquals(0, field(replace(s, new int[][] {{1600101, 295603}}), "retcode"));
    String saved = gson.toJson(s.actor.getGameplay());
    for (int[][] invalid : List.of(new int[][] {{1600102, 295604}},
        new int[][] {{1600201, 295603}},
        new int[][] {{1600101, 295603}, {1600102, 295603}},
        new int[][] {{1600101, 295603}, {1600101, 295603}})) {
      assertNotEquals(0, field(replace(s, invalid), "retcode"));
      assertEquals(saved, gson.toJson(s.actor.getGameplay()));
    }
    assertEquals(1, s.actor.saves);
  }

  @Test void levelSceneAndSaveFailureCannotChangeThePreviousArrangement() throws Exception {
    var s = session(); own(s, 295603, 1);
    assertEquals(0, field(replace(s, new int[][] {{1600101, 295603}}), "retcode"));
    String saved = gson.toJson(s.actor.getGameplay());
    s.actor.level = 20;
    assertEquals(Retcode.PLAYER_LEVEL_NOT_MEET.getVal(), field(replace(s, new int[0][0]), "retcode"));
    s.actor.level = 21;
    s.actor.scene = null;
    assertEquals(Retcode.NOT_IN_SCENE.getVal(), field(replace(s, new int[0][0]), "retcode"));
    s.actor.scene = new Scene(s.actor, gson.fromJson("{\"PlaneID\":10000}", MazePlaneExcel.class), 10000003);
    s.actor.failSave = true;
    assertEquals(Retcode.SERVER_INTERNAL_ERROR.getVal(), field(replace(s, new int[0][0]), "retcode"));
    assertEquals(saved, gson.toJson(s.actor.getGameplay()));
  }

  @Test void malformedSnapshotAndBadgeRequestsDoNotEraseAnExistingDisplay() throws Exception {
    var s = session(); own(s, 295603, 1);
    assertEquals(0, field(replace(s, new int[][] {{1600101, 295603}}), "retcode"));
    String saved = gson.toJson(s.actor.getGameplay());
    // Missing snapshot, truncated nested message, duplicate outer field and unknown field.
    for (byte[] body : List.of(new byte[0], new byte[] {42, 2, 8},
        new byte[] {42, 0, 42, 0}, new byte[] {42, 0, 8, 1})) {
      s.nativePackets.clear();
      assertTrue(VersionedPacketHandler.handle(s, 8096, body));
      var packet = s.nativePackets.getLast();
      assertEquals(8047, packet.cmdId());
      var response = s.bridge.clientSchema().parse("TrainPartyBuildSelfDisplayAreaDiyScRsp", packet.body());
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), field(response, "retcode"));
      assertEquals(saved, gson.toJson(s.actor.getGameplay()));
    }
    var badge = Gameplay46.builder(s, "POFNNGOCEOK");
    Gameplay46.set(badge, "dice_slot_id", 1600106);
    Gameplay46.set(badge, "EIKLMFPMJJD", 295532);
    var display = Gameplay46.builder(s, "OIIPJFDNPAH");
    Gameplay46.add(display, "POLMJFIKNHN", badge.build());
    var request = Gameplay46.builder(s, "TrainPartyBuildSelfDisplayAreaDiyCsReq");
    Gameplay46.set(request, "ACLKMFCDMED", display.build());
    s.nativePackets.clear();
    assertTrue(VersionedPacketHandler.handle(s, 8096, request.build().toByteArray()));
    var badgeResponse = s.bridge.clientSchema().parse("TrainPartyBuildSelfDisplayAreaDiyScRsp",
        s.nativePackets.getLast().body());
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), field(badgeResponse, "retcode"));
    assertEquals(saved, gson.toJson(s.actor.getGameplay()));
    var autofill = Gameplay46.builder(s, "TrainPartySetBadgeAutoFillCsReq");
    Gameplay46.set(autofill, "PELJACANAFH", true);
    s.nativePackets.clear();
    assertTrue(VersionedPacketHandler.handle(s, 8026, autofill.build().toByteArray()));
    var packet = s.nativePackets.getLast();
    assertEquals(8080, packet.cmdId());
    var response = s.bridge.clientSchema().parse("TrainPartySetBadgeAutoFillScRsp", packet.body());
    assertEquals(0, field(response, "retcode"));
    assertEquals(saved, gson.toJson(s.actor.getGameplay()));
  }

  @Test void badgeQueryUsesActualOwnedItemsAndTheNativePermanentExpiry() throws Exception {
    var s = session();
    var empty = badgeInfo(s);
    assertTrue(empty.hasField(empty.getDescriptorForType().findFieldByName("AIANIJCIBGE")));
    assertTrue(list(child(empty, "AIANIJCIBGE"), "CPMCCCODGAB").isEmpty());
    ownBadge(s, 230532, 0xffffffffL); ownBadge(s, 230533, 0xffffffffL);
    var rows = list(child(badgeInfo(s), "AIANIJCIBGE"), "CPMCCCODGAB");
    assertEquals(List.of(295532, 295533), rows.stream()
        .map(row -> field((DynamicMessage) row, "EIKLMFPMJJD")).toList());
    assertTrue(rows.stream().allMatch(row -> (Long)field((DynamicMessage)row, "expire_time") == 0xffffffffL));
  }

  @Test void ownedBadgesCanBePlacedButForgedSlotsIdsAndExpiryCannotReplaceThem() throws Exception {
    var s = session(); ownBadge(s, 230532, 0xffffffffL); ownBadge(s, 230533, 0xffffffffL);
    long permanent = 0xffffffffL;
    assertEquals(0, field(replaceBadges(s, new long[][] {{1600106, 295532, permanent}}), "retcode"));
    var rows = list(child(profile(s), "CMCNNJKJNPK"), "POLMJFIKNHN");
    assertEquals(1, rows.size());
    assertEquals(295532, field((DynamicMessage) rows.getFirst(), "EIKLMFPMJJD"));
    String saved = gson.toJson(s.actor.getGameplay());
    for (long[][] invalid : List.of(new long[][] {{1600106, 230900, permanent}},
        new long[][] {{1600101, 295532, permanent}}, new long[][] {{1600106, 295532, 0}},
        new long[][] {{1600106, 295532, permanent}, {1600107, 295532, permanent}},
        new long[][] {{1600106, 295532, permanent}, {1600106, 295533, permanent}})) {
      assertNotEquals(0, field(replaceBadges(s, invalid), "retcode"));
      assertEquals(saved, gson.toJson(s.actor.getGameplay()));
    }
    assertEquals(0, field(replaceBadges(s, new long[0][0]), "retcode"));
    assertTrue(list(child(profile(s), "CMCNNJKJNPK"), "POLMJFIKNHN").isEmpty());
  }

  @Test void enablingAutoFillUsesOnlyOwnedBadgesAndPreservesExistingPositions() throws Exception {
    var s = session(); ownBadge(s, 230532, 0xffffffffL); ownBadge(s, 230533, 0xffffffffL);
    long permanent = 0xffffffffL;
    assertEquals(0, field(replaceBadges(s, new long[][] {{1600110, 295532, permanent}}), "retcode"));
    var enabled = setBadgeAutoFillClosed(s, false);
    assertEquals(0, field(enabled, "retcode"));
    assertEquals(false, field(enabled, "PELJACANAFH"));
    var rows = list(child(profile(s), "CMCNNJKJNPK"), "POLMJFIKNHN");
    var slots = new TreeMap<Integer,Integer>();
    for (var raw : rows) {
      var row=(DynamicMessage)raw;
      slots.put((Integer)field(row,"dice_slot_id"), (Integer)field(row,"EIKLMFPMJJD"));
    }
    assertEquals(Map.of(1600106,295533,1600110,295532), slots);
    // A deliberate removal stays removed while the same owned badge is already known.
    assertEquals(0, field(replaceBadges(s, new long[][] {{1600110,295532,permanent}}), "retcode"));
    assertEquals(1, list(child(profile(s), "CMCNNJKJNPK"), "POLMJFIKNHN").size());
    assertEquals(0, field(setBadgeAutoFillClosed(s, true), "retcode"));
    assertEquals(1, list(child(profile(s), "CMCNNJKJNPK"), "POLMJFIKNHN").size());
    assertEquals(0, field(setBadgeAutoFillClosed(s, false), "retcode"));
    assertEquals(slots.size(), list(child(profile(s), "CMCNNJKJNPK"), "POLMJFIKNHN").size());
  }

  @Test void failedAutoFillSaveDoesNotAlterTheStoredArrangementOrPreference() throws Exception {
    var s = session(); ownBadge(s, 230532, 0xffffffffL);
    String before = gson.toJson(s.actor.getGameplay());
    s.actor.failSave = true;
    assertEquals(Retcode.SERVER_INTERNAL_ERROR.getVal(), field(setBadgeAutoFillClosed(s, false), "retcode"));
    assertEquals(before, gson.toJson(s.actor.getGameplay()));
  }

  @Test void actuallyAwardedBadgeIsStoredAndAutoFilledAfterSuccessfulDelivery() throws Exception {
    var s = session();
    var definition = GameData.getItemExcelMap().get(230900);
    assertEquals("Badge", String.valueOf(definition.getItemSubType()), "Original badge subtype must load");
    assertEquals(0, field(setBadgeAutoFillClosed(s, false), "retcode"));
    var item = new GameItem(definition, 1) { @Override public void save() {} };
    s.nativePackets.clear();
    assertTrue(s.actor.getInventory().addItem(item));
    var notification = s.nativePackets.stream().filter(packet -> packet.cmdId() == 7180).findFirst().orElseThrow();
    var update = s.bridge.clientSchema().parse("BadgeInfoScNotify", notification.body());
    assertEquals(1, list(child(update, "AIANIJCIBGE"), "CPMCCCODGAB").size());
    var badgeRows = list(child(badgeInfo(s), "AIANIJCIBGE"), "CPMCCCODGAB");
    assertEquals(1, badgeRows.size());
    assertEquals(230900, field((DynamicMessage) badgeRows.getFirst(), "EIKLMFPMJJD"));
    var placed = list(child(profile(s), "CMCNNJKJNPK"), "POLMJFIKNHN");
    assertEquals(1, placed.size());
    assertEquals(230900, field((DynamicMessage) placed.getFirst(), "EIKLMFPMJJD"));
  }

  @Test void originalBadgeRewardRowsTravelThroughTheRealInventoryAndPersistOnce() throws Exception {
    var server = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    var info = new emu.lunarcore.Config.DatabaseInfo();
    info.uri = "mongodb://" + address.getHostString() + ":" + address.getPort();
    info.collection = "player_room_badge_reward";
    info.useInternal = false;
    var oldDatabase = LunarCore.getGameDatabase();
    emu.lunarcore.database.DatabaseManager database = null;
    var databaseField = LunarCore.class.getDeclaredField("gameDatabase");
    databaseField.setAccessible(true);
    try {
      database = new emu.lunarcore.database.DatabaseManager(info, LunarCore.ServerType.GAME);
      databaseField.set(null, database);
      var s = session();
      assertEquals(0, field(setBadgeAutoFillClosed(s, false), "retcode"));
      var rows = JsonParser.parseString(Files.readString(resources.resolve("ExcelOutput/RewardData.json")))
          .getAsJsonArray();
      assertEquals(2, rows.size());
      for (var raw : rows) {
        var reward = gson.fromJson(raw, emu.lunarcore.data.excel.RewardExcel.class);
        reward.onLoad();
        var grant = s.actor.getInventory().grantItems(reward.getRewards(), false);
        assertTrue(grant.complete());
        assertEquals(1, grant.items().size());
        int itemId = grant.items().getFirst().getItemId();
        var persisted = database.getDatastore().find(GameItem.class)
            .filter(dev.morphia.query.filters.Filters.eq("itemId", itemId)).first();
        assertNotNull(persisted);
        assertEquals(10001, persisted.getOwnerUid());
        assertEquals(1, persisted.getCount());
        assertEquals(0xffffffffL, persisted.getBadgeExpireTime());
        assertFalse(s.actor.getInventory().grantItems(reward.getRewards(), false).complete());
        assertEquals(1, s.actor.getInventory().getMaterialByItemId(itemId).getCount());
      }
      assertEquals(2, list(child(badgeInfo(s), "AIANIJCIBGE"), "CPMCCCODGAB").size());
      assertEquals(2, list(child(profile(s), "CMCNNJKJNPK"), "POLMJFIKNHN").size());
    } finally {
      databaseField.set(null, oldDatabase);
      if (database != null) database.close();
      server.shutdownNow();
    }
  }

  @Test void aLegacyBadgeItemWithNoExpirationEvidenceIsNotMadePermanent() throws Exception {
    var s = session();
    own(s, 230532, 1);
    assertTrue(list(child(badgeInfo(s), "AIANIJCIBGE"), "CPMCCCODGAB").isEmpty(),
        "An item with unknown expiry must not become a permanent badge");
    var unknown = new GameItem(GameData.getItemExcelMap().get(230533), 1) {
      @Override public void save() { fail("Unknown expiration must not be stored"); }
    };
    assertFalse(s.actor.getInventory().addItem(unknown));
  }

  @Test void actualExpiryIsRespectedAndRenewalCanAutoFillWithoutInventingTime() throws Exception {
    var s = session();
    long now = System.currentTimeMillis() / 1000;
    ownBadge(s, 230532, now - 1);
    ownBadge(s, 230533, now + 3600);
    var rows = list(child(badgeInfo(s), "AIANIJCIBGE"), "CPMCCCODGAB");
    assertEquals(1, rows.size());
    assertEquals(295533, field((DynamicMessage)rows.getFirst(), "EIKLMFPMJJD"));
    assertEquals(now + 3600, field((DynamicMessage)rows.getFirst(), "expire_time"));
    assertNotEquals(0, field(replaceBadges(s, new long[][] {{1600106,295532,0xffffffffL}}), "retcode"));
    assertEquals(0, field(setBadgeAutoFillClosed(s, false), "retcode"));
    var renewed = new GameItem(GameData.getItemExcelMap().get(230532), 1) {
      @Override public void save() {}
    };
    renewed.setBadgeExpireTime(now + 7200);
    assertTrue(s.actor.getInventory().addItem(renewed));
    var placed = list(child(profile(s), "CMCNNJKJNPK"), "POLMJFIKNHN");
    assertEquals(2, placed.size());
    assertTrue(placed.stream().map(DynamicMessage.class::cast).anyMatch(row ->
        field(row, "EIKLMFPMJJD").equals(295532) && field(row, "expire_time").equals(now + 7200)));
  }

  @Test void missingOriginalRewardSourceCannotTurnAnActivityBadgeIntoAPermanentGrant() throws Exception {
    var s = session();
    Files.writeString(resources.resolve("ExcelOutput/RewardData.json"), "[]");
    var candidate = new GameItem(GameData.getItemExcelMap().get(230900), 1) {
      @Override public void save() { fail("An unverified reward cannot be stored"); }
    };
    assertFalse(s.actor.getInventory().addItem(candidate));
    assertTrue(list(child(badgeInfo(s), "AIANIJCIBGE"), "CPMCCCODGAB").isEmpty());
  }

  @Test void failedAutoPlacementNeverRevokesTheAwardAndRetriesWhenTheProfileLoads() throws Exception {
    var s = session();
    assertEquals(0, field(setBadgeAutoFillClosed(s, false), "retcode"));
    s.actor.failSave = true;
    var candidate = new GameItem(GameData.getItemExcelMap().get(230900), 1) {
      @Override public void save() {}
    };
    assertTrue(s.actor.getInventory().addItem(candidate));
    assertEquals(1, list(child(badgeInfo(s), "AIANIJCIBGE"), "CPMCCCODGAB").size());
    assertTrue(list(child(profile(s), "CMCNNJKJNPK"), "POLMJFIKNHN").isEmpty());
    s.actor.failSave = false;
    assertEquals(1, list(child(profile(s), "CMCNNJKJNPK"), "POLMJFIKNHN").size());
  }

  @Test void failedBadgeItemSaveCannotCreateOwnershipOrAutomaticPlacement() throws Exception {
    var s = session();
    assertEquals(0, field(setBadgeAutoFillClosed(s, false), "retcode"));
    String before = gson.toJson(s.actor.getGameplay());
    var candidate = new GameItem(GameData.getItemExcelMap().get(230900), 1) {
      @Override public void save() { throw new IllegalStateException("synthetic item save failure"); }
    };
    assertThrows(IllegalStateException.class, () -> s.actor.getInventory().addItem(candidate));
    assertEquals(before, gson.toJson(s.actor.getGameplay()));
    assertTrue(list(child(badgeInfo(s), "AIANIJCIBGE"), "CPMCCCODGAB").isEmpty());
    assertTrue(list(child(profile(s), "CMCNNJKJNPK"), "POLMJFIKNHN").isEmpty());
  }

  @Test void malformedBadgeRequestsCannotChangeTheSavedPreference() throws Exception {
    var s = session();
    String before = gson.toJson(s.actor.getGameplay());
    for (var body : List.of(new byte[] {8, 2}, new byte[] {8, 1, 8, 0}, new byte[] {(byte)0x80})) {
      s.nativePackets.clear();
      assertTrue(VersionedPacketHandler.handle(s, 8026, body));
      var response = s.bridge.clientSchema().parse("TrainPartySetBadgeAutoFillScRsp", s.nativePackets.getFirst().body());
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), field(response, "retcode"));
      assertEquals(before, gson.toJson(s.actor.getGameplay()));
    }
  }

  @Test void unexpectedServerFailureIsNotReportedAsAMalformedClientRequest() throws Exception {
    var s = session(); own(s, 295603, 1);
    assertEquals(0, field(replace(s, new int[][] {{1600101, 295603}}), "retcode"));
    String saved = gson.toJson(s.actor.getGameplay());
    s.actor.failInventory = true;
    assertEquals(Retcode.SERVER_INTERNAL_ERROR.getVal(),
        field(replace(s, new int[][] {{1600101, 295603}}), "retcode"));
    assertEquals(saved, gson.toJson(s.actor.getGameplay()));
  }

  @Test void photosRequireTheActualCharacterLightConeOrFinishedOriginalStory() throws Exception {
    var s = session();
    var avatar = new GameAvatar();
    UpdatedArchive46Test.set(avatar, GameAvatar.class, "avatarId", 1202);
    avatar.setOwner(s.actor);
    s.actor.getAvatars().getAvatars().put(1202, avatar);
    var weapon = gson.fromJson("{\"ID\":20000,\"ItemMainType\":\"Equipment\"}", ItemExcel.class);
    weapon.setEquipmentExcel(gson.fromJson("{\"EquipmentID\":20000}", EquipmentExcel.class));
    previousItems.put(20000, GameData.getItemExcelMap().get(20000));
    GameData.getItemExcelMap().add(weapon);
    var actual = new GameItem(weapon);
    actual.setOwner(s.actor);
    s.actor.getInventory().getItems().put(actual.getInternalUid(), actual);
    int[][] photos = {{1600301, 1202, 1}, {1600303, 1005, 4}, {1600305, 20000, 3}};
    assertNotEquals(0, field(replace(s, new int[0][0], photos), "retcode"));
    var mission = new QuestState(1000401);
    mission.advance(1, 1, 100);
    s.actor.getGameplay().getMainMissions().put(1000401, mission);
    assertEquals(0, field(replace(s, new int[0][0], photos), "retcode"));
    var rows = list(child(profile(s), "CMCNNJKJNPK"), "OFOKOHGPEDO");
    assertEquals(List.of(1202, 1005, 20000), rows.stream()
        .map(row -> field((DynamicMessage) row, "id")).toList());
    String saved = gson.toJson(s.actor.getGameplay());
    assertNotEquals(0, field(replace(s, new int[0][0],
        new int[][] {{1600301, 20000, 3}}), "retcode"));
    assertEquals(saved, gson.toJson(s.actor.getGameplay()));
  }

  @Test void savedDisplaySurvivesRealPlayerDocumentReload() throws Exception {
    var s = session(); own(s, 295603, 1);
    var avatar = new GameAvatar();
    UpdatedArchive46Test.set(avatar, GameAvatar.class, "avatarId", 1202);
    avatar.setOwner(s.actor);
    s.actor.getAvatars().getAvatars().put(1202, avatar);
    assertEquals(0, field(replace(s, new int[][] {{1600101, 295603}},
        new int[][] {{1600301, 1202, 1}}), "retcode"));
    ownBadge(s, 230532, 0xffffffffL);
    assertEquals(0, field(setBadgeAutoFillClosed(s, false), "retcode"));
    var server = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client = com.mongodb.client.MongoClients.create(
        "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "player_room_display");
      store.getMapper().map(Player.class, QuestState.class, GameItem.class);
      var document = new Player();
      UpdatedArchive46Test.set(document, Player.class, "uid", 10001);
      UpdatedArchive46Test.set(document, Player.class, "name", "Room fixture");
      UpdatedArchive46Test.set(document, Player.class, "signature", "");
      UpdatedArchive46Test.set(document, Player.class, "gameplay", s.actor.getGameplay());
      store.save(document);
      var badgeItem = new GameItem(GameData.getItemExcelMap().get(230532), 1);
      badgeItem.setOwner(document);
      badgeItem.setBadgeExpireTime(0xffffffffL);
      store.save(badgeItem);
      for (int i = 0; i < 2; i++) {
        var restored = store.find(Player.class)
            .filter(dev.morphia.query.filters.Filters.eq("_id", 10001)).first();
        assertNotNull(restored);
        assertEquals(Map.of(1600101, 295603), restored.getGameplay().getPlayerRoomDisplay().getItems());
        var photo = restored.getGameplay().getPlayerRoomDisplay().getPictures().get(1600301);
        assertNotNull(photo);
        assertEquals(1202, photo.getId());
        assertEquals(1, photo.getType());
        assertFalse(restored.getGameplay().getPlayerRoomDisplay().isBadgeAutoFillClosed());
        assertEquals(295532, restored.getGameplay().getPlayerRoomDisplay().getBadges().get(1600106).getId());
        assertEquals(0xffffffffL, restored.getGameplay().getPlayerRoomDisplay().getAutoSeenBadges().get(295532));
        var restoredItem = store.find(GameItem.class)
            .filter(dev.morphia.query.filters.Filters.eq("ownerUid", 10001)).first();
        assertNotNull(restoredItem);
        assertEquals(0xffffffffL, restoredItem.getBadgeExpireTime());
      }
    } finally { server.shutdownNow(); }
  }
}
