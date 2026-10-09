package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.AvatarSkillTreeExcel;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TrialRunSkillFreezeTest {
  final TrialRunRecoveryTest fixture = new TrialRunRecoveryTest();
  List<AvatarSkillTreeExcel> oldRows;
  Map<Long, AvatarSkillTreeExcel> oldCache;
  @TempDir Path root;
  java.lang.reflect.Field configField;
  Object oldConfig;

  @BeforeEach
  void install() {
    fixture.install();
    oldRows = new ArrayList<>(GameData.getAvatarSkillTreeExcelMap().values());
    oldCache = new HashMap<>(GameDepot.getAvatarSkillTreeExcels());
    GameData.getAvatarSkillTreeExcelMap().values().clear();
  }

  @AfterEach
  void restore() throws Exception {
    if (configField != null) configField.set(null, oldConfig);
    GameData.getAvatarSkillTreeExcelMap().values().clear();
    oldRows.forEach(GameData.getAvatarSkillTreeExcelMap()::add);
    GameDepot.getAvatarSkillTreeExcels().clear();
    GameDepot.getAvatarSkillTreeExcels().putAll(oldCache);
    fixture.restore();
  }

  AvatarSkillTreeExcel skill(int anchor) {
    var row =
        fixture.gson.fromJson(
            "{\"AvatarID\":199951,\"PointID\":"
                + (19995100 + anchor)
                + ",\"Level\":1,\"MaxLevel\":1,\"AnchorType\":\"Point"
                + String.format("%02d", anchor)
                + "\",\"DefaultUnlock\":true}",
            AvatarSkillTreeExcel.class);
    GameData.getAvatarSkillTreeExcelMap().add(row);
    row.onLoad();
    return row;
  }

  TrialRunRecoveryTest.Origin savedTrial() {
    fixture.fixture.roster(TrialRunRecoveryTest.SPECIAL);
    var player = new TrialRunRecoveryTest.Origin();
    assertNotNull(player.getTrialManager().startTrial(TrialRunRecoveryTest.STAGE));
    player.save();
    return player;
  }

  Path customPreset(int level) throws Exception {
    configField = emu.lunarcore.LunarCore.class.getDeclaredField("config");
    configField.setAccessible(true);
    oldConfig = configField.get(null);
    var config = new emu.lunarcore.Config();
    config.resourceDir = root.toString();
    configField.set(null, config);
    var path = root.resolve("Config/BattleLineupPage/SkillTreePointPreset/frozen.json");
    Files.createDirectories(path.getParent());
    Files.writeString(
        path,
        "{\"Name\":\"frozen\",\"PresetList\":[{\"Anchor\":\"Point01\",\"Level\":" + level + "}]}");
    var row =
        fixture
            .gson
            .toJsonTree(GameData.getSpecialAvatarExcelMap().get(TrialRunRecoveryTest.SPECIAL, 0))
            .getAsJsonObject();
    row.addProperty("SkillTreeTemplate", "TYPE_CUSTOM");
    row.addProperty("CustomSkillTreeKey", "frozen");
    GameData.getSpecialAvatarExcelMap()
        .add(fixture.gson.fromJson(row, emu.lunarcore.data.excel.SpecialAvatarExcel.class));
    return path;
  }

  @Test
  void savedCustomTrialRestoresItsOwnPresetEvenIfTheCurrentPresetFileIsRemoved() throws Exception {
    skill(1);
    var path = customPreset(1);
    var saved = savedTrial();
    Files.delete(path);
    var restored = fixture.reload(saved.getGameplay());
    assertTrue(restored.getTrialManager().restoreOnLogin());
    assertEquals(
        Map.of(1, 1),
        new HashMap<>(restored.getAvatarById(TrialRunRecoveryTest.SPECIAL).getSkillTree()));
  }

  @Test
  void resourceAuthoredFixedLevelsKeepTheirExplicitPolicyOnRestore() throws Exception {
    var first = skill(1);
    var json = fixture.gson.toJsonTree(first).getAsJsonObject();
    json.addProperty("MaxLevel", 6);
    for (int level = 1; level <= 6; level++) {
      json.addProperty("Level", level);
      json.addProperty("AvatarLevelLimit", level > 1 ? 60 : 0);
      json.addProperty("AvatarPromotionLimit", level > 1 ? 4 : 0);
      var row = fixture.gson.fromJson(json, AvatarSkillTreeExcel.class);
      GameData.getAvatarSkillTreeExcelMap().add(row);
      row.onLoad();
    }
    customPreset(5);
    var saved = savedTrial();
    assertEquals(5, saved.getAvatarById(TrialRunRecoveryTest.SPECIAL).getSkillTree().get(1));
    var restored = fixture.reload(saved.getGameplay());
    assertTrue(restored.getTrialManager().restoreOnLogin());
    assertEquals(5, restored.getAvatarById(TrialRunRecoveryTest.SPECIAL).getSkillTree().get(1));
  }

  @Test
  void resourceReloadCannotAddNewSkillsToAnAlreadySavedTrialParty() throws Exception {
    skill(1);
    var saved = savedTrial();
    skill(2);
    var restored = fixture.reload(saved.getGameplay());
    assertTrue(restored.getTrialManager().restoreOnLogin());
    var actor = restored.getAvatarById(TrialRunRecoveryTest.SPECIAL);
    assertEquals(Map.of(1, 1), new HashMap<>(actor.getSkillTree()));
    assertEquals(
        1, actor.toBattleProto(restored.getCurrentLineup(), 0).getSkilltreeList().length());
  }

  @Test
  void aMissingFrozenSkillCannotSilentlyDisappearFromTheRestoredBattleParty() throws Exception {
    var row = skill(1);
    var saved = savedTrial();
    GameData.getAvatarSkillTreeExcelMap().values().remove(row);
    GameDepot.getAvatarSkillTreeExcels().values().removeIf(value -> value == row);
    var restored = fixture.reload(saved.getGameplay());
    assertThrows(IllegalStateException.class, () -> restored.getTrialManager().restoreOnLogin());
    assertTrue(restored.getAvatars().getTempAvatars().isEmpty());
    assertNotNull(restored.getGameplay().getTrialRun());
    assertEquals(0, restored.returnAttempts);
  }

  @Test
  void cyclicResourcesCannotValidateACorruptedFrozenSkillGraph() throws Exception {
    var first = skill(1);
    var second = skill(2);
    var saved = savedTrial();
    for (var row : List.of(first, second)) {
      var json = fixture.gson.toJsonTree(row).getAsJsonObject();
      json.add("PrePoint", fixture.gson.toJsonTree(List.of(19995100 + (row == first ? 2 : 1))));
      var changed = fixture.gson.fromJson(json, AvatarSkillTreeExcel.class);
      GameData.getAvatarSkillTreeExcelMap().add(changed);
      changed.onLoad();
    }
    var restored = fixture.reload(saved.getGameplay());
    assertThrows(IllegalStateException.class, () -> restored.getTrialManager().restoreOnLogin());
    assertTrue(restored.getAvatars().getTempAvatars().isEmpty());
  }

  @Test
  void aCorruptedSnapshotCannotSkipMissingIntermediateLevelsDuringRestore() throws Exception {
    var first = skill(1);
    var json = fixture.gson.toJsonTree(first).getAsJsonObject();
    json.addProperty("MaxLevel", 6);
    for (int level : List.of(1, 5)) {
      json.addProperty("Level", level);
      var changed = fixture.gson.fromJson(json, AvatarSkillTreeExcel.class);
      GameData.getAvatarSkillTreeExcelMap().add(changed);
      changed.onLoad();
    }
    var saved = savedTrial();
    saved.getGameplay().getTrialRun().getSkills().get(TrialRunRecoveryTest.SPECIAL).put(1, 5);
    var restored = fixture.reload(saved.getGameplay());
    assertThrows(IllegalStateException.class, () -> restored.getTrialManager().restoreOnLogin());
    assertTrue(restored.getAvatars().getTempAvatars().isEmpty());
  }

  @Test
  void aNewIncompletePresetNodeDoesNotBlockTheSavedOrdinaryTrial() throws Exception {
    skill(1);
    customPreset(1);
    var folder = root.resolve("Config/BattleLineupPage/SkillTreePointPreset");
    Path file;
    try (var paths = Files.list(folder)) {
      file = paths.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
    }
    var preset = fixture.gson.fromJson(Files.readString(file), com.google.gson.JsonObject.class);
    preset
        .getAsJsonArray("PresetList")
        .add(
            fixture.gson.fromJson(
                "{\"Anchor\":\"Point02\",\"LevelType\":\"MaxLevel\"}",
                com.google.gson.JsonObject.class));
    Files.writeString(file, fixture.gson.toJson(preset));
    var saved = savedTrial();
    var added = fixture.gson.toJsonTree(skill(2)).getAsJsonObject();
    added.addProperty("MaxLevel", 2);
    var row = fixture.gson.fromJson(added, AvatarSkillTreeExcel.class);
    GameData.getAvatarSkillTreeExcelMap().add(row);
    row.onLoad();
    assertNull(
        new TrialRunRecoveryTest.Origin().getTrialManager().startTrial(TrialRunRecoveryTest.STAGE));
    var restored = fixture.reload(saved.getGameplay());
    assertTrue(restored.getTrialManager().restoreOnLogin());
    assertEquals(
        Map.of(1, 1),
        new HashMap<>(restored.getAvatarById(TrialRunRecoveryTest.SPECIAL).getSkillTree()));
  }
}
