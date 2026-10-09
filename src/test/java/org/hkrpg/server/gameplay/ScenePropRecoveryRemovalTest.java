package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.excel.PropExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.scene.entity.EntityProp;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Original recovery props; scene removal must not impersonate an actual player destruction. */
class ScenePropRecoveryRemovalTest {
  @TempDir Path root;
  final MazeCasterBuffRemovalTest fixture = new MazeCasterBuffRemovalTest();
  MazeCasterBuffRemovalTest.Capture player;
  GameAvatar actor;

  @BeforeEach
  void prepare() throws Exception {
    fixture.root = root;
    player = fixture.owner(false);
    actor =
        new GameAvatar(player.getCurrentLeaderAvatar().getExcel()) {
          @Override
          public void save() {}
        };
    actor.setOwner(player);
    player.getAvatars().getAvatars().put(actor.getAvatarId(), actor);
    player.getScene().syncLineup();
    player.getCurrentLineup().setMp(1);
    actor.setCurrentHp(player.getCurrentLineup(), 4000);
    player.sent.clear();
  }

  @AfterEach
  void restore() throws Exception {
    fixture.cleanup();
  }

  EntityProp prop(int id) throws Exception {
    JsonObject raw;
    try (var in = getClass().getResourceAsStream("/fixtures/rogue-prop-recovery-4.6.json")) {
      assertNotNull(in);
      var rows =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonObject()
              .getAsJsonObject("rows")
              .getAsJsonArray("MazeProp");
      raw =
          rows.asList().stream()
              .map(JsonElement::getAsJsonObject)
              .filter(r -> r.get("ID").getAsInt() == id)
              .findFirst()
              .orElseThrow()
              .deepCopy();
    }
    raw.addProperty("PropName", raw.getAsJsonObject("PropName").get("Hash").getAsLong());
    var excel = fixture.gson.fromJson(raw, PropExcel.class);
    excel.onLoad();
    assertEquals(id == 100025, excel.isRecoverMp());
    assertEquals(id == 100024, excel.isRecoverHp());
    var prop = new EntityProp(player.getScene(), excel, null, null);
    prop.setGroupId(987);
    player.getScene().addEntity(prop, false);
    return prop;
  }

  void unchanged() {
    assertEquals(1, player.getCurrentLineup().getMp(), "A scene removal granted technique points");
    assertEquals(
        4000, actor.getCurrentHp(player.getCurrentLineup()), "A scene removal healed the actor");
  }

  @ParameterizedTest
  @CsvSource({
    "100024,object",
    "100025,object",
    "100024,id",
    "100025,id",
    "100024,group",
    "100025,group"
  })
  void administrativeRemovalAndMissionGroupUnloadingDoNotGrantRecovery(int id, String mode)
      throws Exception {
    var prop = prop(id);
    var scene = player.getScene();
    int entity = prop.getEntityId();
    switch (mode) {
      case "object" -> scene.removeEntity(prop);
      case "id" -> scene.removeEntity(entity);
      case "group" -> {
        var group =
            fixture.gson.fromJson(
                "{\"Category\":\"Mission\",\"LoadSide\":\"Server\",\"OwnerMainMissionID\":199999987,\"LoadOnInitial\":false}",
                GroupInfo.class);
        group.setId(987);
        scene.getFloorInfo().getGroups().put(987, group);
        scene.getLoadedGroups().add(987);
        assertFalse(player.getMissionManager().shouldLoadGroup(group, scene));
        scene.refreshMissionGroups();
        assertFalse(scene.getLoadedGroups().contains(987));
      }
      default -> fail(mode);
    }
    assertNull(scene.getEntityById(entity));
    assertEquals(0, prop.getEntityId());
    unchanged();
    scene.removeEntity(entity);
    unchanged();
  }

  @ParameterizedTest
  @ValueSource(ints = {100024, 100025})
  void actualOwnedDestructionStillGrantsRecoveryExactlyOnce(int id) throws Exception {
    var prop = prop(id);
    int entity = prop.getEntityId();
    assertTrue(player.getScene().destroyProp(prop));
    assertEquals(id == 100025 ? 3 : 1, player.getCurrentLineup().getMp());
    assertEquals(id == 100024 ? 6500 : 4000, actor.getCurrentHp(player.getCurrentLineup()));
    assertNull(player.getScene().getEntityById(entity));
    assertFalse(player.getScene().destroyProp(prop));
    assertEquals(id == 100025 ? 3 : 1, player.getCurrentLineup().getMp());
    assertEquals(id == 100024 ? 6500 : 4000, actor.getCurrentHp(player.getCurrentLineup()));
  }

  @ParameterizedTest
  @ValueSource(ints = {100024, 100025})
  void aPropFromTheDetachedSceneCannotGrantRecovery(int id) throws Exception {
    var prop = prop(id);
    var old = player.getScene();
    assertTrue(
        player.loadScene(
            StoryLineLifecycleTest.PLANE,
            StoryLineLifecycleTest.FLOOR,
            StoryLineLifecycleTest.ENTRY,
            new emu.lunarcore.util.Position(),
            new emu.lunarcore.util.Position(),
            false,
            4));
    assertNotSame(old, player.getScene());
    assertFalse(old.destroyProp(prop));
    unchanged();
  }
}
