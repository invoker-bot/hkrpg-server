package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.ResourceMap;
import emu.lunarcore.game.player.PlayerGender;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exact source team fields with explicitly isolated mission, map and parent fixtures. */
class RaidTrialOnly46Test {
  @TempDir Path root;
  final RaidHybridTrial46Test fixture = new RaidHybridTrial46Test();
  final List<Runnable> restorations = new ArrayList<>();
  JsonObject source;
  Map<Long, AvatarSkillTreeExcel> cache;

  <T> void install(String key, Class<T> type, ResourceMap<T> map, Collection<T> values) {
    var saved = new ArrayList<>(values);
    restorations.add(
        () -> {
          values.clear();
          saved.forEach(map::add);
        });
    for (var raw : source.getAsJsonArray(key)) map.add(fixture.gson.fromJson(raw, type));
  }

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    fixture.setup();
    try (var in = getClass().getResourceAsStream("/gameplay/raid-trial-only-4.6.json")) {
      source =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
    cache = new HashMap<>(GameDepot.getAvatarSkillTreeExcels());
    install(
        "avatars",
        AvatarExcel.class,
        GameData.getAvatarExcelMap(),
        GameData.getAvatarExcelMap().values());
    install(
        "avatarPromotions",
        AvatarPromotionExcel.class,
        GameData.getAvatarPromotionExcelMap(),
        GameData.getAvatarPromotionExcelMap().values());
    install(
        "specials",
        SpecialAvatarExcel.class,
        GameData.getSpecialAvatarExcelMap(),
        GameData.getSpecialAvatarExcelMap().values());
    install(
        "skills",
        AvatarSkillTreeExcel.class,
        GameData.getAvatarSkillTreeExcelMap(),
        GameData.getAvatarSkillTreeExcelMap().values());
    install(
        "items", ItemExcel.class, GameData.getItemExcelMap(), GameData.getItemExcelMap().values());
    install(
        "equipmentPromotions",
        EquipmentPromotionExcel.class,
        GameData.getEquipmentPromotionExcelMap(),
        GameData.getEquipmentPromotionExcelMap().values());
    install(
        "paths",
        MultiplePathAvatarExcel.class,
        GameData.getMultiplePathAvatarExcelMap(),
        GameData.getMultiplePathAvatarExcelMap().values());
    var actorIds = new HashSet<Integer>();
    for (var raw : source.getAsJsonArray("avatars"))
      actorIds.add(raw.getAsJsonObject().get("AvatarID").getAsInt());
    GameData.getAvatarSkillTreeExcelMap().values().stream()
        .filter(row -> actorIds.contains(row.getAvatarID()))
        .forEach(AvatarSkillTreeExcel::onLoad);
    for (var raw : source.getAsJsonArray("equipment"))
      fixture.gson.fromJson(raw, EquipmentExcel.class).onLoad();
    Files.writeString(
        root.resolve("Config/BattleLineupPage/SkillTreePointPreset/preset.json"),
        source.get("preset").toString());
    Files.writeString(
        root.resolve("Config/BattleLineupPage/SkillTreePointPreset/level-preset.json"),
        source.get("levelPreset").toString());
    Files.writeString(
        root.resolve("ExcelOutput/TeamLimitCondition.json"), source.get("rules").toString());
  }

  @AfterEach
  void cleanup() throws Exception {
    Collections.reverse(restorations);
    restorations.forEach(Runnable::run);
    GameDepot.getAvatarSkillTreeExcels().clear();
    GameDepot.getAvatarSkillTreeExcels().putAll(cache);
    fixture.cleanup();
  }

  JsonObject team(int raid) {
    var selected =
        source.getAsJsonArray("raids").asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(row -> row.get("RaidID").getAsInt() == raid)
            .findFirst()
            .orElseThrow();
    var row = fixture.gson.toJsonTree(fixture.hybrid.fixture.resource).getAsJsonObject();
    for (String key :
        List.of(
            "TeamType",
            "TrialAvatarList",
            "TeamLimitIDList",
            "LockCaptain",
            "LockCaptainAvatarID")) {
      row.remove(key);
      if (selected.has(key)) row.add(key, selected.get(key).deepCopy());
    }
    return row;
  }

  void gender(RaidHybridTrial46Test.Session session, PlayerGender value) throws Exception {
    var field = emu.lunarcore.game.player.Player.class.getDeclaredField("gender");
    field.setAccessible(true);
    field.set(session.trial, value);
  }

  @ParameterizedTest
  @ValueSource(ints = {40222001, 40531, 40452})
  void originalTeamDeclarationEntersWithoutGrantingFormalRoles(int raid) throws Exception {
    fixture.raidFields(team(raid));
    var session = fixture.ready();
    assertDoesNotThrow(
        () ->
            session
                .trial
                .getRaidTrialManager()
                .prepare(
                    RaidTeams.plan(fixture.hybrid.fixture.resource),
                    session
                        .trial
                        .getRaidTrialManager()
                        .defaults(RaidTeams.plan(fixture.hybrid.fixture.resource))));
    assertEquals(0, fixture.choicesStart(session, List.of()));
    var expected =
        switch (raid) {
          case 40222001 -> List.of(1021002, 1021203, 1021206);
          case 40531 -> List.of(1031003);
          default -> List.of(1028007);
        };
    assertEquals(expected, session.trial.getCurrentLineup().getAvatars());
    assertEquals(0, session.trial.getCurrentLineup().getLeader());
    for (int id : expected) {
      var battle =
          session.trial.getAvatarById(id).toBattleProto(session.trial.getCurrentLineup(), 0);
      assertEquals(raid == 40222001 ? 38 : 40, battle.getLevel());
      assertTrue(battle.getSkilltreeList().length() > 0);
    }
    assertEquals(
        Set.of(MissionRaid46Test.AVATAR), session.trial.getAvatars().getAvatars().keySet());
    assertEquals(0, fixture.hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sourceGenderAndBaseCaptainPickOnlyTheMatchingDeclaredPath(boolean female) throws Exception {
    var row = team(40452);
    row.addProperty("LockCaptain", true);
    row.addProperty("LockCaptainAvatarID", 8001);
    fixture.raidFields(row);
    var session = fixture.ready();
    gender(session, female ? PlayerGender.GENDER_WOMAN : PlayerGender.GENDER_MAN);
    assertEquals(0, fixture.choicesStart(session, List.of()));
    assertEquals(
        List.of(female ? 1028008 : 1028007), session.trial.getCurrentLineup().getAvatars());
    assertTrue(new MissionRaid(session.trial).activeFor(MissionRaid46Test.MAIN));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4})
  void trialOnlyCannotBeReplacedWithFormalOppositeGenderDuplicateOrUndeclaredMembers(int variant)
      throws Exception {
    fixture.raidFields(team(40452));
    var session = fixture.ready();
    var selected =
        switch (variant) {
          case 0 -> List.of(new RaidTeams.Selection(MissionRaid46Test.AVATAR, false));
          case 1 -> List.of(new RaidTeams.Selection(1028008, true));
          case 2 ->
              List.of(
                  new RaidTeams.Selection(1028007, true), new RaidTeams.Selection(1028007, true));
          case 3 ->
              List.of(
                  new RaidTeams.Selection(1028007, true), new RaidTeams.Selection(1028008, true));
          default -> List.of(new RaidTeams.Selection(1011209, true));
        };
    assertNotEquals(0, fixture.choicesStart(session, selected));
    assertEquals(MissionRaid46Test.HOME, session.trial.entry);
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertEquals(0, session.trial.getGameplay().getCurrentMissionRaid());
  }

  @Test
  void lockedCaptainStillUsesItsActorWhenDeclaredOrderChanges() throws Exception {
    var row = team(40222001);
    row.add("TrialAvatarList", JsonParser.parseString("[1021203,1021002,1021206]"));
    fixture.raidFields(row);
    var session = fixture.ready();
    assertEquals(0, fixture.choicesStart(session, List.of()));
    assertEquals(1, session.trial.getCurrentLineup().getLeader());
    assertNotEquals(Retcode.SUCC, LineupLeaders.change(session.trial, 2));
    assertEquals(Retcode.SUCC, LineupLeaders.change(session.trial, 1));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void invalidCaptainDeclarationsNeverEnter(int variant) throws Exception {
    var row = team(40222001);
    switch (variant) {
      case 0 -> row.addProperty("LockCaptainAvatarID", 1209);
      case 1 -> row.addProperty("LockCaptainAvatarID", -1);
      case 2 -> row.addProperty("LockCaptain", false);
      default -> row.remove("LockCaptainAvatarID");
    }
    fixture.raidFields(row);
    var session = fixture.ready();
    assertNotEquals(0, fixture.choicesStart(session, List.of()));
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void savedAutomaticPartyCannotRestoreWithChangedGenderOrLeader(boolean gender) throws Exception {
    fixture.raidFields(team(gender ? 40452 : 40222001));
    var original = fixture.ready();
    assertEquals(0, fixture.choicesStart(original, List.of()));
    var data = fixture.savedGson.toJsonTree(original.trial.getGameplay()).getAsJsonObject();
    if (!gender)
      data.getAsJsonObject("missionRaids")
          .getAsJsonObject(Integer.toString(MissionRaid46Test.RAID))
          .getAsJsonObject("trialParty")
          .addProperty("leader", 1);
    var restored =
        fixture.reload(original.trial, fixture.savedGson.fromJson(data, GameplayState.class));
    if (gender) gender(restored, PlayerGender.GENDER_WOMAN);
    assertFalse(new MissionRaid(restored.trial).restoreOnLogin());
    assertEquals(0, restored.trial.getGameplay().getCurrentMissionRaid());
    assertEquals(MissionRaid46Test.HOME, restored.trial.entry);
    assertTrue(restored.trial.getAvatars().getTempAvatars().isEmpty());
    assertTrue(restored.trial.getGameplay().getMissionRaids().containsKey(MissionRaid46Test.RAID));
  }

  @Test
  void frozenCaptainAndReplaceRuleCannotBeChangedByTheCurrentResource() throws Exception {
    fixture.raidFields(team(40222001));
    var session = fixture.ready();
    assertEquals(0, fixture.choicesStart(session, List.of()));
    var row = team(40222001);
    row.addProperty("LockCaptainAvatarID", 1203);
    fixture.raidFields(row);
    assertFalse(new MissionRaid(session.trial).activeFor(MissionRaid46Test.MAIN));
  }

  @Test
  void missingDeclaredTrialDoesNotSilentlyShrinkTheAutomaticRoster() throws Exception {
    fixture.raidFields(team(40222001));
    GameData.getSpecialAvatarExcelMap().values().removeIf(row -> row.getId() == 1021203);
    var session = fixture.ready();
    assertNotEquals(0, fixture.choicesStart(session, List.of()));
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
  }
}
