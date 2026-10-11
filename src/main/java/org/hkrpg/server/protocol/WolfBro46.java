package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.Set;
import org.hkrpg.server.gameplay.*;

/** Pinned lifecycle, ordinary ammo and blast reports, with explicit server quantities. */
public final class WolfBro46 {
  private static final Set<String> REQUESTS =
      Set.of(
          "StartWolfBroGameCsReq",
          "GetWolfBroGameDataCsReq",
          "ArchiveWolfBroGameCsReq",
          "RestoreWolfBroGameArchiveCsReq",
          "QuitWolfBroGameCsReq",
          "WolfBroGameActivateBulletCsReq",
          "WolfBroGamePickupBulletCsReq");

  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()) return false;
    // Frozen CmdWolfBroType_HPHNFDIPEIE and the actual OKEFGCNNEKI sender both use 6514.
    // It has no named command entry; retain the original descriptor and raw command identity.
    var name = command == 6514 ? "OKEFGCNNEKI" : command == 6502 ? "LBMFCJLHNAA" : REQUESTS.stream()
            .filter(n -> session.getProtocol().clientCommand(n) == command)
            .findFirst()
            .orElse(null);
    if (name == null) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var req = session.getProtocol().clientSchema().parse(name, bytes);
    int id = req.getDescriptorForType().findFieldByName("id") == null
        ? session.getPlayer().getGameplay().getWolfBro().getActiveId() : number(req, "id");
    synchronized (session.getPlayer()) {
      var manager = new WolfBroManager(session.getPlayer());
      WolfBroManager.Result result;
      if (!wireValid(req.getDescriptorForType(), bytes, 0) || !known(req, 0))
        result = new WolfBroManager.Result(Retcode.REQ_PARA_INVALID, id, null, false);
      else
        result =
            switch (name) {
              case "GetWolfBroGameDataCsReq" -> manager.query(id);
              case "StartWolfBroGameCsReq" -> {
                if (!req.hasField(req.getDescriptorForType().findFieldByName("IDANCEPOOEI")))
                  yield new WolfBroManager.Result(Retcode.REQ_PARA_INVALID, id, null, false);
                var g = (DynamicMessage) value(req, "IDANCEPOOEI");
                yield manager.start(
                    id,
                    number(g, "group_id"),
                    number(g, "group_state"),
                    number(g, "entry_id"),
                    number(g, "GMEHAMCHJBK"),
                    motion(req));
              }
              case "ArchiveWolfBroGameCsReq" -> manager.archive(id, motion(req));
              case "RestoreWolfBroGameArchiveCsReq" -> manager.restore(id);
              case "QuitWolfBroGameCsReq" -> manager.quit(id);
              case "WolfBroGameActivateBulletCsReq" -> new WolfBroAmmunition(session.getPlayer())
                  .activate(number(req, "group_id"), number(req, "DAKENPDMDDJ"));
              case "WolfBroGamePickupBulletCsReq" -> new WolfBroAmmunition(session.getPlayer())
                  .pickup(req.hasField(req.getDescriptorForType().findFieldByName("GGDCDJDBCCN"))
                      ? point((DynamicMessage) value(req, "GGDCDJDBCCN")) : null);
              case "OKEFGCNNEKI" -> {
                // The pinned ordinary shot sender leaves its optional vector unset.
                if (req.hasField(req.getDescriptorForType().findFieldByName("GGDCDJDBCCN")))
                  yield new WolfBroManager.Result(Retcode.REQ_PARA_INVALID, id, null, false);
                @SuppressWarnings("unchecked")
                var ids = (java.util.List<Integer>) value(req, "assist_monster_entity_id_list");
                yield new WolfBroAmmunition(session.getPlayer()).use(ids);
              }
              case "LBMFCJLHNAA" -> {
                @SuppressWarnings("unchecked")
                var ids = (java.util.List<Integer>) value(req, "assist_monster_entity_id_list");
                yield new WolfBroAmmunition(session.getPlayer()).explode(ids);
              }
              default -> throw new ProtocolException("Unsupported WolfBro request");
            };
      DynamicMessage data =
          result.code() == Retcode.SUCC ? data(session, result.id(), result.run()) : null;
      if (result.changed()) {
        var notify = builder(session, "WolfBroGameDataChangeScNotify");
        set(notify, "game_data", data);
        session.sendClientPacket("WolfBroGameDataChangeScNotify", notify.build().toByteArray());
      }
      var response = switch (name) {
        case "OKEFGCNNEKI" -> "WolfBroGameUseBulletScRsp";
        case "LBMFCJLHNAA" -> "WolfBroGameExplodeMonsterScRsp";
        default -> name.replace("CsReq", "ScRsp");
      };
      var b = builder(session, response);
      set(b, "retcode", result.code().getVal());
      if (data != null && b.getDescriptorForType().findFieldByName("game_data") != null) set(b, "game_data", data);
      session.sendClientPacket(response, b.build().toByteArray());
    }
    return true;
  }

  /** Shot and blast reports use packed IDs; check full raw varints before using parsed IDs. */
  private static boolean wireValid(com.google.protobuf.Descriptors.Descriptor descriptor, byte[] bytes, int depth) {
    if (depth > 4 || bytes.length > 16384) return false;
    var input = com.google.protobuf.CodedInputStream.newInstance(bytes);
    var seen = new java.util.HashSet<Integer>();
    try {
      int tag;
      while ((tag = input.readTag()) != 0) {
        int number = tag >>> 3, wire = tag & 7;
        var field = descriptor.findFieldByNumber(number);
        if (field == null || !field.isRepeated() && !seen.add(number)) return false;
        if (field.getJavaType() == com.google.protobuf.Descriptors.FieldDescriptor.JavaType.MESSAGE) {
          if (wire != 2 || !wireValid(field.getMessageType(), input.readByteArray(), depth + 1)) return false;
        } else if (field.isRepeated() && field.getType() == com.google.protobuf.Descriptors.FieldDescriptor.Type.UINT32
            && wire == 2) {
          int previous = input.pushLimit(input.readRawVarint32()), count = 0;
          while (input.getBytesUntilLimit() > 0) {
            if (++count > 128 || Long.compareUnsigned(input.readUInt64(), 0xffffffffL) > 0) return false;
          }
          input.popLimit(previous);
        } else {
          if (wire != 0) return false;
          long value = input.readUInt64();
          if (Long.compareUnsigned(value, 0xffffffffL) > 0
              || field.getJavaType() == com.google.protobuf.Descriptors.FieldDescriptor.JavaType.ENUM
                  && field.getEnumType().findValueByNumber((int) value) == null
              || field.getJavaType() == com.google.protobuf.Descriptors.FieldDescriptor.JavaType.BOOLEAN && value > 1)
            return false;
        }
      }
      return true;
    } catch (IOException invalid) { return false; }
  }

  private static boolean known(DynamicMessage m, int depth) {
    if (depth > 4 || !m.getUnknownFields().asMap().isEmpty()) return false;
    for (var entry : m.getAllFields().entrySet()) {
      if (entry.getValue() instanceof Integer n
          && entry.getKey().getType() == com.google.protobuf.Descriptors.FieldDescriptor.Type.UINT32
          && n < 0) return false;
      if (entry.getValue() instanceof DynamicMessage nested && !known(nested, depth + 1))
        return false;
      if (entry.getValue() instanceof java.util.List<?> list) {
        if (list.size() > 128) return false;
        for (Object item : list) {
          if (item instanceof Integer n && n < 0) return false;
          if (item instanceof DynamicMessage nested && !known(nested, depth + 1)) return false;
        }
      }
    }
    return true;
  }

  private static WolfBroState.Motion motion(DynamicMessage request) {
    var field = request.getDescriptorForType().findFieldByName("motion");
    if (field == null || !request.hasField(field)) return null;
    var m = (DynamicMessage) request.getField(field);
    for (var name : Set.of("pos", "rot"))
      if (!m.hasField(m.getDescriptorForType().findFieldByName(name))) return null;
    return new WolfBroState.Motion(
        point((DynamicMessage) value(m, "pos")), point((DynamicMessage) value(m, "rot")));
  }

  private static WolfBroState.Point point(DynamicMessage m) {
    return new WolfBroState.Point(number(m, "x"), number(m, "y"), number(m, "z"));
  }

  private static DynamicMessage vector(GameSession s, WolfBroState.Point p) {
    var b = builder(s, "Vector");
    set(b, "x", p.getX());
    set(b, "y", p.getY());
    set(b, "z", p.getZ());
    return b.build();
  }

  private static DynamicMessage data(GameSession s, int id, WolfBroState.Run run) {
    var b = builder(s, "GGHOBPBFFOB");
    set(b, "id", id);
    if (run != null) {
      set(b, "uuid", run.isActive() ? run.getUuid() : "");
      var a = builder(s, "KDFPANILFOH");
      var m = builder(s, "MotionInfo");
      set(m, "pos", vector(s, run.getArchive().getPos()));
      set(m, "rot", vector(s, run.getArchive().getRot()));
      set(a, "motion", m.build());
      ammo(s, a, run.getArchiveAmmo());
      set(b, "BBEMIGELGJC", a.build());
      ammo(s, b, run.getAmmo());
    }
    return b.build();
  }

  private static void ammo(GameSession s, DynamicMessage.Builder data, WolfBroAmmoState ammo) {
    if (ammo == null) return;
    set(data, "is_bullet_activated", ammo.isActivated());
    set(data, "CHCOPKAKBNO", ammo.getCount());
    set(data, "PAOEJKABFEP", ammo.getBullets().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
        .map(e -> vector(s, e.getValue())).toList());
  }

  private WolfBro46() {}
}
