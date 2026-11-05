package org.hkrpg.server.gameplay;

import com.google.gson.JsonObject;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.game.scene.entity.GameEntity;
import java.util.Collection;

/** Client-owned placements still exist as logical NPCs for the server's mission checks. */
public final class ClientNpcGroups {
  static void markSource(GroupInfo group, JsonObject raw) {
    var side = raw.get("LoadSide");
    // The original 4.6 runtime group constructor defaults to Client (enum zero).
    boolean client =
        side == null
            || side.isJsonPrimitive()
                && side.getAsJsonPrimitive().isString()
                && "Client".equals(side.getAsString());
    var initial = raw.get("LoadOnInitial");
    var gate = group.getLoadCondition();
    boolean active =
        initial != null
                && initial.isJsonPrimitive()
                && initial.getAsJsonPrimitive().isBoolean()
                && initial.getAsBoolean()
            || gate != null && gate.getConditions() != null && gate.getConditions().length > 0;
    if (client
        && active
        && group.getOwnerMainMissionID() == 0
        && !empty(group.getNPCList())
        && (!raw.has("Category") || group.getCategory() == GroupInfo.GroupCategory.Normal))
      group.setClientOwnedNpcSource(true);
  }

  public static boolean supports(Scene scene) {
    if (scene == null
        || scene.getFloorInfo() == null
        || scene.getDimensionId() != 0
        || scene.getStoryLineId() != 0) return false;
    return switch (scene.getPlaneType()) {
      case Town, Maze, Train -> true;
      default -> false;
    };
  }

  private static GroupInfo group(Scene scene, int id) {
    if (!supports(scene)) return null;
    var source = scene.getResourceGroups().get(id);
    return source != null && source.isClientOwnedNpcSource() ? source : null;
  }

  /** Whether this authored placement can load without a server entity. */
  public static boolean clientOwned(GameEntity entity) {
    if (!(entity instanceof EntityNpc npc) || npc.getClass() != EntityNpc.class) return false;
    var source = group(npc.getScene(), npc.getGroupId());
    return source != null
        && source.getNPCList() != null
        && !publishBernardWithTrigger(npc, source)
        && source.getNPCList().stream()
                .filter(
                    info ->
                        info != null
                            && !info.isIsDelete()
                            && info.getID() == npc.getInstId()
                            && info.getNPCID() == npc.getNpcId())
                .count()
            == 1;
  }

  private static boolean publishBernardWithTrigger(EntityNpc npc, GroupInfo source) {
    // The native loader registers group 16 when it receives the interaction prop, so
    // it skips the local NPC pass for that group. Keep the same route for adds and deletes.
    return npc.getScene().getFloorId() == 10001001
        && source.getId() == 16
        && npc.getInstId() == 400001
        && npc.getNpcId() == 10403
        && source.getPropList() != null
        && source.getPropList().stream()
            .anyMatch(
                prop ->
                    prop != null
                        && prop.getID() == 300001
                        && prop.getPropID() == 100
                        && prop.isLoadOnInitial()
                        && !prop.isIsDelete()
                        && !prop.isIsClientOnly());
  }

  public static boolean shouldPublish(GameEntity entity) {
    if (!clientOwned(entity)) return true;
    var session = entity.getScene().getPlayer().getSession();
    return session == null || !session.getProtocol().experimental();
  }

  public static boolean onlyLocalNpcs(Scene scene, int id) {
    var source = group(scene, id);
    return source != null
        && empty(source.getPropList())
        && empty(source.getMonsterList())
        && empty(source.getGroupStateList())
        && (source.getGroupPropertyMap() == null || source.getGroupPropertyMap().isEmpty())
        && (source.getLevelGraph() == null || source.getLevelGraph().isBlank());
  }

  private static boolean empty(Collection<?> values) {
    return values == null || values.isEmpty();
  }

  private ClientNpcGroups() {}
}
