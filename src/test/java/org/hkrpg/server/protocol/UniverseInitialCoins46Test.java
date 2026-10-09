package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class UniverseInitialCoins46Test {
  @BeforeEach
  void setup() {
    new Universe46LifecycleTest().setup();
  }

  @AfterEach
  void cleanup() {
    new Universe46LifecycleTest().reset();
    GameplayDefinitions.rogueConstants.clear();
  }

  int coins(DynamicMessage response) {
    var game = (DynamicMessage) Gameplay46.value(response, "rogue_game_info");
    var run = (DynamicMessage) Gameplay46.value(game, "CFDIKLBINHK");
    return Gameplay46.number((DynamicMessage) Gameplay46.value(run, "DHEKLJDBNNP"), "rogue_money");
  }

  @Test
  void nativeStartUsesTheActual46ResourceBaseInsteadOfTheOldHardcoded100() throws Exception {
    GameplayDefinitions.rogueConstants.put("Start_Rogue_Coin_Base", "50");
    var s = new Universe46LifecycleTest.ExplorerSession();
    assertEquals(50, coins(s.start(List.of(199961), 0)));
    assertEquals(50, s.explorer.getGameplay().getRogue().getCoins());
  }

  @Test
  void resumeKeepsTheActualBalanceAfterResourcesChangeWhileANewRunUsesItsOwnBase()
      throws Exception {
    GameplayDefinitions.rogueConstants.put("Start_Rogue_Coin_Base", "50");
    var s = new Universe46LifecycleTest.ExplorerSession();
    assertEquals(0, Gameplay46.number(s.start(List.of(199961), 0), "retcode"));
    s.explorer.getGameplay().getRogue().spend(17);
    s.call("LeaveRogueCsReq", null);
    GameplayDefinitions.rogueConstants.put("Start_Rogue_Coin_Base", "80");
    assertEquals(33, coins(s.call("EnterRogueCsReq", Universe46LifecycleTest.AREA)));
    s.call("LeaveRogueCsReq", null);
    s.call("QuitRogueCsReq", Universe46LifecycleTest.AREA);
    assertEquals(80, coins(s.start(List.of(199961), 0)));
  }

  @Test
  void malformedOrMissingBaseCannotInventInitialFragmentsOrEnterThe46Scene() throws Exception {
    for (String base : List.of("-1", "1.5", "2147483648", "", "50x")) {
      GameplayDefinitions.rogueConstants.put("Start_Rogue_Coin_Base", base);
      var s = new Universe46LifecycleTest.ExplorerSession();
      assertNotEquals(0, Gameplay46.number(s.start(List.of(199961), 0), "retcode"));
      assertEquals(
          GameplayState.RogueRun.Phase.IDLE, s.explorer.getGameplay().getRogue().getPhase());
      assertEquals(11, s.explorer.getEntryId());
    }
    GameplayDefinitions.rogueConstants.remove("Start_Rogue_Coin_Base");
    var s = new Universe46LifecycleTest.ExplorerSession();
    assertNotEquals(0, Gameplay46.number(s.start(List.of(199961), 0), "retcode"));
    assertEquals(11, s.explorer.getEntryId());
  }

  @Test
  void explicitZeroIsARealBalanceAndDoesNotFallBackToTheOldAmount() throws Exception {
    GameplayDefinitions.rogueConstants.put("Start_Rogue_Coin_Base", "0");
    var s = new Universe46LifecycleTest.ExplorerSession();
    assertEquals(0, Gameplay46.number(s.start(List.of(199961), 0), "retcode"));
    assertEquals(0, coins(s.response));
  }
}
