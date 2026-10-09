package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.*;
import emu.lunarcore.game.player.*;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class ProfileCreation46Test {
  static final int MAIN = 19996925, SUB = 199969251;
  final Gson gson = new Gson();
  final Map<Integer, AvatarExcel> previousAvatars = new HashMap<>();
  final Map<Integer, MultiplePathAvatarExcel> previousPaths = new HashMap<>();
  Session session;

  static class Actor extends Player {
    final List<String> saves = new ArrayList<>();
    final List<Integer> notifications = new ArrayList<>();

    public void save() {
      saves.add(getName() + ":" + getGender() + ":" + getCurAvatarPathId(8001));
    }

    public void sendPacket(BasePacket packet) {
      notifications.add(packet.getCmdId());
    }
  }

  static class Session extends GameSession {
    final Actor actor = new Actor();
    DynamicMessage response;
    String responseName;

    Session() {
      super(null);
      setState(SessionState.ACTIVE);
    }

    public Player getPlayer() {
      return actor;
    }

    public ProtocolBridge getProtocol() {
      return ProtocolBridge.forVersion("4.6.0");
    }

    public void sendClientPacket(String name, byte[] bytes) {
      try {
        responseName = name;
        response = getProtocol().clientSchema().parse(name, bytes);
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
    }
  }

  @BeforeEach
  void prepare() {
    session = new Session();
    for (int id : new int[] {8001, 8002}) {
      previousAvatars.put(id, GameData.getAvatarExcelMap().get(id));
      previousPaths.put(id, GameData.getMultiplePathAvatarExcelMap().get(id));
      var excel = gson.fromJson("{\"AvatarID\":" + id + "}", AvatarExcel.class);
      GameData.getAvatarExcelMap().add(excel);
      GameData.getMultiplePathAvatarExcelMap()
          .add(
              gson.fromJson(
                  "{\"AvatarID\":"
                      + id
                      + ",\"BaseAvatarID\":8001,\"Gender\":\""
                      + (id == 8001 ? "GENDER_MAN" : "GENDER_WOMAN")
                      + "\"}",
                  MultiplePathAvatarExcel.class));
      session.actor.getAvatars().getMultiPaths().put(id, new AvatarMultiPath(session.actor, excel));
    }
    var avatar = new GameAvatar(GameData.getAvatarExcelMap().get(8001));
    avatar.setOwner(session.actor);
    avatar.setMultiPath(session.actor.getAvatars().getMultiPathById(8001));
    session.actor.getAvatars().getAvatars().put(8001, avatar);
    session.actor.getCurAvatarPaths().put(8001, 8001);
    session.actor.setNickname("Before");
    session.actor.saves.clear();
    session.actor.notifications.clear();
    GameData.getMainMissionExcelMap()
        .add(
            gson.fromJson(
                "{\"MainMissionID\":"
                    + MAIN
                    + ",\"TakeParam\":[{\"Type\":\"Manual\"}],\"BeginParam\":[{\"Type\":\"Auto\"}]}",
                MainMissionExcel.class));
    var info =
        gson.fromJson(
            "{\"MainMissionID\":"
                + MAIN
                + ",\"FinishSubMissionList\":["
                + SUB
                + "],\"SubMissionList\":[{\"ID\":"
                + SUB
                + ",\"MainMissionID\":"
                + MAIN
                + ",\"TakeType\":\"Auto\",\"FinishType\":\"CreateCharacter\",\"ParamType\":\"Equal\",\"Progress\":1}]}",
            MissionInfo.class);
    GameData.getMissionInfos().put(MAIN, info);
    GameplayDefinitions.subs.put(SUB, info.getSubMissionList()[0]);
  }

  @AfterEach
  void cleanup() {
    GameData.getMainMissionExcelMap().keySet().remove(MAIN);
    GameData.getMissionInfos().remove(MAIN);
    GameplayDefinitions.subs.remove(SUB);
    for (int id : new int[] {8001, 8002}) {
      if (previousAvatars.get(id) == null) GameData.getAvatarExcelMap().keySet().remove(id);
      else GameData.getAvatarExcelMap().add(previousAvatars.get(id));
      if (previousPaths.get(id) == null)
        GameData.getMultiplePathAvatarExcelMap().keySet().remove(id);
      else GameData.getMultiplePathAvatarExcelMap().add(previousPaths.get(id));
    }
  }

  byte[] choose(String nickname, int gender, boolean modify) {
    var request = Gameplay46.builder(session, "SetPlayerInfoCsReq");
    Gameplay46.set(request, "nickname", nickname);
    Gameplay46.set(request, "is_modify", modify);
    var field = request.getDescriptorForType().findFieldByName("gender");
    request.setField(field, field.getEnumType().findValueByNumber(gender));
    return request.build().toByteArray();
  }

  void submit(int command, byte[] bytes) throws Exception {
    assertTrue(VersionedPacketHandler.handle(session, command, bytes));
    assertTrue(session.response.getUnknownFields().asMap().isEmpty());
  }

  @Test
  void choosingOwnedFemalePathCommitsNameGenderAndCreationGoal() throws Exception {
    assertEquals(Retcode.SUCC, session.actor.getMissionManager().accept(MAIN));
    assertEquals(0, session.actor.getMissionManager().sub(SUB).getProgress());
    submit(41, choose("开拓者", 2, true));
    assertEquals("SetPlayerInfoScRsp", session.responseName);
    assertEquals(30, session.getProtocol().clientCommand(session.responseName));
    assertEquals(0, Gameplay46.number(session.response, "retcode"));
    assertEquals("开拓者", session.actor.getName());
    assertEquals(PlayerGender.GENDER_WOMAN, session.actor.getGender());
    assertEquals(8002, session.actor.getCurAvatarPathId(8001));
    assertEquals(8002, session.actor.getAvatarById(8001).getBattleAvatarId());
    assertEquals(1, session.actor.getMissionManager().sub(SUB).getProgress());
    assertTrue(session.actor.getMissionManager().finished(MAIN));
  }

  @Test
  void defaultMaleAvatarIsNotEvidenceOfAConfirmedCharacterSelection() throws Exception {
    submit(80, new byte[0]);
    assertEquals("GetBasicInfoScRsp", session.responseName);
    assertEquals(false, Gameplay46.value(session.response, "is_gender_set"));
    assertEquals(1, Gameplay46.number(session.response, "gender"));
    assertTrue(session.actor.saves.isEmpty());
    assertEquals(Retcode.SUCC, session.actor.getMissionManager().accept(MAIN));
    session.actor.getMissionManager().refresh();
    assertEquals(0, session.actor.getMissionManager().sub(SUB).getProgress());
  }

  @Test
  void invalidNamesFlagsAndWireValuesDoNotModifyIdentityOrCompleteObjectives() throws Exception {
    assertEquals(Retcode.SUCC, session.actor.getMissionManager().accept(MAIN));
    session.actor.saves.clear();
    for (byte[] bytes :
        new byte[][] {
          choose("", 2, true),
          choose("  ", 2, true),
          choose("x".repeat(33), 2, true),
          choose("a\nb", 2, true),
          choose("valid", 0, true),
          choose("valid", 2, false),
          {24, 1, 80, 3, 98, 1, 65},
          {24, 2, 80, 2, 98, 1, 65},
          {24, 1, 80, (byte) 130, (byte) 128, (byte) 128, (byte) 128, 16, 98, 1, 65},
          {24, 1, 80, 2, 98, 1, (byte) 255},
          {24, 1, 24, 1, 80, 2, 98, 1, 65},
          {24, 1, 80, 2, 98, 1, 65, 112, 1},
          {24, 1, 80, 2, 98, 2, 65},
          {(byte) 128}
        }) {
      submit(41, bytes);
      assertNotEquals(0, Gameplay46.number(session.response, "retcode"));
      assertEquals("Before", session.actor.getName());
      assertEquals(PlayerGender.GENDER_MAN, session.actor.getGender());
      assertEquals(8001, session.actor.getCurAvatarPathId(8001));
      assertFalse(session.actor.hasCreatedCharacter());
      assertEquals(0, session.actor.getMissionManager().sub(SUB).getProgress());
    }
    assertTrue(session.actor.saves.isEmpty());
  }

  @Test
  void missingOrForeignOwnedPathAndMismatchedResourcesCannotSelectGender() throws Exception {
    var owned = session.actor.getAvatars().getMultiPathById(8002);
    session.actor.getAvatars().getMultiPaths().remove(8002);
    submit(41, choose("valid", 2, true));
    assertNotEquals(0, Gameplay46.number(session.response, "retcode"));
    session.actor.getAvatars().getMultiPaths().put(8002, owned);
    owned.setOwner(new Actor());
    submit(41, choose("valid", 2, true));
    assertNotEquals(0, Gameplay46.number(session.response, "retcode"));
    owned.setOwner(session.actor);
    var correct = GameData.getMultiplePathAvatarExcelMap().get(8002);
    GameData.getMultiplePathAvatarExcelMap()
        .add(
            gson.fromJson(
                "{\"AvatarID\":8002,\"BaseAvatarID\":8003,\"Gender\":\"GENDER_WOMAN\"}",
                MultiplePathAvatarExcel.class));
    submit(41, choose("valid", 2, true));
    assertNotEquals(0, Gameplay46.number(session.response, "retcode"));
    GameData.getMultiplePathAvatarExcelMap().add(correct);
    var real = session.actor.getAvatars().getAvatars().remove(8001);
    submit(41, choose("valid", 2, true));
    assertNotEquals(0, Gameplay46.number(session.response, "retcode"));
    session.actor.getAvatars().getAvatars().put(8001, real);
    session.actor.getGameplay().getStoryLines().begin(123, null, null);
    submit(41, choose("valid", 2, true));
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), Gameplay46.number(session.response, "retcode"));
    assertFalse(session.actor.hasCreatedCharacter());
    assertEquals("Before", session.actor.getName());
    assertTrue(session.actor.saves.isEmpty());
  }

  @Test
  void concurrentSameChoiceCompletesOnceAndConflictingRetryCannotChangeIdentity() throws Exception {
    assertEquals(Retcode.SUCC, session.actor.getMissionManager().accept(MAIN));
    session.actor.saves.clear();
    var ready = new CountDownLatch(6);
    var start = new CountDownLatch(1);
    var outcomes = new ArrayList<Future<Retcode>>();
    try (var pool = Executors.newFixedThreadPool(6)) {
      for (int i = 0; i < 6; i++)
        outcomes.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  assertTrue(start.await(5, TimeUnit.SECONDS));
                  return session.actor.setInitialProfile("开拓者", 8002);
                }));
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      for (var future : outcomes) assertEquals(Retcode.SUCC, future.get(5, TimeUnit.SECONDS));
    }
    assertTrue(session.actor.hasCreatedCharacter());
    assertEquals(1, session.actor.getMissionManager().sub(SUB).getProgress());
    assertTrue(session.actor.saves.stream().allMatch(s -> s.equals("开拓者:GENDER_WOMAN:8002")));
    int saveCount = session.actor.saves.size();
    submit(41, choose("different", 1, true));
    assertNotEquals(0, Gameplay46.number(session.response, "retcode"));
    assertEquals("开拓者", session.actor.getName());
    assertEquals(8002, session.actor.getCurAvatarPathId(8001));
    submit(41, choose("开拓者", 2, true));
    assertEquals(0, Gameplay46.number(session.response, "retcode"));
    assertEquals(saveCount, session.actor.saves.size());
    submit(80, new byte[0]);
    assertEquals(true, Gameplay46.value(session.response, "is_gender_set"));
    assertEquals(2, Gameplay46.number(session.response, "gender"));
  }

  @Test
  void unsupportedCreationParametersAndCancellationRemainPending() throws Exception {
    var original = GameplayDefinitions.subs.get(SUB);
    GameplayDefinitions.subs.put(
        SUB,
        gson.fromJson(
            gson.toJson(original).replace("\"ParamInt1\":0", "\"ParamInt1\":1"),
            MissionInfo.SubMissionInfo.class));
    assertEquals(Retcode.SUCC, session.actor.getMissionManager().accept(MAIN));
    submit(41, choose("开拓者", 2, true));
    assertEquals(0, Gameplay46.number(session.response, "retcode"));
    assertEquals(0, session.actor.getMissionManager().sub(SUB).getProgress());
    assertFalse(session.actor.getMissionManager().finished(MAIN));
    GameplayDefinitions.subs.put(SUB, original);
    session.actor.getGameplay().getCancelledSubs().add(SUB);
    session.actor.getMissionManager().refresh();
    assertEquals(0, session.actor.getMissionManager().sub(SUB).getProgress());
    session.actor.getGameplay().getCancelledSubs().clear();
    session.actor.getMissionManager().refresh();
    assertEquals(1, session.actor.getMissionManager().sub(SUB).getProgress());
  }

  @Test
  void actualMorphiaDocumentReloadRetainsProfileAndConfirmedMission() throws Exception {
    assertEquals(Retcode.SUCC, session.actor.getMissionManager().accept(MAIN));
    submit(41, choose("开拓者", 2, true));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "profile-creation");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      for (String name : new String[] {"name", "gender", "curAvatarPaths", "gameplay"}) {
        var field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(document, field.get(session.actor));
      }
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456799);
      store.save(document);
      var restored = store.find(Player.class).first();
      assertEquals("开拓者", restored.getName());
      assertEquals(PlayerGender.GENDER_WOMAN, restored.getGender());
      assertEquals(8002, restored.getCurAvatarPathId(8001));
      assertTrue(restored.hasCreatedCharacter());
      assertEquals(1, restored.getMissionManager().sub(SUB).getProgress());
      assertTrue(restored.getMissionManager().finished(MAIN));
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void authenticationLegacyAndInvalidBasicQueryDoNotMutateProfile() throws Exception {
    session.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(Profile46.handle(session, 41, choose("valid", 2, true)));
    assertNull(session.response);
    session.setState(SessionState.ACTIVE);
    submit(80, new byte[] {8, 1});
    assertNotEquals(0, Gameplay46.number(session.response, "retcode"));
    var legacy =
        new Session() {
          public ProtocolBridge getProtocol() {
            return ProtocolBridge.forVersion("4.2.0");
          }
        };
    assertFalse(Profile46.handle(legacy, 41, new byte[0]));
    assertFalse(Profile46.handle(session, 44, new byte[0]));
    assertFalse(session.actor.hasCreatedCharacter());
    assertTrue(session.actor.saves.isEmpty());
  }

  @Test
  void nativeResponseMatchesPinnedClientConsumerAndWriterTags() throws Exception {
    submit(41, choose("开拓者", 2, true));
    // CN PlayerModule612901 consumes ret+18/modify+1c/path+28. JNAOCJBLPPC
    // WriteTo213181 serializes those fields with tags 80/48/88 respectively.
    var input = com.google.protobuf.CodedInputStream.newInstance(session.response.toByteArray());
    boolean modify = false, path = false;
    int tag;
    while ((tag = input.readTag()) != 0) {
      if (tag == 48) {
        assertTrue(input.readBool());
        modify = true;
      } else if (tag == 80) assertEquals(0, input.readUInt32());
      else if (tag == 88) {
        assertEquals(8002, input.readEnum());
        path = true;
      } else fail("Unverified profile response field: " + tag);
    }
    assertTrue(modify && path);
  }
}
