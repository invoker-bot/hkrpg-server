package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MultiplePathAvatarExcel;
import java.nio.file.Path;
import java.util.List;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Gender-bound trials must remain valid after restoring or reusing their frozen raid party. */
class RaidTrialGender46Test {
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

  void heroWithoutPathRequirement() throws Exception {
    data.rule(43308);
    var row = data.fixture.gson.toJsonTree(data.fixture.hybrid.fixture.resource).getAsJsonObject();
    row.add("TeamLimitIDList", data.fixture.gson.toJsonTree(List.of()));
    data.fixture.raidFields(row);
  }

  void changeGender(emu.lunarcore.game.player.Player player, boolean female) throws Exception {
    var field = emu.lunarcore.game.player.Player.class.getDeclaredField("gender");
    field.setAccessible(true);
    field.set(
        player,
        female
            ? emu.lunarcore.game.player.PlayerGender.GENDER_WOMAN
            : emu.lunarcore.game.player.PlayerGender.GENDER_MAN);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aCachedHeroTrialCannotContinueUnderTheOppositePlayerGender(boolean female) throws Exception {
    heroWithoutPathRequirement();
    var session = data.fixture.ready();
    changeGender(session.trial, female);
    assertEquals(
        0,
        data.fixture.choicesStart(
            session, List.of(new RaidTeams.Selection(female ? 1028006 : 1028005, true))));
    changeGender(session.trial, !female);
    assertFalse(new MissionRaid(session.trial).activeFor(MissionRaid46Test.MAIN));
    assertFalse(new MissionRaid(session.trial).restoreOnLogin());
    assertEquals(MissionRaid46Test.HOME, session.trial.entry);
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertTrue(session.trial.getGameplay().getMissionRaids().containsKey(MissionRaid46Test.RAID));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void restoredHeroTrialsCannotBypassTheOriginalGenderCheck(int variant) throws Exception {
    boolean active = variant < 2, female = variant % 2 != 0;
    heroWithoutPathRequirement();
    var session = data.fixture.ready();
    changeGender(session.trial, female);
    var selected = List.of(new RaidTeams.Selection(female ? 1028006 : 1028005, true));
    assertEquals(0, data.fixture.choicesStart(session, selected));
    if (!active) assertEquals(0, data.fixture.hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    var gson = new Gson();
    var restored =
        data.fixture.reload(
            session.trial,
            gson.fromJson(gson.toJson(session.trial.getGameplay()), GameplayState.class));
    changeGender(restored.trial, !female);
    if (active) assertFalse(new MissionRaid(restored.trial).restoreOnLogin());
    else assertNotEquals(0, data.fixture.choicesStart(restored, selected));
    assertEquals(MissionRaid46Test.HOME, restored.trial.entry);
    assertTrue(restored.trial.getAvatars().getTempAvatars().isEmpty());
    assertTrue(restored.trial.getGameplay().getMissionRaids().containsKey(MissionRaid46Test.RAID));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void neutralMarchSeventhTrialDoesNotFreezeAnUnrelatedPlayerGender(boolean active)
      throws Exception {
    data.rule(41212);
    var session = data.fixture.ready();
    var selected = List.of(new RaidTeams.Selection(1021224, true));
    assertEquals(0, data.fixture.choicesStart(session, selected));
    if (!active) assertEquals(0, data.fixture.hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    var gson = new Gson();
    var restored =
        data.fixture.reload(
            session.trial,
            gson.fromJson(gson.toJson(session.trial.getGameplay()), GameplayState.class));
    changeGender(restored.trial, true);
    if (active) assertTrue(new MissionRaid(restored.trial).restoreOnLogin());
    else assertEquals(0, data.fixture.choicesStart(restored, selected));
    assertTrue(new MissionRaid(restored.trial).activeFor(MissionRaid46Test.MAIN));
    assertEquals(List.of(1021224), restored.trial.getCurrentLineup().getAvatars());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aChangedHeroGenderRelationshipStopsBothCachedAndRestoredActors(boolean restored)
      throws Exception {
    heroWithoutPathRequirement();
    var session = data.fixture.ready();
    assertEquals(
        0, data.fixture.choicesStart(session, List.of(new RaidTeams.Selection(1028005, true))));
    var row =
        data.source.getAsJsonArray("paths").asList().stream()
            .filter(r -> r.getAsJsonObject().get("AvatarID").getAsInt() == 8005)
            .findFirst()
            .orElseThrow()
            .getAsJsonObject()
            .deepCopy();
    row.addProperty("Gender", "GENDER_WOMAN");
    GameData.getMultiplePathAvatarExcelMap()
        .add(data.fixture.gson.fromJson(row, MultiplePathAvatarExcel.class));
    if (restored) {
      var gson = new Gson();
      session =
          data.fixture.reload(
              session.trial,
              gson.fromJson(gson.toJson(session.trial.getGameplay()), GameplayState.class));
    }
    assertFalse(new MissionRaid(session.trial).activeFor(MissionRaid46Test.MAIN));
    assertFalse(new MissionRaid(session.trial).restoreOnLogin());
    assertEquals(MissionRaid46Test.HOME, session.trial.entry);
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
  }
}
