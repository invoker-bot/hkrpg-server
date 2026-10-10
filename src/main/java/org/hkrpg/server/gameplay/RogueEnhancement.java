package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Classic Herta in resource rest rooms; pricing rule follows AGPL LunarCore ec315bb1. */
public final class RogueEnhancement {
  public static Retcode context(Player p) {
    var run = p.getGameplay().getRogue();
    if (run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING
        || !run.isInside()
        || run.getRoster().isEmpty()
        || p.isInBattle()
        || run.pending()) return Retcode.ROGUE_STATUS_NOT_MATCH;
    var room = GameplayDefinitions.rooms.get(run.getRoomId());
    var entry = room == null ? null : GameData.getMapEntranceExcelMap().get(room.MapEntrance());
    var scene = p.getScene();
    if (room == null
        || room.RogueRoomType() != 5
        || entry == null
        || scene == null
        || p.getEntryId() != room.MapEntrance()
        || scene.getPlaneType() != PlaneType.Rogue
        || scene.getPlaneId() != entry.getPlaneID()
        || scene.getFloorId() != entry.getFloorID()
        || scene.getFloorInfo() == null) return Retcode.SCENE_ENTITY_NOT_EXIST;
    for (var entity : scene.getEntities().values()) {
      if (!(entity instanceof EntityNpc npc)
          || npc.getScene() != scene
          || npc.getNpcId() != 1013
          || npc.getEntityId() <= 0
          || scene.getEntityById(npc.getEntityId()) != npc
          || npc.getInstId() <= 0
          || !UniverseEntityLoader.roomGroups(p).contains(npc.getGroupId())) continue;
      var group = scene.getResourceGroups().get(npc.getGroupId());
      if (group != null
          && group.getNPCList() != null
          && group.getNPCList().stream()
              .anyMatch(
                  info ->
                      info.getID() == npc.getInstId()
                          && info.getNPCID() == 1013
                          && !info.isIsDelete()
                          && !info.isIsClientOnly()
                          && info.isLoadOnInitial())) return Retcode.SUCC;
    }
    return Retcode.SCENE_ENTITY_NOT_EXIST;
  }

  private static boolean resource(int id) {
    return GameData.getMazeBuffExcelMap().get(id, 1) != null
        && GameData.getMazeBuffExcelMap().get(id, 2) != null
        && GameplayDefinitions.blessings.stream()
            .anyMatch(b -> b.MazeBuffID() == id && b.MazeBuffLevel() == 2 && b.RogueVersion() == 1);
  }

  /** The first advertised quote is saved; later queries cannot reprice it. */
  public static Map<Integer, Integer> quotes(Player p) {
    synchronized (p) {
      if (context(p) != Retcode.SUCC) return Map.of();
      var run = p.getGameplay().getRogue();
      var quotes = new TreeMap<Integer, Integer>();
      boolean changed = false;
      for (var owned : run.getBlessings().entrySet()) {
        int id = owned.getKey();
        if (owned.getValue() != 1 || !resource(id)) continue;
        var plan = run.getEnhancementPlans().get(id);
        if (plan == null) {
          var rows =
              GameplayDefinitions.blessings.stream()
                  .filter(
                      b -> b.MazeBuffID() == id && b.MazeBuffLevel() == 1 && b.RogueVersion() == 1)
                  .toList();
          if (rows.size() != 1) continue;
          int base =
              switch (rows.getFirst().RogueBuffCategory()) {
                case "Common" -> 100;
                case "Rare" -> 130;
                case "Legendary" -> 160;
                default -> -1;
              };
          if (base < 0) continue;
          var cost = RogueMiracles.enhancementPlan(run, base);
          plan = new GameplayState.RogueEnhancementPlan(cost);
          run.getEnhancementPlans().put(id, plan);
          changed = true;
        }
        if (!plan.isCompleted() && plan.debitPlan() != null) quotes.put(id, plan.getCost());
      }
      if (changed) p.save();
      return Collections.unmodifiableMap(quotes);
    }
  }

  public static Retcode enhance(Player p, int id) {
    synchronized (p) {
      var status = context(p);
      if (status != Retcode.SUCC) return status;
      var run = p.getGameplay().getRogue();
      var plan = run.getEnhancementPlans().get(id);
      if (id <= 0
          || !Objects.equals(run.getBlessings().get(id), 1)
          || plan == null
          || plan.isCompleted()
          || plan.getCost() < 0
          || !resource(id)) return Retcode.REQ_PARA_INVALID;
      var history=p.getGameplay().getRogueEnhancements();
      if(history==null || !history.canEnhance())return Retcode.PLAYER_DATA_ERROR;
      var debit = RogueMiracles.pay(run, plan.debitPlan());
      if (debit.retcode() != Retcode.SUCC) return debit.retcode();
      run.enhance(id);
      plan.complete();
      if(!history.recordEnhancement())throw new IllegalStateException("Prechecked enhancement count changed");
      p.save();
      p.getQuestManager().onRogueEnhancementProgress();
      debit.syncEffects(p);
      org.hkrpg.server.protocol.Universe46.syncBlessing(p, id, 0);
      org.hkrpg.server.protocol.RogueTalent46.syncCurrency(p);
      return Retcode.SUCC;
    }
  }

  private RogueEnhancement() {}
}
