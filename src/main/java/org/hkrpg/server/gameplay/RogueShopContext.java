package org.hkrpg.server.gameplay;

import com.google.gson.JsonParser;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.NpcInfo;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityNpc;
import java.nio.file.*;
import java.util.Objects;

/** Merchant identity and flat page tasks; not a complete dialogue condition interpreter. */
final class RogueShopContext {
  record Context(String key, RogueRestMerchantRecipes.Rule recipe) {}
  private static boolean opensPage(NpcInfo info, String page) {
    try {
      var graph = info.getDialog() == null ? null : info.getDialog().getLevelGraph();
      if (graph == null || !graph.startsWith("Config/Level/") || graph.contains("\\")) return false;
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      var file = root.resolve(graph).normalize();
      if (!file.startsWith(root) || !Files.isRegularFile(file) || Files.size(file) > 1_000_000)
        return false;
      try (var input = Files.newBufferedReader(file)) {
        var json = JsonParser.parseReader(input).getAsJsonObject();
        var sequences = json.getAsJsonArray("OnStartSequece");
        if (sequences == null || sequences.size() > 100) return false;
        for (var sequence : sequences) {
          var tasks = sequence.getAsJsonObject().getAsJsonArray("TaskList");
          if (tasks == null || tasks.size() > 100) continue;
          for (var value : tasks) {
            var task = value.getAsJsonObject();
            if (task.has("$type")
                && task.has("Type")
                && task.get("$type").getAsString().equals("RPG.GameCore.RoguePropOpenPage")
                && task.get("Type").getAsString().equals(page)) return true;
          }
        }
      }
    } catch (Exception invalid) {
      return false;
    }
    return false;
  }

  static String key(Player p, int entity, int npcId, String page) {
    var context = context(p, entity, npcId, page);
    return context == null ? null : context.key();
  }

  static Context context(Player p, int entity, int npcId, String page) {
    var run = p.getGameplay().getRogue();
    if (entity <= 0
        || run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING
        || !run.isInside()
        || run.getRoster().isEmpty()
        || p.isInBattle()
        || run.pending()
        || !Objects.equals(run.getRooms().get(run.getSiteId()), run.getRoomId())) return null;
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
        || scene.getFloorInfo() == null) return null;
    if (!(scene.getEntityById(entity) instanceof EntityNpc npc)
        || npc.getScene() != scene
        || npc.getEntityId() != entity
        || npc.getNpcId() != npcId
        || npc.getInstId() <= 0
        || !UniverseEntityLoader.roomGroups(p).contains(npc.getGroupId())) return null;
    var group = scene.getResourceGroups().get(npc.getGroupId());
    if (group == null || group.getNPCList() == null) return null;
    var rows = group.getNPCList().stream().filter(info -> info.getID() == npc.getInstId()).toList();
    if (rows.size() != 1) return null;
    var info = rows.getFirst();
    if (info.getNPCID() != npcId
        || info.isIsDelete()
        || info.isIsClientOnly()
        || !info.isLoadOnInitial()) return null;
    var key = run.getSiteId() + ":" + run.getRoomId() + ":" + npc.getGroupId() + ":" + npc.getInstId();
    if ("MiracleShop".equals(page) && room.RogueRoomID() == 306 && npc.getGroupId() == 22) {
      // The server recipe is separate from the authored enhancement graph, which stays intact.
      // A changed G22 source must not fall through to an injected ordinary shop page.
      var recipe = RogueRestMerchantRecipes.rule(p, npc, group, room, entry);
      return recipe == null ? null : new Context(key, recipe);
    }
    return opensPage(info, page) ? new Context(key, null) : null;
  }

  static boolean valid(GameplayState.RogueShopPlan plan) {
    return plan != null
        && plan.getOffers() != null
        && plan.getOffers().size() <= 3
        && plan.getOffers().entrySet().stream()
            .allMatch(
                e ->
                    e.getKey() != null
                        && e.getKey() > 0
                        && e.getValue() != null
                        && e.getValue().getLevel() == 1
                        && e.getValue().getCost() >= 0
                        && e.getValue().getCost() <= 1_000_000
                        && e.getValue().debitPlan() != null);
  }

  private RogueShopContext() {}
}
