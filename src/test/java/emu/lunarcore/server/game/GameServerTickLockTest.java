package emu.lunarcore.server.game;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.game.player.Player;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class GameServerTickLockTest {
  // Exercise the real tick without starting network sockets, databases, watchers or a timer.
  static GameServer detached(Int2ObjectOpenHashMap<Player> registry) throws Exception {
    var singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    singleton.setAccessible(true);
    var server =
        (GameServer) ((sun.misc.Unsafe) singleton.get(null)).allocateInstance(GameServer.class);
    var players = GameServer.class.getDeclaredField("players");
    players.setAccessible(true);
    players.set(server, registry);
    return server;
  }

  static void tick(GameServer server) throws Exception {
    var method = GameServer.class.getDeclaredMethod("onTick");
    method.setAccessible(true);
    method.invoke(server);
  }

  @Test
  void playerLogicRunsAfterReleasingTheOnlineRegistryMutex() throws Exception {
    var registry = new Int2ObjectOpenHashMap<Player>();
    var observed = new AtomicBoolean();
    registry.put(
        10001,
        new Player() {
          @Override
          public void onTick(long timestamp, long delta) {
            observed.set(Thread.holdsLock(registry));
          }
        });
    tick(detached(registry));
    assertFalse(
        observed.get(),
        "Player callbacks must not hold the registry mutex while GM holds the player mutex");
  }

  @Test
  void tickAndGmOperationsUseTheSamePlayerMutex() throws Exception {
    var registry = new Int2ObjectOpenHashMap<Player>();
    var observed = new AtomicBoolean();
    registry.put(
        10001,
        new Player() {
          @Override
          public void onTick(long timestamp, long delta) {
            observed.set(Thread.holdsLock(this));
          }
        });
    tick(detached(registry));
    assertTrue(
        observed.get(), "Scene ticking and GM operations must enter through the same player mutex");
  }
}
