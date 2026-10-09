package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import org.hkrpg.server.gameplay.GameplayState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native CreateDicePlacement reads DiceValue at +0x1c, not DiceIndex at +0x18. */
class ClockParkPlacedFaces46Test {
  @TempDir Path root;
  final ClockParkDice46Test fixture = new ClockParkDice46Test();

  @BeforeEach
  void prepare() throws Exception {
    fixture.root = root;
    fixture.prepare();
  }

  @AfterEach
  void restore() throws Exception {
    fixture.restore();
  }

  void faces(int... faces) throws Exception {
    var gameplay =
        JsonParser.parseString(fixture.opening.gson.toJson(fixture.opening.player.getGameplay()))
            .getAsJsonObject();
    var values = new JsonArray();
    for (int face : faces) values.add(face);
    gameplay.getAsJsonObject("clockPark").getAsJsonObject("active").add("dice", values);
    fixture.opening.replaceGameplay(fixture.opening.gson.fromJson(gameplay, GameplayState.class));
    assertEquals(0, fixture.opening.code());
  }

  void submit(int script, int[] placed) throws Exception {
    fixture.submit(
        fixture.placement(
            script, fixture.first(script), placed, new int[] {1001, 1002, 1003}, false, false));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5})
  void nativeFaceValuesAdvanceAllFiveOriginalScripts(int script) throws Exception {
    fixture.begin(script);
    faces(4, 5, 6);
    submit(script, new int[] {4, 5, 6});
    assertEquals(0, fixture.opening.code());
    var attrs = fixture.nested(fixture.query(), "JOFJEIFLGBP");
    assertEquals(10, fixture.number(attrs, "JEKOHIHJFMK"));
    assertEquals(5, fixture.number(attrs, "AKKLILNJCPL"));
  }

  @Test
  void duplicateFacesUseDistinctSavedDiceWithMatchingMultiplicity() throws Exception {
    fixture.begin(1);
    faces(2, 2, 5);
    submit(1, new int[] {2, 2, 5});
    assertEquals(0, fixture.opening.code());
    var attrs = fixture.nested(fixture.query(), "JOFJEIFLGBP");
    assertEquals(7, fixture.number(attrs, "JEKOHIHJFMK"));
    assertEquals(2, fixture.number(attrs, "AKKLILNJCPL"));
  }

  @Test
  void reorderedFacesChangeCardEffectsWhileKeepingSavedDiceOwnership() throws Exception {
    fixture.begin(1);
    faces(4, 5, 6);
    submit(1, new int[] {6, 4, 5});
    assertEquals(0, fixture.opening.code());
    var attrs = fixture.nested(fixture.query(), "JOFJEIFLGBP");
    assertEquals(11, fixture.number(attrs, "JEKOHIHJFMK"));
    assertEquals(4, fixture.number(attrs, "AKKLILNJCPL"));
  }

  @Test
  void syntheticZeroBasedIndicesCannotStandInForNativeFaceValues() throws Exception {
    fixture.begin(1);
    faces(4, 5, 6);
    String before = fixture.opening.gson.toJson(fixture.opening.player.getGameplay());
    submit(1, new int[] {0, 1, 2});
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), fixture.opening.code());
    assertEquals(before, fixture.opening.gson.toJson(fixture.opening.player.getGameplay()));
  }

  @Test
  void repeatedFaceWithoutAnotherOwnedDieCannotBePlacedTwice() throws Exception {
    fixture.begin(1);
    faces(4, 5, 6);
    String before = fixture.opening.gson.toJson(fixture.opening.player.getGameplay());
    submit(1, new int[] {4, 4, 6});
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), fixture.opening.code());
    assertEquals(before, fixture.opening.gson.toJson(fixture.opening.player.getGameplay()));
  }

  @Test
  void matchingSumCannotReplaceAnUnownedFaceCombination() throws Exception {
    fixture.begin(1);
    faces(2, 4, 6);
    String before = fixture.opening.gson.toJson(fixture.opening.player.getGameplay());
    submit(1, new int[] {1, 5, 6});
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), fixture.opening.code());
    assertEquals(before, fixture.opening.gson.toJson(fixture.opening.player.getGameplay()));
  }
}
