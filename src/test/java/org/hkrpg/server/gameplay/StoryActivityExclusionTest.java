package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Activity occupancy is a fixture; accepted story plan and actual scene/party are exercised. */
class StoryActivityExclusionTest {
  @TempDir Path root;
  final StoryLineLifecycleTest story = new StoryLineLifecycleTest();

  @BeforeEach
  void setup() throws Exception {
    story.root = root;
    story.prepare();
  }

  @AfterEach
  void restore() throws Exception {
    story.restore();
  }

  @ParameterizedTest
  @ValueSource(strings = {"clock", "rhythm", "parkour"})
  void acceptedStoryWaitsWithoutReservingAnotherSceneOrTrialRoster(String kind) throws Exception {
    var player = story.player(true);
    if (kind.equals("clock")) {
      var fixture =
          JsonParser.parseString(
                  new String(
                      Objects.requireNonNull(
                              getClass()
                                  .getResourceAsStream("/fixtures/clock-park-catalog-4.6.json"))
                          .readAllBytes(),
                      StandardCharsets.UTF_8))
              .getAsJsonObject();
      for (var e : fixture.getAsJsonObject("files").entrySet()) {
        var file = root.resolve(e.getKey());
        Files.createDirectories(file.getParent());
        Files.write(
            file,
            HexFormat.of().parseHex(e.getValue().getAsJsonObject().get("rawHex").getAsString()));
      }
      var catalog = ClockParkCatalog.load(root);
      assertNotNull(catalog);
      player.getGameplay().getClockPark().start(catalog.scripts().get(1), catalog);
    } else if (kind.equals("parkour"))
      player.getGameplay().getParkour().start(19996801, 1, 4, 199968001, 0, Long.MAX_VALUE, 0);
    else player.getGameplay().getRhythm().start(19996801, List.of(Map.of(), Map.of(), Map.of()));
    var scene = player.getScene();
    var lineup = player.getCurrentLineup();
    String before = story.gson.toJson(player.getGameplay());
    int saves = player.saves;
    assertFalse(player.getStoryLineManager().canChange(StoryLineLifecycleTest.STORY, 0, 0, 0));
    assertEquals(Retcode.SYSTEM_BUSY, story.enter(player));
    assertEquals(before, story.gson.toJson(player.getGameplay()));
    assertEquals(saves, player.saves);
    assertSame(scene, player.getScene());
    assertSame(lineup, player.getCurrentLineup());
    assertEquals(List.of(StoryLineLifecycleTest.ACTOR), lineup.getAvatars());
    assertNull(player.getAvatarById(StoryLineLifecycleTest.SPECIAL));
    if (kind.equals("clock")) player.getGameplay().getClockPark().quit();
    else if (kind.equals("parkour")) player.getGameplay().getParkour().close();
    else player.getGameplay().getRhythm().finish(0, false);
    assertTrue(player.getStoryLineManager().canChange(StoryLineLifecycleTest.STORY, 0, 0, 0));
    assertEquals(Retcode.SUCC, story.enter(player));
    assertEquals(4, player.getScene().getDimensionId());
    assertEquals(List.of(StoryLineLifecycleTest.SPECIAL), player.getCurrentLineup().getAvatars());
    assertEquals(List.of(StoryLineLifecycleTest.ACTOR), lineup.getAvatars());
  }
}
