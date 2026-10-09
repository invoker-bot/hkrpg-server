package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;

/** Original permanent twenty-roll achievement; query/claim cannot borrow a fake finished record. */
public final class DiceRollQuests {
  private static final int QUEST = 4055044;
  private static final JsonElement QUEST_SOURCE =
      JsonParser.parseString(
          """
          {"QuestID":4055044,"QuestType":1,"ImagePath":"","UnlockType":"FinishQuest",
           "UnlockParamList":[4000001],"RewardID":113000,"FinishWayID":4055044}
          """);
  private static final JsonElement FINISH_SOURCE =
      JsonParser.parseString(
          """
          {"ID":4055044,"FinishType":"DiceCombatRollDiceCnt","ParamType":"NoPara",
           "ParamStr1":"","ParamIntList":[],"ParamItemList":[],"Progress":20}
          """);
  private static final JsonElement REWARD_SOURCE =
      JsonParser.parseString("{\"RewardID\":113000,\"Hcoin\":5}");

  private record Binding(Path root, String quest, String finish, String reward) {}

  private static volatile Binding current;

  public static synchronized void load(Path root) {
    current = null;
    if (!Files.isRegularFile(root.resolve("ExcelOutput/ActivityDiceConstValueCommo.json"))
        || !DiceProgressionRules.available(root)) return;
    try {
      var quest = selected(root, "QuestData", "QuestID", QUEST);
      if (quest == null) return;
      var finish = selected(root, "FinishWay", "ID", QUEST);
      var reward = selected(root, "RewardData", "RewardID", 113000);
      if (!QUEST_SOURCE.equals(quest)
          || !FINISH_SOURCE.equals(finish)
          || !REWARD_SOURCE.equals(reward))
        throw new IllegalArgumentException("Unsupported dice-roll achievement source");
      var gson = ResourceLoader.getGson();
      var rewardDefinition = gson.fromJson(reward, RewardExcel.class);
      current =
          new Binding(
              root.toAbsolutePath().normalize(),
              gson.toJson(gson.fromJson(quest, QuestExcel.class)),
              gson.toJson(gson.fromJson(finish, FinishWayExcel.class)),
              gson.toJson(rewardDefinition));
    } catch (Exception invalid) {
      throw new IllegalStateException("Invalid dice-roll achievement resources", invalid);
    }
  }

  private static JsonObject selected(Path root, String name, String key, int id) throws Exception {
    var path = root.resolve("ExcelOutput/" + name + ".json");
    if (!Files.isRegularFile(path) || Files.size(path) > 2097152)
      throw new IllegalArgumentException("Missing or oversized dice-roll source table");
    var json = JsonParser.parseString(Files.readString(path));
    var rows =
        json.isJsonArray()
            ? json.getAsJsonArray().asList()
            : json.getAsJsonObject().asMap().values();
    if (rows.size() > 16384) throw new IllegalArgumentException("Oversized dice-roll source rows");
    JsonObject found = null;
    for (var value : rows) {
      var row = value.getAsJsonObject();
      if (LocalLegendCatalog.positive(row, key) != id) continue;
      if (found != null) throw new IllegalArgumentException("Duplicate dice-roll source identity");
      found = row;
    }
    return found;
  }

  public static boolean belongs(QuestExcel definition) {
    if (definition == null) return false;
    var finish = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
    return definition.getId() == QUEST
        || finish != null && "DiceCombatRollDiceCnt".equals(finish.getFinishType());
  }

  private static boolean rule(Player player) {
    var binding = current;
    if (binding == null
        || !DiceProgressionRules.available(binding.root())
        || !binding
            .root()
            .equals(Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize()))
      return false;
    var quest = GameData.getQuestExcelMap().get(QUEST);
    var finish = GameData.getFinishWayExcelMap().get(QUEST);
    var reward = GameData.getRewardExcelMap().get(113000);
    var gson = ResourceLoader.getGson();
    return quest != null
        && finish != null
        && reward != null
        && binding.quest().equals(gson.toJson(quest))
        && binding.finish().equals(gson.toJson(finish))
        && binding.reward().equals(gson.toJson(reward))
        && reward.getRewards() != null
        && reward.getRewards().equals(Map.of(1, 5))
        && QuestCompletionRecords.completed(player, 4000001);
  }

  public static boolean hasRolls(Player player) {
    return rule(player)
        && player.getGameplay().getDiceRolls().valid()
        && player.getGameplay().getDiceRolls().getCount() > 0;
  }

  public static boolean refresh(Player player) {
    synchronized (player) {
      if (!hasRolls(player)) return false;
      var state = player.getQuestStates().get(QUEST);
      int count = (int) Math.min(20, player.getGameplay().getDiceRolls().getCount());
      return MissionStateRecords.doing(state, QUEST, 20)
          && state.getProgress() <= count
          && state.advance(count - state.getProgress(), 20, System.currentTimeMillis() / 1000);
    }
  }

  public static QuestState state(Player player, int id) {
    synchronized (player) {
      if (id != QUEST || !rule(player) || !player.getGameplay().getDiceRolls().valid()) return null;
      var state = player.getQuestStates().get(id);
      long count = Math.min(20, player.getGameplay().getDiceRolls().getCount());
      return (MissionStateRecords.doing(state, id, 20)
                  || MissionStateRecords.completed(state, id, 20))
              && state.getProgress() <= count
          ? state
          : null;
    }
  }

  private DiceRollQuests() {}
}
