package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.trial.TrialAvatar;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original 4010106 roster and fixed captain; source-gated protagonist append. */
class MissionTrialOnlyRaid46Test {
  @TempDir Path root;
  final MissionRaidTrialLifecycle46Test helper = new MissionRaidTrialLifecycle46Test();
  MissionTrialProvisioning46Test.Actor player;

  @BeforeEach
  void prepare() throws Exception {
    helper.root = root;
    helper.prepare();
    player = helper.player;
    var maps = helper.fixture.oldResources;
    maps.put(
        GameData.getAvatarPromotionExcelMap(),
        new ArrayList<>(GameData.getAvatarPromotionExcelMap().values()));
    maps.put(
        GameData.getEquipmentPromotionExcelMap(),
        new ArrayList<>(GameData.getEquipmentPromotionExcelMap().values()));
    maps.put(
        GameData.getMultiplePathAvatarExcelMap(),
        new ArrayList<>(GameData.getMultiplePathAvatarExcelMap().values()));
    installRecords("trial-only-scripted-mission-4.6.json");
  }

  void installRecords(String fixture) throws Exception {
    var source = helper.fixture.fixture(fixture);
    var records = source.getAsJsonObject("records");
    for (var entry : records.entrySet()) {
      helper.original.getAsJsonObject("records").add(entry.getKey(), entry.getValue());
      for (var raw : entry.getValue().getAsJsonArray()) {
        var gson = ResourceLoader.getGson();
        switch (entry.getKey()) {
          case "ExcelOutput/RaidConfig.json" -> {
            var row = helper.gson.fromJson(raw, GameplayDefinitions.Raid.class);
            GameplayDefinitions.raids.put((long) row.RaidID() * 100 + row.WorldLevel(), row);
          }
          case "ExcelOutput/MainMission.json" ->
              GameData.getMainMissionExcelMap().add(gson.fromJson(raw, MainMissionExcel.class));
          case "ExcelOutput/MazePlane.json" ->
              GameData.getMazePlaneExcelMap().add(gson.fromJson(raw, MazePlaneExcel.class));
          case "ExcelOutput/MapEntrance.json" ->
              GameData.getMapEntranceExcelMap().add(gson.fromJson(raw, MapEntranceExcel.class));
          case "ExcelOutput/SpecialAvatar.json" ->
              GameData.getSpecialAvatarExcelMap().add(gson.fromJson(raw, SpecialAvatarExcel.class));
          case "ExcelOutput/AvatarConfig.json" ->
              GameData.getAvatarExcelMap().add(gson.fromJson(raw, AvatarExcel.class));
          case "ExcelOutput/AvatarPromotionConfig.json" ->
              GameData.getAvatarPromotionExcelMap()
                  .add(gson.fromJson(raw, AvatarPromotionExcel.class));
          case "ExcelOutput/AvatarSkillTreeConfig.json" -> {
            var row = gson.fromJson(raw, AvatarSkillTreeExcel.class);
            GameData.getAvatarSkillTreeExcelMap().add(row);
            row.onLoad();
          }
          case "ExcelOutput/EquipmentPromotionConfig.json" ->
              GameData.getEquipmentPromotionExcelMap()
                  .add(gson.fromJson(raw, EquipmentPromotionExcel.class));
          case "ExcelOutput/ItemConfigEquipment.json" ->
              GameData.getItemExcelMap().add(gson.fromJson(raw, ItemExcel.class));
          case "ExcelOutput/EquipmentConfig.json" -> {
            var row = gson.fromJson(raw, EquipmentExcel.class);
            GameData.getEquipExcelMap().add(row);
            row.onLoad();
          }
          case "ExcelOutput/RewardData.json" -> {
            var row = gson.fromJson(raw, RewardExcel.class);
            GameData.getRewardExcelMap().add(row);
            row.onLoad();
          }
          case "ExcelOutput/MultiplePathAvatarConfig.json" ->
              GameData.getMultiplePathAvatarExcelMap()
                  .add(gson.fromJson(raw, MultiplePathAvatarExcel.class));
          default -> {
            if (entry.getKey().startsWith("ExcelOutput/ItemConfig")) {
              GameData.getItemExcelMap().add(gson.fromJson(raw, ItemExcel.class));
            } else if (entry.getKey().startsWith("Config/BattleLineupPage/SkillTreePointPreset/")) {
              var file = root.resolve(entry.getKey());
              Files.createDirectories(file.getParent());
              Files.writeString(file, raw.toString());
            }
          }
        }
      }
    }
    var rules = root.resolve("ExcelOutput/TeamLimitCondition.json");
    Files.createDirectories(rules.getParent());
    Files.writeString(rules, records.get("ExcelOutput/TeamLimitCondition.json").toString());
  }

  @AfterEach
  void restore() throws Exception {
    helper.restore();
  }

  @ParameterizedTest
  @ValueSource(ints = {1108001, 1108002})
  void originalGenderTaskAppendsToTheDeclaredTrialPartyAndPreservesItsCaptain(int protagonist)
      throws Exception {
    helper.open(4010106, 201141225, protagonist);
    var run = new MissionRaid(player).current();
    assertEquals(List.of(1101110, 1101106), player.getCurrentLineup().getAvatars());
    assertTrue(
        player
            .getRaidTrialManager()
            .allowed(RaidTeams.plan(GameplayDefinitions.raids.get(401010600L))));
    var base = helper.gson.toJson(run.getTrialParty());
    player.getMissionManager().refresh();
    assertInstanceOf(TrialAvatar.class, player.getAvatarById(protagonist));
    assertEquals(List.of(1101110, 1101106, protagonist), player.getCurrentLineup().getAvatars());
    assertEquals(0, player.getCurrentLineup().getLeader());
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(201141225).getPhase());
    assertTrue(new MissionRaid(player).activeFor(2011412));
    assertEquals(base, helper.gson.toJson(run.getTrialParty()));
    assertEquals(Set.of(199986), player.getAvatars().getAvatars().keySet());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void clientLeaderChangesCannotReplaceTheSourceLockedCaptain(int slot) throws Exception {
    helper.open(4010106, 201141225, 1108001);
    player.getMissionManager().refresh();
    var before = helper.gson.toJson(player.getGameplay());
    int saves = player.saves;
    assertFalse(player.getLineupManager().changeLeader(slot));
    player.getCurrentLineup().setLeader(slot);
    assertEquals(0, player.getCurrentLineup().getLeader());
    assertEquals(before, helper.gson.toJson(player.getGameplay()));
    assertEquals(saves, player.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"base", "source"})
  void pausedViewsCannotPublishUnownedTrialParties(String kind) throws Exception {
    helper.open(4010106, 201141225, 1108001);
    var base = player.getRaidTrialManager().lineup();
    player.getMissionManager().refresh();
    var source = player.getCurrentLineup();
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC, new MissionRaid(player).leave(4010106, false));
    int packets = player.packets.size();
    var before = helper.gson.toJson(player.getGameplay());
    (kind.equals("base") ? base : source).refreshLineup();
    assertEquals(packets, player.packets.size());
    assertEquals(before, helper.gson.toJson(player.getGameplay()));
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"level", "promotion", "rank", "skill", "equipmentLevel", "equipmentRank"})
  void modifiedBaseCombatDataCannotBePublishedInTheSourceParty(String field) throws Exception {
    helper.open(4010106, 201141225, 1108001);
    player.getMissionManager().refresh();
    var base = player.getAvatarById(1101110);
    switch (field) {
      case "level" -> base.setLevel(base.getLevel() + 1);
      case "promotion" -> base.setPromotion(base.getPromotion() + 1);
      case "rank" -> base.setRank(base.getRank() + 1);
      case "skill" -> base.getSkillTree().put(1, base.getSkillTree().get(1) + 1);
      case "equipmentLevel" -> base.getEquips().get(100).setLevel(1);
      case "equipmentRank" -> base.getEquips().get(100).setRank(5);
    }
    var before = helper.gson.toJson(player.getGameplay());
    int saves = player.saves;
    assertThrows(IllegalStateException.class, () -> player.getCurrentLineup().toProto());
    assertEquals(before, helper.gson.toJson(player.getGameplay()));
    assertEquals(saves, player.saves);
  }

  @ParameterizedTest
  @ValueSource(ints = {1108001, 1108002})
  void pausingAndRestoringRetainsAllThreeIndependentTrialVitals(int protagonist) throws Exception {
    helper.open(4010106, 201141225, protagonist);
    player.getMissionManager().refresh();
    var run = new MissionRaid(player).current();
    var base = helper.gson.toJson(run.getTrialParty());
    var current = player.getCurrentLineup();
    player.getAvatarById(1101110).setCurrentHp(current, 2468);
    player.getAvatarById(1101106).setCurrentSp(current, 3456);
    player.getAvatarById(protagonist).setCurrentHp(current, 5678);
    current.setMp(2);
    var source = helper.gson.toJson(run.getMissionTrials());
    int entry = player.getEntryId();
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC, new MissionRaid(player).leave(4010106, false));
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
    assertEquals(List.of(199986), player.getCurrentLineup().getAvatars());
    var copy = helper.fixture.reload(player.getGameplay());
    var saved = copy.getGameplay().getMissionRaids().get(4010106);
    copy.getGameplay().beginMissionRaid(saved);
    helper.position(copy, entry);
    copy.getMissionTrialManager().restoreOnLogin();
    assertTrue(new MissionRaid(copy).restoreOnLogin());
    var party = copy.getCurrentLineup();
    assertEquals(List.of(1101110, 1101106, protagonist), party.getAvatars());
    assertEquals(2468, copy.getAvatarById(1101110).getCurrentHp(party));
    assertEquals(3456, copy.getAvatarById(1101106).getCurrentSp(party));
    assertEquals(5678, copy.getAvatarById(protagonist).getCurrentHp(party));
    assertEquals(2, party.getMp());
    assertEquals(base, helper.gson.toJson(saved.getTrialParty()));
    assertEquals(source, helper.gson.toJson(saved.getMissionTrials()));
    assertEquals(0, copy.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"baseActor", "sourceActor", "captain", "roster", "binding", "hp"})
  void damagedFrozenBindingCannotRestoreAnyDynamicTrial(String field) throws Exception {
    helper.open(4010106, 201141225, 1108001);
    player.getMissionManager().refresh();
    var copy = helper.fixture.reload(player.getGameplay());
    var run = new MissionRaid(copy).current();
    var party = run.getMissionTrials().getParty();
    switch (field) {
      case "baseActor" -> party.getBaseActors().remove(1101110);
      case "sourceActor" -> party.getActors().put(1101110, party.getBaseActors().get(1101110));
      case "captain" -> party.leader(1);
      case "roster" -> party.select(List.of(1101106, 1101110, 1108001), 0, false, 201141225);
      case "binding" -> party.bindBase("{}");
      case "hp" ->
          MissionTrialProvisioning46Test.setField(
              party.getVitals().get(1101110), StoryLineState.Vital.class, "hp", 10001);
    }
    var before = helper.gson.toJson(copy.getGameplay());
    assertThrows(IllegalStateException.class, () -> copy.getMissionTrialManager().restoreOnLogin());
    assertNull(copy.getAvatarById(1108001));
    assertEquals(before, helper.gson.toJson(copy.getGameplay()));
    assertEquals(0, copy.saves);
  }

  @ParameterizedTest
  @ValueSource(ints = {1108001, 1108002})
  void actualPlayerDocumentsPreserveTheBaseAndSourceActorsAcrossTwoReads(int protagonist)
      throws Exception {
    helper.open(4010106, 201141225, protagonist);
    player.getMissionManager().refresh();
    var expected = helper.gson.toJson(player.getGameplay());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "trial-only-source-document-fixture");
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
      for (int i = 0; i < 2; i++) {
        var saved =
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(expected, helper.gson.toJson(saved.getGameplay()));
        var copy = helper.fixture.reload(saved.getGameplay());
        helper.position(copy, player.getEntryId());
        copy.getMissionTrialManager().restoreOnLogin();
        assertTrue(new MissionRaid(copy).restoreOnLogin());
        assertEquals(List.of(1101110, 1101106, protagonist), copy.getCurrentLineup().getAvatars());
        assertEquals(expected, helper.gson.toJson(copy.getGameplay()));
        assertEquals(0, copy.saves);
      }
    } finally {
      server.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"level", "gear"})
  void creationRejectsAlreadyModifiedBaseCombatDataBeforeSaving(String field) throws Exception {
    var target = helper.open(4010106, 201141225, 1108001);
    var base = player.getAvatarById(1101110);
    if (field.equals("level")) base.setLevel(1);
    else base.getEquips().get(100).setRank(5);
    var before = helper.gson.toJson(player.getGameplay());
    int saves = player.saves;
    assertFalse(player.getMissionTrialManager().execute(target));
    assertNull(player.getAvatarById(1108001));
    assertNull(new MissionRaid(player).current().getMissionTrials().getParty());
    assertEquals(before, helper.gson.toJson(player.getGameplay()));
    assertEquals(saves, player.saves);
  }

  @Test
  void sixConcurrentRefreshesCreateOneSourcePartyAndOneReceipt() throws Exception {
    helper.open(4010106, 201141225, 1108001);
    var gate = new java.util.concurrent.CountDownLatch(1);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var futures = new ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 6; i++)
        futures.add(
            workers.submit(
                () -> {
                  try {
                    gate.await();
                  } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                  }
                  player.getMissionManager().refresh();
                }));
      gate.countDown();
      for (var future : futures) future.get(30, java.util.concurrent.TimeUnit.SECONDS);
    }
    var state = new MissionRaid(player).current().getMissionTrials();
    assertEquals(Set.of(201141225), state.getExecuted().keySet());
    assertEquals(Set.of(1108001), state.getParty().getActors().keySet());
    assertEquals(Set.of(1101110, 1101106), state.getParty().getBaseActors().keySet());
    assertEquals(List.of(1101110, 1101106, 1108001), player.getCurrentLineup().getAvatars());
    var before = helper.gson.toJson(player.getGameplay());
    player.getMissionManager().refresh();
    assertEquals(before, helper.gson.toJson(player.getGameplay()));
  }

  @ParameterizedTest
  @ValueSource(ints = {1108001, 1108002})
  void allThreeTrialsProduceTheirActualCombatSkillsAndEquipment(int protagonist) throws Exception {
    helper.open(4010106, 201141225, protagonist);
    player.getMissionManager().refresh();
    var party = player.getCurrentLineup();
    for (int slot = 0; slot < party.size(); slot++) {
      int id = party.getAvatars().get(slot);
      var actor = (TrialAvatar) player.getAvatarById(id);
      var row = actor.getSpecialAvatarExcel();
      var combat = actor.toBattleProto(party, slot);
      assertEquals(id, combat.getId());
      assertEquals(row.getLevel(), combat.getLevel());
      assertEquals(row.getPromotion(), combat.getPromotion());
      assertEquals(row.getRank(), combat.getRank());
      assertEquals(actor.getSkillTree().size(), combat.getSkilltreeList().length());
      assertEquals(1, combat.getEquipmentList().length());
      var equipment = combat.getEquipmentList().get(0);
      assertEquals(row.getEquipmentID(), equipment.getId());
      assertEquals(row.getEquipmentLevel(), equipment.getLevel());
      assertEquals(row.getEquipmentPromotion(), equipment.getPromotion());
      assertEquals(row.getEquipmentRank(), equipment.getRank());
      assertEquals(10000, combat.getHp());
    }
  }
}
