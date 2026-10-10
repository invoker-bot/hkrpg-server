package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original ball components omit InitState; no animation state or count is prewritten. */
class TimelineActivityMissingInitial46Test {
  @TempDir Path root;
  final TimelineActivity46Test fixture = new TimelineActivity46Test();

  @BeforeEach
  void load() throws Exception {
    fixture.root = root;
    fixture.load();
  }

  @AfterEach
  void restore() throws Exception {
    fixture.restore();
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029849})
  void originalComponentWithoutAuthoredInitialStateAcceptsItsChangedReport(int id)
      throws Exception {
    int plane = id == 6029846 ? 10401 : id == 6029847 ? 20411 : 20413;
    int group = plane == 10401 ? 96 : plane == 20411 ? 18 : 49;
    var original =
        fixture.source.getAsJsonArray("catalogGroups").asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(
                r ->
                    r.get("plane").getAsInt() == plane
                        && r.get("group").getAsInt() == group
                        && r.get("path").getAsString().endsWith("_G" + group + ".json"))
            .findFirst()
            .orElseThrow()
            .deepCopy();
    int instance = plane == 20413 ? 300003 : 300001;
    var prop =
        original.getAsJsonObject("definition").getAsJsonArray("PropList").asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(p -> p.get("ID").getAsInt() == instance)
            .findFirst()
            .orElseThrow();
    var tag =
        prop
            .getAsJsonObject("TagOverrideComponents")
            .getAsJsonArray("TagComponents")
            .asList()
            .stream()
            .map(JsonElement::getAsJsonObject)
            .filter(
                t ->
                    "RPG.GameCore.PropTagTimeRewindEntityComponent"
                        .equals(t.get("$type").getAsString()))
            .findFirst()
            .orElseThrow();
    assertFalse(tag.has("InitState"));
    original.addProperty("quest", id);
    original.addProperty("instance", instance);
    fixture.selected = original;
    fixture.activateSelected();
    fixture.accepted(id);
    assertEquals(0, fixture.arm(id));
    assertEquals(0, fixture.report("L0_Idle"));
    assertEquals(1, fixture.accepted(id).getProgress());
    assertEquals(0, fixture.arm(id));
    assertEquals(0, fixture.report("L0_Idle"));
    assertEquals(1, fixture.accepted(id).getProgress());
  }
}
