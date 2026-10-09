package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.data.excel.MultiplePathAvatarExcel;
import emu.lunarcore.data.excel.PropExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class UniverseDownload46Test {
  static final int AVATAR = 1202, ENTITY = 199966, INSTANCE = 300004;
  UniverseEnhance46Test fixture = new UniverseEnhance46Test();
  Session s;
  PropExcel previous;
  GroupInfo group;
  final List<Runnable> restore = new ArrayList<>();

  static class DownloadExplorer extends UniverseEnhance46Test.Explorer {
    public GameAvatar getAvatarById(int id) {
      return getAvatars().getAvatarById(id);
    }
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final DownloadExplorer explorer = new DownloadExplorer();

    public Player getPlayer() {
      return explorer;
    }
  }

  @BeforeEach
  void setup() throws Exception {
    fixture.setup();
    s = new Session();
    GameplayDefinitions.rogueConstants.put("Rogue_Character_Initial_SP", "0.5");
    previous = GameData.getPropExcelMap().get(1001);
    var row =
        new Gson()
            .fromJson(
                """
                {"ID":1001,"PropType":"PROP_ROGUE_OBJECT",
                 "JsonPath":"Config/Props/Rogue/Prop_Rogue_DownloadConsole_01_Config.json",
                 "PropStateList":["Closed","Open","Locked"]}
                """,
                PropExcel.class);
    row.onLoad();
    GameData.getPropExcelMap().add(row);
    try (var reader =
        new java.io.InputStreamReader(
            getClass().getResourceAsStream("/fixtures/rogue-download-4.6.json"),
            java.nio.charset.StandardCharsets.UTF_8)) {
      for (var value : com.google.gson.JsonParser.parseReader(reader).getAsJsonArray()) {
        var record = value.getAsJsonObject();
        if (record.get("kind").getAsString().equals("avatar")) {
          var avatarRow = ResourceLoader.getGson().fromJson(record.get("row"), AvatarExcel.class);
          var old = GameData.getAvatarExcelMap().get(avatarRow.getId());
          restore.add(
              () -> {
                GameData.getAvatarExcelMap().keySet().remove(avatarRow.getId());
                if (old != null) GameData.getAvatarExcelMap().add(old);
              });
          GameData.getAvatarExcelMap().add(avatarRow);
        } else if (record.get("kind").getAsString().equals("path")) {
          var path =
              ResourceLoader.getGson().fromJson(record.get("row"), MultiplePathAvatarExcel.class);
          var old = GameData.getMultiplePathAvatarExcelMap().get(path.getId());
          restore.add(
              () -> {
                GameData.getMultiplePathAvatarExcelMap().keySet().remove(path.getId());
                if (old != null) GameData.getMultiplePathAvatarExcelMap().add(old);
              });
          GameData.getMultiplePathAvatarExcelMap().add(path);
        }
      }
    }
    ownedAvatar(s.explorer, AVATAR);
  }

  GameAvatar ownedAvatar(Player player, int id) {
    var avatar =
        new GameAvatar(GameData.getAvatarExcelMap().get(id)) {
          public void save() {}
        };
    avatar.setOwner(player);
    player.getAvatars().getAvatars().put(id, avatar);
    return avatar;
  }

  @AfterEach
  void cleanup() {
    for (int i = restore.size() - 1; i >= 0; i--) restore.get(i).run();
    restore.clear();
    fixture.cleanup();
    GameplayDefinitions.rogueConstants.remove("Rogue_Character_Initial_SP");
    if (previous == null) GameData.getPropExcelMap().keySet().remove(1001);
    else GameData.getPropExcelMap().add(previous);
  }

  EntityProp console() {
    group =
        new Gson().fromJson("{\"PropList\":[{\"ID\":300004,\"PropID\":1001}]}", GroupInfo.class);
    group.setId(8);
    s.explorer.floor.getGroups().put(8, group);
    var prop =
        new EntityProp(
            s.explorer.getScene(),
            GameData.getPropExcelMap().get(1001),
            group,
            group.getPropList().getFirst());
    prop.setEntityId(ENTITY);
    s.explorer.getScene().getEntities().put(ENTITY, prop);
    return prop;
  }

  void begin() {
    assertEquals(Retcode.SUCC, s.explorer.getUniverseManager().start(Universe46LifecycleTest.AREA));
    console();
  }

  DynamicMessage pick(List<Integer> ids, int entity, List<Integer> extraIds) throws Exception {
    var req = Gameplay46.builder(s, "PickRogueAvatarCsReq");
    ids.forEach(id -> Gameplay46.add(req, "NOEPBNMJFGL", id));
    extraIds.forEach(id -> Gameplay46.add(req, "DFPPCAJDBFK", id));
    Gameplay46.set(req, "prop_entity_id", entity);
    assertTrue(VersionedPacketHandler.handle(s, 1864, req.build().toByteArray()));
    assertEquals("PickRogueAvatarScRsp", s.responseName);
    return s.response;
  }

  int code(DynamicMessage response) {
    return Gameplay46.number(response, "retcode");
  }

  @Test
  void nativeDownloadAddsOwnedFormalAvatarWithIndependentVitalsAndPreservesDeployment()
      throws Exception {
    begin();
    var run = s.explorer.getGameplay().getRogue();
    int saves = s.explorer.saves;
    var response = pick(List.of(AVATAR), ENTITY, List.of());
    assertEquals(0, code(response));
    assertEquals(List.of(AVATAR), Gameplay46.value(response, "NOEPBNMJFGL"));
    assertEquals(List.of(), Gameplay46.value(response, "BFEDFBEKOBI"));
    assertEquals(10000, run.getVitals().get(AVATAR).getHp());
    assertEquals(5000, run.getVitals().get(AVATAR).getSp());
    assertEquals(List.of(199961), run.getRoster());
    assertEquals(300, run.getCoins());
    assertEquals(
        0,
        s.explorer
            .getAvatarById(AVATAR)
            .getCurrentSp(new emu.lunarcore.game.player.lineup.PlayerLineup(s.explorer, 0)));
    assertEquals(saves + 1, s.explorer.saves);
    assertEquals(
        PropState.Locked, ((EntityProp) s.explorer.getScene().getEntityById(ENTITY)).getState());
    assertNotEquals(0, code(pick(List.of(AVATAR), ENTITY, List.of())));
    assertEquals(5000, run.getVitals().get(AVATAR).getSp());
  }

  @Test
  void unknownAlreadyDownloadedAdditionalAndOversizedSelectionsAreRejectedBeforeAnyMutation()
      throws Exception {
    begin();
    var run = s.explorer.getGameplay().getRogue();
    for (var ids :
        List.of(
            List.<Integer>of(),
            List.of(AVATAR, AVATAR),
            List.of(AVATAR, 199961),
            List.of(AVATAR + 1),
            List.of(199961))) {
      assertNotEquals(0, code(pick(ids, ENTITY, List.of())));
    }
    assertNotEquals(0, code(pick(List.of(AVATAR), ENTITY, List.of(AVATAR))));
    assertEquals(Set.of(199961), run.getVitals().keySet());
    assertEquals(0, code(pick(List.of(AVATAR), ENTITY, List.of())));
  }

  @Test
  void onlyTheCurrentResourceDownloadConsoleAndAnUnblockedRunCanAcceptTheRequest()
      throws Exception {
    begin();
    var run = s.explorer.getGameplay().getRogue();
    assertNotEquals(0, code(pick(List.of(AVATAR), ENTITY + 1, List.of())));
    console().setState(PropState.Locked, false);
    assertNotEquals(0, code(pick(List.of(AVATAR), ENTITY, List.of())));
    console().setGroupId(9);
    assertNotEquals(0, code(pick(List.of(AVATAR), ENTITY, List.of())));
    console();
    run.getMiracleOffers().add(4);
    assertNotEquals(0, code(pick(List.of(AVATAR), ENTITY, List.of())));
    run.getMiracleOffers().clear();
    run.setInside(false);
    assertNotEquals(0, code(pick(List.of(AVATAR), ENTITY, List.of())));
    run.setInside(true);
    assertEquals(Set.of(199961), run.getVitals().keySet());
    assertEquals(0, code(pick(List.of(AVATAR), ENTITY, List.of())));
  }

  @Test
  void downloadUsesTheRunsInitialEnergyRatherThanHotReloadedResources() throws Exception {
    begin();
    GameplayDefinitions.rogueConstants.put("Rogue_Character_Initial_SP", "1");
    assertEquals(0, code(pick(List.of(AVATAR), ENTITY, List.of())));
    assertEquals(5000, s.explorer.getGameplay().getRogue().getVitals().get(AVATAR).getSp());
  }

  @Test
  void concurrentRequestsCannotDownloadTwiceOrConsumeAnotherConsole() throws Exception {
    begin();
    var gate = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<java.util.concurrent.Future<Retcode>>();
      for (int i = 0; i < 6; i++)
        tasks.add(
            executor.submit(
                () -> {
                  gate.await();
                  return RogueDownload.pick(s.explorer, List.of(AVATAR), ENTITY);
                }));
      gate.countDown();
      int success = 0;
      for (var task : tasks)
        if (task.get(10, java.util.concurrent.TimeUnit.SECONDS) == Retcode.SUCC) success++;
      assertEquals(1, success);
      var run = s.explorer.getGameplay().getRogue();
      assertEquals(1, run.getUsedDownloadConsoles().size());
      assertEquals(Set.of(199961, AVATAR), run.getVitals().keySet());
      assertEquals(List.of(199961), run.getRoster());
    }
  }

  @Test
  void downloadedAndConsumedStateSurvivesMorphiaAndOldRecordsCannotInferEnergy() throws Exception {
    begin();
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "rogue-download");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456784);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, s.explorer.getGameplay());
      store.save(document);
      gameplay.set(s.explorer, store.find(Player.class).first().getGameplay());
      GameplayDefinitions.rogueConstants.put("Rogue_Character_Initial_SP", "1");
      assertEquals(0, code(pick(List.of(AVATAR), ENTITY, List.of())));
      var vital = s.explorer.getGameplay().getRogue().getVitals().get(AVATAR);
      vital.setHp(2300);
      vital.setSp(900);
      gameplay.set(document, s.explorer.getGameplay());
      store.save(document);
      gameplay.set(s.explorer, store.find(Player.class).first().getGameplay());
      console();
      assertNotEquals(0, code(pick(List.of(AVATAR), ENTITY, List.of())));
      vital = s.explorer.getGameplay().getRogue().getVitals().get(AVATAR);
      assertEquals(2300, vital.getHp());
      assertEquals(900, vital.getSp());
      var loaded =
          new UniverseEntityLoader()
              .loadProp(s.explorer.getScene(), group, group.getPropList().getFirst());
      assertEquals(PropState.Locked, loaded.getState());
      var game = Universe46.snapshot(s, null);
      var info =
          (DynamicMessage)
              Gameplay46.value(
                  (DynamicMessage) Gameplay46.value(game, "CFDIKLBINHK"), "EIOHFJMFOKF");
      assertEquals(List.of(AVATAR, 199961), Gameplay46.value(info, "NOEPBNMJFGL"));
      store
          .getDatabase()
          .getCollection(store.getMapper().getEntityModel(Player.class).getCollectionName())
          .updateOne(
              new org.bson.Document("_id", 123456784),
              new org.bson.Document(
                  "$unset", new org.bson.Document("gameplay.rogue.initialAvatarSp", "")));
      gameplay.set(s.explorer, store.find(Player.class).first().getGameplay());
      assertEquals(-1, s.explorer.getGameplay().getRogue().getInitialAvatarSp());
      s.explorer.getGameplay().getRogue().getVitals().remove(AVATAR);
      assertNotEquals(0, code(pick(List.of(AVATAR), ENTITY, List.of())));
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void consumedConsoleRemainsUsedEvenIfItsEntityIdOrStateIsRecreated() throws Exception {
    begin();
    assertEquals(0, code(pick(List.of(AVATAR), ENTITY, List.of())));
    int other = 1102;
    ownedAvatar(s.explorer, other);
    var replaced = console();
    replaced.setEntityId(ENTITY + 1);
    s.explorer.getScene().getEntities().put(ENTITY + 1, replaced);
    assertNotEquals(0, code(pick(List.of(other), ENTITY + 1, List.of())));
    assertFalse(s.explorer.getGameplay().getRogue().getVitals().containsKey(other));
  }

  @Test
  void fullEnergyTalentIsFrozenAtEntryForLaterDownloadedAvatars() throws Exception {
    GameplayDefinitions.rogueTalents.put(
        32,
        new GameplayDefinitions.RogueTalent(
            32, List.of(), List.of(), List.of(), List.of(new GameplayDefinitions.EffectValue(1))));
    s.explorer.getGameplay().getRogueTalents().add(32);
    try {
      begin();
      s.explorer.getGameplay().getRogueTalents().remove(32);
      GameplayDefinitions.rogueTalents.remove(32);
      assertEquals(0, code(pick(List.of(AVATAR), ENTITY, List.of())));
      assertEquals(10000, s.explorer.getGameplay().getRogue().getVitals().get(AVATAR).getSp());
    } finally {
      GameplayDefinitions.rogueTalents.remove(32);
    }
  }

  @Test
  void unrecognizedEnergyAndUnknownWireFieldsNeverUnlockFreeDownloads() throws Exception {
    GameplayDefinitions.rogueConstants.put("Rogue_Character_Initial_SP", "NaN");
    begin();
    assertNotEquals(0, code(pick(List.of(AVATAR), ENTITY, List.of())));
    GameplayDefinitions.rogueConstants.put("Rogue_Character_Initial_SP", "0.5");
    s = new Session();
    ownedAvatar(s.explorer, AVATAR);
    begin();
    var req = Gameplay46.builder(s, "PickRogueAvatarCsReq");
    Gameplay46.add(req, "NOEPBNMJFGL", AVATAR);
    Gameplay46.set(req, "prop_entity_id", ENTITY);
    req.setUnknownFields(
        com.google.protobuf.UnknownFieldSet.newBuilder()
            .addField(
                199, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build());
    assertTrue(VersionedPacketHandler.handle(s, 1864, req.build().toByteArray()));
    assertNotEquals(0, code(s.response));
    assertEquals(Set.of(199961), s.explorer.getGameplay().getRogue().getVitals().keySet());
  }

  @Test
  void terminalOrNonRestRoomAndMissingResourceInstanceCannotAcceptDownload() throws Exception {
    begin();
    var run = s.explorer.getGameplay().getRogue();
    GameplayDefinitions.rooms.put(
        Universe46LifecycleTest.ROOM,
        new GameplayDefinitions.Room(Universe46LifecycleTest.ROOM, 1, 12, 8));
    assertNotEquals(0, code(pick(List.of(AVATAR), ENTITY, List.of())));
    GameplayDefinitions.rooms.put(
        Universe46LifecycleTest.ROOM,
        new GameplayDefinitions.Room(Universe46LifecycleTest.ROOM, 5, 12, 8));
    s.explorer.floor.getGroups().clear();
    assertNotEquals(0, code(pick(List.of(AVATAR), ENTITY, List.of())));
    console();
    run.finish(GameplayState.RogueRun.Phase.FAILED);
    assertNotEquals(0, code(pick(List.of(AVATAR), ENTITY, List.of())));
    assertEquals(Set.of(199961), run.getVitals().keySet());
  }
}
