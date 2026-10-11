package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.SkillAbilityInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Complete original1407 Free direct attack through actual Battle46; no animation/summon simulation. */
class MazeCastoriceManualAbility46Test {
  static final String BASE = "LocalPlayer_Castorice_MazeSkill";
  static final String INPUT = "LocalPlayer_Castorice_MazeSkill_Input";
  static final String ABILITY = "Config/ConfigAdventureAbility/LocalPlayer/LocalPlayer_Castorice_00_Ability.json";
  static final int BASE_USE = 91800, CHILD_USE = 91801;
  @TempDir Path root;
  final MazeCastSequence46Test casts = new MazeCastSequence46Test();
  JsonObject source, fixture;
  List<AvatarSkillParameterExcel> previousParameters;
  List<MazeBuffExcel> previousBuffs;

  @BeforeEach
  void setup() throws Exception {
    casts.root = root;
    casts.setup();
    previousParameters = new ArrayList<>(GameData.getAvatarSkillParameterExcelMap().values());
    previousBuffs = new ArrayList<>(GameData.getMazeBuffExcelMap().values());
    var configured = System.getProperty("castorice.originalFixture");
    String fixtureText;
    if (configured != null) fixtureText = Files.readString(Path.of(configured));
    else try (var stream = getClass().getResourceAsStream("/fixtures/maze-castorice-manual-ability-4.6.json")) {
      assertNotNull(stream, "Complete original Castorice source fixture must be present");
      fixtureText = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    fixture = JsonParser.parseString(fixtureText).getAsJsonObject();
    assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b", fixture.get("sourceCommit").getAsString());
    assertEquals(12, fixture.getAsJsonObject("sourceFiles").size());
    for (var entry : fixture.getAsJsonObject("sourceFiles").entrySet()) {
      var record = entry.getValue().getAsJsonObject();
      byte[] raw = originalBytes(record);
      assertEquals(record.get("blobSHA256").getAsString(), MazeManualAbility46Test.sha(raw));
      assertEquals(record.get("fullBlobBytes").getAsInt(), raw.length);
      var file = root.resolve(entry.getKey());
      Files.createDirectories(file.getParent());
      Files.write(file, raw);
      assertTrue(file.toRealPath().startsWith(root.toRealPath()));
    }
    var excel = ResourceLoader.getGson().fromJson(row("AvatarConfig", "AvatarID", 1407), AvatarExcel.class);
    source = JsonParser.parseString(Files.readString(root.resolve(ABILITY))).getAsJsonObject();
    casts.phases.previous.putIfAbsent(1407, GameData.getAvatarExcelMap().get(1407));
    assertTrue(ResourceLoader.getGson().fromJson(source, SkillAbilityInfo.class).parse(excel));
    assertEquals(BASE, excel.getMazeSkill().getAbilityName());
    GameData.getAvatarExcelMap().add(excel);
    GameData.getAvatarSkillParameterExcelMap().add(ResourceLoader.getGson().fromJson(
        row("AvatarSkillConfig", "SkillID", 140707), AvatarSkillParameterExcel.class));
    for (int id : new int[] {140701, 140704}) GameData.getMazeBuffExcelMap().add(
        ResourceLoader.getGson().fromJson(row("AvatarMazeBuff", "ID", id), MazeBuffExcel.class));
    var player = casts.session.getPlayer();
    int entity = casts.base.actor.getEntityId();
    casts.base.actor = new GameAvatar(excel) { @Override public void save() {} };
    casts.base.actor.setOwner(player);
    casts.base.actor.setEntityId(entity);
    player.getAvatars().getAvatars().put(1407, casts.base.actor);
    player.getCurrentLineup().getAvatars().clear();
    player.getCurrentLineup().getAvatars().add(1407);
    player.getCurrentLineup().setMp(5);
    player.getScene().getAvatars().clear();
    player.getScene().getAvatars().put(1407, casts.base.actor);
    player.getScene().getAvatarEntityIds().clear();
    player.getScene().getAvatarEntityIds().add(entity);
  }

  static byte[] originalBytes(JsonObject record) throws Exception {
    if (record.has("rawBase64")) return Base64.getDecoder().decode(record.get("rawBase64").getAsString());
    assertTrue(record.has("zlibBase64"), "Complete original blob encoding must be declared");
    int length = record.get("fullBlobBytes").getAsInt();
    assertTrue(length > 0 && length <= 16_000_000);
    try (var stream = new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(
        Base64.getDecoder().decode(record.get("zlibBase64").getAsString())))) {
      byte[] raw = stream.readNBytes(length + 1);
      assertEquals(length, raw.length, "Decoded original blob length must match its pinned byte count");
      return raw;
    }
  }

  @AfterEach
  void cleanup() throws Exception {
    if (previousParameters != null) {
      GameData.getAvatarSkillParameterExcelMap().values().clear();
      previousParameters.forEach(GameData.getAvatarSkillParameterExcelMap()::add);
    }
    if (previousBuffs != null) {
      GameData.getMazeBuffExcelMap().values().clear();
      previousBuffs.forEach(GameData.getMazeBuffExcelMap()::add);
    }
    casts.restore();
  }

  JsonObject row(String table, String key, int id) throws Exception {
    return JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/" + table + ".json")))
        .getAsJsonArray().asList().stream().map(JsonElement::getAsJsonObject)
        .filter(value -> value.get(key).getAsInt() == id).findFirst().orElseThrow();
  }

  DynamicMessage named(int use, String name, int index, int[] tags, EntityMonster... targets) throws Exception {
    var request = DynamicMessage.newBuilder(casts.base.protocol.clientSchema().message("SceneCastSkillCsReq"));
    var type = request.getDescriptorForType();
    request.setField(type.findFieldByName("cast_entity_id"), use);
    request.setField(type.findFieldByName("attacked_by_entity_id"), casts.base.actor.getEntityId());
    request.setField(type.findFieldByName("maze_ability_str"), name);
    request.setField(type.findFieldByName("skill_index"), index);
    var field = type.findFieldByName("skill_extra_tags");
    for (int tag : tags) request.addRepeatedField(field, field.getEnumType().findValueByNumberCreatingIfUnknown(tag));
    for (var target : targets) request.addRepeatedField(type.findFieldByName("hit_target_entity_id_list"), target.getEntityId());
    return casts.dispatch("SceneCastSkillCsReq", request.build());
  }

  int code(DynamicMessage value) { return MazeCastSequence46Test.number(value, "retcode"); }
  int notices() { return casts.session.messages.getOrDefault("SceneCastSkillMpUpdateScNotify", List.of()).size(); }

  void activate() throws Exception {
    long before = System.currentTimeMillis();
    // This source has a real Player config but omits phase. No fictitious1408 payment is seeded:
    // the actual unprepaid base1460 compatibility path remains the control until native default is audited.
    assertEquals(0, code(named(BASE_USE, BASE, 1, new int[] {0})));
    long after = System.currentTimeMillis();
    assertTrue(casts.base.actor.getBuffs().containsKey(140704), "True original base creates its source buff");
    long expiry = casts.base.actor.getBuffs().get(140704);
    assertTrue(expiry >= before + 20000 && expiry <= after + 20000,
        "Complete originalPlayer ReadInfo and140707 param2 declare20seconds");
    assertEquals(4, casts.mp());
    assertEquals(1, notices());
    var origin = casts.session.getPlayer().getGameplay().getMazeCasts().find(casts.session, BASE_USE);
    assertNotNull(origin);
    assertTrue(origin.released());
    assertFalse(origin.prepaid());
  }

  @Test void wholeOriginalSourceClosesBaseWaitInputAndDirectAttackAndCleanup() throws Exception {
    var local = JsonParser.parseString(Files.readString(root.resolve(
        "Config/ConfigCharacter/LocalPlayer/LocalPlayer_Castorice_00_Config.json"))).getAsJsonObject();
    var read = MazeManualAbility46Test.at(local, "/DynamicValues/Floats/-1733965089/ReadInfo").getAsJsonObject();
    assertEquals(JsonParser.parseString("{\"Type\":\"SkillParam\",\"TriggerKey\":\"SkillMaze\",\"Index\":2}"), read);
    assertTrue(local.getAsJsonArray("AbilityList").asList().stream().anyMatch(v -> v.getAsString().equals(INPUT)));
    var input = MazeManualAbility46Test.at(source, "/AbilityList/3").getAsJsonObject();
    assertEquals("Free", input.get("ManualTriggerType").getAsString());
    var wait = MazeManualAbility46Test.at(source,
        "/GlobalModifiers/ADV_StageAbility_Maze_Castorice_Radius/ModifierTaskList/0").getAsJsonObject();
    assertTrue(wait.get("CheckAllowUseSkill").getAsBoolean());
    assertEquals(INPUT, MazeManualAbility46Test.at(wait, "/OnInput/0/AbilityName").getAsString());
    assertEquals("MazeSkill", MazeManualAbility46Test.at(wait, "/OnInput/0/SkillType").getAsString());
    var attack = MazeManualAbility46Test.at(input, "/OnStart/14").getAsJsonObject();
    assertEquals("RPG.GameCore.AdventureTriggerAttack", attack.get("$type").getAsString());
    assertFalse(attack.has("CostMP"));
    assertFalse(attack.has("CostMpPhaseType"));
    assertEquals(140701, MazeManualAbility46Test.at(attack, "/OnBattle/4/ID").getAsInt());
    assertEquals(140704, MazeManualAbility46Test.at(input, "/OnStart/18/ID").getAsInt());
    assertFalse(fixture.get("originalAttackHasProjectile").getAsBoolean());
    assertEquals(1, row("MazeSkill", "MazeSkillId", 140702).get("MPCost").getAsInt());
  }

  @Test void actualBase1460KeepsItsSourceDurationAndCompatibilityPayment() throws Exception { activate(); }

  @ParameterizedTest @ValueSource(ints = {0, 4})
  void actualFreeDirectInputDoesNotChargeAndDefersItsOnBattleBeforeTheSnapshot(int mp) throws Exception {
    activate();
    casts.session.getPlayer().getCurrentLineup().setMp(mp); // Explicit unit boundary, not Native seeding.
    var target = casts.base.monster("[]");
    var response = named(CHILD_USE, INPUT, 1, new int[] {1, 2}, target);
    assertEquals(0, code(response), "Original Free direct Input must reach Battle46");
    assertEquals(CHILD_USE, MazeCastSequence46Test.number(response, "cast_entity_id"));
    assertEquals(mp, casts.mp());
    assertEquals(1, notices());
    assertEquals(List.of(target), casts.session.getPlayer().getBattle().getNpcMonsters());
    var battle = (DynamicMessage) response.getField(response.getDescriptorForType().findFieldByName("battle_info"));
    @SuppressWarnings("unchecked")
    var buffs = (List<DynamicMessage>) battle.getField(battle.getDescriptorForType().findFieldByName("buff_list"));
    var ids = buffs.stream().map(v -> MazeCastSequence46Test.number(v, "id")).toList();
    assertTrue(ids.contains(140701), "The exact original OnBattle buff is collected at real admission");
    assertFalse(ids.contains(140704), "The original Input removes its radius buff after the Attack task");
    assertFalse(casts.base.actor.getBuffs().containsKey(140704));
  }

  @Test void aSuccessfulEmptyInputStillExecutesItsOriginalSourceBuffRemovalOnce() throws Exception {
    activate();
    assertEquals(0, code(named(CHILD_USE, INPUT, 1, new int[] {1, 2})));
    assertNull(casts.session.getPlayer().getBattle());
    assertEquals(4, casts.mp());
    assertEquals(1, notices());
    assertFalse(casts.base.actor.getBuffs().containsKey(140704));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), code(named(CHILD_USE + 1, INPUT, 1, new int[] {1, 2})));
  }

  @Test void bothActualNpcTargetsEnterWithoutManufacturingAnotherMpCost() throws Exception {
    activate();
    var first = casts.base.monster("[]");
    var second = casts.base.monster("[]");
    assertEquals(0, code(named(CHILD_USE, INPUT, 1, new int[] {1, 2}, first, second)));
    assertEquals(List.of(first, second), casts.session.getPlayer().getBattle().getNpcMonsters());
    assertEquals(4, casts.mp());
    assertEquals(1, notices());
  }

  static List<int[]> unprovenEvents() {
    return List.of(new int[] {1}, new int[] {2}, new int[] {2, 1}, new int[] {1, 1},
        new int[] {99}, new int[] {0, 2});
  }

  @ParameterizedTest @MethodSource("unprovenEvents")
  void aDirectSourceDoesNotInventASingleProjectileOrMalformedEvent(int[] tags) throws Exception {
    activate();
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), code(named(CHILD_USE, INPUT, 1, tags)));
    assertTrue(casts.base.actor.getBuffs().containsKey(140704));
    assertEquals(4, casts.mp());
    assertEquals(1, notices());
    assertNull(casts.session.getPlayer().getBattle());
  }

  @Test void anInvalidEventBeforeTheLegalDirectInputCannotPoisonItsId() throws Exception {
    activate();
    assertEquals(5, code(named(CHILD_USE, INPUT, 1, new int[] {99})));
    assertEquals(0, code(named(CHILD_USE, INPUT, 1, new int[] {1, 2})));
    assertEquals(4, casts.mp());
  }

  @Test void aFailedRealStageAdmissionPreservesTheOriginalOriginForSameIdRetry() throws Exception {
    activate();
    long expiry = casts.base.actor.getBuffs().get(140704);
    var target = casts.base.monster("[]");
    var stage = target.getCustomStage();
    int event = target.getEventId();
    target.setCustomStage((emu.lunarcore.game.battle.BattleStage) null);
    target.setEventId(199997301); // Explicit missing event fixture, not a fabricated sourceStage.
    assertNotEquals(0, code(named(CHILD_USE, INPUT, 1, new int[] {1, 2}, target)));
    assertEquals(expiry, casts.base.actor.getBuffs().get(140704));
    assertTrue(target.isAlive());
    assertNull(casts.session.getPlayer().getBattle());
    assertEquals(4, casts.mp());
    target.setCustomStage(stage);
    target.setEventId(event);
    assertEquals(0, code(named(CHILD_USE, INPUT, 1, new int[] {1, 2}, target)));
    assertFalse(casts.base.actor.getBuffs().containsKey(140704));
    assertEquals(4, casts.mp());
    assertEquals(1, notices());
  }

  @Test void aConsumedDirectChildIdCannotBorrowAnotherRealBaseRelease() throws Exception {
    activate();
    assertEquals(0, code(named(CHILD_USE, INPUT, 1, new int[] {1, 2})));
    assertEquals(0, code(named(BASE_USE + 10, BASE, 1, new int[] {0})));
    assertTrue(casts.base.actor.getBuffs().containsKey(140704));
    long expiry = casts.base.actor.getBuffs().get(140704);
    assertEquals(5, code(named(CHILD_USE, INPUT, 1, new int[] {1, 2})));
    assertEquals(expiry, casts.base.actor.getBuffs().get(140704));
    assertEquals(0, code(named(CHILD_USE + 11, INPUT, 1, new int[] {1, 2})));
    assertEquals(3, casts.mp());
    assertEquals(2, notices());
  }

  @Test void aLiveBuffAloneIsNotARealBaseRelease() throws Exception {
    casts.base.actor.addBuff(140704, 20);
    assertEquals(5, code(named(CHILD_USE, INPUT, 1, new int[] {1, 2})));
    assertEquals(5, casts.mp());
    assertEquals(0, notices());
  }

  @Test void anotherSessionCannotBorrowTheCurrentActorAndOriginalBuff() throws Exception {
    activate();
    casts.session = casts.new Session();
    casts.session.getPlayer().setSession(casts.session);
    assertEquals(5, code(named(CHILD_USE, INPUT, 1, new int[] {1, 2})));
    assertEquals(4, casts.mp());
    assertTrue(casts.base.actor.getBuffs().containsKey(140704));
  }

  @Test void expiredSourceBuffDoesNotRemainAFreePermission() throws Exception {
    activate();
    casts.base.actor.getBuffs().put(140704, System.currentTimeMillis() - 1);
    assertEquals(5, code(named(CHILD_USE, INPUT, 1, new int[] {1, 2})));
    assertEquals(4, casts.mp());
  }

  @ParameterizedTest @ValueSource(strings = {"LocalPlayer_Castorice_MazeSkill_Out", "unknown_Input"})
  void sourceInputAuthorizationDoesNotOpenOutOrUnknownAbilities(String name) throws Exception {
    activate();
    assertEquals(5, code(named(CHILD_USE, name, 1, new int[] {1, 2})));
    assertTrue(casts.base.actor.getBuffs().containsKey(140704));
    assertEquals(4, casts.mp());
  }

  @ParameterizedTest @ValueSource(ints = {0, -1, BASE_USE})
  void aChildRequiresItsOwnValidNewAbilityUseId(int use) throws Exception {
    activate();
    assertEquals(5, code(named(use, INPUT, 1, new int[] {1, 2})));
    assertTrue(casts.base.actor.getBuffs().containsKey(140704));
    assertEquals(4, casts.mp());
  }

  @ParameterizedTest @ValueSource(strings = {
      ABILITY,
      "Config/ConfigCharacter/LocalPlayer/LocalPlayer_Castorice_00_Config.json",
      "ExcelOutput/AvatarConfig.json", "ExcelOutput/AdventurePlayer.json",
      "ExcelOutput/MazeSkill.json", "ExcelOutput/AvatarMazeBuff.json",
      "ExcelOutput/AvatarSkillConfig.json", "ExcelOutput/AvatarSkillConfigLD.json"})
  void changedOrMissingWholeOriginalCannotBorrowTheLoadedInputAndCanRetryAfterRestore(String path) throws Exception {
    activate();
    byte[] original = Files.readAllBytes(root.resolve(path));
    Files.delete(root.resolve(path));
    assertEquals(5, code(named(CHILD_USE, INPUT, 1, new int[] {1, 2})));
    assertTrue(casts.base.actor.getBuffs().containsKey(140704));
    byte[] altered = Arrays.copyOf(original, original.length + 1);
    altered[altered.length - 1] = ' '; // Still valid JSON; the complete original blob changed.
    Files.write(root.resolve(path), altered);
    assertEquals(5, code(named(CHILD_USE, INPUT, 1, new int[] {1, 2})));
    assertTrue(casts.base.actor.getBuffs().containsKey(140704));
    Files.write(root.resolve(path), original);
    assertEquals(0, code(named(CHILD_USE, INPUT, 1, new int[] {1, 2})));
    assertEquals(4, casts.mp());
    assertEquals(1, notices());
  }

  @Test void changedLoadedSourceSkillParameterCannotAuthorizeAnUnrelatedLifetime() throws Exception {
    activate();
    var original = row("AvatarSkillConfig", "SkillID", 140707);
    var changed = original.deepCopy();
    changed.getAsJsonArray("ParamList").get(2).getAsJsonObject().addProperty("Value", 30);
    GameData.getAvatarSkillParameterExcelMap().add(ResourceLoader.getGson().fromJson(changed, AvatarSkillParameterExcel.class));
    assertEquals(5, code(named(CHILD_USE, INPUT, 1, new int[] {1, 2})));
    assertTrue(casts.base.actor.getBuffs().containsKey(140704));
    GameData.getAvatarSkillParameterExcelMap().add(ResourceLoader.getGson().fromJson(original, AvatarSkillParameterExcel.class));
    assertEquals(0, code(named(CHILD_USE, INPUT, 1, new int[] {1, 2})));
  }

  @ParameterizedTest @ValueSource(ints = {140701, 140704})
  void changedLoadedBuffBindingCannotAuthorizeOrSnapshotAnotherEffect(int buff) throws Exception {
    activate();
    var original = row("AvatarMazeBuff", "ID", buff);
    var changed = original.deepCopy();
    changed.addProperty("UseType", "AddBattleBuff");
    GameData.getMazeBuffExcelMap().add(ResourceLoader.getGson().fromJson(changed, MazeBuffExcel.class));
    assertEquals(5, code(named(CHILD_USE, INPUT, 1, new int[] {1, 2})));
    assertTrue(casts.base.actor.getBuffs().containsKey(140704));
    GameData.getMazeBuffExcelMap().add(ResourceLoader.getGson().fromJson(original, MazeBuffExcel.class));
    assertEquals(0, code(named(CHILD_USE, INPUT, 1, new int[] {1, 2})));
  }

  @Test void aDifferentActualLivingCasterCannotCopyTheSourceBuffAndOrigin() throws Exception {
    activate();
    var player = casts.session.getPlayer();
    var previous = casts.base.actor;
    var other = new GameAvatar(previous.getExcel()) { @Override public void save() {} };
    other.setOwner(player);
    var allocator = emu.lunarcore.game.scene.Scene.class.getDeclaredMethod("getNextEntityId");
    allocator.setAccessible(true);
    other.setEntityId((Integer) allocator.invoke(player.getScene()));
    other.getBuffs().put(140704, previous.getBuffs().get(140704)); // Explicit forged identity boundary.
    player.getAvatars().getAvatars().put(1407, other);
    player.getScene().getAvatars().put(1407, other);
    player.getScene().getAvatarEntityIds().remove(previous.getEntityId());
    player.getScene().getAvatarEntityIds().add(other.getEntityId());
    casts.base.actor = other;
    assertTrue(other.isAlive(player.getCurrentLineup()));
    assertSame(other, player.getScene().getAvatarByEntityId(other.getEntityId()));
    assertEquals(5, code(named(CHILD_USE, INPUT, 1, new int[] {1, 2})));
    assertEquals(4, casts.mp());
    assertTrue(previous.getBuffs().containsKey(140704));
  }

  @Test void anotherSceneWithTheSameFloorAndActorCannotBorrowTheSourceRelease() throws Exception {
    activate();
    var player = casts.session.getPlayer();
    var previous = player.getScene();
    var next = new emu.lunarcore.game.scene.Scene(player, previous.getExcel(), previous.getFloorId(), previous.getDimensionId());
    next.getAvatars().put(1407, casts.base.actor);
    next.getAvatarEntityIds().add(casts.base.actor.getEntityId());
    var field = emu.lunarcore.game.player.Player.class.getDeclaredField("scene");
    field.setAccessible(true);
    field.set(player, next);
    assertSame(casts.base.actor, next.getAvatarByEntityId(casts.base.actor.getEntityId()));
    assertEquals(previous.getFloorId(), next.getFloorId());
    assertEquals(5, code(named(CHILD_USE, INPUT, 1, new int[] {1, 2})));
    assertTrue(casts.base.actor.getBuffs().containsKey(140704));
  }

  @ParameterizedTest @ValueSource(strings = {"removed", "refreshed", "unheld"})
  void removedRefreshedOrUnheldSourceCannotBorrowTheCapturedPermission(String condition) throws Exception {
    activate();
    switch (condition) {
      case "removed" -> casts.base.actor.getBuffs().remove(140704);
      case "refreshed" -> casts.base.actor.getBuffs().put(140704, casts.base.actor.getBuffs().get(140704) + 5000);
      case "unheld" -> casts.session.getPlayer().getCurrentLineup().getAvatars().clear();
    }
    assertEquals(5, code(named(CHILD_USE, INPUT, 1, new int[] {1, 2})));
    assertEquals(4, casts.mp());
    assertNull(casts.session.getPlayer().getBattle());
  }

  @Test void theSourceMazeSkillInputCannotMoveToTheOrdinarySlot() throws Exception {
    activate();
    assertEquals(5, code(named(CHILD_USE, INPUT, 0, new int[] {1, 2})));
    assertTrue(casts.base.actor.getBuffs().containsKey(140704));
    assertEquals(0, code(named(CHILD_USE, INPUT, 1, new int[] {1, 2})));
  }
}
