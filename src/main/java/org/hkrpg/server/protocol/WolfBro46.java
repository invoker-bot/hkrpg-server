package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.Set;
import org.hkrpg.server.gameplay.*;

/** Existing pinned 4.6 lifecycle messages; bullets and group scripts need independent evidence. */
public final class WolfBro46 {
  private static final Set<String> REQUESTS =
      Set.of(
          "StartWolfBroGameCsReq",
          "GetWolfBroGameDataCsReq",
          "ArchiveWolfBroGameCsReq",
          "RestoreWolfBroGameArchiveCsReq",
          "QuitWolfBroGameCsReq");

  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()) return false;
    var name =
        REQUESTS.stream()
            .filter(n -> session.getProtocol().clientCommand(n) == command)
            .findFirst()
            .orElse(null);
    if (name == null) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var req = session.getProtocol().clientSchema().parse(name, bytes);
    int id = number(req, "id");
    synchronized (session.getPlayer()) {
      var manager = new WolfBroManager(session.getPlayer());
      WolfBroManager.Result result;
      if (!Parkour46.wireValid(req.getDescriptorForType(), bytes, 0) || !known(req, 0))
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
              default -> throw new ProtocolException("Unsupported WolfBro request");
            };
      DynamicMessage data =
          result.code() == Retcode.SUCC ? data(session, result.id(), result.run()) : null;
      if (result.changed()) {
        var notify = builder(session, "WolfBroGameDataChangeScNotify");
        set(notify, "game_data", data);
        session.sendClientPacket("WolfBroGameDataChangeScNotify", notify.build().toByteArray());
      }
      var response = name.replace("CsReq", "ScRsp");
      var b = builder(session, response);
      set(b, "retcode", result.code().getVal());
      if (data != null) set(b, "game_data", data);
      session.sendClientPacket(response, b.build().toByteArray());
    }
    return true;
  }

  private static boolean known(DynamicMessage m, int depth) {
    if (depth > 4 || !m.getUnknownFields().asMap().isEmpty()) return false;
    for (var entry : m.getAllFields().entrySet()) {
      if (entry.getValue() instanceof Integer n
          && entry.getKey().getType() == com.google.protobuf.Descriptors.FieldDescriptor.Type.UINT32
          && n < 0) return false;
      if (entry.getValue() instanceof DynamicMessage nested && !known(nested, depth + 1))
        return false;
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
      set(b, "BBEMIGELGJC", a.build());
    }
    return b.build();
  }

  private WolfBro46() {}
}
