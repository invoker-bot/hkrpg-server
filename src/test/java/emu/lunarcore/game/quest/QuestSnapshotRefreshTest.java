package emu.lunarcore.game.quest;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.FinishWayExcel;
import emu.lunarcore.data.excel.QuestExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.GetQuestDataScRspOuterClass.GetQuestDataScRsp;
import emu.lunarcore.proto.QuestStatusOuterClass.QuestStatus;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.recv.HandlerGetQuestDataCsReq;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.hkrpg.server.gameplay.DailyManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercise the real quest fixed point and packet builder; only persistence is replaced. */
class QuestSnapshotRefreshTest {
  @TempDir Path root;
  List<QuestExcel> previousQuests;
  List<FinishWayExcel> previousWays;
  Field resourceDir;
  String previousRoot;
  Session session;

  static class ObservedQuest extends QuestExcel {
    int unlockReads;
    @Override public String getUnlockType() { unlockReads++; return super.getUnlockType(); }
  }

  static class Actor extends Player {
    int saves;
    @Override public void save() { saves++; }
  }

  static class Session extends GameSession {
    final Actor actor = new Actor();
    GetQuestDataScRsp response;
    Session() { super(null); }
    @Override public Player getPlayer() { return actor; }
    @Override public void send(BasePacket packet) {
      response = (GetQuestDataScRsp) packet.getData();
    }
  }

  @BeforeEach void prepare() throws Exception {
    previousQuests = new ArrayList<>(GameData.getQuestExcelMap().values());
    previousWays = new ArrayList<>(GameData.getFinishWayExcelMap().values());
    GameData.getQuestExcelMap().values().clear();
    GameData.getFinishWayExcelMap().values().clear();
    previousRoot = LunarCore.getConfig().getResourceDir();
    resourceDir = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
    resourceDir.setAccessible(true);
    resourceDir.set(LunarCore.getConfig(), root.toString());
    session = new Session();
    var now = Instant.parse("2026-10-18T00:00:00Z");
    session.actor.getDailyManager().setClock(Clock.fixed(now, ZoneOffset.UTC));
    session.actor.getGameplay().resetDaily(DailyManager.day(now));
  }

  @AfterEach void restore() throws Exception {
    GameData.getQuestExcelMap().values().clear();
    GameData.getFinishWayExcelMap().values().clear();
    previousQuests.forEach(GameData.getQuestExcelMap()::add);
    previousWays.forEach(GameData.getFinishWayExcelMap()::add);
    resourceDir.set(LunarCore.getConfig(), previousRoot);
  }

  @Test void oneRequestSettlesAReverseOrderedCompletionChainBeforeSerializing() throws Exception {
    for (int id : List.of(1999988101, 1999988102, 1999988103)) quest(id, 0, "AutoFinish");
    // Build dependencies against the actual map traversal order, so one scan cannot settle it.
    var order = new ArrayList<>(GameData.getQuestExcelMap().values());
    var first = quest(order.get(0).getId(), order.get(1).getId(), "AutoFinish");
    var second = quest(order.get(1).getId(), order.get(2).getId(), "AutoFinish");
    var third = quest(order.get(2).getId(), 0, "AutoFinish");
    query();
    assertEquals(Set.of(first.getId(), second.getId(), third.getId()), responseIds());
    assertTrue(first.unlockReads > 1, "The dependent must wait for later fixed-point passes");
    for (var row : session.response.getQuestList()) {
      assertEquals(QuestStatus.QUEST_FINISH, row.getStatus());
      assertEquals(1, row.getProgress());
      assertTrue(row.getFinishTime() > 0);
      assertEquals(QuestState.Phase.FINISHED,
          session.actor.getQuestStates().get(row.getId()).getPhase());
    }
    assertEquals(1, session.actor.saves, "The complete chain is persisted together before the reply");
  }

  @Test void unchangedPrerequisitesAreReadOnceAndTheNextQueryStillSeesNewCompletion() throws Exception {
    int prerequisite = 1999988111, dependent = 1999988112;
    quest(prerequisite, 0, "UnsupportedFixtureFinish");
    var waiting = quest(dependent, prerequisite, "AutoFinish");
    query();
    assertEquals(Set.of(prerequisite), responseIds());
    assertEquals(QuestState.Phase.DOING, session.actor.getQuestStates().get(prerequisite).getPhase());
    int saves = session.actor.saves;
    waiting.unlockReads = 0;
    query();
    int unchangedReads = waiting.unlockReads;
    assertEquals(Set.of(prerequisite), responseIds());
    assertEquals(saves, session.actor.saves, "An unchanged read must not save the player");

    // A real state transition between requests must be observed without any cache invalidation hook.
    assertTrue(session.actor.getQuestStates().get(prerequisite).advance(1, 1, 1792281600L));
    query();
    assertEquals(Set.of(prerequisite, dependent), responseIds());
    assertEquals(QuestState.Phase.FINISHED, session.actor.getQuestStates().get(dependent).getPhase());
    assertEquals(saves + 1, session.actor.saves);
    assertEquals(1, unchangedReads,
        "A settled query must not repeat the entire prerequisite/resource refresh before serialization");
  }

  private ObservedQuest quest(int id, int dependency, String finish) {
    var gson = ResourceLoader.getGson();
    var definition = gson.fromJson("{\"QuestID\":" + id + ",\"QuestType\":1,\"FinishWayID\":" + id
        + ",\"UnlockType\":\"" + (dependency == 0 ? "AutoUnlock" : "FinishQuest")
        + "\",\"UnlockParamList\":[" + (dependency == 0 ? "" : dependency) + "]}", ObservedQuest.class);
    var rule = gson.fromJson("{\"ID\":" + id + ",\"FinishType\":\"" + finish + "\",\"Progress\":1}",
        FinishWayExcel.class);
    GameData.getQuestExcelMap().add(definition);
    GameData.getFinishWayExcelMap().add(rule);
    return definition;
  }

  private void query() throws Exception {
    session.response = null;
    new HandlerGetQuestDataCsReq().handle(session, new byte[0]);
    assertNotNull(session.response);
  }

  private Set<Integer> responseIds() {
    var ids = new java.util.HashSet<Integer>();
    for (var quest : session.response.getQuestList()) assertTrue(ids.add(quest.getId()));
    return ids;
  }
}
