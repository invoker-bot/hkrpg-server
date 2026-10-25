package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.*;
import java.util.*;
import org.hkrpg.server.gameplay.GameplayState;
import org.junit.jupiter.api.Test;

class ServerPrefs46Test {
  static class Session extends Gameplay46HandlerTest.Session {
    int saves;
    boolean failSave;
    final Player actor = new Player() {
      public void save() {
        if (failSave) throw new IllegalStateException("injected save failure");
        saves++;
      }
    };
    public Player getPlayer() { return actor; }
  }

  static byte[] update(Session s, int id, ByteString data) {
    var prefs = builder(s, "ServerPrefs");
    set(prefs, "server_prefs_id", id);
    set(prefs, "data", data);
    var request = builder(s, "UpdateServerPrefsCsReq");
    set(request, "server_prefs", prefs.build());
    return request.build().toByteArray();
  }

  static DynamicMessage call(Session s, int command, byte[] body) throws Exception {
    s.response = null;
    assertTrue(VersionedPacketHandler.handle(s, command, body), "Native prefs request must be handled");
    assertNotNull(s.response, "A prefs request must not leave the UI waiting");
    return s.response;
  }

  static DynamicMessage put(Session s, int id, String data) throws Exception {
    return call(s, 6160, update(s, id, ByteString.copyFromUtf8(data)));
  }

  static Map<Integer, ByteString> all(Session s) throws Exception {
    var rsp = call(s, 6134, new byte[0]);
    assertEquals(0, number(rsp, "retcode"));
    var result = new TreeMap<Integer, ByteString>();
    for (Object raw : (List<?>) value(rsp, "server_prefs_list")) {
      var row = (DynamicMessage) raw;
      assertNull(result.put(number(row, "server_prefs_id"), (ByteString) value(row, "data")));
    }
    return result;
  }

  @Test void freshQueryIsEmptyAndDoesNotMarkAnyHintAsSeen() throws Exception {
    var s = new Session();
    var before = new Gson().toJson(s.actor.getGameplay());
    assertEquals(Map.of(), all(s));
    assertEquals(before, new Gson().toJson(s.actor.getGameplay()));
    assertEquals(0, s.saves);
  }

  @Test void actualAcknowledgementsSurviveSessionAndPlayerReloadWithoutFabricatingProgress() throws Exception {
    var s = new Session();
    var bytes = ByteString.copyFrom(new byte[]{0, -1, 3, 0, 9});
    var rsp = call(s, 6160, update(s, 2, bytes));
    assertEquals(0, number(rsp, "retcode"));
    assertEquals(2, number(rsp, "server_prefs_id"));
    assertEquals(Map.of(2, bytes), all(s));
    assertTrue(s.actor.getGameplay().getMainMissions().isEmpty());
    assertTrue(s.actor.getGameplay().getSubMissions().isEmpty());
    assertTrue(s.actor.getGameplay().getTutorials().snapshot(false).isEmpty());
    String saved = new Gson().toJson(s.actor.getGameplay());
    var other = new Session();
    assertTrue(all(other).isEmpty(), "Prefs belong to one player");
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(other.actor, new Gson().fromJson(saved, GameplayState.class));
    assertEquals(Map.of(2, bytes), all(other));
    assertEquals(0, other.saves);
  }

  @Test void retriesAreIdempotentAndExplicitEmptyValuesRemainReadable() throws Exception {
    var s = new Session();
    assertEquals(0, number(put(s, 1, "read"), "retcode"));
    assertEquals(0, number(put(s, 1, "read"), "retcode"));
    assertEquals(1, s.saves);
    assertEquals(0, number(put(s, 1, ""), "retcode"));
    assertEquals(Map.of(1, ByteString.EMPTY), all(s));
    assertEquals(2, s.saves);
  }

  @Test void singleQueryReturnsRequestedIdentityAndOnlyItsSavedValue() throws Exception {
    var s = new Session();
    put(s, 1, "one");
    put(s, 2, "two");
    // IANCKAHGKMI field 2 is the requested preference ID, command 6105.
    var rsp = call(s, 6105, new byte[]{16, 2});
    assertEquals(0, number(rsp, "retcode"));
    var row = (DynamicMessage) value(rsp, "server_prefs");
    assertEquals(2, number(row, "server_prefs_id"));
    assertEquals(ByteString.copyFromUtf8("two"), value(row, "data"));
    rsp = call(s, 6105, new byte[]{16, 3});
    row = (DynamicMessage) value(rsp, "server_prefs");
    assertEquals(3, number(row, "server_prefs_id"));
    assertEquals(ByteString.EMPTY, value(row, "data"));
    assertEquals(2, all(s).size());
    assertEquals(2, s.saves);
  }

  @Test void malformedAndOversizedUpdatesHaveAReplyAndCannotErasePreviousPrefs() throws Exception {
    var s = new Session();
    put(s, 1, "keep");
    var valid = update(s, 1, ByteString.copyFromUtf8("replace"));
    var duplicate = new byte[valid.length * 2];
    System.arraycopy(valid, 0, duplicate, 0, valid.length);
    System.arraycopy(valid, 0, duplicate, valid.length, valid.length);
    for (var bytes : List.of(new byte[0], new byte[]{122, 1, -1}, new byte[]{8, 1},
        new byte[]{122, 4, 24, 1, 24, 2}, new byte[]{122, 2, 24, 0}, duplicate,
        update(s, 1, ByteString.copyFrom(new byte[65537])))) {
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), number(call(s, 6160, bytes), "retcode"));
      assertEquals(Map.of(1, ByteString.copyFromUtf8("keep")), all(s));
    }
    assertEquals(1, s.saves);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), number(call(s, 6134, new byte[]{8, 1}), "retcode"));
  }

  @Test void failedSaveDoesNotAcknowledgeOrReplaceTheSavedStateAndRetryWorks() throws Exception {
    var s = new Session();
    put(s, 1, "keep");
    s.failSave = true;
    assertEquals(Retcode.SERVER_INTERNAL_ERROR.getVal(), number(put(s, 1, "new"), "retcode"));
    assertEquals(Retcode.SERVER_INTERNAL_ERROR.getVal(), number(put(s, 2, "two"), "retcode"));
    assertEquals(Map.of(1, ByteString.copyFromUtf8("keep")), all(s));
    s.failSave = false;
    assertEquals(0, number(put(s, 1, "new"), "retcode"));
    assertEquals(Map.of(1, ByteString.copyFromUtf8("new")), all(s));
  }

  @Test void unauthenticatedRequestsCannotReadOrWritePlayerPrefs() throws Exception {
    var s = new Session();
    s.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(VersionedPacketHandler.handle(s, 6160, update(s, 1, ByteString.EMPTY)));
    assertTrue(VersionedPacketHandler.handle(s, 6134, new byte[0]));
    assertNull(s.response);
    assertEquals(0, s.saves);
  }

  @Test void limitsAndDefensiveCopiesPreserveOtherPreferences() throws Exception {
    var s = new Session();
    var state = s.actor.getGameplay().getServerPrefs();
    byte[] bytes = new byte[65536];
    bytes[0] = 7;
    for (int id = 1; id <= 16; id++) assertEquals(Retcode.SUCC, state.update(s.actor, id, bytes));
    bytes[0] = 8;
    state.snapshot().get(1)[0] = 9;
    assertEquals(7, state.snapshot().get(1)[0]);
    assertEquals(Retcode.REQ_PARA_INVALID, state.update(s.actor, 17, new byte[]{1}));
    assertEquals(16, state.snapshot().size());
    assertEquals(Retcode.SUCC, state.update(s.actor, 1, new byte[0]));
    assertEquals(Retcode.SUCC, state.update(s.actor, 17, bytes));
    var small = new Session();
    for (int id = 1; id <= 512; id++)
      assertEquals(Retcode.SUCC, small.actor.getGameplay().getServerPrefs().update(small.actor, id, new byte[0]));
    assertEquals(Retcode.REQ_PARA_INVALID,
        small.actor.getGameplay().getServerPrefs().update(small.actor, 513, new byte[0]));
    assertEquals(512, all(small).size());
  }

  @Test void corruptedSavedPreferencesAreRejectedWithoutSilentlyResettingHistory() throws Exception {
    var s = new Session();
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    for (String invalid : List.of("null", "{\"values\":null}", "{\"values\":{\"0\":[1]}}")) {
      field.set(s.actor, new Gson().fromJson("{\"serverPrefs\":" + invalid + "}", GameplayState.class));
      String before = new Gson().toJson(s.actor.getGameplay());
      assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), number(call(s, 6134, new byte[0]), "retcode"));
      assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), number(put(s, 1, "new"), "retcode"));
      assertEquals(before, new Gson().toJson(s.actor.getGameplay()));
    }
    assertEquals(0, s.saves);
  }

  @Test void bsonRoundTripAndOldPlayerDocumentsRetainExactBytes() throws Exception {
    var server = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client = com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "prefs-persistence");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var s = new Session();
      byte[] bytes = new byte[]{0, -1, 4, 0, -128};
      assertEquals(0, number(call(s, 6160, update(s, 3202, ByteString.copyFrom(bytes))), "retcode"));
      put(s, 2, "");
      var player = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(player, 910001);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(player, s.actor.getGameplay());
      for (int reload = 0; reload < 2; reload++) {
        store.save(player);
        player = store.find(Player.class).first();
        assertNotNull(player);
        var data = player.getGameplay().getServerPrefs().snapshot();
        assertEquals(Set.of(2, 3202), data.keySet());
        assertArrayEquals(bytes, data.get(3202));
        assertArrayEquals(new byte[0], data.get(2));
      }
      // Existing accounts have no new preference field. Loading must not fabricate acknowledgements.
      store.getCollection(Player.class).updateOne(new org.bson.Document("_id", 910001),
          new org.bson.Document("$unset", new org.bson.Document("gameplay.serverPrefs", "")));
      assertTrue(store.find(Player.class).first().getGameplay().getServerPrefs().snapshot().isEmpty());
    } finally {
      server.shutdownNow();
    }
  }
}
