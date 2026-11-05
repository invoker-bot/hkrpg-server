package emu.lunarcore.game.scene;

import emu.lunarcore.data.config.*;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.game.scene.entity.EntityProp;
import org.hkrpg.server.gameplay.TrainWorkbench;

public class TrainEntityLoader extends SceneEntityLoader {

    public EntityMonster loadMonster(Scene scene, GroupInfo group, MonsterInfo monsterInfo) {
        return null;
    }

    @Override
    public EntityProp loadProp(Scene scene, GroupInfo group, PropInfo propInfo) {
        var prop = super.loadProp(scene, group, propInfo);
        TrainWorkbench.restore(prop);
        return prop;
    }

}
