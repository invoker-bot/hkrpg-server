package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.ResourceMap;
import emu.lunarcore.game.avatar.*;
import emu.lunarcore.game.player.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RaidMultiPath46Test {
  @TempDir Path root;
  final RaidHybridTrial46Test fixture = new RaidHybridTrial46Test();
  final List<Runnable> restorations = new ArrayList<>();
  JsonObject source;
  Map<Long, AvatarSkillTreeExcel> cache;

  <T> void install(String name, Class<T> type, ResourceMap<T> map, Collection<T> values) {
    var saved = new ArrayList<>(values);
    restorations.add(
        () -> {
          values.clear();
          saved.forEach(map::add);
        });
    for (var raw : source.getAsJsonArray(name)) map.add(fixture.gson.fromJson(raw, type));
  }

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    fixture.setup();
    try (var input = getClass().getResourceAsStream("/gameplay/raid-multipath-4.6.json")) {
      source =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8))
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
        "paths",
        MultiplePathAvatarExcel.class,
        GameData.getMultiplePathAvatarExcelMap(),
        GameData.getMultiplePathAvatarExcelMap().values());
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
    for (var raw : source.getAsJsonArray("skills")) {
      var decoded = fixture.gson.fromJson(raw, AvatarSkillTreeExcel.class);
      GameData.getAvatarSkillTreeExcelMap().get(decoded.getPointID(), decoded.getLevel()).onLoad();
    }
    install(
        "items", ItemExcel.class, GameData.getItemExcelMap(), GameData.getItemExcelMap().values());
    install(
        "equipmentPromotions",
        EquipmentPromotionExcel.class,
        GameData.getEquipmentPromotionExcelMap(),
        GameData.getEquipmentPromotionExcelMap().values());
    for (var raw : source.getAsJsonArray("equipment"))
      fixture.gson.fromJson(raw, EquipmentExcel.class).onLoad();
  }

  @AfterEach
  void cleanup() throws Exception {
    Collections.reverse(restorations);
    restorations.forEach(Runnable::run);
    GameDepot.getAvatarSkillTreeExcels().clear();
    GameDepot.getAvatarSkillTreeExcels().putAll(cache);
    fixture.cleanup();
  }

  void rule(int raid) throws Exception {
    var original =
        source.getAsJsonArray("raids").asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(r -> r.get("RaidID").getAsInt() == raid)
            .findFirst()
            .orElseThrow();
    var row = fixture.gson.toJsonTree(fixture.hybrid.fixture.resource).getAsJsonObject();
    for (String key :
        List.of(
            "TeamType", "TrialAvatarList", "TeamLimitIDList", "LockCaptain", "LockCaptainAvatarID"))
      if (original.has(key)) row.add(key, original.get(key).deepCopy());
    Files.writeString(
        root.resolve("ExcelOutput/TeamLimitCondition.json"), source.get("rules").toString());
    fixture.raidFields(row);
  }

  void female(Player player) throws Exception {
    var field = Player.class.getDeclaredField("gender");
    field.setAccessible(true);
    field.set(player, PlayerGender.GENDER_WOMAN);
  }

  GameAvatar owned(RaidHybridTrial46Test.Session session, int base, int path) {
    var actor =
        new GameAvatar(GameData.getAvatarExcelMap().get(base)) {
          public void save() {}

          public int getMaxSp() {
            return 10000;
          }
        };
    actor.setOwner(session.trial);
    actor.setLevel(40);
    actor.setMultiPath(new AvatarMultiPath(session.trial, GameData.getAvatarExcelMap().get(path)));
    session.trial.getAvatars().getAvatars().put(base, actor);
    return actor;
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sourceHarmonyRequirementAcceptsTheActualMaleOrFemaleFormalPath(boolean female)
      throws Exception {
    rule(43308);
    var session = fixture.ready();
    if (female) female(session.trial);
    owned(session, 8001, female ? 8006 : 8005);
    assertEquals(0, fixture.choicesStart(session, List.of(new RaidTeams.Selection(8001, false))));
    assertEquals(List.of(8001), session.trial.getCurrentLineup().getAvatars());
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
  }

  @Test
  void sourceFemaleTrialPathUsesThePairedRequirementWithoutGrantingABaseRole() throws Exception {
    rule(43308);
    var session = fixture.ready();
    female(session.trial);
    assertEquals(0, fixture.choicesStart(session, List.of(new RaidTeams.Selection(1028006, true))));
    assertEquals(8006, session.trial.getAvatarById(1028006).getActorId());
    assertFalse(session.trial.getAvatars().getAvatars().containsKey(8001));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sourceMarchSeventhPathAcceptsItsActiveFormalOrDeclaredTrial(boolean trial) throws Exception {
    rule(41212);
    var session = fixture.ready();
    if (!trial) owned(session, 1001, 1224);
    var choices = List.of(new RaidTeams.Selection(trial ? 1021224 : 1001, trial));
    assertDoesNotThrow(
        () ->
            session
                .trial
                .getRaidTrialManager()
                .prepare(RaidTeams.plan(fixture.hybrid.fixture.resource), choices));
    assertEquals(0, fixture.choicesStart(session, choices));
    assertEquals(0, session.trial.getCurrentLineup().getLeader());
    assertTrue(new MissionRaid(session.trial).activeFor(MissionRaid46Test.MAIN));
  }

  void heroRule(int id) throws Exception {
    rule(43308);
    var row = fixture.gson.toJsonTree(fixture.hybrid.fixture.resource).getAsJsonObject();
    row.add("TeamLimitIDList", fixture.gson.toJsonTree(List.of(id)));
    fixture.raidFields(row);
  }

  @ParameterizedTest
  @CsvSource({
    "13,8005,false",
    "13,8006,true",
    "32,8003,false",
    "32,8004,true",
    "48,8009,false",
    "48,8010,true"
  })
  void allAuthoredHeroRequirementsUseTheActualPairedPath(int rule, int path, boolean female)
      throws Exception {
    heroRule(rule);
    var session = fixture.ready();
    if (female) female(session.trial);
    owned(session, 8001, path);
    assertEquals(0, fixture.choicesStart(session, List.of(new RaidTeams.Selection(8001, false))));
    assertTrue(new MissionRaid(session.trial).activeFor(MissionRaid46Test.MAIN));
    assertEquals(
        Map.of(rule, female ? path - 1 : path),
        new MissionRaid(session.trial).current().getFormalTeamPlan().getPaths());
  }

  @ParameterizedTest
  @ValueSource(ints = {8001, 8003, 8007, 8009})
  void owningTheHeroInAnotherPathCannotSatisfyHarmony(int path) throws Exception {
    rule(43308);
    var session = fixture.ready();
    owned(session, 8001, path);
    assertNotEquals(
        0, fixture.choicesStart(session, List.of(new RaidTeams.Selection(8001, false))));
    assertTrue(session.trial.getGameplay().getMissionRaids().isEmpty());
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertEquals(MissionRaid46Test.HOME, session.trial.entry);
  }

  @Test
  void owningMarchSeventhInHerBasePathCannotSatisfyTheNewPath() throws Exception {
    rule(41212);
    var session = fixture.ready();
    owned(session, 1001, 1001);
    assertNotEquals(
        0, fixture.choicesStart(session, List.of(new RaidTeams.Selection(1001, false))));
    assertTrue(session.trial.getGameplay().getMissionRaids().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4})
  void mismatchedFormalPathOrGenderRelationshipsCannotPass(int variant) throws Exception {
    rule(43308);
    var session = fixture.ready();
    var avatar = owned(session, 8001, variant == 0 ? 8006 : 8005);
    if (variant == 1) {
      var field = GameAvatar.class.getDeclaredField("multiPath");
      field.setAccessible(true);
      field.set(avatar, null);
    } else if (variant == 2) avatar.getMultiPath().setOwner(new Player());
    else if (variant == 3) avatar.getMultiPath().setAvatar(session.trial.avatar);
    else if (variant == 4) GameData.getMultiplePathAvatarExcelMap().keySet().remove(8005);
    assertNotEquals(
        0, fixture.choicesStart(session, List.of(new RaidTeams.Selection(8001, false))));
    assertTrue(session.trial.getGameplay().getMissionRaids().isEmpty());
  }

  @Test
  void femaleTrialCannotInventAMissingMalePair() throws Exception {
    rule(43308);
    var session = fixture.ready();
    female(session.trial);
    GameData.getMultiplePathAvatarExcelMap().keySet().remove(8005);
    assertNotEquals(
        0, fixture.choicesStart(session, List.of(new RaidTeams.Selection(1028006, true))));
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void thePathRequirementUsesTheActorPathRatherThanTheTrialId(boolean female) throws Exception {
    rule(43308);
    var session = fixture.ready();
    if (female) female(session.trial);
    var selected = List.of(new RaidTeams.Selection(female ? 1028006 : 1028005, true));
    assertEquals(0, fixture.choicesStart(session, selected));
    var actor = session.trial.getAvatarById(selected.getFirst().id());
    assertEquals(female ? 8006 : 8005, actor.getExcel().getAvatarID());
    assertEquals(selected.getFirst().id(), actor.getBattleAvatarId());
    assertTrue(new MissionRaid(session.trial).activeFor(MissionRaid46Test.MAIN));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
  void frozenPathRulesSurviveActiveAndPausedJsonReload(int variant) throws Exception {
    boolean active = variant < 4, female = variant % 2 != 0, trial = variant % 4 >= 2;
    rule(43308);
    var session = fixture.ready();
    if (female) female(session.trial);
    if (!trial) owned(session, 8001, female ? 8006 : 8005);
    var selected =
        List.of(new RaidTeams.Selection(trial ? female ? 1028006 : 1028005 : 8001, trial));
    assertEquals(0, fixture.choicesStart(session, selected));
    if (!active) assertEquals(0, fixture.hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    var saved =
        new Gson().fromJson(new Gson().toJson(session.trial.getGameplay()), GameplayState.class);
    var restored = fixture.reload(session.trial, saved);
    if (female) female(restored.trial);
    if (!trial) owned(restored, 8001, female ? 8006 : 8005);
    assertEquals(
        Map.of(13, 8005),
        saved.getMissionRaids().get(MissionRaid46Test.RAID).getFormalTeamPlan().getPaths());
    if (active) assertTrue(new MissionRaid(restored.trial).restoreOnLogin());
    else assertEquals(0, fixture.choicesStart(restored, selected));
    assertEquals(
        selected.stream().map(RaidTeams.Selection::id).toList(),
        restored.trial.getCurrentLineup().getAvatars());
    assertTrue(new MissionRaid(restored.trial).activeFor(MissionRaid46Test.MAIN));
    assertEquals(0, fixture.hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    assertEquals(List.of(MissionRaid46Test.AVATAR), restored.trial.getCurrentLineup().getAvatars());
  }

  @Test
  void switchingAnOwnedActivePathStopsProgressAndRestoration() throws Exception {
    rule(43308);
    var session = fixture.ready();
    var avatar = owned(session, 8001, 8005);
    assertEquals(0, fixture.choicesStart(session, List.of(new RaidTeams.Selection(8001, false))));
    avatar.setMultiPath(new AvatarMultiPath(session.trial, GameData.getAvatarExcelMap().get(8003)));
    assertFalse(new MissionRaid(session.trial).activeFor(MissionRaid46Test.MAIN));
    assertFalse(new MissionRaid(session.trial).restoreOnLogin());
    assertEquals(MissionRaid46Test.HOME, session.trial.entry);
    assertTrue(session.trial.getGameplay().getMissionRaids().containsKey(MissionRaid46Test.RAID));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void changingTheSourceRequirementCannotRewriteAnExistingParty(boolean active) throws Exception {
    rule(43308);
    var session = fixture.ready();
    owned(session, 8001, 8005);
    var selected = List.of(new RaidTeams.Selection(8001, false));
    assertEquals(0, fixture.choicesStart(session, selected));
    if (!active) assertEquals(0, fixture.hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    var path = root.resolve("ExcelOutput/TeamLimitCondition.json");
    var rows = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
    rows.forEach(
        raw -> {
          if (raw.getAsJsonObject().get("ID").getAsInt() == 13)
            raw.getAsJsonObject().addProperty("ParamInt1", 8003);
        });
    Files.writeString(path, rows.toString());
    if (active) assertFalse(new MissionRaid(session.trial).restoreOnLogin());
    else assertNotEquals(0, fixture.choicesStart(session, selected));
    assertEquals(
        Map.of(13, 8005),
        session
            .trial
            .getGameplay()
            .getMissionRaids()
            .get(MissionRaid46Test.RAID)
            .getFormalTeamPlan()
            .getPaths());
    assertEquals(MissionRaid46Test.HOME, session.trial.entry);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void corruptSavedPathsCannotRelaxTheAuthoredRequirement(boolean active) throws Exception {
    rule(43308);
    var session = fixture.ready();
    owned(session, 8001, 8005);
    var selected = List.of(new RaidTeams.Selection(8001, false));
    assertEquals(0, fixture.choicesStart(session, selected));
    if (!active) assertEquals(0, fixture.hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    var json = new Gson().toJsonTree(session.trial.getGameplay()).getAsJsonObject();
    json.getAsJsonObject("missionRaids")
        .getAsJsonObject(Integer.toString(MissionRaid46Test.RAID))
        .getAsJsonObject("formalTeamPlan")
        .getAsJsonObject("paths")
        .addProperty("13", 8003);
    var restored = fixture.reload(session.trial, new Gson().fromJson(json, GameplayState.class));
    owned(restored, 8001, 8005);
    if (active) assertFalse(new MissionRaid(restored.trial).restoreOnLogin());
    else assertNotEquals(0, fixture.choicesStart(restored, selected));
    assertEquals(MissionRaid46Test.HOME, restored.trial.entry);
    assertTrue(restored.trial.getAvatars().getTempAvatars().isEmpty());
  }

  @Test
  void morphiaPreservesNonemptyPathRequirementsAndTrialParty() throws Exception {
    rule(43308);
    var session = fixture.ready();
    female(session.trial);
    assertEquals(0, fixture.choicesStart(session, List.of(new RaidTeams.Selection(1028006, true))));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "pathraid");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var saved = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(saved, 19997913);
      var field = Player.class.getDeclaredField("gameplay");
      field.setAccessible(true);
      field.set(saved, session.trial.getGameplay());
      store.save(saved);
      var state = Objects.requireNonNull(store.find(Player.class).first()).getGameplay();
      var restored = fixture.reload(session.trial, state);
      female(restored.trial);
      assertEquals(
          Map.of(13, 8005),
          state.getMissionRaids().get(MissionRaid46Test.RAID).getFormalTeamPlan().getPaths());
      assertTrue(new MissionRaid(restored.trial).restoreOnLogin());
      assertTrue(new MissionRaid(restored.trial).activeFor(MissionRaid46Test.MAIN));
    } finally {
      mongo.shutdownNow();
    }
  }
}
