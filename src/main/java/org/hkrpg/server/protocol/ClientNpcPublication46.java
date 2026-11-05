package org.hkrpg.server.protocol;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityNpc;
import java.util.List;
import java.util.function.UnaryOperator;
import org.hkrpg.server.gameplay.ClientNpcGroups;

/** Do not give the native client a network copy of an NPC it already creates locally. */
final class ClientNpcPublication46 {
  static DynamicMessage sceneInfo(DynamicMessage message, Scene scene) {
    if (!ClientNpcGroups.supports(scene)) return message;
    return projectRows(
        message,
        "entity_group_list",
        group -> {
          var projected =
              projectRows(group, "entity_list", entity -> local(entity, scene) ? null : entity);
          return projected != group && only(projected, "group_id") ? null : projected;
        });
  }

  static DynamicMessage refresh(DynamicMessage message, Scene scene) {
    if (!ClientNpcGroups.supports(scene)) return message;
    return projectRows(
        message,
        "group_refresh_list",
        group -> {
          var projected =
              projectRows(
                  group,
                  "refresh_entity",
                  update -> {
                    var add = update.getDescriptorForType().findFieldByName("add_entity");
                    return update.hasField(add)
                            && local((DynamicMessage) update.getField(add), scene)
                        ? null
                        : update;
                  });
          return only(projected, "group_id", "refresh_type")
                  && (projected != group
                      || ClientNpcGroups.onlyLocalNpcs(scene, Gameplay46.number(group, "group_id")))
              ? null
              : projected;
        });
  }

  private static boolean local(DynamicMessage entity, Scene scene) {
    var field = entity.getDescriptorForType().findFieldByName("npc");
    if (!entity.hasField(field)) return false;
    return scene.getEntityById(Gameplay46.number(entity, "entity_id")) instanceof EntityNpc npc
        && npc.getGroupId() == Gameplay46.number(entity, "group_id")
        && npc.getInstId() == Gameplay46.number(entity, "inst_id")
        && npc.getNpcId() == Gameplay46.number((DynamicMessage) entity.getField(field), "npc_id")
        && ClientNpcGroups.clientOwned(npc);
  }

  private static boolean only(DynamicMessage message, String... names) {
    return message.getUnknownFields().asMap().isEmpty() && Gameplay46.only(message, names);
  }

  private static DynamicMessage projectRows(
      DynamicMessage parent, String name, UnaryOperator<DynamicMessage> project) {
    var field = parent.getDescriptorForType().findFieldByName(name);
    var rows = (List<?>) parent.getField(field);
    DynamicMessage.Builder result = null;
    for (int i = 0; i < rows.size(); i++) {
      var row = (DynamicMessage) rows.get(i);
      var next = project.apply(row);
      if (next != row && result == null) {
        result = parent.toBuilder().clearField(field);
        for (int n = 0; n < i; n++) result.addRepeatedField(field, rows.get(n));
      }
      if (result != null && next != null) result.addRepeatedField(field, next);
    }
    return result == null ? parent : result.build();
  }

  private ClientNpcPublication46() {}
}
