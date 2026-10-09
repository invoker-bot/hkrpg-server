package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.game.player.Player;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrialActorResourceTest {
  @TempDir Path root;

  @Test
  void plotActorTablesBuildATrialWithPromotionAndSkillsWithoutEnteringTheFormalCatalog()
      throws Exception {
    var fields = new LinkedHashMap<Field, Object>();
    var configField = LunarCore.class.getDeclaredField("config");
    configField.setAccessible(true);
    var oldConfig = configField.get(null);
    var oldSkillDepot = new HashMap<>(GameDepot.getAvatarSkillTreeExcels());
    try {
      for (String name :
          List.of(
              "avatarExcelMap",
              "avatarPromotionExcelMap",
              "avatarSkillTreeExcelMap",
              "specialAvatarExcelMap",
              "trialActorExcelMap",
              "trialActorPromotionExcelMap",
              "trialActorSkillTreeExcelMap")) {
        try {
          var field = GameData.class.getDeclaredField(name);
          field.setAccessible(true);
          var old = field.get(null);
          fields.put(field, old);
          field.set(null, old.getClass().getConstructor().newInstance());
        } catch (NoSuchFieldException notImplementedYet) {
        }
      }
      Files.createDirectories(root.resolve("ExcelOutput"));
      Files.writeString(
          root.resolve("ExcelOutput/AvatarConfigTrial.json"),
          "[{\"AvatarID\":7213,\"MaxPromotion\":6,\"ManikinJsonPath\":\"Avatar_DanHengIL_Config.json\"}]");
      Files.writeString(
          root.resolve("ExcelOutput/AvatarPromotionConfigTrial.json"),
          "[{\"AvatarID\":7213,\"Promotion\":6,\"MaxLevel\":80}]");
      Files.writeString(
          root.resolve("ExcelOutput/AvatarSkillTreeConfigTrial.json"),
          "[{\"AvatarID\":7213,\"PointID\":7213101,\"Level\":1,\"MaxLevel\":1,\"AnchorType\":\"Point01\",\"DefaultUnlock\":true}]");
      Files.writeString(
          root.resolve("ExcelOutput/SpecialAvatar.json"),
          "[{\"SpecialAvatarID\":1021213,\"AvatarID\":7213,\"Level\":80,\"Promotion\":6}]");
      var config = new Config();
      config.resourceDir = root.toString();
      configField.set(null, config);
      ResourceLoader.loadResources();
      var player = new Player();
      var actor = player.getTrialManager().buildTrialAvatar(1021213);
      assertNotNull(
          actor, "a resource-authored plot actor must not disappear because its table is separate");
      assertEquals(7213, actor.getActorId());
      assertEquals(80, actor.getExcel().getPromotionData(6).getMaxLevel());
      assertEquals(1, actor.getSkillTree().get(1));
      assertNotNull(GameData.getAvatarSkilltree(7213, 0, 1, 1));
      var battleActor = actor.toBattleProto(null, 0);
      assertEquals(
          1,
          battleActor.getSkilltreeList().length(),
          "loaded trial skills must reach battle data under the actor resource identity");
      assertEquals(7213101, battleActor.getSkilltreeList().get(0).getPointId());
      assertEquals(1, battleActor.getSkilltreeList().get(0).getLevel());
      assertEquals(1021213, battleActor.getId());
      assertEquals(
          emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType.AVATAR_TRIAL_TYPE,
          battleActor.getAvatarType());
      var bridge = org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
      var nativeActor =
          bridge
              .clientSchema()
              .parse(
                  "BattleAvatar",
                  bridge.translate("BattleAvatar", battleActor.toByteArray(), true));
      var skillField = nativeActor.getDescriptorForType().findFieldByName("skilltree_list");
      assertEquals(1, nativeActor.getRepeatedFieldCount(skillField));
      var nativeSkill =
          (com.google.protobuf.DynamicMessage) nativeActor.getRepeatedField(skillField, 0);
      assertEquals(
          7213101,
          nativeSkill.getField(nativeSkill.getDescriptorForType().findFieldByName("point_id")));
      assertNull(
          GameData.getAvatarExcelMap().get(7213),
          "trial tables must not populate the formal catalog");
      assertTrue(player.getAvatars().getTempAvatars().isEmpty());
      var place =
          new StoryLineState.Place(
              1, 1, 1, 4, new emu.lunarcore.util.Position(), new emu.lunarcore.util.Position());
      var run =
          new StoryLineState.Run(
              199999,
              List.of(1021213),
              0,
              Map.of(1021213, new com.google.gson.Gson().toJson(actor.getSpecialAvatarExcel())),
              place);
      var state = player.getGameplay().getStoryLines();
      state.getRuns().put(199999, run);
      state.begin(199999, place, place);
      state.arrived();
      var gameplayField = Player.class.getDeclaredField("gameplay");
      gameplayField.setAccessible(true);
      var saved = new com.google.gson.Gson().toJson(player.getGameplay());
      var restored = new Player();
      gameplayField.set(restored, new com.google.gson.Gson().fromJson(saved, GameplayState.class));
      assertEquals(7213, restored.getAvatarById(1021213).getActorId());
      assertEquals(1, restored.getAvatarById(1021213).getSkillTree().get(1));
      assertEquals(
          1, restored.getAvatarById(1021213).toBattleProto(null, 0).getSkilltreeList().length());
      GameData.getTrialActorPromotionExcelMap().values().clear();
      assertNull(
          player.getTrialManager().buildTrialAvatar(1021213),
          "missing plot promotion must reject an incomplete trial");
      var invalidRestore = new Player();
      gameplayField.set(
          invalidRestore, new com.google.gson.Gson().fromJson(saved, GameplayState.class));
      assertThrows(IllegalStateException.class, () -> invalidRestore.getAvatarById(1021213));
    } finally {
      configField.set(null, oldConfig);
      for (var saved : fields.entrySet()) saved.getKey().set(null, saved.getValue());
      GameDepot.getAvatarSkillTreeExcels().clear();
      GameDepot.getAvatarSkillTreeExcels().putAll(oldSkillDepot);
    }
  }
}
