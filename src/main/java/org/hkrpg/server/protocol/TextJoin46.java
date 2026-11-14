package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.TextJoinExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.GameDiagnostics;
import org.hkrpg.server.gameplay.TrainCakeCatch;

/** Configured text selections and the server-derived weekly cat count. */
final class TextJoin46 {
  static boolean handle(GameSession session, int command, byte[] bytes) {
    if (!session.getProtocol().experimental()) return false;
    boolean query = command == session.getProtocol().clientCommand("TextJoinQueryCsReq");
    boolean batch = command == session.getProtocol().clientCommand("TextJoinBatchSaveCsReq");
    if (!query && !batch) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var player = session.getPlayer();
    if (batch && player == null) return true;
    if (player == null) query(session, bytes);
    else synchronized (player) {
      if (query) query(session, bytes); else batch(session, bytes);
    }
    return true;
  }

  private static void query(GameSession session, byte[] bytes) {
    var response = builder(session, "TextJoinQueryScRsp");
    var rows = new ArrayList<DynamicMessage>();
    var code = Retcode.SUCC;
    try {
      var type = session.getProtocol().clientSchema().message("TextJoinQueryCsReq");
      if (!validQuery(bytes)) throw new IllegalArgumentException("Unsupported text query shape");
      var request = DynamicMessage.parseFrom(type, bytes);
      var ids = (List<?>) request.getField(type.findFieldByNumber(3));
      if (ids.size() > 1024) throw new IllegalArgumentException("Text query exceeds limit");
      for (Object entry : ids) {
        int id = (Integer) entry;
        if (id <= 0) throw new IllegalArgumentException("Invalid text join id");
        var excel = GameData.getTextJoinExcelMap().get(id);
        // A returned AvatarID row represents an actual avatar override, not its default text.
        if (excel != null && "AvatarID".equals(excel.getType())) continue;
        if (excel != null && excel.getTextJoinItemList() == null) {
          code = Retcode.REWARD_CONFIG_NOT_EXIST;
          break;
        }
        int item = excel != null && !excel.getTextJoinItemList().isEmpty()
            ? excel.getTextJoinItemList().getInt(0) : Math.multiplyExact(id, 10);
        if (item <= 0) { code = Retcode.REWARD_CONFIG_NOT_EXIST; break; }
        var player = session.getPlayer();
        if (player != null && id == 256) item = weeklySelection(player, excel);
        else if (player != null) {
          var saved = selections(player);
          if (saved.containsKey(id)) {
            item = saved.get(id);
            if (!normal(excel) || !excel.getTextJoinItemList().contains(item))
              throw new IllegalStateException("Saved text selection no longer matches its catalog");
          }
        }
        rows.add(row(session, id, item));
      }
    } catch (IOException | IllegalArgumentException | ArithmeticException invalid) {
      code = Retcode.REQ_PARA_INVALID;
    } catch (IllegalStateException invalid) {
      code = Retcode.PLAYER_DATA_ERROR;
    }
    set(response, "retcode", code.getVal());
    if (code == Retcode.SUCC) for (var row : rows) add(response, "HPEDOFFJKKP", row);
    else SceneActionDiagnostics46.rejected(session, "TextJoinQueryCsReq", "text-join-query", code, 0, 0, 0, 0);
    session.sendClientPacket("TextJoinQueryScRsp", response.build().toByteArray());
  }

  private static void batch(GameSession session, byte[] bytes) {
    var response = builder(session, "TextJoinBatchSaveScRsp");
    var rows = new ArrayList<DynamicMessage>();
    var changes = new TreeMap<Integer, Integer>();
    var code = Retcode.SUCC;
    var player = session.getPlayer();
    try {
      if (!validBatch(bytes)) throw new IllegalArgumentException("Unsupported text batch shape");
      var type = session.getProtocol().clientSchema().message("TextJoinBatchSaveCsReq");
      var request = DynamicMessage.parseFrom(type, bytes);
      var seen = new HashSet<Integer>();
      for (Object raw : (List<?>) request.getField(type.findFieldByNumber(7))) {
        var info = (DynamicMessage) raw;
        int id = (Integer) info.getField(info.getDescriptorForType().findFieldByNumber(10));
        int item = (Integer) info.getField(info.getDescriptorForType().findFieldByNumber(15));
        var excel = GameData.getTextJoinExcelMap().get(id);
        if (!seen.add(id) || !normal(excel) || !excel.getTextJoinItemList().contains(item))
          throw new IllegalArgumentException("Unsupported selection");
        if (id == 256) item = weeklySelection(player, excel);
        else changes.put(id, item);
        rows.add(row(session, id, item));
      }
      var saved = selections(player);
      var previous = new TreeMap<>(saved);
      if (!changes.entrySet().stream().allMatch(e -> Objects.equals(saved.get(e.getKey()), e.getValue()))) {
        saved.putAll(changes);
        try { player.save(); }
        catch (RuntimeException failure) {
          saved.clear(); saved.putAll(previous);
          GameDiagnostics.saveFailed(player, "text-join", 0, 0, failure);
          code = Retcode.SERVER_INTERNAL_ERROR;
        }
      }
    } catch (IOException | IllegalArgumentException invalid) {
      code = Retcode.REQ_PARA_INVALID;
    } catch (IllegalStateException invalid) {
      code = Retcode.PLAYER_DATA_ERROR;
    }
    set(response, "retcode", code.getVal());
    if (code == Retcode.SUCC) for (var row : rows) add(response, "HPEDOFFJKKP", row);
    else SceneActionDiagnostics46.rejected(session, "TextJoinBatchSaveCsReq", "text-join-save", code, 0, 0, 0, 0);
    session.sendClientPacket("TextJoinBatchSaveScRsp", response.build().toByteArray());
  }

  private static Map<Integer, Integer> selections(Player player) {
    var saved = player.getGameplay().getTextJoinSelections();
    if (saved == null || saved.size() > 1024 || saved.entrySet().stream().anyMatch(
        e -> e.getKey() == null || e.getKey() <= 0 || e.getValue() == null || e.getValue() <= 0))
      throw new IllegalStateException("Invalid saved text selections");
    return saved;
  }

  private static boolean normal(TextJoinExcel row) {
    return row != null && (row.getType() == null || row.getType().isBlank())
        && row.getTextJoinItemList() != null && !row.getTextJoinItemList().isEmpty();
  }

  private static int weeklySelection(Player player, TextJoinExcel excel) {
    if (!normal(excel) || excel.getId() != 256 || excel.getTextJoinItemList().size() != 10)
      throw new IllegalStateException("Weekly selection catalog unavailable");
    for (int i = 0; i <= 9; i++) if (excel.getTextJoinItemList().getInt(i) != 2560 + i)
      throw new IllegalStateException("Weekly selection catalog changed");
    return 2560 + TrainCakeCatch.weeklyRemaining(player, TrainCakeCatch.now());
  }

  private static DynamicMessage row(GameSession session, int id, int item) {
    var row = builder(session, "JCDNAGMDELF");
    // Native parser: field 10 is the dictionary key, field 15 is the selected item.
    set(row, "PAIPJMKCCEH", id); set(row, "HPCIKHIBFHD", item);
    return row.build();
  }

  /** This request uses packed uint32 IDs; reject truncating aliases before decoding. */
  private static boolean validQuery(byte[] bytes) throws IOException {
    if (bytes == null || bytes.length > 16_384) return false;
    var input = CodedInputStream.newInstance(bytes);
    int count = 0;
    for (int tag; (tag = input.readTag()) != 0;) {
      if (tag == 24) {
        if (++count > 1024 || Long.compareUnsigned(input.readUInt64(), 0xffffffffL) > 0) return false;
      } else if (tag == 26) {
        int limit = input.pushLimit(input.readRawVarint32());
        while (input.getBytesUntilLimit() > 0)
          if (++count > 1024 || Long.compareUnsigned(input.readUInt64(), 0xffffffffL) > 0) return false;
        input.popLimit(limit);
      } else return false;
    }
    return true;
  }

  /** Only bounded scalar selection rows are supported; custom text and avatar overrides are separate. */
  private static boolean validBatch(byte[] bytes) throws IOException {
    if (bytes == null || bytes.length == 0 || bytes.length > 16_384) return false;
    var input = CodedInputStream.newInstance(bytes);
    int count = 0;
    for (int tag; (tag = input.readTag()) != 0;) {
      if (tag != 58 || ++count > 1024) return false;
      var row = CodedInputStream.newInstance(input.readByteArray());
      boolean id = false, item = false, text = false;
      for (int field; (field = row.readTag()) != 0;) {
        if (field == 80 && !id) {
          long value = row.readUInt64(); id = value > 0 && value <= Integer.MAX_VALUE;
          if (!id) return false;
        } else if (field == 120 && !item) {
          long value = row.readUInt64(); item = value > 0 && value <= Integer.MAX_VALUE;
          if (!item) return false;
        } else if (field == 58 && !text) {
          text = true; if (!row.readStringRequireUtf8().isEmpty()) return false;
        } else return false;
      }
      if (!id || !item) return false;
    }
    return count > 0;
  }

  private TextJoin46() {}
}
