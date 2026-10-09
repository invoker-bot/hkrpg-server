package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.trial.TrialAvatar;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Original raid resources; accepted tasks, formal actor and position are explicit fixtures. */
class MissionRaidTrialLifecycle46Test {
  @TempDir Path root;
  // Reuse resource setup only, without inheriting or repeating the other class's test methods.
  final MissionTrialProvisioning46Test fixture = new MissionTrialProvisioning46Test();
  final Map<Long, GameplayDefinitions.Raid> oldRaids = new HashMap<>();
  final Map<Object, List<?>> oldResources = new LinkedHashMap<>();
  final Gson gson = new Gson();
  JsonObject original;
  MissionTrialProvisioning46Test.Actor player;

  @BeforeEach
  void prepare() throws Exception {
    fixture.root = root;
    fixture.prepare();
    player = fixture.player;
    oldRaids.putAll(GameplayDefinitions.raids);
    GameplayDefinitions.raids.clear();
    for (var map :
        List.of(
            GameData.getMainMissionExcelMap(),
            GameData.getMazePlaneExcelMap(),
            GameData.getMapEntranceExcelMap())) {
      oldResources.put(map, new ArrayList<>(map.values()));
      map.values().clear();
    }
    original = fixture.fixture("mission-raid-scripted-trials-4.6.json");
    var records = original.getAsJsonObject("records");
    for (var raw : records.getAsJsonArray("ExcelOutput/RaidConfig.json")) {
      var row = gson.fromJson(raw, GameplayDefinitions.Raid.class);
      GameplayDefinitions.raids.put((long) row.RaidID() * 100 + row.WorldLevel(), row);
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/MainMission.json"))
      GameData.getMainMissionExcelMap()
          .add(emu.lunarcore.data.ResourceLoader.getGson().fromJson(raw, MainMissionExcel.class));
    for (var raw : records.getAsJsonArray("ExcelOutput/MazePlane.json"))
      GameData.getMazePlaneExcelMap()
          .add(emu.lunarcore.data.ResourceLoader.getGson().fromJson(raw, MazePlaneExcel.class));
    for (var raw : records.getAsJsonArray("ExcelOutput/MapEntrance.json"))
      GameData.getMapEntranceExcelMap()
          .add(emu.lunarcore.data.ResourceLoader.getGson().fromJson(raw, MapEntranceExcel.class));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @AfterEach
  void restore() throws Exception {
    GameplayDefinitions.raids.clear();
    GameplayDefinitions.raids.putAll(oldRaids);
    for (var entry : oldResources.entrySet()) {
      var map = (emu.lunarcore.data.resource.ExcelMap) entry.getKey();
      map.values().clear();
      for (var row : entry.getValue()) map.add((emu.lunarcore.data.resource.GameResource) row);
    }
    fixture.restore();
  }

  void position(Player actor, int entry) {
    var row = GameData.getMapEntranceExcelMap().get(entry);
    MissionTrialProvisioning46Test.set(actor, "entryId", entry);
    MissionTrialProvisioning46Test.set(actor, "planeId", row.getPlaneID());
    MissionTrialProvisioning46Test.set(actor, "floorId", row.getFloorID());
    MissionTrialProvisioning46Test.set(
        actor,
        "scene",
        new Scene(actor, GameData.getMazePlaneExcelMap().get(row.getPlaneID()), row.getFloorID()));
    actor.getScene().setEntryId(entry);
  }

  MissionInfo.SubMissionInfo open(int raid, int sub, int trial) throws Exception {
    var target = fixture.accept(sub, trial);
    var row = GameplayDefinitions.raids.get((long) raid * 100);
    int main = row.MainMissionIDList().getFirst();
    var records = original.getAsJsonObject("records");
    var info =
        gson.fromJson(
            records
                .getAsJsonArray("Config/Level/Mission/" + main + "/MissionInfo_" + main + ".json")
                .get(0),
            MissionInfo.class);
    var subs = new ArrayList<MissionInfo.SubMissionInfo>();
    for (var raw :
        records
            .getAsJsonArray(
                "Config/Level/Mission/" + main + "/MissionInfo_" + main + ".partial.json")
            .get(0)
            .getAsJsonObject()
            .getAsJsonArray("SubMissionList")) {
      var c = gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
      if (c.getId() == target.getId()) c = target;
      GameplayDefinitions.subs.put(c.getId(), c);
      subs.add(c);
    }
    MissionTrialProvisioning46Test.setField(
        info, MissionInfo.class, "subMissionList", subs.toArray(MissionInfo.SubMissionInfo[]::new));
    GameData.getMissionInfos().put(main, info);
    player
        .getGameplay()
        .getMainMissions()
        .put(row.MainMissionIDBefore(), new QuestState(row.MainMissionIDBefore()));
    position(player, 2000101);
    int entry =
        GameData.getMapEntranceExcelMap().values().stream()
            .filter(
                e ->
                    e.getPlaneID() == target.getLevelPlaneID()
                        && e.getFloorID() == target.getLevelFloorID())
            .findFirst()
            .orElseThrow()
            .getId();
    var run =
        new GameplayState.MissionRaidRun(
            raid, 0, main, target.getLevelPlaneID(), target.getLevelFloorID(), entry);
    run.returnFrom(player);
    var plan = RaidTeams.plan(row);
    if (RaidTeams.snapshotRequired(plan)) run.freezeFormalTeam(plan);
    RaidTrialManager.Prepared base = null;
    if (RaidTeams.frozenParty(plan)) {
      base =
          player
              .getRaidTrialManager()
              .prepare(
                  plan,
                  "TrialOnly".equals(plan.type())
                      ? player.getRaidTrialManager().defaults(plan)
                      : player.getCurrentLineup().getAvatars().stream()
                          .map(id -> new RaidTeams.Selection(id, false))
                          .toList());
      run.freezeTrialParty(base.party());
    }
    player.getGameplay().beginMissionRaid(run);
    if (base != null) player.getRaidTrialManager().install(run, base);
    position(player, entry);
    run.checkpoint(player);
    assertNotNull(MissionRaid.resource(raid, 0));
    assertTrue(new MissionRaid(player).activeFor(main));
    assertNotNull(player.getTrialManager().buildTrialAvatar(trial));
    return target;
  }

  @ParameterizedTest
  @CsvSource({"40301,103600414,1021225,false", "40414,104021212,1021404,true"})
  void actualOwnedRaidExecutesItsOriginalAddOrVirtualReplace(
      int raid, int sub, int trial, boolean virtual) throws Exception {
    var target = open(raid, sub, trial);
    player.getMissionManager().refresh();
    assertInstanceOf(TrialAvatar.class, player.getAvatarById(trial));
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(sub).getPhase());
    assertEquals(
        virtual ? List.of(trial) : List.of(199986, trial), player.getCurrentLineup().getAvatars());
    assertEquals(virtual, player.getCurrentLineup().toProto().getIsVirtual());
    assertEquals(List.of(199986), player.getLineupManager().getLineups()[0].getAvatars());
    assertTrue(new MissionRaid(player).activeFor(target.getMainMissionID()));
    assertTrue(player.getAvatars().getAvatars().keySet().equals(Set.of(199986)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"level", "promotion", "rank"})
  void changedCachedEquipmentCannotBeUsedAsFrozenRaidCombatData(String field) throws Exception {
    open(40414, 104021212, 1021404);
    player.getMissionManager().refresh();
    var trial = (TrialAvatar) player.getAvatarById(1021404);
    var gear = Objects.requireNonNull(trial.getEquips().get(100));
    switch (field) {
      case "level" -> gear.setLevel(gear.getLevel() + 1);
      case "promotion" -> gear.setPromotion(gear.getPromotion() + 1);
      case "rank" -> gear.setRank(gear.getRank() + 1);
    }
    var before = gson.toJson(player.getGameplay());
    assertThrows(IllegalStateException.class, () -> player.getCurrentLineup().toProto());
    assertEquals(before, gson.toJson(player.getGameplay()));
  }

  @ParameterizedTest
  @CsvSource({"40301,103600414,1021225", "40414,104021212,1021404"})
  void pausingAndReturningPreservesTheRaidPartyAndRejectsStaleViewWrites(
      int raid, int sub, int trial) throws Exception {
    open(raid, sub, trial);
    player.getMissionManager().refresh();
    var run = new MissionRaid(player).current();
    var view = player.getCurrentLineup();
    player.getAvatarById(trial).setCurrentHp(view, 2468);
    player.getAvatarById(trial).setCurrentSp(view, 3456);
    view.setMp(2);
    var frozen = gson.toJson(run.getMissionTrials());
    int entry = player.getEntryId();
    player.getGameplay().endMissionRaid();
    player.getMissionTrialManager().detach();
    position(player, 2000101);
    assertFalse(player.getMissionTrialManager().inside());
    assertNull(player.getAvatarById(trial));
    assertEquals(List.of(199986), player.getCurrentLineup().getAvatars());
    view.setMp(5);
    player.getMissionManager().refresh();
    assertEquals(frozen, gson.toJson(run.getMissionTrials()));
    player.getGameplay().beginMissionRaid(run);
    position(player, entry);
    player.getMissionTrialManager().restoreOnLogin();
    assertEquals(2468, player.getAvatarById(trial).getCurrentHp(player.getCurrentLineup()));
    assertEquals(3456, player.getAvatarById(trial).getCurrentSp(player.getCurrentLineup()));
    assertEquals(2, player.getCurrentLineup().getMp());
    assertEquals(frozen, gson.toJson(run.getMissionTrials()));
    assertTrue(new MissionRaid(player).restoreOnLogin());
    assertTrue(new MissionRaid(player).activeFor(run.getMainMission()));
  }

  @ParameterizedTest
  @CsvSource({"40301,103600414,1021225", "40414,104021212,1021404"})
  void actualPlayerDocumentsKeepActiveAndPausedRaidActorsAcrossTwoReads(
      int raid, int sub, int trial) throws Exception {
    open(raid, sub, trial);
    player.getMissionManager().refresh();
    player.getAvatarById(trial).setCurrentHp(player.getCurrentLineup(), 2468);
    var expected = gson.toJson(player.getGameplay());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store =
          dev.morphia.Morphia.createDatastore(client, "raid-scripted-trial-document-fixture");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, emu.lunarcore.util.Position.class);
      var document = new Player();
      MissionTrialProvisioning46Test.set(document, "uid", player.getUid());
      MissionTrialProvisioning46Test.set(document, "gameplay", player.getGameplay());
      store.save(document);
      for (int i = 0; i < 2; i++) {
        var stored = Objects.requireNonNull(store.find(Player.class).first());
        assertEquals(expected, gson.toJson(stored.getGameplay()));
        var restored = fixture.reload(stored.getGameplay());
        position(restored, player.getEntryId());
        restored.getMissionTrialManager().restoreOnLogin();
        assertTrue(new MissionRaid(restored).restoreOnLogin());
        assertEquals(2468, restored.getAvatarById(trial).getCurrentHp(restored.getCurrentLineup()));
        assertEquals(expected, gson.toJson(restored.getGameplay()));
        stored.getGameplay().endMissionRaid();
        var paused = fixture.reload(stored.getGameplay());
        position(paused, 2000101);
        paused.getMissionTrialManager().restoreOnLogin();
        assertNull(paused.getAvatarById(trial));
        assertNotNull(
            paused.getGameplay().getMissionRaids().get(raid).getMissionTrials().getParty());
      }
    } finally {
      server.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"raidBinding", "raidSource", "raidResource", "parent", "receipt"})
  void damagedRaidBindingCannotPublishSavedActors(String damage) throws Exception {
    open(40414, 104021212, 1021404);
    player.getMissionManager().refresh();
    var restored = fixture.reload(player.getGameplay());
    var run = new MissionRaid(restored).current();
    var state = run.getMissionTrials();
    switch (damage) {
      case "raidBinding" ->
          MissionTrialProvisioning46Test.setField(
              state.getParty(), MissionTrialState.Party.class, "raid", 0);
      case "raidSource" ->
          MissionTrialProvisioning46Test.setField(
              state.getParty(), MissionTrialState.Party.class, "raidSource", "{}");
      case "raidResource" -> {
        var row = gson.toJsonTree(GameplayDefinitions.raids.get(4041400L)).getAsJsonObject();
        row.addProperty("FinishEntranceID", 2000101);
        GameplayDefinitions.raids.put(4041400L, gson.fromJson(row, GameplayDefinitions.Raid.class));
      }
      case "parent" -> restored.getGameplay().getCancelledMissions().add(1040211);
      case "receipt" -> state.getExecuted().clear();
    }
    var before = gson.toJson(restored.getGameplay());
    assertThrows(
        IllegalStateException.class, () -> restored.getMissionTrialManager().restoreOnLogin());
    assertTrue(restored.getAvatars().getTempAvatars().isEmpty());
    assertEquals(before, gson.toJson(restored.getGameplay()));
    assertEquals(0, restored.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"completed", "cancelled"})
  void terminatingTheRaidTaskRetiresOnlyItsOwnParty(String cause) throws Exception {
    open(40414, 104021212, 1021404);
    player.getMissionManager().refresh();
    var run = new MissionRaid(player).current();
    var outside = player.getTrialManager().buildTrialAvatar(1101006);
    player.getAvatars().addTempAvatar(outside);
    if (cause.equals("completed"))
      player.getGameplay().getMainMissions().get(1040212).advance(1, 1, 1);
    else player.getGameplay().getCancelledMissions().add(1040212);
    assertTrue(player.getMissionTrialManager().reconcile());
    assertNull(run.getMissionTrials().getParty());
    assertEquals(Set.of(104021212), run.getMissionTrials().getExecuted().keySet());
    assertNull(player.getAvatarById(1021404));
    assertSame(outside, player.getAvatarById(1101006));
    assertNull(player.getGameplay().getMissionTrials().getParty());
    assertEquals(List.of(199986), player.getCurrentLineup().getAvatars());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "noOwner",
        "foreignMain",
        "cancelledParent",
        "wrongEntry",
        "wrongFloor",
        "missingMainResource",
        "frozenPlayerTeam",
        "battle"
      })
  void invalidRaidContextDoesNotCreateOrCompleteTheSourceTask(String damage) throws Exception {
    var target = open(40414, 104021212, 1021404);
    var run = new MissionRaid(player).current();
    switch (damage) {
      case "noOwner" -> player.getGameplay().endMissionRaid();
      case "foreignMain" ->
          MissionTrialProvisioning46Test.setField(
              run, GameplayState.MissionRaidRun.class, "mainMission", 1036004);
      case "cancelledParent" -> player.getGameplay().getCancelledMissions().add(1040211);
      case "wrongEntry" -> MissionTrialProvisioning46Test.set(player, "entryId", 2000101);
      case "wrongFloor" -> MissionTrialProvisioning46Test.set(player, "floorId", 40414002);
      case "missingMainResource" -> GameData.getMainMissionExcelMap().values().clear();
      case "frozenPlayerTeam" -> {
        var raw = gson.toJsonTree(GameplayDefinitions.raids.get(4041400L)).getAsJsonObject();
        raw.addProperty("LockCaptain", true);
        raw.addProperty("LockCaptainAvatarID", 199986);
        GameplayDefinitions.raids.put(4041400L, gson.fromJson(raw, GameplayDefinitions.Raid.class));
      }
      case "battle" ->
          MissionTrialProvisioning46Test.set(
              player,
              "battle",
              new emu.lunarcore.game.battle.Battle(
                  player,
                  player.getCurrentLineup(),
                  gson.fromJson("{\"StageID\":1}", StageExcel.class),
                  false));
    }
    var before = gson.toJson(player.getGameplay());
    assertFalse(player.getMissionTrialManager().execute(target));
    assertEquals(before, gson.toJson(player.getGameplay()));
    assertNull(player.getAvatarById(1021404));
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(104021212).getPhase());
    assertEquals(0, player.saves);
  }

  @Test
  void actualLeaveReturnsHomeAndRetainsThePausedSourceParty() throws Exception {
    open(40414, 104021212, 1021404);
    player.getMissionManager().refresh();
    var run = new MissionRaid(player).current();
    var frozen = gson.toJson(run.getMissionTrials());
    assertEquals(
        emu.lunarcore.server.packet.Retcode.RAID_ID_NOT_MATCH,
        new MissionRaid(player).leave(40301, false));
    assertEquals(
        emu.lunarcore.server.packet.Retcode.RAID_CAN_NOT_SAVE,
        new MissionRaid(player).leave(40414, true));
    assertEquals(frozen, gson.toJson(run.getMissionTrials()));
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC, new MissionRaid(player).leave(40414, false));
    assertEquals(2000101, player.getEntryId());
    assertEquals(0, player.getGameplay().getCurrentMissionRaid());
    assertNull(player.getAvatarById(1021404));
    assertEquals(List.of(199986), player.getCurrentLineup().getAvatars());
    assertEquals(frozen, gson.toJson(run.getMissionTrials()));
  }

  @Test
  void ordinaryMissionActorsDoNotOverwritePausedRaidActors() throws Exception {
    open(40414, 104021212, 1021404);
    player.getMissionManager().refresh();
    var run = new MissionRaid(player).current();
    var frozen = gson.toJson(run.getMissionTrials());
    player.getGameplay().endMissionRaid();
    player.getMissionTrialManager().detach();
    position(player, 2000101);
    fixture.accept(100010110, 1001006);
    player.getMissionManager().refresh();
    assertNotNull(player.getAvatarById(1001006));
    assertNull(player.getAvatarById(1021404));
    assertNotNull(player.getGameplay().getMissionTrials().getParty());
    assertEquals(frozen, gson.toJson(run.getMissionTrials()));
  }
}
