package emu.lunarcore.game.scene;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import lombok.Getter;
import lombok.Setter;

@Getter
public class SceneGroup {
    private int id;
    @Setter private int state;
    private Object2IntMap<String> properties;

    public SceneGroup(int id) {
        this.id = id;
        this.properties = new Object2IntOpenHashMap<>();
    }
}
