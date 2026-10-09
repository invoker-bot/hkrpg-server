package emu.lunarcore.data.excel;

import com.google.gson.JsonArray;
import emu.lunarcore.data.resource.MultiKeyGameResource;
import emu.lunarcore.data.resource.ResourceType;
import java.util.List;
import lombok.Getter;

@Getter
@ResourceType(name = {"SpecialAvatar.json", "SpecialAvatarLD.json"})
public class SpecialAvatarExcel extends MultiKeyGameResource {
    private int SpecialAvatarID;
    private int WorldLevel;
    private boolean IsUseWorldLevel;
    private int PlayerID;
    private int AvatarID;
    private int Level;
    private int Promotion;
    private int Rank;
    private int EnhancedID;
    private String SkillTreeTemplate;
    private String CustomSkillTreeKey;

    // Retain declared trial data when an independent party freezes its resource configuration.
    private String Type;
    private String AnchorName;
    private String PlayerJsonPath;
    private String AIPath;
    private String LevelAreaPrefab;
    private String JsonPath;
    private JsonArray OverrideProperty = new JsonArray();
    private List<String> AbilityNameList = List.of();
    private boolean HasJoinHint;
    private boolean IsAutoBattle;
    private boolean LockMazeSkill;
    private boolean HasLeaveHint;
    private boolean HaveActionDelay;
    private boolean IsProtected;
    private boolean DisableSwitch;
    private boolean LockBattleInfo;
    private int RelicSubValue;
    private int RelicMainValue;
    private int RelicPropertyType;
    private int RelicPropertyTypeExtra;
    private int PlaneID;
    private int MainMissionID;
    private int AvatarSkinID;

    private int EquipmentID;
    private int EquipmentLevel;
    private int EquipmentPromotion;
    private int EquipmentRank;

    public int getId() {
        return SpecialAvatarID;
    }

    @Override
    public int getPrimaryKey() {
        return SpecialAvatarID;
    }

    @Override
    public int getSecondaryKey() {
        return WorldLevel;
    }
}
