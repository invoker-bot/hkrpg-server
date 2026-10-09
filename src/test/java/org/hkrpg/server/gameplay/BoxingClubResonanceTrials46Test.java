package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original second-season trial rows; prerequisites and windows are explicit fixtures. */
class BoxingClubResonanceTrials46Test {
  @TempDir Path root;
  final BoxingClubResonance46Test base = new BoxingClubResonance46Test();
  final BoxingClubRounds46Test fixture = base.fixture;
  final List<SpecialAvatarExcel> oldTrials = new ArrayList<>();
  final List<AvatarSkillTreeExcel> oldSkills = new ArrayList<>();
  final Map<Long, AvatarSkillTreeExcel> oldSkillCache = new HashMap<>();
  final List<AvatarPromotionExcel> oldPromotions = new ArrayList<>();
  final List<EquipmentExcel> oldEquipment = new ArrayList<>();
  final List<EquipmentPromotionExcel> oldEquipmentPromotions = new ArrayList<>();

  @BeforeEach
  void load() throws Exception {
    base.root = root;
    base.load();
    oldTrials.addAll(GameData.getSpecialAvatarExcelMap().values());
    oldSkills.addAll(GameData.getAvatarSkillTreeExcelMap().values());
    oldSkillCache.putAll(GameDepot.getAvatarSkillTreeExcels());
    oldPromotions.addAll(GameData.getAvatarPromotionExcelMap().values());
    oldEquipment.addAll(GameData.getEquipExcelMap().values());
    oldEquipmentPromotions.addAll(GameData.getEquipmentPromotionExcelMap().values());
    GameData.getSpecialAvatarExcelMap().values().clear();
    GameData.getAvatarSkillTreeExcelMap().values().clear();
    GameDepot.getAvatarSkillTreeExcels().clear();
    GameData.getAvatarPromotionExcelMap().values().clear();
    GameData.getEquipExcelMap().values().clear();
    GameData.getEquipmentPromotionExcelMap().values().clear();
    var source = fixture.fixture("boxing-club-resonance-trials-4.6.json");
    var gson = ResourceLoader.getGson();
    for (var input : source.getAsJsonArray("inputs")) {
      var row = input.getAsJsonObject();
      var file = root.resolve(row.get("path").getAsString());
      Files.createDirectories(file.getParent());
      Files.writeString(file, row.get("rows").toString());
      for (var raw : row.getAsJsonArray("rows"))
        switch (file.getFileName().toString()) {
          case "SpecialAvatar.json" ->
              GameData.getSpecialAvatarExcelMap().add(gson.fromJson(raw, SpecialAvatarExcel.class));
          case "AvatarConfig.json" ->
              GameData.getAvatarExcelMap().add(gson.fromJson(raw, AvatarExcel.class));
          case "ItemConfigEquipment.json" ->
              GameData.getItemExcelMap().add(gson.fromJson(raw, ItemExcel.class));
          case "EquipmentConfig.json" -> {
            var v = gson.fromJson(raw, EquipmentExcel.class);
            v.onLoad();
            GameData.getEquipExcelMap().add(v);
          }
          case "EquipmentPromotionConfig.json" ->
              GameData.getEquipmentPromotionExcelMap()
                  .add(gson.fromJson(raw, EquipmentPromotionExcel.class));
          case "AvatarSkillTreeConfig.json" -> {
            var v = gson.fromJson(raw, AvatarSkillTreeExcel.class);
            v.onLoad();
            GameData.getAvatarSkillTreeExcelMap().add(v);
          }
          case "AvatarPromotionConfig.json" ->
              GameData.getAvatarPromotionExcelMap()
                  .add(gson.fromJson(raw, AvatarPromotionExcel.class));
        }
    }
    for (var input : source.getAsJsonArray("presets")) {
      var row = input.getAsJsonObject();
      var file = root.resolve(row.get("path").getAsString());
      Files.createDirectories(file.getParent());
      Files.write(file, HexFormat.of().parseHex(row.get("rawHex").getAsString()));
    }
    // Mirrors ResourceLoader's post-table actor promotion link, without loading operator data.
    for (var entry : source.getAsJsonArray("inputs")) {
      var row = entry.getAsJsonObject();
      if (row.get("path").getAsString().endsWith("AvatarConfig.json"))
        for (var actor : row.getAsJsonArray("rows"))
          GameData.getAvatarExcelMap()
              .get(actor.getAsJsonObject().get("AvatarID").getAsInt())
              .onLoad();
    }
  }

  @AfterEach
  void restore() throws Exception {
    GameData.getSpecialAvatarExcelMap().values().clear();
    oldTrials.forEach(GameData.getSpecialAvatarExcelMap()::add);
    GameData.getAvatarSkillTreeExcelMap().values().clear();
    oldSkills.forEach(GameData.getAvatarSkillTreeExcelMap()::add);
    GameDepot.getAvatarSkillTreeExcels().clear();
    GameDepot.getAvatarSkillTreeExcels().putAll(oldSkillCache);
    GameData.getAvatarPromotionExcelMap().values().clear();
    oldPromotions.forEach(GameData.getAvatarPromotionExcelMap()::add);
    GameData.getEquipExcelMap().values().clear();
    oldEquipment.forEach(GameData.getEquipExcelMap()::add);
    GameData.getEquipmentPromotionExcelMap().values().clear();
    oldEquipmentPromotions.forEach(GameData.getEquipmentPromotionExcelMap()::add);
    base.restore();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
  void nativeRosterAdmitsOriginalSecondSeasonTrialAtEveryWorld(int world) throws Exception {
    fixture.session.actor.world = world;
    assertNotNull(fixture.session.actor.getTrialManager().buildTrialAvatar(3071302));
    base.seedRoster(6, 3071302, "AvatarType_AvatarTrialType");
    assertEquals(0, base.code(), "Original second-season trial remains unavailable");
    assertNull(fixture.session.actor.getBattle());
    assertFalse(fixture.session.actor.getAvatars().getAvatars().containsKey(3071302));
    assertTrue(fixture.session.actor.getAvatars().getTempAvatars().isEmpty());
    assertEquals(0, fixture.session.actor.grants);
    base.choose(6, base.resonance(6), true);
    assertEquals(0, base.code());
    var battle = base.start(6);
    var actor = battle.toProto().getBattleAvatarList().get(0);
    var expected = GameData.getSpecialAvatarExcelMap().get(3071302, world);
    assertEquals(AvatarType.AVATAR_TRIAL_TYPE, actor.getAvatarType());
    assertEquals(3071302, actor.getId());
    assertEquals(world, actor.getWorldLevel());
    assertEquals(expected.getLevel(), actor.getLevel());
    assertEquals(expected.getRank(), actor.getRank());
    assertEquals(expected.getPromotion(), actor.getPromotion());
    assertEquals(expected.getEquipmentID(), actor.getEquipmentList().get(0).getId());
    assertEquals(expected.getEquipmentLevel(), actor.getEquipmentList().get(0).getLevel());
    assertEquals(10000, actor.getHp());
    assertEquals(5000, actor.getSpBar().getCurSp());
    assertTrue(actor.getSkilltreeList().length() > 0);
    assertEquals(0, actor.getRelicList().length());
    assertSame(battle, base.finish(battle, BattleEndStatus.BATTLE_END_LOSE, 0));
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> originalTrials() {
    int[] ids = {
      3071302, 3071111, 3071212, 3071106, 3071210, 3071009, 3071108, 3071005, 3071006, 3071102,
      3071004, 3071213, 3071204, 3071008
    };
    return java.util.stream.IntStream.range(0, 98)
        .mapToObj(
            i -> org.junit.jupiter.params.provider.Arguments.of(6 + i / 14, ids[i / 7], i % 7));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("originalTrials")
  void allNinetyEightOriginalTrialWorldRowsBuildOnlyTheirDeclaredActor(
      int challenge, int id, int world) {
    fixture.session.actor.world = world;
    var party =
        BoxingClubParty.prepare(
            fixture.session.actor,
            challenge,
            List.of(new BoxingClubManager.Member(id, 3)),
            java.time.Instant.now());
    assertNotNull(party);
    var actor = party.battleActors().get(0);
    var expected = GameData.getSpecialAvatarExcelMap().get(id, world);
    assertEquals(id, actor.getId());
    assertEquals(AvatarType.AVATAR_TRIAL_TYPE, actor.getAvatarType());
    assertEquals(expected.getLevel(), actor.getLevel());
    assertEquals(expected.getPromotion(), actor.getPromotion());
    assertEquals(expected.getRank(), actor.getRank());
    assertEquals(expected.getEquipmentID(), actor.getEquipmentList().get(0).getId());
    assertEquals(10000, actor.getHp());
    assertEquals(5000, actor.getSpBar().getCurSp());
    assertTrue(party.valid());
    assertEquals(Set.of(1001), fixture.session.actor.getAvatars().getAvatars().keySet());
    assertTrue(fixture.session.actor.getAvatars().getTempAvatars().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {6, 7, 8, 9, 10, 11, 12})
  void eachOriginalChallengeCanUseItsTrialAfterActuallyWinningEveryPreviousChallenge(int challenge)
      throws Exception {
    for (int id = 6; id < challenge; id++) {
      GameplayDefinitions.windows.add(
          new GameplayDefinitions.Window(40003, base.challenge(id).module(), 1, Long.MAX_VALUE));
      if (id == 6) GameplayDefinitions.windows.remove(GameplayDefinitions.windows.size() - 1);
      base.prepare(id);
      base.threeWins(id, 1, 0);
    }
    if (challenge > 6)
      GameplayDefinitions.windows.add(
          new GameplayDefinitions.Window(
              40003, base.challenge(challenge).module(), 1, Long.MAX_VALUE));
    int[] ids = {3071302, 3071212, 3071210, 3071108, 3071006, 3071004, 3071204};
    base.seedRoster(challenge, ids[challenge - 6], "AvatarType_AvatarTrialType");
    assertEquals(0, base.code());
    base.choose(challenge, base.resonance(challenge), true);
    assertEquals(0, base.code());
    var b = base.start(challenge);
    assertEquals(ids[challenge - 6], b.toProto().getBattleAvatarList().get(0).getId());
    assertSame(b, base.finish(b, BattleEndStatus.BATTLE_END_LOSE, 0));
  }

  @ParameterizedTest
  @ValueSource(ints = {1001, 3051204, 3071212, 999999})
  void borrowedFormalOrOtherChallengeOrSeasonIDsCannotBecomeADeclaredTrial(int id)
      throws Exception {
    base.seedRoster(6, id, "AvatarType_AvatarTrialType");
    assertNotEquals(0, base.code());
    assertEquals(0, fixture.session.actor.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "changed", "loaded", "preset", "temporary"})
  void invalidTrialSourceBlocksOnlyTrialAdmissionAndLeavesFormalEntryAvailable(String kind)
      throws Exception {
    switch (kind) {
      case "missing" -> Files.delete(root.resolve("ExcelOutput/SpecialAvatar.json"));
      case "changed" -> {
        var file = root.resolve("ExcelOutput/SpecialAvatar.json");
        var rows = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
        rows.get(0).getAsJsonObject().addProperty("Level", 1);
        Files.writeString(file, rows.toString());
      }
      case "loaded" -> {
        var row = GameData.getSpecialAvatarExcelMap().get(3071302, 0);
        var f = SpecialAvatarExcel.class.getDeclaredField("Level");
        f.setAccessible(true);
        f.setInt(row, 1);
      }
      case "preset" ->
          Files.delete(
              root.resolve(
                  "Config/BattleLineupPage/SkillTreePointPreset/SkillTreePointPreset_01.json"));
      case "temporary" ->
          fixture.session.actor.getAvatars().getTempAvatars().put(3071302, fixture.formal);
    }
    base.seedRoster(6, 3071302, "AvatarType_AvatarTrialType");
    assertNotEquals(0, base.code());
    assertEquals(0, fixture.session.actor.saves);
    base.seed(6);
    assertNull(fixture.session.actor.getBattle());
    assertEquals(0, fixture.session.actor.grants);
  }

  @Test
  void laterChallengesNeedTheSeparateOriginalTrialModuleEvenWhenTheirOwnModuleIsOpen()
      throws Exception {
    base.prepare(6);
    base.threeWins(6, 1, 0);
    GameplayDefinitions.windows.removeIf(w -> w.moduleId() == 4000301);
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(40003, 4000302, 1, Long.MAX_VALUE));
    int saves = fixture.session.actor.saves;
    base.seedRoster(7, 3071212, "AvatarType_AvatarTrialType");
    assertNotEquals(0, base.code());
    assertEquals(saves, fixture.session.actor.saves);
    base.seed(7);
    assertEquals(0, base.code());
  }

  @Test
  void trialMemberTypesAndPendingOptionsSurviveSavedRunReconstruction() throws Exception {
    base.seedRoster(6, 3071302, "AvatarType_AvatarTrialType");
    assertEquals(0, base.code());
    base.choose(6, base.resonance(6), true);
    assertEquals(0, base.code());
    var b = base.start(6);
    assertSame(b, base.finish(b, BattleEndStatus.BATTLE_END_WIN, 1));
    var gson = new Gson();
    var copy =
        gson.fromJson(
            gson.toJson(fixture.session.actor.getGameplay().getBoxingClubResonance()),
            BoxingClubResonanceState.class);
    fixture.session.actor.setInstance(null);
    fixture.session.actor.getGameplay().boxingClubResonance(copy);
    BoxingClubManager.restore(fixture.session.actor);
    assertEquals(List.of(new BoxingClubManager.Member(3071302, 3)), copy.getActive().members());
    assertTrue(copy.valid(BoxingClubResonanceRules.load(), fixture.session.actor.getUid()));
    base.choose(6, base.options(6, 1).get(0), false);
    assertEquals(0, base.code());
    b = base.start(6);
    assertEquals(
        AvatarType.AVATAR_TRIAL_TYPE, b.toProto().getBattleAvatarList().get(0).getAvatarType());
    assertSame(b, base.finish(b, BattleEndStatus.BATTLE_END_LOSE, 0));
  }
}
