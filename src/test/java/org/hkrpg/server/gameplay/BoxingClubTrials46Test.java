package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.protocol.VersionedPacketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original first-season trial rows; prerequisites and windows are explicit fixtures. */
class BoxingClubTrials46Test {
  @TempDir Path root;
  final BoxingClubRounds46Test fixture = new BoxingClubRounds46Test();
  final List<SpecialAvatarExcel> oldTrials = new ArrayList<>();
  final List<AvatarSkillTreeExcel> oldSkills = new ArrayList<>();
  final Map<Long, AvatarSkillTreeExcel> oldSkillCache = new HashMap<>();
  final List<AvatarPromotionExcel> oldPromotions = new ArrayList<>();
  final List<EquipmentExcel> oldEquipment = new ArrayList<>();
  final List<EquipmentPromotionExcel> oldEquipmentPromotions = new ArrayList<>();

  @BeforeEach
  void load() throws Exception {
    fixture.root = root;
    fixture.load();
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
    var source = fixture.fixture("boxing-club-trials-4.6.json");
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
    for (int id : List.of(1204, 1109, 1103, 1104, 1003))
      GameData.getAvatarExcelMap().get(id).onLoad();
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(50000, 5000001, 1, Long.MAX_VALUE));
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
    fixture.restore();
  }

  void matchTrial(int trial) throws Exception {
    matchTrial(1, trial);
  }

  void matchTrial(int challenge, int trial) throws Exception {
    var schema = fixture.session.getProtocol().clientSchema();
    var type = schema.message("MatchBoxingClubOpponentCsReq");
    var request =
        DynamicMessage.newBuilder(type).setField(type.findFieldByName("challenge_id"), challenge);
    var member = type.findFieldByName("OOJOMKDMBBM").getMessageType();
    var f = member.findFieldByName("avatar_type");
    request.addRepeatedField(
        type.findFieldByName("OOJOMKDMBBM"),
        DynamicMessage.newBuilder(member)
            .setField(member.findFieldByName("avatar_id"), trial)
            .setField(f, f.getEnumType().findValueByName("AvatarType_AvatarTrialType"))
            .build());
    assertTrue(VersionedPacketHandler.handle(fixture.session, 4205, request.build().toByteArray()));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
  void nativeMatchAllowsTheOriginalTrialAtEachWorldWithoutPublishingAnOwnedActor(int world)
      throws Exception {
    fixture.session.actor.world = world;
    assertNotNull(fixture.session.actor.getTrialManager().buildTrialAvatar(3051204));
    matchTrial(3051204);
    assertEquals(
        0,
        fixture.number(fixture.session.response, "retcode"),
        "Original first-season trial remains unavailable");
    var battle = fixture.start();
    var actor = battle.toProto().getBattleAvatarList().get(0);
    var original = GameData.getSpecialAvatarExcelMap().get(3051204, world);
    assertEquals(AvatarType.AVATAR_TRIAL_TYPE, actor.getAvatarType());
    assertEquals(3051204, actor.getId());
    assertEquals(world, actor.getWorldLevel());
    assertEquals(original.getLevel(), actor.getLevel());
    assertEquals(original.getPromotion(), actor.getPromotion());
    assertEquals(original.getRank(), actor.getRank());
    assertEquals(10000, actor.getHp());
    assertEquals(5000, actor.getSpBar().getCurSp());
    assertEquals(1, actor.getEquipmentList().length());
    var gear = actor.getEquipmentList().get(0);
    assertEquals(original.getEquipmentID(), gear.getId());
    assertEquals(original.getEquipmentLevel(), gear.getLevel());
    assertEquals(original.getEquipmentPromotion(), gear.getPromotion());
    assertEquals(original.getEquipmentRank(), gear.getRank());
    assertTrue(actor.getSkilltreeList().length() > 0);
    assertEquals(0, actor.getRelicList().length());
    assertEquals(Set.of(1001), fixture.session.actor.getAvatars().getAvatars().keySet());
    assertTrue(fixture.session.actor.getAvatars().getTempAvatars().isEmpty());
    assertEquals(0, fixture.session.actor.grants);
    assertSame(battle, fixture.finish(battle, BattleEndStatus.BATTLE_END_LOSE, 0));
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> originalTrials() {
    int[] ids = {3051204, 3051109, 3051103, 3051104, 3051003};
    return java.util.stream.IntStream.range(0, 35)
        .mapToObj(
            i -> org.junit.jupiter.params.provider.Arguments.of(i / 7 + 1, ids[i / 7], i % 7));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("originalTrials")
  void allThirtyFiveOriginalTrialWorldRowsBuildOnlyTheirDeclaredActor(
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
  @ValueSource(ints = {1, 2, 3, 4, 5})
  void eachUnlockedFirstSeasonChallengeMatchesItsOwnOriginalTrialAfterActualPreviousWins(
      int challenge) throws Exception {
    for (int previous = 1; previous < challenge; previous++) {
      fixture.match(previous, 1001);
      assertEquals(0, fixture.number(fixture.session.response, "retcode"));
      fixture.fourWins(1);
      GameplayDefinitions.windows.add(
          new GameplayDefinitions.Window(50000, 5000002 + previous, 1, Long.MAX_VALUE));
    }
    int[] ids = {3051204, 3051109, 3051103, 3051104, 3051003};
    matchTrial(challenge, ids[challenge - 1]);
    assertEquals(0, fixture.number(fixture.session.response, "retcode"));
    var entry = BoxingClubManager.start(fixture.session.actor, challenge, java.time.Instant.now());
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, entry.retcode());
    assertEquals(ids[challenge - 1], entry.battle().toProto().getBattleAvatarList().get(0).getId());
    assertSame(entry.battle(), fixture.finish(entry.battle(), BattleEndStatus.BATTLE_END_LOSE, 0));
  }

  @ParameterizedTest
  @ValueSource(ints = {1001, 3051104, 999999})
  void borrowedFormalForeignChallengeAndUnknownTrialIDsCannotCreateAMatch(int id) throws Exception {
    matchTrial(id);
    assertNotEquals(0, fixture.number(fixture.session.response, "retcode"));
    assertNull(fixture.session.actor.getGameplay().getBoxingClub().getActive());
    assertEquals(0, fixture.session.actor.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"closed", "missing", "changed", "loaded", "preset", "temporary"})
  void invalidTrialAvailabilityOrSourceRejectsOnlyTheTrialAndLeavesFormalMatchingAvailable(
      String variant) throws Exception {
    if (variant.equals("closed"))
      GameplayDefinitions.windows.removeIf(w -> w.moduleId() == 5000001);
    else if (variant.equals("missing"))
      Files.delete(root.resolve("ExcelOutput/SpecialAvatar.json"));
    else if (variant.equals("changed")) {
      var file = root.resolve("ExcelOutput/SpecialAvatar.json");
      var rows = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
      rows.get(0).getAsJsonObject().addProperty("Level", 1);
      Files.writeString(file, rows.toString());
    } else if (variant.equals("loaded")) {
      var row = GameData.getSpecialAvatarExcelMap().get(3051204, 0);
      var f = SpecialAvatarExcel.class.getDeclaredField("Level");
      f.setAccessible(true);
      f.setInt(row, 1);
    } else if (variant.equals("preset"))
      Files.delete(
          root.resolve(
              "Config/BattleLineupPage/SkillTreePointPreset/SkillTreePointPreset_01.json"));
    else
      fixture
          .session
          .actor
          .getAvatars()
          .getTempAvatars()
          .put(3051204, fixture.session.actor.getTrialManager().buildTrialAvatar(3051204));
    matchTrial(3051204);
    assertNotEquals(0, fixture.number(fixture.session.response, "retcode"));
    assertNull(fixture.session.actor.getGameplay().getBoxingClub().getActive());
    assertEquals(0, fixture.session.actor.grants);
    fixture.match(1, 1001);
    assertEquals(0, fixture.number(fixture.session.response, "retcode"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"closed", "missing", "loaded", "preset"})
  void trialChangesDuringABattleCannotProduceAWinButCanReleaseABoundLoss(String variant)
      throws Exception {
    matchTrial(3051204);
    assertEquals(0, fixture.number(fixture.session.response, "retcode"));
    var battle = fixture.start();
    if (variant.equals("closed"))
      GameplayDefinitions.windows.removeIf(w -> w.moduleId() == 5000001);
    else if (variant.equals("missing"))
      Files.delete(root.resolve("ExcelOutput/SpecialAvatar.json"));
    else if (variant.equals("loaded"))
      GameData.getSpecialAvatarExcelMap()
          .values()
          .remove(GameData.getSpecialAvatarExcelMap().get(3051204, 0));
    else
      Files.delete(
          root.resolve(
              "Config/BattleLineupPage/SkillTreePointPreset/SkillTreePointPreset_01.json"));
    assertNull(fixture.finish(battle, BattleEndStatus.BATTLE_END_WIN, 1));
    assertSame(battle, fixture.session.actor.getBattle());
    assertSame(battle, fixture.finish(battle, BattleEndStatus.BATTLE_END_LOSE, 0));
    assertNull(fixture.session.actor.getBattle());
    assertEquals(0, fixture.session.actor.grants);
  }

  @Test
  void olderFormalOnlyRecordsWithoutRosterTypesStillRestoreAsFormal() throws Exception {
    fixture.match(1, 1001);
    var gson = ResourceLoader.getGson();
    var raw =
        gson.toJsonTree(fixture.session.actor.getGameplay().getBoxingClub()).getAsJsonObject();
    raw.getAsJsonObject("active").remove("rosterTypes");
    var restored = gson.fromJson(raw, BoxingClubState.class);
    fixture.session.actor.getGameplay().boxingClub(restored);
    var battle = fixture.start();
    assertEquals(
        AvatarType.AVATAR_FORMAL_TYPE,
        battle.toProto().getBattleAvatarList().get(0).getAvatarType());
    assertEquals(1001, battle.toProto().getBattleAvatarList().get(0).getId());
  }

  @Test
  void formalAndTrialFormsOfTheSameCharacterCannotOccupyTwoSlots() throws Exception {
    var formal = new LocalLegendEntry46Test.Formal(GameData.getAvatarExcelMap().get(1204));
    formal.setOwner(fixture.session.actor);
    var id = emu.lunarcore.game.avatar.GameAvatar.class.getDeclaredField("id");
    id.setAccessible(true);
    id.set(formal, new org.bson.types.ObjectId());
    fixture.session.actor.getAvatars().getAvatars().put(1204, formal);
    fixture.session.actor.getAvatars().getAvatarObjectIdMap().put(formal.getId(), formal);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.REQ_PARA_INVALID,
        BoxingClubManager.matchMembers(
            fixture.session.actor,
            1,
            List.of(
                new BoxingClubManager.Member(1204, 1), new BoxingClubManager.Member(3051204, 3)),
            java.time.Instant.now()));
    assertNull(fixture.session.actor.getGameplay().getBoxingClub().getActive());
    assertEquals(0, fixture.session.actor.saves);
  }

  @Test
  void serializedMixedRosterPreservesMemberTypesAndRebuildsBattleOnlyTrials() throws Exception {
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        BoxingClubManager.matchMembers(
            fixture.session.actor,
            1,
            List.of(
                new BoxingClubManager.Member(3051204, 3), new BoxingClubManager.Member(1001, 1)),
            java.time.Instant.now()));
    var gson = ResourceLoader.getGson();
    var restored =
        gson.fromJson(
            gson.toJson(fixture.session.actor.getGameplay().getBoxingClub()),
            BoxingClubState.class);
    fixture.session.actor.getGameplay().boxingClub(restored);
    assertEquals(
        List.of(new BoxingClubManager.Member(3051204, 3), new BoxingClubManager.Member(1001, 1)),
        restored.getActive().members());
    var battle = fixture.start();
    var actors = battle.toProto().getBattleAvatarList();
    assertEquals(2, actors.length());
    assertEquals(AvatarType.AVATAR_TRIAL_TYPE, actors.get(0).getAvatarType());
    assertEquals(AvatarType.AVATAR_FORMAL_TYPE, actors.get(1).getAvatarType());
    assertTrue(fixture.session.actor.getAvatars().getTempAvatars().isEmpty());
    assertEquals(Set.of(1001), fixture.session.actor.getAvatars().getAvatars().keySet());
  }
}
