package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.BackGroundMusicExcel;
import emu.lunarcore.data.excel.BackGroundMusicGroupExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.SessionState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.*;

class JukeboxSave46Test {
  static class Actor extends Player {
    int saves;
    boolean failSave;
    @Override public void save() {
      saves++;
      if (failSave) throw new IllegalStateException("storage unavailable");
    }
  }

  static class Session extends GameSession {
    final Player actor;
    final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
    DynamicMessage response;
    String responseName;
    Session(Player actor) { super(null); this.actor = actor; setState(SessionState.ACTIVE); }
    @Override public Player getPlayer() { return actor; }
    @Override public ProtocolBridge getProtocol() { return bridge; }
    @Override public void sendClientPacket(String name, byte[] bytes) {
      try { response = bridge.clientSchema().parse(name, bytes); responseName = name; }
      catch (Exception invalid) { throw new AssertionError(invalid); }
    }
    DynamicMessage request(String name, byte[] bytes) throws Exception {
      assertTrue(VersionedPacketHandler.handle(this, bridge.clientCommand(name), bytes),
          "The observed CN4.6 jukebox operation must have a native handler");
      assertEquals(name.replace("CsReq", "ScRsp"), responseName);
      return response;
    }
  }

  List<BackGroundMusicExcel> prior;
  List<BackGroundMusicGroupExcel> priorGroups;
  Actor actor;
  Session session;

  @BeforeEach void setup() {
    var map = GameData.getBackGroundMusicExcelMap();
    prior = new ArrayList<>(map.values());
    var groups = GameData.getBackGroundMusicGroupExcelMap();
    priorGroups = new ArrayList<>(groups.values());
    groups.keySet().clear();
    groups.add(new Gson().fromJson("{\"ID\":1}", BackGroundMusicGroupExcel.class));
    groups.add(new Gson().fromJson("{\"ID\":21,\"Type\":\"WhiteNoise\"}", BackGroundMusicGroupExcel.class));
    map.keySet().clear();
    for (int id : List.of(210000, 210001, 215001, 215002, 215003, 215004, 215005, 215006))
      map.add(new Gson().fromJson("{\"ID\":" + id + ",\"GroupID\":"
          + (id < 215000 ? 1 : 21) + "}", BackGroundMusicExcel.class));
    actor = new Actor();
    session = new Session(actor);
  }

  @AfterEach void restore() {
    var map = GameData.getBackGroundMusicExcelMap();
    map.keySet().clear();
    prior.forEach(map::add);
    var groups = GameData.getBackGroundMusicGroupExcelMap();
    groups.keySet().clear();
    priorGroups.forEach(groups::add);
  }

  static DynamicMessage selection(Session s, int id, boolean paused, boolean noisePaused, int... layers) {
    var selected = builder(s, "KHADHDLCNOI");
    set(selected, "id", id);
    set(selected, "is_pause", paused);
    var mix = builder(s, "MNCBEDBDDHL");
    set(mix, "JKNLCEEDAHJ", selected.build());
    set(mix, "PGPNCNABHAM", noisePaused);
    for (int i = 0; i < layers.length; i += 2) {
      var layer = builder(s, "AAKECCJELLI");
      set(layer, "id", layers[i]);
      set(layer, "volume", layers[i + 1]);
      add(mix, "NNJCEINOEHH", layer.build());
    }
    return mix.build();
  }

  static DynamicMessage save(Session s, DynamicMessage mix) throws Exception {
    var request = builder(s, "PlayBackGroundMusicCsReq");
    set(request, "IKJNIKIGFLF", mix);
    return s.request("PlayBackGroundMusicCsReq", request.build().toByteArray());
  }

  static DynamicMessage current(Session s) throws Exception {
    return (DynamicMessage) value(s.request("GetJukeboxDataCsReq", new byte[0]), "GFFOBALDBPM");
  }

  @Test void nativeSaveReturnsTheWholeMixThenQueryRestoresIt() throws Exception {
    var mix = selection(session, 210001, true, true, 215003, 100, 215004, 73, 215005, 0);
    var response = save(session, mix);
    assertEquals(0, number(response, "retcode"));
    assertEquals(mix, value(response, "GFFOBALDBPM"));
    assertEquals(mix, current(session));
    assertEquals(1, actor.saves);
  }

  @Test void pureAmbientMixAndClearingLayersPreservePauseAndMode() throws Exception {
    var mix = selection(session, 0, false, true, 215003, 42, 215004, 70);
    assertEquals(0, number(save(session, mix), "retcode"));
    assertEquals(mix, current(session));
    var cleared = selection(session, 210000, true, false);
    assertEquals(0, number(save(session, cleared), "retcode"));
    assertEquals(cleared, current(session));
  }

  @Test void invalidMusicDoesNotReplaceTheSavedMix() throws Exception {
    var original = selection(session, 210001, false, true, 215003, 100);
    assertEquals(0, number(save(session, original), "retcode"));
    assertNotEquals(0, number(save(session, selection(session, 999999, false, false)), "retcode"));
    assertNotEquals(0, number(save(session, selection(session, 210000, false, true, 999999, 100)), "retcode"));
    assertEquals(original, current(session));
    assertEquals(1, actor.saves);
  }

  @Test void failedPersistenceLeavesThePreviousMixInMemoryAndAllowsRetry() throws Exception {
    var original = selection(session, 210001, false, false);
    var changed = selection(session, 210000, true, true, 215004, 70);
    assertEquals(0, number(save(session, original), "retcode"));
    actor.failSave = true;
    assertNotEquals(0, number(save(session, changed), "retcode"));
    assertEquals(original, current(session));
    actor.failSave = false;
    assertEquals(0, number(save(session, changed), "retcode"));
    assertEquals(changed, current(session));
  }

  @Test void malformedAndUnknownNestedFieldsReturnErrorsWithoutSaving() throws Exception {
    for (byte[] bytes : List.of(new byte[0], new byte[] {106, 2, 10},
        new byte[] {106, 2, 32, 1}, new byte[] {106, 4, 10, 2, 24, 1})) {
      assertNotEquals(0, number(session.request("PlayBackGroundMusicCsReq", bytes), "retcode"));
    }
    assertEquals(0, actor.saves);
  }

  @Test void layerLimitsAndResourceKindsAreCheckedBeforeAnyWrite() throws Exception {
    for (var mix : List.of(
        selection(session, 215003, false, false),
        selection(session, 210000, false, false, 210001, 100),
        selection(session, 210000, false, false, 215003, 101),
        selection(session, 210000, false, false, 215003, -1),
        selection(session, 210000, false, false, 215003, 50, 215003, 100),
        selection(session, 210000, false, false,
            215001, 100, 215002, 90, 215003, 80, 215004, 70, 215005, 60, 215006, 50)))
      assertNotEquals(0, number(save(session, mix), "retcode"));
    assertEquals(0, actor.saves);
    var max = selection(session, 210000, false, false,
        215001, 100, 215002, 90, 215003, 80, 215004, 70, 215005, 0);
    assertEquals(0, number(save(session, max), "retcode"));
    assertEquals(max, current(session));
  }

  @Test void duplicateScalarsAndOverflowDoNotBecomeValidIdsOrBooleans() throws Exception {
    for (byte[] bytes : List.of(
        new byte[] {106, 0, 106, 0},
        new byte[] {106, 2, 24, 2},
        new byte[] {106, 6, 10, 4, 16, 0, 16, 1},
        new byte[] {106, 8, 10, 6, 8, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 16}))
      assertNotEquals(0, number(session.request("PlayBackGroundMusicCsReq", bytes), "retcode"));
    assertEquals(0, actor.saves);
  }

  @Test void oldPlayerSelectionIsReadWithoutCreatingAMixOrSaving() throws Exception {
    assertEquals(selection(session, 210000, false, false), current(session));
    assertEquals(0, actor.saves);
    assertNull(actor.getGameplay().getJukebox());
  }

  @Test void ambientOnlyWithoutABgmMessageIsValid() throws Exception {
    var mix = selection(session, 0, false, true, 215003, 42);
    var withoutBgm = mix.toBuilder().clearField(mix.getDescriptorForType().findFieldByNumber(1)).build();
    assertEquals(0, number(save(session, withoutBgm), "retcode"));
    assertEquals(mix, current(session));
  }

  @Test void twoRealMorphiaReloadsKeepOrderVolumesAndIndependentPauseFlags() throws Exception {
    var mix = selection(session, 210001, true, true, 215005, 0, 215003, 100, 215004, 73);
    assertEquals(0, number(save(session, mix), "retcode"));
    var server = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client = com.mongodb.client.MongoClients.create(
        "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "jukebox_save");
      store.getMapper().map(Player.class);
      var base = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.set(base, 10001);
      base.getGameplay().setJukebox(actor.getGameplay().getJukebox());
      store.save(base);
      for (int iteration = 0; iteration < 2; iteration++) {
        var restored = store.find(Player.class).first();
        assertNotNull(restored);
        assertEquals(mix, current(new Session(restored)));
        store.save(restored);
      }
    } finally { server.shutdownNow(); }
  }
}
