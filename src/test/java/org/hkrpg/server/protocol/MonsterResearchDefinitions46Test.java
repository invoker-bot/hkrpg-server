package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.InflaterInputStream;
import org.hkrpg.server.gameplay.MaterialSubmitDefinitions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Original seven orders remain distinct from the newer material-submission activities. */
class MonsterResearchDefinitions46Test {
  @TempDir Path root;

  @BeforeEach
  void copyPinnedSources() throws Exception {
    try (var stream = getClass().getResourceAsStream("/fixtures/material-submit-activity-4.6.json")) {
      assertNotNull(stream);
      var raw = stream.readAllBytes();
      assertEquals("455304ad8bd49e783b91337bd1824d1b6bf843a368f97e1fa40a40577f290d64", sha(raw));
      var fixture = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
      for (var source : fixture.getAsJsonObject("files").entrySet()) {
        var row = source.getValue().getAsJsonObject();
        try (var inflated = new InflaterInputStream(new ByteArrayInputStream(
            Base64.getDecoder().decode(row.get("rawBase64").getAsString())))) {
          var bytes = inflated.readAllBytes();
          assertEquals(row.get("sha256").getAsString(), sha(bytes));
          var target = root.resolve(source.getKey());
          Files.createDirectories(target.getParent());
          Files.write(target, bytes);
        }
      }
    }
  }

  private static String sha(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  @Test
  void loadsTheSevenOriginalResearchOrdersWithoutInventingAnActivityPanel() {
    var source = MaterialSubmitDefinitions.load(root, 30005);
    assertNotNull(source);
    assertEquals(1, source.firstId());
    assertEquals(7, source.lastId());
    assertEquals(7, source.rows().size());
    var first = source.rows().get(1);
    assertEquals(3000501, first.module());
    assertEquals(8000170, first.mission());
    assertEquals(3100301, first.reward());
    assertEquals(Map.of(111011, 1, 181002, 1, 180005, 1), first.costs());
    assertEquals(Map.of(1, 70, 2, 30000, 110112, 3, 402017, 1), first.rewards());
    var seventh = source.rows().get(7);
    assertEquals(8000177, seventh.mission());
    assertEquals(3100307, seventh.reward());
  }

  @Test
  void refusesChangedOrMissingOriginalSources() throws Exception {
    Files.writeString(root.resolve("ExcelOutput/MaterialSubmitter.json"), "[]");
    assertNull(MaterialSubmitDefinitions.load(root, 30005));
  }

  @Test
  void newerMaterialActivitiesStillUseTheirOwnSevenOrderRanges() {
    var belobog = MaterialSubmitDefinitions.load(root, 30011);
    var amphoreus = MaterialSubmitDefinitions.load(root, 30014);
    assertNotNull(belobog);
    assertNotNull(amphoreus);
    assertEquals(201, belobog.firstId());
    assertEquals(207, belobog.lastId());
    assertEquals(301, amphoreus.firstId());
    assertEquals(307, amphoreus.lastId());
    assertNull(MaterialSubmitDefinitions.load(root, 30009));
  }
}
