package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MultiplePathAvatarExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.PlayerGender;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Isolated story lifecycle with explicit gender-bound source-shaped trial declarations. */
class StoryTrialGenderTest {
  @TempDir Path root;
  final StoryLineLifecycleTest fixture = new StoryLineLifecycleTest();
  List<MultiplePathAvatarExcel> paths;

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    fixture.prepare();
    paths = new ArrayList<>(GameData.getMultiplePathAvatarExcelMap().values());
  }

  @AfterEach
  void cleanup() throws Exception {
    GameData.getMultiplePathAvatarExcelMap().values().clear();
    paths.forEach(GameData.getMultiplePathAvatarExcelMap()::add);
    fixture.restore();
  }

  void gender(Player player, boolean female) throws Exception {
    var field = Player.class.getDeclaredField("gender");
    field.setAccessible(true);
    field.set(player, female ? PlayerGender.GENDER_WOMAN : PlayerGender.GENDER_MAN);
  }

  StoryLineLifecycleTest.Director started(boolean female, boolean bound) throws Exception {
    if (bound)
      GameData.getMultiplePathAvatarExcelMap()
          .add(
              fixture.gson.fromJson(
                  "{\"AvatarID\":199931,\"BaseAvatarID\":199931,\"Gender\":\""
                      + (female ? "GENDER_WOMAN" : "GENDER_MAN")
                      + "\"}",
                  MultiplePathAvatarExcel.class));
    var player = fixture.player(true);
    gender(player, female);
    assertEquals(Retcode.SUCC, fixture.enter(player));
    assertEquals(List.of(StoryLineLifecycleTest.SPECIAL), player.getCurrentLineup().getAvatars());
    return player;
  }

  StoryLineLifecycleTest.Director reload(StoryLineLifecycleTest.Director original)
      throws Exception {
    var restored = fixture.player(true);
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(
        restored,
        fixture.gson.fromJson(fixture.gson.toJson(original.getGameplay()), GameplayState.class));
    return restored;
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void cachedStoryActorsCannotRemainUsableAfterTheirGenderNoLongerMatches(boolean female)
      throws Exception {
    var player = started(female, true);
    gender(player, !female);
    assertThrows(IllegalStateException.class, player::getCurrentLineup);
    assertEquals(Retcode.SUCC, player.getStoryLineManager().change(0, 0, 0, 0, false));
    assertEquals(0, player.getGameplay().getStoryLines().getCurrentId());
    assertTrue(
        player.getGameplay().getStoryLines().getRuns().containsKey(StoryLineLifecycleTest.STORY));
    assertEquals(List.of(StoryLineLifecycleTest.ACTOR), player.getCurrentLineup().getAvatars());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aRestoredStoryCannotRepublishAnOppositeGenderActor(boolean female) throws Exception {
    var player = started(female, true);
    var restored = reload(player);
    gender(restored, !female);
    assertThrows(
        IllegalStateException.class, () -> restored.getStoryLineManager().restoreOnLogin());
    assertTrue(
        restored.getGameplay().getStoryLines().getRuns().containsKey(StoryLineLifecycleTest.STORY));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aPausedStoryCannotBypassTheOriginalActorGenderCheck(boolean female) throws Exception {
    var player = started(female, true);
    assertEquals(Retcode.SUCC, player.getStoryLineManager().change(0, 0, 0, 0, false));
    var restored = reload(player);
    gender(restored, !female);
    assertFalse(restored.getStoryLineManager().canChange(StoryLineLifecycleTest.STORY, 0, 0, 0));
    assertEquals(Retcode.PLAYER_DATA_ERROR, fixture.enter(restored));
    assertEquals(0, restored.getGameplay().getStoryLines().getCurrentId());
    assertEquals(List.of(StoryLineLifecycleTest.ACTOR), restored.getCurrentLineup().getAvatars());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aNeutralStoryActorRemainsUsableAcrossUnrelatedGenderChanges(boolean restored)
      throws Exception {
    var original = started(false, false);
    var player = restored ? reload(original) : original;
    gender(player, true);
    if (restored) assertTrue(player.getStoryLineManager().restoreOnLogin());
    assertEquals(List.of(StoryLineLifecycleTest.SPECIAL), player.getCurrentLineup().getAvatars());
    assertNotNull(player.getAvatarById(StoryLineLifecycleTest.SPECIAL));
  }
}
