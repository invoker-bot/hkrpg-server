package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.battle.skills.MazeSkill;
import emu.lunarcore.game.enums.*;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual BattleService consumes evaluated metadata; empty waves isolate ownership/policy only. */
class MazeConditionalMetadataBattleTest {
  @TempDir Path root;
  private final SceneSkillCasterTest scene = new SceneSkillCasterTest();
  private final MazeCasterBuffRemovalTest parser = new MazeCasterBuffRemovalTest();

  @BeforeEach
  void setup() throws Exception {
    scene.root = root;
    scene.setup();
    for (int id : List.of(StoryLineLifecycleTest.ACTOR, StoryLineLifecycleTest.ACTOR + 2)) {
      var excel = GameData.getAvatarExcelMap().get(id);
      var field = excel.getClass().getDeclaredField("DamageType");
      field.setAccessible(true);
      field.set(excel, DamageType.Fire);
    }
  }

  @AfterEach
  void cleanup() throws Exception {
    scene.cleanup();
  }

  private EntityMonster monster(emu.lunarcore.game.player.Player owner) {
    var group = new emu.lunarcore.data.config.GroupInfo();
    group.setId(199960);
    var info =
        parser.gson.fromJson(
            "{\"ID\":19996001,\"Pos\":{},\"Rot\":{}}", emu.lunarcore.data.config.MonsterInfo.class);
    var entity =
        new EntityMonster(
            owner.getScene(), new emu.lunarcore.data.excel.NpcMonsterExcel(), group, info);
    entity.setEntityId(19996001);
    entity.setCustomStage(
        new BattleStage() {
          public int getId() {
            return 199960;
          }

          public StageType getStageType() {
            return StageType.Mainline;
          }

          public List<it.unimi.dsi.fastutil.ints.IntList> getMonsterWaves() {
            return List.of();
          }
        });
    owner.getScene().getEntities().put(entity.getEntityId(), entity);
    return entity;
  }

  private JsonObject branch(boolean policy) {
    var task =
        JsonParser.parseString(
                """
                {"$type":"RPG.GameCore.PredicateTaskList",
                 "Predicate":{"$type":"RPG.GameCore.ByCompareCarryMazebuff","BuffID":130402,
                   "TargetType":{"$type":"RPG.GameCore.TargetAlias","Alias":"Caster"}},
                 "SuccessTaskList":[]}
                """)
            .getAsJsonObject();
    task.getAsJsonArray("SuccessTaskList")
        .add(
            JsonParser.parseString(
                policy
                    ? """
                      {"$type":"RPG.GameCore.AdventureTriggerAttack","TriggerBattle":false}
                      """
                    : """
                      {"$type":"RPG.GameCore.AddAdventureModifier",
                       "TargetType":{"$type":"RPG.GameCore.TargetAlias","Alias":"Caster"},
                       "ModifierName":"ADV_StageAbility_Maze_IgnoreWeakness_MazeSkillMark"}
                      """));
    return task;
  }

  private void attack(
      emu.lunarcore.game.player.Player owner,
      emu.lunarcore.game.avatar.GameAvatar caster,
      MazeSkill skill,
      EntityMonster monster) {
    new BattleService(null)
        .attack(
            owner,
            caster.getEntityId(),
            999,
            skill,
            new it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet(new int[] {monster.getEntityId()}),
            it.unimi.dsi.fastutil.ints.IntSets.EMPTY_SET);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void entryEffectReadsTheEvaluatedCasterModifier(boolean marked) throws Exception {
    var owner = scene.base.started(false);
    var caster = owner.getAvatarById(StoryLineLifecycleTest.SPECIAL + 1);
    var monster = monster(owner);
    var skill = parser.parse(caster.getExcel(), "ConsumerMetadata_MazeSkill", branch(false));
    if (marked) caster.addBuff(130402, -1);
    skill.onCast(caster, MotionInfo.newInstance());
    attack(owner, caster, skill, monster);
    assertNotNull(owner.getBattle());
    int expected = marked ? 1000119 : DamageType.Fire.getEnterBattleBuff();
    var buff = owner.getBattle().getBuffs().get(expected);
    assertNotNull(buff, "BattleService ignored the evaluated modifier snapshot");
    assertEquals(1, buff.getOwnerIndex());
    assertEquals(List.of(1), buff.getTargetIndex());
    var nativeBuff =
        scene
            .base
            .fixture
            .bridge
            .clientSchema()
            .parse(
                "BattleBuff",
                scene.base.fixture.bridge.translate(
                    "BattleBuff", buff.toProto().toByteArray(), true));
    assertEquals(expected, StoryLineWireTest.number(nativeBuff, "id"));
    assertEquals(1, StoryLineWireTest.number(nativeBuff, "owner_index"));
    assertEquals(0, owner.getCurrentLineup().getLeader());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void battleEntryReadsTheEvaluatedCasterPolicy(boolean marked) {
    var owner = scene.base.started(false);
    var caster = owner.getAvatarById(StoryLineLifecycleTest.SPECIAL + 1);
    var monster = monster(owner);
    var skill = parser.parse(caster.getExcel(), "ConsumerPolicy_MazeSkill", branch(true));
    if (marked) caster.addBuff(130402, -1);
    skill.onCast(caster, MotionInfo.newInstance());
    attack(owner, caster, skill, monster);
    assertEquals(!marked, owner.getBattle() != null, "BattleService ignored the evaluated policy");
    assertEquals(0, owner.getCurrentLineup().getLeader());
  }
}
