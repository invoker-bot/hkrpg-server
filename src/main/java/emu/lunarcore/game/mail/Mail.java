package emu.lunarcore.game.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bson.types.ObjectId;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import dev.morphia.annotations.Indexed;
import emu.lunarcore.LunarCore;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.ClientMailOuterClass.ClientMail;
import emu.lunarcore.proto.ItemListOuterClass.ItemList;
import lombok.Getter;
import lombok.Setter;

@Getter
@Entity(value = "mail", useDiscriminator = false)
public class Mail {
    @Id private ObjectId id;
    @Indexed private int ownerUid; // Uid of player that this mail belongs to

    @Setter
    private transient int uniqueId;

    private String title;
    private String sender;
    private String content;
    private long time;
    private long expiry;
    private boolean isRead;
    private List<GameItem> attachments;
    private org.hkrpg.server.gameplay.MailAttachmentClaim attachmentClaim;

    @Deprecated // Morphia only!
    public Mail() {}

    public Mail(String title, String sender, String content) {
        this.title = title;
        this.sender = sender;
        this.content = content;
        this.time = System.currentTimeMillis() / 1000;
        this.expiry = this.time + TimeUnit.DAYS.toSeconds(30);
        this.attachmentClaim = org.hkrpg.server.gameplay.MailAttachmentClaim.ready();
    }

    public void setOwner(Player player) {
        this.ownerUid = player.getUid();
    }

    public void setRead() {
        if (!this.isRead) {
            if (this.attachmentClaim == null)
                this.attachmentClaim = org.hkrpg.server.gameplay.MailAttachmentClaim.ready();
            this.isRead = true;
            this.save();
        }
    }

    public void addAttachment(GameItem item) {
        if (hasClaimedAttachments() || this.attachmentClaim != null
                && this.attachmentClaim.getPhase() != org.hkrpg.server.gameplay.MailAttachmentClaim.Phase.READY)
            throw new IllegalStateException("Cannot change reserved or claimed mail attachments");
        if (this.attachments == null) {
            this.attachments = new ArrayList<>();
        }
        this.attachments.add(item);
    }

    public boolean hasClaimedAttachments() {
        // Historical isRead conflated read and claimed. Never re-grant those ambiguous records.
        return this.attachmentClaim == null ? this.isRead
                : this.attachmentClaim.getPhase() == org.hkrpg.server.gameplay.MailAttachmentClaim.Phase.CLAIMED;
    }

    public void reserveAttachments(java.util.Map<Integer, Integer> expected,
                                   java.util.Map<Integer, Integer> before) {
        if (this.attachmentClaim == null)
            this.attachmentClaim = org.hkrpg.server.gameplay.MailAttachmentClaim.ready();
        this.attachmentClaim.reserve(expected, before);
        this.save();
    }

    public void recordAttachmentDelivery(java.util.Map<Integer, Integer> received,
                                         List<Integer> uniqueIds, boolean complete) {
        this.attachmentClaim.settle(received, uniqueIds, complete);
        if (complete) this.isRead = true;
        this.save();
    }

    // Database

    public void save() {
        LunarCore.getGameDatabase().save(this);
    }

    public void delete() {
        LunarCore.getGameDatabase().delete(this);
    }

    // Proto

    public ClientMail toProto() {
        var proto = ClientMail.newInstance()
                .setId(this.getUniqueId())
                .setTitle(this.getTitle())
                .setContent(this.getContent())
                .setSender(this.getSender())
                .setTime(this.getTime())
                .setExpireTime(this.getExpiry())
                .setIsRead(this.isRead());

        // Add attachments
        ItemList list = ItemList.newInstance();

        if (this.attachments != null && !hasClaimedAttachments()) {
            this.attachments.stream().map(GameItem::toProto).forEach(list::addItemList);
        }

        proto.setAttachment(list);

        return proto;
    }
}
