package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ActivityPanelExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Test;

class Parkour46Test {
  static final int LEVEL = 199920, NEXT = 199921, SUB = 199922, FLOOR = 44306001;

  static class Session extends Gameplay46HandlerTest.Session {
    int floor = FLOOR;
    boolean battle;
    final Player racer =
        new Player() {
          public void save() {}

          public int getFloorId() {
            return floor;
          }

          public boolean isInBattle() {
            return battle;
          }

          public void sendPacket(emu.lunarcore.server.packet.BasePacket packet) {}
        };

    public Player getPlayer() {
      return racer;
    }
  }

  Session s;
  ActivityPanelExcel prior;
  GameplayDefinitions.Window window =
      new GameplayDefinitions.Window(50039, 5003901, 0, 4102444800L);

  @BeforeEach
  void setup() {
    s = new Session();
    prior = GameData.getActivityPanelExcelMap().get(50039);
    GameData.getActivityPanelExcelMap()
        .add(new com.google.gson.Gson().fromJson("{\"PanelID\":50039}", ActivityPanelExcel.class));
    GameplayDefinitions.windows.add(window);
    GameplayDefinitions.parkourConstants.put("parkour_activity_module_id", 5003901);
    GameplayDefinitions.parkourConstants.put("parkour_gameplay_floor_id", FLOOR);
    GameplayDefinitions.parkourLevels.put(
        LEVEL,
        new GameplayDefinitions.ParkourLevel(
            LEVEL, "FinishSubMission", SUB, 1, 3, 3, "race.asset"));
    GameplayDefinitions.parkourLevels.put(
        NEXT,
        new GameplayDefinitions.ParkourLevel(NEXT, "FinishLevel", LEVEL, 1, 1, 3, "next.asset"));
    GameplayDefinitions.parkourBalls.put(1, new GameplayDefinitions.ParkourBall(1, 0, 0));
  }

  @AfterEach
  void clear() {
    GameplayDefinitions.windows.remove(window);
    GameplayDefinitions.parkourConstants.clear();
    GameplayDefinitions.parkourLevels.clear();
    GameplayDefinitions.parkourBalls.clear();
    if (prior != null) GameData.getActivityPanelExcelMap().add(prior);
    else GameData.getActivityPanelExcelMap().keySet().remove(50039);
    GameplayDefinitions.subs.remove(1999231);
    GameplayDefinitions.subs.remove(1999232);
    GameData.getMissionInfos().remove(199923);
    GameData.getMainMissionExcelMap().keySet().remove(199923);
  }

  void unlock() {
    var q = new QuestState(SUB);
    q.advance(1, 1, 1);
    s.racer.getGameplay().getSubMissions().put(SUB, q);
  }

  int start(int id, int ball) throws Exception {
    var b = Gameplay46.builder(s, "ParkourStartLevelCsReq");
    Gameplay46.set(b, "level_id", id);
    Gameplay46.set(b, "CEKKJHBNEOD", ball);
    assertTrue(VersionedPacketHandler.handle(s, 8384, b.build().toByteArray()));
    return Gameplay46.number(s.response, "retcode");
  }

  com.google.protobuf.DynamicMessage end(
      int id, int ball, int time, int total, int rank, int reason) throws Exception {
    var b = Gameplay46.builder(s, "ParkourEndLevelCsReq");
    for (var e :
        Map.of("level_id", id, "CEKKJHBNEOD", ball, "time", time, "total_time", total, "rank", rank)
            .entrySet()) Gameplay46.set(b, e.getKey(), e.getValue());
    var f = b.getDescriptorForType().findFieldByName("end_reason");
    b.setField(f, f.getEnumType().findValueByNumber(reason));
    assertTrue(VersionedPacketHandler.handle(s, 8400, b.build().toByteArray()));
    return s.response;
  }

  @Test
  void dataQueryHasARealNativeResponseWithoutInventingFinishedLevels() throws Exception {
    var s = new Gameplay46HandlerTest.Session();
    assertTrue(VersionedPacketHandler.handle(s, 8390, new byte[0]));
    assertEquals("ParkourGetDataScRsp", s.responseName);
    assertEquals(0, Gameplay46.number(s.response, "retcode"));
    assertEquals(
        0,
        s.response.getRepeatedFieldCount(s.response.getDescriptorForType().findFieldByNumber(15)));
  }

  @Test
  void unknownLevelCannotStartOrBeAcknowledgedAsSuccess() throws Exception {
    var s = new Gameplay46HandlerTest.Session();
    assertTrue(VersionedPacketHandler.handle(s, 8384, new byte[] {8, 127, 32, 1}));
    assertEquals("ParkourStartLevelScRsp", s.responseName);
    assertNotEquals(0, Gameplay46.number(s.response, "retcode"));
  }

  @Test
  void prerequisitesAndActualFloorAreRequired() throws Exception {
    assertNotEquals(0, start(LEVEL, 1));
    unlock();
    assertNotEquals(0, start(NEXT, 1));
    assertNotEquals(0, start(LEVEL, 2));
    s.floor = 1;
    assertNotEquals(0, start(LEVEL, 1));
    s.floor = FLOOR;
    s.battle = true;
    assertNotEquals(0, start(LEVEL, 1));
    s.battle = false;
    s.racer.getGameplay().getCancelledSubs().add(SUB);
    assertNotEquals(0, start(LEVEL, 1));
    s.racer.getGameplay().getCancelledSubs().clear();
    assertEquals(0, start(LEVEL, 1));
    assertNotEquals(0, start(LEVEL, 1));
  }

  @Test
  void finishSavesRealBestTimeAndNeverForcesFullCompletion() throws Exception {
    unlock();
    assertEquals(0, start(LEVEL, 1));
    var r = end(LEVEL, 1, 81000, 84000, 4, 2);
    assertEquals(false, Gameplay46.value(r, "ADJLGNBJAOC"));
    assertEquals(false, Gameplay46.value(r, "AJGOLAKNGPA"));
    assertNotEquals(0, start(NEXT, 1));
    assertEquals(0, start(LEVEL, 1));
    r = end(LEVEL, 1, 80000, 85000, 2, 2);
    assertEquals(true, Gameplay46.value(r, "ADJLGNBJAOC"));
    assertEquals(true, Gameplay46.value(r, "AJGOLAKNGPA"));
    assertEquals(
        80000,
        Gameplay46.number(
            (com.google.protobuf.DynamicMessage) Gameplay46.value(r, "ENKCCEACJKL"), "time"));
    assertEquals(0, start(LEVEL, 1));
    r = end(LEVEL, 1, 90000, 91000, 3, 2);
    assertEquals(false, Gameplay46.value(r, "AJGOLAKNGPA"));
    assertFalse(r.hasField(r.getDescriptorForType().findFieldByNumber(4)));
    assertNotEquals(0, Gameplay46.number(end(LEVEL, 1, 90000, 91000, 3, 2), "retcode"));
    s.request("ParkourGetDataCsReq");
    var field = s.response.getDescriptorForType().findFieldByNumber(15);
    var row = (com.google.protobuf.DynamicMessage) s.response.getRepeatedField(field, 0);
    assertEquals(80000, Gameplay46.number(row, "time"));
    assertEquals(true, Gameplay46.value(row, "AJGOLAKNGPA"));
    assertEquals(0, start(NEXT, 1));
  }

  @Test
  void mismatchedAndInvalidResultsPreserveActiveRace() throws Exception {
    unlock();
    assertEquals(0, start(LEVEL, 1));
    for (int[] a :
        List.of(
            new int[] {NEXT, 1, 80, 100, 1, 2},
            new int[] {LEVEL, 2, 80, 100, 1, 2},
            new int[] {LEVEL, 1, 0, 0, 1, 2},
            new int[] {LEVEL, 1, 80, 70, 1, 2},
            new int[] {LEVEL, 1, 80, 100, 0, 2},
            new int[] {LEVEL, 1, 80, 100, 33, 2},
            new int[] {LEVEL, 1, 80, 100, 1, 0}))
      assertNotEquals(0, Gameplay46.number(end(a[0], a[1], a[2], a[3], a[4], a[5]), "retcode"));
    assertEquals(LEVEL, s.racer.getGameplay().getParkour().getActiveLevel());
    assertTrue(s.racer.getGameplay().getParkour().getRecords().isEmpty());
    assertEquals(0, Gameplay46.number(end(LEVEL, 1, 0, 0, 0, 1), "retcode"));
    assertEquals(0, s.racer.getGameplay().getParkour().getActiveLevel());
    assertTrue(s.racer.getGameplay().getParkour().getRecords().isEmpty());
  }

  @Test
  void malformedWireCannotAliasLargeUnsignedNumbersIntoARealLevel() throws Exception {
    unlock();
    var out = new java.io.ByteArrayOutputStream();
    var coded = com.google.protobuf.CodedOutputStream.newInstance(out);
    coded.writeUInt64(1, 0x100000000L + LEVEL);
    coded.writeUInt32(4, 1);
    coded.flush();
    assertTrue(VersionedPacketHandler.handle(s, 8384, out.toByteArray()));
    assertNotEquals(0, Gameplay46.number(s.response, "retcode"));
    assertEquals(0, s.racer.getGameplay().getParkour().getActiveLevel());
  }

  @Test
  void startingGatesAndCancellationStayEffectiveDuringTheRace() throws Exception {
    unlock();
    assertEquals(0, start(LEVEL, 1));
    s.racer.getGameplay().getCancelledSubs().add(SUB);
    assertNotEquals(0, Gameplay46.number(end(LEVEL, 1, 80, 100, 1, 2), "retcode"));
    s.racer.getGameplay().getCancelledSubs().clear();
    GameplayDefinitions.windows.remove(window);
    assertNotEquals(0, Gameplay46.number(end(LEVEL, 1, 80, 100, 1, 2), "retcode"));
    assertEquals(0, Gameplay46.number(end(LEVEL, 1, 0, 0, 0, 1), "retcode"));
  }

  @Test
  void frozenTargetAndRaceSurviveMorphiaReloadWithoutDoubleFinishing() throws Exception {
    unlock();
    assertEquals(0, start(LEVEL, 1));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "parkour");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456781);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, s.racer.getGameplay());
      store.save(document);
      gameplay.set(s.racer, store.find(Player.class).first().getGameplay());
      GameplayDefinitions.parkourLevels.put(
          LEVEL,
          new GameplayDefinitions.ParkourLevel(
              LEVEL, "FinishSubMission", SUB, 1, 1, 3, "race.asset"));
      assertEquals(true, Gameplay46.value(end(LEVEL, 1, 80000, 85000, 2, 2), "ADJLGNBJAOC"));
      gameplay.set(document, s.racer.getGameplay());
      store.save(document);
      gameplay.set(s.racer, store.find(Player.class).first().getGameplay());
      assertNotEquals(0, Gameplay46.number(end(LEVEL, 1, 80000, 85000, 2, 2), "retcode"));
      assertEquals(80000, s.racer.getGameplay().getParkour().getRecords().get(LEVEL).getTime());
      store
          .getDatabase()
          .getCollection(store.getMapper().getEntityModel(Player.class).getCollectionName())
          .updateOne(
              new org.bson.Document("_id", 123456781),
              new org.bson.Document("$unset", new org.bson.Document("gameplay.parkour", "")));
      assertNotNull(store.find(Player.class).first().getGameplay().getParkour());
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void onlySuccessfulRaceAdvancesPreviouslyAcceptedStoryObjectives() throws Exception {
    var gson = new com.google.gson.Gson();
    GameData.getMainMissionExcelMap()
        .add(
            gson.fromJson(
                "{\"MainMissionID\":199923,\"TakeParam\":[{\"Type\":\"Manual\"}],\"BeginParam\":[{\"Type\":\"Auto\"}]}",
                emu.lunarcore.data.excel.MainMissionExcel.class));
    var info =
        gson.fromJson(
            """
{"MainMissionID":199923,"FinishSubMissionList":[1999232],"SubMissionList":[
{"ID":1999231,"MainMissionID":199923,"TakeType":"Auto","FinishType":"ParkourFinishLevel","ParamType":"ListContain","ParamIntList":[199920]},
{"ID":1999232,"MainMissionID":199923,"TakeType":"MultiSequence","TakeParamIntList":[1999231],"FinishType":"ParkourFinishLevel","ParamType":"ListContain","ParamIntList":[199920]}]}
""",
            emu.lunarcore.data.config.MissionInfo.class);
    GameData.getMissionInfos().put(199923, info);
    for (var c : info.getSubMissionList()) GameplayDefinitions.subs.put(c.getId(), c);
    assertEquals(Retcode.SUCC, s.racer.getMissionManager().accept(199923));
    unlock();
    assertEquals(0, start(LEVEL, 1));
    end(LEVEL, 1, 80, 100, 4, 2);
    assertEquals(0, s.racer.getMissionManager().sub(1999231).getProgress());
    assertEquals(0, start(LEVEL, 1));
    end(LEVEL, 1, 80, 100, 2, 2);
    assertEquals(1, s.racer.getMissionManager().sub(1999231).getProgress());
    assertEquals(0, s.racer.getMissionManager().sub(1999232).getProgress());
    end(LEVEL, 1, 80, 100, 2, 2);
    assertEquals(0, s.racer.getMissionManager().sub(1999232).getProgress());
    assertEquals(0, start(LEVEL, 1));
    end(LEVEL, 1, 80, 100, 1, 2);
    assertEquals(1, s.racer.getMissionManager().sub(1999232).getProgress());
  }

  @Test
  void resourceCarUpgradeRequiresItsFinishedStoryAndKeepsOldCarLocked() throws Exception {
    unlock();
    GameplayDefinitions.parkourLevels.put(
        LEVEL,
        new GameplayDefinitions.ParkourLevel(
            LEVEL, "FinishSubMission", SUB, 0, 3, 3, "race.asset"));
    GameplayDefinitions.parkourBalls.put(2, new GameplayDefinitions.ParkourBall(2, SUB + 1, 5));
    GameplayDefinitions.parkourBalls.put(5, new GameplayDefinitions.ParkourBall(5, 0, 0));
    assertNotEquals(0, start(LEVEL, 5));
    assertNotEquals(0, start(LEVEL, 6));
    assertEquals(0, start(LEVEL, 2));
    end(LEVEL, 2, 0, 0, 0, 1);
    var upgraded = new QuestState(SUB + 1);
    upgraded.advance(1, 1, 1);
    s.racer.getGameplay().getSubMissions().put(SUB + 1, upgraded);
    assertNotEquals(0, start(LEVEL, 2));
    assertEquals(0, start(LEVEL, 5));
  }

  @Test
  void concurrentFinishOnlyConsumesCurrentRaceOnce() throws Exception {
    unlock();
    assertEquals(0, start(LEVEL, 1));
    var manager = new ParkourManager(s.racer);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(6);
    try {
      var work = new ArrayList<java.util.concurrent.Callable<ParkourManager.Result>>();
      for (int i = 0; i < 6; i++) work.add(() -> manager.finish(LEVEL, 1, 80000, 85000, 2, 2));
      int accepted = 0;
      for (var result : executor.invokeAll(work))
        if (result.get().code() == Retcode.SUCC) accepted++;
      assertEquals(1, accepted);
      assertEquals(1, s.racer.getGameplay().getParkour().getRecords().size());
      assertEquals(0, s.racer.getGameplay().getParkour().getActiveLevel());
    } finally {
      executor.shutdownNow();
    }
  }
}
