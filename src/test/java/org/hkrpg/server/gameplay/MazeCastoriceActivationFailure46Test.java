package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.battle.skills.MazeSkill;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.server.game.GameServer;
import emu.lunarcore.server.packet.Retcode;
import it.unimi.dsi.fastutil.ints.*;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Controlled failures in the actual source-linked native entry, not a simulated lifecycle. */
class MazeCastoriceActivationFailure46Test {
  @TempDir Path root;
  final MazeCastoriceManualAbility46Test source = new MazeCastoriceManualAbility46Test();

  @BeforeEach void setup() throws Exception { source.root = root; source.setup(); source.activate(); }
  @AfterEach void cleanup() throws Exception { source.cleanup(); }

  void service(BattleService service) throws Exception {
    var field = GameServer.class.getDeclaredField("battleService");
    field.setAccessible(true);
    field.set(source.casts.base.shell, service);
  }

  DynamicMessage input(EntityMonster target) throws Exception {
    return source.named(MazeCastoriceManualAbility46Test.CHILD_USE,
        MazeCastoriceManualAbility46Test.INPUT, 1, new int[] {1, 2}, target);
  }

  void failureKeepsTheActualSource(EntityMonster target, long expiry) throws Exception {
    DynamicMessage observed = null;
    Throwable escaped = null;
    try { observed = input(target); } catch (Throwable error) { escaped = error; }
    assertNull(escaped, "A failed finite activation must produce its native failure reply");
    assertNotNull(observed);
    assertNotEquals(0, source.code(observed));
    assertNull(source.casts.session.getPlayer().getBattle());
    assertEquals(expiry, source.casts.base.actor.getBuffs().get(140704));
    assertFalse(source.casts.base.actor.getBuffs().containsKey(140701), "Rejected admission cannot keep its OnBattle addition");
    var child = source.casts.session.getPlayer().getGameplay().getMazeCasts()
        .find(source.casts.session, MazeCastoriceManualAbility46Test.CHILD_USE);
    assertNotNull(child);
    assertFalse(child.released());
    assertTrue(target.isAlive());
    assertSame(target, source.casts.session.getPlayer().getScene().getEntities().get(target.getEntityId()));
    assertEquals(4, source.casts.mp());
    assertEquals(1, source.notices());
  }

  @Test void beforeActivationFailureDoesNotConsumeTheOriginalPermissionAndSameIdCanRetry() throws Exception {
    boolean[] fail = {true};
    service(new BattleService(null) {
      @Override public MazeAttackResult resolveAttack(Player p, int caster, MazeSkill skill, IntSet hits,
          IntSet assists, MazeSkill.CallbackPhase phase, java.util.function.Consumer<Battle> beforeActivation) {
        if (!MazeCastoriceManualAbility46Test.INPUT.equals(skill.getAbilityName()))
          return super.resolveAttack(p, caster, skill, hits, assists, phase, beforeActivation);
        return super.resolveAttack(p, caster, skill, hits, assists, phase, battle -> {
          beforeActivation.accept(battle);
          if (fail[0]) throw new IllegalStateException("Controlled beforeActivation failure");
        });
      }
    });
    long expiry = source.casts.base.actor.getBuffs().get(140704);
    var target = source.casts.base.monster("[]");
    failureKeepsTheActualSource(target, expiry);
    fail[0] = false;
    assertEquals(0, source.code(input(target)));
    assertFalse(source.casts.base.actor.getBuffs().containsKey(140704));
  }

  @Test void actualPlayerSetBattleSnapshotFailureRestoresTheSourceAndSameIdCanRetry() throws Exception {
    boolean[] fail = {true};
    var player = source.casts.session.getPlayer();
    var map = new Int2LongOpenHashMap(source.casts.base.actor.getBuffs()) {
      @Override public Int2LongMap.FastEntrySet int2LongEntrySet() {
        if (fail[0] && player.getBattle() != null)
          throw new IllegalStateException("Controlled actual prepareAvatarBuffs map failure");
        return super.int2LongEntrySet();
      }
    };
    var field = emu.lunarcore.game.avatar.GameAvatar.class.getDeclaredField("buffs");
    field.setAccessible(true);
    field.set(source.casts.base.actor, map);
    long expiry = map.get(140704);
    var target = source.casts.base.monster("[]");
    failureKeepsTheActualSource(target, expiry);
    fail[0] = false;
    assertEquals(0, source.code(input(target)));
    assertFalse(map.containsKey(140704));
  }

  @Test void aReturnedServiceFailureDoesNotReleaseAndSameIdCanReachTheRealServiceAfterward() throws Exception {
    boolean[] fail = {true};
    service(new BattleService(null) {
      @Override public MazeAttackResult resolveAttack(Player p, int caster, MazeSkill skill, IntSet hits,
          IntSet assists, MazeSkill.CallbackPhase phase, java.util.function.Consumer<Battle> beforeActivation) {
        if (fail[0] && MazeCastoriceManualAbility46Test.INPUT.equals(skill.getAbilityName()))
          return MazeAttackResult.failure(Retcode.SYSTEM_BUSY);
        return super.resolveAttack(p, caster, skill, hits, assists, phase, beforeActivation);
      }
    });
    long expiry = source.casts.base.actor.getBuffs().get(140704);
    var target = source.casts.base.monster("[]");
    failureKeepsTheActualSource(target, expiry);
    fail[0] = false;
    assertEquals(0, source.code(input(target)));
    assertFalse(source.casts.base.actor.getBuffs().containsKey(140704));
  }
}
