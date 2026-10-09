package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.game.player.Player;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Complete original groups and tags; battle buffs below use an explicit isolated catalogue. */
class RogueBuffTags46Test {
  JsonObject proof;
  final Gson gson = new Gson();
  final List<GameplayDefinitions.Blessing> previous = new ArrayList<>();
  final Map<Integer, GameplayDefinitions.RogueBuffGroup> oldGroups = new HashMap<>();

  @BeforeEach
  void prepare() throws Exception {
    previous.addAll(GameplayDefinitions.blessings);
    oldGroups.putAll(GameplayDefinitions.rogueBuffGroups);
    GameplayDefinitions.blessings.clear();
    GameplayDefinitions.rogueBuffGroups.clear();
    try (var in = getClass().getResourceAsStream("/fixtures/rogue-buff-tags-4.6.json")) {
      assertNotNull(in);
      proof =
          JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
    for (var raw : proof.getAsJsonArray("groups")) {
      var row = gson.fromJson(raw, GameplayDefinitions.RogueBuffGroup.class);
      GameplayDefinitions.rogueBuffGroups.put(row.GMLOGNJAIGI(), row);
    }
    for (var raw : proof.getAsJsonArray("buffs"))
      GameplayDefinitions.blessings.add(gson.fromJson(raw, GameplayDefinitions.Blessing.class));
  }

  @AfterEach
  void restore() {
    GameplayDefinitions.blessings.clear();
    GameplayDefinitions.blessings.addAll(previous);
    GameplayDefinitions.rogueBuffGroups.clear();
    GameplayDefinitions.rogueBuffGroups.putAll(oldGroups);
  }

  Set<Integer> expected(int id) {
    var out = new TreeSet<Integer>();
    var group =
        proof.getAsJsonArray("groups").asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(r -> r.get("GMLOGNJAIGI").getAsInt() == id)
            .findFirst()
            .orElseThrow();
    for (var child : group.getAsJsonArray("HECJCAMDGNO")) {
      var leaf =
          proof.getAsJsonArray("buffs").asList().stream()
              .map(JsonElement::getAsJsonObject)
              .filter(r -> r.get("RogueBuffTag").getAsInt() == child.getAsInt())
              .findFirst();
      if (leaf.isPresent()) out.add(leaf.get().get("MazeBuffID").getAsInt());
      else out.addAll(expected(child.getAsInt()));
    }
    return out;
  }

  @ParameterizedTest
  @ValueSource(ints = {100005, 12004, 12005, 12604, 12605})
  void originalRecursiveGroupsExpandTagsIntoActualMazeBuffIds(int id) {
    assertFalse(expected(id).isEmpty());
    assertEquals(
        Optional.of(expected(id)), RogueTalentEffects.group(id), "A tag is not a MazeBuffID");
  }

  @Test
  void mazeBuffIdCannotImpersonateAnOriginalTag() {
    GameplayDefinitions.rogueBuffGroups.put(
        77777, new GameplayDefinitions.RogueBuffGroup(77777, List.of(612020)));
    assertTrue(
        RogueTalentEffects.group(77777).isEmpty(),
        "Original tagged rows forbid numeric ID aliases");
  }

  @Test
  void duplicateOriginalTagCannotChooseAnArbitraryBuff() {
    var raw =
        proof.getAsJsonArray("buffs").asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(r -> r.get("MazeBuffID").getAsInt() == 612020)
            .findFirst()
            .orElseThrow()
            .deepCopy();
    raw.addProperty("MazeBuffID", 612021);
    GameplayDefinitions.blessings.add(gson.fromJson(raw, GameplayDefinitions.Blessing.class));
    assertTrue(RogueTalentEffects.group(12004).isEmpty());
  }

  @Test
  void productionRowRetainsTagAeonAndBattleEventKind() {
    var row =
        GameplayDefinitions.blessings.stream()
            .filter(r -> r.MazeBuffID() == 612020)
            .findFirst()
            .orElseThrow();
    var saved = gson.toJsonTree(row).getAsJsonObject();
    assertTrue(saved.has("RogueBuffTag"));
    assertEquals(1202001, saved.get("RogueBuffTag").getAsInt());
    assertEquals(1, saved.get("AeonID").getAsInt());
    assertEquals("BattleEventBuff", saved.get("BattleEventBuffType").getAsString());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 7})
  void ordinaryVictoryNeverRandomlyOffersResonanceOrEnhancements(int aeon) {
    var wanted = expected(aeon == 1 ? 12004 : 12604);
    wanted.addAll(expected(aeon == 1 ? 12005 : 12605));
    int normalId = proof.getAsJsonArray("buffs").asList().stream()
        .map(JsonElement::getAsJsonObject).filter(r -> !r.has("BattleEventBuffType"))
        .findFirst().orElseThrow().get("MazeBuffID").getAsInt();
    var normal = GameplayDefinitions.blessings.stream()
        .filter(r -> r.MazeBuffID() == normalId).findFirst().orElseThrow();
    wanted.add(normal.MazeBuffID());
    GameplayDefinitions.blessings.removeIf(r -> !wanted.contains(r.MazeBuffID()));
    for (var row : GameplayDefinitions.blessings)
      GameData.getMazeBuffExcelMap()
          .add(gson.fromJson("{\"ID\":" + row.MazeBuffID() + ",\"Lv\":1}", MazeBuffExcel.class));
    var player =
        new Player() {
          public void save() {}
        };
    var run = new GameplayState.RogueRun(100, 1, Map.of(1, 77777));
    run.enter(1, 77777);
    run.clear();
    player.getGameplay().setRogue(run);
    var offered = player.getUniverseManager().offerBlessings();
    assertEquals(List.of(normal.MazeBuffID()), offered, "Resonance needs its own earned menu");
    assertTrue(run.getBlessings().isEmpty());
  }
}
