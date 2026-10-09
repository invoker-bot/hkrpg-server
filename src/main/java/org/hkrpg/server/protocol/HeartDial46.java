package org.hkrpg.server.protocol;

import com.google.protobuf.*;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.*;

/** CN 602255/602234 and protobuf writers: NPC identity is physical fields 2 and 10. */
public final class HeartDial46 {
  private record Request(int script, int entity, int value) {}

  private record PaymentRequest(int script, Map<Integer, Integer> items) {}

  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()
        || command != 6334 && command != 6305 && command != 6358 && command != 6360) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var player = session.getPlayer();
    synchronized (player) {
      if (command == 6360) {
        var payment = readPayment(bytes);
        var result =
            payment == null
                ? Retcode.REQ_PARA_INVALID
                : HeartDialSteps.submit(player, payment.script(), payment.items());
        var response = Gameplay46.builder(session, "SubmitEmotionItemScRsp");
        Gameplay46.set(response, "retcode", result.getVal());
        if (payment != null) Gameplay46.set(response, "script_id", payment.script());
        session.sendClientPacket("SubmitEmotionItemScRsp", response.build().toByteArray());
        return true;
      }
      if (command == 6334) {
        var response = Gameplay46.builder(session, "GetHeartDialInfoScRsp");
        int result = bytes.length == 0 ? 0 : Retcode.REQ_PARA_INVALID.getVal();
        if (result == 0 && !HeartDials.validState(player))
          result = Retcode.PLAYER_DATA_ERROR.getVal();
        if (result == 0) {
          enumField(response, "unlock_status", HeartDials.unlock(player));
          for (var row :
              HeartDialDefinitions.scripts.values().stream()
                  .sorted(Comparator.comparingInt(HeartDialDefinitions.Script::ScriptID))
                  .toList()) {
            int step = HeartDialSteps.step(player, row);
            if (step < 0) continue;
            int current = HeartDials.current(player, row);
            if (!HeartDials.choices(row).containsKey(current)) {
              result = Retcode.PLAYER_DATA_ERROR.getVal();
              response.clearField(
                  response.getDescriptorForType().findFieldByName("script_info_list"));
              break;
            }
            var script =
                DynamicMessage.newBuilder(
                    session.getProtocol().clientSchema().message("HeartDialScriptInfo"));
            Gameplay46.set(script, "script_id", row.ScriptID());
            enumField(script, "step", step);
            enumField(script, "cur_emotion_type", current);
            Gameplay46.add(response, "script_info_list", script.build());
          }
          if (result == 0)
            for (var entry : new TreeMap<>(player.getGameplay().getHeartDialReports()).entrySet()) {
              if (entry.getValue() == null || !entry.getValue().isApplied()) continue;
              var row = HeartDialDefinitions.scripts.get(entry.getValue().getScript());
              if (!Objects.equals(
                  HeartDials.dialogue(
                      row, entry.getValue().getStep(), entry.getValue().getEmotion()),
                  entry.getKey())) continue;
              var dialogue =
                  DynamicMessage.newBuilder(
                      session.getProtocol().clientSchema().message("HeartDialDialogueInfo"));
              Gameplay46.set(dialogue, "dialogue_id", entry.getKey());
              Gameplay46.set(dialogue, "GDPHOIMONLN", true);
              Gameplay46.add(response, "dialogue_info_list", dialogue.build());
            }
        }
        Gameplay46.set(response, "retcode", result);
        session.sendClientPacket("GetHeartDialInfoScRsp", response.build().toByteArray());
        return true;
      }
      var request = read(bytes, command == 6305);
      var result =
          request == null
              ? Retcode.REQ_PARA_INVALID
              : command == 6305
                  ? HeartDials.change(player, request.script(), request.entity(), request.value())
                  : HeartDials.finish(player, request.script(), request.value(), request.entity());
      String name =
          command == 6305 ? "ChangeScriptEmotionScRsp" : "FinishEmotionDialoguePerformanceScRsp";
      var response = Gameplay46.builder(session, name);
      Gameplay46.set(response, "retcode", result.getVal());
      if (request != null) {
        Gameplay46.set(response, "script_id", request.script());
        if (command == 6305) {
          var row = HeartDialDefinitions.scripts.get(request.script());
          int current = row == null ? 0 : HeartDials.current(player, row);
          enumField(response, "emotion_type", Math.max(0, Math.min(3, current)));
        } else Gameplay46.set(response, "dialogue_id", request.value());
      }
      session.sendClientPacket(name, response.build().toByteArray());
      return true;
    }
  }

  private static void enumField(DynamicMessage.Builder message, String name, int value) {
    var field = message.getDescriptorForType().findFieldByName(name);
    var enumValue = field.getEnumType().findValueByNumber(value);
    if (enumValue == null) throw new ProtocolException("Unknown clockwork enum: " + name);
    message.setField(field, enumValue);
  }

  /**
   * Saving an attempted publication prevents repeats; reconnect query supplies the current state.
   */
  public static void syncUnlock(emu.lunarcore.game.player.Player player) {
    synchronized (player) {
      var session = player.getSession();
      if (!player.isOnline()
          || session == null
          || session.getState() != SessionState.ACTIVE
          || !session.getProtocol().experimental()
          || !HeartDials.validState(player)) return;
      int status = HeartDials.unlock(player);
      if (status == player.getGameplay().getHeartDialUnlockNotified()) return;
      var notification = Gameplay46.builder(session, "HeartDialScriptChangeScNotify");
      enumField(notification, "unlock_status", status);
      player.getGameplay().recordHeartDialUnlock(status);
      player.save();
      session.sendClientPacket("HeartDialScriptChangeScNotify", notification.build().toByteArray());
    }
  }

  public static void syncScript(emu.lunarcore.game.player.Player player, int id) {
    synchronized (player) {
      var session = player.getSession();
      var row = HeartDialDefinitions.scripts.get(id);
      int step = HeartDialSteps.step(player, row);
      var payment = player.getGameplay().getHeartDialPayments().get(id);
      var lock = player.getGameplay().getHeartDialLocks().get(id);
      if (!player.isOnline()
          || session == null
          || session.getState() != SessionState.ACTIVE
          || !session.getProtocol().experimental()
          || !HeartDials.validState(player)
          || step < 0
          || (payment == null || !payment.isPaid()) && !HeartDialLocks.committed(player, id)
          || step == (payment != null ? payment.getNotifiedStep() : lock.getNotifiedStep())) return;
      var notification = Gameplay46.builder(session, "HeartDialScriptChangeScNotify");
      enumField(notification, "unlock_status", HeartDials.unlock(player));
      var script =
          DynamicMessage.newBuilder(
              session.getProtocol().clientSchema().message("HeartDialScriptInfo"));
      Gameplay46.set(script, "script_id", id);
      enumField(script, "step", step);
      enumField(script, "cur_emotion_type", HeartDials.current(player, row));
      Gameplay46.add(notification, "changed_script_info_list", script.build());
      if (payment != null) payment.recordNotifiedStep(step);
      else lock.recordNotifiedStep(step);
      player.save();
      session.sendClientPacket("HeartDialScriptChangeScNotify", notification.build().toByteArray());
    }
  }

  private static PaymentRequest readPayment(byte[] bytes) {
    if (bytes.length > 256) return null;
    try {
      var input = CodedInputStream.newInstance(bytes);
      int script = 0;
      Map<Integer, Integer> items = null;
      int tag;
      while ((tag = input.readTag()) != 0) {
        if (tag == 120 && script == 0) {
          long id = input.readUInt64();
          if (id <= 0 || id > Integer.MAX_VALUE) return null;
          script = (int) id;
        } else if (tag == 106 && items == null) {
          var list = CodedInputStream.newInstance(input.readByteArray());
          if (list.readTag() != 18) return null;
          var item = CodedInputStream.newInstance(list.readByteArray());
          int id = 0, count = 0;
          int itemTag;
          while ((itemTag = item.readTag()) != 0) {
            long number = item.readUInt64();
            if (number <= 0 || number > Integer.MAX_VALUE) return null;
            if (itemTag == 120 && id == 0) id = (int) number;
            else if (itemTag == 48 && count == 0) count = (int) number;
            else return null;
          }
          if (id <= 0 || count <= 0 || !item.isAtEnd() || list.readTag() != 0 || !list.isAtEnd())
            return null;
          items = Map.of(id, count);
        } else return null;
      }
      return script > 0 && items != null && input.isAtEnd()
          ? new PaymentRequest(script, items)
          : null;
    } catch (IOException invalid) {
      return null;
    }
  }

  private static Request read(byte[] bytes, boolean change) {
    if (bytes.length > 128) return null;
    try {
      var input = CodedInputStream.newInstance(bytes);
      int script = 0, entity = 0, value = change ? 0 : -1;
      boolean scriptSeen = false, entitySeen = false, valueSeen = false;
      int tag;
      while ((tag = input.readTag()) != 0) {
        long number = input.readUInt64();
        if (number < 0 || number > Integer.MAX_VALUE) return null;
        if (tag == (change ? 8 : 24)) {
          if (scriptSeen || number == 0) return null;
          scriptSeen = true;
          script = (int) number;
        } else if (tag == (change ? 16 : 80)) {
          if (entitySeen || number == 0) return null;
          entitySeen = true;
          entity = (int) number;
        } else if (tag == (change ? 96 : 32)) {
          if (valueSeen || change && number > 3 || !change && number == 0) return null;
          valueSeen = true;
          value = (int) number;
        } else return null;
      }
      return scriptSeen && entitySeen && (change || valueSeen) && input.isAtEnd()
          ? new Request(script, entity, value)
          : null;
    } catch (IOException invalid) {
      return null;
    }
  }

  private HeartDial46() {}
}
