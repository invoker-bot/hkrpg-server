package emu.lunarcore.game.inventory;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.GameConstants;
import emu.lunarcore.game.player.Player;
import org.junit.jupiter.api.Test;

class RewardDefinitionTest {
  @Test
  void unhandledItemCannotBeReportedAsGranted() {
    var player = new Player();
    var definition =
        new com.google.gson.Gson()
            .fromJson(
                "{\"ID\":19995001,\"ItemMainType\":\"Unknown\"}",
                emu.lunarcore.data.excel.ItemExcel.class);
    var item = new GameItem(definition, 1);
    assertTrue(player.getInventory().addItems(java.util.List.of(item), false).isEmpty());
    var virtual =
        new com.google.gson.Gson()
            .fromJson(
                "{\"ID\":19995002,\"ItemMainType\":\"Virtual\"}",
                emu.lunarcore.data.excel.ItemExcel.class);
    assertTrue(
        player
            .getInventory()
            .addItems(java.util.List.of(new GameItem(virtual, 1)), false)
            .isEmpty());
  }

  @Test
  void maxLevelExpConversionCannotMutateSharedRewardDefinition() {
    var player =
        new Player() {
          public int getLevel() {
            return GameConstants.MAX_TRAILBLAZER_LEVEL;
          }
        };
    var reward = new ItemParamMap();
    reward.add(GameConstants.TRAILBLAZER_EXP_ID, 10);
    player.getInventory().addItems(reward, false);
    assertEquals(10, reward.get(GameConstants.TRAILBLAZER_EXP_ID));
    assertEquals(0, reward.get(GameConstants.MATERIAL_COIN_ID));
  }
}
