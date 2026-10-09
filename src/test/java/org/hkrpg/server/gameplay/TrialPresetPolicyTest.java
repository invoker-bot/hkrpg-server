package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.SpecialAvatarExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.trial.TrialAvatar;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TrialPresetPolicyTest {
  @TempDir Path root;
  final TrialSkillEligibilityTest fixture = new TrialSkillEligibilityTest();
  Field config;
  Object oldConfig;

  @BeforeEach
  void install() throws Exception {
    fixture.install();
    config = LunarCore.class.getDeclaredField("config");
    config.setAccessible(true);
    oldConfig = config.get(null);
    var settings = new Config();
    settings.resourceDir = root.toString();
    config.set(null, settings);
    for (int level = 1; level <= 6; level++)
      fixture.row(1, level, 6, level > 2 ? 4 : 0, level > 2 ? 60 : 0);
    for (int level = 1; level <= 10; level++)
      fixture.row(2, level, 10, level > 2 ? 4 : 0, level > 2 ? 60 : 0);
  }

  @AfterEach
  void restore() throws Exception {
    config.set(null, oldConfig);
    fixture.restore();
  }

  void preset(String name, String nodes) throws Exception {
    var path = root.resolve("Config/BattleLineupPage/SkillTreePointPreset/" + name + ".json");
    Files.createDirectories(path.getParent());
    Files.writeString(path, "{\"Name\":\"" + name + "\",\"PresetList\":" + nodes + "}");
  }

  TrialAvatar trial(String key) {
    var row =
        fixture.gson.fromJson(
            "{\"SpecialAvatarID\":1199960,\"AvatarID\":199960,\"Level\":40,\"Promotion\":2,\"SkillTreeTemplate\":\"TYPE_CUSTOM\",\"CustomSkillTreeKey\":\""
                + key
                + "\"}",
            SpecialAvatarExcel.class);
    return new TrialAvatar(new Player(), row, fixture.actor);
  }

  @Test
  void fixedResourcePresetChangesActualBattleLevelsAndKeepsOnlyUnlistedDefaults() throws Exception {
    preset("fixed", "[{\"Anchor\":\"Point01\",\"Level\":1}]");
    var actor = trial("fixed");
    assertEquals(Map.of(1, 1, 2, 1), new HashMap<>(actor.getSkillTree()));
    var levels = new HashSet<Integer>();
    for (var row : actor.toBattleProto(null, 0).getSkilltreeList()) levels.add(row.getLevel());
    assertEquals(Set.of(1), levels);
  }

  @Test
  void maxLevelPresetUsesDeclaredMaximumEvenForALowerLevelSpecialActor() throws Exception {
    preset(
        "max",
        "[{\"Anchor\":\"Point01\",\"LevelType\":\"MaxLevel\"},{\"Anchor\":\"Point02\",\"LevelType\":\"MaxLevel\"}]");
    assertEquals(Map.of(1, 6, 2, 10), new HashMap<>(trial("max").getSkillTree()));
  }

  @Test
  void characterLevelPresetUsesActualPromotionLimitsRatherThanLegacyFiveEight() throws Exception {
    preset(
        "within",
        "[{\"Anchor\":\"Point01\",\"LevelType\":\"MaxWithCharacterLevel\"},{\"Anchor\":\"Point02\",\"LevelType\":\"MaxWithCharacterLevel\"}]");
    var actor = trial("within");
    assertEquals(Map.of(1, 2, 2, 2), new HashMap<>(actor.getSkillTree()));
    assertEquals(2, actor.toBattleProto(null, 0).getSkilltreeList().length());
  }

  @Test
  void emptyNamedPresetKeepsDefaultLevelOneWithoutInventingTheStandardTemplate() throws Exception {
    preset("None", "[]");
    assertEquals(Map.of(1, 1, 2, 1), new HashMap<>(trial("None").getSkillTree()));
  }

  @Test
  void unknownNamedPresetIsRejectedWithoutPublishingATemporaryActor() {
    var player = new Player();
    var row =
        fixture.gson.fromJson(
            "{\"SpecialAvatarID\":1199960,\"AvatarID\":199960,\"Level\":40,\"Promotion\":2,\"SkillTreeTemplate\":\"TYPE_CUSTOM\",\"CustomSkillTreeKey\":\"missing\"}",
            SpecialAvatarExcel.class);
    var old = new ArrayList<>(GameData.getSpecialAvatarExcelMap().values());
    try {
      GameData.getSpecialAvatarExcelMap().add(row);
      assertNull(player.getTrialManager().buildTrialAvatar(1199960));
      assertTrue(player.getAvatars().getTempAvatars().isEmpty());
    } finally {
      GameData.getSpecialAvatarExcelMap().values().clear();
      old.forEach(GameData.getSpecialAvatarExcelMap()::add);
    }
  }

  @Test
  void fixedPresetIsClampedToTheSkillMaximumAsInTheNativeConsumer() throws Exception {
    preset("clamped", "[{\"Anchor\":\"Point01\",\"Level\":8}]");
    assertEquals(6, trial("clamped").getSkillTree().get(1));
  }

  @Test
  void fullyPromotedCharacterLevelPresetReachesSixTenInsteadOfLegacyFiveEight() throws Exception {
    preset(
        "eligible",
        "[{\"Anchor\":\"Point01\",\"LevelType\":\"MaxWithCharacterLevel\"},{\"Anchor\":\"Point02\",\"LevelType\":\"MaxWithCharacterLevel\"}]");
    var config =
        fixture.gson.toJsonTree(trial("eligible").getSpecialAvatarExcel()).getAsJsonObject();
    config.addProperty("Level", 80);
    config.addProperty("Promotion", 6);
    var actor =
        new TrialAvatar(
            new Player(), fixture.gson.fromJson(config, SpecialAvatarExcel.class), fixture.actor);
    assertEquals(Map.of(1, 6, 2, 10), new HashMap<>(actor.getSkillTree()));
  }

  @Test
  void duplicatePresetAnchorsAreRejectedBeforeAnIncompleteBattlePartyIsBuilt() throws Exception {
    preset("broken", "[{\"Anchor\":\"Point01\",\"Level\":1},{\"Anchor\":\"Point01\",\"Level\":2}]");
    assertThrows(IllegalArgumentException.class, () -> trial("broken"));
  }
}
