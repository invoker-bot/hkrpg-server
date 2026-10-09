package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Source-only catalogue tests; not client path selection or resonance acceptance. */
class RogueAeonCatalogTest {
  @TempDir Path root;
  JsonObject proof;
  Object previous;
  java.lang.reflect.Field cache;

  @BeforeEach
  void prepare() throws Exception {
    cache = RogueAeonCatalog.class.getDeclaredField("catalogue");
    cache.setAccessible(true);
    previous = cache.get(null);
    try (var in = getClass().getResourceAsStream("/fixtures/rogue-aeon-catalogue-4.6.json")) {
      assertNotNull(in);
      proof =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
    Files.createDirectories(root.resolve("ExcelOutput"));
  }

  @AfterEach
  void restore() throws Exception {
    cache.set(null, previous);
  }

  void write(JsonElement rows) throws Exception {
    Files.writeString(
        root.resolve("ExcelOutput/RogueAeon.json"), rows.toString(), StandardCharsets.UTF_8);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9})
  void readsEveryCompleteOriginalRowWithoutDroppingUnknownSourceInformation(int id)
      throws Exception {
    write(proof.getAsJsonArray("aeons"));
    RogueAeonCatalog.load(root);
    var row = RogueAeonCatalog.find(id).orElse(null);
    assertNotNull(row, "The original path row must be loaded");
    var original = proof.getAsJsonArray("aeons").get(id - 1).getAsJsonObject();
    assertEquals(id, row.id());
    assertEquals(1, row.version());
    assertEquals(original.get("Sort").getAsInt(), row.sort());
    assertEquals(original.get("DisplayID").getAsInt(), row.display());
    assertEquals(original.get("RogueBuffType").getAsInt(), row.buffType());
    assertEquals(original.has("UnlockID") ? original.get("UnlockID").getAsInt() : 0, row.unlock());
    assertEquals(
        original.has("ArrivedTalkDialogueGroupID")
            ? original.get("ArrivedTalkDialogueGroupID").getAsInt()
            : 0,
        row.dialogue());
    assertEquals(original.get("BattleEventBuffGroup").getAsInt(), row.basicGroup());
    assertEquals(original.get("BattleEventEnhanceBuffGroup").getAsInt(), row.enhanceGroup());
    assertEquals(
        original.toString(), row.sourceJson(), "Preserve uint64 hashes as exact source text");
  }

  @Test
  void optionalMissingTableRemovesPreviousCatalogue() throws Exception {
    write(proof.getAsJsonArray("aeons"));
    RogueAeonCatalog.load(root);
    assertTrue(RogueAeonCatalog.find(1).isPresent());
    Files.delete(root.resolve("ExcelOutput/RogueAeon.json"));
    RogueAeonCatalog.load(root);
    assertTrue(RogueAeonCatalog.find(1).isEmpty());
  }

  @Test
  void nullPlayerDoesNotAdvertiseUnlockedPaths() throws Exception {
    write(proof.getAsJsonArray("aeons"));
    RogueAeonCatalog.load(root);
    assertEquals(List.of(), RogueAeonCatalog.available(null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "null", "[null]", "[1]", "[{}]", "malformed"})
  void corruptTableFailsClosedAndDoesNotRetainAnEarlierCatalogue(String shape) throws Exception {
    write(proof.getAsJsonArray("aeons"));
    RogueAeonCatalog.load(root);
    Files.writeString(root.resolve("ExcelOutput/RogueAeon.json"), shape, StandardCharsets.UTF_8);
    assertThrows(IllegalStateException.class, () -> RogueAeonCatalog.load(root));
    assertTrue(RogueAeonCatalog.find(1).isEmpty());
  }

  @Test
  void duplicateIdentityDoesNotPublishPartOfTheTable() throws Exception {
    var rows = proof.getAsJsonArray("aeons").deepCopy();
    rows.add(rows.get(0).deepCopy());
    write(rows);
    assertThrows(IllegalStateException.class, () -> RogueAeonCatalog.load(root));
    assertTrue(RogueAeonCatalog.find(1).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "fraction",
        "string",
        "overflow",
        "zero",
        "unknownVersion",
        "missingGroup",
        "nullHash",
        "hashOverflow"
      })
  void invalidOriginalFieldsDoNotBecomeUsablePathRows(String mode) throws Exception {
    var rows = proof.getAsJsonArray("aeons").deepCopy();
    var row = rows.get(0).getAsJsonObject();
    switch (mode) {
      case "fraction" -> row.addProperty("AeonID", 1.5);
      case "string" -> row.addProperty("AeonID", "1");
      case "overflow" -> row.addProperty("AeonID", Long.MAX_VALUE);
      case "zero" -> row.addProperty("AeonID", 0);
      case "unknownVersion" -> row.addProperty("RogueVersion", 2);
      case "missingGroup" -> row.remove("BattleEventBuffGroup");
      case "nullHash" -> row.add("EffectDesc1", JsonNull.INSTANCE);
      case "hashOverflow" ->
          row.getAsJsonObject("EffectDesc1")
              .addProperty("Hash", new java.math.BigInteger("18446744073709551616"));
      default -> fail(mode);
    }
    write(rows);
    assertThrows(IllegalStateException.class, () -> RogueAeonCatalog.load(root));
    assertTrue(RogueAeonCatalog.find(1).isEmpty());
  }
}
