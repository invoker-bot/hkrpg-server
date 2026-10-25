package emu.lunarcore.game.gacha;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.GachaInfoOuterClass.GachaInfo;
import lombok.Getter;
import org.hkrpg.server.GachaWebRoutes;

@Getter
public class GachaBanner {
    private int id; // Id should match one of the ids in GachaBasicInfo.json
    private GachaType gachaType;
    private int beginTime;
    private int endTime;
    private int[] rateUpItems5;
    private int[] rateUpItems4;
    private int eventChance = 50;
    private boolean disabled;

    public boolean isActive(long now) {
        return !disabled && (gachaType == GachaType.Normal || gachaType == GachaType.Newbie
                || beginTime <= now && now < endTime);
    }

    public GachaBanner createBanner(int id, GachaType gachaType, int beginTime, int endTime, int[] rateUpItems5, int[] rateUpItems4) {
        return createBanner(id, gachaType, beginTime, endTime, rateUpItems5, rateUpItems4, 50);
    }

    public GachaBanner createBanner(int id, GachaType gachaType, int beginTime, int endTime, int[] rateUpItems5, int[] rateUpItems4, int eventChance) {
        this.id = id;
        this.gachaType = gachaType;
        this.beginTime = beginTime;
        this.endTime = endTime;
        this.rateUpItems5 = rateUpItems5;
        this.rateUpItems4 = rateUpItems4;
        this.eventChance = eventChance;
        return this;
    }

    public GachaInfo toProto(GachaService service, Player player) {
        var pool = service.getPool(this);
        var info = GachaInfo.newInstance()
                .setGachaId(this.getId())
                .setDetailUrl(GachaWebRoutes.detailUrl(this.getId()))
                .setHistoryUrl(GachaWebRoutes.historyUrl(this.getId()));

        if (this.gachaType != GachaType.Normal) {
            info.setBeginTime(this.getBeginTime());
            info.setEndTime(this.getEndTime());
        }

        if (this.getId() == 1001) {
            info.setGachaCeiling(player.getGachaInfo().toGachaCeiling(player));

            pool.fourStars().forEach(info::addUpInfo);
            pool.fiveStars().forEach(info::addUpInfo);
            info.addAllFeatured(service.getFeatured5(this, pool));
        } else {
            (pool.permanent() ? pool.fourStars() : pool.up4()).forEach(info::addUpInfo);
            (pool.permanent() ? pool.fiveStars() : pool.up5()).forEach(info::addUpInfo);
            info.addAllFeatured(service.getFeatured5(this, pool));
        }

        return info;
    }
}
