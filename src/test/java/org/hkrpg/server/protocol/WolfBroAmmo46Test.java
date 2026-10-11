package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.*;
import emu.lunarcore.server.packet.*;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual level 11 placements. Initial fill and drops are explicit server policy, not official proof. */
class WolfBroAmmo46Test {
  final Gson gson = ResourceLoader.getGson();
  @TempDir Path root;
  JsonObject proof;
  Session session;
  String previousRoot;
  FloorInfo previousFloor;
  final List<MazePlaneExcel> oldPlanes = new ArrayList<>();
  final List<PropExcel> oldProps = new ArrayList<>();
  final List<NpcMonsterExcel> oldMonsters = new ArrayList<>();
  final Map<Integer, WolfBroCatalog.Level> oldLevels = new HashMap<>();

  static final class Actor extends Player {
    @dev.morphia.annotations.Transient Scene current;
    @dev.morphia.annotations.Transient final PlayerLineup team = new PlayerLineup(this, 0);
    @dev.morphia.annotations.Transient int saves;
    @dev.morphia.annotations.Transient boolean failSave;
    @dev.morphia.annotations.Transient String saved;
    @dev.morphia.annotations.Transient int owner = 987;
    Actor() { resetPosition(); }
    @Override public int getUid() { return owner; }
    @Override public Scene getScene() { return current; }
    @Override public PlayerLineup getCurrentLineup() { return team; }
    @Override public void save() {
      if (failSave) throw new IllegalStateException("isolated player save rejected");
      saved = ResourceLoader.getGson().toJson(getGameplay());
      saves++;
    }
    @Override public void sendPacket(BasePacket packet) {}
  }

  static final class Session extends Gameplay46HandlerTest.Session {
    final Actor actor = new Actor();
    final List<String> emitted = new ArrayList<>();
    @Override public Player getPlayer() { return actor; }
    @Override public void sendClientPacket(String name, byte[] bytes) {
      super.sendClientPacket(name, bytes);
      emitted.add(name);
    }
  }

  @BeforeEach void setup() throws Exception {
    proof = JsonParser.parseString(Files.readString(Path.of("src/test/resources/fixtures/wolfbro-ammo-4.6.json"))).getAsJsonObject();
    previousRoot = LunarCore.getConfig().resourceDir;
    oldPlanes.addAll(GameData.getMazePlaneExcelMap().values());
    oldProps.addAll(GameData.getPropExcelMap().values());
    oldMonsters.addAll(GameData.getNpcMonsterExcelMap().values());
    oldLevels.putAll(WolfBroCatalog.levels);
    for (var element : proof.getAsJsonArray("sourceFiles")) {
      var file = element.getAsJsonObject();
      var path = root.resolve(file.get("path").getAsString());
      Files.createDirectories(path.getParent());
      Files.writeString(path, file.get("data").toString());
    }
    var tables = new LinkedHashMap<String, JsonArray>();
    for (var element : proof.getAsJsonArray("sourceRows")) {
      var record = element.getAsJsonObject();
      String name = record.get("table").getAsString();
      var value = record.getAsJsonObject("data").deepCopy();
      // Loaded scalar adapters may change their private copy; the source table stays whole.
      tables.computeIfAbsent(name, ignored -> new JsonArray()).add(value.deepCopy());
      switch (name) {
        case "MazePlane" -> {
          value.addProperty("PlaneName", value.getAsJsonObject("PlaneName").get("Hash").getAsLong());
          GameData.getMazePlaneExcelMap().add(gson.fromJson(value, MazePlaneExcel.class));
        }
        case "MazeProp" -> {
          var prop = gson.fromJson(value, PropExcel.class);
          prop.onLoad();
          GameData.getPropExcelMap().add(prop);
        }
        case "NPCMonsterData" -> GameData.getNpcMonsterExcelMap().add(gson.fromJson(value, NpcMonsterExcel.class));
      }
    }
    for (var entry : tables.entrySet()) {
      Path path = root.resolve("ExcelOutput/" + entry.getKey() + ".json");
      Files.createDirectories(path.getParent());
      Files.writeString(path, entry.getValue().toString());
    }
    LunarCore.getConfig().resourceDir = root.toString();
    WolfBroCatalog.load(root);
    var floor = gson.fromJson(file("Config/LevelOutput/RuntimeFloor/P20321_F20321001.json"), FloorInfo.class);
    for (int id : List.of(20, 21, 22, 42, 44)) {
      var group = gson.fromJson(file(groupPath(id)), GroupInfo.class);
      group.setId(id);
      floor.getGroups().put(id, group);
    }
    floor.onLoad();
    previousFloor = GameData.getFloorInfos().put("P20321_F20321001", floor);
    session = new Session();
    session.actor.current = new Scene(session.actor, GameData.getMazePlaneExcelMap().get(20321), 20321001);
    session.actor.current.setEntryId(2032101);
    for (var group : floor.getGroups().values()) session.actor.current.loadGroup(group);
    assertNotNull(gun());
    assertTrue(session.actor.current.hasStoryGroup(21));
  }

  @AfterEach void cleanup() {
    LunarCore.getConfig().resourceDir = previousRoot;
    GameData.getMazePlaneExcelMap().keySet().clear(); oldPlanes.forEach(GameData.getMazePlaneExcelMap()::add);
    GameData.getPropExcelMap().keySet().clear(); oldProps.forEach(GameData.getPropExcelMap()::add);
    GameData.getNpcMonsterExcelMap().keySet().clear(); oldMonsters.forEach(GameData.getNpcMonsterExcelMap()::add);
    WolfBroCatalog.levels.clear(); WolfBroCatalog.levels.putAll(oldLevels);
    if (previousFloor == null) GameData.getFloorInfos().remove("P20321_F20321001");
    else GameData.getFloorInfos().put("P20321_F20321001", previousFloor);
  }

  static String groupPath(int id) {
    return "Config/LevelOutput/SharedRuntimeGroup/Groups_P20321_F20321001/LevelGroup_P20321_F20321001_G" + id + ".json";
  }
  JsonObject file(String name) {
    return proof.getAsJsonArray("sourceFiles").asList().stream().map(JsonElement::getAsJsonObject)
        .filter(row -> row.get("path").getAsString().equals(name)).findFirst().orElseThrow().getAsJsonObject("data");
  }
  EntityProp gun() {
    return session.actor.current.getEntities().values().stream().filter(EntityProp.class::isInstance)
        .map(EntityProp.class::cast).filter(p -> p.getGroupId() == 20 && p.getInstId() == 300009).findFirst().orElse(null);
  }

  @Test void completeFixedG21NumberTokensAreAcceptedWithoutReexportingTheSourceThroughFloatingPoint() throws Exception {
    // Whole blob from TurnBasedGameData 724b139d8c9c32d12552eb95745a4fee72bfe48b.
    // The runtime source identity must match the original number tokens, including long decimals.
    var original = Files.readString(Path.of("src/test/resources/fixtures/wolfbro-g21-original-4.6.json"));
    Files.writeString(root.resolve(groupPath(21)), original);
    start();
    assertEquals(0, code(activate(20, 300009)), "A complete fixed original must admit its actual source gun");
  }
  EntityMonster monster() {
    return session.actor.current.getEntities().values().stream().filter(EntityMonster.class::isInstance)
        .map(EntityMonster.class::cast).filter(p -> p.getGroupId() == 22 && p.getInstId() == 200001).findFirst().orElseThrow();
  }
  DynamicMessage call(int command, String request, String response, Map<String, Object> fields) throws Exception {
    var builder = Gameplay46.builder(session, request);
    for (var entry : fields.entrySet()) Gameplay46.set(builder, entry.getKey(), entry.getValue());
    session.response = null;
    assertTrue(VersionedPacketHandler.handle(session, command, builder.build().toByteArray()), "actual native request " + command);
    assertEquals(response, session.responseName);
    assertNotNull(session.response);
    return session.response;
  }
  DynamicMessage named(String request, Map<String, Object> fields) throws Exception {
    return call(session.getProtocol().clientCommand(request), request, request.replace("CsReq", "ScRsp"), fields);
  }
  static int code(DynamicMessage value) { return Gameplay46.number(value, "retcode"); }
  DynamicMessage query() throws Exception {
    var reply = named("GetWolfBroGameDataCsReq", Map.of("id", 11));
    assertEquals(0, code(reply));
    return (DynamicMessage) Gameplay46.value(reply, "game_data");
  }
  static int ammo(DynamicMessage data) { return Gameplay46.number(data, "CHCOPKAKBNO"); }
  DynamicMessage vector(int x, int y, int z) {
    var v = Gameplay46.builder(session, "Vector");
    Gameplay46.set(v, "x", x); Gameplay46.set(v, "y", y); Gameplay46.set(v, "z", z);
    return v.build();
  }
  DynamicMessage motion() {
    var m = Gameplay46.builder(session, "MotionInfo");
    Gameplay46.set(m, "pos", vector(-20016, 53840, -146616));
    Gameplay46.set(m, "rot", vector(0, 0, 0));
    return m.build();
  }
  void start() throws Exception {
    var g = Gameplay46.builder(session, "GroupStateInfo");
    Gameplay46.set(g, "group_id", 21); Gameplay46.set(g, "group_state", 1);
    assertEquals(0, code(named("StartWolfBroGameCsReq", Map.of("id", 11, "motion", motion(), "IDANCEPOOEI", g.build()))));
    Gameplay46.set(g, "group_state", 2);
    assertEquals(0, code(named("GroupStateChangeCsReq", Map.of("group_state_info", g.build()))));
    session.actor.saves = 0; session.emitted.clear();
  }
  DynamicMessage activate(int group, int instance) throws Exception {
    return named("WolfBroGameActivateBulletCsReq", Map.of("group_id", group, "DAKENPDMDDJ", instance));
  }
  void activate() throws Exception { assertEquals(0, code(activate(20, 300009))); }
  DynamicMessage use(List<Integer> ids) throws Exception {
    return call(6514, "OKEFGCNNEKI", "WolfBroGameUseBulletScRsp", Map.of("assist_monster_entity_id_list", ids));
  }
  DynamicMessage useRawIds(List<Long> packed, List<Integer> unpacked) throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    var out = com.google.protobuf.CodedOutputStream.newInstance(bytes);
    if (!packed.isEmpty()) {
      var values = new java.io.ByteArrayOutputStream();
      var payload = com.google.protobuf.CodedOutputStream.newInstance(values);
      for (long id : packed) payload.writeUInt64NoTag(id);
      payload.flush(); out.writeByteArray(1, values.toByteArray());
    }
    for (int id : unpacked) out.writeUInt32(1, id);
    out.flush(); session.response = null;
    assertTrue(VersionedPacketHandler.handle(session, 6514, bytes.toByteArray()));
    assertEquals("WolfBroGameUseBulletScRsp", session.responseName);
    return Objects.requireNonNull(session.response);
  }
  DynamicMessage pickup(DynamicMessage pos) throws Exception {
    return named("WolfBroGamePickupBulletCsReq", Map.of("GGDCDJDBCCN", pos));
  }

  @Test void originalSourceRowsAndPlacementStayCompleteAndBounded() {
    assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b", proof.get("sourceCommit").getAsString());
    assertEquals(6, proof.getAsJsonArray("sourceFiles").size());
    assertEquals(30, proof.getAsJsonArray("sourceRows").size());
    assertFalse(proof.getAsJsonObject("serverPolicy").get("officialNumericalRulesRecovered").getAsBoolean());
    assertEquals(103068, gun().getPropId());
    assertEquals(17, WolfBroCatalog.levels.size());
  }
  @Test void queryAndStartNeverManufactureAnActiveGunOrFreeAmmo() throws Exception {
    assertEquals(0, ammo(query()));
    assertFalse((Boolean) Gameplay46.value(query(), "is_bullet_activated"));
    start();
    assertEquals(0, ammo(query()));
    assertFalse((Boolean) Gameplay46.value(query(), "is_bullet_activated"));
  }
  @Test void resourceLoaderNormalizedPropsAcceptTheSameCompleteOriginalGun() throws Exception {
    var originalGun = proof.getAsJsonArray("sourceRows").asList().stream()
        .map(JsonElement::getAsJsonObject).filter(r -> r.get("table").getAsString().equals("MazeProp"))
        .map(r -> r.getAsJsonObject("data")).filter(r -> r.get("ID").getAsInt() == 103068)
        .findFirst().orElseThrow();
    assertFalse(originalGun.get("JsonPath").getAsString().isEmpty());
    GameData.getPropExcelMap().values().forEach(PropExcel::onLoad);
    assertNull(GameData.getPropExcelMap().get(103068).getJsonPath());
    start();
    assertEquals(0, code(activate(20, 300009)), "actual ResourceLoader calls PropExcel.onLoad before gameplay");
    assertEquals(5, ammo(query()));
    assertEquals(1, session.actor.saves);
  }
  @Test void oneActualLoadedGunActivationSavesBeforePublishingTheOriginalNativeState() throws Exception {
    start(); activate();
    assertEquals(5, ammo(query()));
    assertTrue((Boolean) Gameplay46.value(query(), "is_bullet_activated"));
    assertEquals(1, session.actor.saves);
    assertEquals(1, Collections.frequency(session.emitted, "WolfBroGameDataChangeScNotify"));
    assertNotNull(session.actor.saved);
  }
  @Test void repeatedActivationDoesNotRefillAfterAnActualShot() throws Exception {
    start(); activate(); assertEquals(0, code(use(List.of())));
    assertEquals(4, ammo(query()));
    int saved = session.actor.saves;
    activate();
    assertEquals(4, ammo(query())); assertEquals(saved, session.actor.saves);
  }
  @Test void emptyNativeHitListsRepresentShotsAndSpendExactlyOneUntilTheMagazineIsEmpty() throws Exception {
    start(); activate();
    for (int expected = 4; expected >= 0; expected--) {
      assertEquals(0, code(use(List.of()))); assertEquals(expected, ammo(query()));
    }
    assertNotEquals(0, code(use(List.of()))); assertEquals(0, ammo(query()));
    assertEquals(6, session.actor.saves);
  }
  @ParameterizedTest @ValueSource(ints={0, 300008, 300010, 103068, Integer.MAX_VALUE})
  void anInstanceOutsideTheOriginalLoadedGunIsRejectedWithoutAmmo(int instance) throws Exception {
    start(); assertNotEquals(0, code(activate(20, instance))); assertEquals(0, ammo(query())); assertEquals(0, session.actor.saves);
  }
  @Test void theSameInstanceInAnotherSourceGroupIsNotTheGunIdentity() throws Exception {
    start(); assertNotEquals(0, code(activate(22, 300009))); assertEquals(0, ammo(query()));
  }
  @Test void activationRequiresTheActiveSmallStateAndActualLoadedSourceEntity() throws Exception {
    assertNotEquals(0, code(activate(20, 300009)));
    start(); session.actor.current.removeEntity(gun());
    assertNotEquals(0, code(activate(20, 300009))); assertEquals(0, ammo(query()));
  }
  @Test void rejectedActivationSaveRollsBackAndCanRetryTheSameOriginalGun() throws Exception {
    start(); session.actor.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), code(activate(20, 300009)));
    assertEquals(0, ammo(query())); assertEquals(0, session.actor.saves);
    assertEquals(0, Collections.frequency(session.emitted, "WolfBroGameDataChangeScNotify"));
    session.actor.failSave = false; activate(); assertEquals(5, ammo(query()));
  }
  @Test void rejectedShotSaveDoesNotConsumeAmmoAndTheExactShotCanRetry() throws Exception {
    start(); activate(); session.actor.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), code(use(List.of()))); assertEquals(5, ammo(query()));
    session.actor.failSave = false; assertEquals(0, code(use(List.of()))); assertEquals(4, ammo(query()));
  }
  @Test void aSourceMonsterHitCreatesOneSavedPickupAndDoesNotStartAnOrdinaryBattle() throws Exception {
    start(); activate(); var target = monster(); int id = target.getEntityId();
    assertEquals(0, code(use(List.of(id)))); assertEquals(4, ammo(query()));
    assertNull(session.actor.current.getEntityById(id)); assertFalse(session.actor.isInBattle());
    var drops = (List<?>) Gameplay46.value(query(), "PAOEJKABFEP"); assertEquals(1, drops.size());
    var pos = (DynamicMessage) drops.getFirst();
    assertEquals(List.of(target.getPos().getX(), target.getPos().getY(), target.getPos().getZ()),
        List.of(Gameplay46.number(pos,"x"), Gameplay46.number(pos,"y"), Gameplay46.number(pos,"z")));
    assertEquals(0, code(pickup(pos))); assertEquals(5, ammo(query()));
    assertTrue(((List<?>) Gameplay46.value(query(), "PAOEJKABFEP")).isEmpty());
    assertNotEquals(0, code(pickup(pos))); assertEquals(5, ammo(query()));
    assertNotEquals(0, code(use(List.of(id)))); assertEquals(5, ammo(query()));
  }
  @Test void unknownPickupCoordinatesAndForeignOrDuplicateHitIdsNeverMintAmmo() throws Exception {
    start(); activate();
    assertNotEquals(0, code(pickup(vector(1,2,3))));
    assertNotEquals(0, code(use(List.of(Integer.MAX_VALUE))));
    int id = monster().getEntityId(); assertNotEquals(0, code(use(List.of(id,id))));
    assertEquals(5, ammo(query())); assertNotNull(session.actor.current.getEntityById(id));
  }
  @Test void archiveAndRestoreReturnTheSavedAmmoStateWithTheSameGameIdentity() throws Exception {
    start(); activate(); assertEquals(0, code(use(List.of())));
    String uuid = (String) Gameplay46.value(query(), "uuid");
    assertEquals(0, code(named("ArchiveWolfBroGameCsReq", Map.of("id",11,"motion",motion()))));
    assertEquals(0, code(use(List.of()))); assertEquals(3, ammo(query()));
    assertEquals(0, code(named("RestoreWolfBroGameArchiveCsReq", Map.of("id",11))));
    assertEquals(4, ammo(query())); assertEquals(uuid, Gameplay46.value(query(),"uuid"));
  }
  @Test void changedOriginalMaximumFailsClosedInsteadOfMintingANewMagazine() throws Exception {
    start(); var path = root.resolve("ExcelOutput/ConstValueCommon.json");
    Files.writeString(path, Files.readString(path).replace("\"IntValue\":5", "\"IntValue\":500"));
    assertNotEquals(0, code(activate(20,300009))); assertEquals(0, ammo(query()));
  }

  @Test void aLargePlayerCannotActivateOrSpendTheMiniFormGun() throws Exception {
    start();
    var group = Gameplay46.builder(session, "GroupStateInfo");
    Gameplay46.set(group, "group_id", 21); Gameplay46.set(group, "group_state", 1);
    assertEquals(0, code(named("GroupStateChangeCsReq", Map.of("group_state_info", group.build()))));
    int saved = session.actor.saves;
    assertNotEquals(0, code(activate(20, 300009))); assertEquals(0, ammo(query()));
    assertNotEquals(0, code(use(List.of()))); assertEquals(saved, session.actor.saves);
  }

  @Test void aRejectedHitSaveLeavesTheActualNpcAndDropLedgerAvailableForRetry() throws Exception {
    start(); activate(); int id = monster().getEntityId(); int saved = session.actor.saves;
    session.actor.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), code(use(List.of(id))));
    assertNotNull(session.actor.current.getEntityById(id)); assertEquals(5, ammo(query()));
    assertTrue(((List<?>) Gameplay46.value(query(), "PAOEJKABFEP")).isEmpty());
    assertEquals(saved, session.actor.saves);
    session.actor.failSave = false;
    assertEquals(0, code(use(List.of(id)))); assertNull(session.actor.current.getEntityById(id));
    assertEquals(4, ammo(query()));
  }

  @Test void rejectedPickupSaveKeepsItsActualCoordinatesAndCanRetryOnce() throws Exception {
    start(); activate(); assertEquals(0, code(use(List.of(monster().getEntityId()))));
    var pos = (DynamicMessage) ((List<?>) Gameplay46.value(query(), "PAOEJKABFEP")).getFirst();
    session.actor.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), code(pickup(pos))); assertEquals(4, ammo(query()));
    assertEquals(1, ((List<?>) Gameplay46.value(query(), "PAOEJKABFEP")).size());
    session.actor.failSave = false;
    assertEquals(0, code(pickup(pos))); assertEquals(5, ammo(query()));
  }

  @Test void oneShotCanHitTwoActualOriginalNpcsAndStoresBothDropsBeforeRemovingEither() throws Exception {
    start(); activate();
    var targets = session.actor.current.getEntities().values().stream().filter(EntityMonster.class::isInstance)
        .map(EntityMonster.class::cast).filter(m -> m.getGroupId() == 22).toList();
    assertEquals(2, targets.size());
    var ids = targets.stream().map(EntityMonster::getEntityId).toList();
    assertEquals(0, code(use(ids))); assertEquals(4, ammo(query()));
    assertEquals(2, ((List<?>) Gameplay46.value(query(), "PAOEJKABFEP")).size());
    for (int id : ids) assertNull(session.actor.current.getEntityById(id));
  }

  @Test void oneNativeShotAcceptsMixedPackedAndUnpackedOriginalEntityIds() throws Exception {
    start(); activate();
    var ids = session.actor.current.getEntities().values().stream().filter(EntityMonster.class::isInstance)
        .map(EntityMonster.class::cast).filter(m -> m.getGroupId() == 22).map(EntityMonster::getEntityId).sorted().toList();
    assertEquals(2, ids.size());
    assertEquals(0, code(useRawIds(List.of(ids.getFirst().longValue()), List.of(ids.getLast()))));
    assertEquals(4, ammo(query()));
    assertEquals(2, ((List<?>) Gameplay46.value(query(), "PAOEJKABFEP")).size());
    for (int id : ids) assertNull(session.actor.current.getEntityById(id));
  }

  @Test void oversizedPackedVarintsCannotAliasAnActualOriginalNpcIdOrConsumeAmmo() throws Exception {
    start(); activate(); var target = monster(); int id = target.getEntityId(), saves = session.actor.saves;
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), code(useRawIds(List.of((1L << 32) + id), List.of())));
    assertEquals(5, ammo(query())); assertEquals(saves, session.actor.saves);
    assertSame(target, session.actor.current.getEntityById(id));
    assertTrue(((List<?>) Gameplay46.value(query(), "PAOEJKABFEP")).isEmpty());
    assertEquals(0, code(use(List.of(id)))); assertEquals(4, ammo(query()));
  }

  @Test void theNativeArchiveContainsItsOwnAmmoSnapshotAndRejectedRestoreRollsBack() throws Exception {
    start(); activate(); assertEquals(0, code(use(List.of())));
    var archived = named("ArchiveWolfBroGameCsReq", Map.of("id",11,"motion",motion()));
    assertEquals(0, code(archived));
    var nested = (DynamicMessage) Gameplay46.value(query(), "BBEMIGELGJC");
    assertEquals(4, ammo(nested)); assertTrue((Boolean) Gameplay46.value(nested,"is_bullet_activated"));
    assertEquals(0, code(use(List.of())));
    var position = new emu.lunarcore.util.Position(session.actor.getPos());
    session.actor.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), code(named("RestoreWolfBroGameArchiveCsReq", Map.of("id",11))));
    assertEquals(3, ammo(query())); assertEquals(position.getX(), session.actor.getPos().getX());
    session.actor.failSave = false;
    assertEquals(0, code(named("RestoreWolfBroGameArchiveCsReq", Map.of("id",11)))); assertEquals(4, ammo(query()));
  }

  @Test void aCommittedDefeatDoesNotRespawnWhenItsOriginalGroupLoadsAgain() throws Exception {
    start(); activate(); var source = monster().getMonsterInfo();
    assertEquals(0, code(use(List.of(monster().getEntityId()))));
    var group = session.actor.current.getFloorInfo().getGroups().get(22);
    assertNull(session.actor.current.getEntityLoader().loadMonster(session.actor.current, group, source));
    var other = group.getMonsterList().stream().filter(m -> m.getID() != source.getID()).findFirst().orElseThrow();
    assertNotNull(session.actor.current.getEntityLoader().loadMonster(session.actor.current, group, other));
  }

  @Test void anAmmoReceiptCopiedToAnotherUidFailsBeforeAnyQueryOrSpend() throws Exception {
    start(); activate(); int saves = session.actor.saves;
    session.actor.owner = 988;
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), code(named("GetWolfBroGameDataCsReq", Map.of("id",11))));
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), code(use(List.of()))); assertEquals(saves, session.actor.saves);
  }

  @Test void anEntityWhoseActualEventWasChangedIsNotAnOriginalTarget() throws Exception {
    start(); activate(); var target = monster(); target.setEventId(1);
    assertNotEquals(0, code(use(List.of(target.getEntityId())))); assertEquals(5, ammo(query()));
    assertNotNull(session.actor.current.getEntityById(target.getEntityId()));
  }
}
