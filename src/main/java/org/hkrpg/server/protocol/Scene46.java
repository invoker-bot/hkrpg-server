package org.hkrpg.server.protocol;

import com.google.protobuf.Descriptors.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.RoguePortal;
import org.hkrpg.server.gameplay.WorldChests;

/** Native scene fields absent from the frozen lite 4.2 schema. */
public final class Scene46 {
  private static final Map<Descriptor, Boolean> HAS_ENTITY =
      new java.util.concurrent.ConcurrentHashMap<>();

  public static byte[] enrich(ProtocolSchema schema, String name, byte[] bytes, Player player) {
    var type = schema.find(name);
    if ("SceneEntityMoveScNotify".equals(name) && player != null && player.getScene() != null) {
      try {
        var original = schema.parse(name, bytes);
        if (Gameplay46.number(original, "entry_id") != player.getScene().getEntryId()) return bytes;
        var correction = original.toBuilder();
        Gameplay46.set(correction, "client_pos_version", player.getScene().getClientPosVersion());
        return correction.build().toByteArray();
      } catch (IOException e) {
        throw new ProtocolException("Malformed outgoing native position correction");
      }
    }
    if (player == null || type == null || !containsEntity(type, new HashSet<>())) return bytes;
    var scene = player.getScene();
    if (scene == null) return bytes;
    var run = player.getGameplay().getRogue();
    boolean enrichPortals =
        run.isInside() && scene.getPlaneType() == emu.lunarcore.game.enums.PlaneType.Rogue;
    var portals = new HashMap<Integer, RoguePortal>();
    if (enrichPortals)
      for (var entity : scene.getEntities().values())
        if (entity instanceof RoguePortal portal && portal.valid())
          portals.put(portal.getEntityId(), portal);
    try {
      var original = schema.parse(name, bytes);
      var changed = transform(original, schema, portals, scene, 0);
      return changed == original ? bytes : changed.toByteArray();
    } catch (IOException e) {
      throw new ProtocolException("Malformed outgoing native scene: " + name);
    }
  }

  private static boolean containsEntity(Descriptor type, Set<Descriptor> visiting) {
    var cached = HAS_ENTITY.get(type);
    if (cached != null) return cached;
    if ("SceneEntityInfo".equals(type.getName())) return true;
    if (!visiting.add(type)) return false;
    boolean found =
        type.getFields().stream()
            .filter(f -> f.getJavaType() == FieldDescriptor.JavaType.MESSAGE)
            .anyMatch(f -> containsEntity(f.getMessageType(), visiting));
    visiting.remove(type);
    // Cache positive paths only: a cycle seen during another traversal cannot prove absence.
    if (found) HAS_ENTITY.put(type, true);
    return found;
  }

  private static DynamicMessage transform(
      DynamicMessage message,
      ProtocolSchema schema,
      Map<Integer, RoguePortal> portals,
      emu.lunarcore.game.scene.Scene scene,
      int depth) {
    if (depth > 32) throw new ProtocolException("Outgoing scene nesting exceeds supported depth");
    String type = message.getDescriptorForType().getName();
    if (("SceneInfo".equals(type) || "SceneGroupRefreshScNotify".equals(type))
        && Gameplay46.number(message, "floor_id") != scene.getFloorId()) return message;
    if ("SceneInfo".equals(type) && Gameplay46.number(message, "plane_id") != scene.getPlaneId())
      return message;
    if ("SceneInfo".equals(type)) {
      if (Gameplay46.number(message, "entry_id") != scene.getEntryId()) return message;
      var dimension = message.getDescriptorForType().findFieldByName("dimension_id");
      if (dimension == null) throw new ProtocolException("Missing native scene dimension field");
      var story = message.getDescriptorForType().findFieldByName("game_story_line_id");
      if (story == null) throw new ProtocolException("Missing native scene story field");
      var selected = message.toBuilder().setField(dimension, scene.getDimensionId())
          .setField(story,scene.getStoryLineId());
      Gameplay46.set(selected, "interact_id", scene.getRuntimeId());
      Gameplay46.set(selected, "client_pos_version", scene.getClientPosVersion());
      if (scene.getPlayer().getInstance() == null)
        for (int chest : WorldChests.openedOnFloor(
            scene.getPlayer(), scene.getPlaneId(), scene.getFloorId(), scene.getDimensionId()))
          Gameplay46.add(selected, "opened_chests_list", chest);
      message = ClientNpcPublication46.sceneInfo(selected.build(), scene);
    }
    if ("SceneGroupRefreshScNotify".equals(type)) message = ClientNpcPublication46.refresh(message, scene);
    if (message.getDescriptorForType().getName().equals("SceneEntityInfo")) {
      int entityId = Gameplay46.number(message, "entity_id");
      var avatar = scene.getAvatarByEntityId(entityId);
      var actorField = message.getDescriptorForType().findFieldByName("actor");
      var lineup = scene.getPlayer().getCurrentLineup();
      if (avatar != null && message.hasField(actorField) && lineup != null
          && lineup.getLeader() >= 0 && lineup.getLeader() < lineup.getAvatars().size()
          && lineup.getAvatars().get(lineup.getLeader()) == avatar.getAvatarId()) {
        var actor = ((DynamicMessage) message.getField(actorField)).toBuilder();
        Gameplay46.set(actor, "map_layer", scene.getTeleportMapLayer());
        return message.toBuilder().setField(actorField, actor.build()).build();
      }
      var actor = scene.getEntityById(Gameplay46.number(message, "entity_id"));
      if (actor instanceof org.hkrpg.server.gameplay.RogueEventNpc npc && npc.valid()
          && npc.getGroupId() == Gameplay46.number(message, "group_id")
          && npc.getInstId() == Gameplay46.number(message, "inst_id")) {
        var field = message.getDescriptorForType().findFieldByName("npc");
        if (!message.hasField(field)) return message;
        var original = (DynamicMessage) message.getField(field);
        if (Gameplay46.number(original, "npc_id") != npc.getNpcId()) return message;
        RogueDialogue46.persist(scene);
        var changed = original.toBuilder();
        var extra = DynamicMessage.newBuilder(schema.message("NpcExtraInfo"));
        Gameplay46.set(extra, "rogue_game_info", RogueDialogue46.npcInfo(schema, npc));
        Gameplay46.set(changed, "extra_info", extra.build());
        return message.toBuilder().setField(field, changed.build()).build();
      }
      var portal = portals.get(Gameplay46.number(message, "entity_id"));
      if (portal == null
          || portal.getGroupId() != Gameplay46.number(message, "group_id")
          || portal.getInstId() != Gameplay46.number(message, "inst_id")) return message;
      var field = message.getDescriptorForType().findFieldByName("prop");
      if (!message.hasField(field)) return message;
      var original = (DynamicMessage) message.getField(field);
      if (Gameplay46.number(original, "prop_id") != portal.getPropId()) return message;
      var prop = original.toBuilder();
      prop.setField(
          prop.getDescriptorForType().findFieldByName("prop_state"), portal.getState().getVal());
      var extraField = prop.getDescriptorForType().findFieldByName("extra_info");
      var extra = ((DynamicMessage) prop.getField(extraField)).toBuilder();
      var destination = DynamicMessage.newBuilder(schema.message("PropRogueInfo"));
      destination.setField(
          destination.getDescriptorForType().findFieldByName("room_id"), portal.destinationRoom());
      destination.setField(
          destination.getDescriptorForType().findFieldByName("MAJKFIMPOOF"),
          portal.destinationSite());
      extra.setField(
          extra.getDescriptorForType().findFieldByName("rogue_game_info"), destination.build());
      prop.setField(extraField, extra.build());
      return message.toBuilder().setField(field, prop.build()).build();
    }
    DynamicMessage.Builder changed = null;
    for (var entry : message.getAllFields().entrySet()) {
      var field = entry.getKey();
      if (field.getJavaType() != FieldDescriptor.JavaType.MESSAGE
          || !containsEntity(field.getMessageType(), new HashSet<>())) continue;
      if (field.isRepeated()) {
        var rows = (List<?>) entry.getValue();
        for (int i = 0; i < rows.size(); i++) {
          var row = (DynamicMessage) rows.get(i);
          var result = transform(row, schema, portals, scene, depth + 1);
          if (row != result) {
            if (changed == null) changed = message.toBuilder();
            changed.setRepeatedField(field, i, result);
          }
        }
      } else {
        var row = (DynamicMessage) entry.getValue();
        var result = transform(row, schema, portals, scene, depth + 1);
        if (row != result) {
          if (changed == null) changed = message.toBuilder();
          changed.setField(field, result);
        }
      }
    }
    return changed == null ? message : changed.build();
  }

  public static void syncPortals(Player player) {
    if (!player.isOnline()
        || player.getSession() == null
        || !player.getSession().getProtocol().experimental()) return;
    var scene = player.getScene();
    if (scene == null || scene.getPlaneType() != emu.lunarcore.game.enums.PlaneType.Rogue) return;
    for (var entity : scene.getEntities().values()) {
      if ((entity instanceof RoguePortal portal && portal.valid())
          || (entity instanceof org.hkrpg.server.gameplay.RogueMapPortal mapPortal && mapPortal.valid()))
        player.sendPacket(
            new emu.lunarcore.server.packet.send.PacketSceneGroupRefreshScNotify(
                scene, entity, null));
    }
  }

  private Scene46() {}
}
