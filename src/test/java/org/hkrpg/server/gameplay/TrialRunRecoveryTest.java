package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.trial.TrialInstance;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.util.*;
import java.util.*;
import org.junit.jupiter.api.*;

class TrialRunRecoveryTest {
  static final int STAGE = TrialActivityLifecycleTest.STAGE;
  static final int SPECIAL = TrialActivityLifecycleTest.SPECIAL;
  final TrialActivityLifecycleTest fixture = new TrialActivityLifecycleTest();
  final Gson gson = new Gson();
  MazePlaneExcel oldPlane;
  MapEntranceExcel oldEntry;
  MazePlaneExcel oldTrialPlane;
  MapEntranceExcel oldTrialEntry;

  static class Origin extends TrialActivityLifecycleTest.Director {
    int plane = 20001, floor = 20001001, entry = 2000101;
    Position pos = new Position(1, 2, 3), rot = new Position(0, 70, 0);

    public String getName() {
      return "trial-recovery-fixture";
    }

    public int getPlaneId() {
      return plane;
    }

    public int getFloorId() {
      return floor;
    }

    public int getEntryId() {
      return entry;
    }

    public Position getPos() {
      return pos;
    }

    public Position getRot() {
      return rot;
    }

    public void save() {
      super.save();
      getTrialManager().recordState();
    }

    public boolean enterScene(int entry, int anchor, boolean send) {
      if (!super.enterScene(entry, anchor, send)) return false;
      this.entry = entry;
      plane = STAGE;
      floor = 199951001;
      pos = new Position(9, 8, 7);
      scene =
          new Scene(
              this,
              new Gson()
                  .fromJson(
                      "{\"PlaneID\":199951,\"PlaneType\":\"TrialActivity\"}", MazePlaneExcel.class),
              floor);
      return true;
    }

    public boolean loadScene(Location place, boolean send) {
      if (!super.loadScene(place, send)) return false;
      plane = place.getPlaneId();
      floor = place.getFloorId();
      entry = place.getEntryId();
      pos = place.getPos().clone();
      rot = place.getRot().clone();
      scene =
          new Scene(
              this,
              new Gson()
                  .fromJson(
                      "{\"PlaneID\":"
                          + plane
                          + ",\"PlaneType\":\""
                          + (plane == 20001 ? "Town" : "TrialActivity")
                          + "\"}",
                      MazePlaneExcel.class),
              floor);
      return true;
    }
  }

  @BeforeEach
  void install() {
    fixture.install();
    fixture.roster(SPECIAL);
    oldPlane = GameData.getMazePlaneExcelMap().get(20001);
    oldEntry = GameData.getMapEntranceExcelMap().get(2000101);
    oldTrialPlane = GameData.getMazePlaneExcelMap().get(STAGE);
    oldTrialEntry = GameData.getMapEntranceExcelMap().get(30501001);
    GameData.getMazePlaneExcelMap().add(gson.fromJson("{\"PlaneID\":199951,\"PlaneType\":\"TrialActivity\"}", MazePlaneExcel.class));
    GameData.getMapEntranceExcelMap().add(gson.fromJson("{\"ID\":30501001,\"PlaneID\":199951,\"FloorID\":199951001}", MapEntranceExcel.class));
    GameData.getMazePlaneExcelMap()
        .add(gson.fromJson("{\"PlaneID\":20001,\"PlaneType\":\"Town\"}", MazePlaneExcel.class));
    GameData.getMapEntranceExcelMap()
        .add(
            gson.fromJson(
                "{\"ID\":2000101,\"PlaneID\":20001,\"FloorID\":20001001}", MapEntranceExcel.class));
  }

  @AfterEach
  void restore() {
    fixture.restore();
    GameData.getMazePlaneExcelMap().keySet().remove(20001);
    GameData.getMapEntranceExcelMap().keySet().remove(2000101);
    if (oldPlane != null) GameData.getMazePlaneExcelMap().add(oldPlane);
    if (oldEntry != null) GameData.getMapEntranceExcelMap().add(oldEntry);
    GameData.getMazePlaneExcelMap().keySet().remove(STAGE);
    GameData.getMapEntranceExcelMap().keySet().remove(30501001);
    if (oldTrialPlane != null) GameData.getMazePlaneExcelMap().add(oldTrialPlane);
    if (oldTrialEntry != null) GameData.getMapEntranceExcelMap().add(oldTrialEntry);
  }

  Origin started() {
    var p = new Origin();
    assertNotNull(p.getTrialManager().startTrial(STAGE));
    return p;
  }

  Origin reload(GameplayState state) throws Exception {
    var p = new Origin();
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(p, gson.fromJson(gson.toJson(state), GameplayState.class));
    return p;
  }

  static class LoginSession extends emu.lunarcore.server.game.GameSession {
    final Origin player;
    final List<com.google.protobuf.DynamicMessage> trialNotifications = new ArrayList<>();
    final List<Integer> commands = new ArrayList<>();

    LoginSession(Origin player) {
      super(null);
      this.player = player;
      player.setSession(this);
      setState(emu.lunarcore.server.packet.SessionState.WAITING_FOR_LOGIN);
    }

    public Player getPlayer() { return player; }

    public org.hkrpg.server.protocol.ProtocolBridge getProtocol() {
      return org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
    }

    public void send(emu.lunarcore.server.packet.BasePacket packet) {
      commands.add(getProtocol().outbound(packet.getCmdId(), packet.getData() == null
          ? new byte[0] : packet.getData().toByteArray()).cmdId());
    }

    public void sendClientPacket(String name, byte[] bytes) {
      commands.add(getProtocol().clientCommand(name));
      if (name.equals("CurTrialActivityScNotify")) {
        try {
          trialNotifications.add(getProtocol().clientSchema().parse(name, bytes));
        } catch (java.io.IOException error) {
          throw new AssertionError(error);
        }
      }
    }
  }

  LoginSession login(Origin player) throws Exception {
    var session = new LoginSession(player);
    new emu.lunarcore.server.packet.recv.HandlerPlayerLoginCsReq().handle(session, new byte[0]);
    return session;
  }

  void assertActiveTrialNotification(LoginSession session) {
    assertEquals(1, session.trialNotifications.size(),
        "A restored trial must initialize the native RoleTrialInstance before its scene UI opens");
    var message = session.trialNotifications.getFirst();
    var type = message.getDescriptorForType();
    assertEquals(STAGE, message.getField(type.findFieldByNumber(1)));
    assertEquals(0, ((com.google.protobuf.Descriptors.EnumValueDescriptor)
        message.getField(type.findFieldByNumber(10))).getNumber(),
        "Restoring an active trial must not report a victory");
    assertTrue(message.getUnknownFields().asMap().isEmpty());
    assertEquals(session.getProtocol().clientCommand("PlayerLoginScRsp"), session.commands.getFirst());
    assertTrue(session.commands.indexOf(2646) > 0);
  }

  @Test
  void restoredTrialLoginPublishesItsStageWithoutCompletingOrChangingTheRun() throws Exception {
    var original = started();
    original.getAvatarById(SPECIAL).setCurrentHp(original.trial, 2300);
    original.save();
    var restored = reload(original.getGameplay());
    assertTrue(restored.getTrialManager().restoreOnLogin());
    var instance = restored.getInstance();
    var before = gson.toJson(restored.getGameplay());
    int saves = restored.saves;
    assertActiveTrialNotification(login(restored));
    assertEquals(before, gson.toJson(restored.getGameplay()));
    assertEquals(saves, restored.saves);
    assertSame(instance, restored.getInstance());
    assertEquals(2300, restored.getAvatarById(SPECIAL).getCurrentHp(restored.trial));
    assertTrue(restored.getGameplay().getTrialCompletions().isEmpty());
  }

  @Test
  void interruptedTrialEntryAlsoInitializesTheClientAfterSuccessfulRecovery() throws Exception {
    var json = gson.toJsonTree(started().getGameplay()).getAsJsonObject();
    json.getAsJsonObject("trialRun").addProperty("phase", "ENTERING");
    json.getAsJsonObject("trialRun").remove("location");
    var restored = reload(gson.fromJson(json, GameplayState.class));
    assertTrue(restored.getTrialManager().restoreOnLogin());
    assertActiveTrialNotification(login(restored));
    assertEquals(1, restored.entryAttempts);
    assertTrue(restored.getGameplay().getTrialCompletions().isEmpty());
  }

  @Test
  void anUnrestoredSavedIntentCannotPublishAnActiveTrialToTheClient() throws Exception {
    var restored = reload(started().getGameplay());
    assertNull(restored.getInstance());
    assertTrue(login(restored).trialNotifications.isEmpty());
    assertNotNull(restored.getGameplay().getTrialRun());
  }

  @Test
  void activeTrialRestoresFrozenActorsVitalsSecretPointsLocationAndExactHome() throws Exception {
    var original = started();
    original.getAvatarById(SPECIAL).setCurrentHp(original.trial, 2300);
    original.getAvatarById(SPECIAL).setCurrentSp(original.trial, 4700);
    original.trial.setMp(2);
    original.pos.setX(456);
    original.save();
    var restored = reload(original.getGameplay());
    GameData.getSpecialAvatarExcelMap()
        .add(
            gson.fromJson(
                "{\"SpecialAvatarID\":1199951,\"AvatarID\":199951,\"Level\":80,\"Promotion\":6}",
                SpecialAvatarExcel.class));
    assertTrue(restored.getTrialManager().restoreOnLogin());
    assertNotNull(restored.getInstance(TrialInstance.class));
    assertEquals(40, restored.getAvatarById(SPECIAL).getLevel());
    assertEquals(2300, restored.getAvatarById(SPECIAL).getCurrentHp(restored.trial));
    assertEquals(4700, restored.getAvatarById(SPECIAL).getCurrentSp(restored.trial));
    assertEquals(2, restored.trial.getMp());
    assertEquals(456, restored.getPos().getX());
    assertEquals(TrialActivityLifecycleTest.TYPE, restored.teams.getCurrentExtraIndex());
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC, restored.getTrialManager().leaveTrial(STAGE));
    assertEquals(2000101, restored.getEntryId());
    assertEquals(1, restored.getPos().getX());
    assertEquals(70, restored.getRot().getY());
    assertNull(restored.getGameplay().getTrialRun());
    assertTrue(restored.getAvatars().getTempAvatars().isEmpty());
  }

  @Test
  void anInterruptedEntryRestoresTheFrozenIntentAndEntersTheTrialOnce() throws Exception {
    var original = started();
    var json = gson.toJsonTree(original.getGameplay()).getAsJsonObject();
    json.getAsJsonObject("trialRun").addProperty("phase", "ENTERING");
    json.getAsJsonObject("trialRun").remove("location");
    var restored = reload(gson.fromJson(json, GameplayState.class));
    assertTrue(restored.getTrialManager().restoreOnLogin());
    assertEquals(1, restored.entryAttempts);
    assertEquals(TrialRun.Phase.ACTIVE, restored.getGameplay().getTrialRun().getPhase());
    assertEquals(List.of(SPECIAL), restored.trial.getAvatars());
    assertEquals(0, restored.getAvatarById(SPECIAL).getCurrentSp(restored.trial));
  }

  @Test
  void aPendingManualReturnRestoresHomeWithoutCompletingOrRepublishingTrialActors()
      throws Exception {
    var original = started();
    original.allowReturn = false;
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY,
        original.getTrialManager().leaveTrial(STAGE));
    assertEquals(TrialRun.Phase.RETURNING, original.getGameplay().getTrialRun().getPhase());
    var restored = reload(original.getGameplay());
    assertTrue(restored.getTrialManager().restoreOnLogin());
    assertEquals(2000101, restored.getEntryId());
    assertNull(restored.getGameplay().getTrialRun());
    assertNull(restored.getInstance());
    assertTrue(restored.getGameplay().getTrialCompletions().isEmpty());
    assertTrue(restored.getAvatars().getTempAvatars().isEmpty());
    assertTrue(login(restored).trialNotifications.isEmpty());
  }

  @Test
  void aConfirmedVictoryWithFailedReturnRestoresHomeAndKeepsItsOriginalCompletion()
      throws Exception {
    var original = started();
    original.allowReturn = false;
    original
        .getInstance(TrialInstance.class)
        .onBattleFinish(
            fixture.trialBattle(original, true, 42),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance());
    assertEquals(TrialRun.Phase.WON, original.getGameplay().getTrialRun().getPhase());
    var restored = reload(original.getGameplay());
    assertTrue(restored.getTrialManager().restoreOnLogin());
    assertEquals(2000101, restored.getEntryId());
    assertNull(restored.getGameplay().getTrialRun());
    assertEquals(Set.of(STAGE), restored.getGameplay().getTrialCompletions().keySet());
    assertTrue(restored.getAvatars().getTempAvatars().isEmpty());
    assertTrue(login(restored).trialNotifications.isEmpty());
  }

  @Test
  void aSavedTrialLocationCannotUseAnOrdinaryPlaneWithTheTrialEntranceId() throws Exception {
    var json = gson.toJsonTree(started().getGameplay()).getAsJsonObject();
    var place = json.getAsJsonObject("trialRun").getAsJsonObject("location");
    place.addProperty("plane", 20001);
    place.addProperty("floor", 20001001);
    var restored = reload(gson.fromJson(json, GameplayState.class));
    assertThrows(IllegalStateException.class, () -> restored.getTrialManager().restoreOnLogin());
    assertEquals(0, restored.returnAttempts);
    assertTrue(restored.getAvatars().getTempAvatars().isEmpty());
    assertNull(restored.getInstance());
  }

  @Test
  void invalidSavedVitalsCannotPublishActorsOrMutateTheOrdinaryParty() throws Exception {
    var json = gson.toJsonTree(started().getGameplay()).getAsJsonObject();
    json.getAsJsonObject("trialRun")
        .getAsJsonObject("vitals")
        .getAsJsonObject(Integer.toString(SPECIAL))
        .addProperty("hp", -1);
    var restored = reload(gson.fromJson(json, GameplayState.class));
    assertThrows(IllegalStateException.class, () -> restored.getTrialManager().restoreOnLogin());
    assertTrue(restored.getAvatars().getTempAvatars().isEmpty());
    assertNull(restored.getInstance());
    assertEquals(0, restored.teams.getCurrentExtraIndex());
    assertNotNull(restored.getGameplay().getTrialRun());
  }

  @Test
  void aFailedSavedSceneLoadKeepsTheRunAndCleansActorsForAnExplicitRetry() throws Exception {
    var restored = reload(started().getGameplay());
    restored.allowReturn = false;
    assertThrows(IllegalStateException.class, () -> restored.getTrialManager().restoreOnLogin());
    assertTrue(restored.getAvatars().getTempAvatars().isEmpty());
    assertNull(restored.getInstance());
    assertEquals(0, restored.teams.getCurrentExtraIndex());
    assertNotNull(restored.getGameplay().getTrialRun());
    restored.allowReturn = true;
    assertTrue(restored.getTrialManager().restoreOnLogin());
    assertNotNull(restored.getInstance(TrialInstance.class));
  }

  @Test
  void activeTrialStateSurvivesRealMorphiaStorageAndRebuildsItsTemporaryParty() throws Exception {
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "trial-run-recovery");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var original = started();
      original.getAvatarById(SPECIAL).setCurrentHp(original.trial, 4200);
      original.save();
      var plain = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(plain, 123456785);
      var state = Player.class.getDeclaredField("gameplay");
      state.setAccessible(true);
      state.set(plain, original.getGameplay());
      store.save(plain);
      var saved = store.find(Player.class).first().getGameplay();
      assertEquals(STAGE, saved.getTrialRun().getStageId());
      assertEquals(2000101, saved.getTrialRun().getHome().getEntry());
      assertEquals(4200, saved.getTrialRun().getVitals().get(SPECIAL).getHp());
      var restored = reload(saved);
      assertTrue(restored.getTrialManager().restoreOnLogin());
      assertEquals(4200, restored.getAvatarById(SPECIAL).getCurrentHp(restored.trial));
    } finally {
      server.shutdownNow();
    }
  }
}
