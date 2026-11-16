package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class TrainCakeRandomBatchTest {
  @Test void seededDrawsCoverAllCatalogCatsAndAllSevenRealTailPlacements() throws Exception {
    Set<Integer> authoredSlots = new TreeSet<>();
    try (var stream = getClass().getResourceAsStream("/fixtures/train-cake-tail-slots-4.6.json")) {
      var source = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
      for (var row : source.getAsJsonArray("placements"))
        assertTrue(authoredSlots.add(row.getAsJsonObject().get("slot").getAsInt()));
    }
    assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7), authoredSlots);
    var clientSlot = TrainCakeCatch.class.getMethod("clientSlot", int.class);
    var random = new Random(381042L);
    var cats = new TreeSet<Integer>();
    var slots = new TreeSet<Integer>();
    for (int week = 0; week < 160; week++) {
      var batch = TrainCakeCatch.randomBatch(random);
      assertEquals(3, batch.size());
      var localCats = new HashSet<Integer>(); var localSlots = new HashSet<Integer>();
      for (var spot : batch) {
        assertTrue(localCats.add(spot.getCat()));
        int physicalSlot = (int) clientSlot.invoke(null, spot.getSlot());
        assertTrue(localSlots.add(physicalSlot));
        cats.add(spot.getCat()); slots.add(physicalSlot);
      }
    }
    var all = new TreeSet<Integer>();
    for (int id = 1; id <= 27; id++) all.add(id);
    assertEquals(all, cats);
    assertEquals(authoredSlots, slots, "Every drawn slot must have a real scene prop");
  }
}
