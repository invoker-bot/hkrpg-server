package emu.lunarcore.game.battle;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.game.scene.entity.GameEntity;
import emu.lunarcore.server.packet.Retcode;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.*;

/** Actual results of one synchronous scene attack, before defeated entity IDs are cleared. */
public record MazeAttackResult(Retcode retcode, Battle battle, List<Monster> monsters) {
    public MazeAttackResult {
        monsters = List.copyOf(monsters);
    }

    public record Monster(int entityId, Outcome outcome) {}
    public enum Outcome { TRIGGER_BATTLE, DIRECT_DIE_SKIP_BATTLE, NO_BATTLE }

    public static MazeAttackResult failure(Retcode error) {
        return new MazeAttackResult(error, null, List.of());
    }

    public static MazeAttackResult empty() {
        return new MazeAttackResult(Retcode.SUCC, null, List.of());
    }

    // Skill callbacks run synchronously under the player's lock. A scoped collector avoids
    // persisting request state on shared skill definitions or reusing a previous cast's deaths.
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    static Scope observe(Player player, List<GameEntity> targets, IntSet assists) {
        return new Scope(player, targets, assists);
    }

    /** Called only after the instant-kill action's Scene.defeatMonster actually succeeds. */
    public static void recordDirectDefeat(Player player, EntityMonster monster, int entityId) {
        var scope = CURRENT.get();
        if (scope != null && scope.player == player && monster.getScene() == scope.scene
                && Objects.equals(scope.identities.get(monster), entityId)) {
            scope.defeated.add(monster);
        }
    }

    static final class Scope implements AutoCloseable {
        private final Player player;
        private final Scene scene;
        private final Scope previous;
        private final Map<EntityMonster, Integer> identities = new LinkedHashMap<>();
        private final Set<EntityMonster> defeated = Collections.newSetFromMap(new IdentityHashMap<>());

        private Scope(Player player, List<GameEntity> targets, IntSet assists) {
            this.player = player;
            this.scene = player.getScene();
            this.previous = CURRENT.get();
            for (var target : targets) capture(target);
            for (int id : assists) capture(scene.getEntityById(id));
            CURRENT.set(this);
        }

        private void capture(GameEntity entity) {
            if (entity instanceof EntityMonster monster && monster.isAlive()
                    && monster.getScene() == scene
                    && scene.getEntityById(monster.getEntityId()) == monster) {
                identities.putIfAbsent(monster, monster.getEntityId());
            }
        }

        boolean present(EntityMonster monster) {
            var id = identities.get(monster);
            return id != null && monster.isAlive() && scene.getEntityById(id) == monster;
        }

        MazeAttackResult success(Battle battle) {
            var result = new ArrayList<Monster>();
            for (var target : identities.entrySet()) {
                var monster = target.getKey();
                var outcome = defeated.contains(monster) ? Outcome.DIRECT_DIE_SKIP_BATTLE
                        : battle != null && present(monster) && battle.getNpcMonsters().contains(monster)
                            ? Outcome.TRIGGER_BATTLE : Outcome.NO_BATTLE;
                result.add(new Monster(target.getValue(), outcome));
            }
            return new MazeAttackResult(Retcode.SUCC, battle, result);
        }

        @Override public void close() {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
