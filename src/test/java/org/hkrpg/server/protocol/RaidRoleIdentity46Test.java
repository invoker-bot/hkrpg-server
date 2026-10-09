package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Raw IncludeAvatar1 combined with source trials in an explicitly isolated raid. */
class RaidRoleIdentity46Test {
  @TempDir Path root;
  final RaidMultiPath46Test data = new RaidMultiPath46Test();

  @BeforeEach
  void setup() throws Exception {
    data.root = root;
    data.setup();
  }

  @AfterEach
  void cleanup() throws Exception {
    data.cleanup();
  }

  void rule(boolean actorPathInsteadOfBase) throws Exception {
    data.rule(43308);
    JsonObject base;
    try (var input = getClass().getResourceAsStream("/gameplay/raid-role-identity-4.6.json")) {
      var all =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
      base = all.getAsJsonObject("rule").deepCopy();
    }
    assertEquals("IncludeAvatar", base.get("LimitType").getAsString());
    assertEquals(8001, base.get("ParamInt1").getAsInt());
    if (actorPathInsteadOfBase) base.addProperty("ParamInt1", 8005);
    var rules = data.source.getAsJsonArray("rules").deepCopy();
    rules.add(base);
    Files.writeString(root.resolve("ExcelOutput/TeamLimitCondition.json"), rules.toString());
    var row = data.fixture.gson.toJsonTree(data.fixture.hybrid.fixture.resource).getAsJsonObject();
    row.add("TeamLimitIDList", data.fixture.gson.toJsonTree(List.of(13, 1)));
    data.fixture.raidFields(row);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sourceHeroRequirementAcceptsTrialRoleIdentityAndPreservesItsActualActorPath(boolean female)
      throws Exception {
    rule(false);
    var session = data.fixture.ready();
    if (female) data.female(session.trial);
    int trial = female ? 1028006 : 1028005;
    assertEquals(
        0, data.fixture.choicesStart(session, List.of(new RaidTeams.Selection(trial, true))));
    var actor = session.trial.getAvatarById(trial);
    assertEquals(female ? 8006 : 8005, actor.getActorId());
    assertEquals(trial, actor.getBattleAvatarId());
    assertFalse(session.trial.getAvatars().getAvatars().containsKey(8001));
    assertTrue(new MissionRaid(session.trial).activeFor(MissionRaid46Test.MAIN));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sourceHeroRequirementStillAcceptsTheOwnedBaseIdentity(boolean female) throws Exception {
    rule(false);
    var session = data.fixture.ready();
    if (female) data.female(session.trial);
    data.owned(session, 8001, female ? 8006 : 8005);
    assertEquals(
        0, data.fixture.choicesStart(session, List.of(new RaidTeams.Selection(8001, false))));
    assertTrue(new MissionRaid(session.trial).activeFor(MissionRaid46Test.MAIN));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void frozenTrialRoleRequirementsSurviveActiveAndPausedRestore(int variant) throws Exception {
    boolean active = variant < 2, female = variant % 2 != 0;
    rule(false);
    var session = data.fixture.ready();
    if (female) data.female(session.trial);
    var selected = List.of(new RaidTeams.Selection(female ? 1028006 : 1028005, true));
    assertEquals(0, data.fixture.choicesStart(session, selected));
    if (!active) assertEquals(0, data.fixture.hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    var gson = new Gson();
    var restored =
        data.fixture.reload(
            session.trial,
            gson.fromJson(gson.toJson(session.trial.getGameplay()), GameplayState.class));
    if (female) data.female(restored.trial);
    if (active) assertTrue(new MissionRaid(restored.trial).restoreOnLogin());
    else assertEquals(0, data.fixture.choicesStart(restored, selected));
    assertEquals(
        Map.of(1, 8001),
        new MissionRaid(restored.trial).current().getFormalTeamPlan().getRequirements());
    assertTrue(new MissionRaid(restored.trial).activeFor(MissionRaid46Test.MAIN));
  }

  @Test
  void anIncludeRoleConditionCannotBeSatisfiedOnlyByItsCurrentPathNumber() throws Exception {
    rule(true);
    var session = data.fixture.ready();
    assertNotEquals(
        0, data.fixture.choicesStart(session, List.of(new RaidTeams.Selection(1028005, true))));
    assertTrue(session.trial.getGameplay().getMissionRaids().isEmpty());
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aTrialCannotInventAMissingBaseRoleRelationship(boolean female) throws Exception {
    rule(false);
    var session = data.fixture.ready();
    if (female) data.female(session.trial);
    GameData.getMultiplePathAvatarExcelMap().keySet().remove(female ? 8006 : 8005);
    assertNotEquals(
        0,
        data.fixture.choicesStart(
            session, List.of(new RaidTeams.Selection(female ? 1028006 : 1028005, true))));
    assertTrue(session.trial.getGameplay().getMissionRaids().isEmpty());
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
  }
}
