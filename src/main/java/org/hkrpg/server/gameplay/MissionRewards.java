package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;

/** Authored payouts are frozen when the objective finishes, before inventory callbacks run. */
final class MissionRewards {
  private final Player player;

  MissionRewards(Player player) {
    this.player = player;
  }

  void freezeSub(MissionInfo.SubMissionInfo c) {
    if (!DailyMissionRepeats.payoutAllowed(player, c.getMainMissionID(), player.getMissionManager().currentInstant())) return;
    if (c.getSubRewardID() == 0 && c.getCustomValueReward().length == 0) return;
    String key = DailyMissionRepeats.subKey(player, c);
    if (player.getGameplay().getMissionRewardPlans().containsKey(key)) return;
    var ids = new ArrayList<Integer>();
    if (c.getSubRewardID() != 0) ids.add(c.getSubRewardID());
    var choices = new HashMap<String, Integer>();
    boolean ready = true;
    var main = GameData.getMissionInfos().get(c.getMainMissionID());
    var names = new HashSet<String>();
    for (var r : c.getCustomValueReward()) {
      if (r == null || r.Name() == null || r.RewardID() <= 0) {
        ready = false;
        continue;
      }
      var definitions =
          main == null || main.getMissionCustomValueList() == null
              ? List.<MissionInfo.MissionCustomValueInfo>of()
              : Arrays.stream(main.getMissionCustomValueList())
                  .filter(v -> r.Name().equals(v.getName()))
                  .toList();
      String variable = "mission:" + c.getMainMissionID() + ":" + r.Name();
      Integer actual = player.getGameplay().getStoryValues().get(variable);
      if (definitions.size() != 1 || actual == null) {
        ready = false;
        continue;
      }
      var definition = definitions.getFirst();
      if (!"Former".equals(definition.getType())
          || (r.Index() != null && !r.Index().equals(definition.getIndex()))
          || !allowed(definition, actual)
          || !allowed(definition, r.Value())
          || !names.add(r.Name() + ":" + r.Value())) {
        ready = false;
        continue;
      }
      choices.put(r.Name(), actual);
      if (actual == r.Value()) ids.add(r.RewardID());
    }
    freeze(key, ids, choices, ready);
  }

  private boolean allowed(MissionInfo.MissionCustomValueInfo definition, int value) {
    var allowed = definition.getValidValueParamList();
    if (allowed == null || Arrays.asList(allowed).contains(null)) return false;
    return definition.isRange()
        ? allowed.length == 2 && value >= allowed[0] && value <= allowed[1]
        : Arrays.asList(allowed).contains(value);
  }

  void freezeMain(MainMissionExcel c) {
    if (!DailyMissionRepeats.payoutAllowed(player, c.getId(), player.getMissionManager().currentInstant())) return;
    var ids = new ArrayList<Integer>();
    if (c.getRewardID() != 0) ids.add(c.getRewardID());
    // SubRewardList previews child payouts, including mutually exclusive custom-value branches.
    // Those payouts are earned at their own objectives, never paid wholesale with the parent.
    freeze(DailyMissionRepeats.mainKey(player, c.getId()), ids, Map.of(), true);
  }

  private void freeze(String key, List<Integer> ids, Map<String, Integer> choices, boolean ready) {
    if (player.getGameplay().getMissionRewardPlans().containsKey(key)) return;
    var quantities = new TreeMap<Integer, Integer>();
    for (Integer id : ids) {
      var reward = id == null || id <= 0 ? null : GameData.getRewardExcelMap().get(id);
      if (reward == null || reward.getRewards() == null) {
        ready = false;
        continue;
      }
      for (var item : reward.getRewards()) {
        if (item.getIntKey() <= 0
            || item.getIntValue() <= 0
            || GameData.getItemExcelMap().get(item.getIntKey()) == null) {
          ready = false;
          continue;
        }
        try {
          quantities.merge(item.getIntKey(), item.getIntValue(), Math::addExact);
        } catch (ArithmeticException e) {
          ready = false;
        }
      }
    }
    player
        .getGameplay()
        .getMissionRewardPlans()
        .put(key, new GameplayState.MissionRewardPlan(ids, choices, quantities, ready));
  }

  void settleSubRewards() {
    for (var entry : List.copyOf(player.getGameplay().getSubMissions().entrySet())) {
      var state = entry.getValue();
      var c = GameplayDefinitions.subs.get(entry.getKey());
      if (c == null
          || !MissionStateRecords.subCompleted(player, entry.getKey())
          || state.getPhase() != QuestState.Phase.FINISHED
          || !player.getGameplay().getMainMissions().containsKey(c.getMainMissionID())
          || player.getGameplay().getCancelledMissions().contains(c.getMainMissionID())
          || player.getGameplay().getCancelledSubs().contains(c.getId())) continue;
      // Old completed objectives have no trustworthy choice snapshot. Never infer it on a query.
      settle(DailyMissionRepeats.subKey(player, c), state);
    }
  }

  boolean settle(String key, QuestState state) {
    if (state == null) return false;
    int id = state.getQuestId();
    boolean main = DailyMissionRepeats.mainKey(player, id).equals(key);
    var definition = GameplayDefinitions.subs.get(id);
    boolean sub = definition != null && DailyMissionRepeats.subKey(player, definition).equals(key);
    if (!DailyMissionRepeats.payoutAllowed(player, main ? id : definition == null ? id : definition.getMainMissionID(), player.getMissionManager().currentInstant())) return false;
    if (!(main && player.getGameplay().getMainMissions().get(id) == state
            && MissionStateRecords.mainCompleted(player, id))
        && !(sub && player.getGameplay().getSubMissions().get(id) == state
            && MissionStateRecords.subCompleted(player, id))) return false;
    var plan = player.getGameplay().getMissionRewardPlans().get(key);
    if (plan == null || !plan.isReady()) return false;
    var items = new ItemParamMap();
    var avatars = new TreeSet<Integer>();
    for (var entry : plan.getItems().entrySet()) {
      var item = GameData.getItemExcelMap().get(entry.getKey());
      if (item == null || entry.getValue() <= 0) return false;
      if (item.getItemSubType() == emu.lunarcore.game.enums.ItemSubType.AvatarCard) {
        var avatar = GameData.getAvatarExcelMap().get(entry.getKey());
        var path = GameData.getMultiplePathAvatarExcelMap().get(entry.getKey());
        if (entry.getValue() != 1 || avatar == null || (path != null && !path.isDefault())) return false;
        avatars.add(entry.getKey());
      } else items.add(entry.getKey(), entry.getValue());
    }
    // Formal avatar ownership is idempotent. Keep it out of the non-repeatable currency ledger:
    // owning a character already must not strand the rest of a mission reward in SYSTEM_BUSY.
    String source = "mission-avatar-v1:" + plan.getRewardIds() + ":"
        + new TreeMap<>(plan.getChoices()) + ":" + new TreeMap<>(plan.getItems());
    for (int avatar : avatars)
      if (!new StoryParties(player).grantAvatar("reward:" + key + ":" + avatar, source, avatar)) return false;
    if (!player.getRewardLedger().granted(key)) {
      var claim = player.getRewardLedger().claimItems(key, items);
      if (claim.retcode() == emu.lunarcore.server.packet.Retcode.SUCC) {
        var published = new ArrayList<>(claim.items());
        for (int avatar : avatars) published.add(new emu.lunarcore.game.inventory.GameItem(avatar, 1));
        org.hkrpg.server.protocol.Story46.syncReward(
            player, main, state.getQuestId(), published);
      }
    }
    if (!player.getRewardLedger().granted(key)) return false;
    if (state.getPhase() == QuestState.Phase.FINISHED) state.reserveReward();
    if (state.getPhase() == QuestState.Phase.CLAIMING) state.closeReward();
    player.save();
    return true;
  }
}
