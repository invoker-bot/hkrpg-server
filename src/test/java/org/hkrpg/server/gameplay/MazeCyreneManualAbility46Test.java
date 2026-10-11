package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.SkillAbilityInfo;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.BattleStage;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original Free NormalAtk02 END through actual Battle46; no client input/physics simulation. */
class MazeCyreneManualAbility46Test {
  static final String BASE = "LocalPlayer_Cyrene_00_MazeSkill";
  static final String INPUT = "LocalPlayer_Cyrene_00_NormalAtk02";
  static final String ABILITY = "Config/ConfigAdventureAbility/LocalPlayer/LocalPlayer_Cyrene_00_Ability.json";
  @TempDir Path root;
  final MazeCastSequence46Test casts = new MazeCastSequence46Test();
  JsonObject source;

  @BeforeEach
  void setup() throws Exception {
    casts.root = root;
    casts.setup();
    JsonObject fixture;
    try (var input = getClass().getResourceAsStream("/fixtures/maze-cyrene-manual-ability-4.6.json")) {
      assertNotNull(input);
      fixture = JsonParser.parseString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
    }
    assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b", fixture.get("sourceCommit").getAsString());
    for (var entry : fixture.getAsJsonObject("sourceFiles").entrySet()) {
      byte[] raw = Base64.getDecoder().decode(entry.getValue().getAsJsonObject().get("rawBase64").getAsString());
      assertEquals(entry.getValue().getAsJsonObject().get("blobSHA256").getAsString(), MazeManualAbility46Test.sha(raw));
      var file = root.resolve(entry.getKey());
      Files.createDirectories(file.getParent());
      Files.write(file, raw);
      assertTrue(file.toRealPath().startsWith(root.toRealPath()));
    }
    var avatars = JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/AvatarConfig.json"))).getAsJsonArray();
    var row = avatars.asList().stream().map(JsonElement::getAsJsonObject)
        .filter(v -> v.get("AvatarID").getAsInt() == 1415).findFirst().orElseThrow();
    casts.phases.previous.putIfAbsent(1415, GameData.getAvatarExcelMap().get(1415));
    var excel = ResourceLoader.getGson().fromJson(row, AvatarExcel.class);
    source = JsonParser.parseString(Files.readString(root.resolve(ABILITY))).getAsJsonObject();
    assertTrue(ResourceLoader.getGson().fromJson(source, SkillAbilityInfo.class).parse(excel));
    assertEquals(BASE, excel.getMazeSkill().getAbilityName());
    GameData.getAvatarExcelMap().add(excel);
    var player = casts.session.getPlayer();
    int id = casts.base.actor.getEntityId();
    casts.base.actor = new GameAvatar(excel) { @Override public void save() {} };
    casts.base.actor.setOwner(player);
    casts.base.actor.setEntityId(id);
    player.getAvatars().getAvatars().put(1415, casts.base.actor);
    player.getCurrentLineup().getAvatars().clear();
    player.getCurrentLineup().getAvatars().add(1415);
    player.getCurrentLineup().setMp(5);
    player.getScene().getAvatars().clear();
    player.getScene().getAvatars().put(1415, casts.base.actor);
    player.getScene().getAvatarEntityIds().clear();
    player.getScene().getAvatarEntityIds().add(id);
  }

  @AfterEach void cleanup() throws Exception { casts.restore(); }

  DynamicMessage named(int use, String name, int index, int tag, EntityMonster... targets) throws Exception {
    var request = DynamicMessage.newBuilder(casts.base.protocol.clientSchema().message("SceneCastSkillCsReq"));
    var type = request.getDescriptorForType();
    request.setField(type.findFieldByName("cast_entity_id"), use);
    request.setField(type.findFieldByName("attacked_by_entity_id"), casts.base.actor.getEntityId());
    request.setField(type.findFieldByName("maze_ability_str"), name);
    request.setField(type.findFieldByName("skill_index"), index);
    var tags = type.findFieldByName("skill_extra_tags");
    request.addRepeatedField(tags, tags.getEnumType().findValueByNumberCreatingIfUnknown(tag));
    for (var target : targets) request.addRepeatedField(type.findFieldByName("hit_target_entity_id_list"), target.getEntityId());
    return casts.dispatch("SceneCastSkillCsReq", request.build());
  }

  int code(DynamicMessage value) { return MazeCastSequence46Test.number(value, "retcode"); }
  int notices() { return casts.session.messages.getOrDefault("SceneCastSkillMpUpdateScNotify", List.of()).size(); }

  void activate() throws Exception {
    // Source AdventureSubmitSkill emits the base1460. This compatibility fixture sends no1408,
    // because the absent original Character config does not prove its base payment phase.
    long before = System.currentTimeMillis();
    assertEquals(0, code(named(81400, BASE, 1, 0)));
    long after = System.currentTimeMillis();
    long expiry = casts.base.actor.getBuffs().get(141501);
    assertTrue(expiry >= before + 30000 && expiry <= after + 30000, "The real source buff lifetime is30seconds");
    assertEquals(4, casts.mp(), "Existing unprepaid base1460 compatibility consumes one point");
    assertEquals(1, notices());
    var origin = casts.session.getPlayer().getGameplay().getMazeCasts().find(casts.session, 81400);
    assertNotNull(origin);
    assertTrue(origin.released());
    assertFalse(origin.prepaid(), "No fictitious OnUse receipt is seeded");
  }

  @Test void originalSourceRetainsItsExactFreeLinkBuffAndZeroCostNormalRow() throws Exception {
    var manual = MazeManualAbility46Test.at(source, "/AbilityList/1").getAsJsonObject();
    assertEquals(INPUT, manual.get("Name").getAsString());
    assertEquals("Free", manual.get("ManualTriggerType").getAsString());
    var trigger = MazeManualAbility46Test.at(source,
        "/GlobalModifiers/ADV_StageAbility_Maze_Cyrene/ModifierTaskList/0/TaskList/0/OnInput/0").getAsJsonObject();
    assertEquals(INPUT, trigger.get("AbilityName").getAsString());
    assertFalse(trigger.has("SkillType"), "Native ctor and enum prove default NormalAtk0");
    assertEquals(141501, MazeManualAbility46Test.at(source, "/AbilityList/2/OnStart/11/ID").getAsInt());
    for (var branch : List.of("SuccessTaskList", "FailedTaskList")) {
      var projectile = MazeManualAbility46Test.at(source, "/AbilityList/1/OnStart/1/" + branch + "/7").getAsJsonObject();
      assertFalse(projectile.has("OnProjectileHit"));
      var attack = projectile.getAsJsonArray("OnProjectileLifetimeFinish").get(0).getAsJsonObject();
      assertEquals("RPG.GameCore.AdventureTriggerAttack", attack.get("$type").getAsString());
      assertFalse(attack.has("CostMP"));
      assertFalse(attack.has("CostMpPhaseType"));
    }
    var rows = JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/MazeSkill.json"))).getAsJsonArray();
    var normal = rows.asList().stream().map(JsonElement::getAsJsonObject)
        .filter(v -> v.get("MazeSkillId").getAsInt() == 141501).findFirst().orElseThrow();
    assertEquals(141506, normal.get("RelatedAvatarSkill").getAsInt());
    assertEquals(1, normal.get("MazeSkilltype").getAsInt());
    assertEquals("NormalAtk", normal.get("SkillTriggerKey").getAsString());
    assertFalse(normal.has("MPCost"));
  }

  @Test void originalBase1460ReallyReleasesTheBuffWithoutInventingPrepayment() throws Exception { activate(); }

  @ParameterizedTest @ValueSource(ints = {0, 4})
  void theActualFreeEndUsesNewUseIdAndHasNoOrdinaryMpCost(int mp) throws Exception {
    activate();
    casts.session.getPlayer().getCurrentLineup().setMp(mp); // Explicit boundary fixture, not network item use.
    var target = casts.base.monster("[]");
    var response = named(81401, INPUT, 0, 2, target);
    assertEquals(0, code(response), "Original Free NormalAtk02 END must reach native Battle46");
    assertEquals(81401, MazeCastSequence46Test.number(response, "cast_entity_id"));
    assertEquals(mp, casts.mp());
    assertEquals(1, notices());
    assertEquals(List.of(target), casts.session.getPlayer().getBattle().getNpcMonsters());
    MazeAttackResults46Test.outcome(MazeAttackResults46Test.rows(response).getFirst(), target.getEntityId(), 1);
  }

  @Test void multipleActualTargetsInOneEndDoNotManufactureAHitFee() throws Exception {
    activate();
    var first = casts.base.monster("[]"); var second = casts.base.monster("[]");
    assertEquals(0, code(named(81402, INPUT, 0, 2, first, second)));
    assertEquals(List.of(first, second), casts.session.getPlayer().getBattle().getNpcMonsters());
    assertEquals(4, casts.mp());
    assertEquals(1, notices());
  }

  @Test void anEmptyActualEndIsAcceptedWithoutStartingAnEncounterOrCharging() throws Exception {
    activate();
    assertEquals(0, code(named(81403, INPUT, 0, 2)));
    assertNull(casts.session.getPlayer().getBattle());
    assertEquals(4, casts.mp());
    assertEquals(1, notices());
  }

  @Test void anIllegalEventBeforeTheActualEndDoesNotPoisonItsUseId() throws Exception {
    activate(); var target = casts.base.monster("[]");
    assertEquals(5, code(named(81404, INPUT, 0, 99, target)));
    assertTrue(target.isAlive());
    assertEquals(0, code(named(81404, INPUT, 0, 2, target)));
    assertEquals(4, casts.mp());
  }

  @Test void aFailedRealStageAdmissionCanRetryTheSameEndAndRetainsTheSourceBuff() throws Exception {
    activate(); var target = casts.base.monster("[]");
    long expiry = casts.base.actor.getBuffs().get(141501);
    target.setCustomStage((BattleStage) null); target.setEventId(199996001);
    assertNotEquals(0, code(named(81405, INPUT, 0, 2, target)));
    assertNull(casts.session.getPlayer().getBattle());
    assertTrue(target.isAlive());
    assertEquals(expiry, casts.base.actor.getBuffs().get(141501));
    assertEquals(4, casts.mp()); assertEquals(1, notices());
    target.setCustomStage(SceneEventBattle46Test.STAGE);
    assertEquals(0, code(named(81405, INPUT, 0, 2, target)));
    assertNotNull(casts.session.getPlayer().getBattle());
  }

  @Test void aBaseUseIdCannotBeReassignedToItsFreeChildAbility() throws Exception {
    activate(); var target = casts.base.monster("[]");
    assertEquals(5, code(named(81400, INPUT, 0, 2, target)));
    assertNull(casts.session.getPlayer().getBattle());
    assertEquals(0, code(named(81406, INPUT, 0, 2, target)));
  }

  @Test void aSuccessfulEmptyChildBindingCannotLaterBeReassignedToTheCanonicalAttack() throws Exception {
    activate();
    assertEquals(0, code(named(81407, INPUT, 0, 2)));
    var target = casts.base.monster("[]");
    assertEquals(5, code(named(81407, "LocalPlayer_Cyrene_00_NormalAtk01", 0, 2, target)));
    assertTrue(target.isAlive());
    assertNull(casts.session.getPlayer().getBattle());
  }

  @ParameterizedTest @ValueSource(strings = {"notReleased", "expired", "removed", "newSession", "unheld"})
  void theChildCannotBorrowMissingExpiredOrForeignOrigin(String condition) throws Exception {
    if (!condition.equals("notReleased")) activate();
    switch (condition) {
      case "expired" -> casts.base.actor.getBuffs().put(141501, System.currentTimeMillis() - 1);
      case "removed" -> casts.base.actor.getBuffs().remove(141501);
      case "newSession" -> { casts.session = casts.new Session(); casts.session.getPlayer().setSession(casts.session); }
      case "unheld" -> casts.session.getPlayer().getCurrentLineup().getAvatars().clear();
    }
    var target = casts.base.monster("[]"); int mp = casts.mp();
    assertEquals(5, code(named(81408, INPUT, 0, 2, target)));
    assertTrue(target.isAlive()); assertEquals(mp, casts.mp());
    assertNull(casts.session.getPlayer().getBattle());
  }

  @ParameterizedTest @ValueSource(ints = {0, 1, 99})
  void nonEmittedTagsNeverBorrowTheEndCallback(int tag) throws Exception {
    activate(); var target = casts.base.monster("[]");
    assertEquals(5, code(named(81409, INPUT, 0, tag, target)));
    assertTrue(target.isAlive()); assertEquals(4, casts.mp());
    assertNull(casts.session.getPlayer().getBattle());
  }

  @Test void theActualNormalSlotCannotMoveToMazeSkill() throws Exception {
    activate(); var target = casts.base.monster("[]");
    assertEquals(5, code(named(81410, INPUT, 1, 2, target)));
    assertTrue(target.isAlive()); assertEquals(4, casts.mp());
  }

  @Test void changedInstalledWholeSourceCannotBorrowTheLoadedVariant() throws Exception {
    activate(); var changed = source.deepCopy();
    MazeManualAbility46Test.at(changed, "/AbilityList/1").getAsJsonObject().addProperty("ManualTriggerType", "BySummonUnit");
    Files.writeString(root.resolve(ABILITY), changed.toString());
    var target = casts.base.monster("[]");
    assertEquals(5, code(named(81411, INPUT, 0, 2, target)));
    assertTrue(target.isAlive()); assertEquals(4, casts.mp());
  }

  @ParameterizedTest @ValueSource(ints = {0, -1})
  void nativeZeroAndSentinelAreNotManualUseIds(int use) throws Exception {
    activate(); var target = casts.base.monster("[]");
    assertEquals(5, code(named(use, INPUT, 0, 2, target)));
    assertTrue(target.isAlive()); assertEquals(4, casts.mp());
  }

  @Test void aLiveBuffAloneCannotManufactureTheMissingActualBaseRelease() throws Exception {
    casts.base.actor.addBuff(141501, 30);
    assertEquals(5, code(named(81412, INPUT, 0, 2)));
    assertEquals(5, casts.mp());
    assertNull(casts.session.getPlayer().getBattle());
  }

  @Test void aDifferentActualLivingCasterCannotCopyTheFirstCastersOrigin() throws Exception {
    activate();
    var player = casts.session.getPlayer();
    var previous = casts.base.actor;
    var other = new GameAvatar(previous.getExcel()) { @Override public void save() {} };
    other.setOwner(player);
    var allocator = emu.lunarcore.game.scene.Scene.class.getDeclaredMethod("getNextEntityId");
    allocator.setAccessible(true);
    other.setEntityId((Integer) allocator.invoke(player.getScene()));
    other.getBuffs().put(141501, previous.getBuffs().get(141501)); // Deliberate identity-forgery boundary fixture.
    player.getAvatars().getAvatars().put(1415, other);
    player.getScene().getAvatars().put(1415, other);
    player.getScene().getAvatarEntityIds().remove(previous.getEntityId());
    player.getScene().getAvatarEntityIds().add(other.getEntityId());
    casts.base.actor = other;
    assertTrue(other.isAlive(player.getCurrentLineup()));
    assertSame(other, player.getScene().getAvatarByEntityId(other.getEntityId()));
    assertEquals(5, code(named(81413, INPUT, 0, 2)));
    assertEquals(4, casts.mp());
  }

  @Test void evenAnIdenticalFloorAndCasterCannotCarryTheOriginIntoAnotherScene() throws Exception {
    activate();
    var player = casts.session.getPlayer(); var previous = player.getScene();
    var next = new emu.lunarcore.game.scene.Scene(player, previous.getExcel(), previous.getFloorId(), previous.getDimensionId());
    next.getAvatars().put(1415, casts.base.actor);
    next.getAvatarEntityIds().add(casts.base.actor.getEntityId());
    var field = emu.lunarcore.game.player.Player.class.getDeclaredField("scene");
    field.setAccessible(true); field.set(player, next);
    assertSame(casts.base.actor, next.getAvatarByEntityId(casts.base.actor.getEntityId()));
    assertEquals(previous.getFloorId(), next.getFloorId());
    assertEquals(5, code(named(81414, INPUT, 0, 2)));
    assertEquals(4, casts.mp());
  }

  @Test void anUnreleasedBuffRefreshCannotBorrowAnEarlierReleaseSnapshot() throws Exception {
    activate();
    long prior = casts.base.actor.getBuffs().get(141501);
    casts.base.actor.getBuffs().put(141501, prior + 5000); // Not a source release and not an inferred TTL.
    assertEquals(5, code(named(81415, INPUT, 0, 2)));
    assertEquals(4, casts.mp());
  }
}
