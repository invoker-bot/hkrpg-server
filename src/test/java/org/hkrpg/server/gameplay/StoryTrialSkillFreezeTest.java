package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class StoryTrialSkillFreezeTest {
  @TempDir Path root;
  final StoryLineLifecycleTest fixture = new StoryLineLifecycleTest();
  List<AvatarSkillTreeExcel> oldRows;
  Map<Long, AvatarSkillTreeExcel> oldCache;
  java.lang.reflect.Field configField;
  Object oldConfig;

  @BeforeEach
  void install() throws Exception {
    fixture.root = root;
    fixture.prepare();
    oldRows = new ArrayList<>(GameData.getAvatarSkillTreeExcelMap().values());
    oldCache = new HashMap<>(GameDepot.getAvatarSkillTreeExcels());
    GameData.getAvatarSkillTreeExcelMap().values().clear();
    configField = emu.lunarcore.LunarCore.class.getDeclaredField("config");
    configField.setAccessible(true);
    oldConfig = configField.get(null);
    var config = new emu.lunarcore.Config();
    config.resourceDir = root.toString();
    configField.set(null, config);
  }

  @AfterEach
  void restore() throws Exception {
    configField.set(null, oldConfig);
    GameData.getAvatarSkillTreeExcelMap().values().clear();
    oldRows.forEach(GameData.getAvatarSkillTreeExcelMap()::add);
    GameDepot.getAvatarSkillTreeExcels().clear();
    GameDepot.getAvatarSkillTreeExcels().putAll(oldCache);
    fixture.restore();
  }

  AvatarSkillTreeExcel skill(int anchor) {
    var row =
        fixture.gson.fromJson(
            "{\"AvatarID\":199931,\"PointID\":"
                + (19993100 + anchor)
                + ",\"Level\":1,\"MaxLevel\":1,\"AnchorType\":\"Point"
                + String.format("%02d", anchor)
                + "\",\"DefaultUnlock\":true}",
            AvatarSkillTreeExcel.class);
    GameData.getAvatarSkillTreeExcelMap().add(row);
    row.onLoad();
    return row;
  }

  StoryLineLifecycleTest.Director started() {
    var player = fixture.player(true);
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(player));
    return player;
  }

  StoryLineLifecycleTest.Director reload(StoryLineLifecycleTest.Director saved) throws Exception {
    var restored = fixture.player(true);
    var field = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(
        restored,
        fixture.gson.fromJson(fixture.gson.toJson(saved.getGameplay()), GameplayState.class));
    return restored;
  }

  @Test
  void restoringAStoryPartyCannotAddNewResourceSkillsToItsFrozenActors() throws Exception {
    skill(1);
    var saved = started();
    skill(2);
    var restored = reload(saved);
    assertTrue(restored.getStoryLineManager().restoreOnLogin());
    var actor = restored.getAvatarById(StoryLineLifecycleTest.SPECIAL);
    assertEquals(Map.of(1, 1), new HashMap<>(actor.getSkillTree()));
    assertEquals(
        1, actor.toBattleProto(restored.getCurrentLineup(), 0).getSkilltreeList().length());
  }

  @Test
  void missingFrozenStorySkillsAreRejectedBeforeInstallingAnIncompleteParty() throws Exception {
    var row = skill(1);
    var saved = started();
    GameData.getAvatarSkillTreeExcelMap().values().remove(row);
    GameDepot.getAvatarSkillTreeExcels().values().removeIf(value -> value == row);
    var restored = reload(saved);
    assertThrows(
        IllegalStateException.class, () -> restored.getStoryLineManager().restoreOnLogin());
    assertTrue(restored.getAvatars().getTempAvatars().isEmpty());
    assertTrue(
        restored.getGameplay().getStoryLines().getRuns().containsKey(StoryLineLifecycleTest.STORY));
  }

  @Test
  void customStoryPresetIsRestoredFromItsSnapshotAfterTheLiveFileIsRemoved() throws Exception {
    skill(1);
    var path = root.resolve("Config/BattleLineupPage/SkillTreePointPreset/story.json");
    Files.createDirectories(path.getParent());
    Files.writeString(
        path, "{\"Name\":\"story\",\"PresetList\":[{\"Anchor\":\"Point01\",\"Level\":1}]}");
    var json =
        fixture
            .gson
            .toJsonTree(GameData.getSpecialAvatarExcelMap().get(StoryLineLifecycleTest.SPECIAL, 0))
            .getAsJsonObject();
    json.addProperty("SkillTreeTemplate", "TYPE_CUSTOM");
    json.addProperty("CustomSkillTreeKey", "story");
    GameData.getSpecialAvatarExcelMap().add(fixture.gson.fromJson(json, SpecialAvatarExcel.class));
    var saved = started();
    Files.delete(path);
    var restored = reload(saved);
    assertTrue(restored.getStoryLineManager().restoreOnLogin());
    assertEquals(
        Map.of(1, 1),
        new HashMap<>(restored.getAvatarById(StoryLineLifecycleTest.SPECIAL).getSkillTree()));
  }

  @Test
  void aNewIncompletePresetNodeCannotBlockAnOtherwiseValidFrozenStoryActor() throws Exception {
    skill(1);
    var path = root.resolve("Config/BattleLineupPage/SkillTreePointPreset/story.json");
    Files.createDirectories(path.getParent());
    Files.writeString(
        path,
        "{\"Name\":\"story\",\"PresetList\":[{\"Anchor\":\"Point01\",\"Level\":1},{\"Anchor\":\"Point02\",\"LevelType\":\"MaxLevel\"}]}");
    var config =
        fixture
            .gson
            .toJsonTree(GameData.getSpecialAvatarExcelMap().get(StoryLineLifecycleTest.SPECIAL, 0))
            .getAsJsonObject();
    config.addProperty("SkillTreeTemplate", "TYPE_CUSTOM");
    config.addProperty("CustomSkillTreeKey", "story");
    GameData.getSpecialAvatarExcelMap()
        .add(fixture.gson.fromJson(config, SpecialAvatarExcel.class));
    var saved = started();
    var added = fixture.gson.toJsonTree(skill(2)).getAsJsonObject();
    added.addProperty("MaxLevel", 2);
    var row = fixture.gson.fromJson(added, AvatarSkillTreeExcel.class);
    GameData.getAvatarSkillTreeExcelMap().add(row);
    row.onLoad();
    assertNotEquals(emu.lunarcore.server.packet.Retcode.SUCC, fixture.enter(fixture.player(true)));
    var restored = reload(saved);
    assertTrue(restored.getStoryLineManager().restoreOnLogin());
    assertEquals(
        Map.of(1, 1),
        new HashMap<>(restored.getAvatarById(StoryLineLifecycleTest.SPECIAL).getSkillTree()));
  }
}
