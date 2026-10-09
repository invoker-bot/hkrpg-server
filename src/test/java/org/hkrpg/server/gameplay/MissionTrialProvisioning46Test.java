package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.PlayerGender;
import emu.lunarcore.game.player.lineup.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.trial.TrialAvatar;
import emu.lunarcore.util.Position;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Original complete tasks and real actor/skill/equipment rows; acceptance and scene are fixtures.
 */
class MissionTrialProvisioning46Test {
  final Gson gson = new Gson();
  final Map<Integer, JsonObject> cases = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  final Map<Integer, MissionInfo> oldMissions = new HashMap<>();
  final Map<Long, AvatarSkillTreeExcel> oldSkillCache = new HashMap<>();
  final Map<Object, List<?>> oldResources = new LinkedHashMap<>();
  @TempDir Path root;
  java.lang.reflect.Field configField;
  Object oldConfig;
  Actor player;

  static class Actor extends Player {
    int saves;
    final List<byte[]> packets = new ArrayList<>();

    Actor() {
      set(this, "uid", 199985);
      set(this, "pos", new Position());
      set(this, "rot", new Position());
      set(this, "lineupManager", new LineupManager(this));
      getLineupManager().getLineups()[0] = new NormalTeam(this);
    }

    @Override
    public int getWorldLevel() {
      return 0;
    }

    @Override
    public void save() {
      saves++;
    }

    @Override
    public void sendPacket(emu.lunarcore.server.packet.BasePacket packet) {
      packets.add(packet.build());
    }
  }

  static class NormalTeam extends PlayerLineup {
    NormalTeam(Player player) {
      super(player, 0);
    }

    @Override
    public void save() {
      fail("Trial execution must not write a formal team document");
    }
  }

  static class PartySession extends emu.lunarcore.server.game.GameSession {
    final Player actor;

    PartySession(Player player) {
      super(null);
      actor = player;
    }

    @Override
    public Player getPlayer() {
      return actor;
    }
  }

  @Test
  void allPartyQueriesPublishTheSameCurrentMissionParty() {
    accept(104021212, 1021404);
    player.getMissionManager().refresh();
    var packet =
        new emu.lunarcore.server.packet.send.PacketGetAllLineupDataScRsp(new PartySession(player));
    var all =
        (emu.lunarcore.proto.GetAllLineupDataScRspOuterClass.GetAllLineupDataScRsp)
            packet.getData();
    assertEquals(player.getCurrentLineup().toProto(), all.getLineupList().get(0));
    assertTrue(all.getLineupList().get(0).getIsVirtual());
    assertEquals(1021404, all.getLineupList().get(0).getAvatarList().get(0).getId());
    assertEquals(List.of(199986), player.getLineupManager().getLineups()[0].getAvatars());
  }

  static void set(Player player, String name, Object value) {
    try {
      var field = Player.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(player, value);
    } catch (ReflectiveOperationException error) {
      throw new IllegalStateException(error);
    }
  }

  JsonObject fixture(String name) throws Exception {
    try (var reader =
        new InputStreamReader(
            Objects.requireNonNull(getClass().getResourceAsStream("/fixtures/" + name)),
            StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    }
  }

  @BeforeEach
  void prepare() throws Exception {
    oldSubs.putAll(GameplayDefinitions.subs);
    GameplayDefinitions.subs.clear();
    oldMissions.putAll(GameData.getMissionInfos());
    GameData.getMissionInfos().clear();
    oldSkillCache.putAll(GameDepot.getAvatarSkillTreeExcels());
    GameDepot.getAvatarSkillTreeExcels().clear();
    for (var map :
        List.of(
            GameData.getAvatarExcelMap(),
            GameData.getTrialActorExcelMap(),
            GameData.getSpecialAvatarExcelMap(),
            GameData.getAvatarSkillTreeExcelMap(),
            GameData.getTrialActorSkillTreeExcelMap(),
            GameData.getTrialActorPromotionExcelMap(),
            GameData.getItemExcelMap(),
            GameData.getEquipExcelMap())) {
      Collection<?> values =
          map instanceof emu.lunarcore.data.resource.ExcelMap<?> excel
              ? excel.values()
              : ((emu.lunarcore.data.resource.MultiKeyExcelMap<?>) map).values();
      oldResources.put(map, new ArrayList<>(values));
      values.clear();
    }
    configField = LunarCore.class.getDeclaredField("config");
    configField.setAccessible(true);
    oldConfig = configField.get(null);
    var config = new Config();
    config.resourceDir = root.toString();
    configField.set(null, config);
    for (var name :
        List.of("get-trial-avatar-missions-4.6.json", "get-trial-avatar-list-missions-4.6.json")) {
      for (var value : fixture(name).getAsJsonArray("cases")) {
        var c = value.getAsJsonObject();
        cases.put(c.getAsJsonObject("sub").get("ID").getAsInt(), c);
        var roles = c.has("roles") ? c.getAsJsonArray("roles") : new JsonArray();
        if (!c.has("roles")) roles.add(c);
        for (var role : roles) {
          var raw = role.getAsJsonObject();
          var special = gson.fromJson(raw.get("special"), SpecialAvatarExcel.class);
          GameData.getSpecialAvatarExcelMap().add(special);
          if (special.getAvatarID() >= 7000 && special.getAvatarID() < 8000) {
            GameData.getTrialActorExcelMap()
                .add(ResourceLoader.getGson().fromJson(raw.get("actor"), TrialActorExcel.class));
          } else
            GameData.getAvatarExcelMap()
                .add(ResourceLoader.getGson().fromJson(raw.get("actor"), AvatarExcel.class));
        }
      }
    }
    var records = fixture("mission-trial-skills-4.6.json").getAsJsonObject("records");
    for (var entry : records.entrySet()) {
      for (var raw : entry.getValue().getAsJsonArray()) {
        switch (entry.getKey()) {
          case "AvatarSkillTreeConfig.json" -> {
            var row = gson.fromJson(raw, AvatarSkillTreeExcel.class);
            GameData.getAvatarSkillTreeExcelMap().add(row);
            row.onLoad();
          }
          case "AvatarSkillTreeConfigTrial.json" -> {
            var row = gson.fromJson(raw, TrialActorSkillTreeExcel.class);
            GameData.getTrialActorSkillTreeExcelMap().add(row);
            row.onLoad();
          }
          case "ItemConfigEquipment.json" ->
              GameData.getItemExcelMap()
                  .add(ResourceLoader.getGson().fromJson(raw, ItemExcel.class));
          case "EquipmentConfig.json" -> {
            var row = gson.fromJson(raw, EquipmentExcel.class);
            GameData.getEquipExcelMap().add(row);
            row.onLoad();
          }
          case "AvatarPromotionConfigTrial.json" ->
              GameData.getTrialActorPromotionExcelMap()
                  .add(gson.fromJson(raw, TrialActorPromotionExcel.class));
          default -> {
            var file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, raw.toString());
          }
        }
      }
    }
    // Formal party is a deliberately separate fixture, never a copied trial.
    var actor = new AvatarExcel();
    setField(actor, AvatarExcel.class, "AvatarID", 199986);
    GameData.getAvatarExcelMap().add(actor);
    player = new Actor();
    var formal = new GameAvatar(actor);
    formal.setOwner(player);
    player.getAvatars().getAvatars().put(199986, formal);
    player.getLineupManager().getLineups()[0].getAvatars().add(199986);
  }

  static void setField(Object target, Class<?> type, String name, Object value) throws Exception {
    var field = type.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @AfterEach
  void restore() throws Exception {
    configField.set(null, oldConfig);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
    GameData.getMissionInfos().clear();
    GameData.getMissionInfos().putAll(oldMissions);
    for (var entry : oldResources.entrySet()) {
      if (entry.getKey() instanceof emu.lunarcore.data.resource.MultiKeyExcelMap map) {
        map.values().clear();
        entry
            .getValue()
            .forEach(value -> map.add((emu.lunarcore.data.resource.MultiKeyGameResource) value));
      } else if (entry.getKey() instanceof emu.lunarcore.data.resource.ExcelMap map) {
        map.values().clear();
        entry
            .getValue()
            .forEach(value -> map.add((emu.lunarcore.data.resource.GameResource) value));
      }
    }
    GameDepot.getAvatarSkillTreeExcels().clear();
    GameDepot.getAvatarSkillTreeExcels().putAll(oldSkillCache);
  }

  MissionInfo.SubMissionInfo accept(int id, int trial) {
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
    set(player, "gender", trial == 1108002 ? PlayerGender.GENDER_WOMAN : PlayerGender.GENDER_MAN);
    set(player, "planeId", sub.getLevelPlaneID());
    set(player, "floorId", sub.getLevelFloorID());
    var plane =
        gson.fromJson(
            "{\"PlaneID\":" + sub.getLevelPlaneID() + ",\"PlaneType\":\"Town\",\"WorldID\":100}",
            MazePlaneExcel.class);
    set(player, "scene", new Scene(player, plane, sub.getLevelFloorID()));
    return sub;
  }

  @ParameterizedTest
  @CsvSource({
    "100010110,1001006",
    "102040213,1021203",
    "102040214,1021206",
    "102140142,1021213",
    "102140143,1021204",
    "103600414,1021225",
    "104021212,1021404",
    "104020708,1021403",
    "200070318,1101006",
    "103190112,1021304",
    "202050410,1101208",
    "202050412,1101001",
    "201141225,1108001",
    "201141225,1108002"
  })
  void acceptedOriginalTaskCreatesItsActualTrialAndCompletesOnlyAfterPublication(
      int id, int trial) {
    accept(id, trial);
    assertNull(player.getAvatarById(trial));
    assertNotNull(
        player.getTrialManager().buildTrialAvatar(trial),
        "Original actor resources must be complete before execution");
    player.getMissionManager().refresh();
    assertInstanceOf(TrialAvatar.class, player.getAvatarById(trial));
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(id).getPhase());
    assertTrue(player.getCurrentLineup().getAvatars().contains(trial));
    assertFalse(player.getAvatars().getAvatars().containsKey(trial));
    assertEquals(List.of(199986), player.getLineupManager().getLineups()[0].getAvatars());
    assertFalse(player.getAvatarById(trial).getSkillTree().isEmpty());
    assertEquals(1, player.getAvatarById(trial).getEquips().size());
    var before = gson.toJson(player.getGameplay());
    int saves = player.saves;
    player.getMissionManager().refresh();
    assertEquals(before, gson.toJson(player.getGameplay()));
    assertEquals(saves, player.saves);
  }

  Actor reload(GameplayState saved) {
    var restored = new Actor();
    set(restored, "gameplay", gson.fromJson(gson.toJson(saved), GameplayState.class));
    set(restored, "gender", player.getGender());
    set(restored, "planeId", player.getPlaneId());
    set(restored, "floorId", player.getFloorId());
    var formal = new GameAvatar(GameData.getAvatarExcelMap().get(199986));
    formal.setOwner(restored);
    restored.getAvatars().getAvatars().put(199986, formal);
    restored.getLineupManager().getLineups()[0].getAvatars().add(199986);
    return restored;
  }

  @ParameterizedTest
  @CsvSource({
    "100010110,1001006",
    "102140142,1021213",
    "104021212,1021404",
    "201141225,1108001",
    "201141225,1108002"
  })
  void frozenActualActorsSkillsEquipmentAndVitalsRestoreWithoutReexecuting(int sub, int id)
      throws Exception {
    accept(sub, id);
    player.getMissionManager().refresh();
    var party = player.getCurrentLineup();
    var actor = player.getAvatarById(id);
    actor.setCurrentHp(party, 4567);
    actor.setCurrentSp(party, 1234);
    party.setMp(2);
    var stored = gson.toJson(player.getGameplay());
    // The frozen skill policy must survive removal of the current preset files.
    try (var paths = Files.walk(root)) {
      for (var path : paths.filter(Files::isRegularFile).toList()) Files.delete(path);
    }
    for (int iteration = 0; iteration < 2; iteration++) {
      var restored = reload(player.getGameplay());
      assertDoesNotThrow(() -> restored.getMissionTrialManager().restoreOnLogin());
      var actual = restored.getAvatarById(id);
      var restoredParty = restored.getCurrentLineup();
      assertInstanceOf(TrialAvatar.class, actual);
      assertEquals(actor.getSkillTree(), actual.getSkillTree());
      assertEquals(1, actual.getEquips().size());
      assertEquals(4567, actual.getCurrentHp(restoredParty));
      assertEquals(1234, actual.getCurrentSp(restoredParty));
      assertEquals(2, restoredParty.getMp());
      assertEquals(stored, gson.toJson(restored.getGameplay()));
      assertEquals(0, restored.saves);
      assertFalse(restored.getAvatars().getAvatars().containsKey(id));
      assertEquals(List.of(199986), restored.getLineupManager().getLineups()[0].getAvatars());
    }
  }

  @Test
  void consecutiveOriginalCreatesAppendAndOriginalReplaceRetainsOnlyTheNewTrial() {
    accept(102040213, 1021203);
    player.getMissionManager().refresh();
    player.getAvatarById(1021203).setCurrentHp(player.getCurrentLineup(), 6789);
    accept(102040214, 1021206);
    player.getMissionManager().refresh();
    assertEquals(List.of(199986, 1021203, 1021206), player.getCurrentLineup().getAvatars());
    assertEquals(6789, player.getAvatarById(1021203).getCurrentHp(player.getCurrentLineup()));
    var restored = reload(player.getGameplay());
    restored.getMissionTrialManager().restoreOnLogin();
    assertEquals(player.getCurrentLineup().getAvatars(), restored.getCurrentLineup().getAvatars());
    assertEquals(2, restored.getAvatars().getTempAvatars().size());
  }

  @Test
  void aSecondOriginalReplaceDoesNotWriteOrEraseTheFormalParty() {
    accept(202050410, 1101208);
    player.getMissionManager().refresh();
    assertEquals(List.of(1101208), player.getCurrentLineup().getAvatars());
    accept(202050412, 1101001);
    player.getMissionManager().refresh();
    assertEquals(List.of(1101001), player.getCurrentLineup().getAvatars());
    assertEquals(List.of(199986), player.getLineupManager().getLineups()[0].getAvatars());
    var restored = reload(player.getGameplay());
    restored.getMissionTrialManager().restoreOnLogin();
    assertEquals(List.of(1101001), restored.getCurrentLineup().getAvatars());
    assertEquals(Set.of(1101208, 1101001), restored.getAvatars().getTempAvatars().keySet());
  }

  @Test
  void missionVitalsAreIndependentFromTheRetainedFormalParty() {
    var normal = player.getLineupManager().getLineups()[0];
    var formal = player.getAvatarById(199986);
    formal.setCurrentHp(normal, 8765);
    formal.setCurrentSp(normal, 4321);
    accept(103600414, 1021225);
    player.getMissionManager().refresh();
    assertEquals(List.of(199986, 1021225), player.getCurrentLineup().getAvatars());
    formal.setCurrentHp(player.getCurrentLineup(), 100);
    formal.setCurrentSp(player.getCurrentLineup(), 200);
    assertEquals(8765, formal.getCurrentHp(normal));
    assertEquals(4321, formal.getCurrentSp(normal));
    assertEquals(100, formal.getCurrentHp(player.getCurrentLineup()));
    assertEquals(200, formal.getCurrentSp(player.getCurrentLineup()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "mixed",
        "noScene",
        "foreignScene",
        "wrongFloor",
        "unaccepted",
        "cancelSub",
        "cancelMain",
        "noSkills",
        "noEquipment",
        "tempCollision",
        "formalCollision",
        "fullParty",
        "extraParty",
        "battle",
        "unknownTask",
        "unknownSequence"
      })
  void invalidOrIncompleteTaskContextsCannotPublishATrialOrClaimCompletion(String damage)
      throws Exception {
    var sub =
        accept(
            damage.equals("mixed") ? 103420827 : 100010110,
            damage.equals("mixed") ? 1021317 : 1001006);
    switch (damage) {
      case "mixed" -> {}
      case "noScene" -> set(player, "scene", null);
      case "foreignScene" -> {
        var foreign = new Actor();
        set(
            player,
            "scene",
            new Scene(foreign, player.getScene().getExcel(), sub.getLevelFloorID()));
      }
      case "wrongFloor" -> set(player, "floorId", 1);
      case "unaccepted" -> player.getGameplay().getSubMissions().remove(sub.getId());
      case "cancelSub" -> player.getGameplay().getCancelledSubs().add(sub.getId());
      case "cancelMain" -> player.getGameplay().getCancelledMissions().add(sub.getMainMissionID());
      case "noSkills" -> {
        GameData.getTrialActorSkillTreeExcelMap().values().clear();
        GameDepot.getAvatarSkillTreeExcels().clear();
      }
      case "noEquipment" -> GameData.getEquipExcelMap().values().clear();
      case "tempCollision" ->
          player.getAvatars().addTempAvatar(player.getTrialManager().buildTrialAvatar(1001006));
      case "formalCollision" -> {
        var formal = new GameAvatar(GameData.getActorForTrial(7006));
        formal.setOwner(player);
        setField(formal, GameAvatar.class, "avatarId", 1001006);
        player.getAvatars().getAvatars().put(1001006, formal);
      }
      case "fullParty" -> {
        for (int id : List.of(199991, 199992, 199993)) {
          var excel = gson.fromJson("{\"AvatarID\":" + id + "}", AvatarExcel.class);
          var formal = new GameAvatar(excel);
          formal.setOwner(player);
          player.getAvatars().getAvatars().put(id, formal);
          player.getLineupManager().getLineups()[0].getAvatars().add(id);
        }
      }
      case "extraParty" ->
          setField(player.getLineupManager(), LineupManager.class, "currentExtraIndex", 8);
      case "battle" ->
          set(
              player,
              "battle",
              new emu.lunarcore.game.battle.Battle(
                  player,
                  player.getCurrentLineup(),
                  gson.fromJson("{\"StageID\":1}", StageExcel.class),
                  false));
      case "unknownTask", "unknownSequence" -> {
        var script = cases.get(sub.getId()).getAsJsonObject("script").deepCopy();
        if (damage.equals("unknownTask"))
          script
              .getAsJsonArray("OnStartSequece")
              .get(0)
              .getAsJsonObject()
              .getAsJsonArray("TaskList")
              .add(
                  gson.fromJson(
                      "{\"$type\":\"RPG.GameCore.ClientFinishMission\",\"SubmissionID\":100010110}",
                      JsonObject.class));
        else script.addProperty("UnrecognisedSequence", true);
        var task = gson.fromJson(script, MissionTaskInfo.class);
        task.retainSourceDefinition(script);
        sub.setTask(task);
      }
    }
    var before = new HashMap<>(player.getAvatars().getTempAvatars());
    var normal = List.copyOf(player.getLineupManager().getLineups()[0].getAvatars());
    assertDoesNotThrow(() -> player.getMissionManager().refresh());
    assertNull(player.getGameplay().getMissionTrials().getParty());
    assertTrue(player.getGameplay().getMissionTrials().getExecuted().isEmpty());
    assertEquals(before, player.getAvatars().getTempAvatars());
    assertEquals(normal, player.getLineupManager().getLineups()[0].getAvatars());
    if (player.getMissionManager().sub(sub.getId()) != null)
      assertEquals(0, player.getMissionManager().sub(sub.getId()).getProgress());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "roster",
        "duplicate",
        "leader",
        "mp",
        "hp",
        "skills",
        "skillResources",
        "preset",
        "source",
        "receipt",
        "virtual",
        "lastSub",
        "main",
        "index"
      })
  void damagedSavedActorsAndReceiptsCannotBePublishedOnRestore(String damage) {
    accept(100010110, 1001006);
    player.getMissionManager().refresh();
    var json = gson.toJsonTree(player.getGameplay()).getAsJsonObject();
    var state = json.getAsJsonObject("missionTrials");
    var party = state.getAsJsonObject("party");
    var actor = party.getAsJsonObject("actors").getAsJsonObject("1001006");
    switch (damage) {
      case "roster" -> party.add("roster", new JsonArray());
      case "duplicate" -> party.getAsJsonArray("roster").add(1001006);
      case "leader" -> party.addProperty("leader", 4);
      case "mp" -> party.addProperty("mp", 99);
      case "hp" ->
          party.getAsJsonObject("vitals").getAsJsonObject("1001006").addProperty("hp", 10001);
      case "skills" -> actor.add("skills", new JsonObject());
      case "skillResources" -> actor.add("skillResources", new JsonObject());
      case "preset" -> actor.addProperty("skillPlan", "null");
      case "source" -> party.add("sources", new JsonObject());
      case "receipt" -> state.add("executed", new JsonObject());
      case "virtual" -> party.addProperty("virtual", true);
      case "lastSub" -> party.addProperty("lastSub", 1);
      case "main" -> party.addProperty("main", 1);
      case "index" -> party.addProperty("index", 1);
    }
    var restored = reload(gson.fromJson(json, GameplayState.class));
    var before = gson.toJson(restored.getGameplay());
    assertThrows(
        IllegalStateException.class, () -> restored.getMissionTrialManager().restoreOnLogin());
    assertEquals(before, gson.toJson(restored.getGameplay()));
    assertTrue(restored.getAvatars().getTempAvatars().isEmpty());
    assertEquals(0, restored.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"task", "special", "actor", "skill", "item", "equipment", "gender"})
  void changedSourceResourcesCannotRecalculateOrRetargetTheSavedParty(String damage)
      throws Exception {
    int sub = damage.equals("gender") ? 201141225 : 100010110,
        id = damage.equals("gender") ? 1108001 : 1001006;
    accept(sub, id);
    player.getMissionManager().refresh();
    var restored = reload(player.getGameplay());
    switch (damage) {
      case "task" ->
          GameplayDefinitions.subs.get(sub).getTask().retainSourceDefinition(new JsonObject());
      case "special" -> GameData.getSpecialAvatarExcelMap().values().clear();
      case "actor" -> GameData.getTrialActorExcelMap().values().clear();
      case "skill" -> GameDepot.getAvatarSkillTreeExcels().clear();
      case "item" -> GameData.getItemExcelMap().values().clear();
      case "equipment" -> GameData.getEquipExcelMap().values().clear();
      case "gender" -> set(restored, "gender", PlayerGender.GENDER_WOMAN);
    }
    assertThrows(
        IllegalStateException.class, () -> restored.getMissionTrialManager().restoreOnLogin());
    assertTrue(restored.getAvatars().getTempAvatars().isEmpty());
    assertEquals(0, restored.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"mainFinished", "cancelMain", "cancelSub", "leaveFloor"})
  void legitimateExitCleansOnlyOwnedActorsAndDoesNotReexecuteOldReceipts(String cause) {
    var sub = accept(100010110, 1001006);
    player.getMissionManager().refresh();
    var external = player.getTrialManager().buildTrialAvatar(1101006);
    player.getAvatars().addTempAvatar(external);
    switch (cause) {
      case "mainFinished" ->
          player.getGameplay().getMainMissions().get(sub.getMainMissionID()).advance(1, 1, 1);
      case "cancelMain" -> player.getGameplay().getCancelledMissions().add(sub.getMainMissionID());
      case "cancelSub" -> player.getGameplay().getCancelledSubs().add(sub.getId());
      case "leaveFloor" -> set(player, "floorId", 1);
    }
    assertTrue(player.getMissionTrialManager().reconcile());
    assertNull(player.getGameplay().getMissionTrials().getParty());
    assertNull(player.getAvatarById(1001006));
    assertSame(external, player.getAvatarById(1101006));
    assertEquals(List.of(199986), player.getCurrentLineup().getAvatars());
    assertTrue(player.getGameplay().getMissionTrials().getExecuted().containsKey(sub.getId()));
    var before = gson.toJson(player.getGameplay());
    assertFalse(player.getMissionTrialManager().reconcile());
    assertEquals(before, gson.toJson(player.getGameplay()));
    if (cause.equals("leaveFloor")) {
      set(player, "floorId", sub.getLevelFloorID());
      player.getGameplay().getSubMissions().put(sub.getId(), new QuestState(sub.getId()));
      player.getAvatars().getTempAvatars().remove(external.getAvatarId());
      player.getMissionManager().refresh();
      assertNull(player.getAvatarById(1001006));
      assertEquals(0, player.getMissionManager().sub(sub.getId()).getProgress());
    }
  }

  @Test
  void clientPartyEditsCannotReportSuccessOrMutateTheScriptParty() {
    accept(104021212, 1021404);
    player.getMissionManager().refresh();
    var before = gson.toJson(player.getGameplay());
    var manager = player.getLineupManager();
    assertFalse(manager.joinLineup(0, 0, 199986));
    assertFalse(manager.quitLineup(0, 1021404));
    assertFalse(manager.replaceLineup(0, 0, List.of(199986)));
    assertFalse(manager.swapLineup(0, 0, 1));
    assertFalse(manager.switchLineup(1));
    assertThrows(
        UnsupportedOperationException.class, () -> player.getCurrentLineup().getAvatars().clear());
    assertEquals(before, gson.toJson(player.getGameplay()));
    assertTrue(player.getCurrentLineup().toProto().getIsVirtual());
    assertEquals(0, player.getCurrentLineup().getExtraLineupType());
  }

  @ParameterizedTest
  @ValueSource(strings = {"mainFinished", "cancelMain", "leaveFloor"})
  void persistedPartyIsNotPublishedDuringFormalLineupDatabaseValidation(String cause)
      throws Exception {
    var sub = accept(100010110, 1001006);
    player.getMissionManager().refresh();
    var restored = reload(player.getGameplay());
    switch (cause) {
      case "mainFinished" ->
          restored.getGameplay().getMainMissions().get(sub.getMainMissionID()).advance(1, 1, 1);
      case "cancelMain" ->
          restored.getGameplay().getCancelledMissions().add(sub.getMainMissionID());
      case "leaveFloor" -> set(restored, "floorId", 1);
    }
    var validate = LineupManager.class.getDeclaredMethod("validate");
    validate.setAccessible(true);
    assertDoesNotThrow(() -> validate.invoke(restored.getLineupManager()));
    assertTrue(restored.getAvatars().getTempAvatars().isEmpty());
    assertEquals(List.of(199986), restored.getLineupManager().getLineups()[0].getAvatars());
    assertTrue(restored.getMissionTrialManager().inside());
    restored.getMissionTrialManager().restoreOnLogin();
    assertFalse(restored.getMissionTrialManager().inside());
    assertEquals(List.of(199986), restored.getCurrentLineup().getAvatars());
    assertTrue(restored.getGameplay().getMissionTrials().getExecuted().containsKey(sub.getId()));
  }

  @Test
  void actualPlayerDocumentsRestoreTheFrozenPartyTwice() throws Exception {
    accept(100010110, 1001006);
    player.getMissionManager().refresh();
    player.getAvatarById(1001006).setCurrentHp(player.getCurrentLineup(), 2468);
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "mission-trial-document-fixture");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, Position.class);
      var document = new Player();
      set(document, "uid", player.getUid());
      set(document, "gameplay", player.getGameplay());
      store.save(document);
      var expected = gson.toJson(player.getGameplay());
      for (int i = 0; i < 2; i++) {
        var stored = Objects.requireNonNull(store.find(Player.class).first());
        assertEquals(expected, gson.toJson(stored.getGameplay()));
        var restored = reload(stored.getGameplay());
        restored.getMissionTrialManager().restoreOnLogin();
        assertEquals(
            2468, restored.getAvatarById(1001006).getCurrentHp(restored.getCurrentLineup()));
        assertEquals(expected, gson.toJson(restored.getGameplay()));
        assertEquals(0, restored.saves);
        store.save(stored);
      }
    } finally {
      server.shutdownNow();
    }
  }
}
