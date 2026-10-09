package emu.lunarcore.game.battle.skills;

import emu.lunarcore.data.excel.SummonUnitExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import emu.lunarcore.util.Position;
import lombok.Getter;

@Getter
public class MazeSkillSummonUnit extends MazeSkillAction {
    private SummonUnitExcel excel;
    private int duration;
    private final double lifetimeSeconds;

    public MazeSkillSummonUnit(SummonUnitExcel excel, int duration) {
        this(excel, (double) duration);
    }

    public MazeSkillSummonUnit(SummonUnitExcel excel, double duration) {
        long milliseconds = org.hkrpg.server.gameplay.MazeBuffLifetime.milliseconds(duration);
        if (milliseconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Summon lifetime does not fit int32 milliseconds");
        }
        this.excel = excel;
        this.duration = (int) duration;
        this.lifetimeSeconds = duration;
    }

    @Override
    public void onCast(GameAvatar caster, MotionInfo castPosition) {
        caster.getScene().summonUnit(caster, excel, new Position(castPosition.getPos()), new Position(castPosition.getRot()), lifetimeSeconds);
    }

}
