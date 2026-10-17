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
    if (command == protocol.clientCommand("GetMailCsReq")) {
      if (session.getState() == SessionState.ACTIVE) snapshot(session, bytes);
      return true;
    }
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

  /** CN4.6 field 4 is a zero-based offset; field 9 is the requested row count. */
  private static void snapshot(GameSession session, byte[] bytes) throws IOException {
    var response = Gameplay46.builder(session, "GetMailScRsp");
    long[] page = decodePage(bytes);
    if (page == null) {
      Gameplay46.set(response, "retcode", Retcode.REQ_PARA_INVALID.getVal());
    } else {
      synchronized (session.getPlayer()) {
        var mails = new ArrayList<emu.lunarcore.game.mail.Mail>();
        session.getPlayer().getMailbox().forEach(mails::add);
        // The backing mailbox is a hash map. Stable IDs prevent pages from skipping rows.
        mails.sort(Comparator.comparingInt(emu.lunarcore.game.mail.Mail::getUniqueId));
        int start = (int) Math.min(page[0], mails.size());
        int count = page[1] == 0 ? 50 : (int) Math.min(page[1], 50);
        int end = start + Math.min(count, mails.size() - start);
        Gameplay46.set(response, "retcode", Retcode.SUCC.getVal());
        Gameplay46.set(response, "start", (int) page[0]);
        Gameplay46.set(response, "total_num", mails.size());
        Gameplay46.set(response, "is_end", end == mails.size());
        for (int i = start; i < end; i++) {
          var protocol = session.getProtocol();
          var mail = protocol.clientSchema().parse("ClientMail",
              protocol.translate("ClientMail", mails.get(i).toProto().toByteArray(), true));
          Gameplay46.add(response, "mail_list", mail);
        }
      }
    }
    session.sendClientPacket("GetMailScRsp", response.build().toByteArray());
  }

  private static long[] decodePage(byte[] bytes) {
    if (bytes == null || bytes.length > 128) return null;
    long[] page = new long[2];
    boolean[] seen = new boolean[2];
    try {
      var input = CodedInputStream.newInstance(bytes);
      while (!input.isAtEnd()) {
        long tag = readPageUInt32(input);
        int index = tag == 32 ? 0 : tag == 72 ? 1 : -1;
        if (index < 0 || seen[index]) return null;
        seen[index] = true;
        page[index] = readPageUInt32(input);
      }
      return page;
    } catch (IOException invalid) {
      return null;
    }
  }

  private static long readPageUInt32(CodedInputStream input) throws IOException {
    long value = 0;
    for (int shift = 0; shift <= 28; shift += 7) {
      int next = input.readRawByte() & 0xff;
      if (shift == 28 && (next & 0xf0) != 0) throw new IOException("Mail uint32 overflow");
      value |= (long) (next & 0x7f) << shift;
      if ((next & 0x80) == 0) return value;
    }
    throw new IOException("Mail uint32 overflow");
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
