package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ActivityPanelExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ParkourRanking46Test {
  static final int UID = 123456783, FIRST = 19993501, SUB = 19993505;
  @TempDir Path root;
  Parkour46Test.Session s;
  ActivityPanelExcel prior;

  @BeforeEach
  void setup() throws Exception {
    Files.createDirectories(root.resolve("ExcelOutput"));
    Files.writeString(
        root.resolve("ExcelOutput/ParkourRankingList.json"),
        """
        [{"RailBallID":6,"LevelBestRecordList":[
        {"MLDLOJJBIJD":19993501,"GAFENHHEBPG":50000},
        {"MLDLOJJBIJD":19993502,"GAFENHHEBPG":60000},
        {"MLDLOJJBIJD":19993503,"GAFENHHEBPG":70000}]}]
        """);
    GameplayDefinitions.load(root);
    s = new Parkour46Test.Session();
    var uid = Player.class.getDeclaredField("uid");
    uid.setAccessible(true);
    uid.setInt(s.racer, UID);
    prior = GameData.getActivityPanelExcelMap().get(50039);
    GameData.getActivityPanelExcelMap()
        .add(new com.google.gson.Gson().fromJson("{\"PanelID\":50039}", ActivityPanelExcel.class));
    GameplayDefinitions.windows.add(new GameplayDefinitions.Window(50039, 5003901, 0, 4102444800L));
    GameplayDefinitions.parkourConstants.put("parkour_activity_module_id", 5003901);
    GameplayDefinitions.parkourConstants.put("parkour_gameplay_floor_id", Parkour46Test.FLOOR);
    for (int id = FIRST; id < FIRST + 3; id++)
      GameplayDefinitions.parkourLevels.put(
          id,
          new GameplayDefinitions.ParkourLevel(id, "FinishSubMission", SUB, 0, 3, 3, "race.asset"));
    GameplayDefinitions.parkourBalls.put(1, new GameplayDefinitions.ParkourBall(1, 0, 0));
    GameplayDefinitions.parkourBalls.put(2, new GameplayDefinitions.ParkourBall(2, 0, 0));
    var sub = new QuestState(SUB);
    sub.advance(1, 1, 1);
    s.racer.getGameplay().getSubMissions().put(SUB, sub);
  }

  @AfterEach
  void clear() {
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.parkourLevels.clear();
    GameplayDefinitions.parkourBalls.clear();
    GameplayDefinitions.parkourConstants.clear();
    GameplayDefinitions.parkourRankings.clear();
    if (prior == null) GameData.getActivityPanelExcelMap().keySet().remove(50039);
    else GameData.getActivityPanelExcelMap().add(prior);
  }

  DynamicMessage query(byte[] bytes) throws Exception {
    assertTrue(VersionedPacketHandler.handle(s, 8397, bytes));
    assertEquals("ParkourGetRankingInfoScRsp", s.responseName);
    return s.response;
  }

  List<DynamicMessage> rows(DynamicMessage m, int field) {
    return ((List<?>) m.getField(m.getDescriptorForType().findFieldByNumber(field)))
        .stream().map(DynamicMessage.class::cast).toList();
  }

  DynamicMessage group(DynamicMessage m, int level) {
    return rows(m, 14).stream()
        .filter(x -> Gameplay46.number(x, "level_id") == level)
        .findFirst()
        .orElseThrow();
  }

  void race(int id, int ball, int time) {
    var manager = new ParkourManager(s.racer);
    assertEquals(Retcode.SUCC, manager.start(id, ball));
    assertEquals(Retcode.SUCC, manager.finish(id, ball, time, time + 1000, 1, 2).code());
  }

  @Test
  void unplayedRankingSuppliesResourceLevelsAndANonNullAggregateWithoutInventingPeople()
      throws Exception {
    var r = query(new byte[0]);
    assertEquals(0, Gameplay46.number(r, "retcode"));
    assertTrue(r.hasField(r.getDescriptorForType().findFieldByNumber(12)), "客户端会读取汇总消息，不能省略对象");
    assertEquals(3, rows(r, 14).size());
    for (var row : rows(r, 14)) assertTrue(rows(row, 8).isEmpty());
    assertTrue(
        rows((DynamicMessage) r.getField(r.getDescriptorForType().findFieldByNumber(12)), 8)
            .isEmpty());
  }

  @Test
  void nativeFieldsExposeOnlyActualBestTimeUidAndTheCarThatProducedIt() throws Exception {
    race(FIRST, 1, 50000);
    race(FIRST + 1, 1, 60000);
    race(FIRST + 2, 1, 70000);
    race(FIRST, 2, 55000);
    var r = query(new byte[0]);
    var row = rows(group(r, FIRST), 8).getFirst();
    assertEquals(50000, Gameplay46.number(row, "time"));
    assertEquals(UID, Gameplay46.number(row, "id"));
    assertEquals(1, Gameplay46.number(row, "LJJGFIPLDLI"));
    var aggregate = (DynamicMessage) r.getField(r.getDescriptorForType().findFieldByNumber(12));
    assertEquals(0, Gameplay46.number(aggregate, "level_id"));
    assertEquals(180000, Gameplay46.number(rows(aggregate, 8).getFirst(), "time"));
    race(FIRST, 2, 49000);
    r = query(new byte[0]);
    assertEquals(2, Gameplay46.number(rows(group(r, FIRST), 8).getFirst(), "LJJGFIPLDLI"));
    aggregate = (DynamicMessage) r.getField(r.getDescriptorForType().findFieldByNumber(12));
    assertTrue(rows(aggregate, 8).isEmpty(), "混合球车的官方汇总展示规则未核对，不能选择虚构球车");
  }

  @Test
  void oldRecordsWithoutCarEvidenceAndNonRankingLevelsDoNotBecomeRankingRows() throws Exception {
    s.racer
        .getGameplay()
        .getParkour()
        .getRecords()
        .computeIfAbsent(FIRST, k -> new GameplayState.ParkourRecord())
        .record(40000, 1, true);
    s.racer
        .getGameplay()
        .getParkour()
        .getRecords()
        .computeIfAbsent(FIRST + 9, k -> new GameplayState.ParkourRecord())
        .record(1, 1, true);
    var r = query(new byte[0]);
    assertEquals(3, rows(r, 14).size());
    for (var row : rows(r, 14)) assertTrue(rows(row, 8).isEmpty());
  }

  @Test
  void unknownQueryFieldsAreRejectedWithoutChangingRaceHistory() throws Exception {
    race(FIRST, 1, 50000);
    assertNotEquals(0, Gameplay46.number(query(new byte[] {8, 1}), "retcode"));
    assertEquals(50000, s.racer.getGameplay().getParkour().getRecords().get(FIRST).getTime());
  }

  @Test
  void missingOrMalformedRankingResourcesReturnAnError() throws Exception {
    GameplayDefinitions.parkourRankings.clear();
    assertNotEquals(0, Gameplay46.number(query(new byte[0]), "retcode"));
    GameplayDefinitions.parkourRankings.add(
        new GameplayDefinitions.ParkourRankReference(
            6, List.of(new GameplayDefinitions.ParkourRankReferenceTime(FIRST + 9, 50000))));
    assertNotEquals(0, Gameplay46.number(query(new byte[0]), "retcode"));
    GameplayDefinitions.parkourRankings.clear();
    GameplayDefinitions.parkourRankings.add(
        new GameplayDefinitions.ParkourRankReference(
            6, Arrays.asList((GameplayDefinitions.ParkourRankReferenceTime) null)));
    assertNotEquals(0, Gameplay46.number(query(new byte[0]), "retcode"));
  }

  @Test
  void bestCarAndNativeRankingSurviveRealMorphiaReload() throws Exception {
    race(FIRST, 2, 50000);
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "parkourranking");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, UID);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, s.racer.getGameplay());
      store.save(document);
      gameplay.set(s.racer, store.find(Player.class).first().getGameplay());
      var row = rows(group(query(new byte[0]), FIRST), 8).getFirst();
      assertEquals(50000, Gameplay46.number(row, "time"));
      assertEquals(2, Gameplay46.number(row, "LJJGFIPLDLI"));
      assertEquals(UID, Gameplay46.number(row, "id"));
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void resourceNpcsWithoutFinishTimesDoNotBlockTheWholeRankingOrBecomePlayers() throws Exception {
    GameplayDefinitions.parkourRankings.add(
        new GameplayDefinitions.ParkourRankReference(
            1,
            List.of(
                new GameplayDefinitions.ParkourRankReferenceTime(FIRST, 0),
                new GameplayDefinitions.ParkourRankReferenceTime(FIRST + 1, 0),
                new GameplayDefinitions.ParkourRankReferenceTime(FIRST + 2, 0))));
    var r = query(new byte[0]);
    assertEquals(0, Gameplay46.number(r, "retcode"));
    assertEquals(3, rows(r, 14).size());
    for (var row : rows(r, 14)) assertTrue(rows(row, 8).isEmpty());
  }
}
