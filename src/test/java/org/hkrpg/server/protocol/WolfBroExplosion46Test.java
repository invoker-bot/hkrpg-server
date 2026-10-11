package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.PropExcel;
import emu.lunarcore.game.scene.entity.*;
import emu.lunarcore.server.packet.Retcode;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real 6502/field12 reports use the same original-level death receipts as ordinary shots. */
class WolfBroExplosion46Test {
  @TempDir Path root;
  WolfBroAmmo46Test h;
  JsonObject source;

  @BeforeEach void setup() throws Exception {
    h = new WolfBroAmmo46Test(); h.root = root; h.setup();
    source = JsonParser.parseString(Files.readString(
        Path.of("src/test/resources/fixtures/wolfbro-explosion-4.6.json"))).getAsJsonObject();
    for (var entry : source.getAsJsonArray("sourceFiles")) {
      var record = entry.getAsJsonObject(); var path = root.resolve(record.get("path").getAsString());
      Files.createDirectories(path.getParent()); Files.writeString(path, record.get("data").toString());
    }
    var row = source.getAsJsonArray("sourceRows").get(0).getAsJsonObject().getAsJsonObject("data");
    var tablePath = root.resolve("ExcelOutput/MazeProp.json");
    var table = JsonParser.parseString(Files.readString(tablePath)).getAsJsonArray();
    table.add(row.deepCopy()); Files.writeString(tablePath, table.toString());
    var prop = h.gson.fromJson(row, PropExcel.class); prop.onLoad(); GameData.getPropExcelMap().add(prop);
    for (var group : h.session.actor.current.getFloorInfo().getGroups().values()) {
      for (var info : group.getPropList()) if (info.getPropID() == 103055) {
        var actual = h.session.actor.current.getEntityLoader().loadProp(h.session.actor.current, group, info);
        assertNotNull(actual); h.session.actor.current.addEntity(actual);
      }
    }
    assertEquals(3, barrels().size());
  }

  @AfterEach void cleanup() { if (h != null) h.cleanup(); }

  List<EntityProp> barrels() {
    return h.session.actor.current.getEntities().values().stream().filter(EntityProp.class::isInstance)
        .map(EntityProp.class::cast).filter(p -> p.getPropId() == 103055).toList();
  }

  List<EntityMonster> targets() {
    return h.session.actor.current.getEntities().values().stream().filter(EntityMonster.class::isInstance)
        .map(EntityMonster.class::cast).filter(m -> m.getGroupId() == 22).sorted(
            Comparator.comparingInt(EntityMonster::getInstId)).toList();
  }

  DynamicMessage explode(List<Integer> ids) throws Exception {
    return h.call(6502, "LBMFCJLHNAA", "WolfBroGameExplodeMonsterScRsp",
        Map.of("assist_monster_entity_id_list", ids));
  }

  DynamicMessage raw(byte[] bytes) throws Exception {
    h.session.response = null;
    assertTrue(VersionedPacketHandler.handle(h.session, 6502, bytes), "native explosion command 6502");
    assertEquals("WolfBroGameExplodeMonsterScRsp", h.session.responseName);
    return Objects.requireNonNull(h.session.response);
  }

  byte[] ids(List<Long> packed, List<Integer> unpacked) throws Exception {
    var bytes = new ByteArrayOutputStream(); var out = CodedOutputStream.newInstance(bytes);
    if (!packed.isEmpty()) {
      var values = new ByteArrayOutputStream(); var payload = CodedOutputStream.newInstance(values);
      for (long id : packed) payload.writeUInt64NoTag(id);
      payload.flush(); out.writeByteArray(12, values.toByteArray());
    }
    for (int id : unpacked) out.writeUInt32(12, id);
    out.flush(); return bytes.toByteArray();
  }

  static int code(DynamicMessage value) { return WolfBroAmmo46Test.code(value); }
  int ammo() throws Exception { return WolfBroAmmo46Test.ammo(h.query()); }
  int drops() throws Exception { return ((List<?>) Gameplay46.value(h.query(), "PAOEJKABFEP")).size(); }
  void start() throws Exception { h.start(); h.activate(); h.session.emitted.clear(); }
  void unchanged(int saved, int count, List<EntityMonster> before) throws Exception {
    assertEquals(saved, h.session.actor.saves); assertEquals(count, ammo());
    for (var target : before) assertSame(target, h.session.actor.current.getEntityById(target.getEntityId()));
    assertEquals(0, drops());
    assertEquals(0, Collections.frequency(h.session.emitted, "WolfBroGameDataChangeScNotify"));
  }

  @Test void completeOriginalSourcesExposeFourBoomTasksAndTheirActualCharacterEntries() {
    assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b", source.get("sourceCommit").getAsString());
    assertEquals(11, source.getAsJsonArray("sourceFiles").size());
    assertEquals(1, source.getAsJsonArray("sourceRows").size());
    assertFalse(source.getAsJsonObject("serverPolicy").get("serverExplosionPhysicsImplemented").getAsBoolean());
    assertEquals(4, source.getAsJsonArray("sourceFiles").asList().stream()
        .map(JsonElement::getAsJsonObject).filter(r -> r.get("path").getAsString().contains("ConfigAdventureAbility"))
        .filter(r -> r.get("data").toString().contains("WolfBroMonsterHitBoomProp")).count());
  }

  @Test void realWireSchemaKeepsItsUnnamedRequestAndRetcodeOnlyResponse() {
    var request = Gameplay46.builder(h.session, "LBMFCJLHNAA").getDescriptorForType();
    assertEquals(1, request.getFields().size());
    assertEquals(12, request.findFieldByName("assist_monster_entity_id_list").getNumber());
    assertEquals(Descriptors.FieldDescriptor.Type.UINT32,
        request.findFieldByName("assist_monster_entity_id_list").getType());
    assertEquals(6518, h.session.getProtocol().clientCommand("WolfBroGameExplodeMonsterScRsp"));
    var response = Gameplay46.builder(h.session, "WolfBroGameExplodeMonsterScRsp").getDescriptorForType();
    assertEquals(1, response.getFields().size()); assertEquals(15, response.findFieldByName("retcode").getNumber());
  }

  @Test void oneOriginalBlastSavesItsDeathAndDropWithoutSpendingAnOrdinaryRound() throws Exception {
    start(); var target = targets().getFirst(); int id = target.getEntityId(); int saved = h.session.actor.saves;
    assertEquals(0, code(explode(List.of(id)))); assertEquals(saved+1, h.session.actor.saves);
    assertEquals("WolfBroGameExplodeMonsterScRsp", h.session.emitted.getLast());
    assertNull(h.session.actor.current.getEntityById(id)); assertEquals(5, ammo()); assertEquals(1, drops());
    assertTrue(h.session.actor.getGameplay().getWolfBro().getRuns().get(11).getAmmo().getDefeated().contains("22:200001"));
    assertEquals(1, Collections.frequency(h.session.emitted, "WolfBroGameDataChangeScNotify"));
    assertEquals(0, h.session.actor.getScoin()); assertEquals(0, h.session.actor.getHcoin());
  }

  @Test void aBlastCanCommitTwoOriginalVictimsAsOneSaveAndOneNativeNotify() throws Exception {
    start(); var before = targets(); assertEquals(2, before.size()); int saved = h.session.actor.saves;
    var entityIds = before.stream().map(EntityMonster::getEntityId).toList();
    assertEquals(0, code(explode(entityIds))); assertEquals(saved+1, h.session.actor.saves);
    assertEquals(5, ammo()); assertEquals(2, drops());
    for (int id : entityIds) assertNull(h.session.actor.current.getEntityById(id));
    assertEquals(1, Collections.frequency(h.session.emitted, "WolfBroGameDataChangeScNotify"));
  }

  @Test void anEmptyExplosionReportIsAnAcknowledgementWithoutMutation() throws Exception {
    start(); int saved = h.session.actor.saves;
    assertEquals(0, code(explode(List.of()))); unchanged(saved, 5, targets());
  }

  @Test void anActivatedEmptyMagazineCanStillReportARealBlast() throws Exception {
    start(); for (int shot=0; shot<5; shot++) assertEquals(0, code(h.use(List.of())));
    assertEquals(0, ammo()); var target = targets().getFirst();
    assertEquals(0, code(explode(List.of(target.getEntityId())))); assertEquals(0, ammo()); assertEquals(1, drops());
  }

  @Test void aBlastDropIsTheSameExactPositionPickupAndCannotBeTakenTwice() throws Exception {
    start(); assertEquals(0, code(h.use(List.of())));
    assertEquals(0, code(explode(List.of(targets().getFirst().getEntityId()))));
    assertEquals(4, ammo()); var point = (DynamicMessage) ((List<?>) Gameplay46.value(h.query(), "PAOEJKABFEP")).getFirst();
    assertEquals(0, code(h.pickup(point))); assertEquals(5, ammo()); assertEquals(0, drops());
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), code(h.pickup(point)));
  }

  @Test void mixedPackedAndUnpackedField12IdsAreOneOriginalExplosionReport() throws Exception {
    start(); var before = targets();
    assertEquals(0, code(raw(ids(List.of((long) before.getFirst().getEntityId()), List.of(before.getLast().getEntityId())))));
    assertEquals(5, ammo()); assertEquals(2, drops());
  }

  @ParameterizedTest @ValueSource(ints={0, -1, Integer.MAX_VALUE})
  void malformedOrForeignIdsRejectTheEntireBlast(int invalid) throws Exception {
    start(); var before = targets(); int saved = h.session.actor.saves;
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), code(explode(List.of(before.getFirst().getEntityId(), invalid))));
    unchanged(saved, 5, before);
  }

  @Test void duplicateIdsNeverCreateTwoDropsOrDefeatEitherNpc() throws Exception {
    start(); var before = targets(); int saved = h.session.actor.saves, id = before.getFirst().getEntityId();
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), code(explode(List.of(id,id)))); unchanged(saved,5,before);
  }

  @Test void aPackedUint64CannotAliasTheActualUint32Victim() throws Exception {
    start(); var before = targets(); int saved = h.session.actor.saves;
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), code(raw(ids(List.of((1L<<32)+before.getFirst().getEntityId()),List.of()))));
    unchanged(saved,5,before);
  }

  @Test void theShotFieldNumberCannotBeSubstitutedForTheBlastFieldNumber() throws Exception {
    start(); var before=targets(); int saved=h.session.actor.saves;
    var bytes=new ByteArrayOutputStream(); var out=CodedOutputStream.newInstance(bytes);
    out.writeUInt32(1,before.getFirst().getEntityId()); out.flush();
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(),code(raw(bytes.toByteArray()))); unchanged(saved,5,before);
  }

  @Test void sourceEventDriftRejectsBeforeAnyDeathOrSave() throws Exception {
    start(); var before=targets(); before.getFirst().setEventId(1); int saved=h.session.actor.saves;
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(),code(explode(List.of(before.getFirst().getEntityId()))));
    unchanged(saved,5,before);
  }

  @Test void aLargePlayerCannotSubmitTheMiniFormBlastReport() throws Exception {
    start(); var g=Gameplay46.builder(h.session,"GroupStateInfo");
    Gameplay46.set(g,"group_id",21); Gameplay46.set(g,"group_state",1);
    assertEquals(0,code(h.named("GroupStateChangeCsReq",Map.of("group_state_info",g.build()))));
    h.session.emitted.clear(); var before=targets(); int saved=h.session.actor.saves;
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(),code(explode(List.of(before.getFirst().getEntityId())))); unchanged(saved,5,before);
  }

  @Test void theOriginalLevelRequiresARealActivatedGun() throws Exception {
    h.start(); var before=targets(); int saved=h.session.actor.saves;
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(),code(explode(List.of(before.getFirst().getEntityId())))); unchanged(saved,0,before);
  }

  @Test void aBlastOutsideTheActiveGameHasNoDeathOrDrop() throws Exception {
    var before=targets(); int saved=h.session.actor.saves;
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(),code(explode(List.of(before.getFirst().getEntityId())))); unchanged(saved,0,before);
  }

  @Test void theReportRequiresAnActualAuthoredExplosivePropOnThisLoadedLevel() throws Exception {
    start(); for (var barrel:barrels()) h.session.actor.current.removeEntity(barrel);
    var before=targets(); int saved=h.session.actor.saves;
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(),code(explode(List.of(before.getFirst().getEntityId())))); unchanged(saved,5,before);
  }

  @Test void theCompleteOriginalAbilityCannotBeReplacedByAnUnreviewedBlastTask() throws Exception {
    start(); Path ability=root.resolve("Config/ConfigAdventureAbility/NpcMonster/NPCMonster_W3_TV_00_Ability.json");
    var data=JsonParser.parseString(Files.readString(ability)).getAsJsonObject(); data.addProperty("UnknownOwnerPolicy",1);
    Files.writeString(ability,data.toString()); var before=targets(); int saved=h.session.actor.saves;
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(),code(explode(List.of(before.getFirst().getEntityId())))); unchanged(saved,5,before);
  }

  @Test void changedOriginalExplosiveRowCannotAuthorizeItsLoadedInstance() throws Exception {
    start(); var path=root.resolve("ExcelOutput/MazeProp.json"); var table=JsonParser.parseString(Files.readString(path)).getAsJsonArray();
    table.asList().stream().map(JsonElement::getAsJsonObject).filter(r->r.get("ID").getAsInt()==103055)
        .forEach(r->r.addProperty("LodPriority",99)); Files.writeString(path,table.toString());
    var before=targets(); int saved=h.session.actor.saves;
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(),code(explode(List.of(before.getFirst().getEntityId())))); unchanged(saved,5,before);
  }

  @Test void aRejectedBlastSaveKeepsBothRealEntitiesAndTheSameRunForRetry() throws Exception {
    start(); var before=targets(); var run=h.session.actor.getGameplay().getWolfBro().getRuns().get(11);
    int saved=h.session.actor.saves; h.session.actor.failSave=true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(),code(explode(before.stream().map(EntityMonster::getEntityId).toList())));
    assertSame(run,h.session.actor.getGameplay().getWolfBro().getRuns().get(11)); unchanged(saved,5,before);
    h.session.actor.failSave=false;
    assertEquals(0,code(explode(before.stream().map(EntityMonster::getEntityId).toList()))); assertEquals(2,drops());
  }

  @Test void ordinaryShotAndBlastCannotPayTwiceForTheSameOriginalVictim() throws Exception {
    start(); int id=targets().getFirst().getEntityId(); assertEquals(0,code(h.use(List.of(id))));
    int saved=h.session.actor.saves;
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(),code(explode(List.of(id))));
    assertEquals(saved,h.session.actor.saves); assertEquals(4,ammo()); assertEquals(1,drops());
  }

  @Test void aRepeatedBlastCannotDefeatOrDropAgainWhenTheGroupReloads() throws Exception {
    start(); var target=targets().getFirst(); int id=target.getEntityId(); var info=target.getMonsterInfo();
    assertEquals(0,code(explode(List.of(id)))); int saved=h.session.actor.saves;
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(),code(explode(List.of(id)))); assertEquals(saved,h.session.actor.saves);
    var group=h.session.actor.current.getFloorInfo().getGroups().get(22);
    assertNull(h.session.actor.current.getEntityLoader().loadMonster(h.session.actor.current,group,info));
    assertEquals(5,ammo()); assertEquals(1,drops());
  }

  @Test void archiveRestoreKeepsTheActualBlastDeathReceiptWithoutAddingAmmo() throws Exception {
    start(); assertEquals(0,code(h.named("ArchiveWolfBroGameCsReq",Map.of("id",11,"motion",h.motion()))));
    var target=targets().getFirst(); var info=target.getMonsterInfo();
    assertEquals(0,code(explode(List.of(target.getEntityId())))); assertEquals(1,drops());
    assertEquals(0,code(h.named("RestoreWolfBroGameArchiveCsReq",Map.of("id",11)))); assertEquals(5,ammo()); assertEquals(0,drops());
    var group=h.session.actor.current.getFloorInfo().getGroups().get(22);
    assertNull(h.session.actor.current.getEntityLoader().loadMonster(h.session.actor.current,group,info));
  }

  @Test void aStoredBlastDeathAndDropResumeInTheSameUidDocument() throws Exception {
    start(); var target=targets().getFirst(); int id=target.getEntityId(); var info=target.getMonsterInfo();
    assertEquals(0,code(explode(List.of(id)))); String saved=h.session.actor.saved;
    var field=emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay"); field.setAccessible(true);
    field.set(h.session.actor,h.gson.fromJson(saved,GameplayState.class));
    assertEquals(5,ammo()); assertEquals(1,drops());
    var group=h.session.actor.current.getFloorInfo().getGroups().get(22);
    assertNull(h.session.actor.current.getEntityLoader().loadMonster(h.session.actor.current,group,info));
    h.session.actor.owner++;
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(),code(explode(List.of(targets().getFirst().getEntityId()))));
  }

  @Test void existingOrdinaryShotStillConsumesItsOwnMagazineAndReceipt() throws Exception {
    start(); assertEquals(0,code(h.use(List.of(targets().getFirst().getEntityId()))));
    assertEquals(4,ammo()); assertEquals(1,drops());
  }
}
