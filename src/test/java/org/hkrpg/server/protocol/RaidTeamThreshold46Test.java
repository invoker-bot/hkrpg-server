package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RaidTeamThreshold46Test {
  @TempDir Path root;
  final RaidHybridTrial46Test trial = new RaidHybridTrial46Test();
  final Gson gson = new Gson();
  JsonObject source;

  @BeforeEach
  void setup() throws Exception {
    trial.root = root;
    trial.setup();
    try (var input = getClass().getResourceAsStream("/gameplay/raid-team-thresholds-4.6.json")) {
      source =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
  }

  @AfterEach
  void cleanup() throws Exception {
    trial.cleanup();
  }

  void rules(boolean player, int... ids) throws Exception {
    var rules = new JsonArray();
    rules.add(trial.hybrid.source.get("teamLimit").deepCopy());
    for (var rule : source.getAsJsonArray("rules")) rules.add(rule.deepCopy());
    Files.writeString(root.resolve("ExcelOutput/TeamLimitCondition.json"), rules.toString());
    var row = gson.toJsonTree(trial.hybrid.fixture.resource).getAsJsonObject();
    row.add("TeamLimitIDList", gson.toJsonTree(ids));
    if (player) {
      row.addProperty("TeamType", "Player");
      row.add("TrialAvatarList", new JsonArray());
    }
    trial.raidFields(row);
  }

  GameAvatar owned(RaidHybridTrial46Test.Session session, int id, int level) {
    var actor =
        new GameAvatar() {
          public int getAvatarId() {
            return id;
          }

          public int getActorId() {
            return id;
          }

          public int getMaxSp() {
            return 10000;
          }

          public void save() {}
        };
    actor.setOwner(session.trial);
    actor.setLevel(level);
    session.trial.getAvatars().getAvatars().put(id, actor);
    return actor;
  }

  List<RaidTeams.Selection> fourMembers(RaidHybridTrial46Test.Session session) {
    trial.ownedMember(session, 8001).setLevel(30);
    owned(session, 1002, 30);
    owned(session, 1102, 80);
    return List.of(
        new RaidTeams.Selection(MissionRaid46Test.AVATAR, false),
        new RaidTeams.Selection(1002, false),
        new RaidTeams.Selection(1102, false),
        new RaidTeams.Selection(1011209, true));
  }

  @Test
  void sourceMinimumLevelAcceptsAMixedPartyExactlyAtTheThreshold() throws Exception {
    rules(false, 3, 2);
    var session = trial.ready();
    trial.ownedMember(session, 8001).setLevel(30);
    assertEquals(0, trial.choicesStart(session, trial.mixed(false)));
    assertEquals(40, session.trial.getAvatarById(1011209).getLevel());
    assertTrue(new MissionRaid(session.trial).activeFor(MissionRaid46Test.MAIN));
  }

  @Test
  void sourceFourMemberMinimumCountsMixedFormalAndTrialMembers() throws Exception {
    rules(false, 3, 5);
    var session = trial.ready();
    var selected = fourMembers(session);
    assertEquals(0, trial.choicesStart(session, selected));
    assertEquals(
        selected.stream().map(RaidTeams.Selection::id).toList(),
        session.trial.getCurrentLineup().getAvatars());
    assertEquals(Set.of(1011209), session.trial.getAvatars().getTempAvatars().keySet());
  }

  @Test
  void playerTeamUsesTheSameMinimumLevelRuleAndFreezesIt() throws Exception {
    rules(true, 2);
    var session = trial.ready();
    trial.ownedMember(session, 8001).setLevel(30);
    assertEquals(0, trial.choicesStart(session, List.of()));
    assertNotNull(new MissionRaid(session.trial).current().getFormalTeamPlan());
    assertTrue(new MissionRaid(session.trial).activeFor(MissionRaid46Test.MAIN));
  }

  @ParameterizedTest
  @ValueSource(ints = {29, 30, 31, 80})
  void mixedLevelBoundaryChecksEveryMember(int level) throws Exception {
    rules(false, 3, 2);
    var session = trial.ready();
    trial.ownedMember(session, 8001).setLevel(level);
    int result = trial.choicesStart(session, trial.mixed(false));
    if (level >= 30) assertEquals(0, result);
    else {
      assertNotEquals(0, result);
      assertEquals(MissionRaid46Test.HOME, session.trial.entry);
      assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
      assertTrue(session.trial.getGameplay().getMissionRaids().isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {29, 30, 31, 80})
  void playerLevelBoundaryChecksTheActualOwnedParty(int level) throws Exception {
    rules(true, 2);
    var session = trial.ready();
    trial.ownedMember(session, 8001).setLevel(level);
    int result = trial.choicesStart(session, List.of());
    assertEquals(level >= 30, result == 0);
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
  }

  @Test
  void aHighLeaderAndAverageCannotHideALowerMember() throws Exception {
    rules(false, 3, 2, 5);
    var session = trial.ready();
    var selected = fourMembers(session);
    session.trial.getAvatarById(MissionRaid46Test.AVATAR).setLevel(80);
    session.trial.getAvatarById(1002).setLevel(29);
    assertNotEquals(0, trial.choicesStart(session, selected));
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertTrue(session.trial.getGameplay().getMissionRaids().isEmpty());
  }

  @Test
  void aDeclaredTrialBelowTheThresholdIsRejectedBeforePublishingIt() throws Exception {
    rules(false, 3, 2);
    var raw = trial.source.getAsJsonObject("special").deepCopy();
    raw.addProperty("Level", 29);
    emu.lunarcore.data.GameData.getSpecialAvatarExcelMap()
        .values()
        .removeIf(r -> r.getId() == 1011209);
    emu.lunarcore.data.GameData.getSpecialAvatarExcelMap()
        .add(trial.gson.fromJson(raw, emu.lunarcore.data.excel.SpecialAvatarExcel.class));
    var session = trial.ready();
    trial.ownedMember(session, 8001).setLevel(80);
    assertNotEquals(0, trial.choicesStart(session, trial.mixed(false)));
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertEquals(MissionRaid46Test.HOME, session.trial.entry);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void countCannotBeFilledByMissingDuplicateOrUnownedMembers(int variant) throws Exception {
    rules(false, 3, 5);
    var session = trial.ready();
    var selected = new ArrayList<>(fourMembers(session));
    if (variant == 0) selected.remove(2);
    else if (variant == 1) selected.set(2, selected.get(1));
    else session.trial.getAvatars().getAvatars().remove(1102);
    assertNotEquals(0, trial.choicesStart(session, selected));
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertTrue(session.trial.getGameplay().getMissionRaids().isEmpty());
    assertEquals(MissionRaid46Test.HOME, session.trial.entry);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void thresholdRulesSurviveActiveAndPausedJsonReload(int variant) throws Exception {
    boolean player = variant % 2 == 0, active = variant < 2;
    rules(player, player ? new int[] {2} : new int[] {3, 2, 5});
    var session = trial.ready();
    var selected = player ? List.<RaidTeams.Selection>of() : fourMembers(session);
    if (player) trial.ownedMember(session, 8001).setLevel(30);
    assertEquals(0, trial.choicesStart(session, selected));
    if (!active) assertEquals(0, trial.hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    var restored =
        trial.reload(
            session.trial,
            gson.fromJson(gson.toJson(session.trial.getGameplay()), GameplayState.class));
    if (player) trial.ownedMember(restored, 8001).setLevel(30);
    else fourMembers(restored);
    var run = restored.trial.getGameplay().getMissionRaids().get(MissionRaid46Test.RAID);
    assertEquals(Map.of(2, 30), run.getFormalTeamPlan().getLevels());
    assertEquals(player ? Map.of() : Map.of(5, 4), run.getFormalTeamPlan().getCounts());
    if (active) assertTrue(new MissionRaid(restored.trial).restoreOnLogin());
    else assertEquals(0, trial.choicesStart(restored, selected));
    assertTrue(new MissionRaid(restored.trial).activeFor(MissionRaid46Test.MAIN));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void loweringAnOwnedMemberStopsProgressAndRestoreButAllowsReturningHome(boolean player)
      throws Exception {
    rules(player, player ? new int[] {2} : new int[] {3, 2, 5});
    var session = trial.ready();
    var selected = player ? List.<RaidTeams.Selection>of() : fourMembers(session);
    if (player) trial.ownedMember(session, 8001).setLevel(30);
    assertEquals(0, trial.choicesStart(session, selected));
    session.trial.getAvatarById(MissionRaid46Test.AVATAR).setLevel(29);
    assertFalse(new MissionRaid(session.trial).activeFor(MissionRaid46Test.MAIN));
    assertFalse(new MissionRaid(session.trial).restoreOnLogin());
    assertEquals(MissionRaid46Test.HOME, session.trial.entry);
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertTrue(session.trial.getGameplay().getMissionRaids().containsKey(MissionRaid46Test.RAID));
  }

  @Test
  void changingAnAuthoredThresholdCannotRewriteAnExistingRaidEvenWhenEasier() throws Exception {
    rules(false, 3, 2, 5);
    var session = trial.ready();
    assertEquals(0, trial.choicesStart(session, fourMembers(session)));
    var path = root.resolve("ExcelOutput/TeamLimitCondition.json");
    var rows = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
    for (var row : rows)
      if (row.getAsJsonObject().get("ID").getAsInt() == 2)
        row.getAsJsonObject().addProperty("ParamInt1", 29);
    Files.writeString(path, rows.toString());
    assertFalse(new MissionRaid(session.trial).activeFor(MissionRaid46Test.MAIN));
    assertFalse(new MissionRaid(session.trial).restoreOnLogin());
    assertEquals(
        Map.of(2, 30),
        session
            .trial
            .getGameplay()
            .getMissionRaids()
            .get(MissionRaid46Test.RAID)
            .getFormalTeamPlan()
            .getLevels());
    assertEquals(MissionRaid46Test.HOME, session.trial.entry);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void corruptedSavedThresholdsCannotRelaxActiveOrPausedEntry(boolean active) throws Exception {
    rules(false, 3, 2, 5);
    var session = trial.ready();
    var selected = fourMembers(session);
    assertEquals(0, trial.choicesStart(session, selected));
    if (!active) assertEquals(0, trial.hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    var json = gson.toJsonTree(session.trial.getGameplay()).getAsJsonObject();
    json.getAsJsonObject("missionRaids")
        .getAsJsonObject(Integer.toString(MissionRaid46Test.RAID))
        .getAsJsonObject("formalTeamPlan")
        .getAsJsonObject("counts")
        .addProperty("5", 3);
    var restored = trial.reload(session.trial, gson.fromJson(json, GameplayState.class));
    fourMembers(restored);
    if (active) assertFalse(new MissionRaid(restored.trial).restoreOnLogin());
    else assertNotEquals(0, trial.choicesStart(restored, selected));
    assertEquals(MissionRaid46Test.HOME, restored.trial.entry);
    assertTrue(restored.trial.getAvatars().getTempAvatars().isEmpty());
  }

  @Test
  void legacyPlayerContextWithoutFrozenRulesCannotAdoptNewThresholds() throws Exception {
    rules(true, 2);
    var session = trial.ready();
    trial.ownedMember(session, 8001).setLevel(30);
    assertEquals(0, trial.choicesStart(session, List.of()));
    var json = gson.toJsonTree(session.trial.getGameplay()).getAsJsonObject();
    json.getAsJsonObject("missionRaids")
        .getAsJsonObject(Integer.toString(MissionRaid46Test.RAID))
        .remove("formalTeamPlan");
    var restored = trial.reload(session.trial, gson.fromJson(json, GameplayState.class));
    trial.ownedMember(restored, 8001).setLevel(30);
    assertFalse(new MissionRaid(restored.trial).restoreOnLogin());
    assertEquals(MissionRaid46Test.HOME, restored.trial.entry);
  }

  @Test
  void morphiaPreservesTheRuleIdsAndThresholds() throws Exception {
    rules(false, 3, 2, 5);
    var session = trial.ready();
    assertEquals(0, trial.choicesStart(session, fourMembers(session)));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "thresholdraid");
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
      var restored = trial.reload(session.trial, state);
      fourMembers(restored);
      var plan = state.getMissionRaids().get(MissionRaid46Test.RAID).getFormalTeamPlan();
      assertEquals(Map.of(2, 30), plan.getLevels());
      assertEquals(Map.of(5, 4), plan.getCounts());
      assertTrue(new MissionRaid(restored.trial).restoreOnLogin());
      assertEquals(4, restored.trial.getCurrentLineup().getAvatars().size());
    } finally {
      mongo.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9})
  void malformedOrImpossibleThresholdRulesCannotEnter(int variant) throws Exception {
    rules(false, variant >= 8 ? new int[] {3, 5} : new int[] {3, 2});
    var path = root.resolve("ExcelOutput/TeamLimitCondition.json");
    var rows = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
    var raw =
        rows.asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(row -> row.get("ID").getAsInt() == (variant >= 8 ? 5 : 2))
            .findFirst()
            .orElseThrow();
    switch (variant) {
      case 0 -> raw.addProperty("ParamInt1", 0);
      case 1 -> raw.addProperty("ParamInt1", -1);
      case 2 -> raw.addProperty("ParamInt1", 29.5);
      case 3 -> raw.addProperty("ParamInt1", "30");
      case 4 -> raw.addProperty("ParamInt2", 1);
      case 5 -> raw.addProperty("ParamInt3", 1);
      case 6 -> raw.add("ParamIntList", JsonParser.parseString("[1]"));
      case 7 -> raw.addProperty("UnverifiedCondition", 1);
      case 8 -> raw.addProperty("ParamInt1", 5);
      case 9 -> raw.addProperty("ParamInt1", 0);
    }
    Files.writeString(path, rows.toString());
    var session = trial.ready();
    assertNotEquals(0, trial.choicesStart(session, fourMembers(session)));
    assertTrue(session.trial.getGameplay().getMissionRaids().isEmpty());
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertEquals(MissionRaid46Test.HOME, session.trial.entry);
  }

  @Test
  void resourceFixtureRetainsTheOriginalRaidAndRuleBoundaries() {
    var raids = source.getAsJsonArray("raids");
    assertEquals(2, raids.size());
    var original = raids.get(0).getAsJsonObject();
    assertEquals(1, original.get("RaidID").getAsInt());
    assertEquals("Player", original.get("TeamType").getAsString());
    assertEquals(JsonParser.parseString("[1,2]"), original.get("TeamLimitIDList"));
    assertEquals(JsonParser.parseString("[1011401,1011402]"), original.get("MainMissionIDList"));
    assertFalse(original.has("EnterType"));
    var another = raids.get(1).getAsJsonObject();
    assertEquals(43211, another.get("RaidID").getAsInt());
    assertEquals(JsonParser.parseString("[10,5]"), another.get("TeamLimitIDList"));
    assertEquals(2, source.getAsJsonObject("sourceSha256").size());
    assertFalse(source.getAsJsonObject("clientEvidence").get("clientAccepted").getAsBoolean());
  }
}
