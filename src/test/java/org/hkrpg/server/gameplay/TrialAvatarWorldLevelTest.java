package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.ResourceMap;
import emu.lunarcore.data.resource.ResourceType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.trial.TrialAvatar;
import emu.lunarcore.game.trial.TrialManager;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TrialAvatarWorldLevelTest {
  static final int SPECIAL = 199943, AVATAR = 199943, EQUIPMENT = 299943;
  final Gson gson = new Gson();
  List<SpecialAvatarExcel> oldSpecials;
  AvatarExcel oldAvatar;
  ItemExcel oldItem;

  @BeforeEach
  void prepareResources() {
    oldSpecials = new ArrayList<>(GameData.getSpecialAvatarExcelMap().values());
    GameData.getSpecialAvatarExcelMap().values().clear();
    oldAvatar = GameData.getAvatarExcelMap().get(AVATAR);
    oldItem = GameData.getItemExcelMap().get(EQUIPMENT);
    GameData.getAvatarExcelMap()
        .add(gson.fromJson("{\"AvatarID\":" + AVATAR + "}", AvatarExcel.class));
  }

  @AfterEach
  void restoreResources() {
    GameData.getSpecialAvatarExcelMap().values().clear();
    oldSpecials.forEach(GameData.getSpecialAvatarExcelMap()::add);
    GameData.getAvatarExcelMap().keySet().remove(AVATAR);
    GameData.getItemExcelMap().keySet().remove(EQUIPMENT);
    if (oldAvatar != null) GameData.getAvatarExcelMap().add(oldAvatar);
    if (oldItem != null) GameData.getItemExcelMap().add(oldItem);
  }

  SpecialAvatarExcel row(int world, int level, boolean useWorld) {
    return gson.fromJson(
        "{\"SpecialAvatarID\":"
            + SPECIAL
            + ",\"AvatarID\":"
            + AVATAR
            + ",\"WorldLevel\":"
            + world
            + ",\"IsUseWorldLevel\":"
            + useWorld
            + ",\"Level\":"
            + level
            + ",\"Promotion\":3}",
        SpecialAvatarExcel.class);
  }

  Player player(int world) {
    return new Player() {
      public int getWorldLevel() {
        return world;
      }

      public void save() {}
    };
  }

  @Test
  void anOrdinaryTrialAlsoEncodesItsActorSkillsWithoutChangingItsTrialBattleIdentity() {
    int point = 19994301, special = 1199943;
    var prior = GameData.getAvatarSkillTreeExcelMap().get(point, 1);
    var cache = new HashMap<>(emu.lunarcore.data.GameDepot.getAvatarSkillTreeExcels());
    var skill =
        gson.fromJson(
            "{\"PointID\":19994301,\"AvatarID\":199943,\"Level\":1,"
                + "\"MaxLevel\":1,\"AnchorType\":\"Point01\",\"DefaultUnlock\":true}",
            AvatarSkillTreeExcel.class);
    GameData.getAvatarSkillTreeExcelMap().add(skill);
    skill.onLoad();
    GameData.getSpecialAvatarExcelMap()
        .add(
            gson.fromJson(
                "{\"SpecialAvatarID\":1199943,\"AvatarID\":199943,\"Level\":40,\"Promotion\":2}",
                SpecialAvatarExcel.class));
    try {
      var owner = player(0);
      var actor = owner.getTrialManager().buildTrialAvatar(special);
      assertNotNull(actor);
      var battle = actor.toBattleProto(null, 0);
      assertEquals(1, battle.getSkilltreeList().length());
      assertEquals(point, battle.getSkilltreeList().get(0).getPointId());
      assertEquals(AVATAR, actor.getExcelId());
      assertEquals(special, battle.getId());
      assertEquals(
          emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType.AVATAR_TRIAL_TYPE,
          battle.getAvatarType());
      assertTrue(owner.getAvatars().getTempAvatars().isEmpty());
    } finally {
      GameData.getAvatarSkillTreeExcelMap().values().remove(skill);
      if (prior != null) GameData.getAvatarSkillTreeExcelMap().add(prior);
      emu.lunarcore.data.GameDepot.getAvatarSkillTreeExcels().clear();
      emu.lunarcore.data.GameDepot.getAvatarSkillTreeExcels().putAll(cache);
    }
  }

  void checkTrialEnhancement(int enhancement) throws Exception {
    var previous = new ArrayList<>(GameData.getAvatarSkillTreeExcelMap().values());
    var cache = new HashMap<>(emu.lunarcore.data.GameDepot.getAvatarSkillTreeExcels());
    String config =
        "{\"SpecialAvatarID\":1199943,\"AvatarID\":199943,\"Level\":40,"
            + "\"Promotion\":2,\"EnhancedID\":"
            + enhancement
            + "}";
    var rows =
        List.of(
            "{\"PointID\":19994301,\"AvatarID\":199943,\"Level\":1,\"MaxLevel\":1,\"AnchorType\":\"Point01\",\"DefaultUnlock\":true}",
            "{\"PointID\":19994303,\"AvatarID\":199943,\"Level\":1,\"MaxLevel\":1,\"AnchorType\":\"Point03\",\"DefaultUnlock\":true}",
            "{\"PointID\":119994301,\"AvatarID\":199943,\"Level\":1,\"MaxLevel\":1,\"AnchorType\":\"Point01\",\"DefaultUnlock\":true,\"EnhancedID\":1}",
            "{\"PointID\":119994302,\"AvatarID\":199943,\"Level\":1,\"MaxLevel\":1,\"AnchorType\":\"Point02\",\"DefaultUnlock\":true,\"EnhancedID\":1}");
    for (String json : rows) {
      var skill = gson.fromJson(json, AvatarSkillTreeExcel.class);
      GameData.getAvatarSkillTreeExcelMap().add(skill);
      skill.onLoad();
    }
    GameData.getSpecialAvatarExcelMap().add(gson.fromJson(config, SpecialAvatarExcel.class));
    try {
      var owner = player(0);
      var actor = owner.getTrialManager().buildTrialAvatar(1199943);
      if (enhancement == 2)
        assertNull(actor, "an unprovided enhancement must not become a base trial");
      else {
        assertNotNull(actor);
        assertEquals(enhancement, actor.getEnhanceId());
        assertEquals(
            enhancement == 0 ? Set.of(1, 3) : Set.of(1, 2),
            new HashSet<>(actor.getSkillTree().keySet()));
        var battle = actor.toBattleProto(null, 0);
        assertEquals(enhancement, battle.getAvatarEnhanceId());
        assertEquals(2, battle.getSkilltreeList().length());
        var points = new HashSet<Integer>();
        for (var skill : battle.getSkilltreeList()) points.add(skill.getPointId());
        assertEquals(
            enhancement == 0 ? Set.of(19994301, 19994303) : Set.of(119994301, 119994302), points);
        var bridge = org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
        var nativeActor =
            bridge
                .clientSchema()
                .parse(
                    "BattleAvatar", bridge.translate("BattleAvatar", battle.toByteArray(), true));
        assertEquals(
            enhancement,
            nativeActor.getField(
                nativeActor.getDescriptorForType().findFieldByName("enhanced_id")));
      }
      var place =
          new StoryLineState.Place(
              1, 1, 1, 4, new emu.lunarcore.util.Position(), new emu.lunarcore.util.Position());
      var run = new StoryLineState.Run(199999, List.of(1199943), 0, Map.of(1199943, config), place);
      owner.getGameplay().getStoryLines().getRuns().put(199999, run);
      owner.getGameplay().getStoryLines().begin(199999, place, place);
      owner.getGameplay().getStoryLines().arrived();
      var restored = player(0);
      var field = Player.class.getDeclaredField("gameplay");
      field.setAccessible(true);
      field.set(restored, gson.fromJson(gson.toJson(owner.getGameplay()), GameplayState.class));
      if (enhancement == 2)
        assertThrows(IllegalStateException.class, () -> restored.getAvatarById(1199943));
      else {
        assertEquals(enhancement, restored.getAvatarById(1199943).getEnhanceId());
        assertEquals(
            2, restored.getAvatarById(1199943).toBattleProto(null, 0).getSkilltreeList().length());
      }
      assertTrue(owner.getAvatars().getTempAvatars().isEmpty());
    } finally {
      GameData.getAvatarSkillTreeExcelMap().values().clear();
      previous.forEach(GameData.getAvatarSkillTreeExcelMap()::add);
      emu.lunarcore.data.GameDepot.getAvatarSkillTreeExcels().clear();
      emu.lunarcore.data.GameDepot.getAvatarSkillTreeExcels().putAll(cache);
    }
  }

  @Test
  void enhancedTrialUsesOnlyItsDeclaredSkillVariantAndKeepsItAfterStoryReload() throws Exception {
    checkTrialEnhancement(1);
  }

  @Test
  void baseTrialDoesNotInheritEnhancementOnlyNodes() throws Exception {
    checkTrialEnhancement(0);
  }

  @Test
  void aMissingTrialEnhancementCannotFallBackToBaseSkillsOrActivateFromStorage() throws Exception {
    checkTrialEnhancement(2);
  }

  @Test
  void theActualExcelLoaderRetainsEveryWorldLevelRow(@TempDir Path root) throws Exception {
    Files.createDirectories(root.resolve("ExcelOutput"));
    Files.writeString(
        root.resolve("ExcelOutput/SpecialAvatar.json"),
        gson.toJson(List.of(row(0, 40, true), row(2, 50, true), row(6, 80, true))));
    var configField = LunarCore.class.getDeclaredField("config");
    configField.setAccessible(true);
    var oldConfig = configField.get(null);
    try {
      var config = new Config();
      config.resourceDir = root.toString();
      configField.set(null, config);
      var load =
          ResourceLoader.class.getDeclaredMethod(
              "loadFromResource", Class.class, ResourceType.class, String.class, ResourceMap.class);
      load.setAccessible(true);
      load.invoke(
          null,
          SpecialAvatarExcel.class,
          SpecialAvatarExcel.class.getAnnotation(ResourceType.class),
          "SpecialAvatar.json",
          GameData.getSpecialAvatarExcelMap());
      assertEquals(
          3,
          GameData.getSpecialAvatarExcelMap().size(),
          "world-level variants must not overwrite one another");
      assertEquals(
          Set.of(40, 50, 80),
          new HashSet<>(
              GameData.getSpecialAvatarExcelMap().stream()
                  .map(SpecialAvatarExcel::getLevel)
                  .toList()));
    } finally {
      configField.set(null, oldConfig);
    }
  }

  @Test
  void theDeclaredResourceFilesAlsoLoadTheAlternateAvatarTable(@TempDir Path root)
      throws Exception {
    Files.createDirectories(root.resolve("ExcelOutput"));
    Files.writeString(
        root.resolve("ExcelOutput/SpecialAvatar.json"), gson.toJson(List.of(row(0, 40, false))));
    var alternate =
        gson.fromJson(
            "{\"SpecialAvatarID\":199945,\"AvatarID\":" + AVATAR + ",\"Level\":70}",
            SpecialAvatarExcel.class);
    Files.writeString(
        root.resolve("ExcelOutput/SpecialAvatarLD.json"), gson.toJson(List.of(alternate)));
    var configField = LunarCore.class.getDeclaredField("config");
    configField.setAccessible(true);
    var oldConfig = configField.get(null);
    try {
      var config = new Config();
      config.resourceDir = root.toString();
      configField.set(null, config);
      var type = SpecialAvatarExcel.class.getAnnotation(ResourceType.class);
      var load =
          ResourceLoader.class.getDeclaredMethod(
              "loadFromResource", Class.class, ResourceType.class, String.class, ResourceMap.class);
      load.setAccessible(true);
      for (String name : type.name())
        load.invoke(
            null, SpecialAvatarExcel.class, type, name, GameData.getSpecialAvatarExcelMap());
      assertEquals(2, GameData.getSpecialAvatarExcelMap().size());
      var trial = new TrialManager(player(2)).createTrialAvatar(199945);
      assertNotNull(trial, "an alternate-table trial cannot disappear at resource loading");
      assertEquals(70, trial.getLevel());
    } finally {
      configField.set(null, oldConfig);
    }
  }

  @Test
  void aLowerWorldLevelCannotReceiveTheLastLoadedMaximumLevelVariant() {
    for (var r : List.of(row(0, 40, true), row(2, 50, true), row(6, 80, true)))
      GameData.getSpecialAvatarExcelMap().add(r);
    var owner = player(2);
    var trial = new TrialManager(owner).createTrialAvatar(SPECIAL);
    assertNotNull(trial);
    assertEquals(50, trial.getLevel());
    assertEquals(SPECIAL, trial.getAvatarId());
    assertEquals(AVATAR, trial.getActorId());
    assertSame(trial, owner.getAvatarById(SPECIAL));
    assertEquals(0, owner.getAvatars().getAvatarCount(), "a trial must not become a formal avatar");
  }

  @Test
  void resourceOrderCannotDowngradeTheHigherWorldLevelVariant() {
    for (var r : List.of(row(6, 80, true), row(2, 50, true), row(0, 40, true)))
      GameData.getSpecialAvatarExcelMap().add(r);
    var trial = new TrialManager(player(6)).createTrialAvatar(SPECIAL);
    assertNotNull(trial);
    assertEquals(80, trial.getLevel());
  }

  @Test
  void aMissingWorldScaledVariantCannotInventStatsOrPolluteTemporaryStorage() {
    GameData.getSpecialAvatarExcelMap().add(row(0, 40, true));
    GameData.getSpecialAvatarExcelMap().add(row(6, 80, true));
    var owner = player(3);
    assertNull(new TrialManager(owner).createTrialAvatar(SPECIAL));
    assertNull(owner.getAvatarById(SPECIAL));
  }

  @Test
  void aFixedLevelVariantRemainsAvailableAcrossWorldLevels() {
    GameData.getSpecialAvatarExcelMap().add(row(0, 20, false));
    for (int world : List.of(0, 2, 6)) {
      var trial = new TrialManager(player(world)).createTrialAvatar(SPECIAL);
      assertNotNull(trial);
      assertEquals(20, trial.getLevel());
    }
    assertNull(new TrialManager(player(-1)).createTrialAvatar(SPECIAL));
  }

  @Test
  void constructingATrialCannotAnnounceAnUnownedAvatarOrEquipmentAsFormalInventory() {
    var item =
        gson.fromJson("{\"ID\":" + EQUIPMENT + ",\"ItemMainType\":\"Equipment\"}", ItemExcel.class);
    item.setEquipmentExcel(
        gson.fromJson("{\"EquipmentID\":" + EQUIPMENT + "}", EquipmentExcel.class));
    GameData.getItemExcelMap().add(item);
    var special =
        gson.fromJson(
            "{\"SpecialAvatarID\":"
                + SPECIAL
                + ",\"AvatarID\":"
                + AVATAR
                + ",\"Level\":50,\"Promotion\":3,\"EquipmentID\":"
                + EQUIPMENT
                + ",\"EquipmentLevel\":40,\"EquipmentPromotion\":1,\"EquipmentRank\":2}",
            SpecialAvatarExcel.class);
    var packets = new java.util.concurrent.atomic.AtomicInteger();
    var owner =
        new Player() {
          public void sendPacket(emu.lunarcore.server.packet.BasePacket packet) {
            packets.incrementAndGet();
          }
        };
    var trial = new TrialAvatar(owner, special, GameData.getAvatarExcelMap().get(AVATAR));
    assertNotNull(trial.getEquipment());
    assertEquals(0, packets.get(), "constructing a trial must not send formal PlayerSync updates");
    assertEquals(0, owner.getAvatars().getAvatarCount());
    assertNull(trial.getEquipment().getId());
    assertEquals(0, trial.getEquipment().getOwnerUid());
  }

  @Test
  void trialEquipmentUsesItsOwnPromotionInsteadOfTheAvatarsPromotion() {
    var item =
        gson.fromJson("{\"ID\":" + EQUIPMENT + ",\"ItemMainType\":\"Equipment\"}", ItemExcel.class);
    item.setEquipmentExcel(
        gson.fromJson("{\"EquipmentID\":" + EQUIPMENT + "}", EquipmentExcel.class));
    GameData.getItemExcelMap().add(item);
    var special =
        gson.fromJson(
            "{\"SpecialAvatarID\":"
                + SPECIAL
                + ",\"AvatarID\":"
                + AVATAR
                + ",\"Level\":50,\"Promotion\":3,\"EquipmentID\":"
                + EQUIPMENT
                + ",\"EquipmentLevel\":40,\"EquipmentPromotion\":1,\"EquipmentRank\":2}",
            SpecialAvatarExcel.class);
    var trial = new TrialAvatar(player(2), special, GameData.getAvatarExcelMap().get(AVATAR));
    assertEquals(3, trial.getPromotion());
    assertNotNull(trial.getEquipment());
    assertEquals(40, trial.getEquipment().getLevel());
    assertEquals(1, trial.getEquipment().getPromotion());
    assertEquals(2, trial.getEquipment().getRank());
    assertNull(trial.getEquipment().getId());
    assertEquals(0, trial.getEquipment().getOwnerUid());
  }
}
