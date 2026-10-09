package emu.lunarcore.game.battle.skills;

import java.util.List;

import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.game.scene.entity.GameEntity;

public class MazeSkillSetAttackTargetMonsterDie extends MazeSkillAction {

    public void onAttack(GameAvatar caster, List<? extends GameEntity> entities) {
        if (caster == null || caster.getOwner() == null || entities == null) return;
        var player = caster.getOwner();
        synchronized (player) {
            var scene = player.getScene();
            if (scene == null || scene.getPlayer() != player
                    || scene.getAvatarByEntityId(caster.getEntityId()) != caster) return;
            for (var entity : entities) {
                if (entity instanceof EntityMonster monster
                        && org.hkrpg.server.gameplay.MazeInstantKillTargets.allows(scene, monster)
                        && scene.defeatMonster(monster)) {
                    var drops = new ItemParamMap();
                    monster.calculateDrops(drops);
                    player.getInventory().addItems(drops.toItemList(), true);
                }
            }
        }
    }

}
