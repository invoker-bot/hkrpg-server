package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.util.HashSet;
import org.hkrpg.server.gameplay.GameplayState;
import org.hkrpg.server.gameplay.TrainCakeCatch;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** All 27 published cats must be reachable without altering an existing weekly box. */
class TrainCakeGacha46Test {
  @TempDir Path root;
  final TrainCakeCatch46Test fixture = new TrainCakeCatch46Test();

  @BeforeEach void setup() throws Exception { fixture.resources = root; fixture.resources(); }
  @AfterEach void cleanup() { fixture.restoreResources(); }

  @Test void laterWeeklyBoxesDrawFromTheCatalogAndKeepOneStableBatchPerWeek() {
    var s = fixture.room(true);
    long now = TrainCakeCatch.now();
    assertEquals(Retcode.SUCC, TrainCakeCatch.open(s.actor, now).retcode());
    var seen = new HashSet<Integer>();
    for (int week = 0; week < 12; week++) {
      now = s.actor.getGameplay().getTrainCakeCatch().getRefreshAt();
      var result = TrainCakeCatch.open(s.actor, now);
      assertEquals(Retcode.SUCC, result.retcode());
      assertEquals(3, result.state().getSpots().size());
      var ids = new HashSet<Integer>(); var slots = new HashSet<Integer>();
      for (var spot : result.state().getSpots()) {
        assertTrue(spot.getCat() >= 1 && spot.getCat() <= 27);
        assertTrue(spot.getSlot() >= 0 && spot.getSlot() < 7);
        assertTrue(ids.add(spot.getCat())); assertTrue(slots.add(spot.getSlot()));
      }
      seen.addAll(ids);
      assertSame(result.state(), TrainCakeCatch.open(s.actor, now).state());
    }
    assertTrue(seen.size() > 3, "The same three fixed cats must not recur every week");
  }

  @Test void oneMaxedCatDoesNotPreventABoxForTheOwnerOrVisitors() throws Exception {
    var s = fixture.room(true); long now = TrainCakeCatch.now();
    assertEquals(Retcode.SUCC, TrainCakeCatch.open(s.actor, now).retcode());
    var json = JsonParser.parseString(fixture.gson.toJson(s.actor.getGameplay())).getAsJsonObject();
    json.getAsJsonObject("trainCakeCatch").getAsJsonObject("counts").addProperty("10", 13);
    var gameplay = Player.class.getDeclaredField("gameplay"); gameplay.setAccessible(true);
    gameplay.set(s.actor, fixture.gson.fromJson(json, GameplayState.class));
    long next = s.actor.getGameplay().getTrainCakeCatch().getRefreshAt();
    var opened = TrainCakeCatch.open(s.actor, next);
    assertEquals(Retcode.SUCC, opened.retcode());
    assertEquals(13, opened.state().getCounts().get(10));
    assertEquals(3, opened.state().getSpots().size());
  }
}
