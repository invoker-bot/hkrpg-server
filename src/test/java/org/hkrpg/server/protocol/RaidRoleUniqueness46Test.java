package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MultiplePathAvatarExcel;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** One selected base role per raid party, even when formal/trial instances use different IDs. */
class RaidRoleUniqueness46Test {
  @TempDir Path root;
  final RaidTrialCandidates46Test candidates = new RaidTrialCandidates46Test();

  @BeforeEach
  void setup() throws Exception {
    candidates.root = root;
    candidates.setup();
    candidates.candidates(40546);
  }

  @AfterEach
  void cleanup() throws Exception {
    candidates.cleanup();
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void ownedAndTrialHeroCannotFillTwoSlotsUsingTheSameBaseRole(
      boolean female, boolean differentPath) throws Exception {
    var session = candidates.data.fixture.ready();
    if (female) candidates.data.female(session.trial);
    candidates.data.owned(
        session, 8001, differentPath ? (female ? 8006 : 8005) : (female ? 8008 : 8007));
    var selected =
        List.of(
            new RaidTeams.Selection(1031510, true),
            new RaidTeams.Selection(8001, false),
            new RaidTeams.Selection(female ? 1048008 : 1048007, true));
    assertNotEquals(0, candidates.data.fixture.choicesStart(session, selected));
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertTrue(session.trial.getGameplay().getMissionRaids().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void twoDeclaredTrialPathsCannotDuplicateTheHeroBaseRole(boolean female) throws Exception {
    int extra = female ? 1028006 : 1028005;
    var row =
        candidates
            .data
            .fixture
            .gson
            .toJsonTree(candidates.data.fixture.hybrid.fixture.resource)
            .getAsJsonObject();
    row.getAsJsonArray("TrialAvatarList").add(extra);
    candidates.data.fixture.raidFields(row);
    var session = candidates.data.fixture.ready();
    if (female) candidates.data.female(session.trial);
    assertNotEquals(
        0,
        candidates.data.fixture.choicesStart(
            session, candidates.choices(1031510, extra, female ? 1048008 : 1048007)));
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertTrue(session.trial.getGameplay().getMissionRaids().isEmpty());
  }

  @Test
  void anUnlockedCaptainCannotAllowFormalAndTrialMarchPathsForTheSameRole() throws Exception {
    var row =
        candidates
            .data
            .fixture
            .gson
            .toJsonTree(candidates.data.fixture.hybrid.fixture.resource)
            .getAsJsonObject();
    row.getAsJsonArray("TrialAvatarList").add(1021224);
    candidates.data.fixture.raidFields(row);
    var session = candidates.data.fixture.ready();
    candidates.data.owned(session, 1001, 1224);
    assertNotEquals(
        0,
        candidates.data.fixture.choicesStart(
            session,
            List.of(
                new RaidTeams.Selection(1031510, true),
                new RaidTeams.Selection(1001, false),
                new RaidTeams.Selection(1021224, true))));
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertTrue(session.trial.getGameplay().getMissionRaids().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void duplicateRoleInAFrozenActiveOrPausedRosterCannotRestore(int variant) throws Exception {
    boolean active = variant < 2, female = variant % 2 != 0;
    var session = candidates.data.fixture.ready();
    if (female) candidates.data.female(session.trial);
    candidates.data.owned(session, 8001, female ? 8008 : 8007);
    var selected = candidates.choices(1031510, female ? 1048008 : 1048007);
    assertEquals(0, candidates.data.fixture.choicesStart(session, selected));
    if (!active)
      assertEquals(0, candidates.data.fixture.hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    var gson = new Gson();
    var saved = gson.toJsonTree(session.trial.getGameplay()).getAsJsonObject();
    var party =
        saved
            .getAsJsonObject("missionRaids")
            .getAsJsonObject(Integer.toString(MissionRaid46Test.RAID))
            .getAsJsonObject("trialParty");
    party.getAsJsonArray("roster").add(8001);
    var vital = new JsonObject();
    vital.addProperty("hp", 10000);
    vital.addProperty("sp", 7000);
    party.getAsJsonObject("vitals").add("8001", vital);
    var restored =
        candidates.data.fixture.reload(session.trial, gson.fromJson(saved, GameplayState.class));
    if (female) candidates.data.female(restored.trial);
    candidates.data.owned(restored, 8001, female ? 8008 : 8007);
    if (active) assertFalse(new MissionRaid(restored.trial).restoreOnLogin());
    else {
      var duplicate = new ArrayList<>(selected);
      duplicate.add(new RaidTeams.Selection(8001, false));
      assertNotEquals(0, candidates.data.fixture.choicesStart(restored, duplicate));
    }
    assertTrue(restored.trial.getAvatars().getTempAvatars().isEmpty());
    assertEquals(MissionRaid46Test.HOME, restored.trial.entry);
    assertEquals(
        3,
        restored
            .trial
            .getGameplay()
            .getMissionRaids()
            .get(MissionRaid46Test.RAID)
            .getTrialParty()
            .getRoster()
            .size());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void distinctOwnedAndTrialBaseRolesStillMakeAValidParty(boolean female) throws Exception {
    var session = candidates.data.fixture.ready();
    if (female) candidates.data.female(session.trial);
    candidates.data.owned(session, 1414, 1414);
    assertEquals(
        0,
        candidates.data.fixture.choicesStart(
            session,
            List.of(
                new RaidTeams.Selection(1031510, true),
                new RaidTeams.Selection(1414, false),
                new RaidTeams.Selection(female ? 1048008 : 1048007, true))));
    assertTrue(new MissionRaid(session.trial).activeFor(MissionRaid46Test.MAIN));
    assertEquals(2, session.trial.getAvatars().getTempAvatars().size());
    assertTrue(session.trial.getAvatars().getAvatars().containsKey(1414));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aCachedPartyCannotContinueAfterResourceRelationshipsMakeTwoRolesIdentical(boolean female)
      throws Exception {
    var session = candidates.data.fixture.ready();
    if (female) candidates.data.female(session.trial);
    candidates.data.owned(session, 1414, 1414);
    int path = female ? 8008 : 8007;
    assertEquals(
        0,
        candidates.data.fixture.choicesStart(
            session,
            List.of(
                new RaidTeams.Selection(1031510, true),
                new RaidTeams.Selection(1414, false),
                new RaidTeams.Selection(female ? 1048008 : 1048007, true))));
    assertTrue(new MissionRaid(session.trial).activeFor(MissionRaid46Test.MAIN));
    var gson = candidates.data.fixture.gson;
    var relationship =
        gson.toJsonTree(GameData.getMultiplePathAvatarExcelMap().get(path)).getAsJsonObject();
    relationship.addProperty("BaseAvatarID", 1414);
    GameData.getMultiplePathAvatarExcelMap().keySet().remove(path);
    GameData.getMultiplePathAvatarExcelMap()
        .add(gson.fromJson(relationship, MultiplePathAvatarExcel.class));
    assertFalse(new MissionRaid(session.trial).activeFor(MissionRaid46Test.MAIN));
    assertFalse(new MissionRaid(session.trial).current().isCompleted());
  }
}
