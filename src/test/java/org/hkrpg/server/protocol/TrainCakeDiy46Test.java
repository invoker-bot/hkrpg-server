package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Only collected cats may be arranged; failed or repeated saves do not change progress. */
class TrainCakeDiy46Test {
  @TempDir Path root;
  final TrainCakeCatch46Test fixture = new TrainCakeCatch46Test();
  @BeforeEach void setup() throws Exception { fixture.resources = root; fixture.resources(); }
  @AfterEach void cleanup() { fixture.restoreResources(); }

  static DynamicMessage.Builder layout(emu.lunarcore.server.game.GameSession session, int frameCat, int groundCat) {
    var frame = builder(session, "HJACHMJFEAH"); set(frame, "PCPDHEIEJFO", frameCat); set(frame, "slot", 1);
    var ground = builder(session, "IIONNFELFEF"); set(ground, "dice_slot_id", 1); set(ground, "pose", 0);
    add(ground, "DMIOBEKLDED", groundCat);
    var layout = builder(session, "BFAKMCIJFCB"); add(layout, "PNHLAAMNGFE", frame.build());
    add(layout, "PKMHMGAEAGO", ground.build());
    var req = builder(session, "TrainCakeCatchDiyCsReq"); set(req, "NLJBJMDPBGN", layout.build());
    return req;
  }

  DynamicMessage save(TrainCakeCatch46Test.Session session, DynamicMessage.Builder req) throws Exception {
    return TrainCakeCatch46Test.request(session, "TrainCakeCatchDiyCsReq", req.build().toByteArray(), "TrainCakeCatchDiyScRsp");
  }

  @Test void collectedCatsCanBeArrangedAndReadBackWithoutChangingCollection() throws Exception {
    var s = fixture.room(true); fixture.open(s); fixture.search(s, 10); fixture.search(s, 21);
    int saves = s.actor.saves;
    var req = layout(s, 10, 21);
    var rsp = save(s, req);
    assertEquals(0, number(rsp, "retcode"));
    var expected = (DynamicMessage) value(req.build(), "NLJBJMDPBGN");
    assertEquals(expected, value(rsp, "NLJBJMDPBGN"));
    assertEquals(saves + 1, s.actor.saves);
    assertEquals(0, number(save(s, req), "retcode"));
    assertEquals(saves + 1, s.actor.saves, "Retrying the same layout must not save twice");
    var profile = TrainCakeCatch46Test.request(s, "SocialPlayGetDataCsReq", new byte[0], "SocialPlayGetDataScRsp");
    assertEquals(expected, value((DynamicMessage) value(profile, "ELDIONMAAHO"), "NLJBJMDPBGN"));
    assertEquals(java.util.Map.of(10, 1, 21, 1), s.actor.getGameplay().getTrainCakeCatch().getCounts());
  }

  @Test void unownedOrRepeatedCatsAndSaveFailuresCannotChangeLayout() throws Exception {
    var s = fixture.room(true); fixture.open(s); fixture.search(s, 10); fixture.search(s, 21);
    var good = layout(s, 10, 21); assertEquals(0, number(save(s, good), "retcode"));
    int before = s.actor.saves;
    assertNotEquals(0, number(save(s, layout(s, 10, 26)), "retcode"));
    assertNotEquals(0, number(save(s, layout(s, 10, 10)), "retcode"));
    assertEquals(before, s.actor.saves);
    s.actor.failSave = true;
    assertNotEquals(0, number(save(s, layout(s, 21, 10)), "retcode"));
    s.actor.failSave = false;
    var profile = TrainCakeCatch46Test.request(s, "SocialPlayGetDataCsReq", new byte[0], "SocialPlayGetDataScRsp");
    assertEquals(value(good.build(), "NLJBJMDPBGN"), value((DynamicMessage) value(profile, "ELDIONMAAHO"), "NLJBJMDPBGN"));
  }

  @Test void emptySlotsCanBeSavedWithoutGrantingCats() throws Exception {
    var s = fixture.room(true); fixture.open(s);
    assertEquals(0, number(save(s, layout(s, 0, 0)), "retcode"));
    assertTrue(s.actor.getGameplay().getTrainCakeCatch().getCounts().isEmpty());
  }

  @Test void stackedPosesRequireThreeOrSixCollectedCopies() throws Exception {
    var s = fixture.room(true); fixture.open(s); fixture.search(s, 10); fixture.search(s, 21);
    var req = layout(s, 21, 10);
    var data = (DynamicMessage) value(req.build(), "NLJBJMDPBGN");
    var ground = ((DynamicMessage) ((java.util.List<?>) value(data, "PKMHMGAEAGO")).get(0)).toBuilder();
    set(ground, "pose", 1);
    var layout = data.toBuilder();
    layout.setRepeatedField(layout.getDescriptorForType().findFieldByName("PKMHMGAEAGO"), 0, ground.build());
    set(req, "NLJBJMDPBGN", layout.build());
    assertNotEquals(0, number(save(s, req), "retcode"));
    var own = s.actor.getGameplay().getTrainCakeCatch(); long now = org.hkrpg.server.gameplay.TrainCakeCatch.now();
    for (int uid = 20001; uid <= 20005; uid++) {
      var room = new org.hkrpg.server.gameplay.TrainCakeRoomBatch(uid, own.getSource(), own.getRefreshAt(), own.getSpots());
      assertEquals(emu.lunarcore.server.packet.Retcode.SUCC,
          org.hkrpg.server.gameplay.TrainCakeVisits.capture(s.actor, room, 10, now));
      if (uid == 20002) assertEquals(0, number(save(s, req), "retcode"));
    }
    set(ground, "pose", 2);
    layout.setRepeatedField(layout.getDescriptorForType().findFieldByName("PKMHMGAEAGO"), 0, ground.build());
    set(req, "NLJBJMDPBGN", layout.build());
    assertEquals(0, number(save(s, req), "retcode"));
  }

  @Test void theEntireArrangementCannotContainMoreThanThirteenCats() throws Exception {
    var s = fixture.room(true); fixture.open(s);
    for (int cat : java.util.List.of(10, 21, 26)) fixture.search(s, cat);
    var own = s.actor.getGameplay().getTrainCakeCatch(); long now = org.hkrpg.server.gameplay.TrainCakeCatch.now();
    for (int uid = 20001; uid <= 20002; uid++) {
      var room = new org.hkrpg.server.gameplay.TrainCakeRoomBatch(uid, own.getSource(), own.getRefreshAt(), own.getSpots());
      for (int cat : java.util.List.of(10, 21, 26))
        assertEquals(emu.lunarcore.server.packet.Retcode.SUCC,
            org.hkrpg.server.gameplay.TrainCakeVisits.capture(s.actor, room, cat, now));
    }
    long next = own.getRefreshAt();
    var nextRoom = new org.hkrpg.server.gameplay.TrainCakeRoomBatch(20003, own.getSource(),
        org.hkrpg.server.gameplay.TrainCakeCatch.nextRefresh(next), own.getSpots());
    for (int cat : java.util.List.of(10, 21, 26))
      assertEquals(emu.lunarcore.server.packet.Retcode.SUCC,
          org.hkrpg.server.gameplay.TrainCakeVisits.capture(s.actor, nextRoom, cat, next));
    var room = new org.hkrpg.server.gameplay.TrainCakeRoomBatch(20004, own.getSource(),
        org.hkrpg.server.gameplay.TrainCakeCatch.nextRefresh(next), own.getSpots());
    for (int cat : java.util.List.of(10, 21))
      assertEquals(emu.lunarcore.server.packet.Retcode.SUCC,
          org.hkrpg.server.gameplay.TrainCakeVisits.capture(s.actor, room, cat, next));
    var ground = builder(s, "IIONNFELFEF"); set(ground, "dice_slot_id", 1);
    for (int i = 0; i < 5; i++) add(ground, "DMIOBEKLDED", 10);
    for (int i = 0; i < 4; i++) add(ground, "DMIOBEKLDED", 21);
    for (int i = 0; i < 4; i++) add(ground, "DMIOBEKLDED", 26);
    var frame = builder(s, "HJACHMJFEAH"); set(frame, "slot", 1); set(frame, "PCPDHEIEJFO", 21);
    var layout = builder(s, "BFAKMCIJFCB"); add(layout, "PKMHMGAEAGO", ground.build());
    add(layout, "PNHLAAMNGFE", frame.build());
    var request = builder(s, "TrainCakeCatchDiyCsReq"); set(request, "NLJBJMDPBGN", layout.build());
    assertNotEquals(0, number(save(s, request), "retcode"));
  }

  @Test void anArrangementAndItsInventorySurviveH2Restart() throws Exception {
    var s = fixture.room(true); fixture.open(s); fixture.search(s, 10); fixture.search(s, 21);
    var req = layout(s, 10, 21); assertEquals(0, number(save(s, req), "retcode"));
    var path = root.resolve("layout.mv");
    var first = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.h2.H2Backend(path.toString()));
    var address = first.bind();
    try (var client = com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "layout-test");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var p = new emu.lunarcore.game.player.Player();
      var uid = p.getClass().getDeclaredField("uid"); uid.setAccessible(true); uid.setInt(p, 10001);
      var gameplay = p.getClass().getDeclaredField("gameplay"); gameplay.setAccessible(true);
      gameplay.set(p, fixture.gson.fromJson(fixture.gson.toJson(s.actor.getGameplay()), org.hkrpg.server.gameplay.GameplayState.class));
      store.save(p);
    } finally { first.shutdownNow(); }
    var second = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.h2.H2Backend(path.toString()));
    address = second.bind();
    try (var client = com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "layout-test");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var restored = store.find(emu.lunarcore.game.player.Player.class).first();
      assertEquals(value(req.build(), "NLJBJMDPBGN"), TrainCakeDiy46.toProto(s, restored));
    } finally { second.shutdownNow(); }
  }
}
