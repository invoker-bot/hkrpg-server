package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.config.MissionTaskInfo;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RemainingWorldMechanism46Test {
  @TempDir Path root;
  final WorldMechanism46Test fixture = new WorldMechanism46Test();
  Session session;
  Map<Integer, MissionInfo.SubMissionInfo> previousSubs;

  @BeforeEach
  void load() throws Exception {
    fixture.root = root;
    fixture.load();
    previousSubs = new HashMap<>(GameplayDefinitions.subs);
    var raw =
        JsonParser.parseReader(
                new InputStreamReader(
                    getClass().getResourceAsStream("/fixtures/world-mechanisms-4.6.json"),
                    StandardCharsets.UTF_8))
            .getAsJsonObject();
    for (var e : raw.getAsJsonObject("missions").entrySet())
      for (var sub : e.getValue().getAsJsonObject().getAsJsonArray("SubMissionList")) {
        var def = map().fixture.gson.fromJson(sub, MissionInfo.SubMissionInfo.class);
        var script = root.resolve(def.getMissionJsonPath());
        if (!def.getMissionJsonPath().isBlank() && Files.isRegularFile(script)) {
          var definition = JsonParser.parseString(Files.readString(script));
          def.setTask(map().fixture.gson.fromJson(definition, MissionTaskInfo.class));
          def.getTask().retainSourceDefinition(definition);
        }
        GameplayDefinitions.subs.put(def.getId(), def);
      }
    session = new Session();
  }

  @AfterEach
  void restore() throws Exception {
    if (previousSubs != null) {
      GameplayDefinitions.subs.clear();
      GameplayDefinitions.subs.putAll(previousSubs);
    }
    fixture.restore();
  }

  private MapChest46Test map() {
    return fixture.map;
  }

  @Test
  void loadedMissionTaskCacheDoesNotInvalidatePinnedMetadata() {
    var rule = WorldMechanismSources.RULES.get(20211675);
    var definition = GameplayDefinitions.subs.get(401510301);
    assertNotNull(definition.getTask(), "Match the production mission loader's enrichment");
    assertSame(definition, WorldMechanismSources.mission(rule, 401510301));
    definition.setParamInt2(definition.getParamInt2() + 1);
    assertNull(WorldMechanismSources.mission(rule, 401510301), "Changed target must still fail");
  }

  private void doing(int mission) {
    if (mission != 0)
      map().fixture.player.getGameplay().getMainMissions().put(mission, new QuestState(mission));
  }

  private EntityProp actor(int group, int instance) {
    return map().fixture.player.scene.getEntitiesByGroup(EntityProp.class, group).stream()
        .filter(p -> p.getInstId() == instance)
        .findFirst()
        .orElseThrow();
  }

  private void claim(int chest) {
    assertEquals(PropState.ChestClosed, map().chest(chest).getState());
    assertEquals(0, map().fixture.player.grants);
    fixture.reload();
    assertEquals(Retcode.SUCC, map().open(chest));
    fixture.reload();
    assertEquals(PropState.ChestUsed, map().chest(chest).getState());
    assertNotEquals(Retcode.SUCC, map().open(chest));
    assertEquals(1, map().fixture.player.grants);
  }

  @ParameterizedTest
  @CsvSource({
    "20123655,71,300002,0",
    "20134651,18,300020,2011409",
    "20003657,41,300002,2000302",
    "20003658,42,300002,2000303",
    "20003659,43,300002,2000304"
  })
  void exactControllerUnlocksItsLocalOrCrossGroupChest(
      int chest, int group, int instance, int mission) {
    doing(mission);
    if (group == 42 || group == 43) {
      var sub = new QuestState(mission * 100 + 1);
      sub.advance(1, 1, 1);
      map().fixture.player.getGameplay().getSubMissions().put(sub.getQuestId(), sub);
    }
    map().enter(chest);
    assertNotEquals(Retcode.SUCC, map().open(chest));
    assertEquals(
        Retcode.SUCC,
        ScenePropTransitions.interact(
                map().fixture.player, 1010, actor(group, instance).getEntityId())
            .retcode());
    claim(chest);
  }

  @ParameterizedTest
  @ValueSource(ints = {20223651, 20223652, 20223653})
  void droneReportTargetsChestInOwnerGroupAndSurvivesReload(int chest) {
    var main = new QuestState(8015163);
    main.advance(1, 1, 1);
    map().fixture.player.getGameplay().getMainMissions().put(8015163, main);
    map().enter(chest);
    assertEquals(
        Retcode.SUCC,
        WorldChests.open(map().fixture.player, 120120, map().chest(chest).getEntityId()).retcode());
    claim(chest);
  }

  private void timeLock(int chest) {
    doing(4016201);
    int n = chest - 20433690;
    map().fixture.player.getGameplay().getStoryValues().put("floor:20433001:FSV_TimeLock_" + n, 0);
    map().enter(chest);
  }

  @Test
  void instanceGraphBirdUsesItsExactCompletionTarget() {
    map().enter(20241665);
    assertNotEquals(Retcode.SUCC, map().open(20241665));
    assertEquals(
        Retcode.SUCC,
        WorldChests.open(map().fixture.player, 120120, map().chest(20241665).getEntityId())
            .retcode());
    claim(20241665);
  }

  @ParameterizedTest
  @CsvSource({
    "20211672,4015101,401510111,111120",
    "20211675,4015103,401510301,111120",
    "20211676,4015103,401510308,111120",
    "20211678,4040223,404022302,120120"
  })
  void missionScriptReportRequiresItsOwnActiveObjective(int chest, int main, int sub, int report) {
    doing(main);
    map().fixture.player.getGameplay().getSubMissions().put(sub, new QuestState(sub));
    map().enter(chest);
    assertEquals(
        Retcode.SUCC,
        WorldChests.open(map().fixture.player, report, map().chest(chest).getEntityId()).retcode());
    claim(chest);
  }

  @ParameterizedTest
  @CsvSource({
    "20222671,4015202,401520202,401520203,135",
    "20222672,4015202,401520206,401520207,136",
    "20222673,4015202,401520210,401520211,137",
    "20222674,4015203,401520302,401520303,144",
    "20222675,4015203,401520306,401520307,145",
    "20222676,4015203,401520310,401520311,146",
    "20222677,4015204,401520402,401520403,147",
    "20222678,4015204,401520406,401520407,148",
    "20222679,4015204,401520410,401520411,149"
  })
  void rootPuzzleWaitsForBoardAndDialogueBeforeRevealingReward(
      int chest, int main, int board, int talk, int group) {
    doing(main);
    map().fixture.player.getGameplay().getSubMissions().put(board, new QuestState(board));
    map().enter(chest);
    assertEquals(
        Retcode.SUCC,
        ScenePropTransitions.interact(
                map().fixture.player, 1010, actor(group, 300001).getEntityId())
            .retcode());
    assertEquals(PropState.Hidden, map().chest(chest).getState());
    fixture.reload();
    assertEquals(PropState.Hidden, map().chest(chest).getState());
    assertEquals(
        Retcode.SUCC, map().fixture.player.getMissionManager().finishTalk(talk, "Mission_" + talk));
    claim(chest);
  }

  @Test
  void completedBirdBoardUsesItsDifferentCompletionState() {
    map().enter(20331662);
    assertNotEquals(
        Retcode.SUCC,
        ScenePropTransitions.interact(map().fixture.player, 11000, actor(321, 300001).getEntityId())
            .retcode());
    assertEquals(
        Retcode.SUCC,
        ScenePropTransitions.interact(map().fixture.player, 11090, actor(321, 300001).getEntityId())
            .retcode());
    claim(20331662);
  }

  @Test
  void authoredAutomaticMissionChestAppearsAfterItsDialogue() {
    doing(2010503);
    map().fixture.player.getGameplay().getSubMissions().put(201050301, new QuestState(201050301));
    map().enter(20121686);
    assertEquals(PropState.Hidden, map().chest(20121686).getState());
    assertEquals(
        Retcode.SUCC,
        map().fixture.player.getMissionManager().finishTalk(201050301, "Mission_201050301"));
    claim(20121686);
  }

  @ParameterizedTest
  @CsvSource({
    "20221671,4015301,401530101,129,300014",
    "20221673,4015301,401530104,130,300014",
    "20221674,4015302,401530201,138,300015",
    "20221676,4015302,401530204,139,300015",
    "20221677,4015303,401530301,140,300015",
    "20221679,4015303,401530304,135,300015"
  })
  void deploymentBoardActivatesOnlyItsAuthoredReward(
      int chest, int main, int board, int group, int instance) {
    doing(main);
    map().fixture.player.getGameplay().getSubMissions().put(board, new QuestState(board));
    map().enter(chest);
    assertNotEquals(Retcode.SUCC, map().open(chest));
    assertEquals(
        Retcode.SUCC,
        ScenePropTransitions.interact(
                map().fixture.player, 1010, actor(group, instance).getEntityId())
            .retcode());
    claim(chest);
  }

  private int property(int group, String name, int value) throws Exception {
    var b =
        DynamicMessage.newBuilder(
            session.protocol.clientSchema().message("UpdateGroupPropertyCsReq"));
    b.setField(b.getDescriptorForType().findFieldByName("floor_id"), map().fixture.player.floor);
    b.setField(b.getDescriptorForType().findFieldByName("group_id"), group);
    b.setField(b.getDescriptorForType().findFieldByName("property_name"), name);
    b.setField(b.getDescriptorForType().findFieldByName("OLBEPIBLDBD"), value);
    assertTrue(
        VersionedPacketHandler.handle(
            session,
            session.getProtocol().clientCommand("UpdateGroupPropertyCsReq"),
            b.build().toByteArray()),
        "Native group property completion must be handled");
    return (Integer)
        session.reply.getField(session.reply.getDescriptorForType().findFieldByName("retcode"));
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        20433691, 20433692, 20433693, 20433694, 20433695, 20433696, 20433697, 20433698, 20433699
      })
  void timeLockReportPersistsGroupFloorAndExactReward(int chest) throws Exception {
    timeLock(chest);
    int group = chest - 20433691 + 120;
    assertEquals(0, property(group, "Srv_PuzzleState", 1));
    fixture.reload();
    assertEquals(
        1,
        map()
            .fixture
            .player
            .getGameplay()
            .getStoryValues()
            .get("floor:20433001:FSV_TimeLock_" + (chest - 20433690)));
    assertEquals(
        1,
        map()
            .fixture
            .player
            .scene
            .getGroups()
            .get(group)
            .getProperties()
            .getInt("Srv_PuzzleState"));
    assertEquals(0, property(group, "Srv_PuzzleState", 1));
    claim(chest);
  }

  @Test
  void groupSaveFailureRollsBackFloorPropertyAndCompletion() throws Exception {
    timeLock(20433691);
    map().fixture.player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), property(120, "Srv_PuzzleState", 1));
    assertEquals(PropState.Hidden, map().chest(20433691).getState());
    assertEquals(
        0,
        map().fixture.player.scene.getGroups().get(120).getProperties().getInt("Srv_PuzzleState"));
    assertEquals(
        0,
        map().fixture.player.getGameplay().getStoryValues().get("floor:20433001:FSV_TimeLock_1"));
    assertTrue(map().fixture.player.getGameplay().getWorldMechanisms().getRecords().isEmpty());
    map().fixture.player.failSave = false;
    assertEquals(0, property(120, "Srv_PuzzleState", 1));
    claim(20433691);
  }

  @ParameterizedTest
  @CsvSource({"20433697,126,7", "20433698,127,8"})
  void blackScreenCallbackIsASeparateDurableStage(int chest, int group, int n) throws Exception {
    timeLock(chest);
    assertNotEquals(0, property(group, "Srv_KindlingFall", 1));
    assertEquals(0, property(group, "Srv_PuzzleState", 1));
    fixture.reload();
    String late = "floor:20433001:FSV_TimeLock_" + n + "_Late";
    assertNull(map().fixture.player.getGameplay().getStoryValues().get(late));
    map().fixture.player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), property(group, "Srv_KindlingFall", 1));
    map().fixture.player.failSave = false;
    fixture.reload();
    assertEquals(0, property(group, "Srv_KindlingFall", 1));
    fixture.reload();
    assertEquals(1, map().fixture.player.getGameplay().getStoryValues().get(late));
    assertEquals(0, property(group, "Srv_KindlingFall", 1));
    claim(chest);
  }

  @Test
  void unstartedWrongGroupAndWrongPropertyCannotUnlock() throws Exception {
    doing(4016201);
    map().enter(20433691);
    assertNotEquals(0, property(120, "Srv_PuzzleState", 1));
    timeLock(20433691);
    assertNotEquals(0, property(121, "Srv_PuzzleState", 1));
    assertNotEquals(0, property(120, "PuzzleState", 1));
    assertNotEquals(0, property(120, "Srv_PuzzleState", 2));
    assertNotEquals(0, property(120, "Srv_PuzzleState", -1));
    assertTrue(map().fixture.player.getGameplay().getWorldMechanisms().getRecords().isEmpty());
  }

  @Test
  void missingObjectiveAndCompletedQuestWithoutBoardReceiptDoNotUnlockMissionRewards() {
    doing(4015202);
    for (int id : new int[] {401520202, 401520203}) {
      var done = new QuestState(id);
      done.advance(1, 1, 1);
      map().fixture.player.getGameplay().getSubMissions().put(id, done);
    }
    map().fixture.player.getGameplay().getSubMissions().put(401520204, new QuestState(401520204));
    map().enter(20222671);
    WorldMechanisms.missionRewards(map().fixture.player);
    assertEquals(PropState.Hidden, map().chest(20222671).getState());
    assertTrue(map().fixture.player.getGameplay().getWorldMechanisms().getRecords().isEmpty());
    doing(4015103);
    map().fixture.player.getGameplay().getSubMissions().put(401510301, new QuestState(401510301));
    map().enter(20211675);
    map().fixture.player.getGameplay().getSubMissions().remove(401510301);
    assertNotEquals(
        Retcode.SUCC,
        WorldChests.open(map().fixture.player, 111120, map().chest(20211675).getEntityId())
            .retcode());
  }

  @Test
  void changedLoadedFloorDeclarationCannotAuthorizeACompletion() throws Exception {
    doing(4016201);
    map().enter(20433691);
    var declaration =
        map().fixture.player.scene.getFloorInfo().getExtraDatas().stream()
            .filter(v -> v.getName().equals("FSV_TimeLock_1"))
            .findFirst()
            .orElseThrow();
    var value = declaration.getClass().getDeclaredField("DefaultValue");
    value.setAccessible(true);
    value.setInt(declaration, 0);
    assertNotEquals(0, property(120, "Srv_PuzzleState", 1));
    assertTrue(map().fixture.player.getGameplay().getWorldMechanisms().getRecords().isEmpty());
  }

  @Test
  void malformedPropertyPacketsNeverMutateTheJournal() throws Exception {
    timeLock(20433691);
    var stream = new java.io.ByteArrayOutputStream();
    var wire = com.google.protobuf.CodedOutputStream.newInstance(stream);
    wire.writeUInt32(5, 120);
    wire.writeUInt32(11, 20433001);
    wire.writeString(12, "Srv_PuzzleState");
    wire.writeInt32(10, 1);
    wire.writeInt32(10, 1);
    wire.flush();
    for (byte[] bytes :
        new byte[][] {stream.toByteArray(), {(byte) 0x80}, {98, 1, (byte) 0xff}, new byte[513]}) {
      session.reply = null;
      assertDoesNotThrow(
          () ->
              VersionedPacketHandler.handle(
                  session, session.protocol.clientCommand("UpdateGroupPropertyCsReq"), bytes));
      assertNotNull(session.reply);
      assertNotEquals(
          0,
          session.reply.getField(session.reply.getDescriptorForType().findFieldByName("retcode")));
      assertTrue(map().fixture.player.getGameplay().getWorldMechanisms().getRecords().isEmpty());
    }
  }

  final class Session extends GameSession {
    final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
    DynamicMessage reply;

    Session() {
      super(null);
      setState(SessionState.ACTIVE);
    }

    public ProtocolBridge getProtocol() {
      return protocol;
    }

    public Player getPlayer() {
      return map().fixture.player;
    }

    public void send(BasePacket p) {}

    public void sendClientPacket(String name, byte[] bytes) {
      try {
        if (name.equals("UpdateGroupPropertyScRsp"))
          reply = protocol.clientSchema().parse(name, bytes);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }
  }
}
