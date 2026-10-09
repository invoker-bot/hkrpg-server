package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import dev.morphia.Morphia;
import dev.morphia.mapping.MapperOptions;
import emu.lunarcore.database.codecs.Int2IntMapCodec;
import emu.lunarcore.database.codecs.IntListCodec;
import emu.lunarcore.database.codecs.IntSetCodec;
import emu.lunarcore.game.avatar.AvatarData;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.skills.MazeSkill;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import java.util.List;
import java.util.concurrent.*;
import org.bson.codecs.configuration.CodecRegistries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Constructed metadata branches under the established caster-effect predicate, not a full script.
 */
class MazeConditionalMetadataTest {
  private static final String MODIFIER = "ADV_StageAbility_Maze_IgnoreWeakness_MazeSkillMark";
  private final MazeCasterBuffRemovalTest fixture = new MazeCasterBuffRemovalTest();

  private JsonObject modifier(String alias) {
    return JsonParser.parseString(
            "{\"$type\":\"RPG.GameCore.AddAdventureModifier\",\"ModifierName\":\""
                + MODIFIER
                + "\",\"TargetType\":{\"$type\":\"RPG.GameCore.TargetAlias\",\"Alias\":\""
                + alias
                + "\"}}")
        .getAsJsonObject();
  }

  private JsonObject trigger(boolean value) {
    var task = new JsonObject();
    task.addProperty("$type", "RPG.GameCore.AdventureTriggerAttack");
    task.addProperty("TriggerBattle", value);
    return task;
  }

  private JsonObject branch(JsonObject... children) {
    var task = new JsonObject();
    task.addProperty("$type", "RPG.GameCore.PredicateTaskList");
    task.add(
        "Predicate",
        JsonParser.parseString(
            """
            {"$type":"RPG.GameCore.ByCompareCarryMazebuff","BuffID":130402,
             "TargetType":{"$type":"RPG.GameCore.TargetAlias","Alias":"Caster"}}
            """));
    var list = new JsonArray();
    for (var child : children) list.add(child);
    task.add("SuccessTaskList", list);
    return task;
  }

  private MazeSkill parse(JsonObject... tasks) {
    return fixture.parse(fixture.excel(), "ConditionalMetadata_MazeSkill", tasks);
  }

  private GameAvatar caster(boolean marked) {
    var caster = new GameAvatar(fixture.excel());
    if (marked) caster.addBuff(130402, -1);
    return caster;
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sharedSkillModifierDependsOnTheActualCastBranch(boolean marked) {
    var skill = parse(branch(modifier("Caster")));
    var caster = caster(marked);
    skill.onCast(caster, MotionInfo.newInstance());
    assertEquals(marked, skill.hasAdventureModifier(caster, MODIFIER));
    assertFalse(
        skill.hasAdventureModifier(MODIFIER), "Conditional modifier entered the shared cache");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sharedSkillBattlePolicyDependsOnTheActualCastBranch(boolean marked) {
    var skill = parse(branch(trigger(false)));
    var caster = caster(marked);
    skill.onCast(caster, MotionInfo.newInstance());
    assertEquals(!marked, skill.isTriggerBattle(caster));
    assertTrue(skill.isTriggerBattle(), "Conditional battle policy changed the shared default");
  }

  @Test
  void anotherCasterCannotReplaceAnAlreadyEvaluatedCastSnapshot() {
    var skill = parse(branch(modifier("Caster"), trigger(false)));
    var first = caster(true);
    var second = caster(false);
    skill.onCast(first, MotionInfo.newInstance());
    skill.onCast(second, MotionInfo.newInstance());
    assertTrue(skill.hasAdventureModifier(first, MODIFIER));
    assertFalse(skill.isTriggerBattle(first));
    assertFalse(skill.hasAdventureModifier(second, MODIFIER));
    assertTrue(skill.isTriggerBattle(second));
  }

  @Test
  void aLaterMarkerRemovalDoesNotReevaluateTheAlreadyExecutedBranch() {
    var skill = parse(branch(modifier("Caster"), trigger(false)), fixture.remove(130402));
    var caster = caster(true);
    skill.onCast(caster, MotionInfo.newInstance());
    assertFalse(caster.getBuffs().containsKey(130402));
    assertTrue(skill.hasAdventureModifier(caster, MODIFIER));
    assertFalse(skill.isTriggerBattle(caster));
  }

  @Test
  void nextCastClearsPriorConditionalMetadata() {
    var skill = parse(branch(modifier("Caster"), trigger(false)));
    var caster = caster(true);
    skill.onCast(caster, MotionInfo.newInstance());
    caster.getBuffs().remove(130402);
    skill.onCast(caster, MotionInfo.newInstance());
    assertFalse(skill.hasAdventureModifier(caster, MODIFIER));
    assertTrue(skill.isTriggerBattle(caster));
  }

  @Test
  void conditionalModifierWithAnUnresolvedTargetDoesNotBecomeCasterMetadata() {
    var skill = parse(branch(modifier("ParamEntity")));
    var caster = caster(true);
    skill.onCast(caster, MotionInfo.newInstance());
    assertFalse(skill.hasAdventureModifier(caster, MODIFIER));
    assertFalse(skill.hasAdventureModifier(MODIFIER));
  }

  @Test
  void failedBranchMetadataIsSelectedAtRuntime() {
    var task = branch(modifier("Caster"));
    var failed = new JsonArray();
    failed.add(trigger(false));
    task.add("FailedTaskList", failed);
    var skill = parse(task);
    var first = caster(false);
    var second = caster(true);
    skill.onCast(first, MotionInfo.newInstance());
    skill.onCast(second, MotionInfo.newInstance());
    assertFalse(skill.isTriggerBattle(first));
    assertFalse(skill.hasAdventureModifier(first, MODIFIER));
    assertTrue(skill.isTriggerBattle(second));
    assertTrue(skill.hasAdventureModifier(second, MODIFIER));
  }

  @Test
  void metadataFollowsTheChosenNestedBranchRatherThanParsingOrder() {
    var inner = branch(trigger(true));
    var failed = new JsonArray();
    failed.add(trigger(false));
    inner.add("FailedTaskList", failed);
    var skill = parse(branch(inner));
    var caster = caster(true);
    skill.onCast(caster, MotionInfo.newInstance());
    assertTrue(skill.isTriggerBattle(caster));
  }

  @Test
  void independentUnconditionalMetadataRemainsTheSkillDefault() {
    var skill = parse(modifier("Caster"), trigger(false));
    var caster = caster(false);
    assertTrue(skill.hasAdventureModifier(MODIFIER));
    assertFalse(skill.isTriggerBattle());
    skill.onCast(caster, MotionInfo.newInstance());
    assertTrue(skill.hasAdventureModifier(caster, MODIFIER));
    assertFalse(skill.isTriggerBattle(caster));
  }

  @Test
  void sharedSkillCastsRemainSeparateAcrossConcurrentCasters() throws Exception {
    var skill = parse(branch(modifier("Caster"), trigger(false)));
    var pool = Executors.newFixedThreadPool(4);
    try {
      var futures = new java.util.ArrayList<Future<Boolean>>();
      for (int index = 0; index < 24; index++) {
        boolean marked = index % 2 == 0;
        futures.add(
            pool.submit(
                () -> {
                  var caster = caster(marked);
                  skill.onCast(caster, MotionInfo.newInstance());
                  return skill.hasAdventureModifier(caster, MODIFIER) == marked
                      && skill.isTriggerBattle(caster) != marked;
                }));
      }
      for (var future : futures) assertTrue(future.get(5, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void unrelatedSkillCannotReuseAnEarlierSkillMetadataSnapshot() {
    var first = parse(branch(modifier("Caster"), trigger(false)));
    var second = parse();
    var caster = caster(true);
    first.onCast(caster, MotionInfo.newInstance());
    second.onCast(caster, MotionInfo.newInstance());
    assertFalse(first.hasAdventureModifier(caster, MODIFIER));
    assertTrue(first.isTriggerBattle(caster));
    assertFalse(second.hasAdventureModifier(caster, MODIFIER));
    assertTrue(second.isTriggerBattle(caster));
  }

  @Test
  void attackMetadataCanExecuteInsideAnAlreadyChosenAttackBranch() {
    var attack = trigger(true);
    var nested = new JsonArray();
    nested.add(branch(modifier("Caster")));
    attack.add("OnAttack", nested);
    var skill = parse(attack);
    var caster = caster(true);
    skill.onCast(caster, MotionInfo.newInstance());
    assertFalse(skill.hasAdventureModifier(caster, MODIFIER));
    skill.onAttack(caster, List.of());
    assertTrue(skill.hasAdventureModifier(caster, MODIFIER));
    assertFalse(skill.hasAdventureModifier(MODIFIER));
  }

  @Test
  void aFreshCasterDoesNotEvaluateMetadataBeforeCasting() {
    var skill = parse(branch(modifier("Caster"), trigger(false)));
    var caster = caster(true);
    assertFalse(skill.hasAdventureModifier(caster, MODIFIER));
    assertTrue(skill.isTriggerBattle(caster));
    skill.onCast(caster, MotionInfo.newInstance());
    assertTrue(skill.hasAdventureModifier(caster, MODIFIER));
    assertFalse(skill.isTriggerBattle(caster));
  }

  @Test
  void savingAnAvatarCannotPersistItsPerCastMetadata() throws Exception {
    var skill = parse(branch(modifier("Caster"), trigger(false)));
    var caster = caster(true);
    skill.onCast(caster, MotionInfo.newInstance());
    var gson = new Gson();
    String document = assertDoesNotThrow(() -> gson.toJson(caster));
    assertFalse(document.contains("mazeCastMetadata"));
    var server = new MongoServer(new MemoryBackend());
    var address = server.bind();
    try (var client = MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var options =
          MapperOptions.builder()
              .storeEmpties(true)
              .storeNulls(false)
              .codecProvider(
                  CodecRegistries.fromCodecs(
                      new IntSetCodec(), new IntListCodec(), new Int2IntMapCodec()))
              .build();
      var store = Morphia.createDatastore(client, "maze-cast-metadata", options);
      store.getMapper().map(GameAvatar.class, AvatarData.class);
      store.save(caster);
      var stored = store.getDatabase().getCollection("avatars").find().first();
      assertNotNull(stored);
      assertFalse(stored.containsKey("mazeCastMetadata"));
      var restored = store.find(GameAvatar.class).first();
      assertNotNull(restored);
      assertNull(restored.getMazeCastMetadata());
      assertFalse(skill.hasAdventureModifier(restored, MODIFIER));
      assertTrue(skill.isTriggerBattle(restored));
    } finally {
      server.shutdownNow();
    }
  }
}
