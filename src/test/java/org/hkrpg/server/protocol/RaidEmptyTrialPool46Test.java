package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Empty optional trial catalogs keep a formal team; TrialOnly still requires actual trials. */
class RaidEmptyTrialPool46Test {
  final MissionRaid46Test fixture = new MissionRaid46Test();
  JsonArray rows;

  @BeforeEach
  void setup() throws Exception {
    fixture.setup();
    try (var input = getClass().getResourceAsStream("/fixtures/raid-empty-trial-pool-4.6.json")) {
      rows =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
    var row = fixture.gson.toJsonTree(fixture.resource).getAsJsonObject();
    var original = original(40403);
    for (var name : List.of("TeamType", "TrialAvatarList", "TeamLimitIDList"))
      row.add(name, original.get(name).deepCopy());
    fixture.resource = fixture.gson.fromJson(row, GameplayDefinitions.Raid.class);
    GameplayDefinitions.raids.put(fixture.resource.key(), fixture.resource);
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
  }

  JsonObject original(int id) {
    return rows.asList().stream()
        .map(JsonElement::getAsJsonObject)
        .map(r -> r.getAsJsonObject("row"))
        .filter(r -> r.get("RaidID").getAsInt() == id)
        .findFirst()
        .orElseThrow();
  }

  @ParameterizedTest
  @ValueSource(ints = {40242, 40403, 4420201, 4420202, 4420203, 4420204})
  void originalOptionalCatalogsCanBeEmptyWithoutInventingATrial(int raid) {
    var plan =
        RaidTeams.plan(fixture.gson.fromJson(original(raid), GameplayDefinitions.Raid.class));
    assertNotNull(plan);
    assertEquals("TrialOrPlayer", plan.type());
    assertTrue(plan.trials().isEmpty());
    assertTrue(plan.requirements().isEmpty());
    assertTrue(RaidTeams.snapshotRequired(plan));
  }

  @Test
  void nativeEntryAndLeavePreserveOwnedTeamAndReturnWithoutTrials() throws Exception {
    var actor = fixture.session.actor;
    var owned = Set.copyOf(actor.getAvatars().getAvatars().keySet());
    assertEquals(0, fixture.start());
    assertEquals(MissionRaid46Test.ENTRY, actor.entry);
    assertEquals(List.of(MissionRaid46Test.AVATAR), actor.getCurrentLineup().getAvatars());
    var run = actor.getGameplay().getMissionRaids().get(MissionRaid46Test.RAID);
    assertEquals("TrialOrPlayer", run.getFormalTeamPlan().getType());
    assertTrue(run.getFormalTeamPlan().getTrials().isEmpty());
    assertNull(run.getTrialParty());
    assertTrue(actor.getAvatars().getTempAvatars().isEmpty());
    assertEquals(0, fixture.leave(MissionRaid46Test.RAID, false));
    assertEquals(MissionRaid46Test.HOME, actor.entry);
    assertEquals(12f, actor.pos.getX());
    assertEquals(owned, actor.getAvatars().getAvatars().keySet());
  }

  @Test
  void originalEmptyCatalogStillRejectsAnExplicitTrialBeforeAcceptingTasks() {
    var actor = fixture.session.actor;
    assertEquals(
        Retcode.RAID_AVATAR_NOT_MATCH,
        new MissionRaid(actor)
            .enterSelected(
                MissionRaid46Test.RAID, 0, 0, 0, List.of(new RaidTeams.Selection(1031224, true))));
    assertEquals(MissionRaid46Test.HOME, actor.entry);
    assertFalse(actor.getGameplay().getMainMissions().containsKey(MissionRaid46Test.MAIN));
    assertTrue(actor.getGameplay().getMissionRaids().isEmpty());
    assertTrue(actor.getAvatars().getTempAvatars().isEmpty());
  }

  @Test
  void aForeignFormalSelectionCannotReplaceTheActualOwnedRoster() {
    assertEquals(
        Retcode.RAID_AVATAR_NOT_MATCH,
        new MissionRaid(fixture.session.actor)
            .enterSelected(
                MissionRaid46Test.RAID, 0, 0, 0, List.of(new RaidTeams.Selection(1001, false))));
    assertEquals(MissionRaid46Test.HOME, fixture.session.actor.entry);
    assertEquals(
        List.of(MissionRaid46Test.AVATAR), fixture.session.actor.getCurrentLineup().getAvatars());
  }

  @Test
  void pauseAndJsonRestoreKeepEmptyCatalogAndCompletionCursor() throws Exception {
    var actor = fixture.session.actor;
    assertEquals(0, fixture.start());
    assertEquals(
        Retcode.SUCC, actor.getMissionManager().finishTalk(MissionRaid46Test.FIRST, "first"));
    assertEquals(MissionRaid46Test.NEXT_ENTRY, actor.entry);
    assertEquals(0, fixture.leave(MissionRaid46Test.RAID, false));
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(
        actor,
        fixture.gson.fromJson(fixture.gson.toJson(actor.getGameplay()), GameplayState.class));
    assertEquals(0, fixture.start());
    assertEquals(MissionRaid46Test.NEXT_ENTRY, actor.entry);
    assertEquals(
        QuestState.Phase.FINISHED,
        actor.getMissionManager().sub(MissionRaid46Test.FIRST).getPhase());
    assertTrue(
        actor
            .getGameplay()
            .getMissionRaids()
            .get(MissionRaid46Test.RAID)
            .getFormalTeamPlan()
            .getTrials()
            .isEmpty());
    assertNull(actor.getGameplay().getMissionRaids().get(MissionRaid46Test.RAID).getTrialParty());
  }

  @Test
  void omittedFrozenOptionalCatalogCannotBeInventedOnResume() throws Exception {
    var actor = fixture.session.actor;
    assertEquals(0, fixture.start());
    assertEquals(0, fixture.leave(MissionRaid46Test.RAID, false));
    var data = fixture.gson.toJsonTree(actor.getGameplay()).getAsJsonObject();
    data.getAsJsonObject("missionRaids")
        .getAsJsonObject(Integer.toString(MissionRaid46Test.RAID))
        .remove("formalTeamPlan");
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(actor, fixture.gson.fromJson(data, GameplayState.class));
    var before = fixture.gson.toJson(actor.getGameplay());
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), fixture.start());
    assertEquals(before, fixture.gson.toJson(actor.getGameplay()));
    assertEquals(MissionRaid46Test.HOME, actor.entry);
  }

  @Test
  void aPureTrialTeamWithAnEmptyCatalogRemainsUnsupported() throws Exception {
    var row = fixture.gson.toJsonTree(fixture.resource).getAsJsonObject();
    row.addProperty("TeamType", "TrialOnly");
    GameplayDefinitions.raids.put(
        fixture.resource.key(), fixture.gson.fromJson(row, GameplayDefinitions.Raid.class));
    assertNull(RaidTeams.plan(GameplayDefinitions.raids.get(fixture.resource.key())));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), fixture.start());
    assertTrue(fixture.session.actor.getGameplay().getMissionRaids().isEmpty());
  }
}
