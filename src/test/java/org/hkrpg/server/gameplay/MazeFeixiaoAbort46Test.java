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

/** Complete1220 base and original normal-abort cleanup through the actual native ingress. */
class MazeFeixiaoAbort46Test {
  static final String BASE = "LocalPlayer_Feixiao_00_MazeSkill";
  static final String CLEANUP = "LocalPlayer_Feixiao_00_NormalAtk01_RemoveMazebuff";
  static final String NORMAL = "LocalPlayer_Feixiao_00_NormalAtk01";
  static final String ABILITY = "Config/ConfigAdventureAbility/LocalPlayer/LocalPlayer_Feixiao_00_Ability.json";
  static final int BASE_USE = 92200, CHILD_USE = 92201;
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
    var configured = System.getProperty("feixiao.originalFixture");
    String fixtureText;
    if (configured != null) fixtureText = Files.readString(Path.of(configured));
    else try (var stream = getClass().getResourceAsStream("/fixtures/maze-feixiao-abort-4.6.json")) {
      assertNotNull(stream, "Complete original Feixiao source fixture must be present");
      fixtureText = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    fixture = JsonParser.parseString(fixtureText).getAsJsonObject();
    assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b", fixture.get("sourceCommit").getAsString());
    assertEquals(14, fixture.getAsJsonObject("sourceFiles").size());
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
    var excel = ResourceLoader.getGson().fromJson(row("AvatarConfig", "AvatarID", 1220), AvatarExcel.class);
    source = JsonParser.parseString(Files.readString(root.resolve(ABILITY))).getAsJsonObject();
    casts.phases.previous.putIfAbsent(1220, GameData.getAvatarExcelMap().get(1220));
    assertTrue(ResourceLoader.getGson().fromJson(source, SkillAbilityInfo.class).parse(excel));
    assertEquals(BASE, excel.getMazeSkill().getAbilityName());
    GameData.getAvatarExcelMap().add(excel);
    GameData.getAvatarSkillParameterExcelMap().add(ResourceLoader.getGson().fromJson(
        row("AvatarSkillConfig", "SkillID", 122007), AvatarSkillParameterExcel.class));
    for (int id : new int[] {122001, 122002}) GameData.getMazeBuffExcelMap().add(
        ResourceLoader.getGson().fromJson(row("AvatarMazeBuff", "ID", id), MazeBuffExcel.class));
    var player = casts.session.getPlayer();
    int entity = casts.base.actor.getEntityId();
    casts.base.actor = new GameAvatar(excel) { @Override public void save() {} };
    casts.base.actor.setOwner(player);
    casts.base.actor.setEntityId(entity);
    player.getAvatars().getAvatars().put(1220, casts.base.actor);
    player.getCurrentLineup().getAvatars().clear();
    player.getCurrentLineup().getAvatars().add(1220);
    player.getCurrentLineup().setMp(5);
    player.getScene().getAvatars().clear();
    player.getScene().getAvatars().put(1220, casts.base.actor);
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

  void submitBase(int use) throws Exception {
    assertEquals(0, code(named(use, BASE, 1, new int[] {1, 2})));
    var binding = casts.session.getPlayer().getGameplay().getMazeCasts().find(casts.session, use);
    assertNotNull(binding);
    assertTrue(binding.released());
    assertFalse(binding.prepaid(), "No fake1408 prepayment is seeded for an omitted original phase");
  }

  DynamicMessage cleanup(int use) throws Exception { return named(use, CLEANUP, 0, new int[] {1, 2}); }

  @Test void completeOriginalSourceClosesTheActualBaseSubmitAndNormalAbortCleanup() throws Exception {
    var local = JsonParser.parseString(Files.readString(root.resolve(
        "Config/ConfigCharacter/LocalPlayer/LocalPlayer_Feixiao_00_Config.json")));
    assertTrue(MazeManualAbility46Test.at(local, "/SkillList/1/SubmitSkillWhenCast").getAsBoolean());
    assertEquals(BASE, MazeManualAbility46Test.at(local, "/SkillList/1/EntryAbility").getAsString());
    assertFalse(MazeManualAbility46Test.at(local, "/SkillList/1").getAsJsonObject().has("SkillCostMpPhase"));
    assertEquals(JsonParser.parseString("{\"Type\":\"SkillParam\",\"TriggerKey\":\"SkillMaze\",\"Index\":0}"),
        MazeManualAbility46Test.at(local, "/DynamicValues/Floats/692437857/ReadInfo"));
    assertEquals(20, MazeManualAbility46Test.at(row("AvatarSkillConfig", "SkillID", 122007), "/ParamList/0/Value").getAsInt());
    assertEquals(CLEANUP, MazeManualAbility46Test.at(source, "/AbilityList/0/OnAbort/0/AbilityName").getAsString());
    assertFalse(MazeManualAbility46Test.at(source, "/AbilityList/0/OnAbort/0").getAsJsonObject().has("SkillType"));
    assertEquals("Free", MazeManualAbility46Test.at(source, "/AbilityList/1/ManualTriggerType").getAsString());
    assertEquals(122001, MazeManualAbility46Test.at(source, "/AbilityList/1/OnStart/0/ID").getAsInt());
    assertEquals("RPG.GameCore.AdventureSubmitSkill", MazeManualAbility46Test.at(source, "/AbilityList/1/OnStart/1/$type").getAsString());
    assertEquals(2, MazeManualAbility46Test.at(source, "/AbilityList/1/OnStart").getAsJsonArray().size());
  }

  @Test void actualOriginalBaseCreatesItsDeclaredTwentySecondCasterBuff() throws Exception {
    long before = System.currentTimeMillis();
    submitBase(BASE_USE);
    long after = System.currentTimeMillis();
    assertTrue(casts.base.actor.getBuffs().containsKey(122001), "Actual original base must grant its dynamic20s buff");
    long expiry = casts.base.actor.getBuffs().get(122001);
    assertTrue(expiry >= before + 20_000 && expiry <= after + 20_000);
    assertEquals(4, casts.mp());
    assertEquals(1, notices());
  }

  @Test void originalBaseCompatibilityCostAndReceiptRemainDistinctFromNativePrepayment() throws Exception {
    submitBase(BASE_USE);
    assertEquals(4, casts.mp());
    assertEquals(1, notices());
  }

  @Test void actualNormalAbortFreeCleanupRemovesItsSourceWithoutAnotherMpOrBattle() throws Exception {
    submitBase(BASE_USE);
    int before = notices();
    assertEquals(0, code(cleanup(CHILD_USE)));
    assertFalse(casts.base.actor.getBuffs().containsKey(122001));
    assertEquals(4, casts.mp());
    assertEquals(before, notices());
    assertNull(casts.session.getPlayer().getBattle());
  }

  @Test void originalFreeCleanupStillWorksAtZeroMp() throws Exception {
    submitBase(BASE_USE);
    casts.session.getPlayer().getCurrentLineup().setMp(0);
    int before = notices();
    assertEquals(0, code(cleanup(CHILD_USE)));
    assertEquals(0, casts.mp());
    assertEquals(before, notices());
  }

  @Test void repeatedCleanupCannotBorrowTheSameOriginTwice() throws Exception {
    submitBase(BASE_USE);
    assertEquals(0, code(cleanup(CHILD_USE)));
    assertEquals(5, code(cleanup(CHILD_USE)));
    assertEquals(5, code(cleanup(CHILD_USE + 1)));
    assertEquals(1, notices());
  }

  @Test void aNewActualBaseAllowsANewCleanupButNeverReusesAnAcceptedOldUse() throws Exception {
    submitBase(BASE_USE);
    assertEquals(0, code(cleanup(CHILD_USE)));
    submitBase(BASE_USE + 10);
    assertEquals(5, code(cleanup(CHILD_USE)));
    assertTrue(casts.base.actor.getBuffs().containsKey(122001));
    assertEquals(0, code(cleanup(CHILD_USE + 1)));
    assertFalse(casts.base.actor.getBuffs().containsKey(122001));
    assertEquals(3, casts.mp());
    assertEquals(2, notices());
  }

  @Test void noActualBaseCannotAuthorizeTheFiniteCleanup() throws Exception {
    assertEquals(5, code(cleanup(CHILD_USE)));
    assertEquals(5, casts.mp());
    assertEquals(0, notices());
  }

  static int[][] unprovenTags() { return new int[][] {{}, {0}, {1}, {2}, {2, 1}, {1, 1}, {2, 2}, {99}}; }
  @ParameterizedTest @MethodSource("unprovenTags")
  void unprovenTagPatternsCannotExecuteTheCleanup(int[] tags) throws Exception {
    submitBase(BASE_USE);
    var buffs = new HashMap<>(casts.base.actor.getBuffs());
    assertEquals(5, code(named(CHILD_USE, CLEANUP, 0, tags)));
    assertEquals(buffs, new HashMap<>(casts.base.actor.getBuffs()));
    assertEquals(4, casts.mp());
    assertEquals(1, notices());
  }

  @Test void sourceNormalSlotAndBaseUseIdentityRejectWithoutPoisoningTheSameNewUse() throws Exception {
    submitBase(BASE_USE);
    assertEquals(5, code(named(CHILD_USE, CLEANUP, 1, new int[] {1, 2})));
    assertEquals(5, code(cleanup(BASE_USE)));
    assertEquals(0, code(cleanup(CHILD_USE)));
    assertEquals(4, casts.mp());
  }

  @Test void targetBearingCleanupCannotDamageOrEnterBattleAndTheSameEmptyUseCanRetry() throws Exception {
    submitBase(BASE_USE);
    var target = casts.base.monster("[]");
    assertEquals(5, code(named(CHILD_USE, CLEANUP, 0, new int[] {1, 2}, target)));
    assertSame(target, casts.session.getPlayer().getScene().getEntities().get(target.getEntityId()));
    assertTrue(target.isAlive());
    assertNull(casts.session.getPlayer().getBattle());
    assertEquals(0, code(cleanup(CHILD_USE)));
  }

  @ParameterizedTest @ValueSource(strings = {
      ABILITY, "Config/ConfigCharacter/LocalPlayer/LocalPlayer_Feixiao_00_Config.json",
      "ExcelOutput/AvatarConfig.json", "ExcelOutput/AdventurePlayer.json", "ExcelOutput/MazeSkill.json",
      "ExcelOutput/AvatarMazeBuff.json", "ExcelOutput/AvatarSkillConfig.json", "ExcelOutput/AvatarSkillConfigLD.json"})
  void missingAndChangedWholeOriginalsRejectThenAllowTheSameUseAfterExactRestore(String path) throws Exception {
    submitBase(BASE_USE);
    var file = root.resolve(path);
    byte[] original = Files.readAllBytes(file);
    Files.delete(file);
    assertEquals(5, code(cleanup(CHILD_USE)));
    Files.writeString(file, "{}");
    assertEquals(5, code(cleanup(CHILD_USE)));
    Files.write(file, original);
    assertEquals(0, code(cleanup(CHILD_USE)));
  }

  @Test void aNewSessionCannotBorrowThePriorSessionsActualBase() throws Exception {
    submitBase(BASE_USE);
    casts.session = casts.new Session();
    casts.session.getPlayer().setSession(casts.session);
    assertEquals(5, code(cleanup(CHILD_USE)));
    assertEquals(4, casts.mp());
  }

  @Test void sourceExpiryIsRequiredByTheFiniteOriginAdmission() throws Exception {
    submitBase(BASE_USE);
    casts.base.actor.getBuffs().put(122001, System.currentTimeMillis() - 1);
    assertEquals(5, code(cleanup(CHILD_USE)));
    assertEquals(4, casts.mp());
  }

  @ParameterizedTest @ValueSource(ints = {0, -1})
  void nativeZeroAndAllOnesUseIdAreNotAuthorized(int use) throws Exception {
    submitBase(BASE_USE);
    assertEquals(5, code(cleanup(use)));
    assertEquals(4, casts.mp());
  }

  @Test void anActualLoadedCleanupForAnotherBuffCannotBorrowTheOriginalName() throws Exception {
    submitBase(BASE_USE);
    var cleanup = casts.base.actor.getExcel().getMazeAttack().manualAbility(CLEANUP);
    assertNotNull(cleanup, "The positive source-linked cleanup must actually be loaded");
    var original = cleanup.getCastActions().getFirst();
    cleanup.getCastActions().set(0, new emu.lunarcore.game.battle.skills.MazeSkillRemoveBuff(19996301));
    assertEquals(5, code(cleanup(CHILD_USE)), "Whole raw sources cannot authorize a differently loaded Remove action");
    assertTrue(casts.base.actor.getBuffs().containsKey(122001));
    cleanup.getCastActions().set(0, original);
    assertEquals(0, code(cleanup(CHILD_USE)));
  }

  @Test void changedLoadedBuffUseTypeCannotBorrowTheOriginalSourceBinding() throws Exception {
    submitBase(BASE_USE);
    var original = row("AvatarMazeBuff", "ID", 122001);
    var changed = original.deepCopy();
    changed.addProperty("UseType", "TriggerBattle");
    GameData.getMazeBuffExcelMap().add(ResourceLoader.getGson().fromJson(changed, MazeBuffExcel.class));
    assertEquals(5, code(cleanup(CHILD_USE)));
    assertTrue(casts.base.actor.getBuffs().containsKey(122001));
    GameData.getMazeBuffExcelMap().add(ResourceLoader.getGson().fromJson(original, MazeBuffExcel.class));
    assertEquals(0, code(cleanup(CHILD_USE)));
  }

  @Test void changedLoadedParameterCannotTurnAnotherDurationIntoTheSourcePermission() throws Exception {
    submitBase(BASE_USE);
    var original = row("AvatarSkillConfig", "SkillID", 122007);
    var changed = original.deepCopy();
    changed.getAsJsonArray("ParamList").get(0).getAsJsonObject().addProperty("Value", 30);
    GameData.getAvatarSkillParameterExcelMap().add(ResourceLoader.getGson().fromJson(changed, AvatarSkillParameterExcel.class));
    assertEquals(5, code(cleanup(CHILD_USE)));
    GameData.getAvatarSkillParameterExcelMap().add(ResourceLoader.getGson().fromJson(original, AvatarSkillParameterExcel.class));
    assertEquals(0, code(cleanup(CHILD_USE)));
  }

  @Test void anotherSceneWithTheSameFloorAndCasterCannotBorrowTheBase() throws Exception {
    submitBase(BASE_USE);
    var player = casts.session.getPlayer();
    var old = player.getScene();
    var next = new emu.lunarcore.game.scene.Scene(player, old.getExcel(), old.getFloorId(), old.getDimensionId());
    next.getAvatars().put(1220, casts.base.actor);
    next.getAvatarEntityIds().add(casts.base.actor.getEntityId());
    var field = emu.lunarcore.game.player.Player.class.getDeclaredField("scene");
    field.setAccessible(true);
    field.set(player, next);
    assertSame(casts.base.actor, next.getAvatarByEntityId(casts.base.actor.getEntityId()));
    assertEquals(5, code(cleanup(CHILD_USE)));
    assertTrue(casts.base.actor.getBuffs().containsKey(122001));
    assertEquals(4, casts.mp());
  }

  @Test void anotherActualLivingCasterCannotCopyTheBaseBuffAndIdentity() throws Exception {
    submitBase(BASE_USE);
    var player = casts.session.getPlayer();
    var previous = casts.base.actor;
    var other = new GameAvatar(previous.getExcel()) { @Override public void save() {} };
    other.setOwner(player);
    var allocator = emu.lunarcore.game.scene.Scene.class.getDeclaredMethod("getNextEntityId");
    allocator.setAccessible(true);
    other.setEntityId((Integer) allocator.invoke(player.getScene()));
    other.getBuffs().put(122001, previous.getBuffs().get(122001)); // Explicit forged-state boundary only.
    player.getAvatars().getAvatars().put(1220, other);
    player.getScene().getAvatars().put(1220, other);
    player.getScene().getAvatarEntityIds().remove(previous.getEntityId());
    player.getScene().getAvatarEntityIds().add(other.getEntityId());
    casts.base.actor = other;
    assertTrue(other.isAlive(player.getCurrentLineup()));
    assertSame(other, player.getScene().getAvatarByEntityId(other.getEntityId()));
    assertEquals(5, code(cleanup(CHILD_USE)));
    assertTrue(previous.getBuffs().containsKey(122001));
  }

  @Test void sourceCleanupPreservesAnExplicitUnrelatedCasterBuff() throws Exception {
    submitBase(BASE_USE);
    casts.base.actor.addBuff(19996301, 30); // Local unrelated marker, not an original grant claim.
    long marker = casts.base.actor.getBuffs().get(19996301);
    assertEquals(0, code(cleanup(CHILD_USE)));
    assertEquals(marker, casts.base.actor.getBuffs().get(19996301));
    assertFalse(casts.base.actor.getBuffs().containsKey(122001));
  }
}

