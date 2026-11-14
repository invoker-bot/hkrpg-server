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
      if (entry.getKey().endsWith("ItemConfigPlayerRoomDynamic.json")) {
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
    assertEquals(Retcode.FEATURE_SWITCH_CLOSED.getVal(), field(response, "retcode"));
    assertEquals(saved, gson.toJson(s.actor.getGameplay()));
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
    var server = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client = com.mongodb.client.MongoClients.create(
        "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "player_room_display");
      store.getMapper().map(Player.class, QuestState.class);
      var document = new Player();
      UpdatedArchive46Test.set(document, Player.class, "uid", 10001);
      UpdatedArchive46Test.set(document, Player.class, "name", "Room fixture");
      UpdatedArchive46Test.set(document, Player.class, "signature", "");
      UpdatedArchive46Test.set(document, Player.class, "gameplay", s.actor.getGameplay());
      store.save(document);
      for (int i = 0; i < 2; i++) {
        var restored = store.find(Player.class)
            .filter(dev.morphia.query.filters.Filters.eq("_id", 10001)).first();
        assertNotNull(restored);
        assertEquals(Map.of(1600101, 295603), restored.getGameplay().getPlayerRoomDisplay().getItems());
        var photo = restored.getGameplay().getPlayerRoomDisplay().getPictures().get(1600301);
        assertNotNull(photo);
        assertEquals(1202, photo.getId());
        assertEquals(1, photo.getType());
      }
    } finally { server.shutdownNow(); }
  }
}
