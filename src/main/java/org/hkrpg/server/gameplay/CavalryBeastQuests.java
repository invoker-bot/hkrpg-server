package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.QuestExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.PacketGetQuestDataScRsp;
import java.time.Instant;
import java.util.List;

/** Original native ride reports count only an existing acceptance in its current source scene. */
public final class CavalryBeastQuests {
  public static boolean belongs(QuestExcel definition) {
    if (definition == null) return false;
    var finish = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
    return definition.getId() == CavalryBeastSources.QUEST
        || definition.getQuestType() == 3 && finish != null
            && "RideCavalryBeastCnt".equals(finish.getFinishType());
  }

  public static boolean refresh(Player player) {
    synchronized (player) {
      var rules = CavalryBeastRules.load();
      var domain = player.getGameplay().getCavalryBeasts();
      return domain.valid(rules) && rules.window(player, Instant.now()) != null && domain.accept();
    }
  }

  public static List<QuestState> states(Player player) {
    var state = state(player, CavalryBeastSources.QUEST);
    return state == null ? List.of() : List.of(state);
  }

  public static QuestState state(Player player, int id) {
    synchronized (player) {
      if (id != CavalryBeastSources.QUEST) return null;
      var rules = CavalryBeastRules.load();
      var domain = player.getGameplay().getCavalryBeasts();
      return domain.valid(rules) && rules.window(player, Instant.now()) != null
          ? domain.getActivity() : null;
    }
  }

  public static QuestState completionState(Player player, int id) {
    synchronized (player) {
      if (id != CavalryBeastSources.QUEST) return null;
      var rules = CavalryBeastRules.load();
      var domain = player.getGameplay().getCavalryBeasts();
      return domain.valid(rules) && rules.unlocked(player)
          && MissionStateRecords.completed(domain.getActivity(), id, 1)
          ? domain.getActivity() : null;
    }
  }

  public static Retcode finish(Player player, int quest, int group, int instance) {
    synchronized (player) {
      if (quest != CavalryBeastSources.QUEST || group <= 0 || instance <= 0)
        return Retcode.REQ_PARA_INVALID;
      var rules = CavalryBeastRules.load();
      if (rules == null) return Retcode.PLAYER_DATA_ERROR;
      if (player.isInBattle() || player.getBattle() != null || player.getInstance() != null
          || player.getGameplay().getStoryLines().isTransitionPending()) return Retcode.SYSTEM_BUSY;
      var domain = player.getGameplay().getCavalryBeasts();
      if (!domain.valid(rules)) return Retcode.PLAYER_DATA_ERROR;
      var now = Instant.now();
      var window = rules.window(player, now);
      if (window == null || domain.getActivity() == null) return Retcode.QUEST_NOT_ACCEPT;
      var actor = rules.actor(player, group, instance);
      if (actor == null) return Retcode.MISSION_SCENE_NOT_MATCH;
      var accepted = domain.getActivity();
      if (MissionStateRecords.completed(accepted, quest, 1)) return Retcode.SUCC;
      var scene = player.getScene();
      var binding = rules.binding(scene.getPlaneId(), scene.getFloorId(), scene.getDimensionId(), group, instance);
      var receipt = new CavalryBeastState.Receipt(player, actor, binding, accepted, window, now.getEpochSecond());
      var next = domain.recorded(receipt);
      if (!next.valid(rules)) return Retcode.PLAYER_DATA_ERROR;
      player.getGameplay().cavalryBeasts(next);
      try {
        player.save();
      } catch (RuntimeException failed) {
        player.getGameplay().cavalryBeasts(domain);
        return Retcode.SYSTEM_BUSY;
      }
      if (player.getSession() != null) player.sendPacket(new PacketGetQuestDataScRsp(player));
      return Retcode.SUCC;
    }
  }

  private CavalryBeastQuests() {}
}
