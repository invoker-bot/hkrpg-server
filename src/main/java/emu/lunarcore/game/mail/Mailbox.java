package emu.lunarcore.game.mail;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.stream.Stream;

import emu.lunarcore.LunarCore;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.player.BasePlayerManager;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.send.PacketNewMailScNotify;
import emu.lunarcore.server.packet.Retcode;
import org.hkrpg.server.gameplay.MailAttachments;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

import lombok.AccessLevel;
import lombok.Getter;
import us.hebi.quickbuf.RepeatedInt;

@Getter(AccessLevel.PRIVATE)
public class Mailbox extends BasePlayerManager implements Iterable<Mail> {
    private final Int2ObjectMap<Mail> map;
    private int lastMailId;

    public Mailbox(Player player) {
        super(player);

        this.map = new Int2ObjectOpenHashMap<>();
    }

    private int getNextMailId() {
        return ++lastMailId;
    }

    public int size() {
        synchronized (getPlayer()) { return getMap().size(); }
    }

    public void readMail(int id) {
        markReadMail(id);
    }

    public Retcode markReadMail(int id) {
        synchronized (getPlayer()) {
        Mail mail = getMap().get(id);
        if (mail == null || mail.getOwnerUid() != getPlayer().getUid()
                || mail.getExpiry() <= System.currentTimeMillis() / 1000) return Retcode.MAIL_NOT_EXIST;
        mail.setRead();
        return Retcode.SUCC;
        }
    }

    public void sendMail(Mail mail) {
        synchronized (getPlayer()) {
        // Set owner for mail first before we save
        mail.setOwner(this.getPlayer());

        // Put mail into our backing hash map
        this.putMail(mail);

        // Save mail to database
        mail.save();

        // Send packet
        this.getPlayer().sendPacket(new PacketNewMailScNotify(mail));
        }
    }

    public List<Mail> takeMailAttachments(RepeatedInt idList) {
        return claimMailAttachments(idList).mails();
    }

    public MailAttachments.Batch claimMailAttachments(RepeatedInt idList) {
        synchronized (getPlayer()) {
            if (idList == null || idList.length() > 500)
                return new MailAttachments.Batch(Retcode.MAIL_PARA_INVALID, List.of(), List.of());
            var requested = new LinkedHashSet<Integer>();
            if (idList.length() == 0) getMap().keySet().stream().sorted().forEach(requested::add);
            else for (int id : idList) {
                if (id <= 0) return new MailAttachments.Batch(Retcode.MAIL_MAIL_ID_INVALID, List.of(), List.of());
                requested.add(id);
            }
            var mails = new ArrayList<Mail>();
            var items = new ArrayList<GameItem>();
            Retcode error = Retcode.SUCC;
            for (int id : requested) {
                var mail = getMap().get(id);
                var claim = MailAttachments.claim(getPlayer(), mail);
                if (claim.retcode() == Retcode.SUCC) {
                    mails.add(mail);
                    items.addAll(claim.items());
                } else if (error == Retcode.SUCC) error = claim.retcode();
            }
            if (requested.isEmpty()) error = Retcode.MAIL_NO_MAIL_TAKE_ATTACHMENT;
            return new MailAttachments.Batch(error, List.copyOf(mails), List.copyOf(items));
        }
    }

    public IntList deleteMail(RepeatedInt idList) {
        synchronized (getPlayer()) {
        IntList deleteList = new IntArrayList();
        var requested = new LinkedHashSet<Integer>();
        if (idList.length() == 0) getMap().keySet().stream().sorted().forEach(requested::add);
        else for (int id : idList) requested.add(id);
        for (int id : requested) {
            // Get mail from hash map
            Mail mail = getMap().get(id);
            if (mail == null || !mail.isRead()
                    || mail.getAttachmentClaim() != null && mail.getAttachmentClaim().getPhase()
                        == org.hkrpg.server.gameplay.MailAttachmentClaim.Phase.PENDING
                    || mail.getAttachments() != null && !mail.getAttachments().isEmpty()
                        && !mail.hasClaimedAttachments()) {
                continue;
            }

            // Remove
            getMap().remove(id);

            // Delete from database
            mail.delete();

            // Add to delete result list
            deleteList.add(mail.getUniqueId());
        }

        return deleteList;
        }
    }

    public void sendWelcomeMail() {
        var welcomeMail = LunarCore.getConfig().getServerOptions().welcomeMail;
        if (welcomeMail == null) return;

        Mail mail = new Mail(welcomeMail.getTitle(), welcomeMail.getSender(), welcomeMail.getContent());

        new ItemParamMap(welcomeMail.getAttachments()).forEachItem(mail::addAttachment);

        this.sendMail(mail);
    }

    // Internal method to put mail into the hash map
    private void putMail(Mail mail) {
        mail.setUniqueId(this.getNextMailId());
        getMap().put(mail.getUniqueId(), mail);
    }

    @Override
    public Iterator<Mail> iterator() {
        synchronized (getPlayer()) { return List.copyOf(getMap().values()).iterator(); }
    }

    // Database

    public void loadFromDatabase() {
        synchronized (getPlayer()) {
        Stream<Mail> stream = LunarCore.getGameDatabase().getObjects(Mail.class, "ownerUid", this.getPlayer().getUid());

        stream.forEach(this::putMail);
        }
    }

}
