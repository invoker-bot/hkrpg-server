package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.TrainCakeSocialSettings;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Entry must check the real owner's saved permissions and original unlock. */
class TrainCakeRoomAccess46Test {
  @TempDir Path root;
  final TrainCakeCatch46Test fixture = new TrainCakeCatch46Test();
  Map<Integer, Player> players = new HashMap<>();
  boolean friends;

  class Actor extends TrainCakeCatch46Test.Actor {
    final int id;
    Actor(int id) { this.id = id; roomStore = fixture.roomStore; }
    @Override public int getUid() { return id; }
  }
  class Session extends Gameplay46HandlerTest.Session implements TrainCakeSocial46.Resolver {
    final Actor actor;
    Session(Actor actor) { this.actor = actor; }
    @Override public Player getPlayer() { return actor; }
    public Player trainCakePlayer(int uid) { return players.get(uid); }
    public boolean trainCakeFriends(int visitorUid, int ownerUid) { return friends; }
  }

  @BeforeEach void setup() throws Exception {
    fixture.resources = root; fixture.resources();
    Path path = root.resolve("ExcelOutput/FuncUnlockData.json");
    var rows = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
    rows.add(JsonParser.parseString("{\"UnlockID\":5200,\"Conditions\":[{\"Type\":\"FinishMainMission\",\"Param\":\"4020800\"}],\"ShowCondition\":[]}"));
    Files.writeString(path, fixture.gson.toJson(rows));
  }
  @AfterEach void cleanup() { fixture.restoreResources(); }

  Actor actor(int uid, boolean unlocked) {
    var actor = new Actor(uid);
    var plane = fixture.gson.fromJson("{\"PlaneID\":10000,\"PlaneType\":\"Train\"}", MazePlaneExcel.class);
    actor.scene = new Scene(actor, plane, 10000003);
    var gate = new QuestState(402080005); gate.advance(1, 1, 1);
    actor.getGameplay().getSubMissions().put(402080005, gate);
    if (unlocked) {
      var main = new QuestState(4020800); main.advance(1, 1, 1);
      actor.getGameplay().getMainMissions().put(4020800, main);
    }
    players.put(uid, actor);
    return actor;
  }

  int precheck(Session session, int owner, int entry) throws Exception {
    var req = Gameplay46.builder(session, "SocialPlayPreCheckEnterRoomCsReq");
    Gameplay46.set(req, "teleport_id", owner); Gameplay46.set(req, "entry_id", entry);
    assertTrue(VersionedPacketHandler.handle(session, 9736, req.build().toByteArray()));
    assertEquals("SocialPlayPreCheckEnterRoomScRsp", session.responseName);
    return Gameplay46.number(session.response, "retcode");
  }

  @Test void selfEntryRequiresTheOriginalFinishedMainMission() throws Exception {
    var actor = actor(10001, false); var session = new Session(actor);
    assertNotEquals(0, precheck(session, 0, 100000399));
    var main = new QuestState(4020800); main.advance(1, 1, 1);
    actor.getGameplay().getMainMissions().put(4020800, main);
    assertEquals(0, precheck(session, 0, 100000399));
    assertEquals(0, actor.saves, "Precheck is read-only");
  }

  @Test void strangersAndFriendsRespectTheOwnersExplicitSettings() throws Exception {
    var owner = actor(20001, true); var guest = actor(10001, true); var session = new Session(guest);
    assertNotEquals(0, precheck(session, 20001, 100000353));
    assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(owner, new TrainCakeSocialSettings(false, true, false)));
    assertNotEquals(0, precheck(session, 20001, 100000353), "Strangers are closed");
    friends = true;
    assertEquals(0, precheck(session, 20001, 100000353));
    friends = false;
    assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(owner, new TrainCakeSocialSettings(true, true, false)));
    assertEquals(0, precheck(session, 20001, 100000353));
    assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(owner, new TrainCakeSocialSettings(true, false, false)));
    assertNotEquals(0, precheck(session, 20001, 100000353));
    assertEquals(0, guest.saves);
  }

  @Test void nonexistingAndLockedOwnersCannotBeUsedAsAPlaceholderRoom() throws Exception {
    var guest = actor(10001, true); var session = new Session(guest);
    assertNotEquals(0, precheck(session, 99999, 100000353));
    var locked = actor(20001, false);
    assertEquals(Retcode.SUCC, TrainCakeSocialSettings.update(locked, new TrainCakeSocialSettings(true, true, true)));
    assertNotEquals(0, precheck(session, 20001, 100000353));
    assertEquals(0, guest.saves);
  }

  @Test void ownerRoomQueryReturnsOnlyTheActualCakeRoomAndDoesNotCreateABox() throws Exception {
    var owner = actor(10001, true); var session = new Session(owner);
    var req = Gameplay46.builder(session, "SocialPlayGetOwnerRoomDataCsReq");
    var type = req.getDescriptorForType().findFieldByName("LHLKJIDFLIN");
    req.setField(type, type.getEnumType().findValueByNumber(1));
    assertTrue(VersionedPacketHandler.handle(session, 9655, req.build().toByteArray()));
    assertEquals("SocialPlayGetOwnerRoomDataScRsp", session.responseName);
    assertEquals(0, Gameplay46.number(session.response, "retcode"));
    var room = (com.google.protobuf.DynamicMessage) Gameplay46.value(session.response, "DJMHHMMABJL");
    assertEquals(owner.getUid(), Gameplay46.number(room, "room_owner_uid"));
    assertTrue(((List<?>) Gameplay46.value(room, "player_info")).isEmpty());
    assertEquals(0, owner.saves);
    assertEquals(0, owner.getGameplay().getTrainCakeCatch().getRefreshAt());
    req.setField(type, type.getEnumType().findValueByNumber(2));
    assertTrue(VersionedPacketHandler.handle(session, 9655, req.build().toByteArray()));
    assertNotEquals(0, Gameplay46.number(session.response, "retcode"), "A pet-park room must not become a cake room");
  }
}
