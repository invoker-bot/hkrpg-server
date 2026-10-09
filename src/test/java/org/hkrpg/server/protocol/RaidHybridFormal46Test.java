package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.config.MissionTaskInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RaidHybridFormal46Test {
  @TempDir Path root;
  final MissionRaid46Test fixture = new MissionRaid46Test();
  final Gson gson = new Gson();
  Object oldConfig;
  java.lang.reflect.Field configField;
  JsonObject source;

  static class Actor extends MissionRaid46Test.Actor {
    final GameAvatar required =
        new GameAvatar() {
          public int getAvatarId() {
            return 1209;
          }

          public int getActorId() {
            return 1209;
          }

          public int getMaxSp() {
            return 10000;
          }

          public void save() {}
        };

    Actor() {
      getAvatars().getAvatars().put(1209, required);
      teams.getCurrentLineup().getAvatars().clear();
      teams.getCurrentLineup().getAvatars().add(1209);
    }

    public GameAvatar getAvatarById(int id) {
      return id == 1209 ? required : super.getAvatarById(id);
    }

    public boolean isInBattle() {
      return getBattle() != null;
    }
  }

  static class Session extends MissionRaid46Test.Session {
    final Actor hybrid = new Actor();

    public Player getPlayer() {
      return hybrid;
    }
  }

  @BeforeEach
  void setup() throws Exception {
    fixture.setup();
    configField = LunarCore.class.getDeclaredField("config");
    configField.setAccessible(true);
    oldConfig = configField.get(null);
    var config = new Config();
    config.resourceDir = root.toString();
    configField.set(null, config);
    try (var input = getClass().getResourceAsStream("/gameplay/raid-trial-or-player-4.6.json")) {
      source =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
    Files.createDirectories(root.resolve("ExcelOutput"));
    Files.writeString(
        root.resolve("ExcelOutput/TeamLimitCondition.json"), "[" + source.get("teamLimit") + "]");
    var row = gson.toJsonTree(fixture.resource).getAsJsonObject();
    for (String field : List.of("TeamType", "TrialAvatarList", "TeamLimitIDList"))
      row.add(field, source.getAsJsonObject("raid").get(field).deepCopy());
    fixture.resource = gson.fromJson(row, GameplayDefinitions.Raid.class);
    GameplayDefinitions.raids.put(fixture.resource.key(), fixture.resource);
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.cleanup();
    if (configField != null) configField.set(null, oldConfig);
  }

  @Test
  void formalPathUsesTheSourceRequiredActorAndKeepsTheRaidGateAndReturnPoint() throws Exception {
    var session = new Session();
    fixture.session = session;
    session
        .hybrid
        .getGameplay()
        .getMainMissions()
        .put(
            MissionRaid46Test.BEFORE,
            new emu.lunarcore.game.quest.QuestState(MissionRaid46Test.BEFORE));
    assertEquals(
        Retcode.MISSION_NOT_DOING,
        session.hybrid.getMissionManager().accept(MissionRaid46Test.MAIN));
    assertEquals(0, fixture.start());
    assertEquals(MissionRaid46Test.ENTRY, session.hybrid.entry);
    assertEquals(List.of(1209), session.hybrid.getCurrentLineup().getAvatars());
    assertEquals(0, fixture.leave(MissionRaid46Test.RAID, false));
    assertEquals(MissionRaid46Test.HOME, session.hybrid.entry);
    assertEquals(12f, session.hybrid.pos.getX());
  }

  @Test
  void aFormalTeamMissingTheSourceIncludeAvatarGetsARealTeamErrorWithoutEntering()
      throws Exception {
    assertEquals(Retcode.RAID_AVATAR_NOT_MATCH.getVal(), fixture.start());
    assertEquals(MissionRaid46Test.HOME, fixture.session.actor.entry);
    assertFalse(
        fixture.session.actor.getGameplay().getMainMissions().containsKey(MissionRaid46Test.MAIN));
    assertEquals(0, fixture.session.actor.getGameplay().getCurrentMissionRaid());
  }

  Session owned() {
    var session = new Session();
    fixture.session = session;
    session
        .hybrid
        .getGameplay()
        .getMainMissions()
        .put(
            MissionRaid46Test.BEFORE,
            new emu.lunarcore.game.quest.QuestState(MissionRaid46Test.BEFORE));
    return session;
  }

  int selectedStart(Session session, String typeName) throws Exception {
    var req = Gameplay46.builder(session, "StartRaidCsReq");
    Gameplay46.set(req, "raid_id", MissionRaid46Test.RAID);
    var rows = req.getDescriptorForType().findFieldByName("NIMDEPFJJNI");
    var row = com.google.protobuf.DynamicMessage.newBuilder(rows.getMessageType());
    Gameplay46.set(row, "id", 1209);
    var type = row.getDescriptorForType().findFieldByName("avatar_type");
    row.setField(type, type.getEnumType().findValueByName(typeName));
    req.addRepeatedField(rows, row.build());
    assertTrue(VersionedPacketHandler.handle(session, 2234, req.build().toByteArray()));
    return Gameplay46.number(session.packets.get("StartRaidScRsp"), "retcode");
  }

  @Test
  void nativeFormalSelectorUsesThe46SemanticEnumRatherThanTheLegacyNumber() throws Exception {
    var session = owned();
    assertEquals(0, selectedStart(session, "AvatarType_AvatarFormalType"));
    assertEquals(MissionRaid46Test.ENTRY, session.hybrid.entry);
  }

  @Test
  void the46TrialSelectorCannotPretendToOwnAFormalRole() throws Exception {
    var session = owned();
    assertNotEquals(0, selectedStart(session, "AvatarType_AvatarTrialType"));
    assertEquals(MissionRaid46Test.HOME, session.hybrid.entry);
    assertEquals(0, session.hybrid.getGameplay().getCurrentMissionRaid());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void authoredEventAndCustomValueOnlyAdvanceInsideTheOriginalOwnedFormalRaid(int mutation)
      throws Exception {
    var session = owned();
    JsonObject battleSource;
    try (var input = getClass().getResourceAsStream("/gameplay/battle-custom-values-4.6.json")) {
      battleSource =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
    int eventId = 20241054, stageId = 202410540;
    var oldEvents =
        GameData.getPlaneEventExcelMap().values().stream()
            .filter(e -> e.getEventId() == eventId)
            .toList();
    var oldStage = GameData.getStageExcelMap().get(stageId);
    try {
      var subs = new JsonArray();
      for (int[] ids :
          List.of(
              new int[] {202200502, MissionRaid46Test.FIRST},
              new int[] {202200506, MissionRaid46Test.LAST})) {
        var row =
            battleSource.getAsJsonArray("subs").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(v -> v.get("ID").getAsInt() == ids[0])
                .findFirst()
                .orElseThrow()
                .deepCopy();
        row.addProperty("ID", ids[1]);
        row.addProperty("MainMissionID", MissionRaid46Test.MAIN);
        row.addProperty("LevelPlaneID", MissionRaid46Test.PLANE);
        row.addProperty("LevelFloorID", MissionRaid46Test.FLOOR);
        row.addProperty("TakeType", "Auto");
        row.add("TakeParamIntList", new JsonArray());
        row.add("GroupIDList", new JsonArray());
        subs.add(row);
      }
      var main = new JsonObject();
      main.addProperty("MainMissionID", MissionRaid46Test.MAIN);
      main.add(
          "StartSubMissionList",
          gson.toJsonTree(List.of(MissionRaid46Test.FIRST, MissionRaid46Test.LAST)));
      main.add("FinishSubMissionList", gson.toJsonTree(List.of(MissionRaid46Test.LAST)));
      main.add("SubMissionList", subs);
      var info = gson.fromJson(main, MissionInfo.class);
      GameData.getMissionInfos().put(MissionRaid46Test.MAIN, info);
      for (var sub : info.getSubMissionList()) GameplayDefinitions.subs.put(sub.getId(), sub);
      GameplayDefinitions.subs
          .get(MissionRaid46Test.FIRST)
          .setTask(gson.fromJson(battleSource.get("battleScript"), MissionTaskInfo.class));
      GameData.getPlaneEventExcelMap().values().removeIf(e -> e.getEventId() == eventId);
      for (var value : battleSource.getAsJsonArray("events"))
        GameData.getPlaneEventExcelMap().add(gson.fromJson(value, PlaneEventExcel.class));
      var stage =
          emu.lunarcore.data.ResourceLoader.getGson()
              .fromJson(battleSource.getAsJsonArray("stages").get(0), StageExcel.class);
      stage.onLoad();
      GameData.getStageExcelMap().add(stage);
      assertEquals(0, fixture.start());
      var service =
          new BattleService(null) {
            public void grantDrops(Battle battle) {}
          };
      var battle = Objects.requireNonNull(service.startBattle(session.hybrid, eventId, 0));
      assertNotNull(battle.getMissionBattleCustomValues());
      if (mutation == 1) session.hybrid.getGameplay().endMissionRaid();
      if (mutation == 2) {
        var original = session.hybrid.getGameplay().getMissionRaids().get(MissionRaid46Test.RAID);
        session
            .hybrid
            .getGameplay()
            .getMissionRaids()
            .put(
                MissionRaid46Test.RAID,
                gson.fromJson(gson.toJson(original), GameplayState.MissionRaidRun.class));
      }
      if (mutation == 3) session.hybrid.getCurrentLineup().getAvatars().clear();
      var stats = BattleStatistics.newInstance();
      stats.getMutableCustomValues().next().setKey("_PlayerWin").setValue(1);
      assertNotNull(
          service.finishBattle(
              session.hybrid, battle.getId(), stageId, BattleEndStatus.BATTLE_END_WIN, stats));
      assertEquals(
          mutation == 0 ? 1 : 0,
          session.hybrid.getMissionManager().sub(MissionRaid46Test.LAST).getProgress());
      assertEquals(
          mutation == 0 ? 1 : 0,
          session.hybrid.getMissionManager().sub(MissionRaid46Test.FIRST).getProgress());
    } finally {
      GameData.getPlaneEventExcelMap().values().removeIf(e -> e.getEventId() == eventId);
      oldEvents.forEach(GameData.getPlaneEventExcelMap()::add);
      if (oldStage == null) GameData.getStageExcelMap().keySet().remove(stageId);
      else GameData.getStageExcelMap().add(oldStage);
    }
  }

  @Test
  void corruptedFrozenTeamIsDeniedWithoutThrowingOrRebuildingThePlan() throws Exception {
    var session = owned();
    assertEquals(0, fixture.start());
    var state = gson.toJsonTree(session.hybrid.getGameplay()).getAsJsonObject();
    var plan =
        state
            .getAsJsonObject("missionRaids")
            .getAsJsonObject(Integer.toString(MissionRaid46Test.RAID))
            .getAsJsonObject("formalTeamPlan");
    plan.getAsJsonArray("trials").add(JsonNull.INSTANCE);
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(session.hybrid, gson.fromJson(state, GameplayState.class));
    assertFalse(new MissionRaid(session.hybrid).activeFor(MissionRaid46Test.MAIN));
    assertFalse(new MissionRaid(session.hybrid).restoreOnLogin());
    assertEquals(0, session.hybrid.getGameplay().getCurrentMissionRaid());
    assertEquals(MissionRaid46Test.HOME, session.hybrid.entry);
  }

  @Test
  void theFrozenSourcePlanSurvivesPauseAndJsonRestoreWithoutGrantingATrial() throws Exception {
    var session = owned();
    assertEquals(0, fixture.start());
    assertEquals(0, fixture.leave(MissionRaid46Test.RAID, false));
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(
        session.hybrid,
        gson.fromJson(gson.toJson(session.hybrid.getGameplay()), GameplayState.class));
    var plan =
        session
            .hybrid
            .getGameplay()
            .getMissionRaids()
            .get(MissionRaid46Test.RAID)
            .getFormalTeamPlan();
    assertEquals("TrialOrPlayer", plan.getType());
    assertEquals(List.of(1011209), plan.getTrials());
    assertEquals(Map.of(3, 1209), plan.getRequirements());
    assertEquals(0, fixture.start());
    assertTrue(new MissionRaid(session.hybrid).activeFor(MissionRaid46Test.MAIN));
    assertEquals(
        Set.of(MissionRaid46Test.AVATAR, 1209), session.hybrid.getAvatars().getAvatars().keySet());
    assertEquals(List.of(1209), session.hybrid.getCurrentLineup().getAvatars());
  }

  @Test
  void losingTheRequiredRoleBlocksProgressButStillAllowsLeaving() throws Exception {
    var session = owned();
    assertEquals(0, fixture.start());
    session.hybrid.getCurrentLineup().getAvatars().clear();
    session.hybrid.getCurrentLineup().getAvatars().add(MissionRaid46Test.AVATAR);
    assertFalse(new MissionRaid(session.hybrid).activeFor(MissionRaid46Test.MAIN));
    assertEquals(
        Retcode.MISSION_NOT_DOING,
        session.hybrid.getMissionManager().finishTalk(MissionRaid46Test.FIRST, "first"));
    assertEquals(0, fixture.leave(MissionRaid46Test.RAID, false));
    assertEquals(MissionRaid46Test.HOME, session.hybrid.entry);
    assertEquals(Retcode.RAID_AVATAR_NOT_MATCH.getVal(), fixture.start());
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "role", "type", "trials"})
  void oldOrChangedHybridPlansCannotBeReinterpretedOnResume(String mutation) throws Exception {
    var session = owned();
    assertEquals(0, fixture.start());
    assertEquals(0, fixture.leave(MissionRaid46Test.RAID, false));
    var state = gson.toJsonTree(session.hybrid.getGameplay()).getAsJsonObject();
    var run =
        state
            .getAsJsonObject("missionRaids")
            .getAsJsonObject(Integer.toString(MissionRaid46Test.RAID));
    switch (mutation) {
      case "missing" -> run.remove("formalTeamPlan");
      case "role" ->
          run.getAsJsonObject("formalTeamPlan")
              .getAsJsonObject("requirements")
              .addProperty("3", 1224);
      case "type" -> run.getAsJsonObject("formalTeamPlan").addProperty("type", "Player");
      case "trials" ->
          run.getAsJsonObject("formalTeamPlan").add("trials", gson.toJsonTree(List.of(1011224)));
    }
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(session.hybrid, gson.fromJson(state, GameplayState.class));
    var before = gson.toJson(session.hybrid.getGameplay());
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), fixture.start());
    assertEquals(before, gson.toJson(session.hybrid.getGameplay()));
    assertEquals(MissionRaid46Test.HOME, session.hybrid.entry);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "absent",
        "duplicate",
        "string-id",
        "fractional-actor",
        "unknown-type",
        "nonempty-list",
        "unknown-field",
        "new-condition"
      })
  void unverifiedSourceRulesCannotEnterOrCreateMissionState(String mutation) throws Exception {
    var session = owned();
    var rule = source.getAsJsonObject("teamLimit").deepCopy();
    var rows = new JsonArray();
    switch (mutation) {
      case "absent" -> rule.addProperty("ID", 4);
      case "duplicate" -> rows.add(rule.deepCopy());
      case "string-id" -> rule.addProperty("ID", "3");
      case "fractional-actor" -> rule.addProperty("ParamInt1", 1209.5);
      case "unknown-type" -> rule.addProperty("LimitType", "TeamLevel");
      case "nonempty-list" -> rule.add("ParamIntList", gson.toJsonTree(List.of(1209)));
      case "unknown-field" -> rule.addProperty("FutureRule", 1);
      case "new-condition" -> rule.addProperty("ParamInt2", 1);
    }
    rows.add(rule);
    Files.writeString(root.resolve("ExcelOutput/TeamLimitCondition.json"), rows.toString());
    assertNotEquals(0, fixture.start());
    assertEquals(MissionRaid46Test.HOME, session.hybrid.entry);
    assertFalse(session.hybrid.getGameplay().getMainMissions().containsKey(MissionRaid46Test.MAIN));
    assertTrue(session.hybrid.getGameplay().getMissionRaids().isEmpty());
  }

  @Test
  void anUnownedObjectWithTheSameRoleIdCannotSatisfyTheSourceRequirement() throws Exception {
    var session = owned();
    session.hybrid.getAvatars().getAvatars().put(1209, new GameAvatar());
    assertEquals(Retcode.RAID_AVATAR_NOT_MATCH.getVal(), fixture.start());
    assertEquals(MissionRaid46Test.HOME, session.hybrid.entry);
  }

  @Test
  void morphiaRestoresTheFrozenSourceTeamAndActualReturnPoint() throws Exception {
    var session = owned();
    assertEquals(0, fixture.start());
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "formalraid");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var saved = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(saved, 19997912);
      var field = Player.class.getDeclaredField("gameplay");
      field.setAccessible(true);
      field.set(saved, session.hybrid.getGameplay());
      store.save(saved);
      var restored = Objects.requireNonNull(store.find(Player.class).first());
      field.set(session.hybrid, restored.getGameplay());
      var plan =
          restored.getGameplay().getMissionRaids().get(MissionRaid46Test.RAID).getFormalTeamPlan();
      assertEquals("TrialOrPlayer", plan.getType());
      assertEquals(List.of(1011209), plan.getTrials());
      assertEquals(Map.of(3, 1209), plan.getRequirements());
      assertTrue(new MissionRaid(session.hybrid).restoreOnLogin());
      assertEquals(0, fixture.leave(MissionRaid46Test.RAID, false));
      assertEquals(MissionRaid46Test.HOME, session.hybrid.entry);
      assertEquals(12f, session.hybrid.pos.getX());
    } finally {
      mongo.shutdownNow();
    }
  }
}
