package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.trial.TrialAvatar;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Original task/actor identities; acceptance, current scene, skills and storage are isolated
 * fixtures.
 */
class GetTrialAvatarMission46Test {
  final Gson gson = new Gson();
  final Map<Integer, JsonObject> cases = new HashMap<>();
  final Map<Integer, MissionInfo> oldMissions = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  final List<SpecialAvatarExcel> oldSpecials = new ArrayList<>();
  final List<AvatarExcel> oldActors = new ArrayList<>();
  final List<TrialActorExcel> oldPlotActors = new ArrayList<>();
  final List<MainMissionExcel> oldMains = new ArrayList<>();
  Actor player;

  static class Actor extends Player {
    int plane, floor, saves;
    boolean virtual;
    final PlayerLineup party =
        new PlayerLineup(this, 0) {
          @Override
          public boolean isExtraLineup() {
            return virtual;
          }
        };

    @Override
    public int getPlaneId() {
      return plane;
    }

    @Override
    public int getFloorId() {
      return floor;
    }

    @Override
    public int getWorldLevel() {
      return 0;
    }

    @Override
    public PlayerLineup getCurrentLineup() {
      return party;
    }

    @Override
    public void save() {
      saves++;
    }
  }

  @BeforeEach
  void setup() throws Exception {
    oldMissions.putAll(GameData.getMissionInfos());
    oldSubs.putAll(GameplayDefinitions.subs);
    oldSpecials.addAll(GameData.getSpecialAvatarExcelMap().values());
    oldActors.addAll(GameData.getAvatarExcelMap().values());
    oldPlotActors.addAll(GameData.getTrialActorExcelMap().values());
    oldMains.addAll(GameData.getMainMissionExcelMap().values());
    GameData.getMissionInfos().clear();
    GameplayDefinitions.subs.clear();
    GameData.getSpecialAvatarExcelMap().values().clear();
    GameData.getAvatarExcelMap().values().clear();
    GameData.getTrialActorExcelMap().values().clear();
    GameData.getMainMissionExcelMap().values().clear();
    try (var reader =
        new InputStreamReader(
            getClass().getResourceAsStream("/fixtures/get-trial-avatar-missions-4.6.json"),
            StandardCharsets.UTF_8)) {
      for (var raw : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("cases")) {
        var row = raw.getAsJsonObject();
        cases.put(row.getAsJsonObject("sub").get("ID").getAsInt(), row);
      }
    }
    player = new Actor();
  }

  @AfterEach
  void restore() {
    GameData.getMissionInfos().clear();
    GameData.getMissionInfos().putAll(oldMissions);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
    GameData.getSpecialAvatarExcelMap().values().clear();
    oldSpecials.forEach(GameData.getSpecialAvatarExcelMap()::add);
    GameData.getAvatarExcelMap().values().clear();
    oldActors.forEach(GameData.getAvatarExcelMap()::add);
    GameData.getTrialActorExcelMap().values().clear();
    oldPlotActors.forEach(GameData.getTrialActorExcelMap()::add);
    GameData.getMainMissionExcelMap().values().clear();
    oldMains.forEach(GameData.getMainMissionExcelMap()::add);
  }

  MissionInfo.SubMissionInfo accepted(int id, boolean publish) {
    var raw = cases.get(id);
    var sub = gson.fromJson(raw.get("sub"), MissionInfo.SubMissionInfo.class);
    var task = gson.fromJson(raw.get("script"), MissionTaskInfo.class);
    task.retainSourceDefinition(raw.get("script"));
    player.virtual = task.getSourceDefinition().contains("RPG.GameCore.ReplaceVirtualTeam");
    sub.setTask(task);
    GameplayDefinitions.subs.put(id, sub);
    // The unrelated final objective prevents a one-step fixture from closing the full story.
    GameData.getMissionInfos()
        .put(
            sub.getMainMissionID(),
            gson.fromJson(
                "{\"MainMissionID\":"
                    + sub.getMainMissionID()
                    + ",\"FinishSubMissionList\":[199988999]}",
                MissionInfo.class));
    player
        .getGameplay()
        .getMainMissions()
        .put(sub.getMainMissionID(), new QuestState(sub.getMainMissionID()));
    player.getGameplay().getSubMissions().put(id, new QuestState(id));
    player.plane = sub.getLevelPlaneID();
    player.floor = sub.getLevelFloorID();
    var special = gson.fromJson(raw.get("special"), SpecialAvatarExcel.class);
    GameData.getSpecialAvatarExcelMap().add(special);
    AvatarExcel actor;
    if (raw.get("actorTable").getAsString().equals("AvatarConfigTrial")) {
      var plot = ResourceLoader.getGson().fromJson(raw.get("actor"), TrialActorExcel.class);
      GameData.getTrialActorExcelMap().add(plot);
      actor = plot;
    } else {
      actor = ResourceLoader.getGson().fromJson(raw.get("actor"), AvatarExcel.class);
      GameData.getAvatarExcelMap().add(actor);
    }
    // Skills are an explicit fixture: this batch checks the provided actor and party identity.
    var trial =
        new TrialAvatar(player, special, actor, new TrialSkillPresets.Plan(true, false, Map.of()));
    if (publish) {
      player.getAvatars().addTempAvatar(trial);
      player.party.getAvatars().add(special.getId());
    }
    return sub;
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        100010110, 102040213, 102040214, 102140142, 102140143, 103600414, 104021212, 104020708,
        200070318
      })
  void anActualPublishedCurrentTrialSatisfiesItsOriginalBareTask(int id) {
    accepted(id, true);
    player.getMissionManager().refresh();
    assertEquals(1, player.getMissionManager().sub(id).getProgress());
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(id).getPhase());
    assertTrue(player.getAvatars().getAvatars().isEmpty());
    var before = gson.toJson(player.getGameplay());
    int saves = player.saves;
    player.getMissionManager().refresh();
    assertEquals(before, gson.toJson(player.getGameplay()));
    assertEquals(saves, player.saves);
  }

  @Test
  void anAdditionalClientCompletionInstructionCannotBeSilentlySkipped() {
    accepted(103420827, true);
    player.getMissionManager().refresh();
    assertEquals(0, player.getMissionManager().sub(103420827).getProgress());
  }

  void unchanged(MissionInfo.SubMissionInfo sub) {
    var state = gson.toJson(player.getGameplay());
    var party = new ArrayList<>(player.party.getAvatars());
    int saves = player.saves;
    assertFalse(assertDoesNotThrow(() -> TrialAvatarMissionConditions.satisfied(player, sub)));
    assertEquals(state, gson.toJson(player.getGameplay()));
    assertEquals(party, player.party.getAvatars());
    assertEquals(saves, player.saves);
  }

  @Test
  void anAcceptedTaskCannotCreateItsOwnEvidence() {
    var sub = accepted(100010110, false);
    unchanged(sub);
    player.getMissionManager().refresh();
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
    assertTrue(player.party.getAvatars().isEmpty());
    assertEquals(0, player.getMissionManager().sub(sub.getId()).getProgress());
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "empty", "duplicate", "oversized", "inactive", "formal"})
  void theActorMustActuallyBelongToTheCurrentTemporaryParty(String corruption) {
    var sub = accepted(100010110, true);
    int id = sub.getParamInt1();
    switch (corruption) {
      case "missing" -> player.getAvatars().getTempAvatars().remove(id);
      case "empty" -> player.party.getAvatars().clear();
      case "duplicate" -> player.party.getAvatars().add(id);
      case "oversized" -> player.party.getAvatars().addAll(List.of(10, 11, 12, 13));
      case "inactive" -> {
        player.party.getAvatars().clear();
        player.party.getAvatars().add(0);
      }
      case "formal" -> player.getAvatars().getAvatars().put(id, player.getAvatarById(id));
      default -> fail(corruption);
    }
    unchanged(sub);
  }

  @Test
  void anotherPlayersPublishedTrialIsNotAnOwnedRole() {
    var sub = accepted(100010110, true);
    player.getAvatarById(sub.getParamInt1()).setOwner(new Actor());
    unchanged(sub);
  }

  @ParameterizedTest
  @ValueSource(strings = {"special", "actor", "identity"})
  void missingOrMismatchedResourceIdentitiesRemainPending(String corruption) {
    var sub = accepted(100010110, true);
    if (corruption.equals("special")) GameData.getSpecialAvatarExcelMap().values().clear();
    else if (corruption.equals("actor")) {
      GameData.getAvatarExcelMap().values().clear();
      GameData.getTrialActorExcelMap().values().clear();
    } else {
      var changed = cases.get(sub.getId()).getAsJsonObject("special").deepCopy();
      changed.addProperty("AvatarID", 1006);
      GameData.getSpecialAvatarExcelMap().values().clear();
      GameData.getSpecialAvatarExcelMap().add(gson.fromJson(changed, SpecialAvatarExcel.class));
    }
    unchanged(sub);
  }

  @ParameterizedTest
  @ValueSource(strings = {"plane", "floor", "cancelledMain", "cancelledSub"})
  void theRoleCannotFinishAnObjectiveInAnUnrelatedOrCancelledContext(String corruption) {
    var sub = accepted(100010110, true);
    switch (corruption) {
      case "plane" -> player.plane++;
      case "floor" -> player.floor++;
      case "cancelledMain" ->
          player.getGameplay().getCancelledMissions().add(sub.getMainMissionID());
      case "cancelledSub" -> player.getGameplay().getCancelledSubs().add(sub.getId());
      default -> fail(corruption);
    }
    unchanged(sub);
  }

  @ParameterizedTest
  @CsvSource({
    "true, identity",
    "false, identity",
    "true, progress",
    "false, progress",
    "true, completed",
    "false, completed",
    "true, timestamp",
    "false, timestamp",
    "true, missing",
    "false, missing",
    "true, negative",
    "false, negative"
  })
  void malformedSavedMissionEvidenceDoesNotAdvance(boolean main, String corruption) {
    var sub = accepted(100010110, true);
    int id = main ? sub.getMainMissionID() : sub.getId();
    var row = gson.toJsonTree(new QuestState(id)).getAsJsonObject();
    switch (corruption) {
      case "identity" -> row.addProperty("questId", id + 1);
      case "progress" -> row.addProperty("progress", 1);
      case "completed" -> row.addProperty("phase", "FINISHED");
      case "timestamp" -> row.addProperty("finishTime", 1234);
      case "negative" -> row.addProperty("progress", -1);
      case "missing" -> {}
      default -> fail(corruption);
    }
    var states =
        main ? player.getGameplay().getMainMissions() : player.getGameplay().getSubMissions();
    if (corruption.equals("missing")) states.remove(id);
    else states.put(id, gson.fromJson(row, QuestState.class));
    unchanged(sub);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "extraInstruction",
        "extraInit",
        "unknownInstruction",
        "extraField",
        "objectInit",
        "objectStart",
        "objectTaskList",
        "nullType"
      })
  void theWholeOriginalScriptShapeMustBeRecognized(String corruption) {
    var sub = accepted(100010110, true);
    var script = cases.get(sub.getId()).getAsJsonObject("script").deepCopy();
    var tasks =
        script.getAsJsonArray("OnStartSequece").get(0).getAsJsonObject().getAsJsonArray("TaskList");
    switch (corruption) {
      case "extraInstruction" ->
          tasks.add(
              JsonParser.parseString(
                  "{\"$type\":\"RPG.GameCore.ClientFinishMission\",\"SubmissionID\":100010111}"));
      case "extraInit" -> script.getAsJsonArray("OnInitSequece").add(new JsonObject());
      case "unknownInstruction" ->
          tasks.get(0).getAsJsonObject().addProperty("$type", "RPG.GameCore.UnknownTrialPlayer");
      case "extraField" -> tasks.get(0).getAsJsonObject().addProperty("AvatarID", 1001006);
      case "objectInit" -> script.add("OnInitSequece", new JsonObject());
      case "objectStart" -> script.add("OnStartSequece", new JsonObject());
      case "objectTaskList" ->
          script
              .getAsJsonArray("OnStartSequece")
              .get(0)
              .getAsJsonObject()
              .add("TaskList", new JsonObject());
      case "nullType" -> script.add("Type", JsonNull.INSTANCE);
      default -> fail(corruption);
    }
    sub.getTask().retainSourceDefinition(script);
    unchanged(sub);
  }

  @Test
  void serializingProgressDoesNotManufactureMissingTemporaryActors() {
    var sub = accepted(100010110, false);
    var restored = gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class);
    player.getGameplay().getMainMissions().clear();
    player.getGameplay().getMainMissions().putAll(restored.getMainMissions());
    player.getGameplay().getSubMissions().clear();
    player.getGameplay().getSubMissions().putAll(restored.getSubMissions());
    unchanged(sub);
  }

  @Test
  void concurrentRefreshesAdvanceTheActualProvidedRoleOnce() throws Exception {
    var sub = accepted(100010110, true);
    try (var threads = Executors.newFixedThreadPool(8)) {
      var results = new ArrayList<Future<?>>();
      for (int i = 0; i < 32; i++)
        results.add(threads.submit(() -> player.getMissionManager().refresh()));
      for (var result : results) result.get(10, TimeUnit.SECONDS);
    }
    assertEquals(1, player.getMissionManager().sub(sub.getId()).getProgress());
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(sub.getId()).getPhase());
    assertEquals(1, player.saves);
    assertEquals(List.of(sub.getParamInt1()), player.party.getAvatars());
  }

  @Test
  void replacingAVirtualTeamRequiresAnActualVirtualParty() {
    var sub = accepted(104021212, true);
    player.virtual = false;
    unchanged(sub);
  }

  @ParameterizedTest
  @ValueSource(ints = {102140142, 104021212, 104020708})
  void replacingThePartyCannotBeProvenByLeavingAnotherRoleInIt(int id) {
    var sub = accepted(id, true);
    player.party.getAvatars().add(123456);
    unchanged(sub);
  }

  @ParameterizedTest
  @ValueSource(strings = {"leader", "missingOtherRole", "nonpositiveOtherRole"})
  void anInvalidCurrentPartyCannotProveAnActualProvidedTrial(String corruption) {
    var sub = accepted(100010110, true);
    switch (corruption) {
      case "leader" -> player.party.setLeader(2);
      case "missingOtherRole" -> player.party.getAvatars().add(123456);
      case "nonpositiveOtherRole" -> player.party.getAvatars().add(-1);
      default -> fail(corruption);
    }
    unchanged(sub);
  }
}
