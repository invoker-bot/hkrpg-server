package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.util.Position;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Native entry, guest capture and return, using two independent player documents. */
class TrainCakeRoomWire46Test {
  @TempDir Path root;
  final TrainCakeCatch46Test fixture = new TrainCakeCatch46Test();
  final Map<Integer, Player> players = new HashMap<>();
  final Map<Integer, MapEntranceExcel> oldEntries = new HashMap<>();
  final List<Session> sessions = new ArrayList<>();
  MazePlaneExcel oldPlane;
  MazePlaneExcel plane;

  class Actor extends Player implements TrainCakeRoomStore.Provider {
    final int uid;
    final PlayerLineup lineup = new PlayerLineup(this, 0);
    int saves;
    boolean failSave;
    Actor(int uid) { this.uid = uid; }
    @Override public int getUid() { return uid; }
    @Override public int getLevel() { return 21; }
    @Override public PlayerLineup getCurrentLineup() { return lineup; }
    @Override public TrainCakeRoomStore trainCakeRoomStore() { return fixture.roomStore; }
    @Override public void save() { saves++; if (failSave) throw new IllegalStateException("injected save failure"); }
  }
  class Session extends Gameplay46HandlerTest.Session implements TrainCakeSocial46.Resolver {
    final Actor actor;
    final Map<String, DynamicMessage> replies = new HashMap<>();
    final List<DynamicMessage> roomNotifications = new ArrayList<>();
    Session(Actor actor) {
      this.actor = actor; actor.setSession(this); sessions.add(this);
      try {
        var address = emu.lunarcore.server.game.GameSession.class.getDeclaredField("address");
        address.setAccessible(true); address.set(this, new java.net.InetSocketAddress("127.0.0.1", 9));
      } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    @Override public Player getPlayer() { return actor; }
    public Player trainCakePlayer(int uid) { return players.get(uid); }
    public boolean trainCakeFriends(int visitor, int owner) { return false; }
    @Override public void sendClientPacket(String name, byte[] bytes) {
      super.sendClientPacket(name, bytes); replies.put(name, response);
      if (name.equals("SocialPlayGameplayOperationScNotify")) roomNotifications.add(response);
    }
  }
  static void field(Player player, String name, Object value) throws Exception {
    var f = Player.class.getDeclaredField(name); f.setAccessible(true); f.set(player, value);
  }

  @BeforeEach void setup() throws Exception {
    fixture.resources = root; fixture.resources();
    var unlockPath = root.resolve("ExcelOutput/FuncUnlockData.json");
    var unlocks = JsonParser.parseString(Files.readString(unlockPath)).getAsJsonArray();
    unlocks.add(JsonParser.parseString("{\"UnlockID\":5200,\"Conditions\":[{\"Type\":\"FinishMainMission\",\"Param\":\"4020800\"}],\"ShowCondition\":[]}"));
    Files.writeString(unlockPath, fixture.gson.toJson(unlocks));
    oldPlane = GameData.getMazePlaneExcelMap().get(10000);
    plane = fixture.gson.fromJson("{\"PlaneID\":10000,\"PlaneType\":\"Train\",\"WorldID\":1}", MazePlaneExcel.class);
    GameData.getMazePlaneExcelMap().add(plane);
    var floor = fixture.gson.fromJson("{\"FloorID\":10000003,\"DimensionList\":[{\"ID\":0,\"GroupIndexList\":[0,1,2]},{\"ID\":11001,\"GroupIndexList\":[0,1,2]}],\"GroupInstanceList\":[{\"ID\":3},{\"ID\":232},{\"ID\":240}]}", FloorInfo.class);
    floor.getGroups().put(240, fixture.floor.getGroups().get(240));
    for (int groupId : List.of(3, 232)) {
      var group = fixture.gson.fromJson("{\"GroupName\":\"room anchor\",\"LoadSide\":\"Server\",\"PropList\":[],\"AnchorList\":[{\"ID\":4},{\"ID\":19}]}", GroupInfo.class);
      group.setId(groupId); floor.getGroups().put(groupId, group);
    }
    fixture.floor = floor; GameData.getFloorInfos().put("P10000_F10000003", floor);
    for (int id : List.of(100000302, 100000353, 100000399)) {
      oldEntries.put(id, GameData.getMapEntranceExcelMap().get(id));
      int group = id == 100000353 ? 232 : 3, anchor = id == 100000353 ? 19 : 4;
      GameData.getMapEntranceExcelMap().add(fixture.gson.fromJson("{\"ID\":" + id + ",\"PlaneID\":10000,\"FloorID\":10000003,\"StartGroupID\":" + group + ",\"StartAnchorID\":" + anchor + "}", MapEntranceExcel.class));
    }
  }

  @AfterEach void cleanup() {
    for (var session : sessions) {
      TrainCakeSocial46.registry(session).leave(session);
      session.setState(emu.lunarcore.server.packet.SessionState.INACTIVE);
    }
    for (var entry : oldEntries.entrySet()) {
      if (entry.getValue() == null) GameData.getMapEntranceExcelMap().keySet().remove(entry.getKey());
      else GameData.getMapEntranceExcelMap().add(entry.getValue());
    }
    if (oldPlane == null) GameData.getMazePlaneExcelMap().keySet().remove(10000);
    else GameData.getMazePlaneExcelMap().add(oldPlane);
    fixture.restoreResources();
  }

  Actor actor(int uid) throws Exception {
    var p = new Actor(uid); players.put(uid, p);
    field(p, "planeId", 10000); field(p, "floorId", 10000003); field(p, "entryId", 100000302);
    field(p, "worldId", 1); field(p, "pos", new Position(100, 200, 300)); field(p, "rot", new Position(0, 123, 0));
    var scene = new Scene(p, plane, 10000003); scene.setEntryId(100000302); field(p, "scene", scene);
    var gate = new QuestState(402080005); gate.advance(1, 1, 1); p.getGameplay().getSubMissions().put(402080005, gate);
    var main = new QuestState(4020800); main.advance(1, 1, 1); p.getGameplay().getMainMissions().put(4020800, main);
    return p;
  }

  DynamicMessage call(Session s, String request, DynamicMessage.Builder body, String response) throws Exception {
    s.replies.remove(response);
    assertTrue(VersionedPacketHandler.handle(s, s.getProtocol().clientCommand(request), body.build().toByteArray()));
    assertNotNull(s.replies.get(response), "Missing native response " + response);
    return s.replies.get(response);
  }

  void enter(Session session, int ownerUid) throws Exception {
    boolean self = session.actor.uid == ownerUid;
    int entry = self ? 100000399 : 100000353;
    var check = builder(session, "SocialPlayPreCheckEnterRoomCsReq");
    set(check, "entry_id", entry); set(check, "teleport_id", ownerUid);
    assertEquals(0, number(call(session, "SocialPlayPreCheckEnterRoomCsReq", check,
        "SocialPlayPreCheckEnterRoomScRsp"), "retcode"));
    var tp = builder(session, "TeleportInfo"); set(tp, "teleport_id", ownerUid); set(tp, "reason", 2);
    var id = builder(session, "SceneIdentifier"); set(id, "floor_id", 10000003); set(id, "teleport_info", tp.build());
    var request = builder(session, "EnterSceneCsReq"); set(request, "entry_id", entry); set(request, "scene_identifier", id.build());
    assertEquals(0, number(call(session, "EnterSceneCsReq", request, "EnterSceneScRsp"), "retcode"));
    assertEquals(11001, session.actor.getScene().getDimensionId());
  }

  @Test void anOfflineOwnersRoomKeepsGuestAndOwnerCapturesIndependent() throws Exception {
    var owner = actor(20001); var guest = actor(10001); var s = new Session(guest);
    assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(owner, new TrainCakeSocialSettings(true, true, true)));
    int ownerSaves = owner.saves;
    enter(s, owner.uid);
    var room = call(s, "SocialPlayGetOnlineRoomDataCsReq", builder(s, "SocialPlayGetOnlineRoomDataCsReq"), "SocialPlayGetOnlineRoomDataScRsp");
    assertEquals(0, number(room, "retcode"));
    assertEquals(true, value(room, "OJPDDAOJJKC"), "The native callback must see that the room exists");
    var snapshot = (DynamicMessage) value(room, "DJMHHMMABJL");
    assertEquals(owner.uid, number(snapshot, "room_owner_uid"));
    assertEquals(1, ((List<?>) value(snapshot, "player_info")).size(), "An offline owner is not an online room member");
    var opened = call(s, "TrainCakeCatchOpenBoxCsReq", builder(s, "TrainCakeCatchOpenBoxCsReq"), "TrainCakeCatchOpenBoxScRsp");
    assertEquals(0, number(opened, "retcode"));
    assertEquals(3, ((List<?>) value(opened, "BJPHDKEBOBK")).size());
    var search = builder(s, "TrainCakeCatchSearchCsReq"); set(search, "PCPDHEIEJFO", 10);
    var result = call(s, "TrainCakeCatchSearchCsReq", search, "TrainCakeCatchSearchScRsp");
    assertEquals(0, number(result, "retcode"));
    assertEquals(1, number((DynamicMessage) value(result, "NIELDCLPOLA"), "count"));
    assertEquals(8, TrainCakeCatch.weeklyRemaining(guest, TrainCakeCatch.now()));
    assertEquals(Map.of(20001, Set.of(10)), TrainCakeVisits.records(guest, TrainCakeCatch.now()));
    assertTrue(owner.getGameplay().getTrainCakeCatch().getCounts().isEmpty());
    assertEquals(ownerSaves, owner.saves, "A visitor cannot save an offline owner's stale document");
    assertEquals(Retcode.SUCC, TrainCakeCatch.open(owner, TrainCakeCatch.now()).retcode());
    assertEquals(Retcode.SUCC, TrainCakeCatch.search(owner, 10, TrainCakeCatch.now()).retcode());
    assertEquals(Map.of(10, 1), owner.getGameplay().getTrainCakeCatch().getCounts());
    assertEquals(Map.of(10, 1), TrainCakeVisits.counts(guest));
    var leave = builder(s, "EnterSceneCsReq"); set(leave, "entry_id", 100000302);
    assertEquals(0, number(call(s, "EnterSceneCsReq", leave, "EnterSceneScRsp"), "retcode"));
    assertEquals(0, guest.getScene().getDimensionId());
    assertEquals(new Position(100, 200, 300), guest.getPos());
    assertFalse(TrainCakeRoomTravel.hasPending(guest));
  }

  @Test void aVisitorSeesAndCanCatchTheOwnersLegacyZeroSlotAtARealTail() throws Exception {
    var owner = actor(20001); var guest = actor(10001); var session = new Session(guest);
    assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(owner, new TrainCakeSocialSettings(true, true, true)));
    assertEquals(Retcode.SUCC, TrainCakeCatch.open(owner, TrainCakeCatch.now()).retcode());
    var json = JsonParser.parseString(fixture.gson.toJson(owner.getGameplay())).getAsJsonObject();
    json.getAsJsonObject("trainCakeCatch").getAsJsonArray("spots").get(0).getAsJsonObject().addProperty("slot", 0);
    field(owner, "gameplay", fixture.gson.fromJson(json, GameplayState.class));
    var rooms = fixture.roomClient.getDatabase("train-cake-fixture").getCollection("train_cake_rooms");
    var savedRoom = rooms.find(com.mongodb.client.model.Filters.eq("_id", owner.uid)).first();
    savedRoom.getList("spots", org.bson.Document.class).getFirst().put("slot", 0);
    rooms.replaceOne(com.mongodb.client.model.Filters.eq("_id", owner.uid), savedRoom);
    String oldOwner = fixture.gson.toJson(owner.getGameplay()), oldRoom = savedRoom.toJson();
    enter(session, owner.uid);
    var response = call(session, "SocialPlayGetOnlineRoomDataCsReq", builder(session, "SocialPlayGetOnlineRoomDataCsReq"),
        "SocialPlayGetOnlineRoomDataScRsp");
    var snapshot = (DynamicMessage) value(response, "DJMHHMMABJL");
    var outer = (DynamicMessage) value(snapshot, "LNMGOFCOMED");
    var box = (DynamicMessage) value(outer, "PKHFMJDNKOE");
    var opened = call(session, "TrainCakeCatchOpenBoxCsReq", builder(session, "TrainCakeCatchOpenBoxCsReq"),
        "TrainCakeCatchOpenBoxScRsp");
    for (var data : List.of(box, opened)) {
      var spots = new TreeMap<Integer, Integer>();
      for (var raw : (List<?>) value(data, "BJPHDKEBOBK")) {
        var row = (DynamicMessage) raw;
        spots.put(number(row, "PCPDHEIEJFO"), number(row, "slot"));
      }
      assertEquals(Map.of(10, 7, 21, 6, 26, 5), spots);
    }
    assertEquals(oldOwner, fixture.gson.toJson(owner.getGameplay()));
    assertEquals(oldRoom, rooms.find(com.mongodb.client.model.Filters.eq("_id", owner.uid)).first().toJson());
    var search = builder(session, "TrainCakeCatchSearchCsReq"); set(search, "PCPDHEIEJFO", 10);
    assertEquals(0, number(call(session, "TrainCakeCatchSearchCsReq", search, "TrainCakeCatchSearchScRsp"), "retcode"));
    assertEquals(Map.of(10, 1), TrainCakeVisits.counts(guest));
  }

  @Test void twoVisitorsCanCaptureTheSameCatAndSeeOneAnotherJoinAndDisconnect() throws Exception {
    var owner = actor(20001); var a = actor(10001); var b = actor(10002);
    var first = new Session(a); var second = new Session(b);
    assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(owner, new TrainCakeSocialSettings(true, true, true)));
    enter(first, owner.uid);
    assertEquals(0, number(call(first, "TrainCakeCatchOpenBoxCsReq", builder(first, "TrainCakeCatchOpenBoxCsReq"),
        "TrainCakeCatchOpenBoxScRsp"), "retcode"));
    enter(second, owner.uid);
    assertTrue(first.roomNotifications.stream().anyMatch(message -> {
      var field = message.getDescriptorForType().findFieldByName("GNLAECODENP");
      return message.hasField(field) && number((DynamicMessage) message.getField(field), "uid") == b.uid;
    }), "Existing members need the native player-joined event as well as the room snapshot");
    var notify = first.replies.get("SocialPlayGameplayOperationScNotify");
    var snapshot = (DynamicMessage) value(notify, "LABNNEGJNHO");
    assertEquals(2, ((List<?>) value(snapshot, "player_info")).size());
    for (var session : List.of(first, second)) {
      var search = builder(session, "TrainCakeCatchSearchCsReq"); set(search, "PCPDHEIEJFO", 10);
      var result = call(session, "TrainCakeCatchSearchCsReq", search, "TrainCakeCatchSearchScRsp");
      assertEquals(0, number(result, "retcode"));
      assertEquals(1, number((DynamicMessage) value(result, "NIELDCLPOLA"), "count"));
      var records = (List<?>) value(result, "CEMFBJPOLNP");
      assertEquals(1, records.size());
      var record = (DynamicMessage) records.getFirst();
      assertEquals(10, number(record, "PCPDHEIEJFO"));
      assertEquals(List.of(owner.uid), value(record, "LPDFMJPFGJH"));
      assertEquals(8, TrainCakeCatch.weeklyRemaining(session.actor, TrainCakeCatch.now()));
    }
    assertEquals(Map.of(10, 1), TrainCakeVisits.counts(a));
    assertEquals(Map.of(10, 1), TrainCakeVisits.counts(b));
    assertTrue(owner.getGameplay().getTrainCakeCatch().getCounts().isEmpty());
    second.onDisconnect();
    notify = first.replies.get("SocialPlayGameplayOperationScNotify");
    snapshot = (DynamicMessage) value(notify, "LABNNEGJNHO");
    assertEquals(1, ((List<?>) value(snapshot, "player_info")).size(), "Disconnect must reconcile other members");
  }

  @Test void anOwnerCanCatchFromTheBatchOpenedEarlierByAVisitorWithoutRedrawing() throws Exception {
    var owner = actor(20001); var guest = actor(10001);
    var gs = new Session(guest); var os = new Session(owner);
    assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(owner, new TrainCakeSocialSettings(true, true, true)));
    enter(gs, owner.uid);
    assertEquals(0, number(call(gs, "TrainCakeCatchOpenBoxCsReq", builder(gs, "TrainCakeCatchOpenBoxCsReq"),
        "TrainCakeCatchOpenBoxScRsp"), "retcode"));
    enter(os, owner.uid);
    var search = builder(os, "TrainCakeCatchSearchCsReq"); set(search, "PCPDHEIEJFO", 10);
    assertEquals(0, number(call(os, "TrainCakeCatchSearchCsReq", search, "TrainCakeCatchSearchScRsp"), "retcode"));
    assertEquals(Map.of(10, 1), owner.getGameplay().getTrainCakeCatch().getCounts());
    assertTrue(TrainCakeVisits.counts(guest).isEmpty());
  }

  @Test void anOwnerOpeningInTheOrdinaryTrainUpdatesVisitorsInTheRoom() throws Exception {
    var owner = actor(20001); var guest = actor(10001);
    var gs = new Session(guest); var os = new Session(owner);
    assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(owner, new TrainCakeSocialSettings(true, true, true)));
    enter(gs, owner.uid);
    gs.replies.remove("SocialPlayGameplayOperationScNotify");
    var open = call(os, "TrainCakeCatchOpenBoxCsReq", builder(os, "TrainCakeCatchOpenBoxCsReq"),
        "TrainCakeCatchOpenBoxScRsp");
    assertEquals(0, number(open, "retcode"));
    var notify = gs.replies.get("SocialPlayGameplayOperationScNotify");
    assertNotNull(notify, "Opening the owner's box must update active visitors");
    var snapshot = (DynamicMessage) value(notify, "LABNNEGJNHO");
    var data = (DynamicMessage) value(snapshot, "LNMGOFCOMED");
    var box = (DynamicMessage) value(data, "PKHFMJDNKOE");
    assertEquals(3, ((List<?>) value(box, "BJPHDKEBOBK")).size());
  }

  @Test void aVisitorSeesTheOwnersArrangementButCannotRewriteIt() throws Exception {
    var owner = actor(20001); var guest = actor(10001);
    var gs = new Session(guest); var os = new Session(owner);
    assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(owner, new TrainCakeSocialSettings(true, true, true)));
    assertEquals(0, number(call(os, "TrainCakeCatchOpenBoxCsReq", builder(os, "TrainCakeCatchOpenBoxCsReq"),
        "TrainCakeCatchOpenBoxScRsp"), "retcode"));
    for (int cat : List.of(10, 21)) {
      var search = builder(os, "TrainCakeCatchSearchCsReq"); set(search, "PCPDHEIEJFO", cat);
      assertEquals(0, number(call(os, "TrainCakeCatchSearchCsReq", search, "TrainCakeCatchSearchScRsp"), "retcode"));
    }
    enter(gs, owner.uid);
    var req = TrainCakeDiy46Test.layout(os, 10, 21);
    assertEquals(0, number(call(os, "TrainCakeCatchDiyCsReq", req, "TrainCakeCatchDiyScRsp"), "retcode"));
    var notify = gs.replies.get("SocialPlayGameplayOperationScNotify"); assertNotNull(notify);
    var snapshot = (DynamicMessage) value(notify, "LABNNEGJNHO");
    var box = (DynamicMessage) value((DynamicMessage) value(snapshot, "LNMGOFCOMED"), "PKHFMJDNKOE");
    assertEquals(value(req.build(), "NLJBJMDPBGN"), value(box, "NLJBJMDPBGN"));
    assertNotEquals(0, number(call(gs, "TrainCakeCatchDiyCsReq", TrainCakeDiy46Test.layout(gs, 10, 21),
        "TrainCakeCatchDiyScRsp"), "retcode"));
  }

  @Test void ownersCanDenyOpeningAndRevokeAccessAfterEntry() throws Exception {
    var owner = actor(20001); var guest = actor(10001); var s = new Session(guest);
    assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(owner, new TrainCakeSocialSettings(true, true, false)));
    enter(s, owner.uid);
    var open = builder(s, "TrainCakeCatchOpenBoxCsReq");
    assertNotEquals(0, number(call(s, "TrainCakeCatchOpenBoxCsReq", open, "TrainCakeCatchOpenBoxScRsp"), "retcode"));
    assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(owner, new TrainCakeSocialSettings(true, true, true)));
    assertEquals(0, number(call(s, "TrainCakeCatchOpenBoxCsReq", open, "TrainCakeCatchOpenBoxScRsp"), "retcode"));
    assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(owner, new TrainCakeSocialSettings(true, false, false)));
    var search = builder(s, "TrainCakeCatchSearchCsReq"); set(search, "PCPDHEIEJFO", 10);
    assertNotEquals(0, number(call(s, "TrainCakeCatchSearchCsReq", search, "TrainCakeCatchSearchScRsp"), "retcode"));
    assertTrue(TrainCakeVisits.counts(guest).isEmpty());
  }

  @Test void switchingRoomsKeepsTheOriginalReturnPointAndDoesNotMixMembers() throws Exception {
    var firstOwner = actor(20001); var secondOwner = actor(20002); var guest = actor(10001);
    var s = new Session(guest);
    for (var owner : List.of(firstOwner, secondOwner))
      assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(owner, new TrainCakeSocialSettings(true, true, true)));
    enter(s, firstOwner.uid);
    var firstScene = guest.getScene();
    enter(s, secondOwner.uid);
    assertNotSame(firstScene, guest.getScene(), "Different owners need separate room scenes");
    assertEquals(secondOwner.uid, TrainCakeSocial46.registry(s).current(s).ownerUid());
    assertNull(TrainCakeSocial46.registry(s).members(firstOwner.uid));
    var leave = builder(s, "EnterSceneCsReq"); set(leave, "entry_id", 100000302);
    assertEquals(0, number(call(s, "EnterSceneCsReq", leave, "EnterSceneScRsp"), "retcode"));
    assertEquals(new Position(100, 200, 300), guest.getPos());
    assertFalse(TrainCakeRoomTravel.hasPending(guest));
  }

  @Test void aFullDestinationDoesNotRemoveTheVisitorFromTheCurrentRoom() throws Exception {
    var firstOwner = actor(20001); var fullOwner = actor(20002); var guest = actor(10001);
    for (var owner : List.of(firstOwner, fullOwner))
      assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(owner, new TrainCakeSocialSettings(true, true, true)));
    var s = new Session(guest); enter(s, firstOwner.uid);
    for (int uid = 10002; uid <= 10005; uid++) enter(new Session(actor(uid)), fullOwner.uid);
    var original = guest.getScene();
    var check = builder(s, "SocialPlayPreCheckEnterRoomCsReq");
    set(check, "entry_id", 100000353); set(check, "teleport_id", fullOwner.uid);
    assertEquals(0, number(call(s, "SocialPlayPreCheckEnterRoomCsReq", check, "SocialPlayPreCheckEnterRoomScRsp"), "retcode"));
    var tp = builder(s, "TeleportInfo"); set(tp, "teleport_id", fullOwner.uid); set(tp, "reason", 2);
    var id = builder(s, "SceneIdentifier"); set(id, "floor_id", 10000003); set(id, "teleport_info", tp.build());
    var request = builder(s, "EnterSceneCsReq"); set(request, "entry_id", 100000353); set(request, "scene_identifier", id.build());
    assertNotEquals(0, number(call(s, "EnterSceneCsReq", request, "EnterSceneScRsp"), "retcode"));
    assertSame(original, guest.getScene());
    assertEquals(firstOwner.uid, TrainCakeSocial46.registry(s).current(s).ownerUid());
    assertTrue(TrainCakeRoomTravel.hasPending(guest));
  }

  @Test void aFailedSwitchSaveRetainsTheOriginalRoomAndReturnBookmark() throws Exception {
    var guest = actor(10001); var otherOwner = actor(20002); var s = new Session(guest);
    assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(otherOwner, new TrainCakeSocialSettings(true, true, true)));
    enter(s, guest.uid);
    var original = guest.getScene();
    var check = builder(s, "SocialPlayPreCheckEnterRoomCsReq");
    set(check, "entry_id", 100000353); set(check, "teleport_id", otherOwner.uid);
    assertEquals(0, number(call(s, "SocialPlayPreCheckEnterRoomCsReq", check, "SocialPlayPreCheckEnterRoomScRsp"), "retcode"));
    var tp = builder(s, "TeleportInfo"); set(tp, "teleport_id", otherOwner.uid); set(tp, "reason", 2);
    var id = builder(s, "SceneIdentifier"); set(id, "floor_id", 10000003); set(id, "teleport_info", tp.build());
    var request = builder(s, "EnterSceneCsReq"); set(request, "entry_id", 100000353); set(request, "scene_identifier", id.build());
    guest.failSave = true;
    assertNotEquals(0, number(call(s, "EnterSceneCsReq", request, "EnterSceneScRsp"), "retcode"));
    guest.failSave = false;
    assertSame(original, guest.getScene());
    assertEquals(guest.uid, TrainCakeSocial46.registry(s).current(s).ownerUid());
    assertTrue(TrainCakeRoomTravel.hasPending(guest));
  }

  @Test void revokedVisitorsCannotReceiveOrSendRoomUpdates() throws Exception {
    var owner = actor(20001); var a = actor(10001); var b = actor(10002);
    var first = new Session(a); var second = new Session(b);
    assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(owner, new TrainCakeSocialSettings(true, true, true)));
    enter(first, owner.uid); enter(second, owner.uid);
    assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(owner, new TrainCakeSocialSettings(true, false, false)));
    var position = builder(first, "Vector"); set(position, "x", 111);
    var motion = builder(first, "JOLAHHGIFHA"); set(motion, "pos", position.build());
    var movement = builder(first, "JGAMELLGLGE"); set(movement, "NJDCPMENIFG", motion.build());
    assertNotEquals(0, number(call(first, "JGAMELLGLGE", movement, "JKILAALMFIB"), "retcode"));
    assertNull(second.replies.get("SocialPlayRoomPlayerMoveScNotify"));
    var online = call(first, "SocialPlayGetOnlineRoomDataCsReq", builder(first, "SocialPlayGetOnlineRoomDataCsReq"),
        "SocialPlayGetOnlineRoomDataScRsp");
    assertNotEquals(0, number(online, "retcode"));
  }

  @Test void aRoomCleanupFailureCannotPreventTheNormalSessionDisconnect() throws Exception {
    var actor = actor(10001);
    var broken = new java.util.concurrent.atomic.AtomicBoolean();
    var session = new Session(actor) {
      @Override public emu.lunarcore.server.game.GameServer getServer() {
        if (broken.get()) throw new IllegalStateException("injected room registry failure");
        return super.getServer();
      }
    };
    broken.set(true);
    try {
      assertDoesNotThrow(session::onDisconnect);
      assertEquals(emu.lunarcore.server.packet.SessionState.INACTIVE, session.getState());
    } finally { broken.set(false); }
  }

  @Test void roomMovementIsAcknowledgedAndSentOnlyToOtherCurrentMembers() throws Exception {
    var owner = actor(20001); var a = actor(10001); var b = actor(10002); var outside = actor(10003);
    var first = new Session(a); var second = new Session(b); var third = new Session(outside);
    assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(owner, new TrainCakeSocialSettings(true, true, true)));
    enter(first, owner.uid); enter(second, owner.uid);
    var position = builder(first, "Vector"); set(position, "x", 111); set(position, "y", 222); set(position, "z", 333);
    var motion = builder(first, "JOLAHHGIFHA"); set(motion, "pos", position.build());
    var movement = builder(first, "JGAMELLGLGE"); set(movement, "NJDCPMENIFG", motion.build());
    var ack = call(first, "JGAMELLGLGE", movement, "JKILAALMFIB");
    assertEquals(0, number(ack, "retcode"));
    var notify = second.replies.get("SocialPlayRoomPlayerMoveScNotify"); assertNotNull(notify);
    assertEquals(owner.uid, number(notify, "room_owner_uid")); assertEquals(a.uid, number(notify, "op_uid"));
    var history = (DynamicMessage) value(notify, "JENKGODEIOM");
    var received = (DynamicMessage) ((List<?>) value(history, "JENKGODEIOM")).get(0);
    var point = (DynamicMessage) value(received, "pos");
    assertEquals(111, number(point, "x")); assertEquals(222, number(point, "y")); assertEquals(333, number(point, "z"));
    assertNull(third.replies.get("SocialPlayRoomPlayerMoveScNotify"));
    assertNull(first.replies.get("SocialPlayRoomPlayerMoveScNotify"));
  }

  @Test void repeatedMovementDoesNotReloadAnOfflineOwnerForEveryPacket() throws Exception {
    var owner = actor(20001); var guest = actor(10001);
    var lookups = new java.util.concurrent.atomic.AtomicInteger();
    var s = new Session(guest) {
      @Override public Player trainCakePlayer(int uid) { lookups.incrementAndGet(); return super.trainCakePlayer(uid); }
    };
    assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(owner, new TrainCakeSocialSettings(true, true, true)));
    enter(s, owner.uid); lookups.set(0);
    var point = builder(s, "Vector"); set(point, "x", 10);
    var motion = builder(s, "JOLAHHGIFHA"); set(motion, "pos", point.build());
    for (int i = 0; i < 4; i++) {
      var movement = builder(s, "JGAMELLGLGE"); set(movement, "NJDCPMENIFG", motion.build());
      assertEquals(0, number(call(s, "JGAMELLGLGE", movement, "JKILAALMFIB"), "retcode"));
    }
    assertTrue(lookups.get() <= 1, "Movement should reuse a short-lived room authorization");
    assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(owner, new TrainCakeSocialSettings(true, false, false)));
    var movement = builder(s, "JGAMELLGLGE"); set(movement, "NJDCPMENIFG", motion.build());
    assertNotEquals(0, number(call(s, "JGAMELLGLGE", movement, "JKILAALMFIB"), "retcode"),
        "A permission update invalidates the cached authorization immediately");
  }

  @Test void aRoomPropInteractionCannotWriteOrdinaryWorldStateOrMissions() throws Exception {
    int interaction = 19994555;
    var previous = GameData.getInteractExcelMap().get(interaction);
    try {
      var row = fixture.gson.fromJson("{\"InteractID\":19994555,\"TargetState\":\"Open\",\"ItemCostList\":[]}", InteractExcel.class);
      row.onLoad(); GameData.getInteractExcelMap().add(row);
      var owner = actor(20001); var guest = actor(10001); var s = new Session(guest);
      assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(owner, new TrainCakeSocialSettings(true, true, true)));
      enter(s, owner.uid);
      var prop = guest.getScene().getEntities().values().stream()
          .filter(emu.lunarcore.game.scene.entity.EntityProp.class::isInstance)
          .map(emu.lunarcore.game.scene.entity.EntityProp.class::cast)
          .filter(p -> p.getGroupId() == 240 && p.getInstId() == 300001).findFirst().orElseThrow();
      var story = fixture.gson.toJson(guest.getGameplay().getStoryValues());
      var missions = fixture.gson.toJson(guest.getGameplay().getSubMissions());
      int saves = guest.saves;
      var req = builder(s, "InteractPropCsReq");
      set(req, "prop_entity_id", prop.getEntityId()); set(req, "interact_id2", interaction);
      assertEquals(0, number(call(s, "InteractPropCsReq", req, "InteractPropScRsp"), "retcode"));
      assertEquals(story, fixture.gson.toJson(guest.getGameplay().getStoryValues()));
      assertEquals(missions, fixture.gson.toJson(guest.getGameplay().getSubMissions()));
      assertEquals(saves, guest.saves);
    } finally {
      if (previous == null) GameData.getInteractExcelMap().keySet().remove(interaction);
      else GameData.getInteractExcelMap().add(previous);
    }
  }
}
