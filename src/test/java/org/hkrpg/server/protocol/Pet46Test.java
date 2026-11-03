package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.PetExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.PlayerUnlockData;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.server.packet.recv.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Independent CN4.6 bytes: normal request 1/13, selected state 13/9, reply 5/8. */
class Pet46Test {
  static final Gson JSON = new Gson();
  final List<PetExcel> previous = new ArrayList<>();

  static class Actor extends Player {
    final PlayerUnlockData owned = new PlayerUnlockData();
    int saves;
    boolean failSave;
    String saved;
    @Override public PlayerUnlockData getUnlocks() { return owned; }
    @Override public void save() {
      if (failSave) throw new IllegalStateException("pet save failed");
      saves++;
      saved = JSON.toJson(this, Player.class);
    }
  }

  record Sent(String name, DynamicMessage body) {}
  static class Session extends GameSession {
    Player owner = new Actor();
    String version = "4.6.0";
    final List<Sent> sent = new ArrayList<>();
    Session() { super(null); setState(SessionState.ACTIVE); }
    @Override public Player getPlayer() { return owner; }
    @Override public ProtocolBridge getProtocol() { return ProtocolBridge.forVersion(version); }
    @Override public void sendClientPacket(String name, byte[] bytes) {
      try { sent.add(new Sent(name, getProtocol().clientSchema().parse(name, bytes))); }
      catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
    }
    @Override public void send(BasePacket packet) {
      var converted = getProtocol().outbound(packet.getCmdId(),
          packet.getData() == null ? new byte[0] : packet.getData().toByteArray());
      var name = switch (packet.getCmdId()) {
        case CmdId.GetPetDataScRsp -> "GetPetDataScRsp";
        case CmdId.SummonPetScRsp -> "SummonPetScRsp";
        case CmdId.RecallPetScRsp -> "RecallPetScRsp";
        default -> throw new AssertionError("Unexpected packet " + packet.getCmdId());
      };
      sendClientPacket(name, converted.body());
    }
    Actor actor() { return (Actor) owner; }
    DynamicMessage reply(String name) {
      return sent.stream().filter(p -> p.name().equals(name)).reduce((a,b) -> b).orElseThrow().body();
    }
    long notifications() { return sent.stream().filter(p -> p.name().endsWith("ScNotify")).count(); }
  }

  @BeforeEach void resources() {
    previous.addAll(GameData.getPetExcelMap().values());
    GameData.getPetExcelMap().values().clear();
    for (int index = 1; index <= 5; index++) {
      int id = 1000 + index;
      var row = JSON.fromJson("{\"PBLDLDIEFNC\":" + id + ",\"HHDHIDNHCBI\":"
          + (251000 + index) + ",\"FNBGMDDOHEA\":" + new int[] {24001,32001,36001,42001,44001}[index-1] + "}", PetExcel.class);
      GameData.getPetExcelMap().add(row);
    }
  }
  @AfterEach void cleanup() {
    GameData.getPetExcelMap().values().clear();
    previous.forEach(GameData.getPetExcelMap()::add);
  }

  static byte[] scalar(int field, long number) throws Exception {
    var out = new ByteArrayOutputStream();
    var wire = CodedOutputStream.newInstance(out); wire.writeUInt64(field, number); wire.flush();
    return out.toByteArray();
  }
  static byte[] join(byte[]... values) {
    var out = new ByteArrayOutputStream();
    for (var v : values) out.writeBytes(v);
    return out.toByteArray();
  }
  static byte[] summon(int id) throws Exception { return join(scalar(1,id), scalar(13,1)); }

  static void dispatch(Session s, int command, byte[] bytes) throws Exception {
    if (VersionedPacketHandler.handle(s, command, bytes)) return;
    var converted = s.getProtocol().inbound(command, bytes);
    assertEquals(CmdId.GetPetDataCsReq,converted.cmdId(),"Pet mutations need a native handler");
    new HandlerGetPetDataCsReq().handle(s, converted.body());
  }
  static void selected(DynamicMessage reply, int field, int id) {
    assertTrue(reply.getUnknownFields().asMap().isEmpty());
    var key = reply.getDescriptorForType().findFieldByNumber(field);
    if (id == 0) {
      if (reply.hasField(key)) {
        var state = (DynamicMessage) reply.getField(key);
        assertEquals(0, ((com.google.protobuf.Descriptors.EnumValueDescriptor)value(state,"LLGIIPALELD")).getNumber());
        assertTrue(state.getAllFields().isEmpty());
      }
      return;
    }
    assertTrue(reply.hasField(key), "A selected pet must survive the native state union");
    var state = (DynamicMessage) reply.getField(key);
    assertEquals(1, ((com.google.protobuf.Descriptors.EnumValueDescriptor)value(state,"LLGIIPALELD")).getNumber());
    assertEquals(id, number(state, "ODNGMIJLKKA"));
    assertFalse(state.hasField(state.getDescriptorForType().findFieldByNumber(3)));
  }
  static Session owned(int... ids) {
    var s = new Session();
    for (int id : ids) s.owner.getUnlocks().getPets().add(id);
    return s;
  }

  @ParameterizedTest @ValueSource(ints = {1001,1002,1003,1004,1005})
  void anOwnedNormalPetIsSummonedAndQueryableWithNativeFields(int id) throws Exception {
    var s = owned(id);
    dispatch(s, 7616, summon(id));
    assertEquals(id, s.owner.getPetId());
    var reply = s.reply("SummonPetScRsp");
    assertEquals(0, number(reply,"retcode")); selected(reply,5,id);
    assertArrayEquals(join(new byte[] {42,5,72,1,104},scalarValue(id)),reply.toByteArray());
    assertEquals(1, s.actor().saves);
    assertEquals(1, s.notifications());
    var notify = s.reply("PetDataChangeScNotify");
    var change = (DynamicMessage) ((List<?>)value(notify,"DGNGMJMPFGK")).getFirst();
    selected(change,11,id);
    dispatch(s,7625,new byte[0]);
    var query = s.reply("GetPetDataScRsp");
    selected(query,11,id);
    assertEquals(List.of(id), value(query,"unlocked_pet_id"));
  }

  static byte[] scalarValue(int value) throws Exception {
    var out = new ByteArrayOutputStream();
    var wire = CodedOutputStream.newInstance(out); wire.writeUInt32NoTag(value); wire.flush();
    return out.toByteArray();
  }

  @Test void switchingRepeatedClicksAndRecallKeepOneSelectionWithoutRepeatedSaves() throws Exception {
    var s = owned(1001,1002);
    dispatch(s,7616,summon(1001));
    dispatch(s,7616,summon(1002));
    dispatch(s,7616,summon(1002));
    assertEquals(1002,s.owner.getPetId());
    assertEquals(2,s.actor().saves); assertEquals(2,s.notifications());
    selected(s.reply("SummonPetScRsp"),5,1002);
    dispatch(s,7604,new byte[0]);
    assertEquals(0,s.owner.getPetId());
    assertEquals(3,s.actor().saves); assertEquals(3,s.notifications());
    selected(s.reply("RecallPetScRsp"),11,0);
    var change = (DynamicMessage) ((List<?>)value(s.reply("PetDataChangeScNotify"),"DGNGMJMPFGK")).getFirst();
    // Clearing an existing selection must carry an explicit empty state, not an absent change.
    assertTrue(change.hasField(change.getDescriptorForType().findFieldByNumber(11)));
    selected(change,11,0);
    dispatch(s,7604,new byte[0]);
    dispatch(s,7625,new byte[0]);
    selected(s.reply("GetPetDataScRsp"),11,0);
    assertEquals(3,s.actor().saves); assertEquals(3,s.notifications());
    assertEquals(Set.of(1001,1002),new HashSet<>((List<?>)value(s.reply("GetPetDataScRsp"),"unlocked_pet_id")));
  }

  @Test void unownedUnknownAndItemIdsNeverReturnSuccessOrChangeTheCurrentPet() throws Exception {
    var s = owned(1001,9999);
    s.owner.setPetId(1001);
    for (int id : new int[] {1002,9999,251001,0,-1}) {
      dispatch(s,7616,summon(id));
      assertNotEquals(0,number(s.reply("SummonPetScRsp"),"retcode"), "id="+id);
      selected(s.reply("SummonPetScRsp"),5,1001);
      assertEquals(1001,s.owner.getPetId());
    }
    assertEquals(0,s.actor().saves); assertEquals(0,s.notifications());
  }

  @Test void saveFailuresRollbackBothSummoningAndRecallingAndCanBeRetried() throws Exception {
    var s = owned(1001,1002);
    s.owner.setPetId(1001); s.actor().failSave = true;
    dispatch(s,7616,summon(1002));
    assertEquals(Retcode.SERVER_INTERNAL_ERROR.getVal(),number(s.reply("SummonPetScRsp"),"retcode"));
    selected(s.reply("SummonPetScRsp"),5,1001);
    assertEquals(1001,s.owner.getPetId()); assertEquals(0,s.notifications());
    dispatch(s,7604,new byte[0]);
    assertEquals(Retcode.SERVER_INTERNAL_ERROR.getVal(),number(s.reply("RecallPetScRsp"),"retcode"));
    selected(s.reply("RecallPetScRsp"),11,1001);
    assertEquals(1001,s.owner.getPetId()); assertEquals(0,s.notifications());
    s.actor().failSave = false;
    dispatch(s,7616,summon(1002)); dispatch(s,7604,new byte[0]);
    assertEquals(0,s.owner.getPetId()); assertEquals(2,s.actor().saves);
  }

  @Test void malformedDuplicateWideAndUnsupportedPetKindsAreRejectedWithoutMutation() throws Exception {
    var s = owned(1001); s.owner.setPetId(1001);
    for (byte[] bytes : List.of(new byte[0], scalar(1,1001), scalar(13,1),
        join(summon(1001),scalar(1,1001)),join(summon(1001),scalar(13,1)),
        join(summon(1001),scalar(14,1001)),join(scalar(1,1001),scalar(13,2)),
        join(scalar(14,1001),scalar(13,2)),scalar(13,3),
        join(scalar(1,0x1000003e9L),scalar(13,1)),join(scalar(1,1001),scalar(13,0x100000001L)),
        join(summon(1001),scalar(3,1)),HexFormat.of().parseHex("08"),HexFormat.of().parseHex("0a01ff6801"),
        HexFormat.of().parseHex("08e9076881808080808080808002"))) {
      dispatch(s,7616,bytes);
      assertNotEquals(0,number(s.reply("SummonPetScRsp"),"retcode"),HexFormat.of().formatHex(bytes));
      assertEquals(1001,s.owner.getPetId());
    }
    for (int command : new int[] {7604,7625}) {
      dispatch(s,command,scalar(1,1001));
      assertNotEquals(0,number(s.reply(command==7604?"RecallPetScRsp":"GetPetDataScRsp"),"retcode"));
    }
    assertEquals(0,s.actor().saves); assertEquals(0,s.notifications());
  }

  @Test void legacyCommandsAndMissingPlayersCannotEnterTheNativeMutationPath() throws Exception {
    var s = owned(1001); s.version="4.2.0";
    for (int command : new int[] {7616,7604,7625})
      assertFalse(VersionedPacketHandler.handle(s,command,command==7616?summon(1001):new byte[0]));
    assertTrue(s.sent.isEmpty()); assertEquals(0,s.actor().saves);
    s.version="4.6.0"; s.owner=null;
    assertTrue(VersionedPacketHandler.handle(s,7616,summon(1001)));
    assertTrue(s.sent.isEmpty());
  }

  @Test void unauthenticatedAndDifferentPlayersCannotChangeAnotherSelection() throws Exception {
    var s = owned(1001); s.setState(SessionState.WAITING_FOR_LOGIN);
    for (int command : new int[] {7616,7604,7625})
      assertTrue(VersionedPacketHandler.handle(s,command,command==7616?summon(1001):new byte[0]));
    assertTrue(s.sent.isEmpty()); assertEquals(0,s.actor().saves);
    var other = owned(1002);
    dispatch(other,7616,summon(1001));
    assertNotEquals(0,number(other.reply("SummonPetScRsp"),"retcode"));
    assertEquals(0,other.owner.getPetId()); assertEquals(0,s.owner.getPetId());
  }

  @Test void selectedPetSurvivesActualPlayerAndUnlockDocumentReloadsAndRecallPersists() throws Exception {
    var s = owned(1001,1002); dispatch(s,7616,summon(1002));
    var mongo = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client = com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:"+address.getPort())) {
      var codecs=org.bson.codecs.configuration.CodecRegistries.fromCodecs(
          new emu.lunarcore.database.codecs.IntSetCodec());
      var options=dev.morphia.mapping.MapperOptions.builder().codecProvider(codecs).build();
      var store = dev.morphia.Morphia.createDatastore(client,"pet-selection",options);
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class,PlayerUnlockData.class,emu.lunarcore.game.quest.QuestState.class);
      var player = JSON.fromJson(s.actor().saved,Player.class);
      setField(Player.class,player,"uid",19996801);
      setField(PlayerUnlockData.class,s.owner.getUnlocks(),"ownerUid",19996801);
      store.save(player); store.save(s.owner.getUnlocks());
      for (int round=0;round<2;round++) {
        var restored = new Session(); restored.owner = store.find(Player.class).first();
        var unlocks = store.find(PlayerUnlockData.class).first();
        setField(Player.class,restored.owner,"unlocks",unlocks);
        dispatch(restored,7625,new byte[0]);
        selected(restored.reply("GetPetDataScRsp"),11,1002);
        assertEquals(Set.of(1001,1002),new HashSet<>(unlocks.getPets()));
        store.save(restored.owner);
      }
      dispatch(s,7604,new byte[0]);
      player=JSON.fromJson(s.actor().saved,Player.class);
      setField(Player.class,player,"uid",19996801); store.save(player);
      assertEquals(0,store.find(Player.class).first().getPetId());
      assertEquals(2,store.find(PlayerUnlockData.class).first().getPets().size());
    } finally { mongo.shutdownNow(); }
  }

  static void setField(Class<?> type,Object target,String name,Object value) throws Exception {
    var field=type.getDeclaredField(name); field.setAccessible(true); field.set(target,value);
  }
}
