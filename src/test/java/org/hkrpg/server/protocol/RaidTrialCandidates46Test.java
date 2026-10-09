package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Source candidate fields isolated from unverified Performance/SkipJoinLineup entry semantics. */
class RaidTrialCandidates46Test {
  @TempDir Path root;
  final RaidMultiPath46Test data = new RaidMultiPath46Test();
  JsonObject source;

  @BeforeEach
  void setup() throws Exception {
    data.root = root;
    data.setup();
    try (var input = getClass().getResourceAsStream("/gameplay/raid-trial-candidates-4.6.json")) {
      source =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
    // Reuse the resource-map restoration discipline of the existing source fixture.
    var previous = data.source;
    data.source = source;
    data.install(
        "avatars",
        AvatarExcel.class,
        GameData.getAvatarExcelMap(),
        GameData.getAvatarExcelMap().values());
    data.install(
        "avatarPromotions",
        AvatarPromotionExcel.class,
        GameData.getAvatarPromotionExcelMap(),
        GameData.getAvatarPromotionExcelMap().values());
    data.install(
        "paths",
        MultiplePathAvatarExcel.class,
        GameData.getMultiplePathAvatarExcelMap(),
        GameData.getMultiplePathAvatarExcelMap().values());
    data.install(
        "specials",
        SpecialAvatarExcel.class,
        GameData.getSpecialAvatarExcelMap(),
        GameData.getSpecialAvatarExcelMap().values());
    data.install(
        "skills",
        AvatarSkillTreeExcel.class,
        GameData.getAvatarSkillTreeExcelMap(),
        GameData.getAvatarSkillTreeExcelMap().values());
    for (var raw : source.getAsJsonArray("skills")) {
      var decoded = data.fixture.gson.fromJson(raw, AvatarSkillTreeExcel.class);
      GameData.getAvatarSkillTreeExcelMap().get(decoded.getPointID(), decoded.getLevel()).onLoad();
    }
    data.install(
        "items", ItemExcel.class, GameData.getItemExcelMap(), GameData.getItemExcelMap().values());
    data.install(
        "equipmentPromotions",
        EquipmentPromotionExcel.class,
        GameData.getEquipmentPromotionExcelMap(),
        GameData.getEquipmentPromotionExcelMap().values());
    for (var raw : source.getAsJsonArray("equipment"))
      data.fixture.gson.fromJson(raw, EquipmentExcel.class).onLoad();
    data.source = previous;
  }

  @AfterEach
  void cleanup() throws Exception {
    data.cleanup();
  }

  void candidates(int raid) throws Exception {
    var original =
        source.getAsJsonArray("raids").asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(r -> r.get("RaidID").getAsInt() == raid)
            .findFirst()
            .orElseThrow();
    assertTrue(original.get("SkipJoinLineup").getAsBoolean());
    var row = data.fixture.gson.toJsonTree(data.fixture.hybrid.fixture.resource).getAsJsonObject();
    for (String key : List.of("TeamType", "TrialAvatarList", "TeamLimitIDList"))
      row.add(key, original.get(key).deepCopy());
    row.addProperty("LockCaptain", false);
    row.addProperty("LockCaptainAvatarID", 0);
    Files.writeString(
        root.resolve("ExcelOutput/TeamLimitCondition.json"), source.get("rules").toString());
    data.fixture.raidFields(row);
  }

  List<RaidTeams.Selection> choices(int... ids) {
    return Arrays.stream(ids).mapToObj(id -> new RaidTeams.Selection(id, true)).toList();
  }

  @ParameterizedTest
  @CsvSource({"40473,12", "40475,13", "40546,7"})
  void sourceCandidateCatalogIsIndependentOfTheFourSelectedSlots(int raid, int count)
      throws Exception {
    candidates(raid);
    var plan = RaidTeams.plan(data.fixture.hybrid.fixture.resource);
    assertNotNull(plan);
    assertEquals(count, plan.trials().size());
    assertEquals("TrialOrPlayer", plan.type());
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        1011415, 1021402, 1021405, 1021407, 1011412, 1031406, 1021414, 1021413, 1021410, 1021409,
        1021404, 1021403, 1031408
      })
  void eachSource40475CandidateCanBeExplicitlySelectedWithTheRequiredRole(int id) throws Exception {
    candidates(40475);
    var session = data.fixture.ready();
    var owned = Set.copyOf(session.trial.getAvatars().getAvatars().keySet());
    var selected = id == 1011415 ? choices(id) : choices(1011415, id);
    assertEquals(0, data.fixture.choicesStart(session, selected));
    assertEquals(
        selected.stream().map(RaidTeams.Selection::id).toList(),
        session.trial.getCurrentLineup().getAvatars());
    assertEquals(selected.size(), session.trial.getAvatars().getTempAvatars().size());
    assertEquals(owned, session.trial.getAvatars().getAvatars().keySet());
    var actor = session.trial.getAvatarById(id);
    assertEquals(40, actor.getLevel());
    assertEquals(id, actor.getBattleAvatarId());
    assertTrue(actor.getSkillTree().size() > 0);
  }

  @ParameterizedTest
  @CsvSource({
    "1048007,false",
    "1048008,true",
    "1031414,false",
    "1031413,false",
    "1031313,false",
    "1011004,false"
  })
  void source40546CandidatesRespectGenderAndTheirActualSkillVariant(int id, boolean female)
      throws Exception {
    candidates(40546);
    var session = data.fixture.ready();
    var owned = Set.copyOf(session.trial.getAvatars().getAvatars().keySet());
    if (female) data.female(session.trial);
    assertEquals(0, data.fixture.choicesStart(session, choices(1031510, id)));
    assertEquals(List.of(1031510, id), session.trial.getCurrentLineup().getAvatars());
    assertEquals(id == 1011004 ? 1 : 0, session.trial.getAvatarById(id).getEnhanceId());
    assertEquals(owned, session.trial.getAvatars().getAvatars().keySet());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void fourSelectedMembersAndTheWholeCatalogSurviveActiveAndPausedRestore(boolean active)
      throws Exception {
    candidates(40475);
    var session = data.fixture.ready();
    var selected = choices(1011415, 1021402, 1021405, 1031408);
    assertEquals(0, data.fixture.choicesStart(session, selected));
    session.trial.getAvatarById(1031408).setCurrentHp(session.trial.getCurrentLineup(), 4321);
    if (!active) assertEquals(0, data.fixture.hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    var gson = new Gson();
    var restored =
        data.fixture.reload(
            session.trial,
            gson.fromJson(gson.toJson(session.trial.getGameplay()), GameplayState.class));
    if (active) assertTrue(new MissionRaid(restored.trial).restoreOnLogin());
    else assertEquals(0, data.fixture.choicesStart(restored, selected));
    assertEquals(
        13, new MissionRaid(restored.trial).current().getFormalTeamPlan().getTrials().size());
    assertEquals(
        selected.stream().map(RaidTeams.Selection::id).toList(),
        restored.trial.getCurrentLineup().getAvatars());
    assertEquals(
        4321,
        restored.trial.getAvatarById(1031408).getCurrentHp(restored.trial.getCurrentLineup()));
    assertEquals(4, restored.trial.getAvatars().getTempAvatars().size());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void aLargeCatalogCannotBypassThePartyLimitOrDeclaredRequiredRole(int variant) throws Exception {
    candidates(40475);
    var session = data.fixture.ready();
    var selected =
        switch (variant) {
          case 0 -> choices(1011415, 1021402, 1021405, 1021407, 1031408);
          case 1 -> choices(1011415, 1011415);
          case 2 -> choices(1011415, 1011209);
          default -> choices(1021402);
        };
    assertNotEquals(0, data.fixture.choicesStart(session, selected));
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertTrue(session.trial.getGameplay().getMissionRaids().isEmpty());
    assertEquals(MissionRaid46Test.HOME, session.trial.entry);
  }

  @Test
  void increasingACatalogCannotEnableAnUnverifiedSkipJoinLineupEntry() throws Exception {
    var row = data.fixture.gson.toJsonTree(data.fixture.hybrid.fixture.resource).getAsJsonObject();
    row.addProperty("SkipJoinLineup", true);
    data.fixture.raidFields(row);
    var session = data.fixture.ready();
    assertNotEquals(0, data.fixture.choicesStart(session, choices(1011209)));
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertTrue(session.trial.getGameplay().getMissionRaids().isEmpty());
  }

  @Test
  void aTrialOnlyAutomaticPartyStillCannotContainMoreThanFourMembers() throws Exception {
    candidates(40475);
    var row = data.fixture.gson.toJsonTree(data.fixture.hybrid.fixture.resource).getAsJsonObject();
    row.addProperty("TeamType", "TrialOnly");
    data.fixture.raidFields(row);
    var session = data.fixture.ready();
    assertNotEquals(0, data.fixture.choicesStart(session, List.of()));
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
  }
}
