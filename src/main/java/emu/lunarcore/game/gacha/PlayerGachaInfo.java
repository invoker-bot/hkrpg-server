package emu.lunarcore.game.gacha;

import dev.morphia.annotations.Entity;
import emu.lunarcore.GameConstants;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.GachaCeilingAvatarOuterClass.GachaCeilingAvatar;
import emu.lunarcore.proto.GachaCeilingOuterClass.GachaCeiling;
import lombok.Getter;
import lombok.Setter;

@Getter @Entity(useDiscriminator = false)
public class PlayerGachaInfo {
    public static final String CEILING_REWARD_KEY = "gacha:standard-ceiling";
    public static final int NEWBIE_DRAW_LIMIT = 50;
    private PlayerGachaBannerInfo standardBanner;
    private PlayerGachaBannerInfo newbieBanner;
    private PlayerGachaBannerInfo eventCharacterBanner;
    private PlayerGachaBannerInfo eventWeaponBanner;
    private int newbieDrawCount;
    private boolean newbieFiveStarObtained;

    private int ceilingNum;
    @Setter private boolean ceilingClaimed;

    public PlayerGachaInfo() {
        this.standardBanner = new PlayerGachaBannerInfo();
        this.newbieBanner = new PlayerGachaBannerInfo();
        this.eventCharacterBanner = new PlayerGachaBannerInfo();
        this.eventWeaponBanner = new PlayerGachaBannerInfo();
    }

    public void addCeilingNum(int amount) {
        this.ceilingNum = Math.min(ceilingNum + amount, GameConstants.GACHA_CEILING_MAX);
    }

    public PlayerGachaBannerInfo getBannerInfo(GachaType type) {
        if (type == GachaType.Newbie) {
            // Documents created before this field existed have no independent beginner pity.
            if (this.newbieBanner == null) this.newbieBanner = new PlayerGachaBannerInfo();
            return this.newbieBanner;
        } else if (type == GachaType.AvatarUp) {
            return this.eventCharacterBanner;
        } else if (type == GachaType.WeaponUp) {
            return this.eventWeaponBanner;
        }

        return this.standardBanner;
    }

    public void recordNewbieDraw(boolean fiveStar) {
        this.newbieDrawCount++;
        this.newbieFiveStarObtained |= fiveStar;
    }

    public boolean isNewbieClosed() {
        return this.newbieDrawCount >= NEWBIE_DRAW_LIMIT;
    }

    public GachaCeiling toGachaCeiling(Player player) {
        var proto = GachaCeiling.newInstance()
                .setIsClaimed(this.isCeilingClaimed(player))
                .setCeilingNum(this.getCeilingNum());

        // Gacha ceiling avatars are the avatars that we can pick
        var ceilingAvatars = player.getServer().getGachaService().getYellowAvatars();
        for (int i = 0; i < ceilingAvatars.length; i++) {
            int avatarId = ceilingAvatars[i];
            int repeatedCount = 0; // Eidolon count

            GameAvatar avatar = player.getAvatarById(avatarId);
            if (avatar != null) {
                repeatedCount = avatar.getRank();
            }

            var ceilingAvatar = GachaCeilingAvatar.newInstance()
                .setRepeatedCnt(repeatedCount)
                .setAvatarId(avatarId);

            proto.addAvatarList(ceilingAvatar);
        }

        return proto;
    }

    /** A durable delivery receipt is authoritative even if the legacy flag was not saved. */
    public boolean isCeilingClaimed(Player player) {
        return this.ceilingClaimed || player.getRewardLedger().granted(CEILING_REWARD_KEY);
    }
}
