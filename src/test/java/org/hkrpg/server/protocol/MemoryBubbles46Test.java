package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.ExcelMap;
import emu.lunarcore.data.resource.GameResource;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.BasePacket;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.hkrpg.server.gameplay.GameplayDefinitions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Runs the native reports against the installed mission and reward definitions. */
class MemoryBubbles46Test {
  private static final Path RESOURCES = Path.of("resources/4.6");
  private static final int ROAD_TO_REVIVAL = 2000132;
  private static final int ACHIEVEMENT_PREREQUISITE = 1000201;
  private static final int MISSION = 2000320;
  private static final int TOUCHED = 200032001;
  private static final int SIGNED = 200032002;
  private static final int FIRST_BUBBLE = 200032003;
  private static final int LAST_BUBBLE = 200032022;
  private static final int COUNT = 200032023;
  private static final int TOUCH_AT_BASE = 200032032;
  private static final int SIGN_AT_PLATFORM = 200032044;
  private static final int ACHIEVEMENT = 4042301;
  private static final int ACHIEVEMENT_ROOT = 4000001;
  private static final int CREDIT = 2;

  private static final Map<Integer, MissionInfo> oldMissions = new HashMap<>();
  private static final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  private static final List<Runnable> restore = new ArrayList<>();

  private final BubbleSession session = new BubbleSession();
  private final Player player = session.getPlayer();

  @BeforeAll
  static void loadOriginals(@TempDir Path fixture) throws IOException {
    var missionPath = Path.of("Config/Level/Mission");
    var source = RESOURCES.resolve(missionPath).resolve(Integer.toString(MISSION));
    var target = fixture.resolve(missionPath).resolve(Integer.toString(MISSION));
    Files.createDirectories(target);
    try (var files = Files.list(source)) {
      for (var file : files.filter(Files::isRegularFile).toList())
        Files.copy(file, target.resolve(file.getFileName()));
    }
    oldMissions.putAll(GameData.getMissionInfos());
    oldSubs.putAll(GameplayDefinitions.subs);
    GameplayDefinitions.loadMissions(fixture.resolve(missionPath));

    register(
        "MainMission",
        "MainMissionID",
        MainMissionExcel.class,
        Set.of(MISSION, ROAD_TO_REVIVAL, ACHIEVEMENT_PREREQUISITE),
        GameData.getMainMissionExcelMap());
    register(
        "QuestData",
        "QuestID",
        QuestExcel.class,
        Set.of(ACHIEVEMENT, ACHIEVEMENT_ROOT),
        GameData.getQuestExcelMap());
    register(
        "FinishWay",
        "ID",
        FinishWayExcel.class,
        Set.of(ACHIEVEMENT, ACHIEVEMENT_ROOT),
        GameData.getFinishWayExcelMap());
    register("ItemConfig", "ID", ItemExcel.class, Set.of(CREDIT), GameData.getItemExcelMap());
    var rewards =
        IntStream.rangeClosed(2000403, 2000422)
            .boxed()
            .collect(Collectors.toCollection(HashSet::new));
    rewards.add(100);
    register("RewardData", "RewardID", RewardExcel.class, rewards, GameData.getRewardExcelMap());
  }

  private static <T extends GameResource> void register(
      String file, String key, Class<T> type, Set<Integer> ids, ExcelMap<T> table)
      throws IOException {
    var remaining = new HashSet<>(ids);
    try (var reader =
        new JsonReader(
            Files.newBufferedReader(RESOURCES.resolve("ExcelOutput/" + file + ".json")))) {
      reader.beginArray();
      while (reader.hasNext() && !remaining.isEmpty()) {
        var row = JsonParser.parseReader(reader).getAsJsonObject();
        if (!row.has(key) || !remaining.remove(row.get(key).getAsInt())) continue;
        var excel = ResourceLoader.getGson().fromJson(row, type);
        int id = excel.getId();
        var old = table.get(id);
        restore.add(
            () -> {
              table.keySet().remove(id);
              if (old != null) table.add(old);
            });
        excel.onLoad();
        table.add(excel);
      }
    }
    assertTrue(remaining.isEmpty(), () -> file + " is missing " + remaining);
  }

  @AfterAll
  static void restoreDefinitions() {
    GameData.getMissionInfos().clear();
    GameData.getMissionInfos().putAll(oldMissions);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
    restore.forEach(Runnable::run);
  }

  @Test
  void requiresTheOriginalPrerequisiteAndAnInitialTouchBeforeSigning() throws Exception {
    player.getMissionManager().refresh();
    assertNull(player.getGameplay().getMainMissions().get(MISSION));
    session.place(TOUCH_AT_BASE);
    assertNotEquals(0, session.report(TOUCH_AT_BASE));
    unlock();
    session.place(SIGN_AT_PLATFORM);
    assertNotEquals(0, session.report(SIGN_AT_PLATFORM));
    assertNull(player.getMissionManager().sub(SIGNED));
    assertEquals(0, player.getScoin());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void cannotCollectOrEarnCreditBeforeSigning(boolean touched) throws Exception {
    unlock();
    if (touched) {
      session.place(TOUCH_AT_BASE);
      assertEquals(0, session.report(TOUCH_AT_BASE));
      assertTrue(player.getMissionManager().sub(TOUCHED).getFinishTime() > 0);
    }

    session.place(FIRST_BUBBLE);
    assertNotEquals(0, session.report(FIRST_BUBBLE), "A read report cannot stand in for signing");
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(FIRST_BUBBLE).getPhase());
    assertEquals(0, player.getScoin());
    assertFalse(player.getRewardLedger().granted("submission:" + FIRST_BUBBLE));
  }

  @Test
  void signsAcrossZonesAndCompletesAfterTwentyDistinctReadReports() throws Exception {
    unlock();
    session.place(TOUCH_AT_BASE);
    assertEquals(0, session.report(TOUCH_AT_BASE));
    assertEquals(0, player.getScoin());
    assertNotEquals(
        0, session.report(SIGN_AT_PLATFORM), "The signing report belongs to the platform");
    session.place(SIGN_AT_PLATFORM);
    assertEquals(0, session.report(SIGN_AT_PLATFORM));
    assertTrue(player.getMissionManager().sub(SIGNED).getFinishTime() > 0);
    assertEquals(0, player.getMissionManager().sub(COUNT).getProgress());
    assertEquals(0, player.getScoin());

    assertNotEquals(0, session.report(FIRST_BUBBLE), "Reading must take place on the source floor");
    for (int id = FIRST_BUBBLE; id <= LAST_BUBBLE; id++) {
      session.place(id);
      int seen = id - FIRST_BUBBLE + 1;
      assertEquals(0, session.report(id), "Read bubble " + id);
      assertEquals(seen * 1_000, player.getScoin());
      assertEquals(seen, player.getMissionManager().sub(COUNT).getProgress());
      assertTrue(player.getRewardLedger().granted("submission:" + id));
      var reward = session.rewards.getLast();
      assertEquals(id, Gameplay46.number(reward, "sub_mission_id"));
      assertEquals(seen, session.rewards.size());
      assertNotEquals(0, session.report(id), "Duplicate read of " + id);
      assertEquals(seen * 1_000, player.getScoin());
      assertEquals(seen, session.rewards.size());
      assertEquals(seen == 20, player.getMissionManager().finished(MISSION));
      var achievement = player.getQuestStates().get(ACHIEVEMENT);
      if (seen < 20) assertTrue(achievement == null || achievement.getFinishTime() == 0);
      else {
        assertNotNull(achievement);
        assertEquals(QuestState.Phase.FINISHED, achievement.getPhase());
        assertEquals(1, achievement.getProgress());
        assertTrue(achievement.getFinishTime() > 0);
      }
    }
    player.getMissionManager().refresh();
    assertEquals(20_000, player.getScoin());
    assertEquals(20, session.rewards.size());
  }

  private void unlock() {
    for (int id : List.of(ACHIEVEMENT_PREREQUISITE, ROAD_TO_REVIVAL)) {
      var completed = new QuestState(id);
      assertTrue(completed.advance(1, 1, 100));
      assertTrue(completed.reserveReward());
      completed.closeReward();
      player.getGameplay().getMainMissions().put(id, completed);
    }
    player.getMissionManager().refresh();
    assertEquals(
        QuestState.Phase.DOING, player.getGameplay().getMainMissions().get(MISSION).getPhase());
  }

  private static final class BubbleSession extends Gameplay46HandlerTest.Session {
    private final List<DynamicMessage> rewards = new ArrayList<>();
    private int plane = 10001;
    private int floor = 10001001;
    private final Player actor =
        new Player() {
          @Override
          public void save() {}

          @Override
          public boolean isOnline() {
            return true;
          }

          @Override
          public String getName() {
            return "Bubble fixture";
          }

          @Override
          public GameSession getSession() {
            return BubbleSession.this;
          }

          @Override
          public int getPlaneId() {
            return plane;
          }

          @Override
          public int getFloorId() {
            return floor;
          }

          @Override
          public void sendPacket(BasePacket packet) {}
        };

    @Override
    public Player getPlayer() {
      return actor;
    }

    @Override
    public void sendClientPacket(String name, byte[] bytes) {
      super.sendClientPacket(name, bytes);
      if (name.equals("SubMissionRewardScNotify")) rewards.add(response);
    }

    void place(int id) {
      var source = GameplayDefinitions.subs.get(id);
      plane = source.getLevelPlaneID();
      floor = source.getLevelFloorID();
    }

    int report(int id) throws IOException {
      String name = "FinishTalkMissionCsReq";
      var req = Gameplay46.builder(this, name);
      // The original FinishPerformanceMission graph supplies the report key.
      Gameplay46.set(req, "talk_str", "Mission_" + id);
      assertTrue(
          VersionedPacketHandler.handle(
              this, getProtocol().clientCommand(name), req.build().toByteArray()));
      assertEquals("FinishTalkMissionScRsp", responseName);
      return Gameplay46.number(response, "retcode");
    }
  }
}
