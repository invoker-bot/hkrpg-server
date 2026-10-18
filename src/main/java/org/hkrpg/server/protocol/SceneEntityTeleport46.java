package org.hkrpg.server.protocol;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import emu.lunarcore.util.Position;
import java.io.IOException;
import java.util.HashSet;
import org.hkrpg.server.gameplay.SceneTeleportLocation;

/** Native 1491 relocates an owned actor within its current scene runtime. */
public final class SceneEntityTeleport46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    var protocol = session.getProtocol();
    boolean acknowledgement = command == protocol.clientCommand("SceneUpdatePositionVersionNotify");
    if (!acknowledgement && command != protocol.clientCommand("SceneEntityTeleportCsReq"))
      return false;
    if (session.getState() != SessionState.ACTIVE || session.getPlayer() == null) return true;
    String name = acknowledgement ? "SceneUpdatePositionVersionNotify" : "SceneEntityTeleportCsReq";
    var descriptor = protocol.clientSchema().message(name);
    var request = wireValid(descriptor, bytes, 0) ? protocol.clientSchema().parse(name, bytes) : null;
    var player = session.getPlayer();
    synchronized (player) {
      if (session.getState() != SessionState.ACTIVE) return true;
      var scene = player.getScene();
      boolean current = scene != null && scene.getPlayer() == player && scene.getEntryId() > 0
          && scene.getPlaneId() == player.getPlaneId() && scene.getFloorId() == player.getFloorId()
          && scene.getEntryId() == player.getEntryId();
      if (acknowledgement) {
        if (current && request != null)
          scene.acknowledgeClientPosVersion((int) request.getField(descriptor.findFieldByNumber(5)));
        return true;
      }
      var response = Gameplay46.builder(session, "SceneEntityTeleportScRsp");
      Retcode result = Retcode.REQ_PARA_INVALID;
      var motion = nested(request, "entity_motion");
      var location = nested(motion, "motion");
      var pos = nested(location, "pos");
      var rot = nested(location, "rot");
      if (pos != null && rot != null
          && !Boolean.TRUE.equals(Gameplay46.value(motion, "FJOEOEDLNED"))) {
        int layer = Gameplay46.number(motion, "map_layer");
        var masks = current && scene.getFloorInfo() != null
            ? scene.getFloorInfo().getLayerToAreaMask() : null;
        if (!current || Gameplay46.number(request, "entry_id") != scene.getEntryId()
            || (long) Gameplay46.value(request, "interact_id") != scene.getRuntimeId())
          result = Retcode.SCENE_ENTRY_ID_NOT_MATCH;
        else if (scene.getAvatarByEntityId(Gameplay46.number(motion, "entity_id")) == null)
          result = Retcode.SCENE_ENTITY_NOT_EXIST;
        else if (layer < 0 || (layer != 0 && (masks == null || layer > masks.length)))
          result = Retcode.REQ_PARA_INVALID;
        else if (scene.getClientPosVersion() == -1)
          result = Retcode.SCENE_POSITION_VERSION_NOT_MATCH;
        else {
          int acceptedLayer = layer == 0 ? scene.getTeleportMapLayer() : layer;
          player.getPos().set(position(pos));
          player.getRot().set(position(rot));
          scene.setTeleportMapLayer(acceptedLayer);
          player.setSceneTeleportLocation(new SceneTeleportLocation(scene, acceptedLayer));
          scene.advanceClientPosVersion();
          Gameplay46.set(response, "entity_motion", motion);
          result = Retcode.SUCC;
        }
      }
      Gameplay46.set(response, "retcode", result.getVal());
      if (current) Gameplay46.set(response, "client_pos_version", scene.getClientPosVersion());
      session.sendClientPacket("SceneEntityTeleportScRsp", response.build().toByteArray());
    }
    return true;
  }

  private static DynamicMessage nested(DynamicMessage parent, String name) {
    if (parent == null) return null;
    var field = parent.getDescriptorForType().findFieldByName(name);
    return parent.hasField(field) ? (DynamicMessage) parent.getField(field) : null;
  }

  private static Position position(DynamicMessage vector) {
    return new Position(Gameplay46.number(vector, "x"), Gameplay46.number(vector, "y"),
        Gameplay46.number(vector, "z"));
  }

  /** Keep signed zigzag coordinates and full uint64 runtime IDs without protobuf truncation. */
  private static boolean wireValid(Descriptor descriptor, byte[] bytes, int depth) {
    if (depth > 4 || bytes.length > 4096) return false;
    var input = CodedInputStream.newInstance(bytes);
    var seen = new HashSet<Integer>();
    try {
      while (!input.isAtEnd()) {
        long rawTag = unsigned64(input, bytes);
        if (Long.compareUnsigned(rawTag, 0xffffffffL) > 0 || rawTag == 0) return false;
        int tag = (int) rawTag;
        var field = descriptor.findFieldByNumber(tag >>> 3);
        if (field == null || !seen.add(field.getNumber()) || field.isRepeated()) return false;
        switch (field.getType()) {
          case MESSAGE -> {
            if ((tag & 7) != 2) return false;
            long length = unsigned64(input, bytes);
            if (Long.compareUnsigned(length, 4096) > 0
                || !wireValid(field.getMessageType(), input.readRawBytes((int) length), depth + 1))
              return false;
          }
          case UINT32, SINT32, UINT64, BOOL -> {
            if ((tag & 7) != 0) return false;
            long value = unsigned64(input, bytes);
            if (field.getType() != com.google.protobuf.Descriptors.FieldDescriptor.Type.UINT64
                && Long.compareUnsigned(value, 0xffffffffL) > 0) return false;
            if (field.getType() == com.google.protobuf.Descriptors.FieldDescriptor.Type.BOOL
                && value != 0 && value != 1) return false;
          }
          default -> { return false; }
        }
      }
      return input.isAtEnd();
    } catch (IOException e) {
      return false;
    }
  }

  private static long unsigned64(CodedInputStream input, byte[] bytes) throws IOException {
    int start = input.getTotalBytesRead();
    long value = input.readUInt64();
    int end = input.getTotalBytesRead();
    // Protobuf accepts overflowing tenth bytes; reject aliases before any narrowing conversion.
    if (end - start == 10 && (bytes[end - 1] & 0xff) > 1)
      throw new IOException("Varint exceeds uint64");
    return value;
  }

  private SceneEntityTeleport46() {}
}
