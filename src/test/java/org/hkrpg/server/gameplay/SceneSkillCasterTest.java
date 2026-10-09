package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.SpecialAvatarExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.skills.MazeSkill;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.protocol.VersionedPacketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Distinct scene actors must receive their own technique and resource restrictions. */
class SceneSkillCasterTest {
  @TempDir Path root;
  final TrialMazeSkillLockTest base = new TrialMazeSkillLockTest();
  final List<Integer> castActors = new ArrayList<>();
  emu.lunarcore.data.excel.AvatarExcel oldExemptActor;
  boolean exemptActorInstalled;

  @BeforeEach
  void setup() throws Exception {
    base.root = root;
    base.setup();
    base.fixture.configureSecondTrial(true);
    for (int id : List.of(StoryLineLifecycleTest.ACTOR, StoryLineLifecycleTest.ACTOR + 2)) {
      var excel = GameData.getAvatarExcelMap().get(id);
      excel.setMazeSkill(
          new MazeSkill(excel, 1) {
            @Override
            public void onCast(GameAvatar actor, MotionInfo motion) {
              castActors.add(actor.getAvatarId());
            }
          });
    }
  }

  @AfterEach
  void cleanup() throws Exception {
    try {
      base.cleanup();
    } finally {
      if (exemptActorInstalled) {
        GameData.getAvatarExcelMap().keySet().remove(1308);
        if (oldExemptActor != null) GameData.getAvatarExcelMap().add(oldExemptActor);
      }
    }
  }

  void lock(int id, boolean value) {
    var row =
        base.fixture
            .fixture
            .gson
            .toJsonTree(GameData.getSpecialAvatarExcelMap().get(id, 0))
            .getAsJsonObject();
    row.addProperty("LockMazeSkill", value);
    GameData.getSpecialAvatarExcelMap()
        .add(base.fixture.fixture.gson.fromJson(row, SpecialAvatarExcel.class));
  }

  TrialMazeSkillLockTest.Capture cast(boolean nativePath) throws Exception {
    var owner = base.fixture.player;
    var session = new TrialMazeSkillLockTest.Capture(owner);
    int entity = owner.getAvatarById(StoryLineLifecycleTest.SPECIAL + 1).getEntityId();
    assertNotEquals(owner.getCurrentLeaderAvatar().getEntityId(), entity);
    try {
      if (nativePath) {
        var req =
            DynamicMessage.newBuilder(
                base.fixture.bridge.clientSchema().message("SceneCastSkillCsReq"));
        for (String name : List.of("cast_entity_id", "attacked_by_entity_id"))
          req.setField(req.getDescriptorForType().findFieldByName(name), entity);
        req.setField(req.getDescriptorForType().findFieldByName("skill_index"), 1);
        assertTrue(
            VersionedPacketHandler.handle(
                session,
                base.fixture.bridge.clientCommand("SceneCastSkillCsReq"),
                req.build().toByteArray()));
      } else {
        var req =
            emu.lunarcore.proto.SceneCastSkillCsReqOuterClass.SceneCastSkillCsReq.newInstance()
                .setCasterId(entity)
                .setAttackedGroupId(entity)
                .setSkillIndex(1);
        new emu.lunarcore.server.packet.recv.HandlerSceneCastSkillCsReq()
            .handle(session, req.toByteArray());
      }
    } catch (TrialMazeSkillLockTest.AttackBoundary expected) {
      assertTrue(nativePath);
    }
    return session;
  }

  @ParameterizedTest
  @CsvSource({
    "false,false,false",
    "true,false,false",
    "false,true,false",
    "true,true,false",
    "false,false,true",
    "true,false,true"
  })
  void requestedActorSuppliesTheTechniqueAndItsOwnLock(
      boolean nativePath, boolean leaderLocked, boolean casterLocked) throws Exception {
    lock(StoryLineLifecycleTest.SPECIAL + 1, casterLocked);
    var owner = base.started(leaderLocked);
    assertEquals(
        List.of(StoryLineLifecycleTest.SPECIAL, StoryLineLifecycleTest.SPECIAL + 1),
        owner.getCurrentLineup().getAvatars());
    var session = cast(nativePath);
    if (casterLocked) {
      assertTrue(castActors.isEmpty(), "A locked requested actor executed the leader's technique");
      base.rejected(session, nativePath);
      assertEquals(5, owner.getCurrentLineup().getMp());
    } else {
      assertEquals(
          List.of(StoryLineLifecycleTest.SPECIAL + 1),
          castActors,
          "Technique belongs to the requested scene actor");
      assertEquals(4, owner.getCurrentLineup().getMp());
      assertEquals(nativePath, session.attackAttempted);
    }
    assertEquals(0, owner.getCurrentLineup().getLeader());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void hitAndAttackCallbacksUseTheRequestedSceneActor(boolean attack) {
    var owner = base.started(false);
    var requested = owner.getAvatarById(StoryLineLifecycleTest.SPECIAL + 1);
    var group = new emu.lunarcore.data.config.GroupInfo();
    group.setId(199960);
    var info =
        base.fixture.fixture.gson.fromJson(
            "{\"ID\":19996001,\"Pos\":{},\"Rot\":{}}", emu.lunarcore.data.config.MonsterInfo.class);
    var monster =
        new emu.lunarcore.game.scene.entity.EntityMonster(
            owner.getScene(), new emu.lunarcore.data.excel.NpcMonsterExcel(), group, info);
    monster.setEntityId(19996001);
    owner.getScene().getEntities().put(monster.getEntityId(), monster);
    var skill =
        new MazeSkill(requested.getExcel(), 1) {
          @Override
          public void onCastHit(
              GameAvatar caster,
              List<? extends emu.lunarcore.game.scene.entity.GameEntity> targets) {
            castActors.add(caster.getAvatarId());
          }

          @Override
          public void onAttack(
              GameAvatar caster,
              List<? extends emu.lunarcore.game.scene.entity.GameEntity> targets) {
            castActors.add(caster.getAvatarId());
            monster.setEntityId(0);
          }
        };
    skill.setTriggerBattle(attack);
    new emu.lunarcore.game.battle.BattleService(null)
        .attack(
            owner,
            requested.getEntityId(),
            999,
            skill,
            new it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet(new int[] {monster.getEntityId()}),
            it.unimi.dsi.fastutil.ints.IntSets.EMPTY_SET);
    assertEquals(List.of(StoryLineLifecycleTest.SPECIAL + 1), castActors);
    assertNull(owner.getBattle());
    assertEquals(0, owner.getCurrentLineup().getLeader());
  }

  @ParameterizedTest
  @CsvSource({"false,false", "true,false", "false,true", "true,true"})
  void pointExemptionBelongsToTheRequestedActor(boolean nativePath, boolean exemptCaster)
      throws Exception {
    oldExemptActor = GameData.getAvatarExcelMap().get(1308);
    exemptActorInstalled = true;
    var excel =
        base.fixture.fixture.gson.fromJson(
            "{\"AvatarID\":1308}", emu.lunarcore.data.excel.AvatarExcel.class);
    excel.setMazeSkill(
        new MazeSkill(excel, 1) {
          @Override
          public void onCast(GameAvatar actor, MotionInfo motion) {
            castActors.add(actor.getAvatarId());
          }
        });
    GameData.getAvatarExcelMap().add(excel);
    int special = StoryLineLifecycleTest.SPECIAL + (exemptCaster ? 1 : 0);
    var row =
        base.fixture
            .fixture
            .gson
            .toJsonTree(GameData.getSpecialAvatarExcelMap().get(special, 0))
            .getAsJsonObject();
    row.addProperty("AvatarID", 1308);
    GameData.getSpecialAvatarExcelMap()
        .add(base.fixture.fixture.gson.fromJson(row, SpecialAvatarExcel.class));
    var owner = base.started(false);
    owner.getCurrentLineup().setMp(exemptCaster ? 0 : 5);
    var session = cast(nativePath);
    assertEquals(List.of(StoryLineLifecycleTest.SPECIAL + 1), castActors);
    assertEquals(exemptCaster ? 0 : 4, owner.getCurrentLineup().getMp());
    assertEquals(nativePath, session.attackAttempted);
    assertEquals(0, owner.getCurrentLineup().getLeader());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void entryDebuffKeepsTheRequestedActorsElementAndLineupSlot(boolean ignoreWeakness)
      throws Exception {
    for (int id : List.of(StoryLineLifecycleTest.ACTOR, StoryLineLifecycleTest.ACTOR + 2)) {
      var excel = GameData.getAvatarExcelMap().get(id);
      var field = excel.getClass().getDeclaredField("DamageType");
      field.setAccessible(true);
      field.set(
          excel,
          id == StoryLineLifecycleTest.ACTOR
              ? emu.lunarcore.game.enums.DamageType.Physical
              : emu.lunarcore.game.enums.DamageType.Fire);
    }
    var owner = base.started(false);
    var requested = owner.getAvatarById(StoryLineLifecycleTest.SPECIAL + 1);
    var group = new emu.lunarcore.data.config.GroupInfo();
    group.setId(199960);
    var info =
        base.fixture.fixture.gson.fromJson(
            "{\"ID\":19996001,\"Pos\":{},\"Rot\":{}}", emu.lunarcore.data.config.MonsterInfo.class);
    var monster =
        new emu.lunarcore.game.scene.entity.EntityMonster(
            owner.getScene(), new emu.lunarcore.data.excel.NpcMonsterExcel(), group, info);
    monster.setEntityId(19996001);
    // Empty synthetic waves isolate entry-effect ownership; they do not prove a playable battle.
    monster.setCustomStage(
        new emu.lunarcore.game.battle.BattleStage() {
          public int getId() {
            return 199960;
          }

          public emu.lunarcore.game.enums.StageType getStageType() {
            return emu.lunarcore.game.enums.StageType.Mainline;
          }

          public List<it.unimi.dsi.fastutil.ints.IntList> getMonsterWaves() {
            return List.of();
          }
        });
    owner.getScene().getEntities().put(monster.getEntityId(), monster);
    var skill = new MazeSkill(requested.getExcel(), 1);
    if (ignoreWeakness)
      skill.addAdventureModifier("ADV_StageAbility_Maze_IgnoreWeakness_MazeSkillMark");
    new emu.lunarcore.game.battle.BattleService(null)
        .attack(
            owner,
            requested.getEntityId(),
            999,
            skill,
            new it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet(new int[] {monster.getEntityId()}),
            it.unimi.dsi.fastutil.ints.IntSets.EMPTY_SET);
    var battle = owner.getBattle();
    assertNotNull(battle);
    int expected =
        ignoreWeakness ? 1000119 : emu.lunarcore.game.enums.DamageType.Fire.getEnterBattleBuff();
    var buff = battle.getBuffs().get(expected);
    assertNotNull(buff, "Entry effect must use the requested actor's element");
    assertEquals(1, buff.getOwnerIndex());
    assertEquals(List.of(1), buff.getTargetIndex());
    var nativeBuff =
        base.fixture
            .bridge
            .clientSchema()
            .parse(
                "BattleBuff",
                base.fixture.bridge.translate("BattleBuff", buff.toProto().toByteArray(), true));
    assertEquals(expected, StoryLineWireTest.number(nativeBuff, "id"));
    assertEquals(1, StoryLineWireTest.number(nativeBuff, "owner_index"));
    assertEquals(
        List.of(1),
        nativeBuff.getField(
            nativeBuff.getDescriptorForType().findFieldByName("target_index_list")));
    assertEquals(0, owner.getCurrentLineup().getLeader());
  }
}
