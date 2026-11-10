package emu.lunarcore.data.config;

import java.util.List;
import java.util.Map;
import com.google.gson.JsonElement;

import lombok.Getter;
import lombok.Setter;

/**
 *  Original name: LevelGroupInfo
 */
@Getter
public class GroupInfo {
    private String GroupName;
    private transient int id;
    @Setter private GroupLoadSide LoadSide = GroupLoadSide.Server;
    // Native client ownership is distinct from the legacy logical group used for mission checks.
    @Setter private transient boolean clientOwnedNpcSource;
    @Setter private transient int clientInvestigationFloor;
    @Setter private transient int greatMineSourceGroup;
    private GroupCategory Category;
    private boolean LoadOnInitial = true;
    private int OwnerMainMissionID;

    private JsonElement AtmosphereCondition;
    private LoadConditionInfo LoadCondition;
    private LoadConditionInfo UnloadCondition;
    private String LevelGraph;
    private JsonElement SavedValueCondition;
    private JsonElement ValueSource;

    private Map<Integer, GroupProperty> GroupPropertyMap;
    private List<StateTransition> GroupStateList;

    public record StateTransition(int SrcState, int DstState, JsonElement StateName, JsonElement Comment) {}

    private List<AnchorInfo> AnchorList;
    private List<MonsterInfo> MonsterList;
    private List<PropInfo> PropList;
    private List<NpcInfo> NPCList;

    public void setId(int id) {
        if (this.id == 0) this.id = id;
    }

    public String getGroupName() {
        return this.GroupName != null ? this.GroupName : "" ;
    }

    public MonsterInfo getMonsterById(int configId) {
        return MonsterList.stream().filter(m -> m.getID() == configId).findFirst().orElse(null);
    }

    @Getter
    public static class GroupProperty {
        private int ID;
        private String Name;
        private String Side; // Enum
        private int DefaultValue;
        private int MaxValue;
    }

    public static enum GroupLoadSide {
        Client, Server;
    }

    public static enum GroupCategory {
        Normal, Mission, BattleProps, BattleAudiences, Custom, System, Atmosphere;
    }
}
