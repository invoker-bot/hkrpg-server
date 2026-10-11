package emu.lunarcore.game.battle.skills;

import java.util.List;

import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.scene.entity.GameEntity;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;

public abstract class MazeSkillAction {

    public void onCast(GameAvatar caster, MotionInfo castPosition) {

    }

    public void onCastHit(GameAvatar caster, List<? extends GameEntity> entities) {

    }

    public void onAttack(GameAvatar caster, List<? extends GameEntity> targets) {

    }

    /** Select callback-owned battle policy without applying its gameplay effects. */
    public void prepareAttack(GameAvatar caster, List<? extends GameEntity> targets) {

    }

    /** Called only after the encounter's stage and story admission checks succeed. */
    public void onBattle(GameAvatar caster, List<? extends GameEntity> targets, Battle battle) {

    }

}
