package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ActivityPanelExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClockParkActivityExclusion46Test {
  @TempDir Path root;
  final ClockParkOpening46Test opening = new ClockParkOpening46Test();
  final Gson gson = new Gson();

  @BeforeEach
  void prepare() throws Exception {
    opening.root = root;
    opening.prepare();
  }

  @AfterEach
  void restore() throws Exception {
    opening.restore();
  }

  void occupy(Player target, String kind) {
    if (kind.equals("clock")) {
      opening.unlock(1);
      assertEquals(Retcode.SUCC, new ClockParkManager(opening.player).start(1).code());
      if (target != opening.player) {
        try {
          var field = GameplayState.class.getDeclaredField("clockPark");
          field.setAccessible(true);
          field.set(
              target.getGameplay(),
              gson.fromJson(
                  gson.toJson(opening.player.getGameplay().getClockPark()), ClockParkState.class));
        } catch (ReflectiveOperationException failed) {
          throw new IllegalStateException(failed);
        }
      }
    } else if (kind.equals("parkour"))
      target.getGameplay().getParkour().start(19996801, 1, 4, 199968001, 0, Long.MAX_VALUE, 0);
    else if (kind.equals("instance"))
      target.setInstance(
          new emu.lunarcore.game.player.PlayerGameInstance() {
            public void onBattleStart(emu.lunarcore.game.battle.Battle battle) {}

            public void onBattleFinish(
                emu.lunarcore.game.battle.Battle battle,
                emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus result,
                emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics stats) {}

            public void onLeave() {}
          });
    else if (kind.equals("story") || kind.equals("return")) {
      var place = new StoryLineState.Place(new UniverseLifecycleTest.Explorer());
      target.getGameplay().getStoryLines().begin(199931, place, place);
      if (kind.equals("return")) target.getGameplay().getStoryLines().begin(0, place, place);
    } else target.getGameplay().getRhythm().start(19996801, List.of(Map.of(), Map.of(), Map.of()));
  }

  void release(Player player, String kind) {
    if (kind.equals("clock"))
      assertEquals(Retcode.SUCC, new ClockParkManager(player).quit(1).code());
    else if (kind.equals("parkour")) player.getGameplay().getParkour().close();
    else if (kind.equals("instance")) player.setInstance(null);
    else if (kind.equals("story") || kind.equals("return")) {
      var place = new StoryLineState.Place(new UniverseLifecycleTest.Explorer());
      player.getGameplay().getStoryLines().begin(0, place, place);
      player.getGameplay().getStoryLines().returned();
    } else player.getGameplay().getRhythm().finish(0, false);
  }

  @ParameterizedTest
  @ValueSource(strings = {"clock", "rhythm", "parkour", "instance"})
  void nativeUniverseStartCannotReplaceAnOngoingActivity(String kind) throws Exception {
    var fixture = new Universe46LifecycleTest();
    fixture.setup();
    try {
      var session = new Universe46LifecycleTest.ExplorerSession();
      occupy(session.explorer, kind);
      String before = gson.toJson(session.explorer.getGameplay());
      assertEquals(
          Retcode.ROGUE_STATUS_NOT_MATCH.getVal(),
          InitialData46Test.field(session.start(List.of(199961), 0), "retcode"));
      assertEquals(before, gson.toJson(session.explorer.getGameplay()));
      assertEquals(11, session.explorer.getEntryId());
      assertEquals(List.of(199961), session.explorer.getCurrentLineup().getAvatars());
      release(session.explorer, kind);
      assertEquals(0, InitialData46Test.field(session.start(List.of(199961), 0), "retcode"));
    } finally {
      fixture.reset();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"clock", "rhythm", "parkour", "instance"})
  void nativePausedUniverseResumeKeepsBothSavedActivitiesWhenBlocked(String kind) throws Exception {
    var fixture = new Universe46LifecycleTest();
    fixture.setup();
    try {
      var session = new Universe46LifecycleTest.ExplorerSession();
      assertEquals(0, InitialData46Test.field(session.start(List.of(199961), 0), "retcode"));
      assertEquals(0, InitialData46Test.field(session.call("LeaveRogueCsReq", null), "retcode"));
      // A saved overlap from the old admission paths is retained until the other activity ends.
      occupy(session.explorer, kind);
      String before = gson.toJson(session.explorer.getGameplay());
      assertEquals(
          Retcode.ROGUE_STATUS_NOT_MATCH.getVal(),
          InitialData46Test.field(
              session.call("EnterRogueCsReq", Universe46LifecycleTest.AREA), "retcode"));
      assertEquals(before, gson.toJson(session.explorer.getGameplay()));
      assertEquals(11, session.explorer.getEntryId());
      release(session.explorer, kind);
      assertEquals(
          0,
          InitialData46Test.field(
              session.call("EnterRogueCsReq", Universe46LifecycleTest.AREA), "retcode"));
    } finally {
      fixture.reset();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"clock", "rogue", "story", "return", "parkour", "instance"})
  void rhythmStartRespectsTheOtherActivityWithoutFreezingRewards(String kind) {
    int id = 19996801, group = 199968;
    var previous = GameData.getActivityPanelExcelMap().get(50022);
    GameData.getActivityPanelExcelMap()
        .add(gson.fromJson("{\"PanelID\":50022}", ActivityPanelExcel.class));
    var window = new GameplayDefinitions.Window(50022, 0, 0, Long.MAX_VALUE);
    GameplayDefinitions.windows.add(window);
    GameplayDefinitions.musicLevels.put(
        id, new GameplayDefinitions.MusicLevel(id, group, 1, List.of(1, 2, 3), List.of(0, 0, 0)));
    GameplayDefinitions.musicGroups.put(
        group, new GameplayDefinitions.MusicGroup(group, 1, 0, List.of(11)));
    try {
      var player = opening.player;
      if (Set.of("clock", "parkour", "instance").contains(kind)) occupy(player, kind);
      else if (kind.equals("rogue")) {
        var run = new GameplayState.RogueRun(19996, 1999601, Map.of(1, 19996001));
        run.enter(1, 19996001);
        run.setInside(true);
        player.getGameplay().setRogue(run);
      } else {
        // Existing persistent story transition states; admission must fail before reading a plan.
        var place = new StoryLineState.Place(new UniverseLifecycleTest.Explorer());
        player.getGameplay().getStoryLines().begin(199931, place, place);
        if (kind.equals("return")) player.getGameplay().getStoryLines().begin(0, place, place);
      }
      String before = gson.toJson(player.getGameplay());
      assertEquals(Retcode.MUSIC_LOCKED, new MusicManager(player).start(id));
      assertEquals(before, gson.toJson(player.getGameplay()));
      assertEquals(0, player.getGameplay().getRhythm().getActiveLevel());
    } finally {
      GameplayDefinitions.windows.remove(window);
      GameplayDefinitions.musicLevels.remove(id);
      GameplayDefinitions.musicGroups.remove(group);
      GameData.getActivityPanelExcelMap().keySet().remove(50022);
      if (previous != null) GameData.getActivityPanelExcelMap().add(previous);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"clock", "story", "return", "instance"})
  void nativeParkourLevelWaitsForTheCurrentActivity(String kind) throws Exception {
    var fixture = new Parkour46Test();
    fixture.setup();
    try {
      fixture.unlock();
      occupy(fixture.s.racer, kind);
      String before = gson.toJson(fixture.s.racer.getGameplay());
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), fixture.start(Parkour46Test.LEVEL, 1));
      assertEquals(before, gson.toJson(fixture.s.racer.getGameplay()));
      release(fixture.s.racer, kind);
      assertEquals(0, fixture.start(Parkour46Test.LEVEL, 1));
    } finally {
      fixture.clear();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"clock", "instance"})
  void nativeParkourVenueCannotMoveTheOccupiedPlayerToAnotherScene(String kind) throws Exception {
    var fixture = new ParkourRaid46Test();
    fixture.setup();
    try {
      occupy(fixture.s.racer, kind);
      String before = gson.toJson(fixture.s.racer.getGameplay());
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), fixture.start(ParkourRaid46Test.RAID, 0));
      assertEquals(before, gson.toJson(fixture.s.racer.getGameplay()));
      assertEquals(ParkourRaid46Test.HOME, fixture.s.racer.entry);
      release(fixture.s.racer, kind);
      assertEquals(0, fixture.start(ParkourRaid46Test.RAID, 0));
    } finally {
      fixture.clear();
    }
  }

  @Test
  void nativeMissionRaidKeepsTheClockRunAndFormalRoster() throws Exception {
    var fixture = new RaidHybridFormal46Test();
    fixture.root = root.resolve("raid");
    java.nio.file.Files.createDirectories(fixture.root);
    fixture.setup();
    try {
      var session = fixture.owned();
      // Start the source clock in its own configured root, then retain that active receipt.
      var config = emu.lunarcore.LunarCore.getConfig();
      String previous = config.getResourceDir();
      config.resourceDir = root.toString();
      occupy(session.hybrid, "clock");
      config.resourceDir = previous;
      String before = gson.toJson(session.hybrid.getGameplay());
      assertEquals(
          Retcode.REQ_PARA_INVALID.getVal(),
          fixture.selectedStart(session, "AvatarType_AvatarFormalType"));
      assertEquals(before, gson.toJson(session.hybrid.getGameplay()));
      assertEquals(MissionRaid46Test.HOME, session.hybrid.entry);
      assertEquals(List.of(1209), session.hybrid.getCurrentLineup().getAvatars());
    } finally {
      fixture.cleanup();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"story", "return", "parkour", "instance"})
  void clockStartWaitsForStoryBranchOrItsReservedReturn(String kind) {
    opening.unlock(1);
    occupy(opening.player, kind);
    String before = gson.toJson(opening.player.getGameplay());
    assertEquals(Retcode.SYSTEM_BUSY, new ClockParkManager(opening.player).start(1).code());
    assertEquals(before, gson.toJson(opening.player.getGameplay()));
  }
}
