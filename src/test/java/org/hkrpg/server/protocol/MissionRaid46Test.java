package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.proto.SceneInfoOuterClass.SceneInfo;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.util.Position;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

/** Native raid requests must preserve the authored task and real return location. */
class MissionRaid46Test {
  static final int RAID = 49979,
      PLANE = 49979,
      FLOOR = 49979001,
      ENTRY = 499791,
      NEXT_FLOOR = 49979002,
      NEXT_ENTRY = 499792,
      HOME = 1999791,
      HOME_PLANE = 199980,
      HOME_FLOOR = 19998001,
      MAIN = 199979,
      BEFORE = 19997990,
      FIRST = 19997901,
      TRANSFER = 19997902,
      LAST = 19997903,
      AVATAR = 19997911;
  final Gson gson = new Gson();

  static class Actor extends Player {
    int entry = HOME, plane = HOME_PLANE, floor = HOME_FLOOR;
    boolean rejectScene, battle;
    final Position pos = new Position(12, 34, 56), rot = new Position(0, 90, 0);
    final LineupManager teams = new LineupManager(this);
    final GameAvatar avatar =
        new GameAvatar() {
          public int getAvatarId() {
            return AVATAR;
          }

          public int getMaxSp() {
            return 10000;
          }

          public void save() {}
        };

    Actor() {
      getAvatars().getAvatars().put(AVATAR, avatar);
      teams.getCurrentLineup().getAvatars().add(AVATAR);
    }

    public LineupManager getLineupManager() {
      return teams;
    }

    public GameAvatar getAvatarById(int id) {
      return id == AVATAR ? avatar : null;
    }

    public int getEntryId() {
      return entry;
    }

    public int getPlaneId() {
      return plane;
    }

    public int getFloorId() {
      return floor;
    }

    public Position getPos() {
      return pos;
    }

    public Position getRot() {
      return rot;
    }

    public boolean isInBattle() {
      return battle;
    }

    public void save() {}

    public void sendPacket(BasePacket packet) {}

    public Scene getScene() {
      var excel = GameData.getMazePlaneExcelMap().get(plane);
      return new Scene(this, excel, floor) {
        public synchronized SceneInfo toProto() {
          return SceneInfo.newInstance()
              .setPlaneId(plane)
              .setFloorId(floor)
              .setEntryId(entry)
              .setGameModeType(excel.getPlaneType().getVal());
        }
      };
    }

    public boolean enterScene(int id, int teleport, boolean send) {
      var target = GameData.getMapEntranceExcelMap().get(id);
      if (rejectScene || target == null) return false;
      return loadScene(
          target.getPlaneID(),
          target.getFloorID(),
          id,
          new Position(1, 2, 3),
          new Position(0, 0, 0),
          send);
    }

    public boolean loadScene(
        int p, int f, int id, Position position, Position rotation, boolean send) {
      if (rejectScene) return false;
      entry = id;
      plane = p;
      floor = f;
      pos.set(position);
      rot.set(rotation);
      return true;
    }
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final Actor actor = new Actor();
    final Map<String, DynamicMessage> packets = new HashMap<>();

    public Player getPlayer() {
      return actor;
    }

    public void send(BasePacket packet) {
      if (packet.getCmdId() == CmdId.EnterSceneByServerScNotify) {
        var wire = getProtocol().outbound(packet.getCmdId(), packet.getData().toByteArray());
        sendClientPacket("EnterSceneByServerScNotify", wire.body());
      }
    }

    public void sendClientPacket(String name, byte[] bytes) {
      super.sendClientPacket(name, bytes);
      packets.put(name, response);
    }
  }

  Session session;
  GameplayDefinitions.Raid resource;

  @BeforeEach
  void setup() {
    for (int[] row :
        List.of(
            new int[] {HOME, HOME_PLANE, HOME_FLOOR},
            new int[] {ENTRY, PLANE, FLOOR},
            new int[] {NEXT_ENTRY, PLANE, NEXT_FLOOR}))
      GameData.getMapEntranceExcelMap()
          .add(
              gson.fromJson(
                  "{\"ID\":" + row[0] + ",\"PlaneID\":" + row[1] + ",\"FloorID\":" + row[2] + "}",
                  MapEntranceExcel.class));
    GameData.getMazePlaneExcelMap()
        .add(
            gson.fromJson(
                "{\"PlaneID\":" + HOME_PLANE + ",\"PlaneType\":\"Town\"}", MazePlaneExcel.class));
    GameData.getMazePlaneExcelMap()
        .add(
            gson.fromJson(
                "{\"PlaneID\":" + PLANE + ",\"PlaneType\":\"Raid\"}", MazePlaneExcel.class));
    resource =
        gson.fromJson(
            "{\"RaidID\":"
                + RAID
                + ",\"WorldLevel\":0,\"Type\":\"Mission\","
                + "\"TeamType\":\"Player\",\"EnterType\":\"SkipUI\",\"SkipRewardOnFinish\":true,"
                + "\"MainMissionIDList\":["
                + MAIN
                + "],\"MainMissionIDBefore\":"
                + BEFORE
                + ","
                + "\"MainMissionIDAfter\":"
                + BEFORE
                + ",\"TrialAvatarList\":[],\"RaidTargetID\":[],"
                + "\"RewardList\":[],\"BuffParamList\":[],\"TeamLimitIDList\":[],\"LimitIDList\":[],"
                + "\"RecoverType\":[\"Unknown\"],\"UnlockWorldLevel\":[]}",
            GameplayDefinitions.Raid.class);
    GameplayDefinitions.raids.put(resource.key(), resource);
    GameData.getMainMissionExcelMap()
        .add(
            gson.fromJson(
                "{\"MainMissionID\":"
                    + MAIN
                    + ",\"IsInRaid\":true,\"TakeParam\":[{\"Type\":\"Manual\"}],\"BeginParam\":[{\"Type\":\"Auto\"}]}",
                MainMissionExcel.class));
    GameData.getMainMissionExcelMap()
        .add(gson.fromJson("{\"MainMissionID\":" + BEFORE + "}", MainMissionExcel.class));
    var info =
        gson.fromJson(
            "{\"MainMissionID\":"
                + MAIN
                + ",\"StartSubMissionList\":["
                + FIRST
                + "],\"FinishSubMissionList\":["
                + LAST
                + "],\"SubMissionList\":["
                + "{\"ID\":"
                + FIRST
                + ",\"MainMissionID\":"
                + MAIN
                + ",\"TakeType\":\"Auto\",\"FinishType\":\"Talk\",\"ParamType\":\"Equal\",\"ParamStr1\":\"first\",\"LevelPlaneID\":"
                + PLANE
                + ",\"LevelFloorID\":"
                + FLOOR
                + "},"
                + "{\"ID\":"
                + TRANSFER
                + ",\"MainMissionID\":"
                + MAIN
                + ",\"TakeType\":\"AnySequence\",\"TakeParamIntList\":["
                + FIRST
                + "],\"FinishType\":\"EnterRaidScene\",\"ParamType\":\"Equal\",\"ParamInt1\":"
                + RAID
                + ",\"ParamInt2\":"
                + NEXT_ENTRY
                + ",\"LevelPlaneID\":"
                + PLANE
                + ",\"LevelFloorID\":"
                + FLOOR
                + "},"
                + "{\"ID\":"
                + LAST
                + ",\"MainMissionID\":"
                + MAIN
                + ",\"TakeType\":\"AnySequence\",\"TakeParamIntList\":["
                + TRANSFER
                + "],\"FinishType\":\"Talk\",\"ParamType\":\"Equal\",\"ParamStr1\":\"last\",\"LevelPlaneID\":"
                + PLANE
                + ",\"LevelFloorID\":"
                + NEXT_FLOOR
                + "}]}",
            MissionInfo.class);
    GameData.getMissionInfos().put(MAIN, info);
    for (var sub : info.getSubMissionList()) GameplayDefinitions.subs.put(sub.getId(), sub);
    session = new Session();
    session.actor.getGameplay().getMainMissions().put(BEFORE, new QuestState(BEFORE));
  }

  @AfterEach
  void cleanup() {
    GameplayDefinitions.raids.remove(resource.key());
    GameData.getMissionInfos().remove(MAIN);
    for (int id : List.of(FIRST, TRANSFER, LAST)) GameplayDefinitions.subs.remove(id);
    for (int id : List.of(MAIN, BEFORE)) GameData.getMainMissionExcelMap().keySet().remove(id);
    for (int id : List.of(HOME_PLANE, PLANE)) GameData.getMazePlaneExcelMap().keySet().remove(id);
    for (int id : List.of(HOME, ENTRY, NEXT_ENTRY))
      GameData.getMapEntranceExcelMap().keySet().remove(id);
  }

  int start() throws Exception {
    var request = Gameplay46.builder(session, "StartRaidCsReq");
    Gameplay46.set(request, "raid_id", RAID);
    assertTrue(VersionedPacketHandler.handle(session, 2234, request.build().toByteArray()));
    return Gameplay46.number(session.packets.get("StartRaidScRsp"), "retcode");
  }

  int leave(int id, boolean save) throws Exception {
    var request = Gameplay46.builder(session, "LeaveRaidCsReq");
    Gameplay46.set(request, "raid_id", id);
    Gameplay46.set(request, "is_save", save);
    assertTrue(VersionedPacketHandler.handle(session, 2220, request.build().toByteArray()));
    return Gameplay46.number(session.packets.get("LeaveRaidScRsp"), "retcode");
  }

  @Test
  void ordinaryTeleportCannotAbandonTheActiveRaidOrReplaceItsCheckpoint() throws Exception {
    assertEquals(0, start());
    var run = session.actor.getGameplay().getMissionRaids().get(RAID);
    var checkpoint = gson.toJson(run);
    var request = Gameplay46.builder(session, "EnterSceneCsReq");
    Gameplay46.set(request, "entry_id", HOME);
    assertTrue(
        VersionedPacketHandler.handle(
            session,
            session.getProtocol().clientCommand("EnterSceneCsReq"),
            request.build().toByteArray()));
    assertEquals(
        Retcode.SYSTEM_BUSY.getVal(),
        Gameplay46.number(session.packets.get("EnterSceneScRsp"), "retcode"));
    assertEquals(ENTRY, session.actor.entry);
    assertEquals(RAID, session.actor.getGameplay().getCurrentMissionRaid());
    assertEquals(checkpoint, gson.toJson(run));
    assertEquals(0, leave(RAID, false));
    assertTrue(
        VersionedPacketHandler.handle(
            session,
            session.getProtocol().clientCommand("EnterSceneCsReq"),
            request.build().toByteArray()));
    assertEquals(0, Gameplay46.number(session.packets.get("EnterSceneScRsp"), "retcode"));
  }

  @Test
  void aRaidOnlyMissionCannotBeAcceptedInTheOrdinaryWorld() {
    assertEquals(
        Retcode.MISSION_NOT_DOING,
        session.actor.getMissionManager().accept(MAIN),
        "普通接取请求不得绕开 IsInRaid 与当前副本归属");
    assertFalse(session.actor.getGameplay().getMainMissions().containsKey(MAIN));
  }

  @Test
  void nativeEntryUsesTheAuthoredStartFloorAndPreservesTheParentAndFormalTeam() throws Exception {
    assertEquals(0, start(), "已接父任务的 Player/Mission 副本必须实际进入");
    assertEquals(ENTRY, session.actor.entry, "入口须按资源查找，不能由副本号拼接");
    assertEquals(FLOOR, session.actor.floor);
    assertEquals(List.of(AVATAR), session.actor.getCurrentLineup().getAvatars());
    assertEquals(
        QuestState.Phase.DOING,
        session.actor.getGameplay().getMainMissions().get(BEFORE).getPhase());
    assertNotNull(session.actor.getMissionManager().sub(FIRST));
    assertNotEquals(0, start(), "重复进入不得改写返回点");
    assertEquals(0, leave(RAID, false));
    assertEquals(HOME, session.actor.entry);
    assertEquals(12f, session.actor.pos.getX());
    assertEquals(90f, session.actor.rot.getY());
  }

  @Test
  void authoredEnterRaidSceneMovesThenCompletesAndRealTalkFinishesTheRaidMission()
      throws Exception {
    assertEquals(0, start());
    assertEquals(Retcode.SUCC, session.actor.getMissionManager().finishTalk(FIRST, "first"));
    assertEquals(NEXT_ENTRY, session.actor.entry, "任务动作应实际换层后才完成");
    assertNotEquals(
        QuestState.Phase.DOING, session.actor.getMissionManager().sub(TRANSFER).getPhase());
    assertEquals(Retcode.SUCC, session.actor.getMissionManager().finishTalk(LAST, "last"));
    assertTrue(session.actor.getMissionManager().finished(MAIN));
    assertEquals(0, leave(RAID, false));
    assertEquals(HOME, session.actor.entry);
  }

  @Test
  void failedEntryAndFailedReturnKeepTheirRespectiveLocationAndTaskProgress() throws Exception {
    session.actor.rejectScene = true;
    assertNotEquals(0, start());
    assertEquals(HOME, session.actor.entry);
    assertFalse(session.actor.getGameplay().getMainMissions().containsKey(MAIN));
    session.actor.rejectScene = false;
    assertEquals(0, start());
    var state = session.actor.getMissionManager().sub(FIRST);
    assertNotEquals(0, leave(RAID + 1, false));
    assertNotEquals(0, leave(RAID, true));
    session.actor.rejectScene = true;
    assertNotEquals(0, leave(RAID, false));
    assertEquals(ENTRY, session.actor.entry);
    assertSame(state, session.actor.getMissionManager().sub(FIRST));
    session.actor.rejectScene = false;
    assertEquals(0, leave(RAID, false));
  }

  @Test
  void leavingPausesTheRaidTaskAndReentryResumesItsActualFloorWithoutDeletingHistory()
      throws Exception {
    assertEquals(0, start());
    assertEquals(Retcode.SUCC, session.actor.getMissionManager().finishTalk(FIRST, "first"));
    var state = session.actor.getMissionManager().sub(FIRST);
    assertEquals(0, leave(RAID, false));
    assertEquals(
        Retcode.MISSION_NOT_DOING, session.actor.getMissionManager().finishTalk(LAST, "last"));
    session.actor.getMissionManager().refresh();
    assertSame(state, session.actor.getMissionManager().sub(FIRST));
    assertEquals(0, start());
    assertEquals(NEXT_ENTRY, session.actor.entry);
    assertSame(state, session.actor.getMissionManager().sub(FIRST));
    assertEquals(Retcode.SUCC, session.actor.getMissionManager().finishTalk(LAST, "last"));
  }

  void restore(com.google.gson.JsonObject state) throws Exception {
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(session.actor, gson.fromJson(state, GameplayState.class));
  }

  @Test
  void aMissingCancelledOrMismatchedParentCannotAuthorizeEntry() throws Exception {
    var states = session.actor.getGameplay().getMainMissions();
    states.remove(BEFORE);
    assertNotEquals(0, start());
    states.put(BEFORE, new QuestState(BEFORE));
    session.actor.getGameplay().getCancelledMissions().add(BEFORE);
    assertNotEquals(0, start());
    session.actor.getGameplay().getCancelledMissions().clear();
    states.put(BEFORE, new QuestState(BEFORE + 1));
    assertNotEquals(0, start());
    assertEquals(HOME, session.actor.entry);
    assertFalse(states.containsKey(MAIN));
  }

  @Test
  void anAmbiguousResourceEntranceAndUnsupportedTeamRestrictionCannotEnter() throws Exception {
    int alias = ENTRY + 100;
    GameData.getMapEntranceExcelMap()
        .add(
            gson.fromJson(
                "{\"ID\":" + alias + ",\"PlaneID\":" + PLANE + ",\"FloorID\":" + FLOOR + "}",
                MapEntranceExcel.class));
    try {
      assertNotEquals(0, start());
    } finally {
      GameData.getMapEntranceExcelMap().keySet().remove(alias);
    }
    var row = gson.toJsonTree(resource).getAsJsonObject();
    row.addProperty("TeamType", "TrialOnly");
    GameplayDefinitions.raids.put(
        resource.key(), gson.fromJson(row, GameplayDefinitions.Raid.class));
    assertNotEquals(0, start());
    row.addProperty("TeamType", "Player");
    row.add("TeamLimitIDList", gson.toJsonTree(List.of(14)));
    GameplayDefinitions.raids.put(
        resource.key(), gson.fromJson(row, GameplayDefinitions.Raid.class));
    assertNotEquals(0, start());
    assertEquals(HOME, session.actor.entry);
  }

  @Test
  void failedTransferRetainsTheSourceAndCanRetryWithoutRepeatingTheFirstTalk() throws Exception {
    assertEquals(0, start());
    session.actor.rejectScene = true;
    assertEquals(Retcode.SUCC, session.actor.getMissionManager().finishTalk(FIRST, "first"));
    assertEquals(ENTRY, session.actor.entry);
    assertEquals(
        QuestState.Phase.DOING, session.actor.getMissionManager().sub(TRANSFER).getPhase());
    assertEquals(0, session.actor.getGameplay().getMissionRaids().get(RAID).getPendingTransfer());
    session.actor.rejectScene = false;
    session.actor.getMissionManager().refresh();
    assertEquals(NEXT_ENTRY, session.actor.entry);
    assertEquals(
        QuestState.Phase.FINISHED, session.actor.getMissionManager().sub(TRANSFER).getPhase());
  }

  @Test
  void unsupportedTransferActionsDoNotMoveOrManufactureACompletion() throws Exception {
    var row = gson.toJsonTree(GameplayDefinitions.subs.get(TRANSFER)).getAsJsonObject();
    row.add(
        "FinishActionList",
        com.google.gson.JsonParser.parseString("[{\"FinishActionType\":\"UnknownRaidEffect\"}]"));
    var sub = gson.fromJson(row, MissionInfo.SubMissionInfo.class);
    GameplayDefinitions.subs.put(TRANSFER, sub);
    var subs = GameData.getMissionInfos().get(MAIN).getSubMissionList();
    subs[1] = sub;
    assertEquals(0, start());
    assertEquals(Retcode.SUCC, session.actor.getMissionManager().finishTalk(FIRST, "first"));
    assertEquals(ENTRY, session.actor.entry);
    assertEquals(
        QuestState.Phase.DOING, session.actor.getMissionManager().sub(TRANSFER).getPhase());
    assertFalse(session.actor.getMissionManager().finished(MAIN));
  }

  @Test
  void aCorruptedActiveMissionCannotBeResumedOrAdvanceFromTheSavedRaid() throws Exception {
    assertEquals(0, start());
    var value = gson.toJsonTree(session.actor.getGameplay()).getAsJsonObject();
    value
        .getAsJsonObject("mainMissions")
        .getAsJsonObject(Integer.toString(MAIN))
        .addProperty("questId", MAIN + 1);
    restore(value);
    assertFalse(new MissionRaid(session.actor).restoreOnLogin(), "错任务ID的存储记录不能成为活动副本");
    assertEquals(HOME, session.actor.entry);
    assertEquals(MAIN + 1, session.actor.getGameplay().getMainMissions().get(MAIN).getQuestId());
    assertEquals(
        Retcode.MISSION_NOT_DOING, session.actor.getMissionManager().finishTalk(FIRST, "first"));
  }

  @Test
  void anInterruptedTransferReturnsHomeAndKeepsItsPendingRecordForReconciliation()
      throws Exception {
    assertEquals(0, start());
    var run = session.actor.getGameplay().getMissionRaids().get(RAID);
    run.reserveTransfer(TRANSFER, PLANE, NEXT_FLOOR, NEXT_ENTRY);
    session.actor.enterScene(NEXT_ENTRY, 0, false);
    restore(gson.toJsonTree(session.actor.getGameplay()).getAsJsonObject());
    assertFalse(new MissionRaid(session.actor).restoreOnLogin());
    assertEquals(HOME, session.actor.entry);
    assertEquals(
        TRANSFER, session.actor.getGameplay().getMissionRaids().get(RAID).getPendingTransfer());
    assertNotEquals(0, start(), "中断标记不能被新入场当作已执行动作或自动清空");
    assertEquals(QuestState.Phase.DOING, session.actor.getMissionManager().sub(FIRST).getPhase());
  }

  @Test
  void aCompletedRaidUsesItsExplicitFinishEntranceAndCannotReplayItsMission() throws Exception {
    int finish = HOME + 100;
    GameData.getMapEntranceExcelMap()
        .add(
            gson.fromJson(
                "{\"ID\":"
                    + finish
                    + ",\"PlaneID\":"
                    + HOME_PLANE
                    + ",\"FloorID\":"
                    + HOME_FLOOR
                    + "}",
                MapEntranceExcel.class));
    try {
      var row = gson.toJsonTree(resource).getAsJsonObject();
      row.addProperty("FinishEntranceID", finish);
      GameplayDefinitions.raids.put(
          resource.key(), gson.fromJson(row, GameplayDefinitions.Raid.class));
      assertEquals(0, start());
      assertEquals(Retcode.SUCC, session.actor.getMissionManager().finishTalk(FIRST, "first"));
      assertEquals(Retcode.SUCC, session.actor.getMissionManager().finishTalk(LAST, "last"));
      assertEquals(0, leave(RAID, false));
      assertEquals(finish, session.actor.entry);
      assertNotEquals(0, start());
      assertTrue(session.actor.getGameplay().getMissionRaids().get(RAID).isCompleted());
    } finally {
      GameData.getMapEntranceExcelMap().keySet().remove(finish);
    }
  }

  @Test
  void morphiaRestoresTheActiveFloorTransferReceiptParentAndExactReturnPoint() throws Exception {
    assertEquals(0, start());
    assertEquals(Retcode.SUCC, session.actor.getMissionManager().finishTalk(FIRST, "first"));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "missionraid");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var player = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(player, AVATAR + 1);
      var field = Player.class.getDeclaredField("gameplay");
      field.setAccessible(true);
      field.set(player, session.actor.getGameplay());
      store.save(player);
      var restored = store.find(Player.class).first();
      assertNotNull(restored);
      field.set(session.actor, restored.getGameplay());
      assertTrue(new MissionRaid(session.actor).restoreOnLogin());
      assertEquals(NEXT_ENTRY, session.actor.entry);
      var run = restored.getGameplay().getMissionRaids().get(RAID);
      assertEquals(NEXT_ENTRY, run.getTransferredSubs().get(TRANSFER));
      assertEquals(12f, run.getReturnTo().getPos().getX());
      assertEquals(0, leave(RAID, false));
      assertEquals(HOME, session.actor.entry);
      assertEquals(90f, session.actor.rot.getY());
      assertEquals(
          QuestState.Phase.DOING, restored.getGameplay().getMainMissions().get(BEFORE).getPhase());
      assertNotNull(session.actor.getMissionManager().sub(LAST));
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void nativeFinishedRaidQueryReflectsActualCompletionAndTheVerifiedIdAndWorldFields()
      throws Exception {
    assertEquals(0, start());
    var query = Gameplay46.builder(session, "GetRaidInfoCsReq");
    assertTrue(VersionedPacketHandler.handle(session, 2248, query.build().toByteArray()));
    assertTrue(
        ((List<?>)
                Gameplay46.value(
                    session.packets.get("GetRaidInfoScRsp"), "finished_raid_info_list"))
            .isEmpty());
    assertEquals(Retcode.SUCC, session.actor.getMissionManager().finishTalk(FIRST, "first"));
    assertEquals(Retcode.SUCC, session.actor.getMissionManager().finishTalk(LAST, "last"));
    Raid46.sync(session);
    assertEquals(
        2,
        ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                Gameplay46.value(session.packets.get("RaidInfoNotify"), "status"))
            .getNumber());
    assertEquals(0, leave(RAID, false));
    assertTrue(VersionedPacketHandler.handle(session, 2248, query.build().toByteArray()));
    var rows =
        (List<?>)
            Gameplay46.value(session.packets.get("GetRaidInfoScRsp"), "finished_raid_info_list");
    assertEquals(1, rows.size(), "实际完成副本不能从完成查询中丢失");
    var row = (DynamicMessage) rows.getFirst();
    assertEquals(RAID, Gameplay46.number(row, "raid_id"));
    assertEquals(0, Gameplay46.number(row, "world_level"));
  }

  @Test
  void aPausedRaidPreservesMovementAfterTransferInsteadOfReusingTheEntryAnchor() throws Exception {
    assertEquals(0, start());
    assertEquals(Retcode.SUCC, session.actor.getMissionManager().finishTalk(FIRST, "first"));
    session.actor.pos.set(77, 88, 99);
    session.actor.rot.set(0, 180, 0);
    assertEquals(0, leave(RAID, false));
    assertEquals(0, start());
    assertEquals(NEXT_ENTRY, session.actor.entry);
    assertEquals(77, session.actor.pos.getX(), "重入应恢复最后移动的位置，不能倒回入口锚点");
    assertEquals(180, session.actor.rot.getY());
  }
}
