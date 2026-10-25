package emu.lunarcore.data.excel;

import emu.lunarcore.data.resource.GameResource;
import emu.lunarcore.data.resource.ResourceType;
import lombok.Getter;

@Getter
@ResourceType(name = "AvatarPromotionReward.json")
public class AvatarPromotionRewardExcel extends GameResource {
    private int Promotion;
    private int PromotionRewardId;

    @Override public int getId() { return Promotion; }
}
