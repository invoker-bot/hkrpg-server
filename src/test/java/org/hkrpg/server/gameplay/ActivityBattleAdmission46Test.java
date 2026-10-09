package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Source event/stage and actual core factories; activity occupancy is an explicit domain fixture.
 */
class ActivityBattleAdmission46Test {
  @TempDir Path root;
  final Gson gson = new Gson();
  ClockParkCatalog catalog;

  @BeforeEach
  void clockSource() throws Exception {
    var raw =
        JsonParser.parseString(
                new String(
                    Objects.requireNonNull(
                            getClass().getResourceAsStream("/fixtures/clock-park-catalog-4.6.json"))
                        .readAllBytes(),
                    StandardCharsets.UTF_8))
            .getAsJsonObject();
    for (var e : raw.getAsJsonObject("files").entrySet()) {
      var p = root.resolve(e.getKey());
      Files.createDirectories(p.getParent());
      Files.write(
          p, HexFormat.of().parseHex(e.getValue().getAsJsonObject().get("rawHex").getAsString()));
    }
    catalog = ClockParkCatalog.load(root);
    assertNotNull(catalog);
  }

  void occupy(Player player, String kind) {
    switch (kind) {
      case "clock" -> player.getGameplay().getClockPark().start(catalog.scripts().get(1), catalog);
      case "rhythm" ->
          player.getGameplay().getRhythm().start(19996801, List.of(Map.of(), Map.of(), Map.of()));
      case "parkour" ->
          player.getGameplay().getParkour().start(19996801, 1, 4, 199968001, 0, Long.MAX_VALUE, 0);
      case "transition", "return" -> {
        var p =
            new StoryLineState.Place(
                199931,
                199931001,
                199931001,
                0,
                new emu.lunarcore.util.Position(),
                new emu.lunarcore.util.Position());
        player.getGameplay().getStoryLines().begin(199931, p, p);
        if (kind.equals("return")) player.getGameplay().getStoryLines().begin(0, p, p);
      }
      default -> throw new IllegalArgumentException("Unknown fixture occupancy");
    }
  }

  void release(Player player, String kind) {
    if (kind.equals("clock")) player.getGameplay().getClockPark().quit();
    else if (kind.equals("rhythm")) player.getGameplay().getRhythm().finish(0, false);
    else if (kind.equals("parkour")) player.getGameplay().getParkour().close();
    else {
      var p =
          new StoryLineState.Place(
              199931,
              199931001,
              199931001,
              0,
              new emu.lunarcore.util.Position(),
              new emu.lunarcore.util.Position());
      player.getGameplay().getStoryLines().begin(0, p, p);
      player.getGameplay().getStoryLines().returned();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"clock", "rhythm", "parkour"})
  void originalNativeEventWaitsWithoutAllocatingBattleOrEditingActivity(String kind)
      throws Exception {
    var f = new SceneEventBattle46Test();
    f.temp = root;
    f.load();
    try {
      var session = f.new Session();
      occupy(session.owner, kind);
      String before = gson.toJson(session.owner.getGameplay());
      f.dispatch(session, f.request(SceneEventBattle46Test.EVENT));
      assertEquals(
          Retcode.REQ_PARA_INVALID.getVal(),
          session.response.getField(
              session.response.getDescriptorForType().findFieldByName("retcode")));
      assertNull(session.owner.getBattle());
      assertEquals(0, session.owner.ids);
      assertEquals(0, session.owner.saves);
      assertEquals(0, session.owner.grants);
      assertEquals(before, gson.toJson(session.owner.getGameplay()));
      release(session.owner, kind);
      f.dispatch(session, f.request(SceneEventBattle46Test.EVENT));
      assertNotNull(session.owner.getBattle());
      assertEquals(1, session.owner.ids);
    } finally {
      f.restore();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"clock", "rhythm", "parkour"})
  void internalDirectStageWaitsForTheActivityAndStillStartsAfterItEnds(String kind)
      throws Exception {
    var f = new SceneEventBattle46Test();
    f.temp = root;
    f.load();
    try {
      var player = new SceneEventBattle46Test.Actor();
      occupy(player, kind);
      String before = gson.toJson(player.getGameplay());
      assertNull(f.service.startBattle(player, SceneEventBattle46Test.STAGE, 0));
      assertNull(player.getBattle());
      assertEquals(0, player.ids);
      assertEquals(before, gson.toJson(player.getGameplay()));
      release(player, kind);
      assertNotNull(f.service.startBattle(player, SceneEventBattle46Test.STAGE, 0));
      assertEquals(1, player.ids);
    } finally {
      f.restore();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"clock", "rhythm", "parkour"})
  void cocoonFactoryDoesNotFreezeStaminaOrWavesWhileAnActivityIsOpen(String kind) throws Exception {
    var f = new CocoonFactoryValidationTest();
    f.before();
    try {
      f.cocoon("");
      var player = new SceneEventBattle46Test.Actor();
      occupy(player, kind);
      String before = gson.toJson(player.getGameplay());
      assertNull(
          new MissionFarmingBattleTest.Service()
              .startCocoon(player, MissionFarmingBattleTest.COCOON, 0, 2));
      assertNull(player.getBattle());
      assertEquals(0, player.ids);
      assertEquals(100, player.stamina);
      assertEquals(before, gson.toJson(player.getGameplay()));
      release(player, kind);
      var battle =
          new MissionFarmingBattleTest.Service()
              .startCocoon(player, MissionFarmingBattleTest.COCOON, 0, 2);
      assertNotNull(battle);
      assertEquals(2, battle.getCocoonWave());
      assertEquals(20, battle.getStaminaCost());
      assertEquals(100, player.stamina);
    } finally {
      f.cleanup();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"clock", "rhythm", "parkour"})
  void adjustedNativeEventCannotBypassTheActivityAdmission(String kind) throws Exception {
    var f = new StageEventDifficulty46Test();
    f.root = root;
    f.prepare();
    try {
      var player = f.session.getPlayer();
      occupy(player, kind);
      String before = gson.toJson(player.getGameplay());
      var t = f.session.getProtocol().clientSchema().message("SceneEnterStageCsReq");
      var response =
          f.call(
              "SceneEnterStageCsReq",
              DynamicMessage.newBuilder(t)
                  .setField(t.findFieldByName("event_id"), StageEventDifficulty46Test.EVENT)
                  .build());
      assertEquals(
          Retcode.REQ_PARA_INVALID.getVal(),
          RaidDifficultyAdjustment46Test.number(response, "retcode"));
      assertNull(player.getBattle());
      assertEquals(before, gson.toJson(player.getGameplay()));
      release(player, kind);
      assertNotNull(f.enter());
    } finally {
      f.restore();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"clock", "rhythm", "parkour"})
  void actualPrimaryMazeAttackCannotCreateBattleWhileItsPlayerIsInAnActivity(String kind)
      throws Exception {
    var f = new DailyWeaknessEntry46Test();
    f.setup();
    try {
      var player = f.player;
      var actor = player.getAvatarById(player.getCurrentLineup().getAvatars().getFirst());
      var monster = f.monster(300001);
      occupy(player, kind);
      String before = gson.toJson(player.getGameplay());
      int saves = player.saves;
      f.service.attack(
          player,
          actor.getEntityId(),
          7,
          actor.getExcel().getMazeAttack(),
          new it.unimi.dsi.fastutil.ints.IntOpenHashSet(new int[] {300001}),
          new it.unimi.dsi.fastutil.ints.IntOpenHashSet());
      assertNull(player.getBattle());
      assertSame(monster, player.scene.getEntities().get(300001));
      assertEquals(saves, player.saves);
      assertEquals(before, gson.toJson(player.getGameplay()));
      release(player, kind);
      assertNotNull(f.enterWeaknessBattle(300001));
    } finally {
      f.cleanup();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"clock", "rhythm", "parkour", "transition", "return"})
  void elementReentryWaitsWithoutReplacingTheActivity(String kind) throws Exception {
    var f = new CocoonFactoryValidationTest();
    f.before();
    try {
      f.stage("FarmElement", 1);
      var player = new SceneEventBattle46Test.Actor();
      occupy(player, kind);
      String before = gson.toJson(player.getGameplay());
      var service = new MissionFarmingBattleTest.Service();
      service.reEnterBattle(player, CocoonFactoryValidationTest.STAGE);
      assertNull(player.getBattle());
      assertEquals(0, player.ids);
      assertEquals(before, gson.toJson(player.getGameplay()));
      release(player, kind);
      service.reEnterBattle(player, CocoonFactoryValidationTest.STAGE);
      assertNotNull(player.getBattle());
      assertEquals(1, player.ids);
    } finally {
      f.cleanup();
    }
  }
}
