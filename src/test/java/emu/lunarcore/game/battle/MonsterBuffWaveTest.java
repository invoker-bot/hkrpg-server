package emu.lunarcore.game.battle;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.NpcMonsterExcel;
import emu.lunarcore.game.enums.StageType;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.entity.EntityMonster;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.List;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Isolated monster-to-battle callbacks; no real game client or authored encounter. */
class MonsterBuffWaveTest {
  static final int CASTER = 199981, BUFF = 19998101;
  final Gson gson = new Gson();

  Battle battle() {
    var player = new BattleSettlementTest.TestPlayer();
    var team = new PlayerLineup(player, 0);
    team.getAvatars().add(CASTER + 1);
    team.getAvatars().add(CASTER);
    var stage =
        new BattleStage() {
          public int getId() {
            return 199981;
          }

          public StageType getStageType() {
            return StageType.Mainline;
          }

          public List<IntList> getMonsterWaves() {
            return List.of();
          }
        };
    return new Battle(player, team, stage, false);
  }

  EntityMonster monster(int id) {
    var group = new GroupInfo();
    group.setId(199981);
    return new EntityMonster(
        null,
        new NpcMonsterExcel(),
        group,
        gson.fromJson("{\"ID\":" + id + ",\"Pos\":{},\"Rot\":{}}", MonsterInfo.class));
  }

  void wire(Battle battle, int expected) throws Exception {
    var buff = battle.getBuffs().get(BUFF);
    assertNotNull(buff);
    assertEquals(expected, buff.getWaveFlag(), "Buff must apply only to its actual wave bits");
    assertEquals(1, buff.getOwnerIndex());
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var nativeBuff =
        bridge
            .clientSchema()
            .parse(
                "BattleBuff", bridge.translate("BattleBuff", buff.toProto().toByteArray(), true));
    assertEquals(
        expected,
        nativeBuff.getField(nativeBuff.getDescriptorForType().findFieldByName("wave_flag")));
    assertEquals(
        1, nativeBuff.getField(nativeBuff.getDescriptorForType().findFieldByName("owner_index")));
    assertTrue(nativeBuff.getUnknownFields().asMap().isEmpty());
  }

  @ParameterizedTest
  @CsvSource({"1,1", "2,2", "4,8", "32,-2147483648"})
  void permanentMonsterBuffUsesOnlyItsActualWave(int wave, int expected) throws Exception {
    var monster = monster(1);
    monster.addBuff(CASTER, BUFF, -1);
    var battle = battle();
    monster.applyBuffs(battle, wave);
    wire(battle, expected);
  }

  @ParameterizedTest
  @CsvSource({"2,4", "4,2"})
  void sameCasterAndBuffMergeNonAdjacentWaveBitsInEitherOrder(int first, int second)
      throws Exception {
    var a = monster(1);
    var b = monster(2);
    a.addBuff(CASTER, BUFF, -1);
    b.addBuff(CASTER, BUFF, -1);
    var battle = battle();
    a.applyBuffs(battle, first);
    b.applyBuffs(battle, second);
    wire(battle, 10);
    assertEquals(1, battle.getBuffs().size());
  }

  @Test
  void repeatedSameWaveDoesNotEraseExistingBuffMetadata() throws Exception {
    var monster = monster(1);
    monster.addBuff(CASTER, BUFF, -1);
    var battle = battle();
    monster.applyBuffs(battle, 1);
    battle.getBuffs().get(BUFF).addDynamicValue("ExplicitFixture", 7);
    monster.applyBuffs(battle, 1);
    assertEquals(
        1,
        battle.getBuffs().get(BUFF).toProto().getDynamicValues().length(),
        "Repeated wave binding must preserve existing buff metadata");
    wire(battle, 1);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 33})
  void unrepresentableWaveCannotWrapToAnotherWave(int wave) {
    var monster = monster(1);
    monster.addBuff(CASTER, BUFF, -1);
    var battle = battle();
    monster.applyBuffs(battle, wave);
    assertFalse(battle.hasBuff(BUFF), "Invalid wave must not wrap into a valid wave mask");
  }
}
