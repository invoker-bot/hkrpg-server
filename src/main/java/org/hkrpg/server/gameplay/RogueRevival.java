package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.enums.PropType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import java.util.regex.Pattern;

/** Classic revive console 1002 and numeric ConstValueRogue from the pinned 4.6 resources. */
public final class RogueRevival {
  private static final Pattern COST = Pattern.compile("\\[31:([1-9][0-9]*)]");

  public record Plan(int cost, int hp) {}

  public static Optional<Plan> plan() {
    try {
      // Other count/effect variants have no confirmed rules in the public resource snapshot.
      if (!"0".equals(GameplayDefinitions.rogueConstants.get("Rogue_Recover_Times"))
          || !"1".equals(GameplayDefinitions.rogueConstants.get("Rogue_Recover_Effect")))
        return Optional.empty();
      int percent =
          Integer.parseInt(GameplayDefinitions.rogueConstants.get("Rogue_Recover_Percent"));
      var cost = COST.matcher(GameplayDefinitions.rogueConstants.get("Rogue_Recover_ItemCost"));
      if (percent <= 0 || percent > 100 || !cost.matches()) return Optional.empty();
      return Optional.of(new Plan(Integer.parseInt(cost.group(1)), percent * 100));
    } catch (RuntimeException invalid) {
      return Optional.empty();
    }
  }

  /** Each requested dead avatar consumes the saved unit price; the batch is all-or-nothing. */
  public static Retcode revive(Player p, List<Integer> ids, int entity) {
    synchronized (p) {
      var run = p.getGameplay().getRogue();
      if (run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING
          || !run.isInside()
          || p.isInBattle()
          || run.pending()) return Retcode.ROGUE_STATUS_NOT_MATCH;
      if (run.getRevivalCost() < 0
          || run.getRevivalHp() <= 0
          || run.getRevivalHp() > 10000
          || ids == null
          || ids.isEmpty()
          || ids.size() > 4
          || new HashSet<>(ids).size() != ids.size()) return Retcode.REQ_PARA_INVALID;
      for (var id : ids) {
        var vital = run.getVitals().get(id);
        if (id == null
            || !run.getRoster().contains(id)
            || p.getAvatarById(id) == null
            || vital == null
            || vital.getHp() != 0) return Retcode.ROGUE_PICK_AVATAR_INVALID;
      }
      var scene = p.getScene();
      var room = GameplayDefinitions.rooms.get(run.getRoomId());
      var entrance =
          room == null ? null : GameData.getMapEntranceExcelMap().get(room.MapEntrance());
      if (scene == null
          || scene.getPlaneType() != PlaneType.Rogue
          || entrance == null
          || p.getEntryId() != room.MapEntrance()
          || entrance.getPlaneID() != scene.getPlaneId()
          || entrance.getFloorID() != scene.getFloorId()
          || !(scene.getEntityById(entity) instanceof EntityProp prop)
          || prop.getScene() != scene
          || prop.getPropId() != 1002
          || prop.getExcel() != GameData.getPropExcelMap().get(1002)
          || prop.getPropType() != PropType.PROP_ROGUE_OBJECT
          || !UniverseEntityLoader.roomGroups(p).contains(prop.getGroupId())
          || prop.getPropInfo() == null
          || prop.getInstId() <= 0
          || prop.getPropInfo().getID() != prop.getInstId()
          || prop.getPropInfo().getPropID() != 1002
          || prop.getPropInfo().isIsDelete()
          || prop.getPropInfo().isIsClientOnly()
          || !Set.of(PropState.Closed, PropState.Open, PropState.TriggerEnable)
              .contains(prop.getState())) return Retcode.SCENE_ENTITY_NOT_EXIST;
      final GameplayState.RogueFragmentCost cost;
      try {
        // Project rule: floor the extra for each unit price before multiplying the batch.
        var unit = RogueMiracles.fragmentPlan(run, run.getRevivalCost());
        cost = new GameplayState.RogueFragmentCost(
            Math.multiplyExact(unit.getCost(), ids.size()),
            Math.multiplyExact(unit.getExtra(), ids.size()), unit.getClockAcquisition());
      } catch (ArithmeticException overflow) {
        return Retcode.REQ_PARA_INVALID;
      }
      var payment = RogueMiracles.pay(run, cost);
      if (payment.retcode() != Retcode.SUCC) return payment.retcode();
      for (int id : ids) run.getVitals().get(id).setHp(run.getRevivalHp());
      p.save();
      payment.syncEffects(p);
      org.hkrpg.server.protocol.RogueTalent46.syncCurrency(p);
      if (p.isOnline())
        p.sendPacket(
            new emu.lunarcore.server.packet.send.PacketSyncLineupNotify(
                p.getUniverseManager().lineup()));
      return Retcode.SUCC;
    }
  }

  private RogueRevival() {}
}
