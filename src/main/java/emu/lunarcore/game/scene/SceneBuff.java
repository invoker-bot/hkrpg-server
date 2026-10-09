package emu.lunarcore.game.scene;

import emu.lunarcore.LunarCore;
import emu.lunarcore.proto.BuffInfoOuterClass.BuffInfo;
import lombok.Getter;
import lombok.Setter;

@Getter
public class SceneBuff {
    private int casterAvatarId; // Owner avatar id
    private int buffId;
    private int buffLevel;
    @Setter private int count;

    private float duration;
    private long createTime;
    private long expiry;

    public SceneBuff(int buffId) {
        this.buffId = buffId;
        this.buffLevel = 1;
        this.count = 1;
        this.createTime = System.currentTimeMillis();
        this.duration = -1;
    }

    public SceneBuff(int casterAvatarId, int buffId) {
        this(buffId);
        this.casterAvatarId = casterAvatarId;
        this.expiry = Long.MAX_VALUE;
    }

    public SceneBuff(int casterAvatarId, int buffId, int seconds) {
        this(casterAvatarId, buffId, (double) seconds);
    }

    public SceneBuff(int casterAvatarId, int buffId, double seconds) {
        this(buffId);
        this.casterAvatarId = casterAvatarId;
        this.duration = (float) org.hkrpg.server.gameplay.MazeBuffLifetime.milliseconds(seconds);
        this.expiry = org.hkrpg.server.gameplay.MazeBuffLifetime.expiry(this.createTime, seconds);
    }

    public int decrementAndGet() {
        this.count--;
        return this.count;
    }

    public boolean isExpired(long timestamp) {
        return timestamp > this.expiry;
    }

    // Serialization

    public BuffInfo toProto() {
        var proto = BuffInfo.newInstance()
                .setBuffId(this.getBuffId())
                .setLevel(this.getBuffLevel())
                .setBaseAvatarId(this.getCasterAvatarId())
                .setAddTimeMs(LunarCore.convertToServerTime(this.getCreateTime()))
                // BuffInfo field 4 is seconds; internal expiration and field 8 retain milliseconds.
                .setLifeTime(this.getDuration() < 0 ? -1 : this.getDuration() / 1000f)
                .setCount(this.getCount());

        return proto;
    }
}
