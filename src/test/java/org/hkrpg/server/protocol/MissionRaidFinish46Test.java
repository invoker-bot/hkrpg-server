package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

/** Real instance completion, ordinary return, and the parent's authored finish conditions. */
class MissionRaidFinish46Test {
  final MissionRaid46Test fixture = new MissionRaid46Test();
  static final int GOAL = 19997991, HOLD = 19997992;

  @BeforeEach
  void setup() {
    fixture.setup();
    parent("RaidFinishCnt");
  }

  @AfterEach
  void cleanup() {
    GameData.getMissionInfos().remove(fixture.BEFORE);
    GameplayDefinitions.subs.remove(GOAL);
    GameplayDefinitions.subs.remove(HOLD);
    fixture.cleanup();
  }

  void parent(String type) {
    var info =
        fixture.gson.fromJson(
            "{\"MainMissionID\":"
                + fixture.BEFORE
                + ",\"FinishSubMissionList\":["
                + HOLD
                + "],\"SubMissionList\":["
                + "{\"ID\":"
                + GOAL
                + ",\"MainMissionID\":"
                + fixture.BEFORE
                + ",\"TakeType\":\"Auto\",\"FinishType\":\""
                + type
                + "\",\"ParamType\":\""
                + (type.equals("RaidFinishCnt")
                    ? "ListContain\",\"ParamIntList\":[" + fixture.RAID + "]"
                    : "Equal\",\"ParamInt1\":" + fixture.RAID)
                + ",\"Progress\":1,\"LevelPlaneID\":"
                + fixture.HOME_PLANE
                + ",\"LevelFloorID\":"
                + fixture.HOME_FLOOR
                + "},"
                + "{\"ID\":"
                + HOLD
                + ",\"MainMissionID\":"
                + fixture.BEFORE
                + ",\"TakeType\":\"Auto\",\"FinishType\":\"Talk\",\"ParamType\":\"Equal\",\"ParamStr1\":\"hold\"}]}",
            MissionInfo.class);
    GameData.getMissionInfos().put(fixture.BEFORE, info);
    for (var row : info.getSubMissionList()) GameplayDefinitions.subs.put(row.getId(), row);
    fixture.session.actor.getMissionManager().refresh();
  }

  int progress() {
    return fixture.session.actor.getMissionManager().sub(GOAL).getProgress();
  }

  void completeRaid() throws Exception {
    assertEquals(0, fixture.start());
    assertEquals(
        Retcode.SUCC, fixture.session.actor.getMissionManager().finishTalk(fixture.FIRST, "first"));
    assertEquals(
        Retcode.SUCC, fixture.session.actor.getMissionManager().finishTalk(fixture.LAST, "last"));
    assertTrue(fixture.session.actor.getMissionManager().finished(fixture.MAIN));
  }

  void reload() throws Exception {
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(
        fixture.session.actor,
        fixture.gson.fromJson(
            fixture.gson.toJson(fixture.session.actor.getGameplay()), GameplayState.class));
  }

  @Test
  void anActualCompletedRaidSatisfiesItsParentsCountAfterReturningToTheAuthoredFloor()
      throws Exception {
    assertEquals(0, progress());
    completeRaid();
    assertEquals(0, progress());
    assertEquals(0, fixture.leave(fixture.RAID, false));
    assertEquals(1, progress(), "成功返回应主动刷新副本完成目标");
    fixture.session.actor.getMissionManager().refresh();
    fixture.session.actor.getMissionManager().refresh();
    assertEquals(1, progress());
    assertEquals(
        QuestState.Phase.DOING,
        fixture.session.actor.getGameplay().getMainMissions().get(fixture.BEFORE).getPhase());
  }

  @Test
  void transferCompletionRequiresTheActualSuccessfulReturnAndCannotBeReplacedByTheFinishFlag()
      throws Exception {
    parent("RaidFinishedAndTransfer");
    var json = fixture.gson.toJsonTree(GameplayDefinitions.subs.get(GOAL)).getAsJsonObject();
    json.addProperty("LevelPlaneID", 0);
    json.addProperty("LevelFloorID", 0);
    GameplayDefinitions.subs.put(
        GOAL, fixture.gson.fromJson(json, MissionInfo.SubMissionInfo.class));
    completeRaid();
    assertEquals(0, progress(), "无地点限制也不能把场内通关当作已经返回");
    fixture.session.actor.rejectScene = true;
    assertNotEquals(0, fixture.leave(fixture.RAID, false));
    fixture.session.actor.getMissionManager().refresh();
    assertEquals(0, progress());
    fixture.session.actor.rejectScene = false;
    assertEquals(0, fixture.leave(fixture.RAID, false));
    assertEquals(1, progress());
  }

  @Test
  void pausedAndWrongRaidHistoryCannotSatisfyEitherParentFinishCondition() throws Exception {
    for (String type : List.of("RaidFinishCnt", "RaidFinishedAndTransfer")) {
      parent(type);
      assertEquals(0, fixture.start());
      assertEquals(0, fixture.leave(fixture.RAID, false));
      fixture.session.actor.getMissionManager().refresh();
      assertEquals(0, progress());
    }
    var fake =
        new GameplayState.MissionRaidRun(
            fixture.RAID + 1, 0, fixture.MAIN, fixture.PLANE, fixture.FLOOR, fixture.ENTRY);
    fake.complete();
    fixture.session.actor.getGameplay().getMissionRaids().put(fixture.RAID + 1, fake);
    fixture.session.actor.getMissionManager().refresh();
    assertEquals(0, progress());
  }

  @Test
  void aSavedCompletionFlagWithoutTheMainAndFinalSubEvidenceCannotCompleteTheParent()
      throws Exception {
    assertEquals(0, fixture.start());
    fixture.session.actor.getGameplay().getMissionRaids().get(fixture.RAID).complete();
    assertEquals(0, fixture.leave(fixture.RAID, false));
    fixture.session.actor.getMissionManager().refresh();
    assertEquals(0, progress());
    var wrong = new QuestState(fixture.MAIN + 1);
    wrong.advance(1, 1, 1);
    fixture.session.actor.getGameplay().getMainMissions().put(fixture.MAIN, wrong);
    fixture.session.actor.getMissionManager().refresh();
    assertEquals(0, progress());
  }

  @Test
  void completedReturnAndParentProgressSurviveReloadWithoutRepeatedAdvancement() throws Exception {
    parent("RaidFinishedAndTransfer");
    completeRaid();
    assertEquals(0, fixture.leave(fixture.RAID, false));
    assertEquals(1, progress());
    reload();
    fixture.session.actor.getMissionManager().refresh();
    assertEquals(1, progress());
    assertFalse(fixture.session.actor.getMissionManager().finished(fixture.BEFORE));
  }

  @Test
  void aCancelledOrForeignRaidMissionCannotBecomeAValidHistoricalCount() throws Exception {
    completeRaid();
    assertEquals(0, fixture.leave(fixture.RAID, false));
    fixture.session.actor.getGameplay().getSubMissions().put(GOAL, new QuestState(GOAL));
    fixture.session.actor.getGameplay().getCancelledMissions().add(fixture.MAIN);
    fixture.session.actor.getMissionManager().refresh();
    assertEquals(0, progress());
    fixture.session.actor.getGameplay().getCancelledMissions().remove(fixture.MAIN);
    fixture.session.actor.getGameplay().getCancelledSubs().add(fixture.LAST);
    fixture.session.actor.getMissionManager().refresh();
    assertEquals(0, progress());
    fixture.session.actor.getGameplay().getCancelledSubs().remove(fixture.LAST);
    fixture.session.actor.getMissionManager().refresh();
    assertEquals(1, progress());
  }

  @Test
  void aFinishedRaidWhoseOwnershipWasOnlyClearedHasNoInventedReturnReceipt() throws Exception {
    parent("RaidFinishedAndTransfer");
    completeRaid();
    fixture.session.actor.getGameplay().endMissionRaid();
    fixture.session.actor.entry = fixture.HOME;
    fixture.session.actor.plane = fixture.HOME_PLANE;
    fixture.session.actor.floor = fixture.HOME_FLOOR;
    fixture.session.actor.getMissionManager().refresh();
    assertEquals(0, progress(), "清空实例号或普通坐标不能代替已保存的实际完成返回");
  }

  @Test
  void aCorruptedReturnPositionIsRejectedAndTheSavedReceiptIsNotRepaired() throws Exception {
    parent("RaidFinishedAndTransfer");
    completeRaid();
    assertEquals(0, fixture.leave(fixture.RAID, false));
    var json = fixture.gson.toJsonTree(fixture.session.actor.getGameplay()).getAsJsonObject();
    var run = json.getAsJsonObject("missionRaids").getAsJsonObject(Integer.toString(fixture.RAID));
    run.getAsJsonObject("finishedReturn").getAsJsonObject("pos").addProperty("x", 999);
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(fixture.session.actor, fixture.gson.fromJson(json, GameplayState.class));
    fixture.session.actor.getGameplay().getSubMissions().put(GOAL, new QuestState(GOAL));
    String receipt = fixture.gson.toJson(fixture.session.actor.getGameplay().getMissionRaids());
    fixture.session.actor.getMissionManager().refresh();
    assertEquals(0, progress());
    assertEquals(
        receipt, fixture.gson.toJson(fixture.session.actor.getGameplay().getMissionRaids()));
  }

  @Test
  void anExplicitFinishEntranceProducesItsOwnValidTransferReceipt() throws Exception {
    parent("RaidFinishedAndTransfer");
    int finish = fixture.HOME + 100;
    GameData.getMapEntranceExcelMap()
        .add(
            fixture.gson.fromJson(
                "{\"ID\":"
                    + finish
                    + ",\"PlaneID\":"
                    + fixture.HOME_PLANE
                    + ",\"FloorID\":"
                    + fixture.HOME_FLOOR
                    + "}",
                emu.lunarcore.data.excel.MapEntranceExcel.class));
    try {
      var row = fixture.gson.toJsonTree(fixture.resource).getAsJsonObject();
      row.addProperty("FinishEntranceID", finish);
      GameplayDefinitions.raids.put(
          fixture.resource.key(), fixture.gson.fromJson(row, GameplayDefinitions.Raid.class));
      completeRaid();
      assertEquals(0, progress());
      assertEquals(0, fixture.leave(fixture.RAID, false));
      assertEquals(finish, fixture.session.actor.entry);
      assertEquals(1, progress());
      assertEquals(
          finish,
          fixture
              .session
              .actor
              .getGameplay()
              .getMissionRaids()
              .get(fixture.RAID)
              .getFinishedReturn()
              .getEntry());
    } finally {
      GameData.getMapEntranceExcelMap().keySet().remove(finish);
    }
  }

  @Test
  void morphiaRestoresTheActualFinishedReturnBeforeEvaluatingAHistoricalTransfer()
      throws Exception {
    parent("RaidFinishedAndTransfer");
    completeRaid();
    assertEquals(0, fixture.leave(fixture.RAID, false));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "raid-finished-return");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var document = new Player();
      var field = Player.class.getDeclaredField("gameplay");
      field.setAccessible(true);
      field.set(document, fixture.session.actor.getGameplay());
      store.save(document);
      field.set(fixture.session.actor, store.find(Player.class).first().getGameplay());
      var run = fixture.session.actor.getGameplay().getMissionRaids().get(fixture.RAID);
      assertNotNull(run.getFinishedReturn());
      assertEquals(fixture.HOME, run.getFinishedReturn().getEntry());
      assertEquals(12, run.getFinishedReturn().getPos().getX());
      fixture.session.actor.getGameplay().getSubMissions().put(GOAL, new QuestState(GOAL));
      fixture.session.actor.getMissionManager().refresh();
      assertEquals(1, progress());
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void unsupportedParametersOrFinishActionsDoNotConsumeHistoricalCompletion() throws Exception {
    completeRaid();
    var original = fixture.gson.toJsonTree(GameplayDefinitions.subs.get(GOAL)).getAsJsonObject();
    original.addProperty("ParamInt2", 1);
    GameplayDefinitions.subs.put(
        GOAL, fixture.gson.fromJson(original, MissionInfo.SubMissionInfo.class));
    assertEquals(0, fixture.leave(fixture.RAID, false));
    fixture.session.actor.getMissionManager().refresh();
    assertEquals(0, progress());
    original.remove("ParamInt2");
    original.add(
        "FinishActionList",
        JsonParser.parseString("[{\"FinishActionType\":\"UnknownRaidEffect\"}]"));
    GameplayDefinitions.subs.put(
        GOAL, fixture.gson.fromJson(original, MissionInfo.SubMissionInfo.class));
    fixture.session.actor.getMissionManager().refresh();
    assertEquals(0, progress());
    original.add("FinishActionList", new JsonArray());
    GameplayDefinitions.subs.put(
        GOAL, fixture.gson.fromJson(original, MissionInfo.SubMissionInfo.class));
    fixture.session.actor.getMissionManager().refresh();
    assertEquals(1, progress());
  }
}
