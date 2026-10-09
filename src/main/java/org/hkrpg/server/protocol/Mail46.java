package org.hkrpg.server.protocol;

import com.google.protobuf.CodedInputStream;
import emu.lunarcore.proto.ItemListOuterClass.ItemList;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.MailAttachments;
import us.hebi.quickbuf.RepeatedInt;

/** Frozen 4.6 mail fields with bounded, non-truncating request decoding. */
public final class Mail46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    var protocol = session.getProtocol();
    if (!protocol.experimental()) return false;
    String name = null;
    for (var candidate : List.of("MarkReadMailCsReq", "TakeMailAttachmentCsReq", "DelMailCsReq"))
      if (command == protocol.clientCommand(candidate)) {
        name = candidate;
        break;
      }
    if (name == null) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var requested = decode(name, bytes);
    String responseName = name.replace("CsReq", "ScRsp");
    var response = Gameplay46.builder(session, responseName);
    var code = Retcode.REQ_PARA_INVALID;
    synchronized (session.getPlayer()) {
      if (name.equals("MarkReadMailCsReq")) {
        if (requested != null) {
          int id = requested.get(0);
          code = session.getPlayer().getMailbox().markReadMail(id);
          Gameplay46.set(response, "id", id);
        }
      } else if (name.equals("DelMailCsReq")) {
        if (requested != null) {
          var deleted = session.getPlayer().getMailbox().deleteMail(requested);
          code = deleted.isEmpty() ? Retcode.MAIL_NO_MAIL_TO_DEL : Retcode.SUCC;
          var field = response.getDescriptorForType().findFieldByName("id_list");
          for (int id : deleted) response.addRepeatedField(field, id);
        }
      } else {
        var result =
            requested == null
                ? new MailAttachments.Batch(code, List.of(), List.of())
                : session.getPlayer().getMailbox().claimMailAttachments(requested);
        code = result.retcode();
        var field = response.getDescriptorForType().findFieldByName("succ_mail_id_list");
        for (var mail : result.mails()) response.addRepeatedField(field, mail.getUniqueId());
        var items = ItemList.newInstance();
        result.items().forEach(item -> items.addItemList(item.toProto()));
        Gameplay46.set(
            response,
            "attachment",
            protocol
                .clientSchema()
                .parse("ItemList", protocol.translate("ItemList", items.toByteArray(), true)));
      }
      Gameplay46.set(response, "retcode", code.getVal());
      session.sendClientPacket(responseName, response.build().toByteArray());
    }
    return true;
  }

  private static RepeatedInt decode(String name, byte[] raw) {
    if (raw == null || raw.length > 8192) return null;
    var ids = RepeatedInt.newEmptyInstance();
    boolean read = name.equals("MarkReadMailCsReq"), take = name.equals("TakeMailAttachmentCsReq");
    int number = read ? 9 : take ? 2 : 13;
    boolean optionalSeen = false;
    try {
      var input = CodedInputStream.newInstance(raw);
      int tag;
      while ((tag = input.readTag()) != 0) {
        if (tag == number * 8 && (!read || ids.length() == 0)) {
          if (!append(ids, input.readUInt64())) return null;
        } else if (!read && tag == number * 8 + 2) {
          var packed = CodedInputStream.newInstance(input.readByteArray());
          while (!packed.isAtEnd()) if (!append(ids, packed.readUInt64())) return null;
        } else if (take && tag == 32 && !optionalSeen) {
          optionalSeen = true;
          if (input.readUInt64() != 0) return null;
        } else return null;
      }
      return !read || ids.length() == 1 ? ids : null;
    } catch (IOException invalid) {
      return null;
    }
  }

  private static boolean append(RepeatedInt ids, long value) {
    if (value <= 0 || value > Integer.MAX_VALUE || ids.length() >= 500) return false;
    ids.add((int) value);
    return true;
  }

  private Mail46() {}
}
