package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original Gilgamesh switches; the missing first wait uses the statically verified zero value. */
class SummonTriggerDefaultWaitTest {
  @TempDir Path root;
  final SummonTriggerEnableTest fixture = new SummonTriggerEnableTest();

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    fixture.setup();
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.cleanup();
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 459, 460, 461})
  void sourcePropTriggerWaitsForTheSecondDeclaredPointFourSixSecondDelay(long elapsed)
      throws Exception {
    var owner = fixture.fixture.owner(false);
    var unit = fixture.summon(owner, 3, fixture.raw(3));
    fixture.advance(owner, unit, elapsed);
    var prop = fixture.prop(owner, 1);
    int id = prop.getEntityId();
    owner.sent.clear();
    fixture.trigger(owner, unit, "HitPropTrigger", prop);
    if (elapsed < 460) assertSame(prop, owner.getScene().getEntityById(id));
    else
      assertNull(
          owner.getScene().getEntityById(id),
          "The source's zero default wait blocked its later switch");
    assertEquals(elapsed < 460 ? 0 : 1, fixture.notifications(owner));
  }

  @ParameterizedTest
  @ValueSource(strings = {"AddDebuffOnEnemyTrigger", "PausePropTrigger"})
  void theTwoSourceNamedTriggersEnableAfterTheVerifiedZeroDefaultWait(String name)
      throws Exception {
    var owner = fixture.fixture.owner(false);
    var unit = fixture.summon(owner, 3, fixture.raw(3));
    fixture.advance(owner, unit, 0);
    owner.sent.clear();
    fixture.trigger(owner, unit, name, null);
    assertEquals(
        1,
        fixture.notifications(owner),
        "The missing first WaitTime is the client's fixed zero object");
    fixture.trigger(owner, unit, "HitPropTrigger", null);
    assertEquals(
        1, fixture.notifications(owner), "The later named switch executed before its source delay");
  }
}
