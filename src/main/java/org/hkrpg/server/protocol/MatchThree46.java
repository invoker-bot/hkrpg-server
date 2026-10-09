package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.*;

/** Commands/field numbers are matched to CN 4.6 native sends and serialization. */
public final class MatchThree46 {
  public static boolean handle(GameSession s, int command, byte[] bytes) throws IOException {
    if (!s.getProtocol().experimental()) return false;
    String name;
    if (command == 7411) name = "PBDKDFCOAPB";
    else if (command == s.getProtocol().clientCommand("MatchThreeSetBirdPosCsReq"))
      name = "MatchThreeSetBirdPosCsReq";
    else if (command == s.getProtocol().clientCommand("MatchThreeLevelEndCsReq"))
      name = "MatchThreeLevelEndCsReq";
    else return false;
    if (s.getState() != SessionState.ACTIVE) return true;
    synchronized (s.getPlayer()) {
      var schema = s.getProtocol().clientSchema().message(name);
      var code = Retcode.REQ_PARA_INVALID;
      DynamicMessage req = null;
      if (wireValid(schema, bytes, 0)) {
        req = s.getProtocol().clientSchema().parse(name, bytes);
        if (req.getUnknownFields().asMap().isEmpty()) code = Retcode.SUCC;
      }
      if (name.equals("PBDKDFCOAPB")) {
        var b = builder(s, "MatchThreeGetDataDataScRsp");
        if (code == Retcode.SUCC && MatchThreeCatalog.roster().isEmpty())
          code = Retcode.REWARD_CONFIG_NOT_EXIST;
        set(b, "retcode", code.getVal());
        if (code == Retcode.SUCC) set(b, "GJFKKFFJDAH", snapshot(s));
        s.sendClientPacket("MatchThreeGetDataDataScRsp", b.build().toByteArray());
      } else if (name.equals("MatchThreeSetBirdPosCsReq")) {
        int bird = req == null ? 0 : number(req, "HBONKLEOEEI");
        int pos = req == null ? 0 : number(req, "pos");
        if (code == Retcode.SUCC) code = new MatchThreeManager(s.getPlayer()).position(bird, pos);
        if (code == Retcode.SUCC) sync(s);
        send(
            s,
            "MatchThreeSetBirdPosScRsp",
            Map.of("retcode", code.getVal(), "HBONKLEOEEI", bird, "pos", pos));
      } else {
        int mode = req == null ? 0 : number(req, "KKHPKMAHDMK");
        int id = req == null ? 0 : number(req, "level_id");
        var stats = new HashMap<Integer, Integer>();
        if (code == Retcode.SUCC) {
          for (var value : (List<?>) value(req, "GMONIMLFKFC")) {
            var row = (DynamicMessage) value;
            if (stats.putIfAbsent(number(row, "key"), number(row, "value")) != null)
              code = Retcode.REQ_PARA_INVALID;
          }
          if (code == Retcode.SUCC)
            code =
                new MatchThreeManager(s.getPlayer())
                    .finish(
                        mode,
                        id,
                        number(req, "HBONKLEOEEI"),
                        number(req, "LALGADHLMCB"),
                        (String) value(req, "uuid"),
                        stats);
        }
        if (code == Retcode.SUCC) sync(s);
        send(
            s,
            "MatchThreeLevelEndScRsp",
            Map.of("retcode", code.getVal(), "level_id", id, "KKHPKMAHDMK", mode));
      }
    }
    return true;
  }

  static DynamicMessage snapshot(GameSession s) {
    var state = s.getPlayer().getGameplay().getMatchThree();
    var data = builder(s, "ALNGMGOJDOA");
    for (int id : MatchThreeCatalog.roster()) {
      var record = state.getBirds().get(id);
      var row = builder(s, "MLHLIMIJHDD");
      set(row, "HBONKLEOEEI", id);
      if (record != null) {
        set(row, "pos", record.getPosition());
        set(row, "count", record.getCount());
        set(row, "LALGADHLMCB", record.getMaximum());
      }
      add(data, "DBINCLPHEFN", row.build());
    }
    state.getCompleted().values().stream()
        .sorted(
            Comparator.comparingInt(GameplayState.MatchThreeLevel::getMode)
                .thenComparingInt(GameplayState.MatchThreeLevel::getId))
        .forEach(
            record -> {
              var row = builder(s, "HPIEHAMDJAP");
              set(row, "KKHPKMAHDMK", record.getMode());
              set(row, "level_id", record.getId());
              add(data, "MJNNNJPDKDI", row.build());
            });
    var field = data.getDescriptorForType().findFieldByName("GMONIMLFKFC");
    new TreeMap<>(state.getStatistics())
        .forEach(
            (key, value) -> {
              var row = DynamicMessage.newBuilder(field.getMessageType());
              set(row, "key", key);
              set(row, "value", value);
              data.addRepeatedField(field, row.build());
            });
    return data.build();
  }

  static void sync(GameSession s) {
    send(s, "MatchThreeSyncDataScNotify", Map.of("GJFKKFFJDAH", snapshot(s)));
  }

  /** Reject uint32 truncation, duplicate scalars/map members, and fields outside this contract. */
  static boolean wireValid(
      com.google.protobuf.Descriptors.Descriptor descriptor, byte[] bytes, int depth) {
    if (depth > 2 || bytes.length > 4096) return false;
    var in = com.google.protobuf.CodedInputStream.newInstance(bytes);
    var seen = new HashSet<Integer>();
    int entries = 0;
    try {
      int tag;
      while ((tag = in.readTag()) != 0) {
        var field = descriptor.findFieldByNumber(tag >>> 3);
        int wire = tag & 7;
        if (field == null || !field.isRepeated() && !seen.add(field.getNumber())) return false;
        switch (field.getJavaType()) {
          case MESSAGE -> {
            if (wire != 2
                || ++entries > 2
                || !wireValid(field.getMessageType(), in.readByteArray(), depth + 1)) return false;
          }
          case STRING -> {
            if (wire != 2 || in.readStringRequireUtf8().length() > 128) return false;
          }
          case INT -> {
            if (wire != 0 || Long.compareUnsigned(in.readUInt64(), 0xffffffffL) > 0) return false;
          }
          default -> {
            return false;
          }
        }
      }
      return true;
    } catch (IOException ex) {
      return false;
    }
  }

  private MatchThree46() {}
}
