package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RaidHybridTrial46Test {
  @TempDir Path root;
  final RaidHybridFormal46Test hybrid = new RaidHybridFormal46Test();
  final Gson gson = ResourceLoader.getGson();
  final Gson savedGson = new Gson();
  List<SpecialAvatarExcel> oldSpecials;
  List<AvatarSkillTreeExcel> oldSkills;
  List<AvatarPromotionExcel> oldPromotions;
  List<EquipmentPromotionExcel> oldGearPromotions;
  Map<Long, AvatarSkillTreeExcel> oldCache;
  AvatarExcel oldActor;
  ItemExcel oldItem;
  JsonObject source;

  static class Actor extends MissionRaid46Test.Actor {
    public GameAvatar getAvatarById(int id) {
      var trial = getAvatars().getTempAvatars().get(id);
      var owned = getAvatars().getAvatars().get(id);
      return trial != null ? trial : owned != null ? owned : super.getAvatarById(id);
    }
  }

  static class Session extends MissionRaid46Test.Session {
    final Actor trial = new Actor();

    public Player getPlayer() {
      return trial;
    }
  }

  @BeforeEach
  void setup() throws Exception {
    hybrid.root = root;
    hybrid.setup();
    oldSpecials = new ArrayList<>(GameData.getSpecialAvatarExcelMap().values());
    oldSkills = new ArrayList<>(GameData.getAvatarSkillTreeExcelMap().values());
    oldPromotions = new ArrayList<>(GameData.getAvatarPromotionExcelMap().values());
    oldGearPromotions = new ArrayList<>(GameData.getEquipmentPromotionExcelMap().values());
    oldCache = new HashMap<>(GameDepot.getAvatarSkillTreeExcels());
    oldActor = GameData.getAvatarExcelMap().get(1209);
    oldItem = GameData.getItemExcelMap().get(23012);
    try (var input = getClass().getResourceAsStream("/gameplay/raid-trial-party-4.6.json")) {
      source =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
    GameData.getSpecialAvatarExcelMap().values().removeIf(row -> row.getId() == 1011209);
    GameData.getSpecialAvatarExcelMap()
        .add(gson.fromJson(source.get("special"), SpecialAvatarExcel.class));
    GameData.getAvatarSkillTreeExcelMap().values().removeIf(row -> row.getAvatarID() == 1209);
    GameData.getAvatarExcelMap().add(gson.fromJson(source.get("avatar"), AvatarExcel.class));
    GameData.getAvatarPromotionExcelMap().values().removeIf(row -> row.getAvatarID() == 1209);
    for (var raw : source.getAsJsonArray("avatarPromotions"))
      GameData.getAvatarPromotionExcelMap().add(gson.fromJson(raw, AvatarPromotionExcel.class));
    GameData.getEquipmentPromotionExcelMap()
        .values()
        .removeIf(row -> row.getEquipmentID() == 23012);
    for (var raw : source.getAsJsonArray("equipmentPromotions"))
      GameData.getEquipmentPromotionExcelMap()
          .add(gson.fromJson(raw, EquipmentPromotionExcel.class));
    for (var raw : source.getAsJsonArray("skills")) {
      var skill = gson.fromJson(raw, AvatarSkillTreeExcel.class);
      GameData.getAvatarSkillTreeExcelMap().add(skill);
      skill.onLoad();
    }
    GameData.getItemExcelMap().add(gson.fromJson(source.get("item"), ItemExcel.class));
    var equipment = gson.fromJson(source.get("equipment"), EquipmentExcel.class);
    equipment.onLoad();
    var folder = root.resolve("Config/BattleLineupPage/SkillTreePointPreset");
    Files.createDirectories(folder);
    Files.writeString(folder.resolve("preset.json"), source.get("preset").toString());
  }

  @AfterEach
  void cleanup() throws Exception {
    GameData.getAvatarPromotionExcelMap().values().clear();
    oldPromotions.forEach(GameData.getAvatarPromotionExcelMap()::add);
    GameData.getEquipmentPromotionExcelMap().values().clear();
    oldGearPromotions.forEach(GameData.getEquipmentPromotionExcelMap()::add);
    GameData.getSpecialAvatarExcelMap().values().clear();
    oldSpecials.forEach(GameData.getSpecialAvatarExcelMap()::add);
    GameData.getAvatarSkillTreeExcelMap().values().clear();
    oldSkills.forEach(GameData.getAvatarSkillTreeExcelMap()::add);
    GameDepot.getAvatarSkillTreeExcels().clear();
    GameDepot.getAvatarSkillTreeExcels().putAll(oldCache);
    GameData.getAvatarExcelMap().keySet().remove(1209);
    if (oldActor != null) GameData.getAvatarExcelMap().add(oldActor);
    GameData.getItemExcelMap().keySet().remove(23012);
    if (oldItem != null) GameData.getItemExcelMap().add(oldItem);
    hybrid.cleanup();
  }

  Session ready() {
    var session = new Session();
    hybrid.fixture.session = session;
    session
        .trial
        .getGameplay()
        .getMainMissions()
        .put(MissionRaid46Test.BEFORE, new QuestState(MissionRaid46Test.BEFORE));
    return session;
  }

  int start(Session session, int id, String typeName) throws Exception {
    var req = Gameplay46.builder(session, "StartRaidCsReq");
    Gameplay46.set(req, "raid_id", MissionRaid46Test.RAID);
    var field = req.getDescriptorForType().findFieldByName("NIMDEPFJJNI");
    var avatar = DynamicMessage.newBuilder(field.getMessageType());
    Gameplay46.set(avatar, "id", id);
    var type = avatar.getDescriptorForType().findFieldByName("avatar_type");
    avatar.setField(type, type.getEnumType().findValueByName(typeName));
    req.addRepeatedField(field, avatar.build());
    assertTrue(VersionedPacketHandler.handle(session, 2234, req.build().toByteArray()));
    return Gameplay46.number(session.packets.get("StartRaidScRsp"), "retcode");
  }

  void started(Session session) throws Exception {
    assertEquals(0, start(session, 1011209, "AvatarType_AvatarTrialType"));
    assertEquals(List.of(1011209), session.trial.getCurrentLineup().getAvatars());
    assertEquals(13, session.trial.getCurrentLineup().getExtraLineupType());
    assertEquals(MissionRaid46Test.ENTRY, session.trial.entry);
    assertTrue(
        session.trial.getAvatars().getAvatars().keySet().stream()
            .noneMatch(id -> id == 1209 || id == 1011209));
  }

  @Test
  void sourceTrialChoiceSatisfiesTheActorRuleWithoutOwningOrGrantingTheFormalRole()
      throws Exception {
    var session = ready();
    started(session);
    var actor = session.trial.getAvatarById(1011209);
    assertNotNull(actor);
    assertEquals(1209, actor.getActorId());
    assertEquals(AvatarType.AVATAR_TRIAL_TYPE, actor.getAvatarType());
    var battle = actor.toBattleProto(session.trial.getCurrentLineup(), 0);
    assertEquals(1011209, battle.getId());
    assertEquals(40, battle.getLevel());
    assertEquals(23012, battle.getEquipmentList().get(0).getId());
    assertEquals(40, battle.getEquipmentList().get(0).getLevel());
    assertTrue(battle.getSkilltreeList().length() > 0);
    assertEquals(0, hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    assertEquals(List.of(MissionRaid46Test.AVATAR), session.trial.getCurrentLineup().getAvatars());
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertEquals(12f, session.trial.getPos().getX());
  }

  @Test
  void pausedTrialPartyKeepsItsFrozenLevelEquipmentSkillsAndVitalsAfterJsonReload()
      throws Exception {
    var original = ready();
    started(original);
    var actor = original.trial.getAvatarById(1011209);
    var skills = new HashMap<>(actor.getSkillTree());
    actor.setCurrentHp(original.trial.getCurrentLineup(), 4321);
    actor.setCurrentSp(original.trial.getCurrentLineup(), 2468);
    assertEquals(0, hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    var saved = savedGson.toJson(original.trial.getGameplay());
    var changed = source.getAsJsonObject("special").deepCopy();
    changed.addProperty("Level", 80);
    changed.addProperty("EquipmentLevel", 80);
    GameData.getSpecialAvatarExcelMap().values().removeIf(row -> row.getId() == 1011209);
    GameData.getSpecialAvatarExcelMap().add(gson.fromJson(changed, SpecialAvatarExcel.class));
    Files.writeString(
        root.resolve("Config/BattleLineupPage/SkillTreePointPreset/preset.json"), "{}");
    var reloaded = ready();
    var gameplay = Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(reloaded.trial, savedGson.fromJson(saved, GameplayState.class));
    started(reloaded);
    var restored = reloaded.trial.getAvatarById(1011209);
    assertEquals(40, restored.getLevel());
    assertEquals(skills, new HashMap<>(restored.getSkillTree()));
    assertEquals(4321, restored.getCurrentHp(reloaded.trial.getCurrentLineup()));
    assertEquals(2468, restored.getCurrentSp(reloaded.trial.getCurrentLineup()));
    assertEquals(
        40,
        restored
            .toBattleProto(reloaded.trial.getCurrentLineup(), 0)
            .getEquipmentList()
            .get(0)
            .getLevel());
  }

  @ParameterizedTest
  @ValueSource(ints = {1209, 1011208, 1011210, 0})
  void aTrialChoiceOutsideTheExactRaidDeclarationCannotEnter(int id) throws Exception {
    var session = ready();
    assertNotEquals(0, start(session, id, "AvatarType_AvatarTrialType"));
    assertEquals(MissionRaid46Test.HOME, session.trial.entry);
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertEquals(0, session.trial.getGameplay().getCurrentMissionRaid());
  }

  @Test
  void trialIdWithFormalTypeDoesNotPretendToBeAnOwnedRole() throws Exception {
    var session = ready();
    assertNotEquals(0, start(session, 1011209, "AvatarType_AvatarFormalType"));
    assertEquals(MissionRaid46Test.HOME, session.trial.entry);
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
  }

  Session reload(Actor original, GameplayState data) throws Exception {
    var session = ready();
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(session.trial, data);
    session.trial.entry = original.entry;
    session.trial.plane = original.plane;
    session.trial.floor = original.floor;
    session.trial.pos.set(original.pos);
    session.trial.rot.set(original.rot);
    return session;
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void activePartyRestoresFrozenActorsAndVitalsThroughJsonAndActualPlayerStorage(boolean morphia)
      throws Exception {
    var original = ready();
    started(original);
    var actor = original.trial.getAvatarById(1011209);
    actor.setCurrentHp(original.trial.getCurrentLineup(), 4321);
    actor.setCurrentSp(original.trial.getCurrentLineup(), 2468);
    var data =
        savedGson.fromJson(savedGson.toJson(original.trial.getGameplay()), GameplayState.class);
    if (morphia) {
      var mongo =
          new de.bwaldvogel.mongo.MongoServer(
              new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
      var address = mongo.bind();
      try (var client =
          com.mongodb.client.MongoClients.create(
              "mongodb://" + address.getHostString() + ":" + address.getPort())) {
        var store = dev.morphia.Morphia.createDatastore(client, "trialraid");
        store.getMapper().mapPackage("org.hkrpg.server.gameplay");
        store.getMapper().map(Player.class, QuestState.class);
        var saved = new Player();
        var uid = Player.class.getDeclaredField("uid");
        uid.setAccessible(true);
        uid.setInt(saved, 19997913);
        var gameplay = Player.class.getDeclaredField("gameplay");
        gameplay.setAccessible(true);
        gameplay.set(saved, data);
        store.save(saved);
        data = Objects.requireNonNull(store.find(Player.class).first()).getGameplay();
      } finally {
        mongo.shutdownNow();
      }
    }
    var restored = reload(original.trial, data);
    assertTrue(new MissionRaid(restored.trial).restoreOnLogin());
    assertEquals(List.of(1011209), restored.trial.getCurrentLineup().getAvatars());
    assertEquals(
        4321,
        restored.trial.getAvatarById(1011209).getCurrentHp(restored.trial.getCurrentLineup()));
    assertEquals(
        2468,
        restored.trial.getAvatarById(1011209).getCurrentSp(restored.trial.getCurrentLineup()));
    assertEquals(
        new HashMap<>(actor.getSkillTree()),
        new HashMap<>(restored.trial.getAvatarById(1011209).getSkillTree()));
    assertEquals(0, hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    assertTrue(restored.trial.getAvatars().getTempAvatars().isEmpty());
    assertEquals(List.of(MissionRaid46Test.AVATAR), restored.trial.getCurrentLineup().getAvatars());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
  void corruptFrozenTrialPartyReturnsHomeWithoutGrantingOrDiscardingItsHistory(int variant)
      throws Exception {
    var original = ready();
    started(original);
    var json = savedGson.toJsonTree(original.trial.getGameplay()).getAsJsonObject();
    var party =
        json.getAsJsonObject("missionRaids")
            .getAsJsonObject(Integer.toString(MissionRaid46Test.RAID))
            .getAsJsonObject("trialParty");
    switch (variant) {
      case 0, 1 -> {
        var config =
            JsonParser.parseString(party.getAsJsonObject("configs").get("1011209").getAsString())
                .getAsJsonObject();
        config.addProperty(
            variant == 0 ? "SpecialAvatarID" : "AvatarID", variant == 0 ? 1209 : 1208);
        party.getAsJsonObject("configs").addProperty("1011209", config.toString());
      }
      case 2 -> party.add("skills", new JsonObject());
      case 3 -> party.getAsJsonObject("vitals").getAsJsonObject("1011209").addProperty("hp", -1);
      case 4 -> party.getAsJsonArray("roster").add(1011209);
      case 5 -> party.add("plans", new JsonObject());
      case 6 -> party.addProperty("mp", Integer.MAX_VALUE);
      case 7 -> party.addProperty("playerWorld", 1);
    }
    var restored = reload(original.trial, savedGson.fromJson(json, GameplayState.class));
    assertFalse(new MissionRaid(restored.trial).restoreOnLogin());
    assertEquals(MissionRaid46Test.HOME, restored.trial.entry);
    assertEquals(0, restored.trial.getGameplay().getCurrentMissionRaid());
    assertTrue(restored.trial.getAvatars().getTempAvatars().isEmpty());
    assertTrue(restored.trial.getGameplay().getMissionRaids().containsKey(MissionRaid46Test.RAID));
    assertFalse(restored.trial.getAvatars().getAvatars().containsKey(1011209));
  }

  @Test
  void rejectedDestinationRemovesOnlyItsPreparedTrialsAndKeepsTheOrdinaryRoster() throws Exception {
    var session = ready();
    session.trial.rejectScene = true;
    assertEquals(
        Retcode.SCENE_ENTRY_ID_NOT_MATCH.getVal(),
        start(session, 1011209, "AvatarType_AvatarTrialType"));
    assertEquals(List.of(MissionRaid46Test.AVATAR), session.trial.getCurrentLineup().getAvatars());
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertTrue(session.trial.getGameplay().getMissionRaids().isEmpty());
  }

  @Test
  void anExistingTemporaryIdentityCannotBeReplacedByTheRaidChoice() throws Exception {
    var session = ready();
    var existing = session.trial.getTrialManager().createTrialAvatar(1011209);
    assertNotNull(existing);
    assertNotEquals(0, start(session, 1011209, "AvatarType_AvatarTrialType"));
    assertSame(existing, session.trial.getAvatars().getTempAvatars().get(1011209));
    assertEquals(0, session.trial.getGameplay().getCurrentMissionRaid());
  }

  @Test
  void aFakeOrdinaryReturnPartyCannotUseTrialsToBypassOwnedAvatarIdentity() throws Exception {
    var session = ready();
    session.trial.getAvatars().getAvatars().clear();
    assertNotEquals(0, start(session, 1011209, "AvatarType_AvatarTrialType"));
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertEquals(MissionRaid46Test.HOME, session.trial.entry);
  }

  @Test
  void aWorldScaledTrialNeedsItsExactWorldRowBeforeTheRaidCanEnter() throws Exception {
    var session = ready();
    GameData.getSpecialAvatarExcelMap().values().removeIf(row -> row.getId() == 1011209);
    var row = source.getAsJsonObject("special").deepCopy();
    row.addProperty("WorldLevel", 1);
    GameData.getSpecialAvatarExcelMap().add(gson.fromJson(row, SpecialAvatarExcel.class));
    assertNotEquals(0, start(session, 1011209, "AvatarType_AvatarTrialType"));
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertEquals(MissionRaid46Test.HOME, session.trial.entry);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9})
  void invalidFrozenActorProgressionAndEquipmentCannotRestoreAPlayableParty(int variant)
      throws Exception {
    var original = ready();
    started(original);
    var json = savedGson.toJsonTree(original.trial.getGameplay()).getAsJsonObject();
    var party =
        json.getAsJsonObject("missionRaids")
            .getAsJsonObject(Integer.toString(MissionRaid46Test.RAID))
            .getAsJsonObject("trialParty");
    var configs = party.getAsJsonObject("configs");
    var row = JsonParser.parseString(configs.get("1011209").getAsString()).getAsJsonObject();
    switch (variant) {
      case 0 -> row.addProperty("Level", -1);
      case 1 -> row.addProperty("Level", 0);
      case 2 -> row.addProperty("Level", 81);
      case 3 -> row.addProperty("Promotion", -1);
      case 4 -> row.addProperty("Promotion", 7);
      case 5 -> row.addProperty("Rank", 7);
      case 6 -> row.addProperty("WorldLevel", 1);
      case 7 -> row.addProperty("EquipmentLevel", 81);
      case 8 -> row.addProperty("EquipmentPromotion", 7);
      case 9 -> row.addProperty("EquipmentRank", 6);
    }
    configs.addProperty("1011209", row.toString());
    var restored = reload(original.trial, savedGson.fromJson(json, GameplayState.class));
    assertFalse(new MissionRaid(restored.trial).restoreOnLogin());
    assertEquals(MissionRaid46Test.HOME, restored.trial.entry);
    assertTrue(restored.trial.getAvatars().getTempAvatars().isEmpty());
    assertTrue(restored.trial.getGameplay().getMissionRaids().containsKey(MissionRaid46Test.RAID));
  }

  @Test
  void declaredTrialAndOwnedFormalMemberCanShareARaidWithoutChangingTheOrdinaryParty()
      throws Exception {
    var session = ready();
    var regular =
        new GameAvatar() {
          public int getAvatarId() {
            return MissionRaid46Test.AVATAR;
          }

          public int getActorId() {
            return MissionRaid46Test.AVATAR;
          }

          public int getMaxSp() {
            return 10000;
          }

          public void save() {}
        };
    session.trial.getAvatars().getAvatars().put(MissionRaid46Test.AVATAR, regular);
    var choices =
        List.of(
            new RaidTeams.Selection(MissionRaid46Test.AVATAR, false),
            new RaidTeams.Selection(1011209, true));
    assertEquals(
        Retcode.SUCC,
        new MissionRaid(session.trial).enterSelected(MissionRaid46Test.RAID, 0, 0, 0, choices));
    var lineup = session.trial.getCurrentLineup();
    assertEquals(List.of(MissionRaid46Test.AVATAR, 1011209), lineup.getAvatars());
    assertSame(regular, session.trial.getAvatarById(MissionRaid46Test.AVATAR));
    regular.setCurrentHp(lineup, 4321);
    assertEquals(10000, regular.getCurrentHp(session.trial.teams.getLineupByIndex(0)));
    assertEquals(4321, regular.getCurrentHp(lineup));
    assertEquals(0, hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertEquals(List.of(MissionRaid46Test.AVATAR), session.trial.getCurrentLineup().getAvatars());
    assertEquals(10000, regular.getCurrentHp(session.trial.getCurrentLineup()));
  }

  GameAvatar ownedMember(Session session, int actorId) {
    var actor =
        new GameAvatar() {
          public int getAvatarId() {
            return MissionRaid46Test.AVATAR;
          }

          public int getActorId() {
            return actorId;
          }

          public int getMaxSp() {
            return 10000;
          }

          public void save() {}
        };
    actor.setOwner(session.trial);
    session.trial.getAvatars().getAvatars().put(MissionRaid46Test.AVATAR, actor);
    return actor;
  }

  List<RaidTeams.Selection> mixed(boolean reverse) {
    var choices =
        new ArrayList<>(
            List.of(
                new RaidTeams.Selection(MissionRaid46Test.AVATAR, false),
                new RaidTeams.Selection(1011209, true)));
    if (reverse) Collections.reverse(choices);
    return choices;
  }

  int choicesStart(Session session, List<RaidTeams.Selection> choices) throws Exception {
    var req = Gameplay46.builder(session, "StartRaidCsReq");
    Gameplay46.set(req, "raid_id", MissionRaid46Test.RAID);
    var rows = req.getDescriptorForType().findFieldByName("NIMDEPFJJNI");
    for (var choice : choices) {
      var avatar = DynamicMessage.newBuilder(rows.getMessageType());
      Gameplay46.set(avatar, "id", choice.id());
      var type = avatar.getDescriptorForType().findFieldByName("avatar_type");
      avatar.setField(
          type,
          type.getEnumType()
              .findValueByName(
                  choice.trial() ? "AvatarType_AvatarTrialType" : "AvatarType_AvatarFormalType"));
      req.addRepeatedField(rows, avatar.build());
    }
    assertTrue(VersionedPacketHandler.handle(session, 2234, req.build().toByteArray()));
    return Gameplay46.number(session.packets.get("StartRaidScRsp"), "retcode");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nativeMixedSelectionsKeepBothSemanticTypesAndRequestOrder(boolean reverse) throws Exception {
    var session = ready();
    ownedMember(session, MissionRaid46Test.AVATAR);
    var choices = mixed(reverse);
    assertEquals(0, choicesStart(session, choices));
    assertEquals(
        choices.stream().map(RaidTeams.Selection::id).toList(),
        session.trial.getCurrentLineup().getAvatars());
    assertEquals(0, session.trial.getCurrentLineup().getLeader());
    var proto = session.trial.getCurrentLineup().toProto();
    assertEquals(2, proto.getAvatarList().length());
    for (int i = 0; i < 2; i++)
      assertEquals(
          choices.get(i).trial() ? AvatarType.AVATAR_TRIAL_TYPE : AvatarType.AVATAR_FORMAL_TYPE,
          proto.getAvatarList().get(i).getAvatarType());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void mixedPartyJsonReloadKeepsEachMembersRaidVitalsAndOrdinaryVitals(boolean active)
      throws Exception {
    var original = ready();
    var formal = ownedMember(original, MissionRaid46Test.AVATAR);
    assertEquals(0, choicesStart(original, mixed(false)));
    formal.setCurrentHp(original.trial.getCurrentLineup(), 4321);
    original.trial.getAvatarById(1011209).setCurrentHp(original.trial.getCurrentLineup(), 2468);
    if (!active) assertEquals(0, hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    var restored =
        reload(
            original.trial,
            savedGson.fromJson(
                savedGson.toJson(original.trial.getGameplay()), GameplayState.class));
    var owned = ownedMember(restored, MissionRaid46Test.AVATAR);
    if (active) assertTrue(new MissionRaid(restored.trial).restoreOnLogin());
    else assertEquals(0, choicesStart(restored, mixed(false)));
    assertEquals(4321, owned.getCurrentHp(restored.trial.getCurrentLineup()));
    assertEquals(
        2468,
        restored.trial.getAvatarById(1011209).getCurrentHp(restored.trial.getCurrentLineup()));
    assertEquals(10000, owned.getCurrentHp(restored.trial.teams.getLineupByIndex(0)));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5})
  void mixedPartyCannotContainUnownedWrongTypedOrDuplicateMembers(int variant) throws Exception {
    var session = ready();
    ownedMember(session, variant == 5 ? 1209 : MissionRaid46Test.AVATAR);
    var choices = new ArrayList<>(mixed(false));
    switch (variant) {
      case 0 -> choices.set(0, new RaidTeams.Selection(MissionRaid46Test.AVATAR + 1, false));
      case 1 -> choices.set(0, new RaidTeams.Selection(MissionRaid46Test.AVATAR, true));
      case 2 -> choices.set(1, new RaidTeams.Selection(1011209, false));
      case 3 -> choices.add(choices.get(0));
      case 4 -> choices.add(choices.get(1));
      case 5 -> {}
    }
    assertNotEquals(0, choicesStart(session, choices));
    assertEquals(MissionRaid46Test.HOME, session.trial.entry);
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertEquals(0, session.trial.getGameplay().getCurrentMissionRaid());
  }

  void raidFields(JsonObject row) {
    hybrid.fixture.resource = gson.fromJson(row, GameplayDefinitions.Raid.class);
    GameplayDefinitions.raids.put(hybrid.fixture.resource.key(), hybrid.fixture.resource);
  }

  @Test
  void syntheticTrialOnlyUsesItsDeclarationWhenTheRequestOmitsChoices() throws Exception {
    var row = gson.toJsonTree(hybrid.fixture.resource).getAsJsonObject();
    row.addProperty("TeamType", "TrialOnly");
    raidFields(row);
    var session = ready();
    assertEquals(0, choicesStart(session, List.of()));
    assertEquals(List.of(1011209), session.trial.getCurrentLineup().getAvatars());
    assertEquals(
        AvatarType.AVATAR_TRIAL_TYPE, session.trial.getAvatarById(1011209).getAvatarType());
    assertEquals(
        List.of(MissionRaid46Test.AVATAR), session.trial.teams.getLineupByIndex(0).getAvatars());
  }

  @Test
  void sourceForbidReplaceRuleAllowsAnImmutableDeclaredTrialParty() throws Exception {
    var row = gson.toJsonTree(hybrid.fixture.resource).getAsJsonObject();
    row.add("TeamLimitIDList", JsonParser.parseString("[14]"));
    Files.writeString(
        root.resolve("ExcelOutput/TeamLimitCondition.json"),
        "[{\"ID\":14,\"LimitType\":\"ForbidReplace\",\"ParamIntList\":[]}]");
    raidFields(row);
    var session = ready();
    assertEquals(0, choicesStart(session, List.of(new RaidTeams.Selection(1011209, true))));
    session.trial.getCurrentLineup().replace(List.of(MissionRaid46Test.AVATAR));
    assertEquals(List.of(1011209), session.trial.getCurrentLineup().getAvatars());
  }

  @Test
  void declaredCaptainUsesTheSelectedActorSlotAndCannotBeChanged() throws Exception {
    var row = gson.toJsonTree(hybrid.fixture.resource).getAsJsonObject();
    row.addProperty("LockCaptain", true);
    row.addProperty("LockCaptainAvatarID", 1209);
    raidFields(row);
    var session = ready();
    ownedMember(session, MissionRaid46Test.AVATAR);
    assertEquals(0, choicesStart(session, mixed(false)));
    assertEquals(1, session.trial.getCurrentLineup().getLeader());
    assertNotEquals(Retcode.SUCC, LineupLeaders.change(session.trial, 0));
    session.trial.getCurrentLineup().setLeader(0);
    assertEquals(1, session.trial.getCurrentLineup().getLeader());
  }

  @Test
  void missingMixedFormalMemberBlocksRaidProgressButStillAllowsReturningHome() throws Exception {
    var session = ready();
    ownedMember(session, MissionRaid46Test.AVATAR);
    assertEquals(0, choicesStart(session, mixed(false)));
    session.trial.getAvatars().getAvatars().clear();
    assertFalse(new MissionRaid(session.trial).activeFor(MissionRaid46Test.MAIN));
    assertEquals(0, hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    assertEquals(MissionRaid46Test.HOME, session.trial.entry);
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertTrue(session.trial.getGameplay().getMissionRaids().containsKey(MissionRaid46Test.RAID));
  }

  void formalFrozenRule(boolean captain) throws Exception {
    var row = gson.toJsonTree(hybrid.fixture.resource).getAsJsonObject();
    if (captain) {
      row.addProperty("LockCaptain", true);
      row.addProperty("LockCaptainAvatarID", 1209);
    } else {
      row.add("TeamLimitIDList", JsonParser.parseString("[3,14]"));
      var rules = new JsonArray();
      rules.add(hybrid.source.get("teamLimit").deepCopy());
      rules.add(
          JsonParser.parseString(
              "{\"ID\":14,\"LimitType\":\"ForbidReplace\",\"ParamIntList\":[]}"));
      Files.writeString(root.resolve("ExcelOutput/TeamLimitCondition.json"), rules.toString());
    }
    raidFields(row);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void formalOnlyCaptainOrReplaceRuleGetsAnIndependentPartyWithoutGrantingTrials(int variant)
      throws Exception {
    formalFrozenRule(variant < 2);
    var session = ready();
    var actor = ownedMember(session, 1209);
    var selected =
        variant % 2 == 0
            ? List.<RaidTeams.Selection>of()
            : List.of(new RaidTeams.Selection(MissionRaid46Test.AVATAR, false));
    assertEquals(0, choicesStart(session, selected));
    assertEquals(13, session.trial.getCurrentLineup().getExtraLineupType());
    assertEquals(List.of(MissionRaid46Test.AVATAR), session.trial.getCurrentLineup().getAvatars());
    assertTrue(new MissionRaid(session.trial).current().getTrialParty().getConfigs().isEmpty());
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    actor.setCurrentHp(session.trial.getCurrentLineup(), 4321);
    assertEquals(10000, actor.getCurrentHp(session.trial.teams.getLineupByIndex(0)));
    assertEquals(0, hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    assertEquals(10000, actor.getCurrentHp(session.trial.getCurrentLineup()));
  }

  GameAvatar extraOwned(Session session) {
    var actor =
        new GameAvatar() {
          public int getAvatarId() {
            return 1002;
          }

          public int getActorId() {
            return 1002;
          }

          public int getMaxSp() {
            return 10000;
          }

          public void save() {}
        };
    actor.setOwner(session.trial);
    session.trial.getAvatars().getAvatars().put(1002, actor);
    return actor;
  }

  List<RaidTeams.Selection> frozenFormals() {
    return List.of(
        new RaidTeams.Selection(1002, false),
        new RaidTeams.Selection(MissionRaid46Test.AVATAR, false));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void ownedFormalSelectionsOutsideTheOrdinaryRosterKeepCaptainAndReplaceRules(boolean captain)
      throws Exception {
    formalFrozenRule(captain);
    var session = ready();
    ownedMember(session, 1209);
    extraOwned(session);
    assertEquals(0, choicesStart(session, frozenFormals()));
    assertEquals(
        List.of(1002, MissionRaid46Test.AVATAR), session.trial.getCurrentLineup().getAvatars());
    assertEquals(captain ? 1 : 0, session.trial.getCurrentLineup().getLeader());
    assertEquals(
        captain ? Retcode.REQ_PARA_INVALID : Retcode.SUCC,
        LineupLeaders.change(session.trial, captain ? 0 : 1));
    session.trial.getCurrentLineup().replace(List.of(MissionRaid46Test.AVATAR));
    assertEquals(
        List.of(1002, MissionRaid46Test.AVATAR), session.trial.getCurrentLineup().getAvatars());
    assertEquals(
        List.of(MissionRaid46Test.AVATAR), session.trial.teams.getLineupByIndex(0).getAvatars());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void formalFrozenPartyReloadUsesItsSavedRosterAndVitalsAfterOrdinaryEdits(int variant)
      throws Exception {
    boolean captain = variant % 2 == 0, active = variant < 2;
    formalFrozenRule(captain);
    var original = ready();
    var owned = ownedMember(original, 1209);
    extraOwned(original);
    assertEquals(0, choicesStart(original, frozenFormals()));
    owned.setCurrentHp(original.trial.getCurrentLineup(), 4321);
    if (!active) assertEquals(0, hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    var data =
        savedGson.fromJson(savedGson.toJson(original.trial.getGameplay()), GameplayState.class);
    var restored = reload(original.trial, data);
    var actor = ownedMember(restored, 1209);
    extraOwned(restored);
    var ordinary = restored.trial.teams.getLineupByIndex(0);
    ordinary.getAvatars().clear();
    ordinary.getAvatars().add(1002);
    ordinary.setLeader(0);
    if (active) assertTrue(new MissionRaid(restored.trial).restoreOnLogin());
    else assertEquals(0, choicesStart(restored, List.of()));
    assertEquals(
        List.of(1002, MissionRaid46Test.AVATAR), restored.trial.getCurrentLineup().getAvatars());
    assertEquals(4321, actor.getCurrentHp(restored.trial.getCurrentLineup()));
    assertEquals(10000, actor.getCurrentHp(ordinary));
    assertEquals(0, hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    assertEquals(List.of(1002), restored.trial.getCurrentLineup().getAvatars());
    assertTrue(restored.trial.getAvatars().getTempAvatars().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void missingFrozenFormalMemberCannotResumeAndKeepsItsHistory(boolean active) throws Exception {
    formalFrozenRule(true);
    var original = ready();
    ownedMember(original, 1209);
    extraOwned(original);
    assertEquals(0, choicesStart(original, frozenFormals()));
    if (!active) assertEquals(0, hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    var restored =
        reload(
            original.trial,
            savedGson.fromJson(
                savedGson.toJson(original.trial.getGameplay()), GameplayState.class));
    ownedMember(restored, 1209);
    if (active) assertFalse(new MissionRaid(restored.trial).restoreOnLogin());
    else assertNotEquals(0, choicesStart(restored, List.of()));
    assertEquals(0, restored.trial.getGameplay().getCurrentMissionRaid());
    assertEquals(MissionRaid46Test.HOME, restored.trial.entry);
    assertTrue(restored.trial.getGameplay().getMissionRaids().containsKey(MissionRaid46Test.RAID));
    assertTrue(restored.trial.getAvatars().getTempAvatars().isEmpty());
  }

  @Test
  void replacingAnOwnedFormalObjectCannotAdvanceItsAlreadyFrozenRaid() throws Exception {
    formalFrozenRule(true);
    var session = ready();
    ownedMember(session, 1209);
    extraOwned(session);
    assertEquals(0, choicesStart(session, frozenFormals()));
    ownedMember(session, 1209);
    assertFalse(new MissionRaid(session.trial).activeFor(MissionRaid46Test.MAIN));
    assertEquals(0, hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
  }

  void playerFrozenRule(boolean captain) throws Exception {
    JsonObject source;
    try (var input = getClass().getResourceAsStream("/gameplay/raid-player-frozen-4.6.json")) {
      source =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
    var row = gson.toJsonTree(hybrid.fixture.resource).getAsJsonObject();
    for (String key : List.of("TeamType", "TrialAvatarList", "TeamLimitIDList"))
      row.add(key, source.getAsJsonObject("raid").get(key).deepCopy());
    Files.writeString(
        root.resolve("ExcelOutput/TeamLimitCondition.json"), "[" + source.get("rule") + "]");
    if (captain) {
      row.addProperty("LockCaptain", true);
      row.addProperty("LockCaptainAvatarID", 1209);
    }
    raidFields(row);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void playerFrozenPartyUsesOwnedSelectionsWithoutDeclaringOrGrantingTrials(int variant)
      throws Exception {
    playerFrozenRule(variant < 2);
    var session = ready();
    var actor = ownedMember(session, 1209);
    var selected =
        variant % 2 == 0
            ? List.<RaidTeams.Selection>of()
            : List.of(new RaidTeams.Selection(MissionRaid46Test.AVATAR, false));
    assertEquals(0, choicesStart(session, selected));
    assertEquals(13, session.trial.getCurrentLineup().getExtraLineupType());
    var run = new MissionRaid(session.trial).current();
    assertEquals("Player", run.getFormalTeamPlan().getType());
    assertTrue(run.getFormalTeamPlan().getTrials().isEmpty());
    assertTrue(run.getTrialParty().getConfigs().isEmpty());
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    actor.setCurrentHp(session.trial.getCurrentLineup(), 4321);
    assertEquals(10000, actor.getCurrentHp(session.trial.teams.getLineupByIndex(0)));
    assertEquals(0, hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    assertEquals(10000, actor.getCurrentHp(session.trial.getCurrentLineup()));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void playerFrozenSelectedRosterCanDifferFromOrdinaryButRetainsItsLeaderPolicy(boolean captain)
      throws Exception {
    playerFrozenRule(captain);
    var session = ready();
    ownedMember(session, 1209);
    extraOwned(session);
    assertEquals(0, choicesStart(session, frozenFormals()));
    var lineup = session.trial.getCurrentLineup();
    assertEquals(captain ? 1 : 0, lineup.getLeader());
    assertEquals(
        captain ? Retcode.REQ_PARA_INVALID : Retcode.SUCC,
        LineupLeaders.change(session.trial, captain ? 0 : 1));
    lineup.replace(List.of(1002));
    assertEquals(List.of(1002, MissionRaid46Test.AVATAR), lineup.getAvatars());
    assertEquals(
        List.of(MissionRaid46Test.AVATAR), session.trial.teams.getLineupByIndex(0).getAvatars());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void playerFrozenRosterAndVitalsSurviveActiveAndPausedReload(int variant) throws Exception {
    boolean captain = variant % 2 == 0, active = variant < 2;
    playerFrozenRule(captain);
    var session = ready();
    var actor = ownedMember(session, 1209);
    extraOwned(session);
    assertEquals(0, choicesStart(session, frozenFormals()));
    actor.setCurrentHp(session.trial.getCurrentLineup(), 4321);
    if (!active) assertEquals(0, hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    var restored =
        reload(
            session.trial,
            savedGson.fromJson(savedGson.toJson(session.trial.getGameplay()), GameplayState.class));
    var owned = ownedMember(restored, 1209);
    extraOwned(restored);
    var ordinary = restored.trial.teams.getLineupByIndex(0);
    ordinary.getAvatars().clear();
    ordinary.getAvatars().add(1002);
    ordinary.setLeader(0);
    if (active) assertTrue(new MissionRaid(restored.trial).restoreOnLogin());
    else assertEquals(0, choicesStart(restored, List.of()));
    assertEquals(
        List.of(1002, MissionRaid46Test.AVATAR), restored.trial.getCurrentLineup().getAvatars());
    assertEquals(4321, owned.getCurrentHp(restored.trial.getCurrentLineup()));
    assertEquals(10000, owned.getCurrentHp(ordinary));
    assertEquals(0, hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    assertEquals(List.of(1002), restored.trial.getCurrentLineup().getAvatars());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aPlayerRaidCannotSelectADeclaredOrUndeclaredTemporaryTrial(boolean captain)
      throws Exception {
    playerFrozenRule(captain);
    var session = ready();
    ownedMember(session, 1209);
    assertNotEquals(0, choicesStart(session, List.of(new RaidTeams.Selection(1011209, true))));
    assertEquals(MissionRaid46Test.HOME, session.trial.entry);
    assertTrue(session.trial.getAvatars().getTempAvatars().isEmpty());
    assertTrue(session.trial.getGameplay().getMissionRaids().isEmpty());
  }

  @Test
  void missingPlayerCaptainCannotBeReplacedByAnotherOwnedFormalMember() throws Exception {
    playerFrozenRule(true);
    var session = ready();
    ownedMember(session, 1209);
    extraOwned(session);
    assertNotEquals(0, choicesStart(session, List.of(new RaidTeams.Selection(1002, false))));
    assertTrue(session.trial.getGameplay().getMissionRaids().isEmpty());
    assertEquals(MissionRaid46Test.HOME, session.trial.entry);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void playerFrozenOwnershipLossCannotResumeAndKeepsTheSavedHistory(boolean active)
      throws Exception {
    playerFrozenRule(false);
    var session = ready();
    ownedMember(session, 1209);
    extraOwned(session);
    assertEquals(0, choicesStart(session, frozenFormals()));
    if (!active) assertEquals(0, hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    var restored =
        reload(
            session.trial,
            savedGson.fromJson(savedGson.toJson(session.trial.getGameplay()), GameplayState.class));
    ownedMember(restored, 1209);
    if (active) assertFalse(new MissionRaid(restored.trial).restoreOnLogin());
    else assertNotEquals(0, choicesStart(restored, List.of()));
    assertEquals(MissionRaid46Test.HOME, restored.trial.entry);
    assertEquals(0, restored.trial.getGameplay().getCurrentMissionRaid());
    assertTrue(restored.trial.getGameplay().getMissionRaids().containsKey(MissionRaid46Test.RAID));
  }

  @Test
  void removingThePlayerReplaceRuleCannotAdoptAnExistingFrozenRun() throws Exception {
    playerFrozenRule(false);
    var session = ready();
    ownedMember(session, 1209);
    assertEquals(0, choicesStart(session, List.of()));
    var row = gson.toJsonTree(hybrid.fixture.resource).getAsJsonObject();
    row.add("TeamLimitIDList", new JsonArray());
    raidFields(row);
    assertFalse(new MissionRaid(session.trial).activeFor(MissionRaid46Test.MAIN));
    assertFalse(new MissionRaid(session.trial).restoreOnLogin());
    assertEquals(MissionRaid46Test.HOME, session.trial.entry);
    assertTrue(session.trial.getGameplay().getMissionRaids().containsKey(MissionRaid46Test.RAID));
  }
}
