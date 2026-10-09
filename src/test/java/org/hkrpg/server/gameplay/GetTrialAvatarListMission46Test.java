package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.PlayerGender;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.trial.TrialAvatar;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Whole original list tasks and actor rows; scene, acceptance and publication are fixtures. */
class GetTrialAvatarListMission46Test {
  final Gson gson = new Gson();
  final Map<Integer, JsonObject> cases = new HashMap<>();
  final Map<Integer, MissionInfo> oldMissions = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  final List<SpecialAvatarExcel> oldSpecials = new ArrayList<>();
  final List<AvatarExcel> oldActors = new ArrayList<>();
  Actor player;

  static class Actor extends Player {
    int plane, floor, saves;
    boolean virtual;
    PlayerGender gender = PlayerGender.GENDER_MAN;
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
    public PlayerGender getGender() {
      return gender;
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
    GameData.getMissionInfos().clear();
    GameplayDefinitions.subs.clear();
    GameData.getSpecialAvatarExcelMap().values().clear();
    GameData.getAvatarExcelMap().values().clear();
    try (var reader =
        new InputStreamReader(
            getClass().getResourceAsStream("/fixtures/get-trial-avatar-list-missions-4.6.json"),
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
  }

  MissionInfo.SubMissionInfo accept(int id, int selected, boolean publish) {
    var raw = cases.get(id);
    var sub = gson.fromJson(raw.get("sub"), MissionInfo.SubMissionInfo.class);
    var task = gson.fromJson(raw.get("script"), MissionTaskInfo.class);
    task.retainSourceDefinition(raw.get("script"));
    sub.setTask(task);
    GameplayDefinitions.subs.put(id, sub);
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
    player.virtual = task.getSourceDefinition().contains("RPG.GameCore.ReplaceVirtualTeam");
    player.gender = selected == 1108002 ? PlayerGender.GENDER_WOMAN : PlayerGender.GENDER_MAN;
    for (var role : raw.getAsJsonArray("roles")) {
      var r = role.getAsJsonObject();
      var special = gson.fromJson(r.get("special"), SpecialAvatarExcel.class);
      var actor = ResourceLoader.getGson().fromJson(r.get("actor"), AvatarExcel.class);
      GameData.getSpecialAvatarExcelMap().add(special);
      GameData.getAvatarExcelMap().add(actor);
      if (publish && special.getId() == selected) {
        var trial =
            new TrialAvatar(
                player, special, actor, new TrialSkillPresets.Plan(true, false, Map.of()));
        player.getAvatars().addTempAvatar(trial);
        player.party.getAvatars().add(selected);
      }
    }
    return sub;
  }

  @ParameterizedTest
  @CsvSource({
    "103190112,1021304",
    "202050410,1101208",
    "202050412,1101001",
    "201141225,1108001",
    "201141225,1108002"
  })
  void anActualProvidedSourceTrialSatisfiesItsOriginalListTarget(int id, int selected) {
    accept(id, selected, true);
    player.getMissionManager().refresh();
    assertEquals(1, player.getMissionManager().sub(id).getProgress());
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(id).getPhase());
    var before = gson.toJson(player.getGameplay());
    int saves = player.saves;
    player.getMissionManager().refresh();
    assertEquals(before, gson.toJson(player.getGameplay()));
    assertEquals(saves, player.saves);
    assertTrue(player.getAvatars().getAvatars().isEmpty());
  }

  void unchanged(MissionInfo.SubMissionInfo sub) {
    var before = gson.toJson(player.getGameplay());
    var party = List.copyOf(player.party.getAvatars());
    int saves = player.saves;
    assertFalse(assertDoesNotThrow(() -> TrialAvatarMissionConditions.satisfied(player, sub)));
    player.getMissionManager().refresh();
    assertEquals(before, gson.toJson(player.getGameplay()));
    assertEquals(party, player.party.getAvatars());
    assertEquals(saves, player.saves);
    assertEquals(0, player.getMissionManager().sub(sub.getId()).getProgress());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "empty",
        "unrelated",
        "duplicate",
        "nullMember",
        "reversed",
        "scalar",
        "progress",
        "collapsed"
      })
  void unknownListSemanticsCannotUseTheOriginalGenderAlternative(String corruption) {
    var sub = accept(201141225, 1108001, true);
    switch (corruption) {
      case "empty" -> sub.setParamIntList(new Integer[0]);
      case "collapsed" -> sub.setParamIntList(new Integer[] {1108001});
      case "unrelated" -> sub.setParamIntList(new Integer[] {1108001, 1021304});
      case "duplicate" -> sub.setParamIntList(new Integer[] {1108001, 1108001});
      case "nullMember" -> sub.setParamIntList(new Integer[] {null});
      case "reversed" -> sub.setParamIntList(new Integer[] {1108002, 1108001});
      case "scalar" -> sub.setParamInt1(1108001);
      case "progress" -> sub.setProgress(2);
      default -> fail(corruption);
    }
    unchanged(sub);
  }

  @ParameterizedTest
  @ValueSource(ints = {1108001, 1108002})
  void aGenderMismatchedProvidedActorCannotSatisfyTheAlternative(int selected) {
    var sub = accept(201141225, selected, true);
    player.gender = selected == 1108001 ? PlayerGender.GENDER_WOMAN : PlayerGender.GENDER_MAN;
    unchanged(sub);
  }

  void publish(JsonObject role) {
    var special = gson.fromJson(role.get("special"), SpecialAvatarExcel.class);
    var actor = ResourceLoader.getGson().fromJson(role.get("actor"), AvatarExcel.class);
    GameData.getSpecialAvatarExcelMap().add(special);
    GameData.getAvatarExcelMap().add(actor);
    var trial =
        new TrialAvatar(player, special, actor, new TrialSkillPresets.Plan(true, false, Map.of()));
    player.getAvatars().addTempAvatar(trial);
    player.party.getAvatars().add(special.getId());
  }

  @Test
  void aListTaskCannotManufactureItsOwnTemporaryActor() {
    unchanged(accept(202050410, 1101208, false));
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
  }

  @Test
  void bothProtagonistGendersInOnePartyAreNotAValidCreationResult() {
    var sub = accept(201141225, 1108001, true);
    publish(cases.get(201141225).getAsJsonArray("roles").get(1).getAsJsonObject());
    unchanged(sub);
  }

  @Test
  void aMissingOriginalAlternativeResourceKeepsTheTaskPending() {
    var sub = accept(201141225, 1108001, true);
    GameData.getSpecialAvatarExcelMap().values().removeIf(r -> r.getId() == 1108002);
    unchanged(sub);
  }

  @Test
  void replacementMustActuallyRemoveOtherOwnedTrialMembers() {
    var sub = accept(202050410, 1101208, true);
    publish(cases.get(202050412).getAsJsonArray("roles").get(0).getAsJsonObject());
    unchanged(sub);
  }

  @Test
  void virtualReplacementNeedsAnActualTemporaryParty() {
    var sub = accept(103190112, 1021304, true);
    player.virtual = false;
    unchanged(sub);
  }

  @Test
  void aFollowingClientInstructionCannotBeSilentlySkipped() {
    var sub = accept(202050410, 1101208, true);
    var script = cases.get(sub.getId()).getAsJsonObject("script").deepCopy();
    script
        .getAsJsonArray("OnStartSequece")
        .get(0)
        .getAsJsonObject()
        .getAsJsonArray("TaskList")
        .add(
            JsonParser.parseString(
                "{\"$type\":\"RPG.GameCore.ClientFinishMission\",\"SubmissionID\":202050411}"));
    sub.getTask().retainSourceDefinition(script);
    unchanged(sub);
  }

  @Test
  void concurrentRefreshesAdvanceTheActualListTargetOnce() throws Exception {
    var sub = accept(201141225, 1108002, true);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var jobs = new ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 32; i++)
        jobs.add(executor.submit(() -> player.getMissionManager().refresh()));
      for (var job : jobs) job.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertEquals(1, player.getMissionManager().sub(sub.getId()).getProgress());
    assertEquals(1, player.saves);
    assertEquals(List.of(1108002), player.party.getAvatars());
  }

  @Test
  void restoredAcceptedProgressAndRosterCannotInventMissingActualActors() throws Exception {
    var sub = accept(202050410, 1101208, true);
    var saved = gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class);
    player = new Actor();
    player.plane = sub.getLevelPlaneID();
    player.floor = sub.getLevelFloorID();
    player.party.getAvatars().add(1101208);
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(player, saved);
    unchanged(sub);
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
  }
}
