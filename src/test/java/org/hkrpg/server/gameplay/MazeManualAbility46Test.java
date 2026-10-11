package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.SkillAbilityInfo;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Whole original 1510 Input source, actual native ingress and admitted battle callbacks. */
class MazeManualAbility46Test {
  private static final String COMMIT = "724b139d8c9c32d12552eb95745a4fee72bfe48b";
  private static final String ABILITY =
      "Config/ConfigAdventureAbility/LocalPlayer/LocalPlayer_HimekoNova_00_Ability.json";
  private static final String BASE = "LocalPlayer_HimekoNova_MazeSkill";
  private static final String INPUT = "LocalPlayer_HimekoNova_MazeSkill_Input";
  private static final String MODIFIER = "ADV_StageAbility_Maze_HimekoNova_AttackRadius";
  private static final Map<String, String> PINS = Map.of(
      ABILITY, "ac339d288f866ec7ecac96b90e6bf0a494e5098ec5b635570ace5ddd7f4da6c3",
      "ExcelOutput/AvatarConfig.json", "9e57b5e3700b525a3f7097895d34177f769012034ac9bd0428318c3ff47f889e",
      "ExcelOutput/MazeSkill.json", "16a060e36ee361f40bcf604abd79d1adfbea7528ca0beb57fe1f93a51083b23e");

  @TempDir Path root;
  final MazeCastSequence46Test casts = new MazeCastSequence46Test();
  JsonObject fixture, original;

  @BeforeEach
  void setup() throws Exception {
    casts.root = root;
    casts.setup();
    try (var input = getClass().getResourceAsStream("/fixtures/maze-manual-ability-4.6.json")) {
      assertNotNull(input);
      fixture = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
    }
    assertEquals(COMMIT, fixture.get("sourceCommit").getAsString());
    assertEquals(PINS.keySet(), fixture.getAsJsonObject("sourceFiles").keySet());
    for (var entry : fixture.getAsJsonObject("sourceFiles").entrySet()) {
      var record = entry.getValue().getAsJsonObject();
      byte[] bytes = Base64.getDecoder().decode(record.get("rawBase64").getAsString());
      assertEquals(PINS.get(entry.getKey()), sha(bytes));
      assertEquals(bytes.length, record.get("fullBlobBytes").getAsInt());
      var file = root.resolve(entry.getKey());
      Files.createDirectories(file.getParent());
      Files.write(file, bytes);
      assertTrue(file.toRealPath().startsWith(root.toRealPath()), "Sources are physical isolated originals");
    }
    casts.actor(1510);
    original = JsonParser.parseString(Files.readString(root.resolve(ABILITY))).getAsJsonObject();
    assertTrue(ResourceLoader.getGson().fromJson(original, SkillAbilityInfo.class).parse(casts.base.actor.getExcel()));
    assertEquals(BASE, casts.base.actor.getExcel().getMazeSkill().getAbilityName());
  }

  @AfterEach
  void restore() throws Exception { casts.restore(); }

  static String sha(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  static JsonElement at(JsonElement value, String pointer) {
    for (var key : pointer.substring(1).split("/")) {
      key = key.replace("~1", "/").replace("~0", "~");
      value = value.isJsonArray() ? value.getAsJsonArray().get(Integer.parseInt(key))
          : value.getAsJsonObject().get(key);
      assertNotNull(value, pointer);
    }
    return value;
  }

  DynamicMessage named(int use, String ability, int index, int tag, EntityMonster... targets) throws Exception {
    var request = DynamicMessage.newBuilder(casts.base.protocol.clientSchema().message("SceneCastSkillCsReq"));
    var type = request.getDescriptorForType();
    request.setField(type.findFieldByName("cast_entity_id"), use);
    request.setField(type.findFieldByName("attacked_by_entity_id"), casts.base.actor.getEntityId());
    request.setField(type.findFieldByName("skill_index"), index);
    request.setField(type.findFieldByName("maze_ability_str"), ability);
    var extra = type.findFieldByName("skill_extra_tags");
    request.addRepeatedField(extra, extra.getEnumType().findValueByNumberCreatingIfUnknown(tag));
    for (var target : targets)
      request.addRepeatedField(type.findFieldByName("hit_target_entity_id_list"), target.getEntityId());
    return casts.dispatch("SceneCastSkillCsReq", request.build());
  }

  int code(DynamicMessage response) { return MazeCastSequence46Test.number(response, "retcode"); }

  List<DynamicMessage> notices() {
    return casts.session.messages.getOrDefault("SceneCastSkillMpUpdateScNotify", List.of());
  }

  void activate() throws Exception {
    assertEquals(0, code(casts.cost(73100)));
    assertEquals(3, casts.mp(), "Original base OnUse row costs two points");
    assertEquals(0, code(named(73100, BASE, 1, 0)));
    var record = casts.session.getPlayer().getGameplay().getMazeCasts().find(casts.session, 73100);
    assertNotNull(record);
    assertTrue(record.prepaid());
    assertTrue(record.released());
    assertTrue(casts.base.actor.getExcel().getMazeSkill().hasAdventureModifier(casts.base.actor, MODIFIER),
        "The actual base release owns the original WaitSkillInput modifier");
    assertEquals(3, casts.mp());
    assertEquals(1, notices().size());
  }

  @Test
  void fullOriginalBytesRetainTheInputLinkAndBothExplicitHitCosts() throws Exception {
    assertEquals(66910, Files.size(root.resolve(ABILITY)));
    assertEquals(232938, Files.size(root.resolve("ExcelOutput/AvatarConfig.json")));
    assertEquals(55434, Files.size(root.resolve("ExcelOutput/MazeSkill.json")));
    var input = at(original, fixture.get("manualPointer").getAsString()).getAsJsonObject();
    assertEquals(INPUT, input.get("Name").getAsString());
    assertEquals("Free", input.get("ManualTriggerType").getAsString());
    var wait = at(original, fixture.get("waitInputPointer").getAsString()).getAsJsonObject();
    assertEquals("RPG.GameCore.WaitSkillInput", wait.get("$type").getAsString());
    assertTrue(wait.get("CheckAllowUseSkill").getAsBoolean());
    assertTrue(wait.get("CheckMPNeed").getAsBoolean());
    var trigger = wait.getAsJsonArray("OnInput").get(0).getAsJsonObject();
    assertEquals("RPG.GameCore.AdventureTriggerTargetAbility", trigger.get("$type").getAsString());
    assertEquals("MazeSkill", trigger.get("SkillType").getAsString());
    assertEquals(INPUT, trigger.get("AbilityName").getAsString());
    var slots = at(original, "/AbilityList/1/OnStart/24").getAsJsonObject();
    assertEquals("RPG.GameCore.AdvSetSkillSlotData", slots.get("$type").getAsString());
    for (var pointer : fixture.getAsJsonArray("attackPointers")) {
      var attack = at(original, pointer.getAsString()).getAsJsonObject();
      assertEquals("RPG.GameCore.AdventureTriggerAttack", attack.get("$type").getAsString());
      assertEquals(2, attack.get("CostMP").getAsInt());
      assertEquals("OnFirstHitEnemy", attack.get("CostMpPhaseType").getAsString());
      assertEquals("RPG.GameCore.ByAllowInstantKill", at(attack, "/OnAttack/0/Predicate/$type").getAsString());
      assertEquals(151002, at(attack, "/OnBattle/0/ID").getAsInt());
    }
  }

  @Test
  void theNativeRequestCarriesVariantNameAndUintUseIdAndNoCostAliasIsAdded() {
    var type = casts.base.protocol.clientSchema().message("SceneCastSkillCsReq");
    assertEquals(1460, casts.base.protocol.clientCommand("SceneCastSkillCsReq"));
    assertEquals(15, type.findFieldByName("maze_ability_str").getNumber());
    assertEquals(13, type.findFieldByName("cast_entity_id").getNumber());
    assertEquals(Descriptors.FieldDescriptor.Type.UINT32, type.findFieldByName("cast_entity_id").getType());
    assertEquals(4, type.findFieldByName("skill_index").getNumber());
    assertEquals(11, type.findFieldByName("skill_extra_tags").getNumber());
    assertNotNull(type.findFieldByNumber(8));
    assertNotEquals("cost_mp", type.findFieldByNumber(8).getName());
  }

  @Test
  void explicitOriginalBaseNameRetainsItsRealOnUseActivation() throws Exception { activate(); }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void originalManualInputEventUsesItsOwnUseIdAndActualCostAndAttack(int tag) throws Exception {
    activate();
    var target = casts.base.monster("[]");
    int id = target.getEntityId();
    var response = named(73101, INPUT, 1, tag, target);
    assertEquals(0, code(response), "Original Free Input must reach its selected native projectile event");
    assertEquals(73101, MazeCastSequence46Test.number(response, "cast_entity_id"));
    assertFalse(target.isAlive(), "The unchanged original OnAttack instant-kill branch must execute");
    assertNull(casts.session.getPlayer().getBattle());
    assertEquals(1, casts.mp(), "This manual Attack costs the explicit two points without another 1408");
    assertEquals(2, notices().size());
    assertEquals(73101, MazeCastSequence46Test.number(notices().getLast(), "cast_entity_id"));
    assertEquals(1, MazeCastSequence46Test.number(notices().getLast(), "mp"));
    MazeAttackResults46Test.outcome(MazeAttackResults46Test.rows(response).getFirst(), id, 3);
  }

  @Test
  void firstHitEnemyClampsTheActualTwoTargetAttackToOneCost() throws Exception {
    activate();
    var first = casts.base.monster("[]");
    var second = casts.base.monster("[]");
    assertNotEquals(first.getEntityId(), second.getEntityId());
    var response = named(73102, INPUT, 1, 1, first, second);
    assertEquals(0, code(response));
    assertFalse(first.isAlive());
    assertFalse(second.isAlive());
    assertEquals(1, casts.mp(), "Phase2 clamps count per Attack; it does not multiply by both enemies");
    assertEquals(2, MazeAttackResults46Test.rows(response).size());
    assertEquals(2, notices().size());
  }

  @Test
  void anOriginalResistantEnemyStartsItsRealStageAndReceivesOnlyOnBattleBuff() throws Exception {
    activate();
    var target = casts.base.monster("[\"ResistInstantKill\"]");
    var response = named(73103, INPUT, 1, 1, target);
    assertEquals(0, code(response));
    var battle = casts.session.getPlayer().getBattle();
    assertNotNull(battle);
    assertEquals(List.of(target), battle.getNpcMonsters());
    assertEquals(SceneEventBattle46Test.STAGE, battle.getStage().getId());
    assertTrue(target.isAlive());
    boolean sourceBuff = false;
    for (var buff : battle.toProto().getBuffList()) if (buff.getId() == 151002) sourceBuff = true;
    assertTrue(sourceBuff, "Original Input OnBattle runs before actual wave buff collection");
    assertEquals(1, casts.mp());
    MazeAttackResults46Test.outcome(MazeAttackResults46Test.rows(response).getFirst(), target.getEntityId(), 1);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void anEventWithNoValidEnemyHasNoHitPhaseCost(int tag) throws Exception {
    activate();
    var response = named(73104, INPUT, 1, tag);
    assertEquals(0, code(response));
    assertEquals(3, casts.mp(), "PreCostMpWhenHitValidTarget exits at validTargetCount0");
    assertEquals(1, notices().size(), "An empty event must not send a new payment notification");
    assertTrue(MazeAttackResults46Test.rows(response).isEmpty());
    assertNull(casts.session.getPlayer().getBattle());
  }

  @Test
  void anEmptyFinishAfterThePaidManualHitDoesNotApplyAnotherHitCost() throws Exception {
    activate();
    assertEquals(0, code(named(73105, INPUT, 1, 1, casts.base.monster("[]"))));
    assertEquals(1, casts.mp());
    assertEquals(0, code(named(73105, INPUT, 1, 2)));
    assertEquals(1, casts.mp());
    assertEquals(2, notices().size());
  }

  @Test
  void insufficientExplicitHitCostDoesNotBindOrActAndTheSameUseCanRetry() throws Exception {
    activate();
    casts.session.getPlayer().getCurrentLineup().setMp(1); // Explicit insufficient-ingress fixture.
    var target = casts.base.monster("[]");
    var response = named(73106, INPUT, 1, 1, target);
    assertEquals(Retcode.MAZE_MP_NOT_ENOUGH.getVal(), code(response));
    assertTrue(target.isAlive());
    assertNull(casts.session.getPlayer().getGameplay().getMazeCasts().find(casts.session, 73106));
    assertEquals(1, casts.mp());
    assertEquals(1, notices().size());
    // Test fixture replenishes MP; no item-use or official input sequence is claimed here.
    casts.session.getPlayer().getCurrentLineup().setMp(3);
    assertEquals(0, code(named(73106, INPUT, 1, 1, target)));
    assertFalse(target.isAlive());
    assertEquals(1, casts.mp());
  }

  @ParameterizedTest
  @ValueSource(strings = {"LocalPlayer_Asta_MazeSkill_Input", "LocalPlayer_HimekoNova_MazeSkill_Out", "Unknown_MazeSkill_Input"})
  void unsupportedAndForeignManualNamesDoNotSpendOrRunAnotherAbility(String name) throws Exception {
    activate();
    var target = casts.base.monster("[]");
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), code(named(73107, name, 1, 1, target)));
    assertTrue(target.isAlive());
    assertEquals(3, casts.mp());
    assertEquals(1, notices().size());
    assertNull(casts.session.getPlayer().getGameplay().getMazeCasts().find(casts.session, 73107));
  }

  @Test
  void theSourceInputCannotMoveFromItsOriginalMazeSkillSlotToNormalAttack() throws Exception {
    activate();
    var target = casts.base.monster("[]");
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), code(named(73108, INPUT, 0, 1, target)));
    assertTrue(target.isAlive());
    assertEquals(3, casts.mp());
  }

  @Test
  void aBasePrepaymentReceiptCannotBeBorrowedByTheDifferentManualAbility() throws Exception {
    activate();
    var target = casts.base.monster("[]");
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), code(named(73100, INPUT, 1, 1, target)));
    assertTrue(target.isAlive());
    assertEquals(3, casts.mp());
    assertEquals(0, code(named(73100, BASE, 1, 0)));
    assertEquals(3, casts.mp());
    assertEquals(1, notices().size());
  }

  @Test
  void aManualCastCannotLaterBeReassignedToTheBaseOnUsePayment() throws Exception {
    activate();
    assertEquals(0, code(named(73109, INPUT, 1, 1, casts.base.monster("[]"))));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), code(casts.cost(73109)));
    assertEquals(1, casts.mp());
    assertEquals(2, notices().size());
  }

  @Test
  void changedInstalledAttackSourceCannotBorrowTheAlreadyLoadedOriginalVariant() throws Exception {
    activate();
    var changed = original.deepCopy();
    at(changed, "/AbilityList/2/OnStart/11/OnProjectileHit/1").getAsJsonObject().addProperty("CostMP", 1);
    Files.writeString(root.resolve(ABILITY), changed.toString());
    var target = casts.base.monster("[]");
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), code(named(73110, INPUT, 1, 1, target)));
    assertEquals(3, casts.mp());
    assertTrue(target.isAlive());
    assertEquals(1, notices().size());
  }

  @Test
  void anInvalidEventBeforeInputDoesNotPoisonItsLaterValidUseId() throws Exception {
    activate();
    var target = casts.base.monster("[]");
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), code(named(73111, INPUT, 1, 99, target)));
    assertEquals(3, casts.mp());
    assertTrue(target.isAlive());
    assertEquals(0, code(named(73111, INPUT, 1, 1, target)));
    assertFalse(target.isAlive());
    assertEquals(1, casts.mp());
  }
}
