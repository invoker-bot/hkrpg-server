package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Original Default entrance; performance/battle results and scene arrival are explicit fixtures.
 */
class DefaultEntryMissionRaid46Test {
  @TempDir Path root;
  final MissionTrialOnlyRaid46Test source = new MissionTrialOnlyRaid46Test();
  WorldActor player;

  static class WorldActor extends MultiStageMissionRaid46Test.SceneActor {
    int world;

    WorldActor(MissionRaidTrialLifecycle46Test helper) {
      super(helper);
    }

    @Override
    public int getWorldLevel() {
      return world;
    }
  }

  @BeforeEach
  void prepare() throws Exception {
    source.root = root;
    source.prepare();
    source.installRecords("default-entry-mission-raid-4.6.json");
    var helper = source.helper;
    player = new WorldActor(helper);
    var formal = helper.player.getAvatarById(199986);
    formal.setOwner(player);
    player.getAvatars().getAvatars().put(199986, formal);
    player.getCurrentLineup().getAvatars().add(199986);
    helper.player = player;
    helper.fixture.player = player;
    source.player = player;
    var records = helper.original.getAsJsonObject("records");
    Files.writeString(
        root.resolve("ExcelOutput/RaidConfig.json"),
        records.get("ExcelOutput/RaidConfig.json").toString());
    for (int main : List.of(1032201, 1031901)) {
      String path = "Config/Level/Mission/" + main + "/";
      var raw =
          records
              .getAsJsonArray(path + "MissionInfo_" + main + ".json")
              .get(0)
              .getAsJsonObject()
              .deepCopy();
      raw.add(
          "SubMissionList",
          records
              .getAsJsonArray(path + "MissionInfo_" + main + ".partial.json")
              .get(0)
              .getAsJsonObject()
              .get("SubMissionList"));
      var info = helper.gson.fromJson(raw, MissionInfo.class);
      for (var sub : info.getSubMissionList()) {
        if (records.has(sub.getMissionJsonPath())) {
          var script = records.getAsJsonArray(sub.getMissionJsonPath()).get(0);
          var task = helper.gson.fromJson(script, MissionTaskInfo.class);
          task.retainSourceDefinition(script);
          sub.setTask(task);
        }
        GameplayDefinitions.subs.put(sub.getId(), sub);
      }
      GameData.getMissionInfos().put(main, info);
    }
    player.getGameplay().getMainMissions().put(1031801, new QuestState(1031801));
    helper.position(player, 2000101);
    player.getCurrentLineup().setMp(2);
  }

  @AfterEach
  void restore() throws Exception {
    source.restore();
  }

  private void enter() {
    assertEquals(Retcode.SUCC, new MissionRaid(player).enter(43306, 0, 0, 0, List.of()));
  }

  private void goal(int id, Set<Integer> found) {
    if (!found.add(id)) return;
    var sub = Objects.requireNonNull(GameplayDefinitions.subs.get(id));
    if (!"Auto".equals(sub.getTakeType()) && sub.getTakeParamIntList() != null)
      for (int gate : sub.getTakeParamIntList()) goal(gate, found);
  }

  private void completeStage() {
    var run = new MissionRaid(player).current();
    var ids = new HashSet<Integer>();
    for (int id : GameData.getMissionInfos().get(run.getMainMission()).getFinishSubMissionList())
      goal(id, ids);
    for (int id : ids) {
      var sub = GameplayDefinitions.subs.get(id);
      if ("EnterRaidScene".equals(sub.getFinishType())) continue;
      assertTrue(
          Set.of("Talk", "StageWin").contains(sub.getFinishType()),
          "Explicit isolated outcome " + id);
      var state = new QuestState(id);
      state.advance(sub.getProgress(), sub.getProgress(), 123);
      player.getGameplay().getSubMissions().put(id, state);
    }
    player.getMissionManager().refresh();
  }

  @Test
  void originalOmittedEntranceIsAvailableWithoutRewritingItToSkipUI() {
    var row = GameplayDefinitions.raids.get(4330600L);
    assertNull(row.EnterType());
    assertEquals(List.of(1032201, 1031901), row.MainMissionIDList());
    assertTrue(MissionRaidSequence.supported(row));
    assertNotNull(MissionRaid.resource(43306, 0));
    assertNull(GameplayDefinitions.raids.get(4330600L).EnterType());
  }

  @Test
  void defaultEntrancePreservesTheFormalPartyAndItsVitals() {
    var ordinary = player.getCurrentLineup();
    var actor = player.getAvatarById(199986);
    int hp = actor.getCurrentHp(ordinary), sp = actor.getCurrentSp(ordinary);
    enter();
    assertEquals(43306001, player.getEntryId());
    assertEquals(List.of(199986), player.getCurrentLineup().getAvatars());
    assertEquals(2, player.getCurrentLineup().getMp());
    assertEquals(hp, actor.getCurrentHp(ordinary));
    assertEquals(sp, actor.getCurrentSp(ordinary));
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
    assertTrue(new MissionRaid(player).owns(1032201));
  }

  @Test
  void originalTransferVirtualTaskAndBothFinalListsFinishAtTheDeclaredReturn() {
    enter();
    completeStage();
    var run = new MissionRaid(player).current();
    assertEquals(1, run.getSequence().getStage());
    assertEquals(43306002, player.getEntryId());
    assertEquals(Map.of(103220105, 43306002), run.getTransferredSubs());
    var talk = new QuestState(103190102);
    talk.advance(1, 1, 123);
    player.getGameplay().getSubMissions().put(103190102, talk);
    player.getMissionManager().refresh();
    assertTrue(MissionStateRecords.subCompleted(player, 103190112));
    assertEquals(List.of(1021304), player.getCurrentLineup().getAvatars());
    assertTrue(player.getCurrentLineup().toProto().getIsVirtual());
    assertEquals(2, player.getCurrentLineup().getMp());
    completeStage();
    assertTrue(MissionRaidSequence.finished(player, run, 1032201));
    assertTrue(MissionRaidSequence.finished(player, run, 1031901));
    assertEquals(Retcode.SUCC, new MissionRaid(player).leave(43306, false));
    assertTrue(run.isCompleted());
    assertEquals(2031101, player.getEntryId());
    assertEquals(List.of(199986), player.getCurrentLineup().getAvatars());
    assertEquals(2, player.getCurrentLineup().getMp());
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
  }

  @Test
  void unverifiedHigherPlayerWorldCannotBypassDifficultyScope() {
    player.world = 7;
    var before = source.helper.gson.toJson(player.getGameplay());
    int saves = player.saves;
    assertNotEquals(Retcode.SUCC, new MissionRaid(player).enter(43306, 0, 0, 0, List.of()));
    assertEquals(before, source.helper.gson.toJson(player.getGameplay()));
    assertEquals(saves, player.saves);
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
  }

  private JsonArray entranceRows() throws Exception {
    return JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/RaidConfig.json")))
        .getAsJsonArray();
  }

  private void writeEntrances(JsonArray rows) throws Exception {
    var path = root.resolve("ExcelOutput/RaidConfig.json");
    Files.writeString(path, rows.toString());
    // A reload is explicit even on filesystems with coarse timestamp resolution.
    Files.setLastModifiedTime(
        path,
        java.nio.file.attribute.FileTime.fromMillis(
            Files.getLastModifiedTime(path).toMillis() + 2000));
  }

  @Test
  void explicitDefaultMatchesItsWholeSourceWithoutChangingTheEntryMode() throws Exception {
    var rows = entranceRows();
    rows.get(0).getAsJsonObject().addProperty("EnterType", "Default");
    writeEntrances(rows);
    GameplayDefinitions.raids.put(
        4330600L, source.helper.gson.fromJson(rows.get(0), GameplayDefinitions.Raid.class));
    enter();
    assertEquals("Default", GameplayDefinitions.raids.get(4330600L).EnterType());
    assertTrue(new MissionRaid(player).active());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
  void unavailableOrAmbiguousWholeEntranceSourcesRejectBeforeSaving(int variant) throws Exception {
    var rows = entranceRows();
    var raw = rows.get(0).getAsJsonObject();
    switch (variant) {
      case 0 -> rows.add(raw.deepCopy());
      case 1 -> raw.add("EnterType", JsonNull.INSTANCE);
      case 2 -> raw.addProperty("EnterType", "Performance");
      case 3 -> raw.addProperty("DifficultyAdjustmentType", 2);
      case 4 -> raw.addProperty("WorldLevel", 0.5);
      case 5 -> raw.addProperty("RaidID", "43306");
      case 6 -> raw.addProperty("DifficultyAdjustmentType", "1");
      case 7 -> raw.addProperty("FinishEntranceID", 2000101);
      default -> fail("Unknown source mutation");
    }
    writeEntrances(rows);
    var before = source.helper.gson.toJson(player.getGameplay());
    int saves = player.saves;
    assertNull(MissionRaid.resource(43306, 0));
    assertNotEquals(Retcode.SUCC, new MissionRaid(player).enter(43306, 0, 0, 0, List.of()));
    assertEquals(before, source.helper.gson.toJson(player.getGameplay()));
    assertEquals(saves, player.saves);
    assertEquals(List.of(199986), player.getCurrentLineup().getAvatars());
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "malformed"})
  void missingOrMalformedSourceCannotUseAPreviousCachedEntrance(String variant) throws Exception {
    assertNotNull(MissionRaid.resource(43306, 0));
    var path = root.resolve("ExcelOutput/RaidConfig.json");
    if (variant.equals("missing")) Files.delete(path);
    else Files.writeString(path, "[null]");
    assertNull(MissionRaid.resource(43306, 0));
    assertNotEquals(Retcode.SUCC, new MissionRaid(player).enter(43306, 0, 0, 0, List.of()));
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
    assertEquals(0, player.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"DifficultyAdjustmentType", "MonsterHideList", "AutoObtainDamageType"})
  void activeSequenceFreezesEvenFieldsOutsideTheTypedRaidRecord(String field) throws Exception {
    enter();
    var run = new MissionRaid(player).current();
    var expected = source.helper.gson.toJson(player.getGameplay());
    var rows = entranceRows();
    var raw = rows.get(0).getAsJsonObject();
    if (field.equals("DifficultyAdjustmentType")) raw.addProperty(field, 0);
    else if (field.equals("AutoObtainDamageType")) raw.addProperty(field, false);
    else raw.add(field, new JsonArray());
    writeEntrances(rows);
    assertFalse(new MissionRaid(player).owns(1032201));
    assertFalse(new MissionRaid(player).active());
    assertEquals(expected, source.helper.gson.toJson(player.getGameplay()));
    assertFalse(run.isCompleted());
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
  }

  private void virtualParty() {
    completeStage();
    var talk = new QuestState(103190102);
    talk.advance(1, 1, 123);
    player.getGameplay().getSubMissions().put(103190102, talk);
    player.getMissionManager().refresh();
    assertTrue(MissionStateRecords.subCompleted(player, 103190112));
  }

  @Test
  void pausedVirtualPartyRestoresItsConsumedPointsAndTheOriginalLocation() {
    enter();
    virtualParty();
    player.getCurrentLineup().setMp(1);
    assertEquals(Retcode.SUCC, new MissionRaid(player).leave(43306, false));
    assertEquals(List.of(199986), player.getCurrentLineup().getAvatars());
    assertEquals(2, player.getCurrentLineup().getMp());
    assertEquals(2000101, player.getEntryId());
    enter();
    assertEquals(43306002, player.getEntryId());
    assertEquals(List.of(1021304), player.getCurrentLineup().getAvatars());
    assertTrue(player.getCurrentLineup().toProto().getIsVirtual());
    assertEquals(1, player.getCurrentLineup().getMp());
    assertEquals(
        Set.of(103190112),
        new MissionRaid(player).current().getMissionTrials().getExecuted().keySet());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void actualPlayerMorphiaRestoresFormalOrVirtualActiveAndPausedStates(int mode) throws Exception {
    enter();
    if (mode > 0) virtualParty();
    if (mode > 0) player.getCurrentLineup().setMp(1);
    if (mode >= 2) assertEquals(Retcode.SUCC, new MissionRaid(player).leave(43306, false));
    if (mode == 3) {
      enter();
      completeStage();
      assertEquals(Retcode.SUCC, new MissionRaid(player).leave(43306, false));
    }
    var expected = source.helper.gson.toJson(player.getGameplay());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "default-entry-fixture");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              emu.lunarcore.game.player.Player.class,
              QuestState.class,
              emu.lunarcore.util.Position.class);
      var document = new emu.lunarcore.game.player.Player();
      MissionTrialProvisioning46Test.set(document, "uid", player.getUid());
      MissionTrialProvisioning46Test.set(document, "gameplay", player.getGameplay());
      store.save(document);
      for (int attempt = 0; attempt < 2; attempt++) {
        var saved =
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(expected, source.helper.gson.toJson(saved.getGameplay()));
        var copy = new WorldActor(source.helper);
        MissionTrialProvisioning46Test.set(copy, "gameplay", saved.getGameplay());
        var formal =
            new emu.lunarcore.game.avatar.GameAvatar(GameData.getAvatarExcelMap().get(199986));
        formal.setOwner(copy);
        copy.getAvatars().getAvatars().put(199986, formal);
        copy.getLineupManager().getLineups()[0].getAvatars().add(199986);
        copy.getLineupManager().getLineups()[0].setMp(2);
        source.helper.position(copy, player.getEntryId());
        copy.getMissionTrialManager().restoreOnLogin();
        assertEquals(mode < 2, new MissionRaid(copy).restoreOnLogin());
        assertEquals(expected, source.helper.gson.toJson(copy.getGameplay()));
        assertEquals(0, copy.saves);
        if (mode == 2)
          assertEquals(Retcode.SUCC, new MissionRaid(copy).enter(43306, 0, 0, 0, List.of()));
        assertEquals(
            mode == 0 || mode == 3 ? List.of(199986) : List.of(1021304),
            copy.getCurrentLineup().getAvatars());
        assertEquals(mode == 0 || mode == 3 ? 2 : 1, copy.getCurrentLineup().getMp());
        assertEquals(2, copy.getLineupManager().getLineups()[0].getMp());
        assertEquals(mode > 0 && mode < 3, copy.getCurrentLineup().toProto().getIsVirtual());
        store.save(document);
      }
    } finally {
      server.shutdownNow();
    }
  }
}
