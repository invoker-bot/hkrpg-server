package emu.lunarcore.game.trial;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.data.excel.SpecialAvatarExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType;
import lombok.Getter;

@Getter
public class TrialAvatar extends GameAvatar {
    private SpecialAvatarExcel specialAvatarExcel;
    private org.hkrpg.server.gameplay.TrialSkillPresets.Plan skillPlan;

    public TrialAvatar(Player owner, SpecialAvatarExcel specialAvatarExcel, AvatarExcel excel) {
        this(owner, specialAvatarExcel, excel, org.hkrpg.server.gameplay.TrialSkillPresets.resolve(specialAvatarExcel));
    }

    public TrialAvatar(Player owner, SpecialAvatarExcel specialAvatarExcel, AvatarExcel excel,
                       org.hkrpg.server.gameplay.TrialSkillPresets.Plan skillPlan) {
        this(owner, specialAvatarExcel, excel, skillPlan, true);
    }

    /** Rebuild only the saved skills, without initializing newly introduced resource nodes. */
    public static TrialAvatar restore(Player owner, SpecialAvatarExcel config, AvatarExcel excel,
                                     org.hkrpg.server.gameplay.TrialSkillPresets.Plan plan,
                                     java.util.Map<Integer, Integer> frozenSkills) {
        var actor = new TrialAvatar(owner, config, excel, plan, false);
        org.hkrpg.server.gameplay.TrialSkillTrees.restore(actor, frozenSkills);
        return actor;
    }

    private TrialAvatar(Player owner, SpecialAvatarExcel specialAvatarExcel, AvatarExcel excel,
                        org.hkrpg.server.gameplay.TrialSkillPresets.Plan skillPlan, boolean initializeSkills) {
        super(excel);
        this.setOwner(owner);
        this.setExcel(excel);
        this.specialAvatarExcel = specialAvatarExcel;
        this.skillPlan = java.util.Objects.requireNonNull(skillPlan);
        this.avatarId = specialAvatarExcel.getId();

        // Set stats
        this.setLevel(specialAvatarExcel.getLevel());
        this.setPromotion(specialAvatarExcel.getPromotion());
        this.setRank(specialAvatarExcel.getRank());
        this.extraLineupHp = 10000;
        this.extraLineupSp = 7000;

        this.getData().setEnhanceId(specialAvatarExcel.getEnhancedID());
        if (initializeSkills) org.hkrpg.server.gameplay.TrialSkillTrees.initialize(this);

        // Set equipment
        var equipmentExcel = GameData.getItemExcelMap().get(specialAvatarExcel.getEquipmentID());
        if (equipmentExcel != null) {
            var equipment = new GameItem(equipmentExcel);
            equipment.setLevel(specialAvatarExcel.getEquipmentLevel());
            equipment.setPromotion(specialAvatarExcel.getEquipmentPromotion());
            equipment.setRank(specialAvatarExcel.getEquipmentRank());

            // Trial gear is local battle data, not an owned inventory equip operation.
            int slot = equipment.getEquipSlot();
            if (slot > 0) this.getEquips().put(slot, equipment);
        }
    }

    @Override
    public int getExcelId() {
        return this.getExcel().getAvatarID();
    }

    @Override
    public int getActorId() {
        return specialAvatarExcel.getAvatarID();
    }

    @Override
    public int getBattleAvatarId() {
        return this.avatarId;
    }

    @Override
    public AvatarType getAvatarType() {
        return AvatarType.AVATAR_TRIAL_TYPE;
    }

    @Override
    public boolean isMazeSkillLocked() {
        return specialAvatarExcel.isLockMazeSkill();
    }

    @Override
    public void save() {
        // Prevent the server from saving trial avatars
    }

}
