package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.hkrpg.server.gameplay.*;

/** CN 4.6 parkour request/response fields, verified against the read-only local client. */
public final class Parkour46 {
  private static final Set<String> REQUESTS =
      Set.of(
          "ParkourGetDataCsReq",
          "ParkourStartLevelCsReq",
          "ParkourEndLevelCsReq",
          "ParkourGetRankingInfoCsReq");

  public static boolean handle(GameSession s, int command, byte[] bytes) throws IOException {
    if (!s.getProtocol().experimental()) return false;
    String name =
        REQUESTS.stream()
            .filter(n -> s.getProtocol().clientCommand(n) == command)
            .findFirst()
            .orElse(null);
    if (name == null) return false;
    if (s.getState() != SessionState.ACTIVE) return true;
    var req = s.getProtocol().clientSchema().parse(name, bytes);
    var p = s.getPlayer();
    synchronized (p) {
      var manager = new ParkourManager(p);
      switch (name) {
        case "ParkourGetRankingInfoCsReq" -> {
          var b = builder(s, "ParkourGetRankingInfoScRsp");
          if (!req.getUnknownFields().asMap().isEmpty())
            set(b, "retcode", Retcode.REQ_PARA_INVALID.getVal());
          else {
            try {
              var snapshot = ParkourRanking.snapshot(p);
              if (snapshot.isEmpty()) set(b, "retcode", Retcode.PLAYER_DATA_ERROR.getVal());
              else {
                set(b, "LGFNFABAAGA", rankings(s, snapshot.get().aggregate()));
                for (var board : snapshot.get().levels()) add(b, "JNMFKJHCOEG", rankings(s, board));
              }
            } catch (RuntimeException failure) {
              emu.lunarcore.LunarCore.getLogger().debug("Parkour ranking lookup failed", failure);
              set(b, "retcode", Retcode.SYSTEM_BUSY.getVal());
            }
          }
          s.sendClientPacket("ParkourGetRankingInfoScRsp", b.build().toByteArray());
        }
        case "ParkourGetDataCsReq" -> {
          var b = builder(s, "ParkourGetDataScRsp");
          if (!req.getUnknownFields().asMap().isEmpty())
            set(b, "retcode", Retcode.REQ_PARA_INVALID.getVal());
          else
            p.getGameplay().getParkour().getRecords().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                    e -> {
                      if (!GameplayDefinitions.parkourLevels.containsKey(e.getKey())) return;
                      add(b, "CGIPFMLDPNA", record(s, e.getKey(), e.getValue()));
                    });
          s.sendClientPacket("ParkourGetDataScRsp", b.build().toByteArray());
        }
        case "ParkourStartLevelCsReq" -> {
          int id = number(req, "level_id"), ball = number(req, "CEKKJHBNEOD");
          var code =
              wireValid(req.getDescriptorForType(), bytes, 0) && valid(req, 0)
                  ? manager.start(id, ball)
                  : Retcode.REQ_PARA_INVALID;
          send(
              s,
              "ParkourStartLevelScRsp",
              Map.of("retcode", code.getVal(), "level_id", id, "CEKKJHBNEOD", ball));
        }
        case "ParkourEndLevelCsReq" -> {
          int id = number(req, "level_id"), time = number(req, "time");
          int reason = ((EnumValueDescriptor) value(req, "end_reason")).getNumber();
          var result =
              wireValid(req.getDescriptorForType(), bytes, 0) && valid(req, 0)
                  ? manager.finish(
                      id,
                      number(req, "CEKKJHBNEOD"),
                      time,
                      number(req, "total_time"),
                      number(req, "rank"),
                      reason)
                  : new ParkourManager.Result(
                      Retcode.REQ_PARA_INVALID, id, time, reason, false, false, false);
          var b = builder(s, "ParkourEndLevelScRsp");
          set(b, "retcode", result.code().getVal());
          set(b, "level_id", id);
          set(b, "time", time);
          var reasonField = b.getDescriptorForType().findFieldByName("end_reason");
          var enumValue = reasonField.getEnumType().findValueByNumber(reason);
          if (enumValue != null) b.setField(reasonField, enumValue);
          set(b, "ADJLGNBJAOC", result.success());
          set(b, "AJGOLAKNGPA", result.firstPass());
          if (result.newRecord())
            set(b, "ENKCCEACJKL", record(s, id, p.getGameplay().getParkour().getRecords().get(id)));
          s.sendClientPacket("ParkourEndLevelScRsp", b.build().toByteArray());
        }
        default -> throw new ProtocolException("Unsupported parkour request");
      }
    }
    return true;
  }

  private static DynamicMessage record(GameSession s, int id, GameplayState.ParkourRecord record) {
    var b = builder(s, "BADOONPCDLO");
    set(b, "level_id", id);
    set(b, "time", record.getTime());
    set(b, "AJGOLAKNGPA", record.isPassed());
    return b.build();
  }

  private static DynamicMessage rankings(GameSession s, ParkourRanking.Board board) {
    var b = builder(s, "COIMBCBMNIE");
    set(b, "level_id", board.level());
    for (var entry : board.entries()) {
      var row = builder(s, "HANMCLKOCBO");
      set(row, "id", entry.uid());
      set(row, "time", entry.time());
      set(row, "LJJGFIPLDLI", entry.ball());
      add(b, "BEDHEKAGAPE", row.build());
    }
    return b.build();
  }

  /**
   * Telemetry is bounded and never interpreted as inventory, missions, race simulation or rewards.
   */
  private static boolean valid(DynamicMessage message, int depth) {
    if (depth > 4 || !message.getUnknownFields().asMap().isEmpty()) return false;
    for (var entry : message.getAllFields().entrySet()) {
      var values =
          entry.getKey().isRepeated()
              ? (java.util.List<?>) entry.getValue()
              : java.util.List.of(entry.getValue());
      if (values.size() > 128) return false;
      for (var value : values) {
        if (value instanceof Integer number && number < 0) return false;
        if (value instanceof EnumValueDescriptor enumeration && enumeration.getIndex() < 0)
          return false;
        if (value instanceof DynamicMessage nested && !valid(nested, depth + 1)) return false;
      }
    }
    return true;
  }

  /** Protobuf readUInt32 truncates a large varint: reject aliases before evaluating domain IDs. */
  static boolean wireValid(
      com.google.protobuf.Descriptors.Descriptor descriptor, byte[] bytes, int depth) {
    if (depth > 4 || bytes.length > 16384) return false;
    var input = com.google.protobuf.CodedInputStream.newInstance(bytes);
    var seen = new java.util.HashSet<Integer>();
    try {
      int tag;
      while ((tag = input.readTag()) != 0) {
        int number = tag >>> 3, wire = tag & 7;
        var field = descriptor.findFieldByNumber(number);
        if (field == null || !field.isRepeated() && !seen.add(number)) return false;
        if (field.getJavaType()
            == com.google.protobuf.Descriptors.FieldDescriptor.JavaType.MESSAGE) {
          if (wire != 2 || !wireValid(field.getMessageType(), input.readByteArray(), depth + 1))
            return false;
        } else {
          if (wire != 0) return false;
          long value = input.readUInt64();
          if (Long.compareUnsigned(value, 0xffffffffL) > 0) return false;
          if (field.getJavaType() == com.google.protobuf.Descriptors.FieldDescriptor.JavaType.ENUM
              && field.getEnumType().findValueByNumber((int) value) == null) return false;
          if (field.getJavaType()
                  == com.google.protobuf.Descriptors.FieldDescriptor.JavaType.BOOLEAN
              && value > 1) return false;
        }
      }
      return true;
    } catch (IOException ex) {
      return false;
    }
  }
}
