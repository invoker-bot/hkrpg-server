package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.GameConstants;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import java.nio.file.Path;
import java.util.List;
import org.hkrpg.server.protocol.VersionedPacketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native request identities, with isolated scene placement and original stage fixtures. */
class MonsterAmbush46Test {
  @TempDir Path root;
  final MazeAttackResults46Test base = new MazeAttackResults46Test();

  @BeforeEach
  void setup() throws Exception {
    base.root = root;
    base.setup();
  }

  @AfterEach
  void cleanup() throws Exception {
    base.cleanup();
  }

  DynamicMessage request(
      int use,
      int caster,
      int index,
      String ability,
      int[] tags,
      int[] hits,
      int[] assists,
      int... groupedAssists)
      throws Exception {
    var session = base.new Session();
    var type = base.protocol.clientSchema().message("SceneCastSkillCsReq");
    var request =
        DynamicMessage.newBuilder(type)
            .setField(type.findFieldByName("cast_entity_id"), use)
            .setField(type.findFieldByName("attacked_by_entity_id"), caster)
            .setField(type.findFieldByName("skill_index"), index)
            .setField(type.findFieldByName("maze_ability_str"), ability);
    for (int id : hits)
      request.addRepeatedField(type.findFieldByName("hit_target_entity_id_list"), id);
    for (int id : assists)
      request.addRepeatedField(type.findFieldByName("assist_monster_entity_id_list"), id);
    if (groupedAssists.length > 0) {
      var field = type.findFieldByName("assist_monster_entity_info");
      var info = DynamicMessage.newBuilder(field.getMessageType());
      for (int id : groupedAssists)
        info.addRepeatedField(field.getMessageType().findFieldByName("entity_id_list"), id);
      request.addRepeatedField(field, info.build());
    }
    var tagField = type.findFieldByName("skill_extra_tags");
    for (int tag : tags)
      request.addRepeatedField(
          tagField, tagField.getEnumType().findValueByNumberCreatingIfUnknown(tag));
    assertTrue(VersionedPacketHandler.handle(session, 1460, request.build().toByteArray()));
    assertEquals(1, session.responses.size());
    var response = session.responses.getFirst();
    assertEquals(use, MazeAttackResults46Test.number(response, "cast_entity_id"));
    return response;
  }

  @Test
  void capturedEmptyHitCallbackAcknowledgesUseIdWithoutInventingAnAmbush() throws Exception {
    var monster = base.monster("[]");
    // Live 2026-10-22 21:00:13 UTC: UseID 395, monster entity 39, tag [1], no hits.
    // Map only the scene entity to the isolated fixture. No motion is needed for a monster.
    var response =
        request(395, monster.getEntityId(), 0, "", new int[] {1}, new int[] {}, new int[] {});
    assertEquals(0, MazeAttackResults46Test.number(response, "retcode"));
    assertTrue(MazeAttackResults46Test.rows(response).isEmpty());
    assertNull(base.branch.base.base.player.getBattle());
    assertTrue(monster.isAlive());
    assertEquals(0, base.branch.base.base.drops);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void capturedAvatarInAssistListEntersAmbushAndSettlesOnlyItsAttacker(int tag) throws Exception {
    var monster = base.monster("[]");
    var bystander = base.monster("[]");
    var player = base.branch.base.base.player;
    int mp = player.getCurrentLineup().getMp();
    int[] tags = tag == 0 ? new int[] {} : tag == 3 ? new int[] {1, 2} : new int[] {tag};
    // Live 2026-10-22 21:53:18 UTC: UseID 411, monster 39, tag [1], field 6 [avatar 1].
    // Both scene IDs are remapped to fixtures. The ordinary hit list is absent.
    for (int attempt = 0; attempt < 2; attempt++) {
      var response =
          request(
              411 + attempt,
              monster.getEntityId(),
              0,
              "",
              tags,
              new int[] {},
              new int[] {base.actor.getEntityId()});
      assertEquals(0, MazeAttackResults46Test.number(response, "retcode"));
      var battle = player.getBattle();
      assertNotNull(battle);
      assertEquals(List.of(monster), battle.getNpcMonsters());
      assertTrue(battle.getBuffs().containsKey(GameConstants.BATTLE_AMBUSH_BUFF_ID));
      assertEquals(mp, player.getCurrentLineup().getMp());
      assertEquals(1, MazeAttackResults46Test.rows(response).size());
      MazeAttackResults46Test.outcome(
          MazeAttackResults46Test.rows(response).getFirst(), monster.getEntityId(), 1);
      assertSame(
          battle,
          base.service.finishBattle(
              player,
              battle.getId(),
              battle.getStage().getId(),
              attempt == 0 ? BattleEndStatus.BATTLE_END_LOSE : BattleEndStatus.BATTLE_END_WIN,
              BattleStatistics.newInstance()));
      assertEquals(attempt == 0, monster.isAlive());
      assertTrue(bystander.isAlive());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void avatarInEitherAssistFormRemainsAHitWhenOtherHitsAndAssistsExist(boolean grouped)
      throws Exception {
    var monster = base.monster("[]");
    var assist = base.monster("[]");
    var actor = base.actor.getEntityId();
    int[] targets = {actor, actor, assist.getEntityId()};
    var response =
        request(
            actor,
            monster.getEntityId(),
            0,
            "",
            new int[] {1},
            new int[] {monster.getEntityId()},
            grouped ? new int[] {} : targets,
            grouped ? targets : new int[] {});
    assertEquals(0, MazeAttackResults46Test.number(response, "retcode"));
    var battle = base.branch.base.base.player.getBattle();
    assertNotNull(battle);
    assertEquals(List.of(monster, assist), battle.getNpcMonsters());
    assertTrue(battle.getBuffs().containsKey(GameConstants.BATTLE_AMBUSH_BUFF_ID));
    assertEquals(2, MazeAttackResults46Test.rows(response).size());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void playerCannotUseAvatarInEitherAssistFormAsAnAttackTarget(boolean grouped) throws Exception {
    base.skill(false, true);
    var monster = base.monster("[]");
    var actor = base.actor.getEntityId();
    var response =
        request(
            413,
            actor,
            0,
            "",
            new int[] {},
            new int[] {monster.getEntityId()},
            grouped ? new int[] {} : new int[] {actor},
            grouped ? new int[] {actor} : new int[] {});
    assertNotEquals(0, MazeAttackResults46Test.number(response, "retcode"));
    assertNull(base.branch.base.base.player.getBattle());
    assertTrue(monster.isAlive());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void unknownEntityAlongsideAvatarInAssistFormRejectsTheWholeAttack(boolean grouped)
      throws Exception {
    var monster = base.monster("[]");
    int[] targets = {base.actor.getEntityId(), 199996001};
    var response =
        request(
            414,
            monster.getEntityId(),
            0,
            "",
            new int[] {1},
            new int[] {},
            grouped ? new int[] {} : targets,
            grouped ? targets : new int[] {});
    assertNotEquals(0, MazeAttackResults46Test.number(response, "retcode"));
    assertNull(base.branch.base.base.player.getBattle());
    assertTrue(monster.isAlive());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void monsterHitUsesItsEntityAndPreservesAmbushBattleAndSettlement(int tag) throws Exception {
    var monster = base.monster("[]");
    var assist = base.monster("[]");
    int[] tags = tag == 0 ? new int[] {} : tag == 3 ? new int[] {1, 2} : new int[] {tag};
    var player = base.branch.base.base.player;
    int mp = player.getCurrentLineup().getMp();
    var response =
        request(
            396,
            monster.getEntityId(),
            0,
            "",
            tags,
            new int[] {base.actor.getEntityId()},
            new int[] {assist.getEntityId()});
    assertEquals(0, MazeAttackResults46Test.number(response, "retcode"));
    var battle = player.getBattle();
    assertNotNull(battle);
    assertEquals(List.of(monster, assist), battle.getNpcMonsters());
    assertTrue(battle.getBuffs().containsKey(GameConstants.BATTLE_AMBUSH_BUFF_ID));
    assertEquals(mp, player.getCurrentLineup().getMp());
    assertTrue(response.hasField(response.getDescriptorForType().findFieldByName("battle_info")));
    assertSame(
        battle,
        base.service.finishBattle(
            player,
            battle.getId(),
            battle.getStage().getId(),
            BattleEndStatus.BATTLE_END_LOSE,
            BattleStatistics.newInstance()));
    assertTrue(monster.isAlive());
    assertTrue(assist.isAlive());
    var retry =
        request(
            397,
            monster.getEntityId(),
            0,
            "",
            tags,
            new int[] {base.actor.getEntityId()},
            new int[] {});
    assertEquals(0, MazeAttackResults46Test.number(retry, "retcode"));
    battle = player.getBattle();
    assertEquals(List.of(monster), battle.getNpcMonsters());
    assertSame(
        battle,
        base.service.finishBattle(
            player,
            battle.getId(),
            battle.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance()));
    assertFalse(monster.isAlive());
    assertTrue(assist.isAlive());
  }

  @Test
  void useIdEqualToAvatarEntityDoesNotReverseAMonsterProjectile() throws Exception {
    var monster = base.monster("[]");
    var response =
        request(
            base.actor.getEntityId(),
            monster.getEntityId(),
            0,
            "",
            new int[] {1},
            new int[] {base.actor.getEntityId()},
            new int[] {});
    assertEquals(0, MazeAttackResults46Test.number(response, "retcode"));
    assertTrue(
        base.branch
            .base
            .base
            .player
            .getBattle()
            .getBuffs()
            .containsKey(GameConstants.BATTLE_AMBUSH_BUFF_ID));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void emptyMonsterHitCannotBecomeAPlayerAttackWhenUseIdMatchesAnAvatar(int tag) throws Exception {
    base.skill(false, true);
    var monster = base.monster("[]");
    int[] tags = tag == 0 ? new int[] {} : tag == 3 ? new int[] {1, 2} : new int[] {tag};
    var response =
        request(
            base.actor.getEntityId(),
            monster.getEntityId(),
            0,
            "",
            tags,
            new int[] {},
            new int[] {});
    assertEquals(0, MazeAttackResults46Test.number(response, "retcode"));
    assertNull(base.branch.base.base.player.getBattle());
    assertTrue(monster.isAlive());
    assertTrue(MazeAttackResults46Test.rows(response).isEmpty());
    assertEquals(0, base.branch.base.base.drops);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5})
  void malformedMonsterReportsCannotCreateABattleOrConsumePoints(int invalid) throws Exception {
    var monster = base.monster("[]");
    var player = base.branch.base.base.player;
    int mp = player.getCurrentLineup().getMp();
    var response =
        request(
            398,
            invalid == 0 ? 199996001 : monster.getEntityId(),
            invalid == 1 ? 1 : 0,
            invalid == 2 ? "Unrelated_Player_Ability" : "",
            invalid == 3 ? new int[] {1, 1} : new int[] {1},
            new int[] {invalid == 4 ? 199996001 : base.actor.getEntityId()},
            invalid == 5 ? new int[] {199996001} : new int[] {});
    assertNotEquals(0, MazeAttackResults46Test.number(response, "retcode"));
    assertNull(player.getBattle());
    assertEquals(mp, player.getCurrentLineup().getMp());
    assertTrue(monster.isAlive());
    assertTrue(MazeAttackResults46Test.rows(response).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void missingMonsterStageDoesNotTurnAHitIntoSuccess(boolean assistField) throws Exception {
    EntityMonster monster = base.monster("[]");
    monster.setCustomStage((emu.lunarcore.game.battle.BattleStage) null);
    monster.setEventId(199996001);
    var response =
        request(
            399,
            monster.getEntityId(),
            0,
            "",
            new int[] {1},
            assistField ? new int[] {} : new int[] {base.actor.getEntityId()},
            assistField ? new int[] {base.actor.getEntityId()} : new int[] {});
    assertEquals(1, MazeAttackResults46Test.number(response, "retcode"));
    assertNull(base.branch.base.base.player.getBattle());
    assertTrue(monster.isAlive());
  }
}
