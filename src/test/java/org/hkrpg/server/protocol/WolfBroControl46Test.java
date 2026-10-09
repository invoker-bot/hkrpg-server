package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original control 156 declares 1 -> 2 and 2 -> 1; neither transition fabricates a reward. */
class WolfBroControl46Test {
  WolfBro46Test fixture = new WolfBro46Test();
  static final String KEY = "group-state:10301001:156";

  @BeforeEach
  void setup() throws Exception {
    fixture.prepare();
  }

  @AfterEach
  void cleanup() {
    fixture.restore();
  }

  int state() {
    var group = fixture.current.getGroups().get(156);
    return group == null ? 0 : group.getState();
  }

  void serverState(int state) {
    assertTrue(fixture.current.setStoryGroupState(156, state));
  }

  DynamicMessage changeRequest(int group, int state) {
    var b = Gameplay46.builder(fixture.session, "GroupStateChangeCsReq");
    var g = Gameplay46.builder(fixture.session, "GroupStateInfo");
    Gameplay46.set(g, "group_id", group);
    Gameplay46.set(g, "group_state", state);
    Gameplay46.set(b, "group_state_info", g.build());
    return b.build();
  }

  DynamicMessage change(int state) throws Exception {
    return fixture.request("GroupStateChangeCsReq", changeRequest(156, state));
  }

  @Test
  void startActuallyPersistsTheOriginalStartControlState() throws Exception {
    fixture.start();
    assertEquals(1, state());
    assertEquals(1, fixture.session.actor.getGameplay().getStoryValues().get(KEY));
    assertEquals(1, fixture.saves);
  }

  @Test
  void aFailedStartDoesNotLeaveANewGroupOrSavedState() throws Exception {
    fixture.failSave = true;
    assertEquals(
        Retcode.SYSTEM_BUSY.getVal(),
        fixture.code(fixture.request("StartWolfBroGameCsReq", fixture.startRequest(156, 1))));
    assertEquals(0, state());
    assertFalse(fixture.session.actor.getGameplay().getStoryValues().containsKey(KEY));
  }

  @Test
  void theOriginalSmallAndLargeTransitionsUpdateTheActualSavedGroup() throws Exception {
    fixture.start();
    serverState(1);
    int old = fixture.saves;
    assertEquals(0, fixture.code(change(2)));
    assertEquals(2, state());
    assertEquals(2, fixture.session.actor.getGameplay().getStoryValues().get(KEY));
    assertEquals(0, fixture.code(change(1)));
    assertEquals(1, state());
    assertEquals(old + 2, fixture.saves);
    assertEquals(0, fixture.session.actor.getHcoin());
  }

  @Test
  void repeatedSameStateDoesNotSaveOrChangeTheGameIdentity() throws Exception {
    fixture.start();
    serverState(1);
    int old = fixture.saves;
    var run = fixture.session.actor.getGameplay().getWolfBro().getRuns().get(3);
    assertEquals(0, fixture.code(change(1)));
    assertEquals(old, fixture.saves);
    assertSame(run, fixture.session.actor.getGameplay().getWolfBro().getRuns().get(3));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 3, -1, Integer.MAX_VALUE})
  void unknownOrUndeclaredDestinationsCannotChangeTheOriginalControl(int target) throws Exception {
    fixture.start();
    serverState(1);
    int old = fixture.saves;
    assertNotEquals(0, fixture.code(change(target)));
    assertEquals(1, state());
    assertEquals(old, fixture.saves);
  }

  @Test
  void anUnrelatedGroupCannotUseTheHanuControlEndpoint() throws Exception {
    fixture.start();
    serverState(1);
    int old = fixture.saves;
    assertNotEquals(
        0, fixture.code(fixture.request("GroupStateChangeCsReq", changeRequest(163, 2))));
    assertEquals(1, state());
    assertEquals(old, fixture.saves);
  }

  @Test
  void aControlTransitionRequiresAnActualActiveGame() throws Exception {
    serverState(1);
    assertNotEquals(0, fixture.code(change(2)));
    assertEquals(1, state());
    assertEquals(0, fixture.saves);
  }

  @Test
  void aFailedControlSaveRollsBackTheSceneAndThePersistedValue() throws Exception {
    fixture.start();
    serverState(1);
    int old = fixture.saves;
    fixture.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), fixture.code(change(2)));
    assertEquals(1, state());
    assertEquals(1, fixture.session.actor.getGameplay().getStoryValues().get(KEY));
    assertEquals(old, fixture.saves);
  }

  @Test
  void quitActuallyClosesTheOriginalControlWithoutResettingTheCheckpoint() throws Exception {
    fixture.start();
    serverState(2);
    fixture.archive(140000);
    assertEquals(0, fixture.code(fixture.request("QuitWolfBroGameCsReq", 3)));
    assertEquals(0, state());
    assertEquals(0, fixture.session.actor.getGameplay().getStoryValues().get(KEY));
    assertEquals(
        140000,
        fixture
            .session
            .actor
            .getGameplay()
            .getWolfBro()
            .getRuns()
            .get(3)
            .getArchive()
            .getPos()
            .getX());
  }

  @Test
  void aFailedQuitKeepsTheActualControlAndGameActive() throws Exception {
    fixture.start();
    serverState(2);
    fixture.failSave = true;
    assertEquals(
        Retcode.SYSTEM_BUSY.getVal(), fixture.code(fixture.request("QuitWolfBroGameCsReq", 3)));
    assertEquals(2, state());
    assertEquals(3, fixture.session.actor.getGameplay().getWolfBro().getActiveId());
  }

  @Test
  void restoringAnArchiveActuallyRestoresItsControlStateTogetherWithPosition() throws Exception {
    fixture.start();
    serverState(2);
    fixture.archive(140000);
    serverState(1);
    fixture.session.actor.getPos().set(1, 2, 3);
    fixture.session.actor.getRot().set(4, 5, 6);
    assertEquals(0, fixture.code(fixture.request("RestoreWolfBroGameArchiveCsReq", 3)));
    assertEquals(2, state());
    assertEquals(2, fixture.session.actor.getGameplay().getStoryValues().get(KEY));
    assertEquals(140000, fixture.session.actor.getPos().getX());
    assertEquals(-90000, fixture.session.actor.getRot().getX());
  }

  @Test
  void failedRestoreRollsBackBothTheControlAndPlayerCoordinates() throws Exception {
    fixture.start();
    serverState(2);
    fixture.archive(140000);
    serverState(1);
    fixture.session.actor.getPos().set(1, 2, 3);
    fixture.session.actor.getRot().set(4, 5, 6);
    fixture.failSave = true;
    assertEquals(
        Retcode.SYSTEM_BUSY.getVal(),
        fixture.code(fixture.request("RestoreWolfBroGameArchiveCsReq", 3)));
    assertEquals(1, state());
    assertEquals(1, fixture.session.actor.getPos().getX());
    assertEquals(4, fixture.session.actor.getRot().getX());
  }

  @Test
  void twentyFourConcurrentControlChangesSaveOnlyOneTransition() throws Exception {
    fixture.start();
    serverState(1);
    int old = fixture.saves;
    var pool = Executors.newFixedThreadPool(8);
    try {
      var tasks = new ArrayList<Callable<Integer>>();
      for (int i = 0; i < 24; i++)
        tasks.add(
            () -> {
              synchronized (fixture.session.actor) {
                return fixture.code(change(2));
              }
            });
      for (var answer : pool.invokeAll(tasks)) assertEquals(0, answer.get());
      assertEquals(2, state());
      assertEquals(old + 1, fixture.saves);
    } finally {
      pool.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"entry", "extra", "interact", "topUnknown", "nestedUnknown", "missing"})
  void invalidControlContextAndWireFieldsDoNotChangeTheOriginalGroup(String kind) throws Exception {
    fixture.start();
    int old = fixture.saves;
    var request = changeRequest(156, 2).toBuilder();
    var group =
        ((DynamicMessage) Gameplay46.value(request.build(), "group_state_info")).toBuilder();
    if (kind.equals("entry")) Gameplay46.set(group, "entry_id", 123);
    if (kind.equals("extra")) Gameplay46.set(group, "GMEHAMCHJBK", 1);
    if (kind.equals("interact")) Gameplay46.set(request, "interact_id", 1L);
    var unknown =
        com.google.protobuf.UnknownFieldSet.newBuilder()
            .addField(
                127, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    if (kind.equals("topUnknown")) request.setUnknownFields(unknown);
    if (kind.equals("nestedUnknown")) group.setUnknownFields(unknown);
    Gameplay46.set(request, "group_state_info", group.build());
    if (kind.equals("missing"))
      request.clearField(request.getDescriptorForType().findFieldByName("group_state_info"));
    assertNotEquals(0, fixture.code(fixture.request("GroupStateChangeCsReq", request.build())));
    assertEquals(1, state());
    assertEquals(old, fixture.saves);
  }

  @Test
  void changingTheOriginalTransitionDefinitionHoldsTheOldCheckpointInsteadOfResettingIt()
      throws Exception {
    fixture.start();
    var run = fixture.session.actor.getGameplay().getWolfBro().getRuns().get(3);
    fixture.current.getFloorInfo().getGroups().get(156).getGroupStateList().remove(1);
    int old = fixture.saves;
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), fixture.code(change(2)));
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(),
        fixture.code(fixture.request("GetWolfBroGameDataCsReq", 3)));
    assertSame(run, fixture.session.actor.getGameplay().getWolfBro().getRuns().get(3));
    assertEquals(1, state());
    assertEquals(old, fixture.saves);
  }

  @Test
  void contradictorySceneAndSavedControlStatesAreNotSilentlyRepaired() throws Exception {
    fixture.start();
    fixture.session.actor.getGameplay().getStoryValues().put(KEY, 2);
    int old = fixture.saves;
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), fixture.code(change(2)));
    assertEquals(1, state());
    assertEquals(2, fixture.session.actor.getGameplay().getStoryValues().get(KEY));
    assertEquals(old, fixture.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"archiveControlState", "controlSignature"})
  void halfMissingControlSnapshotsAreHeldWithoutResettingTheGame(String field) throws Exception {
    fixture.start();
    var run = fixture.session.actor.getGameplay().getWolfBro().getRuns().get(3);
    WolfBroRestore46Test.field(run, run.getClass(), field, null);
    int old = fixture.saves;
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(),
        fixture.code(fixture.request("RestoreWolfBroGameArchiveCsReq", 3)));
    assertSame(run, fixture.session.actor.getGameplay().getWolfBro().getRuns().get(3));
    assertEquals(1, state());
    assertEquals(old, fixture.saves);
  }

  @Test
  void legacyCheckpointsWithoutControlProofRemainReadableAndDoNotInventAGroupSnapshot()
      throws Exception {
    fixture.start();
    var run = fixture.session.actor.getGameplay().getWolfBro().getRuns().get(3);
    WolfBroRestore46Test.field(run, run.getClass(), "archiveControlState", null);
    WolfBroRestore46Test.field(run, run.getClass(), "controlSignature", null);
    serverState(2);
    int old = fixture.saves;
    assertEquals(0, fixture.code(fixture.request("GetWolfBroGameDataCsReq", 3)));
    assertEquals(0, fixture.code(fixture.request("RestoreWolfBroGameArchiveCsReq", 3)));
    assertEquals(2, state());
    assertNull(run.getArchiveControlState());
    assertNull(run.getControlSignature());
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), fixture.code(change(1)));
    assertEquals(old + 1, fixture.saves);
  }

  @Test
  void twoActualPlayerMorphiaReadsAndRealSceneReloadsPreserveTheControlCheckpoint()
      throws Exception {
    fixture.start();
    serverState(2);
    fixture.archive(140000);
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "wolfbro-control");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      WolfBroRestore46Test.field(document, document.getClass(), "uid", 123456786);
      WolfBroRestore46Test.field(document, document.getClass(), "name", "control-fixture");
      WolfBroRestore46Test.field(
          document, document.getClass(), "gameplay", fixture.session.actor.getGameplay());
      for (int i = 0; i < 2; i++) {
        store.save(document);
        document = store.find(emu.lunarcore.game.player.Player.class).first();
        assertNotNull(document);
        assertEquals(2, document.getGameplay().getStoryValues().get(KEY));
        assertEquals(
            2, document.getGameplay().getWolfBro().getRuns().get(3).getArchiveControlState());
        WolfBroRestore46Test.field(
            fixture.session.actor,
            emu.lunarcore.game.player.Player.class,
            "gameplay",
            document.getGameplay());
        var plane = fixture.current.getExcel();
        fixture.current =
            new emu.lunarcore.game.scene.Scene(fixture.session.actor, plane, 10301001);
        fixture.current.setEntryId(1030102);
        for (var group : fixture.current.getFloorInfo().getGroups().values())
          fixture.current.loadGroup(group);
        assertEquals(2, state());
        assertEquals(0, fixture.code(fixture.request("GetWolfBroGameDataCsReq", 3)));
      }
    } finally {
      mongo.shutdownNow();
    }
  }
}
