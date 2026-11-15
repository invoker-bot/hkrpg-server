package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class TrainCakeRandomBatchTest {
  @Test void seededDrawsCoverAllCatalogCatsAndAllSevenNativeSlots() {
    var random = new Random(381042L);
    var cats = new TreeSet<Integer>();
    var slots = new TreeSet<Integer>();
    for (int week = 0; week < 160; week++) {
      var batch = TrainCakeCatch.randomBatch(random);
      assertEquals(3, batch.size());
      var localCats = new HashSet<Integer>(); var localSlots = new HashSet<Integer>();
      for (var spot : batch) {
        assertTrue(localCats.add(spot.getCat()));
        assertTrue(localSlots.add(spot.getSlot()));
        cats.add(spot.getCat()); slots.add(spot.getSlot());
      }
    }
    var all = new TreeSet<Integer>();
    for (int id = 1; id <= 27; id++) all.add(id);
    assertEquals(all, cats);
    assertEquals(Set.of(0, 1, 2, 3, 4, 5, 6), slots);
  }
}
